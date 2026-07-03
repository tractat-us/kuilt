plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            // Exposed so shared virtual-time helpers (drainAntiEntropy) can take a TestScope receiver.
            api(libs.kotlinx.coroutines.test)
        }
    }
}
