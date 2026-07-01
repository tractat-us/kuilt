plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The appender normalizes a log4j2 LogEvent into kuilt-otel-logging's
            // NormalizedLogEvent and feeds the same LogCapture core, exporting into
            // kuilt-otel's WarpLogRecordExporter. Both are part of this module's
            // public surface (installLog4j2Capture takes the exporter) — hence api.
            api(project(":kuilt-otel"))
            api(project(":kuilt-otel-logging"))
            // runCatchingCancellable — cancellation-safe best-effort capture.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        // jvmAndAndroidMain: log4j2 is JVM-world — there is no native (iOS/macOS) or
        // wasmJs variant. The whole appender lives here, leaving commonMain
        // log4j2-free so the off-JVM targets compile empty. This is the optional,
        // additive JVM/Android add-on that recovers raw-SLF4J capture (the log4j2
        // sibling of :kuilt-otel-logback); the core uniform capture edge
        // (kuilt-otel-logging) stays dependency-light.
        //
        // log4j2 is compileOnly: this module only needs the types to compile the
        // appender. A consumer brings its own logging backend at runtime and installs
        // the appender on its LoggerContext.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly(libs.log4j2.core)
                compileOnly(libs.log4j2.api)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // iosMain / macosMain: declared explicitly because the manual
        // jvmAndAndroidMain intermediate disables the KMP plugin's default hierarchy
        // auto-wiring. Both are empty — there is no log4j2 off the JVM.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        jvmTest.dependencies {
            // A real log4j2 backend so raw-SLF4J output actually flows through the
            // appender (compileOnly above keeps it off consumers' runtime classpath).
            implementation(libs.log4j2.core)
            implementation(libs.log4j2.api)
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
