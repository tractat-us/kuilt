plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Deliberately near-empty. A durable key→bytes store is three suspend methods over
            // opaque byte arrays; a consumer that wants one should not inherit a CRDT lattice, a
            // serialization format or a logging facade along with it. Anything added here is paid
            // for by every consumer of every implementation, so add nothing that only one needs.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // jvmAndAndroidMain: FileChannelDurableStore uses java.io / java.nio.channels,
        // which are available on both JVM and Android but not on iOS/macOS/wasmJs.
        // Adding this intermediate disables KMP's hierarchy auto-wiring, so all other
        // intermediates are declared explicitly below (mirroring kuilt-otel / kuilt-tcp).
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
    }
}
