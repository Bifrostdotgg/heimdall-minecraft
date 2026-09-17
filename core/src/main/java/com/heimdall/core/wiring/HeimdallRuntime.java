package com.heimdall.core.wiring;

import com.heimdall.core.concurrent.HeimdallExecutors;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.http.ApiClient;
import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.http.ClaimClient;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.identity.InstanceFingerprint;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.HealthModule;
import com.heimdall.core.module.ModuleEnvironment;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.pipeline.CommandPipeline;
import com.heimdall.core.pipeline.LoginPipeline;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.remoteconfig.ConfigDocument;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.RemoteConfig;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.tunnel.TunnelSettings;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Everything the plugin is, assembled once, in an order that has to be right.
 *
 * <h2>Why this is in core rather than in each platform module</h2>
 *
 * <p>v2's two entry points were 1,086 and 1,311 lines, and they were <em>different</em> — the
 * Velocity one had fixes the Paper one never got, in code that was meant to do the same thing. Wiring
 * is exactly the kind of work where duplication is invisible: both files compile, both boot, and the
 * divergence only shows up as a bug that reproduces on one platform.
 *
 * <p>None of the assembly needs a server. It needs a {@link PlatformFacade}, which is the seam that
 * exists precisely so this code does not. So it lives here, is written once, and the platform
 * entry points shrink to "build a facade, hand it over, start".
 *
 * <h2>Not configured is a first-class state, and the objects exist anyway</h2>
 *
 * <p>A fresh install has no {@code bootstrap.yml}. That is the normal case, not a failure: the
 * plugin still enables, still registers its commands, and still loads its modules on their defaults
 * — it simply does not dial the bot, and says so once.
 *
 * <p><strong>What changed in 1e is that "not configured" no longer means "the collaborators are
 * null".</strong> The {@link ApiClient}, the {@link HeimdallApi} gateway over it and the
 * {@link TunnelClient} are all built on every boot, configured or not, and
 * {@link #applySetup(BootstrapConfig)} reconfigures them <em>in place</em> when {@code /hd setup}
 * lands. That is what makes setup work without a restart, and it closes three separate versions of
 * the same bug:
 *
 * <ul>
 *   <li>a module captured a {@code null} {@code ApiClient} at registration and held it forever
 *       (departure D56), so {@code /offend} refused on a server that was demonstrably connected;
 *   <li>a module's tunnel subscriptions went to the offline no-op bus and were never re-made
 *       against the real one, so a freshly claimed server received role syncs nothing was listening
 *       for;
 *   <li>{@code TunnelSpiService} captured the same {@code null} bus at enable, so a third-party
 *       plugin's SPI stayed dead until a restart (the 1c TODO known as N7).
 * </ul>
 *
 * <p>None of those needed three fixes. They needed the objects to be stable and their
 * <em>settings</em> to be what moves — which is exactly what guild discovery had always done, and
 * exactly why guild discovery never had this problem.
 *
 * <h2>Construction and start are separate</h2>
 *
 * <p>{@link Builder#build()} assembles; {@link #start()} sets things in motion. The gap is where
 * modules get registered — {@link #modules()} is live before anything is running, so a platform can
 * register its module set without the manager having to accept a registry it did not build.
 *
 * <h2>Shutdown order is the reverse, and the executors go last</h2>
 *
 * <p>Modules stop, then the tunnel, then the pools. Shutting the pools down first would strand every
 * in-flight disable on a rejected task, and the symptom — a mirror that did not flush — appears one
 * boot later as data loss.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #applySetup}, {@link #reload()} and the private guild adoption are serialised on one
 * lock: all three re-point the same settings and the same discovery handle, and two of them
 * genuinely can be concurrent (a command handler on a server thread, and guild discovery completing
 * on {@code heimdall-io}).
 *
 * <p><strong>{@link #close()} deliberately does not take that lock.</strong> It shuts the pools
 * down, and that <em>waits</em> for {@code heimdall-io} to drain — so a guild adoption blocked on a
 * lock this method holds would sit there until the grace period expired and then be interrupted,
 * turning a clean shutdown into fifteen seconds of hanging and three severe lines. That is the
 * deadlock class the connected smoke scenario already caught once. Teardown reads a volatile
 * {@code closed} flag instead, and every reconfiguration step re-checks it before touching the
 * tunnel.
 */
public final class HeimdallRuntime implements AutoCloseable {

    private final HeimdallLogger logger;
    private final PlatformFacade platform;
    private final BootstrapStore bootstrapStore;
    private final IdentitySource identitySource;

    /**
     * What is on disk right now.
     *
     * <p>Volatile and no longer final: {@link #applySetup} replaces it, and a status command on a
     * server thread reads it.
     */
    private volatile BootstrapConfig bootstrap;

    /**
     * The guild in force. Volatile because guild discovery writes it from {@code heimdall-io} while
     * whatever asked is on a server thread.
     */
    private volatile String guildId;

    private final HeimdallExecutors executors;
    private final RemoteConfig remoteConfig;
    private final LoginPipeline loginPipeline;
    private final ChatPipeline chatPipeline;
    private final CommandPipeline commandPipeline;
    private final PlayerSessionEvents playerSessions;
    private final ModuleManager modules;

    /** Built on every boot, configured or not. Re-pointed in place; never replaced. */
    private final ApiClient apiClient;

    /** The gateway modules hold. One per plugin, for the life of the plugin. See departure D56. */
    private final HeimdallApi api;

    /** Built on every boot, configured or not; dials only once it has settings that can. */
    private final TunnelClient tunnel;

    /** Built on demand by {@code /hd setup}, which is the only thing that claims a code. */
    private volatile ClaimClient claimClient;

    /**
     * The current attempt to resolve this token's guild, or {@code null} when there is nothing to
     * resolve with. Replaced by {@link #applySetup}, because a resolved or closed one cannot be
     * restarted.
     */
    private volatile GuildDiscovery guildDiscovery;

    /**
     * Which machine this process is, worked out once at construction.
     *
     * <p>Not re-derived per check: nothing in it can change while the JVM is up, and a value that
     * moved under a running server would turn the guard into a source of flapping rather than a
     * defence against it.
     */
    private final InstanceFingerprint fingerprint;

    /**
     * The last identity decision. Volatile because a status command on a server thread reads what
     * {@link #start()} or a reload wrote.
     */
    private volatile IdentityGuard.Decision identity;

    /** The repeating mismatch warning, or {@code null} when there is nothing to complain about. */
    private volatile ScheduledFuture<?> identityWarning;

    /**
     * The root command this build registered: {@code hd} on the Bukkit family, {@code hdp} on the
     * proxies.
     *
     * <p>Held here only so the runtime's own console lines can name a command the operator actually
     * has. Everything a subcommand prints uses {@code AdminContext#label()} instead, which is the
     * same string arriving by the shorter route.
     */
    private final String commandLabel;

    /** Everything {@link #start()} registered, closed in reverse on the way out. */
    private final List<Registration> registrations = new ArrayList<Registration>();

    /**
     * Serialises the three paths that re-point the client and the tunnel.
     *
     * <p>A dedicated object rather than {@code this}, so {@link #close()} can be written without it
     * — see the threading note on the class. Nothing that waits on a pool may ever hold this.
     */
    private final Object reconfigureLock = new Object();

    private volatile boolean started;
    private volatile boolean closed;

    private HeimdallRuntime(Builder builder) {
        this.logger = builder.logger;
        this.platform = builder.platform;
        this.bootstrapStore = builder.bootstrapStore;
        this.commandLabel = Strings.isBlank(builder.commandLabel)
                ? "hd" : builder.commandLabel.trim();
        this.fingerprint = builder.instanceFingerprint == null
                ? InstanceFingerprint.detect(System.getenv(), builder.platform.dataDirectory())
                : builder.instanceFingerprint;
        // Wrapped rather than replaced, so the handshake carries the fingerprint's digest without
        // three platform identity sources having to know the check exists. A platform that supplied
        // no source still supplies none: "unknown server software" is a state the status command
        // reports, and inventing an identity here would hide it.
        this.identitySource = builder.identitySource == null
                ? null
                : new FingerprintedIdentity(builder.identitySource, fingerprint.digest());
        this.bootstrap = bootstrapStore.load();
        // Explicit beats cached beats nothing. The cached value is what a restart during a bot
        // outage runs on — see BootstrapConfig#guildId — and is overwritten by whatever `identify`
        // next answers.
        String supplied = builder.guildId == null ? "" : builder.guildId.trim();
        this.guildId = supplied.isEmpty() ? bootstrap.guildId() : supplied;

        logger.setDebugEnabled(bootstrap.debug());
        // Msg is static, and every template render is several layers below anything holding a
        // logger. This is the one place that has both, so it is where the "that screen would not
        // parse" warning gets somewhere to go.
        Msg.diagnostics(logger);

        this.executors = builder.executors == null
                ? new HeimdallExecutors(logger)
                : builder.executors;
        this.loginPipeline = new LoginPipeline(logger);
        this.chatPipeline = new ChatPipeline(logger);
        this.commandPipeline = new CommandPipeline(logger);

        Path cachePath = platform.dataDirectory().resolve("config-cache.json");
        this.remoteConfig = new RemoteConfig(logger, cachePath, builtInDefaults());

        this.apiClient = buildApiClient(builder);
        this.api = new HeimdallApi(apiClient);
        // Built unconditionally, with whatever settings there are. TunnelClient tolerates settings
        // it cannot connect with — connect() says so and returns — and building it here is what
        // gives every module a subscription registry that survives the server being set up
        // underneath it.
        this.tunnel = buildTunnel(builder);
        this.guildDiscovery = bootstrap.isConfigured() ? buildGuildDiscovery() : null;

        this.playerSessions = new PlayerSessionEvents(logger, executors.io());

        this.modules = new ModuleManager(ModuleEnvironment.builder()
                .logger(logger)
                .executors(executors)
                .api(api)
                .tunnel(tunnel)
                .remoteConfig(remoteConfig)
                .loginPipeline(loginPipeline)
                .chatPipeline(chatPipeline)
                .commandPipeline(commandPipeline)
                .platform(platform)
                .playerSessions(playerSessions)
                .build());

        // Core's own module, registered here rather than in HeimdallModules: health is emitted by
        // the tunnel heartbeat, so core is the only place that can own it, and core must not depend
        // on the feature modules. Registered in the constructor — not in start() — because the
        // declared capability set is about what is REGISTERED (departure D55), and a platform
        // registers its modules in the gap between build() and start().
        this.modules.register(new HealthModule(tunnel, reportsVanish(platform)));

        // Set after the manager exists: the dependency genuinely runs both ways — the manager hands
        // each module a bus backed by the client, and the client asks the manager what to declare.
        // See CapabilitySource.
        tunnel.setCapabilitySource(modules);

        // Decided here so a status command asked before start() gets an answer rather than null.
        // Deliberately without the side effects: nothing is written and nothing is logged until
        // start() runs the same evaluation through applyIdentityGuard().
        this.identity = IdentityGuard.evaluate(bootstrap, fingerprint);
    }

    /**
     * Whether this platform can see vanish state, asked once at construction.
     *
     * <p>It decides whether {@code identify} declares {@code vanish@1}, and a capability set is a
     * snapshot the handshake reads: asking again later could only produce a jar that declares
     * something different on its second connection than on its first. The answer is a property of the
     * platform binding rather than of anything configurable, so once is right.
     *
     * <p>A directory that is missing or throws answers "no". Over-declaring is the expensive
     * direction: it tells the bot that a row with no {@code vanished} key is a player everyone can
     * see, which is exactly the mistake that would publish a hidden staff member.
     */
    private static boolean reportsVanish(PlatformFacade platform) {
        try {
            PlayerDirectory players = platform.players();
            return players != null && players.reportsVanish();
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    /**
     * The configuration this plugin runs on when nothing upstream has said otherwise.
     *
     * <p>The bottom layer of {@code RemoteConfig}'s "live push &gt; disk cache &gt; built-in
     * defaults" overlay, and the reason it is no longer empty is departure D69: {@code health} must
     * default to <strong>on</strong>. A module entry parsed from a document defaults to
     * {@code enabled: false} — the right answer for a module the bot declined to mention — but health
     * was sent unconditionally by v2 and by every v3 build before the module existed, so inheriting
     * that default would silently stop the dashboard's TPS chart on every server that has not
     * received a push yet: a fresh install, a server whose bot is unreachable, one running in
     * v2-compat, or one that was never registered.
     *
     * <p>Because the layers overlay rather than replace, an explicit {@code health: {enabled: false}}
     * from the dashboard still wins — which is the entire point of making the row a working toggle.
     */
    private static ConfigDocument builtInDefaults() {
        Map<String, ModuleConfig> defaults = new LinkedHashMap<String, ModuleConfig>();
        defaults.put(HealthModule.ID, ModuleConfig.of(true, Payload.empty()));
        return ConfigDocument.of(ConfigDocument.UNVERSIONED, defaults, Payload.empty());
    }

    public static Builder builder(HeimdallLogger logger, PlatformFacade platform) {
        return new Builder(logger, platform);
    }

    private ApiClient buildApiClient(Builder builder) {
        ApiClient client = new ApiClient(
                logger,
                ApiSettingsFactory.fromBootstrap(bootstrap, guildId).build(),
                executors.io());
        if (builder.bedrockIdentityProvider != null) {
            client.setBedrockIdentityProvider(builder.bedrockIdentityProvider);
        }
        return client;
    }

    /**
     * Discovery runs whenever there are credentials to run it with — cached guild or not.
     *
     * <p>The cache is provisional. Skipping the ask because a value exists is the sticky-wrong-guild
     * trap {@link GuildDiscovery} describes: a token moved between guilds, re-issued bot-side, or a
     * {@code bootstrap.yml} copied to a second server leaves the cached value permanently wrong, and
     * a plugin that never re-asks signs perfectly valid requests against somebody else's guild.
     */
    private GuildDiscovery buildGuildDiscovery() {
        return new GuildDiscovery(logger, apiClient, executors.scheduler(),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String resolved) {
                        adoptGuild(resolved);
                    }
                });
    }

    private TunnelClient buildTunnel(Builder builder) {
        return TunnelClient.builder(logger, executors)
                .settings(tunnelSettings(bootstrap, guildId))
                .identitySource(identitySource)
                .healthSource(builder.healthSource)
                .configPushHandler(remoteConfig)
                .build();
    }

    private static TunnelSettings tunnelSettings(BootstrapConfig config, String guild) {
        return TunnelSettings.builder()
                .endpoint(config.endpoint())
                .guildId(guild)
                .serverId(config.serverId())
                .apiKey(config.token())
                .build();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Loads cached configuration, starts the modules, and dials the bot if there is one to dial.
     *
     * <p>Idempotent: a second call does nothing. Never throws for a configuration problem — the
     * whole point of the not-configured state is that the plugin boots far enough to explain
     * itself.
     */
    public void start() {
        if (started || closed) {
            return;
        }
        started = true;

        // Before anything else: the plugin should be configured from its first moment rather than
        // from whenever the bot answers, and a module enabled below reads its settings immediately.
        remoteConfig.loadFromCache();

        registrations.add(tunnel.onModeChange(remoteConfig));
        registrations.add(modules.followRemoteConfig());
        // The dashboard's three direct questions — the roster, the console box, the mod probe.
        // Subscribed unconditionally and BEFORE the not-configured return below: a subscription
        // lives on the client rather than on a socket, so it survives every reconnect and is
        // already in place when /hd setup brings a tunnel up without a restart. Not a module,
        // because none of the three is a feature a guild opts into — see RemoteRequestWiring.
        registrations.add(RemoteRequestWiring.install(
                logger, platform, tunnel, executors.io(), executors.scheduler()));
        // Applies bootstrap.yml's local-disable set AND does the first reconcile against it, so a
        // module an operator switched off locally is never even started, whatever the cached or
        // pushed config says.
        modules.setLocallyDisabled(parseModuleIds(bootstrap.disabledModules()));

        if (!bootstrap.isConfigured()) {
            logger.info("not set up yet: run /" + commandLabel + " setup <code> to connect this "
                    + "server to Discord (see " + bootstrapStore.file() + ")");
            return;
        }
        // Before dial(), because the whole point is to not dial. A blocked server keeps its
        // commands, its modules and its local whitelist: it just stays off the tunnel that its twin
        // is already on.
        if (applyIdentityGuard().blocksTunnel()) {
            return;
        }
        dial();
    }

    /**
     * Starts discovery and, if there is a guild to dial with, the tunnel.
     *
     * <p>Shared by {@link #start()} and {@link #applySetup}, because the two want exactly the same
     * thing to happen and the second used to be reachable only by restarting the server.
     */
    private void dial() {
        GuildDiscovery discovery = guildDiscovery;
        if (discovery != null) {
            if (Strings.isBlank(guildId)) {
                logger.info("discovering which guild this server's token belongs to; the tunnel "
                        + "stays idle until it answers");
            } else {
                logger.debug(() -> "dialling on the cached guild " + guildId
                        + " and confirming it with the bot in the background");
            }
            discovery.start();
        }

        if (Strings.isBlank(guildId)) {
            // Nothing to dial with yet. Discovery above is what changes that.
            return;
        }
        if (!tunnel.settings().isConfigured()) {
            // A guild we have, but something else the tunnel needs is missing. Not the discovering
            // state, and not something retrying would fix.
            logger.info("tunnel idle: this server has a guild but incomplete tunnel settings");
            return;
        }
        tunnel.connect();
    }

    /**
     * Adopts the credentials a setup code was just exchanged for, without a restart.
     *
     * <p>The whole of {@code /hd setup}'s second half, and the reason the collaborators above are
     * built unconditionally. In order: persist, re-point the HTTP client, re-point the tunnel,
     * confirm the guild, dial. Nothing is replaced and nothing is re-registered, so every module,
     * every tunnel subscription and the public SPI are all still pointing at the objects that just
     * became usable.
     *
     * <p>The file is written <strong>first</strong>, and a failure to write it aborts the whole
     * thing. A server that connects but did not persist its token is one that silently reverts to
     * unconfigured on its next restart, having consumed a single-use setup code that cannot be
     * claimed again — the operator would have to notice by themselves, weeks later.
     *
     * @param updated what to write; must be {@link BootstrapConfig#isConfigured()}
     * @throws IOException if {@code bootstrap.yml} could not be written, in which case nothing else
     *     has changed
     */
    public void applySetup(BootstrapConfig updated) throws IOException {
        synchronized (reconfigureLock) {
            applySetupLocked(updated);
        }
    }

    private void applySetupLocked(BootstrapConfig updated) throws IOException {
        if (updated == null || !updated.isConfigured()) {
            throw new IllegalArgumentException("setup needs an endpoint and credentials");
        }
        if (closed) {
            throw new IllegalStateException("this runtime has been shut down");
        }
        // Setup binds. These credentials are new, so whatever machine is claiming them is the one
        // they belong to, and a server that was blocked a moment ago is unblocked by having been set
        // up again here rather than by anything else having to notice.
        BootstrapConfig bound =
                updated.toBuilder().instanceFingerprint(fingerprint.value()).build();
        bootstrapStore.save(bound);
        this.bootstrap = bound;
        this.guildId = bound.guildId();
        logger.setDebugEnabled(bound.debug());
        this.identity = IdentityGuard.evaluate(bound, fingerprint);
        cancelIdentityWarning();

        apiClient.reconfigure(ApiSettingsFactory.fromBootstrap(bound, guildId).build());
        tunnel.applySettings(tunnelSettings(bound, guildId));

        // A resolved or closed discovery cannot be restarted, and the token has just changed, so
        // whatever the old one concluded is about a credential this server no longer uses.
        GuildDiscovery previous = guildDiscovery;
        if (previous != null) {
            previous.close();
        }
        this.guildDiscovery = buildGuildDiscovery();

        // Only after everything is pointed at the new bot. A module reading its config during the
        // reconcile below must not see a half-applied setup. Goes through the local-disable filter,
        // which setup does not change but must not drop.
        modules.setLocallyDisabled(parseModuleIds(bootstrap.disabledModules()));
        dial();
    }

    /**
     * Writes a bootstrap that changes nothing about how the bot is reached.
     *
     * <p>{@code /hd debug on} is the only caller, and the restriction is the point: this does
     * <strong>not</strong> re-point the HTTP client, the tunnel or guild discovery, so handing it
     * changed credentials would produce a file that disagrees with the running process until the
     * next restart. Anything that touches the endpoint, the token or the server id goes through
     * {@link #applySetup} instead.
     *
     * @throws IOException if the file could not be written, in which case the in-memory config is
     *     left alone too
     */
    public void persist(BootstrapConfig updated) throws IOException {
        if (updated == null) {
            throw new IllegalArgumentException("a config is required");
        }
        synchronized (reconfigureLock) {
            bootstrapStore.save(updated);
            this.bootstrap = updated;
        }
    }

    /**
     * Switches a module off, or back on, locally — the offline escape hatch (departure D66).
     *
     * <p>Persists the change to {@code bootstrap.yml} and reconciles immediately, so it takes effect
     * now and survives a restart. A locally-disabled module stays off even while the tunnel is up and
     * the dashboard says on, until it is enabled again here — which is the whole point: it is the one
     * lever an operator has when the bot is unreachable, and "I turned it off here" has to win.
     *
     * @param disabled {@code true} to disable, {@code false} to clear the local override
     * @return the module ids now disabled locally
     * @throws IOException if the change could not be written; the in-memory state is left unchanged
     */
    public Set<String> setModuleLocallyDisabled(String moduleId, boolean disabled) throws IOException {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            throw new IllegalArgumentException("a module id is required");
        }
        synchronized (reconfigureLock) {
            Set<String> current = new LinkedHashSet<String>(parseModuleIds(bootstrap.disabledModules()));
            if (disabled) {
                current.add(moduleId.trim());
            } else {
                current.remove(moduleId.trim());
            }
            BootstrapConfig updated =
                    bootstrap.toBuilder().disabledModules(String.join(" ", current)).build();
            bootstrapStore.save(updated);
            this.bootstrap = updated;
            modules.setLocallyDisabled(current);
            return current;
        }
    }

    /** The module ids currently switched off locally. */
    public Set<String> locallyDisabledModules() {
        return modules.locallyDisabled();
    }

    private static Set<String> parseModuleIds(String spaceSeparated) {
        Set<String> ids = new LinkedHashSet<String>();
        if (spaceSeparated == null) {
            return ids;
        }
        for (String token : spaceSeparated.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                ids.add(token);
            }
        }
        return ids;
    }

    /**
     * Turns debug logging on or off for every thread, immediately.
     *
     * <p>Separate from {@link #persist} so the toggle takes effect even when the file cannot be
     * written: an operator debugging a server whose data directory is read-only is exactly the
     * person who needs the flag, and refusing it because the <em>persistence</em> failed would be
     * the wrong half to drop.
     */
    public void setDebugLogging(boolean enabled) {
        logger.setDebugEnabled(enabled);
    }

    /**
     * Re-reads {@code bootstrap.yml} and the config cache, and rebuilds the tunnel in place.
     *
     * <p>What {@code /hd reload} calls. "In place" is the whole contract, and it is v2's reload bug
     * class stated as a requirement: v2 rebuilt its WebSocket client, which orphaned the previous
     * one's scheduler and selector thread and silently dropped the message-handler wiring, so a
     * server reloaded a few times was a server with several half-live sockets and no role sync.
     * {@link TunnelClient#reconnect(String)} reuses the instance, its executors and its
     * subscriptions.
     *
     * <p>Credentials that changed on disk are applied; credentials that did not are left alone, so a
     * reload on a working server is a reconnect rather than a re-authentication.
     *
     * @return a short line describing what it did, for the command that asked
     */
    public String reload() {
        synchronized (reconfigureLock) {
            return reloadLocked();
        }
    }

    private String reloadLocked() {
        if (closed) {
            return "this server is shutting down";
        }
        BootstrapConfig onDisk = bootstrapStore.load();
        boolean credentialsChanged = !onDisk.endpoint().equals(bootstrap.endpoint())
                || !onDisk.token().equals(bootstrap.token())
                || !onDisk.tokenId().equals(bootstrap.tokenId())
                || !onDisk.serverId().equals(bootstrap.serverId());

        this.bootstrap = onDisk;
        logger.setDebugEnabled(onDisk.debug());

        // The cache and the live document are the same thing on a connected server — every push is
        // written through — so this only ever does something for a hand-edited cache or a server
        // that has not connected yet. Harmless in the common case, and the only way to pick up the
        // uncommon one without a restart.
        remoteConfig.loadFromCache();
        // Re-reads the local-disable set from the file the operator may have just edited, and
        // reconciles against it.
        modules.setLocallyDisabled(parseModuleIds(onDisk.disabledModules()));

        if (!onDisk.isConfigured()) {
            cancelIdentityWarning();
            this.identity = IdentityGuard.evaluate(onDisk, fingerprint);
            return "re-read " + bootstrapStore.file() + "; this server is still not set up";
        }

        // Re-decided on every reload, in both directions: an operator who fixes the file gets their
        // tunnel back below without a restart, and one who edits identityCheck to strict on a
        // mismatched server loses it here.
        boolean wasBlocked = identity != null && identity.blocksTunnel();
        IdentityGuard.Decision decision = applyIdentityGuard();
        if (decision.blocksTunnel()) {
            // Down BEFORE the settings are applied below. A tunnel that was already up has to come
            // down anyway, and doing it first is what stops applySettings finding a live socket and
            // reconnecting it onto the very credentials this decision has just refused.
            tunnel.disconnect();
        }

        // Applied whatever the decision was. These two calls dial nothing on their own on a
        // blocked reload: both objects are re-pointed in place, and the tunnel only reconnects
        // here if it finds a live socket, which the disconnect above has just torn down.
        // Returning before them left the file consumed but unapplied, so a later `identity adopt`
        // dialled with the token from before the edit and nothing said why.
        apiClient.reconfigure(ApiSettingsFactory.fromBootstrap(onDisk, guildId).build());
        tunnel.applySettings(tunnelSettings(onDisk, guildId));

        if (decision.blocksTunnel()) {
            // Again, because a connect that was already in flight can publish its socket between
            // the disconnect above and applySettings, which then schedules a fresh reconnect that
            // no staleness check would refuse.
            tunnel.disconnect();
            return "re-read " + bootstrapStore.file() + "; identity mismatch, so the tunnel is "
                    + "stopped. Run /" + commandLabel + " identity to see both fingerprints and how "
                    + "to fix it.";
        }

        if (credentialsChanged) {
            GuildDiscovery previous = guildDiscovery;
            if (previous != null) {
                previous.close();
            }
            this.guildDiscovery = buildGuildDiscovery();
            this.guildId = onDisk.guildId();
            dial();
            return "re-read " + bootstrapStore.file()
                    + "; credentials changed, so the guild is being resolved again";
        }
        if (wasBlocked) {
            // The block was total: start() returned before dial(), so on this boot discovery has
            // never been started and the tunnel has never been dialled. None of the branches below
            // would notice - a resolved-looking discovery reports nothing to do, and a cached guild
            // makes the discovery callback a no-op - so the server would sit there, unblocked and
            // still disconnected, until somebody restarted it.
            dial();
            return "re-read " + bootstrapStore.file() + "; the identity now matches, connecting";
        }
        GuildDiscovery discovery = guildDiscovery;
        if (discovery != null && !discovery.isResolved()) {
            // Still discovering. Reconnecting on a guild we do not have would only log a refusal.
            discovery.start();
            return "re-read configuration; still waiting for the bot to name this token's guild";
        }
        tunnel.reconnect(guildId);
        return "re-read configuration and reconnected the tunnel";
    }

    // ── Instance identity ────────────────────────────────────────────────────

    /**
     * Re-decides the identity question and does whatever the answer requires.
     *
     * <p>The side-effecting half of {@link IdentityGuard}: it records a first fingerprint, logs a
     * mismatch, arms or cancels the repeating warning, and reports back so the caller can decide
     * whether to dial. Callers already holding {@link #reconfigureLock} are fine, the lock is
     * reentrant.
     *
     * @return the decision, whose {@link IdentityGuard.Decision#blocksTunnel()} the caller must obey
     */
    private IdentityGuard.Decision applyIdentityGuard() {
        synchronized (reconfigureLock) {
            IdentityGuard.Decision decision = IdentityGuard.evaluate(bootstrap, fingerprint);
            this.identity = decision;
            switch (decision.state()) {
                case ADOPTED:
                    bindFingerprint();
                    logger.info("bound server identity " + shortServerId(bootstrap.serverId())
                            + " to this instance: " + fingerprint.value());
                    // Re-decided from what was just recorded. ADOPTED describes the moment, not the
                    // state: leaving it in place made `/hd identity` report "bound to this instance
                    // just now" with an empty recorded value for the rest of the boot, and made
                    // `adopt` offer to do work that was already done.
                    decision = IdentityGuard.evaluate(bootstrap, fingerprint);
                    this.identity = decision;
                    break;
                case DISABLED:
                    logger.debug(() -> "identity check is off; connecting without comparing "
                            + "this instance (" + fingerprint.value() + ") with the recorded one");
                    break;
                case MISMATCH_BLOCKED:
                case MISMATCH_ADVISORY:
                    logger.warn(decision.warning(commandLabel));
                    armIdentityWarning();
                    return decision;
                default:
                    logger.debug(() -> "this instance matches the identity in "
                            + bootstrapStore.file() + ": " + fingerprint.value());
                    break;
            }
            cancelIdentityWarning();
            return decision;
        }
    }

    /**
     * Records the current fingerprint, best-effort.
     *
     * <p>Persisting is allowed to fail. A read-only data directory costs one adopt line per boot,
     * the same trade {@link #adoptGuildLocked} makes for the guild cache, and refusing to connect
     * over it would turn a permissions problem into an outage. The in-memory config is updated
     * either way, so the rest of this boot behaves as bound.
     */
    private void bindFingerprint() {
        BootstrapConfig updated =
                bootstrap.toBuilder().instanceFingerprint(fingerprint.value()).build();
        try {
            bootstrapStore.save(updated);
        } catch (IOException | RuntimeException notPersisted) {
            logger.warn("could not record this instance in " + bootstrapStore.file()
                    + "; the identity check will bind again on the next boot: " + notPersisted);
        }
        this.bootstrap = updated;
    }

    /**
     * Binds these credentials to this machine, on an operator's say-so, and dials if that unblocked
     * anything.
     *
     * <p>What {@code /hd identity adopt} calls. The answer to "I moved this server". It is not the
     * answer to "I copied this server", and the command says so before it runs.
     *
     * @return the decision after adopting, which is
     *     {@link IdentityGuard.State#BOUND} unless the check is off
     * @throws IOException if {@code bootstrap.yml} could not be written, in which case nothing has
     *     changed on disk and the old binding still stands
     */
    public IdentityGuard.Decision adoptInstanceIdentity() throws IOException {
        synchronized (reconfigureLock) {
            if (closed) {
                throw new IllegalStateException("this runtime has been shut down");
            }
            boolean wasBlocked = identity != null && identity.blocksTunnel();
            BootstrapConfig updated =
                    bootstrap.toBuilder().instanceFingerprint(fingerprint.value()).build();
            bootstrapStore.save(updated);
            this.bootstrap = updated;
            logger.info("bound server identity " + shortServerId(updated.serverId())
                    + " to this instance: " + fingerprint.value());

            IdentityGuard.Decision decision = IdentityGuard.evaluate(updated, fingerprint);
            this.identity = decision;
            cancelIdentityWarning();
            if (wasBlocked && !decision.blocksTunnel() && updated.isConfigured() && started) {
                dial();
            }
            return decision;
        }
    }

    /**
     * Throws these credentials away: token, server id, cached guild and binding.
     *
     * <p>What {@code /hd identity reset confirm} calls, and the answer to "this directory is a copy
     * of another server". The endpoint, the role, the debug flag and the operational knobs are
     * kept, because they are settings rather than identity and retyping them is nobody's idea of a
     * remedy.
     *
     * <p>The tunnel is disconnected rather than shut down: this server is expected to be set up
     * again in the next minute, and {@link TunnelClient#shutdown()} latches for good.
     *
     * @throws IOException if {@code bootstrap.yml} could not be written, in which case nothing has
     *     changed and this server is still using the credentials it had
     */
    public void resetIdentity() throws IOException {
        synchronized (reconfigureLock) {
            if (closed) {
                throw new IllegalStateException("this runtime has been shut down");
            }
            if (!bootstrap.isConfigured()) {
                // Nothing to clear. Writing the file anyway would create a bootstrap.yml on a server
                // that has never had one, and warning that credentials were cleared would be a
                // sentence about something that did not happen.
                logger.debug("nothing to reset: this server is not set up");
                return;
            }
            BootstrapConfig cleared = bootstrap.toBuilder()
                    .tokenId("")
                    .token("")
                    .serverId("")
                    .guildId("")
                    .instanceFingerprint("")
                    .build();
            bootstrapStore.save(cleared);
            this.bootstrap = cleared;
            this.guildId = "";

            GuildDiscovery previous = guildDiscovery;
            if (previous != null) {
                previous.close();
            }
            this.guildDiscovery = null;

            // Re-pointed at the empty config so nothing that already holds one of these keeps using
            // the token that was just given up. ApiClient and TunnelClient both tolerate settings
            // they cannot connect with, which is the same state a fresh install is in.
            apiClient.reconfigure(ApiSettingsFactory.fromBootstrap(cleared, "").build());
            tunnel.applySettings(tunnelSettings(cleared, ""));
            tunnel.disconnect();

            // The cache is the PREVIOUS guild's answer. Left in place, a server claimed by a
            // different guild runs on it until that guild's first push arrives, and reads it back
            // off disk on every restart in between - which is the copied-directory failure again,
            // one layer down.
            remoteConfig.clearCache();

            cancelIdentityWarning();
            this.identity = IdentityGuard.evaluate(cleared, fingerprint);
            logger.warn("this server's credentials have been cleared from " + bootstrapStore.file()
                    + "; it is no longer connected to Discord. Run /" + commandLabel
                    + " setup <code> to claim a new one.");
        }
    }

    /**
     * Arms the repeating mismatch warning, if it is not already armed.
     *
     * <p>On the shared scheduler rather than a pool of its own: one warning every fifteen minutes
     * does not justify a thread, and the ArchUnit rules would rightly refuse an anonymous one.
     */
    private void armIdentityWarning() {
        ScheduledFuture<?> armed = identityWarning;
        if (armed != null && !armed.isCancelled() && !armed.isDone()) {
            return;
        }
        try {
            identityWarning = executors.scheduler().scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    repeatIdentityWarning();
                }
            }, IdentityGuard.WARN_INTERVAL_MS, IdentityGuard.WARN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            logger.debug("not repeating the identity warning: the scheduler is shutting down");
        }
    }

    /**
     * One repeat, on the scheduler thread.
     *
     * <p>The decision is read off the volatile field without the lock, because the only thing this
     * does with a still-mismatched one is log it, and logging holds no lock the reconfigure path
     * could be waiting on. The lock is taken only to cancel, since every other arm and cancel is
     * made under it: without that a repeat deciding "there is nothing to warn about any more"
     * could cancel a task some other thread had armed a microsecond earlier, and the mismatch
     * would go quiet while still being true. Safe to block for the cancel - nothing that holds the
     * lock waits on this pool.
     */
    private void repeatIdentityWarning() {
        IdentityGuard.Decision decision = identity;
        if (closed || decision == null || !decision.isMismatch()) {
            synchronized (reconfigureLock) {
                cancelIdentityWarning();
            }
            return;
        }
        logger.warn(decision.warning(commandLabel));
    }

    /**
     * Cancels the repeat, if the one in the field is still the one this call is about.
     *
     * <p>The compare-and-clear matters for {@link #close()}, which deliberately runs without the
     * lock: clearing the field unconditionally would let a shutdown racing a re-arm cancel the new
     * task and leave the field naming nothing.
     */
    private void cancelIdentityWarning() {
        ScheduledFuture<?> armed = identityWarning;
        if (armed == null) {
            return;
        }
        armed.cancel(false);
        if (identityWarning == armed) {
            identityWarning = null;
        }
    }

    /** Enough of a server id to recognise in a log line, without wrapping it. */
    private static String shortServerId(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return "<unset>";
        }
        return serverId.length() <= 12 ? serverId : serverId.substring(0, 12) + "...";
    }

    /**
     * Adopts a freshly discovered guild: HTTP client, disk cache, then the tunnel.
     *
     * <p>Runs on {@code heimdall-io}, from {@link GuildDiscovery}'s completion.
     *
     * <p>The order is deliberate. The API client is re-pointed first because a login arriving
     * during this must use the new guild or none; the bootstrap file is written next so a restart
     * in the following second does not have to ask again; and the tunnel is dialled last, because
     * it is the only step whose failure is retried by something other than this method.
     *
     * <p>Persisting is best-effort. A read-only data directory costs one {@code identify} per boot,
     * which is a great deal better than refusing to connect.
     */
    // Package-private rather than private: the guard's test drives this directly, because the only
    // production caller is a callback on a thread a unit test cannot schedule deterministically.
    void adoptGuild(String resolved) {
        synchronized (reconfigureLock) {
            adoptGuildLocked(resolved);
        }
    }

    private void adoptGuildLocked(String resolved) {
        if (resolved.equals(guildId)) {
            // The overwhelmingly common case once a server has booted once: the cache was right.
            // Returning here is what keeps confirm-on-every-boot cheap — no file write, and above
            // all no reconnect, which would drop a tunnel that is already up and working.
            logger.debug(() -> "the bot confirmed the cached guild " + resolved);
            return;
        }
        boolean wasProvisional = Strings.isNotBlank(guildId);
        if (wasProvisional) {
            // Loud, because it means this server has been talking to the wrong guild — reading its
            // config and reporting its logins there — for as long as the cache has been wrong.
            logger.warn("this server's token belongs to guild " + resolved + ", not the cached "
                    + guildId + "; switching over. If that is a surprise, this bootstrap.yml was "
                    + "probably copied from another server.");
        }
        guildId = resolved;
        BootstrapConfig current = bootstrap;
        apiClient.reconfigure(ApiSettingsFactory.fromBootstrap(current, resolved).build());
        try {
            BootstrapConfig persisted = current.toBuilder().guildId(resolved).build();
            bootstrapStore.save(persisted);
            bootstrap = persisted;
        } catch (IOException | RuntimeException notPersisted) {
            logger.warn("could not cache the resolved guild in " + bootstrapStore.file()
                    + "; this server will ask again on its next boot: " + notPersisted);
        }
        if (closed) {
            return;
        }
        IdentityGuard.Decision decision = identity;
        if (decision != null && decision.blocksTunnel()) {
            // The answer is recorded above, because it is true and the next boot should not have to
            // ask again. Acting on it is what is refused: discovery runs on its own thread, so a
            // reload that blocks the tunnel while an answer is in flight would otherwise be undone
            // a second later by a callback that never heard about it.
            logger.warn("resolved guild " + resolved + ", but this instance is blocked by the "
                    + "identity check, so the tunnel stays down. Run /" + commandLabel
                    + " identity.");
            return;
        }
        // reconnect() rather than connect(): it accepts the guild, cancels anything the backoff has
        // armed, and works whether or not a socket exists — including the case where this is a
        // correction and a socket is already open on the wrong guild. See TunnelClient#reconnect.
        tunnel.reconnect(resolved);
    }

    /**
     * Stops everything, in reverse.
     *
     * <p>Idempotent, and every step is contained: one failure on the way out must not skip the
     * steps after it, because the last of them is the one that stops the threads.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        cancelIdentityWarning();

        final GuildDiscovery discovery = guildDiscovery;
        if (discovery != null) {
            guarded("stopping guild discovery", new Runnable() {
                @Override
                public void run() {
                    discovery.close();
                }
            });
        }
        guildDiscovery = null;

        guarded("stopping modules", new Runnable() {
            @Override
            public void run() {
                modules.shutdown();
            }
        });

        Collections.reverse(registrations);
        for (final Registration registration : registrations) {
            guarded("closing a runtime registration", new Runnable() {
                @Override
                public void run() {
                    registration.close();
                }
            });
        }
        registrations.clear();

        guarded("shutting the tunnel down", new Runnable() {
            @Override
            public void run() {
                tunnel.shutdown();
            }
        });

        // Last, and bounded: everything above may have scheduled its final work here.
        guarded("shutting the executors down", new Runnable() {
            @Override
            public void run() {
                executors.shutdown();
            }
        });
    }

    /**
     * Runs one teardown step without letting its failure skip the steps after it.
     *
     * <p><strong>{@code Throwable}, not {@code RuntimeException}</strong>, and the difference is not
     * theoretical. The failure class that actually shows up on the way out is a
     * {@code NoSuchMethodError} or {@code NoClassDefFoundError} from an API that moved between
     * server versions — the same class of failure departures D43, D44 and D45 are about, and the
     * lesson the login and chat listeners already learned. An {@code Error} escaping here skips
     * every remaining step, and the last of them is the one that stops the threads while the one
     * after this method returns is the platform close that detaches the root log4j appender. A
     * module throwing an {@code Error} from {@code disable()} would therefore leak an appender per
     * reload and, on Velocity, swallow the shutdown line the smoke matrix asserts on.
     *
     * <p>Rethrowing after logging is not an option for the same reason: there is nobody left to
     * handle it, and the cost of continuing is a log line while the cost of stopping is a leak.
     */
    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable failed) {
            logger.error(what + " failed; continuing with the rest of shutdown", failed);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Whether {@code bootstrap.yml} carries enough to talk to the bot at all. */
    public boolean isConfigured() {
        return bootstrap.isConfigured();
    }

    /**
     * The platform's primary command verb, {@code hd} on a server and {@code hdp} on a proxy.
     * Every message this runtime writes that names a command reads it from here, so a proxy
     * never tells an operator to run a command that does not exist there.
     */
    public String commandLabel() {
        return commandLabel;
    }

    /** What is on disk now. Replaced wholesale by {@link #applySetup}; never mutated. */
    public BootstrapConfig bootstrap() {
        return bootstrap;
    }

    /** The store the bootstrap came from, so the setup flow can write to the same file. */
    public BootstrapStore bootstrapStore() {
        return bootstrapStore;
    }

    /** The shared pools. Borrowed by everything, shut down only by {@link #close()}. */
    public HeimdallExecutors executors() {
        return executors;
    }

    public RemoteConfig remoteConfig() {
        return remoteConfig;
    }

    public LoginPipeline loginPipeline() {
        return loginPipeline;
    }

    public ChatPipeline chatPipeline() {
        return chatPipeline;
    }

    public CommandPipeline commandPipeline() {
        return commandPipeline;
    }

    /**
     * Where the platform adapters push join and quit, and where modules subscribe.
     *
     * <p>Live from construction, like {@link #modules()}: a platform registers its listeners before
     * {@link #start()}, and a module can be enabled by the first reconcile inside it.
     */
    public PlayerSessionEvents playerSessions() {
        return playerSessions;
    }

    /**
     * The server underneath.
     *
     * <p>Exposed so the admin tree can reach the two diagnostics that live on the platform rather
     * than in core — the console tap's dropped-consumer count, and the player directory a
     * {@code /hd test} resolves a name through. Nothing else should use it: everything a module
     * needs already arrives through {@code ModuleContext}.
     */
    public PlatformFacade platform() {
        return platform;
    }

    /** What this server tells the bot about itself, or {@code null} if the platform supplied none. */
    public IdentitySource identitySource() {
        return identitySource;
    }

    /**
     * The guild this server belongs to, or {@code ""} while discovery is still asking.
     *
     * <p>A status command reads this to tell "not set up" apart from "set up, still discovering",
     * which are the two states an operator confuses.
     */
    public String guildId() {
        return guildId;
    }

    /** Whether the guild is still being resolved — the state in which the tunnel stays idle. */
    public boolean isDiscoveringGuild() {
        GuildDiscovery discovery = guildDiscovery;
        return discovery != null && !discovery.isResolved();
    }

    /**
     * One line describing how this server stands with its bot, for {@code /hd}.
     *
     * <p>Three states an operator confuses constantly, told apart here rather than in the command so
     * both platforms say the same thing: never set up, set up but the bot will not have the token,
     * and set up but the bot is unreachable. The middle one is the one that looks like a network
     * problem and is not.
     */
    public String connectionStatus() {
        if (!isConfigured()) {
            return "not set up — no bootstrap.yml yet";
        }
        IdentityGuard.Decision decision = identity;
        if (decision != null && decision.blocksTunnel()) {
            return "identity mismatch: these credentials were bound to \"" + decision.recorded()
                    + "\" but this server is \"" + decision.current() + "\", so the tunnel was not "
                    + "started. Run /" + commandLabel + " identity.";
        }
        GuildDiscovery discovery = guildDiscovery;
        if (discovery != null && !discovery.isResolved()) {
            String provisional = Strings.isBlank(guildId)
                    ? "" : " (running on the cached guild " + guildId + ")";
            switch (discovery.status()) {
                case TOKEN_REFUSED:
                    return "the bot refused this server's token — it looks revoked or re-issued; "
                            + "run setup again" + provisional;
                case UNREACHABLE:
                    return "cannot reach the bot (" + discovery.lastFailure() + "); retrying"
                            + provisional;
                case DISCOVERING:
                default:
                    return "asking the bot which guild this token belongs to" + provisional;
            }
        }
        String connection = tunnel.isConnected()
                ? "connected to guild " + guildId
                : "guild " + guildId + " resolved; the tunnel is not connected";
        if (decision != null && decision.isMismatch()) {
            return connection + " (identity mismatch, warning only: run /" + commandLabel
                    + " identity)";
        }
        return connection;
    }

    /** This machine's fingerprint. Fixed for the life of the process. */
    public InstanceFingerprint instanceFingerprint() {
        return fingerprint;
    }

    /**
     * The last identity decision: what state this install is in, and the two values behind it.
     *
     * <p>Never {@code null} - the constructor makes one before anything can ask.
     */
    public IdentityGuard.Decision identity() {
        return identity;
    }

    /** Live from construction, so a platform can register its modules before {@link #start()}. */
    public ModuleManager modules() {
        return modules;
    }

    /**
     * The API, as everything outside core sees it. Never {@code null}, in any state.
     *
     * <p>Deliberately not the raw {@link ApiClient}: a caller that held one of those would be
     * holding a reference that says nothing about whether it can be used, which is the shape of the
     * bug departure D56 describes.
     */
    public HeimdallApi api() {
        return api;
    }

    /**
     * The unsigned claim client, built on first use.
     *
     * <p>Lazy because exactly one command ever touches it, once, on a server that is by definition
     * not doing anything else yet — and eager construction would put an object with no
     * configuration and its own request executor into every server's boot path for nothing.
     */
    public ClaimClient claimClient() {
        ClaimClient existing = claimClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (claimClient == null) {
                claimClient = new ClaimClient(logger, executors.io());
            }
            return claimClient;
        }
    }

    /** The tunnel. Never {@code null}; idle rather than absent on a server that is not set up. */
    public TunnelClient tunnel() {
        return tunnel;
    }

    /**
     * The platform's identity, plus this instance's fingerprint digest.
     *
     * <p>The digest rather than the value: the bot only ever needs to compare two of them, and the
     * readable form contains a host name and a path that nothing outside this server needs to know.
     * It rides in {@code extra} rather than as a named field so the three platform identity sources
     * do not each have to grow the same line.
     */
    private static final class FingerprintedIdentity implements IdentitySource {

        private static final String EXTRA_KEY = "instanceFingerprint";

        private final IdentitySource delegate;
        private final String digest;

        FingerprintedIdentity(IdentitySource delegate, String digest) {
            this.delegate = delegate;
            this.digest = digest;
        }

        @Override
        public ServerIdentity identity() {
            ServerIdentity identity = delegate.identity();
            if (identity == null) {
                return null;
            }
            return identity.toBuilder()
                    .extra(Payload.builder()
                            .putAll(identity.extra())
                            .put(EXTRA_KEY, digest)
                            .build())
                    .build();
        }
    }

    /** The mutable writer. Only the logger, the platform and the bootstrap store are required. */
    public static final class Builder {

        private final HeimdallLogger logger;
        private final PlatformFacade platform;

        private BootstrapStore bootstrapStore;
        private HeimdallExecutors executors;
        private String guildId = "";
        private IdentitySource identitySource;
        private InstanceFingerprint instanceFingerprint;
        private String commandLabel = "hd";
        private HealthSnapshotSource healthSource;
        private BedrockIdentityProvider bedrockIdentityProvider;

        private Builder(HeimdallLogger logger, PlatformFacade platform) {
            if (logger == null || platform == null) {
                throw new IllegalArgumentException("logger and platform are required");
            }
            this.logger = logger;
            this.platform = platform;
        }

        /**
         * The pools to use, if the caller has already built them.
         *
         * <p><strong>Ownership transfers.</strong> {@link HeimdallRuntime#close()} shuts these down
         * like any others — this is not a "borrowed executor" hook. It exists because a platform
         * facade needs a pool at construction and the runtime needs the facade, so somebody has to
         * create them first; making that a loan instead would leave two objects each believing the
         * other would stop the threads.
         *
         * <p>Left unset, the runtime builds its own.
         */
        public Builder executors(HeimdallExecutors value) {
            this.executors = value;
            return this;
        }

        /** Where {@code bootstrap.yml} lives. Defaults to one under the platform's data directory. */
        public Builder bootstrapStore(BootstrapStore value) {
            this.bootstrapStore = value;
            return this;
        }

        /**
         * The guild this server belongs to.
         *
         * <p>Not a bootstrap setting: a server is configured with a token alone and resolves its
         * guild from the bot (departure D54). This exists for a caller that already knows the
         * answer — a test, or a claim that has just returned one.
         */
        public Builder guildId(String value) {
            this.guildId = value;
            return this;
        }

        public Builder identitySource(IdentitySource value) {
            this.identitySource = value;
            return this;
        }

        /**
         * This machine's fingerprint.
         *
         * <p>Left unset, the runtime works it out from the environment and the data directory. It
         * is injectable so a test can decide what "this machine" is, rather than the machine the
         * test happens to run on deciding for it.
         */
        public Builder instanceFingerprint(InstanceFingerprint value) {
            this.instanceFingerprint = value;
            return this;
        }

        /**
         * The root command this platform registers, without a slash: {@code hd} or {@code hdp}.
         *
         * <p>Only the runtime's own console lines use it. Left unset it is {@code hd}, which is
         * right for every backend and wrong for a proxy, so the proxy entry points set it.
         */
        public Builder commandLabel(String value) {
            this.commandLabel = value;
            return this;
        }

        public Builder healthSource(HealthSnapshotSource value) {
            this.healthSource = value;
            return this;
        }

        public Builder bedrockIdentityProvider(BedrockIdentityProvider value) {
            this.bedrockIdentityProvider = value;
            return this;
        }

        public HeimdallRuntime build() {
            if (bootstrapStore == null) {
                bootstrapStore =
                        new BootstrapStore(logger, platform.dataDirectory().resolve("bootstrap.yml"));
            }
            return new HeimdallRuntime(this);
        }
    }
}
