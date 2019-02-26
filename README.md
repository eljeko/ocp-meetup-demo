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


## OCP: Create a servie account

Create a new service account called jenkins

```oc create serviceaccount jenkins```

The service account will need to have project level access to each of the projects it will manage The edit role provides a sufficient level of access needed by Jenkins.

```oc policy add-role-to-user edit system:serviceaccount:release-demo:jenkins -n release-demo```

```oc serviceaccounts get-token jenkins -n release-demo```

## Configure Jenkins

