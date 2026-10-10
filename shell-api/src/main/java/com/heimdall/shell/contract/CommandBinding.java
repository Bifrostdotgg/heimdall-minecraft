package com.heimdall.shell.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A command a core wants in front of players, described in plain strings, plus its
 * {@link CommandTarget}.
 *
 * <h2>Why the shell owns the platform command</h2>
 *
 * <p>The platform object a command is registered as (a Bukkit {@code PluginCommand}, a Velocity
 * {@code SimpleCommand}, a BungeeCord {@code Command}) is created by the shell and stays registered
 * across a swap; a binding only points it at the current core's code. Three things depend on that:
 *
 * <ul>
 *   <li>Bukkit's {@code plugin.yml} commands can never be unregistered, so whatever the platform
 *       holds for them must not be a core class, or the first swap pins the old core forever.
 *   <li>Players see no "Unknown command" while a swap is between generations: the relay answers
 *       "Heimdall is updating" instead.
 *   <li>The command map is not mutated on every swap, which matters on Folia, where it is a plain
 *       map read concurrently by region threads.
 * </ul>
 *
 * <p>Immutable.
 */
public final class CommandBinding {

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final String description;
    private final String usage;
    private final CommandTarget target;

    private CommandBinding(Builder builder) {
        this.name = normalise(builder.name);
        List<String> cleaned = new ArrayList<String>();
        for (String alias : builder.aliases) {
            String normalised = normalise(alias);
            if (!normalised.isEmpty() && !normalised.equals(name) && !cleaned.contains(normalised)) {
                cleaned.add(normalised);
            }
        }
        this.aliases = Collections.unmodifiableList(cleaned);
        this.permission = builder.permission == null ? "" : builder.permission.trim();
        this.description = builder.description == null ? "" : builder.description;
        this.usage = builder.usage == null ? "/" + name : builder.usage;
        this.target = builder.target;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Lower-case primary name. */
    public String name() {
        return name;
    }

    /** Lower-case aliases, without the name and without duplicates. */
    public List<String> aliases() {
        return aliases;
    }

    /** The node a sender needs; empty when the command is open to everyone. */
    public String permission() {
        return permission;
    }

    public String description() {
        return description;
    }

    public String usage() {
        return usage;
    }

    public CommandTarget target() {
        return target;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Mutable writer. */
    public static final class Builder {

        private final String name;
        private List<String> aliases = Collections.emptyList();
        private String permission;
        private String description;
        private String usage;
        private CommandTarget target;

        private Builder(String name) {
            this.name = name;
        }

        public Builder aliases(List<String> value) {
            this.aliases = value == null ? Collections.<String>emptyList() : value;
            return this;
        }

        public Builder permission(String value) {
            this.permission = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder usage(String value) {
            this.usage = value;
            return this;
        }

        public Builder target(CommandTarget value) {
            this.target = value;
            return this;
        }

        /** Builds it; a name and a target are required. */
        public CommandBinding build() {
            if (normalise(name).isEmpty() || target == null) {
                throw new IllegalArgumentException("a command binding needs a name and a target");
            }
            return new CommandBinding(this);
        }
    }
}
