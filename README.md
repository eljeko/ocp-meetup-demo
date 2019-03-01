# Intro

This repo contains a sample on how to manage a release pipeline with an external jenkins on OCP.

# Setup the OCP

Login from cli:

```oc login <OCP_URL>```

Prepare the new project:

```oc new-project release-demo```

Create a new build for binary deploy:

```oc new-build --image-stream=openshift/jboss-webserver31-tomcat8-openshift:1.2 --name=acme-app --binary=true```

## Build the app

cd ```web-app``` follow the README.md inside the dir to create the bianry then go back to the root of this project.

## Execute Bianry build

Start a build with the binary

```oc start-build acme-app --from-file=web-app/target/ROOT.war```

Create the new app

```oc new-app acme-app```

Expose the routes:

```oc expose svc/acme-app```

# Setup Jenkins

Download the latest war file

Run the war file as a server

```java -jar jenkins.war --httpListenAddress=<IP_ADDRESS>```

eg:

```java -jar jenkins.war --httpListenAddress=192.100.20.100```


## OCP: Create a service account

Create a new service account called jenkins

```oc create serviceaccount jenkins```

The service account will need to have project level access to each of the projects it will manage The edit role provides a sufficient level of access needed by Jenkins.

```oc policy add-role-to-user edit system:serviceaccount:release-demo:jenkins -n release-demo```

```oc serviceaccounts get-token jenkins -n release-demo```

## Configure Jenkins

Add permission in order to be able to manage project from Jenkins
```oc policy add-role-to-user edit system:serviceaccount:release-demo-prod:jenkins -n release-demo-prod```

Add permission in order to push image across projects
```oc policy add-role-to-user edit system:serviceaccount:release-demo-prod:jenkins -n release-demo```

Excrat token used from Jenkins
```
oc serviceaccounts get-token jenkins -n release-demo-prod
oc serviceaccounts get-token jenkins -n release-demo
```

## Blue/Green Deployment
```
oc create is acme-app-green
oc create is acme-app-blue
oc tag release-demo/acme-app:1.0_GA release-demo-prod/acme-app-blue:1.0_GA
oc tag release-demo/acme-app:1.0_GA release-demo-prod/acme-app-green:1.0_GA
oc new-app --name='acme-app-blue' -l name='acme-app' --image-stream="release-demo-prod/acme-app-blue:1.0_GA"
oc expose service acme-app-blue
oc new-app --name='acme-app-green' -l name='acme-app' --image-stream="release-demo-prod/acme-app-green:1.0_GA"
oc expose service acme-app-green
oc expose service acme-app-blue --name='acme-app' -l name='acme-app'
oc patch route/acme-app  --patch='{"spec":{"to":{"name":"acme-app-green"}}}'
```

## Pipeline Stages
### Pre Production
* prepare
* Source checkout
* Quality Assurance
  * SonarQube analysis
  * Maven Tests
* App Build and Package
* OCP
  * Prepare
  * Update BuildConfig
  * Start New Build
  * Deploy
  * Rollout

### Production
* Prepare
* Verify Active Service
* Tag Image
* Confirm Rollout
* Rollout
* Patch Route





