# Export-path residual (#2193 + the two cheap wins) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the three measured costs out of `WarpLogRecordExporter`'s steady-state export path that #2194's batching did not touch.

**Architecture:** Three independent changes, cheapest first. Two are local to `:kuilt-otel` (stop narrating every eviction; stop materialising the whole buffer to read its head). The third is `:kuilt-crdt`'s Phase 3B — thread the materialised `sequence` forward in `RgaCache` so a mutation stops leaving the next reader a cold `computeSequence()`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-atomicfu, kotlin-logging (oshai).

**Issues:** Task 1 → [#2218](https://github.com/tractat-us/kuilt/issues/2218) · Task 2 → [#2219](https://github.com/tractat-us/kuilt/issues/2219) · Task 3 → [#2193](https://github.com/tractat-us/kuilt/issues/2193).

## The measurement this comes from

On-device, iPhone XS, Debug K/N, post-#2194, at the production `maxRecords = 10_000`
([#2193 comment](https://github.com/tractat-us/kuilt/issues/2193#issuecomment-5229084972)):

| Term | Share of `export()` | Task |
|---|---|---|
| `computeSequence` recomputation | ~20% | **Task 3** (Phase 3B) |
| op-set copies (`ops + op`) | ~17% | **not this plan** — Phase 3A/CHAMP, a weak case on its own merits |
| `entries()` filter/map in `evictLeading` | ~5% (≈0.18 ms/record) | **Task 2** |
| encode + 2 file writes + **128 eviction WARN logs** + window pass | ~58% | **Task 1** takes the logging out of this |

Whole-path cost is **3.34 ms/record Debug ≈ 0.42 ms Release** at 10,000 ops. Nothing here is a
crisis; all three are cheap, and Task 1 is nearly free.

## Global Constraints

- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public`.
- **`detektAll`, never bare `detekt`** — bare `detekt` is `NO-SOURCE` in this KMP setup and is a false green.
- **Full `./gradlew build detektAll --max-workers=6`** before any merge, never module-scoped: a `:kuilt-otel:build` green skips the `:examples` / `:kuilt-cluster` E2E tests. Unthrottled, `--rerun-tasks` oversubscribes this box and is slower *and* less reliable.
- **Do not drive ~10,000 exports in a `:kuilt-otel` test** — it breaks `wasmJsBrowserTest` with a misleading "did not discover any tests" (#2183). Use small `maxRecords`/`segmentOps` and tens of records.
- **`runTest(timeout = TEST_WEDGE_BACKSTOP)`** on every coroutine test. Never a hand-picked ceiling; `forbidTightRunTestTimeout` enforces it.
- **`assertAll(vararg assertions: () -> Unit)` takes NON-suspending lambdas** — drive the subject first, assert on the result after.
- **Never `advanceUntilIdle()`**; bounded `advanceTimeBy` / `runCurrent()` only.
- **`runCatchingCancellable`, never bare `runCatching`**, in any suspend context.
- **Run every new test alone** and read the **results XML**, not the console line — a class can produce zero results silently here (#2185).
- **Never `git stash`** — `refs/stash` is repo-global across linked worktrees and agents run concurrently.
- **Audit commit messages for a stray close keyword before pushing.** `fix #N` fires even when "fix" is a noun:
  ```bash
  git log origin/main..HEAD --format=%B | grep -inE '\b(close[sd]?|fix(e[sd])?|resolve[sd]?)[[:space:]]+#[0-9]+'
  ```

---

### Task 1: Stop narrating every eviction

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt` (`evictLeading`, `refuse`, class KDoc)
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/ExporterHealth.kt`
- Test: `kuilt-otel/src/commonTest/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporterDropAccountingTest.kt` (create)

**Interfaces:**
- Produces: `ExporterHealth.dropped: Long` and `ExporterHealth.refused: Long`.

**Why this is right, and what it costs.** The class KDoc promises "**Every drop is logged** with enough detail to correlate against a backend's log index." At `maxRecords = 10_000` the buffer is full essentially always, so *every* record evicts one — the exporter emits one `warn` per record, forever, to narrate a ring buffer doing exactly what it is configured to do. The line has outlived its purpose: it was written when eviction was an exceptional event, and batching made it the steady state.

**Do not simply delete it.** Silent loss is the inversion #1860 was about. Replace per-record narration with (a) an exact counter on `ExporterHealth`, and (b) one rate-limited summary line, so a reader who never polls health still learns.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.otel

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Eviction and refusal must stay **counted** even though they stopped being **logged** per
 * record.
 *
 * The per-eviction `warn` was written when eviction was exceptional. At the production
 * cap the buffer is full permanently, so every record evicts one and the line became a
 * per-record narration of normal operation — measured inside the ~58% of the export path
 * that is neither CRDT copying nor sequence recomputation. Removing it is only safe if
 * the loss stays visible somewhere cheaper, which is what these pin.
 */
class WarpLogRecordExporterDropAccountingTest {

    private fun record(n: Int) = LogRecord(
        recordId = ByteString(ByteArray(8) { n.toByte() }),
        severityNumber = 9,
        severityText = "INFO",
        body = "event $n",
        attributes = emptyMap(),
        timestampEpochNanos = n.toLong(),
        observedEpochNanos = n.toLong(),
    )

    private fun records(count: Int) = List(count) { record(it) }

    @Test
    fun everyEvictionIsCountedUnderDropOldest() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP + OVERFLOW))

        assertAll(
            { assertEquals(CAP, exporter.snapshot().toList().size) },
            { assertEquals(OVERFLOW.toLong(), exporter.health.value.dropped, "every evicted record must be counted") },
            { assertEquals(0L, exporter.health.value.refused, "DROP_OLDEST never refuses") },
        )
    }

    @Test
    fun everyRefusalIsCountedUnderDropNewest() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(
            ReplicaId("device-1"),
            InMemoryDurableStore(),
            maxRecords = CAP,
            bufferPolicy = BufferPolicy.DROP_NEWEST,
        )
        exporter.export(records(CAP + OVERFLOW))

        assertAll(
            { assertEquals(OVERFLOW.toLong(), exporter.health.value.refused) },
            { assertEquals(0L, exporter.health.value.dropped, "DROP_NEWEST never evicts") },
        )
    }

    @Test
    fun aBufferThatNeverFillsCountsNeither() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = CAP)
        exporter.export(records(CAP))

        assertAll(
            { assertEquals(0L, exporter.health.value.dropped) },
            { assertEquals(0L, exporter.health.value.refused) },
        )
    }

    private companion object {
        private const val CAP = 8
        private const val OVERFLOW = 5
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterDropAccountingTest*"`
Expected: FAIL — `Unresolved reference: dropped`.

- [ ] **Step 3: Add the counters**

In `ExporterHealth`, add two properties alongside `accepted`/`failed`, with KDoc that says what replaced the log line and why:

```kotlin
    /**
     * Records evicted by the buffer cap under [BufferPolicy.DROP_OLDEST], cumulative.
     *
     * This is the **only** per-record account of eviction. Until #2193 the exporter emitted
     * one `warn` per evicted record; at [DEFAULT_MAX_LOG_RECORDS] the buffer is full
     * permanently, so that was a line per exported record narrating a ring buffer behaving
     * exactly as configured — measurable on the export hot path and useless as a signal,
     * because a signal that fires always is not one. The count is exact; a rate-limited
     * summary line still says it out loud for a consumer who never reads health.
     */
    public val dropped: Long = 0,

    /**
     * Records refused by the buffer cap under [BufferPolicy.DROP_NEWEST], cumulative.
     *
     * Kept separate from [dropped] because the two are different events with different
     * causes: an eviction means the buffer recycled, a refusal means this replica declined
     * to author anything at all (which is what keeps its contribution to the shared op-log a
     * downward-closed prefix — #2127).
     */
    public val refused: Long = 0,
```

- [ ] **Step 4: Replace the per-record logs**

In `evictLeading`, delete the `evicted.forEachIndexed { … logger.warn { … } }` block and its `firstDisplacer` computation. Increment instead, and emit at most one summary per interval:

```kotlin
        healthState.update { it.copy(dropped = it.dropped + count) }
        reportDropsPeriodically()
```

In `refuse`, replace the `logger.warn` with `healthState.update { it.copy(refused = it.refused + 1) }` and the same periodic report. Rename it `recordRefusal` — it no longer logs, and a name that says "refuse" invites re-adding one.

Add the reporter. Rate-limit on a **count** rather than a clock — this type owns no `Clock`, and adding one would put a wall-clock read on the export hot path (the same argument `ExporterHealth`'s KDoc already makes about `lastFailure`):

```kotlin
    /**
     * Say out loud, at most once per [DROP_REPORT_INTERVAL] drops, that the buffer is
     * recycling — so the loss is not silent for a consumer who never reads [health].
     *
     * Counted rather than timed: this type owns no [kotlin.time.Clock], and a wall-clock
     * read per eviction is exactly the kind of per-record cost this change exists to remove.
     * The interval is deliberately coarse — the *number* is the signal and it is on
     * [health]; this line only has to be frequent enough to be noticed and rare enough not
     * to be the thing being reported.
     */
    private fun reportDropsPeriodically() {
        val health = healthState.value                      // ONE read — both fields, one snapshot
        val bucket = (health.dropped + health.refused) / DROP_REPORT_INTERVAL
        if (bucket == lastDropReport) return
        lastDropReport = bucket
        val total = health.dropped + health.refused
        logger.info {
            "WarpLogRecordExporter: buffer cap ($maxRecords) recycling under $bufferPolicy — " +
                "$total record(s) dropped or refused so far. This is the cap doing its job; read " +
                "ExporterHealth.dropped / .refused for the running totals."
        }
    }
```

**`lastDropReport` must start at `-1L`, not `0L`.** At `0L` the first bucket compares equal to the
initial value, so nothing is logged until drop number `DROP_REPORT_INTERVAL` — and an exporter that
drops 800 records with an interval of 1000 would log **nothing, ever**, while an operator who never
polls `health` sees no evidence of loss. That is precisely the #1860 shape this step's own
justification invokes. Starting at `-1L` makes the *first* drop report, then once per bucket.

`lastDropReport` is a `private var Long = -1L` under `lock` (every caller already holds it —
`evictLeading` and the refusal site both run inside the turn-building `lock.withLock` block).

**`DROP_REPORT_INTERVAL = 10_000`** — one line per buffer-worth of churn at `DEFAULT_MAX_LOG_RECORDS`.
Coarse on purpose: the *number* is the signal and it lives on `health`; this line only has to be
frequent enough to be noticed and rare enough not to become the thing being reported.

**Level drops from `warn` to `info`**: a cap behaving as configured is not a warning. Say so in the commit message — someone alerting on `warn` from this logger will notice.

- [ ] **Step 5: Update the KDoc promises — there are three, not one, plus dead code**

The class KDoc says "**Every drop is logged** with enough detail to correlate against a backend's log index." That is now false. Replace with an accurate statement: every drop is **counted** on `health`, and a periodic line reports the running total. Say plainly that per-record correlation was given up deliberately and why.

Also, in the same task and easy to miss:

- **`evictLeading`'s own KDoc** restates the same "every drop is logged individually" promise. Rewrite it too — a class KDoc corrected while the function KDoc still promises the old behaviour is worse than neither.
- **`evictLeading`'s `admitted: List<LogRecord>` parameter becomes unused.** It exists only to supply the `incoming recordId=` correlation to the log line. Remove the parameter and its argument at the `applyTurn` call site.
- **`EVICTION_BODY_CHARS` becomes dead** once both log sites go. Remove it.
- The internal-logger naming comment at the top of the file justifies itself with "the exporter logs on its eviction hot path". Soften rather than delete — the summary line keeps the same logger name, so the self-capture exclusion argument still holds and must keep its explanation.

`detekt` will catch the dead parameter and constant, but the plan's file list claims precision and a worker should not discover them from a lint failure.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterDropAccountingTest*"
./gradlew build detektAll --max-workers=6
git commit -m "perf(otel): count evictions instead of narrating them

Part of #2193. At DEFAULT_MAX_LOG_RECORDS the buffer is full permanently, so every
exported record evicts one and the exporter emitted a warn per record to narrate a
ring buffer behaving exactly as configured. Measured on device inside the ~58% of
the export path that is neither CRDT copying nor sequence recomputation.

Replaced with exact counters on ExporterHealth (dropped / refused) plus one
rate-limited summary at info. Rate-limited by COUNT, not by a clock: this type owns
no Clock and a wall-clock read per eviction is the cost being removed.

The level drops from warn to info — a cap doing its job is not a warning. Anyone
alerting on warn from this logger should switch to ExporterHealth."
```

Note in the PR that this is the same production-logging volume #2185's "direction 1" proposes to reduce, so the two should not be solved twice.

---

### Task 2: Stop materialising the buffer to read its head

**Files:**
- Modify: `kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpLogRecordExporter.kt:1127` (`evictLeading`)
- Test: extend `WarpLogRecordExporterBatchTest`

**Interfaces:** this task **adds public API to `:kuilt-crdt`** — see Step 3. It is not internal-only, and budgeting it as such is how it gets attempted and abandoned.

`evictLeading` does `log.entries().take(count)`. `Rga.entries()` is `sequence.filter { … }.map { … }` — **two eager Θ(N) lists** built in full before `take(128)` throws almost all of it away. Measured ≈0.18 ms/record at 10,000 ops, and fixed by neither Phase 3A nor 3B. It is mine, from #2199's Task 3, and I waved it through in planning as "O(N) once per turn, acceptable".

- [ ] **Step 1: Write the failing test**

The property is "does not materialise the whole buffer", which is invisible to any assertion on output — so it needs a **control arm** on allocation-free observable behaviour. The honest, portable pin is a `Rga` call-count probe: assert `entries()` is not called on the eviction path at all.

Since `Rga.entries()` cannot be intercepted, pin the *observable equivalent*: eviction must read only `count` values. Add to `WarpLogRecordExporterBatchTest`:

```kotlin
    /**
     * Eviction must not materialise the whole buffer to read its head.
     *
     * `entries()` builds two eager Θ(N) lists; `take(count)` then discards nearly all of
     * it. At the production cap that was ≈0.18 ms per record. This pins the *behaviour*
     * (the right records are evicted, in order, at a large cap) so a reimplementation that
     * reads ids off `sequence` directly is provably equivalent — the cost itself is pinned
     * by the probe on device, not here.
     */
    @Test
    fun evictionTakesTheOldestRecordsAtACapMuchLargerThanTheTurn() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val exporter = WarpLogRecordExporter(ReplicaId("device-1"), InMemoryDurableStore(), maxRecords = WIDE_CAP)
        exporter.export(records(WIDE_CAP))
        exporter.export(records(WIDE_CAP + WIDE_OVERFLOW).drop(WIDE_CAP))

        assertEquals(
            (WIDE_OVERFLOW until WIDE_CAP + WIDE_OVERFLOW).map { "event $it" },
            exporter.snapshot().toList().map { it.body },
            "the oldest WIDE_OVERFLOW records must go, and the rest must keep their order",
        )
    }
```

with `WIDE_CAP = 64`, `WIDE_OVERFLOW = 20` in the companion — wide enough that a head/tail confusion shows, small enough for wasm (#2183).

- [ ] **Step 2: Run it**

Run: `./gradlew :kuilt-otel:jvmTest --tests "*WarpLogRecordExporterBatchTest.evictionTakesTheOldest*"`
Expected: **PASS** against today's code — this is a characterisation test that must stay green across the rewrite, not a red-first test. Say so in the commit; a test that was green before and after is worth having here precisely because the change is a pure optimisation.

- [ ] **Step 3: Read ids off the sequence directly**

Replace the head of `evictLeading`:

```kotlin
        // The leading `count` VISIBLE ids, taken off `sequence` lazily — `entries()` would
        // build two eager Θ(N) lists and then discard all but `count` of them (≈0.18 ms per
        // record at DEFAULT_MAX_LOG_RECORDS, measured on device for #2193). `sequence` is
        // already warm here: `removeFirst` below walks the same instance's lazy.
        val tombstoned = log.tombstones
        val evictedIds = log.sequence.asSequence().filter { it !in tombstoned }.take(count).toList()
        val evictedRecords = evictedIds.map { id -> log.valueAt(id) }
```

**`Rga.valueAt(id): V` is effectively mandatory, and it must be `public`.** `insertsById` is `internal` to `:kuilt-crdt`, so `:kuilt-otel` cannot reach it — and `explicitApi()` means the accessor is a public-API addition to the CRDT, with the KDoc and `@sample` obligations this repo attaches to those. Budget that step; do not discover it as a cross-module compile failure and then reach for the fallback.

**There is no useful fallback.** `log.entries().asSequence().take(count).toList()` looks like one and is not: `entries()` has already built both eager Θ(N) lists before `asSequence()` sees anything, so it recovers ~0% of the measured 0.18 ms/record. If the accessor is rejected, this task should be dropped rather than implemented in a form that buys nothing.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :kuilt-otel:jvmTest && ./gradlew build detektAll --max-workers=6
git commit -m "perf(otel): stop materialising the whole buffer to evict its head

Part of #2193. evictLeading did log.entries().take(count); entries() builds two
eager Theta(N) lists and take() then discards nearly all of them. ~0.18 ms per
record at DEFAULT_MAX_LOG_RECORDS, measured on an iPhone XS, and fixed by neither
of #2193's two candidate tracks.

Introduced in #2199 and waved through in planning as 'O(N) once per turn,
acceptable'. It is per turn, and it was still the third-largest term."
```

---

### Task 3: Phase 3B — thread `sequence` forward in `RgaCache`

The measured winner: ~20% of the export path, no dependency, no module, no consumer cost.

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaSequenceThreadingTest.kt` (create)

**Interfaces:** none public — `RgaCache` is internal.

**Read `FugueSeqState.kt` and `Fugue.kt`'s "Performance" paragraph first.** `Fugue` already ships this exact design (#1211): local edits thread a materialised structure forward; remote `apply`/`piece`/`compact`/deserialization pass nothing and the next read rebuilds once. Copy the ownership contract and the null-on-general-paths rule rather than re-deriving them.

**And `FugueSeqState`'s KDoc is also the warning.** It justifies Fugue's O(1) local insert by proving the new node always joins an **empty** sibling list. **RGA has no such guarantee** — its siblings sort by id *descending*, so a new node can join a non-empty sibling list and land ahead of an existing one.

#### The guard, before any code

Threading is valid **only when `after` is the last element of the full `sequence`, tombstones included.** Then the new op is the sole child of a leaf and the descending tiebreak never engages. Three ways it fails:

- `insertAllAfter(r, HEAD, values)` on a non-empty log **prepends** — HEAD's children sort descending and a fresh local id has the maximal Lamport.
- Any mid-sequence `after` lands mid-sequence.
- **Reachable in production.** `WarpLogRecordExporter.tail` is the last *visible* element. Log `a, b, c` with `c` tombstoned by a remote `Remove` absorbed through `merge`; `tail == b`. The next append becomes a **sibling of `c`**, and its higher Lamport sorts it first: true order `a, b, new, c`, naive suffix `a, b, c, new`. `Rga.equals` is `ops` + `compactedBelow` only, so the two states compare **equal** while `toList()` disagrees — a silent divergence no equality assertion can see.

Two rules follow:

1. **The guard lives inside `Rga`, never in the caller.** Thread only when
   `after == (sequence.lastOrNull() ?: RgaId.HEAD)` on the full sequence; otherwise pass `null`.
   **Note the parentheses** — `==` binds tighter than `?:`, so the unparenthesised form parses as
   `(after == sequence.lastOrNull()) ?: RgaId.HEAD` and does not compile. Inferring "this is an
   append" from caller intent is exactly how the exporter's `tail` — which *looks* like an append and
   is not — gets through.
2. **The guard must not force the cold lazy.** On the fill path nothing reads `sequence` until the first eviction, so a naive `sequence.last()` check re-introduces the very `computeSequence()` this task removes, on a path that today never pays it. Carry a **nullable** materialised sequence in `RgaCache` and thread only when one is already present.

#### Every construction site, with its disposition

| Site | Disposition |
|---|---|
| `insertAfter`, `insertAllAfter` | Thread **iff** the guard holds; else `null` |
| `insertAt` | Delegates to `insertAfter` — inherits the guard |
| `removeAt`, `removeFirst` | Thread the **same list reference**. These already force the lazy (both go through `visibleSequence()`), so the reference is in hand for free — **and this is where warmth enters the chain** |
| `applyRemove` | Thread **only if a cached sequence is already present**, else `null`. Correctness is identical to its siblings, but this path never reads `sequence` today, so "unconditionally" would force a full `computeSequence()` on **every remote `Remove`** — a new Θ(N) per-op cost on the gossip path, violating rule 2 |
| `applyInsert` (remote) | `null` — a remote op lands anywhere |
| `applyCompact`, `compact` | `null`, implemented once in the shared `withCompactCaches` |
| `withCompactedBelow` / `cacheAfterFloor` | `null` — a floor removes elements *and* HEAD-re-roots survivors |
| `dropWindow` | `null` (via the floor path) |
| `piece` | `null` — a union reorders arbitrarily |
| `fromOps` (wire decode) | `null` — no cache today; unchanged |
| `empty()` | **`null`.** Seeding `emptyList()` would thread from birth, but then every append turn on the fill path pays an O(N) list copy on a path that today reads the sequence not at all — the same "cost on a path that never pays it" rule 2 argues from. Warmth arrives at the first eviction instead |
| `deltaOf` | `null` — already constructs with `cache = null`; unchanged, listed so the table is genuinely exhaustive |

**How warmth propagates, which is the load-bearing mechanism and not an implementation detail.**
Every site above either propagates an *already-present* cached sequence or passes `null`, and
`empty()`/`fromOps` start `null` — so if that were the whole story the guard would never fire and the
optimisation would deliver exactly zero. The chain is warmed by `removeFirst`/`removeAt`, which force
the lazy anyway and thread the result forward. So the first eviction after a start, a merge, a window
pass or a recovery pays one `computeSequence()` and every append turn after it threads. On the
exporter's steady state that is the common case, because eviction tombstones only the *leading*
prefix, leaving `tail == sequence.last()`.

- [ ] **Step 1: Write the failing tests, parameterised over `after` position**

The test shape matters more than usual: a suite that covers "every entry point" with chained-append data passes while all three divergence shapes hide. Cover at minimum — `after` = full-sequence tail (must thread), `after` = `HEAD` on a non-empty log (must not), `after` = mid-sequence (must not), `after` = last *visible* with a trailing tombstone (must not — the production counterexample).

**The oracle is a cache-free reconstruction of the same state.** `Rga.fromOps` is `internal` and so reachable from `:kuilt-crdt`'s own `commonTest`, but it needs the Lamport clock and the floor as well as the ops — the assertion is

```kotlin
assertEquals(Rga.fromOps(threaded.ops, threaded.lamport, threaded.compactedBelow).sequence, threaded.sequence)
```

(check the actual `fromOps` signature before copying — it is `internal` and has moved before). An oracle that reconstructs from ops alone would drop the floor and disagree for reasons that have nothing to do with threading.

- [ ] **Step 2: Confirm they fail** (`Unresolved reference` on the new cache field).

- [ ] **Step 3: Implement**, following `FugueSeqState`'s null-on-general-paths shape and the table above.

- [ ] **Step 4: Confirm green, then add the never-recomputes assertion** — a counter on a test-visible hook, or a wall-clock ratio across N. Without it, a guard that is correct but always falls through to `null` passes as a correctness success while delivering nothing.

- [ ] **Step 5: Full build, every test alone, commit.**

The existing `Rga` conformance, lattice-law and golden-vector suites are the regression surface and must pass **unmodified**.

---

## Verification before any PR leaves draft

- [ ] `./gradlew build detektAll --max-workers=6` green, tasks `EXECUTED` not `FROM-CACHE`.
- [ ] Every new test run **alone**, verdict read from the results XML.
- [ ] The `Rga` golden-vector and canonical-encoding suites pass **unmodified**.
- [ ] Close-keyword audit clean on every commit message.
- [ ] PR bodies say **`part of #2193`** — the exit test and the decision to close are the user's.
- [ ] Re-run the device probe after Task 3 and report the new arm F against 3.34 ms/record, as a **ratio**.

## Explicitly not in this plan

- **Phase 3A / CHAMP.** ~17% of the export path, needs a module and a dependency. A weak case on the measurement; decide it separately, with numbers, once Tasks 1–3 have moved the baseline.
- **Anything in `:kuilt-bolt`.** Different subsystem, own spec and plan.
