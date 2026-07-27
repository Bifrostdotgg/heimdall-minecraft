#!/usr/bin/env bash
#
# Boot-smoke matrix: does the one jar load, and unload cleanly, on real servers across the whole
# supported range?
#
#   smoke/run.sh                 # every row, sequentially
#   smoke/run.sh all
#   smoke/run.sh paper-1.8.8     # one row (what CI runs, one row per runner)
#   smoke/run.sh --list
#   smoke/run.sh --selftest      # verify the log assertions themselves; needs no Docker
#
# Asserts load, real work at boot, and clean unload. From phase 1c the plugin actually builds its
# runtime on enable — role detection, a log4j console tap, listeners, a registered command — with no
# bot to talk to, so these rows are what proves the not-configured path stays green on every
# supported server. The tunnel itself still has nothing to connect to; smoke/docker-compose.yml has
# that topology wired and waiting.
#
# Environment:
#   SMOKE_JAR             path to the shaded jar (default: newest app/build/libs/heimdall-whitelist-*.jar)
#   SMOKE_BOOT_TIMEOUT    seconds to wait for the enable line (default 240)
#   SMOKE_STOP_TIMEOUT    seconds to wait for a graceful stop (default 120)
#   SMOKE_RCON_TIMEOUT    seconds to wait for RCON to answer before the console fallback (default 120)
#   SMOKE_KEEP            1 = leave containers and work dirs behind for inspection
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=smoke/lib.sh
source "${SCRIPT_DIR}/lib.sh"

BOOT_TIMEOUT="${SMOKE_BOOT_TIMEOUT:-240}"
STOP_TIMEOUT="${SMOKE_STOP_TIMEOUT:-120}"
RCON_TIMEOUT="${SMOKE_RCON_TIMEOUT:-120}"
WORK_ROOT="${SCRIPT_DIR}/.work"

# The plugin's own banners. Change the text in the plugin and here, in one place.
ENABLE_PATTERN='Heimdall v[0-9][^ ]* enabled'
DISABLE_PATTERN='Heimdall v[0-9][^ ]* shutting down'
# Velocity's own shutdown line, not ours. Still checked alongside our disable banner: it is what
# distinguishes a graceful stop from a SIGKILL, and our banner alone could in principle be logged by
# a shutdown handler on a proxy that was then killed mid-teardown.
VELOCITY_SHUTDOWN_PATTERN='Shutting down the proxy'
# Part of the enable banner. Attaching a root log4j appender is the single most version-sensitive
# thing the plugin does — the API that works on Minecraft 1.8.8's log4j 2.0-beta9 is not the one v2
# used — and asserting it here is what turns "it compiled" into "it attached on all six servers".
CONSOLE_TAP_PATTERN='console tap on'
# What /hd prints. Proves the command is actually registered: a commands: block in plugin.yml that
# the entry point never claims yields "Unknown command" with nothing in any log to say why, and the
# plugin loads perfectly either way.
#
# `.*` between the name and the version is not laziness. The plugin answers in §-coded text and
# rcon-cli rewrites those into ANSI escapes, so what actually comes back is
# `<esc>[33mHeimdall <esc>[37mv3.0.0-SNAPSHOT` — a literal 'Heimdall v3' never matches. The
# self-test carries that exact shape.
COMMAND_PATTERN='Heimdall.*v[0-9]+\.[0-9]'
# The server's own "I have finished starting" line. Both families print it.
#
# Waiting for this as well as for the plugin banner is not belt-and-braces. A plugin enables during
# startup, several seconds before the server opens its RCON port, so stopping as soon as the banner
# appears races the server's own boot — and losing that race does not fail cleanly: rcon-cli is
# refused, the fallback `stop` reaches a console not yet reading input, and the row dies a minute
# later on "Took too long, so killing server process".
READY_PATTERN='Done \([0-9.]+s\)'

# ── The matrix ───────────────────────────────────────────────────────────────────────────────
#
# Image tags are pinned to a dated itzg release rather than `:java8` / `:java21`, which are moving
# tags. A smoke matrix that silently changes what it tests is worse than no matrix, because a
# failure then has two candidate causes.
#
# Every Minecraft row is PAPER. Paper's own API serves 1.8.8 through 1.21 from one place, so a
# single TYPE covers the whole range; SPIGOT would mean either a BuildTools compile (minutes per
# row) or getbukkit.org, whose availability is not something CI should depend on. Paper is also
# what the overwhelming majority of the deployed fleet actually runs.
#
#   row | image | TYPE | VERSION | why this row exists
ROWS=(
    # The floor. Java 8 bytecode, the oldest API the plugin compiles against, and the row that
    # catches anything accidentally compiled or shaded above release 8.
    "paper-1.8.8|itzg/minecraft-server:2026.7.2-java8|PAPER|1.8.8|bukkit|1G"
    # Mid-legacy: still Java 8, but a different server generation and plugin loader.
    "paper-1.12.2|itzg/minecraft-server:2026.7.2-java8|PAPER|1.12.2|bukkit|1G"
    # Pre-modern, on a mid JRE — the transition where Paper stopped being a Spigot fork in spirit.
    "paper-1.16.5|itzg/minecraft-server:2026.7.2-java11|PAPER|1.16.5|bukkit|2G"
    # Current. Java 21, modern plugin loader, and the row that catches a jar that only works on old
    # servers (e.g. a missing api-version handled differently, or a relocation clash).
    "paper-1.21.8|itzg/minecraft-server:2026.7.2-java21|PAPER|1.21.8|bukkit|2G"
    # The proxy. A completely different entry point, loader and lifecycle from the Bukkit family —
    # the one-jar design lives or dies on these rows loading the Java 17 classes while the Bukkit
    # rows load the Java 8 ones out of the same file.
    #
    # Two rows, because Velocity moved its own floor and the two ends now disagree:
    #
    #   3.4.0 is the version :platform-velocity compiles against (pinned in libs.versions.toml) and
    #         the last one that runs on Java 17, which is the level that module targets. This row is
    #         the compile-target floor.
    #   3.5.1 is current, and is compiled at class file version 65 — it will not even start on a
    #         Java 17 JRE. This row is what customers actually run.
    #
    # Collapsing them would mean either not testing what we compile against or not testing what is
    # deployed. Both rows run in parallel on CI, so the pair costs nothing there.
    "velocity-3.4.0|itzg/mc-proxy:2026.7.1-java17|VELOCITY|3.4.0|velocity|1G"
    "velocity-3.5.1|itzg/mc-proxy:2026.7.1-java21|VELOCITY|3.5.1|velocity|1G"
)

# ── Self-test ────────────────────────────────────────────────────────────────────────────────
#
# Every assertion this harness makes is a grep, and a grep that matches nothing is
# indistinguishable from a clean server log. So the patterns are pointed at lines that MUST trip
# them and at real noise that must NOT, and that runs in CI without Docker.
#
# This is not hypothetical rigour. The first version of HEIMDALL_ERROR_PATTERN used `[^\n]*`, which
# in a POSIX bracket expression means "not a backslash and not the letter n" — so it silently failed
# to match `Could not enable Heimdall` and most of what it was written to catch. Nothing about a
# passing smoke run would ever have revealed that.

# Sample lines that MUST be flagged as our fault.
readonly SELFTEST_ERRORS=(
    "[13:48:25 ERROR]: Could not enable Heimdall v3.0.0-SNAPSHOT"
    "[13:48:25 SEVERE]: Error occurred while enabling Heimdall v3.0.0-SNAPSHOT (Is it up to date?)"
    "[13:48:25 ERROR]: Error occurred while disabling Heimdall v3.0.0-SNAPSHOT"
    "	at com.heimdall.platform.bukkit.HeimdallBukkitPlugin.onEnable(HeimdallBukkitPlugin.java:20)"
    "[13:48:25 ERROR]: Could not load 'plugins/heimdall-whitelist-3.0.0-SNAPSHOT.jar' in folder 'plugins'"
    "[13:48:25 WARN]: Heimdall threw a java.lang.NullPointerException"
)

# Real lines from the captured logs of a PASSING run. Flagging any of these makes the check useless.
readonly SELFTEST_CLEAN=(
    "[13:50:10 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role standalone, ticks via paper (tps+mspt), console tap on"
    "[13:50:13 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT shutting down"
    "[13:51:44 INFO] [heimdall]: Heimdall v3.0.0-SNAPSHOT enabled — role gatekeeper, text bridge ok, console tap on"
    "[13:50:09 INFO]: [Heimdall] server role: standalone (auto — no proxy forwarding is configured)"
    "[13:50:05 WARN]: [Heimdall] Legacy plugin detected: it has not specified an api-version"
    "[13:49:18 INFO]: Thread RCON Client /0:0:0:0:0:0:0:1 shutting down"
    "[13:50:01 ERROR]: Failed to fetch telemetry from api.mojang.com"
    "[13:50:01 ERROR]: Could not load 'plugins/SomeOtherPlugin.jar' in folder 'plugins'"
    # mc-server-runner's stop-path failures are ERROR-level and appear when the HARNESS loses the
    # shutdown race — they are infrastructure, not the plugin, and the error detector must not
    # convert one misdiagnosis into another. explain_runner_kill owns these lines instead.
    'ERROR mc-server-runner  Failed to stop using rcon-cli  {"error": "exit status 1"}'
    "ERROR mc-server-runner  Took too long, so killing server process"
)

expect_match() {
    local pattern="$1" line="$2" want="$3" label="$4"
    if printf '%s\n' "${line}" | grep -Eq "${pattern}"; then
        [ "${want}" = "yes" ] && return 0
        fail "${label}: matched but should not have"
    else
        [ "${want}" = "no" ] && return 0
        fail "${label}: did not match but should have"
    fi
    printf '       line: %s\n' "${line}" >&2
    return 1
}

selftest() {
    local failures=0 line

    for line in "${SELFTEST_ERRORS[@]}"; do
        expect_match "${HEIMDALL_ERROR_PATTERN}" "${line}" yes "error detector" || failures=$((failures + 1))
    done
    for line in "${SELFTEST_CLEAN[@]}"; do
        expect_match "${HEIMDALL_ERROR_PATTERN}" "${line}" no "error detector" || failures=$((failures + 1))
    done

    expect_match "${ENABLE_PATTERN}" \
        "[13:50:10 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role standalone, ticks via paper (tps+mspt), console tap on" \
        yes "enable banner (bukkit)" || failures=$((failures + 1))
    expect_match "${ENABLE_PATTERN}" \
        "[13:51:44 INFO] [heimdall]: Heimdall v3.0.0-SNAPSHOT enabled — role gatekeeper, text bridge ok, console tap on" \
        yes "enable banner (velocity)" || failures=$((failures + 1))
    # The near-miss that matters most here: a boot where the log4j appender could NOT attach still
    # logs a perfectly good enable banner, so ENABLE_PATTERN alone would pass on it.
    expect_match "${CONSOLE_TAP_PATTERN}" \
        "[13:50:10 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role standalone, ticks via nms-reflection, console tap on" \
        yes "console tap attached" || failures=$((failures + 1))
    expect_match "${CONSOLE_TAP_PATTERN}" \
        "[13:50:10 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT enabled — role standalone, ticks via nms-reflection, console tap off" \
        no "console tap attached vs a boot where it did not" || failures=$((failures + 1))
    # Exactly what rcon-cli hands back: the plugin answers in §-codes and rcon-cli rewrites them
    # into ANSI, so the version is not adjacent to the name. A pattern written against the plain
    # text passes this self-test and fails every real row — which is what happened.
    expect_match "${COMMAND_PATTERN}" $'\033[33mHeimdall \033[37mv3.0.0-SNAPSHOT' \
        yes "/hd output as rcon-cli renders it" || failures=$((failures + 1))
    expect_match "${COMMAND_PATTERN}" "Heimdall v3.0.0-SNAPSHOT" \
        yes "/hd output, uncoloured" || failures=$((failures + 1))
    expect_match "${COMMAND_PATTERN}" "Unknown command. Type \"/help\" for help." \
        no "/hd output vs an unregistered command" || failures=$((failures + 1))
    expect_match "${DISABLE_PATTERN}" \
        "[13:50:13 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT shutting down" \
        yes "disable banner" || failures=$((failures + 1))
    # A near-miss that really appears in the 1.16.5 log — the disable check must not accept it.
    expect_match "${DISABLE_PATTERN}" \
        "[13:49:18 INFO]: Thread RCON Client /0:0:0:0:0:0:0:1 shutting down" \
        no "disable banner vs the RCON thread's own shutdown line" || failures=$((failures + 1))
    expect_match "${VELOCITY_SHUTDOWN_PATTERN}" \
        "[13:51:47 INFO]: Shutting down the proxy..." \
        yes "velocity graceful shutdown" || failures=$((failures + 1))
    # Both families print this, in different shapes.
    expect_match "${READY_PATTERN}" '[13:48:25 INFO]: Done (4.541s)! For help, type "help" or "?"' \
        yes "ready line (bukkit)" || failures=$((failures + 1))
    expect_match "${READY_PATTERN}" "[13:51:44 INFO]: Done (0.56s)!" \
        yes "ready line (velocity)" || failures=$((failures + 1))
    expect_match "${READY_PATTERN}" "[13:48:20 INFO]: Preparing spawn area: 36%" \
        no "ready line vs mid-boot progress" || failures=$((failures + 1))

    # The two shapes mc-server-runner takes when the harness's stop failed and the server was
    # killed. These are exactly the lines from the red paper-1.8.8 CI runs, and matching them is
    # what turns "onDisable did not run" into the honest "the harness never stopped the server".
    expect_match "${RUNNER_KILL_PATTERN}" \
        'ERROR mc-server-runner  Failed to stop using rcon-cli  {"error": "exit status 1"}' \
        yes "runner kill (rcon-cli stop failed)" || failures=$((failures + 1))
    expect_match "${RUNNER_KILL_PATTERN}" \
        "ERROR mc-server-runner  Took too long, so killing server process" \
        yes "runner kill (grace period expired)" || failures=$((failures + 1))
    # A clean stop must not look like a kill, and neither must the plugin's own banner.
    expect_match "${RUNNER_KILL_PATTERN}" \
        "[13:50:13 INFO]: [Heimdall] Heimdall v3.0.0-SNAPSHOT shutting down" \
        no "runner kill vs the plugin's disable banner" || failures=$((failures + 1))
    expect_match "${RUNNER_KILL_PATTERN}" \
        "[13:49:18 INFO]: Thread RCON Client /0:0:0:0:0:0:0:1 shutting down" \
        no "runner kill vs the RCON thread's own shutdown line" || failures=$((failures + 1))

    # The rows themselves have to be well-formed, since a typo in a 6-field record would otherwise
    # surface as a confusing docker error many minutes into a run.
    local row name image type version platform memory
    for row in "${ROWS[@]}"; do
        IFS='|' read -r name image type version platform memory <<<"${row}"
        if [ -z "${name}" ] || [ -z "${image}" ] || [ -z "${type}" ] || [ -z "${version}" ] \
                || [ -z "${memory}" ]; then
            fail "malformed row: ${row}"
            failures=$((failures + 1))
        elif [ "${platform}" != "bukkit" ] && [ "${platform}" != "velocity" ]; then
            fail "row ${name} has unknown platform '${platform}'"
            failures=$((failures + 1))
        fi
    done

    if [ "${failures}" -ne 0 ]; then
        fail "${failures} self-test assertion(s) failed"
        return 1
    fi
    pass "self-test: patterns fire on ${#SELFTEST_ERRORS[@]} error lines, stay quiet on ${#SELFTEST_CLEAN[@]} clean ones, ${#ROWS[@]} rows well-formed"
}

row_names() {
    local row
    for row in "${ROWS[@]}"; do
        printf '%s\n' "${row%%|*}"
    done
}

find_jar() {
    if [ -n "${SMOKE_JAR:-}" ]; then
        [ -f "${SMOKE_JAR}" ] || { fail "SMOKE_JAR=${SMOKE_JAR} does not exist"; return 1; }
        printf '%s' "${SMOKE_JAR}"
        return 0
    fi
    # `original-` is shadow's unshaded intermediate; the self-updater skips it and so do we.
    local found
    found="$(find "${REPO_ROOT}/app/build/libs" "${REPO_ROOT}/dist" -maxdepth 1 \
        -name 'heimdall-whitelist-*.jar' ! -name 'original-*' 2>/dev/null | sort | tail -n 1)"
    if [ -z "${found}" ]; then
        fail "no shaded jar found — run ./gradlew build, or set SMOKE_JAR"
        return 1
    fi
    printf '%s' "${found}"
}

# ── One row ──────────────────────────────────────────────────────────────────────────────────

# run_row owns setup and teardown; row_body owns the assertions and may return early from
# anywhere. Splitting them is not style: a `trap … RETURN` set inside a function stays installed on
# the shell and then fires again when *every later* function returns, which is how the first version
# of this script reported "all rows passed" and immediately printed an unbound-variable error from
# its own cleanup handler.
run_row() {
    ROW_NAME="$1"
    ROW_IMAGE="$2"
    ROW_TYPE="$3"
    ROW_VERSION="$4"
    ROW_PLATFORM="$5"
    ROW_MEMORY="$6"
    ROW_JAR="$7"

    ROW_CONTAINER="heimdall-smoke-${ROW_NAME}"
    ROW_WORK="${WORK_ROOT}/${ROW_NAME}"
    ROW_LOG="${ROW_WORK}/server.log"
    ROW_TAIL_PID=""

    local rc=0
    row_body || rc=$?

    if [ -n "${ROW_TAIL_PID}" ]; then
        kill "${ROW_TAIL_PID}" 2>/dev/null || true
        wait "${ROW_TAIL_PID}" 2>/dev/null || true
    fi
    if [ "${SMOKE_KEEP:-0}" != "1" ]; then
        docker rm -f "${ROW_CONTAINER}" >/dev/null 2>&1 || true
    else
        log "SMOKE_KEEP=1 — container ${ROW_CONTAINER} and ${ROW_WORK} left in place"
    fi
    return "${rc}"
}

row_body() {
    local name="${ROW_NAME}" image="${ROW_IMAGE}" type="${ROW_TYPE}" version="${ROW_VERSION}"
    local platform="${ROW_PLATFORM}" memory="${ROW_MEMORY}" jar="${ROW_JAR}"
    local container="${ROW_CONTAINER}" work="${ROW_WORK}" log_file="${ROW_LOG}"

    log "── ${name}: ${type} ${version} on ${image}"

    docker rm -f "${container}" >/dev/null 2>&1 || true
    rm -rf "${work}"
    mkdir -p "${work}/plugins"
    cp "${jar}" "${work}/plugins/"

    # Deliberately no `--rm`: it removes the container the instant it exits, taking the logs with
    # it — which is exactly when a shutdown assertion needs them. Cleanup is explicit instead.
    # The two families differ in more than a flag: the proxy image takes VELOCITY_VERSION rather
    # than VERSION, keeps its data under /server rather than /data, and has no EULA or RCON at all.
    local -a docker_args
    if [ "${platform}" = "bukkit" ]; then
        docker_args=(
            run -d --name "${container}"
            -e EULA=TRUE
            -e "TYPE=${type}"
            -e "VERSION=${version}"
            -e "MEMORY=${memory}"
            -e ONLINE_MODE=FALSE
            -e USE_AIKAR_FLAGS=false
            # Nothing here needs a world worth generating, and spawn-area generation is most of a
            # cold legacy boot.
            -e VIEW_DISTANCE=4
            -e SPAWN_PROTECTION=0
            -e ENABLE_RCON=true
            -e RCON_PASSWORD=smoke
            # Mounted READ-ONLY at the image's staging path, not straight onto /data/plugins.
            #
            # Modern Paper writes into its own plugins directory — it creates
            # plugins/.paper-remapped before it loads anything. A host bind mount there is owned by
            # the host user, the server runs as uid 1000, and the boot dies with
            # AccessDeniedException before the plugin is ever considered. That is invisible under
            # rootless Podman, which maps the host user into the container, and it is exactly how
            # this row passed locally and failed on CI.
            #
            # itzg copies /plugins into a container-owned /data/plugins on start (COPY_PLUGINS_SRC
            # defaults to /plugins), so using the designed path fixes ownership for every row and
            # keeps the host copy immutable as a bonus.
            -v "$(host_path "${work}/plugins"):/plugins:ro"
        )
    else
        # mc-proxy has no staging-copy step, but it also runs as root, so a writable bind mount
        # straight onto its plugins directory has none of the ownership problem the Bukkit rows
        # have. Velocity does write in there (bStats), hence rw rather than ro.
        docker_args=(
            run -d --name "${container}"
            -e "TYPE=${type}"
            -e "VELOCITY_VERSION=${version}"
            -e "MEMORY=${memory}"
            -v "$(host_path "${work}/plugins"):/server/plugins:rw"
        )
    fi

    docker "${docker_args[@]}" "${image}" >/dev/null

    # Stream to a file from the start. `docker logs` after the fact would do for a container we
    # keep, but streaming means the log survives even if the container is lost, and it lets the
    # wait loop poll a plain file.
    docker logs -f "${container}" >"${log_file}" 2>&1 &
    ROW_TAIL_PID=$!

    if ! wait_for_pattern "${log_file}" "${ENABLE_PATTERN}" "${BOOT_TIMEOUT}" \
            "the plugin's enable banner"; then
        dump_log "${log_file}"
        return 1
    fi
    pass "plugin enabled: $(grep -Eo "${ENABLE_PATTERN}.*" "${log_file}" | head -n 1)"

    # The tap is attached eagerly at enable precisely so this row exercises it. Asserting it is what
    # makes the log4j 2.0-beta9 compatibility on the 1.8.8 row a tested fact rather than a comment.
    if ! grep -Eq "${CONSOLE_TAP_PATTERN}" "${log_file}"; then
        fail "the console tap did not attach — the log4j appender is not usable on this server"
        dump_log "${log_file}"
        return 1
    fi
    pass "console tap attached"

    # The plugin enables DURING startup, before the server opens its RCON port. Stopping now would
    # race the rest of the boot, and losing that race does not fail cleanly — see READY_PATTERN.
    if ! wait_for_pattern "${log_file}" "${READY_PATTERN}" "${BOOT_TIMEOUT}" \
            "the server's own ready line"; then
        dump_log "${log_file}"
        return 1
    fi
    pass "server finished starting"

    if ! assert_no_heimdall_errors "${log_file}" "boot"; then
        dump_log "${log_file}"
        return 1
    fi

    # ── Graceful stop ────────────────────────────────────────────────────────────────────────
    if [ "${platform}" = "bukkit" ]; then
        # `stop` over RCON is the server's own shutdown path, so onDisable actually runs. A
        # `docker stop` would work too, but only because itzg traps the signal — asserting the
        # disable line through a code path the plugin will never see in production proves less.
        #
        # Readiness and the action are separate commands, and that separation is the point.
        # Retrying `stop` conflates two outcomes it cannot tell apart: "the RCON listener is not up
        # yet" and "the stop was accepted, and the connection then dropped because the server is
        # shutting down". The second is the NORMAL case — the server closes the socket while it
        # answers, so rcon-cli's exit status after a successful `stop` is not reliable — and a
        # retry loop around `stop` therefore reports five failures, falls through to SIGTERM
        # against a server already on its way down, and fails the row. That is what the three red
        # runs on this branch were.
        #
        # So: poll `list`, which is idempotent and answers only once RCON is genuinely serving.
        # Then issue `stop` exactly once and let wait_for_exit be the assertion.
        #
        # The budget is generous (SMOKE_RCON_TIMEOUT, default 120s) because RCON opens some time
        # AFTER the Done line, and on the slowest rows — paper-1.8.8 on a loaded CI runner — that
        # gap has been seen to blow past 30s. Undershooting does not fail cleanly: the old SIGTERM
        # fallback made mc-server-runner retry rcon itself, fail the same way, and SIGKILL the
        # server, which then surfaced as a missing disable banner five red runs in a row.
        log "waiting for rcon to answer (budget ${RCON_TIMEOUT}s)"
        local rcon_deadline=$(( $(date +%s) + RCON_TIMEOUT )) ready=0
        while [ "$(date +%s)" -lt "${rcon_deadline}" ]; do
            if docker exec "${container}" rcon-cli list >/dev/null 2>&1; then
                ready=1
                break
            fi
            sleep 3
        done

        if [ "${ready}" -eq 1 ]; then
            # Prove the command is registered before stopping. Console is op-equivalent, so /hd
            # answers without a permission dance. Failure here is a real finding: plugin.yml and the
            # entry point can disagree silently, and the plugin loads either way.
            #
            # Retried, unlike `stop`. Every rcon-cli invocation opens its own connection, and
            # legacy RCON drops one now and then — the 1.8.8 row answered `list` and then had `hd`
            # reset by peer on the very next call. Retrying is safe here precisely because /hd is
            # read-only and idempotent, which is exactly what `stop` is not (see below).
            local hd_output="" hd_attempt=0 hd_ok=0
            while [ "${hd_attempt}" -lt 5 ]; do
                hd_output="$(docker exec "${container}" rcon-cli hd 2>&1 || true)"
                if printf '%s\n' "${hd_output}" | grep -Eq "${COMMAND_PATTERN}"; then
                    hd_ok=1
                    break
                fi
                hd_attempt=$(( hd_attempt + 1 ))
                sleep 3
            done
            if [ "${hd_ok}" -eq 1 ]; then
                pass "/hd is registered: $(printf '%s' "${hd_output}" | head -n 1)"
            else
                fail "/hd did not answer — the command is declared in plugin.yml but not claimed"
                printf '       rcon said: %s\n' "${hd_output}" >&2
                dump_log "${log_file}"
                return 1
            fi

            log "stopping via rcon-cli"
            # Exit status deliberately ignored: see above. wait_for_exit decides whether it worked.
            docker exec "${container}" rcon-cli stop >/dev/null 2>&1 || true
        else
            # The console pipe is the next-best graceful path. The itzg images run the server
            # behind a named pipe wired to its stdin, and mc-send-to-console types into it — so
            # `stop` arrives exactly as if an operator had typed it, and onDisable still runs.
            # Going straight to SIGTERM instead would make mc-server-runner retry rcon (which
            # just spent the whole budget failing), give up, and SIGKILL the server — destroying
            # exactly the shutdown this row exists to observe.
            warn "rcon never answered within ${RCON_TIMEOUT}s; sending 'stop' on the server console"
            if ! docker exec "${container}" mc-send-to-console stop >/dev/null 2>&1; then
                warn "console send failed too, falling back to SIGTERM"
                docker stop -t "${STOP_TIMEOUT}" "${container}" >/dev/null
            fi
        fi
    else
        # Velocity has no RCON. SIGTERM runs its shutdown hook.
        log "stopping via SIGTERM"
        docker stop -t "${STOP_TIMEOUT}" "${container}" >/dev/null
    fi

    if ! wait_for_exit "${container}" "${STOP_TIMEOUT}"; then
        dump_log "${log_file}"
        return 1
    fi
    # Wait for the log follower to finish draining, rather than sleeping a fixed two seconds and
    # hoping. Every assertion below greps for lines the server writes on its way out, and reading
    # the file while the stream is still catching up produces a "missing disable banner" that is
    # indistinguishable from onDisable genuinely never running.
    wait_for_log_flush "${ROW_TAIL_PID}" 30
    ROW_TAIL_PID=""

    # Velocity only, and still worth checking now that the plugin has a banner of its own: the
    # proxy's line is what distinguishes a graceful stop from a proxy killed part-way through
    # teardown. "No errors" would be weaker still — a SIGKILL logs nothing at all.
    if [ "${platform}" = "velocity" ]; then
        if ! wait_for_pattern "${log_file}" "${VELOCITY_SHUTDOWN_PATTERN}" 30 \
                "the proxy's own shutdown line"; then
            # A runner-kill signature means the harness's stop never reached the proxy, which is
            # its failure and not the plugin's.
            explain_runner_kill "${log_file}" \
                || fail "the proxy never logged its shutdown — it was killed rather than stopped"
            dump_log "${log_file}"
            return 1
        fi
        pass "proxy shut down gracefully"
    fi

    # Both families now. The Velocity rows were boot-only until phase 1c: the scaffold registered no
    # ProxyShutdownEvent listener, so it had no banner, and those rows proved the jar loaded and the
    # proxy stopped but nothing at all about Heimdall unloading. It has one now.
    #
    # Polled with a short budget rather than a single grep: on a slow daemon the log follower can
    # exit before the last buffered lines have landed in the file.
    if ! wait_for_pattern "${log_file}" "${DISABLE_PATTERN}" 30 "the plugin's disable banner"; then
        # Two very different stories end here, and the log can tell them apart. If mc-server-runner
        # logged that it killed the server, the shutdown handler never got the chance to run and
        # blaming the plugin would be wrong — that is the harness failing to stop the server, and
        # explain_runner_kill says so. Only a log with no kill signature means the shutdown
        # genuinely ran and the plugin stayed silent.
        explain_runner_kill "${log_file}" \
            || fail "no disable banner — the shutdown handler did not run, or threw before logging"
        dump_log "${log_file}"
        return 1
    fi
    pass "plugin disabled cleanly"

    if ! assert_no_heimdall_errors "${log_file}" "shutdown"; then
        dump_log "${log_file}"
        return 1
    fi

    # Informational only. It is not an assertion because the two families legitimately differ:
    # an RCON `stop` exits 0, while a SIGTERM'd Velocity exits 143, and some daemons report
    # nothing at all once the container has been reaped.
    local exit_code
    exit_code="$(docker inspect -f '{{.State.ExitCode}}' "${container}" 2>/dev/null || true)"
    log "${name}: container exit code ${exit_code:-unavailable}"

    return 0
}

# ── Entry point ──────────────────────────────────────────────────────────────────────────────

main() {
    local target="${1:-all}"

    if [ "${target}" = "--list" ] || [ "${target}" = "-l" ]; then
        row_names
        return 0
    fi
    # No Docker needed, so CI runs this alongside the matrix generation.
    if [ "${target}" = "--selftest" ]; then
        selftest
        return $?
    fi
    if [ "${target}" = "--help" ] || [ "${target}" = "-h" ]; then
        sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        return 0
    fi

    command -v docker >/dev/null 2>&1 || { fail "docker is not on PATH"; return 1; }
    docker version >/dev/null 2>&1 || { fail "the Docker daemon is not reachable"; return 1; }

    local jar
    jar="$(find_jar)" || return 1
    log "using jar: ${jar}"

    local -a selected=()
    local row
    for row in "${ROWS[@]}"; do
        if [ "${target}" = "all" ] || [ "${row%%|*}" = "${target}" ]; then
            selected+=("${row}")
        fi
    done
    if [ "${#selected[@]}" -eq 0 ]; then
        fail "unknown row '${target}'. Known rows:"
        row_names >&2
        return 1
    fi

    local failures=0
    local -a failed=()
    for row in "${selected[@]}"; do
        IFS='|' read -r name image type version platform memory <<<"${row}"
        if run_row "${name}" "${image}" "${type}" "${version}" "${platform}" "${memory}" "${jar}"; then
            pass "${name}"
        else
            fail "${name}"
            failures=$(( failures + 1 ))
            failed+=("${name}")
        fi
    done

    printf '\n'
    if [ "${failures}" -eq 0 ]; then
        pass "all ${#selected[@]} row(s) passed"
        return 0
    fi
    fail "${failures} of ${#selected[@]} row(s) failed: ${failed[*]}"
    return 1
}

main "$@"
