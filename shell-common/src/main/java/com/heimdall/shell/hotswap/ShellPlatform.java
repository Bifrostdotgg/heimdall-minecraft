package com.heimdall.shell.hotswap;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.core.util.Registration;
import java.io.File;
import java.nio.file.Path;

/**
 * What a platform shell tells the platform-free {@link ShellHost} about its server, and the handful
 * of things only the platform can do.
 *
 * <p>One implementation per platform, each small: the point of the split is that the hard part
 * (loading, swapping, rolling back) is written once, here, and tested without a server.
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

    /** Registers relay commands with the platform. */
    CommandPlatform commands();

    /** Sends text to senders and checks their permissions. */
    ShellAudience audience();

    /** The admin command's label here: {@code hd} on the Bukkit family, {@code hdp} on a proxy. */
    String adminLabel();

    /**
     * Publishes the shell's permanent tunnel where other plugins look for it, once, at enable.
     *
     * @return a handle that withdraws it again at disable
     */
    Registration publishTunnel(HeimdallTunnel tunnel);

    /**
     * Removes whatever the platform still holds that belongs to a core that has stopped: listeners
     * a library registered behind the core's back, services, commands. Called after the core's own
     * teardown and the shell's tracked teardown, so on a well-behaved core it finds nothing.
     *
     * <p>Exists because "well-behaved" is not under Heimdall's control. adventure-platform-bukkit's
     * {@code BukkitAudiences.close()} leaves its own join and quit listeners registered against the
     * plugin, which before the split only a plugin disable cleaned up, and a swap is not one.
     *
     * @return how many registrations were removed
     */
    int sweep(LoadedCore retired);
}
