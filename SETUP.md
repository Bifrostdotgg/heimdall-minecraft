# Setup

**There is no config file to edit, and no HMAC secret to copy.** If you are looking for
`plugins/HeimdallWhitelist/config.yml`, that was v2. v3 keeps six connection keys in
`plugins/Heimdall/bootstrap.yml` (`plugins/heimdall/` on Velocity, which names a plugin's folder
after its id rather than its name) and the plugin writes that file
itself; every other setting lives on the Heimdall dashboard and is pushed to the plugin over its
tunnel.

The whole of setup is therefore:

1. Drop the JAR in `plugins/` and start the server.
2. Mint a setup code on the dashboard's **Minecraft** page for that guild.
3. Run `/hd setup <code>` (`/hdp setup <code>` on a proxy, Velocity or BungeeCord alike). It
   connects immediately — no restart, no file editing.

See the [README](README.md) for the full walkthrough:

- [Installation](README.md#installation) — Paper/Spigot, Velocity and BungeeCord, fresh installs
- [Upgrading from v2](README.md#upgrading-from-v2) — drop the new JAR in, the old config is found
  next door and migrated on the first boot
- [Configuration](README.md#configuration) — what `bootstrap.yml` holds and what the dashboard owns
- [Admin Commands](README.md#admin-commands) — `/hd status`, `/hd test`, `/hd reload` and the rest

Upgrading from v2 needs no manual step at all. Leave the old config where it is; the plugin finds
`plugins/HeimdallWhitelist/config.yml` (Bukkit) or `plugins/heimdall-whitelist/config.json`
(Velocity), writes a `bootstrap.yml` from its credentials, keeps the original as a `.v2-backup`, and
hands the rest of its settings to the dashboard. **Do remove the v2 JAR** — the two declare different
plugin names and ids, so both would load, and only one of them can own the config.

There is no v2 to upgrade from on **BungeeCord**: v2 shipped Bukkit and Velocity builds only, so a
Bungee proxy is always a fresh install.
