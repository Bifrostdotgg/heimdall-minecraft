package com.heimdall.module.punishments;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.util.Registration;

/**
 * Optional LiteBans hook and importer. Reflection so the jar loads without LiteBans.
 * Event types live in {@link LiteBansEventBridge}, loaded only when Events is present.
 */
final class LiteBansSupport {

    private static volatile Registration hook = Registration.NONE;

    /** Non-null while LiteBans is hooked; the only way the module asks LiteBans anything. */
    private static volatile LiteBansRevokeLift.ActiveCheck activeCheck;

    private LiteBansSupport() {
    }

    static void tryHook(final HeimdallPunishmentsModule module, final ModuleContext context) {
        try {
            Class.forName("litebans.api.Events");
            Class<?> bridge = Class.forName("com.heimdall.module.punishments.LiteBansEventBridge");
            Object handle = bridge.getDeclaredMethod("install", ModuleContext.class)
                    .invoke(null, context);
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
        LiteBansRevokeLift.Request request = LiteBansRevokeLift.requestFor(
                frame.string("type", ""),
                frame.string("targetUuid", ""),
                frame.string("revokeCause", ""),
                frame.string("source", ""),
                frame.string("importProviderId", ""));
        if (request == null) return;
        LiteBansRevokeLift.lift(request, check, context.executors().io(),
                context.platform().console(), context.logger(),
                context.platform().role() == ServerRole.GATEKEEPER);
    }

    /**
     * Stands in for a hooked LiteBans, so the revoke path can be driven end to end without one.
     * {@code null} unhooks it again. Tests only.
     */
    static void activeCheckForTest(LiteBansRevokeLift.ActiveCheck check) {
        activeCheck = check;
        LiteBansRevokeLift.clearForTest();
    }

    static void importNow(final ModuleContext context) {
        PunishmentImporters.importAll(context);
    }
}
