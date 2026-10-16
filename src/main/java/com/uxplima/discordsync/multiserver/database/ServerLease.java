package com.uxplima.discordsync.multiserver.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Rejects two live plugin instances using the same server identity.
 */
public final class ServerLease implements AutoCloseable {
    private final ConnectionSupplier connectionSupplier;
    private final String server;
    private final String owner = UUID.randomUUID().toString();
    private final long duration;

    public ServerLease(ConnectionSupplier connectionSupplier, String server, long duration) {
        this.connectionSupplier = connectionSupplier;
        this.server = server;
        this.duration = duration;
    }

    public boolean acquire(long now) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS uxm_server_instances "
                    + "(server_key VARCHAR(32) PRIMARY KEY, owner VARCHAR(36) NOT NULL, expires_at BIGINT NOT NULL)");
        }
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO uxm_server_instances (server_key, owner, expires_at) VALUES (?, ?, ?)")) {
            insert.setString(1, this.server);
            insert.setString(2, this.owner);
            insert.setLong(3, now + this.duration);
            insert.executeUpdate();
            return true;
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState()) && error.getErrorCode() != 1062) {
                throw error;
            }
        }
        return this.renew(now);
    }

    public boolean renew(long now) throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE uxm_server_instances SET owner = ?, expires_at = ? "
                             + "WHERE server_key = ? AND (owner = ? OR expires_at < ?)")) {
            update.setString(1, this.owner);
            update.setLong(2, now + this.duration);
            update.setString(3, this.server);
            update.setString(4, this.owner);
            update.setLong(5, now);
            return update.executeUpdate() == 1;
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = this.connectionSupplier.get();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM uxm_server_instances WHERE server_key = ? AND owner = ?")) {
            delete.setString(1, this.server);
            delete.setString(2, this.owner);
            delete.executeUpdate();
        }
    }
}
