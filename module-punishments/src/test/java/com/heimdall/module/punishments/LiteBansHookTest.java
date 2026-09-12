package com.heimdall.module.punishments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import litebans.api.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the LiteBans hook puts on the wire.
 *
 * <p>The hook is the path nobody runs locally: it only fires on a server that has LiteBans
 * installed and is in hook mode, so a wrong payload here is found by a customer rather than by a
 * developer. Everything asserted is a pure function of one {@link Entry}, which is why the bridge
 * builds its payload in a method that takes one rather than inside the listener.
 */
class LiteBansHookTest {

    private static final long START = 1_700_000_000_000L;

    @Test
    @DisplayName("a hooked tempban carries its length in both units")
    void hookedTempbanCarriesBothUnits() {
        Payload body = LiteBansEventBridge.issueBody(
                entry("ban", START, START + 7L * 86_400_000L), "salt", new RecordingLogger());

        assertNotNull(body);
        assertEquals(7L * 86_400L, body.longValue("durationSeconds", -1L));
        assertEquals(7L * 1440L, body.longValue("durationMinutes", -1L),
                "a bot that predates the seconds field reads only this one, and with neither key "
                        + "it makes every temporary ban permanent");
    }

    @Test
    @DisplayName("a length under a minute still arrives as a minute for the older reader")
    void shortLengthsRoundUpForTheMinutesKey() {
        Payload body = LiteBansEventBridge.issueBody(
                entry("mute", START, START + 30_000L), "salt", new RecordingLogger());

        assertNotNull(body);
        assertEquals(30L, body.longValue("durationSeconds", -1L));
        assertEquals(1L, body.longValue("durationMinutes", -1L),
                "floored it would be zero, which an older bot reads as no length at all");
    }

    @Test
    @DisplayName("a permanent hooked ban carries no length keys at all")
    void permanentCarriesNoLength() {
        Payload body = LiteBansEventBridge.issueBody(
                entry("ban", START, 0L), "salt", new RecordingLogger());

        assertNotNull(body);
        assertFalse(body.has("durationSeconds"), body.toJson());
        assertFalse(body.has("durationMinutes"), body.toJson());
    }

    @Test
    @DisplayName("a type the bot does not mirror is not posted")
    void unmappedTypesAreDropped() {
        assertNull(LiteBansEventBridge.issueBody(
                entry("note", START, 0L), "salt", new RecordingLogger()));
    }

    @Test
    @DisplayName("an IP ban with no salt pushed yet is skipped rather than sent unhashed")
    void ipBanNeedsTheSalt() {
        RecordingLogger logger = new RecordingLogger();
        Entry ipban = new FakeEntry("ban", START, 0L).ipban("203.0.113.9");

        assertNull(LiteBansEventBridge.issueBody(ipban, "", logger));
        assertTrue(logger.records().toString().contains("ip salt"), logger.records().toString());
    }

    private static Entry entry(String type, long start, long end) {
        return new FakeEntry(type, start, end);
    }

    /** One LiteBans row, with only the fields the bridge reads. */
    static final class FakeEntry extends Entry {

        private final String type;
        private final long start;
        private final long end;
        private String ip;
        private boolean ipban;

        FakeEntry(String type, long start, long end) {
            this.type = type;
            this.start = start;
            this.end = end;
        }

        FakeEntry ipban(String address) {
            this.ip = address;
            this.ipban = true;
            return this;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getUuid() {
            return "11111111111111111111111111111111";
        }

        @Override
        public String getIp() {
            return ip;
        }

        @Override
        public String getReason() {
            return "griefing";
        }

        @Override
        public String getExecutorUUID() {
            return null;
        }

        @Override
        public String getExecutorName() {
            return "Adam";
        }

        @Override
        public long getDateStart() {
            return start;
        }

        @Override
        public long getDateEnd() {
            return end;
        }

        @Override
        public boolean isSilent() {
            return false;
        }

        @Override
        public boolean isIpban() {
            return ipban;
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }
}
