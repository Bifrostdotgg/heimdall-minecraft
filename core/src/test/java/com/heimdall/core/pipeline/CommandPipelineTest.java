package com.heimdall.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.text.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandPipelineTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    @DisplayName("label strips slash, namespace, and is the first token only")
    void labelStripsNamespace() {
        assertEquals("msg", CommandAttempt.commandLabel("/msg Steve hi"));
        assertEquals("msg", CommandAttempt.commandLabel("/minecraft:msg Steve"));
        assertEquals("msg", CommandAttempt.commandLabel("/essentials:msg"));
        assertEquals("msg", CommandAttempt.commandLabel("msg"));
        assertEquals("me", CommandAttempt.commandLabel("/ME"));
        assertEquals("menu", CommandAttempt.commandLabel("/menu"));
        assertEquals("msg", CommandAttempt.commandLabel("  /Msg   Steve"));
    }

    @Test
    @DisplayName("a deny cancels; abstain falls through to allow")
    void denyWinsAllowIsDefault() {
        CommandPipeline pipeline = new CommandPipeline(logger);
        pipeline.register(attempt -> Verdict.abstain(), 10);
        assertTrue(pipeline.dispatch(cmd("/spawn")).isDeny() == false);

        pipeline.register(attempt -> {
            if ("msg".equals(attempt.label())) {
                return Verdict.deny(Msg.legacy("&cYou are muted."));
            }
            return Verdict.abstain();
        }, 50, "mute");

        Verdict blocked = pipeline.dispatch(cmd("/essentials:msg Steve hi"));
        assertTrue(blocked.isDeny());
        Verdict allowed = pipeline.dispatch(cmd("/spawn"));
        assertTrue(allowed.isDeny() == false);
    }

    @Test
    @DisplayName("a throwing interceptor does not cancel the command")
    void throwFailsOpen() {
        CommandPipeline pipeline = new CommandPipeline(logger);
        pipeline.register(attempt -> {
            throw new IllegalStateException("boom");
        }, 1, "broken");
        assertTrue(pipeline.dispatch(cmd("/msg")).isDeny() == false,
                "fail-open: a broken mute check must not silence commands");
        assertFalse(logger.records().isEmpty());
    }

    @Test
    @DisplayName("first denial wins")
    void firstDenyWins() {
        CommandPipeline pipeline = new CommandPipeline(logger);
        final List<String> ran = new ArrayList<String>();
        pipeline.register(attempt -> {
            ran.add("early");
            return Verdict.deny(Msg.legacy("early"));
        }, 1);
        pipeline.register(attempt -> {
            ran.add("late");
            return Verdict.deny(Msg.legacy("late"));
        }, 50);
        Verdict verdict = pipeline.dispatch(cmd("/msg"));
        assertEquals("early", ran.get(0));
        assertEquals(1, ran.size());
        assertTrue(verdict.isDeny());
    }

    private static CommandAttempt cmd(String raw) {
        return CommandAttempt.of(STEVE, "Steve", raw);
    }
}
