# uxmdiscord-multiserver

[**Türkçe**](README.md) · **English**

![Java 21+](https://img.shields.io/badge/Java-21%2B-F4BD60?style=flat-square)
![Paper API 1.21+](https://img.shields.io/badge/Paper_API-1.21%2B-55C7F4?style=flat-square)
[![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-4ADB9B?style=flat-square)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/SerhatDew/uxmdiscord-multiserver?display_name=tag&style=flat-square&color=F4BD60&label=Latest%20release)](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest)

Multi-server (BungeeCord / Velocity network) synchronization extension hook for uxmDiscordSync on Paper servers. Enables a coordinated single Discord bot gateway connection and shared database synchronization without conflict across backend sub-servers.

[**Download latest JAR**](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest/download/uxmdiscordsync-multiserver.jar) · [Release notes](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest)

[Features](#features) · [Requirements](#requirements) · [Architecture](#architecture) · [Installation](#installation) · [Configuration](#configuration) · [Building](#building) · [License](#license)

---

## Features

- **Coordinator Architecture:** Only one designated coordinator server connects to the Discord Gateway (JDA), handling slash commands and button interactions. Sub-servers remain passive to prevent duplicate bot instances and rate-limits.
- **Shared Account Linking:** Once a player links their Minecraft account with Discord on any server, the state is synchronized network-wide.
- **Flexible Reward Modes:** Supports `per-server` (rewards claimable once on each server) or `global` (rewards claimable only once across the whole network).
- **Distributed Server Leases:** Heartbeat-based database lease management prevents duplicate active instances with the same server name and detects stale nodes.
- **Cross-Server Command Queue:** SQL-based command queue dispatches Discord-triggered reward commands to target players on target sub-servers; unclaimed records are retried.
- **PlaceholderAPI & LuckPerms:** Optional soft-depend hooks for permissions and placeholders; their APIs are supplied by the server plugins and are not bundled into this JAR.

## Requirements

- Java 21 or newer.
- Paper (or forks) 1.21 / 1.21.1 / 1.21.4.
- Core plugin: `uxmDiscordSync` v2.1.0 (configured with shared MySQL/MariaDB database).
- Optional: `LuckPerms`, `PlaceholderAPI`.

## Architecture

```text
┌────────────────────────────────────────────────────────┐
│                   DISCORD GATEWAY                      │
└───────────────────────────┬────────────────────────────┘
                            │ (Single JDA connection)
               ┌────────────▼────────────┐
               │   COORDINATOR SERVER    │
               │        (e.g. hub)       │
               └────────────┬────────────┘
                            │ Shared SQL Database
          ┌─────────────────┴─────────────────┐
          ▼                                   ▼
┌───────────────────┐               ┌───────────────────┐
│   SUB-SERVER 1    │               │   SUB-SERVER 2    │
│  (e.g. survival)  │               │   (e.g. skyblock) │
└───────────────────┘               └───────────────────┘
```

1. **Coordinator Server:** Keeps the bot gateway alive and handles Discord interactions.
2. **Sub-Servers:** If the core plugin starts a Gateway connection, the hook stops it; sub-servers only read link state and commands from the shared database.

Command and reward rows receive a short SQL ownership lease before processing. A successful Bukkit operation is acknowledged afterward; if a process stops mid-flight, another server can retry after the lease expires. Because Bukkit commands are external side effects, a crash between execution and acknowledgement can duplicate an operation; the queue does not promise exactly-once semantics.

## Installation

1. Install `uxmDiscordSync` v2.1.0 on all Paper servers and point them to the **same MySQL/MariaDB database**.
2. Place `uxmdiscordsync-multiserver.jar` in each server's `plugins/` directory.
3. Start and stop the servers once to generate `plugins/uxmDiscordSync-MultiServer/config.yml`.
4. Designate only one server as the coordinator (`discord-coordinator-server`).

## Configuration

`plugins/uxmDiscordSync-MultiServer/config.yml`:

```yaml
multi-server:
  # Enable/disable multi-server synchronization
  enabled: true

  # Unique server identifier ("auto" uses server-name from uxmDiscordSync)
  server-name: "auto"

  # Name of the server that manages the active Discord bot
  discord-coordinator-server: "main"

  # Account linking mode:
  # - shared: Account linked on one server applies across the network
  # - separate: Each server requires separate linking
  link-mode: "shared"

  # Reward mode:
  # - per-server: Rewards can be claimed on each server
  # - global: Rewards can only be claimed once across the whole network
  reward-mode: "per-server"
```

## Building

To build from source, Maven 3 and Java 21 are required:

```sh
mvn clean package
```

The compiled jar will be located at `target/uxmdiscordsync-multiserver.jar`.

## License

This project is licensed under the **GNU General Public License v3.0**. See the [LICENSE](LICENSE) file for details.
