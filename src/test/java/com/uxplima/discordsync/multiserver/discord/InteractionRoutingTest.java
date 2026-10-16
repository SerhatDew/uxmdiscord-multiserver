package com.uxplima.discordsync.multiserver.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRoutingTest {

    @Test
    void testServerKeyConsistency() {
        String key1 = InteractionRouting.serverKey("leaf-1");
        String key2 = InteractionRouting.serverKey("leaf-1");
        String keyOther = InteractionRouting.serverKey("leaf-2");

        assertNotNull(key1);
        assertEquals(24, key1.length());
        assertEquals(key1, key2);
        assertNotEquals(key1, keyOther);
    }

    @Test
    void testIdGeneration() {
        String server = "leaf-1";
        String serverKey = InteractionRouting.serverKey(server);

        // When multi-server is enabled, link_account remains standard shared ID
        assertEquals("link_account", InteractionRouting.id(server, true, "link_account"));
        assertEquals("link_modal", InteractionRouting.id(server, true, "link_modal"));

        // 2FA button IDs get tagged with server key
        assertEquals("2fa_approve_123456:" + serverKey,
                InteractionRouting.id(server, true, "2fa_approve_123456"));

        // When multi-server is disabled, legacy ID is kept verbatim
        assertEquals("2fa_approve_123456",
                InteractionRouting.id(server, false, "2fa_approve_123456"));
    }

    @Test
    void testOwnership() {
        String serverName = "leaf-1";

        // Coordinator owns link_account button
        assertTrue(InteractionRouting.owns(true, serverName, true, "link_account", "link_account"));
        // Secondary server does NOT own link_account button
        assertFalse(InteractionRouting.owns(false, "leaf-2", true, "link_account", "link_account"));

        // 2FA button with matching serverKey is owned by that server
        String server1Key = InteractionRouting.serverKey("leaf-1");
        String server2Key = InteractionRouting.serverKey("leaf-2");

        assertTrue(InteractionRouting.owns(false, "leaf-1", true, "2fa_approve_123456:" + server1Key, "2fa_approve_123456"));
        assertFalse(InteractionRouting.owns(false, "leaf-1", true, "2fa_approve_123456:" + server2Key, "2fa_approve_123456"));
    }

    @Test
    void testExtractTwoFactorCode() {
        String server1Key = InteractionRouting.serverKey("leaf-1");
        String server2Key = InteractionRouting.serverKey("leaf-2");

        String buttonId = "2fa_approve_654321:" + server1Key;

        // Server 1 should extract the code successfully
        String codeServer1 = InteractionRouting.extractTwoFactorCode(false, "leaf-1", buttonId, "2fa_approve_");
        assertEquals("654321", codeServer1);

        // Server 2 should return null (does not belong to server 2)
        String codeServer2 = InteractionRouting.extractTwoFactorCode(false, "leaf-2", buttonId, "2fa_approve_");
        assertNull(codeServer2);

        // Untagged legacy button
        String legacyId = "2fa_approve_111222";
        // Coordinator can extract legacy
        assertEquals("111222", InteractionRouting.extractTwoFactorCode(true, "main", legacyId, "2fa_approve_"));
        // Non-coordinator returns null for legacy
        assertNull(InteractionRouting.extractTwoFactorCode(false, "leaf-2", legacyId, "2fa_approve_"));
    }
}
