#!/usr/bin/env bash
#
# Connected smoke: does the plugin actually talk to a bot?
#
#   smoke/connected.sh                    # both rows
#   smoke/connected.sh paper-1.21.8       # one row
#   smoke/connected.sh --list
#   smoke/connected.sh --selftest         # verify the assertions themselves; needs no Docker
#
# smoke/run.sh proves the jar loads and unloads on six servers with NO bot anywhere. This proves the
# other half: pointed at :stub-bot — the executable copy of the bot's wire contract — the plugin
# resolves its guild, negotiates v3, takes a config push, pre-warms its whitelist mirror and streams
# console lines back. Every assertion below is either a line the plugin wrote or a line the STUB
# wrote, and the stub's half is what makes this more than the plugin agreeing with itself.
#
# What this deliberately does NOT do is join a player. There is no headless client in this harness,
# so the login gate's six outcomes are proven by module tests against the same stub over a real
# socket, and are not re-proven here. `/hd test` is the closest this gets: it drives the real login
# interceptor for a named player, writes nothing, and is asserted at BOTH ends — the plugin's own
# decision line and, for the branch that reaches the bot, the stub's record of the request.
#
# Five rows, in three modes, because two of the flows cannot be exercised on a server that is
# already configured:
#
#   configured  the steady state: a bootstrap.yml exists, the plugin dials, and /hd test probes.
#   setup       no bootstrap.yml at all. A setup code is minted in the stub, `/hd setup` is driven
#               down the console, and the tunnel has to come up WITHOUT a restart — which is the
#               whole of departure D56 and was impossible before phase 1e.
#   migrate     a v2 config in the sibling directory v2 actually used — plugins/HeimdallWhitelist/
#               config.yml on Bukkit, plugins/heimdall-whitelist/config.json on Velocity, which are
#               different names AND different formats. The plugin has to find it, write a
#               bootstrap.yml from it, connect on the legacy guild key, keep the original as
#               *.v2-backup, and hand the translated settings to the dashboard. Run on BOTH
#               platforms, because this is the one mode whose inputs are not shared between them.
#
# Environment:
#   SMOKE_JAR             path to the shaded jar (default: newest app/build/libs/heimdall-whitelist-*.jar)
#   SMOKE_STUB_DIST       stub-bot installDist directory (default: stub-bot/build/install/stub-bot)
#   SMOKE_BOOT_TIMEOUT    seconds to wait for the enable banner (default 240)
#   SMOKE_STOP_TIMEOUT    seconds to wait for a graceful stop (default 120)
#   SMOKE_RCON_TIMEOUT    seconds to wait for RCON to answer before SIGTERM (default 120)
#   SMOKE_KEEP            1 = leave containers and work dirs behind for inspection
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=smoke/lib.sh
source "${SCRIPT_DIR}/lib.sh"

BOOT_TIMEOUT="${SMOKE_BOOT_TIMEOUT:-240}"
STOP_TIMEOUT="${SMOKE_STOP_TIMEOUT:-120}"
RCON_TIMEOUT="${SMOKE_RCON_TIMEOUT:-120}"
WORK_ROOT="${SCRIPT_DIR}/.work-connected"

# The guild the stub answers `identify` with, and the shared HMAC secret. Both are stub-bot's own
# defaults; naming them here rather than relying on them keeps the assertions readable.
STUB_GUILD="123456789012345678"
STUB_SECRET="smoke-shared-secret"
STUB_SERVER_ID="survival"

# ── What the plugin must say ─────────────────────────────────────────────────────────────────

# Guild discovery (departure D54). bootstrap.yml deliberately carries no guildId, so reaching this
# line means the plugin asked POST /api/minecraft/identify and the stub accepted the signature —
# which is the one endpoint signed without a guild in the path, and therefore the one most likely
# to be canonicalised wrongly.
GUILD_RESOLVED_PATTERN="resolved guild ${STUB_GUILD} from this server's token"
# The v3 handshake completed. Departure D51 is a pair of misreads that each turned a perfectly good
# v3 bot into a silent v2-compat downgrade, and both were invisible to every unit test we had.
NEGOTIATED_PATTERN='tunnel negotiated protocol v3'
# The whitelist pre-warm actually ran AND took rows.
#
# The `[1-9]` is the assertion. `Mirror reconcile (whitelist-mirror.json): 0 added, 0 refreshed,
# 0 pruned (0 held)` is a perfectly well-formed line that a poll against an empty or unreachable
# whitelist produces, so a pattern matching the prefix alone passes on a sync that fetched nothing —
# which is the state this row exists to distinguish from a working one.
MIRROR_PATTERN='Mirror reconcile \(whitelist-mirror\.json\): [1-9][0-9]* added'

# ── What the stub must say ───────────────────────────────────────────────────────────────────

STUB_CONNECTED_PATTERN="ws connected: guild=${STUB_GUILD} server=${STUB_SERVER_ID}"
# protocol=3 rather than protocol=v2. The stub prints v2 for a client that declared no capabilities,
# so this single line distinguishes "connected" from "connected and speaking the new protocol".
STUB_IDENTIFIED_PATTERN="identified: server=${STUB_SERVER_ID} .* protocol=3 .* capabilities=\[.*whitelist@1"
STUB_CONFIG_ACKED_PATTERN="config acked by ${STUB_SERVER_ID} at version"
# Console streaming, end to end: the module batched lines off the server's own log4j tap and the
# stub received the frame. Only visible with STUB_BOT_VERBOSE=true, which this harness sets.
STUB_CONSOLE_PATTERN='ws recv console_line'

# ── The chat bridge ──────────────────────────────────────────────────────────
#
# THE HALF THIS HARNESS CANNOT REACH, stated rather than faked. Everything the bridge relays OUT —
# chat, join, leave, death — needs a player, and there is no headless client here (the same gap D43
# names for chat cancellation, and the reason `/hd test` exists for the login path). There is no
# console verb that injects chat either, and adding one would mean shipping a test hook in the
# production jar to make a smoke row green: that trade is refused. Those paths are covered by the
# module's own unit tests, which drive the REAL ChatPipeline and the REAL PlayerSessionEvents
# through the REAL ModuleManager, and by stub-bot's BridgeFramesTest for the wire shape.
#
# What IS reachable from here is asserted, and it is the half that broke last time: the capability
# has to be negotiated, and the bot->plugin direction has to have a live subscriber. Those two are
# exactly what `get_players` shipped without.
#
# bridge@1 negotiated. Separate from STUB_IDENTIFIED_PATTERN so a failure says which capability is
# missing rather than "the identify looked wrong".
STUB_BRIDGE_CAPABILITY_PATTERN="identified: server=${STUB_SERVER_ID} .* capabilities=\\[.*bridge@1"
# The stub pushed a rendered line and the plugin's bridge module HANDLED it. `0 online player(s)` is
# the expected audience — this harness has no client — and it is still a real assertion, for the
# same reason `0 players` is on the roster row: a plugin with no subscription logs nothing at all.
# The `[1-9]` on the message count is the other half: `relayed 0 discord message(s)` is what a
# handler that ran and understood nothing would print.
BRIDGE_DELIVERED_PATTERN='\[bridge\] relayed [1-9][0-9]* discord message\(s\) to [0-9]+ online player\(s\)'
# What the stub is told to push. Deliberately ASCII: the § codes are what the real bot sends, but
# the plugin logs no content, so a § here would prove nothing at this end and would put a non-ASCII
# byte through `docker run -e` on three host platforms for it.
STUB_DISCORD_ON_ACK='[Discord] smoke: bridge reachable'
STUB_DISCORD_SENT_PATTERN="on-ack bridge.discord -> ${STUB_SERVER_ID}: [0-9]+ message\\(s\\), delivered=1"
# The whitelist sync really crossed the wire, as a signed request the stub accepted. The plugin's
# own "Mirror reconcile" line proves it processed a response; only the stub can prove it asked.
STUB_SYNC_PATTERN="GET /api/guilds/${STUB_GUILD}/minecraft/whitelist/sync"

# ── The dashboard's on-demand questions ──────────────────────────────────────
#
# Everything above is the plugin talking. This is the other direction: the BOT asks a live server a
# question over the tunnel and waits for a correlated reply, which is what the dashboard's Online
# Players panel, its console command box and its mod-probe button all are.
#
# The stub fires these once the server acks its config — STUB_BOT_REQUEST_ON_ACK exists for exactly
# this, because bot.ws().getPlayers() is a Java hook and a shell script driving a container cannot
# reach into the JVM.
#
# get_players alone, for now. run_command would need a verb that exists on both a Paper server and a
# Velocity proxy, and probe_player needs the Trace plugin; get_players is the one that is answerable
# everywhere, and it is the one that actually broke — v3 shipped with the whole reply path built and
# nothing subscribed to the request, so the panel 504ed after ten seconds on every server in the
# fleet.
STUB_ON_ACK_REQUESTS="get_players"
# `0 players` is the expected count — this harness has no headless client, so nobody is ever online —
# and it is still a real assertion, because the alternative outcome is not a different number. A
# plugin with no handler replies NOTHING, and the stub then logs `FAILED: Request timed out (…)`,
# which this pattern does not match. What is being distinguished is answered-at-all.
STUB_ROSTER_PATTERN="on-ack get_players -> ${STUB_SERVER_ID}: [0-9]+ players"
# The tunnel was still live at shutdown and closed deliberately. Without this the "disabled cleanly
# with a live tunnel" claim rests on the disable banner alone, which says nothing about the tunnel —
# a socket that had already dropped ten minutes earlier would look identical.
STUB_CLEAN_CLOSE_PATTERN="ws disconnected: guild=${STUB_GUILD} server=${STUB_SERVER_ID}.*Plugin shutting down"

# ── The login probe (/hd test) ───────────────────────────────────────────────
#
# Two players, chosen so the two halves of the login path are both exercised.
#
# AllowedSteve is in smoke/fixtures/players.json with outcome `allow`, so the pre-warm sync has put
# him in the mirror — and the probe therefore short-circuits there, which is the COMMON path on a
# real server and the one v2 shipped without a report on. Resolving his name to the right UUID at
# all is the interesting part: he is not online, so the probe reads the mirror's own uuid-to-name
# mapping rather than hashing his name into an offline-mode UUID that belongs to nobody.
#
# NobodyKnowsThisName has no fixture, so the probe misses the mirror, asks the bot, and is denied by
# the stub's default outcome. That is the branch with a wire round trip in it, so it is the one the
# STUB has to corroborate.
PROBE_ALLOWED_PLAYER="AllowedSteve"
PROBE_DENIED_PLAYER="NobodyKnowsThisName"
PROBE_ALLOW_PATTERN="mirror hit for ${PROBE_ALLOWED_PLAYER}"
PROBE_DENY_PATTERN="refusing ${PROBE_DENIED_PLAYER}: not whitelisted"
STUB_PROBE_PATTERN="POST /api/guilds/${STUB_GUILD}/minecraft/connection-attempt"

# ── The setup flow ───────────────────────────────────────────────────────────
#
# The code and the server id are fixed rather than random, because the assertions below are on the
# stub's own log lines and a shell script cannot know a UUID minted inside a container.
SETUP_CODE="ABCD2345"
SETUP_SERVER_ID="smoke-setup"
STUB_CLAIMED_PATTERN="claimed setup code ${SETUP_CODE} -> server ${SETUP_SERVER_ID}"
STUB_SETUP_CONNECTED_PATTERN="ws connected: guild=${STUB_GUILD} server=${SETUP_SERVER_ID}"
STUB_SETUP_IDENTIFIED_PATTERN="identified: server=${SETUP_SERVER_ID} .* protocol=3 .* capabilities=\\[.*whitelist@1"
# A module going from off to on is the proof that the config push landed and was applied — which is
# what "modules go live without a restart" actually means. The plugin logs one line per module.
SETUP_MODULE_ENABLED_PATTERN="module 'whitelist' enabled"

# ── The v2 migration ─────────────────────────────────────────────────────────
MIGRATE_SERVER_ID="migrated-survival"
# Both file names, because v2 wrote YAML on Bukkit and JSON on Velocity and there is a migrate row
# for each. The row-specific half — which directory the file was in — is asserted on the file system
# below rather than in this pattern, so a row cannot pass on the other platform's line.
MIGRATED_PATTERN='Migrated the v2 config at .*config\.(yml|json)'
STUB_MIGRATE_CONNECTED_PATTERN="ws connected: guild=${STUB_GUILD} server=${MIGRATE_SERVER_ID}"
STUB_IMPORT_PATTERN="config import for ${MIGRATE_SERVER_ID} \\(imported=true\\)"
# The legacy HMAC path, proven rather than assumed: v2 had no token id, so a migrated server signs
# identify with the guild key alone and sends NO X-Token-Id. The stub logs which path it saw, so the
# migrate row is not tautological on the legacy signing.
STUB_LEGACY_IDENTIFY_PATTERN="identify: guild=${STUB_GUILD} \\(legacy: no token id\\)"

# The plugin's own banners, shared with run.sh. Kept in step by the self-test below.
ENABLE_PATTERN='Heimdall v[0-9][^ ]* enabled'
DISABLE_PATTERN='Heimdall v[0-9][^ ]* shutting down'
READY_PATTERN='Done \([0-9.]+s\)'

# ── The matrix ───────────────────────────────────────────────────────────────────────────────
#
# Two rows, not six. This scenario is about the wire, and the wire does not vary by Minecraft
# version — what varies by version is class loading and the log4j tap, which run.sh already covers on
# all six. One current server from each family is what proves the one-jar design still reaches a bot
# from both entry points, and it costs two boots instead of six.
#
#   row | image | TYPE | VERSION | platform | memory | mode
#
# The two flow rows are Bukkit-only, and that is a harness limitation stated rather than hidden: the
# Paper image exposes RCON and a console pipe, and the proxy image in this matrix exposes neither,
# so there is no way to type a command into a running Velocity here. The code under test is the
# same on both — the admin tree is one platform-free class registered through each platform's own
# CommandRegistrar — so what the missing rows would prove is the registrar binding, which the
# configured Velocity row already exercises by answering /hdp at all.
#
# That reasoning covers the SETUP row and only the setup row. The migrate row needs no console at
# all — it waits on log lines and then checks the host file system — and it is the one row where the
# code under test genuinely differs per platform (departure D70): the directory a v2 install left
# its config in is v2's Bukkit plugin NAME on Paper and v2's Velocity plugin ID on the proxy, and
# those are different strings that shipped disagreeing. The Velocity migrate row below is the row
# that would have caught it; it did not exist, which is the whole reason the wrong name reached
# production. Its absence was never a harness limitation.
# There is deliberately no bungee MIGRATE row, and its absence is a statement rather than a gap: v2
# shipped a Bukkit build and a Velocity build and nothing else, so no BungeeCord proxy in the world
# has a v2 config to find (departure D78). A migrate row here would be testing a fixture nobody has
# ever had — which is precisely the mistake the velocity-migrate row's own comment warns about, in
# the other direction. What the bungee row proves instead is the third entry point reaching a real
# bot: a fourth plugin loader, an asynchronous login gate, and a console tap that is not log4j.
ROWS=(
    "paper-1.21.8|itzg/minecraft-server:2026.7.2-java21|PAPER|1.21.8|bukkit|2G|configured"
    "velocity-3.5.1|itzg/mc-proxy:2026.7.1-java21|VELOCITY|3.5.1|velocity|1G|configured"
    "bungee-2085|itzg/mc-proxy:2026.7.1-java21|BUNGEECORD|2085|bungee|1G|configured"
    "paper-setup|itzg/minecraft-server:2026.7.2-java21|PAPER|1.21.8|bukkit|2G|setup"
    "paper-migrate|itzg/minecraft-server:2026.7.2-java21|PAPER|1.21.8|bukkit|2G|migrate"
    "velocity-migrate|itzg/mc-proxy:2026.7.1-java21|VELOCITY|3.5.1|velocity|1G|migrate"
)

CURRENT_CONTAINERS=""
CURRENT_NETWORK=""
cleanup_on_signal() {
    local signal="$1"
    for container in ${CURRENT_CONTAINERS}; do
        docker rm -f "${container}" >/dev/null 2>&1 || true
    done
    [ -n "${CURRENT_NETWORK}" ] && docker network rm "${CURRENT_NETWORK}" >/dev/null 2>&1 || true
    trap - INT TERM
    kill -s "${signal}" "$$"
}
trap 'cleanup_on_signal INT' INT
trap 'cleanup_on_signal TERM' TERM

# ── Self-test ────────────────────────────────────────────────────────────────────────────────
#
# Same discipline as run.sh's: every assertion here is a grep, and a grep that matches nothing looks
# exactly like a clean log. Each pattern is pointed at the real line it must catch and at the
# nearest line it must NOT.
#
# This runs in CI without Docker, in the `smoke-matrix` job alongside run.sh's own self-test, so a
# broken pattern fails the build before any runner starts a server. The sample lines are copied out
# of smoke/.work-connected from a green run rather than written from memory — a fabricated sample
# only proves that the pattern matches the sample.

selftest() {
    local failures=0

    check_match() {
        local pattern="$1" line="$2" want="$3" label="$4"
        if printf '%s\n' "${line}" | grep -Eq "${pattern}"; then
            [ "${want}" = "yes" ] && { pass "${label}"; return 0; }
            fail "${label}: matched but should not have"
        else
            [ "${want}" = "no" ] && { pass "${label}"; return 0; }
            fail "${label}: did not match but should have"
        fi
        printf '       line: %s\n' "${line}" >&2
        return 1
    }

    check_match "${GUILD_RESOLVED_PATTERN}" \
        "[13:50:09 INFO]: [Heimdall] resolved guild ${STUB_GUILD} from this server's token" \
        yes "guild resolved" || failures=$((failures + 1))
    # The near-miss that matters: a server running on its CACHED guild never logs this, and a row
    # that accepted the discovering line would prove nothing about the identify endpoint.
    check_match "${GUILD_RESOLVED_PATTERN}" \
        "[13:50:09 INFO]: [Heimdall] discovering which guild this server's token belongs to; the tunnel stays idle until it answers" \
        no "guild resolved vs the discovering line" || failures=$((failures + 1))

    check_match "${NEGOTIATED_PATTERN}" \
        "[13:50:11 INFO]: [Heimdall] tunnel negotiated protocol v3 with a v3 bot (bot config version 1)" \
        yes "v3 negotiated" || failures=$((failures + 1))
    check_match "${NEGOTIATED_PATTERN}" \
        "[13:50:21 INFO]: [Heimdall] no identify_ack within the negotiation window — this bot speaks v2; using cached configuration" \
        no "v3 negotiated vs the v2-compat fallback" || failures=$((failures + 1))

    check_match "${MIRROR_PATTERN}" \
        "[13:50:12 INFO]: [Heimdall] Mirror reconcile (whitelist-mirror.json): 2 added, 0 refreshed, 0 pruned (2 held)" \
        yes "mirror pre-warmed" || failures=$((failures + 1))
    check_match "${MIRROR_PATTERN}" \
        "[13:50:12 WARN]: [Heimdall] [whitelist] whitelist pre-warm failed; the mirror is unchanged (0 entries (0 expired), etag=none): Connection refused" \
        no "mirror pre-warmed vs a failed poll" || failures=$((failures + 1))

    check_match "${STUB_CONNECTED_PATTERN}" \
        "[stub-bot] ws connected: guild=${STUB_GUILD} server=${STUB_SERVER_ID}" \
        yes "stub saw the socket" || failures=$((failures + 1))
    check_match "${STUB_IDENTIFIED_PATTERN}" \
        "[stub-bot] identified: server=${STUB_SERVER_ID} name=Survival protocol=3 registered=true capabilities=[whitelist@1, rolesync@1, console@1]" \
        yes "stub saw a v3 identify" || failures=$((failures + 1))
    # A v2 client identifies too. Accepting that line would make this row pass against a plugin that
    # had silently stopped declaring capabilities at all.
    check_match "${STUB_IDENTIFIED_PATTERN}" \
        "[stub-bot] identified: server=${STUB_SERVER_ID} name=Survival protocol=v2 registered=true capabilities=[]" \
        no "v3 identify vs a v2 one" || failures=$((failures + 1))

    check_match "${STUB_CONFIG_ACKED_PATTERN}" \
        "[stub-bot] config acked by ${STUB_SERVER_ID} at version 1" \
        yes "stub saw the config ack" || failures=$((failures + 1))
    check_match "${STUB_CONSOLE_PATTERN}" \
        "[stub-bot] ws recv console_line id=abc from ${STUB_SERVER_ID}" \
        yes "stub received console lines" || failures=$((failures + 1))
    check_match "${STUB_CONSOLE_PATTERN}" \
        "[stub-bot] ws recv health id=abc from ${STUB_SERVER_ID}" \
        no "console_line vs the heartbeat's health frame" || failures=$((failures + 1))

    check_match "${STUB_ROSTER_PATTERN}" \
        "[stub-bot 22:41:07.883] on-ack get_players -> ${STUB_SERVER_ID}: 0 players" \
        yes "the plugin answered the bot's get_players" || failures=$((failures + 1))

    # ── The chat bridge ──────────────────────────────────────────────────────
    check_match "${STUB_BRIDGE_CAPABILITY_PATTERN}" \
        "[stub-bot 18:58:29.744] identified: server=${STUB_SERVER_ID} name=survival protocol=3 registered=true capabilities=[health@1, whitelist@1, rolesync@1, console@1, bridge@1]" \
        yes "bridge: the capability was declared" || failures=$((failures + 1))
    # The near-miss that matters: every other capability present and this one absent is exactly what
    # a build that forgot to register the module looks like, and the identify line still looks fine.
    check_match "${STUB_BRIDGE_CAPABILITY_PATTERN}" \
        "[stub-bot 18:58:29.744] identified: server=${STUB_SERVER_ID} name=survival protocol=3 registered=true capabilities=[health@1, whitelist@1, rolesync@1, console@1]" \
        no "bridge: a declared capability vs a build that shipped without the module" \
        || failures=$((failures + 1))
    # And a major bump must not satisfy it: bridge@2 against a bot that speaks major 1 is DROPPED,
    # so a row that accepted the string would be green on a plugin receiving no bridge config at all.
    check_match "${STUB_BRIDGE_CAPABILITY_PATTERN}" \
        "[stub-bot] identified: server=${STUB_SERVER_ID} name=Survival protocol=3 registered=true capabilities=[whitelist@1, bridge@2]" \
        no "bridge: bridge@1 vs an unnegotiable bridge@2" || failures=$((failures + 1))

    check_match "${STUB_DISCORD_SENT_PATTERN}" \
        "[stub-bot 18:58:30.257] on-ack bridge.discord -> ${STUB_SERVER_ID}: 1 message(s), delivered=1" \
        yes "bridge: the stub pushed a Discord line" || failures=$((failures + 1))
    # delivered=0 means the socket had already gone. The plugin-side assertion below would then be
    # asserting against a frame that was never sent, which is a harness failure wearing a plugin's
    # clothes.
    check_match "${STUB_DISCORD_SENT_PATTERN}" \
        "[stub-bot 18:58:30.257] on-ack bridge.discord -> ${STUB_SERVER_ID}: 1 message(s), delivered=0" \
        no "bridge: a delivered push vs one that reached no socket" || failures=$((failures + 1))

    # The [DEBUG] marker is in the sample because it is in the real line — the module logs this at
    # debug, and every configured row's bootstrap.yml sets `debug: true`. A row run without it would
    # produce no line at all, which is a harness problem the pattern cannot distinguish from a
    # missing subscriber, so it is worth seeing here.
    check_match "${BRIDGE_DELIVERED_PATTERN}" \
        "[18:58:30 INFO]: [Heimdall] [DEBUG] [bridge] relayed 1 discord message(s) to 0 online player(s)" \
        yes "bridge: the plugin rendered and fanned out an inbound line" || failures=$((failures + 1))
    # THE near-miss. A handler that ran but skipped every message — an empty text, a payload shape it
    # did not understand — prints exactly this, and it is the difference between "the subscription
    # works" and "the subscription exists".
    check_match "${BRIDGE_DELIVERED_PATTERN}" \
        "[18:58:30 INFO]: [Heimdall] [DEBUG] [bridge] relayed 0 discord message(s) to 0 online player(s)" \
        no "bridge: a relayed message vs a handler that understood none" || failures=$((failures + 1))
    # And another module's line must not satisfy it. Both the console and the bridge are batching
    # relays, and the prefix is the only thing in the line that says which one spoke.
    check_match "${BRIDGE_DELIVERED_PATTERN}" \
        "[18:58:30 INFO]: [Heimdall] [DEBUG] [console] relayed 1 discord message(s) to 0 online player(s)" \
        no "bridge: the bridge module's line vs another module's" || failures=$((failures + 1))
    # THE near-miss, and the only one that matters here: with no handler subscribed the plugin
    # replies nothing at all and the stub logs a timeout instead. A pattern that matched this would
    # be green on exactly the build this row exists to fail.
    check_match "${STUB_ROSTER_PATTERN}" \
        "[stub-bot 22:41:27.883] WARN on-ack get_players -> ${STUB_SERVER_ID} FAILED: Request timed out (get_players)" \
        no "an answered roster vs an unanswered request" || failures=$((failures + 1))
    # And the other way a reply can be wrong: correlated, but carrying an error instead of a roster.
    check_match "${STUB_ROSTER_PATTERN}" \
        "[stub-bot 22:41:07.883] on-ack get_players -> ${STUB_SERVER_ID}: error=the server is shutting down" \
        no "a roster vs an error payload" || failures=$((failures + 1))

    # Every sample below is copied verbatim out of smoke/.work-connected from a green run, not
    # written from memory. A fabricated sample proves the pattern matches the sample.
    check_match "${STUB_SYNC_PATTERN}" \
        "[stub-bot 22:41:04.195] DEBUG GET /api/guilds/${STUB_GUILD}/minecraft/whitelist/sync" \
        yes "stub served the whitelist sync" || failures=$((failures + 1))
    check_match "${STUB_SYNC_PATTERN}" \
        "[stub-bot 22:41:04.195] DEBUG POST /api/guilds/${STUB_GUILD}/minecraft/connection-attempt" \
        no "whitelist sync vs another signed route" || failures=$((failures + 1))
    check_match "${STUB_CLEAN_CLOSE_PATTERN}" \
        "[stub-bot 22:34:48.206] ws disconnected: guild=${STUB_GUILD} server=${STUB_SERVER_ID} code=1000 reason=Plugin shutting down" \
        yes "tunnel closed deliberately at shutdown" || failures=$((failures + 1))
    check_match "${STUB_CLEAN_CLOSE_PATTERN}" \
        "[stub-bot 22:34:48.206] ws disconnected: guild=${STUB_GUILD} server=${STUB_SERVER_ID} code=1001 reason=Heartbeat timeout" \
        no "a deliberate close vs a link that had already died" || failures=$((failures + 1))

    # The three banners this script greps but never checked. run.sh covers its own copies; these are
    # separate constants in a separate file, and a divergence between them is invisible until a row
    # times out waiting for a line the plugin stopped printing in that exact shape.
    check_match "${ENABLE_PATTERN}" \
        "[22:41:03 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role standalone, ticks via paper (tps+mspt), console tap on" \
        yes "enable banner (bukkit)" || failures=$((failures + 1))
    check_match "${ENABLE_PATTERN}" \
        "[22:34:45 INFO] [heimdall]: Heimdall v3.0.0-SNAPSHOT enabled — role gatekeeper, text bridge ok, console tap on" \
        yes "enable banner (velocity)" || failures=$((failures + 1))
    # A third log shape again: BungeeCord's ConciseFormatter brackets the level and PluginLogger
    # prefixes the plugin name, and the banner's middle clause differs because this platform has no
    # reflective text bridge to report on.
    check_match "${ENABLE_PATTERN}" \
        "22:34:45 [INFO] [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role gatekeeper, text via legacy components, console tap on" \
        yes "enable banner (bungee)" || failures=$((failures + 1))
    check_match "${DISABLE_PATTERN}" \
        "[22:41:07 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT shutting down" \
        yes "disable banner" || failures=$((failures + 1))
    check_match "${DISABLE_PATTERN}" \
        "[13:49:18 INFO]: Thread RCON Client /0:0:0:0:0:0:0:1 shutting down" \
        no "disable banner vs the RCON thread's own shutdown line" || failures=$((failures + 1))
    check_match "${READY_PATTERN}" \
        '[22:41:04 INFO]: Done (42.045s)! For help, type "help"' \
        yes "ready line (bukkit)" || failures=$((failures + 1))
    check_match "${READY_PATTERN}" "[22:34:45 INFO]: Done (1.12s)!" \
        yes "ready line (velocity)" || failures=$((failures + 1))
    check_match "${READY_PATTERN}" "[22:40:20 INFO]: Preparing spawn area: 36%" \
        no "ready line vs mid-boot progress" || failures=$((failures + 1))

    # The mirror pattern's whole point is the non-zero count, so both sides are pinned.
    check_match "${MIRROR_PATTERN}" \
        "[22:41:04 INFO]: [Heimdall] [whitelist] Mirror reconcile (whitelist-mirror.json): 2 added, 0 refreshed, 0 pruned (2 held)" \
        yes "mirror reconcile with rows" || failures=$((failures + 1))
    check_match "${MIRROR_PATTERN}" \
        "[22:41:04 INFO]: [Heimdall] [whitelist] Mirror reconcile (whitelist-mirror.json): 0 added, 0 refreshed, 0 pruned (0 held)" \
        no "a reconcile that took nothing proves nothing" || failures=$((failures + 1))

    # ── The login probe ──────────────────────────────────────────────────────
    check_match "${PROBE_ALLOW_PATTERN}" \
        "[13:51:02 INFO]: [Heimdall] [whitelist] mirror hit for ${PROBE_ALLOWED_PLAYER}" \
        yes "probe: allowed from the mirror" || failures=$((failures + 1))
    # The near-miss that matters: the same player denied would also mention them by name, so a
    # pattern that only looked for the name would pass on the exact outcome this row exists to
    # distinguish.
    check_match "${PROBE_ALLOW_PATTERN}" \
        "[13:51:02 INFO]: [Heimdall] [whitelist] refusing ${PROBE_ALLOWED_PLAYER}: not whitelisted" \
        no "probe: an allow is not a deny" || failures=$((failures + 1))
    check_match "${PROBE_DENY_PATTERN}" \
        "[13:51:04 INFO]: [Heimdall] [whitelist] refusing ${PROBE_DENIED_PLAYER}: not whitelisted" \
        yes "probe: denied by the bot" || failures=$((failures + 1))
    check_match "${PROBE_DENY_PATTERN}" \
        "[13:51:04 INFO]: [Heimdall] [whitelist] refusing ${PROBE_DENIED_PLAYER}: their whitelist was revoked" \
        no "probe: 'not whitelisted' vs a revocation" || failures=$((failures + 1))
    check_match "${STUB_PROBE_PATTERN}" \
        "[stub-bot 22:41:09.001] DEBUG POST /api/guilds/${STUB_GUILD}/minecraft/connection-attempt" \
        yes "probe: the stub saw the connection attempt" || failures=$((failures + 1))

    # ── Setup ────────────────────────────────────────────────────────────────
    check_match "${STUB_CLAIMED_PATTERN}" \
        "[stub-bot] claimed setup code ${SETUP_CODE} -> server ${SETUP_SERVER_ID}" \
        yes "setup: the stub consumed the code" || failures=$((failures + 1))
    check_match "${STUB_CLAIMED_PATTERN}" \
        "[stub-bot] issued setup code ${SETUP_CODE} for Smoke (server ${SETUP_SERVER_ID})" \
        no "setup: claimed vs merely issued" || failures=$((failures + 1))
    check_match "${STUB_SETUP_CONNECTED_PATTERN}" \
        "[stub-bot] ws connected: guild=${STUB_GUILD} server=${SETUP_SERVER_ID}" \
        yes "setup: the tunnel came up on the claimed server id" || failures=$((failures + 1))
    check_match "${STUB_SETUP_IDENTIFIED_PATTERN}" \
        "[stub-bot] identified: server=${SETUP_SERVER_ID} name=Smoke protocol=3 registered=true capabilities=[whitelist@1, rolesync@1, console@1]" \
        yes "setup: v3 identify after the claim" || failures=$((failures + 1))
    check_match "${SETUP_MODULE_ENABLED_PATTERN}" \
        "[13:52:00 INFO]: [Heimdall] module 'whitelist' enabled" \
        yes "setup: a module went live" || failures=$((failures + 1))
    check_match "${SETUP_MODULE_ENABLED_PATTERN}" \
        "[13:52:00 INFO]: [Heimdall] module 'whitelist' disabled" \
        no "setup: enabled vs disabled" || failures=$((failures + 1))

    # ── Migration ────────────────────────────────────────────────────────────
    check_match "${MIGRATED_PATTERN}" \
        "[13:53:00 INFO]: [Heimdall] Migrated the v2 config at /data/plugins/HeimdallWhitelist/config.yml - wrote /data/plugins/Heimdall/bootstrap.yml and the v2 file has been kept as /data/plugins/HeimdallWhitelist/config.yml.v2-backup." \
        yes "migrate: the v2 config was found and rewritten" || failures=$((failures + 1))
    check_match "${MIGRATED_PATTERN}" \
        "[22:34:45 INFO] [heimdall]: Migrated the v2 config at /server/plugins/heimdall-whitelist/config.json - wrote /server/plugins/heimdall/bootstrap.yml and the v2 file has been kept as /server/plugins/heimdall-whitelist/config.json.v2-backup." \
        yes "migrate: the v2 config was found and rewritten (velocity/json)" || failures=$((failures + 1))
    check_match "${MIGRATED_PATTERN}" \
        "[13:53:00 INFO]: [Heimdall] not set up yet - run /hd setup <code> to connect this server to Discord" \
        no "migrate: a migration vs a fresh install" || failures=$((failures + 1))
    # The row does not stop at MIGRATED_PATTERN: it then greps for its OWN platform's directory and
    # file name, so a Velocity row cannot go green on the line a Bukkit row would produce. That
    # second grep is the assertion that would have caught departure D70, so its near miss is pinned
    # here too — the wrong directory name must NOT match.
    check_match "Migrated the v2 config at .*heimdall-whitelist/config\.json" \
        "[22:34:45 INFO] [heimdall]: Migrated the v2 config at /server/plugins/heimdallwhitelist/config.json - wrote /server/plugins/heimdall/bootstrap.yml" \
        no "migrate: v2's real proxy directory vs the lower-cased display name (D70)" \
        || failures=$((failures + 1))
    check_match "Migrated the v2 config at .*heimdall-whitelist/config\.json" \
        "[22:34:45 INFO] [heimdall]: Migrated the v2 config at /server/plugins/heimdall-whitelist/config.json - wrote /server/plugins/heimdall/bootstrap.yml" \
        yes "migrate: the proxy row's own directory assertion" || failures=$((failures + 1))
    check_match "${STUB_IMPORT_PATTERN}" \
        "[stub-bot] config import for ${MIGRATE_SERVER_ID} (imported=true)" \
        yes "migrate: the translated settings reached the dashboard" || failures=$((failures + 1))
    # Write-once means a second import answers imported=false, which is a SUCCESS bot-side and
    # would be a silent nothing here. The row asserts the first one specifically.
    check_match "${STUB_IMPORT_PATTERN}" \
        "[stub-bot] config import for ${MIGRATE_SERVER_ID} (imported=false)" \
        no "migrate: imported vs already present" || failures=$((failures + 1))
    check_match "${STUB_LEGACY_IDENTIFY_PATTERN}" \
        "[stub-bot] identify: guild=${STUB_GUILD} (legacy: no token id)" \
        yes "migrate: the stub saw a legacy identify" || failures=$((failures + 1))
    check_match "${STUB_LEGACY_IDENTIFY_PATTERN}" \
        "[stub-bot] identify: guild=${STUB_GUILD} tokenId=stub-token-abc12345" \
        no "migrate: legacy identify vs a token-id one" || failures=$((failures + 1))

    local row name image type version platform memory mode
    for row in "${ROWS[@]}"; do
        IFS='|' read -r name image type version platform memory mode <<<"${row}"
        if [ -z "${name}" ] || [ -z "${image}" ] || [ -z "${type}" ] || [ -z "${version}" ] \
                || [ -z "${memory}" ] || [ -z "${mode}" ]; then
            fail "malformed row: ${row}"
            failures=$((failures + 1))
        elif [ "${platform}" != "bukkit" ] && [ "${platform}" != "velocity" ] \
                && [ "${platform}" != "bungee" ]; then
            # Every platform branch in this file is a test against one of these three strings, so a
            # fourth value does not fail — it silently takes the velocity path, mounts the wrong
            # directory, and reports a plugin that would not load.
            fail "row ${name} has unknown platform '${platform}'"
            failures=$((failures + 1))
        elif [ "${platform}" = "bungee" ] && ! [[ "${version}" =~ ^[0-9]+$ ]]; then
            # A bungee row's VERSION field is a Jenkins build number, not a version string. Anything
            # else resolves to no artifact — or, worse, silently falls back to the image's
            # `lastStableBuild` default and stops testing what the row says it tests.
            fail "row ${name} is a bungee row, so its VERSION must be a Jenkins build number, not '${version}'"
            failures=$((failures + 1))
        elif [ "${platform}" = "bungee" ] && [ "${mode}" = "migrate" ]; then
            # v2 shipped a Bukkit build and a Velocity build and nothing else, so there is no v2
            # BungeeCord install anywhere and no fixture that could honestly represent one. A row
            # like this would assert against a file no operator has ever had — the same mistake the
            # velocity-migrate row exists to have corrected, made in the opposite direction.
            fail "row ${name} is mode 'migrate' on bungee, which v2 never shipped a build for (D78)"
            failures=$((failures + 1))
        elif [ "${mode}" != "configured" ] && [ "${mode}" != "setup" ] && [ "${mode}" != "migrate" ]; then
            # Same trap, one field along: an unknown mode would take the `configured` branch, write
            # a bootstrap.yml, and quietly prove the thing the row was not written for.
            fail "row ${name} has unknown mode '${mode}'"
            failures=$((failures + 1))
        elif [ "${mode}" = "setup" ] && [ "${platform}" != "bukkit" ]; then
            # `setup` types a command into the running server, which needs RCON — and only the Paper
            # image in this matrix has it. `migrate` is deliberately NOT covered by this rule: it
            # waits on log lines and then reads the host file system, so it needs no console, and it
            # is the one mode whose inputs differ per platform (departure D70). Excluding it here
            # once cost a production incident.
            fail "row ${name} is mode 'setup' on '${platform}', which has no console to drive"
            failures=$((failures + 1))
        fi
    done

    if [ "${failures}" -ne 0 ]; then
        fail "${failures} self-test assertion(s) failed"
        return 1
    fi
    pass "connected self-test: every pattern fires on its real line and stays quiet on its near-miss"
}

# ── Helpers ──────────────────────────────────────────────────────────────────────────────────

find_jar() {
    if [ -n "${SMOKE_JAR:-}" ]; then
        [ -f "${SMOKE_JAR}" ] || { fail "SMOKE_JAR=${SMOKE_JAR} does not exist"; return 1; }
        printf '%s' "${SMOKE_JAR}"
        return 0
    fi
    local found
    found="$(find "${REPO_ROOT}/app/build/libs" "${REPO_ROOT}/dist" -maxdepth 1 \
        -name 'heimdall-whitelist-*.jar' ! -name 'original-*' 2>/dev/null | sort | tail -n 1)"
    if [ -z "${found}" ]; then
        fail "no shaded jar found — run ./gradlew build, or set SMOKE_JAR"
        return 1
    fi
    printf '%s' "${found}"
}

find_stub() {
    local dist="${SMOKE_STUB_DIST:-${REPO_ROOT}/stub-bot/build/install/stub-bot}"
    if [ ! -x "${dist}/bin/stub-bot" ] && [ ! -f "${dist}/bin/stub-bot" ]; then
        fail "no stub-bot launcher at ${dist}/bin/stub-bot — run ./gradlew build"
        return 1
    fi
    printf '%s' "${dist}"
}

# Writes the bootstrap the plugin will boot with.
#
# Deliberately NO guildIdCache. That is what forces the discovery path (departure D54) and makes
# GUILD_RESOLVED_PATTERN an assertion about the identify endpoint rather than about a value the
# plugin cached on a previous run — and it is also what a real fresh install looks like the moment
# after it is claimed.
#
# CHECKED, not assumed. Both writes below used to be bare, and `set -e` does not help: row_body's
# result is tested by the caller, which disables errexit for everything inside it. A failed write
# therefore sailed on and the row reported "the plugin never resolved its guild", which is a
# plugin-shaped verdict for a harness-shaped problem — the same misattribution the exit-timeout
# work fixed one level up.
write_bootstrap() {
    local target="$1"
    if ! mkdir -p "$(dirname "${target}")"; then
        fail "HARNESS: could not create $(dirname "${target}")"
        return 1
    fi
    if ! cat >"${target}" <<YAML
endpoint: "http://stub-bot:8080"
tokenId: "smoke-token"
token: "${STUB_SECRET}"
serverId: "${STUB_SERVER_ID}"
role: "auto"
debug: true
YAML
    then
        fail "HARNESS: could not write ${target}"
        return 1
    fi
    return 0
}

# Writes the v2 config.yml the Bukkit migration row has to find.
#
# Deliberately in the SIBLING directory. v2's Bukkit plugin declared `name: HeimdallWhitelist` and
# v3 declares `Heimdall`, so a server whose jar has just been swapped has a brand-new empty
# plugins/Heimdall/ and its entire configuration next door. A migration that only looked in its own
# directory would find nothing on every real upgrade, which is the one case it exists for — so the
# row reproduces the real layout rather than a convenient one.
#
# The credentials are the stub's, so the migrated server connects on a LEGACY guild key: v2 had no
# token id, and the whole point of legacy mode is that the same HMAC still authenticates.
write_v2_config() {
    local target="$1"
    if ! mkdir -p "$(dirname "${target}")"; then
        fail "HARNESS: could not create $(dirname "${target}")"
        return 1
    fi
    if ! cat >"${target}" <<YAML
enabled: true
api:
  baseUrl: "http://stub-bot:8080"
  apiKey: "${STUB_SECRET}"
  guildId: "${STUB_GUILD}"
  timeout: 1500
  retries: 1
  retryDelay: 1000
server:
  serverId: "${MIGRATE_SERVER_ID}"
  displayName: "Migrated Survival"
logging:
  debug: true
cache:
  cacheWindow: 90
  extendOnJoin: 120
  extendOnLeave: 180
  maxExtensionHours: 12
  cleanupInterval: 45
  prewarm:
    enabled: true
    intervalMinutes: 15
advanced:
  apiFallbackMode: whitelist-only
bypass:
  uuids: []
roleSync:
  enabled: true
console:
  stream: true
updates:
  checkEnabled: true
  notifyAdmins: false
  checkIntervalHours: 6
YAML
    then
        fail "HARNESS: could not write ${target}"
        return 1
    fi
    return 0
}

# Writes the v2 config.json the Velocity migration row has to find.
#
# A SEPARATE function rather than the YAML one with a different extension, because v2's proxy build
# genuinely wrote a different document: VelocityConfigProvider.createDefaultConfig() (see
# origin/v2-maintenance) emits exactly these blocks and no others. In particular it never wrote
# `roleSync`, `console`, `cache.prewarm` or `updates` at all — so a real proxy's settings for those
# are v2's CODE defaults with nothing on disk to read, and a fixture that helpfully added them would
# be testing a file no v2 install has ever had.
#
# It also lands in a differently-named directory: Velocity derives a plugin's data directory from its
# @Plugin id, and v2's id is `heimdall-whitelist` — hyphen included, and NOT the lower-cased display
# name. That single character is departure D70, and it is the reason this row exists.
write_v2_config_json() {
    local target="$1"
    if ! mkdir -p "$(dirname "${target}")"; then
        fail "HARNESS: could not create $(dirname "${target}")"
        return 1
    fi
    if ! cat >"${target}" <<JSON
{
  "enabled": true,
  "api": {
    "baseUrl": "http://stub-bot:8080",
    "apiKey": "${STUB_SECRET}",
    "guildId": "${STUB_GUILD}",
    "timeout": 1500,
    "retries": 1,
    "retryDelay": 1000
  },
  "server": {
    "serverId": "${MIGRATE_SERVER_ID}",
    "displayName": "Migrated Proxy",
    "publicIp": "play.example.net"
  },
  "messages": {
    "apiUnavailable": "\\u00a7cThe network is having a moment. Try again shortly.",
    "apiUnavailableAllowed": "\\u00a7eWhitelist API is temporarily unavailable. You have been allowed in from cache.",
    "reloaded": "\\u00a7aHeimdall Whitelist plugin reloaded successfully!",
    "status": "\\u00a77Heimdall Whitelist Status:"
  },
  "logging": {
    "debug": true,
    "logDecisions": true
  },
  "performance": {
    "cacheTimeout": 30,
    "maxConcurrentRequests": 128
  },
  "cache": {
    "enabled": true,
    "cacheWindow": 45,
    "extendOnJoin": 240,
    "extendOnLeave": 300,
    "maxExtensionHours": 12,
    "cleanupInterval": 15
  },
  "advanced": {
    "apiFallbackMode": "whitelist-only"
  },
  "bypass": {
    "uuids": []
  },
  "websocket": {
    "enabled": true,
    "reconnect-delay": 5000,
    "max-reconnect-delay": 30000,
    "heartbeat-interval": 30000,
    "heartbeat-timeout": 10000
  }
}
JSON
    then
        fail "HARNESS: could not write ${target}"
        return 1
    fi
    return 0
}

# Waits for RCON to answer, then runs one command and prints whatever it said.
#
# Readiness and the action are separate, and that separation cost five red runs to learn in run.sh:
# retrying the ACTION conflates "RCON is not up yet" with "the command was accepted and the
# connection dropped", and the second is often the normal case. RCON opens some time AFTER the Done
# line and on a loaded runner that gap has been seen past 30s, so the budget is generous and the
# thing polled is `list`, which is idempotent.
wait_for_rcon() {
    local container="$1" budget="$2"
    local deadline=$(( $(date +%s) + budget ))
    while [ "$(date +%s)" -lt "${deadline}" ]; do
        if timeout 20 docker exec "${container}" rcon-cli list >/dev/null 2>&1; then
            return 0
        fi
        sleep 3
    done
    return 1
}

# Deletes a work directory that a container may have written into as root.
#
# This is B4, and it is the assertion-disarming kind of bug. The Bukkit row mounts its work
# directory at /data/plugins, where the server runs as uid 1000 and Paper writes .paper-remapped —
# so on Linux CI a host-side `rm -rf` fails with EPERM on files the runner does not own. The row
# then reuses the PREVIOUS run's directory, which already contains a bootstrap.yml carrying a
# guildIdCache and a config-cache.json. Both of the things this scenario exists to prove are
# skipped: discovery never runs because the guild is already known (D54), and the capability
# deadlock that D55 fixed cannot recur because the cached config enables the modules anyway.
#
# A green row that proved neither of its two headline claims is worse than a red one.
#
# So the delete happens from inside a throwaway container running as root, which can remove
# anything under the mount. The host-side rm stays as the fast path and for the directories no
# container ever touched.
purge_work_dir() {
    local dir="$1"
    [ -e "${dir}" ] || return 0
    rm -rf "${dir}" 2>/dev/null || true
    [ -e "${dir}" ] || return 0

    docker run --rm -v "$(host_path "${dir}"):/purge" eclipse-temurin:21-jre \
        sh -c 'rm -rf /purge/..?* /purge/.[!.]* /purge/* 2>/dev/null || true' >/dev/null 2>&1 || true
    rmdir "${dir}" 2>/dev/null || true

    # Emptied is enough — the directory itself is recreated immediately below. What must not survive
    # is a bootstrap.yml or a config cache from a previous run.
    if [ -n "$(ls -A "${dir}" 2>/dev/null || true)" ]; then
        fail "HARNESS: could not clear ${dir}; a previous run's bootstrap.yml or config cache would"
        fail "be reused, which silently skips the guild-discovery and capability assertions"
        return 1
    fi
    return 0
}

# ── One row ──────────────────────────────────────────────────────────────────────────────────

run_row() {
    local name="$1" image="$2" type="$3" version="$4" platform="$5" memory="$6" mode="$7"
    local jar="$8" stub="$9"

    local network="heimdall-connected-${name}"
    local stub_container="heimdall-connected-stub-${name}"
    local server_container="heimdall-connected-${name}"
    local work="${WORK_ROOT}/${name}"
    local server_log="${work}/server.log"
    local stub_log="${work}/stub.log"

    CURRENT_CONTAINERS="${stub_container} ${server_container}"
    CURRENT_NETWORK="${network}"

    local rc=0
    row_body || rc=$?

    if [ "${SMOKE_KEEP:-0}" != "1" ]; then
        docker rm -f "${server_container}" "${stub_container}" >/dev/null 2>&1 || true
        docker network rm "${network}" >/dev/null 2>&1 || true
    else
        log "SMOKE_KEEP=1 — ${stub_container}, ${server_container} and ${work} left in place"
    fi
    CURRENT_CONTAINERS=""
    CURRENT_NETWORK=""
    return "${rc}"
}

# shellcheck disable=SC2317
row_body() {
    log "── ${name}: ${type} ${version} against stub-bot (${mode})"

    docker rm -f "${server_container}" "${stub_container}" >/dev/null 2>&1 || true
    docker network rm "${network}" >/dev/null 2>&1 || true
    if ! purge_work_dir "${work}"; then
        return 1
    fi
    if ! mkdir -p "${work}/plugins"; then
        fail "HARNESS: could not create ${work}/plugins"
        return 1
    fi
    if ! cp "${jar}" "${work}/plugins/"; then
        fail "HARNESS: could not stage the jar into ${work}/plugins"
        return 1
    fi
    if ! docker network create "${network}" >/dev/null; then
        fail "HARNESS: could not create the network ${network}"
        return 1
    fi

    # The fake bot. No image build: the Gradle installDist output is mounted into a stock JRE, which
    # is the same trick smoke/docker-compose.yml documents — building an image would mean either a
    # Docker-side Gradle build or a repo-root build context, and neither buys anything for a fixture
    # that is rebuilt whenever the code changes.
    #
    # STUB_BOT_MODULES overrides the default, which has console DISABLED because it streams every log
    # line. This scenario is the one place that is exactly what we want to observe.
    # The setup row needs a code waiting before the server boots. STUB_BOT_CLAIM_CODES exists for
    # exactly this: bot.issueClaimCode() is a Java hook, and a shell script driving a container has
    # no way to reach into the JVM. The server id is fixed so the assertions below can name it.
    local -a stub_env=()
    if [ "${mode}" = "setup" ]; then
        stub_env=(-e "STUB_BOT_CLAIM_CODES=${SETUP_CODE}:Smoke:${SETUP_SERVER_ID}")
    fi

    if ! docker run -d --name "${stub_container}" --network "${network}" \
            --network-alias stub-bot \
            -v "$(host_path "${stub}"):/opt/stub-bot:ro" \
            -w /opt/stub-bot \
            -e STUB_BOT_BIND=0.0.0.0 \
            -e STUB_BOT_PORT=8080 \
            -e "STUB_BOT_GUILD_ID=${STUB_GUILD}" \
            -e "STUB_BOT_API_KEY=${STUB_SECRET}" \
            -e STUB_BOT_VERBOSE=true \
            -e 'STUB_BOT_MODULES={"whitelist":{"enabled":true},"rolesync":{"enabled":true},"offenses":{"enabled":true},"console":{"enabled":true},"bridge":{"enabled":true}}' \
            -e "STUB_BOT_REQUEST_ON_ACK=${STUB_ON_ACK_REQUESTS}" \
            -e "STUB_BOT_DISCORD_ON_ACK=${STUB_DISCORD_ON_ACK}" \
            ${stub_env[@]+"${stub_env[@]}"} \
            eclipse-temurin:21-jre /opt/stub-bot/bin/stub-bot >/dev/null; then
        fail "HARNESS: could not start the stub bot"
        return 1
    fi
    docker logs -f "${stub_container}" >"${stub_log}" 2>&1 &
    local stub_tail=$!

    # The stub binds before it prints this, so waiting for it is what stops the server racing an
    # endpoint that is not listening yet — which would look like a plugin that cannot reach its bot.
    if ! wait_for_pattern "${stub_log}" 'listening on' 60 "the stub bot to start"; then
        kill "${stub_tail}" 2>/dev/null || true
        dump_log "${stub_log}"
        return 1
    fi

    local -a docker_args
    if [ "${platform}" = "bukkit" ]; then
        # ONE writable mount at /data/plugins, carrying the jar and the plugin's data directory.
        #
        # This is the opposite of what smoke/run.sh does, and the difference is the point. run.sh
        # only has to get a jar in, so it uses the image's read-only /plugins staging path — which
        # exists precisely because a host mount at /data/plugins is owned by the host user while the
        # server runs as uid 1000, and modern Paper dies creating /data/plugins/.paper-remapped
        # before it looks at a plugin. This row additionally has to get bootstrap.yml in BEFORE
        # boot, and staging cannot place a file inside a plugin's own data directory.
        #
        # So the mount is used and the ownership problem is solved head-on with chmod 777 on the
        # host side. That is safe here and nowhere near production: this directory is created and
        # deleted by this script. Mounting only /data/plugins/Heimdall does NOT work — Docker then
        # creates the /data/plugins parent as root, and Paper fails exactly as described above.
        # Three modes, three starting states. `setup` writes nothing at all, which is what a
        # freshly dropped-in jar looks like and what makes the /hd setup assertions mean anything.
        case "${mode}" in
            configured)
                if ! write_bootstrap "${work}/data-plugins/Heimdall/bootstrap.yml"; then
                    kill "${stub_tail}" 2>/dev/null || true
                    return 1
                fi
                ;;
            migrate)
                if ! write_v2_config "${work}/data-plugins/HeimdallWhitelist/config.yml"; then
                    kill "${stub_tail}" 2>/dev/null || true
                    return 1
                fi
                ;;
            setup)
                if ! mkdir -p "${work}/data-plugins"; then
                    fail "HARNESS: could not create ${work}/data-plugins"
                    kill "${stub_tail}" 2>/dev/null || true
                    return 1
                fi
                ;;
        esac
        if ! cp "${jar}" "${work}/data-plugins/"; then
            fail "HARNESS: could not stage the jar into ${work}/data-plugins"
            kill "${stub_tail}" 2>/dev/null || true
            return 1
        fi
        chmod -R 777 "${work}/data-plugins" 2>/dev/null || true
        docker_args=(
            run -d --name "${server_container}" --network "${network}"
            -e EULA=TRUE -e "TYPE=${type}" -e "VERSION=${version}" -e "MEMORY=${memory}"
            -e ONLINE_MODE=FALSE -e USE_AIKAR_FLAGS=false
            -e VIEW_DISTANCE=4 -e SPAWN_PROTECTION=0
            -e ENABLE_RCON=true -e RCON_PASSWORD=smoke -e CREATE_CONSOLE_IN_PIPE=true
            -v "$(host_path "${work}/data-plugins"):/data/plugins:rw"
        )
    else
        # The proxy image chowns its own /server tree before dropping privileges, so one writable
        # mount carries both the jar and the plugin's own data directory.
        #
        # WHERE that data directory is differs between the two proxies, and it is not a cosmetic
        # difference: Velocity derives it from the plugin ID (`heimdall`), while BungeeCord derives it
        # from the descriptor's NAME (`Heimdall`, matching the Bukkit family). Writing the bootstrap
        # into the wrong one produces a proxy that boots perfectly and reports itself unconfigured,
        # which is exactly the shape of departure D70.
        local proxy_data_dir="heimdall"
        [ "${platform}" = "bungee" ] && proxy_data_dir="Heimdall"
        case "${mode}" in
            configured)
                if ! write_bootstrap "${work}/plugins/${proxy_data_dir}/bootstrap.yml"; then
                    kill "${stub_tail}" 2>/dev/null || true
                    return 1
                fi
                ;;
            migrate)
                # No plugins/heimdall/ is created here on purpose: a proxy that has just had its
                # jar swapped has never run v3, so its only Heimdall directory is v2's. The plugin
                # has to make its own and find the config next door.
                #
                # Velocity only, and the self-test refuses a bungee migrate row: v2 had no BungeeCord
                # build, so there is no v2 directory this fixture could honestly represent (D78).
                if ! write_v2_config_json "${work}/plugins/heimdall-whitelist/config.json"; then
                    kill "${stub_tail}" 2>/dev/null || true
                    return 1
                fi
                ;;
            *)
                # `setup` would land here, and would be a row that stages nothing and then asserts
                # against a console this image does not have. The self-test's row-shape guard
                # already refuses that combination, but this branch should be safe on its own
                # terms — a guard in a different function is not a precondition this one enforces.
                fail "HARNESS: unhandled mode '${mode}' for proxy staging"
                kill "${stub_tail}" 2>/dev/null || true
                return 1
                ;;
        esac
        docker_args=(
            run -d --name "${server_container}" --network "${network}"
            -e "TYPE=${type}" -e "MEMORY=${memory}"
            -v "$(host_path "${work}/plugins"):/server/plugins:rw"
        )
        # One env var apart, and it is not a spelling difference: VELOCITY_VERSION selects a
        # published release, BUNGEE_JOB_ID selects a CI build number the image turns into an
        # artifact URL. Passing the wrong one is silent — the image falls back to lastStableBuild.
        if [ "${platform}" = "bungee" ]; then
            docker_args+=(-e "BUNGEE_JOB_ID=${version}")
        else
            docker_args+=(-e "VELOCITY_VERSION=${version}")
        fi
    fi

    # The premise, asserted rather than assumed. If a previous run's cache survived, the two
    # headline claims below are silently vacuous — so this is checked here, where it is a harness
    # failure, instead of being discovered as a mysteriously fast "guild resolved".
    #
    # Only for the configured rows. The migration row's whole point is that v2's api.guildId becomes
    # the cache (departure D54), and the setup row has no bootstrap.yml to check.
    if [ "${mode}" = "configured" ] && grep -q "guildIdCache" \
            "${work}"/*/Heimdall/bootstrap.yml "${work}"/*/heimdall/bootstrap.yml 2>/dev/null; then
        fail "HARNESS: the bootstrap.yml carries a cached guild, so the discovery assertion would"
        fail "pass without the identify endpoint ever being called"
        kill "${stub_tail}" 2>/dev/null || true
        return 1
    fi

    if ! docker "${docker_args[@]}" "${image}" >/dev/null; then
        fail "HARNESS: docker run failed for ${name}"
        kill "${stub_tail}" 2>/dev/null || true
        return 1
    fi
    docker logs -f "${server_container}" >"${server_log}" 2>&1 &
    local server_tail=$!

    local rc=0
    case "${mode}" in
        configured) assert_row || rc=$? ;;
        setup) assert_setup_row || rc=$? ;;
        migrate) assert_migrate_row || rc=$? ;;
    esac

    # Stop the server first, so its disable banner and the stub's disconnect line both land.
    if [ "${platform}" = "bukkit" ]; then
        # Readiness and the action are separate commands, copied from run.sh where the separation
        # cost five red runs to learn. Retrying `stop` conflates two outcomes it cannot tell apart:
        # "RCON is not up yet" and "the stop was accepted and the connection dropped because the
        # server is shutting down". The second is the NORMAL case, so a retry loop reports failures,
        # falls through to SIGTERM against a server already on its way down, and the row dies on a
        # missing disable banner that was never the plugin's fault.
        #
        # RCON opens some time AFTER the Done line, and on a loaded runner that gap has been seen
        # past 30s — so the budget is generous and `list` is what is polled, being idempotent.
        log "waiting for rcon to answer (budget ${RCON_TIMEOUT}s)"
        local rcon_ready=0
        if wait_for_rcon "${server_container}" "${RCON_TIMEOUT}"; then
            rcon_ready=1
        fi
        if [ "${rcon_ready}" -eq 1 ]; then
            timeout 30 docker exec "${server_container}" rcon-cli stop >/dev/null 2>&1 || true
        else
            warn "rcon never answered within ${RCON_TIMEOUT}s; falling back to SIGTERM"
            docker stop -t "${STOP_TIMEOUT}" "${server_container}" >/dev/null
        fi
    else
        docker stop -t "${STOP_TIMEOUT}" "${server_container}" >/dev/null
    fi
    if ! wait_for_exit "${server_container}" "${STOP_TIMEOUT}"; then
        attribute_exit_timeout "${server_log}" "${server_container}" "${STOP_TIMEOUT}"
        rc=1
    fi
    wait_for_log_flush "${server_tail}" 30

    if [ "${rc}" -eq 0 ]; then
        if ! wait_for_pattern "${server_log}" "${DISABLE_PATTERN}" 30 "the plugin's disable banner"; then
            explain_runner_kill "${server_log}" \
                || fail "no disable banner — the shutdown handler did not run, or threw first"
            rc=1
        elif [ "${mode}" != "configured" ]; then
            # The clean-close pattern names the configured rows' server id, and the flow rows connect
            # under a different one. Their disable banner is the assertion available here; the
            # tunnel-was-live claim is the configured rows' to make.
            pass "plugin disabled cleanly"
        elif ! wait_for_pattern "${stub_log}" "${STUB_CLEAN_CLOSE_PATTERN}" 30 \
                "the stub to see the tunnel closed deliberately"; then
            fail "the plugin logged its disable banner, but the stub never saw a deliberate close —"
            fail "so 'with a live tunnel' is unproven: the socket may have dropped long before"
            rc=1
        else
            pass "plugin disabled cleanly with a live tunnel"
        fi
        if ! assert_no_heimdall_errors "${server_log}" "shutdown"; then
            rc=1
        fi
    fi

    kill "${stub_tail}" 2>/dev/null || true
    wait "${stub_tail}" 2>/dev/null || true

    if [ "${rc}" -ne 0 ]; then
        dump_log "${server_log}"
        warn "── stub bot log ──"
        dump_log "${stub_log}" 60
    fi
    return "${rc}"
}

# Everything this scenario exists to prove, in the order it becomes true.
# shellcheck disable=SC2317
assert_row() {
    if ! wait_for_pattern "${server_log}" "${ENABLE_PATTERN}" "${BOOT_TIMEOUT}" \
            "the plugin's enable banner"; then
        return 1
    fi
    pass "plugin enabled"

    if ! wait_for_pattern "${server_log}" "${GUILD_RESOLVED_PATTERN}" 90 \
            "the guild to be resolved from the token"; then
        fail "bootstrap.yml carries no guildId on purpose, so this is the identify endpoint failing"
        return 1
    fi
    pass "guild resolved from the token alone (D54)"

    if ! wait_for_pattern "${stub_log}" "${STUB_CONNECTED_PATTERN}" 90 \
            "the stub to accept the WebSocket upgrade"; then
        return 1
    fi
    if ! wait_for_pattern "${stub_log}" "${STUB_IDENTIFIED_PATTERN}" 60 \
            "a v3 identify with capabilities"; then
        return 1
    fi
    if ! wait_for_pattern "${server_log}" "${NEGOTIATED_PATTERN}" 60 \
            "the v3 handshake to complete"; then
        fail "the plugin did not read the identify_ack as a v3 acceptance — see departure D51"
        return 1
    fi
    pass "tunnel connected and negotiated v3, from both ends"

    if ! wait_for_pattern "${stub_log}" "${STUB_CONFIG_ACKED_PATTERN}" 60 \
            "the config push to be acked"; then
        return 1
    fi
    pass "config pushed and acked"

    if ! wait_for_pattern "${stub_log}" "${STUB_SYNC_PATTERN}" 120 \
            "the stub to serve a signed whitelist/sync"; then
        fail "the claim below is about a signed HTTP round trip, so the STUB has to have seen it"
        return 1
    fi
    if ! wait_for_pattern "${server_log}" "${MIRROR_PATTERN}" 120 \
            "the whitelist pre-warm to reconcile rows"; then
        return 1
    fi
    pass "whitelist mirror pre-warmed over the signed HTTP API"

    if ! wait_for_pattern "${stub_log}" "${STUB_CONSOLE_PATTERN}" 90 \
            "console lines to reach the stub"; then
        fail "the console module batched nothing, or the tunnel did not carry it"
        return 1
    fi
    pass "console lines streamed to the bot"

    # The other direction: the bot ASKED and the plugin answered. Everything above this line would
    # be green on a build with no request handlers subscribed at all — which is the build that
    # shipped, and what the dashboard saw was a ten-second 504 on its Online Players panel.
    if ! wait_for_pattern "${stub_log}" "${STUB_ROSTER_PATTERN}" 90 \
            "the plugin to answer the bot's get_players"; then
        fail "no correlated player_list came back — the request handler is missing or unsubscribed"
        return 1
    fi
    pass "the bot's on-demand get_players was answered"

    assert_bridge || return 1

    # Waited for last rather than first: a server that is still generating its spawn area has not
    # finished booting, and stopping it there loses the shutdown race.
    if [ "${platform}" = "bukkit" ]; then
        wait_for_pattern "${server_log}" "${READY_PATTERN}" "${BOOT_TIMEOUT}" "the server to finish starting" \
            || return 1
        assert_login_probe || return 1
    fi
    return 0
}

# The chat bridge, as far as a harness with no client can take it.
#
# Run on ALL THREE configured rows, and the Velocity one is not a formality: the bridge module is
# eligible on every role, so a proxy negotiates bridge@1 and delivers bridge.discord exactly as a
# backend does. What differs on a proxy is only relayChat, which defaults OFF there — and that
# default is precisely why the OUTBOUND direction cannot be asserted here even in principle on that
# row. relayEvents, the events half, does NOT differ by role: it defaults on everywhere, since that
# is what every instance did before the setting existed. Duplicate session events are also dropped
# bot-side, but best-effort only — enough that this default need not encode a topology, not enough
# to call several origins safe.
#
# The outbound half is unreachable on every row for a simpler reason: it needs a player. Chat, join,
# leave and death all do, and there is no headless client in this harness. That gap is NOT papered
# over with a console verb that injects fake chat — shipping a test hook in the production jar to
# turn a row green is the kind of trade that makes a suite worthless. It is covered instead by
# HeimdallBridgeModuleTest, which drives the real ChatPipeline and the real PlayerSessionEvents
# through the real ModuleManager, and by stub-bot's BridgeFramesTest for the wire shape. When the
# headless client D43 has been waiting for arrives, this is the function it plugs into.
# shellcheck disable=SC2317
assert_bridge() {
    if ! wait_for_pattern "${stub_log}" "${STUB_BRIDGE_CAPABILITY_PATTERN}" 30 \
            "the bridge capability to be declared"; then
        fail "the plugin connected without bridge@1, so the bot would never send it a Discord line"
        fail "— the module is missing from this build, or ineligible on this role"
        return 1
    fi
    pass "bridge@1 negotiated"

    if ! wait_for_pattern "${stub_log}" "${STUB_DISCORD_SENT_PATTERN}" 60 \
            "the stub to push a rendered Discord line"; then
        fail "HARNESS: the on-ack bridge.discord never went out, so the assertion below would be"
        fail "about a frame that was never sent"
        return 1
    fi

    if ! wait_for_pattern "${server_log}" "${BRIDGE_DELIVERED_PATTERN}" 60 \
            "the plugin to render and fan out the inbound line"; then
        fail "the frame crossed the wire and nothing handled it. That is the failure get_players"
        fail "shipped with — a complete delivery path with nothing subscribed to the request — and"
        fail "in this direction it is silent at both ends: the bot sends a notification and waits"
        fail "for no reply, so only the plugin's own line can prove a subscriber existed."
        return 1
    fi
    pass "an inbound Discord line was rendered and fanned out (0 players — no client in this harness)"
    return 0
}

# The login gate, driven for two named players without either of them joining.
#
# This is as close as the harness gets to a real login, and the gap is worth naming: `/hd test`
# runs the ACTUAL interceptor — module toggle, role check, bypass list, mirror, bot, fallback mode —
# with its writes suppressed, so what it reports is the decision those players would get. What it
# does not exercise is the platform's own login listener and the kick screen, which still need a
# headless client (D43's residual risk 2).
#
# Asserted from both ends wherever both ends have something to say. The deny path reaches the bot,
# so the STUB has to have seen the request; the allow path is answered by the mirror, which is the
# common path on a warmed server and the one v2 shipped without a report on.
# shellcheck disable=SC2317
assert_login_probe() {
    if ! wait_for_rcon "${server_container}" "${RCON_TIMEOUT}"; then
        fail "rcon never answered, so the login probe could not be driven"
        return 1
    fi

    if ! timeout 30 docker exec "${server_container}" \
            rcon-cli hd test "${PROBE_DENIED_PLAYER}" >/dev/null 2>&1; then
        fail "HARNESS: /hd test ${PROBE_DENIED_PLAYER} could not be dispatched over rcon"
        return 1
    fi
    if ! wait_for_pattern "${server_log}" "${PROBE_DENY_PATTERN}" 60 \
            "the probe to refuse an unknown player"; then
        fail "the probe either never ran or decided something else — /hd test drives the real"
        fail "interceptor, so this is the login path disagreeing with the bot"
        return 1
    fi
    if ! wait_for_pattern "${stub_log}" "${STUB_PROBE_PATTERN}" 30 \
            "the stub to have been asked about that player"; then
        fail "the plugin refused them without asking anybody, which is a fallback rather than a"
        fail "decision — the mirror should have missed and the bot should have been consulted"
        return 1
    fi
    pass "login probe: an unknown player is refused, and the bot was asked"

    if ! timeout 30 docker exec "${server_container}" \
            rcon-cli hd test "${PROBE_ALLOWED_PLAYER}" >/dev/null 2>&1; then
        fail "HARNESS: /hd test ${PROBE_ALLOWED_PLAYER} could not be dispatched over rcon"
        return 1
    fi
    if ! wait_for_pattern "${server_log}" "${PROBE_ALLOW_PATTERN}" 60 \
            "the probe to admit a whitelisted player from the mirror"; then
        fail "the mirror holds ${PROBE_ALLOWED_PLAYER} — the pre-warm assertion above proved that —"
        fail "so either the name was not resolved to his UUID or the mirror was not consulted"
        return 1
    fi
    pass "login probe: a whitelisted player is admitted from the pre-warmed mirror"
    return 0
}

# The setup flow: an unclaimed server becomes a connected one, without a restart.
#
# Departure D56 in one row. Before phase 1e the modules captured whatever API client existed at
# registration — null, on a server nobody had set up — so a claim produced a live tunnel and an
# /offend that still refused, and only a restart fixed it. So the assertions are deliberately about
# what happens AFTER the command, in the same boot: the tunnel comes up, the handshake negotiates
# v3, and a module goes from off to on because a config push arrived and was applied.
# shellcheck disable=SC2317
assert_setup_row() {
    if ! wait_for_pattern "${server_log}" "${ENABLE_PATTERN}" "${BOOT_TIMEOUT}" \
            "the plugin's enable banner"; then
        return 1
    fi
    if ! wait_for_pattern "${server_log}" 'not set up yet' 60 \
            "the plugin to say it has no credentials"; then
        fail "this row must start UNCLAIMED, or the claim below proves nothing"
        return 1
    fi
    pass "plugin enabled with no bootstrap.yml, and said so"

    wait_for_pattern "${server_log}" "${READY_PATTERN}" "${BOOT_TIMEOUT}" \
        "the server to finish starting" || return 1
    if ! wait_for_rcon "${server_container}" "${RCON_TIMEOUT}"; then
        fail "rcon never answered, so /hd setup could not be driven"
        return 1
    fi

    # The endpoint is passed explicitly, which is also the documented answer for a whitelabel
    # instance: its setup codes live in its own database and are not claimable anywhere else.
    if ! timeout 60 docker exec "${server_container}" \
            rcon-cli hd setup "${SETUP_CODE}" http://stub-bot:8080 >/dev/null 2>&1; then
        fail "HARNESS: /hd setup could not be dispatched over rcon"
        return 1
    fi

    if ! wait_for_pattern "${stub_log}" "${STUB_CLAIMED_PATTERN}" 60 \
            "the stub to consume the setup code"; then
        fail "the claim never reached the bot — it is the one unsigned endpoint, so a signature"
        fail "problem here would be the client signing something it should not"
        return 1
    fi
    pass "setup code claimed"

    if ! wait_for_pattern "${stub_log}" "${STUB_SETUP_CONNECTED_PATTERN}" 90 \
            "the tunnel to come up on the claimed server id"; then
        fail "the credentials landed but nothing dialled. Before 1e this needed a restart, which is"
        fail "the entire point of the row — see departure D56."
        return 1
    fi
    if ! wait_for_pattern "${stub_log}" "${STUB_SETUP_IDENTIFIED_PATTERN}" 60 \
            "a v3 identify from the freshly claimed server"; then
        return 1
    fi
    pass "tunnel connected and negotiated v3 after the claim, with no restart"

    if ! wait_for_pattern "${server_log}" "${SETUP_MODULE_ENABLED_PATTERN}" 60 \
            "a module to go live on the pushed configuration"; then
        fail "the tunnel is up but nothing enabled, so the config push did not reach a client that"
        fail "was listening — which is what a module holding a dead reference looks like"
        return 1
    fi
    pass "modules went live on the pushed config, in the same boot"
    return 0
}

# The v2 migration: a v2 config next door becomes a working v3 install.
#
# Everything platform-specific about this row is the THREE strings below, and they are the point of
# running it on both platforms rather than one. Each platform names v2's data directory after a
# different declaration — Bukkit after `plugin.yml`'s `name:`, Velocity after the `@Plugin` id — and
# v2 wrote a different file format into each. Getting any of the three wrong looks, at boot, exactly
# like a fresh install (departure D70), which is why they are asserted against the host file system
# here and not merely inferred from a log line the plugin wrote about itself.
# shellcheck disable=SC2317
assert_migrate_row() {
    local host_plugins v2_directory v2_file
    if [ "${platform}" = "bukkit" ]; then
        host_plugins="${work}/data-plugins"
        v2_directory="HeimdallWhitelist"
        v2_file="config.yml"
    else
        host_plugins="${work}/plugins"
        v2_directory="heimdall-whitelist"
        v2_file="config.json"
    fi
    local v2_path="${host_plugins}/${v2_directory}/${v2_file}"

    if ! wait_for_pattern "${server_log}" "${ENABLE_PATTERN}" "${BOOT_TIMEOUT}" \
            "the plugin's enable banner"; then
        return 1
    fi
    if ! wait_for_pattern "${server_log}" "${MIGRATED_PATTERN}" 60 \
            "the v2 config to be found and migrated"; then
        fail "the v2 file is in the SIBLING plugins/${v2_directory}/ directory, which is where a"
        fail "real upgrade leaves it — a migration that only searches its own directory, or that has"
        fail "the wrong idea of what v2's directory is called on this platform, finds nothing on"
        fail "every install it exists for"
        return 1
    fi
    # ...and it must be OUR file it migrated, not something it found elsewhere. The pattern accepts
    # either file name so one row cannot pass on the other platform's line.
    #
    # The dot is escaped because this is an ERE, not a literal: bare `config.json` would also match
    # `config-json`, which is precisely the near-miss genre this assertion exists to refuse.
    #
    # The replacement is SINGLE-QUOTED, and it has to be. `${v2_file//./\\.}` — the obvious spelling
    # — silently produces `config.json` with no backslash at all under bash 5.2, because the
    # replacement text goes through quote removal; the escaping would look present and do nothing.
    if ! grep -Eq "Migrated the v2 config at .*${v2_directory}/${v2_file//./'\.'}" "${server_log}"; then
        fail "a migration happened, but not from plugins/${v2_directory}/${v2_file} — which is the"
        fail "only place a real v2 install on this platform would have left it"
        return 1
    fi
    pass "v2 config found and migrated, from plugins/${v2_directory}/${v2_file}"

    # The original is kept, never deleted. Checked on the host rather than in the log, because the
    # log line is the plugin's account of what it did and this is the file system's.
    if [ ! -f "${v2_path}.v2-backup" ]; then
        fail "no ${v2_file}.v2-backup beside the original — the migration must never delete an"
        fail "operator's configuration, whatever it made of it"
        return 1
    fi
    if [ -f "${v2_path}" ]; then
        fail "the v2 ${v2_file} is still in place, so the next boot would migrate it again"
        return 1
    fi
    pass "the v2 file was kept as a backup, and moved out of the way"

    # Legacy mode: v2 had no token id, so the migrated bootstrap signs with the guild key alone.
    # A connection at all is the assertion — the bot accepts the same HMAC either way, and if it
    # did not, this is where it would show.
    if ! wait_for_pattern "${stub_log}" "${STUB_MIGRATE_CONNECTED_PATTERN}" 90 \
            "the migrated server to connect on its legacy guild key"; then
        fail "the credentials came across but did not authenticate. v2's api.apiKey has no token id"
        fail "beside it, so this is the legacy-key signing path failing."
        return 1
    fi
    if ! wait_for_pattern "${stub_log}" "${STUB_LEGACY_IDENTIFY_PATTERN}" 60 \
            "the stub to record a legacy (no token id) identify"; then
        fail "the server connected, but the stub did not see the legacy signing path — a migrated"
        fail "server must send NO X-Token-Id, and this proves it did."
        return 1
    fi
    pass "connected in legacy mode, on credentials migrated from v2 (no token id)"

    if ! wait_for_pattern "${stub_log}" "${STUB_IMPORT_PATTERN}" 120 \
            "the translated v2 settings to reach the dashboard"; then
        fail "the settings translation was lost. It is deferred until the guild resolves, so a"
        fail "failure here is either the deferral never firing or the import being refused."
        return 1
    fi
    pass "the v2 settings were handed to the dashboard, once"
    return 0
}

# ── Entry point ──────────────────────────────────────────────────────────────────────────────

main() {
    local selection="${1:-all}"
    case "${selection}" in
        --list)
            for row in "${ROWS[@]}"; do printf '%s\n' "${row%%|*}"; done
            return 0
            ;;
        --selftest)
            selftest
            return $?
            ;;
    esac

    selftest || return 1

    local jar stub
    jar="$(find_jar)" || return 1
    stub="$(find_stub)" || return 1
    log "jar: ${jar}"
    log "stub: ${stub}"

    local failures=0 ran=0 row name image type version platform memory mode
    for row in "${ROWS[@]}"; do
        IFS='|' read -r name image type version platform memory mode <<<"${row}"
        if [ "${selection}" != "all" ] && [ "${selection}" != "${name}" ]; then
            continue
        fi
        ran=$((ran + 1))
        if run_row "${name}" "${image}" "${type}" "${version}" "${platform}" "${memory}" \
                "${mode}" "${jar}" "${stub}"; then
            pass "${name}"
        else
            fail "${name}"
            failures=$((failures + 1))
        fi
    done

    if [ "${ran}" -eq 0 ]; then
        fail "no row matched '${selection}' — try --list"
        return 1
    fi
    if [ "${failures}" -ne 0 ]; then
        fail "${failures}/${ran} connected row(s) failed"
        return 1
    fi
    pass "all ${ran} connected row(s) passed"
}

main "$@"
