plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The helpers extend LogTapClient and surface LogRecord, so the tap peer
            // and the otel buffer are both part of this module's public surface.
            api(project(":kuilt-otel-tap"))
            api(project(":kuilt-otel"))
            // Shipped as test support so consumers reach for assertAll alongside the
            // log helpers from their own tests — hence api, not implementation.
            api(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.core)
            // NDJSON artifact serialization of LogRecord.
            implementation(libs.kotlinx.serialization.json)
            // Multiplatform Sink for the per-device artifact writer.
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.bytestring)
        }
        commonTest.dependencies {
            // The end-to-end example captures a real kotlin-logging line through the
            // uniform install edge, then extracts it via the tap.
            implementation(project(":kuilt-otel-logging"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The capture delegate forwards to the previous SLF4J-backed appender on
            // JVM; a binding keeps that passthrough from warning.
            runtimeOnly(libs.logback)
        }
        androidUnitTest.dependencies {
            runtimeOnly(libs.logback)
        }
    }
}
