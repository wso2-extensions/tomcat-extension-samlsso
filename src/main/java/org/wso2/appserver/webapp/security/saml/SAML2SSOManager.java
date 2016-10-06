/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.wso2.appserver.webapp.security.saml;

import com.google.gson.Gson;
import net.shibboleth.utilities.java.support.codec.Base64Support;
import org.apache.catalina.connector.Request;
import org.apache.juli.logging.Log;
import org.apache.xml.security.signature.XMLSignature;
import org.joda.time.DateTime;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnContextComparisonTypeEnumeration;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.Extensions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.saml2.core.RequestedAuthnContext;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.SessionIndex;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.impl.AuthnContextClassRefBuilder;
import org.opensaml.saml.saml2.core.impl.AuthnRequestBuilder;
import org.opensaml.saml.saml2.core.impl.IssuerBuilder;
import org.opensaml.saml.saml2.core.impl.LogoutRequestBuilder;
import org.opensaml.saml.saml2.core.impl.NameIDBuilder;
import org.opensaml.saml.saml2.core.impl.NameIDPolicyBuilder;
import org.opensaml.saml.saml2.core.impl.RequestedAuthnContextBuilder;
import org.opensaml.saml.saml2.core.impl.SessionIndexBuilder;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.wso2.appserver.configuration.context.WebAppSingleSignOn;
import org.wso2.appserver.configuration.listeners.ServerConfigurationLoader;
import org.wso2.appserver.configuration.server.AppServerSingleSignOn;
import org.wso2.appserver.webapp.security.Constants;
import org.wso2.appserver.webapp.security.agent.SSOAgentSessionManager;
import org.wso2.appserver.webapp.security.bean.LoggedInSession;
import org.wso2.appserver.webapp.security.bean.SAML2SSO;
import org.wso2.appserver.webapp.security.saml.signature.SSOX509Credential;
import org.wso2.appserver.webapp.security.saml.signature.SignatureValidator;
import org.wso2.appserver.webapp.security.saml.signature.X509CredentialImplementation;
import org.wso2.appserver.webapp.security.utils.DataHolder;
import org.wso2.appserver.webapp.security.utils.SSOUtils;
import org.wso2.appserver.webapp.security.utils.exception.SSOException;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.servlet.http.HttpSession;

/**
 * This class manages the generation of varied request and response types that are utilized
 * within the SAML 2.0 single-sign-on (SSO) and single-logout (SLO) processes.
 *
 * @since 6.0.0
 */
public class SAML2SSOManager {
    private AppServerSingleSignOn serverConfiguration;
    private WebAppSingleSignOn contextConfiguration;

    public SAML2SSOManager(WebAppSingleSignOn context) throws SSOException {
        serverConfiguration = ServerConfigurationLoader.getServerConfiguration().getSingleSignOnConfiguration();
        contextConfiguration = context;

        loadCustomSignatureValidatorClass();
        SSOUtils.doBootstrap();
    }

    /**
     * Loads a custom signature validator class specified in the SSO Agent configurations.
     */
    private void loadCustomSignatureValidatorClass() throws SSOException {
        try {
            if (serverConfiguration != null) {
                DataHolder.getInstance().setObject(Class.
                        forName(Optional.ofNullable(serverConfiguration.getSignatureValidatorImplClass())
                                .orElse(Constants.DEFAULT_SIGN_VALIDATOR_IMPL)).newInstance());
            }
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new SSOException("Error loading custom signature validator class", e);
        }
    }

    /**
     * Handles a SAML 2.0 Authentication Request (AuthnRequest) for HTTP POST binding.
     *
     * @param request the HTTP servlet request with SAML 2.0 message
     * @return the HTML payload to be transmitted
     * @throws SSOException if an error occurs when handling AuthnRequest
     */
    public String handleAuthenticationRequestForPOSTBinding(Request request) throws SSOException {
        RequestAbstractType requestMessage = buildAuthnRequest(request);

        if (contextConfiguration.isRequestSigningEnabled()) {
            requestMessage = SSOUtils.setSignature(requestMessage, XMLSignature.ALGO_ID_SIGNATURE_RSA,
                    new X509CredentialImplementation(SSOX509Credential.getInstance()));
        }

        return preparePOSTRequest(requestMessage);
    }

    /**
     * Handles a SAML 2.0 Authentication Request (AuthnRequest) for HTTP Redirect binding.
     *
     * @param request the HTTP servlet request with SAML 2.0 message
     * @return the Identity Provider URL with the query string appended based on the SAML 2.0 Request and configurations
     * @throws SSOException if an error occurs when handling AuthnRequest
     */
    public String handleAuthenticationRequestForRedirectBinding(Request request) throws SSOException {
        RequestAbstractType requestMessage = buildAuthnRequest(request);
        return prepareRedirectRequest(requestMessage);
    }

    /**
     * Handles a SAML 2.0 Logout Request (LogoutRequest) for SAML 2.0 HTTP POST binding.
     *
     * @param request the HTTP servlet request with SAML 2.0 message
     * @return the HTML payload to be transmitted
     * @throws SSOException if an error occurs when handling LogoutRequest
     */
    public String handleLogoutRequestForPOSTBinding(Request request) throws SSOException {
        Gson gson = new Gson();
        LoggedInSession session = gson.fromJson(
                request.getSession(false).getAttribute(Constants.LOGGED_IN_SESSION).toString(),
                LoggedInSession.class);
        RequestAbstractType requestMessage;
        if (session != null) {
            requestMessage = buildLogoutRequest(session.getSAML2SSO().getSubjectId(),
                    session.getSAML2SSO().getSessionIndex());
            if (contextConfiguration.isRequestSigningEnabled()) {
                requestMessage = SSOUtils.setSignature(requestMessage, XMLSignature.ALGO_ID_SIGNATURE_RSA,
                        new X509CredentialImplementation(SSOX509Credential.getInstance()));
            }
        } else {
            throw new SSOException(
                    "Single-logout (SLO) Request cannot be built, single-sign-on (SSO) session is null");
        }

        return preparePOSTRequest(requestMessage);
    }


    /**
     * Handles a SAML 2.0 Logout Request (LogoutRequest) for SAML 2.0 HTTP Redirect binding.
     *
     * @param request the HTTP servlet request with SAML message
     * @return the Identity Provider URL with the query string appended based on the SAML 2.0 Request and configurations
     * @throws SSOException if an error occurs when handling LogoutRequest
     */
    public String handleLogoutRequestForRedirectBinding(Request request) throws SSOException {
        Gson gson = new Gson();
        LoggedInSession session = gson.fromJson(
                request.getSession(false).getAttribute(Constants.LOGGED_IN_SESSION).toString(),
                LoggedInSession.class);
        RequestAbstractType requestMessage;
        if (session != null) {
            requestMessage = buildLogoutRequest(session.getSAML2SSO().getSubjectId(),
                    session.getSAML2SSO().getSessionIndex());
        } else {
            throw new SSOException("Single Logout Request can not be built, single-sign-on session is null");
        }

        return prepareRedirectRequest(requestMessage);
    }

    /**
     * Handles the specified {@code RequestAbstractType} for SAML 2.0 HTTP POST binding.
     *
     * @param rawRequestMessage the {@link RequestAbstractType} which is either a SAML 2.0 AuthnRequest or
     *                          a SAML 2.0 LogoutRequest
     * @return the HTML payload string
     * @throws SSOException if an error occurs when encoding the request message
     */
    private String preparePOSTRequest(RequestAbstractType rawRequestMessage) throws SSOException {
        String encodedRequestMessage = SSOUtils.
                encodeRequestMessage(rawRequestMessage, SAMLConstants.SAML2_POST_BINDING_URI);

        Map<String, String[]> parameters = new HashMap<>();
        parameters.put(Constants.HTTP_POST_PARAM_SAML_REQUEST, new String[]{encodedRequestMessage});

        //  add any additional parameters defined
        Map<String, String[]> queryParams = SSOUtils.getSplitQueryParameters(contextConfiguration.getOptionalParams());

        //  encode the optional parameter values
        queryParams.entrySet()
                .stream()
                .forEach(filteredEntry -> filteredEntry.setValue((String[]) Stream.of(filteredEntry.getValue())
                        .map(value -> {
                            try {
                                return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
                            } catch (UnsupportedEncodingException e) {
                                //  ignore the exception since every implementation of the Java platform must support
                                //  the 'UTF-8' character set
                            }
                            return null;
                        })
                        .toArray()));

        if (!queryParams.isEmpty()) {
            parameters.putAll(queryParams);
        }

        StringBuilder htmlParameters = new StringBuilder();
        parameters.entrySet()
                .stream()
                .filter(entry -> ((entry.getKey() != null) &&
                        (entry.getValue() != null) && (entry.getValue().length > 0)))
                .forEach(filteredEntry -> Stream.of(filteredEntry.getValue())
                        .forEach(parameter -> htmlParameters
                                .append("<input type='hidden' name='")
                                .append(filteredEntry.getKey())
                                .append("' value='")
                                .append(parameter)
                                .append("'>\n")));

        return "<html>\n" +
                "<body>\n" +
                "<p>You are now redirected back to " + serverConfiguration.getIdpURL() + " \n" +
                "If the redirection fails, please click the post button.</p>\n" +
                "<form method='post' action='" + serverConfiguration.getIdpURL() + "'>\n" +
                "<p>\n" +
                htmlParameters.toString() +
                "<button type='submit'>POST</button>\n" +
                "</p>\n" +
                "</form>\n" +
                "<script type='text/javascript'>\n" +
                "document.forms[0].submit();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * Handles the specified {@code RequestAbstractType} for SAML 2.0 HTTP-Redirect binding.
     *
     * @param rawRequestMessage the {@link RequestAbstractType} which is either a SAML 2.0 AuthnRequest or
     *                          a SAML 2.0 LogoutRequest
     * @return the Identity Provider URL with the query string appended based on the SAML 2.0 Request and configurations
     * @throws SSOException if an error occurs when preparing the HTTP Redirect request
     */
    private String prepareRedirectRequest(RequestAbstractType rawRequestMessage) throws SSOException {
        //  compresses the message using default DEFLATE encoding since SAMLEncoding query string parameter
        //  is not specified, perform Base64 encoding and then URL encoding
        String encodedRequestMessage = SSOUtils.
                encodeRequestMessage(rawRequestMessage, SAMLConstants.SAML2_REDIRECT_BINDING_URI);
        StringBuilder httpQueryString = new StringBuilder(Constants.HTTP_POST_PARAM_SAML_REQUEST +
                "=" + encodedRequestMessage);

        //  adds any additional parameters defined
        Map<String, String[]> queryParams = SSOUtils.getSplitQueryParameters(contextConfiguration.getOptionalParams());
        if (!queryParams.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            queryParams.entrySet()
                    .stream()
                    .filter(entry -> ((entry.getKey() != null) &&
                            (entry.getValue() != null) && (entry.getValue().length > 0)))
                    .forEach(filteredEntry ->
                            Stream.of(filteredEntry.getValue())
                                    .forEach(parameterValue -> {
                                                try {
                                                    builder
                                                            .append("&")
                                                            .append(filteredEntry.getKey())
                                                            .append("=")
                                                            .append(URLEncoder.encode(parameterValue,
                                                                    StandardCharsets.UTF_8.name()));
                                                } catch (UnsupportedEncodingException e) {
                                                    //  ignore the exception since every implementation of the Java
                                                    //  platform must support the 'UTF-8' character set
                                                }
                                            }
                                            ));

            httpQueryString.append(builder);
        }

        if (contextConfiguration.isRequestSigningEnabled()) {
            SSOUtils.addDeflateSignatureToHTTPQueryString(httpQueryString,
                    new X509CredentialImplementation(SSOX509Credential.getInstance()));
        }

        String idpUrl;
        if (serverConfiguration.getIdpURL().contains("?")) {
            idpUrl = serverConfiguration.getIdpURL().concat("&").concat(httpQueryString.toString());
        } else {
            idpUrl = serverConfiguration.getIdpURL().concat("?").concat(httpQueryString.toString());
        }
        return idpUrl;
    }

    /**
     * Returns a SAML 2.0 Authentication Request (AuthnRequest) instance based on the HTTP servlet request.
     *
     * @param request the HTTP servlet request
     * @return a SAML 2.0 Authentication Request (AuthnRequest) instance
     */
    private AuthnRequest buildAuthnRequest(Request request) {
        //  the Issuer element identifies the entity that generated the request message
        Issuer issuer = new IssuerBuilder().buildObject();

        if (contextConfiguration.getIssuerId() == null) {
            //  generates the service provider entity ID
            String issuerID = SSOUtils.generateIssuerID(request.getContextPath(), request.getHost().getAppBase())
                    .orElse("");
            contextConfiguration.setIssuerId(issuerID);
        }
        issuer.setValue(contextConfiguration.getIssuerId());

        //  the NameIDPolicy element tailors the subject name identifier of assertions resulting from AuthnRequest
        NameIDPolicy nameIdPolicy = new NameIDPolicyBuilder().buildObject();
        //  the URI reference corresponding to a name identifier format
        nameIdPolicy.setFormat("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        //  a unique identifier for a service provider or affiliation of providers for whom the identifier was generated
        nameIdPolicy.setSPNameQualifier("Issuer");
        //  identity provider is allowed, in the course of fulfilling the request to generate a new identifier to
        //  represent the principal
        nameIdPolicy.setAllowCreate(true);

        //  this represents a URI reference identifying an authentication context class that describes the
        //  authentication context declaration that follows
        AuthnContextClassRef authnContextClassRef = new AuthnContextClassRefBuilder().buildObject();
        authnContextClassRef.
                setAuthnContextClassRef("urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport");

        //  specifies the authentication context requirements of authentication statements returned in response
        //  to a request or query
        RequestedAuthnContext requestedAuthnContext = new RequestedAuthnContextBuilder().buildObject();
        //  resulting authentication context in the authentication statement must be the exact match of the
        //  authentication context specified
        requestedAuthnContext.setComparison(AuthnContextComparisonTypeEnumeration.EXACT);
        requestedAuthnContext.getAuthnContextClassRefs().add(authnContextClassRef);

        //  creates an AuthnRequest instance
        AuthnRequest authnRequest = new AuthnRequestBuilder().buildObject();

        //  sets the mandatory attributes of a SAML 2.0 Request
        authnRequest.setID(SSOUtils.createID());
        authnRequest.setVersion(SAMLVersion.VERSION_20);
        authnRequest.setIssueInstant(new DateTime());

        authnRequest.setForceAuthn(
                Optional.ofNullable((Boolean) (request.getAttribute(Constants.IS_FORCE_AUTH_ENABLED)))
                        .orElse(false));
        authnRequest.setIsPassive(
                Optional.ofNullable((Boolean) (request.getAttribute(Constants.IS_PASSIVE_AUTH_ENABLED)))
                        .orElse(false));
        authnRequest.setProtocolBinding(contextConfiguration.getHttpBinding());

        if (contextConfiguration.getConsumerURL() == null) {
            //  generates the SAML 2.0 Assertion Consumer URL
            String acsBase = Optional.ofNullable(serverConfiguration.getACSBase())
                    .orElse(SSOUtils.constructApplicationServerURL(request)
                            .orElse(""));
            String consumerURLPostfix = Optional.ofNullable(contextConfiguration.getConsumerURLPostfix())
                    .orElse(Constants.DEFAULT_CONSUMER_URL_POSTFIX);
            String consumerURL = SSOUtils.generateConsumerURL(request.getContextPath(), acsBase, consumerURLPostfix)
                    .orElse("");
            contextConfiguration.setConsumerURL(consumerURL);
        }
        authnRequest.setAssertionConsumerServiceURL(contextConfiguration.getConsumerURL());

        authnRequest.setIssuer(issuer);
        authnRequest.setNameIDPolicy(nameIdPolicy);
        authnRequest.setRequestedAuthnContext(requestedAuthnContext);
        authnRequest.setDestination(serverConfiguration.getIdpURL());

        //  if any optional protocol message extension elements that are agreed on between the communicating parties
        Optional.ofNullable(request.getAttribute(Extensions.DEFAULT_ELEMENT_LOCAL_NAME))
                .ifPresent(extensions -> authnRequest.setExtensions((Extensions) extensions));

        return authnRequest;
    }

    /**
     * Returns a SAML 2.0 Logout Request (LogoutRequest) instance.
     *
     * @param user         the identifier that specify the principal as currently recognized by the identity and
     *                     service providers
     * @param sessionIndex the identifier that indexes this session at the message recipient
     * @return a SAML 2.0 Logout Request (LogoutRequest) instance
     */
    private LogoutRequest buildLogoutRequest(String user, String sessionIndex) {
        //  creates a Logout Request instance
        LogoutRequest logoutRequest = new LogoutRequestBuilder().buildObject();

        DateTime issueInstant = new DateTime();

        Issuer issuer = new IssuerBuilder().buildObject();
        issuer.setValue(contextConfiguration.getIssuerId());

        NameID nameId = new NameIDBuilder().buildObject();
        nameId.setFormat("urn:oasis:names:tc:SAML:2.0:nameid-format:entity");
        nameId.setValue(user);

        SessionIndex sessionIndexElement = new SessionIndexBuilder().buildObject();
        sessionIndexElement.setSessionIndex(sessionIndex);

        //  sets the mandatory attributes of a SAML 2.0 Request
        logoutRequest.setID(SSOUtils.createID());
        logoutRequest.setIssueInstant(issueInstant);

        logoutRequest.setDestination(serverConfiguration.getIdpURL());
        //  time at which the request expires, after which the recipient may discard the message
        logoutRequest.setNotOnOrAfter(new DateTime(issueInstant.getMillis() + (5 * 60 * 1000)));
        logoutRequest.setIssuer(issuer);
        logoutRequest.setNameID(nameId);
        logoutRequest.getSessionIndexes().add(sessionIndexElement);
        //  indicates the reason for the logout
        logoutRequest.setReason("Single Logout");

        return logoutRequest;
    }

    /**
     * Processes a SAML 2.0 response depending on its type, either a SAML 2.0 Response for a single-sign-on (SSO)
     * SAML 2.0 Request by the client application or a SAML 2.0 Response for a single-logout (SLO) SAML 2.0 Request
     * from a service provider.
     *
     * @param request the servlet request processed
     * @throws SSOException if SAML 2.0 response is null
     */
    public void processResponse(Request request) throws SSOException {
        String saml2SSOResponse = request.getParameter(Constants.HTTP_POST_PARAM_SAML_RESPONSE);

        if (saml2SSOResponse != null) {
            String decodedResponse = new String(Base64Support.decode(saml2SSOResponse), StandardCharsets.UTF_8);
            Optional<XMLObject> samlObject = SSOUtils.unmarshall(decodedResponse);
            if (samlObject.isPresent()) {
                if (samlObject.get() instanceof LogoutResponse) {
                    //  this is a SAML 2.0 Response for a single logout request from the service provider
                    performSingleLogout(request);
                } else {
                    processSingleSignInResponse(request);
                }
            }
        } else {
            throw new SSOException("Invalid SAML 2.0 Response, SAML Response cannot be null");
        }
    }

    /**
     * Processes a single-sign-in SAML 2.0 Response received for an Authentication Request sent.
     *
     * @param request the HTTP servlet request
     * @throws SSOException if the received SAML 2.0 Response is invalid
     */
    private void processSingleSignInResponse(Request request) throws SSOException {
        LoggedInSession session = new LoggedInSession();
        SAML2SSO saml2SSO = new SAML2SSO();
        Gson gson = new Gson();

        String saml2ResponseString = new String(Base64Support.decode(request.getParameter(
                Constants.HTTP_POST_PARAM_SAML_RESPONSE)), StandardCharsets.UTF_8);

        Optional<XMLObject> xmlObject = SSOUtils.unmarshall(saml2ResponseString);
        if (!xmlObject.isPresent()) {
            return;
        }

        Response saml2Response = (Response) xmlObject.get();
        saml2SSO.setResponseString(saml2ResponseString);

        Assertion assertion = null;
        if (contextConfiguration.isAssertionEncryptionEnabled()) {
            List<EncryptedAssertion> encryptedAssertions = saml2Response.getEncryptedAssertions();
            EncryptedAssertion encryptedAssertion;
            if (!((encryptedAssertions == null) || (encryptedAssertions.isEmpty()))) {
                encryptedAssertion = encryptedAssertions
                        .stream()
                        .findFirst()
                        .orElse(null);
                try {
                    assertion = SSOUtils.decryptAssertion(SSOX509Credential.getInstance(), encryptedAssertion);
                } catch (Exception e) {
                    throw new SSOException("Unable to decrypt the SAML 2.0 Assertion");
                }
            }
        } else {
            List<Assertion> assertions = saml2Response.getAssertions();
            if (!((assertions == null) || (assertions.isEmpty()))) {
                assertion = assertions
                        .stream()
                        .findFirst()
                        .orElse(null);
            }
        }

        if (assertion == null) {
            if (isNoPassive(saml2Response)) {
                Log containerLog = request.getHost().getLogger();
                if (containerLog.isDebugEnabled()) {
                    containerLog.debug("Cannot authenticate in passive mode");
                }
                return;
            }
            throw new SSOException("SAML 2.0 Assertion not found in the Response");
        }

        String idPEntityIdValue = assertion.getIssuer().getValue();
        if ((idPEntityIdValue == null) || (idPEntityIdValue.isEmpty())) {
            throw new SSOException("SAML 2.0 Response does not contain an Issuer value");
        } else if (!idPEntityIdValue.equals(serverConfiguration.getIdpEntityId())) {
            throw new SSOException("SAML 2.0 Response Issuer verification failed");
        }

        //  gets the subject name from the Response Object and forward it to login_action.jsp
        String subject = null;
        if ((assertion.getSubject() != null) && (assertion.getSubject().getNameID() != null)) {
            subject = assertion.getSubject().getNameID().getValue();
        }
        if (subject == null) {
            throw new SSOException("SAML 2.0 Response does not contain the name of the subject");
        }

        //  sets the subject in the session bean
        saml2SSO.setSubjectId(subject);

        //  validates the audience restriction
        validateAudienceRestriction(assertion);

        //  validates the signature
        validateSignature(saml2Response, assertion);

        //  marshalling SAML 2.0 assertion after signature validation due to an issue in OpenSAML
        saml2SSO.setAssertionString(SSOUtils.marshall(assertion));

        saml2SSO.setSubjectAttributes(SSOUtils.getAssertionStatements(assertion));

        //  for removing the session when the single-logout request made by the service provider itself
        if (contextConfiguration.isSLOEnabled()) {
            Optional<AuthnStatement> authnStatement = assertion.getAuthnStatements()
                    .stream()
                    .findFirst();
            String sessionId = null;
            if (authnStatement.isPresent()) {
                sessionId = authnStatement.get().getSessionIndex();
            }
            if (sessionId == null) {
                throw new SSOException("Single Logout is enabled but IdP Session ID not found in SAML 2.0 Assertion");
            }
            saml2SSO.setSessionIndex(sessionId);
            session.setSAML2SSO(saml2SSO);
            request.getSession().setAttribute(Constants.LOGGED_IN_SESSION, gson.toJson(session));
            SSOAgentSessionManager.addAuthenticatedSession(request.getSession(false));
        }
    }

    /**
     * Performs single-logout (SLO) function based on the HTTP servlet request.
     *
     * @param request the HTTP servlet request
     * @throws SSOException if the SAML 2.0 Single Logout Request/Response is invalid
     */
    private void performSingleLogout(Request request) throws SSOException {
        XMLObject saml2Object = null;

        if (request.getParameter(Constants.HTTP_POST_PARAM_SAML_REQUEST) != null) {
            Optional<XMLObject> xmlObject = SSOUtils.unmarshall(new String(Base64Support.decode(request.getParameter(
                    Constants.HTTP_POST_PARAM_SAML_REQUEST)), StandardCharsets.UTF_8));
            if (xmlObject.isPresent()) {
                saml2Object = xmlObject.get();
            }
        }
        if (saml2Object == null) {
            Optional<XMLObject> xmlObject = SSOUtils.unmarshall(new String(Base64Support.decode(request.getParameter(
                    Constants.HTTP_POST_PARAM_SAML_RESPONSE)), StandardCharsets.UTF_8));
            if (xmlObject.isPresent()) {
                saml2Object = xmlObject.get();
            }
        }

        if (saml2Object instanceof LogoutResponse) {
            Optional.ofNullable(request.getSession(false))
                    .ifPresent(session -> {
                        //  handles the SAML 2.0 Logout Response for the Logout Request initiating service provider
                        Set<HttpSession> sessions = SSOAgentSessionManager.getAllInvalidatableSessions(session);
                        sessions
                                .stream()
                                .forEach(httpSession -> {
                                    try {
                                        httpSession.invalidate();
                                    } catch (IllegalStateException ignore) {
                                        Log containerLog = request.getHost().getLogger();
                                        if (containerLog.isDebugEnabled()) {
                                            containerLog.debug("Ignoring exception : ", ignore);
                                        }
                                    }
                                });
                    });
        } else if (saml2Object instanceof LogoutRequest) {
            //  handles the back channel SAML 2.0 Logout Requests for the rest of the service providers other than
            //  the Logout Request initiating Service Provider
            LogoutRequest logoutRequest = (LogoutRequest) saml2Object;
            logoutRequest.getSessionIndexes()
                    .stream()
                    .findFirst()
                    .ifPresent(
                            index -> SSOAgentSessionManager.getAllInvalidatableSessions(index.getSessionIndex())
                                    .stream()
                                    .forEach(HttpSession::invalidate));
        } else {
            throw new SSOException("Invalid SAML 2.0 Single Logout Request/Response.");
        }
    }

    /**
     * Returns true if the identity provider cannot authenticate the principal passively, as requested, else false.
     *
     * @param response the SAML 2.0 Response to be evaluated
     * @return true if the identity provider cannot authenticate the principal passively, as requested, else false
     */
    private boolean isNoPassive(Response response) {
        return (response.getStatus() != null) &&
                (response.getStatus().getStatusCode() != null) &&
                (response.getStatus().getStatusCode().getValue().equals(StatusCode.RESPONDER)) &&
                (response.getStatus().getStatusCode().getStatusCode() != null) &&
                (response.getStatus().getStatusCode().getStatusCode().getValue().equals(StatusCode.NO_PASSIVE));
    }

    /**
     * Validates the SAML 2.0 Audience Restrictions set in the specified SAML 2.0 Assertion.
     *
     * @param assertion the SAML 2.0 Assertion in which Audience Restrictions' validity is checked for
     * @throws SSOException if the Audience Restriction validation fails
     */
    private void validateAudienceRestriction(Assertion assertion) throws SSOException {
        if (assertion == null) {
            return;
        }

        Conditions conditions = assertion.getConditions();
        if (conditions == null) {
            throw new SSOException("SAML 2.0 Response doesn't contain Conditions");
        }

        List<AudienceRestriction> audienceRestrictions = conditions.getAudienceRestrictions();
        if ((audienceRestrictions == null) || (audienceRestrictions.isEmpty())) {
            throw new SSOException("SAML 2.0 Response doesn't contain AudienceRestrictions");
        }

        Stream<AudienceRestriction> audienceExistingStream = audienceRestrictions
                .stream()
                .filter(audienceRestriction ->
                        (((audienceRestriction.getAudiences() != null) && (!audienceRestriction.getAudiences().
                                isEmpty()))) && (audienceRestriction.getAudiences()
                                .stream()
                                .filter(audience -> contextConfiguration.getIssuerId().equals(audience.
                                        getAudienceURI())))
                                .count() > 0);

        if (audienceExistingStream.count() == 0) {
            throw new SSOException("SAML 2.0 Assertion Audience Restriction validation failed");
        }
    }

    /**
     * Validates the XML Digital Signature of specified SAML 2.0 based Response and Assertion.
     *
     * @param response  the SAML 2.0 based Response whose XML Digital Signature is to be validated
     * @param assertion the SAML 2.0 based Assertion whose XML Digital Signature is to be validated
     * @throws SSOException if an error occurs during the signature validation
     */
    private void validateSignature(Response response, Assertion assertion) throws SSOException {
        if (DataHolder.getInstance().getObject() != null) {
            //  custom implementation of signature validation
            SignatureValidator signatureValidatorUtility =
                    (SignatureValidator) DataHolder.getInstance().getObject();
            signatureValidatorUtility.validateSignature(response, assertion,
                    contextConfiguration.isResponseSigningEnabled(), contextConfiguration.isAssertionSigningEnabled());
        } else {
            SSOX509Credential ssoX509Credential = SSOX509Credential.getInstance();
            //  if custom implementation not found, execute the default implementation
            if (contextConfiguration.isResponseSigningEnabled()) {
                if (response.getSignature() == null) {
                    throw new SSOException("SAML 2.0 Response signing is enabled, but signature element not found " +
                            "in SAML 2.0 Response element");
                } else {
                    try {
                        org.opensaml.xmlsec.signature.support.SignatureValidator.validate(response.getSignature(),
                                new X509CredentialImplementation(ssoX509Credential.getEntityCertificate()));
                    } catch (SignatureException e) {
                        throw new SSOException("Signature validation failed for SAML 2.0 Response", e);
                    }
                }
            }
            if (contextConfiguration.isAssertionSigningEnabled()) {
                if (assertion.getSignature() == null) {
                    throw new SSOException("SAML 2.0 Assertion signing is enabled, but signature element not found in" +
                            " SAML 2.0 Assertion element");
                } else {
                    try {
                        org.opensaml.xmlsec.signature.support.SignatureValidator.validate(assertion.getSignature(),
                                new X509CredentialImplementation(ssoX509Credential.getEntityCertificate()));
                    } catch (SignatureException e) {
                        throw new SSOException("Signature validation failed for SAML 2.0 Assertion", e);
                    }
                }
            }
        }
    }
}
