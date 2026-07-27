import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    // The assembler has no sources of its own — it owns plugin.yml and the shadow
    // merge. It declares release 17 purely so Gradle lets it put the Java 17
    // :platform-velocity module on its classpath alongside the Java 8 ones; the
    // bytecode in the merged jar is copied verbatim from each module, so this
    // level never reaches the artifact. A 1.8.8 server still only ever loads the
    // Java 8 classes.
    id("heimdall.java17")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))
    implementation(project(":platform-bukkit"))
    implementation(project(":platform-bukkit-paper"))
    implementation(project(":platform-velocity"))
    implementation(project(":module-whitelist"))
    implementation(project(":module-rolesync"))
    implementation(project(":module-offenses"))
    implementation(project(":module-console"))
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    // Never inherit the platform default charset here — plugin.yml is UTF-8 and a
    // Windows/Latin-1 build box would otherwise silently mangle it.
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    // The deployed v2 fleet's self-updater picks the first GitHub release asset
    // ending in `.jar` that does not start with `original-`. Keeping this exact
    // name (no `-all` classifier) keeps that path working across the v3 cut.
    archiveFileName.set("heimdall-whitelist-${project.version}.jar")

    // Everything third-party gets relocated: server platforms load plugins into
    // a shared classloader space and an unrelocated Gson would collide with
    // whatever else is installed.
    relocate("com.google.gson", "com.heimdall.libs.gson")
    relocate("com.neovisionaries", "com.heimdall.libs.nvws")
    relocate("org.yaml.snakeyaml", "com.heimdall.libs.snakeyaml")
    relocate("net.kyori", "com.heimdall.libs.kyori")

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/maven/**")
    exclude("module-info.class")
    exclude("classpath.index")
    // Multi-release overlays are Java 9+ bytecode for a jar that has to load on
    // Java 8, and nothing sets `Multi-Release: true` in the manifest anyway, so
    // they would be inert dead weight at best.
    exclude("META-INF/versions/**")

    mergeServiceFiles()
}

/**
 * Verifies the shipping artifact rather than the build's intentions.
 *
 * This exists because `--release 8` is weaker than it looks: it constrains which
 * JDK APIs our own sources may call and the bytecode we emit, but javac happily
 * reads a Java 17 classfile off the compile classpath at release 8 — verified
 * directly, it does not error. So compiling `CoreSanity` against Gson, SnakeYAML
 * and nv-websocket-client proves those artifacts resolve and their APIs are reachable
 * from Java 8 source; it proves nothing about the bytecode that actually ships.
 *
 * The only thing that catches a dependency quietly moving to Java 11+ classfiles
 * is reading the merged jar, which is what this does. A Java 8 server would
 * otherwise fail with UnsupportedClassVersionError at runtime, on a customer's
 * box, with no build signal at all.
 */
val verifyShadowJar by tasks.registering {
    description = "Asserts the shaded jar's bytecode levels, relocations and descriptors."
    group = "verification"

    val shadowJarTask = tasks.named<ShadowJar>("shadowJar")
    val jarFile = shadowJarTask.flatMap { it.archiveFile }
    // Captured at configuration time: reaching for `project` inside doLast is
    // deprecated and breaks the configuration cache.
    val expectedVersion = project.version.toString()
    inputs.file(jarFile)
    inputs.property("expectedVersion", expectedVersion)
    outputs.upToDateWhen { false }

    doLast {
        val javaEight = 52
        val javaSeventeen = 61

        // The only package allowed to exceed Java 8: Velocity 3.4 is a Java 17+
        // proxy, and a Bukkit server never loads these classes.
        val seventeenOnlyPrefix = "com/heimdall/platform/velocity/"

        val requiredEntries = listOf(
            "plugin.yml",
            "velocity-plugin.json",
            "com/heimdall/platform/bukkit/HeimdallBukkitPlugin.class",
            "com/heimdall/platform/velocity/HeimdallVelocityPlugin.class",
        )
        val requiredRelocations = listOf(
            "com/heimdall/libs/gson/",
            "com/heimdall/libs/nvws/",
            "com/heimdall/libs/snakeyaml/",
            "com/heimdall/libs/kyori/",
        )
        val forbiddenPrefixes = listOf(
            "com/google/gson/",
            "com/neovisionaries/",
            "org/yaml/snakeyaml/",
            "net/kyori/",
            "org/slf4j/",
        )

        val problems = mutableListOf<String>()
        val jar = jarFile.get().asFile

        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val names = entries.map { it.name }.toSet()

            requiredEntries.filterNot { it in names }
                .forEach { problems += "missing required entry: $it" }

            requiredRelocations
                .filterNot { prefix -> names.any { it.startsWith(prefix) } }
                .forEach { problems += "no classes found under relocated prefix: $it" }

            forbiddenPrefixes.forEach { prefix ->
                val leaked = names.filter { it.startsWith(prefix) }
                if (leaked.isNotEmpty()) {
                    problems += "unrelocated third-party classes under $prefix " +
                        "(${leaked.size}, e.g. ${leaked.first()})"
                }
            }

            entries.filter { it.name.endsWith(".class") }.forEach { entry ->
                val header = zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(8)
                    var read = 0
                    while (read < 8) {
                        val n = stream.read(buffer, read, 8 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 8) null else buffer
                }
                if (header == null) {
                    problems += "truncated class entry: ${entry.name}"
                    return@forEach
                }
                val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                val expected = if (entry.name.startsWith(seventeenOnlyPrefix)) {
                    javaSeventeen
                } else {
                    javaEight
                }
                if (major > expected) {
                    problems += "${entry.name} is classfile major $major, expected at most " +
                        "$expected — it would not load on the oldest supported JVM"
                }
            }

            val versionOf = { name: String ->
                zip.getEntry(name)?.let { entry ->
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(8)
                        stream.read(buffer)
                        ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                    }
                }
            }
            // Positive assertion, not just an upper bound: if the Velocity module
            // ever silently dropped to Java 8 the mixed-bytecode merge would have
            // stopped being exercised and nobody would notice.
            val velocityMajor = versionOf("com/heimdall/platform/velocity/HeimdallVelocityPlugin.class")
            if (velocityMajor != javaSeventeen) {
                problems += "the Velocity entry point is classfile major $velocityMajor, " +
                    "expected $javaSeventeen — the mixed-bytecode merge is no longer proven"
            }

            val pluginYml = zip.getEntry("plugin.yml")?.let {
                zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
            } ?: ""
            if (!pluginYml.contains("version: ${expectedVersion}")) {
                problems += "plugin.yml did not get the Gradle version expanded into it"
            }
            if (pluginYml.contains(Regex("(?m)^\\s*api-version:"))) {
                problems += "plugin.yml declares api-version, which stops 1.8.8 loading the plugin"
            }

            val velocityJson = zip.getEntry("velocity-plugin.json")?.let {
                zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
            } ?: ""
            if (!velocityJson.contains("\"id\":\"heimdall\"")) {
                problems += "velocity-plugin.json does not declare the id 'heimdall'"
            }
            if (!velocityJson.contains("\"version\":\"${expectedVersion}\"")) {
                problems += "velocity-plugin.json version does not match the Gradle version"
            }

            logger.lifecycle(
                "verifyShadowJar: ${entries.size} entries, " +
                    "${entries.count { it.name.endsWith(".class") }} classes in ${jar.name}",
            )
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "shaded jar failed verification:\n  - " + problems.joinToString("\n  - "),
            )
        }
    }
}

tasks.check {
    dependsOn(verifyShadowJar)
}

// `build` should produce the shipping artifact, not just the thin jar.
tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
