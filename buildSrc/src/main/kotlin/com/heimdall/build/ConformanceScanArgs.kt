package com.heimdall.build

import java.io.File
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/**
 * Hands the conformance test the exact set of module class directories to scan.
 *
 * This is a real class rather than a lambda in the build script because a SAM-converted lambda in a
 * `.kts` file captures the enclosing script object, which the configuration cache cannot serialize.
 *
 * [classDirs] is annotated `@Classpath` so the compiled classes are a proper task input: the test
 * re-runs when any scanned module changes, and does not when only its timestamps do.
 */
class ConformanceScanArgs(
    @get:Classpath val classDirs: FileCollection,
    @get:Input val moduleNames: String,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> = listOf(
        "-Dheimdall.conformance.classDirs=" +
            classDirs.files.joinToString(File.pathSeparator),
        "-Dheimdall.conformance.moduleNames=$moduleNames",
    )
}
