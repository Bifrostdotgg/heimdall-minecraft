/**
 * Configuration the dashboard owns, delivered over the tunnel and cached on disk.
 *
 * <p>The point of the split (departure D17): {@code bootstrap.yml} holds only what is needed to
 * reach the bot, and everything else — messages, cache windows, which modules are on, role-sync
 * groups, offense templates — arrives as remote config. v2 had a ~200-line {@code config.yml} per
 * server, so a fleet operator changing one message edited it on every box and support could never be
 * sure what a given server was actually running.
 *
 * <p>Three sources, in precedence order: a live {@code config.push}, then the disk cache, then the
 * built-in defaults. The cache is what makes the whole arrangement safe to depend on — a server
 * restarting while the bot is redeploying comes up exactly as it was, rather than with nothing
 * enabled.
 *
 * <p>Which means the built-in defaults are a safety surface, not a formality. There is a real state
 * — first boot, offline, or against a bot that speaks v2 — in which a module runs on nothing but
 * them, so every safety-relevant setting has to be safe at its default. "Fail open or fail closed
 * when the bot is unreachable" is exactly one of those, and it lands with the modules in phase 1d.
 */
package com.heimdall.core.remoteconfig;
