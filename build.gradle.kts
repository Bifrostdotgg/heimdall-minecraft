plugins {
    id("com.gradleup.shadow") version "8.3.6" apply false
}

// Every module inherits the root coordinates; per-module build scripts only
// declare their language level and dependencies.
allprojects {
    group = rootProject.group
    version = rootProject.version
}
