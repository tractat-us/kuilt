// Patchwork demo — the reach-in harness (slice 4 of the demo-app design,
// docs/superpowers/specs/2026-07-07-demo-app-design.md).
//
// A runnable laptop harness that reaches into a running :demo-relay and pulls its
// captured logs + metrics: a one-shot telemetry panel, then an optional live log
// tail. Deliberately a plain kotlinJvm `application` module, not `kuilt.kmp-library`:
// no explicitApi, no publishing. Listed in kuilt-bom's `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
    // Detekt is registered by `kuilt.kmp-library`, which this module doesn't apply (#2005).
    id("kuilt.detekt-jvm")
}

application {
    mainClass = "us.tractat.kuilt.demo.tap.MainKt"
}

dependencies {
    implementation(project(":demo-shared")) // TapWire — the addresses the relay opened
    implementation(project(":kuilt-core"))
    implementation(project(":kuilt-otel")) // LogRecord, MetricKey
    implementation(project(":kuilt-otel-tap")) // LogTapClient, MetricTapClient
    implementation(project(":kuilt-websocket"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    runtimeOnly(libs.logback) // SLF4J backend for kuilt's kotlin-logging
}
