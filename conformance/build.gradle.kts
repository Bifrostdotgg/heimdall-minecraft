plugins {
    // Deliberately NOT java8/java17: this module ships no runtime code, it only
    // runs ArchUnit over everything else on the toolchain JVM (21).
    id("heimdall.java-common")
}

dependencies {
    testImplementation(libs.archunit)

    // Every module whose classes the rules are evaluated against. These land on
    // the test runtime classpath, which is what ClassFileImporter scans.
    testImplementation(project(":core"))
    testImplementation(project(":api"))
    testImplementation(project(":platform-bukkit"))
    testImplementation(project(":platform-bukkit-paper"))
    testImplementation(project(":platform-velocity"))
    testImplementation(project(":module-whitelist"))
    testImplementation(project(":module-rolesync"))
    testImplementation(project(":module-offenses"))
    testImplementation(project(":module-console"))

    // Only so the deliberate-violation fixture has a platform type to leak.
    testImplementation(libs.spigot.api)
}
