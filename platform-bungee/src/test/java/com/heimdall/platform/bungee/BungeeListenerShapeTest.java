package com.heimdall.platform.bungee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.pipeline.ChatPipeline;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.session.PlayerSessionEvents;
import com.heimdall.core.session.PlayerSessionListener;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventBus;
import net.md_5.bungee.event.EventHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every BungeeCord listener in the core is public, and really receives events through BungeeCord's
 * own {@link EventBus}. Departure D88.
 *
 * <p>The opposite of {@code BukkitListenerShapeTest}, and for an unrelated reason. BungeeCord's
 * {@code EventHandlerMethod} calls {@code Method.invoke} without {@code setAccessible}, so a handler
 * on a package-private class is refused and the bus rethrows that as an {@code Error} out of
 * {@code post()}. That is how the session and chat listeners shipped: package-private, and never
 * once dispatched on a real BungeeCord, with every unit test calling the handler directly and so
 * agreeing with itself. This suite posts through the real bus instead.
 */
class BungeeListenerShapeTest {

    private final RecordingLogger logger = new RecordingLogger(true);

    @Test
    @DisplayName("every class in the BungeeCord binding that implements Listener is public")
    void listenersArePublic() throws IOException, URISyntaxException {
        Path classes = Paths.get(BungeeCore.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        List<String> names;
        try (Stream<Path> walk = Files.walk(classes)) {
            names = walk.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> classes.relativize(path).toString()
                            .replace('\\', '/').replace('/', '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .collect(Collectors.toList());
        }

        List<String> listeners = new ArrayList<String>();
        List<String> offenders = new ArrayList<String>();
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name, false, BungeeCore.class.getClassLoader());
            } catch (LinkageError | ClassNotFoundException unloadable) {
                continue;
            }
            if (!Listener.class.isAssignableFrom(type) || type.isInterface()) {
                continue;
            }
            listeners.add(name);
            if (!Modifier.isPublic(type.getModifiers())) {
                offenders.add(name);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventHandler.class)
                        && !Modifier.isPublic(method.getModifiers())) {
                    offenders.add(name + "#" + method.getName());
                }
            }
        }

        assertTrue(listeners.size() >= 3,
                "the scan found too few listeners to be trusted: " + listeners);
        assertTrue(offenders.isEmpty(),
                "BungeeCord cannot invoke a handler on a non-public class or method: " + offenders);
    }

    @Test
    @DisplayName("join and quit reach core through BungeeCord's real event bus")
    void sessionEventsDispatch() {
        PlayerSessionEvents sessions = new PlayerSessionEvents(logger, Runnable::run);
        final List<String> seen = new ArrayList<String>();
        sessions.onJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                seen.add("join " + player.name());
            }
        });
        sessions.onQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                seen.add("quit " + player.name());
            }
        });
        EventBus bus = new EventBus();
        bus.register(new BungeeSessionListener(logger, sessions, new BungeeText()));

        bus.post(new PostLoginEvent(steve()));
        bus.post(new PlayerDisconnectEvent(steve()));

        assertEquals(java.util.Arrays.asList("join Steve", "quit Steve"), seen);
    }

    @Test
    @DisplayName("chat reaches the observers through BungeeCord's real event bus")
    void chatDispatches() {
        ChatPipeline pipeline = new ChatPipeline(logger);
        final List<String> relayed = new ArrayList<String>();
        pipeline.observe(new ChatObserver() {
            @Override
            public void onChat(ChatMessage message) {
                relayed.add(message.senderName() + ": " + message.message());
            }
        });
        EventBus bus = new EventBus();
        bus.register(new BungeeChatListener(logger, pipeline));

        bus.post(new ChatEvent(steve(), mock(Server.class), "hello"));

        assertEquals(java.util.Collections.singletonList("Steve: hello"), relayed);
    }

    private static ProxiedPlayer steve() {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Steve".getBytes()));
        when(player.getName()).thenReturn("Steve");
        return player;
    }
}
