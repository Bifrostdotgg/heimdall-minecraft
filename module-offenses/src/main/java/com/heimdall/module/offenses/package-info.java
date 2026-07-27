/**
 * The Offenses feature module — punishments: bans, mutes, kicks and warnings.
 *
 * <p>{@code /offend <player> <offense> [notes]} reports an infraction to the bot and dispatches the
 * punishment command the bot decides on. <strong>The escalation maths is not here</strong>: the
 * point totals, the tier table, the durations and the command templates are all bot-side, so a
 * dashboard change takes effect across a whole fleet without any server updating anything. The
 * plugin sends three fields and runs the string it is handed.
 *
 * <p>Platform-free by contract: everything here talks to the server through core abstractions,
 * never through Bukkit or Velocity types directly. The {@code :conformance} module enforces it.
 */
package com.heimdall.module.offenses;
