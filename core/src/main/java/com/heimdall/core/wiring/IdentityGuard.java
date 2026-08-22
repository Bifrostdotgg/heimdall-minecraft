package com.heimdall.core.wiring;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.IdentityCheckPolicy;
import com.heimdall.core.identity.InstanceFingerprint;
import com.heimdall.core.util.Strings;
import java.util.concurrent.TimeUnit;

/**
 * Decides whether the credentials in {@code bootstrap.yml} belong to the machine reading them.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>The bot mints a {@code serverId} once, when a setup code is claimed, and it lives in
 * {@code bootstrap.yml} next to the token. Copy that directory, or restore a backup of it onto a
 * second server, and there are now two processes with byte-identical credentials. Both dial the
 * tunnel, the bot keeps the most recent socket per {@code serverId}, and each new connection evicts
 * the other. In production that ran for hours: two Purpur servers taking it in turns to be the one
 * that exists, several reconnects a minute each, and a bot doing nothing else.
 *
 * <p>Neither server can be told from the other by anything on the wire, because everything on the
 * wire came out of the same file. So the plugin records what machine it was bound to, in the same
 * file, and compares it on the way up.
 *
 * <h2>Pure by design</h2>
 *
 * <p>{@link #evaluate(BootstrapConfig, InstanceFingerprint)} makes the decision and does nothing
 * about it. Writing the adopted fingerprint, logging, arming the repeat warning and refusing to dial
 * all live in {@link HeimdallRuntime}, where the lock and the executors are. That keeps the state
 * table testable without a runtime, and it keeps "what we decided" separate from "what we did about
 * it" - which matters here, because the decision is re-made on every reload.
 */
public final class IdentityGuard {

    /**
     * How often a mismatch repeats itself in the log.
     *
     * <p>Matches {@code GuildDiscovery.WARN_INTERVAL_MS} deliberately: an operator who scrolls back
     * through a console should find the two long-running complaints at the same cadence, and fifteen
     * minutes is often enough to be seen, rare enough not to be filtered out.
     */
    public static final long WARN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);

    /** What the comparison concluded. */
    public enum State {

        /** No credentials yet, so there is nothing to bind and nothing to compare. */
        NOT_SET_UP,

        /** {@code identityCheck: off}. No comparison was made. */
        DISABLED,

        /**
         * Configured, but no fingerprint has ever been recorded.
         *
         * <p>Every install that predates this check, and every fresh one, passes through here
         * exactly once. The current fingerprint is written and the boot carries on.
         */
        ADOPTED,

        /** The recorded fingerprint is this machine. The normal state, and silent. */
        BOUND,

        /** A mismatch the plugin refuses to dial through. */
        MISMATCH_BLOCKED,

        /** A mismatch the plugin warns about and dials anyway. */
        MISMATCH_ADVISORY
    }

    private IdentityGuard() {
    }

    /**
     * Compares the recorded fingerprint with this machine's.
     *
     * <p>{@link IdentityCheckPolicy#AUTO} blocks only on a {@link InstanceFingerprint.Tier#PANEL}
     * fingerprint, and the asymmetry is the whole design. A panel uuid is issued per server and is
     * stable across restarts, reinstalls and image updates, so if it changed, this really is a
     * different server and refusing to connect is right. A host-tier fingerprint is a host name and
     * a path, and a container that gets recreated has a new host name through no fault of the
     * operator, so blocking on one would take working servers offline for a cosmetic change. The
     * copied-directory case is still caught: it is just caught with a warning until somebody sets
     * {@code identityCheck: strict}.
     *
     * @param config what is on disk; {@code null} is treated as not set up
     * @param current this machine's fingerprint; {@code null} is treated as not set up
     */
    public static Decision evaluate(BootstrapConfig config, InstanceFingerprint current) {
        if (config == null || current == null || !config.isConfigured()) {
            String currentValue = current == null ? "" : current.value();
            IdentityCheckPolicy policy =
                    config == null ? IdentityCheckPolicy.AUTO : config.identityCheck();
            return new Decision(State.NOT_SET_UP, "", currentValue, policy);
        }
        IdentityCheckPolicy policy = config.identityCheck();
        String recorded = config.instanceFingerprint();
        if (policy == IdentityCheckPolicy.OFF) {
            return new Decision(State.DISABLED, recorded, current.value(), policy);
        }
        if (Strings.isBlank(recorded)) {
            return new Decision(State.ADOPTED, "", current.value(), policy);
        }
        if (recorded.equals(current.value())) {
            return new Decision(State.BOUND, recorded, current.value(), policy);
        }
        boolean refuse = policy == IdentityCheckPolicy.STRICT
                || current.tier() == InstanceFingerprint.Tier.PANEL;
        State state = refuse ? State.MISMATCH_BLOCKED : State.MISMATCH_ADVISORY;
        return new Decision(state, recorded, current.value(), policy);
    }

    /** One evaluation: what was decided, and the values it was decided from. */
    public static final class Decision {

        private final State state;
        private final String recorded;
        private final String current;
        private final IdentityCheckPolicy policy;

        Decision(State state, String recorded, String current, IdentityCheckPolicy policy) {
            this.state = state;
            this.recorded = recorded == null ? "" : recorded;
            this.current = current == null ? "" : current;
            this.policy = policy == null ? IdentityCheckPolicy.AUTO : policy;
        }

        public State state() {
            return state;
        }

        /** The fingerprint in {@code bootstrap.yml}, or {@code ""} if there was none. */
        public String recorded() {
            return recorded;
        }

        /** The fingerprint of the machine that read the file. */
        public String current() {
            return current;
        }

        public IdentityCheckPolicy policy() {
            return policy;
        }

        /** Whether the tunnel must not be dialled. */
        public boolean blocksTunnel() {
            return state == State.MISMATCH_BLOCKED;
        }

        /** Whether the two fingerprints disagree, blocking or not. */
        public boolean isMismatch() {
            return state == State.MISMATCH_BLOCKED || state == State.MISMATCH_ADVISORY;
        }

        /** A few words for a status line. */
        public String summary() {
            switch (state) {
                case NOT_SET_UP:
                    return "not bound yet (this server is not set up)";
                case DISABLED:
                    return "not checked (identityCheck: off)";
                case ADOPTED:
                    return "bound to this instance just now";
                case MISMATCH_BLOCKED:
                    return "MISMATCH (blocked)";
                case MISMATCH_ADVISORY:
                    return "MISMATCH (warning only)";
                case BOUND:
                default:
                    return "bound";
            }
        }

        /**
         * The warning, naming both values and both ways out. Only meaningful for a mismatch.
         *
         * <p>There is deliberately no no-argument overload defaulting to {@code hd}. The proxy
         * builds register {@code hdp}, and a warning that tells a proxy operator to run a command
         * their server does not have is worse than no warning: it is the one line they will paste
         * into a support channel.
         *
         * @param label the root command label, without a slash
         */
        public String warning(String label) {
            String command = "/" + (Strings.isBlank(label) ? "hd" : label.trim());
            String base = "identity mismatch: bootstrap.yml was bound to \"" + recorded
                    + "\" but this server is \"" + current + "\". If you moved this server, run "
                    + command + " identity adopt. If this is a copy of another server, run "
                    + command + " identity reset confirm and claim a new code with " + command
                    + " setup.";
            if (state == State.MISMATCH_BLOCKED) {
                return base + " Tunnel not started.";
            }
            return base + " Tunnel started anyway because identityCheck is auto and no panel id is "
                    + "available; set identityCheck: strict to refuse.";
        }

        @Override
        public String toString() {
            return "IdentityGuard.Decision{" + state + ", policy=" + policy.wireName()
                    + ", recorded='" + recorded + "', current='" + current + "'}";
        }
    }
}
