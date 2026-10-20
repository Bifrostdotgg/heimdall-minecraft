package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.text.Msg;
import com.heimdall.core.update.InstallOutcome;
import java.util.List;

/**
 * {@code /hd version} and {@code /hd update}: the self-updater's two verbs, as in v2 except that
 * {@code update} now does its own check (departure D91).
 *
 * <p>Two rather than one, and the split is v2's: {@code version} asks the bot what the newest
 * release is and reports; {@code update} downloads and installs it. Keeping "tell me" separate from
 * "do it" is what stops a command that prints a version number from also replacing a jar, which is
 * the kind of surprise nobody wants from a server console.
 *
 * <p>{@code update} does its own check first, against the bot with its release cache skipped, and
 * installs only when that check finds something newer. v2 instead refused unless an earlier check
 * had already found an update, which left the operator to run {@code version} first whenever the
 * periodic check was off or a release had landed since it last ran. Installing on no information
 * is still never done: a check that fails installs nothing and says so.
 *
 * <p>Neither verb applies anything to the running process. Both platforms' strategies — Paper's
 * {@code plugins/update/} folder, Velocity's in-place jar swap — take effect on the next start, and
 * every message here says so, because "it said it updated and nothing changed" is the report that
 * follows if they do not.
 *
 * <h2>Threading</h2>
 *
 * <p>Both block: one on an HTTP round trip, the other on that plus a download of up to 50 MB. Both
 * acknowledge and hand off to {@code heimdall-io}.
 */
final class UpdateSubcommands {

    private UpdateSubcommands() {
    }

    /** {@code /hd version} — what is running, and whether anything newer is published. */
    static final class Version implements AdminSubcommand {

        @Override
        public String name() {
            return "version";
        }

        @Override
        public String usage() {
            return "";
        }

        @Override
        public String description() {
            return "show this build's version and check for a newer one";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            source.sendMessage(Msg.legacy("§6Heimdall §fv" + context.pluginVersion()));
            final UpdateAdmin updates = context.updates();
            if (!updates.isSupported()) {
                source.sendMessage(Msg.legacy("§7This build has no self-updater."));
                return;
            }
            source.sendMessage(Msg.legacy("§7Checking for updates…"));
            context.async(new Runnable() {
                @Override
                public void run() {
                    if (updates.checkNow()) {
                        source.sendMessage(Msg.legacy("§aVersion §f" + updates.latestVersion()
                                + "§a is available. Run §f/hd update§a to install it: live when it "
                                + "can be, otherwise on the next restart."));
                    } else {
                        source.sendMessage(Msg.legacy("§aNothing newer is published, or the bot "
                                + "could not be asked. §7/hd status§a says which."));
                    }
                }
            });
        }
    }

    /**
     * {@code swap}: applies a core an operator staged by hand. Listed here so help and completion
     * show it, but the shell answers it before any core sees it, because it has to work with no
     * core running at all (departure D87). Reaching this means no shell took the command.
     */
    static final class Swap implements AdminSubcommand {

        @Override
        public String name() {
            return "swap";
        }

        @Override
        public String usage() {
            return "";
        }

        @Override
        public String description() {
            return "swap in the core staged at core/staged.jar, live";
        }

        @Override
        public void run(CommandSource source, List<String> args, AdminContext context) {
            // The same gate the shell applies, as a belt: the admin tree is already behind
            // heimdall.admin, and this costs one call.
            if (!source.hasPermission(AdminCommand.PERMISSION)) {
                source.sendMessage(Msg.legacy("§cYou do not have permission to use that."));
                return;
            }
            source.sendMessage(Msg.legacy("§eSwapping is done by Heimdall's shell, and this "
                    + "server's shell did not answer. Install the current release and restart."));
        }
    }

    /**
     * {@code /hd update}: download the newest release, install it for the next restart, and swap
     * its core in live when it is verified and fits this shell (departure D87).
     */
    static final class Update implements AdminSubcommand {

        @Override
        public String name() {
            return "update";
        }

        @Override
        public String usage() {
            return "";
        }

        @Override
        public String description() {
            return "check for a newer release and install it, live when it can be, otherwise on "
                    + "restart";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            final UpdateAdmin updates = context.updates();
            if (!updates.isSupported()) {
                source.sendMessage(Msg.legacy("§eThis build has no self-updater."));
                return;
            }
            // No gate on an earlier check: updateNow checks the bot itself, fresh, and installs
            // only if that check finds something newer than what is running.
            source.sendMessage(Msg.legacy("§7Checking for a newer version of Heimdall…"));
            context.async(new Runnable() {
                @Override
                public void run() {
                    // A live swap, if there is one, is requested inside updateNow and the message
                    // says whether it was accepted. The shell holds a requested swap back long
                    // enough for this reply to go out first, then reports the outcome to the same
                    // sender (departure D87).
                    InstallOutcome outcome = updates.updateNow(source.nativeSender());
                    // Green only when something was installed: "already the latest version" and a
                    // check that could not reach the bot are not successes to an operator.
                    source.sendMessage(Msg.legacy((outcome.installed() ? "§a" : "§e")
                            + outcome.message()));
                }
            });
        }
    }
}
