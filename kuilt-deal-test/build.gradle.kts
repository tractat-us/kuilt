import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins { id("kuilt.kmp-library") }

kotlin {
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
