plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-raft"))
            implementation(libs.kotlinx.coroutines.core)
            // Ships MultiNodeRaftSim (runTest / StandardTestDispatcher / TestScope / assertTrue) in
            // MAIN so other modules' tests can use the published simulation harness without adding
            // separate deps. Pattern mirrors kuilt-deal-test's CommutativeSchemeConformanceSuite.
            api(libs.kotlinx.coroutines.test)
            api(kotlin("test"))
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // SLF4J backend so kotlin-logging in RaftEngine doesn't throw NoClassDefFoundError
            // when the harness drives real RaftNodes in tests (see kuilt-raft issue #222).
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            // Same SLF4J backend needed for Android unit-test variants (testDebug/testRelease).
            runtimeOnly(libs.logback)
        }
    }
}
