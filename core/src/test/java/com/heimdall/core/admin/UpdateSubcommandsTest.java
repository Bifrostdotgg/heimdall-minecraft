package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.FakeCommandSource;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The core's {@code swap} verb, which only runs when the shell did not intercept it, checks the
 * same permission the shell's does (departure D87).
 */
class UpdateSubcommandsTest {

    @Test
    @DisplayName("swap without heimdall.admin is refused before anything else is said")
    void swapNeedsThePermission() {
        FakeCommandSource player = FakeCommandSource.player("Steve");

        new UpdateSubcommands.Swap().run(player, Collections.<String>emptyList(), null);

        assertTrue(player.wasTold("do not have permission"), player.messageText().toString());
        assertFalse(player.wasTold("shell did not answer"), player.messageText().toString());
    }

    @Test
    @DisplayName("swap with heimdall.admin explains that the shell did not answer")
    void swapWithThePermission() {
        FakeCommandSource admin = FakeCommandSource.player("Alex").grant(AdminCommand.PERMISSION);

        new UpdateSubcommands.Swap().run(admin, Collections.<String>emptyList(), null);

        assertTrue(admin.wasTold("shell did not answer"), admin.messageText().toString());
    }
}
