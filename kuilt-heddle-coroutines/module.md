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
with a slot count, a clock, and how long one unit of cost is. Then `acquire` a slot for a lane, run
the work, and `complete` it at what it really cost:

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
  `clockStart`.

This module adds coroutines on top of `:kuilt-heddle-core`, which has none and must never gain
them. Waiting on the networked `HeddleNode` is not here.
