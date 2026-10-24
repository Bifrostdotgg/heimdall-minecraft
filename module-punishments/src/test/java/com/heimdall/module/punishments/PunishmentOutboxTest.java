package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishmentOutboxTest {

    @TempDir
    Path dir;

    @Test
    void duplicateOpIdIsSkippedAndOrderIsIssuedAtThenServerId() {
        PunishmentOutbox box = new PunishmentOutbox(new RecordingLogger(true), dir.resolve("outbox.json"));
        Payload payload = Payload.builder().put("type", "ban").build();
        assertTrue(box.enqueue(new PunishmentOutbox.Entry("b", 2L, "a", "issue", payload)));
        assertTrue(box.enqueue(new PunishmentOutbox.Entry("a", 1L, "z", "issue", payload)));
        assertFalse(box.enqueue(new PunishmentOutbox.Entry("a", 9L, "z", "issue", payload)));
        assertTrue(box.enqueue(new PunishmentOutbox.Entry("c", 1L, "a", "revoke", payload)));

        List<PunishmentOutbox.Entry> snap = box.snapshot();
        assertEquals(3, snap.size());
        assertEquals("c", snap.get(0).opId);
        assertEquals("a", snap.get(1).opId);
        assertEquals("b", snap.get(2).opId);
    }

    @Test
    void takeReturnsTheEntryAsItIsNowSoAMarkMadeMidUploadIsSeen() {
        PunishmentOutbox box = new PunishmentOutbox(new RecordingLogger(true), dir.resolve("outbox.json"));
        box.enqueue(new PunishmentOutbox.Entry("op-w", 1L, "lobby", "issue",
                Payload.builder().put("type", "warn").build()));
        PunishmentOutbox.Entry uploading = box.snapshot().get(0);

        assertTrue(box.markIssueDelivered("op-w"), "the warn is shown while its upload is in flight");
        PunishmentOutbox.Entry taken = box.take("op-w");

        assertFalse(uploading.payload.bool("delivered", false), "the upload sent the unmarked copy");
        assertTrue(taken.payload.bool("delivered", false), "and the flush sees the mark");
        assertTrue(box.isEmpty());
        assertNull(box.take("op-w"));
    }

    @Test
    void reloadKeepsUnackedRows() {
        Path file = dir.resolve("outbox.json");
        PunishmentOutbox first = new PunishmentOutbox(new RecordingLogger(true), file);
        first.enqueue(new PunishmentOutbox.Entry("op-1", 10L, "survival", "issue",
                Payload.builder().put("type", "mute").build()));

        PunishmentOutbox second = new PunishmentOutbox(new RecordingLogger(true), file);
        assertEquals(1, second.size());
        assertEquals("op-1", second.snapshot().get(0).opId);
        second.remove("op-1");
        assertTrue(second.isEmpty());
    }
}
