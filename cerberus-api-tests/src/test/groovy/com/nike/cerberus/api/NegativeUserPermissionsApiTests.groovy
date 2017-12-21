package com.nike.cerberus.api

import com.fieldju.commons.PropUtils
import com.nike.cerberus.api.util.TestUtils
import com.thedeanda.lorem.Lorem
import io.restassured.path.json.JsonPath
import org.apache.commons.lang3.StringUtils
import org.apache.http.HttpStatus
import org.testng.annotations.AfterTest
import org.testng.annotations.BeforeTest
import org.testng.annotations.Test

import static com.nike.cerberus.api.CerberusCompositeApiActions.*
import static com.nike.cerberus.api.CerberusApiActions.*
import static com.nike.cerberus.api.util.TestUtils.generateRandomSdbDescription
import static com.nike.cerberus.api.util.TestUtils.generateSdbJson

class NegativeUserPermissionsApiTests {

    private static final String PERMISSION_DENIED_JSON_SCHEMA = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/permission-denied-invalid-auth-token-error.json"

    private String accountId
    private String roleName
    private String region
    private String iamAuthToken

    private String username
    private String password
    private String otpDeviceId
    private String otpSecret
    private String[] userGroups
    private String userAuthToken
    private Map userAuthData

    private Map roleMap

    private def userReadOnlySdb
    private def userWriteOnlySdb

    private void loadRequiredEnvVars() {
        accountId = PropUtils.getRequiredProperty("TEST_ACCOUNT_ID",
                "The account id to use when authenticating with Cerberus using the IAM Auth endpoint")

        roleName = PropUtils.getRequiredProperty("TEST_ROLE_NAME",
                "The role name to use when authenticating with Cerberus using the IAM Auth endpoint")

        region = PropUtils.getRequiredProperty("TEST_REGION",
                "The region to use when authenticating with Cerberus using the IAM Auth endpoint")

        username = PropUtils.getRequiredProperty("TEST_USER_EMAIL",
                "The email address for a test user for testing user based endpoints")

        password = PropUtils.getRequiredProperty("TEST_USER_PASSWORD",
                "The password for a test user for testing user based endpoints")

        // todo: make this optional
        otpSecret = PropUtils.getRequiredProperty("TEST_USER_OTP_SECRET",
                "The secret for the test users OTP MFA (OTP == Google auth)")

        otpDeviceId = PropUtils.getRequiredProperty("TEST_USER_OTP_DEVICE_ID",
                "The device id for the test users OTP MFA (OTP == Google auth)")
    }

    @BeforeTest
    void beforeTest() {
        TestUtils.configureRestAssured()
        loadRequiredEnvVars()
        userAuthData = retrieveUserAuthToken(username, password, otpSecret, otpDeviceId)
        String iamPrincipalArn = "arn:aws:iam::${accountId}:role/${roleName}"
        def iamAuthData = retrieveIamAuthToken(iamPrincipalArn, region)
        userAuthToken = userAuthData."client_token"
        iamAuthToken = iamAuthData."client_token"
        userGroups = userAuthData.metadata.groups.split(/,/)
        String userGroupOfTestUser = userGroups[0]

        String sdbCategoryId = getCategoryMap(userAuthToken).Applications
        String sdbDescription = generateRandomSdbDescription()

        roleMap = getRoleMap(userAuthToken)
        def ownerIamPrincipalPermissions = [["iam_principal_arn": iamPrincipalArn, "role_id": roleMap.owner]]
        def readOnlyUserGroupPermissions = [["name": userGroupOfTestUser, "role_id": roleMap.read]]
        def writeOnlyUserGroupPermissions = [["name": userGroupOfTestUser, "role_id": roleMap.write]]

        userReadOnlySdb = createSdbV2(userAuthToken, TestUtils.generateRandomSdbName(), sdbDescription, sdbCategoryId, iamPrincipalArn, readOnlyUserGroupPermissions, ownerIamPrincipalPermissions)
        userWriteOnlySdb = createSdbV2(userAuthToken, TestUtils.generateRandomSdbName(), sdbDescription, sdbCategoryId, iamPrincipalArn, writeOnlyUserGroupPermissions, ownerIamPrincipalPermissions)
    }

    @AfterTest
    void afterTest() {
        String readOnlyUserSdbId = userReadOnlySdb.getString("id")
        deleteSdb(iamAuthToken, readOnlyUserSdbId, V2_SAFE_DEPOSIT_BOX_PATH)

        String writeOnlyUserGroupSdbId = userWriteOnlySdb.getString("id")
        deleteSdb(iamAuthToken, writeOnlyUserGroupSdbId, V2_SAFE_DEPOSIT_BOX_PATH)

        logoutUser(userAuthToken)
        deleteAuthToken(iamAuthToken)
    }

    @Test
    void "test that a read user cannot edit permissions"() {
        def sdbId = userReadOnlySdb.getString("id")
        def roleMap = getRoleMap(userAuthToken)

        def newUserPermissions = [["name": 'foo', "role_id": roleMap.write]]
        def updateSdbJson = generateSdbJson(
                userReadOnlySdb.getString("description"),
                userReadOnlySdb.getString("owner"),
                newUserPermissions,
                userReadOnlySdb.get("iam_principal_permissions"))
        def updateSdbRequestUri = "$V2_SAFE_DEPOSIT_BOX_PATH/$sdbId"
        String schemaFilePath = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/ownership-required-permissions-error.json"

        // update SDB
        validatePUTApiResponse(userAuthToken, updateSdbRequestUri, HttpStatus.SC_FORBIDDEN, schemaFilePath, updateSdbJson)
    }

    @Test
    void "test that a read user cannot update the SDB owner"() {
        def sdbId = userReadOnlySdb.getString("id")
        def newOwner = "new-owner-group"

        def updateSdbJson = generateSdbJson(
                userReadOnlySdb.getString("description"),
                newOwner,
                userReadOnlySdb.get("user_group_permissions"),
                userReadOnlySdb.get("iam_principal_permissions"))
        def updateSdbRequestUri = "$V2_SAFE_DEPOSIT_BOX_PATH/$sdbId"
        String schemaFilePath = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/ownership-required-permissions-error.json"

        // update SDB
        validatePUTApiResponse(userAuthToken, updateSdbRequestUri, HttpStatus.SC_FORBIDDEN, schemaFilePath, updateSdbJson)
    }

    @Test
    void "test that a read user cannot write a secret"() {
        String sdbPath = userReadOnlySdb.getString("path")
        sdbPath = StringUtils.substringBeforeLast(sdbPath, "/")

        def writeSecretRequestUri = "$SECRETS_PATH/$sdbPath/${UUID.randomUUID().toString()}"

        // create secret
        validatePOSTApiResponse(userAuthToken, writeSecretRequestUri, HttpStatus.SC_FORBIDDEN, PERMISSION_DENIED_JSON_SCHEMA, [value: 'value'])
    }

    @Test
    void "test that a read user cannot delete the SDB"() {
        def sdbId = userReadOnlySdb.getString("id")
        def deleteSdbRequestUri = "$SECRETS_PATH/$sdbId"

        validateDELETEApiResponse(userAuthToken, deleteSdbRequestUri, HttpStatus.SC_FORBIDDEN, PERMISSION_DENIED_JSON_SCHEMA)
    }

    @Test
    void "test that a write user cannot edit permissions"() {
        def sdbId = userWriteOnlySdb.getString("id")
        def roleMap = getRoleMap(userAuthToken)

        def newUserPermissions = [["name": 'foo', "role_id": roleMap.read]]
        def updateSdbJson = generateSdbJson(
                userWriteOnlySdb.getString("description"),
                userWriteOnlySdb.getString("owner"),
                newUserPermissions,
                userWriteOnlySdb.get("iam_principal_permissions"))
        def updateSdbRequestUri = "$V2_SAFE_DEPOSIT_BOX_PATH/$sdbId"
        String schemaFilePath = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/ownership-required-permissions-error.json"

        // update SDB
        validatePUTApiResponse(userAuthToken, updateSdbRequestUri, HttpStatus.SC_FORBIDDEN, schemaFilePath, updateSdbJson)
    }

    @Test
    void "test that a write user cannot update the SDB owner"() {
        def sdbId = userWriteOnlySdb.getString("id")
        def newOwner = "new-owner-group"

        def updateSdbJson = generateSdbJson(
                userWriteOnlySdb.getString("description"),
                newOwner,
                userWriteOnlySdb.get("user_group_permissions"),
                userWriteOnlySdb.get("iam_principal_permissions"))
        def updateSdbRequestUri = "$V2_SAFE_DEPOSIT_BOX_PATH/$sdbId"
        String schemaFilePath = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/ownership-required-permissions-error.json"

        // update SDB
        validatePUTApiResponse(userAuthToken, updateSdbRequestUri, HttpStatus.SC_FORBIDDEN, schemaFilePath, updateSdbJson)
    }

    @Test
    void "test that a write user cannot delete the SDB"() {
        def sdbId = userWriteOnlySdb.getString("id")
        def deleteSdbRequestUri = "$SECRETS_PATH/$sdbId"

        validateDELETEApiResponse(userAuthToken, deleteSdbRequestUri, HttpStatus.SC_FORBIDDEN, PERMISSION_DENIED_JSON_SCHEMA)
    }

    @Test
    void "test that a user cannot call refresh more than the allotted number of times"() {
        def userTokenObject = retrieveUserAuthToken(username, password, otpSecret, otpDeviceId)
        String userClientToken = userTokenObject.client_token

        String allowedRefreshesStr = userTokenObject.metadata.max_refresh_count
        int allowedRefreshes = Integer.parseInt(allowedRefreshesStr)
        // exhaust refresh limit
        for (int i = 0; i < allowedRefreshes; i++) {
            userTokenObject = refreshUserAuthToken(userClientToken).data.client_token
            userClientToken = userTokenObject.client_token
        }

        String schemaFilePath = "$NEGATIVE_JSON_SCHEMA_ROOT_PATH/user-exceeded-limit-of-auth-token-refresh.json"
        // refresh user token
        validateGETApiResponse(AUTH_TOKEN_HEADER_NAME, userClientToken, USER_TOKEN_REFRESH_PATH, HttpStatus.SC_FORBIDDEN, schemaFilePath)
        // logout user
        logoutUser(userClientToken)
    }
}
