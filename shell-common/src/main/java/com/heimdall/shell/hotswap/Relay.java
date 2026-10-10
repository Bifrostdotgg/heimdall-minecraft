package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.CommandBinding;
import java.util.List;

/**
 * The one object a platform holds for a Heimdall command, for as long as the command exists.
 *
 * <p>A relay is a shell class, so the platform's command map never references a core class and a
 * swap never has to touch that map. What it forwards to is a {@link CommandBinding} from the current
 * core, swapped by {@link RelayTable}; with nothing bound it answers for itself (see
 * {@link RelayTable#execute}).
 *
 * <p>Thread-safe: the binding is a volatile read, so a command that arrives mid-swap sees either the
 * old core's code (whose classloader is still open, see the grace period in {@link ShellHost}) or
 * none, never a half-made object.
 */
public final class Relay {

    private final RelayTable table;
    private final String name;
    private final boolean admin;
    private final String permanentPermission;

    private volatile CommandBinding bound;
    private volatile CommandPlatform.Handle handle;

    Relay(RelayTable table, String name, boolean admin, String permanentPermission) {
        this.table = table;
        this.name = name;
        this.admin = admin;
        this.permanentPermission = permanentPermission == null ? "" : permanentPermission;
    }

    /** The primary name, lower-case. */
    public String name() {
        return name;
    }

    /**
     * Whether this is an admin verb, on which the shell itself answers {@code swap} (and
     * {@code status} while no core is running).
     */
    public boolean isAdmin() {
        return admin;
    }

    /** Runs the command for {@code sender}. Never throws. */
    public void execute(Object sender, String label, String[] args) {
        table.execute(this, sender, label, args == null ? new String[0] : args);
    }

    /** Tab completions; {@code null} means "the platform's default". Never throws. */
    public List<String> complete(Object sender, String alias, String[] args) {
        return table.complete(this, sender, alias, args == null ? new String[0] : args);
    }

    /**
     * Whether {@code sender} may see and run this command: the bound command's permission, or the
     * permission the relay was installed with while nothing is bound.
     */
    public boolean visibleTo(Object sender) {
        CommandBinding current = bound;
        String node = current != null ? current.permission() : permanentPermission;
        return node.isEmpty() || table.audience().hasPermission(sender, node);
    }

    CommandBinding bound() {
        return bound;
    }

    void bind(CommandBinding binding) {
        this.bound = binding;
    }

    /** Clears the binding if it is still {@code binding}; answers whether it did. */
    synchronized boolean unbind(CommandBinding binding) {
        if (bound != binding) {
            return false;
        }
        bound = null;
        return true;
    }

    CommandPlatform.Handle handle() {
        return handle;
    }

    void handle(CommandPlatform.Handle value) {
        this.handle = value;
    }
}
