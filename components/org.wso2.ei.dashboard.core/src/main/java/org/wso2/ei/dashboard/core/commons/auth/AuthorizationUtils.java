/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.ei.dashboard.core.commons.auth;

import org.wso2.ei.dashboard.core.commons.audit.AuditLogger;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Response;

/**
 * Resource level authorization checks.
 *
 * These back up the admin-only path check in {@link AuthenticationFilter}. That check derives the
 * verdict from the request URI, so a change to the API layout that stops it matching would fail
 * open silently. Asserting here on the verdict the filter recorded keeps the destructive
 * operations failing closed instead.
 */
public final class AuthorizationUtils {

    /**
     * Request property holding the username the request was authenticated as.
     */
    public static final String ACTION_PERFORMED_BY = "performedBy";

    /**
     * Request property holding the admin verdict computed by {@link AuthenticationFilter}. Absent
     * when the filter did not need to evaluate authorization for the request.
     */
    public static final String CALLER_IS_ADMIN = "isAdmin";

    private AuthorizationUtils() {
    }

    /**
     * Rejects the request with a 403 unless the caller was confirmed to be an admin.
     *
     * @param requestContext context of the request being served
     * @param action         description of the operation, recorded in the audit log on denial
     */
    public static void requireAdmin(ContainerRequestContext requestContext, String action) {
        if (Boolean.TRUE.equals(requestContext.getProperty(CALLER_IS_ADMIN))) {
            return;
        }
        // An absent property lands here too: it means authorization was never evaluated for this
        // route, so the caller is unverified rather than known to be non-admin. Deny either way.
        AuditLogger.logAccessDenied((String) requestContext.getProperty(ACTION_PERFORMED_BY),
                requestContext.getMethod(), requestContext.getUriInfo().getPath(),
                "Admin only operation: " + action);
        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("message", "Forbidden");
        throw new ForbiddenException(Response.status(Response.Status.FORBIDDEN).entity(responseBody)
                .header("content-type", "application/json").build());
    }
}
