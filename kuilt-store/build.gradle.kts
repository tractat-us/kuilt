import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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
            // The DurableStore TCK. `:kuilt-conformance` api's THIS module in its main source set,
            // and this is a TEST-only edge back the other way, so the task graph stays acyclic:
            // :kuilt-store main -> :kuilt-conformance main -> :kuilt-store test. Same shape as
            // :kuilt-quilter / :kuilt-gossip, which also subclass a suite from their own tests.
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // jvmAndAndroidMain: FileChannelDurableStore uses java.io / java.nio.channels,
        // which are available on both JVM and Android but not on iOS/macOS/wasmJs.
        // Adding this intermediate disables KMP's hierarchy auto-wiring, so all other
        // intermediates are declared explicitly below (mirroring kuilt-otel / kuilt-tcp).
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // jvmAndAndroidTest: FileChannelDurableStore ships on BOTH JVM and Android, so its
        // conformance subclass has to run on both — in jvmTest it would be compiled for Android and
        // never executed there, which is the "un-pinned is not the same as unreachable" trap: the
        // Android variant is the one an app actually depends on. Wired by hand for the same reason
        // as jvmAndAndroidMain above, and mirroring :kuilt-websocket.
        val jvmAndAndroidTest by creating { dependsOn(commonTest.get()) }
        jvmTest.get().dependsOn(jvmAndAndroidTest)
        androidUnitTest.get().dependsOn(jvmAndAndroidTest)

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

// kuilt-conformance ships kotlin-test-junit in commonMain; resolve the
// kotlin-test-framework-impl capability conflict to the JUnit4 variant so
// kuilt-conformance and the default kotlin-test wiring don't clash.
configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability(
        "org.jetbrains.kotlin:kotlin-test-framework-impl",
    ) {
        candidates.firstOrNull { (it.id as? ModuleComponentIdentifier)?.module == "kotlin-test-junit" }
            ?.let { select(it) }
    }
}
