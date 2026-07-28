package com.heimdall.core.wiring;

import com.heimdall.core.admin.AdminCommand;
import com.heimdall.core.admin.UpdateAdmin;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.PlayerHandle;
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
 * <h2>The checker runs even when nothing can be installed</h2>
 *
 * <p>A missing {@link UpdateInstaller} — a Bukkit loader that will not tell the plugin where its own
 * jar is — used to make this method return early before it subscribed to anything. That was three
 * dangling paths at once: the bot's {@code update} frame hit no handler and spun its full 120-second
 * timeout, and the periodic check and the admin join notice that v2 ran regardless never started.
 * So everything below is wired <strong>unconditionally</strong>. The service checks and notifies
 * with no installer; {@link UpdateService#updateNow()} answers "this platform cannot install
 * updates" rather than throwing; and the {@code update} frame is always answered — see
 * {@link RemoteUpdateHandler}.
 *
 * <h2>Where the settings come from, and why the dashboard cannot own them</h2>
 *
 * <p>{@code checkEnabled}, {@code notifyAdmins} and {@code checkIntervalHours} are read from
 * {@code bootstrap.yml}, not from remote config. v3 has no {@code updates} capability, so the bot's
 * {@code config.push} narrowing drops an {@code updates} section before it reaches the plugin — a
 * dashboard value there would be permanently unread. Local is therefore the only place an operator
 * can actually turn the check off, which v2 could. They are re-read from {@link HeimdallRuntime#bootstrap()}
 * on every tick and every join, so an operator who edits the file and runs {@code /hd reload} does
 * not have to restart.
 */
public final class UpdateWiring {

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
            final HeimdallRuntime runtime,
            UpdateInstaller installer) {
        // Built with whatever installer there is, including null. checkNow(), the periodic tick and
        // the join notice all work with no installer; only updateNow() needs one, and it answers
        // gracefully when there is none.
        final UpdateService service = new UpdateService(
                logger,
                currentVersion,
                new GatewayReleaseSource(runtime.api()),
                installer,
                installer == null ? null : new UpdateDownloader(logger, DownloadPolicy.github()),
                runtime.executors().scheduler());

        List<Registration> handles = new ArrayList<Registration>();
        handles.add(service.startPeriodicChecks(new Supplier<UpdateSettings>() {
            @Override
            public UpdateSettings get() {
                return settings(runtime);
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
        return tunnel.subscribe("update",
                new RemoteUpdateHandler(logger, service, new Replier() {
                    @Override
                    public void reply(String id, Payload payload) {
                        tunnel.reply(id, "update_result", payload);
                    }
                }),
                runtime.executors().io());
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
     * The settings currently in force, from {@code bootstrap.yml}.
     *
     * <p>Read at the point of use rather than held, so an operator who edits the file and reloads
     * does not have to restart. From the bootstrap rather than remote config because the dashboard
     * cannot deliver these — see the class note.
     */
    public static UpdateSettings settings(HeimdallRuntime runtime) {
        BootstrapConfig bootstrap = runtime.bootstrap();
        return new UpdateSettings(Payload.builder()
                .put("checkEnabled", bootstrap.updatesCheckEnabled())
                .put("notifyAdmins", bootstrap.updatesNotifyAdmins())
                .put("checkIntervalHours", bootstrap.updatesCheckIntervalHours())
                .build());
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

    /** How {@link RemoteUpdateHandler} sends its reply, so a test can watch it without a tunnel. */
    interface Replier {
        void reply(String id, Payload payload);
    }

    /**
     * Answers the dashboard's update button, and <strong>always</strong> answers it.
     *
     * <p>The bot sends a correlated {@code update} frame and waits for {@code update_result}; a client
     * that never replies leaves a spinner on somebody's screen until the bot's 120-second timeout
     * fires. So the reply sits in a {@code finally} under a {@code catch(Throwable)}: whatever
     * {@link UpdateService#updateNow()} does — succeed, fail, report no installer, or throw a bug
     * nobody expected — the frame is answered, with the same sentence {@code /hd update} would have
     * printed. Extracted from the subscription lambda specifically so a test can drive it with a fake
     * {@link Replier} and assert the reply was sent; neutering the {@code reply(...)} call now fails
     * that test rather than silently reintroducing the dangling-frame bug.
     *
     * <p>Runs on {@code heimdall-io}, never the socket's reading thread — {@code updateNow()}
     * downloads up to 50 MB, and doing that on the reading thread would let the bot's liveness sweep
     * reap a connection that is working (departure D27).
     */
    static final class RemoteUpdateHandler implements TunnelMessageHandler {

        private final HeimdallLogger logger;
        private final UpdateService service;
        private final Replier replier;

        RemoteUpdateHandler(HeimdallLogger logger, UpdateService service, Replier replier) {
            this.logger = logger;
            this.service = service;
            this.replier = replier;
        }

        @Override
        public void onMessage(Envelope envelope) {
            InstallOutcome outcome = InstallOutcome.failed("the update did not run");
            try {
                InstallOutcome ran = service.updateNow();
                outcome = ran == null ? InstallOutcome.failed("the update reported nothing") : ran;
            } catch (Throwable broken) {
                // updateNow is documented never to throw, so reaching here is a bug — but the frame
                // must still be answered, so this catches Throwable and falls into the finally.
                logger.error("a dashboard-triggered update threw", broken);
                outcome = InstallOutcome.failed("the update failed unexpectedly: " + broken);
            } finally {
                PluginRelease release = service.latestRelease();
                Payload payload = Payload.builder()
                        .put("success", outcome.installed())
                        .put("message", outcome.message())
                        .put("version", release == null ? "" : Strings.trimToEmpty(release.version()))
                        .build();
                try {
                    replier.reply(envelope.id(), payload);
                } catch (RuntimeException replyFailed) {
                    // The tunnel dropped between receiving the frame and answering it. Nothing to do
                    // but note it; the bot will time out, which is the honest outcome of a dead link.
                    logger.debug(() -> "could not reply to a dashboard update frame: " + replyFailed);
                }
            }
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
