package com.heimdall.shell.hotswap;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CommandBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every Heimdall command relay, and which core binding each one forwards to.
 *
 * <h2>Lifecycle of a relay</h2>
 *
 * <ul>
 *   <li><strong>Permanent relays</strong> are installed by the platform shell at enable: every
 *       {@code plugin.yml} command on Bukkit, and the admin verbs on the proxies. They exist with or
 *       without a core, which is what lets {@code /hd swap} work when no core is running.
 *   <li><strong>Dynamic relays</strong> are created the first time a core binds a name nothing has
 *       registered (a proxy module's command, a punishments root alias). Outside a swap, unbinding
 *       one removes it from the platform, so a module that is switched off really does give its
 *       verb back. During a swap, unbinding only clears the binding; {@link #prune()} removes what
 *       the incoming core did not bind again once the swap is over. A command therefore never
 *       disappears and reappears across a successful swap.
 * </ul>
 *
 * <h2>What a relay says with nothing bound</h2>
 *
 * <p>In order: the admin verbs the shell answers itself ({@code swap}, and {@code status} when no
 * core is running); "updating" while a swap is between generations; "not running" with no core;
 * otherwise "that feature is switched off", which is the only remaining reason a bound name has
 * nothing behind it and the only one an operator can act on.
 */
public final class RelayTable {

    /** The shell's own handling of the admin verbs. */
    public interface AdminVerbs {

        /**
         * Handles {@code args} if they are a verb the shell owns.
         *
         * @param coreBound whether a core currently has the admin command bound
         * @return whether the shell answered, in which case the core is not called
         */
        boolean handle(Object sender, String label, String[] args, boolean coreBound);

        /** Completions the shell adds while no core has the admin command bound. */
        List<String> verbs();
    }

    /** What a relay needs to know about the shell's state. */
    public interface State {

        boolean isSwapping();

        boolean hasCore();

        /** The admin command's label on this platform, for "run /hd status". */
        String adminLabel();
    }

    private final CommandPlatform platform;
    private final ShellAudience audience;
    private final ShellLog log;
    private final State state;
    private volatile AdminVerbs admin;

    private final Object lock = new Object();
    private final Map<String, Relay> relays = new LinkedHashMap<String, Relay>();

    public RelayTable(CommandPlatform platform, ShellAudience audience, ShellLog log, State state) {
        this.platform = platform;
        this.audience = audience;
        this.log = log;
        this.state = state;
    }

    /** Sets who answers the admin verbs. Called once, by the host, before any relay runs. */
    void admin(AdminVerbs verbs) {
        this.admin = verbs;
    }

    ShellAudience audience() {
        return audience;
    }

    /**
     * Installs a relay that stays for the life of the shell.
     *
     * @param admin whether this is an admin verb ({@code /hd}, {@code /hwl}, {@code /hdp})
     */
    public void installPermanent(
            String name,
            List<String> aliases,
            String permission,
            String description,
            String usage,
            boolean admin) {
        String key = key(name);
        synchronized (lock) {
            if (relays.containsKey(key)) {
                return;
            }
            Relay relay = new Relay(this, key, admin, permission);
            CommandPlatform.Handle handle =
                    platform.register(key, lower(aliases), permission, description, usage, relay);
            if (handle == null) {
                log.warn("/" + key + " could not be registered on this server; it will not work "
                        + "until a restart");
                return;
            }
            relay.handle(new PermanentHandle(handle));
            relays.put(key, relay);
        }
    }

    /**
     * Binds {@code binding} to its relay, creating and registering the relay if needed.
     *
     * @return a handle that unbinds it; see the class note for what unbinding does
     */
    public Registration bind(final CommandBinding binding) {
        final Relay relay;
        synchronized (lock) {
            Relay existing = relays.get(binding.name());
            if (existing == null) {
                existing = new Relay(this, binding.name(), false, binding.permission());
                CommandPlatform.Handle handle = platform.register(binding.name(),
                        binding.aliases(), binding.permission(), binding.description(),
                        binding.usage(), existing);
                if (handle == null) {
                    log.warn("/" + binding.name() + " could not be registered on this server");
                    return Registration.NONE;
                }
                existing.handle(handle);
                relays.put(binding.name(), existing);
            } else if (existing.handle().permanent()) {
                warnAboutUndeclaredAliases(binding, existing.handle().aliases());
            } else if (!existing.handle().permanent()
                    && !existing.handle().aliases().equals(binding.aliases())) {
                // A new generation binding the same name with different aliases. Re-registering is
                // the only way a proxy learns about a changed alias list.
                existing.handle().unregister();
                CommandPlatform.Handle handle = platform.register(binding.name(),
                        binding.aliases(), binding.permission(), binding.description(),
                        binding.usage(), existing);
                if (handle == null) {
                    relays.remove(binding.name());
                    log.warn("/" + binding.name() + " could not be re-registered on this server");
                    return Registration.NONE;
                }
                existing.handle(handle);
            }
            relay = existing;
            relay.bind(binding);
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                unbind(relay, binding);
            }
        });
    }

    private void unbind(Relay relay, CommandBinding binding) {
        synchronized (lock) {
            if (!relay.unbind(binding)) {
                // A later generation has already taken the relay over. Leave it alone.
                return;
            }
            if (state.isSwapping() || relay.handle().permanent()) {
                return;
            }
            relay.handle().unregister();
            relays.remove(relay.name());
        }
    }

    /**
     * Removes the dynamic relays nothing is bound to any more. Called when a swap ends, whichever
     * way it ended.
     *
     * @return how many were removed
     */
    public int prune() {
        int removed = 0;
        synchronized (lock) {
            List<Relay> snapshot = new ArrayList<Relay>(relays.values());
            for (Relay relay : snapshot) {
                if (relay.bound() == null && !relay.handle().permanent()) {
                    unregisterQuietly(relay);
                    relays.remove(relay.name());
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Clears any binding whose code belongs to {@code retired}. Belt and braces behind the context's
     * own teardown, which should already have done it.
     *
     * @return how many bindings were cleared
     */
    int sweep(LoadedCore retired) {
        int cleared = 0;
        synchronized (lock) {
            for (Relay relay : relays.values()) {
                CommandBinding binding = relay.bound();
                if (binding != null && retired.owns(binding.target().getClass())
                        && relay.unbind(binding)) {
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /** Unregisters every dynamic relay. Called when the shell itself is disabled. */
    public void shutdown() {
        synchronized (lock) {
            for (Relay relay : relays.values()) {
                relay.bind(null);
                if (!relay.handle().permanent()) {
                    unregisterQuietly(relay);
                }
            }
            relays.clear();
        }
    }

    /** The relay registered under {@code name}, or {@code null}. */
    public Relay find(String name) {
        synchronized (lock) {
            return relays.get(key(name));
        }
    }

    /** Names of every relay, for diagnostics and tests. */
    public List<String> names() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<String>(relays.keySet()));
        }
    }

    void execute(Relay relay, Object sender, String label, String[] args) {
        CommandBinding binding = relay.bound();
        AdminVerbs verbs = admin;
        if (relay.isAdmin() && verbs != null) {
            try {
                if (verbs.handle(sender, label, args, binding != null)) {
                    return;
                }
            } catch (Throwable broken) {
                log.error("the shell's /" + label + " handler failed", broken);
                audience.send(sender, ShellMessages.COMMAND_FAILED);
                return;
            }
        }
        if (binding != null) {
            try {
                binding.target().execute(sender, label, args);
            } catch (Throwable broken) {
                // Throwable: what this guards against in practice is a NoSuchMethodError from a
                // server API that moved, or a NoClassDefFoundError from a core whose loader has
                // closed, and both are Errors.
                log.error("/" + label + " failed", broken);
                audience.send(sender, ShellMessages.COMMAND_FAILED);
            }
            return;
        }
        if (state.isSwapping()) {
            audience.send(sender, ShellMessages.UPDATING);
        } else if (!state.hasCore()) {
            audience.send(sender, String.format(ShellMessages.NOT_RUNNING, state.adminLabel()));
        } else {
            audience.send(sender, String.format(ShellMessages.SWITCHED_OFF, relay.name()));
        }
    }

    List<String> complete(Relay relay, Object sender, String alias, String[] args) {
        CommandBinding binding = relay.bound();
        if (binding != null) {
            try {
                return binding.target().complete(sender, alias, args);
            } catch (Throwable broken) {
                log.debug("tab completion for /" + alias + " failed: " + broken);
                return Collections.emptyList();
            }
        }
        AdminVerbs verbs = admin;
        if (relay.isAdmin() && verbs != null && args.length <= 1
                && audience.hasPermission(sender, ShellMessages.ADMIN_PERMISSION)) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            for (String verb : verbs.verbs()) {
                if (verb.startsWith(prefix)) {
                    out.add(verb);
                }
            }
            return out;
        }
        // Empty, not null: null falls through to Bukkit's own player-name completion, and a command
        // with nothing behind it has no business advertising who is online.
        return Collections.emptyList();
    }

    /**
     * Says so when a core binds aliases a permanent command was not registered with.
     *
     * <p>Not an error: the command still works under its primary name. But on Bukkit the aliases
     * are fixed by {@code plugin.yml} at load time, so an alias the core expects and the descriptor
     * lacks works on a proxy and silently not here, which is exactly the kind of difference that
     * turns into "it works on my proxy".
     */
    private void warnAboutUndeclaredAliases(CommandBinding binding, List<String> registered) {
        List<String> missing = new ArrayList<String>();
        for (String alias : binding.aliases()) {
            if (!registered.contains(alias)) {
                missing.add(alias);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("/" + binding.name() + " was registered without alias(es) " + missing
                    + " (on Bukkit, plugin.yml fixes a command's aliases at load time), so they "
                    + "will not work here");
        }
    }

    private void unregisterQuietly(Relay relay) {
        try {
            relay.handle().unregister();
        } catch (Throwable failed) {
            log.debug("unregistering /" + relay.name() + " failed: " + failed);
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> lower(List<String> aliases) {
        List<String> out = new ArrayList<String>();
        if (aliases != null) {
            for (String alias : aliases) {
                String normalised = key(alias);
                if (!normalised.isEmpty() && !out.contains(normalised)) {
                    out.add(normalised);
                }
            }
        }
        return out;
    }

    /** Marks a relay installed at enable as permanent, whatever the platform said. */
    private static final class PermanentHandle implements CommandPlatform.Handle {

        private final CommandPlatform.Handle delegate;

        PermanentHandle(CommandPlatform.Handle delegate) {
            this.delegate = delegate;
        }

        @Override
        public void unregister() {
            // Permanent: the shell owns it until the plugin is disabled, and the platform tears a
            // disabled plugin's commands down itself.
        }

        @Override
        public boolean permanent() {
            return true;
        }

        @Override
        public List<String> aliases() {
            return delegate.aliases();
        }
    }
}
