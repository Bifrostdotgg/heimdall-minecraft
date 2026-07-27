package com.heimdall.core.testing;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A {@link CommandRegistrar} that keeps what was registered, and can run it.
 *
 * <p>Two jobs, and both are needed by the same tests. It records registrations so a test can assert
 * that disabling a module really took its command away — the property departure D30 exists for, and
 * one that is invisible to a test which only checks that {@code register} was called. And it
 * dispatches, so {@code /offend} can be exercised end to end without a server.
 *
 * <p>The permission check lives here rather than in the handler, because that is where the real
 * registrars put it: a test whose fake skipped it would pass on a command that leaks staff
 * functionality to everybody.
 *
 * <p>Thread-safe.
 */
public final class RecordingCommands implements CommandRegistrar {

    private final Map<String, CommandSpec> registered = new LinkedHashMap<String, CommandSpec>();

    @Override
    public Registration register(final CommandSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        synchronized (registered) {
            registered.put(spec.name(), spec);
            for (String alias : spec.aliases()) {
                if (alias != null && !alias.trim().isEmpty()) {
                    registered.put(alias.trim().toLowerCase(Locale.ROOT), spec);
                }
            }
        }
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                synchronized (registered) {
                    registered.values().removeIf(value -> value == spec);
                }
            }
        });
    }

    /** Every label currently answering, primary names and aliases alike. */
    public Set<String> labels() {
        synchronized (registered) {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(registered.keySet()));
        }
    }

    /** Whether a label is currently registered. */
    public boolean has(String label) {
        return spec(label) != null;
    }

    /** The spec behind a label, or {@code null}. */
    public CommandSpec spec(String label) {
        if (label == null) {
            return null;
        }
        synchronized (registered) {
            return registered.get(label.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Runs a command as {@code source}, applying the permission gate first.
     *
     * @return {@code false} if no such command is registered or the source may not run it — the two
     *     outcomes a caller has to be able to tell apart from "it ran and did nothing"
     */
    public boolean run(CommandSource source, String label, String... args) {
        CommandSpec spec = spec(label);
        if (spec == null || !allowed(source, spec)) {
            return false;
        }
        spec.handler().execute(source, Collections.unmodifiableList(Arrays.asList(args)));
        return true;
    }

    /** Tab completion for a label, gated the same way. Never {@code null}. */
    public List<String> complete(CommandSource source, String label, String... args) {
        CommandSpec spec = spec(label);
        if (spec == null || spec.completer() == null || !allowed(source, spec)) {
            return Collections.emptyList();
        }
        List<String> suggestions =
                spec.completer().complete(source, Collections.unmodifiableList(Arrays.asList(args)));
        return suggestions == null ? Collections.<String>emptyList() : new ArrayList<String>(suggestions);
    }

    private static boolean allowed(CommandSource source, CommandSpec spec) {
        return spec.permission().isEmpty() || (source != null && source.hasPermission(spec.permission()));
    }
}
