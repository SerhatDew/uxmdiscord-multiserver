package com.uxplima.discordsync.multiserver.managers;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.discord.DiscordBot;
import com.uxplima.discordsync.managers.TwoFactorManager;
import com.uxplima.discordsync.models.LinkedAccount;
import com.uxplima.discordsync.multiserver.discord.InteractionRouting;
import com.uxplima.discordsync.utils.EmbedFactory;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Multi-server aware TwoFactorManager that tags 2FA verification buttons with the originating server key.
 */
public class HookTwoFactorManager extends TwoFactorManager {
    private final UXMDiscordSync plugin;
    private final String serverName;
    private final boolean multiServerEnabled;
    private final Logger logger;

    private final Map<UUID, String> pendingCodes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingSince = new ConcurrentHashMap<>();
    private final Set<UUID> verifiedSessions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> pendingMessageIds = new ConcurrentHashMap<>();

    public HookTwoFactorManager(UXMDiscordSync plugin, String serverName, boolean multiServerEnabled, Plugin hookPlugin) {
        super(plugin);
        this.plugin = plugin;
        this.serverName = serverName;
        this.multiServerEnabled = multiServerEnabled;
        this.logger = hookPlugin.getLogger();
    }

    @Override
    public void handleJoin(Player player) {
        if (!this.plugin.getConfigManager().isTwoFactorEnabled()) {
            return;
        }

        if (this.plugin.getConfigManager().isTwoFactorRequirePermission() && !player.hasPermission(REQUIRED_PERMISSION)) {
            this.logger.fine("Skipping 2FA for " + player.getName() + ": missing permission");
            return;
        }

        this.plugin.getLinkingManager().isLinked(player.getUniqueId()).thenAccept(isLinked -> {
            if (!Boolean.TRUE.equals(isLinked)) {
                this.logger.fine("Skipping 2FA for " + player.getName() + ": account not linked");
                return;
            }

            if (player.getAddress() != null) {
                String ip = player.getAddress().getAddress().getHostAddress();
                if (this.isWhitelisted(ip)) {
                    this.logger.fine("Skipping 2FA for " + player.getName() + ": IP whitelisted");
                    return;
                }
            }

            this.startVerification(player);
        });
    }
    private boolean isWhitelisted(String ip) {
        java.util.List<String> list = this.plugin.getConfig().getStringList("discord.two-factor.whitelisted-ips");
        return list.contains(ip);
    }

    @Override
    public void startVerification(Player player) {
        UUID uuid = player.getUniqueId();
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(999999));
        this.pendingCodes.put(uuid, code);
        this.pendingSince.put(uuid, System.currentTimeMillis());

        this.plugin.getLinkingManager().getLinkedAccount(uuid).thenAccept(account -> {
            if (account != null && this.plugin.getDiscordBot() != null && this.plugin.getDiscordBot().getJDA() != null) {
                this.sendTaggedTwoFactorRequest(account, code, player);
            }
        });

        player.sendMessage(this.plugin.getLocaleManager().getComponent("two-factor.freeze-message"));
    }

    private void sendTaggedTwoFactorRequest(LinkedAccount account, String code, Player player) {
        DiscordBot bot = this.plugin.getDiscordBot();
        if (bot == null || bot.getJDA() == null) {
            return;
        }

        bot.getJDA().retrieveUserById(account.getDiscordId()).queue(user -> {
            user.openPrivateChannel().queue(channel -> {
                EmbedBuilder embed = this.buildTwoFactorEmbed(player, this.serverName, code,
                        this.plugin.getConfigManager().getTwoFactorStatus("waiting"));

                String approveLabel = this.plugin.getConfigManager().getTwoFactorApproveButton();
                String denyLabel = this.plugin.getConfigManager().getTwoFactorDenyButton();

                String approveId = InteractionRouting.id(this.serverName, this.multiServerEnabled, "2fa_approve_" + code);
                String denyId = InteractionRouting.id(this.serverName, this.multiServerEnabled, "2fa_deny_" + code);

                Button approveBtn = Button.success(approveId, approveLabel);
                Button denyBtn = Button.danger(denyId, denyLabel);

                channel.sendMessageEmbeds(embed.build())
                        .addComponents(new MessageTopLevelComponent[]{ActionRow.of(approveBtn, new ActionRowChildComponent[]{denyBtn})})
                        .queue(success -> {
                            this.logger.info("Sent multi-server 2FA request to " + user.getName() + " for server " + this.serverName);
                            this.pendingMessageIds.put(player.getUniqueId(), success.getId());
                        }, error -> this.logger.warning("Failed to send 2FA DM: " + error.getMessage()));
            }, error -> this.logger.warning("Failed to open DM channel for 2FA: " + error.getMessage()));
        }, error -> this.logger.warning("Failed to retrieve Discord user for 2FA: " + error.getMessage()));
    }

    @Override
    public boolean isPending(UUID uuid) {
        return this.pendingCodes.containsKey(uuid);
    }

    @Override
    public boolean isVerified(UUID uuid) {
        return this.verifiedSessions.contains(uuid);
    }

    @Override
    public boolean verify(UUID uuid, String code) {
        String stored = this.pendingCodes.get(uuid);
        if (stored != null && stored.equals(code)) {
            this.pendingCodes.remove(uuid);
            this.pendingSince.remove(uuid);
            this.pendingMessageIds.remove(uuid);
            this.verifiedSessions.add(uuid);

            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(this.plugin.getLocaleManager().getComponent("two-factor.verification-approved"));
            }
            return true;
        }
        return false;
    }

    @Override
    public void deny(UUID uuid) {
        this.pendingCodes.remove(uuid);
        this.pendingSince.remove(uuid);
        this.pendingMessageIds.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && this.plugin.getConfigManager().isTwoFactorKickOnDeny()) {
            String reason = this.plugin.getConfigManager().getTwoFactorDenyKickMessage();
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (player.isOnline()) {
                    player.kick(MiniMessage.miniMessage().deserialize(reason));
                }
            });
        }
    }

    @Override
    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        this.pendingCodes.remove(uuid);
        this.pendingSince.remove(uuid);
        this.pendingMessageIds.remove(uuid);
        this.verifiedSessions.remove(uuid);
    }

    @Override
    public void rememberMessageId(UUID uuid, String messageId) {
        this.pendingMessageIds.put(uuid, messageId);
    }

    @Override
    public String takeMessageId(UUID uuid) {
        return this.pendingMessageIds.remove(uuid);
    }

    private EmbedBuilder buildTwoFactorEmbed(Player player, String server, String code, String status) {
        try {
            ConfigurationSection raw = this.plugin.getConfig().getConfigurationSection("discord.two-factor.embed");
            YamlConfiguration copy = new YamlConfiguration();
            if (raw != null) {
                for (String key : raw.getKeys(true)) {
                    if (raw.isConfigurationSection(key)) continue;
                    Object value = raw.get(key);
                    if (value instanceof String s) {
                        copy.set(key, s.replace("%player%", player.getName())
                                .replace("%player_uuid%", player.getUniqueId().toString())
                                .replace("%server%", server)
                                .replace("%code%", code)
                                .replace("%status%", status));
                    } else {
                        copy.set(key, value);
                    }
                }
            }
            return EmbedFactory.buildJda(copy);
        } catch (Exception e) {
            this.logger.warning("Failed to build 2FA embed: " + e.getMessage());
            return new EmbedBuilder().setTitle("2FA Verification").setDescription("Code: " + code);
        }
    }
}
