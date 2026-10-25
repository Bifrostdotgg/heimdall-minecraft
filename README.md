# Heimdall for Minecraft

The Minecraft half of [Heimdall](https://heimdall.gg), the Discord bot. One jar connects a Paper,
Folia, Velocity or BungeeCord server to your Discord server, and from then on the Heimdall dashboard
runs it:

- **Discord-linked whitelist.** Players link their Minecraft account to Discord with a short code,
  and the whitelist follows your Discord roles and approvals.
- **Role sync** between Discord roles and LuckPerms groups, in either direction.
- **Punishments.** Bans, IP bans, mutes, warns and kicks, enforced by the plugin and recorded as
  Heimdall cases, with appeals and a public bans page. It can run alongside LiteBans, import its
  history, and replace it.
- **Offence tiers.** `/offend` records a rule breach and applies the next step on its ladder.
- **Chat bridge** between in-game chat and Discord channels. Nothing is stored.
- **Live console and server status** on the dashboard.

There is no config file to edit. Every setting lives on the dashboard and is pushed to the plugin
live. Most updates install without a restart.

This README is for whoever runs the server. What each dashboard page does is documented at
**[heimdall.gg/docs/integrations/minecraft](https://heimdall.gg/docs/integrations/minecraft)**.

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Proxies and backends](#proxies-and-backends)
- [Features](#features)
- [Commands](#commands)
- [Permissions](#permissions)
- [Updates](#updates)
- [Configuration](#configuration)
- [When the bot is unreachable](#when-the-bot-is-unreachable)
- [Troubleshooting](#troubleshooting)
- [Upgrading from v2](#upgrading-from-v2)
- [Development](#development)
- [License](#license)

## Requirements

| Platform | Versions | Java |
| --- | --- | --- |
| Paper, Spigot and forks | 1.8.8 or newer | 8 or newer |
| Folia, Canvas | Whatever the server needs | Whatever the server needs (21 for current Folia) |
| Velocity | 3.4.0 or newer | 17 or newer |
| BungeeCord, Waterfall | Any current build | Whatever the proxy needs (17 for BungeeCord builds from 2025 on) |

It is the same jar on every platform. Each platform reads its own descriptor from it and loads only
its own classes.

Optional, picked up automatically when installed: **LuckPerms** (role sync), **LiteBans**
(punishment hook and import), **Floodgate** (Bedrock players), **ChatControl** (per-channel chat
bridge), **PremiumVanish** or **SuperVanish** (vanished staff stay out of Discord).

You also need a Discord server with Heimdall in it and the Minecraft feature turned on.

## Installation

1. Download `heimdall-whitelist-<version>.jar` from the
   [latest release](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest).
2. Put it in the server's or proxy's `plugins/` folder and start it.
3. On the dashboard, open **Minecraft > Networks**, add the server, and copy the setup code it
   gives you.
4. From the console or in game as an operator, run:

   ```
   /hd setup <code>      on Paper, Spigot or Folia
   /hdp setup <code>     on Velocity or BungeeCord
   ```

   The server connects straight away. No restart, no file to edit.

Whitelabel bots use the same address as everyone else, so the command above is all they need. Only
a self-hosted Heimdall (an enterprise contract) has its own address, and its setup codes only work
there. Pass it as the second argument: `/hd setup <code> <endpoint>`.

Run `/hd status` (`/hdp status`) to check it connected. On a network, install the plugin on the
proxy and on every backend, and claim each one with its own code.

The plugin keeps its files in `plugins/Heimdall/` on Paper and BungeeCord, and in
`plugins/heimdall/` on Velocity, which names the folder after the plugin id.

## Proxies and backends

Every install has a **role**, worked out automatically from the platform and the server's proxy
forwarding settings:

| Role | Where | What it does |
| --- | --- | --- |
| `standalone` | A server with no proxy in front | Everything |
| `gatekeeper` | Velocity or BungeeCord | Decides who may log in to the network, enforces bans, announces punishments |
| `enforcer` | A backend behind a proxy | Enforces what happens in game: mutes and blocked commands |

Role sync, `/offend` and the chat bridge run on every role.

You can override the role with `role:` in `bootstrap.yml` (it takes effect on restart). The one
case that needs it: **if your proxy does not run Heimdall, set `role: standalone` on each
backend.** Otherwise a backend with proxy forwarding on counts itself as an enforcer, and an
enforcer leaves announcements, IP bans and join-time role sync to a proxy that is not there.

Things worth knowing on a network:

- **The proxy owns the login decision.** By default backends check logins again too
  (`enforceOnBackend`, a whitelist setting on the dashboard). Turn that off and backends leave it to
  the proxy, which saves a request per server switch.
- **Mutes are enforced on the backend.** A proxy cannot cancel signed chat, so a muted player is
  stopped by the server they are on.
- **Only the outermost server announces punishments**, so a ban is not announced twice. On a
  network that is the proxy, which means the announcement permissions that matter are the proxy's.

## Features

Each feature is a module you switch on and off per server on the dashboard, live. `/hd modules`
shows what is running.

| Module | What it does |
| --- | --- |
| `whitelist` | The login gate and the local whitelist mirror |
| `rolesync` | Discord roles to LuckPerms groups and back |
| `punishments` | Bans, mutes, warns and kicks, in the mode the dashboard sets |
| `offenses` | `/offend` and its escalation tiers |
| `bridge` | Chat, joins, leaves and deaths to Discord, and Discord messages back in game |
| `console` | Streams the server console to the dashboard and runs commands from it |
| `health` | TPS, memory and player counts for the dashboard |

`/hd disable <module>` and `/hd enable <module>` are a local override that wins over the dashboard
until you clear it, and works while the bot is unreachable. `/hd disable` on its own turns off the
whitelist: the "let everyone in now" switch.

### Account linking

A player links their Minecraft account to Discord with a six-digit code:

1. They start in Discord, from the account-linking panel or `/link-minecraft`.
2. The code reaches them in game. A whitelisted server shows it on the disconnect screen. A server
   that lets anyone join shows it when they join: as a dialog with a copy button on Paper 1.21.7+
   with a 1.21.6+ client, and otherwise as a chat message, click-to-copy on Paper and Spigot and
   plain text on Velocity and BungeeCord. Bedrock players always get the chat message.
   `/linkdiscord` (or `/link`) shows it on request.
3. They press **Confirm Code** in Discord and enter it.

Players can also start in game with `/linkdiscord`. Linking works whatever modules are on, so an
open server can use it for role sync alone.

The prompt names the Discord account that asked and tells the player never to give the code to
anyone, because whoever enters it gets the link.

### Role sync

Needs LuckPerms on the server that applies it. Mappings are set on **Minecraft > Settings > Role
sync**, and each one has a direction.

- **Discord to Minecraft.** Groups are applied when the player joins and whenever their Discord
  roles change.
- **Minecraft to Discord.** A rank a player gets in game, from a store or `/lp user ... parent`,
  gives them the Discord role. Changes are reported as they happen, and anything missed is caught
  up at their next join. This direction never removes a group in game, so a bought rank cannot be
  taken away by a sync.

**Known gap.** If LuckPerms runs only on the backends and the backends leave logins to the proxy,
nothing syncs groups at join: the proxy has no LuckPerms to write to and the backends do not ask.
Role changes still apply as they happen. To get the join-time sync back, run LuckPerms on the
proxy, or run the whitelist module on the backends with `enforceOnBackend` on. With the whitelist
off on the backends, `enforceOnBackend` alone does not help.

### Punishments

The dashboard's **Minecraft > Enforcement** page sets one of three modes for the guild:

| Mode | In game |
| --- | --- |
| **Offenses only** (default) | Heimdall enforces nothing. `/offend` tiers run their configured commands, usually your ban plugin's |
| **Alongside your ban plugin** | LiteBans stays in charge. Every punishment and lift it makes is copied to Heimdall, and lifting one from Heimdall (the dashboard, `/mc-punish`, an accepted appeal) lifts it in LiteBans too |
| **Heimdall runs bans** | The plugin enforces everything itself: logins, chat, blocked commands and IP bans |

The `/hd` punishment commands issue punishments only in **Heimdall runs bans**. In the other modes
the lookups (`history`, `banlist`, `dupeip`) and `import` still work.

**Moving off LiteBans.** Run alongside it first, so staff see the history while nothing changes in
game. Then preview the import with `/hd import --dry-run` (or **Preview import** on the dashboard),
run `/hd import` on every server that has its own LiteBans database, switch to **Heimdall runs
bans**, and remove LiteBans. The importer also reads AdvancedBan, BanManager and vanilla
`banned-players.json`. IP addresses are hashed on the server before anything is sent. The full
runbook is in the docs:
[Cutover from LiteBans](https://heimdall.gg/docs/integrations/minecraft#cutover-from-litebans).

**Mutes.** A muted player sees the full mute screen once per mute each session, and a one-line
reminder after that. They also cannot run the commands on the blocked list (`msg`, `tell`, `me`, `r`, `whisper`
and `w` by default, editable on the dashboard). An entry blocks that command, its namespaced form
and anything typed after it, a whole word at a time: `ch send` blocks `/ch send hi` but not
`/ch join`, and `me` does not block `/menu`.

**Staff alerts.** Staff holding `heimdall.punishments.notify.muted` are told when a muted player
tries to talk or use a blocked command, on the server that refused it, at most once every 30
seconds per mute. `heimdall.punishments.notify.muted.content` adds what they tried to say. Nobody
holds either by default. What the player said is never stored or sent anywhere.

**Screens** (what a banned or muted player reads) and the announcement lines are edited on the
dashboard, not on the server.

**If the bot is down**, every server checks its own copy of active punishments, so bans and mutes
keep working. A punishment issued during an outage applies on that server at once and syncs when
the connection comes back.

### Offences

`/offend <player> <offense> [notes]` records a breach of a rule set up on **Minecraft > Rules** and
applies the player's next tier. The tier's command runs as the console. An offline player is
looked up through the bot, so that needs the bot to be reachable.

### Chat bridge

Mappings are set on **Minecraft > Chat Bridge**. The bridge keeps nothing: chat passes through
memory and is gone, and the text is sent exactly as typed. Two per-server settings decide which
servers relay:

- `relayChat` is on for backends and off for the proxy by default, so a network running the plugin
  everywhere does not send every line twice. A network that runs it only on the proxy can turn it
  on there.
- `relayEvents` (joins, leaves, deaths) is on everywhere by default. Turn it on for exactly the
  servers you want announcing, usually the proxy. Deaths only ever come from backends.

**Vanished staff stay vanished** on any server that can see the vanish. With PremiumVanish or
SuperVanish, a vanished player's join, leave and death are never posted. Every server with
`relayEvents` on must be able to see it, so if PremiumVanish runs only on the backends, turn
`relayEvents` off on the proxy. EssentialsX, CMI and similar vanish plugins are honoured on a
best-effort basis on backends only. `announceVanishToggles` (off by default) posts a leave and a
join when someone vanishes or reappears.

**ChatControl channels** are honoured: only the channels ticked on a mapping are relayed. A proxy
cannot tell channels apart, so let backends relay chat when ChatControl channels are on.

## Commands

### Players

| Command | What it does | Node |
| --- | --- | --- |
| `/linkdiscord` (`/link`) | Show a code to link this account to Discord | `heimdall.linkdiscord` (everyone) |
| `/offend <player> <offense> [notes]` | Record an offence and apply its tier | `heimdall.offend` |

### Admin

`/hd` (alias `/heimdall`) on Paper, Spigot and Folia. `/hdp` (alias `/heimdallproxy`) on Velocity
and BungeeCord, with the same subcommands. All of it needs `heimdall.admin`, except the
[punishment verbs](#punishment-commands), which also accept their own node. All of it works from the
console. `/hwl` still works as a deprecated alias from v2.

| Command | What it does |
| --- | --- |
| `setup <code> [endpoint]` | Claim this server with a code from the dashboard |
| `status [bot\|modules\|server\|all]` | One-screen health summary, or the detail behind it. `all` is for support tickets |
| `reload` | Re-read `bootstrap.yml` and reconnect |
| `modules` | List modules and what each is doing |
| `enable [module]`, `disable [module]` | Local override of the dashboard. No module means `whitelist` |
| `test <player>` | Run the real login check for a player without changing anything |
| `cache stats\|clear\|cleanup\|sync` | Inspect, empty, sweep or refresh the local whitelist mirror |
| `offense reload\|types` | Refresh or list the offence types `/offend` accepts |
| `import [--dry-run]` | Import punishment history from LiteBans and others. `--dry-run` writes nothing |
| `case note <number> <text>` | Add a note to a Minecraft case, signed by you. Players only |
| `identity [adopt\|reset confirm]` | Show or rebind which machine these credentials belong to |
| `version` | Show the installed and latest versions |
| `update` | Install the latest release, live when it can be |
| `swap` | Swap in a core you placed at `core/staged.jar` yourself |
| `debug on\|off` | Verbose logging, remembered across restarts |

### Punishment commands

```
/hd ban <player> [duration] [reason] [-s|-p] [-h]
```

| Verb | Shape | Root alias registers on |
| --- | --- | --- |
| `ban`, `ipban` | `<player> [duration] [reason]` | proxy |
| `tempban` | `<player> <duration> [reason]` | proxy |
| `mute` | `<player> [duration] [reason]` | backend |
| `tempmute` | `<player> <duration> [reason]` | backend |
| `kick`, `warn` | `<player> [reason]` | proxy |
| `unban`, `unwarn` | `<player> [reason]` | proxy |
| `unmute` | `<player> [reason]` | backend |
| `rollback` | `<player>`, lifts their latest active punishment | proxy |
| `history`, `staffhistory`, `banlist` | `<player>`, `<staff>`, nothing | proxy |
| `dupeip`, `iphistory` | `<player>` | proxy |

A standalone server registers all of them.

- **Durations** go anywhere after the player: `/hd ban Steve 7d griefing` and
  `/hd ban Steve griefing 7d` are the same ban. Units are `s`, `m`, `h`, `d`, `w`, `mo` and `y`,
  compoundable (`1d12h`), and `perm` or no duration means permanent. Ten years is the longest
  allowed. A kick or a warn takes no duration, and one that has one is refused.
- **Flags** go anywhere too. `-s` is silent (only staff are told), `-p` is public, and `-h` is
  hidden (kept out of other staff's lookups, and implies `-s`).
- **Root aliases.** With **Register root command aliases** on, in **Heimdall runs bans** mode only,
  the verbs also register as `/ban`, `/mute` and so on. The table says where each one lands on a
  network. Leave it off while LiteBans is installed, or the two fight over `/ban`.

## Permissions

Each punishment node gives its verbs under both spellings: `heimdall.punishments.ban` allows
`/hd ban` and `/hd tempban`, and `/ban` and `/tempban` when root aliases are on. A moderator with
only punishment nodes sees just those verbs in `/hd` help and tab completion, and nothing else in
the admin tree. `heimdall.admin` covers every verb as well.

On Paper this needs a restart after updating to take effect, because Bukkit reads the `/hd` gate
from `plugin.yml` when the plugin loads. On the proxies it applies as soon as the update does.

| Node | Gives | Default |
| --- | --- | --- |
| `heimdall.admin` | The whole `/hd` tree. Also grants `notify` and `silent` | op |
| `heimdall.linkdiscord` | `/linkdiscord` | everyone |
| `heimdall.offend` | `/offend` | op |
| `heimdall.bypass` | Skip command cooldowns such as `/linkdiscord`'s. Not a whitelist bypass: that is a list on the dashboard | op |
| `heimdall.punishments.ban` | `/ban`, `/tempban`, with or without `/hd` | op |
| `heimdall.punishments.ipban` | `/ipban`, with or without `/hd` | op |
| `heimdall.punishments.mute` | `/mute`, `/tempmute`, with or without `/hd` | op |
| `heimdall.punishments.kick` | `/kick`, with or without `/hd` | op |
| `heimdall.punishments.warn` | `/warn`, with or without `/hd` | op |
| `heimdall.punishments.unban` | `/unban`, `/unmute`, `/unwarn`, `/rollback`, with or without `/hd` | op |
| `heimdall.punishments.history` | `/history`, `/staffhistory`, `/banlist`, with or without `/hd` | op |
| `heimdall.punishments.dupeip` | `/dupeip`, `/iphistory`, with or without `/hd` | op |
| `heimdall.punishments.notify` | See silent punishments announced | op |
| `heimdall.punishments.silent` | Use `-s` or `-p` against the guild's default | op |
| `heimdall.punishments.hidden` | Use `-h`, and see hidden punishments in lookups and announcements. Not implied by `heimdall.admin` | op |
| `heimdall.punishments.notify.muted` | Alert when a muted player tries to talk | nobody |
| `heimdall.punishments.notify.muted.content` | Add what they tried to say to that alert | nobody |

Without the node, `-s`, `-p` and `-h` are refused rather than quietly dropped. A flag that matches
the guild's default needs no node.

**On a proxy, grant these through your permissions plugin.** The defaults above come from
`plugin.yml`, which Velocity and BungeeCord do not read, so there nobody but the console holds a
node until you grant it. `heimdall.admin` is the exception for announcements: an admin sees silent
punishments everywhere.

That includes `heimdall.linkdiscord`: on Velocity and BungeeCord, grant it to your default group,
or the proxy will not let players run `/linkdiscord`.

These nodes do not inherit from `litebans.*`. Grant them before you remove LiteBans, not after.

## Updates

The plugin checks for a new release at startup and every 12 hours, logs it, and tells admins as
they join. `/hd version` shows where you stand, and `/hd update` installs the latest release.

**Most updates apply live.** The jar is a small permanent shell around a core that can be swapped
while the server runs. When a release's checksum matches the one GitHub publishes, `/hd update`
swaps the new core in: the bot connection drops for a few seconds, logins wait up to five seconds,
and bridged chat in that window is not relayed. The jar is installed for the next restart as well,
so the update survives one.

An update needs a restart instead when the release has no published checksum or changes the shell
itself. `/hd update` says which. The jar then goes into `plugins/update/` on Paper, and replaces the
running jar on the proxies (on Windows, where a running jar cannot be replaced, it is downloaded
into the plugin folder and the console tells you to move it).

Downloads are only accepted from this repository's releases. A fork can point the updater at its
own with `updatesReleaseRepo: owner/name` in `bootstrap.yml`.

If a core ever fails to start, the server refuses logins ("This server cannot check logins right
now") rather than letting everyone in, and tells online admins. Restart, or stage a working core and
run `/hd swap`.

## Configuration

Everything except the connection itself is configured on the dashboard and pushed to the server
live. The only local file is `bootstrap.yml`, which `/hd setup` writes:

```yaml
endpoint: "https://api.bifrost.gg" # the Heimdall instance this server talks to
tokenId: "..."                    # written by /hd setup
token: "..."                      # written by /hd setup. Keep it secret
serverId: "..."                   # written by /hd setup
role: "auto"                      # auto | standalone | gatekeeper | enforcer
identityCheck: "auto"             # auto | strict | off, see Troubleshooting
debug: false                      # toggle with /hd debug on|off
timeoutMs: 5000                   # per-attempt timeout for a login check
retries: 3                        # login check attempts, including the first
retryDelayMs: 1000                # pause between attempts
updatesCheckEnabled: true
updatesNotifyAdmins: true
updatesCheckIntervalHours: 12     # minimum 1
disabledModules: ""               # set by /hd enable and /hd disable
instanceFingerprint: "..."        # written by the plugin
guildIdCache: "..."               # written by the plugin
```

The settings here are the ones that have to work before the dashboard can be reached: the login
timing, the update checker, debug logging and the local module override. Do not hand-edit the
token, ids or fingerprint. After editing anything else, `/hd reload` applies it, except `role`
and `updatesCheckIntervalHours`, which need a restart.

## When the bot is unreachable

The plugin keeps running on the last configuration it received. Punishments keep working from the
local copy. For logins, the whitelist's **fallback mode** on the dashboard decides:

| Mode | When the bot cannot be asked |
| --- | --- |
| `whitelist-only` (default) | Players in the local mirror get in. The mirror is a full copy of the whitelist, kept current while connected, so whitelisted players do not notice an outage |
| `allow` | Everyone gets in. The server stays open, and so does the whitelist |
| `deny` | Nobody gets in |

The default is the right choice for almost every server. Each login check is tried `retries` times
before the fallback applies.

## Troubleshooting

Start with `/hd status`. It has one line per part of the plugin and says how to fix anything that
is wrong. `/hd status all` is the version to paste into a support ticket, and `/hd debug on` turns
on verbose logging.

**"Whitelist system is temporarily unavailable" on join.** The bot could not be asked and the
player is not in the mirror. Check the bot and tunnel lines of `/hd status`, and that the server can
reach `endpoint` over HTTPS. No inbound ports are needed.

**A player is let in or refused and you do not know why.** `/hd test <player>` runs the real check
without changing anything and says which part decided.

**"identity mismatch".** This `bootstrap.yml` was written on a different machine, usually because a
server folder was copied or a backup restored. Two copies with the same identity knock each other
offline. `/hd identity` shows both fingerprints. If you moved the server, run `/hd identity adopt`.
If this is a copy and the original still runs, run `/hd identity reset confirm` on the copy and set
it up with a new code. By default (`identityCheck: auto`) the plugin only refuses to connect when it
can prove the machine changed, such as a different game panel server id, and otherwise warns.

**Role sync does nothing at join.** See the [known gap](#role-sync).

**The punishment commands say native punishments are not in replace mode.** The guild is not in
**Heimdall runs bans** mode. Change it on the Enforcement page.

## Upgrading from v2

Replace the old jar with the new one and start the server. Remove the v2 jar: the two have different
names, so both would load.

On first boot the plugin finds the v2 config (`plugins/HeimdallWhitelist/config.yml` on Paper,
`plugins/heimdall-whitelist/config.json` on Velocity), writes a `bootstrap.yml` from its credentials,
renames the original to `*.v2-backup`, and stages its other settings for the dashboard. Finish with
`/hd setup <code>`, which is what applies them. Until then the server runs on v2-equivalent
defaults. BungeeCord had no v2 build, so a BungeeCord proxy is always a fresh install.

## Development

Building needs **JDK 21** (Gradle 8.14 does not run on 25+; the build targets Java 8 bytecode
through toolchains).

```bash
git clone https://github.com/Bifrostdotgg/heimdall-minecraft.git
cd heimdall-minecraft
./gradlew build
```

The jar is `app/build/libs/heimdall-whitelist-<version>.jar`. `./gradlew build` is the whole gate:
unit tests, the ArchUnit rules in `:conformance`, and `:app:verifyShadowJar`, which checks the jar
for too-new bytecode, unrelocated dependencies and mismatched descriptors. `smoke/` boots the jar on
real servers in Docker against `stub-bot/`, a fake bot that speaks the real wire contract.

The version comes from `version` in `gradle.properties`. Releases are cut by pushing a `v*` tag,
which builds the jar and creates the GitHub release. Do not create the release by hand first.

**Where things are.** The platform loads a shell (`shell-*`) that never changes at runtime. The
shell loads `core`, the platform adapters (`platform-*`) and the feature modules (`module-*`) as one
swappable core. `api` is the public API. Deliberate behaviour changes from v2, and the reasoning
behind most design choices, are recorded in [docs/v2-departures.md](docs/v2-departures.md).

### API for other plugins

Compile against `com.heimdall.api` (the `:api` module) and soft-depend on Heimdall. Both objects
belong to the shell, so they survive a core swap and can be cached.

- `HeimdallPunishments`: `isMuted`, `isMuteHidden`, `isBanned`, `isFrozen`, `activePunishment` and
  `isAvailable`. Memory reads only, so safe from async chat threads. Answers "not punished" while
  no core or no punishments module is running.
- `HeimdallTunnel`: publish to, request from and subscribe to the bot over Heimdall's connection.

```java
HeimdallPunishments punishments = HeimdallPunishmentsProvider.get();
// or, on the Bukkit family:
// Bukkit.getServicesManager().load(HeimdallPunishments.class)

if (punishments != null && punishments.isMuted(player.getUniqueId())) {
    // refuse the message
}
```

## Support

Report bugs on [GitHub Issues](https://github.com/Bifrostdotgg/heimdall-minecraft/issues).

## License

Source-available, not open source: [PolyForm Shield 1.0.0](https://polyformproject.org/licenses/shield/1.0.0),
full text in [LICENSE](LICENSE). You may download, build, run and modify the plugin for your own
servers and networks, commercial ones included. You may not use it to offer a product or service
that competes with Heimdall.
