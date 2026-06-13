plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kover)
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
            api(project(":kuilt-crdt"))
            api(project(":kuilt-test"))
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

// koverVerify is NOT bound to the check lifecycle — coverage verification is
// opt-in via: ./gradlew koverVerify koverHtmlReport
// onCheck = false keeps the threshold rules available for explicit invocation
// without paying the kover instrumentation cost on every CI build.
kover {
    reports {
        total {
            verify {
                onCheck = false
                rule("Minimum 70% line coverage") {
                    // Initial threshold: softer than :kuilt-crdt because commonMain here is
                    // a test-harness library exercised by consumer modules' test runs, not its own.
                    // Initial threshold: set from first measurement — raise via follow-up issues.
                    minBound(70)
                }
            }
        }
    }
}
