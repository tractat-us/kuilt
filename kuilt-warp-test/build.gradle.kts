plugins {
    id("kuilt.kmp-library")
    // Dogfoods @WarpOp auto-registration: the echo op in WarpTestOps.kt is collected
    // into a generated us.tractat.kuilt.warp.test.WarpOps registrar at build time.
    id("kuilt.warp-ops")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-warp"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.core)
            // MultiNodeWarpSim's execution log is written from node coroutines — explicit
            // lock, per the no-confinement-as-mutex policy.
            implementation(libs.kotlinx.atomicfu)
            // Ships WasmRuntimeConformanceSuite and MultiNodeWarpSim (kotlin-test @Test +
            // runTest) in MAIN (not commonTest) so each WasmRuntime impl's tests can
            // subclass the TCK and multi-node warp tests can use the published harness.
            // Pattern mirrors kuilt-deal-test's CommutativeSchemeConformanceSuite and
            // kuilt-raft-test's MultiNodeRaftSim.
            api(libs.kotlinx.coroutines.test)
            api(kotlin("test"))
        }
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
        jvmTest.dependencies {
            // SLF4J backend so kotlin-logging inside WarpNode doesn't throw
            // NoClassDefFoundError when the harness self-tests drive real nodes
            // (same wiring as kuilt-raft-test; see kuilt-raft issue #222).
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
