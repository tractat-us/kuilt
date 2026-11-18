# `:kuilt-heddle-coroutines` — waiting your turn for a slot

Epic: #3554. Plan: `docs/superpowers/plans/2026-11-17-heddle-coroutines.md`. Where the two differ, the plan's Rulings win. They settle what this spec left open, and fold in the plan review: an adopted lease at construction, dispatch failures delivered to the waiters, and an unleasable name refused at arrival. `TimeSource` is the stdlib's, not coroutines'.

## The idea

A build farm has four agents. A program that hands them out wants to say "run this build in
web's lane when it is web's turn" and have that one line wait. `LocalHeddle` already knows whose
turn it is, but it answers at once: `acquire` names a lane or says nobody can go. Everything
between the answer and a running build is left to the caller: how many agents are free, a queue
per lane, waking the right waiter, and handing back a slot whose waiter gave up at the moment it
was granted. That last piece is easy to get wrong and silent when it is: a slot leaks, and the
farm runs one agent short for good.

`:kuilt-heddle-coroutines` is that loop, written once. A `FairSharePool` holds a `LocalHeddle`
and a slot count, and `withSlot(lane) { … }` suspends until fair share grants the caller a slot,
runs the block, and charges the time it took.

## Why a module of its own

- `:kuilt-heddle-core` must never resolve kotlinx-coroutines (`verifyHeddleCoreDependencies`,
  #3167). A suspending API cannot live there.
- `:kuilt-heddle` is the distributed node: it carries Raft, Quilter and liveness. A one-process
  consumer should not inherit them to wait for a slot. Same reasoning as the `:kuilt-store`
  split.
- Name ruled by Iain: `:kuilt-heddle-coroutines`, the coroutine front door over the
  coroutine-free core. Package `us.tractat.kuilt.heddle`, as both heddle modules use.
- Dependencies: `api(project(":kuilt-heddle-core"))`, `api(libs.kotlinx.coroutines.core)`
  (suspend signatures and `TimeSource` live in the public API), `implementation(libs.kotlinx.atomicfu)`.

## Surface

```kotlin
public class FairSharePool(
    engine: LocalHeddle,          // the pool owns it from here on; see "One writer"
    slots: Int,                   // how many leases may be open at once; >= 0
    timeSource: TimeSource.WithComparableMarks,
    costUnit: Duration,           // one unit of LocalHeddle cost; positive
) {
    /** Suspend until [leaf] is granted a slot, run [block], charge its measured cost. */
    public suspend fun <T> withSlot(leaf: GroupId, reservation: Long = <engine default>, block: suspend (Lease) -> T): T

    /** Suspend until [leaf] is granted a slot; the caller must complete or cancel the lease. */
    public suspend fun acquire(leaf: GroupId, reservation: Long = <engine default>): SlotOutcome

    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome
    public fun cancel(lease: LeaseId): LeaseOutcome

    /** Run [block] on the engine under the pool's lock (apply, attach, persist, explain…), then re-dispatch. */
    public fun <R> withEngine(block: (LocalHeddle) -> R): R

    /** Change the slot count. Lowering it preempts nothing. */
    public fun resize(slots: Int)
}

public sealed interface SlotOutcome {
    public data class Granted(val lease: Lease) : SlotOutcome
    /** The leaf cannot be leased: [reason] is the engine's own explanation. */
    public data class Refused(val reason: WaitExplanation) : SlotOutcome
}
```

`withSlot` throws `SlotRefusedException(reason)` (an `IllegalStateException`) where `acquire`
would answer `Refused`. The exact class shape is the plan's to settle; the point is that the
convenience form throws, the low-level form answers.

`engine default` is the policy's `defaultReservation`. `LocalHeddle` does not expose its policy
today, so the pool takes a `LocalPolicy` too, or `LocalHeddle` gains a read-only
`defaultReservation` — the plan picks, and a new public read on `LocalHeddle` is an ABI change
to `:kuilt-heddle-core` that rides in the same PR.

## How it works

**State, all under one `reentrantLock`:** the engine; a FIFO queue of waiters per leaf, each a
`CancellableContinuation<SlotOutcome>` with its own reservation; the count of leases the pool has
open; the slot count.

**Dispatch** runs under the lock after anything that might free a slot or add a candidate: a
waiter arriving, a complete, a cancel, a `resize`, a `withEngine`. While open < slots and some
leaf has a waiter:

1. `waiting` = every leaf with a queued waiter. `eligible` = `waiting` (slots are alike; see
   "Not in v1").
2. `engine.acquire(waiting, eligible, startedAt = now, reservation = default)`.
3. `Acquired(lease)` → pop the head waiter of `lease.leaf`. If its reservation is above the
   default, `extend` by the difference (as `samplePreviewQueues` does — design §7.4, one length
   per slot). Count the lease open, and add `(continuation, lease)` to a to-resume list.
4. `NothingEligible` → every waiting leaf is at a ceiling. Stop; a lease closing re-dispatches.
5. `NoLiveLineage` / `LifetimeExhausted` / `SupplyExhausted` → the plan settles each, with this
   default: ask `engine.explain(leaf, waiting, eligible)` for each waiting leaf; a leaf whose
   answer is `NoLiveLineage` or `LifetimeExhausted` has every waiter refused with that answer;
   then loop. `SupplyExhausted` cannot happen once the pool has reconciled capacity, so it is an
   `IllegalStateException`.

After the lock is released, resume every collected continuation.

**`now`** is `timeSource.markNow()` against a mark taken at construction, in whole `costUnit`s.

**The grant/cancel race.** A waiter is resumed with
`continuation.resume(Granted(lease)) { _, _, _ -> handBack(lease) }`. If the waiter's coroutine
was cancelled before the resume took, `onCancellation` runs: under the lock, `engine.cancel`,
count the lease closed, dispatch, and resume the new grants outside the lock. A waiter cancelled
while still queued is removed from its queue by `invokeOnCancellation`; if dispatch had already
popped it, the removal finds nothing and the resume's `onCancellation` hands the lease back.

**Measured cost (`withSlot`).** Mark at grant; on every exit from the block — return, throw, or
cancellation — `complete(lease, ceil(elapsed / costUnit))` in a `finally`, then let the result or
the throwable go. A job that failed or was cancelled still held the slot, and charging it nothing
would let a lane that fails fast take free share. `complete` does not suspend, so the `finally`
needs no `NonCancellable`. `cancel` is only for a grant whose block never started, which is the
race above.

**One writer.** The pool owns the engine. Every other use of it — `apply`, `attach`, `detach`,
`persist`, `explain`, `previewPicks`, `reconcileLeases` — goes through `withEngine`, which holds
the lock and re-dispatches afterwards (a reload can lift a ceiling or remove a lane). A
`withEngine` block that calls `acquire`, `admit`, `complete` or `cancel` on the engine itself goes
around the pool's count; KDoc says not to.

**Capacity.** The pool calls `engine.reconcileCapacity(slots)` at construction and on every
`resize`.

## Hard parts, and how the tests must prove them

- **No slot leaks under cancellation.** A test cancels waiters at every point of the grant: while
  queued, between pop and resume, and after resume inside the block. After each, open leases ==
  0, the engine's `snapshot().leases` is empty, and a fresh waiter is granted.
- **Never more than `slots` open at once.** Under virtual time, and under a real multi-threaded
  dispatcher (a stress arm with a line-tight `// ALLOW-realDispatcher:` reason, asserting its own
  iteration count, run on JVM *and* a native target and wasmJs: a concurrency claim needs a
  cross-target run).
- **Fair share is preserved.** Two lanes weighted 3 : 1 with deep queues and one slot: the
  grant order equals `engine.previewPicks` on a twin engine fed the same calls.
- **Measured cost.** `TestTimeSource` (or `testScheduler.timeSource`): a block that `delay`s 5
  units is charged 5; a block that throws after 2 is charged 2 and the throwable escapes; a block
  cancelled after 3 is charged 3 and the cancellation propagates.
- **Resume outside the lock.** A test resumes a waiter whose continuation immediately calls back
  into the pool (completes its lease from an `Unconfined` resume); it must not deadlock or
  re-enter dispatch inside dispatch.

## Not in v1

- Slots that differ (a Mac agent only mobile builds run on). The engine supports it through
  `eligible`; the pool would need named slot classes. A follow-up issue.
- `admit` (work started elsewhere) through the pool: `withEngine { it.admit(…) }` covers it for
  now, outside the count.
- Awaiting on the distributed `HeddleNode`: separate needs-design issue. If it lands, it belongs
  in `:kuilt-heddle` (already coroutine-based, owns the ledger `StateFlow`).
- A rate limiter: a separate track.

## kuilt obligations, per slice that adds public API

- `kuilt-primitives` skill: an index-table route, and a trigger in `description` only by removing
  one (`verifySkillDescriptionBudget`, 1,536 chars; body capped at 8 KiB).
- `docs/agent-cookbook/heddle.md`: an entry with a `<!-- verbatim from … -->` snippet from a
  compiled `@sample`, and its row in `docs/agent-cookbook.md`.
- `@sample`s under `kuilt-heddle-coroutines/src/commonSamples/kotlin/`.
- `./gradlew updateKotlinAbi`, dump committed.
- New module only: `settings.gradle.kts`, AGENTS.md module-table row (`verifyModuleTable`), a
  `moduleDescription()` branch in `build-logic/src/main/kotlin/kuilt.publish.gradle.kts`,
  `python3 .github/scripts/llms-txt.py`, `module.md`, and the BOM if it lists modules explicitly.
