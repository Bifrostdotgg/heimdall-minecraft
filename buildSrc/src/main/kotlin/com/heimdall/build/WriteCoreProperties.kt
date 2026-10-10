package com.heimdall.build

import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `core.properties`: the nested core's version, SHA-256 and shell contract.
 *
 * <p>The shell reads this before it loads anything, and refuses an extracted core whose bytes do not
 * hash to the value here (departure D87). It is a separate file rather than manifest attributes on
 * the release jar because a `Jar` task's manifest is fixed at configuration time, and the hash only
 * exists once the core has been built.
 *
 * <p>No timestamp, deliberately: identical inputs produce an identical file, so the release jar
 * stays reproducible and this task stays cacheable.
 */
abstract class WriteCoreProperties : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coreJar: RegularFileProperty

    @get:Input
    abstract val coreVersion: Property<String>

    @get:Input
    abstract val contract: Property<Int>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(coreJar.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        output.get().asFile.writeText(
            "version=${coreVersion.get()}\n" +
                "sha256=$sha\n" +
                "contract=${contract.get()}\n",
        )
    }
}
