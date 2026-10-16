package com.uxplima.discordsync.multiserver.database;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.database.Database;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Accesses the underlying SQL connection pool of uxmDiscordSync without duplicating connections.
 */
public class DatabaseAccess {
    private final UXMDiscordSync plugin;
    private final Logger logger;
    private HikariDataSource hikariDataSource;

    public DatabaseAccess(UXMDiscordSync plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.extractDataSource();
    }

    public synchronized void extractDataSource() {
        if (this.hikariDataSource != null && !this.hikariDataSource.isClosed()) {
            return;
        }

        if (this.plugin.getDatabaseManager() == null) {
            throw new IllegalStateException("uxmDiscordSync DatabaseManager is not yet initialized!");
        }

        Database db = this.plugin.getDatabaseManager().getDatabase();
        if (db == null) {
            throw new IllegalStateException("uxmDiscordSync Database is not connected!");
        }

        try {
            Field field = findField(db.getClass(), "dataSource");
            if (field != null) {
                field.setAccessible(true);
                Object ds = field.get(db);
                if (ds instanceof HikariDataSource hds) {
                    this.hikariDataSource = hds;
                    this.logger.info("Successfully hooked into uxmDiscordSync HikariCP connection pool.");
                    return;
                }
            }
        } catch (Exception e) {
            this.logger.warning("Could not reflectively extract HikariDataSource: " + e.getMessage());
        }

        throw new IllegalStateException("Multi-server hook requires MySQL, MariaDB or PostgreSQL database in uxmDiscordSync!");
    }

    public Connection getConnection() throws SQLException {
        if (this.hikariDataSource == null || this.hikariDataSource.isClosed()) {
            this.extractDataSource();
        }
        return this.hikariDataSource.getConnection();
    }

    public boolean isConnected() {
        return this.hikariDataSource != null && !this.hikariDataSource.isClosed();
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
