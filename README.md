# Jenkins shared library example to build docker image and deploy it into Kubernetes

### Github Personal Access Token
- Create Personal Access Token in Github
- Create Secret Text Credential in Jenkins
- Use the Credential in line number 57 on kubernetes.groovy

### Github SSH Key to clone
- Create SSH Key Credential in Jenkins
- Keep ssh private key which has access to Github organization to clone, commit build status
- This is used in line number 115 in kubernetes.groovy

### Kubernetes config files
- Create Secret File Credential in Jenkins for kubeconfig
- For example, kubeconifg_dev for dev kubernetes cluster
- This is used in line number 91 in kubernetes.groovy

### ECR Authentication
- Create IAM role with ECR Push access
- Assign the role to Jenkins EC2 instance

### Hard coded values
- Repository name in line number 61 is hard coded as of now, once manually created the job in Jenkins modify the repository from Jenkins configuration page

### Global Pipeline Settings in Jenkins System Configuration
- Refer the screenshot
- Use "JenkinsSharedLibrary" for the Name field

### Kubernetes Deployment Yaml Manifest
- Keep the kubernetes application deployment yaml manifest in the directory kubernetes/deployment.yaml in the application github repository
- Use $build_commit_id as image tag in the yam file