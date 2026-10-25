# Setup

**There is no config file to edit, and no secret to copy.** If you are looking for
`plugins/HeimdallWhitelist/config.yml`, that was v2. v3 keeps its connection in
`plugins/Heimdall/bootstrap.yml` (`plugins/heimdall/` on Velocity), which the plugin writes itself.
Every other setting lives on the Heimdall dashboard and is pushed to the plugin live.

The whole of setup:

1. Put the jar in `plugins/` and start the server.
2. Add the server on the dashboard under **Minecraft > Networks** and copy its setup code.
3. Run `/hd setup <code>` (`/hdp setup <code>` on Velocity or BungeeCord). It connects straight
   away, with no restart.

The [README](README.md) has the rest:

- [Installation](README.md#installation): every platform, and networks
- [Upgrading from v2](README.md#upgrading-from-v2): replace the jar, and the old config is migrated
  on first boot
- [Configuration](README.md#configuration): what `bootstrap.yml` holds
- [Admin commands](README.md#admin): `/hd status`, `/hd test`, `/hd reload` and the rest
