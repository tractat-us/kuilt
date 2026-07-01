plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Public surface consumes kuilt-otel types (OtlpEdge, records, digests).
            api(project(":kuilt-otel"))
            // runCatchingCancellable — cancellation-safe sends and digest reads.
            implementation(project(":kuilt-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor) // producer-local sent-set persistence
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlin.logging)
            api(libs.kotlinx.io.bytestring)
        }

        // Per-target Ktor engines. The client runs on all targets, so this module is
        // all-target (no server piece, no jvmAndAndroidMain intermediate — the default
        // KMP hierarchy auto-wiring stays on). Engines attach to the leaf source sets,
        // which always exist for the declared targets.
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        androidMain.dependencies { implementation(libs.ktor.client.cio) }
        val iosArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val iosSimulatorArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val macosArm64Main by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        val wasmJsMain by getting { dependencies { implementation(libs.ktor.client.js) } }

        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.serverTestHost)
            implementation(libs.ktor.serverNetty)
        }
    }
}
