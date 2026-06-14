plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))  // public API exposes Loom/PeerId — expose the contract transitively
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
        }
        // Real Google Nearby Connections binding lives Android-only; the pure
        // adapter logic in commonMain stays GMS-free and JVM-testable.
        androidMain.dependencies {
            implementation(libs.play.services.nearby)
            implementation(libs.kotlinx.coroutines.playServices)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.logback)
        }
    }
}
