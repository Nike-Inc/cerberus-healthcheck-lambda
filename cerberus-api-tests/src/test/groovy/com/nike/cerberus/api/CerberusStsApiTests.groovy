package com.nike.cerberus.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldju.commons.PropUtils
import com.nike.cerberus.api.util.TestUtils
import org.testng.annotations.AfterTest
import org.testng.annotations.BeforeTest
import org.testng.annotations.Test

import java.security.NoSuchAlgorithmException

import static com.nike.cerberus.api.CerberusApiActions.*

class CerberusStsApiTests {

    private String region
    private String cerberusAuthToken

    private ObjectMapper mapper

    @BeforeTest
    void beforeTest() throws NoSuchAlgorithmException {
        mapper = new ObjectMapper()
        TestUtils.configureRestAssured()
        loadRequiredEnvVars()
    }

    @AfterTest
    void afterTest() {
        deleteAuthToken(cerberusAuthToken)
    }

    private void loadRequiredEnvVars() {
        region = PropUtils.getRequiredProperty("TEST_REGION",
                "The region to use when authenticating with Cerberus using the STS Auth endpoint")
    }

    @Test
    void "test that IAM role can authenticate with STS auth"() {
        cerberusAuthToken = retrieveStsAuthToken(region)
    }
}
