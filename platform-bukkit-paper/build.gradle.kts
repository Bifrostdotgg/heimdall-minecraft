plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))

    // Paper-only API, loaded reflectively at runtime so the same jar still starts
    // on plain Spigot. 1.16.5 is the oldest Paper line whose API carries the
    // hooks v3 needs while still being Java 8 bytecode.
    compileOnly(libs.paper.api)
}
