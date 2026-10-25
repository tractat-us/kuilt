plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // No kuilt module and no coroutines: a sealed box is two suspend functions over byte
            // arrays, and a consumer that wants one should inherit nothing else with it. The
            // primitives are cryptography-kotlin's; this module composes them and owns no math.
            // `implementation`, not `api`: no cryptography-kotlin type appears in the public surface.
            implementation(libs.cryptography.core)
        }
        // One provider per platform, each a thin wrapper over the platform's own implementation.
        // Chosen explicitly in `platformProvider()` rather than via `CryptographyProvider.Default`,
        // which a consumer can replace process-wide with `CryptographySystem.setDefaultProvider`.
        jvmMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        androidMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        appleMain.dependencies { implementation(libs.cryptography.provider.cryptokit) }
        wasmJsMain.dependencies { implementation(libs.cryptography.provider.webcrypto) }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
