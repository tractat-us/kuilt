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

        // The capture edge is one uniform commonMain hook now (oshai 8.x makes the
        // appender settable on every target). The only platform-specific piece is
        // captureDelegate(previous): non-Apple targets pass console output straight
        // through; Apple targets route it to a %-safe os_log appender. Two
        // intermediates carry those two actuals. Declaring any intermediate disables
        // KMP's hierarchy auto-wiring, so every intermediate is declared explicitly
        // below (mirroring kuilt-otel / kuilt-tcp / kuilt-multipeer).

        // nonAppleMain: captureDelegate(previous) = previous — preserve the existing
        // console appender on JVM, Android and wasmJs.
        val nonAppleMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(nonAppleMain)
        androidMain.get().dependsOn(nonAppleMain)
        val wasmJsMain by getting { dependsOn(nonAppleMain) }

        // appleMain: captureDelegate(previous) = OSLogAppender() — a %-safe Apple
        // unified-logging (os_log) appender wired via the oslog cinterop below.
        val appleMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(appleMain) }
        val iosSimulatorArm64Main by getting { dependsOn(appleMain) }
        val macosArm64Main by getting { dependsOn(appleMain) }

        // appleTest: exercises the os_log appender's %-safety natively.
        val appleTest by creating { dependsOn(commonTest.get()) }
        val iosArm64Test by getting { dependsOn(appleTest) }
        val iosSimulatorArm64Test by getting { dependsOn(appleTest) }
        val macosArm64Test by getting { dependsOn(appleTest) }
    }

    // oslog cinterop — Apple Kotlin/Native targets, main compilation.
    //
    // os/log.h ships in the Apple SDK, so no vendored sources or prebuilt libs are
    // needed; the .def's `---` body is a tiny C shim that calls the os_log macros
    // (which require a string-literal format) with the message passed as a
    // "%{public}s" argument — never as the format string. That is what makes a raw
    // '%' in a log line render literally instead of being read as a printf
    // specifier. Apple targets are host-disabled on the Linux CI runner, so this
    // cinterop is compiled on macOS (local dev + the macOS publish job).
    val oslogDefFile = layout.projectDirectory.file("src/nativeInterop/cinterop/oslog.def")
    macosArm64 {
        compilations.named("main") {
            cinterops.create("oslog") { defFile(oslogDefFile) }
        }
    }
    iosArm64 {
        compilations.named("main") {
            cinterops.create("oslog") { defFile(oslogDefFile) }
        }
    }
    iosSimulatorArm64 {
        compilations.named("main") {
            cinterops.create("oslog") { defFile(oslogDefFile) }
        }
    }
}
