package com.uxplima.discordsync.multiserver;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.multiserver.config.MultiServerConfig;
import com.uxplima.discordsync.multiserver.database.CommandQueueStore;
import com.uxplima.discordsync.multiserver.database.DatabaseAccess;
import com.uxplima.discordsync.multiserver.database.NetworkLinkStore;
import com.uxplima.discordsync.multiserver.database.ServerLease;
import com.uxplima.discordsync.multiserver.discord.HookDiscordListener;
import com.uxplima.discordsync.multiserver.managers.HookLinkingManager;
import com.uxplima.discordsync.multiserver.managers.HookTwoFactorManager;
import com.uxplima.discordsync.multiserver.utils.ReflectionUtils;
import net.dv8tion.jda.api.JDA;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MultiServerHookPlugin extends JavaPlugin {
    private MultiServerConfig config;
    private DatabaseAccess databaseAccess;
    private NetworkLinkStore networkLinkStore;
    private ServerLease serverLease;
    private CommandQueueStore commandQueueStore;
    private HookLinkingManager hookLinkingManager;
    private HookTwoFactorManager hookTwoFactorManager;

    private BukkitTask leaseTask;
    private BukkitTask commandQueueTask;
    private BukkitTask jdaHookWatcherTask;
    private final AtomicBoolean jdaHooked = new AtomicBoolean(false);
    private final AtomicBoolean secondaryStopRequested = new AtomicBoolean(false);
    private final AtomicBoolean commandBatchInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        UXMDiscordSync uxm = UXMDiscordSync.getInstance();
        if (uxm == null || !uxm.isEnabled()) {
            this.getLogger().severe("uxmDiscordSync is not loaded or enabled! Disabling uxmDiscordSync-MultiServer.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.config = new MultiServerConfig(this.getConfig(), uxm);

        this.getLogger().info("==================================================");
        this.getLogger().info(" uxmDiscordSync-MultiServer Hook Plugin");
        this.getLogger().info(" Server Name: " + this.config.getServerName());
        this.getLogger().info(" Coordinator: " + this.config.getCoordinatorServer() + " (Is Coordinator: " + this.config.isCoordinator() + ")");
        this.getLogger().info(" Link Mode: " + this.config.getLinkMode() + ", Reward Mode: " + this.config.getRewardMode());
        this.getLogger().info("==================================================");

        if (!this.config.isEnabled()) {
            this.getLogger().warning("Multi-server mode is disabled in config. Hook will stay idle.");
            return;
        }

        // 1. Initialize database access
        try {
            this.databaseAccess = new DatabaseAccess(uxm, this.getLogger());
            this.networkLinkStore = new NetworkLinkStore(this.databaseAccess::getConnection, this.config.isGlobalRewards());
            this.networkLinkStore.createTables();

            this.commandQueueStore = new CommandQueueStore(this.databaseAccess::getConnection);
            this.commandQueueStore.createTables();

            this.serverLease = new ServerLease(this.databaseAccess::getConnection, this.config.getServerName(), 30000L);
            long now = System.currentTimeMillis();
            if (!this.serverLease.acquire(now)) {
                this.getLogger().severe("==================================================");
                this.getLogger().severe(" CRITICAL: server-name '" + this.config.getServerName() + "' is already in use by another active instance!");
                this.getLogger().severe(" Each server must have a unique server-name.");
                this.getLogger().severe(" Disabling uxmDiscordSync-MultiServer.");
                this.getLogger().severe("==================================================");
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Schedule lease renewal
            this.leaseTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    this.serverLease.renew(System.currentTimeMillis());
                } catch (SQLException e) {
                    this.getLogger().warning("Failed to renew server lease: " + e.getMessage());
                }
            }, 200L, 200L); // every 10s
        } catch (Exception e) {
            this.getLogger().severe("Failed to initialize multi-server database tables: " + e.getMessage());
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Inject HookLinkingManager
        try {
            this.hookLinkingManager = new HookLinkingManager(
                    uxm,
                    this.networkLinkStore,
                    this.config.getServerName(),
                    this.config.isEnabled(),
                    this.config.isSharedMode(),
                    this
            );
            ReflectionUtils.setField(uxm, "linkingManager", this.hookLinkingManager);
            this.getLogger().info("Injected HookLinkingManager into uxmDiscordSync.");
        } catch (Exception e) {
            this.getLogger().severe("Failed to inject HookLinkingManager: " + e.getMessage());
        }

        // 3. Inject HookTwoFactorManager
        try {
            this.hookTwoFactorManager = new HookTwoFactorManager(
                    uxm,
                    this.config.getServerName(),
                    this.config.isEnabled(),
                    this
            );
            ReflectionUtils.setField(uxm, "twoFactorManager", this.hookTwoFactorManager);
            this.getLogger().info("Injected HookTwoFactorManager into uxmDiscordSync.");
        } catch (Exception e) {
            this.getLogger().severe("Failed to inject HookTwoFactorManager: " + e.getMessage());
        }

        // 4. Start command queue listener for target servers.
        this.commandQueueTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!this.commandBatchInFlight.compareAndSet(false, true)) {
                return;
            }
            try {
                List<CommandQueueStore.QueuedCommand> list = this.commandQueueStore.pollPending(this.config.getServerName());
                if (list.isEmpty()) {
                    this.commandBatchInFlight.set(false);
                    return;
                }

                AtomicInteger remaining = new AtomicInteger(1);
                for (CommandQueueStore.QueuedCommand item : list) {
                    remaining.incrementAndGet();
                    try {
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                this.getLogger().info("Executing queued command from Discord: " + item.command());
                                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), item.command())) {
                                    throw new IllegalStateException("Bukkit rejected the command");
                                }
                                this.acknowledgeCommand(item, () -> this.finishCommandBatch(remaining));
                            } catch (RuntimeException error) {
                                this.getLogger().warning("Queued command failed; releasing claim for retry: "
                                        + error.getMessage());
                                this.releaseCommand(item, () -> this.finishCommandBatch(remaining));
                            }
                        });
                    } catch (RuntimeException error) {
                        this.releaseCommand(item, () -> this.finishCommandBatch(remaining));
                    }
                }
                this.finishCommandBatch(remaining);
            } catch (SQLException e) {
                this.commandBatchInFlight.set(false);
                this.getLogger().fine("Command queue poll exception: " + e.getMessage());
            }
        }, 20L, 20L); // every 1 second

        // 5. Watch for JDA startup and hook Discord
        this.startJdaHookWatcher(uxm);
    }

    private void finishCommandBatch(AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            this.commandBatchInFlight.set(false);
        }
    }

    private void acknowledgeCommand(CommandQueueStore.QueuedCommand command, Runnable onFinished) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (!this.commandQueueStore.markExecuted(command)) {
                    this.getLogger().warning("Queued command acknowledgement was rejected for id " + command.id());
                }
            } catch (SQLException e) {
                this.getLogger().warning("Failed to acknowledge queued command " + command.id() + ": " + e.getMessage());
            } finally {
                onFinished.run();
            }
        });
    }

    private void releaseCommand(CommandQueueStore.QueuedCommand command, Runnable onFinished) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                this.commandQueueStore.release(command);
            } catch (SQLException e) {
                this.getLogger().warning("Failed to release queued command " + command.id() + ": " + e.getMessage());
            } finally {
                onFinished.run();
            }
        });
    }

    private void startJdaHookWatcher(UXMDiscordSync uxm) {
        this.jdaHookWatcherTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (MultiServerHookPlugin.this.jdaHooked.get()) {
                    if (MultiServerHookPlugin.this.jdaHookWatcherTask != null) {
                        MultiServerHookPlugin.this.jdaHookWatcherTask.cancel();
                    }
                    return;
                }

                if (!MultiServerHookPlugin.this.config.isCoordinator()) {
                    var discordBot = uxm.getDiscordBot();
                    var jda = discordBot == null ? null : discordBot.getJDA();
                    if (jda != null && jda.getStatus() != JDA.Status.SHUTDOWN
                            && jda.getStatus() != JDA.Status.SHUTTING_DOWN
                            && MultiServerHookPlugin.this.secondaryStopRequested.compareAndSet(false, true)) {
                        Bukkit.getScheduler().runTaskAsynchronously(MultiServerHookPlugin.this, () -> {
                            try {
                                discordBot.shutdown();
                                MultiServerHookPlugin.this.getLogger().info(
                                        "Secondary server: stopped the inherited Discord Gateway connection.");
                            } finally {
                                MultiServerHookPlugin.this.secondaryStopRequested.set(false);
                            }
                        });
                    }
                    return;
                }

                if (++this.attempts > 60) { // 30 seconds
                    MultiServerHookPlugin.this.getLogger().warning("Timed out waiting for JDA initialization.");
                    if (MultiServerHookPlugin.this.jdaHookWatcherTask != null) {
                        MultiServerHookPlugin.this.jdaHookWatcherTask.cancel();
                    }
                    return;
                }

                if (uxm.getDiscordBot() == null || uxm.getDiscordBot().getJDA() == null) {
                    return;
                }

                JDA jda = uxm.getDiscordBot().getJDA();
                if (jda.getStatus() == JDA.Status.CONNECTED || jda.getStatus() == JDA.Status.INITIALIZED) {
                    MultiServerHookPlugin.this.hookJda(uxm, jda);
                    MultiServerHookPlugin.this.jdaHooked.set(true);
                    if (MultiServerHookPlugin.this.jdaHookWatcherTask != null) {
                        MultiServerHookPlugin.this.jdaHookWatcherTask.cancel();
                    }
                }
            }
        }, 10L, 10L);
    }

    private void hookJda(UXMDiscordSync uxm, JDA jda) {
        try {

            // Remove default listeners that would collide across servers
            List<Object> listeners = new ArrayList<>(jda.getRegisteredListeners());
            for (Object listener : listeners) {
                String className = listener.getClass().getName();
                if (className.contains("DiscordListeners")) {
                    jda.removeEventListener(listener);
                    this.getLogger().info("Removed original DiscordListeners from JDA.");
                }
                if (!this.config.isCoordinator()) {
                    if (className.contains("CustomCommandManager") || className.contains("NewCommandManager")) {
                        jda.removeEventListener(listener);
                        this.getLogger().info("Secondary server: removed " + listener.getClass().getSimpleName() + ".");
                    }
                }
            }

            // Add our HookDiscordListener
            HookDiscordListener hookListener = new HookDiscordListener(
                    uxm,
                    this.config.getServerName(),
                    this.config.isCoordinator(),
                    this.config.isEnabled(),
                    this.commandQueueStore,
                    this.hookTwoFactorManager,
                    this
            );
            jda.addEventListener(hookListener);
            this.getLogger().info("Registered HookDiscordListener to JDA (Coordinator: " + this.config.isCoordinator() + ")");
        } catch (Exception e) {
            this.getLogger().severe("Failed to hook JDA: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (this.leaseTask != null) {
            this.leaseTask.cancel();
        }
        if (this.commandQueueTask != null) {
            this.commandQueueTask.cancel();
        }
        if (this.jdaHookWatcherTask != null) {
            this.jdaHookWatcherTask.cancel();
        }

        if (this.hookLinkingManager != null) {
            this.hookLinkingManager.shutdown();
        }

        if (this.serverLease != null) {
            try {
                this.serverLease.close();
                this.getLogger().info("Released server lease for " + this.config.getServerName());
            } catch (SQLException e) {
                this.getLogger().warning("Failed to release server lease on shutdown: " + e.getMessage());
            }
        }

        this.getLogger().info("uxmDiscordSync-MultiServer disabled.");
    }
}
