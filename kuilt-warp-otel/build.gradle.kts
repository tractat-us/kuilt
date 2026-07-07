plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-warp"))
            api(project(":kuilt-warp-planning"))
            implementation(project(":kuilt-otel"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            // WarpOps — the generated registrar carrying the auto-registered echo op.
            implementation(project(":kuilt-warp-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
