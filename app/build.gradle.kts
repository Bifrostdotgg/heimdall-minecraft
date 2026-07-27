import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.heimdall.build.VerifyShadowJar

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
    implementation(project(":platform-common"))
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
        // ReplaceTokens, not `expand()`: expand() runs the file through Groovy's
        // SimpleTemplateEngine, so the first `$` anyone writes in plugin.yml — in a
        // description, a permission message, anything — fails the build with a
        // template error. `@version@` substitution has no such trap.
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf("version" to pluginVersion),
        )
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

// Reads the built jar and fails on anything that would only surface at runtime on
// a customer's server: too-new bytecode, an unrelocated dependency, or a
// descriptor that disagrees with the Gradle version. See VerifyShadowJar's kdoc
// for why compiling at `--release 8` does not cover any of this.
val verifyShadowJar by tasks.registering(VerifyShadowJar::class) {
    description = "Asserts the shaded jar's bytecode levels, relocations and descriptors."
    group = "verification"

    jarFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })

    bytecodeCeiling.set(52)
    exemptPrefix.set("com/heimdall/platform/velocity/")
    exemptBytecodeLevel.set(61)

    // Allowlist, not blacklist: every class in the jar must be ours or relocated
    // under our namespace. Nothing is currently exempt, and adding an entry here
    // should require justifying why a foreign package is safe to ship unrelocated.
    ownedClassPrefix.set("com/heimdall/")
    allowedForeignClassPrefixes.set(emptyList<String>())

    requiredEntries.set(
        listOf(
            "plugin.yml",
            "velocity-plugin.json",
            "com/heimdall/platform/bukkit/HeimdallBukkitPlugin.class",
            "com/heimdall/platform/velocity/HeimdallVelocityPlugin.class",
        ),
    )
    requiredRelocations.set(
        listOf(
            "com/heimdall/libs/gson/",
            "com/heimdall/libs/nvws/",
            "com/heimdall/libs/snakeyaml/",
            "com/heimdall/libs/kyori/",
        ),
    )

    // Shaded libraries must be self-contained. The Java-WebSocket incident had
    // exactly this shape: an unconditional org.slf4j.LoggerFactory call in three
    // constructors, against a facade legacy Spigot does not have. Excluding the
    // slf4j artifact satisfied the relocation allowlist perfectly — the failure
    // was a dangling reference, not a bundled package, so only a constant-pool
    // scan can see it. platform/velocity is outside libs/ and keeps its injected
    // org.slf4j.Logger, which Velocity itself provides.
    shadedLibraryPrefix.set("com/heimdall/libs/")
    bannedLibraryReferences.set(
        listOf("org/slf4j", "org/apache/logging", "org/apache/commons/logging"),
    )

    expectedVersion.set(project.version.toString())
    expectedVelocityPluginId.set("heimdall")

    // Cheap enough to always run, and a stale pass here is worse than useless.
    outputs.upToDateWhen { false }
}

tasks.check {
    dependsOn(verifyShadowJar)
}

// `build` should produce the shipping artifact, not just the thin jar.
tasks.build {
    dependsOn(tasks.named("shadowJar"))
}
