package com.uxplima.discordsync.multiserver.managers;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.managers.LinkingManager;
import com.uxplima.discordsync.models.LinkedAccount;
import com.uxplima.discordsync.multiserver.database.NetworkLinkStore;
import com.uxplima.discordsync.utils.PlaceholderService;
import com.uxplima.discordsync.utils.PlaceholderUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Multi-server aware linking manager that replaces uxmDiscordSync's default LinkingManager.
 */
public class HookLinkingManager extends LinkingManager {
    private final UXMDiscordSync plugin;
    private final NetworkLinkStore linkStore;
    private final String serverName;
    private final boolean multiServerEnabled;
    private final boolean sharedMode;
    private final Logger logger;
    private final Map<UUID, Long> rateLimits = new ConcurrentHashMap<>();
    private BukkitTask deliveryTask;
    private BukkitTask cleanupTask;

    public HookLinkingManager(UXMDiscordSync plugin,
                              NetworkLinkStore linkStore,
                              String serverName,
                              boolean multiServerEnabled,
                              boolean sharedMode,
                              Plugin hookPlugin) {
        super(plugin);
        this.plugin = plugin;
        this.linkStore = linkStore;
        this.serverName = serverName;
        this.multiServerEnabled = multiServerEnabled;
        this.sharedMode = sharedMode;
        this.logger = hookPlugin.getLogger();

        if (this.multiServerEnabled && this.sharedMode) {
            this.startDeliveryTask(hookPlugin);
            this.startCleanupTask(hookPlugin);
        }
    }

    private void startDeliveryTask(Plugin hookPlugin) {
        this.deliveryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(hookPlugin, () -> {
            try {
                List<NetworkLinkStore.Delivery> pending = this.linkStore.pending(this.serverName);
                if (pending.isEmpty()) {
                    return;
                }

                for (NetworkLinkStore.Delivery delivery : pending) {
                    UUID uuid = delivery.account().getMinecraftUUID();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        continue;
                    }

                    if (this.linkStore.claim(delivery.code(), this.serverName)) {
                        this.logger.info("Claimed link delivery for online player: " + player.getName());
                        Bukkit.getScheduler().runTask(this.plugin, () -> {
                            this.deliverLinkRewards(player, delivery.account(), delivery.previouslyLinked());
                        });
                    }
                }
            } catch (SQLException e) {
                this.logger.warning("Error during pending link delivery check: " + e.getMessage());
            }
        }, 20L, 20L); // every 1 second
    }

    private void startCleanupTask(Plugin hookPlugin) {
        this.cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(hookPlugin, () -> {
            try {
                this.linkStore.cleanup();
            } catch (SQLException e) {
                this.logger.warning("Failed to clean up expired network link codes: " + e.getMessage());
            }
        }, 6000L, 6000L); // every 5 minutes
    }

    private void deliverLinkRewards(Player player, LinkedAccount account, boolean wasLinkedBefore) {
        if (!player.isOnline()) {
            return;
        }

        // Cache the account locally
        if (this.plugin.getCacheManager() != null) {
            this.plugin.getCacheManager().cacheLinkedAccountByUUID(account.getMinecraftUUID(), account);
        }

        if (!wasLinkedBefore) {
            // Execute post-link commands
            List<String> commands = this.plugin.getConfigManager().getPostLinkCommands();
            if (commands != null) {
                for (String command : commands) {
                    String formatted = PlaceholderService.apply(player,
                            PlaceholderUtils.replaceCommandPlaceholders(
                                    command,
                                    player.getName(),
                                    account.getDiscordId(),
                                    account.getDiscordUsername(),
                                    this.serverName
                            ).replace("%player_uuid%", player.getUniqueId().toString())
                    );
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
                }
            }
            this.logger.info("Executed post-link commands for " + player.getName());
        } else {
            player.sendMessage(this.plugin.getLocaleManager().getComponent("linking.linked-before"));
            this.logger.info("Skipped post-link commands for " + player.getName() + " (was linked before)");
        }

        // Rank sync
        if (this.plugin.getConfigManager().isSyncOnLink() && this.plugin.getRankSyncManager() != null) {
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (player.isOnline()) {
                    this.plugin.getRankSyncManager().syncPlayer(player);
                }
            }, 20L);
        }

        // Nickname sync
        if (this.plugin.getConfigManager().isNicknameSyncOnLink() && this.plugin.getNicknameManager() != null) {
            this.plugin.getNicknameManager().syncNickname(player);
        }
    }

    @Override
    public CompletableFuture<String> generateLinkCode(Player player) {
        if (!this.multiServerEnabled || !this.sharedMode) {
            return super.generateLinkCode(player);
        }

        return CompletableFuture.supplyAsync(() -> {
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = this.rateLimits.get(uuid);
            if (last != null && now - last < 30000L) {
                return null;
            }
            this.rateLimits.put(uuid, now);

            int length = this.plugin.getConfigManager().getVerificationCodeLength();
            long expiresAt = now + ((long) this.plugin.getConfigManager().getCodeExpiration() * 1000L);

            for (int attempt = 0; attempt < 10; attempt++) {
                String code = String.format("%0" + length + "d",
                        ThreadLocalRandom.current().nextInt((int) Math.pow(10, length)));
                try {
                    if (this.linkStore.createCode(uuid, player.getName(), this.serverName, code, expiresAt)) {
                        this.logger.info("Generated network link code " + code + " for " + player.getName() + " on " + this.serverName);
                        return code;
                    }
                } catch (SQLException e) {
                    this.logger.severe("Database error while creating link code: " + e.getMessage());
                    return null;
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<LinkedAccount> linkAccount(String code, String discordId, String discordUsername) {
        if (!this.multiServerEnabled || !this.sharedMode) {
            return super.linkAccount(code, discordId, discordUsername);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                LinkedAccount account = this.linkStore.redeem(code, discordId, discordUsername);
                if (account != null) {
                    this.logger.info("Successfully redeemed link code " + code + " for " + account.getMinecraftUsername()
                            + " (Origin server: " + account.getServer() + ")");
                    // If the player happens to be on this server right now, delivery loop or local task will execute rewards
                    Player localPlayer = Bukkit.getPlayer(account.getMinecraftUUID());
                    if (localPlayer != null && localPlayer.isOnline() && account.getServer().equalsIgnoreCase(this.serverName)) {
                        if (this.linkStore.claim(code, this.serverName)) {
                            Bukkit.getScheduler().runTask(this.plugin, () -> {
                                this.deliverLinkRewards(localPlayer, account, false);
                            });
                        }
                    }
                }
                return account;
            } catch (SQLException e) {
                this.logger.severe("Database error during account link: " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<LinkedAccount> getLinkedAccount(UUID uuid) {
        if (!this.multiServerEnabled || !this.sharedMode) {
            return super.getLinkedAccount(uuid);
        }
        return this.plugin.getDatabaseManager().getLinkedAccountByUUID(uuid);
    }

    @Override
    public CompletableFuture<LinkedAccount> getLinkedAccountByDiscordId(String discordId) {
        if (!this.multiServerEnabled || !this.sharedMode) {
            return super.getLinkedAccountByDiscordId(discordId);
        }
        return this.plugin.getDatabaseManager().getLinkedAccountByDiscordId(discordId);
    }

    public void shutdown() {
        if (this.deliveryTask != null) {
            this.deliveryTask.cancel();
        }
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
        }
    }
}
