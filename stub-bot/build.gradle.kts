plugins {
    // Never shipped — see the kdoc on heimdall.java21 for why that is stated by
    // choosing this convention rather than by leaving the release level unset.
    id("heimdall.java21")
    // Only for the `api` configuration below: StubWsServer extends Java-WebSocket's
    // WebSocketServer, so that type is genuinely part of this module's public
    // surface, and Gradle's model has an exact way to say so.
    `java-library`
    application
}

dependencies {
    // Gson is already the build's JSON library; reusing it keeps the wire shapes
    // the stub emits and the ones the plugin parses expressed in the same model.
    implementation(libs.gson)
    // `api`, not `implementation`: `StubBot.ws()` returns a StubWsServer, which IS a
    // Java-WebSocket WebSocketServer. A consumer — core's integration tests — cannot
    // so much as name the return type without the library on its compile classpath,
    // and hiding it would only mean every consumer re-declaring the dependency by
    // hand. It still never ships: :app does not depend on :stub-bot, and
    // :app:verifyShadowJar's allowlist would fail the build if it somehow did.
    api(libs.java.websocket)
    // Java-WebSocket logs through slf4j. Without a binding it prints a
    // "no providers were found" warning on every run and swallows its own
    // diagnostics; slf4j-simple is one jar and routes them to stderr, which is
    // exactly what a CI fixture wants when a handshake fails.
    runtimeOnly(libs.slf4j.simple)
    testRuntimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("com.heimdall.stubbot.Main")
    applicationDefaultJvmArgs = listOf(
        // Keep Java-WebSocket's own chatter out of the smoke logs unless someone is
        // debugging the fixture itself; the stub prints its own structured lines.
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        // The launcher inherits the platform charset otherwise, which mangles the
        // non-ASCII in the stub's log lines (and in the §-coded kick messages it
        // echoes back) on a Windows console or a non-UTF-8 container locale.
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

// `installDist` produces stub-bot/build/install/stub-bot/bin/stub-bot, which is
// what the smoke compose file runs. Wiring it into `assemble` means a plain
// `./gradlew build` leaves a runnable launcher behind, so the smoke harness never
// has to know which Gradle task to invoke first.
tasks.assemble {
    dependsOn(tasks.named("installDist"))
}
