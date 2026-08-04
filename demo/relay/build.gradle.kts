// Patchwork demo — the relay process (slice 3 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md).
//
// A runnable WebSocket hub the Patchwork peers connect through. Deliberately a
// plain kotlinJvm `application` module, not `kuilt.kmp-library`: no explicitApi,
// no publishing. Listed in kuilt-bom's `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
    // Detekt is registered by `kuilt.kmp-library`, which this module doesn't apply (#2005).
    id("kuilt.detekt-jvm")
}

application {
    mainClass = "us.tractat.kuilt.demo.relay.MainKt"
}

dependencies {
    implementation(project(":demo-shared"))
    implementation(project(":kuilt-core"))
    implementation(project(":kuilt-crdt"))
    implementation(project(":kuilt-quilter"))
    implementation(project(":kuilt-gossip"))
    implementation(project(":kuilt-websocket"))
    // Observability — capture the relay's own logs and metrics, and offer them for
    // reach-in extraction by the :demo-tap harness (slice 4 of the demo-app design).
    implementation(project(":kuilt-otel"))
    implementation(project(":kuilt-otel-logging"))
    implementation(project(":kuilt-otel-tap"))
    implementation(libs.kotlin.logging) // the relay's own "patchwork.relay" application logger
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverWebsockets)
    runtimeOnly(libs.logback) // SLF4J backend for kuilt's kotlin-logging
}
