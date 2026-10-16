package com.uxplima.discordsync.multiserver.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class ServerLeaseTest {
    private static final String DB_URL = "jdbc:h2:mem:leasetest;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws Exception {
        this.keepAlive = DriverManager.getConnection(DB_URL);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.keepAlive != null && !this.keepAlive.isClosed()) {
            this.keepAlive.close();
        }
    }

    @Test
    void testLeaseAcquireRenewAndConflict() throws Exception {
        long now = 100000L;
        ServerLease lease1 = new ServerLease(() -> DriverManager.getConnection(DB_URL), "leaf-1", 30000L);
        ServerLease lease2 = new ServerLease(() -> DriverManager.getConnection(DB_URL), "leaf-1", 30000L);

        // First instance acquires lease
        assertTrue(lease1.acquire(now));

        // Second instance with same name attempts to acquire while active
        assertFalse(lease2.acquire(now));

        // First instance can renew its own lease
        assertTrue(lease1.renew(now + 10000L));

        // First instance closes
        lease1.close();

        // Second instance can now acquire
        assertTrue(lease2.acquire(now + 15000L));
        lease2.close();
    }
}
