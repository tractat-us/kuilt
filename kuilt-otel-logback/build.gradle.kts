plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The appender normalizes a logback event into kuilt-otel-logging's
            // NormalizedLogEvent and feeds the same LogCapture core, exporting into
            // kuilt-otel's WarpLogRecordExporter. Both are part of this module's
            // public surface (installLogbackCapture takes the exporter) — hence api.
            api(project(":kuilt-otel"))
            api(project(":kuilt-otel-logging"))
            // runCatchingCancellable — cancellation-safe best-effort capture.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        // jvmAndAndroidMain: logback and SLF4J are JVM-world — there is no native
        // (iOS/macOS) or wasmJs variant. The whole appender lives here, leaving
        // commonMain logback-free so the off-JVM targets compile empty. This is the
        // optional, additive JVM/Android add-on that recovers raw-SLF4J capture; the
        // core uniform capture edge (kuilt-otel-logging) stays dependency-light.
        //
        // logback + slf4j are compileOnly: this module only needs the types to
        // compile the appender. A consumer brings its own logging backend at
        // runtime and installs the appender on its LoggerContext.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly(libs.logback)
                compileOnly(libs.slf4j.api)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // iosMain / macosMain: declared explicitly because the manual
        // jvmAndAndroidMain intermediate disables the KMP plugin's default hierarchy
        // auto-wiring. Both are empty — there is no logback off the JVM.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        jvmTest.dependencies {
            // A real logback backend so raw-SLF4J output actually flows through the
            // appender (compileOnly above keeps it off consumers' runtime classpath).
            implementation(libs.logback)
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
