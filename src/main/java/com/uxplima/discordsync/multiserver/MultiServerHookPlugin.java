package com.uxplima.discordsync.multiserver;

import com.blokoyun.hooks.common.logging.LifecycleLogger;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MultiServerHookPlugin extends JavaPlugin {
    private LifecycleLogger lifecycle;
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
    private AtomicBoolean resourcesCleaned = new AtomicBoolean(false);
    private long startupNanos;
    private volatile boolean startupInvoked;
    private volatile boolean startupFailureHandled;
    private volatile boolean shutdownInvoked;
    private volatile boolean passiveMode;
    private volatile boolean readyReported;

    @Override
    public void onEnable() {
        this.startupNanos = System.nanoTime();
        this.startupInvoked = true;
        try {
            this.lifecycle().startup();
            this.saveDefaultConfig();

            UXMDiscordSync uxm = UXMDiscordSync.getInstance();
            if (uxm == null || !uxm.isEnabled()) {
                throw new IllegalStateException("uxmDiscordSync yüklü veya etkin değil.");
            }

            this.config = new MultiServerConfig(this.getConfig(), uxm);
            this.lifecycle().info("Yapılandırma yüklendi.");
            this.lifecycle().info("Sunucu: " + this.config.getServerName() + "; rol: "
                    + (!this.config.isEnabled() ? "pasif"
                    : (this.config.isCoordinator() ? "koordinatör" : "ikincil")) + ".");

            if (!this.config.isEnabled()) {
                this.passiveMode = true;
                this.lifecycle().inactive("Çoklu sunucu modu yapılandırmada kapalı; sunucu: "
                        + this.config.getServerName() + ".");
                return;
            }

            this.databaseAccess = new DatabaseAccess(uxm, this.getLogger());
            this.networkLinkStore = new NetworkLinkStore(
                    this.databaseAccess::getConnection, this.config.isGlobalRewards());
            this.networkLinkStore.createTables();

            this.commandQueueStore = new CommandQueueStore(this.databaseAccess::getConnection);
            this.commandQueueStore.createTables();

            this.serverLease = new ServerLease(
                    this.databaseAccess::getConnection, this.config.getServerName(), 30000L);
            if (!this.serverLease.acquire(System.currentTimeMillis())) {
                throw new IllegalStateException("Sunucu kirası zaten kullanımda: "
                        + this.config.getServerName());
            }

            this.leaseTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    this.serverLease.renew(System.currentTimeMillis());
                } catch (SQLException e) {
                    this.getLogger().warning("Sunucu kirası yenilenemedi: " + e.getMessage());
                }
            }, 200L, 200L);

            this.hookLinkingManager = new HookLinkingManager(
                    uxm,
                    this.networkLinkStore,
                    this.config.getServerName(),
                    true,
                    this.config.isSharedMode(),
                    this);
            ReflectionUtils.setField(uxm, "linkingManager", this.hookLinkingManager);

            this.hookTwoFactorManager = new HookTwoFactorManager(
                    uxm,
                    this.config.getServerName(),
                    true,
                    this);
            ReflectionUtils.setField(uxm, "twoFactorManager", this.hookTwoFactorManager);

            this.commandQueueTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this, this::pollCommandQueue, 20L, 20L);

            this.startJdaHookWatcher(uxm);
            if (!this.config.isCoordinator() && !this.startupFailureHandled && !this.readyReported) {
                this.readyReported = true;
                this.lifecycle().info("Yerel kuyruk ve yöneticiler hazır; rol: ikincil; sunucu: "
                        + this.config.getServerName() + ".");
                this.lifecycle().started(this.startupNanos);
            } else if (this.config.isCoordinator() && !this.readyReported
                    && !this.startupFailureHandled) {
                this.lifecycle().waiting("Discord Gateway ve dinleyici; rol: koordinatör; sunucu: "
                        + this.config.getServerName() + ".");
            }
        } catch (Throwable failure) {
            this.failStartup(failure);
        }
    }

    private void pollCommandQueue() {
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
        BukkitTask scheduledTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (MultiServerHookPlugin.this.shutdownInvoked
                        || MultiServerHookPlugin.this.startupFailureHandled) {
                    cancelJdaWatcher();
                    return;
                }
                if (MultiServerHookPlugin.this.jdaHooked.get()) {
                    cancelJdaWatcher();
                    return;
                }

                if (!MultiServerHookPlugin.this.config.isCoordinator()) {
                    try {
                        var discordBot = uxm.getDiscordBot();
                        var jda = discordBot == null ? null : discordBot.getJDA();
                        if (jda != null && jda.getStatus() != JDA.Status.SHUTDOWN
                                && jda.getStatus() != JDA.Status.SHUTTING_DOWN
                                && MultiServerHookPlugin.this.secondaryStopRequested.compareAndSet(false, true)) {
                            Bukkit.getScheduler().runTaskAsynchronously(
                                    MultiServerHookPlugin.this, () -> {
                                        try {
                                            discordBot.shutdown();
                                            MultiServerHookPlugin.this.lifecycle().info(
                                                    "İkincil sunucunun devralınan Discord Gateway bağlantısı kapatıldı; sunucu: "
                                                            + MultiServerHookPlugin.this.config.getServerName() + ".");
                                        } catch (Throwable failure) {
                                            MultiServerHookPlugin.this.lifecycle().failure(
                                                    "Devralınan Discord Gateway bağlantısı kapatılamadı; sunucu: "
                                                            + MultiServerHookPlugin.this.config.getServerName() + ".",
                                                    failure);
                                        } finally {
                                            MultiServerHookPlugin.this.secondaryStopRequested.set(false);
                                        }
                                    });
                        }
                    } catch (Throwable failure) {
                        MultiServerHookPlugin.this.failStartup(failure);
                    }
                    return;
                }

                if (++this.attempts > 60) {
                    MultiServerHookPlugin.this.failStartup(new TimeoutException(
                            "Discord Gateway 30 saniye içinde hazır olmadı."));
                    cancelJdaWatcher();
                    return;
                }

                try {
                    if (uxm.getDiscordBot() == null || uxm.getDiscordBot().getJDA() == null) {
                        return;
                    }

                    JDA jda = uxm.getDiscordBot().getJDA();
                    JDA.Status status = jda.getStatus();
                    if (status == JDA.Status.CONNECTED || status == JDA.Status.INITIALIZED) {
                        MultiServerHookPlugin.this.hookJda(uxm, jda);
                        MultiServerHookPlugin.this.jdaHooked.set(true);
                        if (!MultiServerHookPlugin.this.readyReported) {
                            MultiServerHookPlugin.this.readyReported = true;
                            MultiServerHookPlugin.this.lifecycle().info(
                                    "Discord dinleyicisi hazır; rol: koordinatör; sunucu: "
                                            + MultiServerHookPlugin.this.config.getServerName() + ".");
                            MultiServerHookPlugin.this.lifecycle().started(
                                    MultiServerHookPlugin.this.startupNanos);
                        }
                        cancelJdaWatcher();
                    }
                } catch (Throwable failure) {
                    MultiServerHookPlugin.this.failStartup(failure);
                    cancelJdaWatcher();
                }
            }

            private void cancelJdaWatcher() {
                BukkitTask task = MultiServerHookPlugin.this.jdaHookWatcherTask;
                if (task != null) {
                    task.cancel();
                }
            }
        }, 10L, 10L);

        if (this.startupFailureHandled || this.jdaHooked.get()) {
            scheduledTask.cancel();
        } else {
            this.jdaHookWatcherTask = scheduledTask;
        }
    }

    private void hookJda(UXMDiscordSync uxm, JDA jda) {
        List<Object> listeners = new ArrayList<>(jda.getRegisteredListeners());
        for (Object listener : listeners) {
            String className = listener.getClass().getName();
            if (className.contains("DiscordListeners")) {
                jda.removeEventListener(listener);
                this.lifecycle().info("Eski Discord dinleyicisi kaldırıldı; sunucu: "
                        + this.config.getServerName() + ".");
            }
            if (!this.config.isCoordinator()
                    && (className.contains("CustomCommandManager")
                    || className.contains("NewCommandManager"))) {
                jda.removeEventListener(listener);
                this.lifecycle().info("İkincil sunucunun eski komut dinleyicisi kaldırıldı; sunucu: "
                        + this.config.getServerName() + ".");
            }
        }

        HookDiscordListener hookListener = new HookDiscordListener(
                uxm,
                this.config.getServerName(),
                this.config.isCoordinator(),
                this.config.isEnabled(),
                this.commandQueueStore,
                this.hookTwoFactorManager,
                this);
        jda.addEventListener(hookListener);
    }
    @Override
    public void onDisable() {
        if (!this.startupInvoked || this.shutdownInvoked) {
            return;
        }
        this.shutdownInvoked = true;

        Throwable failure = null;
        try {
            this.lifecycle().shutdown();
        } catch (Throwable shutdownBannerFailure) {
            failure = shutdownBannerFailure;
        }

        failure = this.cleanupOwnedResources(failure);
        if (failure != null) {
            if (!this.startupFailureHandled) {
                this.lifecycle().shutdownFailed();
            }
            return;
        }

        if (this.passiveMode || this.readyReported) {
            this.lifecycle().stopped(this.startupNanos);
        } else {
            this.lifecycle().stoppedBeforeReady();
        }
    }

    private LifecycleLogger lifecycle() {
        if (this.lifecycle == null) {
            this.lifecycle = new LifecycleLogger(this);
        }
        return this.lifecycle;
    }

    private void failStartup(Throwable failure) {
        if (this.startupFailureHandled) {
            return;
        }
        this.startupFailureHandled = true;
        this.lifecycle().startupFailed(failure);
        Throwable cleanupFailure = this.cleanupOwnedResources(failure);
        if (cleanupFailure != failure) {
            this.lifecycle().failure("Başlatma temizliği başarısız oldu; sunucu: "
                    + this.serverNameForLog() + ".", cleanupFailure);
        }
        try {
            this.getServer().getPluginManager().disablePlugin(this);
        } catch (Throwable disableFailure) {
            failure.addSuppressed(disableFailure);
            this.lifecycle().failure("Eklenti devre dışı bırakılamadı; sunucu: "
                    + this.serverNameForLog() + ".", disableFailure);
        }
    }

    private Throwable cleanupOwnedResources(Throwable primary) {
        if (this.resourcesCleaned == null) {
            this.resourcesCleaned = new AtomicBoolean(false);
        }
        if (!this.resourcesCleaned.compareAndSet(false, true)) {
            return primary;
        }

        Throwable failure = primary;
        BukkitTask task = this.leaseTask;
        this.leaseTask = null;
        if (task != null) {
            failure = attemptCleanup(failure, "sunucu kirası yenileme görevi", task::cancel);
        }

        task = this.commandQueueTask;
        this.commandQueueTask = null;
        if (task != null) {
            failure = attemptCleanup(failure, "komut kuyruğu görevi", task::cancel);
        }

        task = this.jdaHookWatcherTask;
        this.jdaHookWatcherTask = null;
        if (task != null) {
            failure = attemptCleanup(failure, "Discord bekleme görevi", task::cancel);
        }

        HookLinkingManager linkingManager = this.hookLinkingManager;
        this.hookLinkingManager = null;
        if (linkingManager != null) {
            failure = attemptCleanup(failure, "eşleme yöneticisi", linkingManager::shutdown);
        }

        ServerLease lease = this.serverLease;
        this.serverLease = null;
        if (lease != null) {
            failure = attemptCleanup(failure, "sunucu kirası", lease::close);
        }
        return failure;
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Throwable;
    }

    private Throwable attemptCleanup(Throwable primary, String resource, CleanupAction cleanup) {
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            this.lifecycle().failure("Kapanış kaynağı temizlenemedi (" + resource + "); sunucu: "
                    + this.serverNameForLog() + ".", cleanupFailure);
            if (primary == null) {
                return cleanupFailure;
            }
            if (primary != cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
        }
        return primary;
    }

    private String serverNameForLog() {
        return this.config == null ? "bilinmiyor" : this.config.getServerName();
    }
}
