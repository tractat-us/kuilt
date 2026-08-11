# `WarpTelemetry.clear()` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a consumer a supported way to empty a `WarpTelemetry`'s persisted state from inside a running app — no restart, no per-platform directory delete.

**Architecture:** A clear is a **write turn**, not a new subsystem. Each signal forgets through its own CRDT's existing mechanism rather than resetting to `empty()`: logs via `Rga.dropWindow` over every id (which preserves the identity high-water *and* stops a peer resurrecting the records), spans via `ORSet` removal (which retains the causal context), metrics by resetting the maps and deleting the keys (monotonic joins have no merge-safe forget, so that one is local-only). The log exporter's `writeMutex` already fences a turn against in-flight exports, and its retirement ledger already deletes segment keys crash-safely, so most of this is reuse.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-serialization (CBOR), atomicfu locks, Gradle. Modules: `:kuilt-otel` (all changes), `:kuilt-crdt` (read-only — do not modify).

**Spec:** `docs/superpowers/specs/2026-08-10-otel-store-clear-design.md`. **Issue:** [#2208](https://github.com/tractat-us/kuilt/issues/2208).

## Global Constraints

- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public` modifier or the build fails.
- **Never throw from an exporter's public methods.** Wrap in `runCatchingCancellable` (from `:kuilt-core`) — **never bare `runCatching`**, which swallows `CancellationException`.
- **No production dispatchers in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`). A deliberate real-threading test carries an inline `@Suppress("ForbiddenImport")` with a one-line reason — `WarpLogRecordExporterConcurrencyTest` is the in-tree precedent.
- **Test methods take no `test` prefix.** Multi-assert tests use `assertAll()` from `us.tractat.kuilt.test`.
- **Acquisition order in `WarpLogRecordExporter` is `writeMutex` then `lock`, never the reverse.**
- **Do not modify `:kuilt-crdt`.** Every mechanism this plan needs is already public there. If you believe you need a CRDT change, stop and escalate.
- **Verification is `./gradlew :kuilt-otel:build --rerun-tasks`**, not `jvmTest`. `jvmTest` does not compile the Android or Kotlin/Native variants, and the build cache serves stale greens. Add `--no-build-cache` if any test-compile task still reports `FROM-CACHE`.
- **Lint is `./gradlew detektAll`**, never bare `detekt` (which is `NO-SOURCE` here and reports a false green).
- Source JDK first in a non-interactive shell: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `kuilt-otel/src/commonMain/.../WarpLogRecordExporter.kt` | Modify: parameterise window retention; add `clear()` + `clearTurn()` | 1 |
| `kuilt-otel/src/commonTest/.../WarpLogRecordExporterClearTest.kt` | Create: every log-clear property | 1, 2, 3, 4 |
| `kuilt-otel/src/commonMain/.../WarpSpanExporter.kt` | Modify: add `clear()` | 5 |
| `kuilt-otel/src/commonTest/.../WarpSpanExporterClearTest.kt` | Create | 5 |
| `kuilt-otel/src/commonMain/.../WarpMetricExporter.kt` | Modify: add `clear()` | 6 |
| `kuilt-otel/src/commonTest/.../WarpMetricExporterClearTest.kt` | Create | 6 |
| `kuilt-otel/src/commonMain/.../WarpCausalClock.kt` | Modify: add `internal fun clearFrontier()` | 5 |
| `kuilt-otel/src/commonMain/.../WarpTelemetry.kt` | Modify: add `clear()` fan-out | 7 |
| `kuilt-otel/src/commonTest/.../WarpTelemetryClearTest.kt` | Create | 7 |
| `kuilt-otel/src/commonSamples/.../Samples.kt` | Modify: add `sampleWarpTelemetryClear` | 8 |
| `kuilt-otel/module.md`, `Writerside/topics/otel-logs.md`, `docs/agent-cookbook.md` | Modify: docs | 8 |

**PR boundary:** Tasks 1–4 are PR 1 (logs). Tasks 5–8 are PR 2 (spans, metrics, facade, docs).

---

### Task 1: `WarpLogRecordExporter.clear()`

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt` (create)

**Interfaces:**
- Consumes: existing private `windowPass()`, `idsOutsideWindow()`, `pendingWrites(retire: Boolean)`, `commit(actions, logFailure)`, `failure(cause)`, `storeSucceeded()`, `writeMutex`, `lock`.
- Produces: `public suspend fun WarpLogRecordExporter.clear(): ExportResult`. Tasks 2–4 and 7 depend on this exact signature.

**Background you need:** `windowPass()` already drops everything outside the retained window of `maxRecords`, `piece`s the resulting compaction delta into `activeSegment`, and rebuilds the derived state. A clear is that same pass retaining **zero** records. `idsOutsideWindow`'s loop already yields every id when the retained count is zero — it breaks on its first iteration with `cut` still at `sequence.size` — so no loop-body change is needed, only a parameter.

- [ ] **Step 1: Write the failing test**

Create `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt`:

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [WarpLogRecordExporter.clear] (#2208): a supported reset a live exporter keeps
 * exporting into, with the persisted segments actually deleted.
 *
 * The property that matters is durable, not in-memory — "the buffer looks empty" was
 * always achievable and is not what the consumer could not get.
 */
class WarpLogRecordExporterClearTest {

    private val replicaA = ReplicaId("A")

    private fun recordId(id: Int): ByteString =
        ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() })

    private fun record(id: Int) = LogRecord(
        recordId = recordId(id),
        body = "log message body number ${id.toString().padStart(6, '0')}",
        severityNumber = 9,
        severityText = "INFO",
        observedEpochNanos = 1_700_000_000_000_000_000L,
    )

    private fun exporterFor(
        store: DurableStore,
        maxRecords: Int = DEFAULT_MAX_LOG_RECORDS,
        segmentOps: Int = DEFAULT_LOG_SEGMENT_OPS,
    ) = WarpLogRecordExporter(
        replica = replicaA,
        store = store,
        maxRecords = maxRecords,
        bufferPolicy = BufferPolicy.DROP_OLDEST,
        segmentOps = segmentOps,
    )

    @Test
    fun clearEmptiesTheBufferAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }

        assertEquals(ExportResult.Success, exporter.clear())

        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), exporter.snapshot().toList(), "the live buffer is empty") },
            { assertEquals(emptyList(), recovered.snapshot().toList(), "a fresh exporter recovers empty") },
        )
    }

    @Test
    fun clearingAnAlreadyEmptyExporterSucceedsAndLeavesARecoverableStore() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)

        assertEquals(ExportResult.Success, exporter.clear())

        // Asserts a recoverable empty store, NOT "wrote nothing". A clear writes its index and
        // active segment unconditionally — see clearTurn — and a test named for the absent
        // write would pin the optimisation that breaks the retry.
        val recovered = exporterFor(store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), exporter.snapshot().toList()) },
            { assertEquals(emptyList(), recovered.snapshot().toList()) },
        )
    }

    @Test
    fun theSameInstanceKeepsExportingAfterAClearAndARestartSeesOnlyTheNewRecords() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }
        exporter.clear()

        repeat(5) { i -> exporter.export(record(100 + i)) }

        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()
        assertEquals(
            (100 until 105).map { recordId(it) },
            recovered.snapshot().toList().map { it.recordId },
            "re-initialisation: only what was exported after the clear survives a restart",
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest*"
```

Expected: **compilation failure** — `clear()` is not defined on `WarpLogRecordExporter`.

- [ ] **Step 3: Parameterise the window retention**

In `WarpLogRecordExporter.kt`, change `idsOutsideWindow()` to take the retained count. Only the signature and the comparison change:

```kotlin
    private fun idsOutsideWindow(retain: Int): Set<RgaId>? {
        val sequence = log.sequence
        val tombstones = log.tombstones
        var visibleSeen = 0
        var cut = sequence.size
        for (i in sequence.indices.reversed()) {
            if (visibleSeen == retain) break
            if (sequence[i] !in tombstones) visibleSeen++
            cut = i
        }
        if (cut == 0) return null
        return sequence.subList(0, cut).toSet()
    }
```

Update its KDoc's opening line to read `Every id in [log] that falls outside a retained window of [retain] visible records`, and append this paragraph, which is the part a later reader needs:

```
     * At `retain = 0` the loop breaks on its first iteration with `cut` still at
     * `sequence.size`, so every id comes back — which is what makes [clear] the same pass
     * as an ordinary window pass rather than a second code path. An empty log still
     * returns `null` (`cut == 0`), so clearing one owes no write.
```

Then change `windowPass()`'s signature, leaving its body untouched:

```kotlin
    private fun windowPass(retain: Int = maxRecords): Boolean {
        evictionsSincePass = 0
        val dropped = idsOutsideWindow(retain) ?: return false
```

- [ ] **Step 4: Add `clear()` and `clearTurn()`**

Place `clear()` immediately after `merge()` in the file, so the three write turns sit together:

```kotlin
    /**
     * Drop every record this exporter holds and delete the segments that held them —
     * a supported reset the same live instance keeps exporting into (#2208).
     *
     * The forgetting goes through [Rga.dropWindow], not [Rga.empty], and the difference is
     * the whole design. A reset to empty re-mints `RgaId`s this replica has already used —
     * `maxSeqByReplica` and the Lamport clock both restart — so a later [merge] with a peer
     * holding the pre-clear ops resolves two different records onto one id by map-put order.
     * A window pass instead raises the compaction **floor**, which carries the seq high-water
     * forward and *suppresses* the dropped dots, so a peer holding the raw `Insert`s cannot
     * push the records back either: [Rga.piece] merges the floor and re-purges beneath it.
     *
     * **The store settles to two small keys, not zero.** The index and one active segment
     * remain, the latter carrying the floor. That floor is what buys the paragraph above;
     * deleting it would leave a literally empty store and an open resurrection hole. The
     * sealed segments — which is where the bytes are — are deleted through the ordinary
     * retirement ledger, so a refused delete is retried at the next start rather than leaked.
     *
     * **Reclamation is total only on the export path.** A segment carrying an
     * [RgaOp.Compact] is never retired and a [merge]-adopted segment is pinned entire, so a
     * gossip-fed replica keeps residue after a clear. A replica fed only by [export] mints
     * only its own dots, which fold into the floor, so every sealed segment becomes retirable.
     *
     * Not a CRDT delete: this replica forgets, and so does any peer that merges from it
     * afterwards, but the clear does not travel to a peer that never does.
     *
     * **Never throws**, on the same terms as [export]. A failed durable write returns
     * [ExportResult.Failure] and moves [ExporterHealth.failed] — the store really did reject
     * a write — while a successful clear never moves [ExporterHealth.accepted], which keeps
     * meaning "records durably taken". Retrying a failed clear re-converges: raising the floor
     * is idempotent and a repeat delete is a no-op.
     *
     * **On failure, [snapshot] already reads empty while the store still holds the records.**
     * A turn builds its actions from already-mutated state — that is how all three write paths
     * here work — so the drop is not undone. A caller that uses the record count as a baseline
     * must treat any non-[ExportResult.Success] as *count unknown* rather than as zero. On
     * success the count reads zero synchronously, because the drop precedes the write.
     */
    public suspend fun clear(): ExportResult = writeMutex.withLock { clearTurn() }

    /** [clear]'s write turn: build the actions, then apply them. Must hold [writeMutex]. */
    private suspend fun clearTurn(): ExportResult {
        val actions = runCatchingCancellable {
            lock.withLock {
                windowPass(retain = 0)
                // Unconditional, and NOT gated on whether the pass moved anything. Gating is
                // the obvious optimisation and it silently breaks the retry: a clear whose
                // commit failed has already dropped everything in memory, so the retry's pass
                // finds nothing left to drop, returns false, and a gated turn would write
                // NOTHING and report Success while the store still holds every sealed segment.
                //
                // Unconditional costs one index write and one small active-segment write when
                // clearing an already-empty exporter. That is the whole price, and it buys
                // convergence by construction rather than by a flag tracking whether an
                // earlier pass's covering write ever landed.
                //
                // retire = true is sound for the same reason it is on the other two paths: the
                // pass has put the current suppression state into `activeSegment`, and
                // pendingWrites queues that write ahead of the retirement it gates. On a retry
                // the pass is a no-op but `activeSegment` still carries the floor from the
                // first attempt, so the write is still covering and the sealed segments are
                // still retirable against `log.compactedBelow`.
                pendingWrites(retire = true)
            }
        }.getOrElse { cause ->
            logger.error(cause) { "WarpLogRecordExporter: buffer update failed during clear" }
            return failure(cause)
        }
        val result = commit(actions) { cause ->
            logger.error(cause) { "WarpLogRecordExporter: durable write failed during clear" }
        }
        return if (result is ExportResult.Success) storeSucceeded() else result
    }
```

Then widen `storeSucceeded()`'s KDoc first line, since `merge` is no longer its only caller:

```
     * Record that the store accepted a write that carried **no admitted records** — the
     * [merge] and [clear] paths.
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest*"
```

Expected: PASS, 3 tests.

Then run each new test **alone** — a green suite does not prove each test passes in isolation:

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.clearEmptiesTheBufferAndTheStoreAFreshExporterRecoversFrom"
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.clearingAnAlreadyEmptyExporterSucceedsAndWritesNothing"
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.theSameInstanceKeepsExportingAfterAClearAndARestartSeesOnlyTheNewRecords"
```

- [ ] **Step 6: Confirm the existing suite still passes**

The retention parameter touched a path every export uses.

```bash
./gradlew :kuilt-otel:jvmTest
```

Expected: PASS. If `WarpLogRecordExporterWindowingTest` or `WarpLogRecordExporterSegmentTest` reddens, the default argument on `windowPass` was dropped or `idsOutsideWindow`'s comparison was changed to something other than `retain`.

- [ ] **Step 7: Revert the implementation and confirm the tests redden**

```bash
git stash push -- kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest*"   # expect: compile failure
git stash pop
```

- [ ] **Step 8: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt
git commit -m "feat(otel): clear() empties a log exporter's buffer and its store (part of #2208)"
```

---

### Task 2: Prove the sealed segment keys are actually deleted

**Files:**
- Modify: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt`

**Interfaces:**
- Consumes: `WarpLogRecordExporter.clear()` from Task 1.
- Produces: nothing new. This is the reclamation half of the motivation, and Task 1's tests do not cover it — a clear that emptied memory and the index while leaving every segment file on disk would pass all three.

**Why a local store:** `InMemoryDurableStore` has no key-enumeration API, and it should stay that way — #2208 records the absence as a consumer-facing gap this design deliberately routes around rather than closes. The test brings its own recording store.

- [ ] **Step 1: Write the failing test**

Append to `WarpLogRecordExporterClearTest`:

```kotlin
    /**
     * Records which keys currently exist, which `InMemoryDurableStore` cannot report.
     * Deliberately test-local: the absence of key enumeration on `DurableStore` is a
     * documented consumer-facing constraint (#2208), not an oversight to fix here.
     */
    private class RecordingDurableStore : DurableStore {
        private val backing = InMemoryDurableStore()
        val live: MutableSet<String> = mutableSetOf()

        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            live += key.name
            backing.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) {
            live -= key.name
            backing.delete(key)
        }
    }

    @Test
    fun clearDeletesTheSealedSegmentKeysAndLeavesOnlyTheIndexAndOneActiveSegment() = runTest {
        val store = RecordingDurableStore()
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(20) { i -> exporter.export(record(i)) }

        val segmentsBefore = store.live.filter { it.startsWith("otel.logs.seg.") }
        assertTrue(
            segmentsBefore.size >= 5,
            "the fixture must actually roll segments or this test proves nothing; got $segmentsBefore",
        )

        assertEquals(ExportResult.Success, exporter.clear())

        val segmentsAfter = store.live.filter { it.startsWith("otel.logs.seg.") }
        assertAll(
            { assertEquals(1, segmentsAfter.size, "exactly one active segment survives; got $segmentsAfter") },
            { assertTrue("otel.logs.idx" in store.live, "the index survives") },
        )
    }
```

Add `import kotlin.test.assertTrue` to the file's imports.

- [ ] **Step 2: Run it**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.clearDeletesTheSealedSegmentKeysAndLeavesOnlyTheIndexAndOneActiveSegment"
```

Expected: **PASS**, because Task 1's `pendingWrites(retire = true)` already drives retirement.

**If it fails**, do not weaken the assertion. Two failures are diagnostic and each has a different fix:
- *More than one segment survives* — a sealed segment was not retirable. Log `retirableSegments()` and check whether a `SegmentContent.Pinned` entry is present; a `Compact` in a sealed segment is legitimately un-retirable and means the fixture accidentally exercised the merge path.
- *Zero segments survive* — the active segment was retired, which would take the floor with it. That is a real bug in Task 1, not a test problem.

- [ ] **Step 3: Commit**

```bash
git add kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt
git commit -m "test(otel): pin that clear() reclaims the sealed segment keys (part of #2208)"
```

---

### Task 3: Prove a peer cannot resurrect cleared records, and no id is re-minted

**Files:**
- Modify: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt`

**Interfaces:**
- Consumes: `clear()`, `snapshot(): Rga<LogRecord>`, `merge(remote: Rga<LogRecord>): ExportResult`.
- Produces: nothing new.

**What these two tests uniquely pin.** Tasks 1 and 2 already catch a naive `log = Rga.empty()` through the durable path — an empty floor makes `retirableSegments()` retire nothing, so every sealed segment survives and the fresh exporter recovers all the records. What those tests *cannot* express is the in-memory pair: that a peer's re-merge does not resurrect, and that no `RgaId` is re-minted. That is this task, and it is the only place either property is asserted.

- [ ] **Step 1: Write the failing tests**

Append to `WarpLogRecordExporterClearTest`:

```kotlin
    @Test
    fun aPeerHoldingThePreClearOpsCannotPushThemBackThroughMerge() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)
        exporter.export(record(1))
        exporter.export(record(2))
        // A peer that gossiped with us before the clear still holds the raw Inserts.
        val peerCopy = exporter.snapshot()

        assertEquals(ExportResult.Success, exporter.clear())
        assertEquals(ExportResult.Success, exporter.merge(peerCopy))

        assertEquals(
            emptyList(),
            exporter.snapshot().toList(),
            "the floor must suppress the cleared dots, so a merge re-purges rather than resurrects",
        )
    }

    @Test
    fun aRecordExportedAfterAClearDoesNotReuseTheIdOfOneExportedBefore() = runTest {
        val store = InMemoryDurableStore()
        val exporter = exporterFor(store = store)
        exporter.export(record(1))
        val idBefore = exporter.snapshot().entries().single().first

        exporter.clear()
        exporter.export(record(2))
        val idAfter = exporter.snapshot().entries().single().first

        // Rga.empty() would re-mint (lamport = 1, A, seq = 1) for both, and a later merge
        // would then resolve two different records onto one id by map-put order.
        assertNotEquals(
            idBefore,
            idAfter,
            "a cleared exporter must not re-mint an RgaId it has already used",
        )
    }
```

Add `import kotlin.test.assertNotEquals` to the file's imports.

- [ ] **Step 2: Run them**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.aPeerHoldingThePreClearOpsCannotPushThemBackThroughMerge"
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.aRecordExportedAfterAClearDoesNotReuseTheIdOfOneExportedBefore"
```

Expected: PASS.

- [ ] **Step 3: Prove the tests are not vacuous**

A green test proves nothing until you have seen it red for the right reason. Temporarily replace the body of `clearTurn`'s locked section with the naive implementation:

```kotlin
            lock.withLock {
                log = Rga.empty()
                rebuildDerivedState()
                pendingWrites(retire = true)
            }
```

Run both tests. Expected: **both FAIL** — the merge resurrects both records, and the two ids are equal. Then restore Task 1's implementation with `git checkout`-free editing (re-apply the `windowPass(retain = 0)` body by hand; **do not** `git checkout -- <file>`, which restores from the index and would discard Task 1 if it is not yet committed).

Record what you saw in the commit message. If either test stayed green under the naive body, the test is wrong — fix the test, not the implementation.

- [ ] **Step 4: Commit**

```bash
git add kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt
git commit -m "test(otel): pin that a clear suppresses rather than forgets (part of #2208)

Both tests were confirmed red against a naive log = Rga.empty() clear: the
merge resurrected both records and the two RgaIds were equal."
```

---

### Task 4: Failure and concurrency behaviour for the log clear

**Files:**
- Modify: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt`
- Create: `kuilt-otel/src/jvmTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearConcurrencyTest.kt`

**Interfaces:**
- Consumes: `clear()`, `health: StateFlow<ExporterHealth>`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing failure test**

Append to `WarpLogRecordExporterClearTest`:

```kotlin
    private class WriteRefusingStore(private val backing: DurableStore) : DurableStore {
        var refuse: Boolean = false
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (refuse) throw IllegalStateException("store refused $key")
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)
    }

    @Test
    fun aRefusedClearReportsFailureAndARetryConverges() = runTest {
        val store = WriteRefusingStore(InMemoryDurableStore())
        val exporter = exporterFor(store = store, segmentOps = 2)
        repeat(10) { i -> exporter.export(record(i)) }

        // Captured rather than hard-coded: `accepted` counts the ten successful exports above,
        // and what this test asserts is that a clear does not MOVE it, not what it equals.
        val acceptedBefore = exporter.health.value.accepted
        val failedBefore = exporter.health.value.failed

        store.refuse = true
        val refused = exporter.clear()
        val failedAfterRefusal = exporter.health.value.failed

        // A failed clear leaves the buffer empty while the store still holds the records —
        // the documented divergence. A caller must read this as "count unknown", not zero.
        val snapshotAfterRefusal = exporter.snapshot().toList()

        store.refuse = false
        val retried = exporter.clear()

        // THE assertion of this test. A retry that returns Success having written nothing
        // passes every other line here — the live buffer was already empty from the failed
        // attempt — while the store still holds every sealed segment and a restart brings
        // all ten records back. Only recovering a fresh exporter can tell the two apart.
        val recovered = exporterFor(store = store, segmentOps = 2)
        recovered.recover()

        assertAll(
            { assertTrue(refused is ExportResult.Failure, "a refused durable write fails the clear") },
            { assertEquals(failedBefore + 1, failedAfterRefusal, "the store rejected a write, so `failed` moves") },
            { assertEquals(emptyList(), snapshotAfterRefusal, "the in-memory drop is not undone on failure") },
            { assertEquals(ExportResult.Success, retried, "a retry converges") },
            { assertEquals(emptyList(), recovered.snapshot().toList(), "the retry actually reached the store") },
            { assertEquals(acceptedBefore, exporter.health.value.accepted, "no clear moves `accepted`") },
            { assertEquals(emptyList(), exporter.snapshot().toList()) },
        )
    }
```

**This test is the one that catches the gating bug.** Before accepting it green, prove it red: temporarily change `clearTurn`'s locked section to `if (windowPass(retain = 0)) pendingWrites(retire = true) else emptyList()` — the natural-looking version — and confirm the `recovered.snapshot()` assertion fails with all ten records present. Then restore. If it stays green, the fixture never rolled a sealed segment; raise the record count.

- [ ] **Step 2: Run it**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearTest.aRefusedClearReportsFailureAndARetryConverges"
```

Expected: PASS, after the `accepted` value is reconciled per the note above.

- [ ] **Step 3: Write the concurrency test**

Create `kuilt-otel/src/jvmTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearConcurrencyTest.kt`:

```kotlin
@file:Suppress("ForbiddenImport") // deliberate real-threading test: clear() is fenced against a concurrent export by writeMutex, and an unfenced write landing after the clear is only observable on a genuine multi-threaded dispatcher, which virtual-time runTest cannot provide.

package us.tractat.kuilt.otel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two arms, because neither is sufficient alone.
 *
 * The **deterministic** arm asserts a clear actually empties the store. The **concurrent** arm
 * asserts the store and the live buffer still *agree* when a clear races in-flight exports —
 * which is the race property, and is deliberately not "the store is empty": an export
 * serialized after the clear legitimately survives, and which ones those are is not
 * predictable. `writeMutex` is what makes agreement hold — a turn builds its actions and
 * applies them inside one critical section, so a clear cannot interleave between an export's
 * encode and its write and have the stale bytes land afterwards.
 *
 * The agreement assertion alone is satisfied by a `clear()` that drops nothing (live and
 * recovered would agree on all of them), which is why the first arm exists.
 */
class WarpLogRecordExporterClearConcurrencyTest {

    private fun record(i: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { b -> (i shr (8 * b)).toByte() }),
        body = "body-$i",
        observedEpochNanos = 1_000L + i,
    )

    private class VariableLatencyStore(seed: Int) : DurableStore {
        private val backing = InMemoryDurableStore()
        private val rng = kotlin.random.Random(seed)
        override suspend fun read(key: StoreKey): ByteArray? = backing.read(key)
        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            repeat(rng.nextInt(MAX_YIELDS)) { yield() }
            backing.write(key, bytes)
        }
        override suspend fun delete(key: StoreKey) = backing.delete(key)
        private companion object { private const val MAX_YIELDS = 6 }
    }

    @Test
    fun aClearAfterEveryExportHasCompletedLeavesTheStoreEmpty() = kotlinx.coroutines.test.runTest {
        // The deterministic arm. Without it the concurrent arm below is satisfiable by a
        // clear() that drops nothing — live and recovered would simply agree on everything.
        val store = InMemoryDurableStore()
        val exporter = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
        repeat(CONCURRENT) { i -> exporter.export(record(i)) }

        exporter.clear()

        val recovered = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
        recovered.recover()
        assertEquals(emptyList(), recovered.snapshot().toList(), "a clear must actually empty the store")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun theStoreAndTheBufferAgreeWhenAClearRacesConcurrentExports() {
        val dispatcher = newFixedThreadPoolContext(THREADS, "otel-log-clear-stress")
        try {
            runBlocking {
                repeat(REPEATS) { iter ->
                    val store = VariableLatencyStore(iter)
                    val exporter = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)

                    val exports = (0 until CONCURRENT).map { i ->
                        launch(dispatcher) { exporter.export(record(i)) }
                    }
                    exporter.clear()
                    exports.joinAll()

                    // Every record that survives must be one whose export was serialized AFTER
                    // the clear. None of them may be recoverable from a segment the clear retired.
                    val recovered = WarpLogRecordExporter(ReplicaId("A"), store, segmentOps = 4)
                    recovered.recover()
                    val live = exporter.snapshot().toList().map { it.recordId }.toSet()
                    assertEquals(
                        live,
                        recovered.snapshot().toList().map { it.recordId }.toSet(),
                        "iter $iter: the store and the live buffer must agree after a concurrent clear",
                    )
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        private const val THREADS = 4
        private const val CONCURRENT = 16
        private const val REPEATS = 20
    }
}
```

- [ ] **Step 4: Run it**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterClearConcurrencyTest*"
```

Expected: PASS. If it hangs, **stop and escalate** — do not widen a bound. `jstack` the test JVM and name the spinning coroutine. A hang here would most likely mean `clear()` acquired `lock` before `writeMutex`, inverting the documented acquisition order.

- [ ] **Step 5: Full-module verification and lint**

```bash
./gradlew :kuilt-otel:build --rerun-tasks
./gradlew detektAll
```

Both must pass. `:kuilt-otel:build` is what compiles the Android and Kotlin/Native variants; `jvmTest` alone does not.

- [ ] **Step 6: Commit and open PR 1**

```bash
git add kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearTest.kt \
        kuilt-otel/src/jvmTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterClearConcurrencyTest.kt
git commit -m "test(otel): pin clear()'s failure and concurrency behaviour (part of #2208)"
git push -u origin feat/2208-log-exporter-clear
```

Open the PR with `part of #2208` — **not** a closing keyword; #2208 is not satisfied until Task 7 lands `WarpTelemetry.clear()`.

---

### Task 5: `WarpSpanExporter.clear()`

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpCausalClock.kt`
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpSpanExporterClearTest.kt` (create)

**Interfaces:**
- Consumes: existing private `lock`, `ioMutex`, `spans: ORSet<SpanRecord>`, `cbor`, `spanSerializer`, `STORE_KEY`, `causalClock: WarpCausalClock?`.
- Produces: `public suspend fun WarpSpanExporter.clear(): ExportResult` and `internal fun WarpCausalClock.clearFrontier()`. Task 7 depends on both.

**The frontier belongs here, not at the facade.** `WarpSpanExporter.clear()` is a public method in its own right — a caller can invoke it without going through `WarpTelemetry` — and leaving the causal frontier naming dots of spans it just removed breaks the totality `inferCausalLinks` claims. Since this exporter already holds the clock, it clears it.

**Background:** `ORSet` removal **retains** `causal.context`, so the retired dots stay witnessed — a peer's re-merge of the pre-clear adds is dominated rather than resurrecting. That is the span analogue of the log floor, and it is why this writes an emptied set rather than deleting the key. `ORSet` has no bulk remove; the in-tree idiom is `spans.piece { it.remove(victim) }` (see `maybeEvict`).

- [ ] **Step 1: Write the failing test**

Create `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpSpanExporterClearTest.kt`:

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins [WarpSpanExporter.clear] (#2208). */
class WarpSpanExporterClearTest {

    private val replicaA = ReplicaId("A")

    // traceId is validated at 16 bytes and spanId at 8; SpanRecord has no defaults for
    // parentSpanId or kind, so both are explicit.
    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { id.toByte() }),
        spanId = ByteString(ByteArray(8) { id.toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    @Test
    fun clearEmptiesTheSetAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpSpanExporter(replica = replicaA, store = store)
        repeat(5) { i -> exporter.export(span(i)) }

        assertEquals(ExportResult.Success, exporter.clear())

        val recovered = WarpSpanExporter(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptySet(), exporter.snapshot().elements) },
            { assertEquals(emptySet(), recovered.snapshot().elements, "a fresh exporter recovers empty") },
        )
    }

    @Test
    fun aPeerHoldingThePreClearAddsCannotPushThemBackThroughMerge() = runTest {
        val exporter = WarpSpanExporter(replica = replicaA, store = InMemoryDurableStore())
        exporter.export(span(1))
        exporter.export(span(2))
        val peerCopy = exporter.snapshot()

        assertEquals(ExportResult.Success, exporter.clear())
        assertEquals(ExportResult.Success, exporter.merge(peerCopy))

        assertEquals(
            emptySet(),
            exporter.snapshot().elements,
            "the retained causal context must dominate the peer's re-merged adds",
        )
    }
}
```

Import `us.tractat.kuilt.otel.SpanKind` if it is not already resolved by the package.

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterClearTest*"
```

Expected: compilation failure — `clear()` is not defined.

- [ ] **Step 3: Add the clock's frontier reset**

In `WarpCausalClock.kt`, after `frontier()`:

```kotlin
    /**
     * Forget the causal frontier while leaving [seq] untouched — the clock's half of a
     * span clear (#2208).
     *
     * The asymmetry is the point. [seq] must never regress: a reset one re-mints dots already
     * used by earlier spans, which is the corruption this class's "Recovery is mandatory"
     * section exists to prevent, and a clear is not a restart. The **frontier** must go,
     * because after a span clear it names dots of spans no longer in the set, and
     * [inferCausalLinks] resolves every predecessor dot against that set.
     *
     * The caller persists — [persist] is this class's explicit step, not an implicit one.
     */
    internal fun clearFrontier(): Unit = lock.withLock { frontier = emptySet() }
```

- [ ] **Step 4: Implement the span clear**

Add to `WarpSpanExporter`, immediately after `merge()`:

```kotlin
    /**
     * Drop every span this exporter holds and persist the emptied set (#2208).
     *
     * Removal, not [ORSet.empty]: an `ORSet` removal **retains** `causal.context`, so the
     * retired dots stay witnessed and a peer re-merging the pre-clear adds is dominated rather
     * than resurrecting them. An emptied-by-reset set would re-mint dots this replica has
     * already used, and a peer whose context already holds one would treat the *new* span as
     * seen-and-removed — swallowing it silently.
     *
     * The key is rewritten rather than deleted, because the retained context is what the
     * paragraph above rests on and it lives in those bytes.
     *
     * A configured [WarpCausalClock]'s **frontier** is emptied here too, and its `seq` left
     * alone. The frontier would otherwise name dots of spans this call just removed, which
     * breaks the totality [inferCausalLinks] relies on.
     *
     * Shares [ioMutex] with [export] and [merge] so a concurrent export cannot land a stale
     * encoded snapshot after the clear.
     */
    public suspend fun clear(): ExportResult = ioMutex.withLock {
        runCatchingCancellable {
            val encoded = lock.withLock {
                spans = spans.elements.toList().fold(spans) { set, span -> set.piece { it.remove(span) } }
                cbor.encodeToByteArray(spanSerializer, spans)
            }
            // The frontier belongs to this method, not to the facade. It names dots of spans
            // that no longer exist, and `inferCausalLinks` resolves every predecessor dot
            // against the span set — so leaving it would break that totality for anyone who
            // calls this exporter's clear() directly rather than WarpTelemetry.clear().
            // `seq` is deliberately untouched; see WarpCausalClock.clearFrontier.
            causalClock?.clearFrontier()
            // Clock before spans, the same order and for the same reason as export() (#1053).
            causalClock?.persist(store)
            store.write(STORE_KEY, encoded)
        }.fold(
            onSuccess = { ExportResult.Success },
            onFailure = { cause ->
                logger.error(cause) { "WarpSpanExporter: durable write failed during clear" }
                ExportResult.Failure(cause)
            },
        )
    }
```

- [ ] **Step 5: Run to verify it passes, then each test alone**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterClearTest*"
./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterClearTest.clearEmptiesTheSetAndTheStoreAFreshExporterRecoversFrom"
./gradlew :kuilt-otel:jvmTest --tests "*WarpSpanExporterClearTest.aPeerHoldingThePreClearAddsCannotPushThemBackThroughMerge"
```

Expected: PASS.

- [ ] **Step 6: Measure the fold before accepting it**

`DEFAULT_MAX_SPANS` is 10,000 and the fold does one causal `piece` per element. The spec says measure rather than add a bulk API speculatively. Add a temporary timing harness:

```kotlin
    @Test
    fun clearingAFullBufferIsNotPathological() = runTest {
        val exporter = WarpSpanExporter(replica = replicaA, store = InMemoryDurableStore())
        repeat(2_000) { i -> exporter.export(span(i)) }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        exporter.clear()
        println("clear of 2000 spans took ${started.elapsedNow()}")
    }
```

Run it, read the number, then **delete this test** — it asserts nothing and would be a wall-clock flake in CI.

Decision rule: if 2,000 spans clear in under ~200 ms on an idle box, ship the fold. Check `uptime` first — a loaded box distorts an absolute timing by orders of magnitude. If it is slower than that, **stop and escalate**: the fix is a bulk `ORSet.removeAll(elements)` in `:kuilt-crdt`, which is outside this plan's "do not modify `:kuilt-crdt`" constraint and needs a decision, not an improvisation.

- [ ] **Step 7: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpSpanExporterClearTest.kt
git commit -m "feat(otel): clear() empties a span exporter, retaining its causal context (part of #2208)"
```

---

### Task 6: `WarpMetricExporter.clear()` and its documented limit

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterClearTest.kt` (create)

**Interfaces:**
- Consumes: existing private `lock`, the five `LinkedHashMap` fields, the five `*_STORE_KEY` constants.
- Produces: `public suspend fun WarpMetricExporter.clear(): MetricExportResult`. Task 7 depends on it.

**The honest limit, which you must not quietly "fix":** `GCounter` and `HyperLogLog` are monotonic join-semilattices. A merge after a clear **restores the old values**, because that is what a max-join means. There is no merge-safe forget for either, and inventing one is a new CRDT, not a new method. The test below pins that behaviour deliberately so a later change has to face it rather than discover it.

- [ ] **Step 1: Write the failing test**

Create `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterClearTest.kt`:

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins [WarpMetricExporter.clear] (#2208), including the limit it cannot escape. */
class WarpMetricExporterClearTest {

    private val replicaA = ReplicaId("A")

    // MetricKey has no default for `kind`, and `name` must not be blank.
    private val sumKey = MetricKey("requests", MetricKind.SUM, mapOf("route" to "/health"))
    private val gaugeKey = MetricKey("queue.depth", MetricKind.GAUGE)

    @Test
    fun clearEmptiesEveryMetricKindAndTheStoreAFreshExporterRecoversFrom() = runTest {
        val store = InMemoryDurableStore()
        val exporter = WarpMetricExporter(replica = replicaA, store = store)
        exporter.incrementSum(sumKey, by = 7L)
        // setGauge takes an explicit observation timestamp — there is no wall-clock default.
        exporter.setGauge(gaugeKey, value = 1.5, timestamp = 1_700_000_000_000L)

        assertEquals(MetricExportResult.Success, exporter.clear())

        val recovered = WarpMetricExporter(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(0L, exporter.sumValue(sumKey), "the live exporter forgets") },
            { assertEquals(0L, recovered.sumValue(sumKey), "a fresh exporter recovers empty") },
        )
    }

    /**
     * Deliberate, not a defect. A `GCounter` is a monotonic join, so a merge takes the
     * element-wise max and the pre-clear total comes back. Clearing metrics is therefore
     * **local-only**, which is safe on a non-gossiping device and is what the KDoc says.
     * This test exists so a change that assumes otherwise reddens here rather than in the field.
     */
    @Test
    fun aMergeAfterAClearRestoresTheOldSumBecauseAMonotonicJoinCannotForget() = runTest {
        val exporter = WarpMetricExporter(replica = replicaA, store = InMemoryDurableStore())
        exporter.incrementSum(sumKey, by = 7L)
        val peerCopy = exporter.sumSnapshot(sumKey)

        exporter.clear()
        assertEquals(0L, exporter.sumValue(sumKey))

        exporter.mergeSum(sumKey, peerCopy)
        assertEquals(7L, exporter.sumValue(sumKey), "a monotonic join has no merge-safe forget")
    }
}
```

Add `import us.tractat.kuilt.otel.MetricKind` if the package does not already resolve it.

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterClearTest*"
```

Expected: compilation failure — `clear()` is not defined.

- [ ] **Step 3: Implement**

Add to `WarpMetricExporter`, after the recovery section:

```kotlin
    /**
     * Drop every metric series this exporter holds and delete its five persisted keys (#2208).
     *
     * **Local-only, and it cannot be otherwise.** [GCounter] and [HyperLogLog] are monotonic
     * join-semilattices: a merge takes the element-wise maximum, so a peer holding the
     * pre-clear state restores it. [LWWRegister] has no "cleared" value to write. Unlike
     * [WarpLogRecordExporter.clear] and [WarpSpanExporter.clear], which suppress the state they
     * drop, this one only forgets it locally. On a replica that does not gossip its metrics —
     * the case this exists for — the distinction never arises.
     *
     * The keys are deleted rather than rewritten empty: there is no retained context to
     * preserve here, so deleting reclaims the bytes and [recover] treats an absent key as empty.
     *
     * **Never throws.** A refused delete returns [MetricExportResult.Failure]; the in-memory
     * maps are cleared either way, so a retry converges.
     */
    public suspend fun clear(): MetricExportResult {
        lock.withLock {
            sums.clear()
            sumsDouble.clear()
            gauges.clear()
            cardinalities.clear()
            histograms.clear()
        }
        return runCatchingCancellable {
            store.delete(SUM_STORE_KEY)
            store.delete(SUM_DOUBLE_STORE_KEY)
            store.delete(GAUGE_STORE_KEY)
            store.delete(CARDINALITY_STORE_KEY)
            store.delete(HISTOGRAM_STORE_KEY)
        }.fold(
            onSuccess = { MetricExportResult.Success },
            onFailure = { cause ->
                logger.error(cause) { "otel.metrics: durable delete failed during clear" }
                MetricExportResult.Failure(cause)
            },
        )
    }
```

- [ ] **Step 4: Run to verify it passes, then each test alone**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterClearTest*"
./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterClearTest.clearEmptiesEveryMetricKindAndTheStoreAFreshExporterRecoversFrom"
./gradlew :kuilt-otel:jvmTest --tests "*WarpMetricExporterClearTest.aMergeAfterAClearRestoresTheOldSumBecauseAMonotonicJoinCannotForget"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpMetricExporter.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpMetricExporterClearTest.kt
git commit -m "feat(otel): clear() empties a metric exporter, local-only by construction (part of #2208)"
```

---

### Task 7: `WarpTelemetry.clear()`

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpTelemetry.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpTelemetryClearTest.kt` (create)

**Interfaces:**
- Consumes: `WarpLogRecordExporter.clear(): ExportResult` (Task 1), `WarpSpanExporter.clear(): ExportResult` (Task 5), `WarpMetricExporter.clear(): MetricExportResult` (Task 6).
- Produces: `public suspend fun WarpTelemetry.clear(): ExportResult`.

**The causal clock is Task 5's, not this task's.** `WarpSpanExporter.clear()` empties the frontier and persists the clock, because it owns the clock and is public in its own right. This facade only fans out. The clock test below still lives here, because the facade is where a consumer's "Clear store" button lands and the property has to hold end-to-end.

- [ ] **Step 1: Write the failing test**

Create `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpTelemetryClearTest.kt`:

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [WarpTelemetry.clear] (#2208) — the call a consumer's "Clear store" affordance makes. */
class WarpTelemetryClearTest {

    private val replicaA = ReplicaId("A")

    private fun record(id: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { i -> (id shr (8 * i)).toByte() }),
        body = "body-$id",
        observedEpochNanos = 1_000L + id,
    )

    @Test
    fun clearEmptiesEverySignalAndAFreshTelemetryRecoversEmpty() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        repeat(5) { i -> telemetry.logs.export(record(i)) }

        assertEquals(ExportResult.Success, telemetry.clear())

        val recovered = WarpTelemetry(replica = replicaA, store = store)
        recovered.recover()
        assertAll(
            { assertEquals(emptyList(), telemetry.logs.snapshot().toList()) },
            { assertEquals(emptyList(), recovered.logs.snapshot().toList()) },
            { assertEquals(emptySet(), recovered.spans.snapshot().elements) },
        )
    }

    @Test
    fun theSameInstanceKeepsExportingAfterAClear() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        repeat(5) { i -> telemetry.logs.export(record(i)) }
        telemetry.clear()

        assertEquals(ExportResult.Success, telemetry.logs.export(record(99)))

        val recovered = WarpTelemetry(replica = replicaA, store = store)
        recovered.recover()
        assertEquals(
            listOf(record(99).recordId),
            recovered.logs.snapshot().toList().map { it.recordId },
            "no restart is required for the store to accept new records",
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpTelemetryClearTest*"
```

Expected: compilation failure — `clear()` is not defined on `WarpTelemetry`.

- [ ] **Step 3: Add the fan-out**

In `WarpTelemetry.kt`, after `recover()`:

```kotlin
    /**
     * Empty every signal's buffer and its persisted state — the supported reset (#2208).
     *
     * Callable on a live instance: no restart, no per-platform directory delete. The same
     * instance keeps exporting into the cleared store afterwards.
     *
     * **Best-effort across signals, and not atomic.** Every signal is attempted even if an
     * earlier one failed, and the first failure is returned — unlike [WarpOtlpBridge.drain],
     * which tolerates a partial success, because a half-cleared store is a result the caller
     * has to see rather than one to paper over.
     *
     * The three signals differ in what a clear guarantees against a peer, and the difference
     * is structural rather than an omission:
     *
     * - [logs] and [spans] **suppress** what they drop, so a peer holding the pre-clear ops
     *   cannot push them back through a merge.
     * - [metrics] can only forget **locally** — a monotonic join has no merge-safe forget, so a
     *   merge restores the old values. See [WarpMetricExporter.clear].
     *
     * The causal clock's frontier is emptied and its `seq` left alone — by
     * [WarpSpanExporter.clear], which owns the clock, so a caller reaching that exporter
     * directly gets the same treatment. This facade adds nothing there.
     */
    public suspend fun clear(): ExportResult {
        val logsResult = logs.clear()
        val spansResult = spans.clear()
        val metricsResult = metrics.clear()
        return listOf(logsResult, spansResult, metricsResult.asExportResult())
            .filterIsInstance<ExportResult.Failure>()
            .firstOrNull()
            ?: ExportResult.Success
    }

    /** Bridge the metric exporter's own result type into the one this facade reports. */
    private fun MetricExportResult.asExportResult(): ExportResult = when (this) {
        is MetricExportResult.Success -> ExportResult.Success
        is MetricExportResult.Failure -> ExportResult.Failure(cause)
    }
```

- [ ] **Step 4: Run to verify it passes, then each test alone**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpTelemetryClearTest*"
./gradlew :kuilt-otel:jvmTest --tests "*WarpTelemetryClearTest.clearEmptiesEverySignalAndAFreshTelemetryRecoversEmpty"
./gradlew :kuilt-otel:jvmTest --tests "*WarpTelemetryClearTest.theSameInstanceKeepsExportingAfterAClear"
```

Expected: PASS.

- [ ] **Step 5: Add the clock assertion**

Append to `WarpTelemetryClearTest`:

```kotlin
    // traceId is validated at 16 bytes and spanId at 8; parentSpanId and kind have no defaults.
    private fun span(id: Int) = SpanRecord(
        traceId = ByteString(ByteArray(16) { id.toByte() }),
        spanId = ByteString(ByteArray(8) { id.toByte() }),
        parentSpanId = null,
        name = "span-$id",
        kind = SpanKind.INTERNAL,
        startEpochNanos = 1_000L + id,
        endEpochNanos = 2_000L + id,
    )

    @Test
    fun aClearEmptiesTheCausalFrontierWithoutRegressingTheClock() = runTest {
        val store = InMemoryDurableStore()
        val telemetry = WarpTelemetry(replica = replicaA, store = store)
        // Unstamped spans are auto-stamped, so each export ticks the clock AND persists it.
        telemetry.spans.export(span(1))
        telemetry.spans.export(span(2))

        telemetry.clear()

        // WarpTelemetry's clock is private, so read the persisted one — which is the state
        // that actually has to survive, and the state a restart would see.
        val persisted = WarpCausalClock(replicaA).also { it.recover(store) }
        assertAll(
            { assertEquals(emptySet(), persisted.frontier(), "the frontier is forgotten") },
            {
                assertTrue(
                    persisted.tick().dot.seq > 2L,
                    "seq must not regress: the two spans above already consumed 1 and 2, " +
                        "so a recovered clock ticks to 3 — a reset one would tick to 1 and " +
                        "re-mint a dot an earlier span already carries",
                )
            },
        )
    }
```

Add `us.tractat.kuilt.crdt.ReplicaId` and the `SpanKind`/`SpanRecord`/`ByteString` imports the fixture needs. **Do not** widen `WarpTelemetry`'s API to make this test easier — reading the persisted clock is both sufficient and closer to what a restart does.

- [ ] **Step 6: Full-module verification and lint**

```bash
./gradlew :kuilt-otel:build --rerun-tasks
./gradlew detektAll
```

- [ ] **Step 7: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpTelemetry.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpTelemetryClearTest.kt
git commit -m "feat(otel): WarpTelemetry.clear() fans out across every signal (part of #2208)"
```

---

### Task 8: Documentation

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt` (class KDoc)
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/LogSegmentIndex.kt` (KDoc)
- Modify: `kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt`
- Modify: `kuilt-otel/module.md`
- Modify: `Writerside/topics/otel-logs.md`
- Modify: `docs/agent-cookbook.md`

**Interfaces:**
- Consumes: every `clear()` from Tasks 1, 5, 6, 7.
- Produces: nothing code-facing.

**Three of these are staleness, not additions.** Each is a claim `clear()` falsifies, and a doc contradicted by the code is worse than a thin one.

- [ ] **Step 1: Fix the retirement section's driver claim**

`WarpLogRecordExporter`'s "Retiring superseded segments" section describes a `windowPass` as the thing that drives retirement. Add after its first paragraph:

```
 * [clear] is the second driver, and the only one that retires the whole sealed set at once:
 * it is a pass that retains nothing, so every sealed segment a replica fed by [export] holds
 * becomes superseded in one step.
```

- [ ] **Step 2: Qualify the key-enumeration sentence**

In `LogSegmentIndex`'s `retired` KDoc, after "There is no key-enumeration API, so a segment the index simply forgets is unreachable and unsweepable forever.", add:

```
 *   That constraint is why `WarpLogRecordExporter.clear` is index-driven rather than a
 *   directory sweep, and it still binds a **consumer**: holding a `DurableStore` is not
 *   enough to discover the segment numbers. The exporter's own index is sufficient for the
 *   exporter's own reset, and that is the whole of what #2208 closes.
```

- [ ] **Step 3: Add the sample**

`@sample` functions compile as part of `commonTest`, so a broken one breaks the build. Add to `kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt`:

Every function in `Samples.kt` is `internal`, not `public` — match the file.

```kotlin
/**
 * Emptying a telemetry store from a running app — no restart, no directory delete.
 */
internal suspend fun sampleWarpTelemetryClear() {
    val telemetry = WarpTelemetry(
        replica = ReplicaId("device-uuid-here"),
        store = InMemoryDurableStore(),
    )
    telemetry.recover()

    when (val result = telemetry.clear()) {
        is ExportResult.Success -> println("store emptied; the same instance keeps exporting")
        is ExportResult.Failure -> println("clear failed: ${result.cause}; retry converges")
    }
}
```

Reference it from `WarpTelemetry.clear()`'s KDoc with `@sample us.tractat.kuilt.otel.sampleWarpTelemetryClear`. Match the existing samples' import style in that file.

- [ ] **Step 4: Update `module.md` and the Writerside topic**

Add a row to `kuilt-otel/module.md`'s table describing `clear()` as the supported reset. In `Writerside/topics/otel-logs.md`, add a short section — plain language first per the repo's accessible-first rule, technical depth after — covering: what `clear()` does, that the same instance keeps exporting, and that metrics forget only locally.

- [ ] **Step 5: Add the cookbook entry**

`docs/agent-cookbook.md` **already has** a `## Telemetry & log capture` section (line 1067) with a row in the "Don't build this yourself" table (line 32) and a `verbatim` citation of `Samples.kt#sampleBulkExport` (line 1078). Extend it; do not add a second section.

Add a row to the table, using the **existing** anchor — `#telemetry--log-capture`, not `#telemetry`, which would dangle:

```
| deleting a telemetry store's files to reset it, or a "clear on next launch" flag so the delete lands before recovery | `WarpTelemetry.clear()` | [Telemetry & log capture](#telemetry--log-capture) |
```

Then add a snippet to that section in the format its existing `sampleBulkExport` block already uses, cited `<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetryClear -->`. The block must match the source **character-for-character** modulo indentation, or use `<!-- condensed from … -->` if it genuinely cannot.

- [ ] **Step 6: Verify the citations and confirm the skill still routes**

```bash
./gradlew verifyDocCitations
```

Expected: PASS. A drifted or dangling citation fails the build.

Then read `.claude/skills/kuilt-primitives/SKILL.md` and confirm its `description` would fire for a developer phrasing the need as "reset the telemetry store" / "clear logged data". If it would not, add the phrasing.

- [ ] **Step 7: Full verification**

```bash
./gradlew :kuilt-otel:build --rerun-tasks
./gradlew detektAll
./gradlew verifyDocCitations
```

- [ ] **Step 8: Commit and open PR 2**

```bash
git add -A
git commit -m "docs(otel): document clear() and correct what it falsifies (part of #2208)"
git push -u origin feat/2208-telemetry-clear
```

PR 2's body may use `closes #2208` — it is the PR that completes the ask. Note in the body that this is what lets the consumer-side request linked from #2208 drop its interim per-platform directory-delete workaround.

**Verify after merge** that #2208 actually closed; a closing keyword inside backticks does not fire, and a squash turns the PR body into the commit message.

---

## Known gaps this plan deliberately does not close

- **`WarpMetricExporter` has no mutex at all.** It encodes under `lock` and persists outside it, so two concurrent `incrementSum` calls can lose one durably — the same defect `WarpSpanExporter` fixed in #1053 and `WarpLogRecordExporter` in #2187, still live in the third exporter. It makes Task 6's clear racy against a concurrent increment. Filed separately; do **not** expand this plan into it.
- **Key enumeration on `DurableStore`** stays absent. See Task 8 Step 2.
- **Consolidation** — rewriting a pinned segment's `Compact` forward so the segment can be retired — is what would make a gossiping replica's clear total. Declined by #2187's design §9.
