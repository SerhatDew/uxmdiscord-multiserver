package com.uxplima.discordsync.multiserver.database;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.discord.DiscordBot;
import com.uxplima.discordsync.multiserver.MultiServerHookPlugin;
import com.uxplima.discordsync.multiserver.config.MultiServerConfig;
import net.dv8tion.jda.api.JDA;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionGatewayOwnershipTest {
    @Test
    void secondaryWatcherStopsInheritedGateway() throws Exception {
        MultiServerHookPlugin plugin = mock(MultiServerHookPlugin.class);
        UXMDiscordSync uxm = mock(UXMDiscordSync.class);
        DiscordBot bot = mock(DiscordBot.class);
        JDA jda = mock(JDA.class);
        MultiServerConfig config = mock(MultiServerConfig.class);
        when(config.isCoordinator()).thenReturn(false);
        when(uxm.getDiscordBot()).thenReturn(bot);
        when(bot.getJDA()).thenReturn(jda);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("gateway-ownership-test"));
        setField(plugin, "config", config);
        setField(plugin, "jdaHooked", new AtomicBoolean());
        setField(plugin, "secondaryStopRequested", new AtomicBoolean());
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return mock(BukkitTask.class);
                });
        Runnable[] asyncShutdown = new Runnable[1];
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    asyncShutdown[0] = invocation.getArgument(1);
                    return mock(BukkitTask.class);
                });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            invokeWatcher(plugin, uxm);
            // The scheduler answer captured the production async callback through the timer runnable.
            // Invoke it now, outside the scheduler mock, to exercise DiscordBot.shutdown itself.
            if (asyncShutdown[0] == null) {
                throw new AssertionError("secondary watcher did not schedule shutdown");
            }
            asyncShutdown[0].run();
            verify(bot).shutdown();
        }
    }

    @Test
    void coordinatorWatcherDoesNotStopGateway() throws Exception {
        MultiServerHookPlugin plugin = mock(MultiServerHookPlugin.class);
        UXMDiscordSync uxm = mock(UXMDiscordSync.class);
        DiscordBot bot = mock(DiscordBot.class);
        JDA jda = mock(JDA.class);
        MultiServerConfig config = mock(MultiServerConfig.class);
        when(config.isCoordinator()).thenReturn(true);
        when(uxm.getDiscordBot()).thenReturn(bot);
        when(bot.getJDA()).thenReturn(jda);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getRegisteredListeners()).thenReturn(java.util.List.of());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("gateway-ownership-test"));
        setField(plugin, "config", config);
        setField(plugin, "jdaHooked", new AtomicBoolean());
        setField(plugin, "commandQueueStore", mock(CommandQueueStore.class));
        setField(plugin, "hookTwoFactorManager", null);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return mock(BukkitTask.class);
                });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            invokeWatcher(plugin, uxm);
            verify(bot, never()).shutdown();
        }
    }

    private static void invokeWatcher(MultiServerHookPlugin plugin, UXMDiscordSync uxm)
            throws ReflectiveOperationException {
        Method method = MultiServerHookPlugin.class.getDeclaredMethod("startJdaHookWatcher", UXMDiscordSync.class);
        method.setAccessible(true);
        method.invoke(plugin, uxm);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = MultiServerHookPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
