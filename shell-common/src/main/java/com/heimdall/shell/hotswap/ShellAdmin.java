package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CoreIdentity;
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
        CoreArchive.CoreJar jar;
        try {
            jar = host.stage(host.stagedPath());
        } catch (CoreArchiveException unusable) {
            audience.send(sender, "§cThe staged core cannot be used: " + unusable.getMessage());
            return;
        }
        CoreIdentity running = host.runningCore();
        if (jar.identity().equals(running)) {
            audience.send(sender, "§eCore " + jar.identity() + " is already running.");
            return;
        }
        if (!host.requestSwap(jar, new SenderListener(sender))) {
            audience.send(sender, "§cA swap is already in progress.");
            return;
        }
        audience.send(sender, "§7Swap to core §f" + jar.identity() + "§7 started.");
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

    /** Reports a swap's progress and result back to whoever asked for it. */
    private final class SenderListener implements SwapListener {

        private final Object sender;

        SenderListener(Object sender) {
            this.sender = sender;
        }

        @Override
        public void progress(String line) {
            audience.send(sender, "§7" + line);
        }

        @Override
        public void finished(SwapOutcome outcome) {
            String colour = outcome.succeeded() ? "§a"
                    : outcome.kind() == SwapOutcome.Kind.NO_CORE ? "§4" : "§c";
            audience.send(sender, colour + outcome.message());
        }
    }
}
