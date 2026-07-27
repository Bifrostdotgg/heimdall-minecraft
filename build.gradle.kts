plugins {
    alias(libs.plugins.shadow) apply false
}

// Every module inherits the root coordinates; per-module build scripts only
// declare their language level and dependencies.
allprojects {
    group = rootProject.group
    version = rootProject.version
}
