package com.heimdall.core.wiring;

import com.heimdall.core.admin.AdminCommand;
import com.heimdall.core.admin.UpdateAdmin;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.update.DownloadPolicy;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.ReleaseSource;
import com.heimdall.core.update.UpdateDownloader;
import com.heimdall.core.update.UpdateInstaller;
import com.heimdall.core.update.UpdateService;
import com.heimdall.core.update.UpdateSettings;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Assembles the self-updater and hands the admin tree a handle on it.
 *
 * <p>Four objects have to be joined up and none of them should know about the other three: the
 * update service is platform-free state, the installer is entirely platform work, the release source
 * is the HTTP gateway wearing a narrower interface, and the settings come from remote config. This
 * is the seam where they meet, and it lives in {@code wiring} for the same reason
 * {@link HeimdallRuntime} does — the alternative is the same eight lines written twice in two entry
 * points, which is departure D48's whole subject.
 *
 * <p>The updater is optional. A platform that cannot install a jar passes no
 * {@link UpdateInstaller} and gets {@link UpdateAdmin#NONE}, so {@code /hd update} says the feature
 * is not available rather than appearing to work.
 *
 * <h2>Where the settings come from, and why they are read live</h2>
 *
 * <p>{@code updates} is a remote-config section like any module's, so an operator changing the check
 * interval in the dashboard does not restart anything. The supplier is read on each tick rather than
 * captured, which matters most on a first boot: the plugin starts before the bot has pushed
 * anything, so a captured value would be the built-in default forever.
 *
 * <p>There is deliberately no {@code updates} <em>module</em> behind that key. The updater has no
 * listeners, no commands of its own and nothing to unwind, so making it a {@code HeimdallModule}
 * would buy a lifecycle it does not need and a capability the bot's table does not know — which,
 * per {@code Capabilities}, would be silently dropped from the handshake and produce a section that
 * is never pushed.
 */
public final class UpdateWiring {

    /** The remote-config section the updater's settings live under. */
    public static final String SETTINGS_KEY = "updates";

    private UpdateWiring() {
    }

    /**
     * Builds the updater, starts its periodic check, and returns both halves.
     *
     * @param installer where a downloaded jar goes on this platform, or {@code null} for a build
     *     that cannot install one
     * @return the admin surface and the handle that stops the periodic check
     */
    public static Installed install(
            HeimdallLogger logger,
            String currentVersion,
            HeimdallRuntime runtime,
            UpdateInstaller installer) {
        if (installer == null) {
            return new Installed(UpdateAdmin.NONE, Registration.NONE, null);
        }
        final UpdateService service = new UpdateService(
                logger,
                currentVersion,
                new GatewayReleaseSource(runtime.api()),
                installer,
                new UpdateDownloader(logger, DownloadPolicy.github()),
                runtime.executors().scheduler());
        final RemoteConfig config = runtime.remoteConfig();

        List<Registration> handles = new ArrayList<Registration>();
        handles.add(service.startPeriodicChecks(new Supplier<UpdateSettings>() {
            @Override
            public UpdateSettings get() {
                return new UpdateSettings(config.moduleSettings(SETTINGS_KEY));
            }
        }));
        handles.add(subscribeToRemoteUpdates(logger, runtime, service));
        handles.add(notifyAdminsOnJoin(logger, runtime, service));
        return new Installed(new ServiceAdmin(service), combine(handles), service);
    }

    /**
     * Answers the dashboard's update button.
     *
     * <p>The bot sends a correlated {@code update} frame and waits for {@code update_result}; a
     * client that never replies leaves a spinner on somebody's screen until it times out. So the
     * reply is sent on <em>every</em> path, failures included, and it carries the same sentence
     * {@code /hd update} would have printed — the operator who clicked the button and the operator
     * who typed the command should not get different accounts of the same event.
     *
     * <p>Handled on {@code heimdall-io} rather than on the socket's reading thread, and that is not
     * a preference: this downloads up to 50 MB, and doing it on the reading thread would stop the
     * tunnel reading for the duration, letting the bot's liveness sweep reap a connection that is
     * working perfectly (departure D27).
     */
    private static Registration subscribeToRemoteUpdates(
            final HeimdallLogger logger, final HeimdallRuntime runtime, final UpdateService service) {
        final TunnelClient tunnel = runtime.tunnel();
        return tunnel.subscribe("update", new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                InstallOutcome outcome;
                try {
                    outcome = service.updateNow();
                } catch (RuntimeException broken) {
                    // updateNow is documented as never throwing, so this is a bug rather than a
                    // failed update — and the dashboard still has to be told something.
                    logger.error("a dashboard-triggered update threw", broken);
                    outcome = InstallOutcome.failed("the update failed unexpectedly: " + broken);
                }
                PluginRelease release = service.latestRelease();
                tunnel.reply(envelope.id(), "update_result", Payload.builder()
                        .put("success", outcome.installed())
                        .put("message", outcome.message())
                        .put("version", release == null ? "" : Strings.trimToEmpty(release.version()))
                        .build());
            }
        }, runtime.executors().io());
    }

    /**
     * Tells an admin who joins that an update is waiting, if one is.
     *
     * <p>v2's {@code updates.notifyAdmins}, unchanged, and it exists because nobody reads a server's
     * boot log. The line goes to whoever holds {@code heimdall.admin} and to nobody else, because to
     * everybody else it is an announcement about a file.
     *
     * <p>Runs on {@code heimdall-io} — the session dispatcher already moved it off the event thread
     * (departure D52) — so the permission lookup and the send cannot cost a tick.
     */
    private static Registration notifyAdminsOnJoin(
            final HeimdallLogger logger, final HeimdallRuntime runtime, final UpdateService service) {
        return runtime.playerSessions().onJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                String notice = service.joinNotice(settings(runtime));
                if (notice == null) {
                    return;
                }
                try {
                    if (player.hasPermission(AdminCommand.PERMISSION)) {
                        player.sendMessage(Msg.legacy("§e[Heimdall] §7" + notice));
                    }
                } catch (RuntimeException unavailable) {
                    // A permission plugin that throws for a player who has since left, most likely.
                    // Nobody's join is worth failing over a notice about a jar.
                    logger.debug(() -> "could not tell " + player.name() + " about an update: "
                            + unavailable);
                }
            }
        });
    }

    /** One handle that closes several, in reverse. */
    private static Registration combine(final List<Registration> handles) {
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                Collections.reverse(handles);
                for (Registration handle : handles) {
                    handle.close();
                }
            }
        });
    }

    /**
     * The settings currently in force, for the join notice.
     *
     * <p>Read at the point of use rather than held, for the reason every settings read in this
     * codebase is: a dashboard edit does not re-run any of the wiring, so a captured value is
     * permanently stale after the first one.
     */
    public static UpdateSettings settings(HeimdallRuntime runtime) {
        return new UpdateSettings(runtime.remoteConfig().moduleSettings(SETTINGS_KEY));
    }

    /** What {@link #install} produced: the command surface, the timer, and the service itself. */
    public static final class Installed {

        private final UpdateAdmin admin;
        private final Registration periodicChecks;
        private final UpdateService service;

        private Installed(UpdateAdmin admin, Registration periodicChecks, UpdateService service) {
            this.admin = admin;
            this.periodicChecks = periodicChecks;
            this.service = service;
        }

        /** What {@code /hd version} and {@code /hd update} talk to. Never {@code null}. */
        public UpdateAdmin admin() {
            return admin;
        }

        /** Stops the periodic check. Closed by the platform on disable. */
        public Registration periodicChecks() {
            return periodicChecks;
        }

        /**
         * The line an admin joining the server should see, or {@code null}.
         *
         * <p>Here rather than on {@link UpdateAdmin} because the join notice needs the live settings
         * and the admin interface deliberately does not carry them — a command surface that could
         * read remote config would be a second way to reach it.
         */
        public String joinNotice(HeimdallRuntime runtime) {
            return service == null ? null : service.joinNotice(settings(runtime));
        }
    }

    /**
     * The HTTP gateway, narrowed to the one call the updater makes.
     *
     * <p>The updater takes this rather than a {@link HeimdallApi} so its whole state machine is one
     * fake away from being testable, and so it cannot grow a second dependency on the API by
     * accident.
     */
    private static final class GatewayReleaseSource implements ReleaseSource {

        private final HeimdallApi api;

        GatewayReleaseSource(HeimdallApi api) {
            this.api = api;
        }

        @Override
        public CompletableFuture<PluginRelease> latestRelease() {
            return api.latestRelease();
        }

        @Override
        public long joinTimeoutMs() {
            // The update-check budget, not the login one. They differ by roughly twenty seconds at
            // the defaults because plugin/latest runs with a much longer per-attempt timeout, and
            // bounding this on the login budget abandons the request early — departure D16 on a
            // different endpoint.
            return api.settings().updateCheckJoinTimeoutMs();
        }
    }

    /** {@link UpdateService} as the admin tree wants to see it. */
    private static final class ServiceAdmin implements UpdateAdmin {

        private final UpdateService service;

        ServiceAdmin(UpdateService service) {
            this.service = service;
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String currentVersion() {
            return service.currentVersion();
        }

        @Override
        public boolean checkNow() {
            return service.checkNow();
        }

        @Override
        public String updateNow() {
            InstallOutcome outcome = service.updateNow();
            return outcome.message();
        }

        @Override
        public boolean isUpdateAvailable() {
            return service.isUpdateAvailable();
        }

        @Override
        public String latestVersion() {
            PluginRelease release = service.latestRelease();
            return release == null ? "" : Strings.trimToEmpty(release.version());
        }
    }
}
