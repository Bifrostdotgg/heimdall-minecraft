plugins {
    id("heimdall.java-common")
}

// Java 21 bytecode (classfile major version 65) — the toolchain's own level.
//
// This convention exists for modules that are deliberately NOT shipped in the
// plugin jar and therefore have no legacy-server floor to respect: :stub-bot
// runs on CI and dev machines only. Applying it is a statement that the module
// is not distributable, which is why it is a separate convention rather than
// "just leave `options.release` unset" — an unset release level looks like an
// oversight, and would silently start producing whatever the toolchain is
// bumped to next.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
