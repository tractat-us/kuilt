plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            // Exposed so shared virtual-time helpers (drainAntiEntropy) can take a TestScope receiver.
            api(libs.kotlinx.coroutines.test)
        }
        // JVM-only: `runConcurrencyStress` — the real-OS-thread probe harness every `*ConcurrencyTest`
        // runs on (#1135/#1784). It lives HERE rather than in `:kuilt-core`'s own jvmTest because a test
        // source set is not consumable by another module, and `:kuilt-nw`'s probe needs the same harness
        // (#2481) — two copies of a diagnostic that must stay in step is the drift this module exists to
        // prevent. `coroutines-debug` is what lets it dump *coroutine* stacks on a hang; a thread dump
        // shows only threads, and a suspended coroutine has none. JVM-scoped, so no other target sees it.
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.debug)
        }
    }
}
