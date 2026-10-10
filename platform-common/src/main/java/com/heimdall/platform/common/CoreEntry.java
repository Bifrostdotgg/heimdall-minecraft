package com.heimdall.platform.common;

import com.heimdall.shell.contract.HeimdallCore;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;

/**
 * The one entry point the core jar names, which picks the platform's own core by name.
 *
 * <h2>Why one entry and a name lookup, rather than three services lines</h2>
 *
 * <p>The core jar serves all three platforms, exactly as the single plugin jar always has, so it
 * carries the Bukkit, Velocity and BungeeCord bindings side by side. Only one of them can even be
 * linked on a given server: the Velocity binding is Java 17 bytecode that a Java 8 Spigot cannot
 * load, and each binding references its own platform's API. So the shell must never be the one
 * choosing among platform classes. It asks for this class, which is platform-free, and this class
 * resolves exactly one binding by name, through its own loader, for the platform the shell says it
 * is on.
 *
 * <p>The names are strings on purpose: a class literal here would put all three platform bindings
 * in this class's constant pool, and the conformance suite would rightly call that a platform leak.
 */
public final class CoreEntry implements HeimdallCore {

    private HeimdallCore delegate;

    public CoreEntry() {
    }

    @Override
    public int contractVersion() {
        // A compile-time constant, inlined: this is the contract the core was BUILT against.
        return ShellContract.VERSION;
    }

    @Override
    public void start(ShellContext context) throws Exception {
        String binding = bindingFor(context.platform());
        Class<?> type = Class.forName(binding, true, CoreEntry.class.getClassLoader());
        delegate = (HeimdallCore) type.getConstructor().newInstance();
        delegate.start(context);
    }

    @Override
    public void stop() {
        HeimdallCore running = delegate;
        delegate = null;
        if (running != null) {
            running.stop();
        }
    }

    /** The binding class for a platform name, or an exception naming the unknown platform. */
    static String bindingFor(String platform) {
        if (ShellContract.PLATFORM_BUKKIT.equals(platform)) {
            return "com.heimdall.platform.bukkit.BukkitCore";
        }
        if (ShellContract.PLATFORM_VELOCITY.equals(platform)) {
            return "com.heimdall.platform.velocity.VelocityCore";
        }
        if (ShellContract.PLATFORM_BUNGEE.equals(platform)) {
            return "com.heimdall.platform.bungee.BungeeCore";
        }
        throw new IllegalArgumentException("no core binding for platform '" + platform + "'");
    }
}
