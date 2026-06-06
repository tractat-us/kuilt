plugins {
    id("kuilt.kmp-library")
}

// Shareable transport-contract conformance harness. Unlike a normal module's
// commonTest (which sibling modules cannot see), this lives in commonMain so
// every fabric adapter can subclass SeamConformanceSuite from its own test
// source set. It therefore exposes kotlin-test / coroutines-test as `api`.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-session"))
            api(project(":kuilt-raft"))
            // This module intentionally ships a kotlin-test-based suite in MAIN
            // (not commonTest) so other modules' tests can subclass it. That means
            // each platform's main compilation needs the kotlin.test framework
            // backing that is normally auto-wired only for test source sets.
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.coroutines.core)
        }
        // JVM & Android resolve kotlin.test.Test via a JUnit typealias — supply it.
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
    }
}
