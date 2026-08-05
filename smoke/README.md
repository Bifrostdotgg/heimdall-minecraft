# Boot-smoke matrix

Does the one jar load, do real work, and unload cleanly, on real servers across the whole
supported range?

From phase 1c the plugin actually builds its runtime on enable — role detection, a log4j console
tap, event listeners, a registered command — with no bot anywhere to talk to. These rows are what
prove that not-configured path stays green on every supported server, which is the state every
fresh install is in.

That is not a question the Gradle build can answer.
`:app:verifyShadowJar` checks bytecode levels, relocations and descriptors — everything that is
visible by reading the jar. What it cannot see is a server refusing the plugin for a reason that
only exists at runtime: a missing `api-version`, a loader that rejects a descriptor it parses
differently, a class that resolves fine on Java 21 and not on Java 8. Only starting the servers
answers that.

```bash
./gradlew build              # produce the jar first
smoke/run.sh                 # every row, sequentially (~15 min cold)
smoke/run.sh paper-1.8.8     # one row — what CI runs, one row per runner
smoke/run.sh --list
```

There are **two** scenarios, and they answer different questions.

| Script | Question | Rows |
| --- | --- | --- |
| `run.sh` | Does the jar load, work and unload with **no bot anywhere**? | eight, the whole supported range |
| `connected.sh` | Pointed at a real bot, does the plugin **actually talk to it**? | six — one steady-state row per platform, plus the three flow rows |

Both are CI gates. `connected.sh`'s rows run as their own job, and both self-tests run in the
Docker-free job that gates the matrix.

`connected.sh` is phase 1d's addition and is described in its own section below.

Needs Docker (or a Docker-API-compatible daemon — it is developed against both Docker and Podman)
and network access to pull the images and the server jars. Runs on CI's Ubuntu and, unchanged,
under Git Bash on Windows.

| Variable | Default | |
| --- | --- | --- |
| `SMOKE_JAR` | newest `app/build/libs/heimdall-whitelist-*.jar`, else `dist/` | CI points this at the downloaded artifact. |
| `SMOKE_BOOT_TIMEOUT` | `240` | Seconds to wait for the enable banner. A cold 1.8.8 boot generates its spawn area first. |
| `SMOKE_STOP_TIMEOUT` | `120` | Seconds to wait for a graceful stop. |
| `SMOKE_KEEP` | `0` | `1` leaves containers and `smoke/.work/<row>/server.log` behind. |

## The matrix

> **`smoke/run.sh --list` is the source of truth.** The `ROWS` array in that script is what CI
> reads to build its job matrix; the table below is informational and can fall behind. If the two
> ever disagree, the script wins.

| Row | Image | Server | Java | Why this row |
| --- | --- | --- | --- | --- |
| `paper-1.8.8` | `itzg/minecraft-server:2026.7.2-java8` | Paper 1.8.8 | 8 | The floor. Catches anything accidentally compiled or shaded above release 8. |
| `paper-1.12.2` | `itzg/minecraft-server:2026.7.2-java8` | Paper 1.12.2 | 8 | Mid-legacy: same JRE, different server generation and plugin loader. |
| `paper-1.16.5` | `itzg/minecraft-server:2026.7.2-java11` | Paper 1.16.5 | 11 | Pre-modern, on a mid JRE. |
| `paper-1.21.8` | `itzg/minecraft-server:2026.7.2-java21` | Paper 1.21.8 | 21 | Current. Catches a jar that only works on old servers. |
| `velocity-3.4.0` | `itzg/mc-proxy:2026.7.1-java17` | Velocity 3.4.0 | 17 | The compile-target floor: the API version `:platform-velocity` is pinned to, on the Java level it targets. |
| `velocity-3.5.1` | `itzg/mc-proxy:2026.7.1-java21` | Velocity 3.5.1 | 21 | Current Velocity — what customers actually run. |
| `bungee-2000` | `itzg/mc-proxy:2025.9.0-java8` | BungeeCord build 2000 | 8 | The last era of BungeeCord compiled at release 8 — the only row anywhere that loads a **proxy** entry point on a Java 8 JVM. |
| `bungee-2085` | `itzg/mc-proxy:2026.7.1-java21` | BungeeCord build 2085 | 21 | Current BungeeCord, which moved its own floor to Java 17 after the API version we compile against. |

The proxy rows are where the one-jar design is really tested: they load the Velocity module's Java 17
classes, or the Bungee module's Java 8 ones, while the Bukkit rows load a third set — all out of the
same file, with three different descriptors deciding which.

**Why the Bungee rows name a build number rather than a version.** BungeeCord publishes no releases,
only `ci.md-5.net` builds, and `itzg/mc-proxy` turns `BUNGEE_JOB_ID` into the artifact URL. Old
builds are retained indefinitely (1800 still resolves today), so a pinned number is as deterministic
as a pinned version — and strictly better than the image's own `lastStableBuild` default, which is a
moving target. `run.sh --selftest` refuses a bungee row whose VERSION field is not a bare number,
because the failure mode of getting it wrong is silent: the image falls back to `lastStableBuild` and
the row quietly stops testing what it says it tests.

**Why the Java 8 Bungee row pins an older image than everything else.** `itzg/mc-proxy:2026.7.1-java8`
is published and does not work: its own bundled `mc-image-helper` is compiled at classfile 61, so the
entrypoint dies with `UnsupportedClassVersionError` before it has resolved which proxy to download.
`2025.9.0-java8` is the newest tag verified to boot BungeeCord on a Java 8 JRE, and it is pinned like
every other image here.

**Why two BungeeCord rows.** The same reason as the Velocity pair, doing more work. Build 2000 is
Java 8 bytecode and runs on the `java8` image, which makes it the only row in the matrix that proves
a proxy entry point loads on a Java 8 JVM — the legacy 1.8-era network this platform exists for, and
the row that would catch a Bungee class accidentally compiled above release 8 (the Bukkit rows
cannot: they never load these classes). Build 2085 is classfile 61 and is what customers run, so it
is what proves an API pinned at `1.16-R0.4` still binds against a proxy five years newer.

**Why two Velocity rows.** Velocity moved its own floor and the two ends now disagree. 3.4.0 is the
version `:platform-velocity` compiles against and the last that runs on Java 17; 3.5.1 is compiled
at class file version 65 and will not even start on a Java 17 JRE — which is exactly how the first
draft of this matrix failed, with a `java17` tag and `VELOCITY_VERSION=3.5.1`. Collapsing them would
mean either not testing what we compile against or not testing what is deployed.

**Why Paper for every Minecraft row, including 1.8.8.** Paper's own API serves 1.8.8 through 1.21
from one place, so a single `TYPE` covers the range and every row fetches its server the same way.
`TYPE=SPIGOT` would mean either a BuildTools compile (minutes per row, and a JDK-version minefield
on the legacy rows) or `getbukkit.org`, whose availability is not something CI should depend on.
Paper is also what the overwhelming majority of the deployed fleet actually runs, so this is the
more representative choice as well as the more reliable one.

**Why dated image tags.** `:java8` and `:java21` are moving tags. A matrix that silently changes
what it tests is worse than no matrix, because a failure then has two candidate causes.

**Why the jar is mounted read-only at `/plugins`, not at `/data/plugins`.** Modern Paper writes into
its own plugins directory — it creates `plugins/.paper-remapped` before it loads anything. A host
bind mount there is owned by the host user, the server runs as uid 1000, and the boot dies with
`AccessDeniedException` before the plugin is ever considered. Rootless Podman maps the host user
into the container and hides this completely, which is exactly how the `paper-1.21.8` row passed on
a dev machine and failed on CI. `itzg/minecraft-server` copies `/plugins` into a container-owned
`/data/plugins` on start, so using the image's designed staging path fixes ownership for every row.
The proxy rows keep a direct writable mount: `itzg/mc-proxy` chowns its own `/server` tree before it
drops privileges, so it has no such problem, and both proxies write in there (bStats on Velocity, the
plugin's own data directory on either).

## What each row asserts

1. The server starts and the plugin logs its enable banner, within `SMOKE_BOOT_TIMEOUT`.
1a. The banner says `console tap on`. Attaching to a server's logging backend is the single most
   version-sensitive thing the plugin does — the Log4j API that works on Minecraft 1.8.8's
   `2.0-beta9` is not the one v2 used, and the five-argument `AbstractAppender` constructor v2 called
   did not exist until 2.11.2. The Bungee rows exercise a different implementation entirely
   (`JulConsoleTap`, on a platform with no log4j at any version) through the same one-line assertion.
   The tap is attached eagerly at enable precisely so every row exercises it, and a boot where it
   failed still logs a perfectly good enable banner — so this is a separate assertion, not a
   substring of that one.
2. The **server** then logs its own ready line — `Done (Xs)` on the Bukkit family and on Velocity,
   `Listening on …` on BungeeCord, which has no "Done" line at all and announces itself by binding a
   listener instead (and does so *after* enabling plugins, so it means the same thing). A plugin
   enables *during* startup, seconds
   before the server opens its RCON port, so stopping on the plugin banner alone races the rest of
   the boot — and losing that race does not fail cleanly: RCON is refused, the fallback SIGTERM
   reaches a server not yet reading stdin, and the row dies a minute later on "Took too long, so
   killing server process". That is a flake, not a finding, and it cost one CI run to learn.
3. No error in the boot log is attributable to the plugin.
3a. Bukkit rows: `/hd`, typed on the server console, answers with the version line. A `commands:`
   block in `plugin.yml` that the entry point never claims yields "Unknown command" with nothing in
   any log to say why, and the plugin loads perfectly either way.

   Two details that cost a red CI run each. It goes through `mc-send-to-console`, not RCON:
   legacy 1.8.8 RCON is single-session and fragile, and a third `rcon-cli` call before `stop` was
   enough to hang that row on CI while passing locally. And the assertion counts matches rather than
   grepping for one, because Bukkit's own loader logs `Loading server plugin Heimdall v3.0.0-…`
   during boot — any pattern loose enough to survive the console's ANSI colouring matches that too,
   so a plain grep would have passed whether or not the command existed.

   `CREATE_CONSOLE_IN_PIPE=true` and `docker exec -u 1000` are both required for
   `mc-send-to-console` to work at all. Without either it fails immediately, which also silently
   disarmed the console `stop` fallback added for the flaky-RCON case.
4. The server stops **gracefully** — over RCON (`rcon-cli stop`, retried a few times) for the Bukkit
   family, so the server's own shutdown path runs and `onDisable` is actually called. `docker stop`
   would also work, but only because the image traps the signal, and asserting through a path the
   plugin will never see in production proves less.
5. **Every** row logs the disable banner. The Velocity rows were boot-only until phase 1c — the
   scaffold registered no `ProxyShutdownEvent` listener, so it had no banner of its own and those
   rows could only prove the jar loaded and the proxy stopped, nothing about Heimdall unloading. It
   has one now. The proxy's own shutdown line is still asserted alongside ours — `Shutting down the
   proxy` on Velocity, `Thank you and goodbye` on BungeeCord — because our banner alone would not
   distinguish a graceful stop from a proxy killed part-way through teardown, and "no errors" is
   weaker still: a SIGKILL logs nothing at all. BungeeCord's is deliberately the *last* line its
   teardown writes rather than the first (`Closing pending connections`), so reaching it also proves
   the shutdown ran to completion.
6. No error in the shutdown log is attributable to the plugin.

Before any of the shutdown assertions run, the harness waits for the `docker logs -f` follower to
drain. Reading the file while the stream is still catching up produces a "missing disable banner"
that is indistinguishable from `onDisable` genuinely never running — a fixed sleep only makes that
unlikely, not impossible.

On failure the row prints the tail of the server log and the whole run exits non-zero.

### "Attributable to the plugin" is deliberately narrow

A run across this many server generations picks up plenty of noise that has nothing to do with us —
deprecation warnings, missing optional dependencies, Mojang telemetry that cannot reach the network,
and on modern Paper a "legacy plugin" warning caused by our deliberate omission of `api-version`
(declaring one makes 1.8.8 refuse to load the plugin at all). A check that failed on all of that
would be muted within a week.

What is left is: a log line naming Heimdall at `ERROR` or worse, a stack frame in `com.heimdall.`,
and the two messages Bukkit prints when a plugin fails to load or throws in `onEnable`/`onDisable`.
The pattern lives in `lib.sh` as `HEIMDALL_ERROR_PATTERN`.

## The assertions are themselves tested

```bash
smoke/run.sh --selftest      # no Docker needed; CI runs it before the matrix fans out
```

Every assertion here is a grep, and **a grep that matches nothing is indistinguishable from a clean
server log**. So the patterns are pointed at lines that must trip them and at real noise from a
passing run that must not, and the row records are checked for shape.

That is not decorative rigour. The first version of `HEIMDALL_ERROR_PATTERN` used `[^\n]*`, which in
a POSIX bracket expression means "not a backslash and not the letter `n`" — so it silently failed to
match `Could not enable Heimdall` and most of what it was written to catch. Nothing about a passing
smoke run would ever have revealed it.

## Next: the stub bot

`docker-compose.yml` has the next topology already wired: the stub bot (see `stub-bot/README.md`)
next to a server with the plugin, with fixtures in `fixtures/players.json`. **`run.sh` does not use
it and CI does not run it** — the plugin builds its tunnel but has no guild id to dial with until
the setup flow lands, so there is still nothing to connect and nothing to assert.

It is committed now, runnable by hand, so the wiring is written down while that is true rather than
being invented in a hurry the day the tunnel lands. The header of that file lists the assertions to
add, in order.

```bash
./gradlew build
docker compose -f smoke/docker-compose.yml up
```

---

## The connected scenario

```bash
./gradlew build                        # produces the jar AND stub-bot/build/install/
smoke/connected.sh                     # every row, sequentially
smoke/connected.sh velocity-3.5.1      # one row
smoke/connected.sh --list
smoke/connected.sh --selftest          # the assertions themselves; needs no Docker
```

`run.sh` proves the jar survives eight servers with nothing to talk to. This proves the other half.
Each row starts `:stub-bot` — the executable copy of the bot's wire contract — on a private network,
boots a server that ends up pointed at it, and then asserts from **both ends**. How the server gets
there is what the row's *mode* decides: a `bootstrap.yml` staged before boot, a setup code claimed
over the console, or a v2 config migrated on the first boot (see [the modes](#six-rows-in-three-modes)).

| Asserted | Where the evidence is | Why it is worth a container |
| --- | --- | --- |
| The guild is resolved from the token alone | plugin log | `bootstrap.yml` deliberately has no `guildId` (D54), so this is the one endpoint signed without a guild in its path actually working against a real HMAC check |
| The v3 handshake completes | plugin log **and** stub log | D51 is a pair of misreads that each turned a good v3 bot into a silent v2-compat downgrade. The stub's `protocol=3` line is what distinguishes "connected" from "connected and speaking v3" |
| Config is pushed and acked | stub log | proves the narrowing worked — the bot only pushes config for capabilities the client declared |
| The whitelist mirror pre-warms | plugin log | a signed `GET whitelist/sync` round trip, reconciled into a real file on a real disk |
| Console lines reach the bot | stub log | the log4j tap, the module's batching and the tunnel, end to end |
| The bot's `get_players` is **answered** | stub log | the one assertion pointing the *other* way down the tunnel — see below |
| `bridge@1` is negotiated | stub log | a build that shipped without the bridge module produces an identify line that otherwise looks perfectly healthy |
| An inbound Discord line is **rendered and fanned out** | plugin log | the second assertion pointing the other way, and the only one available for it — see below |
| It still unloads cleanly | plugin log | with a live tunnel, which `run.sh` never has |

**The `get_players` row is new, and it exists because every other row above it stayed green on a
build that was broken.** v3.0.0-rc.2 shipped with the entire reply path for the dashboard's on-demand
requests built — `TunnelBus.reply`, the correlation map, the dispatcher's subscription step — and
nothing subscribed to any of them. The Online Players panel 504ed after ten seconds on a real proxy
while this scenario passed, because everything it checked was the plugin *talking* and nothing checked
the plugin *answering*. `STUB_BOT_REQUEST_ON_ACK=get_players` makes the stub ask, once the server has
acked its config, and log either `on-ack get_players -> survival: 0 players` or `FAILED: Request timed
out`. The count is zero — no headless client, so nobody is ever online — and it is still a real
assertion, because the failure mode is not a different number but no reply at all. `run_command` and
`probe_player` are deliberately not wired in: the first would need a verb that exists on both a Paper
server and a Velocity proxy, the second needs the Trace plugin, and both are covered by unit tests
against the same handlers.

**It earned its keep on the first run.** The plugin declared `capabilities=[]` and the stub logged
`protocol=v2`: the capability set was the union over *enabled* modules, nothing was enabled because
no config had arrived, and no config could arrive because the bot narrows its push by declared
capability. A fresh install could never have been configured, and every later boot would have been
in the same state. Nothing in 434 unit tests could see it — it needed real modules and a real bot in
one process, which is exactly what this scenario is.

### The chat bridge, and the half of it a harness with no client cannot reach

Two of the rows above are the bridge's, and they are the two that are reachable. `bridge@1` has to be
negotiated — a build that forgot to register the module, or one where it turned out ineligible on a
role, produces an identify line that is otherwise indistinguishable from a healthy one — and
`STUB_BOT_DISCORD_ON_ACK` makes the stub push a rendered `bridge.discord` once the server has acked
its config, which the plugin must log as `relayed 1 discord message(s) to 0 online player(s)`.

That second one is the direction that has no other witness. Unlike `get_players`, `bridge.discord` is
a **notification**: the bot sends it and waits for no reply, so nothing on the stub's side can tell a
plugin that handled it from a plugin with no subscription at all. Only the plugin's own line can, and
the `[1-9]` in the pattern is doing real work — a handler that ran and understood nothing prints
`relayed 0`. The audience is zero because there is no client here, which is the same shape of
assertion as `0 players` on the roster row: what is being distinguished is handled-at-all.

**The outbound direction is not asserted here, on any row, and is not faked.** Chat, joins, leaves
and deaths all need a player, and there is no headless client in this harness. There is also no
console verb that injects chat — and adding one would mean shipping a test hook in the production jar
to make a row green, which is a worse outcome than an honest gap. Those paths are covered by
`HeimdallBridgeModuleTest`, which drives the real `ChatPipeline` and the real `PlayerSessionEvents`
through the real `ModuleManager` (including the drop-oldest bound, drain-and-discard while
disconnected, and the verbatim-text rule), and by `stub-bot`'s `BridgeFramesTest` for the wire shape
in both directions. When the headless client D43 has been waiting for arrives, `assert_bridge` is the
function it plugs into.

The proxy rows run the bridge assertions too, and that is not a formality: the module is eligible on
every role, so a proxy negotiates `bridge@1` and delivers `bridge.discord` exactly as a backend does.
What differs there is only `relayChat`, which defaults **off** on a gatekeeper — which is a second,
independent reason the outbound direction could not be asserted on that row even with a client.
`relayEvents`, the events half, does **not** differ by role: it defaults on everywhere, because
duplicate session events are collapsed before they reach Discord and so need no per-role default to
be correct. It is not a mirror of `relayChat` and should not be read as one.

### The login gate, without a login

`/hd test <player>` drives the **real** interceptor — module toggle, role check, bypass list, mirror,
bot, fallback mode — with its writes suppressed, so what it reports is the decision that player would
actually get. The `paper-1.21.8` row runs it twice, over RCON, and asserts both ends where both ends
have something to say:

| Probe | Asserted on the plugin | Asserted on the stub |
|---|---|---|
| a name with no fixture | refused, `not whitelisted` | the `connection-attempt` request arrived |
| `AllowedSteve` | admitted, `mirror hit` | (the pre-warm assertion above is what put him there) |

The two players are chosen so both halves of the path run. The deny misses the mirror and reaches the
bot, which is the branch with a wire round trip in it. The allow is answered by the mirror, which is
the *common* path on a warmed server and the one v2 shipped without a report on — and resolving his
name to the right UUID at all is the interesting part, since he is not online and the probe reads the
mirror's own uuid-to-name mapping rather than hashing his name into an id that belongs to nobody.

### What it deliberately does not do

**No player ever joins.** There is no headless client here, so the login gate's six outcomes are
proven by the whitelist module's tests against the same stub over a real socket, and are not
re-proven at this level. What `/hd test` does not reach is everything downstream of the decision —
the platform's own login listener, the kick screen, and chat cancellation — all of which need a
client to be connected. That is departure D43's residual risk 2, and it waits on the headless-client
work.

### Six rows, in three modes

> **`smoke/connected.sh --list` is the source of truth**, the same as for `run.sh` above. If the
> `ROWS` array and this section disagree, the script wins.

The wire does not vary by Minecraft version. What varies is class loading and the console tap, and
`run.sh` already covers those on all eight. One current server from each **platform** is what proves
the one-jar design still reaches a bot from all three entry points.

Three of the six rows are not about the steady state at all, because two of the flows cannot be
exercised on a server that is already configured:

| Mode | Rows | Starting state | What it proves |
|---|---|---|---|
| `configured` | `paper-1.21.8`, `velocity-3.5.1`, `bungee-2085` | a `bootstrap.yml` exists | the steady state, plus the login probe on the Bukkit row |
| `setup` | `paper-setup` | **nothing** — no `bootstrap.yml` at all | a setup code is claimed over the console and the tunnel comes up, negotiates v3 and enables modules **in the same boot**. That is departure D56, and it was impossible before phase 1e |
| `migrate` | `paper-migrate`, `velocity-migrate` | a v2 config in the sibling directory v2 really used — `plugins/HeimdallWhitelist/config.yml` on Bukkit, `plugins/heimdall-whitelist/config.json` on Velocity | the migration finds it next door, writes a `bootstrap.yml`, connects on the **legacy** guild key, keeps the original as `*.v2-backup`, and hands the translated settings to the dashboard once |

**`setup` is Bukkit-only, and that is a real harness limitation:** it types a command into a running
server, which needs RCON, and the Paper image in this matrix exposes it while the proxy image does
not. The code under test is the same on both — the admin tree is one platform-free class registered
through each platform's own `CommandRegistrar` — so what a Velocity setup row would prove is the
registrar binding, which the configured Velocity row already exercises by answering `/hdp` at all.
The same applies to the BungeeCord row.

**There is deliberately no `bungee-migrate` row**, and its absence is a statement rather than a gap.
v2 shipped a Bukkit build and a Velocity build and nothing else, so no BungeeCord proxy has ever had
a v2 config to find (departure D78) — a migrate row here would assert against a fixture no operator
has ever had, which is the same mistake the `velocity-migrate` row exists to have corrected, made in
the opposite direction. `connected.sh --selftest` refuses that combination outright.

**Where the bootstrap goes differs between the two proxies**, and it is not cosmetic: Velocity
derives a plugin's data directory from its **id** (`plugins/heimdall/`) and BungeeCord from its
descriptor's **name** (`plugins/Heimdall/`, matching the Bukkit family). Writing it into the wrong
one produces a proxy that boots perfectly and reports itself unconfigured — exactly the shape of
departure D70.

**`migrate` runs on both, and that is not decoration.** This is the one mode whose *inputs* differ
per platform: v2 named its data directory after its `plugin.yml` `name:` on Bukkit and after its
`@Plugin` id on Velocity — `HeimdallWhitelist` versus `heimdall-whitelist`, which are different
strings — and it wrote YAML into one and JSON into the other. A Bukkit-only migrate row proves
exactly half of that, and for a while the unproven half was wrong in production: see departure D70.
The row needs no console (it waits on log lines, then reads the host file system), so its absence was
never a harness limitation — it simply did not exist. Each row greps for its own platform's directory
and file name, so neither can go green on the other's log line.

### Why the Bukkit row mounts `/data/plugins` when `run.sh` does not

`run.sh` only has to get a jar in, so it uses the image's read-only `/plugins` staging path — which
exists precisely because a host bind mount at `/data/plugins` is owned by the host user while the
server runs as uid 1000, and modern Paper dies creating `/data/plugins/.paper-remapped` before it
looks at a plugin at all.

This row additionally has to place `bootstrap.yml` **inside the plugin's own data directory before
boot**, and staging cannot do that. So it uses the mount and solves the ownership problem head-on
with `chmod 777` on the host side, which is safe for a directory this script creates and deletes.

Mounting only `/data/plugins/Heimdall` does **not** work: Docker then creates the `/data/plugins`
parent as root and Paper fails exactly as above. That was tried first, and it is why this paragraph
exists.
