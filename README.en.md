# uxmdiscord-multiserver

[**Türkçe**](README.md) · **English**

![Java 21+](https://img.shields.io/badge/Java-21%2B-F4BD60?style=flat-square) ![Paper API 1.21.1](https://img.shields.io/badge/Paper_API-1.21.1-55C7F4?style=flat-square)

A Paper multi-server hook for `uxmDiscordSync`. Every Paper backend runs the core and this hook, sharing one SQL database for a single coordinated Discord Gateway, account linking, and command queues. Do not install it on Velocity/Bungee or place duplicate JARs on one server.

## Requirements and security

- Java 21+ and a working `uxmDiscordSync` 2.1.0 meeting its own license terms. The build targets Paper API 1.21.1; coordinator/secondary startup and Discord connectivity were observed on Leaf 26.2 / Java 25. This is not a guarantee for every Paper version or feature.
- The same **MySQL or MariaDB** database for every backend. The hook SQL is not designed for SQLite/flat files; PostgreSQL compatibility is not promised.
- LuckPerms and PlaceholderAPI are integrations; APIs are not bundled. Verified core 2.1.0 reflection scanning requires the LuckPerms API at startup, regardless of whether a permission feature is used. Install PlaceholderAPI when its placeholders are used.
- Keep bot tokens and database passwords secret; never commit them to GitHub or logs. Values below are placeholders.

## Installation

1. Put the same `uxmDiscordSync` version and `uxmdiscordsync-multiserver.jar` in `plugins/` on every backend, including the coordinator.
2. Configure core `plugins/uxmDiscordSync/config.yml`, then hook `plugins/uxmDiscordSync-MultiServer/config.yml`.
3. Use the same database, bot/guild, and Discord linking-channel values in every core installation. The hook creates its tables and applies missing-column migrations during startup.
4. Fully restart every server. There is no reliable supported reload command; lease/queue safety is not guaranteed during reload.

## Hook settings (five fields)

File: `plugins/uxmDiscordSync-MultiServer/config.yml`.

```yaml
multi-server:
  enabled: true
  server-name: "leaf1"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

| Field | Type / default | Effect |
|---|---|---|
| `multi-server.enabled` | boolean, `true` | Enables the hook; when `false`, the hook is passive. It is not a global switch that disables the core plugin or its Gateway session. |
| `multi-server.server-name` | string, `"auto"` | Backend identity. `auto` (case-insensitive), blank, or missing falls back to core `plugin.server-name`. Use a unique, consistent lowercase name of at most 32 characters for every backend; duplicate names collide on the lease. |
| `multi-server.discord-coordinator-server` | string, `"main"` | Name of the one backend owning Discord Gateway/JDA. Comparison is case-insensitive; use identical spelling for other SQL operations. |
| `multi-server.link-mode` | string, `shared` | `shared`: network-wide linking; `separate`: linking per server identity. Use one of the two supported spellings. |
| `multi-server.reward-mode` | string, `per-server` | `per-server`: reward per server; `global`: reward network-wide. Delivery crash boundaries are described below; this is not an exactly-once guarantee. |

Name uniqueness is not repaired automatically: a second process with the same name cannot acquire the `uxm_server_instances` lease. If the coordinator is absent, secondaries do not take over; local tasks may run, but an active coordinator is required for Discord interactions. There is no automatic coordinator selection/failover. The legacy `multi-server.coordinator-server` key is read as a fallback; use only `discord-coordinator-server` in new configuration. Missing enabled/link/reward/coordinator values can also be read from core config; write all five fields explicitly in the hook file to avoid ambiguity.

## Core uxmDiscordSync settings

Configure these core fields on every backend: `database.type`, connection fields under `database.mysql`, `plugin.server-name`, `discord.token`, `discord.guild-id`, and `discord.linking.channel-id`. Database/bot/guild/channel values are shared; server names are unique. Match the hook `server-name` to core `plugin.server-name`. The following is a **mergeable example fragment**; do not delete other generated config keys.

```yaml
# plugins/uxmDiscordSync/config.yml (minimal shape; preserve other keys from your core release)
plugin:
  server-name: "leaf1"
database:
  type: mysql
  mysql:
    host: "db.example.invalid"
    port: 3306
    database: "uxmdiscordsync"
    username: "replace-me"
    password: "replace-me"
discord:
  token: "REPLACE_WITH_PRIVATE_TOKEN"
  guild-id: "REPLACE_WITH_GUILD_ID"
  linking:
    channel-id: "1559952275605299211"
```

`db.example.invalid` is not a reachable address; replace it and credentials with an authorized MySQL/MariaDB account. The account needs read/write/delete access to hook tables and `CREATE`/`ALTER` for startup schema migration. Do not expose the database to the Internet without controls. Port is numeric; Discord snowflake IDs should remain quoted strings.

### Discord application and linking channel

1. Invite the bot to the correct Discord guild with the `bot` and `applications.commands` scopes.
2. Core 2.1.0 requests `GUILD_MEMBERS` and `MESSAGE_CONTENT` intents. Enable **Server Members Intent** and **Message Content Intent** under Developer Portal > Bot. Do not invent an `intents` config key.
3. Create a text channel for linking and copy its ID with Developer Mode. The selected example ID is `1559952275605299211`; use the ID of a channel the bot can access. An ID alone grants no permission.
4. Check View Channel, Send Messages, Embed Links, and (when the existing panel reads messages) Read Message History. For role assignment also grant **Manage Roles**, with the bot role above target roles. Do not grant Administrator unnecessarily.
5. Minecraft permissions belong to LuckPerms/core command rules; Discord roles/panels belong to core configuration. The hook does not create them.

`proxy_online` may remain a literal placeholder in original core 2.1.0; do not treat it as guaranteed network presence. A newer/base configuration may differ. This hook defines no role mappings—do not invent role keys without a verified core setting. Missing LuckPerms API can make core 2.1.0 reflection-based startup fields fail regardless of feature use.

### Two complete backend examples

**leaf1 (coordinator):** core `plugin.server-name: leaf1`, the token/guild/channel above and shared MySQL; hook:

```yaml
multi-server:
  enabled: true
  server-name: "leaf1"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

**leaf2 (secondary):** use core `plugin.server-name: leaf2`; keep database, guild, token, and channel identical to leaf1. Only the local hook identity changes; coordinator remains `leaf1`:

```yaml
multi-server:
  enabled: true
  server-name: "leaf2"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

If the secondary core starts Gateway, the hook stops it and the connection remains with the coordinator. This does not mean the core never attempts to start a session. The old 2.1.0 core may log the intentional shutdown as `Was shutdown trying to await status` / `Failed to start Discord bot`. Use coordinator readiness and the secondary hook role as the authority; do not hide an unexpected coordinator connection failure behind this note.

## Database, migrations, and crash behavior

The actual DDL creates `uxm_server_instances(server_key VARCHAR(32) PRIMARY KEY, owner VARCHAR(36), expires_at BIGINT)`, `uxm_network_link_codes` (code, UUID/user, `server`, expiry, Discord data, delivery and claim columns), and `uxm_network_commands` (target, command, executed, timestamp, and claim columns). If inline `INDEX` syntax fails, a no-inline-index fallback is used; missing claim columns are added with `ALTER TABLE`. This is MySQL/MariaDB-style DDL, not a PostgreSQL/SQLite support promise.

Server lease and command/reward claim duration are 30 seconds; the lease renews every 10 seconds. The command queue is polled approximately once per second. Failed or offline-player deliveries release their claim and can be picked up again. After a crash, the job can be reclaimed after expiry by a consumer using the same target identity; it is not randomly routed to another backend. A crash between a Bukkit side effect and SQL acknowledgement can repeat the operation; exactly-once is not guaranteed. Design reward commands to be idempotent where possible. Link-code generation makes at most 10 attempts.

## Validation and troubleshooting

1. Fully restart both backends. Confirm only the coordinator reports Gateway/listener readiness; the secondary reports local queue/manager readiness.
2. Confirm the three `uxm_*` tables exist and `server_key` values are unique. Fix lease errors by correcting duplicate names.
3. Link a test account on leaf1; in shared mode confirm the state on leaf2 and verify the selected reward mode.
4. Confirm a Discord button/command reaches its target leaf. Stop a backend and verify queue processing after lease expiry. Wrong token/guild/channel, a non-shared DB, missing LuckPerms API, or invalid license terms causes startup errors.

Field implementation: [MultiServerConfig.java](src/main/java/com/uxplima/discordsync/multiserver/config/MultiServerConfig.java), [packaged defaults](src/main/resources/config.yml), [ServerLease.java](src/main/java/com/uxplima/discordsync/multiserver/database/ServerLease.java), [NetworkLinkStore.java](src/main/java/com/uxplima/discordsync/multiserver/database/NetworkLinkStore.java), [CommandQueueStore.java](src/main/java/com/uxplima/discordsync/multiserver/database/CommandQueueStore.java).

## Startup states

The lifecycle uses the same gold/yellow robot banner as the other hooks and displays the real plugin name, version, and platform. After `Configuration loaded.`, it reports the server/role. A coordinator transitions from `Waiting` to timed success only after its JDA listener connects; a secondary reports success when local queues/managers are ready. `enabled: false` reports `Passive`. Dependency, database, lease, or listener failures report `Failed to start; plugin not ready.` with the original exception. A partial startup or cleanup failure does not print successful shutdown.

## Download and building from source

Ready-made files: [latest release](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest). Install only `uxmdiscordsync-multiserver.jar`; not the source ZIP or `original-` JAR. The `main` branch guide/code describes the latest commits; check release notes separately for older artifacts.

With Java 21+ and Maven 3, install a legitimately obtained `uxmDiscordSync` 2.1.0 JAR into your local Maven repository:

```sh
mvn install:install-file -Dfile=uxmDiscordSync-v2.1.0.jar -DgroupId=com.uxplima -DartifactId=uxmdiscordsync -Dversion=2.1.0 -Dpackaging=jar -DgeneratePom=true
mvn clean verify
```

Output is `target/uxmdiscordsync-multiserver.jar`. Core/third-party JARs are not distributed in this repository. Back up config/JARs before changes; never commit tokens, passwords, license values, or player data.

## License

GNU GPL v3.0 — [LICENSE](LICENSE).
