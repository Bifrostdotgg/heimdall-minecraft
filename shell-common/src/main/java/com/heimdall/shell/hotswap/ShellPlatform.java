package com.heimdall.shell.hotswap;

import java.io.File;
import java.nio.file.Path;

/**
 * What a platform shell tells the platform-free {@link ShellHost} about its server.
 *
 * <p>One implementation per platform, each a few dozen lines: the point of the split is that the
 * hard part (loading, swapping, rolling back) is written once, here, and tested without a server.
 */
public interface ShellPlatform {

    /** One of the {@code ShellContract.PLATFORM_*} names. */
    String name();

    /** The platform's plugin object; see {@code ShellContext.platformPlugin()}. */
    Object plugin();

    /** The platform's server object, or {@code null}; see {@code ShellContext.platformServer()}. */
    Object server();

    /** The platform's logger object; see {@code ShellContext.platformLogger()}. */
    Object logger();

    /** The plugin's data directory. */
    Path dataDirectory();

    /** The installed plugin jar, or {@code null} when the platform does not say. */
    File shellJar();

    /** Where the shell writes its own lines. */
    ShellLog log();

    /** The loader the shell's classes came from: every core loader's parent. */
    ClassLoader shellLoader();
}
