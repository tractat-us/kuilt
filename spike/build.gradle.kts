// Phase-0 connectivity spike for kuilt-nw (#1403). THROWAWAY app harness;
// the K/N binding is keeper code that seeds RealNwApi.
//
// Deliberately NOT `kuilt.kmp-library` — no Android/wasm/Dokka/explicitApi
// ceremony for a spike. Apple targets only. Gated out of the root build graph
// via `-PincludeSpike` in settings.gradle.kts so a signing-less CI runner is
// never asked to build it.
plugins { alias(libs.plugins.kotlinMultiplatform) }

kotlin {
    val fwName = "SpikeKit"
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = fwName
            isStatic = true
        }
    }
    // macosArm64 gives a fast on-Mac loopback compile/run without a device.
    macosArm64()

    sourceSets {
        // iosArm64 + iosSimulatorArm64 + macosArm64 → the default hierarchy
        // template auto-creates `appleMain`; the whole probe lives there.
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
