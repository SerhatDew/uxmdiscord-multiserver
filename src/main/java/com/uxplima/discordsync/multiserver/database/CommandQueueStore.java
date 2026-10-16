package com.uxplima.discordsync.multiserver.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles cross-server command execution requests from Discord (/servercommand).
 */
public final class CommandQueueStore {
    private static final long DEFAULT_CLAIM_LEASE_MILLIS = 30000L;
    private final ConnectionSupplier connectionSupplier;
    private final String claimOwner = UUID.randomUUID().toString();
    private final long claimLeaseMillis;

    public record QueuedCommand(long id, String targetServer, String command, String claimToken) {}
    public CommandQueueStore(ConnectionSupplier connectionSupplier) {
        this(connectionSupplier, DEFAULT_CLAIM_LEASE_MILLIS);
    }

    CommandQueueStore(ConnectionSupplier connectionSupplier, long claimLeaseMillis) {
        this.connectionSupplier = connectionSupplier;
        this.claimLeaseMillis = claimLeaseMillis;
    }

    public void createTables() throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_network_commands ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "target_server VARCHAR(50) NOT NULL, "
                    + "command TEXT NOT NULL, "
                    + "executed SMALLINT NOT NULL DEFAULT 0, "
                    + "created_at BIGINT NOT NULL, "
                    + "claim_owner VARCHAR(36), "
                    + "claim_token VARCHAR(36), "
                    + "claim_expires_at BIGINT NOT NULL DEFAULT 0, "
                    + "INDEX idx_netcmd_target (target_server, executed))");
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("syntax")) {
                try (Connection connection = this.connectionSupplier.get();
                     Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_network_commands ("
                            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "target_server VARCHAR(50) NOT NULL, "
                            + "command TEXT NOT NULL, "
                            + "executed SMALLINT NOT NULL DEFAULT 0, "
                            + "created_at BIGINT NOT NULL, "
                            + "claim_owner VARCHAR(36), "
                            + "claim_token VARCHAR(36), "
                            + "claim_expires_at BIGINT NOT NULL DEFAULT 0)");
                }
            } else {
                throw e;
            }
        }
        this.ensureColumn("claim_owner VARCHAR(36)");
        this.ensureColumn("claim_token VARCHAR(36)");
        this.ensureColumn("claim_expires_at BIGINT NOT NULL DEFAULT 0");
    }

    private void ensureColumn(String definition) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE uxm_network_commands ADD COLUMN " + definition);
        } catch (SQLException error) {
            String message = error.getMessage();
            if (message == null || (!message.toLowerCase().contains("duplicate column")
                    && !message.toLowerCase().contains("already exists"))) {
                throw error;
            }
        }
    }

    public boolean queue(String targetServer, String command) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO uxm_network_commands (target_server, command, executed, created_at) "
                             + "VALUES (?, ?, 0, ?)")) {
            insert.setString(1, targetServer);
            insert.setString(2, command);
            insert.setLong(3, System.currentTimeMillis());
            return insert.executeUpdate() == 1;
        }
    }

    public List<QueuedCommand> pollPending(String targetServer) throws SQLException {
        List<QueuedCommand> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Connection connection = this.connectionSupplier.get()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, target_server, command FROM uxm_network_commands "
                                + "WHERE target_server = ? AND executed = 0 "
                                + "AND (claim_owner IS NULL OR claim_expires_at < ?) "
                                + "ORDER BY id ASC LIMIT 20 FOR UPDATE")) {
                    select.setString(1, targetServer);
                    select.setLong(2, now);
                    try (ResultSet rs = select.executeQuery();
                         PreparedStatement claim = connection.prepareStatement(
                                 "UPDATE uxm_network_commands SET claim_owner = ?, claim_token = ?, claim_expires_at = ? "
                                         + "WHERE id = ? AND executed = 0")) {
                        while (rs.next()) {
                            long id = rs.getLong("id");
                            String claimToken = UUID.randomUUID().toString();
                            claim.setString(1, this.claimOwner);
                            claim.setString(2, claimToken);
                            claim.setLong(3, now + this.claimLeaseMillis);
                            claim.setLong(4, id);
                            if (claim.executeUpdate() == 1) {
                                list.add(new QueuedCommand(id, rs.getString("target_server"),
                                        rs.getString("command"), claimToken));
                            }
                        }
                    }
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return list;
    }

    public boolean markExecuted(long id) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_commands SET executed = 1, claim_owner = NULL, claim_token = NULL, "
                             + "claim_expires_at = 0 WHERE id = ? AND executed = 0 AND claim_owner = ?")) {
            update.setLong(1, id);
            update.setString(2, this.claimOwner);
            return update.executeUpdate() == 1;
        }
    }

    public boolean markExecuted(QueuedCommand command) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_commands SET executed = 1, claim_owner = NULL, claim_token = NULL, "
                             + "claim_expires_at = 0 WHERE id = ? AND executed = 0 AND claim_owner = ? AND claim_token = ?")) {
            update.setLong(1, command.id());
            update.setString(2, this.claimOwner);
            update.setString(3, command.claimToken());
            return update.executeUpdate() == 1;
        }
    }

    public boolean release(long id) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_commands SET claim_owner = NULL, claim_token = NULL, claim_expires_at = 0 "
                             + "WHERE id = ? AND executed = 0 AND claim_owner = ?")) {
            update.setLong(1, id);
            update.setString(2, this.claimOwner);
            return update.executeUpdate() == 1;
        }
    }

    public boolean release(QueuedCommand command) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_commands SET claim_owner = NULL, claim_token = NULL, claim_expires_at = 0 "
                             + "WHERE id = ? AND executed = 0 AND claim_owner = ? AND claim_token = ?")) {
            update.setLong(1, command.id());
            update.setString(2, this.claimOwner);
            update.setString(3, command.claimToken());
            return update.executeUpdate() == 1;
        }
    }

    public void cleanup() throws SQLException {
        long cutoff = System.currentTimeMillis() - 86400000L; // 24 hours
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM uxm_network_commands WHERE executed = 1 AND created_at < ?")) {
            delete.setLong(1, cutoff);
            delete.executeUpdate();
        }
    }
}
