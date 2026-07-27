import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

java {
    // One toolchain for the whole build; per-module bytecode levels are set with
    // `options.release` by the heimdall.java8 / heimdall.java17 conventions so a
    // single JDK 21 can produce the mixed-bytecode jar. Test tasks inherit this
    // toolchain, so Java 8 modules still run their tests on 21.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Precompiled script plugins do not get the generated `libs` accessor, so the
// catalog is looked up by hand. The coordinates still live in exactly one place.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    "testImplementation"(libs.findLibrary("mockito-core").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // `--release 8` warns about the obsolete source level on every compile; the
    // level is deliberate (Spigot 1.8.8 support) so the noise is suppressed.
    options.compilerArgs.add("-Xlint:-options")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
