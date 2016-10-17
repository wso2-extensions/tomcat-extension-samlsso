# Tomcat Extension for SAML SSO #

Single Sign-On or SSO is a popular way of managing a log-in session throughout several applications or programs. 
It allows users to access multiple applications through a single log-in session, without having to enter credentials multiple times. 
This extension provides the capability of enabling SSO for user applications via WSO2 Identity Server.

Follow the below steps to see how this extension works

We will use two web applications named 'bookstore' and 'musicstore' with WSO2 Identity server.

## Step 1: Download and install Tomcat 8 and WSO2 IS. ##

Tomcat 8 will be used to deploy web applications and WSO2 IS will be used as the identity provider that enables SSO and SLO.

 1. Download Tomcat 8 and extract the zip file to your computer. The extracted directory will be your &lt;Tomcat_HOME&gt; directory.
 2. Download WSO2 IS and extract the zip file to your computer. The extracted directory will be your &lt;IS_HOME&gt; directory.

## Step 2: Checkout the project and build it. ##

Checkout the project using below command

    git clone https://github.com/wso2-extensions/tomcat-extension-samlsso.git

Build it using maven

    maven clean install

## Step 3: Add the necessary configurations and libraries. ##

 1. Open the server.xml file (stored in the <Tomcat_HOME&gt;/conf directory).
 2. Add the following under the server tag:
    `<Listener className="org.wso2.appserver.configuration.listeners.ServerConfigurationLoader"/>`
 3. Add the following under the Service tag: 
    `<Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"  maxThreads="150" SSLEnabled="true" scheme="https" 
    secure="true" clientAuth="false" sslProtocol="TLS" keystoreFile="conf/wso2/wso2carbon.jks"  keystorePass="wso2carbon"/>`
 4. Add the following under the localhost container: 
    `<Valve className="org.wso2.appserver.webapp.security.saml.SAML2SSOValve"/>`
 5. Build the project and copy the &lt;project_root&gt;/modules/samlsso/target/samlsso-1.0.0-SNAPSHOT-fat.jar to &lt;Tomcat_HOME&gt;/lib
 6. Open the context.xml file (stored in the &lt;Tomcat_HOME&gt;/conf directory).
 7. Add the following under the Context tag:
    `<Listener className="org.wso2.appserver.configuration.listeners.ContextConfigurationLoader"/>`
 8. Copy &lt;project_root&gt;/modules/samlsso/src/main/Resources/wso2 folder to &lt;Tomcat_HOME&gt;/conf
 9. Copy &lt;project_root&gt;/samples/sso-sample-apps/bookstore-app/target/bookstore-app.war and 
    &lt;project_root&gt;/samples/sso-sample-apps/musicstore-app/target/musicstore-app.war to &lt;Tomcat_HOME&gt;/webapps folder.
    
## Step 4: Register web applications on WSO2 Identity Server. ##
 Here WSO2 Identity Server will act as the identity provider for service providers. We have to register web apps as service providers
 to give them the single sign on capability. Follow the below steps to register bookstore app and musicstore app as service providers.
 
 1. Log into the management console of WSO2 IS.
 2. Click Service Providers -> Add in the navigator.
 3. Enter 'bookstore-app' in the Service Provider Name field in the Add New Service Provider screen:
 
 ![alt tag](https://docs.wso2.com/download/attachments/51492203/Screen%20Shot%202016-08-04%20at%202.31.57%20PM.png?version=1&modificationDate=1470301373000&api=v2)

 4. Click Register to open the Service Providers screen.
 
 ![alt tag](https://docs.wso2.com/download/attachments/51492203/Screen%20Shot%202016-08-04%20at%202.43.00%20PM.png?version=1&modificationDate=1470302014000&api=v2)
 
 5. Click Inbound Authentication Configuration -> SAML2 Web SSO Configuration and click Configure.
 6. You can now start specifying the SSO related configurations for the service provider.
 ![alt tag](https://docs.wso2.com/download/attachments/51492203/Screen%20Shot%202016-08-04%20at%202.45.55%20PM.png?version=1&modificationDate=1470302231000&api=v2)
 
 in the above screen:
  
 i. Enter bookstore-app in the Issuer ID field.
 ii. Enter https://localhost:8443/bookstore-app/acs in the Assertion Consumer URLs field and click Add.
 iii. Select wso2carbon for the Certificate Alias field.
 iv. Select the Enable Response Signing, Enable Signature Validation in Authentication Requests and Logout Requests and Enable Single Logout check boxes.
 
 7. Click Update to finish registering the service provider.
 8. Repeat the above steps to register a service provider for the musicstore-app application. Use the following values:
 
 i. The service provider name should be musicstore-app.
 ii. The default assertion consumer url should be https://localhost:8443/musicstore-app/acs.

 ## Step 5 ##
 
 See How samples works
 
 1. Start the WSO2 Application Server.
 2. Now you are ready to try out the Application Server SAML 2.0 based Single-Sign-On Valve.
    * Try accessing http://localhost:8080/musicstore-app web application. You will be redirected to the Identity Server login page. 
       Similarly, try accessing http://localhost:8080/bookstore-app, you will be redirected to the same login page.
    * Enter your credentials to one of the login pages and you will be redirected to the originally requested web application resource.
    * If you have already accessed the musicstore-app, try accessing the bookstore-app. 
      You will be able to access the bookstore-app without needing any additional authentication. 
      Here you have successfully experienced the SAML 2.0 Web Browser Single-Sign-On Profile.