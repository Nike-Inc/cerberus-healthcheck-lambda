/*
 * Copyright (c) 2017 Nike Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nike.cerberus

import java.util.regex.Pattern

import com.amazonaws.AmazonClientException
import com.amazonaws.auth.policy.Statement.Effect
import com.amazonaws.auth.policy.actions.{KMSActions, SecurityTokenServiceActions}
import com.amazonaws.auth.policy.{Policy, Principal, Resource, Statement}
import com.amazonaws.regions.Regions
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import com.amazonaws.services.identitymanagement.model._
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest
import com.fieldju.commons.PropUtils._
import com.fieldju.commons.StringUtils
import com.nike.cerberus.CerberusGatlingApiActions._
import com.nike.cerberus.api.CerberusApiActions
import com.nike.cerberus.api.util.TestUtils
import groovy.json.internal.LazyMap
import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import io.restassured.path.json.JsonPath

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.Breaks._
import scala.util.control.NonFatal

/**
  * Simulation that will create a bunch of SDBs with random secrets and IAM Principals will authenticate and read secrets
  */
class StsAuthAndReadSimulation extends Simulation {

  // Sessions keys
  private val ROLE_NAME = "role_name"
  private val ROLE_ARN = "role_arn"
  private val SDB_ID = "sdb_id"
  private val SDB_DATA_PATH = "sdb_root_path"
  private val REGION = "region"
  private val MAX_RETRY = 10
  private val MAX_COOL_DOWN = 1 minutes

  private val generatedData = ArrayBuffer[Map[String, String]]()
  private var iam: AmazonIdentityManagementClient = _
  private var currentIamPrincipalArn: String = _

  ///////////////////////////////////////////////////////////////////////////////
  //  LOAD REQUIRED PROPS, These can be set via ENV vars or System properties  //
  ///////////////////////////////////////////////////////////////////////////////
  private val cerberusBaseUrl: String = getRequiredProperty("CERBERUS_API_URL", "The base Cerberus API URL")
  private val region = getPropWithDefaultValue("REGION", "us-west-2")
  private val peakUsers = getPropWithDefaultValue("PEAK_USERS", "1").toInt
  private val holdTimeAfterPeakInMinutes = getPropWithDefaultValue("HOLD_TIME_AFTER_PEAK_IN_MINUTES", "1").toInt
  private val rampUpTimeInMinutes = getPropWithDefaultValue("RAMP_UP_TIME_IN_MINUTES", "1").toInt
  private val cerberusAccountId = getRequiredProperty("CERBERUS_ACCOUNT_ID", "The account id that Cerberus is hosted in")
  private val ownerGroup = getRequiredProperty("OWNER_GROUP", "The user group to be the owner of the generated SDBs.")
  private val numberOfServicesToUseForSimulation = getPropWithDefaultValue("NUMBER_OF_SERVICES_FOR_SIMULATION", "1").toInt
  private val createIamRoles = getPropWithDefaultValue("CREATE_IAM_ROLES", "false").toBoolean
  private val numberOfLoadServers = getRequiredProperty("NUMBER_LOAD_SERVERS", "number of load servers to divide load numbers by").toInt

  before {
    println(
      s"""
        |
        |######################################################################
        |# Preparing to execute simulation, received the following parameters #
        |######################################################################
        |
        |   CERBERUS_API_URL: $cerberusBaseUrl
        |   CERBERUS_ACCOUNT_ID: $cerberusAccountId
        |   REGION: $region
        |   OWNER_GROUP: $ownerGroup
        |   NUMBER_OF_SERVICES_FOR_SIMULATION: $numberOfServicesToUseForSimulation
        |   CREATE_IAM_ROLES: $createIamRoles
        |   PEAK_USERS: $peakUsers
        |   RAMP_UP_TIME_IN_MINUTES:  $rampUpTimeInMinutes
        |   HOLD_TIME_AFTER_PEAK_IN_MINUTES: $holdTimeAfterPeakInMinutes
        |
        |######################################################################
        |
      """.stripMargin)

    TestUtils.configureRestAssured()

    breakable { for (j <- 1 to MAX_RETRY) {
      try {
        // get the arn of the principal running the simulation
        currentIamPrincipalArn = getArn
        // Authenticate with Cerberus as the IAM Principal bound to the current AWS credentials
        val authToken: String = authenticate(currentIamPrincipalArn)
        val catData: JsonPath = CerberusApiActions.getCategories(authToken)
        val catMap = mutable.HashMap[String, String]()
        for (cat <- catData.getList("").asInstanceOf[java.util.List[java.util.Map[String, String]]]) {
          catMap += (cat("display_name") -> cat("id"))
        }
        val roleData: JsonPath = CerberusApiActions.getRoles(authToken)
        val roleMap = mutable.HashMap[String, String]()
        for (role <- roleData.getList("").asInstanceOf[java.util.List[java.util.Map[String, String]]]) {
          roleMap += (role("name") -> role("id"))
        }

        // Create iam roles and SDBs
        iam = new AmazonIdentityManagementClient().withRegion(Regions.fromName(region))
        for (i <- 1 to numberOfServicesToUseForSimulation) {
          println(s"Attempting to initiate: retry number $j")

          var createdRoleArn = ""
          var createdRoleName = ""
          if (createIamRoles) {
            val role: Role = createRole(cerberusAccountId, currentIamPrincipalArn, iam)
            createdRoleArn = role.getArn
            createdRoleName = role.getRoleName
            Thread.sleep(1000)
          } else {
            createdRoleArn = currentIamPrincipalArn
            createdRoleName = currentIamPrincipalArn
          }

          val idAndPath = createSDB(
            authToken,
            currentIamPrincipalArn,
            createdRoleArn,
            ownerGroup,
            roleMap("read"),
            roleMap("owner"),
            catMap("Applications")
          )

          val id = idAndPath._1
          val path = idAndPath._2

          DataHelper.writeRandomData(authenticate(currentIamPrincipalArn), path)

          val data = Map(
            ROLE_ARN -> createdRoleArn,
            ROLE_NAME -> createdRoleName,
            SDB_ID -> id,
            SDB_DATA_PATH -> path,
            REGION -> region
          )

          println(
            s"""
               |
              |######################################################################
               |# Generated data for service $i of $numberOfServicesToUseForSimulation

               |#########################################################

               |
              | ROLE_ARN: ${data(ROLE_ARN)}

               | ROLE_NAME: ${
              data(ROLE_NAME)}

               | SDB_ID: ${data(SDB_ID)}

               | SDB_DATA_PATH: ${data(
              SDB_DATA_PATH)}
              | REGION: ${data(REGION)}
               |

               ############################

          #####################
               |
            """.stripMargin)

          generatedData += data
          break
        }
      } catch {
        case NonFatal(t) =>
          if (j == MAX_RETRY) {
            throw t
          }
      }
    } }
  }

  def getArn: String = {
    val sts = new AWSSecurityTokenServiceClient().withRegion(Regions.fromName(region)).asInstanceOf[AWSSecurityTokenServiceClient]
    var arn: String = ""
    try {
      val identity = sts.getCallerIdentity(new GetCallerIdentityRequest())
      arn = identity.getArn
    } catch {
      case e: AmazonClientException =>
        println("\nThis simulation requires that AWS Credentials are set, see http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html and the ability to call get-caller-identity from the STS api\n")
        throw new IllegalStateException("Failed to determine the ARN for the principal running the simulation", e)
    }

    // convert arn to role format rather than assumed role format
    val iamRoleArnParserPattern = Pattern.compile("arn:aws:sts::(?<accountId>.*?):assumed-role/(?<roleName>.*?)/.*")
    val iamRoleArnParserMatcher = iamRoleArnParserPattern.matcher(arn)
    if (iamRoleArnParserMatcher.find()) {
      arn = s"arn:aws:iam::${iamRoleArnParserMatcher.group("accountId")}:role/${iamRoleArnParserMatcher.group("roleName")}"
    }
    println(s"getArn() arn:$arn region:$region")
    arn
  }

  def authenticate(arn: String): String = {
    println(s"authenticate() arn:$arn region:$region")
    if (StringUtils.isBlank(arn)) {
      throw new IllegalStateException()
    }

    try {
      CerberusApiActions.retrieveStsAuthToken(region).asInstanceOf[String]
    } catch {
      case t: Throwable =>
        throw new IllegalStateException(s"Failed to authenticate with cerberus using arn: $arn, region: $region", t)
    }
  }

  def createSDB(authToken: String,
                iamPrincipalArnRunningSimulation: String,
                simulatedIamPrincipalArn: String,
                ownerGroup: String,
                readRoleId: String,
                ownerRoleId: String,
                categoryId: String): (String, String) = {

    val iamPermissions = List(
      Map("iam_principal_arn" -> iamPrincipalArnRunningSimulation, "role_id" -> ownerRoleId).asJava,
      Map("iam_principal_arn" -> simulatedIamPrincipalArn, "role_id" -> readRoleId).asJava
    ).asJava

    val jsonPath: JsonPath = CerberusApiActions.createSdbV2(
      authToken,
      s"perf test ${Random.alphanumeric.take(20).mkString}",
      "Cerberus Gatling performance test SDB",
      categoryId,
      ownerGroup,
      List(),
      iamPermissions
    )

    val id: String = jsonPath.getString("id")
    val path: String = jsonPath.getString("path")

    (id, path)
  }

  /**
    * Creates an IAM Role with an inline policy that allows it to authenticate with Cerberus
    *
    * @param cerberusAccountId - The account id that Cerberus is in, so KMS decrypt perms can be granted
    * @param iamPrincipalArnRunningSimulation - The role arn for principal running this simulation, to grant assume role access
    * @param iam - The iam Client
    * @return - The arn for the created role
    */
  def createRole(cerberusAccountId: String, iamPrincipalArnRunningSimulation: String, iam:AmazonIdentityManagementClient): Role = {
    val roleName = "cerberus-gatling-perf-role-" + Random.alphanumeric.take(20).mkString
    val iamCreateResult = iam.createRole(new CreateRoleRequest()
      .withRoleName(roleName)
      .withAssumeRolePolicyDocument(
        new Policy("assume-role-policy")
          .withStatements(
            new Statement(Effect.Allow)
              .withActions(SecurityTokenServiceActions.AssumeRole)
              .withPrincipals(new Principal("AWS", iamPrincipalArnRunningSimulation, false))
          )
          .toJson
      )
    )
    iam.putRolePolicy(
      new PutRolePolicyRequest()
        .withPolicyDocument(
          new Policy("allow-kms-decrypt-from-the-cerberus-account")
            .withStatements(
              new Statement(Effect.Allow)
                .withActions(KMSActions.Decrypt)
                .withResources(new Resource("arn:aws:kms:*:" + cerberusAccountId + ":key/*"))
            ).toJson
        ).withRoleName(iamCreateResult.getRole.getRoleName)
        .withPolicyName("allow-kms-decrypt-from-the-cerberus-account")
    )
    iamCreateResult.getRole
  }

  val httpConf: HttpProtocolBuilder = http.baseURL(cerberusBaseUrl)
                                          .shareConnections
                                          .maxConnectionsPerHost(1000)

  val scn: ScenarioBuilder =
    scenario("Iam principal authenticates and then reads secrets")
    .feed(generatedData.random)
    .exec(
      authenticate_and_fetch_sts_auth_token_and_store_in_session,
      list_all_the_node_keys_for_the_root_sdb_path_and_store_keys_in_session,
      read_data_from_each_node
    )

  /**
    * Set up the scenario
    */
  setUp(
    scn.inject(
      nothingFor(30 seconds), // let the created IAM roles be eventually consistent
      rampUsersPerSec(1) to peakUsers / numberOfLoadServers during(rampUpTimeInMinutes minutes),
      constantUsersPerSec(peakUsers / numberOfLoadServers) during(holdTimeAfterPeakInMinutes minutes)
    )
  )
    .protocols(httpConf)
    .maxDuration((rampUpTimeInMinutes + holdTimeAfterPeakInMinutes minutes) + MAX_COOL_DOWN)

  /**
    * After the simulation is run, delete the randomly created data.
    */
  after {
    println(
      """
        |################################################################################
        |#                                                                              #
        |#             Deleting generated performance iam roles and SDBs                #
        |#                                                                              #
        |################################################################################
      """.stripMargin)

    var authToken: String = ""
    try {
      authToken = authenticate(currentIamPrincipalArn)
    } catch {
      case t: Throwable =>
        println("Failed to authenticate with Cerberus")
        t.printStackTrace()
    }

    for (data <- generatedData) {
      if (createIamRoles) {
        try {
          val roleName = data(ROLE_NAME)
          println(s"Deleting role: $roleName")
          val policies = iam.listRolePolicies(new ListRolePoliciesRequest().withRoleName(roleName))
          for (policyName <- policies.getPolicyNames) {
            iam.deleteRolePolicy(new DeleteRolePolicyRequest().withPolicyName(policyName).withRoleName(roleName))
          }
          iam.deleteRole(new DeleteRoleRequest().withRoleName(roleName))
        } catch {
          case t: Throwable =>
            println(s"failed to delete generated iam role ${data.getOrElse(ROLE_NAME, "unknown")}")
            t.printStackTrace()
        }
      }

      val sdbId = data(SDB_ID)
      try {
        println(s"Deleting sdb: $sdbId")
        CerberusApiActions.deleteSdb(authToken, sdbId)
      } catch {
        case t: Throwable =>
          println(s"Failed to delete sdb $sdbId")
          t.printStackTrace()
      }
    }

    println(
      s"""
         |
         |######################################################################
         |# Test was ran with the following parameters                         #
         |######################################################################
         |
         |   CERBERUS_API_URL: $cerberusBaseUrl
         |   CERBERUS_ACCOUNT_ID: $cerberusAccountId
         |   REGION: $region
         |   OWNER_GROUP: $ownerGroup
         |   NUMBER_OF_SERVICES_FOR_SIMULATION: $numberOfServicesToUseForSimulation
         |   CREATE_IAM_ROLES: $createIamRoles
         |   PEAK_USERS: $peakUsers
         |   RAMP_UP_TIME_IN_MINUTES:  $rampUpTimeInMinutes
         |   HOLD_TIME_AFTER_PEAK_IN_MINUTES: $holdTimeAfterPeakInMinutes
         |
        |######################################################################
         |
      """.stripMargin)
  }
}
