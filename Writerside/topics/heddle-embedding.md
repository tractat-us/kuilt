# Embedding Heddle

A CI service runs jobs for many teams on one pool of runners. Paying teams
should get more of the pool than free ones, a team must not grab every
runner at once, and only some teams can use the Mac runners. This page
wires Heddle into that service, one call site at a time.

Heddle does not replace your queue, your runners or your database. It
answers one question when a runner frees up, "whose job?", and keeps the
books that the next answer depends on. Everything below is one compiled
sample, `sampleRunnerPool`, quoted in order, and every result shown is a
check in that sample.

## Draw the pool

Teams sign up while the service runs, so the tree has two tiers with a
**template** each, rather than a fixed list of teams:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
// Paid teams share three parts to the free tier's one. Teams are attached as they sign up.
// No paid team holds more than two runners at once, and no free team more than one.
val pool = draft("runners") {
    group("paid", Weight.of(3)) { template(maxConcurrent = 2L) }
    group("free") { template(maxConcurrent = 1L) }
}
// A job takes about ten minutes, and minutes are the unit everything is charged in.
val (heddle, booted) = LocalHeddle.fromTopology(pool, LocalPolicy(defaultReservation = 10L), epoch = 0L)
check(booted.refusals.isEmpty())
heddle.reconcileCapacity(totalSlots = 4L)
```

The weights split the pool between tiers; inside a tier, every team gets
the template's equal weight. The ceiling, `maxConcurrent`, caps each team
on its own. `reconcileCapacity` tells the engine how many runners exist;
call it again when the pool grows or shrinks. An admin-edited file works
too: decode a `TopologyDocument` and pass it to `Topology.from`, which
lists every problem with its address. [Fair share in one program](heddle-local.md#draw-the-tree)
covers both forms.

`epoch` is a boot counter. Keep it in your database and raise it on every
start of the service. [Survive a restart](#survive-a-restart) shows why.

## Sign teams up

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
var generation = 1L
fun signUp(tier: String, team: String): GroupId {
    check(heddle.attach(listOf(tier), team, generation++).refusals.isEmpty())
    return pool.attachedChildId(listOf(tier), team)
}
val acme = signUp("paid", "acme")
val apps = signUp("paid", "apps")
val hobby = signUp("free", "hobby")
// Only the apps team builds for iOS, so only its jobs can run on a Mac.
val needsMac = setOf(apps)
```

`attach` adds a team under its tier's template. A new team starts level
with the teams already running, not owed a backlog. `detach` removes one:
it takes no new jobs at once, and retires when its running jobs finish.
The engine raises `generation` past every one its books hold, so a
removed team's id is never reused.

## Two call sites

The whole integration is two functions your service calls. One runs when a
runner frees up, the other when a job finishes:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
// The pool calls this whenever a runner frees up. A refusal changes nothing: the jobs stay queued.
fun onRunnerFree(queued: Set<GroupId>, mac: Boolean, now: Long): Lease? {
    val eligible = if (mac) queued intersect needsMac else queued
    return (heddle.acquire(queued, eligible, startedAt = now) as? AcquireOutcome.Acquired)?.lease
}
// And this when a job finishes, with the minutes it really took.
fun onJobDone(lease: Lease, minutes: Long, queued: Set<GroupId>): LeaseOutcome =
    heddle.complete(lease.id, actualCost = minutes, waiting = queued)
```

Two sets steer each pick. `queued` (the `waiting` parameter) is every team
with a job in your queue. `eligible` is the teams this particular runner
can serve. Keep them apart: a team that is queued but cannot use this
runner keeps its place in line, and is not treated as idle.

The lease names the team. Take that team's oldest job from your queue and
run it on the runner. Keep the lease id beside the job in your database, so
that a finished job can be completed and a crashed one can be found.

## A Mac runner, and a ceiling

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
val everyone = setOf(acme, apps, hobby)
// Two Mac runners free up. Both go to apps, the one team that can use them, which fills its ceiling.
val macJobs = List(2) { checkNotNull(onRunnerFree(everyone, mac = true, now = 0L)) }
check(macJobs.all { it.leaf == apps })
check(onRunnerFree(everyone, mac = true, now = 0L) == null)
check(heddle.explain(apps, waiting = everyone, eligible = needsMac) == WaitExplanation.AtCeiling(apps, limit = 2L))
```

The third Mac runner gets nothing: `acquire` answers `NothingEligible`,
because the only team it could serve is at its ceiling. `explain` says so
in a value your status page can turn into words.
[Explaining a wait](heddle-local-explain.md) lists every answer.

## A job that runs long

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
// One Mac job runs long. It pays for every minute, and the four past its estimate are an overrun.
check(onJobDone(macJobs[0], minutes = 14L, everyone) == LeaseOutcome.Done(charged = 14L, overrun = 4L, unrecorded = 0L))
check(onJobDone(macJobs[1], minutes = 10L, everyone) == LeaseOutcome.Done(charged = 10L, overrun = 0L, unrecorded = 0L))
```

`complete` is never refused: work that already ran is charged in full.
Guessing low cannot dodge the charge, though it can win a turn early, as
[How fair is it?](heddle-local.md#how-fair-is-it) explains. A job that
finishes early is charged what it used, and the rest goes back.

## Does the split hold?

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRunnerPool -->

```kotlin
// A busy afternoon on one Linux runner: every team keeps work queued for 400 jobs.
val served = mutableMapOf(acme to 0, apps to 0, hobby to 0)
repeat(400) { i ->
    val job = checkNotNull(onRunnerFree(everyone, mac = false, now = 30L + i * 10L))
    served[job.leaf] = served.getValue(job.leaf) + 1
    check(onJobDone(job, minutes = 10L, everyone) is LeaseOutcome.Done)
}
// Paid gets three quarters, within one job, and the free tier the last quarter.
check(served.getValue(acme) + served.getValue(apps) in 299..301 && served.getValue(hobby) in 99..101)
// The 24 minutes apps spent on the Macs count against it here, so acme ran more of these jobs.
check(served.getValue(apps) < served.getValue(acme))
```

The run measured 151 jobs for acme, 148 for apps and 101 for hobby. The
paid tier took 299 of 400 against an ideal of 300. Inside it, apps ran
fewer Linux jobs than acme because its Mac minutes were already on its
books. Share is counted in time across every runner, not in jobs on one.

## Survive a restart

Two kinds of restart reach a CI service. A runner host can die and take
its jobs with it, and the service itself can restart.

**A runner host dies.** List the lease ids of the jobs still running and
pass them to `reconcileLeases`. It reports **orphans**, leases whose work is
gone, and leases it has never heard of. It changes nothing; you settle
each orphan with `complete`, usually at its `defaultSettlement`: the time
it ran, capped at what it was granted.

**The service restarts.** Under your scheduling lock, `persist` the engine
and store the encoded text exactly as it comes. On start, decode it and
`restore` under a raised boot counter. This is the same loop from
`sampleFairShareQueue`, which also starts from an admin's config file:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleFairShareQueue -->

```kotlin
// Under the scheduling lock, persist. Encode and store the text exactly as it comes.
val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

// After the restart: decode, restore under the raised counter, and settle what did not survive.
val decoded = LocalHeddle.decode(stored, Json)
check(decoded is RestoreResult.Restored)
val restored = LocalHeddle.restore(decoded.snapshot, policy, epoch = 1L)
val orphan = restored.reconcileLeases(live = setOf(running.id), now = 10L).orphaned.single()
check(orphan.lease == lost)
check(restored.complete(orphan.lease.id, orphan.defaultSettlement, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
check(restored.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
```

The restored engine picks exactly as the old one would have. Open leases
come back under their old ids, so jobs that kept running complete as
normal. An id from before the restart that is no longer open answers
`Stale`, not `Unknown`. The saved form is a JSON or CBOR array,
`[formatVersion, state]`, frozen at format 1 in this release.
[When the engine restarts too](heddle-local.md#when-the-engine-restarts-too)
gives the rules and what `decode` checks.

## The checklist

Before you ship it, check each of these against your service.

- **One caller at a time.** `LocalHeddle` is not thread-safe and takes no
  locks of its own. Call it under the lock your scheduler already holds.
  `snapshot()` returns an immutable view you can hand to another thread.
- **Pass time in.** Heddle never reads a clock. `startedAt`, `now` and every
  cost are numbers you supply, in one unit. Minutes or seconds both work.
- **`eligible` must be inside `waiting`.** Otherwise `acquire` throws
  `IllegalArgumentException`. An exception here means a caller's mistake.
- **Refusals are values.** `SupplyExhausted`, `NothingEligible`,
  `NoLiveLineage` and `LifetimeExhausted` from `acquire` change nothing
  except, for the last two, the engine's memory of who was waiting. Leave
  the job queued and try on the next free runner.
- **Work started elsewhere** is charged with `admit`, which makes no pick
  and is never refused for supply or a ceiling.
- **Estimates matter.** Set `defaultReservation` near a typical job, and
  pass `reservation` when you know better, such as the team's last run time.
  [How fair is it?](heddle-local.md#how-fair-is-it) shows why.
- **Keep the boot counter durable.** Two engines under one epoch could hand
  out the same lease id. `restore` throws on an epoch that is not higher.

## Limits

The engine counts in 64-bit whole numbers and refuses rather than wraps.

- One reservation or extension is at most `LocalHeddle.MAX_RESERVATION`,
  2^44 units. An overrun charged at `complete` is not bound by it.
- **The books fill up, eventually.** Two lanes of the largest leases fill a
  counter after about half a million leases. Before that, `acquire` answers
  `LifetimeExhausted` and keeps the remaining room for work already
  running. `admit`, `extend` and `complete` are never refused for it.
- A charge that still does not fit is recorded whole or not at all, and
  `LeaseOutcome.Done.unrecorded` says how much was left out. So does a single
  overrun above `LocalHeddle.MAX_RECORDED_OVERRUN`, so one wrong cost cannot
  stop the engine. Sum `unrecorded` over completions only.
- `reconcileCapacity` refuses a pool whose runners × default estimate ×
  target rounds would pass `Long.MAX_VALUE / 4`.

[Running it in a real program](heddle-local-turns.md#running-it-in-a-real-program)
gives the exact rules.

## One program, or many machines?

Everything above is `LocalHeddle`: one process makes every pick. That fits
a CI service with one scheduler, even a busy one; the measured cost of an
`acquire` is in [How fast, how fair](heddle-local-turns.md#how-fast-how-fair).

When the runners schedule themselves, with no central service, use the
distributed node in `:kuilt-heddle`. Each machine spends from its own
holdings, and a split network keeps working.
[Fair share with Heddle](heddle.md) starts that story.
