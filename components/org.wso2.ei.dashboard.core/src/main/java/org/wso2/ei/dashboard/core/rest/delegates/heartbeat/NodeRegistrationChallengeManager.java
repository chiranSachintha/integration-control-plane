/*
 * Copyright (c) 2026, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
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

package org.wso2.ei.dashboard.core.rest.delegates.heartbeat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.wso2.ei.dashboard.core.commons.Constants;
import org.wso2.ei.dashboard.core.commons.utils.ManagementApiUtils;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gates first-time node registration behind proof that the caller already holds the shared mi_super_admin
 * password, so that {@code mgtApiUrl} from an unauthenticated heartbeat is never trusted with that password
 * unless the caller demonstrates it independently.
 * <p>
 * Flow: the first heartbeat for an unknown (groupId, nodeId) gets back a random challenge instead of being
 * registered. The node signs the challenge together with (groupId, nodeId, mgtApiUrl) using its own copy of
 * the shared password and resends the heartbeat with that signature attached; only a signature that matches
 * what ICP computes from its own stored password, over that same tuple, causes registration (and the outbound
 * call to mgtApiUrl) to proceed. Binding mgtApiUrl into the signed payload matters: without it, a signature
 * observed on the wire for a legitimate node could be replayed verbatim against the same (groupId, nodeId)
 * with a different, attacker-chosen mgtApiUrl.
 */
public class NodeRegistrationChallengeManager {

    private static final Logger logger = LogManager.getLogger(NodeRegistrationChallengeManager.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // groupId/nodeId are unauthenticated and attacker-controlled, so without a cap a flood of distinct pairs
    // could grow this cache unboundedly for up to the full expiry window; this caps memory use under load.
    private static final long MAX_PENDING_CHALLENGES = 10_000L;

    // Keyed by groupId/nodeId, single-use, and short-lived: a challenge is only ever meant to be answered by
    // the very next request in the same registration handshake.
    private static final Cache<String, String> PENDING_CHALLENGES = CacheBuilder.newBuilder()
            .maximumSize(MAX_PENDING_CHALLENGES)
            .expireAfterWrite(Constants.NODE_CHALLENGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private NodeRegistrationChallengeManager() {
    }

    /**
     * Issues a fresh challenge for this (groupId, nodeId) pair, replacing any earlier unanswered one.
     */
    public static String issueChallenge(String groupId, String nodeId) {
        byte[] nonce = new byte[32];
        SECURE_RANDOM.nextBytes(nonce);
        String challenge = Base64.getEncoder().encodeToString(nonce);
        PENDING_CHALLENGES.put(cacheKey(groupId, nodeId), challenge);
        return challenge;
    }

    /**
     * Verifies a claimed signature against the challenge previously issued for this node, over the exact
     * (groupId, nodeId, mgtApiUrl) tuple of the current request, using ICP's own stored copy of the shared
     * password. The pending challenge is atomically consumed on lookup, so a given challenge can only ever be
     * checked once - a second concurrent attempt with the same or a different signature both find nothing
     * pending and fail closed.
     */
    public static boolean verify(String groupId, String nodeId, String mgtApiUrl, String signedChallenge) {
        if (signedChallenge == null || signedChallenge.isEmpty()) {
            return false;
        }
        String challenge = PENDING_CHALLENGES.asMap().remove(cacheKey(groupId, nodeId));
        if (challenge == null) {
            // Expired, never issued, or already consumed by a previous concurrent attempt.
            return false;
        }
        String password = ManagementApiUtils.getConfiguredPassword();
        if (password == null || password.isEmpty()) {
            logger.error("mi_super_admin password is not configured; rejecting node registration challenge.");
            return false;
        }
        String expectedSignature = sign(canonicalMessage(challenge, groupId, nodeId, mgtApiUrl), password);
        if (expectedSignature == null) {
            return false;
        }
        return constantTimeEquals(expectedSignature, signedChallenge);
    }

    private static String sign(String message, String password) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Error occurred while signing node registration challenge.", e);
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // Length-prefixed so that (groupId="a:b", nodeId="c") and (groupId="a", nodeId="b:c") cannot collide on a
    // shared delimiter - both groupId and nodeId are attacker-controlled request fields.
    private static String cacheKey(String groupId, String nodeId) {
        return lengthPrefixed(groupId) + lengthPrefixed(nodeId);
    }

    // Binds the signature to the full registration attempt, not just the nonce: same length-prefixing
    // discipline as cacheKey() so no combination of these attacker-controlled fields can be rearranged to
    // produce the same signed bytes as a different, legitimately-signed combination.
    private static String canonicalMessage(String challenge, String groupId, String nodeId, String mgtApiUrl) {
        return lengthPrefixed(challenge) + lengthPrefixed(groupId) + lengthPrefixed(nodeId)
                + lengthPrefixed(mgtApiUrl == null ? "" : mgtApiUrl);
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }
}
