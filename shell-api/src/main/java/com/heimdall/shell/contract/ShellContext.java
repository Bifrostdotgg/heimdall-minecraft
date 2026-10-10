package com.heimdall.shell.contract;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * One core generation's view of the shell that loaded it.
 *
 * <p>A fresh context is made for every generation, and it is retired when that generation stops:
 * anything a retired context is asked to register is closed on the spot instead, so late work from
 * an old core (a task that fires after its {@code stop()}) cannot attach itself to the server again.
 *
 * <p>Thread-safe. Every method may be called from any thread.
 */
public interface ShellContext {

    /** The contract the shell implements; always {@link ShellContract#VERSION} of the shell build. */
    int contractVersion();

    /**
     * Which platform loaded the shell: {@link ShellContract#PLATFORM_BUKKIT},
     * {@link ShellContract#PLATFORM_VELOCITY} or {@link ShellContract#PLATFORM_BUNGEE}.
     */
    String platform();

    /**
     * The platform's plugin object, which is what every platform registration has to be made
     * against: the {@code JavaPlugin} on the Bukkit family, the {@code @Plugin} instance on Velocity,
     * the {@code Plugin} on BungeeCord. Always the shell's, so the platform never sees a core class
     * as a plugin.
     */
    Object platformPlugin();

    /**
     * The platform's server object where the platform hands one to plugins: Velocity's injected
     * {@code ProxyServer} and BungeeCord's {@code ProxyServer}. {@code null} on the Bukkit family,
     * which reaches its server statically.
     */
    Object platformServer();

    /**
     * The plugin's own logger: an {@code org.slf4j.Logger} on Velocity, a
     * {@code java.util.logging.Logger} elsewhere. Handed over rather than rebuilt so a core's lines
     * carry the same prefix the platform gives the plugin.
     */
    Object platformLogger();

    /** The plugin's data directory; the shell has already created it. */
    Path dataDirectory();

    /**
     * The installed plugin jar, which is the shell's jar: the file the updater stages a full
     * release over for a restart. {@code null} when the platform did not say.
     */
    File shellJar();

    /** The shell's own version, which only changes with a restart. */
    String shellVersion();

    /** The core this context was made for. */
    CoreIdentity core();

    /**
     * Whether a swap is in progress: true while the outgoing generation stops and the incoming one
     * starts, false at server start and at server stop.
     *
     * <p>A core reads it in {@code stop()} to choose between "hand over, a successor is coming" and
     * "the server is going away".
     */
    boolean isSwapping();

    /**
     * Tracks a registration against this generation and hands back a handle for it.
     *
     * <p>Closing the returned handle closes {@code registration} and forgets it. Anything still
     * tracked when the generation's {@code stop()} returns is closed by the shell, last registered
     * first, so a registration a core forgot to undo cannot outlive it. A retired context closes
     * {@code registration} immediately and returns {@link Registration#NONE}.
     */
    Registration track(Registration registration);

    /**
     * What the previous generation handed over when it stopped for this swap; empty at server start
     * and after a generation that left nothing. Unmodifiable, plain JDK values only (see
     * {@link Handoff}).
     */
    Map<String, Object> handoff();

    /**
     * Leaves state for the next generation. Only honoured while this generation is stopping for a
     * swap; at server stop it is discarded, because there is no next generation.
     *
     * @throws IllegalArgumentException if a value is not a plain JDK type, naming where
     */
    void handOff(Map<String, ?> state);

    /**
     * Points the shell's relay for {@code binding.name()} at this generation's code, creating the
     * relay if it does not exist yet. Tracked like {@link #track}.
     *
     * <p>Closing the handle unbinds it. Outside a swap that also takes the command away from
     * players, except where the platform cannot (Bukkit's {@code plugin.yml} commands, which then
     * answer "that feature is switched off"). During a swap the relay stays registered until the
     * swap ends, so the incoming generation can take it over without the command disappearing.
     */
    Registration bindCommand(CommandBinding binding);

    /**
     * Makes {@code gate} the login decision the shell's permanent login listener asks. Tracked like
     * {@link #track}. See {@link LoginGate} for why the listener is the shell's.
     */
    Registration bindLoginGate(LoginGate gate);

    /**
     * Makes {@code backend} what the shell's permanent {@code HeimdallTunnel} forwards to. Tracked
     * like {@link #track}; closing it leaves the tunnel disconnected until another core binds.
     */
    Registration bindTunnel(TunnelBackend backend);

    /**
     * Offers an inbound tunnel message no core module claimed to the third-party plugins subscribed
     * through the public SPI.
     *
     * @return whether a subscriber took it
     */
    boolean deliverUnclaimed(String requestId, String type, Payload payload);

    /**
     * Re-checks a release jar the updater downloaded against {@code expectedSha256} (the file on
     * disk is hashed again, here, so a jar replaced after the download is refused), extracts its
     * core, checks that against the hash the build recorded next to it, and says whether it can be
     * swapped in live: built for this shell's contract, and not the core already running.
     *
     * <p>A {@code null} or malformed {@code expectedSha256} is refused: a live swap runs what it is
     * given, and nothing could vouch for this jar.
     *
     * <p>Blocking (it reads and writes a few megabytes); never call it on a server thread. Never
     * throws: an unusable jar comes back as {@link StagedCore#unusable}.
     */
    StagedCore stageRelease(Path releaseJar, String expectedSha256);

    /**
     * Requests a live swap to {@code staged} and returns at once. The swap runs on the shell's swap
     * thread and is held back for {@link ShellContract#SWAP_SETTLE_MS} first, so the caller can
     * still report the outcome of this call (a command reply, a dashboard answer) before the swap
     * stops it. The answer is therefore whether the swap was accepted, and a caller can say so
     * truthfully.
     *
     * @param audience a platform command sender to report the outcome to, or {@code null} for the
     *     console only; the outcome is always logged
     * @return {@code false} if a swap is already running, the shell is stopping, or
     *     {@code staged} is not {@link StagedCore#swappable()}
     */
    boolean swapTo(StagedCore staged, Object audience);
}
