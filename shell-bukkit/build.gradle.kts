plugins {
    id("heimdall.java8")
}

// The Bukkit-family shell: the JavaPlugin plugin.yml names, and the glue between the platform-free
// :shell-common and the Bukkit API. Compiled against the 1.8.8 API, like :platform-bukkit, so
// everything it calls exists on every supported server.
dependencies {
    implementation(project(":shell-common"))
    compileOnly(libs.spigot.api)

    testImplementation(libs.spigot.api)
}
