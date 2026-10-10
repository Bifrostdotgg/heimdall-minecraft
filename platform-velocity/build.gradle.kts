plugins {
    id("heimdall.java17")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))
    implementation(project(":platform-common"))

    // Velocity 3.4 is Java 17+, which is why this module is compiled at release 17.
    // There is no annotation processor here any more: the @Plugin class is the
    // shell's (:shell-velocity), so velocity-plugin.json is generated there.
    compileOnly(libs.velocity.api)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.velocity.api)
}
