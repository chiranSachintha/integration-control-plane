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

package org.wso2.ei.dashboard.core.commons.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes security-relevant events (login, logout, user/role/config/artifact changes) to
 * the dedicated AUDIT_LOG logger, which is routed to audit.log via log4j2 configuration.
 *
 * Log format (mirrors MI AuditLogger):
 *   Initiator : &lt;user&gt; | Action : &lt;action&gt; | Target : &lt;target&gt; | Data : { ... } | Result : Success|Failure
 */
public final class AuditLogger {

    private static final Logger AUDIT_LOG = LogManager.getLogger("AUDIT_LOG");
    private static final String UNKNOWN = "unknown";
    private static final String NA = "N/A";
    private static final String SUCCESS = "Success";
    private static final String FAILURE = "Failure";
    private static final int MAX_FIELD_LENGTH = 256;
    private static final int MAX_DATA_LENGTH = 2048;
    private static final String TRUNCATION_SUFFIX = "...";

    private AuditLogger() {}

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    public static void logLogin(String username, boolean success) {
        write(actor(username), "Login", NA,
                "\"username\" : \"" + actor(username) + "\"",
                success ? SUCCESS : FAILURE);
    }

    public static void logLogout(String username) {
        write(actor(username), "Logout", NA,
                "\"username\" : \"" + actor(username) + "\"",
                SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Authorization
    // -------------------------------------------------------------------------

    /**
     * Records an authenticated user being refused access to a resource. Invalid or expired
     * sessions (401) are deliberately not recorded here: they are routine on session expiry and
     * would drown the genuine denials, and failed logins are already covered by
     * {@link #logLogin(String, boolean)}.
     */
    public static void logAccessDenied(String performedBy, String method, String resource, String reason) {
        write(actor(performedBy), "Access Denied", resource,
                "\"method\" : \"" + method + "\""
                        + ", \"resource\" : \"" + resource + "\""
                        + ", \"reason\" : \"" + reason + "\"",
                FAILURE);
    }

    // -------------------------------------------------------------------------
    // User management
    // -------------------------------------------------------------------------

    public static void logUserAdd(String performedBy, String targetUser) {
        write(actor(performedBy), "Add User", targetUser,
                "\"targetUser\" : \"" + targetUser + "\"",
                SUCCESS);
    }

    public static void logUserDelete(String performedBy, String targetUser) {
        write(actor(performedBy), "Delete User", targetUser,
                "\"targetUser\" : \"" + targetUser + "\"",
                SUCCESS);
    }

    public static void logUserPasswordUpdate(String performedBy, String targetUser) {
        write(actor(performedBy), "Update User Password", targetUser,
                "\"targetUser\" : \"" + targetUser + "\"",
                SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Role management
    // -------------------------------------------------------------------------

    public static void logRoleAdd(String performedBy, String roleName) {
        write(actor(performedBy), "Add Role", roleName,
                "\"roleName\" : \"" + roleName + "\"",
                SUCCESS);
    }

    public static void logRoleUpdate(String performedBy, String userId, String addedRoles, String removedRoles) {
        write(actor(performedBy), "Update User Roles", userId,
                "\"userId\" : \"" + userId + "\""
                        + ", \"addedRoles\" : " + addedRoles
                        + ", \"removedRoles\" : " + removedRoles,
                SUCCESS);
    }

    public static void logRoleDelete(String performedBy, String roleName) {
        write(actor(performedBy), "Delete Role", roleName,
                "\"roleName\" : \"" + roleName + "\"",
                SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Log configuration
    // -------------------------------------------------------------------------

    public static void logLogConfigAdd(String performedBy, String loggerName, String level) {
        write(actor(performedBy), "Add Log Config", loggerName,
                "\"loggerName\" : \"" + loggerName + "\", \"level\" : \"" + level + "\"",
                SUCCESS);
    }

    public static void logLogConfigUpdate(String performedBy, String loggerName, String level) {
        write(actor(performedBy), "Update Log Config", loggerName,
                "\"loggerName\" : \"" + loggerName + "\", \"level\" : \"" + level + "\"",
                SUCCESS);
    }

    public static void logLogConfigDelete(String performedBy, String loggerName) {
        write(actor(performedBy), "Delete Log Config", loggerName,
                "\"loggerName\" : \"" + loggerName + "\"",
                SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Artifact updates (proxy services, APIs, endpoints, etc.)
    // -------------------------------------------------------------------------

    public static void logArtifactUpdate(String performedBy, String artifactType,
                                          String artifactName, String nodeId,
                                          String groupId, String action) {
        write(actor(performedBy), "Update " + artifactType, artifactName,
                "\"artifactName\" : \"" + artifactName + "\""
                        + ", \"groupId\" : \"" + groupId + "\""
                        + ", \"nodeId\" : \"" + (nodeId != null ? nodeId : "all") + "\""
                        + ", \"action\" : \"" + action + "\"",
                SUCCESS);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void write(String initiator, String action, String target, String data, String result) {
        AUDIT_LOG.info("Initiator : " + clean(initiator, MAX_FIELD_LENGTH)
                + " | Action : " + clean(action, MAX_FIELD_LENGTH)
                + " | Target : " + clean(target, MAX_FIELD_LENGTH)
                + " | Data : { " + clean(data, MAX_DATA_LENGTH) + " }"
                + " | Result : " + result);
    }

    /**
     * Keeps a single event on a single line. Values such as a denied request path are supplied by
     * the caller of the REST API, so a raw line break would let one request forge additional audit
     * entries. Over-long values are truncated to bound the damage of a padded field, with the
     * ellipsis counted inside the limit so the emitted value never exceeds it.
     */
    private static String clean(String value, int maxLength) {
        if (value == null) {
            return NA;
        }
        String sanitized = value.replaceAll("[\\r\\n]", " ");
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        // max() keeps the length non-negative: this runs while writing an audit entry, where an
        // exception would both lose the record and surface as a 500 to the caller.
        int keep = Math.max(0, maxLength - TRUNCATION_SUFFIX.length());
        return sanitized.substring(0, keep) + TRUNCATION_SUFFIX;
    }

    private static String actor(String performedBy) {
        return performedBy != null ? performedBy : UNKNOWN;
    }
}
