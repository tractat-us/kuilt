plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-warp"))
        }
        commonTest.dependencies {
            implementation(project(":kuilt-warp-runtime"))
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // FedAvgWarpSimTest drives three WarpNodes over a raftSimTest cluster (F4 E2E),
            // so it needs the shared Raft simulation harness (raftSimTest).
            implementation(project(":kuilt-raft-test"))
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
