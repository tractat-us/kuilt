# Log capture M2 — sampling gate + OTel-SDK bridge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in trace/sampling gate to `:kuilt-otel-logging` and a JVM/Android `:kuilt-otel-sdk` module bridging the OpenTelemetry SDK into kuilt's durable log buffer — both off by default, leaving M1 always-on capture byte-identical.

**Architecture:** The gate's mechanism (`TraceContextProvider`/`ActiveTrace`/`UntracedPolicy`) lives in `commonMain` and hooks the single `LogCapture.capture` choke point; its OTel wiring lives in the new JVM/Android module alongside a non-blocking `LogRecordExporter` that maps OTel `LogRecordData` into kuilt `LogRecord` and drains through an unbounded channel into `WarpLogRecordExporter`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (`Channel`), kotlinx-io `ByteString`, OpenTelemetry Java SDK (`compileOnly`), JUnit + `kotlinx-coroutines-test`.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration gets an explicit `public`/`internal`.
- Test method names carry no `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests use `StandardTestDispatcher(testScheduler)`, a **seeded** `Random`, and a virtual `kotlin.time.Clock`. **No production dispatchers** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) in test sources.
- Scope-owning types take a **required** injected `CoroutineScope` — never a real-dispatcher default.
- In any coroutine/suspend context use `runCatchingCancellable` (from `:kuilt-core`), never bare `runCatching`.
- New modules apply `id("kuilt.kmp-library")` and almost nothing else; Android namespace is `us.tractat.kuilt.<module>`.
- OpenTelemetry artifacts are **`compileOnly`** in the new module (a consumer already runs the OTel SDK). OTel version pinned in the catalog: `otel = "1.45.0"` (bump to latest stable only if resolution fails).
- References policy: abstract use case only; no third-party citations; no other `tractat-us/*` repos.
- Verify before declaring done: `./gradlew :<module>:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`); `detektAll`, never bare `detekt`.

---

### Task 1: Gate primitives + `CaptureConfig.untracedPolicy`

**Files:**
- Create: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/TraceContext.kt`
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CaptureConfig.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/TraceContextTest.kt`

**Interfaces:**
- Produces: `ActiveTrace(traceId: ByteString, spanId: ByteString, sampled: Boolean)`; `fun interface TraceContextProvider { fun current(): ActiveTrace? }`; `enum class UntracedPolicy { CAPTURE, DROP }`; `CaptureConfig.untracedPolicy: UntracedPolicy` (default `CAPTURE`).

- [ ] **Step 1: Write the failing test**

`TraceContextTest.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TraceContextTest {
    private fun bytes(n: Int) = ByteString(ByteArray(n) { it.toByte() })

    @Test
    fun activeTraceRequiresCorrectByteSizes() {
        val ok = ActiveTrace(bytes(16), bytes(8), sampled = true)
        assertEquals(16, ok.traceId.size)
        assertEquals(8, ok.spanId.size)
        assertFailsWith<IllegalArgumentException> { ActiveTrace(bytes(15), bytes(8), true) }
        assertFailsWith<IllegalArgumentException> { ActiveTrace(bytes(16), bytes(7), true) }
    }

    @Test
    fun providerIsAFunctionalInterface() {
        val trace = ActiveTrace(bytes(16), bytes(8), sampled = false)
        val provider = TraceContextProvider { trace }
        assertEquals(trace, provider.current())
        val empty = TraceContextProvider { null }
        assertNull(empty.current())
    }

    @Test
    fun captureConfigDefaultsToCaptureUntraced() {
        assertEquals(UntracedPolicy.CAPTURE, CaptureConfig().untracedPolicy)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-otel-logging:jvmTest --tests "*TraceContextTest"`
Expected: FAIL — unresolved references `ActiveTrace`, `TraceContextProvider`, `UntracedPolicy`, `untracedPolicy`.

- [ ] **Step 3: Write minimal implementation**

`TraceContext.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString

/**
 * The distributed-trace context active on the current call.
 *
 * When an app runs tracing, [LogCapture] consults a [TraceContextProvider] per
 * event: a sampled trace's [traceId]/[spanId] are stamped onto the captured
 * `LogRecord` so logs line up with spans. Byte sizes match OTLP / `SpanRecord`:
 * 16-byte trace id, 8-byte span id.
 */
public data class ActiveTrace(
    /** 16-byte (128-bit) trace id. */
    public val traceId: ByteString,
    /** 8-byte (64-bit) span id. */
    public val spanId: ByteString,
    /** Whether the trace's sampler kept this trace. */
    public val sampled: Boolean,
) {
    init {
        require(traceId.size == 16) { "traceId must be 16 bytes; got ${traceId.size}" }
        require(spanId.size == 8) { "spanId must be 8 bytes; got ${spanId.size}" }
    }
}

/** Supplies the trace context active on the current call, or `null` when none. */
public fun interface TraceContextProvider {
    /** The [ActiveTrace] active on the current call, or `null` if untraced. */
    public fun current(): ActiveTrace?
}

/** What to do with a log emitted outside any active trace. */
public enum class UntracedPolicy {
    /** Keep untraced logs (the always-on default). */
    CAPTURE,

    /** Drop untraced logs — capture only what a sampled trace covers. */
    DROP,
}
```

In `CaptureConfig.kt`, add the field to the `data class` (after `minLevel`):
```kotlin
    /**
     * What to do with a log emitted outside any active trace, when a
     * [TraceContextProvider] is configured on [LogCapture]. Ignored when no
     * provider is set (M1 always-on capture). Defaults to [UntracedPolicy.CAPTURE].
     */
    public val untracedPolicy: UntracedPolicy = UntracedPolicy.CAPTURE,
```
Also update the `CaptureConfig` KDoc line "The only filter is [minLevel]." to: "M1 capture is **always-on**; a trace/sampling gate applies only when a `TraceContextProvider` is wired into [LogCapture] (see [untracedPolicy])."

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*TraceContextTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/TraceContext.kt \
        kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CaptureConfig.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/TraceContextTest.kt
git commit -m "feat(otel-logging): trace-gate primitives (ActiveTrace, TraceContextProvider, UntracedPolicy)"
```

---

### Task 2: Wire the gate into `LogCapture`

**Files:**
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/LogCapture.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/LogCaptureGateTest.kt`

**Interfaces:**
- Consumes: `ActiveTrace`, `TraceContextProvider`, `UntracedPolicy`, `CaptureConfig.untracedPolicy` (Task 1); `WarpLogRecordExporter.export(LogRecord): ExportResult` with `ExportResult.Success`/`Failure`.
- Produces: `LogCapture(exporter, config, clock, random, traceContextProvider: TraceContextProvider? = null)` — new optional last param. `capture` stamps `traceId`/`spanId` when sampled and drops per the gate.

- [ ] **Step 1: Write the failing test**

`LogCaptureGateTest.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LogCaptureGateTest {
    private val fixedClock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }
    private fun exporter() = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
    private fun event() = NormalizedLogEvent(LogLevel.INFO, "com.app.Service", "hello")
    private fun trace(sampled: Boolean) =
        ActiveTrace(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 2 }), sampled)

    private fun capture(exporter: WarpLogRecordExporter, config: CaptureConfig, provider: TraceContextProvider?) =
        LogCapture(exporter, config, fixedClock, Random(0), provider)

    @Test
    fun nullProviderCapturesWithoutStamp() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), provider = null).capture(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertNull(rec.traceId)
        assertNull(rec.spanId)
    }

    @Test
    fun sampledTraceCapturesAndStamps() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = true) }).capture(event())
        assertNotNull(result)
        val rec = exp.snapshot().toList().single()
        assertEquals(ByteString(ByteArray(16) { 1 }), rec.traceId)
        assertEquals(ByteString(ByteArray(8) { 2 }), rec.spanId)
    }

    @Test
    fun unsampledTraceDrops() = runTest(StandardTestDispatcher()) {
        val exp = exporter()
        val result = capture(exp, CaptureConfig(), TraceContextProvider { trace(sampled = false) }).capture(event())
        assertNull(result)
        assertTrue(exp.snapshot().toList().isEmpty())
    }

    @Test
    fun untracedRespectsPolicy() = runTest(StandardTestDispatcher()) {
        val capExp = exporter()
        assertNotNull(capture(capExp, CaptureConfig(untracedPolicy = UntracedPolicy.CAPTURE), TraceContextProvider { null }).capture(event()))
        assertEquals(1, capExp.snapshot().toList().size)

        val dropExp = exporter()
        assertNull(capture(dropExp, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), TraceContextProvider { null }).capture(event()))
        assertTrue(dropExp.snapshot().toList().isEmpty())
    }
}
```

(Read side is `WarpLogRecordExporter.snapshot(): Rga<LogRecord>` → `.toList()`; constructor is `WarpLogRecordExporter(ReplicaId, DurableStore)` — both pinned from the real API and matching `CaptureEdgeTest`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*LogCaptureGateTest"`
Expected: FAIL — `LogCapture` has no 5th parameter / records not stamped.

- [ ] **Step 3: Write minimal implementation**

In `LogCapture.kt`, add the constructor param and import, then the gate. New constructor:
```kotlin
public class LogCapture(
    private val exporter: WarpLogRecordExporter,
    private val config: CaptureConfig,
    private val clock: Clock,
    private val random: Random,
    private val traceContextProvider: TraceContextProvider? = null,
) {
```
Replace the body of `capture` after the two existing guard `return null`s and before `val now = clock.now()` — insert the gate, then thread `traceId`/`spanId` into the `LogRecord`:
```kotlin
        // Trace/sampling gate. A null provider is M1 always-on capture, no stamp.
        var traceId: ByteString? = null
        var spanId: ByteString? = null
        val provider = traceContextProvider
        if (provider != null) {
            when (val trace = provider.current()) {
                null -> if (config.untracedPolicy == UntracedPolicy.DROP) return null
                else -> if (trace.sampled) {
                    traceId = trace.traceId
                    spanId = trace.spanId
                } else {
                    return null // active but unsampled → drop before export
                }
            }
        }
```
And add `traceId = traceId,` / `spanId = spanId,` to the `LogRecord(...)` constructor call. Update the `capture` KDoc to mention the trace gate (drop on unsampled / untraced-`DROP`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*LogCaptureGateTest"`
Expected: PASS. Then run the full module tests to confirm no M1 regression: `./gradlew :kuilt-otel-logging:jvmTest`.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/LogCapture.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/LogCaptureGateTest.kt
git commit -m "feat(otel-logging): trace/sampling gate + traceId/spanId stamping in LogCapture"
```

---

### Task 3: Thread the provider through `installLogCapture`

**Files:**
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/InstallLogCapture.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/InstallLogCaptureGateTest.kt`

**Interfaces:**
- Consumes: `LogCapture(..., traceContextProvider)` (Task 2).
- Produces: `installLogCapture(exporter, config, clock, random, scope, traceContextProvider: TraceContextProvider? = null)` — new optional last param, defaulting to `null` (M1 parity).

- [ ] **Step 1: Write the failing test**

`InstallLogCaptureGateTest.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class InstallLogCaptureGateTest {
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_700_000_000) }

    @Test
    fun installThreadsTheProviderIntoTheCore() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val provider = TraceContextProvider {
            ActiveTrace(ByteString(ByteArray(16) { 1 }), ByteString(ByteArray(8) { 2 }), sampled = true)
        }
        val installation =
            installLogCapture(exporter, CaptureConfig(), clock, Random(0), backgroundScope, provider)
        try {
            // Drive the installed core directly: the threaded provider must stamp.
            installation.capture.capture(NormalizedLogEvent(LogLevel.INFO, "com.app.Service", "hi"))
            val rec = exporter.snapshot().toList().single()
            assertEquals(ByteString(ByteArray(16) { 1 }), rec.traceId)
            assertEquals(ByteString(ByteArray(8) { 2 }), rec.spanId)
        } finally {
            installation.close()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*InstallLogCaptureGateTest"`
Expected: FAIL — `installLogCapture` has no 6th parameter.

- [ ] **Step 3: Write minimal implementation**

In `InstallLogCapture.kt`, add the param to `installLogCapture` and pass it to `LogCapture`:
```kotlin
public fun installLogCapture(
    exporter: WarpLogRecordExporter,
    config: CaptureConfig,
    clock: Clock,
    random: Random,
    scope: CoroutineScope,
    traceContextProvider: TraceContextProvider? = null,
): LogCaptureInstallation {
    val capture = LogCapture(exporter, config, clock, random, traceContextProvider)
```
Add a `@param traceContextProvider` KDoc line: "optional trace/sampling gate — `null` (default) is always-on M1 capture; a provider gates and stamps per [CaptureConfig.untracedPolicy] and the trace's `sampled` flag."

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*InstallLogCaptureGateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/InstallLogCapture.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/InstallLogCaptureGateTest.kt
git commit -m "feat(otel-logging): optional TraceContextProvider on installLogCapture"
```

---

### Task 4: Scaffold the `:kuilt-otel-sdk` module

**Files:**
- Create: `kuilt-otel-sdk/build.gradle.kts`
- Create: `kuilt-otel-sdk/module.md`
- Modify: `settings.gradle.kts` (add `include(":kuilt-otel-sdk")` after `:kuilt-otel-logback`)
- Modify: `gradle/libs.versions.toml` (add OTel version + libraries)

**Interfaces:**
- Produces: an empty JVM/Android module that compiles, with OTel artifacts on the compile classpath.

- [ ] **Step 1: Add catalog entries**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
otel = "1.45.0"
```
Under `[libraries]` add:
```toml
opentelemetry-api = { module = "io.opentelemetry:opentelemetry-api", version.ref = "otel" }
opentelemetry-sdk-logs = { module = "io.opentelemetry:opentelemetry-sdk-logs", version.ref = "otel" }
opentelemetry-sdk-common = { module = "io.opentelemetry:opentelemetry-sdk-common", version.ref = "otel" }
opentelemetry-sdk-testing = { module = "io.opentelemetry:opentelemetry-sdk-testing", version.ref = "otel" }
```

- [ ] **Step 2: Create the build script**

`kuilt-otel-sdk/build.gradle.kts` (mirrors `:kuilt-otel-logback`'s manual source-set wiring — the `jvmAndAndroidMain` intermediate disables auto-wiring, so the apple/wasm intermediates are declared empty):
```kotlin
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
```

> **Verify the empty-intermediate wiring against `:kuilt-otel-logback/build.gradle.kts`.** Copy its exact `iosMain`/`macosMain`/`*Test` source-set block if the above differs — that module is the working reference for a JVM/Android-only KMP module with the auto-wiring disabled.

- [ ] **Step 3: Create `module.md`**

`kuilt-otel-sdk/module.md`:
```markdown
# Module kuilt-otel-sdk

Bridge an app's existing OpenTelemetry setup into kuilt's offline-first log
buffer — on the JVM and Android.

Two optional, additive pieces, both for apps that already run OpenTelemetry:

- `OtelSdkTraceContextProvider` lets kuilt's log capture follow your tracing
  sampler: logs emitted inside a sampled span are kept and stamped with their
  trace and span id, so logs and spans line up; logs outside a trace follow a
  policy you choose.
- `KuiltLogRecordExporter` is an OpenTelemetry SDK log exporter: point your
  existing log pipeline at it and every record also lands in kuilt's durable,
  extractable buffer — without adopting kuilt's own capture edge.

Neither is required for kuilt logging; off the JVM there is nothing here.
```

- [ ] **Step 4: Wire the module into the build**

In `settings.gradle.kts`, after `include(":kuilt-otel-logback")` add:
```kotlin
include(":kuilt-otel-sdk")
```

- [ ] **Step 5: Verify the empty module compiles**

Run: `./gradlew :kuilt-otel-sdk:compileKotlinJvm :kuilt-otel-sdk:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL (empty module, nothing to compile yet).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml kuilt-otel-sdk/build.gradle.kts kuilt-otel-sdk/module.md
git commit -m "feat(otel-sdk): scaffold :kuilt-otel-sdk module (JVM/Android, compileOnly OTel)"
```

---

### Task 5: `OtelSdkTraceContextProvider`

**Files:**
- Create: `kuilt-otel-sdk/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/sdk/OtelSdkTraceContextProvider.kt`
- Test: `kuilt-otel-sdk/src/jvmTest/kotlin/us/tractat/kuilt/otel/sdk/OtelSdkTraceContextProviderTest.kt`

**Interfaces:**
- Consumes: `TraceContextProvider`/`ActiveTrace` (Task 1); OTel `Span.current().spanContext`.
- Produces: `class OtelSdkTraceContextProvider : TraceContextProvider`.

- [ ] **Step 1: Write the failing test**

`OtelSdkTraceContextProviderTest.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtelSdkTraceContextProviderTest {
    private val provider = OtelSdkTraceContextProvider()

    @Test
    fun invalidContextMapsToNull() {
        assertNull(provider.fromSpanContext(SpanContext.getInvalid()))
    }

    @Test
    fun sampledContextMapsWithBytesAndFlag() {
        val ctx = SpanContext.create(
            "0102030405060708090a0b0c0d0e0f10",
            "1112131415161718",
            TraceFlags.getSampled(),
            TraceState.getDefault(),
        )
        val trace = provider.fromSpanContext(ctx)!!
        assertEquals(16, trace.traceId.size)
        assertEquals(8, trace.spanId.size)
        assertEquals(true, trace.sampled)
        assertEquals(ByteString(*ctx.traceIdBytes), trace.traceId)
        assertEquals(ByteString(*ctx.spanIdBytes), trace.spanId)
    }

    @Test
    fun unsampledContextMapsWithFlagFalse() {
        val ctx = SpanContext.create(
            "0102030405060708090a0b0c0d0e0f10",
            "1112131415161718",
            TraceFlags.getDefault(),
            TraceState.getDefault(),
        )
        assertEquals(false, provider.fromSpanContext(ctx)!!.sampled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-sdk:jvmTest --tests "*OtelSdkTraceContextProviderTest"`
Expected: FAIL — `OtelSdkTraceContextProvider` unresolved.

- [ ] **Step 3: Write minimal implementation**

`OtelSdkTraceContextProvider.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.otel.logging.ActiveTrace
import us.tractat.kuilt.otel.logging.TraceContextProvider

/**
 * A [TraceContextProvider] backed by the OpenTelemetry SDK's current context.
 *
 * Reads `Span.current().spanContext` on each call: a valid span context becomes
 * an [ActiveTrace] carrying the 16-byte trace id, 8-byte span id, and the
 * context's sampled flag; an invalid context (no active span) is `null`.
 */
public class OtelSdkTraceContextProvider : TraceContextProvider {
    override fun current(): ActiveTrace? = fromSpanContext(Span.current().spanContext)

    /** Map an OTel [SpanContext] to an [ActiveTrace], or `null` if invalid. Visible for testing. */
    internal fun fromSpanContext(context: SpanContext): ActiveTrace? {
        if (!context.isValid) return null
        return ActiveTrace(
            traceId = ByteString(*context.traceIdBytes),
            spanId = ByteString(*context.spanIdBytes),
            sampled = context.isSampled,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-sdk:jvmTest --tests "*OtelSdkTraceContextProviderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-sdk/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/sdk/OtelSdkTraceContextProvider.kt \
        kuilt-otel-sdk/src/jvmTest/kotlin/us/tractat/kuilt/otel/sdk/OtelSdkTraceContextProviderTest.kt
git commit -m "feat(otel-sdk): OtelSdkTraceContextProvider reading Span.current()"
```

---

### Task 6: `KuiltLogRecordExporter` — the non-blocking ingress bridge

**Files:**
- Create: `kuilt-otel-sdk/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/sdk/KuiltLogRecordExporter.kt`
- Test: `kuilt-otel-sdk/src/jvmTest/kotlin/us/tractat/kuilt/otel/sdk/KuiltLogRecordExporterTest.kt`

**Interfaces:**
- Consumes: `WarpLogRecordExporter.export(LogRecord): ExportResult` (`Success`/`Failure`); OTel `LogRecordExporter`/`LogRecordData`/`CompletableResultCode`.
- Produces: `class KuiltLogRecordExporter(exporter: WarpLogRecordExporter, random: Random, scope: CoroutineScope) : LogRecordExporter`. **No `Clock`** — `LogRecordData` already carries both timestamps (see plan handoff note).

- [ ] **Step 1: Write the failing test**

`KuiltLogRecordExporterTest.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.logs.TestLogRecordData
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuiltLogRecordExporterTest {
    private fun data(): LogRecordData = TestLogRecordData.builder()
        .setResource(Resource.getDefault())
        .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"))
        .setTimestamp(1_700_000_000_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS)
        .setObservedTimestamp(1_700_000_001_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS)
        .setSeverity(Severity.INFO)
        .setSeverityText("INFO")
        .setBody("hello")
        .setAttributes(Attributes.builder().put("k", "v").build())
        .setSpanContext(
            SpanContext.create(
                "0102030405060708090a0b0c0d0e0f10", "1112131415161718",
                TraceFlags.getSampled(), TraceState.getDefault(),
            ),
        )
        .build()

    @Test
    fun exportMapsAndDrainsWithSuccess() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        val code = bridge.export(listOf(data()))
        testScheduler.runCurrent()
        assertTrue(code.isSuccess)
        val rec = warp.snapshot().toList().single()
        assertEquals("hello", rec.body)
        assertEquals(9, rec.severityNumber) // INFO
        assertEquals("v", rec.attributes["k"])
        assertEquals(16, rec.traceId!!.size)
        assertEquals(8, rec.spanId!!.size)
        assertEquals(8, rec.recordId.size)
    }

    @Test
    fun flushCompletesAfterQueueDrains() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        bridge.export(listOf(data()))
        val flush = bridge.flush()
        testScheduler.runCurrent()
        assertTrue(flush.isSuccess)
    }

    @Test
    fun shutdownDrainsThenCompletes() = runTest {
        val warp = WarpLogRecordExporter(ReplicaId("p"), InMemoryDurableStore())
        val bridge = KuiltLogRecordExporter(warp, Random(0), backgroundScope)
        bridge.export(listOf(data()))
        val down = bridge.shutdown()
        testScheduler.runCurrent()
        assertTrue(down.isSuccess)
        assertEquals(1, warp.snapshot().toList().size)
    }
}
```

> **OTel version note:** if `TestLogRecordData` builder setters differ in the resolved OTel version, adjust to that version's builder API (all setters above are stable across 1.4x). `WarpLogRecordExporter(ReplicaId, DurableStore)` + `snapshot().toList()` are pinned from the real API.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-sdk:jvmTest --tests "*KuiltLogRecordExporterTest"`
Expected: FAIL — `KuiltLogRecordExporter` unresolved.

- [ ] **Step 3: Write minimal implementation**

`KuiltLogRecordExporter.kt`:
```kotlin
package us.tractat.kuilt.otel.sdk

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.core.runCatchingCancellable
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random

/**
 * An OpenTelemetry SDK [LogRecordExporter] that funnels records into kuilt's
 * durable [WarpLogRecordExporter] buffer.
 *
 * For apps **already** running the OTel SDK: register this as a log exporter and
 * every record also lands in kuilt's offline-first buffer, extractable through the
 * same tap — without using kuilt's own capture edge. It never replaces the SDK's
 * other exporters; it is purely additive.
 *
 * ## Non-blocking bridge
 *
 * The SDK SPI is already non-blocking — `export`/`flush`/`shutdown` each return a
 * [CompletableResultCode], OTel's async completion handle. This bridge honours
 * that: [export] maps each [LogRecordData] to a [LogRecord], enqueues the batch on
 * an unbounded [Channel] with a fresh result code, and returns immediately. A
 * single scope-bound drain coroutine runs the `suspend`
 * [WarpLogRecordExporter.export] per record and then completes the batch's code —
 * `succeed()` on success, `fail()` otherwise. No thread ever blocks, and the SDK
 * still gets real completion signalling.
 *
 * @param exporter the durable kuilt buffer records are written into.
 * @param random source of the per-record 8-byte id (required — never unseeded).
 * @param scope the drain coroutine's scope (required — never a real-dispatcher
 *   default). [shutdown] completes when the drain finishes.
 */
public class KuiltLogRecordExporter(
    private val exporter: WarpLogRecordExporter,
    private val random: Random,
    scope: CoroutineScope,
) : LogRecordExporter {

    private class Batch(val records: List<LogRecord>, val code: CompletableResultCode)

    private val queue = Channel<Batch>(Channel.UNLIMITED)

    private val drain = scope.launch {
        for (batch in queue) {
            // Best-effort: a capture failure must never propagate to the SDK's
            // logging path. runCatchingCancellable still rethrows cancellation.
            val result = runCatchingCancellable {
                batch.records.forEach { exporter.export(it) }
            }
            if (result.isSuccess) batch.code.succeed() else batch.code.fail()
        }
    }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        val code = CompletableResultCode()
        val records = logs.map { it.toLogRecord() }
        // trySend fails only after shutdown() closed the channel.
        if (queue.trySend(Batch(records, code)).isFailure) code.fail()
        return code
    }

    /** Complete once everything queued before this call has drained (FIFO marker). */
    override fun flush(): CompletableResultCode {
        val code = CompletableResultCode()
        if (queue.trySend(Batch(emptyList(), code)).isFailure) code.fail()
        return code
    }

    /** Stop accepting, drain what's buffered, then complete. */
    override fun shutdown(): CompletableResultCode {
        val code = CompletableResultCode()
        queue.close()
        drain.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) code.succeed() else code.fail()
        }
        return code
    }

    private fun LogRecordData.toLogRecord(): LogRecord {
        val ctx = spanContext
        val traced = ctx.isValid
        return LogRecord(
            recordId = ByteString(random.nextBytes(RECORD_ID_BYTES)),
            severityNumber = severity.severityNumber.takeIf { it != 0 },
            severityText = severityText,
            body = bodyValue?.asString(),
            attributes = buildMap { attributes.forEach { key, value -> put(key.key, value.toString()) } },
            timestampEpochNanos = timestampEpochNanos.takeIf { it != 0L },
            observedEpochNanos = observedTimestampEpochNanos.takeIf { it != 0L },
            traceId = if (traced) ByteString(*ctx.traceIdBytes) else null,
            spanId = if (traced) ByteString(*ctx.spanIdBytes) else null,
        )
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8
    }
}
```

> **API note:** `bodyValue` maps to OTel's `getBodyValue()` (added 1.42; present in the pinned 1.45). If the resolved version predates it, use `body?.asString()` instead. `severity.severityNumber` is `Severity.getSeverityNumber()`; 0 = unspecified.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-sdk:jvmTest --tests "*KuiltLogRecordExporterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-sdk/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/sdk/KuiltLogRecordExporter.kt \
        kuilt-otel-sdk/src/jvmTest/kotlin/us/tractat/kuilt/otel/sdk/KuiltLogRecordExporterTest.kt
git commit -m "feat(otel-sdk): non-blocking KuiltLogRecordExporter ingress bridge"
```

---

### Task 7: Full-build verification + PR

**Files:** none (verification + integration).

- [ ] **Step 1: Detekt + full build of touched modules, cache-disabled**

Run:
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel-logging:build :kuilt-otel-sdk:build detektAll --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tasks EXECUTED (not `FROM-CACHE`). If any test-compile shows `FROM-CACHE`, re-run with `--no-build-cache`. detektAll clean.

- [ ] **Step 2: Whole-repo build (Android + Native variants CI will run)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. (The `commonMain` gate code compiles on every target; `:kuilt-otel-sdk` compiles empty off the JVM.)

- [ ] **Step 3: Open the PR**

```bash
git push -u origin otel-sdk-990
gh pr create --title "feat(otel): log-capture M2 — sampling gate + OTel-SDK bridge" \
  --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Closes #990. Part of #986.

M2 layers two **opt-in** capabilities on M1's always-on capture:

- **Sampling gate** (`:kuilt-otel-logging`, commonMain): `TraceContextProvider`/`ActiveTrace`/`UntracedPolicy` + `CaptureConfig.untracedPolicy`. `LogCapture` (optional provider param) drops unsampled/untraced-`DROP` events and stamps `traceId`/`spanId` onto sampled ones. Null provider = M1 parity.
- **OTel-SDK bridge** (`:kuilt-otel-sdk`, JVM/Android, compileOnly OTel): `OtelSdkTraceContextProvider` (reads `Span.current()`) + `KuiltLogRecordExporter`, a non-blocking `LogRecordExporter` that enqueues onto an unbounded channel and completes each `CompletableResultCode` from a single drain coroutine into `WarpLogRecordExporter`.

Spec: `docs/superpowers/specs/2026-07-01-log-capture-m2-otel-sdk-design.md`.
EOF
)"
gh pr merge --auto --squash
```

- [ ] **Step 4: Open the PR in the browser**

Run: `gh pr view --web`

---

## Self-Review

**Spec coverage:**
- Sampling-gate types (`TraceContextProvider`, `ActiveTrace`, `untracedPolicy`) → Task 1. ✓
- Gate decision table (null/untraced/sampled/unsampled) + stamping → Task 2. ✓
- `installLogCapture` provider wiring → Task 3. ✓
- New JVM/Android module + catalog + `compileOnly` OTel → Task 4. ✓
- `OtelSdkTraceContextProvider` reading `Span.current().spanContext` → Task 5. ✓
- `LogRecordExporter` ingress via non-blocking `CompletableResultCode` + channel drain → Task 6. ✓
- Fake-provider gate tests + adapter compiles + full build green + one ready PR → Tasks 2, 5, 6, 7. ✓

**Deviation from spec (intentional, YAGNI):** the ingress `KuiltLogRecordExporter` takes `(exporter, random, scope)` — **no `Clock`**. The spec listed `Clock` as injected, but `LogRecordData` already carries `timestampEpochNanos`/`observedTimestampEpochNanos`, so a `Clock` there is a dead param. The gate path (`LogCapture`) keeps its `Clock` unchanged. Flag confirmed acceptable in handoff.

**Placeholder scan:** none — every code step is concrete. Two explicit "interface check" callouts (Tasks 2, 6) instruct verifying `WarpLogRecordExporter`'s real constructor/accessor names, since those weren't pinned from source in this plan.

**Type consistency:** `TraceContextProvider.current()`, `ActiveTrace(traceId, spanId, sampled)`, `UntracedPolicy.{CAPTURE,DROP}`, `CaptureConfig.untracedPolicy`, `LogCapture(..., traceContextProvider)`, `installLogCapture(..., traceContextProvider)`, `KuiltLogRecordExporter(exporter, random, scope)` — consistent across all tasks.
