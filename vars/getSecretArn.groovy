#!/usr/bin/groovy

def call(Map parameters = [:]) {

    def task_secrets
    env.ci_assume_role_secret = parameters.ci_assume_role
    if (parameters.task_secrets == null) {
        println "Secrets not in proper format"
        return
    } else {
        task_secrets = parameters.task_secrets
        environment = parameters.environment
    }
    env.aws_region = parameters.region
    env.randText = UUID.randomUUID().toString()

    task_secrets.each{
        env.task_secret_name = environment == "preprod2" ? "${environment}/${it.valueFrom}" : it.valueFrom

        def secretArn = sh returnStdout: true, script: '''
            AWS_CREDS="$(/usr/local/bin/aws sts assume-role --role-arn ${ci_assume_role_secret} --role-session-name "${randText}" --output json)"
            export AWS_ACCESS_KEY_ID="$(echo $AWS_CREDS | jq -r \'.Credentials.AccessKeyId\')"
            export AWS_SECRET_ACCESS_KEY="$(echo $AWS_CREDS | jq -r \'.Credentials.SecretAccessKey\')"
            export AWS_SECURITY_TOKEN="$(echo $AWS_CREDS | jq -r \'.Credentials.SessionToken\')"
            export AWS_SESSION_TOKEN="$(echo $AWS_CREDS | jq -r \'.Credentials.SessionToken\')"

            aws secretsmanager describe-secret --secret-id ${task_secret_name} --region ${aws_region} | jq .ARN -r -j
        '''

        it.valueFrom = secretArn        
    }

    def task_secrets_json = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(task_secrets)).replaceAll("(\\t|\\r?\\n)+", "");

    return task_secrets_json
}
