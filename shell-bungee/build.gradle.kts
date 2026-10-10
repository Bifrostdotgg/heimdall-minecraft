plugins {
    id("heimdall.java8")
}

// The BungeeCord shell: the Plugin bungee.yml names, and the glue between :shell-common and the
// BungeeCord API. Compiled against the same API floor as :platform-bungee (departure D74).
dependencies {
    implementation(project(":shell-common"))
    compileOnly(libs.bungeecord.api)

    testImplementation(libs.bungeecord.api)
}
