package com.heimdall.platform.bukkit.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.testing.TestText;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Paper's dialog API as it really is, not as the binding remembers it.
 *
 * <p>This module's test classpath carries {@code io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT},
 * the first Paper with dialogs. Every lookup {@link PaperLinkDialogs} makes is resolved against
 * that jar here, so a method Paper renames or re-types fails this build instead of quietly turning
 * every dialog into chat on a customer's server.
 *
 * <p>Paper's builders are service-loaded from the server implementation, which a test has none of.
 * {@link FakeDialogServer} stands in for it, so the API's own static methods run for real and every
 * argument the binding passed through reflection can be checked. What no test here can show is a
 * real server turning that into packets, or a real client drawing it.
 */
class PaperLinkDialogsTest {

    /** 2026-10-19T12:00:00Z. */
    private static final long NOON = 1_792_411_200_000L;

    private final RecordingLogger logger = new RecordingLogger(true);

    @Test
    @DisplayName("every lookup resolves against paper-api 1.21.7, with the copy button available")
    void resolvesAgainstTheRealApi() throws Exception {
        PaperLinkDialogs.Lookups found = PaperLinkDialogs.Lookups.resolve();

        for (Field field : PaperLinkDialogs.Lookups.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertNotNull(field.get(found), field.getName() + " did not resolve");
        }
        // The two that matter most, named, so a failure says which half of the API moved.
        assertEquals("io.papermc.paper.dialog.Dialog", found.create.getDeclaringClass().getName());
        assertEquals("net.kyori.adventure.audience.Audience",
                found.showDialog.getDeclaringClass().getName());
        assertEquals(DialogLike.class, found.showDialog.getParameterTypes()[0]);

        PaperLinkDialogs dialogs = PaperLinkDialogs.tryCreate(logger);
        assertNotNull(dialogs);
        assertTrue(dialogs.describe().contains("copy button"), dialogs.describe());
    }

    @Test
    @DisplayName("Player#getProtocolVersion is found where Paper declares it, on NetworkClient")
    void protocolVersionIsPapers() throws Exception {
        Method method = PaperLinkDialogs.Lookups.resolve().protocolVersion;
        assertEquals("com.destroystokyo.paper.network.NetworkClient",
                method.getDeclaringClass().getName());
        assertEquals(int.class, method.getReturnType());
    }

    @Test
    @DisplayName("only a 1.21.6 or newer client is offered a dialog")
    void clientVersionGate() {
        PaperLinkDialogs dialogs = PaperLinkDialogs.tryCreate(logger);

        assertTrue(dialogs.canShow(player(772)), "1.21.7");
        assertTrue(dialogs.canShow(player(PaperLinkDialogs.DIALOG_PROTOCOL)), "1.21.6");
        assertFalse(dialogs.canShow(player(770)), "1.21.5 through ViaBackwards has no dialog screen");
        assertFalse(dialogs.canShow(player(-1)), "a client nobody can place is not guessed at");
    }

    // ClickEvent#value() is deprecated in the server's Adventure 4.23 for payload(), which core's
    // 4.13 does not have; value() reads the same string on both.
    @SuppressWarnings("deprecation")
    @Test
    @DisplayName("the dialog: title, the code in bold green, the instruction, the expiry, a copy button")
    void buildsAndSendsTheDialog() {
        FakeDialogServer.Recorder server = FakeDialogServer.start();
        PaperLinkDialogs dialogs = PaperLinkDialogs.tryCreate(logger);
        Player steve = player(772);

        assertTrue(dialogs.show(steve,
                LinkCodePrompt.of("123456", "2026-10-19T12:05:00Z", "@lerndmina (Wild)"), NOON));

        verify(steve).showDialog(server.dialog);
        assertEquals(LinkCodePrompt.TITLE, TestText.plain(server.title));

        assertEquals(5, server.body.size(),
                "code, instruction, expiry, requester, warning: " + server.body);
        Component code = server.body.get(0);
        assertEquals("123456", TestText.plain(code));
        assertEquals(NamedTextColor.GREEN, colourOf(code));
        assertTrue(isBold(code), "the code is the thing to read; it is bold: " + code);
        assertEquals(LinkCodePrompt.INSTRUCTION, TestText.plain(server.body.get(1)));
        assertEquals("This code expires in about 5 minutes.", TestText.plain(server.body.get(2)));
        assertEquals("Requested by @lerndmina (Wild) in Discord.", TestText.plain(server.body.get(3)));
        assertEquals(LinkCodePrompt.WARNING, TestText.plain(server.body.get(4)));

        verify(server.base).canCloseWithEscape(true);
        verify(server.base).body(anyList());

        assertEquals(LinkCodePrompt.COPY_LABEL, TestText.plain(server.buttonLabel));
        ArgumentCaptor<Component> tooltip = ArgumentCaptor.forClass(Component.class);
        verify(server.button).tooltip(tooltip.capture());
        assertEquals(LinkCodePrompt.COPY_TOOLTIP, TestText.plain(tooltip.getValue()));
        assertNotNull(server.click, "the button carries the copy action itself");
        assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD, server.click.action());
        assertEquals("123456", server.click.value());
        verify(server.button).action(any(DialogAction.class));
        assertSame(server.builtButton, server.noticeButton, "a notice, with the copy button as its one button");

        verify(server.entry).base(server.builtBase);
        verify(server.entry).type(server.notice);
    }

    @Test
    @DisplayName("with no expiry and no requester, those lines go and the warning stays")
    void noExpiryLine() {
        FakeDialogServer.Recorder server = FakeDialogServer.start();

        assertTrue(PaperLinkDialogs.tryCreate(logger)
                .show(player(772), LinkCodePrompt.of("123456", null), NOON));

        assertEquals(3, server.body.size(),
                "code, instruction and the warning, which is never dropped: " + server.body);
        assertEquals(LinkCodePrompt.WARNING, TestText.plain(server.body.get(2)));
    }

    @Test
    @DisplayName("a server that cannot build the dialog fails cleanly, so the caller falls back to chat")
    void failsCleanly() {
        FakeDialogServer.current = null;
        PaperLinkDialogs dialogs = PaperLinkDialogs.tryCreate(logger);
        Player steve = player(772);

        assertFalse(dialogs.show(steve, LinkCodePrompt.of("123456", null), NOON));

        verify(steve, never()).showDialog(any(DialogLike.class));
        assertTrue(logger.logged(LogLevel.DEBUG, "this test server cannot build dialogs"),
                "the cause reaches the debug log unwrapped: " + logger.records());
    }

    @AfterEach
    void stopServer() {
        FakeDialogServer.current = null;
    }

    private static TextColor colourOf(Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor colour = colourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

    private static boolean isBold(Component component) {
        if (component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        for (Component child : component.children()) {
            if (isBold(child)) {
                return true;
            }
        }
        return false;
    }

    private static Player player(int protocol) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Steve".getBytes()));
        when(player.getName()).thenReturn("Steve");
        when(player.getProtocolVersion()).thenReturn(protocol);
        return player;
    }
}
