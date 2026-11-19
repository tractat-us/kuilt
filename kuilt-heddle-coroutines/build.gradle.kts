plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-heddle-core"))
            // `api`: the pool's signatures suspend, and a consumer calls them from its own coroutines.
            api(libs.kotlinx.coroutines.core)
            // The pool's one lock, `reentrantLock`.
            implementation(libs.kotlinx.atomicfu)
            // A hand-out failure no waiter is queued to receive is logged at error, with its trace.
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // On the compile classpath, not just the runtime one: the failure-log test attaches a Logback
        // `ListAppender` to the pool's logger and asserts on what it emitted.
        jvmTest.dependencies {
            implementation(libs.logback)
        }
        // The SLF4J backend kotlin-logging needs on the Android unit-test variant.
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}

// ── `*ConcurrencyTest` is a real-threaded probe, and opt-in ─────────────────────────────────────
//
// `FairSharePoolConcurrencyTest` runs the pool on `Dispatchers.Default` to show its one lock holds
// under real threads, which no test dispatcher can show: under `runTest` everything is serial. A
// box saturated by sibling builds delays real threads far past anything useful, so, as in
// `:kuilt-core` and `:kuilt-heddle`, the probe is excluded from every test task unless
// `-Pconcurrency.stress.tests=true`, and runs on the isolated runner of the non-blocking
// `concurrency-probes` CI job. The exclusion is on `AbstractTestTask`, so the native and wasm tasks
// are covered too, and an excluded probe is absent from the results XML rather than present and green
// (`forbidRuntimeSelfSkippingProbe`). With the flag on no exclusion is added, so a
// `--tests "*ConcurrencyTest"` include can match.
val runConcurrencyStress = providers.gradleProperty("concurrency.stress.tests").orNull == "true"
tasks.withType<AbstractTestTask>().configureEach {
    if (!runConcurrencyStress) {
        // Both spellings, for the reason `:kuilt-heddle-core`'s scale-probe gate gives: the wasmJs
        // Karma runner matches the pattern against `<class>.<method>`.
        filter.excludeTestsMatching("*ConcurrencyTest")
        filter.excludeTestsMatching("*ConcurrencyTest.*")
    }
}
