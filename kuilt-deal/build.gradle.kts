plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core"))
            api(project(":kuilt-crdt"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.ionspin.bignum)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // jvmAndAndroidMain: BouncyCastle ships only for JVM/Android.
        // ElGamalScheme lives here so it doesn't compile on iOS/macOS/wasmJs.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bouncycastle)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // Explicit iosMain/macosMain intermediates required when a manual
        // intermediate (jvmAndAndroidMain) disables KMP auto-hierarchy wiring.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val wasmJsMain by getting { dependsOn(commonMain.get()) }

        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
