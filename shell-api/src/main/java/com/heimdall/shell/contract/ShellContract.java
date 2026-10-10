package com.heimdall.shell.contract;

/**
 * The constants the shell and a core agree on, and the number that says whether they still agree.
 *
 * <h2>Why a single integer</h2>
 *
 * <p>The shell can never be replaced while the server runs; only the core can. So the one question
 * a live swap has to answer before it touches anything is "was this core compiled against the
 * contract this shell implements?", and a monotonically increasing integer answers it exactly. It
 * is a compile-time constant, which matters: the core's {@link HeimdallCore#contractVersion()}
 * returns this field, so javac inlines the value the core was <em>built</em> against rather than
 * reading the shell's copy at runtime, which would always agree with itself.
 *
 * <p><strong>Bump it</strong> whenever anything in this package, in {@code :api}, or in the types
 * {@code :api} exposes ({@code Payload}, {@code Envelope}, {@code Registration}) changes in a way an
 * older shell could not serve. A release with a different number is still installed by the updater;
 * it is staged for the next restart instead of being swapped in, which is the pre-hot-swap behaviour
 * and always safe.
 */
public final class ShellContract {

    /** The contract this build implements. See the class note before changing it. */
    public static final int VERSION = 1;

    /** {@link ShellContext#platform()} on Paper, Spigot and Folia. */
    public static final String PLATFORM_BUKKIT = "bukkit";

    /** {@link ShellContext#platform()} on Velocity. */
    public static final String PLATFORM_VELOCITY = "velocity";

    /** {@link ShellContext#platform()} on BungeeCord. */
    public static final String PLATFORM_BUNGEE = "bungee";

    /**
     * Where the release jar carries the core, as a stored nested jar.
     *
     * <p>Nested rather than merged because a {@code URLClassLoader} cannot load classes out of a jar
     * inside a jar, and because the release has to stay one file: the v2 self-updater and the bot's
     * download card both pick the single {@code heimdall-whitelist-<version>.jar} asset.
     */
    public static final String EMBEDDED_CORE = "META-INF/heimdall/heimdall-core.jar";

    /**
     * Next to {@link #EMBEDDED_CORE}: the nested core's version, SHA-256 and contract, written by the
     * build. The shell checks the extracted bytes against this before loading a single class.
     */
    public static final String EMBEDDED_CORE_PROPERTIES = "META-INF/heimdall/core.properties";

    /** The services entry a core jar names its {@link HeimdallCore} implementation in. */
    public static final String SERVICE_ENTRY =
            "META-INF/services/com.heimdall.shell.contract.HeimdallCore";

    /** Core jar manifest attribute: the contract it was built against. */
    public static final String MANIFEST_CONTRACT = "Heimdall-Shell-Contract";

    /** Core jar manifest attribute: the core's version. */
    public static final String MANIFEST_CORE_VERSION = "Heimdall-Core-Version";

    /** {@link #EMBEDDED_CORE_PROPERTIES} key for the core's version. */
    public static final String PROPERTY_VERSION = "version";

    /** {@link #EMBEDDED_CORE_PROPERTIES} key for the nested jar's SHA-256, lowercase hex. */
    public static final String PROPERTY_SHA256 = "sha256";

    /** {@link #EMBEDDED_CORE_PROPERTIES} key for the contract the nested core was built against. */
    public static final String PROPERTY_CONTRACT = "contract";

    private ShellContract() {
    }
}
