/**
 * Linking a Minecraft account to a Discord one: the player-facing {@code /linkdiscord} verb.
 *
 * <p>In core rather than in a feature module because every feature that cares who a player is on
 * Discord (whitelist, role sync, punishments, the chat bridge) depends on the link existing, and
 * none of them owns it. See {@link com.heimdall.core.link.LinkDiscordCommand} for the decision and
 * its one cost.
 */
package com.heimdall.core.link;
