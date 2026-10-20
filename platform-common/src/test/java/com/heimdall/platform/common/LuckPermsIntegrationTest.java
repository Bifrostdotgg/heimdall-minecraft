package com.heimdall.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    /** What {@code QueryOptions.nonContextual()} resolves to through the mocked registry. */
    private QueryOptions nonContextual;

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
        nonContextual = mock(QueryOptions.class);
        when(registry.defaultNonContextualOptions()).thenReturn(nonContextual);
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
        return subscribe(bridge);
    }

    private Registration subscribe(LuckPermsIntegration bridge) {
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

    // -- forward removal ---------------------------------------------------------------------

    /** Loads {@code user} for {@code setPlayerGroups} and saves it back. */
    private NodeMap loadable(User user) {
        UserManager users = mock(UserManager.class);
        when(api.getUserManager()).thenReturn(users);
        when(users.loadUser(user.getUniqueId())).thenReturn(CompletableFuture.completedFuture(user));
        when(users.saveUser(user)).thenReturn(CompletableFuture.<Void>completedFuture(null));
        NodeMap data = mock(NodeMap.class);
        when(user.data()).thenReturn(data);
        return data;
    }

    private static InheritanceNode parent(String group, boolean negated) {
        InheritanceNode node = mock(InheritanceNode.class);
        when(node.getGroupName()).thenReturn(group);
        when(node.isNegated()).thenReturn(negated);
        return node;
    }

    @Test
    @DisplayName("a forward removal clears every node granting the group, temporary or contextual")
    void removalClearsEveryGrantingNode() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        CompletableFuture<Boolean> done = bridge.setPlayerGroups(
                uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();

        assertTrue(done.join(), logger.records().toString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<? super Node>> cleared =
                ArgumentCaptor.forClass((Class<Predicate<? super Node>>) (Class<?>) Predicate.class);
        verify(data, times(1)).clear(cleared.capture());
        verify(data, never()).remove(any(Node.class));
        verify(api.getUserManager()).saveUser(user);

        Predicate<? super Node> matches = cleared.getValue();
        InheritanceNode temporaryInContext = parent("zeal", false);
        when(temporaryInContext.hasExpiry()).thenReturn(true);
        when(temporaryInContext.getContexts()).thenReturn(mock(ImmutableContextSet.class));
        assertTrue(matches.test(temporaryInContext), "addtemp with server=X is still the rank");
        assertTrue(matches.test(parent("zeal", false)), "the permanent global node too");
        assertTrue(matches.test(parent("Zeal", false)), "names compared ignoring case");
        assertFalse(matches.test(parent("zeal", true)), "a negated node grants nothing; left alone");
        assertFalse(matches.test(parent("default", false)), "another group is untouched");
        assertFalse(matches.test(mock(PermissionNode.class)), "not an inheritance node");
    }

    @Test
    @DisplayName("a managed group typed Zeal removes the held zeal and grants nothing")
    void caseInsensitiveRemoval() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("Zeal"));
        drain();

        verify(data, times(1)).clear(any(Predicate.class));
        verify(data, never()).add(any(Node.class));
    }

    @Test
    @DisplayName("a held group the target still wants, in any spelling, is not written at all")
    void heldInAnotherSpellingIsNoChange() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        CompletableFuture<Boolean> done =
                bridge.setPlayerGroups(uuid, Arrays.asList("Zeal"), Arrays.asList("Zeal"));
        drain();

        assertTrue(done.join());
        verify(data, never()).clear(any(Predicate.class));
        verify(data, never()).add(any(Node.class));
        verify(api.getUserManager(), never()).saveUser(user);
    }

    // -- what the forward diff counts as held ------------------------------------------------

    private static List<Group> groupList(String... names) {
        List<Group> out = new ArrayList<Group>();
        for (String name : names) {
            Group g = mock(Group.class);
            when(g.getName()).thenReturn(name);
            out.add(g);
        }
        return out;
    }

    /**
     * Pio on survival, holding {@code zeal} only as {@code server=lobby}: this server's contextual
     * query does not see it, the non-contextual one does. Stubbed per query, not with
     * {@code any()}, so the test fails if the diff goes back to the contextual read.
     */
    private User heldOnlyInLobby(UUID uuid) {
        User user = mock(User.class);
        when(user.getUniqueId()).thenReturn(uuid);
        when(user.getUsername()).thenReturn("Pio");
        QueryOptions survival = mock(QueryOptions.class);
        when(user.getQueryOptions()).thenReturn(survival);
        List<Group> contextual = groupList("default");
        List<Group> owned = groupList("default", "zeal");
        when(user.getInheritedGroups(survival)).thenReturn(contextual);
        when(user.getInheritedGroups(nonContextual)).thenReturn(owned);
        return user;
    }

    @Test
    @DisplayName("a push applied on survival removes a zeal held only as server=lobby")
    void removalSeesAGroupHeldInAnotherContext() {
        UUID uuid = UUID.randomUUID();
        User user = heldOnlyInLobby(uuid);
        NodeMap data = loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        CompletableFuture<Boolean> done =
                bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();

        assertTrue(done.join(), logger.records().toString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<? super Node>> cleared =
                ArgumentCaptor.forClass((Class<Predicate<? super Node>>) (Class<?>) Predicate.class);
        verify(data, times(1)).clear(cleared.capture());
        InheritanceNode lobby = parent("zeal", false);
        when(lobby.getContexts()).thenReturn(mock(ImmutableContextSet.class));
        assertTrue(cleared.getValue().test(lobby), "cleared in every context, lobby's included");
        verify(api.getUserManager()).saveUser(user);
    }

    @Test
    @DisplayName("a target naming zeal held only as server=lobby adds no global node beside it")
    void heldInAnotherContextIsNotReAdded() {
        UUID uuid = UUID.randomUUID();
        User user = heldOnlyInLobby(uuid);
        NodeMap data = loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        CompletableFuture<Boolean> done =
                bridge.setPlayerGroups(uuid, Arrays.asList("zeal"), Arrays.asList("zeal"));
        drain();

        assertTrue(done.join(), logger.records().toString());
        verify(data, never()).add(any(Node.class));
        verify(data, never()).clear(any(Predicate.class));
        verify(api.getUserManager(), never()).saveUser(user);
    }

    // -- our own writes' node events -----------------------------------------------------------

    private static NodeClearEvent clearedOf(User user, Node... nodes) {
        NodeClearEvent event = mock(NodeClearEvent.class);
        when(event.getNodes()).thenReturn(new LinkedHashSet<Node>(Arrays.asList(nodes)));
        when(event.isUser()).thenReturn(true);
        when(event.getTarget()).thenReturn(user);
        return event;
    }

    private static NodeAddEvent addedOf(User user, Node node) {
        NodeAddEvent event = mock(NodeAddEvent.class);
        when(event.getNode()).thenReturn(node);
        when(event.isUser()).thenReturn(true);
        when(event.getTarget()).thenReturn(user);
        return event;
    }

    @Test
    @DisplayName("the clear a forward removal fires is tagged as our own write; a later edit is not")
    void ownRemovalIsTagged() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        InheritanceNode zeal = parent("zeal", false);
        when(data.toCollection()).thenReturn(Arrays.<Node>asList(zeal));
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        subscribe(bridge);

        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();
        assertEquals(1, bridge.ownWritesOutstanding(), "expected before the write");

        // LuckPerms posts it on its own thread, after the write returned.
        handlers.get(NodeClearEvent.class).accept(clearedOf(user, zeal));
        drain();
        assertEquals(1, calls.size());
        assertEquals(GroupsChangedListener.Cause.ROLE_SYNC_WRITE, calls.get(0).cause,
                "the push coming back is not an edit: an offline report would echo it");
        assertEquals(0, bridge.ownWritesOutstanding(), "consumed");

        // The same change made by somebody else, later, is an edit again.
        handlers.get(NodeClearEvent.class).accept(clearedOf(user, zeal));
        drain();
        assertEquals(GroupsChangedListener.Cause.INHERITANCE_NODE, calls.get(1).cause);
    }

    @Test
    @DisplayName("a removal of something not held expects no event, so it cannot swallow a later edit")
    void nothingToClearExpectsNothing() {
        UUID uuid = UUID.randomUUID();
        // The read says zeal is held (inherited through another group), but no node of the user's
        // own grants it: the clear removes nothing and LuckPerms fires no event for it.
        User user = user(uuid, "Pio", "default", "zeal");
        loadable(user);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        subscribe(bridge);

        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();
        assertEquals(0, bridge.ownWritesOutstanding());
    }

    @Test
    @DisplayName("the add a forward grant fires is tagged as our own write; a no-op add expects nothing")
    void ownAddIsTagged() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default");
        NodeMap data = loadable(user);
        net.luckperms.api.node.NodeBuilderRegistry builders =
                mock(net.luckperms.api.node.NodeBuilderRegistry.class);
        when(api.getNodeBuilderRegistry()).thenReturn(builders);
        InheritanceNode.Builder builder = mock(InheritanceNode.Builder.class);
        when(builders.forInheritance()).thenReturn(builder);
        when(builder.group(any(String.class))).thenReturn(builder);
        InheritanceNode zeal = parent("zeal", false);
        when(builder.build()).thenReturn(zeal);
        net.luckperms.api.model.group.GroupManager groups =
                mock(net.luckperms.api.model.group.GroupManager.class);
        when(api.getGroupManager()).thenReturn(groups);
        when(groups.isLoaded("zeal")).thenReturn(true);
        when(data.add(zeal)).thenReturn(net.luckperms.api.model.data.DataMutateResult.SUCCESS);
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        subscribe(bridge);

        CompletableFuture<Boolean> done =
                bridge.setPlayerGroups(uuid, Arrays.asList("zeal"), Arrays.asList("zeal"));
        drain();
        assertTrue(done.join(), logger.records().toString());
        verify(data).add(zeal);
        assertEquals(1, bridge.ownWritesOutstanding());

        handlers.get(NodeAddEvent.class).accept(addedOf(user, zeal));
        drain();
        assertEquals(GroupsChangedListener.Cause.ROLE_SYNC_WRITE, calls.get(0).cause);

        // LuckPerms saying it already had the node: nothing changed, nothing will be fired.
        when(data.add(zeal)).thenReturn(net.luckperms.api.model.data.DataMutateResult.FAIL_ALREADY_HAS);
        bridge.setPlayerGroups(uuid, Arrays.asList("zeal"), Arrays.asList("zeal"));
        drain();
        assertEquals(0, bridge.ownWritesOutstanding(), "withdrawn");
    }

    @Test
    @DisplayName("our write's event coalesced with a real edit of the same user is still the edit")
    void editOutranksOwnWrite() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        InheritanceNode zeal = parent("zeal", false);
        when(data.toCollection()).thenReturn(Arrays.<Node>asList(zeal));
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);
        subscribe(bridge);
        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();

        handlers.get(NodeClearEvent.class).accept(clearedOf(user, zeal));
        handlers.get(NodeAddEvent.class).accept(addedOf(user, parent("mvp", false)));
        handlers.get(UserDataRecalculateEvent.class).accept(recalculated(user));
        drain();
        assertEquals(1, calls.size(), "one read");
        assertEquals(GroupsChangedListener.Cause.INHERITANCE_NODE, calls.get(0).cause);
    }

    @Test
    @DisplayName("with nobody subscribed, a write records no expectation at all")
    void noListenerNoExpectation() {
        UUID uuid = UUID.randomUUID();
        User user = user(uuid, "Pio", "default", "zeal");
        NodeMap data = loadable(user);
        InheritanceNode zeal = parent("zeal", false);
        when(data.toCollection()).thenReturn(Arrays.<Node>asList(zeal));
        LuckPermsIntegration bridge = LuckPermsIntegration.tryCreate(logger, manual);

        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();
        assertEquals(0, bridge.ownWritesOutstanding());

        Registration registration = subscribe(bridge);
        registration.close();
        bridge.setPlayerGroups(uuid, Arrays.<String>asList(), Arrays.asList("zeal"));
        drain();
        assertEquals(0, bridge.ownWritesOutstanding(), "and none after the subscription closed");
    }
}
