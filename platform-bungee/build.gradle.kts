plugins {
    // Java 8, like the Bukkit binding and unlike the Velocity one. That is not
    // symmetry for its own sake: BungeeCord itself was compiled at release 8 for
    // its whole history and only moved to 17 recently, so a Java-8 proxy is a
    // configuration real legacy networks still run — and the boot-smoke matrix
    // has a row that proves this module loads on one. See departure D74.
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))
    implementation(project(":platform-common"))

    // compileOnly, like every other platform API: none of it reaches the jar.
    //
    // It brings bungeecord-chat (BaseComponent, TextComponent), bungeecord-event
    // (@EventHandler, EventPriority), bungeecord-config and Guava transitively —
    // all of which the proxy provides at runtime, and all of which are equally
    // compileOnly here.
    compileOnly(libs.bungeecord.api)

    testImplementation(testFixtures(project(":core")))
    // The login gate is the one thing on this platform that can hang a player's
    // connection forever if it is wrong (BungeeCord's intents have no timeout of
    // their own), so it is tested against the REAL LoginEvent and the real
    // AsyncEvent intent machinery rather than a stand-in that would only agree
    // with the test's own idea of how intents work.
    testImplementation(libs.bungeecord.api)
}
