package com.uxplima.discordsync.multiserver.discord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable component ownership and routing across servers using the same bot token.
 */
public final class InteractionRouting {
    private InteractionRouting() {}

    public static String serverKey(String server) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(server.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 algorithm not available", error);
        }
    }

    public static String id(String serverName, boolean multiServerEnabled, String legacyId) {
        if (!multiServerEnabled) {
            return legacyId;
        }
        if (legacyId.equals("link_account") || legacyId.equals("link_modal")) {
            return legacyId;
        }
        return legacyId + ":" + serverKey(serverName);
    }

    public static boolean owns(boolean isCoordinator, String serverName, boolean multiServerEnabled, String customId, String legacyId) {
        if (!multiServerEnabled) {
            return legacyId.equals(customId);
        }
        if (legacyId.equals("link_account") || legacyId.equals("link_modal")) {
            return isCoordinator && customId != null && (customId.equals(legacyId) || customId.startsWith(legacyId + ":"));
        }
        String expected = legacyId + ":" + serverKey(serverName);
        return expected.equals(customId) || (legacyId.equals(customId) && isCoordinator);
    }

    public static String extractTwoFactorCode(boolean isCoordinator, String serverName, String componentId, String prefix) {
        if (componentId == null || !componentId.startsWith(prefix)) {
            return null;
        }
        String payload = componentId.substring(prefix.length());
        int separator = payload.indexOf(':');
        if (separator < 0) {
            // Legacy button without server key - handled only by coordinator
            return isCoordinator ? payload : null;
        }
        String code = payload.substring(0, separator);
        String key = payload.substring(separator + 1);
        return serverKey(serverName).equals(key) ? code : null;
    }
}
