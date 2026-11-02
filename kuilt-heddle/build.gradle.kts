plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// Every `*ConcurrencyTest` in this module is a real-threaded probe (the name is the contract, as in
// `:kuilt-core`). A box saturated by sibling test JVMs delays its coroutines far past anything useful,
// so it is EXCLUDED from the normal run and executes only under -Pconcurrency.stress.tests=true, on
// the isolated runner of the deliberately non-blocking `concurrency-probes` job. So `ci-required` does
// not pin `HeddleControlPlane.submit`'s atomic check-and-register: a test dispatcher cannot reach that
// window at all. The deterministic halt behaviour stays pinned by `HeddleControlLogFailClosedTest`.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<Test>().configureEach {
    // The exclusion applies only when the flag is OFF: a build-defined exclude would otherwise win over
    // a command-line `--tests "*ConcurrencyTest"` include and match nothing.
    if (!runConcurrencyStress) {
        filter { excludeTestsMatching("*ConcurrencyTest") }
    } else {
        // The probe harness installs DebugProbes, which attaches an agent and loads JNA; both warn on
        // stderr on current JDKs unless pre-approved, and stderr cleanliness is evidence on a hang.
        jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-native-access=ALL-UNNAMED")
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            api(project(":kuilt-core"))
            api(project(":kuilt-raft"))
            implementation(project(":kuilt-quilter"))
            implementation(project(":kuilt-liveness"))
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
