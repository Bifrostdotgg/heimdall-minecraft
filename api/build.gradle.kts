plugins {
    id("heimdall.java8")
    // The SPI's own signatures speak core types — Payload on every method, Registration on the
    // subscribe. A third-party plugin cannot implement or call any of it without them on its
    // compile classpath, so this is the one place besides core itself where `api` is correct
    // rather than an accident. Everything else in the build keeps `implementation` as the only
    // option available.
    `java-library`
}

dependencies {
    api(project(":core"))
}
