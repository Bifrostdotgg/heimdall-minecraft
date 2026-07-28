package com.heimdall.core.admin;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Everything the admin subcommands are allowed to touch, gathered once.
 *
 * <p>Exists for the reason {@code ModuleEnvironment} does: a dozen small handlers would otherwise
 * each grow a six-argument constructor that has to be edited in a dozen places when the set changes,
 * and a test would have to build all six to exercise one.
 *
 * <p>The three module surfaces default to their {@code NONE} implementations, so a build compiled
 * without a feature module — or a test that only cares about {@code /hd status} — still produces a
 * context that answers every verb coherently.
 *
 * <h2>{@link #async(Runnable)} is not a convenience</h2>
 *
 * <p>A command handler runs on the server's main thread on the Bukkit family. Half the verbs here
 * block on a signed HTTP round trip with a retry budget behind it — at the defaults that is up to
 * 47 seconds for a whitelist sync — and doing that inline would freeze the server. Every blocking
 * subcommand acknowledges immediately and finishes on {@code heimdall-io}, and this is the one place
 * that hand-off is written, so it cannot be forgotten differently in nine handlers.
 *
 * <p>Immutable, and safe from any thread.
 */
public final class AdminContext {

    private final HeimdallRuntime runtime;
    private final ServerRole role;
    private final String pluginVersion;
    private final String label;
    private final WhitelistAdmin whitelist;
    private final OffenseAdmin offenses;
    private final UpdateAdmin updates;

    private AdminContext(Builder builder) {
        if (builder.runtime == null) {
            throw new IllegalArgumentException("a runtime is required");
        }
        this.runtime = builder.runtime;
        this.role = builder.role == null ? ServerRole.AUTO : builder.role;
        this.pluginVersion = builder.pluginVersion == null ? "" : builder.pluginVersion;
        this.label = builder.label == null || builder.label.isEmpty() ? "hd" : builder.label;
        this.whitelist = builder.whitelist == null ? WhitelistAdmin.NONE : builder.whitelist;
        this.offenses = builder.offenses == null ? OffenseAdmin.NONE : builder.offenses;
        this.updates = builder.updates == null ? UpdateAdmin.NONE : builder.updates;
    }

    public static Builder builder(HeimdallRuntime runtime) {
        return new Builder().runtime(runtime);
    }

    /** The assembled plugin. */
    public HeimdallRuntime runtime() {
        return runtime;
    }

    /** The <em>resolved</em> role of this instance — never {@link ServerRole#AUTO} in production. */
    public ServerRole role() {
        return role;
    }

    /** The build's version string, for {@code /hd version} and the status banner. */
    public String pluginVersion() {
        return pluginVersion;
    }

    /**
     * The primary verb on this platform — {@code hd} on the Bukkit family, {@code hdp} on the proxy.
     *
     * <p>Threaded into usage strings so {@code /hdp setup} does not reply "Usage: /hd setup...".
     * Every subcommand builds its usage line as {@code "/" + context.label() + " …"} rather than
     * hardcoding one, which the setup and migrate smoke rows being Bukkit-only would otherwise have
     * hidden.
     */
    public String label() {
        return label;
    }

    public WhitelistAdmin whitelist() {
        return whitelist;
    }

    public OffenseAdmin offenses() {
        return offenses;
    }

    public UpdateAdmin updates() {
        return updates;
    }

    /**
     * Runs blocking work off the server thread.
     *
     * <p>A rejection means the pools are shutting down, which is the one moment an admin command
     * failing to run costs nothing — so it is a debug line rather than an error the operator has to
     * interpret while the server is stopping.
     */
    public void async(Runnable work) {
        Executor io = runtime.executors().io();
        try {
            io.execute(work);
        } catch (RejectedExecutionException shuttingDown) {
            // Deliberately silent. This is only reachable while the pools are draining, which means
            // the server is stopping and the console the answer would go to is about to stop
            // existing — and an admin command that did not complete during shutdown is not a fault
            // worth a line in a log somebody will read tomorrow looking for the cause of a crash.
        }
    }

    /** The mutable writer. Only the runtime is required. */
    public static final class Builder {

        private HeimdallRuntime runtime;
        private ServerRole role;
        private String pluginVersion;
        private String label;
        private WhitelistAdmin whitelist;
        private OffenseAdmin offenses;
        private UpdateAdmin updates;

        private Builder() {
        }

        public Builder runtime(HeimdallRuntime value) {
            this.runtime = value;
            return this;
        }

        public Builder role(ServerRole value) {
            this.role = value;
            return this;
        }

        public Builder pluginVersion(String value) {
            this.pluginVersion = value;
            return this;
        }

        /** The platform's primary verb, {@code hd} or {@code hdp}. Defaults to {@code hd}. */
        public Builder label(String value) {
            this.label = value;
            return this;
        }

        /** The whitelist module's surface. Left unset, {@link WhitelistAdmin#NONE}. */
        public Builder whitelist(WhitelistAdmin value) {
            this.whitelist = value;
            return this;
        }

        /** The offenses module's surface. Left unset, {@link OffenseAdmin#NONE}. */
        public Builder offenses(OffenseAdmin value) {
            this.offenses = value;
            return this;
        }

        /** The self-updater. Left unset, {@link UpdateAdmin#NONE}. */
        public Builder updates(UpdateAdmin value) {
            this.updates = value;
            return this;
        }

        public AdminContext build() {
            return new AdminContext(this);
        }
    }
}
