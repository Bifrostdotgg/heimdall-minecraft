rootProject.name = "heimdall-plugin"

include(
    "core",
    "api",
    "platform-common",
    "platform-bukkit",
    "platform-bukkit-paper",
    "platform-velocity",
    "platform-bungee",
    "module-whitelist",
    "module-rolesync",
    "module-offenses",
    "module-console",
    "module-bridge",
    "conformance",
    "app",
    // Test fixture, not a shipped module: a small HTTP+WS server that speaks the
    // real bot's wire contract, used by the Docker boot-smoke matrix and (from
    // phase 1) by the integration tests. `:app` must never depend on it.
    "stub-bot",
)
