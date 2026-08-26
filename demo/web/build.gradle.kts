import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Patchwork demo — the browser page (slice 5 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md). THE HEADLINE surface:
// open the page in N tabs = N peers stitching one shared quilt through the
// :demo-relay WebSocket hub, with a tunnel toggle that makes
// convergence-under-partition visible (go offline → stitch → reconnect →
// watch the merge).
//
// Deliberately a plain KMP module, not `kuilt.kmp-library`: no explicitApi, no
// publishing, wasmJs only. Listed in kuilt-bom's `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "patchwork.js"
            }
            // The wasm MAIN compilation runs in `build` (the page must keep
            // compiling), but test *execution* is disabled for the same reason
            // as :demo-shared: the Karma timeout config and the build-wide
            // browser-test serializer (Chrome-startup mutex) are private to the
            // `kuilt.kmp-library` convention plugin, and an unserialized Karma
            // task would reintroduce the startup races that infra exists to
            // prevent. The convergence logic this page drives is tested on the
            // JVM in :demo-shared (PatchworkSessionTest) and end-to-end over a
            // real relay in :demo-cli (RelayConvergenceIntegrationTest); this
            // module is deliberately thin DOM glue over those tested pieces.
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":demo-shared")) // PatchworkSession + RelaySpokeLoom
                implementation(project(":kuilt-quilter")) // api-exposes :kuilt-core + :kuilt-crdt
                implementation(project(":kuilt-websocket")) // WebSocketAdvertisement (the relay tag)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js) // browser WebSocket engine
                implementation(libs.ktor.client.websockets)
            }
        }
    }
}
