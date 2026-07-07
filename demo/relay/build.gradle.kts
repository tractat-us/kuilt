// Patchwork demo — the relay process (slice 3 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md).
//
// A runnable WebSocket hub the Patchwork peers connect through. Deliberately a
// plain kotlinJvm `application` module, not `kuilt.kmp-library`: no explicitApi,
// no publishing. Listed in kuilt-bom's `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverWebsockets)
    runtimeOnly(libs.logback) // SLF4J backend for kuilt's kotlin-logging
}
