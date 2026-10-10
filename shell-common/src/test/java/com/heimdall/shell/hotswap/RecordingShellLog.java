package com.heimdall.shell.hotswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A {@link ShellLog} that keeps every line, prefixed with its level. */
final class RecordingShellLog implements ShellLog {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    @Override
    public void info(String message) {
        lines.add("INFO " + message);
    }

    @Override
    public void warn(String message) {
        lines.add("WARN " + message);
    }

    @Override
    public void error(String message, Throwable cause) {
        lines.add("ERROR " + message + (cause == null ? "" : " :: " + cause));
    }

    @Override
    public void debug(String message) {
        lines.add("DEBUG " + message);
    }

    List<String> lines() {
        synchronized (lines) {
            return new ArrayList<String>(lines);
        }
    }

    List<String> errors() {
        return withPrefix("ERROR ");
    }

    List<String> warnings() {
        return withPrefix("WARN ");
    }

    boolean contains(String needle) {
        for (String line : lines()) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<String> withPrefix(String prefix) {
        List<String> out = new ArrayList<String>();
        for (String line : lines()) {
            if (line.startsWith(prefix)) {
                out.add(line);
            }
        }
        return out;
    }
}
