plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    // For the TickSource interface only. The direction is one-way: :platform-bukkit knows this
    // module by name and loads it reflectively, and must never depend on it.
    implementation(project(":platform-bukkit"))

    // Paper-only API, loaded reflectively at runtime so the same jar still starts
    // on plain Spigot. 1.16.5 is the oldest Paper line whose API carries the
    // hooks v3 needs while still being Java 8 bytecode.
    compileOnly(libs.paper.api)

    // The dialog API exists from Paper 1.21.7 on, and PaperLinkDialogs reaches all of it by
    // reflection (see its javadoc for why it cannot be a direct call). Tests resolve every one of
    // those lookups against the real jar, so a renamed method fails this build, not a server.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.paper.api.dialogs)
}

/**
 * Lets the TEST classpaths accept paper-api 1.21.7, which is Java 21 bytecode.
 *
 * The same arrangement as core's, for the same reason: `--release 8` stamps
 * `org.gradle.jvm.version = 8` on every configuration and variant-aware resolution then refuses a
 * Java 21 artifact outright. Raised on the test classpaths alone; main stays at 8, and
 * :app:verifyCoreJar still fails on any shipped class above classfile major 52.
 */
listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { classpath ->
    classpath.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }
}
