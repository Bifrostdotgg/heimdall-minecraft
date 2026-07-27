package com.heimdall.api;

/**
 * The public SPI other server plugins use to talk to Heimdall.
 *
 * <p>Phase 0 exposes only the plugin version so third parties have something stable to compile
 * against while the v3 surface is being designed. Methods are added — never removed or
 * re-signatured — as the modules land.
 */
public interface HeimdallTunnel {

    /**
     * The running plugin version, e.g. {@code 3.0.0}.
     *
     * @return the plugin version string, never {@code null}
     */
    String version();
}
