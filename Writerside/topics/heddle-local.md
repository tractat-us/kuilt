# Fair share in one program

A build farm has four agents and two teams. Web pays for three parts and
mobile for one. Each time an agent frees up, someone must say whose build
runs next. Heddle can make that call inside your own program, with no
network and no extra servers.

It keeps the promise over time, not build by build. A team that sat idle
does not come back owed a pile of turns. A build that ran long pays for
every minute.

> **Playground.** This engine works today, and its API can change. One piece
> is still landing: loading your tree into the engine. The examples below show
> where it fits.

This page follows one farm from its first build to a crash. Every example is
one compiled sample, `sampleBuildFarm`, quoted in order. Every result the page
shows is a check in that sample, so the page cannot drift from what the engine
does.

## Draw the tree

Write down who shares the farm and in what proportions:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// Two teams share the farm's build agents. Web gets three parts to mobile's one.
val farm = draft("farm") {
    group("web", Weight.of(3)) { lane("builds") }
    group("mobile") { lane("builds") }
}
val web = farm.groupId("web", "builds")
val mobile = farm.groupId("mobile", "builds")
```

![The farm splits into web, at weight 3, and mobile, at weight 1. Each team has one lane called builds.](heddle-local-farm.svg)

A **group** divides its share among its children. A **lane** is where work is
charged, so it is what a build belongs to. Each weight is relative to its
siblings: web's 3 against mobile's 1 means three quarters of the agents' time
goes to web while both teams have work queued. Groups nest, so web could split its share between a
`pull-requests` lane and a `nightly` lane, at weights of its own.

`groupId` turns a path of names into the id the engine uses. The two lanes are
both called `builds`, and they are still different lanes, because an id is the
whole path.

A group marked with `runtimeChildren()` is one whose children your program names
while it runs: one per customer, say, all at the same weight. When the tree comes from
an admin's file rather than code, decode a `TopologyDocument` and call
`Topology.from`, which lists every problem with an address instead of throwing.

**A rename or a move is a delete plus an add.** A child is known by its name and
its place in the tree. Rename it, or move it under another group, and the engine
retires the old child and starts a new one with no fairness history.

<!-- TODO(L3): loading a Topology into an engine, reloading it (`apply`), attaching
runtime children to a running engine, and how open leases move across a reweight. Replace the Playground note's "still landing"
sentence and this paragraph's lead-in once L3 merges; until then the samples load
the tree through a test-only seam (`farmEngine`). -->

Today `LocalHeddle.fresh` starts an engine with an empty tree, and no public call
loads yours into it yet. The sample loads the farm into an engine named `heddle`
through a test-only door, so the rest of this page runs against the real engine.

## Tell it how many agents you have

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// A build takes about 3 minutes, so a lease reserves 3 when the caller knows no better.
val policy = LocalPolicy(defaultReservation = 3L)
// …
// Nothing to hand out until the engine knows how many agents there are.
val both = setOf(web, mobile)
check(heddle.acquire(waiting = both, eligible = both, startedAt = 0L) == AcquireOutcome.SupplyExhausted)
heddle.reconcileCapacity(totalSlots = 4L)
```

Heddle counts in whatever unit your program counts time in. Here it is minutes.
It never reads a clock: every time is a number you pass in, so a replay of the
same calls makes the same choices.

Before it can hand out time, the engine needs a supply of it. `reconcileCapacity`
tells it how many agents run at once, and from then on it keeps its own supply
topped up. When its supply would drop below one round of builds (every agent once,
at the default estimate), it refills to two rounds. Until you call it, an
`acquire` the engine cannot fund answers `SupplyExhausted` and changes nothing.
Call it again whenever the farm grows or shrinks.

## Lease one agent at a time

When an agent frees up, ask who goes next, run that build, and report how long
it took:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// One build: lease a slot, run it, and charge the minutes it took.
fun build(waiting: Set<GroupId>, startedAt: Long, minutes: Long = 3L): GroupId {
    val out = heddle.acquire(waiting, eligible = waiting, startedAt = startedAt)
    check(out is AcquireOutcome.Acquired)
    check(heddle.complete(out.lease.id, actualCost = minutes) is LeaseOutcome.Done)
    return out.lease.leaf
}
```

`acquire` opens a **lease**: one agent, given to one lane, with an estimate of
the time it will take set aside. `complete` closes it and charges the real time.
`startedAt` is stored on the lease for your own bookkeeping. No pick ever reads it.

Two sets steer each pick. `waiting` is every lane with work queued. `eligible`
is the lanes this particular agent can run, which is usually all of them. The
difference matters, and the Mac agent further down shows why.

The first eight builds, with every build finishing on its estimate:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// Ask who goes next without changing anything, then run the first eight builds.
val firstEight = listOf(web, mobile, web, web, web, mobile, web, web)
check(heddle.previewPicks(mapOf(web to 8, mobile to 8), both, List(8) { 3L }) == firstEight)
check((0 until 8).map { build(both, startedAt = it * 3L) } == firstEight)
```

Six to two: three to one. `previewPicks` answers "who goes next?" for several
builds ahead without moving anything, and the real builds then match it.

![The first eight builds go web, mobile, web, web, web, mobile, web, web. After mobile's quiet spell, the next eight follow the same pattern.](heddle-local-turns.svg)

## Ask why a team is waiting

Mobile has a build queued and web got the agent. `explain` says why:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// Mobile is waiting and level with web, so it loses the ninth pick on its deadline.
check(
    heddle.explain(mobile, waiting = both, eligible = both) ==
        WaitExplanation.BehindAtLevel(
            parent = farm.groupId(),
            nextChild = farm.groupId("web"),
            leafSideVirtualService = Rational.of(6L),
            front = Rational.of(6L),
            eligibleFront = Rational.of(6L),
        ),
)
check(heddle.explain(web, waiting = both, eligible = both) == WaitExplanation.WouldBePickedNext)
// Of four agents, web is owed three and mobile one.
check(heddle.entitlement(web, waiting = both, totalSlots = 4L) == Rational.of(3L))
check(heddle.entitlement(mobile, waiting = both, totalSlots = 4L) == Rational.ONE)
```

`explain` runs the very pick the next `acquire` would make, on a throwaway copy,
so its answer always agrees with what happens next. Here it says that at the top
of the tree, the farm picks web. The first number is mobile's progress: the time
it has been given, divided by its weight. The other two are lines it is judged
against, an average over everyone waiting and over everyone this agent can run.
Mobile sits at 6, level with both, so it was in the running. It lost because
web's next build ends sooner by that same measure.
[How the next turn is chosen](heddle-local-turns.md) explains those numbers and
works this exact moment out by hand.

The other answers are plainer: `NotWaiting` (nothing queued), `NotEligible`
(this agent cannot run it), `NoLiveLineage` (the tree is being reshaped around
it) and `SupplyExhausted`. A log line or a metric should use the answer's `kind`
and its group ids. The numbers move on every pick.

`entitlement` is the fair share as a number of agents: of four, web is owed
three while both teams keep work queued.

## A build that runs long

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// The ninth build is web's, and it runs 9 minutes against its 3-minute estimate.
val ninth = heddle.acquire(both, both, startedAt = 24L)
check(ninth is AcquireOutcome.Acquired && ninth.lease.leaf == web)
check(heddle.complete(ninth.lease.id, actualCost = 9L) == LeaseOutcome.Done(charged = 9L, overrun = 6L))
// All nine minutes count: web is now past the line, so mobile goes next.
check(
    heddle.explain(web, waiting = both, eligible = both) ==
        WaitExplanation.BehindAtLevel(
            parent = farm.groupId(),
            nextChild = farm.groupId("mobile"),
            leafSideVirtualService = Rational.of(9L),
            front = Rational.of(33L, 4L),
            eligibleFront = Rational.of(33L, 4L),
        ),
)
```

The estimate only decides how much time to set aside. `complete` charges what
the build really took, and the six extra minutes are reported as the overrun. A
charge is never refused, even when it is bigger than the estimate. Web paid for
its long build by moving ahead of the line, so mobile gets the next agent. A
build that finishes early hands the unused minutes back the same way.

## A team that goes quiet

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// Mobile's queue empties, and web runs twelve builds alone.
check((0 until 12).map { build(setOf(web), startedAt = 30L + it * 3L) }.all { it == web })
// Mobile comes back level with web, not owed the twelve builds it sat out.
val returned = heddle.explain(mobile, waiting = both, eligible = both)
check(returned is WaitExplanation.BehindAtLevel && returned.leafSideVirtualService == Rational.of(21L))
check(returned.front == Rational.of(21L))
check((0 until 8).map { build(both, startedAt = 66L + it * 3L) } == firstEight)
```

While mobile had nothing queued, web used every agent, which is the point of
sharing. When mobile's work returns, it starts level with web, not twelve builds
behind it. Without that rule, mobile would take agent after agent until it
caught up. The next eight builds follow the same three-to-one pattern as the first eight.

A team counts as quiet only when it is missing from `waiting`. A team that is
waiting but cannot run on this agent is not quiet, and keeps its place.

## Better estimates, and other endings

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// The next free agent is a Mac, which only mobile builds use. Mobile's last build took
// 7 minutes, a better estimate than the default.
val estimated = heddle.acquire(waiting = both, eligible = setOf(mobile), startedAt = 90L, reservation = 7L)
check(estimated is AcquireOutcome.Acquired && estimated.lease.reserved == 7L)
// Still running at 7 minutes, it asks for 3 more, then finishes at 8.
check(heddle.extend(estimated.lease.id, amount = 3L) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
check(heddle.complete(estimated.lease.id, actualCost = 8L) == LeaseOutcome.Done(charged = 8L, overrun = 0L))
// A build cancelled before it ran is charged nothing.
val cancelled = heddle.acquire(both, both, startedAt = 98L)
check(cancelled is AcquireOutcome.Acquired)
check(heddle.cancel(cancelled.lease.id) == LeaseOutcome.Done(charged = 0L, overrun = 0L))
// Someone started a mobile build by hand. Admit it: no pick, but it is charged like any other.
val byHand = heddle.admit(mobile, startedAt = 99L)
check(byHand is AcquireOutcome.Acquired)
check(heddle.complete(byHand.lease.id, actualCost = 4L) == LeaseOutcome.Done(charged = 4L, overrun = 1L))
```

- **A Mac agent.** Web is still waiting, but this agent cannot run its builds,
  so only mobile is eligible. Web keeps its place for the next agent it can use.
- **An estimate.** How long the lane's last build took is a good guess for the
  next. A closer estimate makes the order fairer while builds are running. The
  charge is corrected at `complete` either way.
- **`extend`** sets aside more time for a build still running. It is optional:
  `complete` charges an overrun anyway.
- **`cancel`** closes a lease and charges nothing.
- **`admit`** charges work that started without a pick, such as a build someone
  ran by hand. It is never refused for lack of supply, because the work is already
  running.

## After a crash

The agent host restarts. Some builds the engine still holds leases for are gone,
and the host may be running one the engine never heard of:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleBuildFarm -->

```kotlin
// Two builds are running when the agent host restarts. It comes back running only one of
// them, and one build the engine never leased.
val kept = (heddle.acquire(both, both, startedAt = 110L) as AcquireOutcome.Acquired).lease
val lost = (heddle.acquire(both, both, startedAt = 111L) as AcquireOutcome.Acquired).lease
val stranger = LeaseId(epoch = 0L, seq = 999L)
val found = heddle.reconcileLeases(live = setOf(kept.id, stranger), now = 125L)
check(found.unknown == listOf(stranger))
val orphan = found.orphaned.single()
check(orphan.lease == lost && orphan.elapsed == 14L && orphan.granted == 3L)
// It ran 14 minutes on a 3-minute lease, which a lost build cannot prove: charge the 3.
check(heddle.complete(orphan.lease.id, orphan.defaultSettlement) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
// Settled once, it is gone: a second settlement changes nothing.
check(heddle.complete(orphan.lease.id, actualCost = 14L) == LeaseOutcome.Unknown)
```

`reconcileLeases` compares the leases the engine holds with the work you say is
still running, and reports both kinds of mismatch. It changes nothing; settling
is your call. An **orphan** is a lease whose work is gone, with how long it has
been since it started. The default settlement, `defaultSettlement`, charges that
time but no more than the lease was given, since a lost build cannot show that
it really ran long. If you have evidence that it did, pass more. Work that will
resume belongs in `live`, so it never becomes an orphan.

<!-- TODO(P): persist and restore — continue the farm across a restart of the
engine itself (`snapshot` → bytes → restore), lease ids from an older epoch
answering `Stale`, and whether a restore must call `reconcileCapacity` again.
Add once P merges; extend `sampleBuildFarm` rather than starting a new story. -->

## How fair is it?

Over time, each lane gets its weight's share of the time while it has work
queued, and the check is one call away: compare `entitlement` with what each lane
was really charged. Two limits come with that promise, stated plainly.

- **Per fork, not end to end.** Each fork in the tree keeps its own children close
  to their weights. A lane deep in the tree inherits the small gaps of every fork
  above it.
- **Estimates steer order.** A lease counts at its estimate until it completes,
  so a lane that always guesses low looks cheaper while its builds run. The charge
  is corrected at `complete`, but a pick already made stays made.

<!-- TODO(fairness fix): the estimate and wake-straddle effects are being decided.
Once the fix merges, restate the second bullet as main then behaves, and add any
guidance on sizing `defaultReservation`. -->

One more thing before you wire it in: the engine is not thread-safe, so call it
from one place at a time. [How the next turn is chosen](heddle-local-turns.md)
covers that, and states the rule exactly, works the first eight builds by hand,
and gives the measured numbers, the limits and what to check on your own engine.
