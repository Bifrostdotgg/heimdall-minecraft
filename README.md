# Heimdall Whitelist Plugin

A Minecraft plugin that integrates with the Heimdall Discord bot to provide dynamic whitelist management. This plugin replaces traditional static whitelists with API-based dynamic checking, allowing for real-time whitelist decisions and Discord-based account linking.

## Supported Platforms

- **Paper/Spigot 1.8.8+** - Backend server plugin
- **Velocity 3.4.0+** - Proxy plugin for network-wide whitelist checking
- **BungeeCord / Waterfall** - Proxy plugin, same role as Velocity, for networks on the other proxy

The plugin can be used on either the backend servers, the proxy, or both depending on your network setup.

A proxy is always the **gatekeeper**: it owns the login decision for everything behind it, and
the backends behind it enforce everything that happens after. Both proxies do that job
identically and answer the same `/hdp` command tree — pick whichever one your network already
runs.

## Features

- **Dynamic Whitelist Checking**: Instead of relying on static whitelist files, the plugin checks with the Heimdall bot API on every connection attempt
- **Discord Account Linking**: Players link their Minecraft accounts to Discord through an authentication code system
- **Real-time Decisions**: Staff can approve/deny players through the Discord dashboard without server restarts
- **Fallback System**: If the API is unavailable, configurable fallback modes (allow, deny, or whitelist-only)
- **Performance Optimized**: Response caching and async processing to minimize server impact
- **Configurable Messages**: Customize all player-facing messages from the Heimdall dashboard — pushed to every connected server live, no config file editing or restart
- **LuckPerms Integration**: Sync Discord roles to LuckPerms groups, on backends and on either proxy — `net.luckperms:api` is the same artifact everywhere, so there is one implementation rather than one per platform
- **Discord Chat Bridge**: Relay in-game chat, joins, leaves and deaths into mapped Discord channels and Discord messages back in-game — nothing is ever stored, and the plugin never edits what a player typed
- **Multi-Platform Support**: One JAR, three platforms — Paper/Spigot, Velocity and BungeeCord

## Requirements

- **Paper/Spigot**: Java 8+, Paper/Spigot 1.8.8+ or compatible fork
- **Velocity**: Java 17+, Velocity 3.4.0+
- **BungeeCord/Waterfall**: Java 8+ — whatever your proxy build itself requires, which is Java 17
  for BungeeCord builds from 2025 onwards and Java 8 for older ones. Both are covered by the
  boot-smoke matrix
- Heimdall Discord Bot with API enabled
- Network connectivity between your Minecraft server and bot API

## Installation

Download the latest `heimdall-whitelist-X.X.X.jar` from the
[**Releases page**](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest).
The same JAR works on Paper, Velocity and BungeeCord: each platform reads its own descriptor out
of the one file and loads only its own classes.

### Paper/Spigot Installation (fresh install)

1. Download the latest `heimdall-whitelist-X.X.X.jar` from the [Releases page](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest)
2. Place the JAR file in your server's `plugins/` folder and start the server
3. On the Heimdall dashboard, open the guild's **Minecraft** page and mint a setup code for this server
4. In-game or from the console, run `/hd setup <code>` — the server connects immediately, no restart needed
5. There is no config file to hand-edit. Everything else — messages, cache windows, which modules are on, role sync, offense templates — is configured on the dashboard and pushed to the plugin over its tunnel. `plugins/Heimdall/bootstrap.yml` only holds the connection itself (see [Configuration](#configuration))
6. After a config change later, `/hd reload` re-reads `bootstrap.yml` and reconnects in place — you rarely need it, since dashboard settings apply live already

### Velocity Installation (fresh install)

1. Download the latest `heimdall-whitelist-X.X.X.jar` from the [Releases page](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest)
2. Place the JAR file in your Velocity proxy's `plugins/` folder and start the proxy
3. On the Heimdall dashboard, open the guild's **Minecraft** page and mint a setup code for this proxy
4. From the console (or in-game as an operator), run `/hdp setup <code>` — the proxy connects immediately, no restart needed
5. As above, there is no config file to hand-edit; `bootstrap.yml` holds only the connection

### BungeeCord / Waterfall Installation (fresh install)

1. Download the latest `heimdall-whitelist-X.X.X.jar` from the [Releases page](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest)
2. Place the JAR file in your proxy's `plugins/` folder and start the proxy
3. On the Heimdall dashboard, open the guild's **Minecraft** page and mint a setup code for this proxy
4. From the console (or in-game as an operator), run `/hdp setup <code>` — the proxy connects immediately, no restart needed
5. As above, there is no config file to hand-edit; `plugins/Heimdall/bootstrap.yml` holds only the connection

> Note the directory: BungeeCord names a plugin's data folder after the descriptor's **name**, so
> it is `plugins/Heimdall/` here as it is on Paper — while Velocity names it after the plugin
> **id** and uses `plugins/heimdall/`.

### Upgrading from v2

Stop the server, drop in the new JAR in place of the old one, and start it back up — no config
edits required. On its first boot, v3 looks for a v2 `config.yml` (Bukkit/Paper) or `config.json`
(Velocity) both in its own data directory and in the sibling directory a v2 install actually used —
`plugins/HeimdallWhitelist/` on Bukkit/Paper, `plugins/heimdall-whitelist/` on Velocity — and
migrates it automatically:

- Credentials, server id and the login-timing knobs (`timeout`, `retries`, `retryDelay`) are written
  into a new `bootstrap.yml`.
- Everything else the old config held (messages, cache windows, fallback mode, etc.) is staged for
  import into the dashboard, and lights up once the server is claimed — see the next step.
- The old config file is renamed to `*.v2-backup` (`config.yml.v2-backup` / `config.json.v2-backup`),
  never deleted.
- Until the server is claimed, it keeps running on v2-equivalent defaults.

**There is nothing to upgrade from on BungeeCord.** v2 shipped a Bukkit build and a Velocity
build and nothing else, so a BungeeCord proxy is always a fresh install — follow the section
above instead. If you copy a v2 directory across from a backend hoping it will be picked up, the
plugin says so on boot and tells you where to put the file.

Finish the upgrade by running `/hd setup <code>` (`/hdp setup` on Velocity) with a code minted on the
dashboard, same as a fresh install — this is what actually applies the imported settings. `/hwl` still
works post-upgrade as a deprecated alias that forwards to `/hd`/`/hdp` (see [Commands](#commands)), so
existing runbooks and macros are not broken by the upgrade.

### Keeping the plugin updated

The plugin checks your Heimdall bot for the latest published version on startup
and every few hours. When a newer version is available:

- A warning is logged to the server console.
- Admins (`heimdall.admin`) are notified as they join (if `updatesNotifyAdmins` is on).
- Run `/hd version` (`/hdp version` on Velocity) to see the installed vs. latest version on demand.
- Run `/hd update` (`/hdp update` on Velocity) to download the latest JAR:
  - **Paper**: it is placed in `plugins/update/` and applied automatically on the
    next server restart.
  - **Velocity and BungeeCord**: the running JAR is replaced in place and picked up on the next
    restart. Neither proxy has an `update/` staging folder, so on Windows — where an open JAR
    cannot be replaced at all — it is downloaded into the plugin's data folder instead and the
    console says to move it into `plugins/` yourself.

`/hwl version` / `/hwl update` still work as the deprecated alias, forwarding to the same commands.

Unlike most plugin behaviour, the update check has no dashboard equivalent — it has to keep working
on a server the bot cannot currently push config to — so it is controlled locally in `bootstrap.yml`:

```yaml
updatesCheckEnabled: true       # check for new versions on startup + interval
updatesNotifyAdmins: true       # message admins on join when an update is available
updatesCheckIntervalHours: 12   # how often to re-check (minimum 1)
```

## Configuration

v3 does not use a per-server `config.yml`/`config.json` the way v2 did. The only local file is a
small `bootstrap.yml`, holding just enough to connect. Everything else — player-facing messages,
cache windows, which modules are enabled, role-sync groups, offense templates, API fallback
behaviour — lives on the Heimdall dashboard's **Minecraft** page and is pushed to the plugin live
over its tunnel. Changing any of it does not need a server restart or a file edit.

### bootstrap.yml

Written by `/hd setup` (`/hdp setup` on Velocity) the first time a server is claimed, and lives at
`plugins/Heimdall/bootstrap.yml` on Paper, or the plugin's data directory on Velocity:

```yaml
endpoint: "https://api.bifrost.gg" # which Heimdall instance this server talks to
tokenId: "..." # public identifier for the guild API token
token: "..." # the signing secret — never share this
serverId: "..." # this server's identity within the guild
role: "auto" # auto | standalone | gatekeeper | enforcer — see ServerRole
debug: false # verbose logging; toggle live with /hd debug on|off
timeoutMs: 5000 # per-attempt login timeout
retries: 3 # total login attempts, including the first
retryDelayMs: 1000 # pause between login attempts
updatesCheckEnabled: true # self-updater — see "Keeping the plugin updated"
updatesNotifyAdmins: true
updatesCheckIntervalHours: 12
disabledModules: "" # space-separated local module overrides — see /hd disable
guildIdCache: "..." # cache of the last resolved guild; written by the plugin, not a setting
```

`token`, `tokenId`, `serverId` and `guildIdCache` are written by `/hd setup` and by the plugin
itself — don't hand-edit them. The login-timing fields (`timeoutMs`/`retries`/`retryDelayMs`) and
the update knobs are the ones you might reasonably tune by hand; they stay local because they shape
the very request that would otherwise fetch the dashboard's config, so the dashboard can't own them.

`endpoint` is the field whitelabel instances care about: most installs talk to the public
`https://api.bifrost.gg`, but a whitelabel instance has its own URL, and its setup codes are only
claimable there — pass it as the optional second argument, `/hd setup <code> <endpoint>`.

### Modules

Every feature is a module that can be switched on and off from the dashboard's **Minecraft** page
while the server is running — no restart, no file edit. `/hd modules` lists this build's set and
each one's current state; `/hd enable`/`/hd disable` are a **local** override that wins over the
dashboard until cleared (see [Admin Commands](#admin-commands)).

| Module | What it does | Runs on | Default |
| --- | --- | --- | --- |
| `whitelist` | The login gate, the local whitelist mirror and `/linkdiscord`. On a proxied network the gatekeeper owns the decision; a backend re-check is the `enforceOnBackend` setting | every role | on |
| `rolesync` | Applies the bot's Discord-role snapshots to a player's LuckPerms groups | every role | on |
| `offenses` | `/offend` and the escalation tiers the dashboard defines | every role | on |
| `console` | Streams the server console to the dashboard and runs commands from it | every role | off — it streams every log line |
| `bridge` | Relays Minecraft chat and join/leave/death into Discord, and mapped Discord channels back in-game | every role | on, and inert until channels are mapped |
| `health` | The TPS/memory/player-count snapshots that ride the heartbeat | every role | on |

Two notes worth having before you turn something on:

- **The chat bridge stores nothing.** Chat passes through memory and is gone — there is no history,
  no buffer beyond a small bounded relay queue, and no log line anywhere carries a message. It also
  never edits what a player typed: the text goes to the bot exactly as it was sent, and all
  formatting is the dashboard's template.
- **Who relays is a per-server choice**, and chat and player events are chosen separately —
  `relayChat` and `relayEvents`, both per server in the dashboard.
  - `relayChat` defaults to **on for each backend and off for the proxy**, so a network running the
    plugin everywhere does not send every line twice. A network that only runs the plugin on its
    proxy can flip that — the proxies observe chat and never block it, so relaying from the
    gatekeeper is safe.
  - `relayEvents` covers joins, leaves and deaths, and defaults to **on everywhere** — which is what
    every server already did before the setting existed, so upgrading changes nothing. It needs no
    per-role default the way chat does, because duplicate join/leave/death are dropped before they
    reach Discord on a best-effort basis: several servers relaying usually shows once, rather than
    always twice the way chat would. That drop is a backstop and not a guarantee — servers whose
    clocks disagree can still let a duplicate through — so **turn it on for exactly the servers you
    want announcing sessions** rather than leaving it on everywhere and relying on the backstop.
    "The proxy announces joins, the backends only relay chat" is the usual shape. If you map each
    server to its own channel, this is also what decides *which* channel a join appears in.
  - Deaths are the exception to any proxy-origin plan: no proxy has a death event, so those only
    ever come from backends.
  - Both take effect the moment you save — no restart, and no switching the module off and on.

### WebSocket Tunnel

Every platform keeps a persistent 2-way WebSocket connection to the Heimdall bot (derived from
`endpoint`, no inbound ports needed). It is core to how v3 works, not an optional extra — it is how
the dashboard's pushed configuration actually reaches the plugin, and it also carries
Discord→Minecraft role-sync, the dashboard's live console, player list and status, and remote
plugin updates. There is no setting to disable it. `/hd status` reports whether it is currently
connected; while it is down the plugin keeps running on the last configuration it received (or
built-in v2-equivalent defaults, for a server that has not been claimed yet).

## Commands

### Player Commands (every platform)

- `/linkdiscord` (alias `/link`) - request a code to link this Minecraft account to Discord
- `/offend <player> <offense> [notes]` - record an offence against a player and apply the
  escalation tier (requires `heimdall.offend`)

### Admin Commands

The admin tree is `/hd` (alias `/heimdall`) on Paper/Spigot backend servers, and `/hdp` (alias
`/heimdallproxy`) on **both** proxies, Velocity and BungeeCord — replace `/hd` with `/hdp` for
everything below when running on a proxy. It is the same tree, registered through each
platform's own command system, so a runbook does not have to know which proxy it is on.
`/hwl` (alias `/heimdallwhitelist`) still works everywhere as a **deprecated alias**: it forwards
to the same tree and prints a one-time-per-start warning telling you to switch.

- `/hd setup <code> [endpoint]` - claim this server with a setup code minted on the dashboard;
  connects immediately, no restart
- `/hd status` - version, role, serverId, endpoint, guild, tunnel state, per-module state, whitelist
  mirror stats, console tap health and update availability
- `/hd reload` - re-read `bootstrap.yml` and reconnect the tunnel in place
- `/hd modules` - list this build's modules and each one's state
- `/hd enable [module]` / `/hd disable [module]` - a **local** override, persisted in
  `bootstrap.yml`, that switches a module off/on even while the bot is unreachable and wins over
  the dashboard until cleared. With no argument, `/hd disable` targets the `whitelist` module — the
  "let everyone in" escape hatch, v2's global `/hwl disable` made per-module and made local
- `/hd test <player>` - run the real login check for a player without changing anything; reports
  the decision and which check made it
- `/hd cache stats|clear|cleanup|sync` - inspect, empty, sweep or refresh the local whitelist mirror
- `/hd offense reload|types` - refresh or list the offense types `/offend` accepts
- `/hd version` - show the installed version and check for a newer one
- `/hd update` - download the newest release; applied on the next restart
- `/hd debug on|off` - toggle debug logging, persisted to `bootstrap.yml`

**Permission Required**: `heimdall.admin` (defaults to OP)

## Permissions

- `heimdall.admin` - access to the `/hd`/`/hdp`/`/hwl` admin tree (default: OP)
- `heimdall.linkdiscord` - use `/linkdiscord` (default: true — everyone, since it only ever acts on
  the sender's own account)
- `heimdall.offend` - use `/offend` (default: OP)
- `heimdall.bypass` - skip Heimdall's per-player command cooldowns, e.g. the `/linkdiscord` cooldown
  (default: OP). This does **not** bypass the whitelist itself — the login-time bypass is a UUID
  list managed on the dashboard, since permissions aren't available yet at
  `AsyncPlayerPreLoginEvent`

## How It Works

### For Players

1. Player attempts to join your Minecraft server
2. If not whitelisted, they're shown instructions to join Discord
3. In Discord, they use `/link-minecraft <username>` to start linking
4. They try joining the server again to receive their authentication code
5. They confirm the code in Discord using `/confirm-code <code>`
6. Staff approve their request through the Discord dashboard
7. Player can now join the server normally

### For Staff

1. View pending whitelist applications in the Discord dashboard
2. See player information, Discord profile, and Minecraft username
3. Approve or deny applications with optional notes
4. Real-time updates - no server restarts needed
5. Manage all linked players through the web interface

### Technical Flow

1. **Connection Attempt**: Player tries to join
2. **API Request**: Plugin calls bot API with player info
3. **Decision Logic**: Bot checks database for player status
4. **Response**: API returns whitelist decision and any messages
5. **Action**: Plugin allows/denies connection based on response
6. **Caching**: Response cached briefly to reduce API load

## Troubleshooting

### Common Issues

**"Whitelist system is temporarily unavailable"**

- Check that your bot API is running and accessible
- Verify `endpoint` in `bootstrap.yml`, and check `/hd status` for the tunnel and connection state
- Check server logs for connection errors

**Players can't get auth codes**

- Ensure Discord integration is properly configured
- Check that the bot has necessary permissions in Discord
- Verify the server ID matches between plugin and bot

**Plugin not working after restart**

- Check console for configuration errors
- Ensure all required permissions are granted
- Verify Java version compatibility

### Debug Mode

Run `/hd debug on` (`/hdp debug on` on Velocity) to turn on verbose logging — it takes effect
immediately and is persisted to `bootstrap.yml` so it survives a restart. `/hd debug off` turns it
back off. Debug is local for a reason: it's the diagnostic you most need when the server can't
reach the dashboard to be told anything else.

## Error Handling & Fail-Open Behavior

The plugin implements a robust error handling system with configurable fallback behavior when the Heimdall bot API is unavailable.

### API Retry Logic

When the API is unreachable or returns errors, the plugin will:

1. **Retry** up to the configured attempt count (`retries` in `bootstrap.yml`, default 3)
2. **Wait between retries** (`retryDelayMs` in `bootstrap.yml`, default 1000ms)
3. **Fall back** to the fallback mode configured on the dashboard after all retries fail

### Fallback Modes

Unlike the retry timing above, the fallback mode itself is not a `bootstrap.yml` field — it's part
of the whitelist module's settings on the dashboard's Minecraft page, pushed to the plugin like
everything else module-related. If the bot can't be reached at all (including on a server that has
never been claimed), the plugin falls back to its last-known pushed value, or a safe built-in
default.

**Available modes:**

- **`"allow"`** (Recommended): **Fail-open** - Allow all players to join when API is down
  - ✅ Ensures server availability during API outages
  - ⚠️ Temporarily bypasses whitelist security
  - 📝 Players receive a message explaining the situation
  - 💡 Best for production servers where uptime is critical

- **`"whitelist-only"`**: Fall back to local Minecraft whitelist only
  - ✅ Maintains some security during outages
  - ❌ Only previously whitelisted players can join
  - 📝 New players cannot join during API downtime

- **`"deny"`**: **Fail-closed** - Deny all connections when API is down
  - ✅ Maximum security (no unauthorized access)
  - ❌ Server becomes inaccessible during API outages
  - 📝 All players see "API unavailable" message

### Production Recommendation

For production servers, set the fallback mode to `allow` on the dashboard's Minecraft page to ensure your server remains accessible even during:

- Network connectivity issues
- Bot maintenance/updates
- API server downtime
- Database connectivity problems

Players connecting during fail-open mode will receive a message encouraging them to link their Discord account when the system is restored.

### Performance Issues

If you're experiencing lag:

1. Increase the whitelist cache window on the dashboard's Minecraft page to reduce API calls, or
   run `/hd cache stats` to see how the local mirror is doing
2. Check your API server performance and network latency
3. Consider if your API server needs more resources

## Integration with Heimdall Bot

This plugin requires the Heimdall Discord bot to be properly configured:

1. **Environment Variables**: Set `ENABLE_MINECRAFT_SYSTEMS=true` in your bot
2. **API Configuration**: Ensure the bot's API server is running
3. **Database**: MongoDB should be accessible to the bot
4. **Discord Setup**: Bot needs appropriate Discord permissions

See the main Heimdall documentation for bot setup instructions.

## Development

### Building from Source

**Prerequisite: a JDK 21.** Gradle 8.14 does not run on JDK 25+, and the build
targets Java 8 bytecode via toolchains, so 21 is what you need installed — not
whatever your `JAVA_HOME` happens to point at. `gradle/gradle-daemon-jvm.properties`
pins the daemon to 21, so Gradle will find a detected JDK 21 on its own and tell
you clearly if there is not one.

```bash
git clone https://github.com/Bifrostdotgg/heimdall-minecraft.git
cd heimdall-minecraft
./gradlew build
```

The shipping JAR is `app/build/libs/heimdall-whitelist-X.X.X.jar`. It is a single
shadow jar that runs on Velocity, BungeeCord, Paper and Spigot 1.8.8+.

`./gradlew build` is the full gate, not just a compile: it builds every module at
its own bytecode level, runs the unit tests, runs the ArchUnit conformance rules
in `:conformance`, and then inspects the produced jar (`:app:verifyShadowJar`) for
too-new bytecode, unrelocated dependencies and mismatched plugin descriptors.

The plugin version has a single source of truth: `version` in `gradle.properties`.
The build generates `BuildConstants.VERSION` from it, substitutes it into
`plugin.yml` and `bungee.yml`, and the Velocity annotation processor writes it into
`velocity-plugin.json`. Release builds override it on the command line
(`./gradlew build -Pversion=3.0.0`), which is what the tag-triggered release
workflow does — so the tag, the jar name and every in-jar version reference cannot
drift apart.

### API Endpoints Used

- `POST /api/minecraft/connection-attempt` - Check if player should be whitelisted

### Dependencies

- Spigot/Paper API 1.20.1
- Gson 2.10.1 (bundled)

## Support

- **Issues**: Report bugs on [GitHub Issues](https://github.com/Bifrostdotgg/heimdall-minecraft/issues)
- **Discord**: Join our Discord server for community support

## License

This project is **source-available, not open source**. It is licensed under the
[PolyForm Shield License 1.0.0](https://polyformproject.org/licenses/shield/1.0.0)
— see the [LICENSE](LICENSE) file for the full text.

In short: you are free to download, build, run, and modify the plugin for your own
Minecraft servers and networks (including commercial/monetized ones). What you may
**not** do is use it to provide a product or service that competes with Heimdall.
