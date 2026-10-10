package com.heimdall.shell.hotswap;

import com.heimdall.api.HeimdallTunnel;
import com.heimdall.core.util.Registration;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A {@link ShellPlatform} with no server behind it, for the shell's own tests.
 *
 * <p>Its "plugin" object is a map of JDK collections, because that is all a fixture core can see:
 * {@code journal} records what the cores did, {@code registry} stands in for the server's own
 * registrations, which the sweep cleans up after a core that left something behind.
 */
final class FixturePlatform implements ShellPlatform {

    final List<String> journal = Collections.synchronizedList(new ArrayList<String>());
    final List<Object> registry = Collections.synchronizedList(new ArrayList<Object>());
    final RecordingShellLog log = new RecordingShellLog();
    final FakeCommands commands = new FakeCommands();
    final RecordingAudience audience = new RecordingAudience();
    final Map<String, Object> plugin = new HashMap<String, Object>();
    final Path dataDirectory;
    volatile File shellJar;
    volatile HeimdallTunnel published;

    FixturePlatform(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        plugin.put("journal", journal);
        plugin.put("registry", registry);
    }

    List<String> journal() {
        synchronized (journal) {
            return new ArrayList<String>(journal);
        }
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public Object plugin() {
        return plugin;
    }

    @Override
    public Object server() {
        return null;
    }

    @Override
    public Object logger() {
        return null;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public File shellJar() {
        return shellJar;
    }

    @Override
    public ShellLog log() {
        return log;
    }

    @Override
    public ClassLoader shellLoader() {
        return FixturePlatform.class.getClassLoader();
    }

    @Override
    public CommandPlatform commands() {
        return commands;
    }

    @Override
    public ShellAudience audience() {
        return audience;
    }

    @Override
    public String adminLabel() {
        return "hd";
    }

    @Override
    public Registration publishTunnel(final HeimdallTunnel tunnel) {
        published = tunnel;
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                published = null;
            }
        });
    }

    @Override
    public int sweep(LoadedCore retired) {
        int removed = 0;
        synchronized (registry) {
            for (Iterator<Object> it = registry.iterator(); it.hasNext(); ) {
                if (retired.owns(it.next().getClass())) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Records every relay registered and unregistered. */
    static final class FakeCommands implements CommandPlatform {

        final List<String> registered = Collections.synchronizedList(new ArrayList<String>());
        final List<String> unregistered = Collections.synchronizedList(new ArrayList<String>());
        final Map<String, Relay> relays = Collections.synchronizedMap(new HashMap<String, Relay>());
        volatile boolean refuse;

        @Override
        public Handle register(
                final String name,
                final List<String> aliases,
                String permission,
                String description,
                String usage,
                Relay relay) {
            if (refuse) {
                return null;
            }
            registered.add(name + aliases);
            relays.put(name, relay);
            return new Handle() {
                @Override
                public void unregister() {
                    unregistered.add(name);
                    relays.remove(name);
                }

                @Override
                public boolean permanent() {
                    return false;
                }

                @Override
                public List<String> aliases() {
                    return aliases;
                }
            };
        }
    }

    /** Records every line sent, as {@code "<sender>: <text>"}, and grants permissions on request. */
    static final class RecordingAudience implements ShellAudience {

        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        final List<String> alerts = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean grantAll = true;

        @Override
        public void send(Object sender, String legacyText) {
            sent.add(sender + ": " + legacyText);
        }

        @Override
        public boolean hasPermission(Object sender, String node) {
            return grantAll;
        }

        @Override
        public void alertOnline(String node, String legacyText) {
            alerts.add(node + ": " + legacyText);
        }

        String last() {
            synchronized (sent) {
                return sent.isEmpty() ? "" : sent.get(sent.size() - 1);
            }
        }
    }
}
