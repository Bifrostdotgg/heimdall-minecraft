package com.heimdall.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.hotswap.CoreArchive;
import com.heimdall.shell.hotswap.CoreLoader;
import com.heimdall.shell.hotswap.JulShellLog;
import com.heimdall.shell.hotswap.LoadedCore;
import com.heimdall.shell.hotswap.Sha256;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release jar this build produced, read the way the shell reads it at server start (D87).
 *
 * <p>{@code verifyJarSplit} proves the bytes are laid out right; this proves they work: the nested
 * core extracts and hashes to what {@code core.properties} records, its services entry names a class
 * that really is in the core jar, that class constructs in a child classloader with the shell's
 * classes as its parent, and it declares the contract this build implements.
 */
class ReleaseJarTest {

    /** Not cleaned up by JUnit: the extracted jar stays open in a loader on Windows. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path dir;

    private static Path releaseJar() {
        Path jar = Paths.get(System.getProperty("heimdall.releaseJar", ""));
        assertTrue(Files.isRegularFile(jar), "no release jar at " + jar + "; run through Gradle");
        return jar;
    }

    @Test
    @DisplayName("the nested core extracts, matches core.properties, and loads in a child classloader")
    void nestedCoreLoads() throws Exception {
        String version = System.getProperty("heimdall.expectedVersion");

        CoreArchive.Embedded embedded = CoreArchive.readEmbedded(releaseJar());
        assertEquals(version, embedded.version());
        assertEquals(ShellContract.VERSION, embedded.contract());

        Path written = CoreArchive.materialise(embedded, dir.resolve("core"));
        assertEquals(embedded.sha256(), Sha256.of(written));
        CoreArchive.CoreJar jar = CoreArchive.describe(written);
        assertEquals("com.heimdall.platform.common.CoreEntry", jar.entryClass());
        assertEquals(version, jar.identity().version());

        LoadedCore loaded = CoreLoader.load(
                jar, ReleaseJarTest.class.getClassLoader(), ShellContract.VERSION);
        try {
            assertNotSame(ReleaseJarTest.class.getClassLoader(), loaded.loader());
            assertTrue(loaded.owns(loaded.core().getClass()),
                    "the entry point came from outside the core jar");
            assertEquals(ShellContract.VERSION, loaded.core().contractVersion());

            // The entry point dispatches by platform name, through its own loader. An unknown
            // platform is the one case that needs no server to answer.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> loaded.core().start(contextFor("nowhere")));
            assertTrue(refused.getMessage().contains("nowhere"), refused.getMessage());
            for (String platform : new String[] {
                "com.heimdall.platform.bukkit.BukkitCore",
                "com.heimdall.platform.velocity.VelocityCore",
                "com.heimdall.platform.bungee.BungeeCore",
            }) {
                Class<?> binding = Class.forName(platform, false, loaded.loader());
                assertTrue(loaded.owns(binding), platform + " is not in the core jar");
            }
        } finally {
            loaded.closeLoader(new JulShellLog(Logger.getLogger("release-jar-test")));
        }
    }

    /** A context that answers only {@code platform()}; the entry point asks nothing else first. */
    private static ShellContext contextFor(final String platform) {
        return (ShellContext) Proxy.newProxyInstance(
                ReleaseJarTest.class.getClassLoader(),
                new Class<?>[] {ShellContext.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("platform".equals(method.getName())) {
                            return platform;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
