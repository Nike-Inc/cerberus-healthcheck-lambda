# Health Check Lambda for Prod Cerberus

This is a quick and dirty node lambda to run an end to end test of the general health of the production Cerberus environment.
It checks that an ec2 instance or in this case a lambda can authenticate with Cerberus which will exercise CMS and its RDS DB.
It then uses that auth token to read from the healthcheck sdb which will exercise and test that Vault and Consul are up and running.

## Building

Give values to these settings in index.js.

```javascript
    var region = ''
    var acctId = ''
    var roleName = ''
    var host = ''
```

All the magic is in index.js, deps are in package.json if you need to add a dep use the normal `npm install --save xxxxxxx`
To build run `npm run package` this will create a healthcheck.zip package that can be used to upload into the lambda function.

## Deploying


### 1) Setup a role with KMS Decrypt policy

1. Login to AWS
1. Make sure the correct region is selected in upper right hand corner
1. Create the IAM Role
   1. Choose IAM
   1. Choose the Roles screen and “Create New Role” button
   1. Give a name e.g. “lambda_prod_healthcheck” and choose “Next Step” button
   1. Choose “AWS Lambda” and Next
   1. Choose a KMS Decrypt policy and Next
   1. Review the policy.  Choose ‘Create Role’.  You will need the account id and role name from this screen in the next steps

#### Policy

```json
{
 "Version": "2012-10-17",
 "Statement": [
     {
         "Sid": "Allow KMS Decrypt",
         "Effect": "Allow",
         "Action": [
             "kms:Decrypt"
         ],
         "Resource": [
             "*"
         ]
     }
 ]
}
```

## 2) Setup the Safe Deposit Box in Cerberus

1. Login to Cerberus
1. Create a new Application 
   1. Give the name "HEALTH CHECK BUCKET"
   1. Choose an owner
   1. Give a description
   1. Choose + under IAM Role Permissions, enter the AWS account id and role name, e.g. ‘lambda_prod_healthcheck’, choose ‘Read’ permissions, and ’Submit’
   1. The new IAM role permissions are now visible.  
1. Add a secret
    1. Choose ”+ Add New Vault Path”
    1. Enter the path ‘healthcheck’, the key name ‘value’, and the value 'I am healthy'.  Choose the eye icon to view the secret.  Click ‘Save’

## 3) Create the Lambda

1. Login to AWS
1. Navigate to the 'Lambda' screen
1. Under ‘Functions’ choose ‘Create a Lambda Function’
1. Choose ‘Blank Function’
1. On the 'Configure triggers' screen just choose 'Next'
1. On the 'Configure function' screen
   1. Give the name ‘cerberus-healthcheck’
   1. Choose ‘Upload a .ZIP file’
   1. Choose the zip created above
   1. Enter the Existing role, e.g. ‘lambda_prod_healthcheck’
   1. Choose ‘Next’ on the bottom of the screen
1. Review the lambda and choose ‘Create function’
1. Click the 'Test' button to see it run and your secret displayed on the screen

## 4) Setup the API Gateway

1. Login to AWS
1. Navigate to the 'API Gateway' screen
1. Create a new API, e.g. 'Cerberus Prod Healthcheck'
1. Create a new Resource, e.g. 'healthcheck'
1. Create a new 'GET' Method
1. Choose the lambda function and the correct region
1. Confirm the permission change
1. Under 'Integration Response' configure a new mapping with the regex 'UNHEALTHY.*' mapped to 500 error code.
   1.  The lambda should return a 200 unless the regex matches.
1. Use the test button to see it work
1. Stage the API if you would like to make it public
1. Setup the monitoring system of your choice to invoke the API endpoint periodically