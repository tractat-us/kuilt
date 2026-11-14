plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        // No commonMain dependencies, deliberately. This module sits under every kuilt module that
        // does arithmetic a wrap would corrupt, the CRDT zoo included, so whatever it depends on
        // every one of them inherits. Anything added here has to be worth that.
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
        }
    }
}
