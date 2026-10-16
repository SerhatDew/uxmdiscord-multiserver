package com.uxplima.discordsync.multiserver.database;

import com.uxplima.discordsync.models.LinkedAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NetworkLinkStoreTest {
    private static final String DB_URL = "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private Connection keepAlive;
    private NetworkLinkStore store;

    @BeforeEach
    void setUp() throws Exception {
        this.keepAlive = DriverManager.getConnection(DB_URL);

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_linked_accounts ("
                    + "minecraft_uuid VARCHAR(36) PRIMARY KEY, "
                    + "minecraft_username VARCHAR(16) NOT NULL, "
                    + "discord_id VARCHAR(20) NOT NULL UNIQUE, "
                    + "discord_username VARCHAR(100) NOT NULL, "
                    + "server VARCHAR(50) NOT NULL, "
                    + "linked_at BIGINT NOT NULL)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_link_events ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "minecraft_uuid VARCHAR(36) NOT NULL, "
                    + "discord_id VARCHAR(20) NOT NULL, "
                    + "server VARCHAR(50) NOT NULL, "
                    + "action VARCHAR(20) NOT NULL, "
                    + "timestamp BIGINT NOT NULL)");
        }

        this.store = new NetworkLinkStore(() -> DriverManager.getConnection(DB_URL), true);
        this.store.createTables();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.keepAlive != null && !this.keepAlive.isClosed()) {
            this.keepAlive.close();
        }
    }

    @Test
    void testCreateAndRedeemCode() throws Exception {
        UUID uuid = UUID.randomUUID();
        String username = "TestPlayer";
        String server = "leaf-1";
        String code = "123456";
        long expiresAt = System.currentTimeMillis() + 60000L;

        boolean created = this.store.createCode(uuid, username, server, code, expiresAt);
        assertTrue(created);

        boolean duplicate = this.store.createCode(UUID.randomUUID(), "Other", server, code, expiresAt);
        assertFalse(duplicate);

        LinkedAccount account = this.store.redeem(code, "987654321", "TestDiscordUser");
        assertNotNull(account);
        assertEquals(uuid, account.getMinecraftUUID());
        assertEquals("TestPlayer", account.getMinecraftUsername());
        assertEquals("987654321", account.getDiscordId());
        assertEquals("leaf-1", account.getServer());

        LinkedAccount secondRedeem = this.store.redeem(code, "987654321", "TestDiscordUser");
        assertNull(secondRedeem);

        List<NetworkLinkStore.Delivery> pending = this.store.pending(server);
        assertEquals(1, pending.size());
        assertEquals(code, pending.get(0).code());
        assertFalse(pending.get(0).previouslyLinked());

        List<NetworkLinkStore.Delivery> otherPending = this.store.pending("leaf-2");
        assertTrue(otherPending.isEmpty());

        boolean claimed = this.store.claim(code, server);
        assertTrue(claimed);

        assertFalse(this.store.claim(code, server));
        assertTrue(this.store.pending(server).isEmpty());
    }

    @Test
    void testExpiredCodeRedeemFails() throws Exception {
        UUID uuid = UUID.randomUUID();
        String code = "999888";
        long expiredTime = System.currentTimeMillis() - 5000L;

        this.store.createCode(uuid, "LatePlayer", "survival", code, expiredTime);

        LinkedAccount account = this.store.redeem(code, "111222333", "User");
        assertNull(account);
    }
}
