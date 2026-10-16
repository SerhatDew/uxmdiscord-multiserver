package com.uxplima.discordsync.multiserver.config;

import com.uxplima.discordsync.UXMDiscordSync;
import org.bukkit.configuration.file.FileConfiguration;

public class MultiServerConfig {
    private final boolean enabled;
    private final String serverName;
    private final String coordinatorServer;
    private final String linkMode;
    private final String rewardMode;
    private final boolean isCoordinator;

    public MultiServerConfig(FileConfiguration config, UXMDiscordSync uxm) {
        // Read multi-server settings: either from this hook's config, or from uxmDiscordSync's config
        this.enabled = config.getBoolean("multi-server.enabled",
                uxm.getConfig().getBoolean("multi-server.enabled", true));

        String sName = config.getString("multi-server.server-name", "auto");
        if (sName == null || sName.equalsIgnoreCase("auto") || sName.isBlank()) {
            sName = uxm.getConfigManager().getServerName();
        }
        this.serverName = sName;

        String coord = config.getString("multi-server.discord-coordinator-server",
                config.getString("multi-server.coordinator-server",
                        uxm.getConfig().getString("multi-server.discord-coordinator-server",
                                uxm.getConfig().getString("multi-server.coordinator-server", "main"))));
        this.coordinatorServer = coord;

        this.linkMode = config.getString("multi-server.link-mode",
                uxm.getConfig().getString("multi-server.link-mode", "shared"));

        this.rewardMode = config.getString("multi-server.reward-mode",
                uxm.getConfig().getString("multi-server.reward-mode", "per-server"));

        this.isCoordinator = !this.enabled || this.serverName.equalsIgnoreCase(this.coordinatorServer);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public String getServerName() {
        return this.serverName;
    }

    public String getCoordinatorServer() {
        return this.coordinatorServer;
    }

    public String getLinkMode() {
        return this.linkMode;
    }

    public String getRewardMode() {
        return this.rewardMode;
    }

    public boolean isCoordinator() {
        return this.isCoordinator;
    }

    public boolean isSharedMode() {
        return "shared".equalsIgnoreCase(this.linkMode);
    }

    public boolean isGlobalRewards() {
        return "global".equalsIgnoreCase(this.rewardMode);
    }
}
