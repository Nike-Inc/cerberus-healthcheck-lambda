package com.nike.cerberus

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import com.nike.cerberus.api.CerberusApiActions._

import scala.collection.JavaConverters._
import scala.collection.{JavaConverters, Map}

/**
  * Contains the Cerberus actions that are defined using the Gatling DSL for the perf testing
  */
object CerberusGatlingApiActions {

  val authenticate_and_fetch_encrypted_iam_auth_payload_and_store_in_session: ChainBuilder = exec(
    http("authenticate and fetch encrypted iam auth payload and store in session")
    .post("/v2/auth/iam-principal")
    .header("X-Cerberus-Client", "GatlingTests/0.0.0")
    .body(StringBody(
      """
        |{
        |   "iam_principal_arn" : "${role_arn}",
        |   "region": "${region}"
        |}
      """.stripMargin)).asJSON
    .check(status.is(200))
    .check(jsonPath("$..auth_data").find.saveAs("auth_data"))
  ).exec(exitHereIfFailed)

  val decrypt_auth_payload_with_kms_and_store_auth_token_in_session: ChainBuilder = exec( session =>
    session.set("auth_token", decryptAuthTokenAsRoleAndRetrieveToken(
      session("role_arn").as[String],
      session("region").as[String],
      session("auth_data").as[String])
    )
  ).exec(exitHereIfFailed)

  val authenticate_and_fetch_sts_auth_token_and_store_in_session: ChainBuilder = exec( session => {
    val map = JavaConverters.mapAsScalaMapConverter(getSignedHeaders(session("region").as[String])).asScala.toMap
    session.set("header_authorization", map get "Authorization" get)
      .set("x-amz-date_header", map get "X-Amz-Date" get)
      .set("x-amz-security-token_header", map get "X-Amz-Security-Token" get)
      .set("host_header", map get "Host" get)
  }
  ).exec(
    http("authenticate with iam sts auth")
      .post("/v2/auth/sts-identity")
      .header("Authorization", "${header_authorization}")
      .header("X-Amz-Date", "${x-amz-date_header}")
      .header("X-Amz-Security-Token", "${x-amz-security-token_header}")
      .header("Host", "${host_header}")
      .check(status.is(200))
      .check(jsonPath("$.client_token").find.saveAs("auth_token"))
  ).exec(exitHereIfFailed)

  val list_all_the_node_keys_for_the_root_sdb_path_and_store_keys_in_session: ChainBuilder = exec(
    http("list all the nodes in the root sdb path")
    .get("/v1/secret/${sdb_root_path}")
    .queryParam("list", "true")
    .header("X-Vault-Token", "${auth_token}")
    .header("X-Cerberus-Client", "GatlingTests/0.0.0")
    .check(status.is(200))
    .check(
      jsonPath("$.data.keys[*]").findAll.saveAs("node_keys")
    )
  ).exec(exitHereIfFailed)

  val read_data_from_each_node: ChainBuilder = foreach("${node_keys}", "key") {
    exec(
      http("read data from each node")
        .get("/v1/secret/${sdb_root_path}${key}")
        .header("X-Vault-Token", "${auth_token}")
        .header("X-Cerberus-Client", "GatlingTests/0.0.0")
        .check(status.is(200))
    )
  }

}
