plugins {
    id("heimdall.java-common")
}

// Java 17 bytecode (classfile major version 61) — required by the Velocity API.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
