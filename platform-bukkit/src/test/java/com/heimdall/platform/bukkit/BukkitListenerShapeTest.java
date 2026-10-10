package com.heimdall.platform.bukkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every Bukkit listener in the core is package-private (departure D87).
 *
 * <p>Load-bearing since the hot-swap split. Paper 1.16's {@code EventExecutor.create} caches a
 * generated executor class in a static, strongly held map, but only for a public listener class
 * with a public handler; a package-private one takes the uncached path. One public listener would
 * therefore pin a swapped-out core's classloader for the life of the server, and nothing at runtime
 * would say so. Reading the compiled classes is the only place this can be caught.
 */
class BukkitListenerShapeTest {

    @Test
    @DisplayName("no class in the Bukkit binding that implements Listener is public")
    void listenersArePackagePrivate() throws IOException, URISyntaxException {
        Path classes = Paths.get(BukkitCore.class.getProtectionDomain().getCodeSource()
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
                type = Class.forName(name, false, BukkitCore.class.getClassLoader());
            } catch (LinkageError | ClassNotFoundException unloadable) {
                // A class touching an API this test classpath lacks (Paper-only types). None of
                // those is a Listener; the count below proves the scan still found the real ones.
                continue;
            }
            if (Listener.class.isAssignableFrom(type)) {
                listeners.add(name);
                if (Modifier.isPublic(type.getModifiers())) {
                    offenders.add(name);
                }
            }
        }

        assertTrue(listeners.size() >= 5,
                "the scan found too few listeners to be trusted: " + listeners);
        assertTrue(offenders.isEmpty(),
                "public listener classes would let Paper 1.16 pin a swapped-out core: " + offenders);
    }
}
