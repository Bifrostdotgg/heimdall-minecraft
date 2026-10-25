package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.UnknownCommandException;
import com.heimdall.core.util.Registration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * Optional LiteBans hook and importer. Reflection so the jar loads without LiteBans.
 * Event types live in {@link LiteBansEventBridge}, loaded only when Events is present.
 */
final class LiteBansSupport {

    private static volatile Registration hook = Registration.NONE;

    /**
     * Whether this is a Bukkit server rather than a proxy. Read off the platform, not the
     * configured role, which an operator can override: it decides whether a bare {@code unban}
     * could belong to some other plugin.
     */
    private static final boolean BUKKIT = classPresent("org.bukkit.Bukkit");

    /** Non-null while LiteBans is hooked; the only way the module asks LiteBans anything. */
    private static volatile LiteBansRevokeLift.ActiveCheck activeCheck;

    private LiteBansSupport() {
    }

    static void tryHook(final HeimdallPunishmentsModule module, final ModuleContext context) {
        try {
            Class.forName("litebans.api.Events");
            Class<?> bridge = Class.forName("com.heimdall.module.punishments.LiteBansEventBridge");
            Object handle = bridge.getDeclaredMethod("install", ModuleContext.class,
                            HeimdallPunishmentsModule.class)
                    .invoke(null, context, module);
            if (handle instanceof Registration) {
                hook = (Registration) handle;
            }
            activeCheck = (LiteBansRevokeLift.ActiveCheck) Class
                    .forName("com.heimdall.module.punishments.LiteBansActiveCheck")
                    .getDeclaredConstructor()
                    .newInstance();
            context.logger().info("LiteBans is present; import and event hook are available");
        } catch (ClassNotFoundException absent) {
            context.logger().debug(() -> "LiteBans is not on the classpath; hook idle");
        } catch (Throwable failed) {
            context.logger().warn("LiteBans probe failed: " + failed);
        }
    }

    private static String ownServerId(ModuleContext context) {
        try {
            return context.api().settings().serverId();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    static void unhook() {
        Registration current = hook;
        hook = Registration.NONE;
        activeCheck = null;
        current.close();
    }

    /**
     * Lifts a bot-side revoke in LiteBans too, in hook mode. See {@link LiteBansRevokeLift}.
     *
     * <p>A no-op when LiteBans is not hooked on this server, or the guild is not in hook mode:
     * in replace mode Heimdall enforces, and a LiteBans still installed beside it holds bans
     * nobody asked this revoke to touch.
     */
    static void liftRevoked(final ModuleContext context, final Payload frame) {
        LiteBansRevokeLift.ActiveCheck check = activeCheck;
        if (check == null || context == null || frame == null) return;
        if (!PunishmentSettings.from(context.config()).hookMode()) return;
        // LiteBans lifted this one itself and the bridge reported it: nothing left to lift there.
        if ("hook".equals(frame.string("revokeSource", ""))) return;
        LiteBansRevokeLift.Request request = LiteBansRevokeLift.requestFor(
                frame.string("type", ""),
                frame.string("targetUuid", ""),
                frame.string("revokeCause", ""),
                frame.string("source", ""),
                frame.string("importProviderId", ""),
                ownServerId(context));
        if (request == null) return;
        LiteBansRevokeLift.lift(request, check, context.executors().io(),
                context.platform().console(), context.logger(), !BUKKIT);
    }

    /**
     * Whether LiteBans holds a ban on the player right now. False when LiteBans is not hooked
     * here or the lookup fails: the callers only use it to stand down, so not knowing means
     * carrying on. A database query, so never on a server's main thread.
     */
    static boolean banned(UUID uuid) {
        LiteBansRevokeLift.ActiveCheck check = activeCheck;
        if (check == null || uuid == null) return false;
        try {
            return check.isActive(LiteBansRevokeLift.Family.BAN, uuid);
        } catch (Throwable failed) {
            return false;
        }
    }

    /** Whether LiteBans is hooked on this server, so a command run here reaches it. */
    static boolean hooked() {
        return activeCheck != null;
    }

    /**
     * Bans a player who logged out while frozen, in hook mode, by running LiteBans' own
     * {@code ban} as the console. LiteBans then enforces it, and the event bridge reports it to
     * the bot like any other LiteBans ban, so it lands in Heimdall's records too.
     *
     * <p>The command line is built from a UUID, a length this plugin formatted and a fixed reason,
     * so nothing a person typed reaches it. Namespaced first for the reason
     * {@link LiteBansRevokeLift} gives, with the bare verb as the proxy fallback.
     */
    static void banFrozenLogout(ModuleContext context, UUID uuid, long seconds, boolean silent) {
        if (activeCheck == null || context == null || uuid == null) return;
        String length = FrozenPlayers.liteBansDuration(seconds);
        String args = uuid + (length == null ? "" : " " + length) + (silent ? " -s " : " ")
                + FrozenPlayers.LOGOUT_BAN_REASON;
        dispatchBan(context, "litebans:ban " + args, "ban " + args, !BUKKIT);
    }

    private static void dispatchBan(final ModuleContext context, final String command,
            final String bare, final boolean onProxy) {
        context.platform().console().dispatchCommand(command).whenComplete(
                new BiConsumer<String, Throwable>() {
                    @Override
                    public void accept(String ack, Throwable failure) {
                        if (failure == null) {
                            context.logger().info("Ran `" + command
                                    + "` for a player who logged out while frozen");
                            return;
                        }
                        Throwable cause = failure;
                        while ((cause instanceof CompletionException
                                || cause instanceof ExecutionException)
                                && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (onProxy && bare != null && cause instanceof UnknownCommandException) {
                            dispatchBan(context, bare, null, onProxy);
                            return;
                        }
                        context.logger().warn("A player logged out while frozen, but `" + command
                                + "` failed (" + cause + "). Ban them in LiteBans by hand.");
                    }
                });
    }

    /**
     * Stands in for a hooked LiteBans, so the revoke path can be driven end to end without one.
     * {@code null} unhooks it again. Tests only.
     */
    static void activeCheckForTest(LiteBansRevokeLift.ActiveCheck check) {
        activeCheck = check;
        LiteBansRevokeLift.clearForTest();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, LiteBansSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

}
