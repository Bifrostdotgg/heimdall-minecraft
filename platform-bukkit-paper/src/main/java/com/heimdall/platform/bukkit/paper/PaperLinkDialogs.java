package com.heimdall.platform.bukkit.paper;

import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.platform.bukkit.adapter.LinkDialogPresenter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

/**
 * A link code as a Paper dialog: a screen over the game with the code, the instruction, the expiry
 * and a button that copies the code.
 *
 * <h2>Entirely reflective, and why</h2>
 *
 * <p>Paper's dialog API arrived in 1.21.7, takes and returns the <em>server's</em> Adventure types,
 * and is Java 21 bytecode. Heimdall's core jar relocates its own Adventure to
 * {@code com.heimdall.libs.kyori}, and Shadow rewrites every {@code net.kyori} reference in every
 * class it merges, including a call site's descriptor, so a plain {@code player.showDialog(dialog)}
 * compiled against Paper's API would ask for a relocated {@code DialogLike} that the server does
 * not have. That is the same wall {@code VelocityText} documents (departure D44) and the reason
 * Paper's {@code AsyncChatEvent} is not used (D43). So every call here goes through a
 * {@link Method} looked up once in {@link #tryCreate}, the Adventure class names are joined from
 * fragments at runtime so the remapper cannot rewrite them, and this module stays Java 8 bytecode
 * that loads, and declines, on every server back to Spigot 1.8.8.
 *
 * <p>Text crosses over the way {@code VelocityText} does it: our component, to {@code §}-coded
 * legacy text, to the server's component. Colour and bold survive; nothing in the dialog body
 * needs a click or a hover, because the button carries the copy action itself.
 *
 * <h2>The client has to be able to draw it</h2>
 *
 * <p>Dialogs are a 1.21.6 client screen (protocol 771). The server having the API does not mean
 * the client does: with ViaBackwards, a 1.21.4 client plays on a 1.21.7 server. ViaVersion, where
 * installed, is asked first because it is the one that knows the client's real version; Paper's
 * own {@code getProtocolVersion()} answers otherwise. A client that cannot be placed is not shown a
 * dialog, because a dialog sent to a client without the screen is a code the player never sees.
 *
 * <p>Verified against {@code io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT} by this module's
 * tests, which resolve every lookup below against the real jar.
 */
public final class PaperLinkDialogs implements LinkDialogPresenter {

    /** The first client protocol with a dialog screen: Minecraft 1.21.6. */
    static final int DIALOG_PROTOCOL = 771;

    /** Fragments, joined at runtime; see {@code VelocityText} for why nothing here may be a literal. */
    private static final String[] ADVENTURE = {"net", "kyori", "adventure"};

    private static final String DIALOG = "io.papermc.paper.dialog.Dialog";
    private static final String REGISTRY_BUILDER_FACTORY =
            "io.papermc.paper.registry.RegistryBuilderFactory";
    private static final String DIALOG_DATA = "io.papermc.paper.registry.data.dialog.";
    private static final String VIA = "com.viaversion.viaversion.api.Via";

    private final HeimdallLogger logger;

    // The server's Adventure.
    private final Object legacySerializer;
    private final Method deserialize;
    private final Method copyToClipboard;
    private final Method showDialog;

    // Paper's dialog API.
    private final Method create;
    private final Method empty;
    private final Method entryBase;
    private final Method entryType;
    private final Method baseBuilder;
    private final Method baseCanCloseWithEscape;
    private final Method baseBody;
    private final Method baseBuild;
    private final Method plainMessage;
    private final Method buttonBuilder;
    private final Method buttonTooltip;
    private final Method buttonAction;
    private final Method buttonBuild;
    private final Method staticAction;
    private final Method notice;

    // The client's version.
    private final Method protocolVersion;

    private PaperLinkDialogs(HeimdallLogger logger, Lookups found) {
        this.logger = logger;
        this.legacySerializer = found.legacySerializer;
        this.deserialize = found.deserialize;
        this.copyToClipboard = found.copyToClipboard;
        this.showDialog = found.showDialog;
        this.create = found.create;
        this.empty = found.empty;
        this.entryBase = found.entryBase;
        this.entryType = found.entryType;
        this.baseBuilder = found.baseBuilder;
        this.baseCanCloseWithEscape = found.baseCanCloseWithEscape;
        this.baseBody = found.baseBody;
        this.baseBuild = found.baseBuild;
        this.plainMessage = found.plainMessage;
        this.buttonBuilder = found.buttonBuilder;
        this.buttonTooltip = found.buttonTooltip;
        this.buttonAction = found.buttonAction;
        this.buttonBuild = found.buttonBuild;
        this.staticAction = found.staticAction;
        this.notice = found.notice;
        this.protocolVersion = found.protocolVersion;
    }

    /**
     * Resolves every lookup, or none.
     *
     * <p>Called reflectively by {@code BukkitAdapters}. All of it happens here, at enable, so a
     * Paper that renamed one method in a later release is found out once, in the debug log, rather
     * than on the first player with a code.
     *
     * @return the presenter, or {@code null} where this server cannot send a dialog
     */
    public static PaperLinkDialogs tryCreate(HeimdallLogger logger) {
        try {
            return new PaperLinkDialogs(logger, Lookups.resolve());
        } catch (Throwable unavailable) {
            logger.debug(() -> "no Paper dialog API on this server (" + unavailable
                    + "); link codes are shown in chat");
            return null;
        }
    }

    @Override
    public String describe() {
        return "paper dialogs (" + (copyToClipboard != null ? "copy button" : "close button")
                + ", client version from " + (viaApi() != null ? "ViaVersion" : "Paper") + ")";
    }

    @Override
    public boolean canShow(Player player) {
        int protocol = clientProtocol(player);
        return protocol >= DIALOG_PROTOCOL;
    }

    @Override
    public boolean show(Player player, LinkCodePrompt prompt, long nowMs) {
        try {
            Object dialog = dialog(prompt, nowMs);
            showDialog.invoke(player, dialog);
            return true;
        } catch (Throwable failed) {
            Throwable cause = unwrap(failed);
            logger.debug(() -> "could not show a link dialog to " + safeName(player) + ": " + cause);
            return false;
        }
    }

    /** Builds the dialog. Needs a running server: Paper's builders are service-loaded. */
    Object dialog(LinkCodePrompt prompt, long nowMs) throws ReflectiveOperationException {
        List<Object> body = new ArrayList<Object>(5);
        body.add(plainMessage.invoke(null, server(Component.text(prompt.code(), NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD))));
        body.add(plainMessage.invoke(null, server(Component.text(LinkCodePrompt.INSTRUCTION))));
        String expiry = prompt.expiryText(nowMs);
        if (!expiry.isEmpty()) {
            body.add(plainMessage.invoke(null, server(Component.text(expiry, NamedTextColor.GRAY))));
        }
        String requestedBy = prompt.requestedByText();
        if (!requestedBy.isEmpty()) {
            body.add(plainMessage.invoke(null, server(Component.text(requestedBy))));
        }
        // Always: the code is only ever for the player to type, never to read out to somebody.
        body.add(plainMessage.invoke(null,
                server(Component.text(LinkCodePrompt.WARNING, NamedTextColor.RED))));

        Object base = baseBuilder.invoke(null, server(Component.text(LinkCodePrompt.TITLE)));
        baseCanCloseWithEscape.invoke(base, Boolean.TRUE);
        baseBody.invoke(base, body);
        final Object builtBase = baseBuild.invoke(base);
        final Object type = notice.invoke(null, button(prompt));

        return create.invoke(null, new Consumer<Object>() {
            @Override
            public void accept(Object factory) {
                try {
                    Object entry = empty.invoke(factory);
                    entryBase.invoke(entry, builtBase);
                    entryType.invoke(entry, type);
                } catch (ReflectiveOperationException failed) {
                    throw new IllegalStateException("could not fill the dialog entry", unwrap(failed));
                }
            }
        });
    }

    /** "Copy code", which copies it, or a plain "Close" where the server cannot copy. */
    private Object button(LinkCodePrompt prompt) throws ReflectiveOperationException {
        if (copyToClipboard == null) {
            return buttonBuild.invoke(
                    buttonBuilder.invoke(null, server(Component.text(LinkCodePrompt.CLOSE_LABEL))));
        }
        Object builder = buttonBuilder.invoke(null, server(Component.text(LinkCodePrompt.COPY_LABEL)));
        buttonTooltip.invoke(builder, server(Component.text(LinkCodePrompt.COPY_TOOLTIP)));
        Object click = copyToClipboard.invoke(null, prompt.code());
        buttonAction.invoke(builder, staticAction.invoke(null, click));
        return buttonBuild.invoke(builder);
    }

    /** Our component as the server's, via legacy text. */
    private Object server(Component component) throws ReflectiveOperationException {
        return deserialize.invoke(legacySerializer, Msg.toLegacy(component));
    }

    /**
     * The client's protocol, or {@code -1} if neither ViaVersion nor Paper can say.
     *
     * <p>Package-private for the tests, which have no ViaVersion and no server.
     */
    int clientProtocol(Player player) {
        int via = viaProtocol(player.getUniqueId());
        if (via > 0) {
            return via;
        }
        if (protocolVersion == null) {
            return -1;
        }
        try {
            Object answer = protocolVersion.invoke(player);
            return answer instanceof Integer ? ((Integer) answer).intValue() : -1;
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    /**
     * ViaVersion's view of the client, {@code -1} without it.
     *
     * <p>Looked up per call rather than once: ViaVersion can be enabled after Heimdall, and its
     * answer is the right one whenever it is there. {@code getPlayerProtocolVersion} is ViaVersion
     * 5; {@code getPlayerVersion} is the int form 4.x had and 5.x keeps as a deprecated default.
     */
    private static int viaProtocol(UUID uuid) {
        Object api = viaApi();
        if (api == null) {
            return -1;
        }
        try {
            Object version = api.getClass().getMethod("getPlayerProtocolVersion", UUID.class)
                    .invoke(api, uuid);
            Object number = version == null ? null : version.getClass().getMethod("getVersion")
                    .invoke(version);
            if (number instanceof Integer) {
                return ((Integer) number).intValue();
            }
        } catch (Throwable olderVia) {
            // Fall through to the 4.x spelling.
        }
        try {
            Object number = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, uuid);
            return number instanceof Integer ? ((Integer) number).intValue() : -1;
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    private static Object viaApi() {
        try {
            return Class.forName(VIA).getMethod("getAPI").invoke(null);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static String safeName(Player player) {
        try {
            return player.getName();
        } catch (Throwable gone) {
            return "a player";
        }
    }

    private static Throwable unwrap(Throwable failed) {
        Throwable cause = failed;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String adventure(String... rest) {
        StringBuilder name = new StringBuilder();
        for (String part : ADVENTURE) {
            name.append(part).append('.');
        }
        for (int i = 0; i < rest.length; i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(rest[i]);
        }
        return name.toString();
    }

    /** Every lookup, resolved in one place so a missing one fails the whole binding at enable. */
    static final class Lookups {

        Object legacySerializer;
        Method deserialize;
        Method copyToClipboard;
        Method showDialog;
        Method create;
        Method empty;
        Method entryBase;
        Method entryType;
        Method baseBuilder;
        Method baseCanCloseWithEscape;
        Method baseBody;
        Method baseBuild;
        Method plainMessage;
        Method buttonBuilder;
        Method buttonTooltip;
        Method buttonAction;
        Method buttonBuild;
        Method staticAction;
        Method notice;
        Method protocolVersion;

        static Lookups resolve() throws ReflectiveOperationException {
            Lookups found = new Lookups();

            // Probed first, so a server without dialogs stops here with one cheap ClassNotFound.
            Class<?> dialog = type(DIALOG);

            Class<?> component = type(adventure("text", "Component"));
            Class<?> legacy = type(
                    adventure("text", "serializer", "legacy", "LegacyComponentSerializer"));
            found.legacySerializer = legacy.getMethod("legacySection").invoke(null);
            found.deserialize = legacy.getMethod("deserialize", String.class);
            Class<?> clickEvent = type(adventure("text", "event", "ClickEvent"));
            try {
                found.copyToClipboard = clickEvent.getMethod("copyToClipboard", String.class);
            } catch (NoSuchMethodException noCopy) {
                found.copyToClipboard = null;
            }
            Class<?> dialogLike = type(adventure("dialog", "DialogLike"));
            found.showDialog = type(adventure("audience", "Audience"))
                    .getMethod("showDialog", dialogLike);

            found.create = dialog.getMethod("create", Consumer.class);
            found.empty = type(REGISTRY_BUILDER_FACTORY).getMethod("empty");

            Class<?> base = type(DIALOG_DATA + "DialogBase");
            Class<?> dialogType = type(DIALOG_DATA + "type.DialogType");
            Class<?> entryBuilder = type(DIALOG_DATA + "DialogRegistryEntry$Builder");
            found.entryBase = entryBuilder.getMethod("base", base);
            found.entryType = entryBuilder.getMethod("type", dialogType);

            found.baseBuilder = base.getMethod("builder", component);
            Class<?> baseBuilder = type(DIALOG_DATA + "DialogBase$Builder");
            found.baseCanCloseWithEscape = baseBuilder.getMethod("canCloseWithEscape", boolean.class);
            found.baseBody = baseBuilder.getMethod("body", List.class);
            found.baseBuild = baseBuilder.getMethod("build");

            found.plainMessage =
                    type(DIALOG_DATA + "body.DialogBody").getMethod("plainMessage", component);

            Class<?> button = type(DIALOG_DATA + "ActionButton");
            found.buttonBuilder = button.getMethod("builder", component);
            Class<?> buttonBuilder = type(DIALOG_DATA + "ActionButton$Builder");
            Class<?> action = type(DIALOG_DATA + "action.DialogAction");
            found.buttonTooltip = buttonBuilder.getMethod("tooltip", component);
            found.buttonAction = buttonBuilder.getMethod("action", action);
            found.buttonBuild = buttonBuilder.getMethod("build");
            found.staticAction = action.getMethod("staticAction", clickEvent);
            found.notice = dialogType.getMethod("notice", button);

            try {
                // Paper's NetworkClient, which Player extends. Old enough to be on every Paper
                // that has dialogs; optional anyway, since ViaVersion can answer instead.
                found.protocolVersion = Player.class.getMethod("getProtocolVersion");
            } catch (NoSuchMethodException noProtocol) {
                found.protocolVersion = null;
            }
            return found;
        }

        /**
         * Loads a class without initialising it.
         *
         * <p>{@code Dialog}'s static initialiser reaches into the server's registries, so running
         * it here would tie a lookup to a server being fully up. Left alone, it runs on the first
         * {@code Dialog.create}, which only ever happens with a player online.
         */
        private static Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, false, PaperLinkDialogs.class.getClassLoader());
        }
    }
}
