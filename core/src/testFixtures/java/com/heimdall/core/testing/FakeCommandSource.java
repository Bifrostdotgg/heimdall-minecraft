package com.heimdall.core.testing;

import com.heimdall.core.command.CommandSource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;

/**
 * Somebody typing a command, and everything they were told back.
 *
 * <p>Has both shapes a real source can take, because the difference matters to the code under test:
 * {@link #console()} has a {@code null} UUID and holds every permission, which is what
 * {@code /offend} has to be able to record an issuer for.
 *
 * <p>Thread-safe: a command handler answers from {@code heimdall-io} once its request completes.
 */
public final class FakeCommandSource implements CommandSource {

    private final String name;
    private final UUID uuid;
    private final boolean player;
    private final boolean allPermissions;
    private final Set<String> permissions = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final CopyOnWriteArrayList<Component> messages = new CopyOnWriteArrayList<Component>();

    private FakeCommandSource(String name, UUID uuid, boolean player, boolean allPermissions) {
        this.name = name;
        this.uuid = uuid;
        this.player = player;
        this.allPermissions = allPermissions;
    }

    /** The console: no UUID, every permission — exactly what both real platforms do. */
    public static FakeCommandSource console() {
        return new FakeCommandSource("CONSOLE", null, false, true);
    }

    /** A player with no permissions until {@link #grant} is called. */
    public static FakeCommandSource player(String name) {
        return player(UUID.nameUUIDFromBytes(
                name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
    }

    public static FakeCommandSource player(UUID uuid, String name) {
        return new FakeCommandSource(name, uuid, true, false);
    }

    public FakeCommandSource grant(String node) {
        permissions.add(node);
        return this;
    }

    /** Everything this source was told, flattened to plain text. */
    public List<String> messageText() {
        List<String> out = new java.util.ArrayList<String>(messages.size());
        for (Component component : messages) {
            out.add(TestText.plain(component));
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether any message contains {@code needle}. The assertion most tests actually want. */
    public boolean wasTold(String needle) {
        for (String line : messageText()) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every message, so one test can assert about two invocations in turn. */
    public void clearMessages() {
        messages.clear();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean isPlayer() {
        return player;
    }

    @Override
    public boolean hasPermission(String node) {
        return allPermissions || permissions.contains(node);
    }

    @Override
    public void sendMessage(Component message) {
        messages.add(message == null ? Component.empty() : message);
    }

    @Override
    public String toString() {
        return "FakeCommandSource{" + name + (player ? "" : " (console)") + "}";
    }
}
