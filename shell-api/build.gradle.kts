plugins {
    id("heimdall.java8")
    `java-library`
}

// The types the shell and every core generation must agree on, and nothing else.
//
// Two groups live here, for two different reasons:
//
//   1. The hot-swap contract (com.heimdall.shell.contract): what a core is handed and what it
//      hands back. Both sides compile against it; at runtime only the shell's copy exists, because
//      the core is loaded in a child classloader that delegates to the shell first.
//
//   2. The types the public :api exposes (Payload, Envelope, Registration, OnceRegistration), plus
//      Gson underneath Payload. They keep their com.heimdall.core.* names so the published SPI does
//      not change, and they move here because a third-party plugin resolves them through the
//      shell's classloader: a copy inside a swappable core would be a different class from the one
//      the plugin linked against. Envelope comes along because it calls Payload's package-private
//      Gson bridge, and package-private access does not work across two classloaders.
//
// Anything added here can only change with a full restart, so the bar for adding to it is high.
dependencies {
    api(libs.gson)
}
