plugins {
    id("kuilt.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The bridge maps OTel LogRecordData into kuilt-otel's LogRecord and
            // exports through WarpLogRecordExporter; the trace-gate provider
            // implements kuilt-otel-logging's TraceContextProvider. Both are part
            // of this module's public surface — hence api.
            api(project(":kuilt-otel"))
            api(project(":kuilt-otel-logging"))
            // runCatchingCancellable — cancellation-safe drain.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.bytestring)
        }

        // jvmAndAndroidMain: the OpenTelemetry SDK is JVM-world — no native/wasm
        // variant. The whole bridge lives here; commonMain/native/wasm compile
        // empty. OTel artifacts are compileOnly — a consumer already running the
        // OTel SDK brings them at runtime; kuilt never forces the SDK on anyone.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly(libs.opentelemetry.api)
                compileOnly(libs.opentelemetry.sdk.logs)
                compileOnly(libs.opentelemetry.sdk.common)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        // Empty off-JVM intermediates (auto-wiring disabled by the manual
        // jvmAndAndroidMain above), mirroring :kuilt-otel-logback.
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(macosMain) }

        // jvmTest: OTel is a real runtime dep for the tests, plus sdk-testing for
        // TestLogRecordData; logback backs any SLF4J on the classpath.
        jvmTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.opentelemetry.api)
            implementation(libs.opentelemetry.sdk.logs)
            implementation(libs.opentelemetry.sdk.testing)
        }
    }
}
