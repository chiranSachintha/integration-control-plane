/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.ei.dashboard.core.commons.auth;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.glassfish.jersey.server.ContainerRequest;
import org.wso2.ei.dashboard.core.commons.audit.AuditLogger;
import org.wso2.micro.integrator.dashboard.utils.SSOConfig;
import org.wso2.micro.integrator.dashboard.utils.SSOConstants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.wso2.ei.dashboard.core.commons.Constants.TOKEN_CACHE_TIMEOUT;

import javax.annotation.Priority;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import static org.wso2.ei.dashboard.core.commons.Constants.JWT_COOKIE;
import static org.wso2.ei.dashboard.core.commons.auth.JwtUtil.isJWTToken;

/**
 * Authenticate the request coming to the rest api.
 * <p>
 * This filter is registered globally, so every resource served by the dashboard rest api is authenticated
 * unless its root resource is listed in {@link #UNAUTHENTICATED_PATHS}. A newly added resource is therefore
 * authenticated by default, and exposing one anonymously is a deliberate change to that list rather than an
 * omission at the resource class.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {
    private static final String AUTHENTICATION_SCHEME = "Bearer";
    private static final List<String> ADMIN_ONLY_PATHS = Arrays.asList("/log-configs", "/users", "/roles");
    // Root resources reachable without a dashboard session. Anything not listed here requires authentication.
    //   login     - credential submission and the CSRF token used to submit it
    //   logout    - session teardown, which cannot require the session it is tearing down
    //   heartbeat - the endpoint managed MI nodes call to register themselves with the dashboard
    //   healthz   - liveness probe, consumed by deployment tooling that holds no dashboard session
    private static final List<String> UNAUTHENTICATED_PATHS =
            Arrays.asList("login", "logout", "heartbeat", "healthz");
    private static final String MAKE_NON_ADMIN_USERS_READ_ONLY = "make_non_admin_users_read_only";
    private static final String ACTION_PERFORMED_BY = "performedBy";
    // Tracks SSO Bearer tokens that have already produced a login audit entry.
    // expireAfterAccess: an active session keeps the entry alive; eviction only happens on inactivity,
    // so a long-lived token does not generate repeated Login entries while it is in continuous use.
    private static final Cache<String, Boolean> SSO_LOGIN_AUDITED =
            CacheBuilder.newBuilder().expireAfterAccess(TOKEN_CACHE_TIMEOUT, TimeUnit.MINUTES).build();

    @Context
    private HttpServletRequest servletRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (isUnauthenticatedResource(requestContext)) {
            return;
        }
        String httpMethod = requestContext.getMethod();
        String token = extractToken(requestContext);
        SecurityHandler securityHandler = getSecurityHandler(requestContext, token);
        if (token == null || securityHandler == null) {
            abortWithUnauthorized(requestContext);
            return;
        }

        SSOConfig config = getSsoConfig();
        try {
            if (!securityHandler.isAuthenticated(config, token)) {
                // The token is missing, expired or otherwise invalid: the session is dead.
                abortWithUnauthorized(requestContext);
                return;
            }
        } catch (TokenValidationException e) {
            // The token could not be validated because of a server/IdP side failure (e.g. the JWKS or introspection
            // endpoint is unreachable or untrusted). The session may well be valid, so do not report it as a 401.
            abortWithServiceUnavailable(requestContext);
            return;
        }

        boolean makeNonAdminUsersReadOnly = Boolean.parseBoolean(System.getProperty(MAKE_NON_ADMIN_USERS_READ_ONLY));
        if (isAdminResource(requestContext) && !securityHandler.isAuthorized(config, token)) {
            // The user is authenticated but not permitted to access this resource.
            abortWithForbidden(requestContext);
            return;
        }
        if (!"GET".equalsIgnoreCase(httpMethod) && makeNonAdminUsersReadOnly
                && !securityHandler.isAuthorized(config, token)) {
            // For non-admin resources, request except GET are blocked
            // if the 'makeNonAdminUsersReadOnly' is set to 'true'
            abortWithForbidden(requestContext);
            return;
        }
        String performedBy = securityHandler.getSubject(config, token);
        requestContext.setProperty(ACTION_PERFORMED_BY, performedBy);

        // Log SSO logins on first use of each Bearer token (cookie-based = local login, already audited in LoginDelegate)
        if (isTokenBasedAuthentication(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION))
                && SSO_LOGIN_AUDITED.getIfPresent(token) == null) {
            SSO_LOGIN_AUDITED.put(token, Boolean.TRUE);
            AuditLogger.logLogin(performedBy, true);
        }
    }

    private SSOConfig getSsoConfig() {
        Object config = this.servletRequest.getServletContext().getAttribute(SSOConstants.CONFIG_BEAN_NAME);
        return config instanceof SSOConfig ? (SSOConfig) config : null;
    }

    private static boolean isUnauthenticatedResource(ContainerRequestContext requestContext) {
        String rootResource = getRootResource(requestContext);
        return UNAUTHENTICATED_PATHS.contains(rootResource);
    }

    /**
     * Returns the first segment of the request path, which identifies the root resource being addressed.
     * <p>
     * Matched on the whole segment rather than as a prefix, so that a resource whose name merely starts with
     * an unauthenticated one (for example a future "login-attempts" alongside "login") is not exempted by
     * accident.
     */
    private static String getRootResource(ContainerRequestContext requestContext) {
        // Relative to the rest api base path and carries no leading slash, e.g. "groups/g1/apis" or "healthz".
        String path = ((ContainerRequest) requestContext).getPath(false);
        int separator = path.indexOf('/');
        return separator < 0 ? path : path.substring(0, separator);
    }

    private static boolean isAdminResource(ContainerRequestContext requestContext) {
        String path = ((ContainerRequest) requestContext).getPath(false);
        int lastSeparator = path.lastIndexOf("/");
        // A single segment path carries no separator to split on, so qualify it to match how ADMIN_ONLY_PATHS
        // is written. Without this the substring below is called with -1 on such paths.
        String resource = lastSeparator < 0 ? "/" + path : path.substring(lastSeparator);
        return ADMIN_ONLY_PATHS.contains(resource);
    }

    private void abortWithUnauthorized(ContainerRequestContext requestContext) {
        abortWith(requestContext, Response.Status.UNAUTHORIZED, "Unauthorized");
    }

    private void abortWithForbidden(ContainerRequestContext requestContext) {
        abortWith(requestContext, Response.Status.FORBIDDEN, "Forbidden");
    }

    private void abortWithServiceUnavailable(ContainerRequestContext requestContext) {
        abortWith(requestContext, Response.Status.SERVICE_UNAVAILABLE,
                "Unable to validate the session with the identity provider");
    }

    private void abortWith(ContainerRequestContext requestContext, Response.Status status, String message) {
        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("message", message);
        Response response = Response.status(status).entity(responseBody)
                .header("content-type", "application/json").build();
        requestContext.abortWith(response);
    }

    private String extractToken(ContainerRequestContext requestContext) {
        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (isTokenBasedAuthentication(authorizationHeader)) {
            return authorizationHeader.substring(AUTHENTICATION_SCHEME.length()).trim();
        }
        Map<String, Cookie> cookies = requestContext.getCookies();
        if (isCookieBasedAuthentication(cookies)) {
            return cookies.get(JWT_COOKIE).getValue();
        }
        return null;
    }

    private boolean isTokenBasedAuthentication(String authorizationHeader) {
        return authorizationHeader != null && authorizationHeader.toLowerCase()
                .startsWith(AUTHENTICATION_SCHEME.toLowerCase() + " ");
    }

    private boolean isCookieBasedAuthentication(Map<String, Cookie> cookies) {
        return cookies != null && cookies.get(JWT_COOKIE) != null;
    }

    private SecurityHandler getSecurityHandler(ContainerRequestContext requestContext, String token) {
        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (isTokenBasedAuthentication(authorizationHeader)) {
            return getSSOSecurityHandler(token);
        }
        if (isCookieBasedAuthentication(requestContext.getCookies())) {
            return new InMemorySecurityHandler();
        }
        return null;
    }

    private static SecurityHandler getSSOSecurityHandler(String token) {
        if (JwtUtil.isJWTToken(token)) {
            return new JWTSecurityHandler();
        }
        return new OpaqueTokenSecurityHandler();
    }
}
