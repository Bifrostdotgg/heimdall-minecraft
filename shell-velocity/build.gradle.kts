plugins {
    id("heimdall.java17")
}

// The Velocity shell: the @Plugin class, and the glue between :shell-common and the Velocity API.
// Java 17 for the same reason :platform-velocity is: the Velocity API is.
//
// The annotation processor lives here now rather than in :platform-velocity, because the @Plugin
// class does. It writes velocity-plugin.json into this module's class output, with the version
// inlined from ShellBuildConstants, and the shell jar picks it up from there.
dependencies {
    implementation(project(":shell-common"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    testImplementation(libs.velocity.api)
}
