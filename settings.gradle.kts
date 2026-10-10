rootProject.name = "heimdall-plugin"

include(
    "core",
    "api",
    // The hot-swap shell. :shell-api is the contract both halves compile against; the three
    // platform shells and :shell-common are what the platform loads and never swaps. See
    // docs/v2-departures.md D87 and app/build.gradle.kts for how the two halves are packaged.
    "shell-api",
    "shell-common",
    "shell-bukkit",
    "shell-velocity",
    "shell-bungee",
    "platform-common",
    "platform-bukkit",
    "platform-bukkit-paper",
    "platform-velocity",
    "platform-bungee",
    "module-whitelist",
    "module-rolesync",
    "module-offenses",
    "module-punishments",
    "module-console",
    "module-bridge",
    "conformance",
    "app",
    // Test fixture, not a shipped module: a small HTTP+WS server that speaks the
    // real bot's wire contract, used by the Docker boot-smoke matrix and (from
    // phase 1) by the integration tests. `:app` must never depend on it.
    "stub-bot",
)
