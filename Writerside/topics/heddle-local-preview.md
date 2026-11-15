# Showing who goes next

Someone on the mobile team has a build queued, and they want to know when it
runs. "Second in line. Web goes first: it pays for three parts of the farm to
your one." A line like that saves a support question, and Heddle can supply
every part of it.

It answers three questions: who goes next, why it is not you, and what share
you are owed. None of them changes anything, so a screen can ask on every
refresh. Only a real build moves the line.

> **Playground.** This engine works today, and its API can change.

This page uses the build farm from [Fair share in one program](heddle-local.md):
web at three parts, mobile at one, four agents, and builds of about three
minutes. Every code block is quoted from a compiled sample, and every result it
shows is checked when the sample runs.

## Where is my build?

Tell the engine how many builds each lane has queued, and how much time each
pick will set aside. It answers with the lanes in the order the next agents
would go to them:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
val both = setOf(web, mobile)
// Where do mobile's next builds sit in line? Second and sixth.
val line = heddle.previewPicks(mapOf(web to 8, mobile to 8), both, List(8) { 3L })
check(line.withIndex().filter { it.value == mobile }.map { it.index + 1 } == listOf(2, 6))
```

The line reads web, mobile, web, web, web, mobile, web, web: six to two, which
is three to one. Mobile's next build is second and the one after it is sixth.
That is your status screen's "you are second".

`previewPicks` gets there by playing the next eight picks on a throwaway copy of
the engine. The copy picks exactly as the engine would, so the real builds
follow the same line, as long as nothing else happens first.
[Lease one agent at a time](heddle-local.md#lease-one-agent-at-a-time) runs
those eight builds and shows that they match.

The arguments are the same two sets every pick takes. The map is `waiting`
with a count on each lane, and the set is `eligible`, the lanes these agents
can run. The list holds one reservation per pick, here three minutes each.

## When each build has its own estimate

A queue often knows more than a count. Web's second build might be a
twelve-minute release build, not a three-minute check. Pass each lane's queue of
estimates, the next build first:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#samplePreviewQueues -->

```kotlin
// Each lane's queued builds, by their estimates in minutes, the next one first.
val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
val both = setOf(web, mobile)
val before = heddle.snapshot()
val preview = heddle.previewPicks(queues, eligible = both)
check(preview == listOf(web, mobile, web, mobile, web, web, mobile))
check(heddle.snapshot() == before)
// Were every build 3 minutes, mobile's second would run sixth. Web's 12-minute build spends
// four builds' worth of web's share, so mobile's moves up to fourth.
check(heddle.previewPicks(mapOf(web to 4, mobile to 3), both, List(7) { 3L }) == listOf(web, mobile, web, web, web, mobile, mobile))
```

A long build ahead of you moves you up, because it uses more of its team's
share. The snapshot taken before and after the preview is equal, which is the
proof that asking moved nothing.

Each pick here does what a typical loop does. It leases an agent at the
default estimate, then stretches it with `extend` to the winner's own estimate. So
the default decides who wins each pick, and the twelve minutes count against
web from then on.

## Why not me?

`explain` names the reason a lane is not next. It runs the very pick the next
`acquire` would make, on a throwaway copy, so its answer always agrees with
what happens next.

The answer is one of a fixed set, so a status screen can write one sentence
for each. Give each part of the tree the name people know it by, then turn
every answer into words:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
// The names a person knows each part of the tree by.
val names = mapOf(
    farm.groupId() to "the farm",
    farm.groupId("web") to "Web",
    web to "Web",
    farm.groupId("mobile") to "Mobile",
    mobile to "Mobile",
)

// One sentence for each answer explain can give. The compiler checks that none is missing.
fun why(lane: GroupId, waiting: Set<GroupId>, eligible: Set<GroupId>): String =
    when (val answer = heddle.explain(lane, waiting, eligible)) {
        WaitExplanation.WouldBePickedNext -> "Next up."
        WaitExplanation.NotWaiting -> "Nothing queued."
        WaitExplanation.NotEligible -> "This agent cannot run it."
        WaitExplanation.SupplyExhausted -> "No agent time is free yet."
        is WaitExplanation.NoLiveLineage -> "Its part of the tree is being changed."
        is WaitExplanation.AtCeiling -> "${names.getValue(answer.group)} already runs ${answer.limit} at once, its limit."
        is WaitExplanation.PositionCeiling -> "Scheduling is stuck at ${names.getValue(answer.parent)}."
        is WaitExplanation.BehindAtLevel -> "${names.getValue(answer.nextChild)} goes first at ${names.getValue(answer.parent)}."
    }
```

`WaitExplanation` is a sealed type, so the `when` must cover every answer. If a
later release adds one, your screen fails to compile instead of showing a
blank. Ask it about mobile, and watch the answer change only when something
real happens:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
check(why(mobile, waiting = both, eligible = both) == "Web goes first at the farm.")
// …
// The answers change only when something real happens. Web takes the next agent:
check((heddle.acquire(both, both, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == web)
check(why(mobile, waiting = both, eligible = both) == "Next up.")
check(why(mobile, waiting = setOf(web), eligible = setOf(web)) == "Nothing queued.")
check(why(mobile, waiting = both, eligible = setOf(web)) == "This agent cannot run it.")
```

What each answer means, in the order the engine checks them:

- **`NotWaiting`**: the lane has nothing queued.
- **`NotEligible`**: it has work, but this agent cannot run it, such as an iOS
  build offered a Linux agent.
- **`NoLiveLineage`**: no pick can end there right now, for example because a
  reload is removing or reshaping its part of the tree. `at` names the lane.
- **`AtCeiling`**: a group on its path already runs as many builds as its
  ceiling allows. `group` is the one nearest the top, and `limit` the ceiling.
  A limit of `0` is a pause.
- **`SupplyExhausted`**: the engine has no time to hand out, so nobody goes
  next. Most often `reconcileCapacity` has not been called yet.
- **`PositionCeiling`**: the pick reaches a group with a child that has hit
  the ceiling on how far ahead its record of service can go. Nobody below that
  group goes next. A short run never meets this.
- **`BehindAtLevel`**: another child goes first at `parent`, the first group
  where the pick leaves this lane's path. `nextChild` is the one it picks.
- **`WouldBePickedNext`**: this lane is next.

The first group named in `BehindAtLevel` matters in a deeper tree. If web split
its share between pull requests and nightly builds, a nightly build could wait
behind mobile at the farm, or behind pull requests inside web. The answer says
which.

## What am I owed?

`entitlement` is the fair share, as a number of agents:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
// Of the four agents, mobile is owed one while both teams have builds queued.
check(heddle.entitlement(mobile, waiting = both, totalSlots = 4L) == Rational.ONE)
// With web's queue empty, mobile is owed all four.
check(heddle.entitlement(mobile, waiting = setOf(mobile), totalSlots = 4L) == Rational.of(4L))
```

It is shared over the lanes in `waiting`, so it is a share of the work that is
really there, and a team with nothing queued leaves its part to the rest. The answer is
an exact fraction, a `Rational`, so one agent split three ways is a third, not
0.333.

## On a screen with no engine

An admin screen often runs in another process, holding only the engine's last
saved state. It needs no engine of its own. Decode the saved snapshot, and
`view` gives it the same three questions:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleSnapshotPreviewQueues -->

```kotlin
// On the admin screen: each lane's queued builds by their estimates in minutes, the next one first.
val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
val decoded = LocalHeddle.decode(stored, Json)
check(decoded is RestoreResult.Restored)
val view = decoded.snapshot.view(policy)
val both = setOf(web, mobile)
check(view.previewPicks(queues, eligible = both) == listOf(web, mobile, web, mobile, web, web, mobile))
check(view.previewPicks(queues, eligible = both) == heddle.previewPicks(queues, eligible = both))
```

A `LocalHeddleView` answers `previewPicks`, `explain` and `entitlement` exactly
as the engine that took the snapshot would have. That is no coincidence: the
engine answers its own questions through a view of its live state. Pass the
policy the engine ran with, since a snapshot does not keep one.

Do not restore a throwaway engine for each question: `restore` wants a raised
epoch every time, and a view needs none. A view is also safe to share. The
engine is not thread-safe, so call it from one place at a time, its reads
included. A view of a snapshot shares nothing with the engine, and any number
of threads may ask it at once. [When the engine restarts too](heddle-local.md#when-the-engine-restarts-too)
covers taking and storing snapshots.

## Logging why work waits

The same answers make a good hold reason in a log line or a metric. Use the
answer's `kind`, a stable name for each arm such as `BEHIND_AT_LEVEL`, and its
group ids. Leave out the numbers `BehindAtLevel` carries: they move on every
pick, so a reader comparing them would see a new reason every time.

## What a preview cannot see

A preview is a forecast, and it is exact only while its premise holds: nothing
else happens in between. Several ordinary things break that premise.

- **Real builds take real time.** A build that runs long or finishes early
  shifts its team's place, and so the line behind it.
- **New work arrives.** A lane that queues work, or empties, changes who is
  waiting.
- **Builds finish.** A preview finishes none of its own picks, and counts
  each at its estimate. With no ceilings, a build that ends on its estimate
  changes nothing, which is why the farm's real builds match their preview.
  Under a ceiling the line is different, because a capped lane shows up no
  more often than its ceiling allows:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
// A preview finishes no build, so deploys, capped at one at a time, appear once.
val pool = draft("pool") {
    lane("deploys", Weight.of(3), maxConcurrent = 1)
    lane("builds")
}
// …
val queued = mapOf(deploys to 3, builds to 3)
check(capped.previewPicks(queued, queued.keys, List(6) { 1L }) == listOf(deploys, builds, builds, builds))
```

Six picks were asked for and four came back. Once the builds run out, only
deploys are waiting, and they are at their ceiling. A preview stops at the
first pick the engine would not make, so a short list is an answer too:
nothing more can start until something finishes.

Three more things to know before you wire a screen up:

- **Bad input throws.** `previewPicks` throws `IllegalArgumentException` for a
  negative count or a reservation outside `1..MAX_RESERVATION`. `explain` takes
  the same checks as `acquire`, so its `eligible` set must sit inside `waiting`.
- **Exact fractions can overflow.** `explain` and `entitlement` throw
  `ArithmeticException` when an exact fraction no longer fits. That can happen
  well before any ceiling, so a status screen should catch it and show
  something plainer.
- **The tree can change.** A reload or an attach changes the tree itself, so
  ask again after an `apply` or an `attach`.

## Reading the numbers behind "behind"

`BehindAtLevel` carries three numbers, all on one scale: the time a child has
been given, divided by its weight. Heddle calls that its progress, or its
virtual service.

- **`leafSideVirtualService`** is the progress of this lane's own child of
  `parent`.
- **`eligibleFront`** is the line the pick drew, an average over the children
  this agent can run. Above it by more than a sixteenth of a unit of the
  lane's own service, the lane was not in the running for this pick. At or
  below it, the lane was in the running and lost on timing, because
  `nextChild`'s build ends sooner on that scale (or, on a tie, by id).
- **`front`** is the same average over everyone waiting: the share line.
  Above it, this side has had more than its share. At or below it, less.

The two lines differ when the agent cannot run everything. A lane below
`front` can still be above `eligibleFront`, when the lanes this agent cannot
run are the ones holding `front` up.

[Ask why a team is waiting](heddle-local.md#ask-why-a-team-is-waiting) shows
the numbers for a real moment in the farm.
[How the next turn is chosen](heddle-local-turns.md) gives the rule they come
from and works the first eight builds out by hand.
