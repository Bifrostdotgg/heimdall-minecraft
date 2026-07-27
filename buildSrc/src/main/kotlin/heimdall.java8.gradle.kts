plugins {
    id("heimdall.java-common")
}

// Java 8 bytecode (classfile major version 52) — required for Spigot 1.8.8.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}
