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
# socket, and are not re-proven here.
#
#   PHASE 1e ADDS: `/hd test <player>` makes a real connection-attempt from the server console, at
#   which point this script can assert an allow and a deny end to end through rcon — and, with the
#   headless-client work D43's residual risk 2 needs, an actual join.
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
# The whitelist sync really crossed the wire, as a signed request the stub accepted. The plugin's
# own "Mirror reconcile" line proves it processed a response; only the stub can prove it asked.
STUB_SYNC_PATTERN="GET /api/guilds/${STUB_GUILD}/minecraft/whitelist/sync"
# The tunnel was still live at shutdown and closed deliberately. Without this the "disabled cleanly
# with a live tunnel" claim rests on the disable banner alone, which says nothing about the tunnel —
# a socket that had already dropped ten minutes earlier would look identical.
STUB_CLEAN_CLOSE_PATTERN="ws disconnected: guild=${STUB_GUILD} server=${STUB_SERVER_ID}.*Plugin shutting down"

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
#   row | image | TYPE | VERSION | platform | memory
ROWS=(
    "paper-1.21.8|itzg/minecraft-server:2026.7.2-java21|PAPER|1.21.8|bukkit|2G"
    "velocity-3.5.1|itzg/mc-proxy:2026.7.1-java21|VELOCITY|3.5.1|velocity|1G"
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

    local row name image type version platform memory
    for row in "${ROWS[@]}"; do
        IFS='|' read -r name image type version platform memory <<<"${row}"
        if [ -z "${name}" ] || [ -z "${image}" ] || [ -z "${type}" ] || [ -z "${version}" ] \
                || [ -z "${memory}" ]; then
            fail "malformed row: ${row}"
            failures=$((failures + 1))
        elif [ "${platform}" != "bukkit" ] && [ "${platform}" != "velocity" ]; then
            # Every platform branch in this file is an if/else on these two strings, so a third
            # value does not fail — it silently takes the velocity path, mounts the wrong
            # directory, and reports a plugin that would not load.
            fail "row ${name} has unknown platform '${platform}'"
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
    local name="$1" image="$2" type="$3" version="$4" platform="$5" memory="$6" jar="$7" stub="$8"

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
    log "── ${name}: ${type} ${version} against stub-bot"

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
    if ! docker run -d --name "${stub_container}" --network "${network}" \
            --network-alias stub-bot \
            -v "$(host_path "${stub}"):/opt/stub-bot:ro" \
            -w /opt/stub-bot \
            -e STUB_BOT_BIND=0.0.0.0 \
            -e STUB_BOT_PORT=8080 \
            -e "STUB_BOT_GUILD_ID=${STUB_GUILD}" \
            -e "STUB_BOT_API_KEY=${STUB_SECRET}" \
            -e STUB_BOT_VERBOSE=true \
            -e 'STUB_BOT_MODULES={"whitelist":{"enabled":true},"rolesync":{"enabled":true},"offenses":{"enabled":true},"console":{"enabled":true}}' \
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
        if ! write_bootstrap "${work}/data-plugins/Heimdall/bootstrap.yml"; then
            kill "${stub_tail}" 2>/dev/null || true
            return 1
        fi
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
        # The proxy image runs as root and writes into its plugins directory, so one writable mount
        # carries both the jar and the plugin's own data directory. Velocity's data directory is
        # named after the plugin id, which is lower-case `heimdall`.
        if ! write_bootstrap "${work}/plugins/heimdall/bootstrap.yml"; then
            kill "${stub_tail}" 2>/dev/null || true
            return 1
        fi
        docker_args=(
            run -d --name "${server_container}" --network "${network}"
            -e "TYPE=${type}" -e "VELOCITY_VERSION=${version}" -e "MEMORY=${memory}"
            -v "$(host_path "${work}/plugins"):/server/plugins:rw"
        )
    fi

    # The premise, asserted rather than assumed. If a previous run's cache survived, the two
    # headline claims below are silently vacuous — so this is checked here, where it is a harness
    # failure, instead of being discovered as a mysteriously fast "guild resolved".
    if grep -q "guildIdCache" "${work}"/*/Heimdall/bootstrap.yml \
            "${work}"/*/heimdall/bootstrap.yml 2>/dev/null; then
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
    assert_row || rc=$?

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
        local rcon_deadline=$(( $(date +%s) + RCON_TIMEOUT )) rcon_ready=0
        while [ "$(date +%s)" -lt "${rcon_deadline}" ]; do
            if timeout 20 docker exec "${server_container}" rcon-cli list >/dev/null 2>&1; then
                rcon_ready=1
                break
            fi
            sleep 3
        done
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

    # Waited for last rather than first: a server that is still generating its spawn area has not
    # finished booting, and stopping it there loses the shutdown race.
    if [ "${platform}" = "bukkit" ]; then
        wait_for_pattern "${server_log}" "${READY_PATTERN}" "${BOOT_TIMEOUT}" "the server to finish starting" \
            || return 1
    fi
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

    local failures=0 ran=0 row name image type version platform memory
    for row in "${ROWS[@]}"; do
        IFS='|' read -r name image type version platform memory <<<"${row}"
        if [ "${selection}" != "all" ] && [ "${selection}" != "${name}" ]; then
            continue
        fi
        ran=$((ran + 1))
        if run_row "${name}" "${image}" "${type}" "${version}" "${platform}" "${memory}" \
                "${jar}" "${stub}"; then
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
