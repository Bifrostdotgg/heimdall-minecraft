import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))

    testImplementation(testFixtures(project(":core")))

    // The login gate is tested against the real bot contract rather than a hand-written fixture.
    // The six connection-attempt outcomes are exactly the shape most likely to be got wrong, and a
    // fake would only agree with whatever this repo believes about them; :stub-bot is a
    // transcription of the bot's own handlers.
    testImplementation(project(":stub-bot"))
}

/**
 * Lets the TEST classpaths — and only those — accept :stub-bot's Java 21 artifact.
 *
 * Verbatim from core/build.gradle.kts, and for the same reason: `options.release = 8` stamps
 * `org.gradle.jvm.version = 8` on every configuration, and variant-aware resolution then refuses
 * the fixture outright, before javac is ever asked whether it minds. That check is about what a
 * consumer of the published artifact could run — the right question for the main classpath and the
 * wrong one for a fixture that only ever runs on this build's own JDK 21 toolchain.
 *
 * The shipped bytecode is untouched: compileJava keeps --release 8, and :app:verifyShadowJar reads
 * every class in the jar and fails on anything above classfile major 52.
 */
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { classpath ->
    classpath.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }
}
