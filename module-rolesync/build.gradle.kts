import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))

    // FakePlatform, FakeLuckPerms, FakePlayer and RecordingLogger. The whole module is tested
    // against these rather than against a server: role sync's behaviour is entirely "what did it
    // ask LuckPerms to do, and when", and a fake that records the arguments answers that exactly.
    testImplementation(testFixtures(project(":core")))

    // The join-time snapshot request is tested against the stub bot rather than a fake gateway:
    // what matters is the request that goes over the wire (and, with the whitelist answering
    // logins, the one that does not), and the stub counts them.
    testImplementation(project(":stub-bot"))
    // The stub hands back the request body it received as a Gson object.
    testImplementation(libs.gson)
}

/**
 * Lets the TEST classpaths, and only those, accept :stub-bot's Java 21 artifact. Verbatim from
 * module-whitelist/build.gradle.kts, which explains why; the shipped bytecode stays --release 8.
 */
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { classpath ->
    classpath.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }
}
