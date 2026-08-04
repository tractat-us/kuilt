import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Patchwork demo — shared session core (slice 2 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md).
//
// Deliberately a PLAIN KMP module, not `kuilt.kmp-library`: no explicitApi, no
// publishing, no full target set — just the two targets the demo needs (JVM for
// the relay/CLI/tap, wasmJs for the browser page). Listed in kuilt-bom's
// `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    // Declared LAST so it applies after the Kotlin Multiplatform plugin — see the
    // ordering note in `kuilt.detekt-kmp`. Gives this module the same type-resolved
    // detekt coverage and the same `detektAll` entry point as a `kuilt.kmp-library`
    // module (#2016).
    id("kuilt.detekt-kmp")
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // The wasm MAIN + TEST compilations run in `build` (proving the shared
            // core stays wasm-compatible for :demo-web), but test *execution* is
            // JVM-only for now: the Karma timeout config and the build-wide
            // browser-test serializer (Chrome-startup mutex) are private to the
            // `kuilt.kmp-library` convention plugin, and an unserialized Karma task
            // would reintroduce the startup races that infra exists to prevent.
            // :demo-web (slice 5) is where real browser execution lands.
            testTask { enabled = false }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-quilter")) // api-exposes :kuilt-core + :kuilt-crdt
            // RelaySpokeLoom — the client half of the Patchwork star, shared by
            // the :demo-cli terminal peer (JVM) and the :demo-web page (wasmJs).
            implementation(project(":kuilt-gossip"))
            implementation(project(":kuilt-websocket"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test")) // assertAll
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            runtimeOnly(libs.logback) // SLF4J backend for :kuilt-quilter's kotlin-logging
        }
    }
}
