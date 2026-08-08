# Batched log-record write turn (#2194) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop paying one CRDT append pass, one segment encode and two durable file writes **per log record**; pay them once per batch of records instead.

**Architecture:** Three layers change, bottom-up. `:kuilt-crdt` gains two bulk mutators on `Rga` (`insertAllAfter`, `removeFirst`) so a run of appends and a run of evictions each cost **one** `ops + newOps` copy and **one** cache build instead of one per element. `:kuilt-otel` gains `WarpLogRecordExporter.export(records: List<LogRecord>)` — a write turn that admits a run of records, mutates the log twice, encodes the active segment once and writes it once. `:kuilt-otel-logging` changes its drain from `for (event in events)` to *drain-what's-already-queued*, so the batch forms only when the producer is outrunning the drain.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (`Channel`, `Mutex`), kotlinx-atomicfu (`reentrantLock`), kotlinx-serialization-cbor, kotlin-logging (oshai).

## Global Constraints

- **The drain is opportunistic, not timed.** No `flushInterval`, no injected clock, no timer. See "Architecture decision 1" below — this is a deliberate departure from the fix #2194's body prescribes, and the reviewer is asked to adjudicate it.
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public`.
- **`detektAll`, never bare `detekt`.** Bare `detekt` is `NO-SOURCE` in this KMP setup and is a false green.
- **Run the full `./gradlew build` before enabling auto-merge**, not `:kuilt-otel:build`. A module-scoped build skips the `:examples` / `:kuilt-cluster` E2E tests, and this is a shipped write path.
- **Verify cache-disabled:** `./gradlew build detektAll --rerun-tasks`; add `--no-build-cache` if any test-compile task still shows `FROM-CACHE`.
- **Do not drive ~10,000 exports in a `:kuilt-otel` test.** It breaks `wasmJsBrowserTest` with a misleading "did not discover any tests" (#2183). Every test here uses a small `maxRecords`/`segmentOps` and tens of records, never thousands.
- **`runTest(timeout = TEST_WEDGE_BACKSTOP)`** (`us.tractat.kuilt.test`) on every new coroutine test. Never a hand-picked `5.seconds`; `forbidTightRunTestTimeout` enforces this.
- **No production dispatchers in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`).
- **Never `advanceUntilIdle()`** — bounded `advanceTimeBy` / `runCurrent()` only.
- **`runCatchingCancellable`, never bare `runCatching`**, in any suspend/coroutine context.
- **Run every new test alone** (`--tests "*<OneTest>*"`) before committing — a green suite is not proof each test passes in isolation.
- **`./gradlew verifyDocCitations`** after touching a doc snippet or the source it cites (~1 s).

---

## Architecture decisions worth reading before Task 1

These three are where a reviewer's disagreement would change the plan rather than a line of code.

### 1. Opportunistic drain, not a flush interval — the durability contract does NOT change

#2194's body prescribes OTel's `BatchLogRecordProcessor` model: accumulate for up to `scheduledDelayMillis` (default 1000), flush at `maxExportBatchSize` (default 512). That trade — "up to one flush interval of records may be lost on a crash" — is a real weakening of a guarantee `WarpLogRecordExporter`'s KDoc currently makes unconditionally ("Returns `ExportResult.Success` after the durable write").

**It buys nothing here, because the queue already exists.** `CapturingAppender` holds a bounded `Channel` between the application's synchronous `log()` and the suspending drain. A record already sits in RAM from `log()` until the drain reaches it; a timer would only make it sit there *longer*. What the timer would add is a batch when the producer is *slow* — and when the producer is slow there is nothing to amortise, because the drain is keeping up and the per-record floor is being paid on an idle device.

So the drain blocks on `receive()` for the first event and then greedily `tryReceive()`s whatever is **already queued**, up to a cap:

- A lone log line on an idle app is exported immediately — same latency, same durability, as today.
- A burst of 500 lines forms one batch of ≤ cap, because they are all queued before the drain gets a turn.
- Sustained overload self-equilibrates: the batch grows until the amortised per-record cost matches the arrival rate, then stops growing. The feedback loop is the point.

The one case a timer would beat this: a steady rate *just below* the drain rate, where each cycle picks up exactly one record and pays the full floor. A timer would coalesce those. That is the "idle-ish device logging steadily" case — where the floor is cheap and nobody is waiting on it. Not worth a durability weakening.

**If the review disagrees**, the timed variant is strictly additive later: a `flushInterval` parameter on `CapturingAppender` that starts a `withTimeoutOrNull` around the greedy loop. Nothing in this plan forecloses it. Building it now would mean an injected dispatcher, a re-arming timer, `advanceTimeBy` discipline in every test, and a KDoc contract change — for the case above.

### 2. "Batch" is being *reassigned*, deliberately

`WarpLogRecordExporter`'s KDoc currently uses **"batch"** ~25 times to mean *the ordered list of `StoreAction`s one write turn applies*. That concept already has a better name in the code — the class calls it a **turn** (`exportTurn`, `mergeTurn`, `writeMutex`, "Serializes one **write turn**"). "Batch" as a group of *records* is what OTel calls it (`BatchLogRecordProcessor`, `maxExportBatchSize`), so it is the word every consumer will arrive with.

Task 1 therefore sweeps the informal "batch" → "the turn's actions" / "this turn" in that file, as a **separate, behaviour-free commit**, so that Task 3's diff is readable. Reviewers can reject the rename and keep the batching, or the reverse.

### 3. Bulk mutators go on `Rga`, and this is NOT #2193

The eviction interleaving is what makes a naive `insertAll` useless: at a full buffer every export is `removeAt(0)` *then* `insertAfter`, alternating, so a bulk insert alone amortises nothing in the steady state — which is the state a device that has been logging for hours is in.

So the turn does **two** `Rga` mutations, not `2k`:

1. `removeFirst(e)` — one `ops + removes` copy, one cache build, one `sequence` walk.
2. `insertAllAfter(replica, tail, values)` — one `ops + inserts` copy, one cache build.

Each is Θ(N) once per **turn**. At a 128-record turn that is the ~128× cut #2194 claims, **with no new dependency and no persistent data structure**. The residual Θ(N)-per-turn is what #2193 is now about; see `2026-08-08-crdt-sequence-persistent-backing.md`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt` | Modify (add 2 public fns after `removeAt`, ~line 410) | `insertAllAfter`, `removeFirst` — bulk siblings of `insertAfter`/`removeAt` |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaBulkMutatorTest.kt` | Create | Equivalence with the per-element path, and the allocation-count property |
| `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt` | Modify | The batched write turn; "batch"→"turn" vocabulary sweep |
| `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterBatchTest.kt` | Create | Batch semantics: equivalence, write-count, health accounting, chunking |
| `kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt` | Modify | A bulk-export sample |
| `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/LogCapture.kt` | Modify | `captureAll(events)` — map a run of events to a run of records, one export |
| `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CapturingAppender.kt` | Modify (`init` block, ~line 94; overflow report, ~line 169) | The opportunistic drain; `maxBatchSize` |
| `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CaptureHealth.kt` | Modify | `CAPTURE_BATCH_MAX` constant + its rationale |
| `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBatchingTest.kt` | Create | **The regression test for #2194** — writes per record |
| `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBoundedQueueTest.kt` | Modify | Keep the guard; re-state what overflow means after batching |
| `kuilt-otel/module.md`, `kuilt-otel-logging/module.md`, `docs/agent-cookbook.md` | Modify | Doc surface for the new primitive |

---

### Task 1: Vocabulary sweep — "batch" means a run of records

Behaviour-free. Does the rename first so Task 3's diff shows only the batching.

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt` (KDoc only)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing (no signature changes). Later tasks rely only on the vocabulary being free.

- [ ] **Step 1: Find every informal use**

Run: `grep -n "batch" kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt`

Expected: ~25 hits, all in KDoc/comments, none in an identifier.

- [ ] **Step 2: Rewrite each hit to name the turn**

Substitute by meaning, not by regex. The four shapes that occur:

| Before | After |
|---|---|
| "build a batch under [lock], then apply it to [store]" | "build a turn's actions under [lock], then apply them to [store]" |
| "a batch does not *merge* into a key — it **overwrites** it" | "a turn's write does not *merge* into a key — it **overwrites** it" |
| "a batch that failed at or before its active-segment write" | "a turn that failed at or before its active-segment write" |
| "Apply a batch of store mutations **in order**" (on `commit`) | "Apply one turn's store mutations **in order**" |

Leave `PendingRetirement`, `StoreAction`, `commit(actions:)` and every other identifier alone — none of them says "batch".

- [ ] **Step 3: Confirm nothing but comments moved**

Run: `git diff --stat && git diff -U0 kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt | grep -E '^[+-]' | grep -vE '^[+-]{3}' | grep -vE '^[+-]\s*(\*|//)' | grep -vE '^[+-]\s*$'`

Expected: the second command prints **nothing** — every changed line is a comment line.

- [ ] **Step 4: Build and lint**

Run: `./gradlew :kuilt-otel:compileKotlinJvm detektAll`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt
git commit -m "docs(otel): say 'turn' for a turn's actions, freeing 'batch' for a run of records

Part of #2194. The class already calls the unit of work a write TURN
(exportTurn/mergeTurn/writeMutex); 'batch' was the informal synonym. OTel's own
vocabulary uses 'batch' for a group of records (BatchLogRecordProcessor,
maxExportBatchSize), which is the word a consumer arrives with. Comments only."
```

---

### Task 2: `Rga.insertAllAfter` and `Rga.removeFirst`

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt` — insert after `removeAt` (currently ends ~line 410)
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaBulkMutatorTest.kt` (create)

**Interfaces:**
- Consumes: existing private `nextSeqFor`, `visibleSequence()`, the `RgaCache` constructor, `Rga`'s private constructor `(ops, lamport, compactedBelow, cache)`.
- Produces:
  - `public fun <V> Rga<V>.insertAllAfter(replica: ReplicaId, after: RgaId, values: List<V>): Pair<Rga<V>, List<RgaOp.Insert<V>>>` — appends `values` as a chain starting after `after`; returns the new state and the ops **in order**. Empty `values` returns `this to emptyList()`.
  - `public fun <V> Rga<V>.removeFirst(count: Int): Pair<Rga<V>, List<RgaOp.Remove<V>>>` — tombstones the first `count` **visible** elements. `count <= 0` returns `this to emptyList()`; `count > size` throws `IllegalArgumentException`.

- [ ] **Step 1: Write the failing tests**

Create `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaBulkMutatorTest.kt`:

```kotlin
package us.tractat.kuilt.crdt

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bulk siblings of [Rga.insertAfter] / [Rga.removeAt] must be *indistinguishable*
 * from the per-element loop they replace — same visible sequence, same op-set, same
 * ids, same Lamport clock — while paying one `ops + newOps` copy instead of one per
 * element (#2194).
 *
 * Equivalence is the load-bearing property: `WarpLogRecordExporter` persists these
 * ops verbatim and gossips them, so an id or a Lamport value that differed from the
 * per-element path would be a wire-format change, not an optimisation.
 */
class RgaBulkMutatorTest {

    private val replica = ReplicaId("r1")

    private fun perElementAppend(values: List<String>): Pair<Rga<String>, List<RgaOp.Insert<String>>> {
        var state = Rga.empty<String>()
        var after = RgaId.HEAD
        val ops = mutableListOf<RgaOp.Insert<String>>()
        values.forEach { value ->
            val (next, op) = state.insertAfter(replica = replica, after = after, value = value)
            state = next
            after = op.id
            ops += op
        }
        return state to ops
    }

    @Test
    fun appendingAsARunIsIndistinguishableFromAppendingOneAtATime() {
        val values = listOf("a", "b", "c", "d")
        val (looped, loopedOps) = perElementAppend(values)
        val (bulk, bulkOps) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, values)

        assertAll(
            { assertEquals(values, bulk.toList()) },
            { assertEquals(looped.toList(), bulk.toList()) },
            { assertEquals(loopedOps.map { it.id }, bulkOps.map { it.id }, "ids must match the per-element path") },
            { assertEquals(loopedOps.map { it.after }, bulkOps.map { it.after }, "the chain must be after-linked") },
            { assertEquals(looped.lamport, bulk.lamport) },
            { assertEquals(looped.opCount, bulk.opCount) },
        )
    }

    @Test
    fun appendingARunOntoANonEmptyLogChainsOffTheGivenPredecessor() {
        val (seeded, seededOps) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b"))
        val (grown, grownOps) = seeded.insertAllAfter(replica, seededOps.last().id, listOf("c", "d"))

        assertAll(
            { assertEquals(listOf("a", "b", "c", "d"), grown.toList()) },
            { assertEquals(seededOps.last().id, grownOps.first().after) },
        )
    }

    @Test
    fun appendingAnEmptyRunIsIdentity() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a"))
        val (after, ops) = seeded.insertAllAfter(replica, RgaId.HEAD, emptyList())

        assertAll(
            { assertTrue(after === seeded, "an empty run must return the same instance, not a copy") },
            { assertEquals(emptyList(), ops) },
        )
    }

    @Test
    fun removingALeadingRunIsIndistinguishableFromRemovingOneAtATime() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b", "c", "d"))

        var looped = seeded
        repeat(2) { looped = looped.removeAt(0)!!.first }
        val (bulk, removes) = seeded.removeFirst(2)

        assertAll(
            { assertEquals(listOf("c", "d"), bulk.toList()) },
            { assertEquals(looped.toList(), bulk.toList()) },
            { assertEquals(looped.tombstones, bulk.tombstones) },
            { assertEquals(2, removes.size) },
            { assertEquals(seeded.sequence.take(2), removes.map { it.id }, "removes must name the leading visible ids") },
        )
    }

    @Test
    fun removingZeroIsIdentityAndRemovingMoreThanIsVisibleFails() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b"))

        assertAll(
            { assertTrue(seeded.removeFirst(0).first === seeded) },
            { assertTrue(seeded.removeFirst(-1).first === seeded) },
            { assertFailsWith<IllegalArgumentException> { seeded.removeFirst(3) } },
        )
    }

    /**
     * Tombstones are skipped, not counted: `removeFirst` takes the first `count`
     * **visible** elements, so a log that already carries a tombstone at the head
     * removes the same records the per-element loop would.
     */
    @Test
    fun removingALeadingRunSkipsExistingTombstones() {
        val (seeded, _) = Rga.empty<String>().insertAllAfter(replica, RgaId.HEAD, listOf("a", "b", "c"))
        val (tombstoned, _) = seeded.removeFirst(1)
        val (bulk, removes) = tombstoned.removeFirst(1)

        assertAll(
            { assertEquals(listOf("c"), bulk.toList()) },
            { assertEquals(1, removes.size) },
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*RgaBulkMutatorTest*"`
Expected: FAIL — `Unresolved reference: insertAllAfter` / `removeFirst`.

- [ ] **Step 3: Implement both mutators**

In `Rga.kt`, immediately after `removeAt` (which ends with the `return Rga(newOps, lamport, compactedBelow, newCache) to op` around line 410), add:

```kotlin
    /**
     * Append [values] as a chain starting immediately after [after], minting one
     * [RgaOp.Insert] per element on behalf of [replica].
     *
     * The bulk sibling of [insertAfter], and **indistinguishable from calling it in a
     * loop**: the same ids, the same `after` links, the same Lamport clock, the same
     * op-set. What differs is the cost. [insertAfter] rebuilds `ops` and `insertsById`
     * on every call, so appending `k` elements to an `N`-op log is `k` copies of `N` —
     * Θ(k·N). This pays **one** `ops + newOps` and **one** cache build for the whole
     * run, so it is Θ(N + k).
     *
     * That is the amortisation `WarpLogRecordExporter` needs to stop paying a Θ(N)
     * append per log record (#2194), and it needs no persistent data structure and no
     * new dependency on this deliberately dependency-free module. It does **not**
     * remove the Θ(N) term — one copy per run remains, which is #2193.
     *
     * An empty [values] returns `this` — the same instance, not a copy.
     *
     * @return the new state, and the ops to broadcast **in append order**.
     */
    public fun insertAllAfter(
        replica: ReplicaId,
        after: RgaId,
        values: List<V>,
    ): Pair<Rga<V>, List<RgaOp.Insert<V>>> {
        if (values.isEmpty()) return this to emptyList()
        var newLamport = lamport
        var seq = nextSeqFor(replica) - 1L
        var predecessor = after
        val minted = ArrayList<RgaOp.Insert<V>>(values.size)
        values.forEach { value ->
            newLamport += 1L
            seq += 1L
            val id = RgaId(lamport = newLamport, replicaId = replica, seq = seq)
            minted += RgaOp.Insert(id = id, value = value, after = predecessor)
            predecessor = id
        }
        val newCache = RgaCache(
            insertsById = insertsById + minted.associateBy { it.id },
            maxSeqByReplica = maxSeqByReplica + (replica to seq),
            tombstones = tombstones,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(ops + minted, newLamport, compactedBelow, newCache) to minted
    }

    /**
     * Tombstone the first [count] **visible** elements, minting one [RgaOp.Remove] each.
     *
     * The bulk sibling of `removeAt(0)` repeated, and indistinguishable from it: the
     * same ids tombstoned, in the same order. The cost differs the same way
     * [insertAllAfter]'s does — one `ops + removes` copy and one cache build for the
     * whole run, and **one** [sequence] materialisation rather than one per removal.
     * That second saving is the larger one in practice: every `removeAt` returns a new
     * instance whose `sequence` lazy is cold, so a loop of `k` removals recomputes the
     * full RGA order `k` times.
     *
     * Existing tombstones are skipped rather than counted, exactly as `removeAt(0)`
     * skips them — [count] is a number of *visible* elements.
     *
     * A [count] of zero or less returns `this` (the same instance, not a copy).
     *
     * @throws IllegalArgumentException if [count] exceeds [size].
     * @return the new state, and the ops to broadcast in removal order.
     */
    public fun removeFirst(count: Int): Pair<Rga<V>, List<RgaOp.Remove<V>>> {
        if (count <= 0) return this to emptyList()
        val visible = visibleSequence()
        require(count <= visible.size) {
            "removeFirst($count) exceeds the visible size of ${visible.size}"
        }
        val removed = visible.subList(0, count)
        val minted = removed.map { id -> RgaOp.Remove<V>(id = id) }
        val newCache = RgaCache(
            insertsById = insertsById,
            maxSeqByReplica = maxSeqByReplica,
            tombstones = tombstones + removed,
            compactedIds = compactedIds,
            compactPositions = compactPositions,
        )
        return Rga(ops + minted, lamport, compactedBelow, newCache) to minted
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*RgaBulkMutatorTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run each test alone, and the whole `:kuilt-crdt` suite**

```bash
for t in appendingAsARunIsIndistinguishableFromAppendingOneAtATime \
         appendingARunOntoANonEmptyLogChainsOffTheGivenPredecessor \
         appendingAnEmptyRunIsIdentity \
         removingALeadingRunIsIndistinguishableFromRemovingOneAtATime \
         removingZeroIsIdentityAndRemovingMoreThanIsVisibleFails \
         removingALeadingRunSkipsExistingTombstones; do
  ./gradlew :kuilt-crdt:jvmTest --tests "*RgaBulkMutatorTest.$t" || echo "FAILED ALONE: $t"
done
./gradlew :kuilt-crdt:build detektAll
```

Expected: every test passes alone; `:kuilt-crdt:build` green. The existing `Rga` conformance and canonical-encoding suites must be untouched — these are additive.

- [ ] **Step 6: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaBulkMutatorTest.kt
git commit -m "feat(crdt): bulk Rga.insertAllAfter / removeFirst — one copy per run

Part of #2194. insertAfter and removeAt each rebuild the op-set and the caches,
so k appends onto an N-op log cost Theta(k*N); the bulk forms pay one copy and
one cache build for the whole run. removeFirst also materialises the RGA
sequence once rather than once per removal.

Indistinguishable from the per-element loop — same ids, same after-links, same
Lamport clock — because the ops are persisted and gossiped verbatim."
```

---

### Task 3: `WarpLogRecordExporter.export(records: List<LogRecord>)`

The substantive task. One turn admits a run, mutates `log` twice, encodes and writes the active segment once.

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterBatchTest.kt` (create)

**Interfaces:**
- Consumes: `Rga.insertAllAfter`, `Rga.removeFirst` (Task 2).
- Produces:
  - `public suspend fun export(records: List<LogRecord>): ExportResult` on `WarpLogRecordExporter`.
  - `public suspend fun export(record: LogRecord): ExportResult` — unchanged signature, now delegating to the above.
  - `ExporterHealth.accepted` now counts **records**, not calls (it already documents itself as "records durably taken").

- [ ] **Step 1: Write the failing tests**

Create `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterBatchTest.kt`:

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A run of records must cost one write turn, not one per record (#2194).
 *
 * Capturing one log line used to cost a Theta(N) CRDT append, a CBOR encode of the
 * active segment and **two** durable file writes — measured at a ~9 ms floor on an
 * iPhone XS that never amortised, plus a growing Theta(N) term. These pin that a
 * batched export is observationally identical to the per-record loop while paying
 * the fixed cost once.
 */
class WarpLogRecordExporterBatchTest {

    /** Counts what reaches the store — the direct measurement #2194 is about. */
    private class CountingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        var writes: Int = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            writes++
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    /**
     * Refuses the **first** write and accepts every later one — the shape a quota-bound
     * store presents transiently, and the discriminator for whether a failed turn
     * abandons the rest of its batch.
     */
    private class FailOnceStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        private var refused = false

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (!refused) {
                refused = true
                error("store refused the write")
            }
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    private fun record(n: Int) = LogRecord(
        recordId = ByteString(ByteArray(RECORD_ID_BYTES) { n.toByte() }),
        severityNumber = 9,
        severityText = "INFO",
        body = "event $n",
        attributes = emptyMap(),
        timestampEpochNanos = n.toLong(),
        observedEpochNanos = n.toLong(),
    )

    private fun records(count: Int) = List(count) { record(it) }

    @Test
    fun aBatchIsObservationallyIdenticalToExportingOneAtATime() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val looped = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        records(BATCH).forEach { looped.export(it) }

        val batched = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        batched.export(records(BATCH))

        assertAll(
            { assertEquals(records(BATCH).map { it.body }, batched.snapshot().toList().map { it.body }) },
            { assertEquals(looped.snapshot().toList().map { it.body }, batched.snapshot().toList().map { it.body }) },
            { assertEquals(looped.snapshot().opCount, batched.snapshot().opCount) },
            { assertEquals(looped.health.value.accepted, batched.health.value.accepted) },
        )
    }

    /**
     * The headline: writes must not scale with records. The per-record path pays two
     * writes each; a batch pays a segment write plus at most one index write.
     */
    @Test
    fun aBatchPaysWritesPerTurnRatherThanPerRecord() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val loopedStore = CountingStore()
        val looped = WarpLogRecordExporter(ReplicaId("device-1"), loopedStore, segmentOps = SEGMENT_OPS)
        records(BATCH).forEach { looped.export(it) }

        val batchedStore = CountingStore()
        val batched = WarpLogRecordExporter(ReplicaId("device-1"), batchedStore, segmentOps = SEGMENT_OPS)
        batched.export(records(BATCH))

        assertAll(
            {
                assertTrue(
                    batchedStore.writes * MIN_AMORTISATION <= loopedStore.writes,
                    "a batch of $BATCH must cost at least ${MIN_AMORTISATION}x fewer writes than the loop; " +
                        "batched=${batchedStore.writes} looped=${loopedStore.writes}",
                )
            },
            // Not just "fewer" — the loop's own cost is the two-writes-per-record shape.
            { assertTrue(loopedStore.writes >= BATCH, "the per-record path pays at least one write per record") },
        )
    }

    /**
     * `accepted` documents itself as "records durably taken", so a batch of k must
     * move it by k — not by one, and not by the number of calls.
     */
    @Test
    fun healthCountsRecordsTakenNotCallsMade() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        exporter.export(records(BATCH))

        assertEquals(BATCH.toLong(), exporter.health.value.accepted)
    }

    /**
     * Dedup and the buffer cap are per-record decisions and stay that way inside a
     * batch: a repeat of an already-exported id is skipped, and neither a skip nor a
     * refusal counts towards `accepted`.
     */
    @Test
    fun aBatchDedupesWithinItselfAndAgainstWhatWasAlreadyExported() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        exporter.export(record(0))
        exporter.export(listOf(record(0), record(1), record(1), record(2)))

        assertAll(
            { assertEquals(listOf("event 0", "event 1", "event 2"), exporter.snapshot().toList().map { it.body }) },
            { assertEquals(3L, exporter.health.value.accepted, "the two duplicates must not count as taken") },
        )
    }

    /**
     * A turn that admits nothing must write nothing.
     *
     * The single-record path returns early on a dedup hit, so a re-export costs zero
     * store writes — `export()`'s KDoc says so outright. A batched turn that ran
     * `pendingWrites` unconditionally would instead rewrite the whole active segment
     * (~123 KB at the production `segmentOps`) for a pure no-op, which is what an
     * anti-entropy caller re-exporting an already-exported page does on every pass.
     */
    @Test
    fun anAllDuplicateBatchWritesNothingAtAll() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
        exporter.export(records(BATCH))
        val writesAfterFirstPass = store.writes

        exporter.export(records(BATCH))

        assertAll(
            { assertEquals(writesAfterFirstPass, store.writes, "a re-export of the same records must write nothing") },
            { assertEquals(BATCH.toLong(), exporter.health.value.accepted) },
        )
    }

    /**
     * Same property on the refusal path, and the one that bites hardest: a
     * `DROP_NEWEST` exporter at a full buffer accepts nothing ever again, so an
     * unconditional segment write would burn flash on every drain cycle for the life of
     * the process.
     */
    @Test
    fun aFullDropNewestBufferWritesNothingWhenItRefusesEverything() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            store,
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP))
        val writesWhileFilling = store.writes

        exporter.export(records(CAP + EXTRA).drop(CAP))

        assertEquals(writesWhileFilling, store.writes, "a wholly-refused turn must not rewrite the segment")
    }

    /**
     * A refused write must not cost the records the turn never got to.
     *
     * A failing turn keeps its own records — they are in `log` and `activeSegment`
     * before the write is attempted, so the next successful segment write carries them.
     * Abandoning the rest of the batch would lose records that looping the
     * single-record overload keeps, and a quota-bound store that refuses the large
     * segment write while accepting small ones holds that condition indefinitely.
     */
    @Test
    fun aFailedTurnDoesNotDiscardTheRestOfTheBatch() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = FailOnceStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)

        val result = exporter.export(records(BATCH))

        assertAll(
            { assertTrue(result is ExportResult.Failure, "the refused write must be reported") },
            {
                assertEquals(
                    records(BATCH).map { it.body },
                    exporter.snapshot().toList().map { it.body },
                    "every record in the batch must still be in the log, awaiting the next write",
                )
            },
        )
    }

    @Test
    fun aBatchLargerThanTheBufferEvictsTheOldestAndKeepsTheCap() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP))
        exporter.export(records(CAP + EXTRA).drop(CAP))

        assertAll(
            { assertEquals(CAP, exporter.snapshot().toList().size) },
            {
                assertEquals(
                    (CAP until CAP + EXTRA).map { "event $it" }.takeLast(CAP),
                    exporter.snapshot().toList().map { it.body }.takeLast(minOf(CAP, EXTRA)),
                )
            },
        )
    }

    @Test
    fun aBatchUnderDropNewestRefusesTheOverflowAndInsertsNoOpForIt() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            InMemoryDurableStore(),
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP + EXTRA))

        assertAll(
            { assertEquals(records(CAP).map { it.body }, exporter.snapshot().toList().map { it.body }) },
            { assertEquals(CAP.toLong(), exporter.health.value.accepted) },
            // DROP_NEWEST never authors a Remove — the op-log stays a downward-closed
            // prefix of this replica's own inserts (#2127).
            { assertEquals(0, exporter.snapshot().tombstones.size) },
        )
    }

    /**
     * A turn may not overflow the active segment, because `segmentOps` is documented
     * as the ceiling on how many bytes one export rewrites. A batch bigger than the
     * segment is split across turns rather than growing one segment past its budget.
     */
    @Test
    fun aBatchBiggerThanASegmentIsSplitAcrossTurnsRatherThanOverfillingOne() =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val store = InMemoryDurableStore()
            val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
            exporter.export(records(SEGMENT_OPS * 2))

            val recovered = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
            recovered.recover()

            assertAll(
                { assertEquals(SEGMENT_OPS * 2, exporter.snapshot().toList().size) },
                {
                    assertEquals(
                        exporter.snapshot().toList().map { it.body },
                        recovered.snapshot().toList().map { it.body },
                        "everything a split batch wrote must survive a restart",
                    )
                },
            )
        }

    @Test
    fun anEmptyBatchIsSuccessAndWritesNothing() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store)

        assertAll(
            { assertEquals(ExportResult.Success, exporter.export(emptyList())) },
            { assertEquals(0, store.writes) },
            { assertEquals(0L, exporter.health.value.accepted) },
        )
    }

    private companion object {
        private const val RECORD_ID_BYTES = 8

        /** Small enough for wasmJs (#2183 — never thousands of exports in this module). */
        private const val BATCH = 40

        /** Two segments' worth at [SEGMENT_OPS], so the split path runs. */
        private const val SEGMENT_OPS = 16

        /** A buffer small enough that one batch overruns it. */
        private const val CAP = 8

        private const val EXTRA = 5

        /**
         * The floor the batched path must beat. Deliberately far below the ~128x a
         * production `segmentOps` gives: this asserts the *shape* (per-turn, not
         * per-record) on a deliberately tiny segment, not a tuning number.
         */
        private const val MIN_AMORTISATION = 4
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterBatchTest*"`
Expected: FAIL — `None of the following functions can be called with the arguments supplied` on `export(records(BATCH))`.

- [ ] **Step 3: Replace the single-record turn with the batched one**

In `WarpLogRecordExporter.kt`:

**3a.** Replace the `export`/`exportTurn` pair (currently lines 862–919) with:

```kotlin
    /**
     * Export one log record — the degenerate one-element case of [export].
     *
     * See that overload for the full contract; nothing about durability differs.
     * A one-record call still returns after its own durable write.
     */
    public suspend fun export(record: LogRecord): ExportResult = export(listOf(record))

    /**
     * Export a **batch** of log records: append them to the [Rga] and durably flush
     * to [store] as one write turn.
     *
     * Returns [ExportResult.Success] after the durable write, exactly as the
     * single-record overload does — **the durability contract is unchanged**. This is
     * not OpenTelemetry's `BatchLogRecordProcessor`, which trades a flush window for
     * amortisation; nothing here is held back in the hope more arrives. A batch is
     * whatever the caller already has in hand, and it is written before this returns.
     * What is amortised is the *fixed* cost of a turn — one CRDT append pass, one CBOR
     * encode of the active segment, one segment write — across however many records the
     * caller supplied, instead of paying it once per record (#2194).
     *
     * Records are admitted **in order**, and dedup and the buffer cap stay per-record
     * decisions: a [LogRecord.recordId] already exported (including earlier in this
     * same batch, and including across restarts after [recover]) is skipped, and a
     * record refused by the cap under [BufferPolicy.DROP_NEWEST] is not inserted.
     * Neither counts towards [ExporterHealth.accepted], which means *records durably
     * taken*.
     *
     * ## A batch may span more than one turn
     *
     * [segmentOps] bounds how many bytes one export rewrites, so a batch that would
     * overfill the active segment is split: each turn takes as many records as still
     * fit, writes, rolls, and the next turn continues. (The bound is *approximate* by
     * exactly one op: a [windowPass] runs after the records are admitted and can `piece`
     * one [RgaOp.Compact] into the active segment outside the turn's budget, so a turn
     * can end at `segmentOps + 1` before the roll seals it. That overshoot exists on the
     * per-record path today, and is bounded at one op either way.)
     *
     * Splitting means the batch is **not atomic**: an earlier turn's records are durable
     * even if a later one fails.
     *
     * **Every turn is attempted, even after one fails.** A failing turn has already
     * admitted its records to the in-memory log and to [activeSegment] before its write
     * was refused, so those records are *not* lost — the next successful
     * active-segment write carries them to disk (the same property
     * [retirableSegments] relies on). Abandoning the remainder of a batch would
     * therefore lose records that looping the single-record overload keeps: a
     * quota-bound store that refuses the ~123 KB segment write while accepting small
     * ones would drop everything after the first failed turn, permanently, on every
     * batch, for as long as the condition lasts. So the loop runs to the end and the
     * **first** [ExportResult.Failure] is returned once it does.
     *
     * A turn also never admits more than [maxRecords] records, so the eviction it
     * computes is always a prefix of what the buffer already held.
     *
     * An empty [records] is [ExportResult.Success] and touches neither the log nor the
     * store.
     *
     * **Never throws**, on the same terms as the single-record overload.
     *
     * @sample us.tractat.kuilt.otel.sampleBulkExport
     */
    public suspend fun export(records: List<LogRecord>): ExportResult {
        var from = 0
        var firstFailure: ExportResult.Failure? = null
        while (from < records.size) {
            val outcome = writeMutex.withLock { exportTurn(records, from) }
            val result = outcome.result
            if (firstFailure == null && result is ExportResult.Failure) firstFailure = result
            from += outcome.consumed
        }
        return firstFailure ?: ExportResult.Success
    }

    /** How far one [exportTurn] got, and how it ended. */
    private class TurnOutcome(val consumed: Int, val result: ExportResult)

    /**
     * One write turn over `records[from until …]`: decide the run, mutate the log
     * twice, build the turn's actions, then apply them. Must hold [writeMutex].
     *
     * Always consumes **at least one** record — otherwise [export]'s loop would not
     * terminate — even when the active segment is already at or past [segmentOps],
     * which a [recover] against a store written with a larger [segmentOps] can produce.
     */
    private suspend fun exportTurn(records: List<LogRecord>, from: Int): TurnOutcome {
        var consumed = 0
        var accepted = 0
        val actions = runCatchingCancellable {
            lock.withLock {
                val admitted = ArrayList<LogRecord>()
                // Dedup within the batch as well as against the log: `seenIds` is not
                // updated until the inserts are minted below, so two copies of one id
                // inside a single batch would otherwise both be admitted.
                val pending = HashSet<ByteString>()
                var evictions = 0
                while (from + consumed < records.size && fitsInTurn(consumed, evictions)) {
                    val record = records[from + consumed]
                    consumed++
                    if (record.recordId in seenIds || !pending.add(record.recordId)) continue
                    if (visibleCount + admitted.size - evictions < maxRecords) {
                        admitted += record
                        continue
                    }
                    when (bufferPolicy) {
                        BufferPolicy.DROP_NEWEST -> {
                            refuse(record)
                            pending.remove(record.recordId)
                        }
                        BufferPolicy.DROP_OLDEST -> {
                            evictions++
                            admitted += record
                        }
                    }
                }
                accepted = admitted.size
                applyTurn(admitted, evictions)
                // Before pendingWrites(), never after: a pass rewrites `activeSegment`,
                // and the active-segment write pendingWrites() already owes is what
                // carries the resulting floor to disk.
                val windowed = windowPassDue() && windowPass()
                // A turn that changed nothing owes nothing. The single-record path
                // returns EARLY on a dedup hit and on a DROP_NEWEST refusal, so both
                // cost ZERO store writes — a property export()'s KDoc states outright,
                // and one a batched turn silently drops unless it is restored here.
                // Without this guard a DROP_NEWEST exporter at a full buffer rewrites
                // the whole ~123 KB active segment on every drain cycle while accepting
                // nothing, forever; and an anti-entropy caller re-exporting an
                // already-exported page pays a segment rewrite per turn for a no-op.
                if (admitted.isEmpty() && evictions == 0 && !windowed) {
                    emptyList()
                } else {
                    pendingWrites(retire = windowed)
                }
            }
        }.getOrElse { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: buffer update failed for a batch of ${records.size - from} record(s) " +
                    "starting at ${records.getOrNull(from)?.recordId}"
            }
            return TurnOutcome(consumed = maxOf(consumed, 1), result = failure(cause))
        }
        // Nothing to write, so nothing to report: an all-dedup or all-refused turn is
        // Success with no durable write and no movement on `accepted`, exactly as the
        // single-record path's two early returns are.
        if (actions.isEmpty()) return TurnOutcome(consumed = consumed, result = ExportResult.Success)
        val result = commit(actions) { cause ->
            logger.error(cause) {
                "WarpLogRecordExporter: durable write failed for a batch of $accepted record(s)"
            }
        }
        return TurnOutcome(
            consumed = consumed,
            result = if (result is ExportResult.Success) success(accepted) else result,
        )
    }

    /**
     * Whether a turn that has already taken [consumed] records and owes [evictions]
     * tombstones may take one more without breaching [segmentOps]. Must hold [lock].
     *
     * A record costs at most two ops in the active segment — its `Insert`, plus the
     * `Remove` of whatever it evicted — so the bound is checked against the worst case
     * rather than the actual eviction count, which is not known until the record has
     * been admitted. That makes the turn slightly conservative while the buffer is
     * still filling (no evictions yet, so the segment ends half-full and the next turn
     * continues into it); it never makes it wrong.
     *
     * Also caps a turn at [maxRecords] records, which is what lets [applyTurn] evict a
     * *prefix* of the existing buffer rather than having to evict records the same turn
     * just inserted. That cap is what makes [Rga.removeFirst]'s `require` unreachable
     * from here: the last eviction a turn can owe needs `admitted >= maxRecords - 1`
     * while `consumed < maxRecords`, which forces the eviction count to stop exactly at
     * the pre-turn [visibleCount].
     *
     * **The bound is on the record-driven ops only.** A [windowPass] runs *after* the
     * records are admitted and `piece`s its delta into [activeSegment], which can mint
     * one [RgaOp.Compact] outside this budget — so a turn can end at `segmentOps + 1`
     * before [flushActiveSegment] rolls it. That overshoot is bounded at one op and
     * exists identically on the per-record path today; it is named here so the
     * "[segmentOps] is a ceiling" claim is not read as exact.
     */
    private fun fitsInTurn(consumed: Int, evictions: Int): Boolean {
        if (consumed >= maxRecords) return false
        if (consumed == 0) return true
        return activeOpCount + consumed + evictions + OPS_PER_RECORD <= segmentOps
    }

    /**
     * Apply one turn's decisions to [log] in **two** CRDT mutations — the whole point
     * of the batch. Must hold [lock].
     *
     * Eviction runs first and always removes a prefix of what the buffer already held,
     * so nothing this turn inserts can be evicted by it ([fitsInTurn] caps the turn at
     * [maxRecords]). Both halves go through [Rga]'s bulk mutators, so each pays one
     * `ops + newOps` copy and one cache build for the run instead of one per record,
     * and the RGA sequence is materialised once rather than once per eviction.
     *
     * ## Two ways this is not *bit*-identical to the per-record loop
     *
     * Both are unreachable at the production [DEFAULT_MAX_LOG_RECORDS] and neither
     * changes the visible sequence, but "indistinguishable from the loop" is exact at
     * the [Rga] level and only *observationally* exact here, so they are written down.
     *
     * - **Evictions that empty the buffer re-root the run.** When a turn's evictions
     *   take the whole pre-turn buffer, [evictLeading] sets `tail = RgaId.HEAD` before
     *   the inserts, so the run chains after HEAD where the loop would have chained
     *   after the tombstoned predecessor. Same visible order, different `after` links in
     *   the op-log. Reachable only when a turn is as large as the buffer, i.e.
     *   `maxRecords` at or below a turn's size — test-scale configuration, not the
     *   10,000-record default.
     * - **A record re-arriving in the same turn that evicts it is skipped.** [seenIds]
     *   is read at admission time, before this function runs, so a batch containing a
     *   record whose id the same turn is about to evict finds it still present and skips
     *   it; the loop would evict first and then re-admit. Contrived, and arguably the
     *   better answer.
     */
    private fun applyTurn(admitted: List<LogRecord>, evictions: Int) {
        if (evictions > 0) evictLeading(evictions, admitted)
        if (admitted.isEmpty()) return
        val (newLog, inserts) = log.insertAllAfter(replica = replica, after = tail, values = admitted)
        log = newLog
        tail = inserts.last().id
        visibleCount += inserts.size
        inserts.forEach { insert -> seenIds[insert.value.recordId] = insert.id }
        appendToActiveSegment(inserts)
    }

    /**
     * Tombstone the [count] oldest visible records, logging each. Must hold [lock].
     *
     * Every drop is logged individually — the class KDoc promises an audit trail
     * detailed enough to correlate against a backend's log index, and a batch changes
     * how many evictions happen per turn, not how many records are lost.
     *
     * The entries are read **before** the removal, off the instance whose [Rga.sequence]
     * `removeFirst` is about to walk, so the lazy is computed once for both.
     */
    private fun evictLeading(count: Int, admitted: List<LogRecord>) {
        val evicted = log.entries().take(count)
        // Each eviction is still paired with the record that displaced it, so the audit
        // line keeps the `incoming recordId=` correlation the per-record path had. The
        // first `maxRecords - visibleCount` admissions fit without evicting, so the
        // displacing record is counted from the END of the admitted run.
        val firstDisplacer = admitted.size - count
        evicted.forEachIndexed { index, (_, record) ->
            logger.warn {
                "WarpLogRecordExporter: buffer cap ($maxRecords) reached, evicting record " +
                    "recordId=${record.recordId} body=${record.body?.take(EVICTION_BODY_CHARS)} " +
                    "policy=$bufferPolicy (incoming recordId=${admitted[firstDisplacer + index].recordId})"
            }
        }
        val (newLog, removes) = log.removeFirst(count)
        log = newLog
        // The tombstones are ops like any other, so they ride in the active segment.
        // They hide the records; they reclaim nothing by themselves. Each evicted
        // record's own `Insert` — body and all — stays in whichever segment it landed
        // in until a window pass suppresses it and that segment is retired.
        appendToActiveSegment(removes)
        evicted.forEach { (_, record) -> seenIds.remove(record.recordId) }
        visibleCount -= count
        evictionsSincePass += count
        // DROP_OLDEST removes a leading prefix of the visible sequence. With at least
        // one element still standing the first and last visible elements are distinct,
        // so `tail` is untouched; at zero there is nothing left to append after.
        if (visibleCount == 0) tail = RgaId.HEAD
    }

    /** Log a [BufferPolicy.DROP_NEWEST] refusal. Must hold [lock]. */
    private fun refuse(incoming: LogRecord) {
        logger.warn {
            "WarpLogRecordExporter: buffer cap ($maxRecords) reached, refusing incoming record " +
                "recordId=${incoming.recordId} body=${incoming.body?.take(EVICTION_BODY_CHARS)} policy=$bufferPolicy"
        }
    }
```

**3b.** Replace `appendToActiveSegment(op: RgaOp<LogRecord>)` (line 1229) with the bulk form:

```kotlin
    /** Absorb a run of ops into the active segment. Must hold [lock]. */
    private fun appendToActiveSegment(ops: List<RgaOp<LogRecord>>) {
        if (ops.isEmpty()) return
        ops.forEach { op -> activeSegment = activeSegment.apply(op) }
        activeOpCount += ops.size
    }
```

> The per-op `apply` loop stays: the active segment is bounded at [segmentOps] ops, so it is Θ(segmentOps) not Θ(N), and `apply` handles `Insert` and `Remove` uniformly where the bulk mutators do not. Replacing it with a bulk `applyAll` is a real but small further win; it is out of scope here and belongs with #2193's measurement.

**3c.** Delete `admit` and `evictOldest` (lines 1449–1488) — `exportTurn` and `evictLeading` replace them. Keep the `admit` KDoc's substance by moving its two load-bearing paragraphs (the `DROP_NEWEST` downward-closed-prefix argument, and "the `when` is exhaustive on purpose") onto `exportTurn`'s policy `when`.

**3d.** Move success-reporting **out of `commit`** and give `accepted` a decided meaning.

This is not optional bookkeeping: `commit` currently reports success itself, in its `onSuccess` fold (`WarpLogRecordExporter.kt:1026–1029`). Leaving it there *and* calling `success(accepted)` in `exportTurn` moves `accepted` by `admitted + 1` per turn.

There is also a contradiction to settle rather than inherit. `export()`'s KDoc says `accepted` means "records durably taken"; `ExporterHealth.accepted`'s own KDoc says the opposite — *"Durable writes that **succeeded**. Counts writes, not `Success` returns"*. Under the current code one export is one record is one write, so the two coincide by accident. Batching breaks the coincidence and forces a choice.

**Decision: `accepted` counts records taken through the export admission path.** That is the question a consumer actually has ("is this device's telemetry landing?"), and it is what `export()` already promises. The store-health fields stay about the store. Concretely:

- `commit`'s `onSuccess` arm drops its `success()` call and returns `ExportResult.Success` plainly. Its `onFailure` arm is **unchanged** — `failure(cause)` still reports there, because a failed write is a store fact regardless of who called.
- `exportTurn` reports, with the count (already wired above).
- `mergeTurn` reports through a new `storeSucceeded()`, because a merge takes **no** records through admission and must not inflate `accepted` — while a successful merge write is still evidence the store is up:

```kotlin
    /** Record a successful durable write of [records] records and return [ExportResult.Success]. */
    private fun success(records: Int): ExportResult {
        healthState.update { it.copy(accepted = it.accepted + records, consecutiveFailures = 0) }
        return ExportResult.Success
    }

    /**
     * Record that the store accepted a write that carried **no admitted records** — the
     * [merge] path. Clears [ExporterHealth.consecutiveFailures] without touching
     * [ExporterHealth.accepted].
     *
     * The split is what keeps `accepted` answering "is this device's own telemetry
     * landing?" on a gossiping replica. A merge writes a whole adopted segment and is
     * real evidence the store is up, so it must clear the failure streak; it takes
     * nothing through admission, so counting it would let a replica that has exported
     * nothing at all report a healthy climbing count.
     */
    private fun storeSucceeded(): ExportResult {
        healthState.update { it.copy(consecutiveFailures = 0) }
        return ExportResult.Success
    }
```

and `mergeTurn`'s tail becomes:

```kotlin
        val result = commit(actions) { cause ->
            logger.error(cause) { "WarpLogRecordExporter: durable write failed during merge" }
        }
        return if (result is ExportResult.Success) storeSucceeded() else result
```

**3d-bis.** Update `ExporterHealth.accepted`'s KDoc to the decided meaning, keeping its existing dedup argument (which survives — a dedup no-op is still not counted):

```kotlin
 * @property accepted Log records **durably taken** by [WarpLogRecordExporter.export],
 *   cumulative. Counts records, not calls and not store writes: one batched export of
 *   256 records moves this by 256, and one export of a record whose id was already
 *   taken moves it by zero — a dedup no-op returns [ExportResult.Success] without
 *   touching the store, so an all-dedup state cannot masquerade as a healthy climbing
 *   count. A record refused by the buffer cap under [BufferPolicy.DROP_NEWEST] is not
 *   counted either, for the same reason. A successful `merge` does not move this at
 *   all — it takes no records through admission — but does clear
 *   [consecutiveFailures], because the store accepting it is evidence the store is up.
```

Add a test to `WarpLogRecordExporterBatchTest` pinning the split, since nothing else would catch a regression to double-counting:

```kotlin
    @Test
    fun aSuccessfulMergeClearsTheFailureStreakWithoutCountingRecords() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val remote = WarpLogRecordExporter(ReplicaId("device-2"), InMemoryDurableStore())
        remote.export(records(3))

        exporter.merge(remote.snapshot())

        assertAll(
            { assertEquals(0L, exporter.health.value.accepted, "a merge takes no records through admission") },
            { assertEquals(3, exporter.snapshot().toList().size, "…but its records are in the log") },
            { assertEquals(0, exporter.health.value.consecutiveFailures) },
        )
    }
```

**3e.** Add the two constants to the private companion:

```kotlin
        /**
         * The most ops one record can add to the active segment: its `Insert`, plus the
         * `Remove` of the record it evicted under [BufferPolicy.DROP_OLDEST].
         */
        private const val OPS_PER_RECORD = 2

        /** How much of an evicted record's body the audit line carries. */
        private const val EVICTION_BODY_CHARS = 80
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterBatchTest*"`
Expected: PASS, 12 tests.

- [ ] **Step 5: Run the whole existing `:kuilt-otel` suite — the regression surface is large**

Run: `./gradlew :kuilt-otel:jvmTest`
Expected: PASS. Pay attention to `WarpLogRecordExporterTest`, `WarpLogRecordExporterSegmentTest`, `WarpLogRecordExporterWindowingTest`, `WarpLogRecordExporterRetirementTest`, `WarpLogRecordExporterTailCacheTest`, `WarpLogRecordExporterConcurrencyTest`, `WarpExporterSilentDeathTest` — all of these drive `export(record)`, which now goes through the batched path with a one-element list.

If `WarpLogRecordExporterConcurrencyTest.anExportDoesNotBuildItsBatchWhileAnotherExportsCommitIsInFlight` fails, do **not** widen it: it pins that the whole turn is under `writeMutex`, and the batched `export` acquires the mutex per *turn*, which is the same property. A failure means the loop is acquiring outside the turn or building outside the lock.

- [ ] **Step 6: Run each new test alone**

```bash
for t in aBatchIsObservationallyIdenticalToExportingOneAtATime \
         aBatchPaysWritesPerTurnRatherThanPerRecord \
         healthCountsRecordsTakenNotCallsMade \
         aSuccessfulMergeClearsTheFailureStreakWithoutCountingRecords \
         aBatchDedupesWithinItselfAndAgainstWhatWasAlreadyExported \
         anAllDuplicateBatchWritesNothingAtAll \
         aFullDropNewestBufferWritesNothingWhenItRefusesEverything \
         aFailedTurnDoesNotDiscardTheRestOfTheBatch \
         aBatchLargerThanTheBufferEvictsTheOldestAndKeepsTheCap \
         aBatchUnderDropNewestRefusesTheOverflowAndInsertsNoOpForIt \
         aBatchBiggerThanASegmentIsSplitAcrossTurnsRatherThanOverfillingOne \
         anEmptyBatchIsSuccessAndWritesNothing; do
  ./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterBatchTest.$t" || echo "FAILED ALONE: $t"
done
```

Expected: every one passes alone.

- [ ] **Step 7: Prove the tests catch the bug — revert and confirm red**

```bash
git stash push kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt
```

That will not compile (the test calls the new overload), which is not a useful red. Instead, temporarily make `export(records)` a loop over the single-record path:

```kotlin
    public suspend fun export(records: List<LogRecord>): ExportResult {
        records.forEach { record ->
            val result = writeMutex.withLock { exportTurn(listOf(record), 0) }.result
            if (result is ExportResult.Failure) return result
        }
        return ExportResult.Success
    }
```

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterBatchTest.aBatchPaysWritesPerTurnRatherThanPerRecord"`
Expected: **FAIL** — `a batch of 40 must cost at least 4x fewer writes than the loop`. Then restore the real implementation and re-run to green.

- [ ] **Step 8: Lint and commit**

```bash
./gradlew :kuilt-otel:build detektAll
git add kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt \
        kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterBatchTest.kt
git commit -m "feat(otel): a batched write turn — export(List<LogRecord>)

Part of #2194. Capturing one log line cost one Theta(N) CRDT append, one CBOR
encode of the active segment and TWO durable file writes, synchronously, per
record — a ~9 ms floor on an iPhone XS that never amortised.

One turn now admits a run of records, mutates the log twice (one bulk evict, one
bulk append), encodes the active segment once and writes it once. Durability is
unchanged: nothing is held back, and export still returns after its own durable
write. A batch that would overfill the active segment is split across turns so
segmentOps stays the ceiling it claims to be.

ExporterHealth.accepted now moves by the number of records a turn took, which is
what it has always documented itself as counting."
```

---

### Task 4: `LogCapture.captureAll`

**Files:**
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/LogCapture.kt`
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/LogCaptureBatchTest.kt` (create)

**Interfaces:**
- Consumes: `WarpLogRecordExporter.export(records: List<LogRecord>)` (Task 3).
- Produces: `public suspend fun captureAll(events: List<NormalizedLogEvent>): ExportResult?` — maps each event to a `LogRecord`, dropping the ones `capture` would drop, then makes **one** `export` call. Returns `null` when every event was dropped.

- [ ] **Step 1: Write the failing test**

Create `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/LogCaptureBatchTest.kt`:

```kotlin
package us.tractat.kuilt.otel.logging

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Mapping a run of events must be one export, and must drop exactly what mapping
 * them one at a time drops — the self-capture exclusion and the level gate are
 * per-event decisions and stay that way (#2194).
 */
class LogCaptureBatchTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    private fun event(message: String, logger: String = "com.example.App", level: LogLevel = LogLevel.INFO) =
        NormalizedLogEvent(level = level, loggerName = logger, message = message, attributes = emptyMap())

    private fun capture() = LogCapture(
        WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore()),
        CaptureConfig(),
        fixedClock,
        Random(1),
    )

    @Test
    fun aRunOfEventsBecomesOneExportInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))

        capture.captureAll(listOf(event("a"), event("b"), event("c")))

        assertEquals(listOf("a", "b", "c"), exporter.snapshot().toList().map { it.body })
    }

    /**
     * The self-capture exclusion is an invariant, not a filter: an exporter-owned
     * logger inside a batch must be dropped before a record is built, exactly as it is
     * on the single-event path. Capturing one would feed an eviction warn back into
     * export → evict → warn.
     */
    @Test
    fun aRunDropsTheExportersOwnLoggersAndSubMinLevelEvents() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(minLevel = LogLevel.INFO), fixedClock, Random(1))

        capture.captureAll(
            listOf(
                event("kept"),
                event("internal", logger = "us.tractat.kuilt.otel.WarpLogRecordExporter"),
                event("too quiet", level = LogLevel.DEBUG),
            ),
        )

        assertEquals(listOf("kept"), exporter.snapshot().toList().map { it.body })
    }

    @Test
    fun aRunWithNothingToExportIsNull() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        assertAll(
            { assertNull(capture().captureAll(emptyList())) },
            {
                assertNull(
                    capture().captureAll(listOf(event("internal", logger = "us.tractat.kuilt.otel.X"))),
                )
            },
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*LogCaptureBatchTest*"`
Expected: FAIL — `Unresolved reference: captureAll`.

- [ ] **Step 3: Implement `captureAll` and factor the record mapping out of `capture`**

In `LogCapture.kt`, keep `capture` as-is but have both go through one mapper:

```kotlin
    /**
     * Map a **run** of events to `LogRecord`s and export them as one write turn.
     *
     * The batched counterpart of [capture], and the drain's entry point since #2194:
     * the exporter's fixed per-turn cost — one CRDT append pass, one CBOR encode, one
     * segment write — is paid once for the whole run instead of once per line.
     *
     * Every per-event decision is unchanged and still per-event. The self-capture
     * exclusion, the [CaptureConfig.minLevel] gate and the trace/sampling gate each
     * drop their own events out of the run before any record is built; the survivors
     * are exported together. Durability is unchanged — nothing is held back waiting
     * for the run to grow (see `WarpLogRecordExporter.export`).
     *
     * Returns `null` when the run produced no records at all — either it was empty or
     * every event was dropped — so a caller can tell "nothing to do" from an export
     * result.
     */
    public suspend fun captureAll(events: List<NormalizedLogEvent>): ExportResult? {
        val records = events.mapNotNull { event -> recordFor(event) }
        if (records.isEmpty()) return null
        return exporter.export(records)
    }
```

Then rewrite `capture` as:

```kotlin
    public suspend fun capture(event: NormalizedLogEvent): ExportResult? {
        val record = recordFor(event) ?: return null
        return exporter.export(record)
    }
```

…and move the whole body of the old `capture` (the gate + the `LogRecord` construction, lines from `if (droppedBeforeRecord(event)) return null` down to the `LogRecord(...)` literal) into:

```kotlin
    /**
     * The `LogRecord` [event] produces, or `null` if it is dropped before one is built.
     *
     * The single decision point shared by [capture] and [captureAll], so a per-event
     * gate cannot come to mean two different things on the two paths. Every gate here
     * reads the values [resolveAtEdge] snapshotted on the caller — never the ambient
     * provider or the mapper — so emit-time semantics survive the drain (#1034, #1630).
     */
    private fun recordFor(event: NormalizedLogEvent): LogRecord? {
        // …the old capture() body verbatim, with `return null` unchanged and
        // `return exporter.export(record)` replaced by `return record`.
    }
```

Keep the existing KDoc on `capture` and add one line pointing at [captureAll].

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*LogCaptureBatchTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the existing suite and each new test alone**

```bash
./gradlew :kuilt-otel-logging:jvmTest
for t in aRunOfEventsBecomesOneExportInOrder \
         aRunDropsTheExportersOwnLoggersAndSubMinLevelEvents \
         aRunWithNothingToExportIsNull; do
  ./gradlew :kuilt-otel-logging:jvmTest --tests "*LogCaptureBatchTest.$t" || echo "FAILED ALONE: $t"
done
```

Expected: all green. The trace-gate tests (`LogCaptureTraceTest` and friends) must be untouched — `recordFor` is the old body verbatim.

- [ ] **Step 6: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/LogCapture.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/LogCaptureBatchTest.kt
git commit -m "feat(otel-logging): LogCapture.captureAll — one export for a run of events

Part of #2194. Both paths now build their record through one private recordFor(),
so the self-capture exclusion, the minLevel gate and the trace gate cannot come to
mean different things on the batched path than on the single-event one."
```

---

### Task 5: The opportunistic drain

**Files:**
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CapturingAppender.kt`
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CaptureHealth.kt` (add `CAPTURE_BATCH_MAX`)
- Test: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBatchingTest.kt` (create)

**Interfaces:**
- Consumes: `LogCapture.captureAll` (Task 4).
- Produces:
  - `public const val CAPTURE_BATCH_MAX: Int = 256`
  - `CapturingAppender(capture, delegate, scope, capacity, maxBatchSize)` — a fifth constructor parameter, defaulted, test-only like `capacity`.

- [ ] **Step 1: Write the failing test — this is the #2194 regression test**

Create `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBatchingTest.kt`:

```kotlin
package us.tractat.kuilt.otel.logging

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.DurableStore
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.StoreKey
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The drain must export what is **already queued** as one turn, not one turn per
 * event (#2194).
 *
 * Capturing one log line cost two durable file writes; a burst of N lines cost 2N,
 * which is why `CapturingAppender` had to warn that "the exporter is draining slower
 * than this application logs". These pin the amortisation, and pin that it is
 * opportunistic — a lone line on an idle app is still exported immediately, so no
 * durability window is introduced.
 */
class CapturingAppenderBatchingTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds = 1L, nanosecondAdjustment = 0)
    }

    private class CountingStore(private val delegate: DurableStore = InMemoryDurableStore()) : DurableStore {
        var writes: Int = 0
            private set

        override suspend fun read(key: StoreKey): ByteArray? = delegate.read(key)

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            writes++
            delegate.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey): Unit = delegate.delete(key)
    }

    private class RecordingAppender : Appender {
        val logged: MutableList<KLoggingEvent> = mutableListOf()

        override fun log(loggingEvent: KLoggingEvent) {
            logged += loggingEvent
        }
    }

    private fun appEvent(message: String) = KLoggingEvent(
        level = Level.INFO,
        marker = null,
        loggerName = "com.example.App",
        message = message,
        timestamp = 0L,
    )

    private fun withGlobalCapture(appender: CapturingAppender, body: () -> Unit) {
        val outerFactory = KotlinLoggingConfiguration.loggerFactory
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val outerAppender = KotlinLoggingConfiguration.direct.appender
        KotlinLoggingConfiguration.direct.appender = appender
        try {
            body()
        } finally {
            KotlinLoggingConfiguration.direct.appender = outerAppender
            KotlinLoggingConfiguration.loggerFactory = outerFactory
        }
    }

    /**
     * The headline. `log()` never suspends, so the whole burst is queued before the
     * drain gets a turn — which is precisely the overload condition the batch exists
     * for, reached without any dependence on scheduling luck.
     */
    @Test
    fun aBurstIsDrainedAsOneTurnRatherThanOnePerEvent() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val store = CountingStore()
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), store, segmentOps = SEGMENT_OPS)
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertAll(
            { assertEquals(BURST, exporter.snapshot().toList().size, "every event must still be exported") },
            { assertEquals(0L, appender.health.value.droppedEvents, "the queue is deep enough for this burst") },
            {
                assertTrue(
                    store.writes < BURST,
                    "a burst of $BURST must cost fewer than $BURST writes; got ${store.writes}",
                )
            },
        )
    }

    /**
     * The other half of "opportunistic": with nothing else queued, one line is one
     * export. No timer holds it back, so the durability contract is unchanged — which
     * is the whole reason this is not OTel's `BatchLogRecordProcessor`.
     */
    @Test
    fun aLoneEventIsExportedWithoutWaitingForCompany() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            appender.log(appEvent("alone"))
            testScheduler.runCurrent()
        }

        assertEquals(listOf("alone"), exporter.snapshot().toList().map { it.body })
    }

    /** A batch is capped, so one turn's memory and one segment write stay bounded. */
    @Test
    fun aBurstBiggerThanTheCapIsDrainedInSeveralTurns() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(
            capture,
            RecordingAppender(),
            backgroundScope,
            capacity = QUEUE,
            maxBatchSize = SMALL_BATCH,
        )

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertEquals(BURST, exporter.snapshot().toList().size)
    }

    @Test
    fun everyEventInABurstSurvivesInOrder() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore())
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.runCurrent()
        }

        assertEquals(List(BURST) { "event $it" }, exporter.snapshot().toList().map { it.body })
    }

    private companion object {
        /** Deep enough that the burst below never overflows — drops are the other test's subject. */
        private const val QUEUE = 128

        /** Small enough for wasmJs (#2183); large enough that per-event writes are obvious. */
        private const val BURST = 40

        private const val SEGMENT_OPS = 32

        /** Forces several turns for [BURST]. */
        private const val SMALL_BATCH = 7
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBatchingTest*"`
Expected: FAIL — `aBurstIsDrainedAsOneTurnRatherThanOnePerEvent` reports `a burst of 40 must cost fewer than 40 writes; got 80`, and `aBurstBiggerThanTheCapIsDrainedInSeveralTurns` fails to compile (`maxBatchSize`).

- [ ] **Step 3: Add `CAPTURE_BATCH_MAX`**

In `CaptureHealth.kt`, after `CAPTURE_QUEUE_CAPACITY`:

```kotlin
/**
 * The most events one drain turn hands to the exporter at once.
 *
 * The drain does not wait for a batch to form: it blocks for the first event and then
 * takes whatever is **already queued**, up to this many. So a batch only grows when
 * the application is outrunning the drain — which is exactly the condition worth
 * amortising — and a lone log line on an idle app is exported immediately, with the
 * same durability it always had. Nothing is held back, so there is no flush window to
 * lose on a crash.
 *
 * ## Why a cap at all, and why this number
 *
 * - **One turn's memory.** The batch is materialised as a list of `LogRecord`s before
 *   it is exported, so the cap bounds what one turn holds beyond the queue itself.
 * - **One segment's bytes.** `WarpLogRecordExporter` splits a batch that would overfill
 *   the active segment, so a cap far above `DEFAULT_LOG_SEGMENT_OPS` buys nothing —
 *   the exporter would split it straight back down. Matching that order of magnitude
 *   keeps the two from arguing.
 * - **Diminishing returns.** The fixed per-turn cost is divided by the batch size, so
 *   the difference between 256 and 1024 is the difference between paying 0.4% and 0.1%
 *   of it. The queue depth ([CAPTURE_QUEUE_CAPACITY]) is what absorbs a burst; this is
 *   only how much of it is swallowed per turn.
 */
public const val CAPTURE_BATCH_MAX: Int = 256
```

- [ ] **Step 4: Replace the drain**

In `CapturingAppender.kt`, add the parameter:

```kotlin
    private val capacity: Int = CAPTURE_QUEUE_CAPACITY,
    private val maxBatchSize: Int = CAPTURE_BATCH_MAX,
```

with the KDoc line:

```
 * @param maxBatchSize the most events one drain turn exports at once. Defaults to
 *   [CAPTURE_BATCH_MAX]; overridden only by tests, which need several turns cheaply.
```

Replace the `init` block (lines 94–104) with:

```kotlin
    init {
        require(maxBatchSize >= 1) { "maxBatchSize must be at least 1; got $maxBatchSize" }
        scope.launch {
            val batch = ArrayList<NormalizedLogEvent>(maxBatchSize)
            while (true) {
                // Block for the first — no timer, no flush interval. A lone line on an
                // idle app is exported immediately, so batching costs no durability.
                val first = events.receiveCatching().getOrNull() ?: break
                batch += first
                // …then take whatever is ALREADY queued. This only finds company when
                // the application is outrunning the drain, which is precisely the case
                // worth amortising, and it self-equilibrates: a bigger batch drains
                // faster per record, so the queue stops growing.
                while (batch.size < maxBatchSize) {
                    batch += events.tryReceive().getOrNull() ?: break
                }
                // Best-effort: a failed export must never crash the app's logging path,
                // and must never re-log through this same appender (a capture feedback
                // loop), so a failure is dropped. runCatchingCancellable still rethrows
                // CancellationException for clean teardown.
                runCatchingCancellable { capture.captureAll(batch) }
                batch.clear()
            }
        }
    }
```

> `receiveCatching()` rather than `for (event in events)` because the loop now has to take the first element and the rest by different means; `getOrNull() == null` on a closed channel is the same termination `for` gave.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBatchingTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Run each alone, plus the module**

```bash
for t in aBurstIsDrainedAsOneTurnRatherThanOnePerEvent \
         aLoneEventIsExportedWithoutWaitingForCompany \
         aBurstBiggerThanTheCapIsDrainedInSeveralTurns \
         everyEventInABurstSurvivesInOrder; do
  ./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBatchingTest.$t" || echo "FAILED ALONE: $t"
done
./gradlew :kuilt-otel-logging:build detektAll
```

- [ ] **Step 7: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CapturingAppender.kt \
        kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CaptureHealth.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBatchingTest.kt
git commit -m "feat(otel-logging): drain what is already queued, as one export turn

Closes #2194. The drain ran one export per event, so a burst of N log lines cost
2N durable file writes. It now blocks for the first event and takes whatever is
already queued, up to CAPTURE_BATCH_MAX.

Opportunistic, not timed: nothing is held back waiting for a batch to form, so a
lone line on an idle app is exported immediately and the durability contract is
unchanged. A batch only grows when the application is outrunning the drain —
which is the condition worth amortising — and it self-equilibrates there."
```

---

### Task 6: What the overflow guard means now

The guard stays. Its *message* asserted a per-record bottleneck that no longer holds, and the existing tests need to keep proving the bound while no longer implying the drain is per-record.

**Files:**
- Modify: `kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CapturingAppender.kt` (the class KDoc's "#2124" section, and `reportOverflowOnce`'s warn text)
- Modify: `kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBoundedQueueTest.kt`

**Interfaces:**
- Consumes: the batching drain (Task 5).
- Produces: nothing new. `CaptureHealth.droppedEvents` keeps its exact meaning.

- [ ] **Step 1: Run the existing guard tests against the batched drain**

Run: `./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBoundedQueueTest*"`

Expected: **PASS, all three, unchanged.** The burst is fully queued before the drain runs, so the survivor set (the newest `QUEUE`) and the drop count (`BURST - QUEUE`) are unaffected by how the survivors are then drained.

If `aDrainSlowerThanItsProducerCannotGrowTheQueueWithoutBound` fails on timing, the cause is `DRAIN_WINDOW`: batching makes the drain *faster*, never slower, so a failure there means the drain stopped early — investigate, do not widen the window.

- [ ] **Step 2: Restate what an overflow means**

In `reportOverflowOnce`, replace the warn body:

```kotlin
            KotlinLogging.logger(OVERFLOW_LOGGER_NAME).warn {
                "log capture queue overflowed (capacity=$capacity): this application is logging faster than " +
                    "the exporter can drain, even batched, so the oldest captured events are being dropped. " +
                    "Read LogCaptureInstallation.health.value.droppedEvents for the running total."
            }
```

- [ ] **Step 3: Say so in the class KDoc**

In the `## The queue is bounded…` section, replace the sentence that reads *"At the field's measured export cost a Debug build on an A12 against a large store structurally cannot sustain one log line per second (#1860), so this is reachable, not theoretical"* with:

```
 * At the field's measured **per-record** export cost — a ~9 ms floor on a Debug A12
 * that never amortised, plus a growing Θ(N) term (#1860) — a burst structurally could
 * not be drained, so this was reachable at ordinary logging rates. Since #2194 the
 * drain exports what is already queued as one turn, so that fixed cost is divided by
 * the batch and the bound is far harder to reach. It is not gone: the queue is what
 * absorbs a burst the drain cannot swallow in one turn, and an overflow now means the
 * application is outrunning an *amortised* drain — a much stronger signal than it used
 * to be, and one worth acting on rather than tuning away.
```

- [ ] **Step 4: Add the assertion that the guard survives batching**

Append to `CapturingAppenderBoundedQueueTest`:

```kotlin
    /**
     * Batching amortises the drain; it does not make the queue unbounded. A burst
     * larger than the queue still drops the oldest and still counts every drop — the
     * bound is the queue's, not the drain's, and #2194 must not have quietly moved it.
     */
    @Test
    fun batchingTheDrainDoesNotWidenTheQueue() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            SlowStore(InMemoryDurableStore(), writeCost = 1.seconds),
        )
        val capture = LogCapture(exporter, CaptureConfig(), fixedClock, Random(1))
        val appender = CapturingAppender(capture, RecordingAppender(), backgroundScope, capacity = QUEUE)

        withGlobalCapture(appender) {
            repeat(BURST) { appender.log(appEvent("event $it")) }
            testScheduler.advanceTimeBy(DRAIN_WINDOW)
            testScheduler.runCurrent()
        }

        assertAll(
            { assertEquals(QUEUE, exporter.snapshot().toList().size, "the queue's bound is unchanged") },
            { assertEquals((BURST - QUEUE).toLong(), appender.health.value.droppedEvents) },
        )
    }
```

- [ ] **Step 5: Run the suite and the new test alone**

```bash
./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBoundedQueueTest*"
./gradlew :kuilt-otel-logging:jvmTest --tests "*CapturingAppenderBoundedQueueTest.batchingTheDrainDoesNotWidenTheQueue"
```

Expected: PASS, 4 tests; the new one passes alone.

- [ ] **Step 6: Commit**

```bash
git add kuilt-otel-logging/src/commonMain/kotlin/us/tractat/kuilt/otel/logging/CapturingAppender.kt \
        kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CapturingAppenderBoundedQueueTest.kt
git commit -m "docs(otel-logging): an overflow now means outrunning an amortised drain

Part of #2194. The bounded-queue guard (#2124) is unchanged and still pinned; what
changed is what tripping it tells you. The warning used to describe a drain that
paid two file writes per record — that was the bug. Overflowing a batched drain is
a much stronger signal, and one to act on rather than tune away."
```

---

### Task 7: Docs, sample and cookbook

**Files:**
- Modify: `kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt`
- Modify: `kuilt-otel/module.md`, `kuilt-otel-logging/module.md`
- Modify: `docs/agent-cookbook.md`
- Check: `.claude/skills/kuilt-primitives/SKILL.md`

**Interfaces:**
- Consumes: everything above.
- Produces: `internal suspend fun sampleBulkExport()` — referenced by `export(records:)`'s `@sample` tag from Task 3.

- [ ] **Step 1: Add the sample**

Append to `kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt`:

```kotlin
/** @suppress — sample only */
internal suspend fun sampleBulkExport() {
    val exporter = WarpLogRecordExporter(
        replica = ReplicaId("device-uuid-abc123"),
        store = InMemoryDurableStore(),
    )

    // Hand the exporter everything you have. One CRDT append pass, one CBOR encode
    // of the active segment, one segment write — for the whole run, instead of once
    // per record.
    val pending: List<LogRecord> = drainedFromSomeQueue()
    when (val result = exporter.export(pending)) {
        is ExportResult.Success -> Unit // every record above is now durable
        is ExportResult.Failure -> {
            // The store refused. Earlier records in the run may already be durable —
            // a batch too large for one segment is split across turns — so this is
            // "stop", not "none of it landed".
            println("export failed: ${result.cause}")
        }
    }
}
```

Add whatever tiny `private fun drainedFromSomeQueue(): List<LogRecord>` stub the file needs to compile; samples are compiled as part of `commonTest`.

- [ ] **Step 2: Verify the sample compiles**

Run: `./gradlew :kuilt-otel:compileTestKotlinJvm`
Expected: BUILD SUCCESSFUL. A broken `@sample` breaks the build — these are load-bearing.

- [ ] **Step 3: Add the cookbook entry**

In `docs/agent-cookbook.md`, add a symptom→primitive row under the telemetry section (create one if absent):

```markdown
### "capturing logs is slow / my app stalls when it logs a lot"

`WarpLogRecordExporter.export(records)` takes a run of records as one write turn, and
`installLogCapture` already drains into it in batches — you get this without doing
anything. Reach for the bulk overload directly when you hold records yourself:

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleBulkExport -->
```kotlin
    val pending: List<LogRecord> = drainedFromSomeQueue()
    when (val result = exporter.export(pending)) {
        is ExportResult.Success -> Unit // every record above is now durable
        // …
    }
```

Nothing is held back waiting for a batch to form, so durability is unchanged: `export`
returns after its own durable write, just as the single-record overload does.
```

> The `// …` marker asserts an omission and keeps the block `verbatim`; each part must
> be a contiguous, character-for-character run of `sampleBulkExport`, in source order.
> A block using the marker **must** name a `#symbol` — it does.

- [ ] **Step 4: Run the citation check**

Run: `./gradlew verifyDocCitations`
Expected: BUILD SUCCESSFUL (~1 s). If it fails on the new block, **re-copy** rather than relabelling to `condensed from` — relabelling is a one-way door that stops the block ever being content-checked again.

- [ ] **Step 5: Update both `module.md` files**

In `kuilt-otel/module.md`, in the `WarpLogRecordExporter` prose, add one plain-language sentence *before* any type name (accessible-first is enforced):

```
Writing a log line to disk used to cost two file writes, every time. Hand it a
handful of lines at once and it pays that cost once for the lot.
```

In `kuilt-otel-logging/module.md`, add the same idea from the consumer's side: capture batches automatically, and nothing is delayed to make that happen.

- [ ] **Step 6: Confirm the skill still routes**

Run: `grep -n "log capture\|telemetry\|slow" .claude/skills/kuilt-primitives/SKILL.md`

Expected: the `description` already covers the phrasing a developer would use ("my logging is slow", "telemetry stalls"). If it does not, add the trigger phrase — a new primitive with no route is the failure this surface exists to prevent.

- [ ] **Step 7: Full build, cache-disabled**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew build detektAll --rerun-tasks --max-workers=6
```

Expected: BUILD SUCCESSFUL. `--max-workers=6` because an unthrottled `--rerun-tasks` full build drives this box to load 70–96 and is *slower* as well as less reliable. Confirm the test-compile tasks show `EXECUTED`, not `FROM-CACHE`.

- [ ] **Step 8: Commit**

```bash
git add kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt \
        kuilt-otel/module.md kuilt-otel-logging/module.md docs/agent-cookbook.md
git commit -m "docs(otel): document the batched write turn and route it in the cookbook"
```

---

## Verification before the PR leaves draft

- [ ] `./gradlew build detektAll --rerun-tasks --max-workers=6` green, tasks `EXECUTED`.
- [ ] `./gradlew verifyDocCitations` green.
- [ ] Every new test run **alone** and green (the per-task loops above).
- [ ] `./gradlew :kuilt-otel:jvmTest` — the six pre-existing `WarpLogRecordExporter*Test` classes untouched and green.
- [ ] TDD receipt recorded in the PR body: for `aBatchPaysWritesPerTurnRatherThanPerRecord`, the red produced by Task 3 Step 7 (looping the single-record path), quoted.
- [ ] PR body says **`closes #2194`** and carries the AI-attribution prefix.
- [ ] The measurement #2193 is waiting on is reported in a comment on #2193: re-run the probe's arm A/B on device with this branch and say what the per-record cost is now.

## What this deliberately does not do

- **No flush interval / `BatchLogRecordProcessor` timer.** See "Architecture decision 1". Additive later; nothing here forecloses it.
- **No persistent (CHAMP) backing for `Rga`.** That is #2193, and this plan is what makes it re-scopable — after this lands, the question shrinks to "what about a caller genuinely appending one element at a time?".
- **No bulk `Rga.applyAll` for the active segment.** `appendToActiveSegment` still applies op-by-op. It is Θ(`segmentOps`) not Θ(N), so it is bounded and small; measure before touching it.
- **No change to `WarpSpanExporter` or `WarpMetricExporter`.** Spans and metrics are not on the per-line hot path and were not measured. If the review wants symmetry, it is a follow-up issue, not scope creep here.
