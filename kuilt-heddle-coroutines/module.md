# Module kuilt-heddle-coroutines

Wait your turn, then run.

A build farm has four agents and two teams, web and mobile, and web paid for three parts of it to
mobile's one. A program handing out the agents wants to say "run this build in web's lane when it
is web's turn" and have that one line wait. This module is that line. A `FairSharePool` holds the
farm's scheduler and the number of agents, and a caller that asks for a slot waits until fair share
says it is its lane's turn.

The part that is easy to get wrong is a caller that gives up at the very moment its turn comes.
Get it wrong and the slot is lost for good, and the farm runs one agent short without anyone
noticing. The pool hands that slot back.

## Start here

Describe the lanes as a `Topology`, start a `LocalHeddle` from it, and hand the engine to a pool
with a slot count, a clock, and how long one unit of cost is. Then run each job inside `withSlot`:
it waits for the lane's turn, runs the job, and charges the lane the time the job held the slot.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleWithSlot -->
```kotlin
    val pool = FairSharePool(heddle, slots = 1, TimeSource.Monotonic, costUnit = 1.minutes)

    val built = mutableListOf<String>()
    coroutineScope {
        // One agent: whichever lane's turn comes second waits for the first build to finish.
        launch { built += pool.withSlot(web) { "web build" } }
        launch { built += pool.withSlot(mobile) { "mobile build" } }
    }
    check(built.sorted() == listOf("mobile build", "web build"))

    // A name the farm does not declare is refused before anything runs.
    val refused = try {
        pool.withSlot(GroupId("nightly")) { "never runs" }
    } catch (e: SlotRefusedException) {
        "refused: ${e.reason}"
    }
    check(refused.startsWith("refused"))
```

A job that fails, or is cancelled part-way, is charged the time it held the slot all the same, so a
lane whose work fails fast does not get the farm for free. The clock starts when the job starts:
the moment between a grant and its caller resuming is never charged.

When the work does not fit in one block, `acquire` a slot for a lane, run the work, and `complete`
it at what it really cost:

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleAcquireSlot -->
```kotlin
    // Two agents, and one unit of cost is one minute on an agent. The pool owns the engine now.
    val pool = FairSharePool(heddle, slots = 2, TimeSource.Monotonic, costUnit = 1.minutes)

    // Waits until fair share grants web a slot: at once, on an idle farm.
    val build = pool.acquire(web)
    check(build is SlotOutcome.Granted)
    // The build took five minutes against the default estimate of three: two are overrun.
    check(pool.complete(build.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L, unrecorded = 0L))

    // A name the farm does not declare is refused, never left waiting.
    check(pool.acquire(GroupId("nightly")) is SlotOutcome.Refused)

    val next = pool.acquire(mobile)
    check(next is SlotOutcome.Granted)
    pool.cancel(next.lease.id)
```

## How it works

One lock guards the engine, a first-in-first-out queue of waiters per lane, and the leases the pool
holds open. After every change, while a slot is free and some lane has a waiter, the pool asks the
engine whose turn it is and grants the slot to that lane's first waiter. Waiters are resumed only
after the lock is released, so a waiter that completes its lease the moment it resumes hands out the
next slot itself.

- **Cancellation.** A waiter cancelled while queued leaves its queue. One cancelled after its grant
  was made but before it resumed hands the lease back to the engine, charged nothing.
- **Refusals.** A lane no lease can end at is refused with the engine's own `WaitExplanation`, and
  never left waiting, even when every slot is busy.
- **Failures.** If handing out slots fails, the waiters get the failure; the `complete` or the
  cancellation that set the hand-out going returns as usual. The same goes for an engine that
  refuses to take back a cancelled waiter's lease: the slot is freed all the same.
- **Restarts.** The engine's open leases are adopted and count against the slots. `now()` is the
  pool's clock in cost units: persist it beside the engine's snapshot and pass it back as
  `clockStart`. A `withSlot` caller that held a slot died with the old program, so nothing settles
  its lease, and a pool whose slots were all busy at the crash grants nothing. Settle those
  leases once the new pool is built, as the restart walk-through below shows. With a `clockStart`
  of `0`, every default settlement reads `0`.
- **One writer.** The pool owns the engine. Reach it only through
  `withEngine { engine, waiting -> … }`, for a snapshot, an explain or a reload. `waiting` is a
  copy of the pool's queued lanes as the block starts: pass it wherever the engine asks for a
  waiting set, since a guessed one skews later picks. Afterwards the pool frees the slot of any
  lease the block closed, refuses the waiters of a lane the reload removed, and grants whoever the
  change let through. `resize` changes the slot count, and lowering it closes nothing; a block's
  own `reconcileCapacity` is undone when it ends.

## After a restart

The farm's program crashed while both agents were busy. Name the leases whose work survived as
`live`, none here, and settle every other one at its default, the time it ran:

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleRestartAPool -->
```kotlin
    // After the crash: restore under a raised epoch, your persisted boot counter, and start the
    // pool's clock where it stopped. Both leases are adopted: every slot is taken.
    val restored = LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
    val pool = FairSharePool(restored, slots = 2, clock, costUnit = 1.minutes, clockStart = stoppedAt)

    coroutineScope {
        val next = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire(web) }
        check(!next.isCompleted) // both slots are held by builds that died with the old program

        // None of the old builds is running, so name none as live, and settle each orphan at the
        // default: the two minutes it ran. With the default clockStart of 0, now() would read 0,
        // and every default settlement would read 0 too.
        val found = pool.withEngine { engine, _ -> engine.reconcileLeases(live = emptySet(), now = pool.now()) }
        check(found.orphaned.map { it.defaultSettlement } == listOf(2L, 2L))
        found.orphaned.forEach { pool.complete(it.lease.id, it.defaultSettlement) }

        val granted = next.await()
        check(granted is SlotOutcome.Granted)
        pool.complete(granted.lease.id, actualCost = 3L)
    }
```

This module adds coroutines on top of `:kuilt-heddle-core`, which has none and must never gain
them. Waiting on the networked `HeddleNode` is not here.
