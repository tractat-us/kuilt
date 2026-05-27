plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
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
    }
}
