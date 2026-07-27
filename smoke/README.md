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
| `run.sh` | Does the jar load, work and unload with **no bot anywhere**? | six, the whole supported range |
| `connected.sh` | Pointed at a real bot, does the plugin **actually talk to it**? | two, one per family |

Both are CI gates. `connected.sh`'s two rows run as their own job, and both self-tests run in the
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

The proxy rows are where the one-jar design is really tested: they load the Java 17 classes while
the Bukkit rows load the Java 8 ones, out of the same file.

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
The proxy rows keep a direct writable mount: `itzg/mc-proxy` runs as root, so it has no such
problem, and Velocity does write in there (bStats).

## What each row asserts

1. The server starts and the plugin logs its enable banner, within `SMOKE_BOOT_TIMEOUT`.
1a. The banner says `console tap on`. Attaching a root Log4j appender is the single most
   version-sensitive thing the plugin does — the API that works on Minecraft 1.8.8's Log4j
   `2.0-beta9` is not the one v2 used, and the five-argument `AbstractAppender` constructor v2 called
   did not exist until 2.11.2. The tap is attached eagerly at enable precisely so every row
   exercises it, and a boot where it failed still logs a perfectly good enable banner — so this is a
   separate assertion, not a substring of that one.
2. The **server** then logs its own `Done (Xs)` line. A plugin enables *during* startup, seconds
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
   has one now. The proxy's own `Shutting down the proxy` line is still asserted alongside ours,
   because our banner alone would not distinguish a graceful stop from a proxy killed part-way
   through teardown, and "no errors" is weaker still — a SIGKILL logs nothing at all.
6. No error in the shutdown log is attributable to the plugin.

Before any of the shutdown assertions run, the harness waits for the `docker logs -f` follower to
drain. Reading the file while the stream is still catching up produces a "missing disable banner"
that is indistinguishable from `onDisable` genuinely never running — a fixed sleep only makes that
unlikely, not impossible.

On failure the row prints the tail of the server log and the whole run exits non-zero.

### "Attributable to the plugin" is deliberately narrow

A run across five server generations picks up plenty of noise that has nothing to do with us —
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
smoke/connected.sh                     # both rows
smoke/connected.sh velocity-3.5.1      # one row
smoke/connected.sh --selftest          # the assertions themselves; needs no Docker
```

`run.sh` proves the jar survives six servers with nothing to talk to. This proves the other half.
Each row starts `:stub-bot` — the executable copy of the bot's wire contract — on a private network,
boots a server whose `bootstrap.yml` points at it, and then asserts from **both ends**.

| Asserted | Where the evidence is | Why it is worth a container |
| --- | --- | --- |
| The guild is resolved from the token alone | plugin log | `bootstrap.yml` deliberately has no `guildId` (D54), so this is the one endpoint signed without a guild in its path actually working against a real HMAC check |
| The v3 handshake completes | plugin log **and** stub log | D51 is a pair of misreads that each turned a good v3 bot into a silent v2-compat downgrade. The stub's `protocol=3` line is what distinguishes "connected" from "connected and speaking v3" |
| Config is pushed and acked | stub log | proves the narrowing worked — the bot only pushes config for capabilities the client declared |
| The whitelist mirror pre-warms | plugin log | a signed `GET whitelist/sync` round trip, reconciled into a real file on a real disk |
| Console lines reach the bot | stub log | the log4j tap, the module's batching and the tunnel, end to end |
| It still unloads cleanly | plugin log | with a live tunnel, which `run.sh` never has |

**It earned its keep on the first run.** The plugin declared `capabilities=[]` and the stub logged
`protocol=v2`: the capability set was the union over *enabled* modules, nothing was enabled because
no config had arrived, and no config could arrive because the bot narrows its push by declared
capability. A fresh install could never have been configured, and every later boot would have been
in the same state. Nothing in 434 unit tests could see it — it needed real modules and a real bot in
one process, which is exactly what this scenario is.

### What it deliberately does not do

**No player ever joins.** There is no headless client here, so the login gate's six outcomes are
proven by the whitelist module's tests against the same stub over a real socket, and are not
re-proven at this level.

**Phase 1e adds `/hd test <player>`**, which makes a real connection-attempt from the server console.
At that point this script can assert an allow and a deny end to end through rcon. An actual join —
which is also what departure D43's residual risk 2 needs to close — waits on the headless-client
work.

### Two rows, not six

The wire does not vary by Minecraft version. What varies is class loading and the log4j tap, and
`run.sh` already covers those on all six. One current server from each family is what proves the
one-jar design still reaches a bot from both entry points, for two boots instead of six.

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
