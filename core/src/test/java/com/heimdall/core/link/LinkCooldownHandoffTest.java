package com.heimdall.core.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.shell.contract.Handoff;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code /linkdiscord} cooldowns are the in-memory state a hot swap hands over (departure D87):
 * a swap an operator can run at any time must not hand every player a fresh allowance.
 */
class LinkCooldownHandoffTest {

    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    private final RecordingLogger logger = new RecordingLogger(true);

    @Test
    @DisplayName("running windows survive export, the handoff's own validation, and restore")
    void roundTrip() {
        LinkDiscordCommand before = new LinkDiscordCommand(logger, null);
        Map<String, Object> seeded = new HashMap<String, Object>();
        long started = System.currentTimeMillis() - 1_000L;
        seeded.put(PLAYER, Long.valueOf(started));
        before.restoreCooldowns(seeded);

        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put(LinkDiscordCommand.HANDOFF_KEY, before.exportCooldowns());
        Map<String, Object> crossed = Handoff.copyOf(state);

        LinkDiscordCommand after = new LinkDiscordCommand(logger, null);
        after.restoreCooldowns(crossed.get(LinkDiscordCommand.HANDOFF_KEY));

        assertEquals(Long.valueOf(started), after.exportCooldowns().get(PLAYER));
    }

    @Test
    @DisplayName("windows that have already ended are not handed over")
    void expiredWindowsAreDropped() {
        LinkDiscordCommand command = new LinkDiscordCommand(logger, null);
        Map<String, Object> seeded = new HashMap<String, Object>();
        seeded.put(PLAYER, Long.valueOf(
                System.currentTimeMillis() - LinkDiscordCommand.COOLDOWN_MS - 1_000L));
        command.restoreCooldowns(seeded);

        assertFalse(command.exportCooldowns().containsKey(PLAYER));
    }

    @Test
    @DisplayName("malformed handed-over entries are skipped, never fatal")
    void malformedIsSkipped() {
        LinkDiscordCommand command = new LinkDiscordCommand(logger, null);
        Map<String, Object> seeded = new HashMap<String, Object>();
        seeded.put("not-a-uuid", Long.valueOf(System.currentTimeMillis()));
        seeded.put(PLAYER, "not a number");

        command.restoreCooldowns(seeded);
        command.restoreCooldowns("not a map");
        command.restoreCooldowns(null);

        assertTrue(command.exportCooldowns().isEmpty());
    }
}
