package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Strings;
import java.util.List;

/**
 * {@code /hd version} and {@code /hd update} — the self-updater's two verbs, unchanged from v2.
 *
 * <p>Two rather than one, and the split is v2's: {@code version} asks the bot what the newest
 * release is and reports; {@code update} downloads and installs it. Keeping "tell me" separate from
 * "do it" is what stops a command that prints a version number from also replacing a jar, which is
 * the kind of surprise nobody wants from a server console.
 *
 * <p>{@code update} refuses unless a check has already found something newer, again as v2 did. That
 * is not caution for its own sake: the installer's whole job is to put a file where the server will
 * pick it up on restart, and doing that on the strength of no information at all is how a server
 * ends up staged with the version it already has.
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
                                + "§a is available. Run §f/hd update§a to download it; it applies "
                                + "on the next restart."));
                    } else {
                        source.sendMessage(Msg.legacy("§aNothing newer is published, or the bot "
                                + "could not be asked. §7/hd status§a says which."));
                    }
                }
            });
        }
    }

    /** {@code /hd update} — download the newest release and stage it for the next restart. */
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
            return "download the newest release, applied on the next restart";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            final UpdateAdmin updates = context.updates();
            if (!updates.isSupported()) {
                source.sendMessage(Msg.legacy("§eThis build has no self-updater."));
                return;
            }
            if (!updates.isUpdateAvailable()) {
                source.sendMessage(Msg.legacy("§eNo update is known to be available. Run §f/hd "
                        + "version§e first — this only installs something a check has found."));
                return;
            }
            String latest = updates.latestVersion();
            source.sendMessage(Msg.legacy("§7Downloading Heimdall §f"
                    + (Strings.isBlank(latest) ? "update" : latest) + "§7…"));
            context.async(new Runnable() {
                @Override
                public void run() {
                    source.sendMessage(Msg.legacy("§a" + updates.updateNow()));
                }
            });
        }
    }
}
