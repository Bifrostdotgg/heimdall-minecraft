import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.heimdall.build.VerifyJarSplit
import com.heimdall.build.VerifyShadowJar
import com.heimdall.build.WriteCoreProperties
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    // The assembler has no sources of its own: it owns the descriptors and the packaging. It
    // declares release 17 purely so Gradle lets it resolve the Java 17 :shell-velocity and
    // :platform-velocity modules alongside the Java 8 ones; the bytecode in both jars is copied
    // verbatim from each module, so this level never reaches an artifact. A 1.8.8 server still only
    // ever loads Java 8 classes.
    id("heimdall.java17")
    alias(libs.plugins.shadow)
}

// ── Two halves, resolved separately (departure D87) ────────────────────────
//
// The release is still ONE jar, heimdall-whitelist-<version>.jar, because the v2 self-updater and
// the bot's download card both pick that single asset. Inside it:
//
//   * the SHELL: the three platform entry points, the hot-swap loader, the public :api and the
//     types it exposes, and Gson under them. Loaded by the platform, never replaced while it runs.
//   * the CORE: everything else, as a jar stored at META-INF/heimdall/heimdall-core.jar, which the
//     shell extracts and loads in a child classloader, and which a live update can replace.
//
// Each half is its own shadow jar built from its own classpath, so neither relocation config can
// touch the other's classes (one jar-wide `relocate("net.kyori", ...)` would rewrite the Velocity
// shell's native Adventure calls), and verifyJarSplit proves on the output that no class is in both.

fun Configuration.resolvesRuntimeJars() {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR),
        )
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        // 17, so the Velocity modules resolve; the Java 8 ones are compatible with it.
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}

val shellClasspath: Configuration by configurations.creating { resolvesRuntimeJars() }
val coreClasspath: Configuration by configurations.creating { resolvesRuntimeJars() }

dependencies {
    shellClasspath(project(":shell-bukkit"))
    shellClasspath(project(":shell-velocity"))
    shellClasspath(project(":shell-bungee"))

    coreClasspath(project(":core"))
    coreClasspath(project(":platform-common"))
    coreClasspath(project(":platform-bukkit"))
    coreClasspath(project(":platform-bukkit-paper"))
    coreClasspath(project(":platform-velocity"))
    coreClasspath(project(":platform-bungee"))
    coreClasspath(project(":module-whitelist"))
    coreClasspath(project(":module-rolesync"))
    coreClasspath(project(":module-offenses"))
    coreClasspath(project(":module-punishments"))
    coreClasspath(project(":module-console"))
    coreClasspath(project(":module-bridge"))
}

/**
 * The contract number, read from the one place it is written.
 *
 * The core's manifest and core.properties both carry it so the updater can decide between a live
 * swap and a restart without loading a class. Parsing the constant out of the source keeps a single
 * definition; verifyJarSplit then checks the built artifacts agree with it.
 */
val shellContract: Int = rootProject.file(
    "shell-api/src/main/java/com/heimdall/shell/contract/ShellContract.java",
).readText().let { source ->
    val match = Regex("""public static final int VERSION = (\d+);""").find(source)
        ?: throw GradleException("could not find ShellContract.VERSION in its source file")
    match.groupValues[1].toInt()
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    // Never inherit the platform default charset here: the descriptors are UTF-8 and a
    // Windows/Latin-1 build box would otherwise silently mangle them.
    filteringCharset = "UTF-8"
    // Both hand-written descriptors. velocity-plugin.json is absent from this list because it is not
    // hand-written at all: Velocity's annotation processor emits it from @Plugin into
    // :shell-velocity's class output, with the version already inlined from ShellBuildConstants.
    filesMatching(listOf("plugin.yml", "bungee.yml")) {
        // ReplaceTokens, not `expand()`: expand() runs the file through Groovy's
        // SimpleTemplateEngine, so the first `$` anyone writes in plugin.yml fails the build with a
        // template error. `@version@` substitution has no such trap.
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf("version" to pluginVersion),
        )
    }
}

/** Shared by both shadow jars: what never belongs in a plugin jar. */
fun ShadowJar.commonExcludes() {
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/maven/**")
    exclude("module-info.class")
    exclude("classpath.index")
    // Multi-release overlays are Java 9+ bytecode for a jar that has to load on Java 8, and nothing
    // sets `Multi-Release: true` in the manifest anyway, so they would be inert dead weight at best.
    exclude("META-INF/versions/**")
}

// The default shadowJar would merge the whole runtime classpath into one jar, which is exactly the
// layout this build no longer ships. The two tasks below replace it.
tasks.named<ShadowJar>("shadowJar") {
    enabled = false
}

val coreJar by tasks.registering(ShadowJar::class) {
    description = "Builds the swappable core: everything except the shell, the API and Gson."
    group = "build"
    archiveFileName.set("heimdall-core.jar")
    destinationDirectory.set(layout.buildDirectory.dir("core"))
    configurations = listOf(coreClasspath)

    // These resolve from the shell at runtime. A copy here would be dead code that a swap could
    // never upgrade; verifyJarSplit fails the build if one slips through.
    dependencies {
        exclude(project(":shell-api"))
        exclude(project(":api"))
        exclude(dependency("com.google.code.gson:gson:.*"))
    }

    // Gson is relocated to the SAME name the shell uses, without being bundled: core's references to
    // it then resolve to the shell's copy through the parent classloader. Everything else is
    // bundled and relocated as before, because the server loads plugins into a shared space and an
    // unrelocated library would collide with whatever else is installed.
    relocate("com.google.gson", "com.heimdall.libs.gson")
    relocate("com.neovisionaries", "com.heimdall.libs.nvws")
    relocate("org.yaml.snakeyaml", "com.heimdall.libs.snakeyaml")
    relocate("net.kyori", "com.heimdall.libs.kyori")

    manifest {
        attributes(
            "Heimdall-Shell-Contract" to shellContract.toString(),
            "Heimdall-Core-Version" to project.version.toString(),
        )
    }
    commonExcludes()
    mergeServiceFiles()
}

val shellJar by tasks.registering(ShadowJar::class) {
    description = "Builds the permanent shell: platform entry points, hot-swap loader, API, Gson."
    group = "build"
    archiveFileName.set("heimdall-shell.jar")
    destinationDirectory.set(layout.buildDirectory.dir("shell"))
    configurations = listOf(shellClasspath)
    from(tasks.processResources)

    // Gson is the only third-party library in the shell, and it is there because Payload, which the
    // public API exposes, is built on it.
    relocate("com.google.gson", "com.heimdall.libs.gson")

    commonExcludes()
    mergeServiceFiles()
}

val coreProperties by tasks.registering(WriteCoreProperties::class) {
    coreJar.set(tasks.named<ShadowJar>("coreJar").flatMap { it.archiveFile })
    coreVersion.set(project.version.toString())
    contract.set(shellContract)
    output.set(layout.buildDirectory.file("core/core.properties"))
}

val releaseJar by tasks.registering(Jar::class) {
    description = "Builds the single release jar: the shell with the core stored inside it."
    group = "build"
    // The deployed v2 fleet's self-updater picks the first GitHub release asset ending in `.jar`
    // that does not start with `original-`. Keeping this exact name keeps that path working.
    archiveFileName.set("heimdall-whitelist-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    from(zipTree(shellJar.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(coreJar) {
        into("META-INF/heimdall")
    }
    from(coreProperties) {
        into("META-INF/heimdall")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    // Byte-for-byte reproducible, so the same commit always produces the same SHA-256 asset digest.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reads the release jar and fails on anything that would only surface at runtime on a customer's
// server: too-new bytecode, an unrelocated dependency, or a descriptor that disagrees with the
// Gradle version. See VerifyShadowJar's kdoc for why compiling at `--release 8` does not cover any
// of this. Since D87 the jar it reads is the shell; verifyCoreJar applies the same checks to the core.
val verifyShadowJar by tasks.registering(VerifyShadowJar::class) {
    description = "Asserts the release jar's bytecode levels, relocations and descriptors."
    group = "verification"

    jarFile.set(releaseJar.flatMap { it.archiveFile })

    bytecodeCeiling.set(52)
    exemptPrefix.set("com/heimdall/shell/velocity/")
    exemptBytecodeLevel.set(61)

    // Allowlist, not blacklist: every class in the jar must be ours or relocated under our
    // namespace. Nothing is currently exempt, and adding an entry here should require justifying
    // why a foreign package is safe to ship unrelocated.
    ownedClassPrefix.set("com/heimdall/")
    allowedForeignClassPrefixes.set(emptyList<String>())

    requiredEntries.set(
        listOf(
            "plugin.yml",
            "velocity-plugin.json",
            "bungee.yml",
            "com/heimdall/shell/bukkit/HeimdallBukkitPlugin.class",
            "com/heimdall/shell/velocity/HeimdallVelocityPlugin.class",
            "com/heimdall/shell/bungee/HeimdallBungeePlugin.class",
            "META-INF/heimdall/heimdall-core.jar",
            "META-INF/heimdall/core.properties",
        ),
    )
    requiredRelocations.set(listOf("com/heimdall/libs/gson/"))

    // Shaded libraries must be self-contained. The Java-WebSocket incident had exactly this shape:
    // an unconditional org.slf4j.LoggerFactory call in three constructors, against a facade legacy
    // Spigot does not have. Only a constant-pool scan can see a dangling reference like that.
    shadedLibraryPrefix.set("com/heimdall/libs/")
    bannedLibraryReferences.set(
        listOf("org/slf4j", "org/apache/logging", "org/apache/commons/logging"),
    )

    expectedVersion.set(project.version.toString())
    expectedVelocityPluginId.set("heimdall")
    // BungeeCord reads bungee.yml in preference to plugin.yml, so a `main` that pointed at the
    // Bukkit entry point would produce a NoClassDefFoundError for JavaPlugin on every proxy.
    expectedBungeeMain.set("com.heimdall.shell.bungee.HeimdallBungeePlugin")

    // Cheap enough to always run, and a stale pass here is worse than useless.
    outputs.upToDateWhen { false }
}

val verifyCoreJar by tasks.registering(VerifyShadowJar::class) {
    description = "Asserts the core jar's bytecode levels and relocations."
    group = "verification"

    jarFile.set(coreJar.flatMap { it.archiveFile })
    checkDescriptors.set(false)

    bytecodeCeiling.set(52)
    exemptPrefix.set("com/heimdall/platform/velocity/")
    exemptBytecodeLevel.set(61)

    ownedClassPrefix.set("com/heimdall/")
    allowedForeignClassPrefixes.set(emptyList<String>())

    requiredEntries.set(
        listOf(
            "META-INF/services/com.heimdall.shell.contract.HeimdallCore",
            "com/heimdall/platform/common/CoreEntry.class",
            "com/heimdall/platform/bukkit/BukkitCore.class",
            "com/heimdall/platform/velocity/VelocityCore.class",
            "com/heimdall/platform/bungee/BungeeCore.class",
        ),
    )
    requiredRelocations.set(
        listOf(
            "com/heimdall/libs/nvws/",
            "com/heimdall/libs/snakeyaml/",
            "com/heimdall/libs/kyori/",
        ),
    )

    // platform/velocity is outside libs/ and keeps its injected org.slf4j.Logger, which Velocity
    // itself provides.
    shadedLibraryPrefix.set("com/heimdall/libs/")
    bannedLibraryReferences.set(
        listOf("org/slf4j", "org/apache/logging", "org/apache/commons/logging"),
    )

    outputs.upToDateWhen { false }
}

val verifyJarSplit by tasks.registering(VerifyJarSplit::class) {
    description = "Asserts that the shell and the core share no class and the nested core is ours."
    group = "verification"

    releaseJar.set(tasks.named<Jar>("releaseJar").flatMap { it.archiveFile })
    coreJar.set(tasks.named<ShadowJar>("coreJar").flatMap { it.archiveFile })
    shellOnlyPrefixes.set(
        listOf(
            "com/heimdall/shell/",
            "com/heimdall/api/",
            "com/heimdall/libs/gson/",
            "com/heimdall/core/json/",
            "com/heimdall/core/util/Registration.class",
            "com/heimdall/core/util/Registration$",
            "com/heimdall/core/util/OnceRegistration.class",
        ),
    )
    nestedCoreEntry.set("META-INF/heimdall/heimdall-core.jar")
    nestedPropertiesEntry.set("META-INF/heimdall/core.properties")
    serviceEntry.set("META-INF/services/com.heimdall.shell.contract.HeimdallCore")
    expectedContract.set(shellContract)
    expectedVersion.set(project.version.toString())

    outputs.upToDateWhen { false }
}

tasks.check {
    dependsOn(verifyShadowJar, verifyCoreJar, verifyJarSplit)
}

/**
 * A second build of the core for the connected smoke's swap rows (departure D87): the same classes
 * under a `+swaptest` version, so a different hash and a different identity, which is what a live
 * swap has to tell apart. Never shipped: it lands in build/smoke, which nothing publishes, and the
 * release workflow uploads only the one named release jar.
 */
val swapTestCore by tasks.registering(Jar::class) {
    description = "Builds a second core build, for the connected smoke's swap rows."
    group = "build"
    archiveFileName.set("heimdall-core-swaptest.jar")
    destinationDirectory.set(layout.buildDirectory.dir("smoke"))
    from(zipTree(coreJar.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        attributes(
            "Heimdall-Shell-Contract" to shellContract.toString(),
            "Heimdall-Core-Version" to "${project.version}+swaptest",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// `build` should produce the shipping artifact, not just the thin jar, and the smoke's second core.
tasks.build {
    dependsOn(releaseJar, swapTestCore)
}

// ReleaseJarTest reads the built release jar the way the shell does at boot: extract the nested
// core, check it against core.properties, load its entry point in a child classloader. The shell
// classes come from :shell-common on the test classpath; the core's, only from the nested jar.
dependencies {
    testImplementation(project(":shell-common"))
}

tasks.test {
    dependsOn(releaseJar)
    inputs.file(releaseJar.flatMap { it.archiveFile })
    systemProperty(
        "heimdall.releaseJar",
        layout.buildDirectory.file("libs/heimdall-whitelist-${project.version}.jar")
            .get().asFile.absolutePath,
    )
    systemProperty("heimdall.expectedVersion", project.version.toString())
}
