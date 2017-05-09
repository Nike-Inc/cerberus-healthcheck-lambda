package com.nike.cerberus

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
import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder
import com.nike.cerberus.api.CerberusApiActions
import com.nike.cerberus.api.util.TestUtils
import groovy.json.internal.LazyMap
import io.restassured.path.json.JsonPath
import com.nike.cerberus.CerberusGatlingApiActions._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.duration._
import scala.util.Random

/**
  * Simulation that will create a bunch of SDBs with random secrets and IAM Principals will authenticate and read secrets
  */
class IamPrincipalAuthAndReadSimulation extends Simulation {

  // Sessions keys
  private val ROLE_NAME = "role_name"
  private val ROLE_ARN = "role_arn"
  private val SDB_ID = "sdb_id"
  private val SDB_DATA_PATH = "sdb_root_path"
  private val REGION = "region"

  private val cerberusBaseUrl: String = getRequiredProperty("CERBERUS_API_URL", "The base Cerberus API URL")
  private val generatedData = ArrayBuffer[Map[String, String]]()
  private var iam: AmazonIdentityManagementClient = _
  private var currentIamPrincipalArn: String = _
  private val region = getPropWithDefaultValue("REGION", "us-west-2")

  before {
    ///////////////////////////////////////////////////////////////////////////////
    //  LOAD REQUIRED PROPS, These can be set via ENV vars or System properties  //
    ///////////////////////////////////////////////////////////////////////////////
    val cerberusAccountId = getRequiredProperty("CERBERUS_ACCOUNT_ID", "The account id that Cerberus is hosted in")
    // This simulation creates and IAM role and SDB with data for each simulated service
    val numberOfServicesToUseForSimulation = getPropWithDefaultValue("NUMBER_OF_SERVICES_FOR_SIMULATION", "1").toInt

    println(
      s"""
        |
        |######################################################################
        |# Preparing to execute simulation, received the following parameters #
        |######################################################################
        |
        |   CERBERUS_API_URL: $cerberusBaseUrl
        |   CERBERUS_ACCOUNT_ID: $cerberusAccountId
        |   NUMBER_OF_SERVICES_FOR_SIMULATION: $numberOfServicesToUseForSimulation
        |
        |######################################################################
        |
      """.stripMargin)

    TestUtils.configureRestAssured()

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
    for (_ <- 1 to numberOfServicesToUseForSimulation) {
      val role: Role = createRole(cerberusAccountId, currentIamPrincipalArn, iam)

      val createdRoleArn = role.getArn
      val idAndPath = createSDB(
        authToken,
        currentIamPrincipalArn,
        createdRoleArn,
        "registered-iam-principals",
        roleMap("read"),
        roleMap("write"),
        catMap("Applications")
      )

      val id = idAndPath._1
      val path = idAndPath._2

      writeRandomData(authenticate(currentIamPrincipalArn), path)

      generatedData += Map(
        ROLE_ARN -> createdRoleArn,
        ROLE_NAME -> role.getRoleName,
        SDB_ID -> id,
        SDB_DATA_PATH -> path,
        REGION -> region
      )
    }
  }

  def writeRandomData(token: String, path: String) {
    // controls the number of nodes (paths in the storage structure that point to maps of data) to create for a given simulated services SDB
    val minNodesToCreate: Int  = getPropWithDefaultValue("minNodesToCreate", "5").toInt
    val maxNodesToCreate: Int  = getPropWithDefaultValue("maxNodesToCreate", "10").toInt
    // controls the path suffix
    val minPathSuffixLength: Int  = getPropWithDefaultValue("minPathSuffixLength", "5").toInt
    val maxPathSuffixLength: Int  = getPropWithDefaultValue("maxPathSuffixLength", "15").toInt
    // how many k,v pairs at each node
    val minKeyValuePairsPerNode: Int  = getPropWithDefaultValue("minKeyValuePairsPerNode", "5").toInt
    val maxKeyValuePairsPerNode: Int  = getPropWithDefaultValue("maxKeyValuePairsPerNode", "25").toInt
    // key length
    val minKeyLength: Int  = getPropWithDefaultValue("minKeyLength", "5").toInt
    val maxKeyLength: Int  = getPropWithDefaultValue("maxKeyLength", "10").toInt
    // value length
    val minValueLength: Int  = getPropWithDefaultValue("minValueLength", "5").toInt
    val maxValueLength: Int = getPropWithDefaultValue("maxValueLength", "100").toInt

    val numberOfNodesToCreate = scala.util.Random.nextInt(maxNodesToCreate - minNodesToCreate) + minNodesToCreate
    for (_ <- 0 to numberOfNodesToCreate) {
      val pathSuffix = Random.alphanumeric.take(scala.util.Random.nextInt(maxPathSuffixLength - minPathSuffixLength) + minPathSuffixLength).mkString
      val numberOfKeyValuePairsToCreate = scala.util.Random.nextInt(maxKeyValuePairsPerNode - minKeyValuePairsPerNode) + minKeyValuePairsPerNode
      var data = mutable.HashMap[String, String]()
      for (_ <- 0 to numberOfKeyValuePairsToCreate) {
        val key: String = Random.alphanumeric.take(scala.util.Random.nextInt(maxKeyLength - minKeyLength) + minKeyLength).mkString
        val value: String = Random.alphanumeric.take(scala.util.Random.nextInt(maxValueLength - minValueLength) + minValueLength).mkString
        data += (key -> value)
      }
      CerberusApiActions.writeSecretData(data.asJava, s"$path$pathSuffix", token)
    }
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
    arn
  }

  def authenticate(arn: String): String = {
    if (StringUtils.isBlank(arn)) {
      throw new IllegalStateException()
    }

    try {
      val data = CerberusApiActions.retrieveIamAuthToken(
        arn,
        region,
        false
      )
      data.asInstanceOf[LazyMap].get("client_token").asInstanceOf[String]
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
                writeRoleId: String,
                categoryId: String): (String, String) = {

    val iamPermissions = List(
      Map("iam_principal_arn" -> iamPrincipalArnRunningSimulation, "role_id" -> writeRoleId).asJava,
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

  val scn: ScenarioBuilder =
    scenario("Iam principal authenticates and then reads secrets")
    .feed(generatedData.random)
    .exec(
      authenticate_and_fetch_encrypted_iam_auth_payload_and_store_in_session,
      decrypt_auth_payload_with_kms_and_store_auth_token_in_session,
      list_all_the_node_keys_for_the_root_sdb_path_and_store_keys_in_session,
      read_data_from_each_node
    )

  /**
    * Set up the scenario
    */
  setUp(
    scn.inject(
      nothingFor(30 seconds), // let the created IAM roles be eventually consistent
      constantUsersPerSec(getPropWithDefaultValue("NUMBER_OF_SERVICES_FOR_SIMULATION", "1").toInt) during(1 minutes)
    ).throttle(
      reachRps(getPropWithDefaultValue("PEAK_RPS", "1000").toInt) in (getPropWithDefaultValue("PEAK_RAMP_TIME_IN_MINUTES", "5").toInt minutes),
      holdFor(getPropWithDefaultValue("PEAK_RPS_HOLD_TIME_IN_MINUTES", "120").toInt minutes)
    )
  ).protocols(httpConf)

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
  }
}
