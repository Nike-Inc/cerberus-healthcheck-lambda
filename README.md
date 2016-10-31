## Health Check Lambda for Prod Cerberus
This is a quick and dirty node lambda to run an end to end test of the general health of the production Cerberus environment.
It checks that an ec2 instance or in this case a lambda can authenticate with Cerberus which will exercise CMS and its RDS DB.
It then uses that auth token to read from the healthcheck sdb which will exercise and test that Vault and Consul are up and running.

## Building and deploying
All the magic is in index.js, deps are in package.json if you need to add a dep use the normal `npm install --save xxxxxxx`
To build run `npm run package` this will create a healthcheck.zip package that can be used to upload into the lambda function.

### Lambda
For this prod healthcheck I have deployed the actual lambda function to us-east-1

    https://console.aws.amazon.com/lambda/home?region=us-east-1#/functions/cerberus-prod-healthcheck?tab=code

arn:aws:lambda:us-east-1:265866363820:function:cerberus-prod-healthcheck

### AWS API Gateway
This function is behind an AWS Api Gateway resource so it can be triggered via an Https request, in us-west-2.

    https://us-west-2.console.aws.amazon.com/apigateway/home?region=us-west-2#/apis/lsnwkqy1b6/resources/nh28et/methods/GET

Right now its configured to return a 200 if the lambda is all good, if the lambda returns and errors that begin with "UNHEALTHY" the gateway will return a 500.

The new relic synthetic is monitoring the end point that is deployed here

    https://lsnwkqy1b6.execute-api.us-west-2.amazonaws.com/prod_cerberus/healthcheck
    https://us-west-2.console.aws.amazon.com/apigateway/home?region=us-west-2#/apis/lsnwkqy1b6/stages/prod_cerberus

If you make any changes to the gateway conf you need to redeploy the prod stage. 