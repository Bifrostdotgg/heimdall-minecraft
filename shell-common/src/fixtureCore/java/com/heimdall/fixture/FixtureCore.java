package com.heimdall.fixture;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CommandBinding;
import com.heimdall.shell.contract.CommandTarget;
import com.heimdall.shell.contract.HeimdallCore;
import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.contract.TunnelBackend;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * A core for the shell's tests, packaged into real jars and loaded through a real child
 * classloader, exactly as a production core is.
 *
 * <p>Each fixture jar carries the same class and a different {@code heimdall-fixture.properties},
 * which picks a behaviour: {@code good} binds a command, a tunnel backend and a login gate, tracks
 * one registration and hands a counter over on a swap; {@code failing} binds the same and then
 * throws from {@code start}; {@code contradicts} declares a contract its manifest does not;
 * {@code leaky} also leaves an object on the platform's registry behind the shell's back, the way
 * adventure-platform-bukkit leaves a listener, and forgets to close its tracked registration;
 * {@code fragile} starts once and fails any later start that carries a handoff, which is what a
 * rollback looks like to it.
 *
 * <p>The platform object is a plain {@code Map} of JDK collections, because a fixture core may only
 * see JDK types and the contract: {@code journal} ({@code List<String>}) records what happened,
 * {@code registry} ({@code List<Object>}) stands in for the server's own registrations.
 */
public final class FixtureCore implements HeimdallCore {

    private final Properties settings = loadSettings();
    private final List<Registration> mine = new ArrayList<Registration>();

    private ShellContext context;
    private List<String> journal;
    private int count;

    public FixtureCore() {
    }

    private static Properties loadSettings() {
        Properties settings = new Properties();
        InputStream in = FixtureCore.class.getClassLoader()
                .getResourceAsStream("META-INF/heimdall-fixture.properties");
        if (in != null) {
            try {
                settings.load(in);
                in.close();
            } catch (IOException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        }
        return settings;
    }

    private String mode() {
        return settings.getProperty("mode", "good");
    }

    @Override
    public int contractVersion() {
        return "contradicts".equals(mode()) ? ShellContract.VERSION + 1 : ShellContract.VERSION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start(ShellContext shell) {
        this.context = shell;
        Map<String, Object> platform = (Map<String, Object>) shell.platformPlugin();
        this.journal = (List<String>) platform.get("journal");
        Object handed = shell.handoff().get("count");
        this.count = handed instanceof Integer ? ((Integer) handed).intValue() : 0;
        final String version = shell.core().version();
        journal.add("start " + version + " count=" + count);

        mine.add(shell.track(Registration.once(new Runnable() {
            @Override
            public void run() {
                journal.add("untrack " + version);
            }
        })));
        mine.add(shell.bindCommand(CommandBinding.named("fixture")
                .aliases(Collections.singletonList("fx"))
                .target(new Target(version))
                .build()));
        mine.add(shell.bindTunnel(new Backend(version)));
        mine.add(shell.bindLoginGate(new LoginGate() {
            @Override
            public void decide(Object event) {
                journal.add("gate " + version + " " + event);
            }
        }));

        if ("leaky".equals(mode())) {
            ((List<Object>) platform.get("registry")).add(new Leak());
        }
        if ("fragile".equals(mode()) && handed != null) {
            throw new IllegalStateException("fixture core " + version + " cannot start twice");
        }
        if ("failing".equals(mode())) {
            throw new IllegalStateException("fixture core " + version + " refuses to start");
        }
    }

    @Override
    public void stop() {
        if (context == null) {
            return;
        }
        journal.add("stop " + context.core().version() + " swapping=" + context.isSwapping());
        if (context.isSwapping()) {
            Map<String, Object> state = new HashMap<String, Object>();
            state.put("count", Integer.valueOf(count + 1));
            context.handOff(state);
        }
        // Newest first, like every real core. A leaky core forgets the first one, its tracked
        // registration, which the shell then has to close.
        int floor = "leaky".equals(mode()) ? 1 : 0;
        for (int i = mine.size() - 1; i >= floor; i--) {
            mine.get(i).close();
        }
    }

    /** Stands in for a listener a library registered behind the core's back. */
    static final class Leak {
    }

    private final class Target implements CommandTarget {

        private final String version;

        Target(String version) {
            this.version = version;
        }

        @Override
        public void execute(Object sender, String label, String[] args) {
            journal.add("command " + version + " " + label);
        }

        @Override
        public List<String> complete(Object sender, String alias, String[] args) {
            return Collections.singletonList(version);
        }
    }

    private static final class Backend implements TunnelBackend {

        private final String version;

        Backend(String version) {
            this.version = version;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void publish(String type, Payload payload) {
        }

        @Override
        public CompletableFuture<Payload> request(String type, Payload payload, long timeoutMs) {
            return CompletableFuture.completedFuture(Payload.empty());
        }

        @Override
        public void reply(String requestId, String type, Payload payload) {
        }
    }
}
