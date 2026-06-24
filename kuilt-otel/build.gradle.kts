plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-crdt"))
            // kuilt-core provides runCatchingCancellable and will be needed for
            // WarpOtlpBridge (A5) once that is built. Keep as an implementation dep
            // (not api) — the kuilt-otel surface does not expose Seam/Swatch types.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlin.logging)
            // ByteString — value-based equals/hashCode for trace/span IDs stored in ORSet.
            // kotlinx-io-bytestring is a transitive of kotlinx-io-core but we declare it
            // explicitly because we use it directly in the public API.
            api(libs.kotlinx.io.bytestring)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // jvmAndAndroidMain: FileChannelDurableStore uses java.io / java.nio.channels,
        // which are available on both JVM and Android but not on iOS/macOS/wasmJs.
        // Adding this intermediate disables KMP's hierarchy auto-wiring, so all other
        // intermediates are declared explicitly below (mirroring kuilt-tcp / kuilt-multipeer).
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // appleMain: NSFileManagerDurableStore uses platform.Foundation, which is available
        // on all Apple targets without an explicit dependency (built into the K/N distribution).
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosMain by creating { dependsOn(appleMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        // appleTest: tests for NSFileManagerDurableStore that use NSTemporaryDirectory().
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }

        jvmTest.dependencies {
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
