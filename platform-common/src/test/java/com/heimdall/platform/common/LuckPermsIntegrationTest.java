package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.util.Registration;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LuckPermsIntegration} against the real LuckPerms API interfaces, mocked.
 *
 * <p>The bridge is the one class that names LuckPerms types, so it is the one place these decisions
 * can be checked: which events count as a rank edit, and what a forward removal deletes. The mocked
 * API is registered with {@link LuckPermsProvider} the way LuckPerms registers itself, because the
 * bridge resolves through it and so do the API's own static helpers.
 */
class LuckPermsIntegrationTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private final ArrayDeque<Runnable> queued = new ArrayDeque<Runnable>();
    /** Runs nothing until {@link #drain}, so a test can queue several events behind one read. */
    private final Executor manual = new Executor() {
        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }
    };
    private final Map<Class<?>, Consumer<Object>> handlers = new HashMap<Class<?>, Consumer<Object>>();
    private LuckPerms api;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        api = mock(LuckPerms.class);
        EventBus bus = mock(EventBus.class);
        when(api.getEventBus()).thenReturn(bus);
        when(bus.subscribe(any(Class.class), any(Consumer.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Object> handler = (Consumer<Object>) invocation.getArgument(1);
            handlers.put((Class<?>) invocation.getArgument(0), handler);
            return mock(EventSubscription.class);
        });
        QueryOptionsRegistry registry = mock(QueryOptionsRegistry.class);
        when(registry.defaultNonContextualOptions()).thenReturn(mock(QueryOptions.class));
        when(api.getQueryOptionsRegistry()).thenReturn(registry);
        provider("register", api);
    }

    @AfterEach
    void tearDown() throws Exception {
        provider("unregister", null);
    }

    /** {@code LuckPermsProvider.register/unregister} are package-private: LuckPerms calls them. */
    private static void provider(String name, LuckPerms instance) throws Exception {
        Method method = instance == null
                ? LuckPermsProvider.class.getDeclaredMethod(name)
                : LuckPermsProvider.class.getDeclaredMethod(name, LuckPerms.class);
        method.setAccessible(true);
        if (instance == null) {
            method.invoke(null);
        } else {
            method.invoke(null, instance);
        }
    }

    private void drain() {
        Runnable next;
        while ((next = queued.poll()) != null) {
            next.run();
        }
    }

    private static User user(UUID uuid, String name, String... groups) {
        User user = mock(User.class);
        when(user.getUniqueId()).thenReturn(uuid);
        when(user.getUsername()).thenReturn(name);
        List<Group> held = new ArrayList<Group>();
        for (String group : groups) {
            Group g = mock(Group.class);
            when(g.getName()).thenReturn(group);
            held.add(g);
        }
        when(user.getInheritedGroups(any())).thenReturn(held);
        return user;
    }

    private static NodeAddEvent added(User user) {
        NodeAddEvent event = mock(NodeAddEvent.class);
        net.luckperms.api.node.types.InheritanceNode node =
                mock(net.luckperms.api.node.types.InheritanceNode.class);
        when(event.getNode()).thenReturn(node);
        when(event.isUser()).thenReturn(true);
        when(event.getTarget()).thenReturn(user);
        return event;
    }

    private static UserDataRecalculateEvent recalculated(User user) {
        UserDataRecalculateEvent event = mock(UserDataRecalculateEvent.class);
        when(event.getUser()).thenReturn(user);
        return event;
    }

    /** One listener call as {@code uuid name groups cause}. */
    private static final class Call {
        final UUID uuid;
        final String name;
        final List<String> groups;
        final GroupsChangedListener.Cause cause;

        Call(UUID uuid, String name, List<String> groups, GroupsChangedListener.Cause cause) {
            this.uuid = uuid;
            this.name = name;
            this.groups = groups;
            this.cause = cause;
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<Call>();

    private Registration subscribe() {
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        assertNotNull(bridge);
        return bridge.onGroupsChanged(new GroupsChangedListener() {
            @Override
            public void onGroupsChanged(UUID uuid, List<String> currentGroups) {
                calls.add(new Call(uuid, null, currentGroups, null));
            }

            @Override
            public void onGroupsChanged(UUID uuid, String username, List<String> currentGroups,
                    GroupsChangedListener.Cause cause) {
                calls.add(new Call(uuid, username, currentGroups, cause));
            }
        });
    }

    @Test
    @DisplayName("subscribes to the three node events and the recalculation")
    void subscribesToAllFour() {
        subscribe();
        assertEquals(new LinkedHashSet<Class<?>>(Arrays.<Class<?>>asList(NodeAddEvent.class,
                        NodeRemoveEvent.class, NodeClearEvent.class, UserDataRecalculateEvent.class)),
                new LinkedHashSet<Class<?>>(handlers.keySet()));
    }

    @Test
    @DisplayName("a bare recalculation is reported as RECALCULATION, with the stored name")
    void recalculationCause() {
        subscribe();
        UUID uuid = UUID.randomUUID();
        handlers.get(UserDataRecalculateEvent.class).accept(recalculated(user(uuid, "Pio", "default")));
        drain();

        assertEquals(1, calls.size());
        assertEquals(GroupsChangedListener.Cause.RECALCULATION, calls.get(0).cause);
        assertEquals("Pio", calls.get(0).name);
        assertEquals(uuid, calls.get(0).uuid);
        assertEquals(Arrays.asList("default"), calls.get(0).groups);
    }

    @Test
    @DisplayName("a node edit and the recalculation queued behind it are one read, reported as the edit")
    void coalescedKeepsTheNodeCause() {
        subscribe();
        UUID uuid = UUID.randomUUID();
        User before = user(uuid, "Pio", "default", "zeal");
        User after = user(uuid, "Pio", "default");
        handlers.get(NodeRemoveEvent.class).accept(removed(before));
        handlers.get(UserDataRecalculateEvent.class).accept(recalculated(after));
        drain();

        assertEquals(1, calls.size(), "coalesced per user");
        assertEquals(GroupsChangedListener.Cause.INHERITANCE_NODE, calls.get(0).cause,
                "the recalculation must not hide the edit: an offline player is reported only on one");
        assertEquals(Arrays.asList("default"), calls.get(0).groups, "the newest User object is read");
    }

    @Test
    @DisplayName("a recalculation then a node edit is still reported as the edit")
    void nodeAfterRecalculation() {
        subscribe();
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default");
        handlers.get(UserDataRecalculateEvent.class).accept(recalculated(user));
        handlers.get(NodeAddEvent.class).accept(added(user));
        drain();

        assertEquals(1, calls.size());
        assertEquals(GroupsChangedListener.Cause.INHERITANCE_NODE, calls.get(0).cause);
    }

    @Test
    @DisplayName("a node event on a non-inheritance node is not a rank change at all")
    void permissionNodeIgnored() {
        subscribe();
        NodeAddEvent event = mock(NodeAddEvent.class);
        net.luckperms.api.node.types.PermissionNode permission =
                mock(net.luckperms.api.node.types.PermissionNode.class);
        User user = user(UUID.randomUUID(), "Pio", "default");
        when(event.getNode()).thenReturn(permission);
        when(event.isUser()).thenReturn(true);
        when(event.getTarget()).thenReturn(user);
        handlers.get(NodeAddEvent.class).accept(event);
        drain();

        assertEquals(0, calls.size());
    }

    private static NodeRemoveEvent removed(User user) {
        NodeRemoveEvent event = mock(NodeRemoveEvent.class);
        net.luckperms.api.node.types.InheritanceNode node =
                mock(net.luckperms.api.node.types.InheritanceNode.class);
        when(event.getNode()).thenReturn(node);
        when(event.isUser()).thenReturn(true);
        when(event.getTarget()).thenReturn(user);
        return event;
    }
}
