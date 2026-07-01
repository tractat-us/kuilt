# Native trace context provider — off-JVM sampled-gate — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `:kuilt-otel-logging` a **`commonMain`** `TraceContextProvider` that resolves an app-set `ActiveTrace` on **wasmJs, iOS and macOS** — the platforms the log-extraction story targets — so the sampling gate (shipped in #990) stamps/drops there, not only on the JVM. No OpenTelemetry SDK dependency. Closes #1029; part of #986.

**Architecture:** The app-facing source of truth is an `ActiveTrace` carried in the `CoroutineContext` via `withActiveTrace(trace) { … }`. Because the gate resolves the trace at the **synchronous** `CapturingAppender.log()` edge (landed in #1034 — `LogCapture.resolveTrace()` → `provider.current()`, non-`suspend`), a coroutine-context element cannot be read there directly. An `ActiveTraceElement : ThreadContextElement<ActiveTrace?>` therefore **mirrors** the trace into an execution-local slot as the coroutine is dispatched; `CoroutineContextTraceProvider.current()` reads that slot synchronously at the edge. The slot is the only per-platform seam: `ThreadLocal` on JVM/Android, a Kotlin/Native `@ThreadLocal` on Apple, a plain module-level `var` on single-threaded wasmJs. On JVM an app keeps `OtelSdkTraceContextProvider`; the interface is identical, so the choice is per-install.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines `1.11.0` (`ThreadContextElement`, `withContext`), kotlinx-io `ByteString`, kotlinx-atomicfu, oshai kotlin-logging, `kotlinx-coroutines-test`. Tests run on **jvm + wasmJs + iosSimulatorArm64 + macosArm64** via `commonTest`.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration gets an explicit `public`; internal seams get `internal`. New public types carry KDoc.
- Test method names carry **no** `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests use `runTest` with `StandardTestDispatcher(testScheduler)` where ordering matters, a **seeded** `Random`, and a virtual `kotlin.time.Clock`. **No production dispatchers** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) in test sources. Drain coroutines live on `backgroundScope`.
- No `!!`. No confinement-as-mutex: the escape-hatch holder guards its field with atomicfu, not a single-thread dispatcher.
- In any coroutine/suspend context use `runCatchingCancellable` (from `:kuilt-core`), never bare `runCatching`.
- References policy: abstract use case only; no third-party citations; no other `tractat-us/*` repos.
- **This module has hand-wired KMP source sets** (the manual `nonAppleMain`/`appleMain` intermediates disable auto-wiring — see its `build.gradle.kts`). Every new intermediate and its leaves must be declared explicitly. Verify each edit against the existing block.
- Verify before declaring done: `./gradlew :kuilt-otel-logging:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`); `detektAll`, never bare `detekt`. **A JVM-green is not proof** — the whole point is off-JVM, so `wasmJsTest`, `iosSimulatorArm64Test`, `macosArm64Test` are hard bars (Task 6).

## Ground-truth references (read before starting)

- `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/TraceContext.kt` — the shipped `ActiveTrace` / `fun interface TraceContextProvider { fun current(): ActiveTrace? }` / `UntracedPolicy`. **Do not change these types** — the native provider *implements* the interface.
- `.../LogCapture.kt` — `resolveTrace(): ActiveTrace? = traceContextProvider?.current()` is invoked at the edge; `capture()` gates on `event.activeTrace`. Non-`suspend` `current()` is a hard constraint.
- `.../CapturingAppender.kt` — `log()` calls `capture.resolveTrace()` synchronously on the caller and does `events.trySend(normalized.copy(activeTrace = …))`.
- `.../InstallLogCapture.kt` — `installLogCapture(exporter, config, clock, random, scope, traceContextProvider = null)`; the native provider is passed here.
- `kuilt-otel-sdk/.../OtelSdkTraceContextProvider.kt` — the JVM provider, for interface parity (`class … : TraceContextProvider { override fun current() … }`).
- `kuilt-otel-logging/src/commonTest/.../GateResolvesAtEdgeTest.kt` — the **model** for the end-to-end test in Task 4 (install → log → `testScheduler.runCurrent()` → assert stamped). Reuse its harness shape verbatim.
- `kuilt-otel-logging/build.gradle.kts` — the source-set block edited in Task 1.

---

### Task 1: The execution-local trace slot (`expect`/`actual` + source-set intermediate)

The per-platform seam: a mutable, execution-local `ActiveTrace?` the coroutine element mirrors into and the edge reads. Split out first because it is the only platform-specific code and the highest-risk piece off the JVM.

**Files:**
- Create: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt` (the `expect`)
- Create: `kuilt-otel-logging/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt` (`ThreadLocal`)
- Create: `kuilt-otel-logging/src/wasmJsMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt` (plain `var`)
- Create: `kuilt-otel-logging/src/appleMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt` (`@ThreadLocal`)
- Modify: `kuilt-otel-logging/build.gradle.kts` (add the `jvmAndAndroidMain` intermediate)
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlotTest.kt`

**Interfaces:**
- Produces (internal): `internal expect fun currentActiveTrace(): ActiveTrace?` and `internal expect fun setActiveTrace(value: ActiveTrace?): ActiveTrace?` (returns the prior value, so the element can save/restore).

- [ ] **Step 1: Add the `jvmAndAndroidMain` intermediate to `build.gradle.kts`**

The module currently wires `jvmMain`/`androidMain`/`wasmJsMain` under one `nonAppleMain` (which provides `captureDelegate = previous`). JVM/Android need a `java.lang.ThreadLocal` actual that wasmJs must **not** see. Insert an intermediate between `nonAppleMain` and the JVM/Android leaves; leave `wasmJsMain` directly under `nonAppleMain`. In the `sourceSets { … }` block, replace:

```kotlin
        val nonAppleMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(nonAppleMain)
        androidMain.get().dependsOn(nonAppleMain)
        val wasmJsMain by getting { dependsOn(nonAppleMain) }
```
with:
```kotlin
        val nonAppleMain by creating { dependsOn(commonMain.get()) }
        // JVM + Android share a java.lang.ThreadLocal-backed trace slot; wasmJs must
        // not see java.* so it stays directly under nonAppleMain with a plain-var slot.
        val jvmAndAndroidMain by creating { dependsOn(nonAppleMain) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
        val wasmJsMain by getting { dependsOn(nonAppleMain) }
```
`appleMain` and the `*Test` intermediates are unchanged. (`captureDelegate` still resolves: JVM/Android inherit it transitively through `nonAppleMain`.)

- [ ] **Step 2: Write the failing test**

`ActiveTraceSlotTest.kt` (commonTest — runs on every target; `internal` seam is visible in-module):
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveTraceSlotTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @AfterTest fun clear() { setActiveTrace(null) }

    @Test
    fun slotDefaultsToNull() {
        assertNull(currentActiveTrace())
    }

    @Test
    fun setReturnsPriorAndUpdates() {
        val t1 = trace(1)
        assertNull(setActiveTrace(t1))          // prior was null
        assertEquals(t1, currentActiveTrace())
        val t2 = trace(2)
        assertEquals(t1, setActiveTrace(t2))     // prior was t1
        assertEquals(t2, currentActiveTrace())
        assertEquals(t2, setActiveTrace(null))   // prior was t2
        assertNull(currentActiveTrace())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-otel-logging:jvmTest --tests "*ActiveTraceSlotTest"`
Expected: FAIL — unresolved `currentActiveTrace` / `setActiveTrace`.

- [ ] **Step 4: Write the `expect` + three `actual`s**

`commonMain/.../ActiveTraceSlot.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

/**
 * The execution-local slot holding the trace active on the current thread of
 * execution. [ActiveTraceElement] mirrors [ActiveTrace] into it as a coroutine is
 * dispatched; [CoroutineContextTraceProvider.current] reads it synchronously at the
 * capture edge. Backed by a `ThreadLocal` on JVM/Android, a Kotlin/Native
 * `@ThreadLocal` on Apple, and a plain module-level var on single-threaded wasmJs.
 */
internal expect fun currentActiveTrace(): ActiveTrace?

/** Set the slot, returning the prior value (so callers can save/restore). */
internal expect fun setActiveTrace(value: ActiveTrace?): ActiveTrace?
```

`jvmAndAndroidMain/.../ActiveTraceSlot.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

private val slot = ThreadLocal<ActiveTrace?>()

internal actual fun currentActiveTrace(): ActiveTrace? = slot.get()

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot.get()
    if (value == null) slot.remove() else slot.set(value)
    return prior
}
```

`wasmJsMain/.../ActiveTraceSlot.kt` (single-threaded — a plain var is correct):
```kotlin
package us.tractat.kuilt.otel.logging

private var slot: ActiveTrace? = null

internal actual fun currentActiveTrace(): ActiveTrace? = slot

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot
    slot = value
    return prior
}
```

`appleMain/.../ActiveTraceSlot.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var slot: ActiveTrace? = null

internal actual fun currentActiveTrace(): ActiveTrace? = slot

internal actual fun setActiveTrace(value: ActiveTrace?): ActiveTrace? {
    val prior = slot
    slot = value
    return prior
}
```

- [ ] **Step 5: Run test to verify it passes on JVM, then off-JVM**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*ActiveTraceSlotTest"` → PASS.
Then prove the actuals compile+run off-JVM (the whole point):
`./gradlew :kuilt-otel-logging:wasmJsTest :kuilt-otel-logging:macosArm64Test :kuilt-otel-logging:iosSimulatorArm64Test`
Expected: PASS on all. A compile error here means the intermediate wiring is wrong — fix the source-set block, do not move the file.

- [ ] **Step 6: Commit**

```bash
git add kuilt-otel-logging/build.gradle.kts \
        kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt \
        kuilt-otel-logging/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt \
        kuilt-otel-logging/src/wasmJsMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt \
        kuilt-otel-logging/src/appleMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlot.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceSlotTest.kt
git commit -m "feat(otel-logging): per-platform execution-local trace slot (JVM/Native/wasm)"
```

---

### Task 2: `ActiveTraceElement` + `withActiveTrace`

The app-facing setter. A `ThreadContextElement` mirrors the trace into the slot on each dispatch and restores it on suspend, so structured concurrency propagates it to children and the synchronous edge always sees the right value.

**Files:**
- Create: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceElement.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/WithActiveTraceTest.kt`

**Interfaces:**
- Consumes: `currentActiveTrace`/`setActiveTrace` (Task 1); `ActiveTrace` (shipped).
- Produces: `class ActiveTraceElement(trace: ActiveTrace?) : ThreadContextElement<ActiveTrace?>` with `companion object Key`; `suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T`.

- [ ] **Step 1: Write the failing test**

`WithActiveTraceTest.kt` (commonTest):
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WithActiveTraceTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @Test
    fun elementSetsSlotInsideAndRestoresOutside() = runTest {
        assertNull(currentActiveTrace())
        val t = trace(1)
        withActiveTrace(t) {
            // The synchronous read the capture edge performs must see `t` here.
            assertEquals(t, currentActiveTrace())
        }
        assertNull(currentActiveTrace())
    }

    @Test
    fun nestedScopesShadowAndRestore() = runTest {
        val outer = trace(1)
        val inner = trace(2)
        withActiveTrace(outer) {
            assertEquals(outer, currentActiveTrace())
            withActiveTrace(inner) {
                assertEquals(inner, currentActiveTrace())
            }
            assertEquals(outer, currentActiveTrace())
        }
        assertNull(currentActiveTrace())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*WithActiveTraceTest"`
Expected: FAIL — unresolved `withActiveTrace` / `ActiveTraceElement`.

- [ ] **Step 3: Write minimal implementation**

`ActiveTraceElement.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Carries the trace active for a logical task across its child coroutines, and
 * mirrors it into the [execution-local slot][currentActiveTrace] so the synchronous
 * capture edge can read it (the edge is non-`suspend`, so it cannot read the
 * coroutine context directly — the slot is the bridge).
 *
 * On each dispatch onto a thread, [updateThreadContext] writes [trace] into the slot
 * and returns the prior value; [restoreThreadContext] puts the prior value back on
 * suspend, so nested scopes shadow and restore correctly.
 */
public class ActiveTraceElement(
    /** The trace to make active for the wrapped coroutine, or `null` to clear. */
    public val trace: ActiveTrace?,
) : ThreadContextElement<ActiveTrace?> {
    /** The context key for [ActiveTraceElement]. */
    public companion object Key : CoroutineContext.Key<ActiveTraceElement>

    override val key: CoroutineContext.Key<ActiveTraceElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): ActiveTrace? = setActiveTrace(trace)

    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveTrace?) {
        setActiveTrace(oldState)
    }
}

/**
 * Run [block] with [trace] as the active trace for every log line it (and its child
 * coroutines) emit. The whole point of native trace context: whoever starts a span
 * wraps the work, and kuilt's sampling gate then stamps or drops those lines by that
 * trace — on wasmJs, iOS and macOS, not only the JVM.
 *
 * @sample us.tractat.kuilt.otel.logging.sampleWithActiveTrace
 */
public suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend CoroutineScope.() -> T): T =
    withContext(ActiveTraceElement(trace), block)
```

- [ ] **Step 4: Run test to verify it passes (JVM + off-JVM)**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*WithActiveTraceTest"` → PASS.
Then: `./gradlew :kuilt-otel-logging:wasmJsTest :kuilt-otel-logging:macosArm64Test --tests "*WithActiveTraceTest"`.
> **Risk to watch:** the assertions run *inside* the `withActiveTrace` block, so `ThreadContextElement.updateThreadContext` must have applied before the block body runs. This holds on all `1.11.0` targets, but if an off-JVM run shows `currentActiveTrace()` still `null` inside the block, that is a real signal the element isn't applied on that dispatcher — **investigate**, do not skip the target. (`@sample` in Step 3 needs Task 5's sample function to compile the module test source; until then, run these named-test commands, or add the sample first.)

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/ActiveTraceElement.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/WithActiveTraceTest.kt
git commit -m "feat(otel-logging): ActiveTraceElement + withActiveTrace coroutine scoping"
```

---

### Task 3: `CoroutineContextTraceProvider`

The `TraceContextProvider` the gate consumes — reads the slot synchronously. Same interface as `OtelSdkTraceContextProvider`, so JVM apps may keep the OTel one while wasm/iOS/macOS use this.

**Files:**
- Create: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CoroutineContextTraceProvider.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CoroutineContextTraceProviderTest.kt`

**Interfaces:**
- Consumes: `currentActiveTrace` (Task 1); `withActiveTrace` (Task 2); `TraceContextProvider` (shipped).
- Produces: `class CoroutineContextTraceProvider : TraceContextProvider`.

- [ ] **Step 1: Write the failing test**

`CoroutineContextTraceProviderTest.kt` (commonTest):
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoroutineContextTraceProviderTest {
    private val provider = CoroutineContextTraceProvider()
    private fun trace() =
        ActiveTrace(ByteString(ByteArray(16) { 3 }), ByteString(ByteArray(8) { 4 }), sampled = true)

    @Test
    fun currentReflectsTheActiveScope() = runTest {
        assertNull(provider.current())
        val t = trace()
        withActiveTrace(t) {
            assertEquals(t, provider.current())
        }
        assertNull(provider.current())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*CoroutineContextTraceProviderTest"`
Expected: FAIL — unresolved `CoroutineContextTraceProvider`.

- [ ] **Step 3: Write minimal implementation**

`CoroutineContextTraceProvider.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

/**
 * A [TraceContextProvider] backed by the coroutine-context trace an app sets with
 * [withActiveTrace] — the dependency-light, `commonMain` source for platforms with
 * no ambient tracer (wasmJs, iOS, macOS).
 *
 * [current] reads the [execution-local slot][currentActiveTrace] the enclosing
 * [ActiveTraceElement] mirrored into it. It is safe to call from the synchronous
 * capture edge (`CapturingAppender.log`), which is where the gate resolves the trace
 * (#1034). Returns `null` outside any [withActiveTrace] scope.
 *
 * On the JVM an app may instead use `OtelSdkTraceContextProvider` (reads the OTel
 * SDK's `Span.current()`); both implement [TraceContextProvider], so the gate and
 * `ActiveTrace` shape are identical and the choice is per-install.
 */
public class CoroutineContextTraceProvider : TraceContextProvider {
    override fun current(): ActiveTrace? = currentActiveTrace()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*CoroutineContextTraceProviderTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CoroutineContextTraceProvider.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CoroutineContextTraceProviderTest.kt
git commit -m "feat(otel-logging): CoroutineContextTraceProvider reading the active-trace slot"
```

---

### Task 4: End-to-end — the sampled gate stamps/drops through `installLogCapture` off the JVM

The acceptance proof. Install with `CoroutineContextTraceProvider()`, log inside/outside `withActiveTrace`, and assert the durable record is stamped / dropped — on wasmJs + Apple, not just JVM. Models `GateResolvesAtEdgeTest`.

**Files:**
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/NativeTraceGateEndToEndTest.kt`

**Interfaces:** consumes the full stack (Tasks 1–3 + shipped `installLogCapture`/`CaptureConfig`/`WarpLogRecordExporter`). No new production code — if a case fails, the defect is in Tasks 1–3, fix it there.

- [ ] **Step 1: Write the test**

`NativeTraceGateEndToEndTest.kt` (commonTest — the whole point is that this runs on wasmJs/iOS/macOS):
```kotlin
package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class NativeTraceGateEndToEndTest {
    private val fixedClock = object : Clock { override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000) }
    private val sampled = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), sampled = true)
    private val unsampled = ActiveTrace(ByteString(ByteArray(16) { 5 }), ByteString(ByteArray(8) { 6 }), sampled = false)

    @Test
    fun sampledScopeStampsTheRecord() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            withActiveTrace(sampled) {
                KotlinLogging.logger("com.example.Native").info { "inside a sampled trace" }
            }
            testScheduler.runCurrent()
            val record = exporter.snapshot().toList().single()
            assertEquals(sampled.traceId, record.traceId)
            assertEquals(sampled.spanId, record.spanId)
        } finally {
            installation.close()
        }
    }

    @Test
    fun unsampledScopeDropsTheRecord() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            withActiveTrace(unsampled) {
                KotlinLogging.logger("com.example.Native").info { "inside an unsampled trace" }
            }
            testScheduler.runCurrent()
            assertTrue(exporter.snapshot().toList().isEmpty())
        } finally {
            installation.close()
        }
    }

    @Test
    fun untracedCapturesUnstampedUnderDefaultPolicy() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(), fixedClock, Random(0), backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            KotlinLogging.logger("com.example.Native").info { "no active trace" }
            testScheduler.runCurrent()
            val record = exporter.snapshot().toList().single()
            assertNull(record.traceId)
            assertNull(record.spanId)
        } finally {
            installation.close()
        }
    }

    @Test
    fun untracedDropsUnderDropPolicy() = runTest {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val installation = installLogCapture(
            exporter, CaptureConfig(untracedPolicy = UntracedPolicy.DROP), fixedClock, Random(0),
            backgroundScope, CoroutineContextTraceProvider(),
        )
        try {
            KotlinLogging.logger("com.example.Native").info { "no active trace, drop policy" }
            testScheduler.runCurrent()
            assertTrue(exporter.snapshot().toList().isEmpty())
        } finally {
            installation.close()
        }
    }
}
```

> These tests mutate the process-global `KotlinLoggingConfiguration` (via `installLogCapture`) and restore it in `finally` — same lifecycle as `GateResolvesAtEdgeTest`. Keep the `try/finally`.

- [ ] **Step 2: Run on JVM first**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*NativeTraceGateEndToEndTest"` → all PASS.

- [ ] **Step 3: Run off-JVM — the acceptance bar**

Run each, one target at a time (`timeout` fences a hang):
```bash
timeout 300 ./gradlew :kuilt-otel-logging:wasmJsTest --tests "*NativeTraceGateEndToEndTest"
timeout 300 ./gradlew :kuilt-otel-logging:iosSimulatorArm64Test --tests "*NativeTraceGateEndToEndTest"
timeout 300 ./gradlew :kuilt-otel-logging:macosArm64Test --tests "*NativeTraceGateEndToEndTest"
```
Expected: PASS on all three. **A null stamp on `sampledScopeStampsTheRecord` off-JVM is the failure this whole issue exists to prevent** — it means the element isn't visible at the edge on that target. Treat it as a real defect (root-cause in Task 2's element/slot), not a flake; do not widen timeouts or skip.

- [ ] **Step 4: Commit**

```bash
git add kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/NativeTraceGateEndToEndTest.kt
git commit -m "test(otel-logging): end-to-end native sampled-gate on wasmJs/iOS/macOS"
```

---

### Task 5: `MutableTraceContextHolder` escape hatch + `@sample` + KDoc cross-links

A minimal, documented escape hatch for apps/tests that cannot express tracing as coroutine scopes, plus the doc surface (`explicitApi` + the repo's `@sample`-compiled-in-`commonTest` convention).

**Files:**
- Create: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/MutableTraceContextHolder.kt`
- Modify: `kuilt-otel-logging/src/commonSamples/kotlin/us/tractat/kuilt/otel/logging/Samples.kt` (add `sampleWithActiveTrace`)
- Modify: `kuilt-otel-logging/module.md` (mention native trace context)
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/MutableTraceContextHolderTest.kt`

**Interfaces:**
- Produces: `class MutableTraceContextHolder(initial: ActiveTrace? = null) : TraceContextProvider { fun set(trace: ActiveTrace?) }`.

- [ ] **Step 1: Write the failing test**

`MutableTraceContextHolderTest.kt` (commonTest):
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MutableTraceContextHolderTest {
    private fun trace(tag: Byte) =
        ActiveTrace(ByteString(ByteArray(16) { tag }), ByteString(ByteArray(8) { tag }), sampled = true)

    @Test
    fun currentReflectsLastSet() {
        val holder = MutableTraceContextHolder()
        assertNull(holder.current())
        val t = trace(1)
        holder.set(t)
        assertEquals(t, holder.current())
        holder.set(null)
        assertNull(holder.current())
    }

    @Test
    fun honoursInitialValue() {
        val t = trace(2)
        assertEquals(t, MutableTraceContextHolder(t).current())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*MutableTraceContextHolderTest"`
Expected: FAIL — unresolved `MutableTraceContextHolder`.

- [ ] **Step 3: Write minimal implementation**

`MutableTraceContextHolder.kt`:
```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.atomicfu.atomic

/**
 * A directly-settable [TraceContextProvider] — the escape hatch for apps or tests
 * that cannot scope their tracing as coroutines (prefer [withActiveTrace] +
 * [CoroutineContextTraceProvider], which is the primary API).
 *
 * The current trace is a single process-global value, guarded by an atomic ref. It
 * has **no execution locality**: on a multi-threaded runtime a concurrent logger on
 * another thread sees whatever was [set] last, so use this only where a single
 * logical trace is active at a time (e.g. a single-threaded wasmJs app, or a test).
 */
public class MutableTraceContextHolder(initial: ActiveTrace? = null) : TraceContextProvider {
    private val ref = atomic(initial)

    /** Set the current trace (or `null` to clear it). */
    public fun set(trace: ActiveTrace?) { ref.value = trace }

    override fun current(): ActiveTrace? = ref.value
}
```

- [ ] **Step 4: Add the `@sample` function**

Append to `commonSamples/.../Samples.kt`:
```kotlin
/** @suppress — sample only */
internal suspend fun sampleWithActiveTrace(installation: LogCaptureInstallation) {
    // installLogCapture(...) was called with CoroutineContextTraceProvider(); on
    // wasmJs / iOS / macOS that is how the sampling gate learns the current trace.
    val log = KotlinLogging.logger("com.example.Checkout")

    // Whoever starts a span wraps the work. Every line logged inside — here and in
    // any child coroutine — is stamped with this trace when the sampler kept it, or
    // dropped when it didn't. No call-site change to the logging itself.
    val trace = ActiveTrace(
        traceId = ByteString(ByteArray(16) { 1 }),
        spanId = ByteString(ByteArray(8) { 2 }),
        sampled = true,
    )
    withActiveTrace(trace) {
        log.info { "charged the card" } // stamped with trace/span id
    }

    // Outside any withActiveTrace scope the line is untraced — captured unstamped
    // (default) or dropped, per CaptureConfig.untracedPolicy.
    log.info { "background heartbeat" }
}
```
Add the imports it needs at the top of `Samples.kt` (`kotlinx.io.bytestring.ByteString`). (The existing file already imports `KotlinLogging`.)

- [ ] **Step 5: Update `module.md`**

Add a short paragraph after the existing overview (accessible-first, per the repo doc rule):
```markdown
When your app runs distributed tracing, wrap work in `withActiveTrace(trace) { … }`
and kuilt keeps and stamps the log lines that belong to a kept trace — on an iPhone
and in the browser too, not only on a server. On the JVM you can instead let the
OpenTelemetry SDK be the source (`kuilt-otel-sdk`); both feed the same gate.
```

- [ ] **Step 6: Run tests + verify samples compile**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*MutableTraceContextHolderTest"` → PASS.
Run: `./gradlew :kuilt-otel-logging:compileTestKotlinJvm` → the `@sample` compiles (commonSamples is wired into `commonTest`). If `sampleWithActiveTrace` fails to resolve a symbol, fix the sample — it is load-bearing.

- [ ] **Step 7: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/MutableTraceContextHolder.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/MutableTraceContextHolderTest.kt \
        kuilt-otel-logging/src/commonSamples/kotlin/us/tractat/kuilt/otel/logging/Samples.kt \
        kuilt-otel-logging/module.md
git commit -m "feat(otel-logging): MutableTraceContextHolder escape hatch + withActiveTrace sample/docs"
```

---

### Task 6: Full-build verification (all targets) + PR

**Files:** none (verification + integration).

- [ ] **Step 1: Detekt + full module build, cache-disabled**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel-logging:build detektAll --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tasks EXECUTED (not `FROM-CACHE`). If any test-compile shows `FROM-CACHE`, re-run with `--no-build-cache`. `detektAll` clean.

- [ ] **Step 2: Off-JVM test targets (the acceptance bar)**

```bash
timeout 600 ./gradlew :kuilt-otel-logging:wasmJsTest :kuilt-otel-logging:macosArm64Test :kuilt-otel-logging:iosSimulatorArm64Test
```
Expected: PASS on all three — the sampled gate resolves an app-set `ActiveTrace` and stamps/drops off the JVM. This is Done-when.

- [ ] **Step 3: Whole-repo build (what CI runs)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (the new `commonMain` code compiles on every target; the three slot actuals cover JVM/Android, Apple, wasmJs; no other module is touched).

- [ ] **Step 4: Open the PR**

```bash
git push -u origin <impl-branch>
gh pr create --title "feat(otel): native TraceContextProvider — sampled-gate on wasmJs/iOS/macOS" \
  --body "$(cat <<'EOF'
> 🤖 This comment was generated by Claude on behalf of @keddie.

Closes #1029. Part of #986.

Adds a `commonMain`, OTel-free `CoroutineContextTraceProvider` so the log-capture sampling gate (#990, resolving at the log edge since #1034) works on **wasmJs, iOS and macOS** — not only the JVM.

- **`withActiveTrace(trace) { … }`** sets an `ActiveTrace` on the `CoroutineContext`; `ActiveTraceElement : ThreadContextElement` mirrors it into an execution-local slot (ThreadLocal on JVM/Android, `@ThreadLocal` on Apple, plain var on single-threaded wasmJs) so the non-`suspend` capture edge can read it.
- **`CoroutineContextTraceProvider`** reads that slot — same `TraceContextProvider` interface as `OtelSdkTraceContextProvider`, so JVM apps may keep the OTel source; wasm/iOS/macOS use this. `MutableTraceContextHolder` is a minimal escape hatch.
- End-to-end tests prove stamp/drop on **wasmJs + iOS + macOS** through `installLogCapture`.

Design: `docs/superpowers/specs/2026-07-01-native-trace-context-provider-design.md`. Plan: `docs/superpowers/plans/2026-07-01-native-trace-context-provider.md`.
EOF
)"
gh pr merge --auto --squash
```

- [ ] **Step 5: Open the PR in the browser**

Run: `gh pr view --web`

---

## Self-Review

**Spec coverage:**
- `commonMain` provider resolving an app-set `ActiveTrace`, no OTel dep → `CoroutineContextTraceProvider` (Task 3) + slot (Task 1). ✓
- The API to **set** the active trace on native → `withActiveTrace` / `ActiveTraceElement` (Task 2), `MutableTraceContextHolder` hatch (Task 5). ✓
- Interoperates with JVM `OtelSdkTraceContextProvider` (same interface, per-install choice) → documented in Task 3 KDoc + PR body. ✓
- Gate stamps/drops on **wasmJs + iOS + macOS** with tests on those targets → Task 4 + Task 6 Step 2. ✓
- No gate change (resolution-site fix already landed #1034) → confirmed; this plan adds only the provider + setter. ✓

**Locked-decision fidelity:** coroutine element primary (Tasks 2–3), holder demoted to minimal hatch (Task 5), `current()` non-`suspend` (Task 3 implements the shipped interface unchanged), plugs into the merged `resolveTrace()` edge (Task 4 relies on #1034), all in `:kuilt-otel-logging` `commonMain` with no OTel dep.

**Placeholder scan:** none — every code block is concrete and grounded in the read sources (`GateResolvesAtEdgeTest` harness, `installLogCapture` signature, `ThreadContextElement` API, the module's real source-set block).

**Risk register:** (1) `ThreadContextElement` visibility at the synchronous edge on wasmJs/Native — the reason Task 2/4 run each off-JVM target explicitly and treat a null stamp as a real defect. (2) Source-set intermediate wiring — a slot compile error means the `jvmAndAndroidMain` insert is wrong; fix wiring, don't relocate files. (3) `@ThreadLocal` import is `kotlin.native.concurrent.ThreadLocal`, appleMain only.

**Cross-plan dependency:** depends on **merged #1034/#1041** (edge resolution + `NormalizedLogEvent.activeTrace` + `LogCapture.resolveTrace`). No dependency on the in-flight `:kuilt-otel-sdk` M2 plan; the two providers are independent implementations of the same shipped `TraceContextProvider` interface. Rebase the implementation branch onto `origin/main` at start to guarantee #1034 is present.

**Type consistency:** `ActiveTrace(traceId, spanId, sampled)` and `TraceContextProvider.current()` are the shipped types (unchanged); `ActiveTraceElement(trace)`/`ActiveTraceElement.Key`, `withActiveTrace(trace, block)`, `CoroutineContextTraceProvider()`, `MutableTraceContextHolder(initial)` + `.set(...)`, and the internal `currentActiveTrace()`/`setActiveTrace(value): prior` seam are consistent across all tasks.
</content>
