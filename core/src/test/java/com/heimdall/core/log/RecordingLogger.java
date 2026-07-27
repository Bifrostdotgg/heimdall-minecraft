package com.heimdall.core.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link HeimdallLogger} that keeps what it was told, so a test can assert on it.
 *
 * <p>Shared across the whole core test suite rather than re-declared per test class: a no-op logger
 * copied into five test files is five places that stop reflecting the interface the moment it
 * gains a method, and none of them can answer "did this actually warn?".
 *
 * <p>Thread-safe — the executor and mirror tests log from pool threads.
 */
public final class RecordingLogger extends AbstractHeimdallLogger {

    /** One captured log call. */
    public static final class Record {

        public final LogLevel level;
        public final String message;
        public final Throwable throwable;

        Record(LogLevel level, String message, Throwable throwable) {
            this.level = level;
            this.message = message;
            this.throwable = throwable;
        }

        @Override
        public String toString() {
            return level + ": " + message + (throwable == null ? "" : " (" + throwable + ")");
        }
    }

    private final List<Record> records = Collections.synchronizedList(new ArrayList<Record>());

    public RecordingLogger() {
        super(false);
    }

    public RecordingLogger(boolean debugEnabled) {
        super(debugEnabled);
    }

    @Override
    protected void write(LogLevel level, String message, Throwable throwable) {
        records.add(new Record(level, message, throwable));
    }

    /** Every record captured so far, in order. */
    public List<Record> records() {
        synchronized (records) {
            return new ArrayList<Record>(records);
        }
    }

    /** The records at one level, in order. */
    public List<Record> at(LogLevel level) {
        List<Record> matching = new ArrayList<Record>();
        for (Record record : records()) {
            if (record.level == level) {
                matching.add(record);
            }
        }
        return matching;
    }

    /** The messages at one level, in order. */
    public List<String> messagesAt(LogLevel level) {
        List<String> messages = new ArrayList<String>();
        for (Record record : at(level)) {
            messages.add(record.message);
        }
        return messages;
    }

    /** Whether any record at {@code level} contains {@code needle}. */
    public boolean logged(LogLevel level, String needle) {
        for (Record record : at(level)) {
            if (record.message != null && record.message.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        records.clear();
    }
}
