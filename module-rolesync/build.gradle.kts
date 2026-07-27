plugins {
    id("heimdall.java8")
}

dependencies {
    implementation(project(":core"))

    // FakePlatform, FakeLuckPerms, FakePlayer and RecordingLogger. The whole module is tested
    // against these rather than against a server: role sync's behaviour is entirely "what did it
    // ask LuckPerms to do, and when", and a fake that records the arguments answers that exactly.
    testImplementation(testFixtures(project(":core")))
}
