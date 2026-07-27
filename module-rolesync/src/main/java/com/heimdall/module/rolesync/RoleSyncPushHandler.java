package com.heimdall.module.rolesync;

import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code role_sync} frame: work out who it is about, then hand it to {@link RoleSyncApplier}.
 *
 * <h2>What the frame carries, and what is used</h2>
 *
 * <p>{@code {uuid, username, targetGroups, managedGroups, groupsAdded, groupsRemoved}}, broadcast to
 * every server in the guild rather than addressed to one — so an ordinary frame is about somebody
 * this server has never seen, and "not found" is the common case rather than an error.
 *
 * <p>{@code groupsAdded} and {@code groupsRemoved} are logged at debug and otherwise ignored. They
 * are the bot's account of what changed on the <em>Discord</em> side; the groups actually written
 * are decided by diffing {@code targetGroups} against what LuckPerms reports, which is the only
 * account that is true about this server. Trusting the deltas would write changes a
 * hand-edited server did not need and skip ones it did.
 *
 * <h2>Resolving the player</h2>
 *
 * <p>The UUID is parsed defensively and a malformed one is a log line, not an exception — the frame
 * arrives from the network and a parse failure must not reach the tunnel's dispatcher. When there is
 * no usable UUID the username is looked up against the online players, which is v2's documented
 * fallback and is bounded by design: {@link com.heimdall.core.platform.PlayerDirectory} has no
 * offline lookup, because "resolve this name" has a different answer on every platform and the wrong
 * answer is silent. A frame naming somebody who is offline and has no UUID is dropped with one
 * warning.
 *
 * <h2>Threading</h2>
 *
 * <p>Runs on {@code heimdall-io} — the default {@code TunnelBus.subscribe} executor — never on the
 * socket's reading thread, which is what stops a slow LuckPerms write from making a healthy link
 * look dead to the heartbeat (v2 dispatched inline and had exactly that failure). Nothing here
 * blocks and nothing here throws: {@link #onMessage} contains everything, because a handler that
 * throws costs the frame after it as well as its own.
 *
 * <p>Stateless and immutable; safe to be invoked concurrently for two frames, which the IO pool will
 * do. Ordering between frames is not guaranteed and is not needed — each carries a whole snapshot,
 * so the last one to be applied wins, which is the same answer wire order would have given.
 */
final class RoleSyncPushHandler implements TunnelMessageHandler {

    /** The wire message type. Part of the bot's contract — see {@code stub-bot/README.md}. */
    static final String MESSAGE_TYPE = "role_sync";

    private final ModuleContext context;
    private final RoleSyncApplier applier;
    private final HeimdallLogger logger;

    RoleSyncPushHandler(ModuleContext context, RoleSyncApplier applier) {
        this.context = context;
        this.applier = applier;
        this.logger = context.logger();
    }

    @Override
    public void onMessage(Envelope envelope) {
        try {
            Payload payload = envelope.payload();
            final String rawUuid = payload.string("uuid", null);
            final String username = payload.string("username", null);

            UUID uuid = parseUuid(rawUuid);
            if (uuid == null && username != null && !username.isEmpty()) {
                Optional<PlayerHandle> online = context.platform().players().byName(username);
                if (online.isPresent()) {
                    uuid = online.get().uuid();
                }
            }
            if (uuid == null) {
                // Not an error: role_sync is broadcast to every server in the guild, so most frames
                // are about somebody who is not here. Warn rather than log severely, and say both
                // of the things that failed so a genuinely malformed uuid is distinguishable from a
                // player who simply is not online.
                logger.warn("role_sync: no player to apply it to (uuid='" + rawUuid
                        + "', username='" + username + "'); ignoring it");
                return;
            }

            final UUID resolved = uuid;
            final String label = RoleSyncApplier.label(username, resolved);

            // R3: absent is not empty, and the difference is a revocation.
            //
            // Payload.strings answers an empty list for three states — absent, not-an-array, and a
            // genuinely empty array — and two of those mean opposite things here. `targetGroups: []`
            // is the bot saying "hold none of the managed groups", which strips them. A frame where
            // the field never arrived, or arrived as something this build cannot read, is a frame we
            // do not understand, and treating it as `[]` would revoke groups nobody asked to revoke
            // on the strength of a parse failure.
            if (!payload.hasArray("managedGroups")) {
                logger.warn("role_sync for " + label + " carried no readable managedGroups; "
                        + "ignoring it rather than guessing. An empty list would mean 'change "
                        + "nothing' and a missing one means the frame is not what this build "
                        + "expects — those are not the same thing.");
                return;
            }
            if (!payload.hasArray("targetGroups")) {
                logger.warn("role_sync for " + label + " carried no readable targetGroups; "
                        + "ignoring it rather than treating it as an empty set, which would strip "
                        + "every managed group this player holds.");
                return;
            }

            List<String> target = payload.strings("targetGroups");
            List<String> managed = payload.strings("managedGroups");
            logger.debug(() -> "role_sync for " + label
                    + " — the bot reports added=" + payload.strings("groupsAdded")
                    + " removed=" + payload.strings("groupsRemoved")
                    + "; the diff is recomputed locally against LuckPerms");

            applier.applyFromPush(resolved, label, target, managed);
        } catch (RuntimeException e) {
            logger.error("role_sync could not be handled; the tunnel is unaffected", e);
        }
    }

    /**
     * A UUID, or {@code null} for anything that is not one.
     *
     * <p>Falling through to the username lookup rather than giving up is v2's behaviour, and it is
     * the right one: the two fields come from different places in the bot, so a bad UUID does not
     * imply a bad username.
     */
    private UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            logger.debug(() -> "role_sync carried an unparseable uuid '" + raw
                    + "'; falling back to the username");
            return null;
        }
    }
}
