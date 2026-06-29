# Heimdall Minecraft Plugin Setup Guide

This guide will help you set up the Heimdall Minecraft Plugin for dynamic whitelist management with your Discord bot.

## Prerequisites

- Heimdall Discord bot running with API server enabled
- Minecraft server (Spigot/Paper 1.19+)
- Administrator access to both Discord bot and Minecraft server

## Step 1: Enable Minecraft Features in the Bot

1. Set the environment variable `ENABLE_MINECRAFT_SYSTEMS=true` in your bot's configuration
2. Restart your Discord bot to enable Minecraft commands

## Step 2: Get HMAC Secret

1. Copy the `INTERNAL_API_KEY` value from your bot's `.env` file
2. This secret is used to sign requests — it is never sent over the wire

## Step 3: Install the Plugin

1. Download `heimdall-whitelist-1.0.0.jar` from the releases
2. Place it in your Minecraft server's `plugins/` directory
3. Start your server to generate the default config file
4. Stop your server

## Step 4: Configure the Plugin

1. Edit `plugins/HeimdallWhitelist/config.yml`:

   ```yaml
   api:
     baseUrl: "http://your-bot-server:3001" # Your bot's API URL
     hmacSecret: "your-internal-api-key-from-step-2" # The bot's INTERNAL_API_KEY value

   server:
     displayName: "My Minecraft Server" # Name shown in Discord

   messages:
     notWhitelisted: "§cNot whitelisted! Link your Discord account first."
   ```

2. Customize other settings as needed (see config-example.yml)

## Step 5: Set Up Discord Commands

1. In your Discord server, run:
   ```
   /minecraft-setup
   ```
2. Follow the setup wizard to configure:
   - Server IP/port for status checking
   - Welcome channels
   - Role assignments

## Step 6: Test the Integration

1. Start your Minecraft server
2. Try joining with a player that's not whitelisted
3. You should see the custom message directing them to Discord
4. Use `/link-minecraft` in Discord to start the linking process

## How It Works

### Dynamic Whitelist Flow

1. **Player Joins**: Plugin intercepts all login attempts
2. **API Check**: Plugin calls bot API to check if player should be whitelisted
3. **Response Handling**:
   - **Allowed**: Player joins normally
   - **Denied**: Player sees custom message
   - **Pending**: Player gets temporary access with auth code

### Discord Integration

- `/link-minecraft` - Start account linking process
- `/confirm-code` - Complete linking with generated code
- `/minecraft-status` - Check server status
- `/mcstatus` - Quick server ping

### Admin Features

- Real-time whitelist management through Discord
- No need to manually edit whitelist.json
- Automatic cleanup of expired auth codes
- Detailed logging and error handling

## Troubleshooting

### "Unauthorized" Error (HTTP 401)

- Check that `api.hmacSecret` in `config.yml` matches the bot's `INTERNAL_API_KEY` env var
- Ensure the bot is running and API server is accessible
- Verify your server's clock is accurate (signatures expire after 5 minutes)

### Players Can't Connect

- Check plugin logs for API errors
- Verify bot API URL is correct and accessible from Minecraft server
- Test API connection: `curl http://your-bot:3001/api/health` (health endpoint requires no auth)

### API Timeouts

- Increase `api.timeout` in plugin config
- Check network connectivity between Minecraft and bot servers
- Consider enabling `advanced.allowTemporaryAccess` for better UX

### Debug Logging

Enable debug logging in the plugin config:

```yaml
logging:
  debug: true
  logApiCalls: true
```

This will show detailed API request/response information.

## Security Notes

- Keep your HMAC secret secure — treat it like a password
- The secret is never sent over the wire; only HMAC signatures are transmitted
- Use HTTPS for production deployments
- Consider running bot API on internal network only
- Regularly rotate secrets by updating both the bot's `INTERNAL_API_KEY` and the plugin's `api.hmacSecret`

## Support

If you encounter issues:

1. Check the plugin logs in `logs/latest.log`
2. Enable debug logging for more details
3. Verify all configuration settings
4. Test API connectivity manually
