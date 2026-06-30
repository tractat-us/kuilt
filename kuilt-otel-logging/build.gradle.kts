plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The capture core maps onto kuilt-otel's LogRecord / WarpLogRecordExporter,
            // which are part of this module's public surface (installLogCapture takes the
            // exporter) — hence api, not implementation.
            api(project(":kuilt-otel"))
            // runCatchingCancellable — cancellation-safe best-effort capture.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // jvmAndAndroidMain: the JVM/Android capture edge is a no-op in M1. On the JVM
        // kotlin-logging logs *through* SLF4J, so capture must sit at the SLF4J layer —
        // a separate milestone. Adding this intermediate disables KMP's hierarchy
        // auto-wiring, so every other intermediate is declared explicitly below
        // (mirroring kuilt-otel / kuilt-tcp / kuilt-multipeer).
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // nonJvmMain: the oshai Appender capture edge, shared by every non-JVM target
        // (iOS, macOS, wasmJs). Off the JVM, kotlin-logging routes through its own
        // Appender mechanism, so this is where capture hooks in.
        val nonJvmMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(nonJvmMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nonJvmMain) }
        val macosArm64Main by getting { dependsOn(nonJvmMain) }
        val wasmJsMain by getting { dependsOn(nonJvmMain) }

        // nonJvmTest: exercises the oshai edge against the shared capture core on a
        // non-JVM target.
        val nonJvmTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(nonJvmTest) }
        val iosSimulatorArm64Test by getting { dependsOn(nonJvmTest) }
        val macosArm64Test by getting { dependsOn(nonJvmTest) }
        val wasmJsTest by getting { dependsOn(nonJvmTest) }
    }
}
