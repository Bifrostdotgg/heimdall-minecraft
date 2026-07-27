import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    // Deliberately NOT java8/java17: this module ships no runtime code, it only
    // runs ArchUnit over everything else on the toolchain JVM (21).
    id("heimdall.java-common")
}

/**
 * Projects the rules are NOT evaluated against, and why.
 *
 * Everything else in the build is scanned automatically. That is the important
 * property here: adding `module-punishments` tomorrow puts it under every
 * conformance rule with zero edits to this module. A hand-maintained list would
 * silently leave new modules unpoliced, which is exactly how architecture tests
 * rot into decoration.
 */
val notScanned = setOf(
    // The rules live here; scanning ourselves would pick up the deliberate
    // violation fixtures.
    "conformance",
    // Pure assembler, no sources of its own. Its output is checked by
    // :app:verifyShadowJar instead.
    "app",
)

val scannedProjects = rootProject.subprojects.filter { it.name !in notScanned }

/**
 * Resolves each scanned project's compiled main classes as a *directory* rather
 * than a jar, so the importer reads exactly our own output and nothing else.
 * Requesting the CLASSES library-elements variant also wires up the compile task
 * dependencies for free.
 */
val scannedClasses: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.CLASSES),
        )
    }
}

dependencies {
    testImplementation(libs.archunit)

    scannedProjects.forEach { scanned ->
        scannedClasses(project(scanned.path))
        // Also on the test classpath so ArchUnit can resolve the types our classes
        // reference, instead of filling the import with stubs.
        testImplementation(project(scanned.path))
    }

    // Only so the deliberate-violation fixtures have platform types to leak.
    testImplementation(libs.spigot.api)
    testImplementation(libs.adventure.platform.bukkit)
}

tasks.test {
    inputs.files(scannedClasses).withPropertyName("scannedClasses")
    val classDirs = scannedClasses
    val moduleNames = scannedProjects.map { it.name }
    doFirst {
        // Passed as directories rather than letting ArchUnit scan the classpath:
        // the import is then exactly the set of modules derived from the project
        // graph, and the presence guard in the test can hold it to that.
        systemProperty(
            "heimdall.conformance.classDirs",
            classDirs.files.joinToString(File.pathSeparator),
        )
        systemProperty("heimdall.conformance.moduleNames", moduleNames.joinToString(","))
    }
}
