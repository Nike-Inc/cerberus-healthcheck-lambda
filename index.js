var request = require('request')
var aws = require('aws-sdk')

exports.handler = function(event, context, callback) {
    var unhealthy = function (msg) {
        callback("UNHEALTHY: " + msg, null)
    }

    var healthy = function(msg) {
        callback(null, msg)
    }

    var region = ''
    var acctId = ''
    var roleName = ''
    var host = ''

    // Step 1 Check that EC2 Authentication is working
    request({
        uri: host + '/v1/auth/iam-role',
        method: 'POST',
        json: true,
        body: {
            "account_id" : acctId,
            "role_name": roleName,
            "region": region
        }
    }, function (err, response, data) {
        if (err) {
            unhealthy("Unable to authenticate again the CMS IAM Auth endpoint | " + JSON.stringify(err))
        }

        var ciphertextBlob = new Buffer(data['auth_data'], 'base64')
        var kms = new aws.KMS({ apiVersion: '2014-11-01', region: region })

        // Step 2 decrypt the payload with kms
        kms.decrypt({ CiphertextBlob: ciphertextBlob }, function (err, kmsResult) {
            if (err) {
                unhealthy("Unable to decrypt kms auth data from CMS IAM auth endpoint | " + JSON.stringify(err))
            }
            var token

            try {
                token = JSON.parse(new Buffer(kmsResult.Plaintext).toString())['client_token']
            } catch (e) {
                unhealthy(JSON.stringify('Error parsing KMS decrypt Result from CMS IAM auth endpoint ' + e.message))
            }

            // Step 3, use the token to read the healthcheck data sdb
            request({
                uri: host + '/v1/secret/app/health-check-bucket/healthcheck',
                method: 'GET',
                headers: {
                    'X-VAULT-TOKEN': token
                }
            }, function (err, response, body) {
                if (err) {
                    unhealthy("Unable to get health check data map from health check sdb, are Vault and Consul up and running? | " + JSON.stringify(err))
                }

                var json = JSON.parse(body)

                var value = undefined
                if ('data' in json && 'value' in json.data) {
                    value = json.data.value
                }

                if (value == undefined || value != "I am healthy") {
                    unhealthy("Unable to get healthcheck value from SDB, received '" + value + "' but was expecting 'I am healthy', are Vault and Consul up and running?")
                }

                // Step 4, delete the auth token
                request({
                    uri: host + '/v1/auth',
                    method: 'DELETE',
                    headers: {
                        'X-VAULT-TOKEN': token
                    }
                }, function (err, response, body) {
                    if (err) {
                        unhealthy("Unable to delete auth token from healthcheck | " + JSON.stringify(err))
                    }
                })
                healthy(value)
            })
        })
    })
};
