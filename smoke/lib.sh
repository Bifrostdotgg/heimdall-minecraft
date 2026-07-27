#!/usr/bin/env bash
# Shared helpers for the boot-smoke harness. Sourced by run.sh; not executable on its own.

# Git Bash rewrites anything that looks like a Unix path in an argument, which turns
# `-v C:\x:/data/plugins` into `-v C:\x:C:/Program Files/Git/data/plugins`. Every path we
# hand to Docker is already in the form that platform wants, so the rewriting is pure damage.
if command -v cygpath >/dev/null 2>&1; then
    export MSYS_NO_PATHCONV=1
fi

# Converts a path for the Docker daemon: a Windows path under Git Bash, unchanged elsewhere.
host_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

log()  { printf '\033[36m[smoke]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[smoke]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31m[smoke] FAIL:\033[0m %s\n' "$*" >&2; }
pass() { printf '\033[32m[smoke] ok:\033[0m %s\n' "$*"; }

# Waits for a regex to appear in a growing file. Returns 1 on timeout.
#
# Polls rather than `tail -f | grep -q`: a pipeline blocks forever if the log goes quiet after
# the match, and the timeout has to be enforced by us anyway.
wait_for_pattern() {
    local file="$1" pattern="$2" timeout="$3" label="$4"
    local deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ -f "$file" ] && grep -Eq "$pattern" "$file"; then
            return 0
        fi
        sleep 2
    done
    fail "timed out after ${timeout}s waiting for ${label}"
    return 1
}

# Waits for a container to stop running. Returns 1 on timeout.
wait_for_exit() {
    local container="$1" timeout="$2"
    local deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != "true" ]; then
            return 0
        fi
        sleep 2
    done
    fail "container ${container} did not exit within ${timeout}s"
    return 1
}

# Lines in the server log that are our fault.
#
# Deliberately narrow. A smoke run over five server generations picks up plenty of noise that has
# nothing to do with us — deprecation warnings, missing optional dependencies, Mojang telemetry
# that cannot reach the network — and a check that fails on all of it gets muted within a week.
# What is left is: a log line naming Heimdall at ERROR or worse, a stack frame in our package, and
# the two messages Bukkit prints when a plugin fails to load or blows up in onEnable/onDisable.
HEIMDALL_ERROR_PATTERN='(ERROR|SEVERE|FATAL)[^\n]*[Hh]eimdall|[Hh]eimdall[^\n]*(Exception|Error occurred)|at com\.heimdall\.|Could not load '\''?plugins[/\\][^'\'']*[Hh]eimdall|Error occurred while (enabling|disabling) Heimdall'

# Fails if the log contains an error attributable to the plugin.
assert_no_heimdall_errors() {
    local file="$1" phase="$2"
    local hits
    hits="$(grep -En "$HEIMDALL_ERROR_PATTERN" "$file" || true)"
    if [ -n "$hits" ]; then
        fail "plugin errors during ${phase}:"
        printf '%s\n' "$hits" >&2
        return 1
    fi
    pass "no plugin errors during ${phase}"
}

# Prints the tail of a log with a header, for a failure report.
dump_log() {
    local file="$1" lines="${2:-120}"
    if [ ! -f "$file" ]; then
        warn "no log captured at ${file}"
        return
    fi
    printf '\n\033[33m──── last %s lines of %s ────\033[0m\n' "$lines" "$file" >&2
    tail -n "$lines" "$file" >&2
    printf '\033[33m────────────────────────────────\033[0m\n\n' >&2
}
