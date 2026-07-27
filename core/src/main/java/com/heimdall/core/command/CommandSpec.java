package com.heimdall.core.command;

import com.heimdall.core.util.Lists;
import com.heimdall.core.util.Strings;
import java.util.List;
import java.util.Locale;

/**
 * One command, described in terms no platform appears in.
 *
 * <p>A builder rather than a constructor for the reason departure D21 gives: five Strings in a row,
 * two of which are optional, is a call site where nothing says which is the permission and which is
 * the usage line.
 *
 * <h2>Aliases are advertised, not guaranteed</h2>
 *
 * <p>On Velocity the registrar really does register every alias. On the Bukkit family it cannot: a
 * command's aliases live in {@code plugin.yml} and are fixed at load time, so the ones here are
 * checked against what the descriptor declared and a mismatch is a warning rather than a silent
 * difference between the two platforms.
 *
 * <p>Immutable.
 */
public final class CommandSpec {

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private final String usage;
    private final String description;
    private final CommandHandler handler;
    private final CommandCompleter completer;

    private CommandSpec(Builder builder) {
        if (Strings.isBlank(builder.name)) {
            throw new IllegalArgumentException("a command needs a name");
        }
        if (builder.handler == null) {
            throw new IllegalArgumentException("a command needs a handler");
        }
        this.name = builder.name.trim().toLowerCase(Locale.ROOT);
        this.aliases = Lists.copyOf(builder.aliases);
        this.permission = Strings.trimToEmpty(builder.permission);
        this.usage = Strings.trimToEmpty(builder.usage);
        this.description = Strings.trimToEmpty(builder.description);
        this.handler = builder.handler;
        this.completer = builder.completer;
    }

    public static Builder named(String name) {
        return new Builder().name(name);
    }

    /** The primary label, lower-cased. */
    public String name() {
        return name;
    }

    /** Alternative labels. Never {@code null}. */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * The node the sender must hold, or {@code ""} for a command anyone may run.
     *
     * <p>Checked by the registrar before {@link #handler()} sees the invocation, and — on both
     * platforms — before {@link #completer()} does, so a command a player cannot run does not
     * advertise its arguments to them.
     */
    public String permission() {
        return permission;
    }

    /** A one-line usage string, shown when the handler rejects the arguments. */
    public String usage() {
        return usage;
    }

    /** What the command is for, as the platform's own help listing would show it. */
    public String description() {
        return description;
    }

    public CommandHandler handler() {
        return handler;
    }

    /** Tab completion, or {@code null} for a command with no arguments to suggest. */
    public CommandCompleter completer() {
        return completer;
    }

    @Override
    public String toString() {
        return "CommandSpec{/" + name + (aliases.isEmpty() ? "" : " aliases=" + aliases)
                + (permission.isEmpty() ? "" : ", permission=" + permission) + "}";
    }

    /** The mutable writer. Only the name and the handler are required. */
    public static final class Builder {

        private String name;
        private List<String> aliases;
        private String permission;
        private String usage;
        private String description;
        private CommandHandler handler;
        private CommandCompleter completer;

        private Builder() {
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder aliases(List<String> value) {
            this.aliases = value;
            return this;
        }

        public Builder permission(String value) {
            this.permission = value;
            return this;
        }

        public Builder usage(String value) {
            this.usage = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder handler(CommandHandler value) {
            this.handler = value;
            return this;
        }

        public Builder completer(CommandCompleter value) {
            this.completer = value;
            return this;
        }

        public CommandSpec build() {
            return new CommandSpec(this);
        }
    }
}
