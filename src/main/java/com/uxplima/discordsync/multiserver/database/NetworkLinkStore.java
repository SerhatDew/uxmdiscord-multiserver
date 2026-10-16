package com.uxplima.discordsync.multiserver.database;

import com.uxplima.discordsync.models.LinkedAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared verification codes and durable delivery of link effects to the origin server.
 */
public final class NetworkLinkStore {
    private static final long DEFAULT_CLAIM_LEASE_MILLIS = 30000L;
    private final ConnectionSupplier connectionSupplier;
    private final boolean globalRewards;
    private final String claimOwner = UUID.randomUUID().toString();
    private final long claimLeaseMillis;

    public record Delivery(String code, LinkedAccount account, boolean previouslyLinked) {}

    public NetworkLinkStore(ConnectionSupplier connectionSupplier, boolean globalRewards) {
        this(connectionSupplier, globalRewards, DEFAULT_CLAIM_LEASE_MILLIS);
    }

    NetworkLinkStore(ConnectionSupplier connectionSupplier, boolean globalRewards, long claimLeaseMillis) {
        this.connectionSupplier = connectionSupplier;
        this.globalRewards = globalRewards;
        this.claimLeaseMillis = claimLeaseMillis;
    }

    public void createTables() throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_network_link_codes ("
                    + "code VARCHAR(32) PRIMARY KEY, "
                    + "minecraft_uuid VARCHAR(36) NOT NULL, "
                    + "minecraft_username VARCHAR(16) NOT NULL, "
                    + "server VARCHAR(50) NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "discord_id VARCHAR(20), "
                    + "discord_username VARCHAR(100), "
                    + "linked_at BIGINT NOT NULL DEFAULT 0, "
                    + "delivered SMALLINT NOT NULL DEFAULT 0, "
                    + "previously_linked SMALLINT NOT NULL DEFAULT 0, "
                    + "delivery_owner VARCHAR(36), "
                    + "delivery_claim_token VARCHAR(36), "
                    + "delivery_claim_expires_at BIGINT NOT NULL DEFAULT 0, "
                    + "INDEX idx_netlink_server (server), "
                    + "INDEX idx_netlink_uuid (minecraft_uuid), "
                    + "INDEX idx_netlink_expires (expires_at))");
        } catch (SQLException e) {
            // Some databases might fail with inline INDEX syntax, fallback without inline indices.
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("syntax")) {
                try (Connection connection = this.connectionSupplier.get();
                     Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_network_link_codes ("
                            + "code VARCHAR(32) PRIMARY KEY, "
                            + "minecraft_uuid VARCHAR(36) NOT NULL, "
                            + "minecraft_username VARCHAR(16) NOT NULL, "
                            + "server VARCHAR(50) NOT NULL, "
                            + "expires_at BIGINT NOT NULL, "
                            + "discord_id VARCHAR(20), "
                            + "discord_username VARCHAR(100), "
                            + "linked_at BIGINT NOT NULL DEFAULT 0, "
                            + "delivered SMALLINT NOT NULL DEFAULT 0, "
                            + "previously_linked SMALLINT NOT NULL DEFAULT 0, "
                            + "delivery_owner VARCHAR(36), "
                            + "delivery_claim_token VARCHAR(36), "
                            + "delivery_claim_expires_at BIGINT NOT NULL DEFAULT 0)");
                }
            } else {
                throw e;
            }
        }
        this.ensureColumn("delivery_owner VARCHAR(36)");
        this.ensureColumn("delivery_claim_token VARCHAR(36)");
        this.ensureColumn("delivery_claim_expires_at BIGINT NOT NULL DEFAULT 0");
    }

    private void ensureColumn(String definition) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE uxm_network_link_codes ADD COLUMN " + definition);
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || (!message.toLowerCase().contains("duplicate column")
                    && !message.toLowerCase().contains("already exists"))) {
                throw error;
            }
        }
    }

    public boolean createCode(UUID uuid, String username, String server, String code, long expiresAt) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO uxm_network_link_codes (code, minecraft_uuid, minecraft_username, server, expires_at) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, code);
            insert.setString(2, uuid.toString());
            insert.setString(3, username);
            insert.setString(4, server);
            insert.setLong(5, expiresAt);
            return insert.executeUpdate() == 1;
        } catch (SQLException error) {
            if (isDuplicate(error)) {
                return false;
            }
            throw error;
        }
    }

    public LinkedAccount redeem(String code, String discordId, String discordName) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = this.connectionSupplier.get()) {
            connection.setAutoCommit(false);
            try {
                LinkedAccount account;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT minecraft_uuid, minecraft_username, server FROM uxm_network_link_codes "
                                + "WHERE code = ? AND linked_at = 0 AND expires_at > ? FOR UPDATE")) {
                    select.setString(1, code);
                    select.setLong(2, now);
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) {
                            connection.rollback();
                            return null;
                        }
                        account = new LinkedAccount(
                                UUID.fromString(row.getString("minecraft_uuid")),
                                row.getString("minecraft_username"),
                                discordId,
                                discordName,
                                row.getString("server"),
                                now
                        );
                    }
                }

                boolean previous;
                String historySql = "SELECT COUNT(*) FROM uxm_link_events WHERE minecraft_uuid = ? AND action = 'LINK'"
                        + (this.globalRewards ? "" : " AND server = ?");
                try (PreparedStatement history = connection.prepareStatement(historySql)) {
                    history.setString(1, account.getMinecraftUUID().toString());
                    if (!this.globalRewards) {
                        history.setString(2, account.getServer());
                    }
                    try (ResultSet row = history.executeQuery()) {
                        row.next();
                        previous = row.getInt(1) > 0;
                    }
                }

                // Delete existing linked account for this UUID or Discord ID if any
                try (PreparedStatement deleteExisting = connection.prepareStatement(
                        "DELETE FROM uxm_linked_accounts WHERE minecraft_uuid = ? OR discord_id = ?")) {
                    deleteExisting.setString(1, account.getMinecraftUUID().toString());
                    deleteExisting.setString(2, discordId);
                    deleteExisting.executeUpdate();
                }

                // Insert new linked account
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO uxm_linked_accounts (minecraft_uuid, minecraft_username, discord_id, discord_username, server, linked_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, account.getMinecraftUUID().toString());
                    insert.setString(2, account.getMinecraftUsername());
                    insert.setString(3, discordId);
                    insert.setString(4, discordName);
                    insert.setString(5, account.getServer());
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }

                // Log event
                try (PreparedStatement log = connection.prepareStatement(
                        "INSERT INTO uxm_link_events (minecraft_uuid, discord_id, server, action, timestamp) "
                                + "VALUES (?, ?, ?, 'LINK', ?)")) {
                    log.setString(1, account.getMinecraftUUID().toString());
                    log.setString(2, discordId);
                    log.setString(3, account.getServer());
                    log.setLong(4, now);
                    log.executeUpdate();
                }

                // Update network link codes table
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE uxm_network_link_codes SET discord_id = ?, discord_username = ?, linked_at = ?, previously_linked = ? "
                                + "WHERE code = ?")) {
                    update.setString(1, discordId);
                    update.setString(2, discordName);
                    update.setLong(3, now);
                    update.setInt(4, previous ? 1 : 0);
                    update.setString(5, code);
                    update.executeUpdate();
                }

                connection.commit();
                return account;
            } catch (SQLException error) {
                connection.rollback();
                if (isDuplicate(error)) {
                    return null;
                }
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<Delivery> pending(String server) throws SQLException {
        List<Delivery> deliveries = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM uxm_network_link_codes WHERE server = ? AND linked_at > 0 AND delivered = 0 "
                             + "AND (delivery_owner IS NULL OR delivery_claim_expires_at < ?)")) {
            select.setString(1, server);
            select.setLong(2, now);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    deliveries.add(new Delivery(
                            row.getString("code"),
                            new LinkedAccount(
                                    UUID.fromString(row.getString("minecraft_uuid")),
                                    row.getString("minecraft_username"),
                                    row.getString("discord_id"),
                                    row.getString("discord_username"),
                                    server,
                                    row.getLong("linked_at")
                            ),
                            row.getInt("previously_linked") != 0
                    ));
                }
            }
        }
        return deliveries;
    }

    /**
     * Claims a delivery for this worker. The claim is only ownership; complete it after
     * the reward work has run on the Bukkit thread.
     */
    public boolean claim(String code, String server) throws SQLException {
        return this.claimWithToken(code, server) != null;
    }

    public String claimWithToken(String code, String server) throws SQLException {
        long now = System.currentTimeMillis();
        String claimToken = UUID.randomUUID().toString();
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_link_codes SET delivery_owner = ?, delivery_claim_token = ?, "
                             + "delivery_claim_expires_at = ? WHERE code = ? AND server = ? AND linked_at > 0 "
                             + "AND delivered = 0 AND (delivery_owner IS NULL OR delivery_claim_expires_at < ?)")) {
            update.setString(1, this.claimOwner);
            update.setString(2, claimToken);
            update.setLong(3, now + this.claimLeaseMillis);
            update.setString(4, code);
            update.setString(5, server);
            update.setLong(6, now);
            return update.executeUpdate() == 1 ? claimToken : null;
        }
    }

    public boolean complete(String code, String server) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_link_codes SET delivered = 1, delivery_owner = NULL, "
                             + "delivery_claim_token = NULL, delivery_claim_expires_at = 0 WHERE code = ? "
                             + "AND server = ? AND linked_at > 0 AND delivered = 0 AND delivery_owner = ?")) {
            update.setString(1, code);
            update.setString(2, server);
            update.setString(3, this.claimOwner);
            return update.executeUpdate() == 1;
        }
    }

    public boolean complete(String code, String server, String claimToken) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_link_codes SET delivered = 1, delivery_owner = NULL, "
                             + "delivery_claim_token = NULL, delivery_claim_expires_at = 0 WHERE code = ? "
                             + "AND server = ? AND linked_at > 0 AND delivered = 0 AND delivery_owner = ? "
                             + "AND delivery_claim_token = ?")) {
            update.setString(1, code);
            update.setString(2, server);
            update.setString(3, this.claimOwner);
            update.setString(4, claimToken);
            return update.executeUpdate() == 1;
        }
    }

    public boolean release(String code, String server) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_link_codes SET delivery_owner = NULL, delivery_claim_token = NULL, "
                             + "delivery_claim_expires_at = 0 WHERE code = ? AND server = ? AND delivered = 0 "
                             + "AND delivery_owner = ?")) {
            update.setString(1, code);
            update.setString(2, server);
            update.setString(3, this.claimOwner);
            return update.executeUpdate() == 1;
        }
    }

    public boolean release(String code, String server, String claimToken) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_link_codes SET delivery_owner = NULL, delivery_claim_token = NULL, "
                             + "delivery_claim_expires_at = 0 WHERE code = ? AND server = ? AND delivered = 0 "
                             + "AND delivery_owner = ? AND delivery_claim_token = ?")) {
            update.setString(1, code);
            update.setString(2, server);
            update.setString(3, this.claimOwner);
            update.setString(4, claimToken);
            return update.executeUpdate() == 1;
        }
    }

    public void cleanup() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM uxm_network_link_codes WHERE (linked_at = 0 AND expires_at < ?) OR (delivered = 1 AND linked_at < ?)")) {
            delete.setLong(1, now);
            delete.setLong(2, now - 86400000L); // 24 hours
            delete.executeUpdate();
        }
    }

    private static boolean isDuplicate(SQLException error) {
        return "23505".equals(error.getSQLState()) || error.getErrorCode() == 1062;
    }
}
