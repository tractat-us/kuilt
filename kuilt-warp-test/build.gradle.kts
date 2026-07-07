plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-warp"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.core)
            // Ships WasmRuntimeConformanceSuite (kotlin-test @Test + runTest) in MAIN
            // (not commonTest) so each WasmRuntime impl's tests can subclass the TCK.
            // Pattern mirrors kuilt-deal-test's CommutativeSchemeConformanceSuite and
            // kuilt-raft-test's MultiNodeRaftSim.
            api(libs.kotlinx.coroutines.test)
            api(kotlin("test"))
        }
        jvmMain.dependencies { api(kotlin("test-junit")) }
        androidMain.dependencies { api(kotlin("test-junit")) }
    }
}
