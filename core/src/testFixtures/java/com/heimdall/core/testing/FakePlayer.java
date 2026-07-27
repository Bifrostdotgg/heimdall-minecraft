package com.heimdall.core.testing;

import com.heimdall.core.platform.PlayerHandle;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;

/**
 * A player that records what was done to them.
 *
 * <p>A real fake rather than a mock: the assertions a module test wants are "were they kicked, and
 * with what text" and "what were they told", which are properties of this object rather than of a
 * verification framework. That also means a test reads as the behaviour it is checking instead of as
 * a list of expected calls.
 *
 * <p>Thread-safe, because the code under test hands work to {@code heimdall-io} and the test asserts
 * from its own thread.
 */
public final class FakePlayer implements PlayerHandle {

    private final UUID uuid;
    private final String name;
    private final Set<String> permissions = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final CopyOnWriteArrayList<Component> messages = new CopyOnWriteArrayList<Component>();
    private final CopyOnWriteArrayList<Component> kicks = new CopyOnWriteArrayList<Component>();

    public FakePlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public static FakePlayer named(String name) {
        return new FakePlayer(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
    }

    /** Grants a permission node. Returns {@code this} so a fixture reads as one expression. */
    public FakePlayer grant(String node) {
        permissions.add(node);
        return this;
    }

    /** Every message this player was sent, oldest first. */
    public List<Component> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** Every message sent, flattened to plain text — what most assertions actually want. */
    public List<String> messageText() {
        return plain(messages);
    }

    /** The reasons this player was kicked with. Empty if they never were. */
    public List<String> kickReasons() {
        return plain(kicks);
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void kick(Component reason) {
        kicks.add(reason == null ? Component.empty() : reason);
    }

    @Override
    public void sendMessage(Component message) {
        messages.add(message == null ? Component.empty() : message);
    }

    @Override
    public boolean hasPermission(String node) {
        return permissions.contains(node);
    }

    @Override
    public String toString() {
        return "FakePlayer{" + name + "/" + uuid + "}";
    }

    private static List<String> plain(List<Component> components) {
        List<String> out = new java.util.ArrayList<String>(components.size());
        for (Component component : components) {
            out.add(TestText.plain(component));
        }
        return Collections.unmodifiableList(out);
    }
}
