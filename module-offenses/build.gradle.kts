import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    // FakePlatform, FakePlayer, FakeCommandSource, RecordingCommands and RecordingLogger. A module
    // test asserts against behaviour — "that command was dispatched", "the sender was told the
    // tier" — which needs a fake server rather than a mock, and copying one per module is how five
    // near-identical no-op loggers end up in a repo.
    testImplementation(testFixtures(project(":core")))
    // The wire contract, executable. The offense flow is tested against the real ApiClient talking
    // to :stub-bot over a socket, not against a mocked client: the escalation response shape is the
    // thing most likely to be wrong, and two of our own files agreeing with each other would not
    // catch it. The stub is a transcription of the bot's own handlers.
    testImplementation(project(":stub-bot"))
}

/**
 * Lets the TEST classpaths — and only the test classpaths — accept a Java 21 artifact.
 *
 * Copied from :core, for the same reason and with the same blast radius. `options.release = 8`
 * makes Gradle stamp `org.gradle.jvm.version = 8` on every one of this module's configurations, and
 * variant-aware resolution then refuses :stub-bot (which compiles at `--release 21`) outright,
 * before javac is ever asked whether it minds. That check is about what a *consumer* of the
 * published artifact could run — the right question for the main classpath, the wrong one for a
 * fixture that only ever runs on this build's own JDK 21 toolchain.
 *
 * The main classpaths keep 8, both compile tasks keep `--release 8`, and the shipped jar is
 * untouched — :app does not depend on :stub-bot, and :app:verifyShadowJar reads every class in the
 * jar and fails on anything above classfile major 52.
 */
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { classpath ->
    classpath.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }
}
