package com.uxplima.discordsync.multiserver.database;

import com.uxplima.discordsync.multiserver.MultiServerHookPlugin;
import com.uxplima.discordsync.multiserver.config.MultiServerConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ProductionQueueDispatchTest {
    private static final String DB_URL = "jdbc:h2:mem:production_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Test
    void productionTimerDoesNotReclaimADeferredCallbackAfterLeaseExpiry() throws Exception {
        try (Connection keepAlive = DriverManager.getConnection(DB_URL);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            CommandQueueStore store = new CommandQueueStore(
                    () -> DriverManager.getConnection(DB_URL), 25L);
            store.createTables();
            assertTrue(store.queue("leaf-1", "say delayed"));

            MultiServerHookPlugin plugin = mock(MultiServerHookPlugin.class);
            MultiServerConfig config = mock(MultiServerConfig.class);
            when(config.getServerName()).thenReturn("leaf-1");
            when(plugin.getLogger()).thenReturn(Logger.getLogger("production-dispatch-test"));
            setField(plugin, "commandQueueStore", store);
            setField(plugin, "config", config);
            setField(plugin, "commandBatchInFlight", new AtomicBoolean());

            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            List<Runnable> mainTasks = new ArrayList<>();
            List<Runnable> asyncTasks = new ArrayList<>();
            when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                mainTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
            when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
                asyncTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
            AtomicInteger dispatches = new AtomicInteger();
            bukkit.when(() -> Bukkit.dispatchCommand(any(ConsoleCommandSender.class), anyString()))
                    .thenAnswer(invocation -> {
                        dispatches.incrementAndGet();
                        return true;
                    });

            Method poll = MultiServerHookPlugin.class.getDeclaredMethod("pollCommandQueue");
            poll.setAccessible(true);
            poll.invoke(plugin);
            try (Connection expiredClaim = DriverManager.getConnection(DB_URL);
                 Statement statement = expiredClaim.createStatement()) {
                statement.executeUpdate("UPDATE uxm_network_commands SET claim_expires_at = 0");
            }
            poll.invoke(plugin);

            assertEquals(1, mainTasks.size(), "the in-flight production batch must block reclaim");
            mainTasks.get(0).run();
            assertEquals(1, asyncTasks.size(), "the production callback must schedule its ACK asynchronously");
            asyncTasks.get(0).run();
            assertEquals(1, dispatches.get());
            assertTrue(store.pollPending("leaf-1").isEmpty());
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = MultiServerHookPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
