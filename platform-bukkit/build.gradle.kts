plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))
    implementation(project(":platform-common"))

    // The lowest supported server API. Compiling the shared Bukkit entry point
    // against 1.8.8 (and nothing newer) is what keeps legacy support honest.
    compileOnly(libs.spigot.api)

    // Adventure is shaded and relocated by :app. Pinned to the newest line that
    // still ships Java 8 bytecode — `--release 8` proves it on every build.
    implementation(libs.adventure.platform.bukkit)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.spigot.api)

    // ZelChatChannels reaches ZelChat by reflection (see its javadoc for why it cannot be a direct
    // call). Its tests resolve every one of those lookups against the real API jar, so a renamed
    // method fails this build, not a server. Not transitive: the jar's POM drags in paper-api 1.21,
    // a second copy of org.bukkit next to the 1.8.8 spigot-api these tests compile against.
    testImplementation(libs.zelchat.api) {
        isTransitive = false
    }
    // Paper ships Adventure's plain-text serializer beside Adventure itself, and ZelChatChannels
    // turns ZelChat's component into text through it. The tests need it on the classpath to match.
    testImplementation(libs.adventure.text.serializer.plain)
}

repositories {
    // ZelChat's API is published here and nowhere else. Scoped to its group, so no other
    // dependency can ever resolve from it.
    maven("https://repo.pinodev.it/releases") {
        content {
            includeGroup("it.pino.zelchat")
        }
    }
}

/**
 * Lets the TEST classpaths accept zelchat-api, which is Java 21 bytecode.
 *
 * The same arrangement as :platform-bukkit-paper's for paper-api 1.21.7: `--release 8` stamps
 * `org.gradle.jvm.version = 8` on every configuration, and variant-aware resolution then refuses a
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
