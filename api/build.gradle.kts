plugins {
    id("heimdall.java8")
    // The SPI's own signatures speak core types: Payload on every method, Registration on the
    // subscribe. A third-party plugin cannot implement or call any of it without them on its
    // compile classpath, so this is one of the few places where `api` is correct rather than an
    // accident.
    //
    // Those types live in :shell-api, not :core, since the hot-swap split (departure D87). A
    // third-party plugin resolves them through the shell's classloader, which is permanent; the
    // core is swappable and must not be what the SPI's signatures point into.
    `java-library`
}

dependencies {
    api(project(":shell-api"))
}
