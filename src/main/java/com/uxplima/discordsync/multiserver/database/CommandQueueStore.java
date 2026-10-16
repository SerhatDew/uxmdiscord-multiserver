package com.uxplima.discordsync.multiserver.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles cross-server command execution requests from Discord (/servercommand).
 */
public final class CommandQueueStore {
    private final ConnectionSupplier connectionSupplier;

    public record QueuedCommand(long id, String targetServer, String command) {}

    public CommandQueueStore(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
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
                            + "created_at BIGINT NOT NULL)");
                }
            } else {
                throw e;
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
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id, target_server, command FROM uxm_network_commands "
                             + "WHERE target_server = ? AND executed = 0 ORDER BY id ASC LIMIT 20")) {
            select.setString(1, targetServer);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    list.add(new QueuedCommand(
                            rs.getLong("id"),
                            rs.getString("target_server"),
                            rs.getString("command")
                    ));
                }
            }
        }
        return list;
    }

    public boolean markExecuted(long id) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_network_commands SET executed = 1 WHERE id = ?")) {
            update.setLong(1, id);
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
