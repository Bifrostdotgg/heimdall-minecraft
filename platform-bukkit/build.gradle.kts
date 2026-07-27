plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))

    // The lowest supported server API. Compiling the shared Bukkit entry point
    // against 1.8.8 (and nothing newer) is what keeps legacy support honest.
    compileOnly(libs.spigot.api)

    // Adventure is shaded and relocated by :app. Pinned to the newest line that
    // still ships Java 8 bytecode — `--release 8` proves it on every build.
    implementation(libs.adventure.platform.bukkit)
}
