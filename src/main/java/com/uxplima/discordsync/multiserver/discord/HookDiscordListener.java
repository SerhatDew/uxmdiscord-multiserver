package com.uxplima.discordsync.multiserver.discord;

import com.uxplima.discordsync.UXMDiscordSync;
import com.uxplima.discordsync.managers.TwoFactorManager;
import com.uxplima.discordsync.models.LinkedAccount;
import com.uxplima.discordsync.multiserver.database.CommandQueueStore;
import com.uxplima.discordsync.utils.EmbedFactory;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Filtered Discord event listener that routes interactions correctly in a multi-server setup.
 */
public class HookDiscordListener extends ListenerAdapter {
    private final UXMDiscordSync plugin;
    private final String serverName;
    private final boolean isCoordinator;
    private final boolean multiServerEnabled;
    private final CommandQueueStore commandQueueStore;
    private final TwoFactorManager twoFactorManager;
    private final Logger logger;

    public HookDiscordListener(UXMDiscordSync plugin,
                               String serverName,
                               boolean isCoordinator,
                               boolean multiServerEnabled,
                               CommandQueueStore commandQueueStore,
                               TwoFactorManager twoFactorManager,
                               Plugin hookPlugin) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.isCoordinator = isCoordinator;
        this.multiServerEnabled = multiServerEnabled;
        this.commandQueueStore = commandQueueStore;
        this.twoFactorManager = twoFactorManager;
        this.logger = hookPlugin.getLogger();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String componentId = event.getComponentId();

        // 1. Link account button
        if (InteractionRouting.owns(this.isCoordinator, this.serverName, this.multiServerEnabled, componentId, "link_account")) {
            this.handleLinkButton(event);
            return;
        }

        // 2. 2FA Approve button
        if (componentId.startsWith("2fa_approve_")) {
            String code = InteractionRouting.extractTwoFactorCode(this.isCoordinator, this.serverName, componentId, "2fa_approve_");
            if (code == null) {
                // Not owned by this server, ignore silently
                return;
            }
            this.handleTwoFactorApprove(event, code);
            return;
        }

        // 3. 2FA Deny button
        if (componentId.startsWith("2fa_deny_")) {
            String code = InteractionRouting.extractTwoFactorCode(this.isCoordinator, this.serverName, componentId, "2fa_deny_");
            if (code == null) {
                // Not owned by this server, ignore silently
                return;
            }
            this.handleTwoFactorDeny(event, code);
        }
    }

    private void handleLinkButton(ButtonInteractionEvent event) {
        int maxLength = this.plugin.getConfigManager().getModalCodeMaxLength();
        int minLength = Math.min(this.plugin.getConfigManager().getModalCodeMinLength(), maxLength);

        TextInput codeInput = TextInput.create("verification_code", TextInputStyle.SHORT)
                .setPlaceholder(this.plugin.getConfig().getString("discord.linking.modal.code-input.placeholder"))
                .setMinLength(minLength)
                .setMaxLength(maxLength)
                .setRequired(true)
                .build();

        String modalId = InteractionRouting.id(this.serverName, this.multiServerEnabled, "link_modal");
        String modalTitle = this.plugin.getConfig().getString("discord.linking.modal.title");
        String labelText = this.plugin.getConfig().getString("discord.linking.modal.code-input.label");

        Modal modal = Modal.create(modalId, modalTitle)
                .addComponents(new ModalTopLevelComponent[]{Label.of(labelText, codeInput)})
                .build();
        event.replyModal(modal).queue();
    }

    private void handleTwoFactorApprove(ButtonInteractionEvent event, String code) {
        event.deferReply(true).queue(hook -> {
            String discordId = event.getUser().getId();
            this.plugin.getLinkingManager().getLinkedAccountByDiscordId(discordId).thenAccept(account -> {
                if (account != null) {
                    UUID uuid = account.getMinecraftUUID();
                    if (this.twoFactorManager != null && this.twoFactorManager.isPending(uuid)) {
                        if (this.twoFactorManager.verify(uuid, code)) {
                            hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-login-approved")).queue();
                            this.refreshTwoFactorMessage(event, account, code, "approved");
                        } else {
                            hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-session-expired")).setEphemeral(true).queue();
                        }
                    } else {
                        hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-no-verification")).setEphemeral(true).queue();
                    }
                } else {
                    hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-account-not-linked")).setEphemeral(true).queue();
                }
            });
        });
    }

    private void handleTwoFactorDeny(ButtonInteractionEvent event, String code) {
        event.deferReply(true).queue(hook -> {
            String discordId = event.getUser().getId();
            this.plugin.getLinkingManager().getLinkedAccountByDiscordId(discordId).thenAccept(account -> {
                if (account != null) {
                    UUID uuid = account.getMinecraftUUID();
                    if (this.twoFactorManager != null && this.twoFactorManager.isPending(uuid)) {
                        this.twoFactorManager.deny(uuid);
                        hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-login-denied")).queue();
                        this.refreshTwoFactorMessage(event, account, code, "denied");
                    } else {
                        hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-no-verification")).setEphemeral(true).queue();
                    }
                } else {
                    hook.sendMessage(this.plugin.getLocaleManager().getMessage("two-factor.discord-account-not-linked")).setEphemeral(true).queue();
                }
            });
        });
    }

    private void refreshTwoFactorMessage(ButtonInteractionEvent event, LinkedAccount account, String code, String status) {
        try {
            event.getMessage().editMessageComponents().queue();
        } catch (Exception ignored) {}
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!InteractionRouting.owns(this.isCoordinator, this.serverName, this.multiServerEnabled, event.getModalId(), "link_modal")) {
            return;
        }

        String submitted = event.getValue("verification_code") != null ? event.getValue("verification_code").getAsString() : "";
        String code = submitted.trim();
        String discordId = event.getUser().getId();
        String discordUsername = event.getUser().getName();
        String discordMention = event.getUser().getAsMention();

        event.deferReply(true).queue();

        this.plugin.getLinkingManager().linkAccount(code, discordId, discordUsername).thenAccept(account -> {
            if (account != null) {
                try {
                    EmbedBuilder embed = this.buildLinkEmbed("discord.linking.success-embed",
                            account.getMinecraftUsername(), account.getMinecraftUUID().toString(),
                            discordUsername, discordId, discordMention, account.getServer());

                    event.getHook().editOriginalEmbeds(embed.build()).queue();

                    Guild guild = event.getGuild();
                    Member member = event.getMember();
                    if (guild != null && member != null) {
                        for (String roleId : this.plugin.getConfigManager().getLinkedRoleIds()) {
                            Role role = guild.getRoleById(roleId);
                            if (role != null) {
                                guild.addRoleToMember(member, role).queue();
                            }
                        }
                    }

                    if (this.plugin.getConfigManager().isSyncOnLink()) {
                        Player player = Bukkit.getPlayer(account.getMinecraftUUID());
                        if (player != null && player.isOnline()) {
                            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                                this.plugin.getRankSyncManager().syncPlayer(player);
                            }, 20L);
                        }
                    }

                    if (this.plugin.getConfigManager().isSendDMOnLink()) {
                        event.getUser().openPrivateChannel().queue(ch -> {
                            ch.sendMessageEmbeds(embed.build()).queue(null, error -> {});
                        });
                    }
                } catch (Exception e) {
                    this.logger.severe("Error processing link success embed: " + e.getMessage());
                    this.sendErrorEmbed(event, "An internal error occurred while completing the link.");
                }
            } else {
                String desc = this.plugin.getConfig().getString("discord.linking.error-embed.description",
                        "Invalid or expired verification code. Please request a new code in Minecraft.");
                this.sendErrorEmbed(event, desc);
            }
        }).exceptionally(error -> {
            this.sendErrorEmbed(event, "An unexpected system error occurred during linking.");
            return null;
        });
    }

    private void sendErrorEmbed(ModalInteractionEvent event, String description) {
        try {
            ConfigurationSection raw = this.plugin.getConfig().getConfigurationSection("discord.linking.error-embed");
            YamlConfiguration copy = new YamlConfiguration();
            if (raw != null) {
                for (String key : raw.getKeys(true)) {
                    if (raw.isConfigurationSection(key)) continue;
                    copy.set(key, raw.get(key));
                }
            }
            if (description != null && !description.isEmpty()) {
                copy.set("description", description);
            }
            EmbedBuilder embed = EmbedFactory.buildJda(copy);
            event.getHook().editOriginalEmbeds(embed.build()).queue();
        } catch (Exception e) {
            this.logger.severe("Failed to send error embed: " + e.getMessage());
        }
    }

    private EmbedBuilder buildLinkEmbed(String configPath, String playerName, String playerUuid,
                                        String discordName, String discordId, String discordMention, String server) {
        ConfigurationSection raw = this.plugin.getConfig().getConfigurationSection(configPath);
        YamlConfiguration copy = new YamlConfiguration();
        if (raw != null) {
            for (String key : raw.getKeys(true)) {
                if (raw.isConfigurationSection(key)) continue;
                Object value = raw.get(key);
                if (value instanceof String s) {
                    copy.set(key, s.replace("%player%", playerName)
                            .replace("%player_uuid%", playerUuid)
                            .replace("%discord%", discordName)
                            .replace("%discord_id%", discordId)
                            .replace("%discord_mention%", discordMention)
                            .replace("%server_name%", server));
                } else {
                    copy.set(key, value);
                }
            }
        }
        return EmbedFactory.buildJda(copy);
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String commandName = event.getName().toLowerCase();

        if (commandName.equals("servercommand")) {
            this.handleServerCommand(event);
            return;
        }

        if (!this.isCoordinator) {
            // Secondary servers do not handle general slash commands
            return;
        }
    }

    private void handleServerCommand(SlashCommandInteractionEvent event) {
        if (!this.plugin.getConfigManager().isServerCommandEnabled()) {
            event.reply(this.plugin.getLocaleManager().getMessage("discord-commands.server-command-disabled")).setEphemeral(true).queue();
            return;
        }

        if (!this.plugin.getServerCommandManager().isAuthorized(event.getUser().getId())) {
            event.reply(this.plugin.getLocaleManager().getMessage("discord-commands.not-authorized")).setEphemeral(true).queue();
            return;
        }

        String targetServer = event.getOption("server") != null ? event.getOption("server").getAsString() : "";
        String command = event.getOption("command") != null ? event.getOption("command").getAsString() : "";

        if (targetServer.equalsIgnoreCase(this.serverName)) {
            boolean success = this.plugin.getServerCommandManager().executeCommand(command);
            if (success) {
                event.reply("✅ Command executed on **" + this.serverName + "**: `" + command + "`").setEphemeral(true).queue();
            } else {
                event.reply("❌ Command failed on **" + this.serverName + "**").setEphemeral(true).queue();
            }
        } else if (this.isCoordinator && this.commandQueueStore != null) {
            try {
                this.commandQueueStore.queue(targetServer, command);
                event.reply("⏳ Command queued for remote execution on **" + targetServer + "**: `" + command + "`").setEphemeral(true).queue();
            } catch (Exception e) {
                event.reply("❌ Failed to queue command for **" + targetServer + "**: " + e.getMessage()).setEphemeral(true).queue();
            }
        } else {
            // If not coordinator and not target server, ignore
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (!this.isCoordinator) {
            return;
        }
    }
}
