# Heimdall Whitelist Plugin

A Minecraft plugin that integrates with the Heimdall Discord bot to provide dynamic whitelist management. This plugin replaces traditional static whitelists with API-based dynamic checking, allowing for real-time whitelist decisions and Discord-based account linking.

## Supported Platforms

- **Paper/Spigot 1.21.1+** - Backend server plugin
- **Velocity 3.4.0+** - Proxy plugin for network-wide whitelist checking

The plugin can be used on either the backend servers, the proxy, or both depending on your network setup.

## Features

- **Dynamic Whitelist Checking**: Instead of relying on static whitelist files, the plugin checks with the Heimdall bot API on every connection attempt
- **Discord Account Linking**: Players link their Minecraft accounts to Discord through an authentication code system
- **Real-time Decisions**: Staff can approve/deny players through the Discord dashboard without server restarts
- **Fallback System**: If the API is unavailable, configurable fallback modes (allow, deny, or whitelist-only)
- **Performance Optimized**: Response caching and async processing to minimize server impact
- **Configurable Messages**: Customize all player-facing messages through the config file
- **LuckPerms Integration**: Sync Discord roles to LuckPerms groups (Paper only)
- **Multi-Platform Support**: Single JAR works on both Paper and Velocity

## Requirements

- **Paper/Spigot**: Java 17+, Paper 1.21.1+ or compatible fork
- **Velocity**: Java 17+, Velocity 3.4.0+
- Heimdall Discord Bot with API enabled
- Network connectivity between your Minecraft server and bot API

## Installation

Download the latest `heimdall-whitelist-X.X.X.jar` from the
[**Releases page**](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest).
The same JAR works on both Paper and Velocity.

### Paper/Spigot Installation

1. Download the latest `heimdall-whitelist-X.X.X.jar` from the [Releases page](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest)
2. Place the JAR file in your server's `plugins/` folder
3. Start your server to generate the default configuration
4. Edit `plugins/HeimdallWhitelist/config.yml`
5. Restart your server or use `/hwl reload`

### Velocity Installation

1. Download the latest `heimdall-whitelist-X.X.X.jar` from the [Releases page](https://github.com/Bifrostdotgg/heimdall-minecraft/releases/latest)
2. Place the JAR file in your Velocity proxy's `plugins/` folder
3. Start your proxy to generate the default configuration
4. Edit `plugins/heimdall-whitelist/config.json`
5. Restart your proxy or use `/hwl reload`

### Keeping the plugin updated

The plugin checks your Heimdall bot for the latest published version on startup
and every few hours. When a newer version is available:

- A warning is logged to the server console.
- Admins (`heimdall.admin`) are notified as they join (if `updates.notifyAdmins` is on).
- Run `/hwl version` to see the installed vs. latest version on demand.
- Run `/hwl update` to download the latest JAR:
  - **Paper**: it is placed in `plugins/update/` and applied automatically on the
    next server restart.
  - **Velocity**: it is downloaded into the plugin's data folder; move it into the
    proxy's `plugins/` directory (replacing the old JAR) and restart.

Update behavior is configurable under the `updates:` section of the config:

```yaml
updates:
  checkEnabled: true       # check for new versions on startup + interval
  notifyAdmins: true       # message admins on join when an update is available
  checkIntervalHours: 12   # how often to re-check (minimum 1)
```

## Configuration

### Paper Configuration (config.yml)

```yaml
# Global Plugin Control (IMPORTANT!)
# Plugin starts DISABLED by default for security
# When disabled, ALL players can join without any whitelist checks
enabled: false

# Bot API Configuration
api:
  # The URL of your Heimdall bot API endpoint
  baseUrl: "http://localhost:3001"
  hmacSecret: "your-hmac-secret-here" # Must match the bot's INTERNAL_API_KEY
  timeout: 5000
  retries: 3
  retryDelay: 1000

# Server identification
server:
  serverId: "auto-generated-uuid"
  displayName: "My Minecraft Server"
  publicIp: "localhost"

# Customize messages shown to players
messages:
  notWhitelisted: "§cYou are not whitelisted on this server!"
  authCodeRequired: "§eYour auth code: §a{code}"
  whitelistSuccess: "§aYou have been whitelisted!"
  apiError: "§cWhitelist system unavailable. Try again later."

# Cache settings
cache:
  enabled: true
  cacheWindow: 60 # Minutes to cache whitelist decisions
  extendOnJoin: 120 # Extend cache when player joins
  extendOnLeave: 180 # Extend cache when player leaves
  cleanupInterval: 30 # Minutes between cache cleanup

# Advanced settings
advanced:
  apiFallbackMode: "deny" # Options: allow, deny, whitelist-only
```

### Velocity Configuration (config.json)

The Velocity version uses JSON configuration with the same options:

```json
{
  "enabled": false,
  "api": {
    "baseUrl": "http://localhost:3001",
    "hmacSecret": "your-hmac-secret-here",
    "timeout": 5000,
    "retries": 3,
    "retryDelay": 1000
  },
  "server": {
    "serverId": "",
    "displayName": "My Minecraft Network",
    "publicIp": "localhost"
  },
  "cache": {
    "enabled": true,
    "cacheWindow": 60,
    "extendOnJoin": 120,
    "extendOnLeave": 180,
    "cleanupInterval": 30
  },
  "advanced": {
    "apiFallbackMode": "deny"
  }
}
```

### Important Configuration Notes

- **enabled**: Controls whether whitelist protection is active. Starts `false` for security
- **api.baseUrl**: Must point to your Heimdall bot's API endpoint (e.g., `http://your-bot-server.com:3001`)
- **api.hmacSecret**: Shared HMAC secret — must match the bot's `INTERNAL_API_KEY` environment variable
- **server.serverId**: Auto-generated unique identifier - don't change this after setup
- **advanced.apiFallbackMode**: What to do when API is unavailable:
  - `deny` - Deny all connections (fail-closed, most secure)
  - `allow` - Allow all connections (fail-open)
  - `whitelist-only` - Only allow players with positive cache entries

⚠️ **Security Notice**: The plugin starts **DISABLED** by default to prevent unauthorized access when using default configuration. Enable only after properly configuring your API settings.

## Commands

### Player Commands (Paper only)

- `/linkdiscord` - Request a code to link your Minecraft account to Discord

### Admin Commands

- `/hwl` - Show available commands
- `/hwl reload` - Reload configuration from file
- `/hwl status` - Display plugin status and connectivity
- `/hwl enable` - Enable whitelist protection (requires proper API config)
- `/hwl disable` - Disable whitelist protection (allows all players)
- `/hwl test <player>` - Test whitelist check for a specific player
- `/hwl cache stats` - Show cache statistics
- `/hwl cache clear` - Clear the whitelist cache
- `/hwl version` - Show the installed version and check for updates
- `/hwl update` - Download the latest version (applied on restart)

**Permission Required**: `heimdall.admin` (defaults to OP)

## Permissions

- `heimdall.admin` - Access to admin commands (default: OP)
- `heimdall.bypass` - Bypass all whitelist checks (default: OP, Paper only)
- `heimdall.linkdiscord` - Use the /linkdiscord command (default: true, Paper only)

## How It Works

### For Players

1. Player attempts to join your Minecraft server
2. If not whitelisted, they're shown instructions to join Discord
3. In Discord, they use `/link-minecraft <username>` to start linking
4. They try joining the server again to receive their authentication code
5. They confirm the code in Discord using `/confirm-code <code>`
6. Staff approve their request through the Discord dashboard
7. Player can now join the server normally

### For Staff

1. View pending whitelist applications in the Discord dashboard
2. See player information, Discord profile, and Minecraft username
3. Approve or deny applications with optional notes
4. Real-time updates - no server restarts needed
5. Manage all linked players through the web interface

### Technical Flow

1. **Connection Attempt**: Player tries to join
2. **API Request**: Plugin calls bot API with player info
3. **Decision Logic**: Bot checks database for player status
4. **Response**: API returns whitelist decision and any messages
5. **Action**: Plugin allows/denies connection based on response
6. **Caching**: Response cached briefly to reduce API load

## Troubleshooting

### Common Issues

**"Whitelist system is temporarily unavailable"**

- Check that your bot API is running and accessible
- Verify the `api.baseUrl` in your config
- Check server logs for connection errors

**Players can't get auth codes**

- Ensure Discord integration is properly configured
- Check that the bot has necessary permissions in Discord
- Verify the server ID matches between plugin and bot

**Plugin not working after restart**

- Check console for configuration errors
- Ensure all required permissions are granted
- Verify Java version compatibility

### Debug Mode

Enable debug logging in `config.yml`:

```yaml
logging:
  debug: true
  logRequests: true
  logDecisions: true
```

This will log detailed information about API requests and whitelist decisions.

## Error Handling & Fail-Open Behavior

The plugin implements a robust error handling system with configurable fallback behavior when the Heimdall bot API is unavailable.

### API Retry Logic

When the API is unreachable or returns errors, the plugin will:

1. **Retry 3 times** (configurable via `api.retries`)
2. **Wait between retries** (configurable via `api.retryDelay`)
3. **Fall back** to the configured `apiFallbackMode` after all retries fail

### Fallback Modes

Configure the fallback behavior in `config.yml` under `advanced.apiFallbackMode`:

```yaml
advanced:
  # Fallback behavior when API is completely unavailable after all retries
  apiFallbackMode: "allow" # Recommended for production
```

**Available modes:**

- **`"allow"`** (Recommended): **Fail-open** - Allow all players to join when API is down
  - ✅ Ensures server availability during API outages
  - ⚠️ Temporarily bypasses whitelist security
  - 📝 Players receive a message explaining the situation
  - 💡 Best for production servers where uptime is critical

- **`"whitelist-only"`**: Fall back to local Minecraft whitelist only
  - ✅ Maintains some security during outages
  - ❌ Only previously whitelisted players can join
  - 📝 New players cannot join during API downtime

- **`"deny"`**: **Fail-closed** - Deny all connections when API is down
  - ✅ Maximum security (no unauthorized access)
  - ❌ Server becomes inaccessible during API outages
  - 📝 All players see "API unavailable" message

### Production Recommendation

For production servers, use `apiFallbackMode: "allow"` to ensure your server remains accessible even during:

- Network connectivity issues
- Bot maintenance/updates
- API server downtime
- Database connectivity problems

Players connecting during fail-open mode will receive a message encouraging them to link their Discord account when the system is restored.

### Performance Issues

If you're experiencing lag:

1. Increase `performance.cacheTimeout` to reduce API calls
2. Check your API server performance and network latency
3. Monitor the `performance.maxConcurrentRequests` setting
4. Consider if your API server needs more resources

## Integration with Heimdall Bot

This plugin requires the Heimdall Discord bot to be properly configured:

1. **Environment Variables**: Set `ENABLE_MINECRAFT_SYSTEMS=true` in your bot
2. **API Configuration**: Ensure the bot's API server is running
3. **Database**: MongoDB should be accessible to the bot
4. **Discord Setup**: Bot needs appropriate Discord permissions

See the main Heimdall documentation for bot setup instructions.

## Development

### Building from Source

```bash
git clone https://github.com/Bifrostdotgg/heimdall-minecraft.git
cd heimdall-minecraft
mvn clean package
```

The compiled JAR will be in `target/heimdall-whitelist-X.X.X.jar`.

The plugin version has a single source of truth: the `<version>` in `pom.xml`.
The `templating-maven-plugin` generates `BuildConstants.VERSION` from it, and
`plugin.yml` reads it via resource filtering — so bumping the POM (or releasing a
`vX.Y.Z` tag, which sets the POM) updates every version reference at once.

### API Endpoints Used

- `POST /api/minecraft/connection-attempt` - Check if player should be whitelisted

### Dependencies

- Spigot/Paper API 1.20.1
- Gson 2.10.1 (bundled)

## Support

- **Issues**: Report bugs on [GitHub Issues](https://github.com/Bifrostdotgg/heimdall-minecraft/issues)
- **Discord**: Join our Discord server for community support

## License

This project is **source-available, not open source**. It is licensed under the
[PolyForm Shield License 1.0.0](https://polyformproject.org/licenses/shield/1.0.0)
— see the [LICENSE](LICENSE) file for the full text.

In short: you are free to download, build, run, and modify the plugin for your own
Minecraft servers and networks (including commercial/monetized ones). What you may
**not** do is use it to provide a product or service that competes with Heimdall.
