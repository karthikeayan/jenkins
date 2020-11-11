#!/usr/bin/groovy

def call(Map parameters = [:]) {

// ########################################################### //
    def task_secrets = parameters.task_secrets ?: null
// ########################################################### //    
   
def deployDatadog = {
    
    sh label: 'deploy', returnStatus: true, script: '''
cat <<EOF >taskdef.json
    {
        "family": "v1-taskDefintion",
        "containerDefinitions": [
            {
                "name": "${microservice_name}",
                "image": "${docker_repo_uri}/${microservice_name}:${commit_id}",
                "portMappings": [
                    {
                        "containerPort": ${containerPort},
                        "hostPort": ${hostPort},
                        "protocol": "tcp"
                    }
                ],
                "logConfiguration": {
                    "logDriver": "awslogs",
                    "options": {
                    "awslogs-group": "/ecs/${microservice_name_task_definition}",
                    "awslogs-region": "${region}",
                    "awslogs-stream-prefix": "ecs"
                    }
                },
                "essential": true,
                "environment": [
                    {
                        "name": "SPRING_PROFILES_ACTIVE",
                        "value": "${SPRING_PROFILE},swagger"
                    },
                    {
                        "name": "DEPLOYMENT_VERSION",
                        "value": "${commit_id}"
                    },
                    {
                        "name": "SERVER_PORT",
                        "value": "${containerPort}"
                    },
                    {
                        "name": "LD_PRELOAD",
                        "value": "/opt/dynatrace/oneagent/agent/lib64/liboneagentproc.so"
                    },
                    {
                        "name": "JAVA_OPTS",
                        "value": "-Djava.rmi.server.hostname=127.0.0.1 -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=1099 -Dcom.sun.management.jmxremote.rmi.port=1099 -Dcom.sun.management.jmxremote.local.only=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
                    }
                ],
                "dependsOn": [
                    {
                        "containerName": "install-oneagent",
                        "condition": "COMPLETE"
                    }
                ],
                "mountPoints": [
                    {
                        "containerPath": "/opt/dynatrace/oneagent",
                        "sourceVolume": "oneagent"
                    }
                ],
// ########################################################### //
                "secrets":
                    $(if ! [[ "$task_secrets" == "null" ]]; then
                        echo ${task_secrets}
                      else
                        echo "[]"
                    fi)
// ########################################################### //
            },
        ],
        "requiresCompatibilities": [
            "FARGATE"
        ],
        "cpu": "1024",
        "memory": "2048",
        "taskRoleArn": "${exec_role_arn}",
        "volumes": [
            {
                "name": "oneagent",
                "host": {}
            }
        ]
    }
EOF
    if [[ $DEBUG_FLAG = true ]]; then
        set -x
        printenv
    else 
        set +x
    fi

    AWS_CREDS="$(/usr/local/bin/aws sts assume-role --role-arn ${ci_assume_role} --role-session-name "${GIT_COMMIT}" --output json)"
    export AWS_ACCESS_KEY_ID="$(echo $AWS_CREDS | jq -r \'.Credentials.AccessKeyId\')"
    export AWS_SECRET_ACCESS_KEY="$(echo $AWS_CREDS | jq -r \'.Credentials.SecretAccessKey\')"
    export AWS_SECURITY_TOKEN="$(echo $AWS_CREDS | jq -r \'.Credentials.SessionToken\')"
    export AWS_SESSION_TOKEN="$(echo $AWS_CREDS | jq -r \'.Credentials.SessionToken\')"

    /usr/local/bin/aws ecs register-task-definition --family "${microservice_name_task_definition}" --execution-role-arn ${exec_role_arn} --task-role-arn ${exec_role_arn} --cli-input-json file://taskdef.json --region ${region} --network-mode awsvpc #&> /dev/null
    export TASK_REVISION="$(/usr/local/bin/aws ecs describe-task-definition --task-definition "${microservice_name_task_definition}" --region ${region} | jq -r \'.taskDefinition.revision\')"
    /usr/local/bin/aws ecs update-service --cluster ${cluster} --service "${microservice_name}" --task-definition "${microservice_name_task_definition}:$TASK_REVISION" --region ${region} #&> /dev/null
    '''
}

            pipeline {
                    agent any
                    options {
                        checkoutToSubdirectory ''
                        buildDiscarder(logRotator(daysToKeepStr: '45', numToKeepStr: '100'))
                        timeout(activity: true, time: 7, unit: 'DAYS')
                    }
                stages {
                    stage('DeployToDev') {
                        environment {
                            account_id = accounts (DEPLOY_TO: 'dev')
                            eureka_registry_url = registryNlb (DEPLOY_TO: 'dev')
                            elasticsearch_url = elasticsearchUrl(DEPLOY_TO: 'dev')
                            dev_exec_role_arn = "arn:aws:iam::${account_id}:role/EcsTaskExecutionRole-${microservice_name}"
                            dev_ci_assume_role = "arn:aws:iam::${account_id}:role/jenkins-mgmt-deploy"
                            exec_role_arn = "${dev_exec_role_arn}"
                            ci_assume_role = "${dev_ci_assume_role}"
                            commit_id = "${commit_id}"
                            SPRING_PROFILE = "dev"
                            ECS_ENV = "dev"
// ########################################################### //
                            task_secrets = getSecretArn(ci_assume_role: "${ci_assume_role}", task_secrets: task_secrets, region: "${region}", environment: "${ECS_ENV}")
// ########################################################### //
                        }
                        when {
                            anyOf {
                                expression { env.DEPLOY_TO == 'dev' }
                            }
                            beforeOptions true
                            beforeInput true
                            beforeAgent true
                        }
                        steps {
                            script {
                                deploy()
                            }
                        }
                    }
                }
            }
}
