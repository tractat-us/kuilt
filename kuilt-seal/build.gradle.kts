plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        // MANUAL appleMain, as in :kuilt-otel-tap and :kuilt-store. The default hierarchy template's
        // appleMain compiles, but Dokka's source-set discovery never registers it once it holds real
        // files, and dokkaGenerateModuleHtml fails with "Cannot find source set with id
        // :kuilt-seal/appleMain". Creating it by hand disables the template, so every Apple
        // intermediate is wired here; jvm, android and wasmJs need none.
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(appleMain) }

        commonMain.dependencies {
            // No kuilt module and no coroutines: a sealed box is two suspend functions over byte
            // arrays, and a consumer that wants one should inherit nothing else with it. The
            // primitives are cryptography-kotlin's; this module composes them and owns no math.
            // `implementation`, not `api`: no cryptography-kotlin type appears in the public surface.
            implementation(libs.cryptography.core)
            // The library's DER codec, for writing PKCS #8 with its public key (SealPrivateKey.encodePkcs8).
            implementation(libs.cryptography.serialization.asn1)
            implementation(libs.cryptography.serialization.asn1.modules)
        }
        // One provider per platform, each a thin wrapper over the platform's own implementation.
        // Chosen explicitly in `platformProvider()` rather than via `CryptographyProvider.Default`,
        // which a consumer can replace process-wide with `CryptographySystem.setDefaultProvider`.
        jvmMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        androidMain.dependencies { implementation(libs.cryptography.provider.jdk) }
        appleMain.dependencies { implementation(libs.cryptography.provider.cryptokit) }
        wasmJsMain.dependencies { implementation(libs.cryptography.provider.webcrypto) }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    constraints {
        // cryptography-core 0.6.0 asks for kotlinx-io 0.9.0, whose wasmJs Node interop contains
        // `import.meta`; a browser bundle (Karma's webpack here, and a consumer's) then fails with
        // "Cannot use 'import.meta' outside a module" before any test runs. 0.9.1, the version the
        // rest of kuilt pins, has none. A constraint rather than a dependency: this module never
        // touches kotlinx-io itself, and the constraint is published, so consumers get 0.9.1 too.
        add("commonMainImplementation", libs.kotlinx.io.core) {
            because("kotlinx-io 0.9.0's wasmJs import.meta breaks browser bundles")
        }
    }
}
