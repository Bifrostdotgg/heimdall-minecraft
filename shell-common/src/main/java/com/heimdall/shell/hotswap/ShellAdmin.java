package com.heimdall.shell.hotswap;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The admin verbs the shell answers itself: {@code swap} always, and {@code status} while no core
 * is running.
 *
 * <p>{@code swap} belongs to the shell because it has to work with no core at all: after a failed
 * swap and a failed rollback, staging a good core and swapping to it is the only recovery short of
 * a restart. {@code status} is the core's own command whenever a core is running, and only falls
 * back to here so an operator with no core can still see why.
 */
final class ShellAdmin implements RelayTable.AdminVerbs {

    private static final List<String> VERBS = Arrays.asList("status", "swap");

    private final ShellHost host;
    private final ShellAudience audience;

    ShellAdmin(ShellHost host, ShellAudience audience) {
        this.host = host;
        this.audience = audience;
    }

    @Override
    public List<String> verbs() {
        return VERBS;
    }

    @Override
    public boolean handle(Object sender, String label, String[] args, boolean coreBound) {
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("swap".equals(verb)) {
            if (!audience.hasPermission(sender, ShellMessages.ADMIN_PERMISSION)) {
                audience.send(sender, "§cYou do not have permission to use that.");
                return true;
            }
            swap(sender, label);
            return true;
        }
        if (!coreBound && (verb.isEmpty() || "status".equals(verb))) {
            if (!audience.hasPermission(sender, ShellMessages.ADMIN_PERMISSION)) {
                audience.send(sender, "§cYou do not have permission to use that.");
                return true;
            }
            status(sender, label);
            return true;
        }
        return false;
    }

    /** {@code swap}: applies the core an operator staged by hand. */
    private void swap(final Object sender, String label) {
        if (!host.hasStagedFile()) {
            audience.send(sender, "§cNo core is staged. Put a core jar or a release jar at §f"
                    + host.stagedPath() + "§c, or run §f/" + label + " update§c.");
            return;
        }
        // Staged and checked on the swap thread, not here: this is the main thread on Bukkit, and
        // staging copies and hashes a jar of a few megabytes. A core that is unusable, built for
        // another contract or already running is refused there and reported back to the sender.
        if (!host.requestSwapFromFile(host.stagedPath(), new AudienceListener(audience, sender))) {
            audience.send(sender, "§cA swap is already in progress.");
            return;
        }
        audience.send(sender, "§7Swap to the staged core started.");
    }

    /** {@code status} with no core: what the shell knows, and how to recover. */
    private void status(Object sender, String label) {
        audience.send(sender, "§6Heimdall §7shell v" + host.shellVersion()
                + " §8- §cno core is running");
        String problem = host.lastProblem();
        if (!problem.isEmpty()) {
            audience.send(sender, "§7Why: §f" + problem);
        }
        if (host.isSwapping()) {
            audience.send(sender, "§7A swap is in progress.");
            return;
        }
        audience.send(sender, "§7Logins are refused until a core is running. Stage a core at §f"
                + host.stagedPath() + "§7 and run §f/" + label + " swap§7, or restart.");
    }
}
