package com.uxplima.discordsync.multiserver.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandQueueStoreTest {
    private static final String DB_URL = "jdbc:h2:mem:cmdtest;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private Connection keepAlive;
    private CommandQueueStore store;

    @BeforeEach
    void setUp() throws Exception {
        this.keepAlive = DriverManager.getConnection(DB_URL);
        this.store = new CommandQueueStore(() -> DriverManager.getConnection(DB_URL));
        this.store.createTables();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.keepAlive != null && !this.keepAlive.isClosed()) {
            this.keepAlive.close();
        }
    }

    @Test
    void testQueuePollAndExecute() throws Exception {
        assertTrue(this.store.queue("leaf-2", "say Hello Leaf 2"));
        assertTrue(this.store.queue("leaf-2", "kick BadPlayer"));
        assertTrue(this.store.queue("leaf-1", "say Hello Leaf 1"));

        // Poll for leaf-2
        List<CommandQueueStore.QueuedCommand> leaf2Cmds = this.store.pollPending("leaf-2");
        assertEquals(2, leaf2Cmds.size());
        assertEquals("say Hello Leaf 2", leaf2Cmds.get(0).command());
        assertEquals("kick BadPlayer", leaf2Cmds.get(1).command());

        // Mark first executed
        assertTrue(this.store.markExecuted(leaf2Cmds.get(0).id()));

        // Claimed rows stay hidden until the original poll acknowledges them.
        List<CommandQueueStore.QueuedCommand> remaining = this.store.pollPending("leaf-2");
        assertTrue(remaining.isEmpty());
        assertTrue(this.store.markExecuted(leaf2Cmds.get(1).id()));

        // Poll for leaf-1
        List<CommandQueueStore.QueuedCommand> leaf1Cmds = this.store.pollPending("leaf-1");
        assertEquals(1, leaf1Cmds.size());
        assertEquals("say Hello Leaf 1", leaf1Cmds.get(0).command());
    }

    @Test
    void expiredClaimCanBeReclaimedAndOldOwnerCannotAcknowledge() throws Exception {
        CommandQueueStore firstOwner = new CommandQueueStore(
                () -> DriverManager.getConnection(DB_URL), -1L);
        CommandQueueStore secondOwner = new CommandQueueStore(
                () -> DriverManager.getConnection(DB_URL), 30000L);
        assertTrue(firstOwner.queue("leaf-1", "say once"));

        List<CommandQueueStore.QueuedCommand> first = firstOwner.pollPending("leaf-1");
        assertEquals(1, first.size());
        List<CommandQueueStore.QueuedCommand> second = secondOwner.pollPending("leaf-1");
        assertEquals(1, second.size());
        assertFalse(firstOwner.markExecuted(first.get(0)));
        assertTrue(secondOwner.markExecuted(second.get(0)));
    }
}
