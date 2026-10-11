package com.heimdall.shell.bungee;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every BungeeCord listener in the shell is public, with public handlers: BungeeCord invokes handlers
 * without {@code setAccessible}, so anything else is never dispatched. The core's twin of this is
 * {@code BungeeListenerShapeTest} in {@code :platform-bungee}. Departure D88.
 */
class BungeeShellListenerShapeTest {

    @Test
    @DisplayName("every class in the BungeeCord shell that implements Listener is public")
    void listenersArePublic() throws IOException, URISyntaxException {
        Path classes = Paths.get(BungeeLoginGate.class.getProtectionDomain().getCodeSource()
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
                type = Class.forName(name, false, BungeeLoginGate.class.getClassLoader());
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

        assertTrue(!listeners.isEmpty(), "the scan found no listener, so it cannot be trusted");
        assertTrue(offenders.isEmpty(),
                "BungeeCord cannot invoke a handler on a non-public class or method: " + offenders);
    }
}
