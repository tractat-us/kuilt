plugins { id("kuilt.kmp-library") }

// The SRA conformance TCK runs 2048-bit crypto on wasmJs (interpreted, no JIT),
// which can exceed Mocha's default 2s per-test timeout and Karma's socket ping on
// slow CI runners. Both limits are raised in karma.config.d/timeouts.js (the
// Gradle `useMocha` DSL does not reach the wasmJs browser test task).
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-deal"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.core)
            // Ships a kotlin-test-based conformance suite in MAIN (not commonTest)
            // so other modules' tests can subclass CommutativeSchemeConformanceSuite.
            api(kotlin("test"))
        }
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
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
