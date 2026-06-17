plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-core")) // public API returns Loom/Seam — expose the contract transitively
            api(project(":kuilt-stream")) // framed() adapts the socket's Source/Sink into a Connection
            implementation(libs.kotlinx.coroutines.core)
        }

        // jvmAndAndroidMain: Ktor's raw-TCP network engine (`ktor-network`) and the
        // blocking JVM channel↔stream adapters (`toInputStream`/`toOutputStream`) ship
        // only for JVM/Android — there is no native (iOS/macOS) or wasmJs variant. The
        // whole TCP fabric lives here, leaving commonMain target-agnostic.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.network)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // iosMain / macosMain: declared explicitly because adding a manual
        // jvmAndAndroidMain intermediate disables the KMP plugin's default hierarchy
        // auto-wiring for the other platform intermediates. Both are empty — the TCP
        // fabric is not available off the JVM.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        jvmTest.dependencies {
            implementation(project(":kuilt-conformance"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
