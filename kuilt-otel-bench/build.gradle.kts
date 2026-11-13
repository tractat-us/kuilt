// :kuilt-otel-bench — JVM-only cost measurements for the kuilt-otel buffer on a server (#3283).
//
// NOT published: the plain kotlinJvm plugin (no kuilt.kmp-library, no kuilt.publish), and listed
// in kuilt-bom's `deliberatelyUnpublished`. The same mould as :kuilt-scale.
//
// Two kinds of test live here, split by name:
//   * `*SmokeTest` runs in the default build. It drives every arm once at small sizes and asserts
//     only that each rig fired — the iteration counts, the prefilled series, the durable writes.
//     It keeps the arms compiling AND executing, so a measurement nobody has run for a month still
//     works the day someone needs a baseline row. It asserts no timing.
//   * `*Benchmark` takes the measurements. Excluded from the test task unless
//     `-Pkuilt.benchmark.tests=true` — a task-level gate, so an un-run benchmark is absent from the
//     results XML rather than a green row (AGENTS.md on -P-gated probes). Run them with:
//       ./gradlew :kuilt-otel-bench:test -Pkuilt.benchmark.tests=true --tests '*Benchmark'
//     Tables go to stdout and to build/reports/otel-bench/<arm>.md.

plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    testImplementation(project(":kuilt-otel"))
    testImplementation(project(":kuilt-otel-sdk"))
    testImplementation(project(":kuilt-otel-otlp"))
    testImplementation(project(":kuilt-store"))
    testImplementation(project(":kuilt-crdt"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.core)
    testImplementation(libs.kotlinx.serialization.cbor)
    // The SDK bridge arm feeds KuiltMetricExporter real SDK MetricData; kuilt-otel-sdk takes the
    // SDK compileOnly, so the bench brings it.
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk.metrics)
    testImplementation(libs.opentelemetry.sdk.common)
    // The edge-drain arm POSTs into a MockEngine: the bench prices the edge's bookkeeping, not a
    // network, so it needs no real engine.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val runBenchmarks = providers.gradleProperty("kuilt.benchmark.tests").orNull == "true"

tasks.test {
    useJUnitPlatform()
    // The 10k-series arms hold several encoded copies of the whole map at once.
    maxHeapSize = "2g"
    if (runBenchmarks) {
        testLogging.showStandardStreams = true
        // A measurement is never up to date: re-running it is the point.
        outputs.upToDateWhen { false }
    } else {
        filter.excludeTestsMatching("*Benchmark")
    }
}
