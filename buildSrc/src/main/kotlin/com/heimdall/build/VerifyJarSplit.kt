package com.heimdall.build

import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarInputStream
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
 * Asserts the hot-swap split on the artifacts that ship (departure D87).
 *
 * <p>Four things, each of which would otherwise surface only at runtime on a customer's server:
 *
 * <ol>
 *   <li><strong>The split itself</strong>, per [JarSplit]: the shell carries only shell classes and
 *       the core carries none of them.
 *   <li><strong>Every shell-side class the core refers to is in the shell</strong>, per
 *       [JarSplit.danglingReferences]: relocated Gson, the contract, the API types.
 *   <li><strong>The nested core is this core.</strong> The release jar's embedded core must be
 *       byte-identical to the core jar this build produced, and the hash recorded next to it must be
 *       that core's hash. The shell refuses a mismatch at boot, which on a real server means a
 *       server that comes up with no core and refuses every login.
 *   <li><strong>The core describes itself correctly</strong>: a services entry the shell can find,
 *       and manifest attributes naming this build's version and contract, which is what the updater
 *       reads to decide between a live swap and a restart.
 * </ol>
 */
abstract class VerifyJarSplit : DefaultTask() {

    /** The release jar: the shell, with the core nested inside it. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseJar: RegularFileProperty

    /** The core jar as built, before nesting. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coreJar: RegularFileProperty

    /** Class-entry prefixes that belong to the shell and never to the core. */
    @get:Input
    abstract val shellOnlyPrefixes: ListProperty<String>

    @get:Input
    abstract val nestedCoreEntry: Property<String>

    @get:Input
    abstract val nestedPropertiesEntry: Property<String>

    @get:Input
    abstract val serviceEntry: Property<String>

    @get:Input
    abstract val expectedContract: Property<Int>

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        for (broken in JarSplit.selfCheckProblems()) {
            problems += "the jar-split check is not working: $broken"
        }

        val coreBytes = coreJar.get().asFile.readBytes()
        val coreSha = sha256(coreBytes)
        val coreClasses = mutableListOf<String>()
        val coreClassBytes = mutableMapOf<String, ByteArray>()
        var coreHasService = false
        var coreContract: String? = null
        var coreVersion: String? = null
        JarInputStream(coreBytes.inputStream()).use { jar ->
            val attributes = jar.manifest?.mainAttributes
            coreContract = attributes?.getValue("Heimdall-Shell-Contract")
            coreVersion = attributes?.getValue("Heimdall-Core-Version")
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.name.endsWith(".class")) {
                    coreClasses += entry.name
                    coreClassBytes[entry.name] = jar.readBytes()
                }
                if (entry.name == serviceEntry.get()) {
                    coreHasService = true
                }
            }
        }
        if (!coreHasService) {
            problems += "the core jar has no ${serviceEntry.get()}, so the shell cannot find its " +
                "entry point"
        }
        if (coreContract != expectedContract.get().toString()) {
            problems += "the core jar's manifest declares contract $coreContract, expected " +
                expectedContract.get()
        }
        if (coreVersion != expectedVersion.get()) {
            problems += "the core jar's manifest declares version $coreVersion, expected " +
                expectedVersion.get()
        }

        val shellClasses = mutableListOf<String>()
        ZipFile(releaseJar.get().asFile).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { shellClasses += it.name }

            val nested = zip.getEntry(nestedCoreEntry.get())
            if (nested == null) {
                problems += "the release jar does not carry ${nestedCoreEntry.get()}"
            } else {
                val nestedSha = sha256(zip.getInputStream(nested).use { it.readBytes() })
                if (nestedSha != coreSha) {
                    problems += "the nested core (${nestedSha.take(12)}) is not the core this " +
                        "build produced (${coreSha.take(12)})"
                }
            }
            val propertiesEntry = zip.getEntry(nestedPropertiesEntry.get())
            if (propertiesEntry == null) {
                problems += "the release jar does not carry ${nestedPropertiesEntry.get()}"
            } else {
                val recorded = Properties()
                zip.getInputStream(propertiesEntry).use { recorded.load(it) }
                if (recorded.getProperty("sha256") != coreSha) {
                    problems += "core.properties records sha256 ${recorded.getProperty("sha256")}, " +
                        "but the core is $coreSha"
                }
                if (recorded.getProperty("version") != expectedVersion.get()) {
                    problems += "core.properties records version " +
                        "${recorded.getProperty("version")}, expected ${expectedVersion.get()}"
                }
                if (recorded.getProperty("contract") != expectedContract.get().toString()) {
                    problems += "core.properties records contract " +
                        "${recorded.getProperty("contract")}, expected ${expectedContract.get()}"
                }
            }
        }

        problems += JarSplit.problems(shellClasses, coreClasses, shellOnlyPrefixes.get())
        problems += JarSplit.danglingReferences(
            coreClassBytes, shellClasses.toSet(), shellOnlyPrefixes.get())

        logger.lifecycle(
            "verifyJarSplit: ${shellClasses.size} shell classes, ${coreClasses.size} core " +
                "classes, core sha256 ${coreSha.take(12)}",
        )
        if (problems.isNotEmpty()) {
            throw GradleException("the hot-swap split failed verification:\n  - " +
                problems.joinToString("\n  - "))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
