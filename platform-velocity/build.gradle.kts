plugins {
    id("heimdall.java17")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))

    // Velocity 3.4 is Java 17+, which is why this is the one module compiled at
    // release 17. The annotation processor emits velocity-plugin.json from the
    // @Plugin annotation into this module's class output, and :app shades it in.
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
}
