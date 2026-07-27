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

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "testImplementation"("org.mockito:mockito-core:4.11.0")
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
