plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

// SRA does real 2048-bit modular exponentiation; on wasmJs (interpreted, no JIT)
// a single CardStateTest crypto case can exceed Mocha's default 2s per-test
// timeout and Karma's socket ping on slow CI runners. Both limits are raised in
// karma.config.d/timeouts.js (the Gradle `useMocha` DSL does not reach the wasmJs
// browser test task).
kotlin {
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
            implementation(project(":kuilt-conformance"))
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
