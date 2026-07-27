plugins {
    // Java 8, because :platform-bukkit consumes it and that module's floor is Spigot 1.8.8.
    // :platform-velocity is Java 17 and consumes it happily — a lower target is always loadable.
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))

    // The shipped feature modules, so HeimdallModules can name them.
    //
    // This is the only place in the build where the module set is written down, and it is here
    // because :platform-common is the one project both entry points already depend on. :app is the
    // assembler and depends on the platform modules, so it cannot be the answer; core must not
    // depend on features at all.
    implementation(project(":module-whitelist"))
    implementation(project(":module-rolesync"))
    implementation(project(":module-offenses"))
    implementation(project(":module-console"))

    // Both optional at runtime, both reached only after a guarded probe, neither shipped.
    //
    // net.luckperms:api is genuinely platform-neutral — it is the same artifact on Bukkit and on
    // Velocity — which is why the LuckPerms bridge can be written once here instead of twice.
    compileOnly(libs.luckperms.api)

    // log4j-core is the SERVER's logging backend on both families (Mojang has bundled log4j2 since
    // 1.7, and Velocity uses it directly), so it is always on the classpath at runtime and never
    // needs shipping. The version compiled against is NOT the version that will be there: Minecraft
    // 1.8.8 carries 2.0-beta9. See Log4jConsoleTap for which API surface is safe across that range
    // and why the timestamp does not come from the LogEvent.
    compileOnly(libs.log4j.core)

    testImplementation(testFixtures(project(":core")))
    // The capture path is the version-sensitive part (departure D45) and the smoke matrix only
    // reaches it transitively, so it is unit-tested against real LogEvents rather than a mock —
    // a hand-rolled LogEvent would let the test agree with itself about an API shape.
    testImplementation(libs.log4j.core)
}
