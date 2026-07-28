/**
 * The administrative command tree: {@code /hd} on the Bukkit family, {@code /hdp} on the proxy.
 *
 * <p>Written once, here, for the same reason {@code HeimdallRuntime} is (departure D48): v2 kept its
 * command tree as a chain of {@code equalsIgnoreCase} branches inside two entry points that were
 * 1,086 and 1,311 lines long, and the two had drifted — the proxy's {@code cache} verb had no
 * {@code cleanup}, its {@code test} printed different fields, and neither could be exercised without
 * starting a Minecraft server. Everything in this package is platform-free and takes a
 * {@link com.heimdall.core.command.CommandSource}, so the whole tree is testable with a fake sender.
 *
 * <p>Only the verb differs per platform, and it differs on purpose: see departure D47 for why the
 * proxy answers to {@code /hdp} rather than to {@code /hd}.
 *
 * <h2>What is a subcommand and what is a module command</h2>
 *
 * <p>Everything here is an <em>operator</em> action gated on {@code heimdall.admin}.
 * {@code /linkdiscord} and {@code /offend} are not: they are player-facing, they belong to the
 * modules that implement them, and they are registered and unregistered with those modules (D53).
 *
 * <h2>Reaching a module from core</h2>
 *
 * <p>Core must not depend on the feature modules — their being optional is the whole point of the
 * module system — so three verbs that need one ({@code test}, {@code cache}, {@code offense}) go
 * through the small interfaces in this package. The modules implement them, and the wiring in
 * {@code :platform-common} — the one place that already depends on both — introduces them. Each has
 * a {@code NONE} implementation, so a build compiled without a module gets a verb that says the
 * feature is not installed rather than a command that is missing.
 */
package com.heimdall.core.admin;
