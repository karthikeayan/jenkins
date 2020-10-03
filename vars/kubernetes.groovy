def call(Map args = [:]) {

// Setting up Jenkins job parameters

properties([
    parameters([
        [$class: 'ChoiceParameter', 
            choiceType: 'PT_SINGLE_SELECT', 
            description: 'Select the Env Name from the Dropdown List', 
            filterLength: 1, 
            filterable: true, 
            name: 'Env', 
            randomName: 'choice-parameter-5631314439613978', 
            script: [
                $class: 'GroovyScript', 
                fallbackScript: [
                    classpath: [], 
                    sandbox: false, 
                    script: 'return[\'Could not get Env\']'
                ], 
                script: [
                    classpath: [], 
                    sandbox: false, 
                    script: 'return["Dev","QA","Prod"]'
                ]
            ]
        ],
        [$class: 'ChoiceParameter', 
            choiceType: 'PT_SINGLE_SELECT', 
            description: 'Select the branch', 
            filterLength: 1, 
            filterable: true, 
            name: 'branch', 
            randomName: 'choice-parameter-branch', 
            script: [
                $class: 'GroovyScript', 
                fallbackScript: [
                    classpath: [], 
                    sandbox: false, 
                    script: 'return[\'Could not get branch\']'
                ], 
                script: [
                    classpath: [], 
                    sandbox: false, 
                    script: 
                        '''import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import jenkins.model.Jenkins

def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
        com.cloudbees.plugins.credentials.Credentials.class,
        Jenkins.instance,
        null,
        null
);

def user = creds.findResult { it.id == "github_pat_jenkins_secret_id" ? it : null }
def github_pat = user.secret

try{
def gettags = ("git ls-remote -t -h https://username:$github_pat@github.com/organization/repository).execute()

def branchesNoDefault = gettags.text.readLines().collect { 
       it.split()[1].replaceAll('refs/heads/', '').replaceAll('refs/tags/', '').replaceAll("\\\\^\\\\{\\\\}", '')
   }

branches = branchesNoDefault.collect {
  if (it == "dev"){ return "dev:selected"}
  else { return it }
}
return branches

}catch(all){ return [all.getMessage()] }
                        '''
                ]
            ]
        ],
    ])
])

// Setup parameters
    def application_name = args.application_name
    def branch = args.branch
    def docker_repo_uri = "<account_id>.dkr.ecr.us-east-1.amazonaws.com"
    def region = "us-east-1"
    def nonprod_submitters = args.nonprod_submitters
    def prod_submitters = args.prod_submitters

// Deploy to Kubernetes
    deploy = {
        kubernetesDeploy(kubeconfigId: "kubeconfig_${kubernetes_env}",
            configs: "kubernetes/deployment.yaml",
            enableConfigSubstitution: true
        )
    }

// Pipeline start
    pipeline {
        agent any
        options {
            buildDiscarder(logRotator(daysToKeepStr: '45', numToKeepStr: '100'))
            timeout(activity: true, time: 7, unit: 'DAYS')
        }

        environment {
            application_name = "${application_name}"
            region = "${region}"
            DOCKER_BUILDKIT = "1"
            docker_repo_uri = "${docker_repo_uri}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout([$class: 'GitSCM', branches: [[name: "*/${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'WipeWorkspace']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'JenkinsGitHubKey', url: "git@github.com:organization/${application_name}.git"]]])
                }
            }
            stage("Build") {
                steps {
                    script {
                        script {
                            commit_id = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                            env.build_commit_id = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                        }
                        buildName "${env.BUILD_ID}-${branch}-${commit_id}-${env.environment}"

                        // Build docker image using Dockerfile in the root of the repository and push it to ECR
                        sh """
                            echo Building ${application_name} of commit_id ${commit_id} for ${branch} branch
                            docker build --rm -t ${microservice_name}:${commit_id} .
                            /usr/local/bin/aws ecr get-login --no-include-email --region ${region} | sh
                            docker tag ${application_name}:${commit_id} ${docker_repo_uri}/${application_name}:${commit_id}
                            docker tag ${application_name}:${commit_id} ${docker_repo_uri}/${application_name}:latest
                            docker push ${docker_repo_uri}/${application_name}:${commit_id}
                            docker push ${docker_repo_uri}/${application_name}:latest
                            docker rmi -f ${docker_repo_uri}/${application_name}:${commit_id}
                            docker rmi -f ${docker_repo_uri}/${application_name}:latest
                        """
                    }
                }
            }
            stage("Dev") {
                when {
                    anyOf {
                        expression { env.environment == 'dev' }
                    }
                }
                environment {
                    submitters = "${nonprod_submitters}"
                    kubernetes_env = "dev"
                }
                steps {
                    script {
                        assert env.branch.startsWith("dev")
                    }
                    script {
                        deploy ()
                    }
                }
            }
            stage("QA") {
                when {
                    anyOf {
                        expression { env.environment == 'qa' }
                    }
                }
                environment {
                    submitters = "${nonprod_submitters}"
                    kubernetes_env = "qa"
                }
                steps {
                    script {
                        assert env.branch.startsWith("dev")
                    }
                    script {
                        deploy ()
                    }
                }
            }
            stage("Prod") {
                when {
                    anyOf {
                        expression { env.environment == 'prod' }
                    }
                }
                environment {
                    submitters = "${prod_submitters}"
                    kubernetes_env = "prod"
                }
                steps {
                    script {
                        assert env.branch.startsWith("release") || env.branch.startsWith("hotfix")
                    }
                    script {
                        deploy ()
                    }
                }
            }
        }
        post { 
            always {
                // use ${currentBuild.number} in sh step
                sh "cp directory/file directory/${currentBuild.number}-file"
                // use ${env.BUILD_ID} in archiveAritfacts steps
                archiveArtifacts artifacts: "directory/${env.BUILD_ID}-file"
            }
        }
    }
}