import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // SRA does real 2048-bit modular exponentiation; on wasmJs (interpreted,
    // no JIT) a single CardStateTest crypto case blocks the browser's JS thread
    // for seconds, overrunning Mocha's default 2s per-test timeout (and Karma's
    // socket ping — see karma.config.d/timeouts.js). Give it ample headroom.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.ionspin.bignum)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlin.testJunit)
        }
    }
}

// Forward -Pkuilt.benchmark.tests=true to the JVM test process so the timing
// benchmarks opt in (skipped by default — hard timing thresholds flake under
// shared-JVM GC pressure and on contended CI runners).
tasks.withType<Test>().configureEach {
    val flag = providers.gradleProperty("kuilt.benchmark.tests").orNull
    if (flag != null) systemProperty("kuilt.benchmark.tests", flag)
}
