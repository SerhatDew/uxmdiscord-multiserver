package com.uxplima.discordsync.multiserver;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.multiserver.config.MultiServerConfig;
import com.uxplima.discordsync.multiserver.managers.HookLinkingManager;
import com.uxplima.discordsync.multiserver.database.ServerLease;
import net.dv8tion.jda.api.JDA;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiServerLifecycleTest {
    @Test
    void coordinatorListenerFailureDoesNotMarkGatewayReady() throws Exception {
        MultiServerHookPlugin plugin = mock(MultiServerHookPlugin.class);
        UXMDiscordSync uxm = mock(UXMDiscordSync.class);
        JDA jda = mock(JDA.class);
        MultiServerConfig config = mock(MultiServerConfig.class);
        when(config.isCoordinator()).thenReturn(true);
        when(config.isEnabled()).thenReturn(true);
        when(config.getServerName()).thenReturn("hub");
        when(jda.getRegisteredListeners()).thenReturn(List.of());
        doThrow(new IllegalStateException("listener rejected"))
                .when(jda).addEventListener(org.mockito.ArgumentMatchers.any(Object.class));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("multiserver-lifecycle-test"));
        setField(plugin, "config", config);
        setField(plugin, "jdaHooked", new AtomicBoolean());
        setField(plugin, "commandQueueStore", null);
        setField(plugin, "hookTwoFactorManager", null);

        Method hook = MultiServerHookPlugin.class.getDeclaredMethod(
                "hookJda", UXMDiscordSync.class, JDA.class);
        hook.setAccessible(true);
        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class, () -> hook.invoke(plugin, uxm, jda));

        assertEquals(IllegalStateException.class, failure.getCause().getClass());
        AtomicBoolean jdaHooked = (AtomicBoolean) getField(plugin, "jdaHooked");
        assertFalse(jdaHooked.get(), "listener failure must not report coordinator readiness");
    }

    @Test
    void cleanupAttemptsEveryResourceAndPreservesStartupFailure() throws Exception {
        MultiServerHookPlugin plugin = mock(MultiServerHookPlugin.class);
        MultiServerConfig config = mock(MultiServerConfig.class);
        when(config.getServerName()).thenReturn("hub");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("multiserver-cleanup-test"));
        setField(plugin, "config", config);
        setField(plugin, "resourcesCleaned", new AtomicBoolean());

        BukkitTask leaseTask = mock(BukkitTask.class);
        BukkitTask queueTask = mock(BukkitTask.class);
        BukkitTask watcherTask = mock(BukkitTask.class);
        doThrow(new IllegalStateException("lease task"))
                .when(leaseTask).cancel();
        doThrow(new IllegalStateException("queue task"))
                .when(queueTask).cancel();
        doThrow(new IllegalStateException("watcher task"))
                .when(watcherTask).cancel();
        HookLinkingManager linkingManager = mock(HookLinkingManager.class);
        doThrow(new IllegalStateException("manager"))
                .when(linkingManager).shutdown();
        ServerLease lease = mock(ServerLease.class);
        doThrow(new SQLException("lease close"))
                .when(lease).close();
        setField(plugin, "leaseTask", leaseTask);
        setField(plugin, "commandQueueTask", queueTask);
        setField(plugin, "jdaHookWatcherTask", watcherTask);
        setField(plugin, "hookLinkingManager", linkingManager);
        setField(plugin, "serverLease", lease);

        IllegalStateException primary = new IllegalStateException("startup");
        Method cleanup = MultiServerHookPlugin.class.getDeclaredMethod(
                "cleanupOwnedResources", Throwable.class);
        cleanup.setAccessible(true);
        Throwable result = (Throwable) cleanup.invoke(plugin, primary);

        assertSame(primary, result);
        assertEquals(5, primary.getSuppressed().length);
        verify(leaseTask).cancel();
        verify(queueTask).cancel();
        verify(watcherTask).cancel();
        verify(linkingManager).shutdown();
        verify(lease).close();

        Throwable secondResult = (Throwable) cleanup.invoke(plugin, new IllegalStateException("second"));
        assertEquals(IllegalStateException.class, secondResult.getClass());
    }

    private static Object getField(Object target, String name) throws ReflectiveOperationException {
        Field field = MultiServerHookPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = MultiServerHookPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
