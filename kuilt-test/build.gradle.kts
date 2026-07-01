plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-gossip"))
            implementation(libs.kotlinx.coroutines.core)
            // Exposed so shared virtual-time helpers (drainAntiEntropy) can take a TestScope receiver.
            api(libs.kotlinx.coroutines.test)
        }
    }
}
