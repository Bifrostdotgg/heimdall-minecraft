package com.heimdall.build

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Verifies the shipping artifact rather than the build's intentions.
 *
 * This task exists because `--release 8` is weaker than it looks. It constrains which JDK APIs our
 * own sources may call and the bytecode we emit, but javac at release 8 reads a Java 17 classfile
 * off the compile classpath without complaint — verified directly against this repo's own Velocity
 * module output, javac exits 0. So compiling `CoreSanity` against Gson, SnakeYAML and
 * nv-websocket-client proves those artifacts resolve and their APIs are reachable from Java 8
 * source; it proves nothing about the bytecode that actually ships.
 *
 * The only thing that catches a dependency quietly moving to Java 11+ classfiles is reading the
 * merged jar. Without this, a Java 8 server fails with UnsupportedClassVersionError at runtime, on
 * a customer's box, with no build signal at all.
 *
 * The relocation check is an allowlist, not a blacklist: **every** class in the jar must live under
 * [ownedClassPrefix] unless explicitly permitted by [allowedForeignClassPrefixes]. A blacklist only
 * catches the dependencies someone remembered to name, so the first new transitive dependency to
 * arrive would ship unrelocated and collide with whatever else the server has loaded.
 */
abstract class VerifyShadowJar : DefaultTask() {

    /**
     * The shaded jar to inspect.
     *
     * Path sensitivity is NONE because only the bytes matter — the jar's location on disk has no
     * bearing on whether it passes, so a build-directory move must not invalidate the result.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    /** Highest classfile major version allowed for ordinary classes (52 = Java 8). */
    @get:Input
    abstract val bytecodeCeiling: Property<Int>

    /** Entry prefix permitted to exceed [bytecodeCeiling], e.g. the Velocity binding. */
    @get:Input
    abstract val exemptPrefix: Property<String>

    /**
     * The exact classfile major version required under [exemptPrefix].
     *
     * Asserted exactly, not as a ceiling: if the exempt module ever silently dropped to the same
     * level as everything else, the mixed-bytecode merge would have stopped being exercised and
     * nothing would say so.
     */
    @get:Input
    abstract val exemptBytecodeLevel: Property<Int>

    /** Prefix every class in the jar must live under. */
    @get:Input
    abstract val ownedClassPrefix: Property<String>

    /** Class prefixes allowed outside [ownedClassPrefix]. Currently empty by design. */
    @get:Input
    abstract val allowedForeignClassPrefixes: ListProperty<String>

    /** Entries that must be present, e.g. the platform descriptors and entry points. */
    @get:Input
    abstract val requiredEntries: ListProperty<String>

    /** Relocation targets that must contain at least one class each. */
    @get:Input
    abstract val requiredRelocations: ListProperty<String>

    /** Prefix holding the shaded third-party libraries, scanned by [bannedLibraryReferences]. */
    @get:Input
    abstract val shadedLibraryPrefix: Property<String>

    /**
     * Internal names of logging facades that shaded libraries may not reference.
     *
     * This closes the hole the Java-WebSocket incident came through. That library called
     * `org.slf4j.LoggerFactory` unconditionally from three constructors, and legacy Spigot ships no
     * slf4j — a guaranteed NoClassDefFoundError. Nothing in the build could see it: excluding the
     * slf4j artifact from the jar satisfies the relocation allowlist perfectly, because the problem
     * is a *dangling reference*, not a bundled package. Only reading the constant pool finds it.
     */
    @get:Input
    abstract val bannedLibraryReferences: ListProperty<String>

    /** The version both platform descriptors must carry. */
    @get:Input
    abstract val expectedVersion: Property<String>

    /** The plugin id `velocity-plugin.json` must declare. */
    @get:Input
    abstract val expectedVelocityPluginId: Property<String>

    @TaskAction
    fun verify() {
        val jar = jarFile.get().asFile
        val problems = mutableListOf<String>()

        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val names = entries.map { it.name }.toSet()
            val classes = entries.filter { it.name.endsWith(".class") }

            requiredEntries.get()
                .filterNot { it in names }
                .forEach { problems += "missing required entry: $it" }

            requiredRelocations.get()
                .filterNot { prefix -> names.any { it.startsWith(prefix) } }
                .forEach { problems += "no classes found under relocated prefix: $it" }

            checkEverythingIsRelocated(classes, problems)
            checkShadedLibrariesAreSelfContained(zip, classes, problems)
            checkBytecodeLevels(zip, classes, problems)
            checkExemptModuleStillDiffers(zip, problems)
            checkPluginYml(zip, problems)
            checkVelocityPluginJson(zip, problems)

            logger.lifecycle(
                "verifyShadowJar: ${entries.size} entries, ${classes.size} classes in ${jar.name}",
            )
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "shaded jar failed verification:\n  - " + problems.joinToString("\n  - "),
            )
        }
    }

    private fun checkEverythingIsRelocated(classes: List<ZipEntry>, problems: MutableList<String>) {
        val owned = ownedClassPrefix.get()
        val allowed = allowedForeignClassPrefixes.get()
        val foreign = classes
            .map { it.name }
            .filterNot { it.startsWith(owned) }
            .filterNot { name -> allowed.any { name.startsWith(it) } }
        if (foreign.isNotEmpty()) {
            problems += "${foreign.size} class(es) outside $owned — every bundled class must be " +
                "relocated under it, e.g. ${foreign.take(3).joinToString(", ")}"
        }
    }

    /**
     * Fails if a shaded library references a logging facade we do not ship.
     *
     * Scans the raw classfile bytes rather than parsing the constant pool: the banned names appear
     * there as plain UTF-8 entries, so a substring search over the file is both sufficient and
     * immune to classfile format changes. ISO-8859-1 is used deliberately — it maps bytes to chars
     * one-to-one, so no byte sequence can be mangled by decoding.
     */
    private fun checkShadedLibrariesAreSelfContained(
        zip: ZipFile,
        classes: List<ZipEntry>,
        problems: MutableList<String>,
    ) {
        val libraryPrefix = shadedLibraryPrefix.get()
        val banned = bannedLibraryReferences.get()
        if (banned.isEmpty()) {
            return
        }
        classes
            .filter { it.name.startsWith(libraryPrefix) }
            .forEach { entry ->
                val body = zip.getInputStream(entry).use {
                    it.readBytes().toString(Charsets.ISO_8859_1)
                }
                banned.filter { body.contains(it) }.forEach { reference ->
                    problems += "${entry.name} references $reference, which is not shipped — a " +
                        "server without that facade on its classpath (legacy Spigot has none) " +
                        "would throw NoClassDefFoundError the moment this class initialises"
                }
            }
    }

    private fun checkBytecodeLevels(
        zip: ZipFile,
        classes: List<ZipEntry>,
        problems: MutableList<String>,
    ) {
        val ceiling = bytecodeCeiling.get()
        val exempt = exemptPrefix.get()
        val exemptLevel = exemptBytecodeLevel.get()
        classes.forEach { entry ->
            val major = majorVersion(zip, entry.name)
            if (major == null) {
                problems += "truncated class entry: ${entry.name}"
                return@forEach
            }
            val allowedMax = if (entry.name.startsWith(exempt)) exemptLevel else ceiling
            if (major > allowedMax) {
                problems += "${entry.name} is classfile major $major, expected at most " +
                    "$allowedMax — it would not load on the oldest supported JVM"
            }
        }
    }

    private fun checkExemptModuleStillDiffers(zip: ZipFile, problems: MutableList<String>) {
        val exempt = exemptPrefix.get()
        val exemptLevel = exemptBytecodeLevel.get()
        val sample = zip.entries().toList()
            .firstOrNull { !it.isDirectory && it.name.startsWith(exempt) && it.name.endsWith(".class") }
        if (sample == null) {
            problems += "no classes found under the exempt prefix $exempt — " +
                "the mixed-bytecode merge is no longer proven"
            return
        }
        val major = majorVersion(zip, sample.name)
        if (major != exemptLevel) {
            problems += "${sample.name} is classfile major $major, expected exactly $exemptLevel — " +
                "the mixed-bytecode merge is no longer proven"
        }
    }

    private fun checkPluginYml(zip: ZipFile, problems: MutableList<String>) {
        val pluginYml = textOf(zip, "plugin.yml")
        if (pluginYml == null) {
            problems += "plugin.yml is missing, so its contents could not be checked"
            return
        }
        if (!pluginYml.contains("version: ${expectedVersion.get()}")) {
            problems += "plugin.yml did not get the Gradle version substituted into it"
        }
        if (pluginYml.contains(Regex("(?m)^\\s*api-version:"))) {
            problems += "plugin.yml declares api-version, which stops 1.8.8 loading the plugin"
        }
        // Matches the token shape rather than a bare '@' so an email address or a
        // future `${...}` in a description does not become a false failure.
        val unsubstituted = Regex("@[A-Za-z_][A-Za-z0-9_.]*@").find(pluginYml)
        if (unsubstituted != null) {
            problems += "plugin.yml still contains an unsubstituted token: ${unsubstituted.value}"
        }
    }

    private fun checkVelocityPluginJson(zip: ZipFile, problems: MutableList<String>) {
        val json = textOf(zip, "velocity-plugin.json")
        if (json == null) {
            problems += "velocity-plugin.json is missing, so its contents could not be checked"
            return
        }
        if (!json.contains("\"id\":\"${expectedVelocityPluginId.get()}\"")) {
            problems += "velocity-plugin.json does not declare the id " +
                "'${expectedVelocityPluginId.get()}'"
        }
        if (!json.contains("\"version\":\"${expectedVersion.get()}\"")) {
            problems += "velocity-plugin.json version does not match the Gradle version"
        }
    }

    private fun textOf(zip: ZipFile, name: String): String? =
        zip.getEntry(name)?.let { entry ->
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun majorVersion(zip: ZipFile, name: String): Int? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { stream ->
            val buffer = ByteArray(8)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read < buffer.size) {
                null
            } else {
                ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
            }
        }
    }
}
