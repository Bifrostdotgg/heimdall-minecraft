package com.heimdall.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.testing.FakeCommandSource;
import com.heimdall.core.testing.FakePlatform;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.core.config.ServerRole;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /hd case note}: the checks that run before anything is sent. What goes on the wire is in
 * {@code ApiClientRequestTest}.
 */
class CaseSubcommandTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CaseSubcommand command = new CaseSubcommand();

    @TempDir
    Path dataDir;

    private HeimdallRuntime runtime;
    private AdminContext context;

    @BeforeEach
    void setUp() {
        FakePlatform platform = new FakePlatform(ServerRole.STANDALONE, dataDir);
        runtime = HeimdallRuntime.builder(logger, platform).build();
        context = AdminContext.builder(runtime).role(ServerRole.STANDALONE).build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    @DisplayName("a case number is digits, with or without a leading #")
    void parsesCaseNumbers() {
        assertEquals(1482, CaseSubcommand.parseCaseNumber("1482"));
        assertEquals(1482, CaseSubcommand.parseCaseNumber("#1482"));
        assertEquals(-1, CaseSubcommand.parseCaseNumber("0"));
        assertEquals(-1, CaseSubcommand.parseCaseNumber("-3"));
        assertEquals(-1, CaseSubcommand.parseCaseNumber("12a"));
        assertEquals(-1, CaseSubcommand.parseCaseNumber("1234567890"));
    }

    @Test
    @DisplayName("the console is refused: a note is signed by the account that wrote it")
    void consoleIsRefused() {
        FakeCommandSource console = FakeCommandSource.console();
        command.run(console, Arrays.asList("note", "12", "hello"), context);
        assertTrue(console.wasTold("Only a player"), console.messageText().toString());
    }

    @Test
    @DisplayName("a missing note or a bad number gets the usage line, not a request")
    void usage() {
        FakeCommandSource player = FakeCommandSource.player("Steve");
        command.run(player, Arrays.asList("note", "12"), context);
        assertTrue(player.wasTold("Usage"), player.messageText().toString());
        player.clearMessages();
        command.run(player, Arrays.asList("note", "abc", "hello"), context);
        assertTrue(player.wasTold("not a case number"), player.messageText().toString());
        assertFalse(player.wasTold("Adding a note"));
    }

    @Test
    @DisplayName("an oversized note fails before a round trip")
    void longNote() {
        FakeCommandSource player = FakeCommandSource.player("Steve");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= CaseSubcommand.MAX_NOTE_CHARS; i++) text.append('x');
        command.run(player, Arrays.asList("note", "12", text.toString()), context);
        assertTrue(player.wasTold("at most"), player.messageText().toString());
        assertFalse(player.wasTold("Adding a note"));
    }

    @Test
    @DisplayName("an unconfigured server says why the note was not added")
    void unconfigured() throws Exception {
        FakeCommandSource player = FakeCommandSource.player("Steve");
        command.run(player, Arrays.asList("note", "12", "spoke", "to", "them"), context);
        for (int i = 0; i < 50 && !player.wasTold("Could not add the note"); i++) {
            Thread.sleep(20);
        }
        assertTrue(player.wasTold("Could not add the note"), player.messageText().toString());
    }

    @Test
    @DisplayName("a failure is described by its cause, not its wrapper")
    void describesTheCause() {
        assertEquals("boom", CaseSubcommand.describe(
                new CompletionException(new IllegalStateException("boom"))));
    }
}
