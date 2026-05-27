plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        wasmJsTest.dependencies {
            implementation(project(":kuilt-conformance"))
        }
        // wasmJsMain inherits from commonMain; the WebRTC actuals are wasmJs-only.
        // Other targets (jvm/android/ios/macos) compile the common interfaces with
        // no concrete factory — there is no WebRTC implementation off the browser.
    }
}
