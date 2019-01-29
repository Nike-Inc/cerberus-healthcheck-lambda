package com.nike.cerberus.api.util

import com.fieldju.commons.PropUtils
import com.nike.cerberus.api.GatewaySslSocketFactory
import com.thedeanda.lorem.Lorem
import io.restassured.RestAssured
import io.restassured.config.SSLConfig
import org.apache.commons.lang3.RandomStringUtils
import org.apache.http.conn.ssl.SSLSocketFactory

import javax.net.ssl.SSLContext
import java.security.NoSuchAlgorithmException

import static io.restassured.RestAssured.*

class TestUtils {

    private TestUtils() {
        // no constructing
    }

    static void configureRestAssured() throws NoSuchAlgorithmException {
        baseURI = PropUtils.getRequiredProperty("CERBERUS_API_URL", "The Cerberus API URL to Test")

        System.out.println("Configuring rest assured to use baseURI: " + baseURI)

        enableLoggingOfRequestAndResponseIfValidationFails()

        // allow us to ping instances directly and not go through the load balancer
        useRelaxedHTTPSValidation()

        config.getHttpClientConfig().reuseHttpClientInstance()

        System.out.print("Performing sanity check get on /dashboard/index.html..")
        RestAssured.get(baseURI + "/dashboard/index.html").then().statusCode(200)
        System.out.println(" Success!")
    }

    static String generateRandomSdbName() {
        return "${RandomStringUtils.randomAlphabetic(5,10)} ${RandomStringUtils.randomAlphabetic(5,10)}"
    }

    static String generateRandomSdbDescription() {
        return "${Lorem.getWords(50)}"
    }

    static Map generateSdbJson(String description,
                                       String owner,
                                       List<Map<String, String>> userGroupPermissions,
                                       List<Map<String, String>> iamPrincipalPermissions) {
        return [
                description: description,
                owner: owner,
                'user_group_permissions': userGroupPermissions,
                'iam_role_permissions': iamPrincipalPermissions
        ]
    }
}
