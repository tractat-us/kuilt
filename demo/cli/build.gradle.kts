// Patchwork demo — the terminal peer (slice 3 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md).
//
// A runnable CLI that joins a Patchwork session over the :demo-relay WebSocket
// hub: stitch cells, render the merged quilt, and toggle tunnel mode
// (disconnect → keep stitching locally → reconnect → watch the merge).
// Deliberately a plain kotlinJvm `application` module, not `kuilt.kmp-library`:
// no explicitApi, no publishing. Listed in kuilt-bom's `deliberatelyUnpublished`
// set.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
    // Detekt is registered by `kuilt.kmp-library`, which this module doesn't apply (#2005).
    id("kuilt.detekt-jvm")
}

application {
    mainClass = "us.tractat.kuilt.demo.cli.MainKt"
}

dependencies {
    implementation(project(":demo-shared"))
    implementation(project(":kuilt-core"))
    implementation(project(":kuilt-crdt"))
    implementation(project(":kuilt-quilter"))
    implementation(project(":kuilt-gossip"))
    implementation(project(":kuilt-websocket"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    runtimeOnly(libs.logback) // SLF4J backend for kuilt's kotlin-logging

    testImplementation(project(":demo-relay"))
    testImplementation(project(":kuilt-test")) // assertAll
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
}
