package com.heimdall.shell.hotswap;

import com.heimdall.shell.contract.ShellContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The fixture core jars the build produced, and release jars built around them. */
final class Fixtures {

    private Fixtures() {
    }

    /** A fixture core jar by variant: alpha, beta, failing, contradicts, leaky, contract2. */
    static Path core(String variant) {
        String dir = System.getProperty("heimdall.fixtureCores");
        if (dir == null || dir.isEmpty()) {
            throw new IllegalStateException("heimdall.fixtureCores is not set; run through Gradle");
        }
        Path jar = Paths.get(dir).resolve("fixture-core-" + variant + ".jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("no fixture core at " + jar);
        }
        return jar;
    }

    /** A copy of a fixture core in {@code dir}, so a test may delete or damage it freely. */
    static Path copyOf(String variant, Path dir) throws IOException {
        Path copy = dir.resolve("copy-" + variant + ".jar");
        Files.copy(core(variant), copy);
        return copy;
    }

    /**
     * A release-shaped jar: the given core nested at {@link ShellContract#EMBEDDED_CORE} with a
     * {@code core.properties} next to it, exactly as {@code :app:releaseJar} lays them out.
     *
     * @param recordedSha the hash to record, or {@code null} for the core's real one
     */
    static Path release(Path dir, String variant, String version, int contract, String recordedSha)
            throws IOException {
        byte[] core = Files.readAllBytes(core(variant));
        String sha = recordedSha == null ? Sha256.of(core) : recordedSha;
        Path release = dir.resolve("release-" + variant + "-" + Math.abs(sha.hashCode()) + ".jar");
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(release));
        try {
            out.putNextEntry(new ZipEntry("plugin.yml"));
            out.write("name: Heimdall\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(ShellContract.EMBEDDED_CORE));
            out.write(core);
            out.closeEntry();
            out.putNextEntry(new ZipEntry(ShellContract.EMBEDDED_CORE_PROPERTIES));
            out.write(("version=" + version + "\nsha256=" + sha + "\ncontract=" + contract + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } finally {
            out.close();
        }
        return release;
    }

    /** Waits up to two seconds for {@code condition}. */
    static boolean eventually(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
