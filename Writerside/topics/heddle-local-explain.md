# Explaining a wait

A build has sat in the queue for ten minutes, and the person who started it
wants to know why. Is it stuck? Did someone jump the line? Most of the time the
true answer is dull and reassuring: another team is ahead, and it is their
turn. Heddle can say so, and name the team.

Ask about any lane at any moment. The answer changes nothing, and it always
agrees with what the engine does next.

> **Playground.** This engine works today, and its API can change.

This page uses the build farm from [Fair share in one program](heddle-local.md):
web at three parts, mobile at one, four agents, and builds of about three
minutes. [Showing who goes next](heddle-local-preview.md) covers the order of the
next builds. This page covers the reasons. Every code block is quoted from a
compiled sample, and every result it shows is checked when the sample runs.

## Your build is waiting because…

There are eight possible answers. The engine checks them in this order, and
the first that holds is the one you get:

| What to tell the person | What they can do |
| --- | --- |
| Nothing is queued for it. | Nothing to wait for: queue a build. |
| This agent cannot run it. | Wait for an agent that can, such as a Mac for an iOS build, or add one. |
| Its part of the tree is changing, or has nothing to run on. | Wait for the change to finish. A team that only holds attached children needs one attached. |
| Its team, or a group above it, already runs as many builds as its limit allows. | Wait for one of those builds to finish, or raise the limit. A limit of zero is a pause: lift it to resume. |
| There is no agent time to hand out yet. | Tell the engine how many agents it has. |
| Its group's record of service has reached its limit. | Rare. The known cause is a lane that keeps work queued but is never served. Make sure every such lane can run somewhere. |
| Another team is ahead, and it is their turn. | Usually nothing: this is fair share at work. The answer names the team, and a preview says when your turn comes. |
| It is next. | Wait a moment. |

Only the last two are normal. The rest point at something you can change.

## Turn the answer into words

`explain` gives one answer from a fixed set, so a status screen can write a
sentence for each. Give each part of the tree the name people know it by, then
turn every answer into words:

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

The arguments are the same two sets every pick takes: `waiting`, the lanes with
work queued, and `eligible`, the lanes this agent can run.

## Why did they go first?

"Another team is ahead" has two everyday causes. The other team pays for a
bigger share, or your team has used more than its share lately. Here web's
ninth build ran nine minutes against an estimate of three:

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

So if web asks why mobile went first, the answer is "mobile goes first at the
farm", and the numbers say why: web's progress, 9, is past the line, 33/4. Its
long build spent its turns ahead of time.
[Reading the numbers](#reading-the-numbers-behind-behind) below says what each
one means.

## Is it fair?

`entitlement` answers "what share should I be getting?", as a number of agents:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleQueueStatus -->

```kotlin
// Of the four agents, mobile is owed one while both teams have builds queued.
check(heddle.entitlement(mobile, waiting = both, totalSlots = 4L) == Rational.ONE)
// With web's queue empty, mobile is owed all four.
check(heddle.entitlement(mobile, waiting = setOf(mobile), totalSlots = 4L) == Rational.of(4L))
```

It is shared over the lanes in `waiting`, so it is a share of the work that is
really there, and a team with nothing queued leaves its part to the rest. The
answer is an exact fraction, a `Rational`, so one agent split three ways is a
third, not 0.333. Compare it with what each lane was really charged over a
stretch of time, and you have checked the promise yourself.

## What explain promises

`explain(leaf, waiting, eligible, reservation)` runs the very pick the next
`acquire` would make, on a throwaway copy of the engine. That gives it three
properties a status page can lean on.

- **It agrees with the next pick.** On the same arguments, the answer is
  `WouldBePickedNext` exactly when the next `acquire` picks this lane, and any
  other answer only when it does not.
- **It changes nothing**, not even the engine's memory of which teams went
  quiet. Ask as often as you like.
- **It gives the first reason.** The answers are checked in a fixed order, the
  order of the table above, and the first that holds wins.

"The same arguments" includes `reservation`, the time the pick would set aside.
It defaults to the policy's `defaultReservation`, as `acquire` does. Pass the
value your next `acquire` will pass, because a pick's timing depends on it.

## The answers, by name

The type is `WaitExplanation`, and each answer carries a `kind` from
`WaitExplanation.Kind`. They come in the same order as the table, and that
order is part of the API:

- **`NotWaiting`**: the lane is not in `waiting`.
- **`NotEligible`**: it is waiting, but not in `eligible`.
- **`NoLiveLineage(at)`**: no pick can end at `at`, this lane. It may be a group
  with a child of its own, or a group with a template and nothing attached.
  Or a reload is removing or reshaping its part of the tree.
- **`AtCeiling(group, limit)`**: `group`, on the lane's path, already has
  `limit` leases open, its `maxConcurrent`. When several groups are at their
  ceilings, `group` is the one nearest the top. A `limit` of `0` is a pause.
  Ceilings are checked before supply, so a capped lane hears this even when the
  engine has no time to hand out.
- **`SupplyExhausted`**: the engine holds less time than the reservation, so
  the next pick is refused whoever is waiting. Most often `reconcileCapacity`
  has not been called yet.
- **`PositionCeiling(parent)`**: the pick reaches `parent`, which has a child at
  its position ceiling, so the pick is refused for everyone below. A busy group
  resets its children's positions long before that, so a running engine rarely
  meets it. The known way there is a child that keeps work queued and is never
  served.
- **`BehindAtLevel(parent, nextChild, …)`**: another child goes first at
  `parent`, the first group where the pick leaves this lane's path, and
  `nextChild` is the one it picks. In a deeper tree this tells you where the
  wait is. If web split its share between pull requests and nightly builds, a
  nightly build could wait behind mobile at the farm, or behind pull requests
  inside web.
- **`WouldBePickedNext`**: the next pick is this lane.

[No more than one at once](heddle-local.md#no-more-than-one-at-once) shows
`AtCeiling` on the farm. The
[refusals and limits](https://github.com/tractat-us/kuilt/blob/main/docs/agent-cookbook/heddle.md#refusals-and-limits)
section of the agent cookbook covers every refusal the engine can give.

## Noticing when the reason changes

A status page wants to say "still waiting behind web" rather than flash a new
message on every pick. A metric wants a hold reason that stays put while
nothing has really changed. Compare the answer's `kind` and its group ids, never
the whole value:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleWaitReason -->

```kotlin
// The reason, without the numbers: the kind, and every group id the answer names.
fun reason(answer: WaitExplanation): Pair<WaitExplanation.Kind, List<GroupId>> =
    answer.kind to when (answer) {
        WaitExplanation.NotWaiting, WaitExplanation.NotEligible, WaitExplanation.SupplyExhausted,
        WaitExplanation.WouldBePickedNext -> emptyList()
        is WaitExplanation.NoLiveLineage -> listOf(answer.at)
        is WaitExplanation.AtCeiling -> listOf(answer.group)
        is WaitExplanation.PositionCeiling -> listOf(answer.parent)
        is WaitExplanation.BehindAtLevel -> listOf(answer.parent, answer.nextChild)
    }

fun build() {
    val out = heddle.acquire(both, both, startedAt = 0L)
    check(out is AcquireOutcome.Acquired)
    heddle.complete(out.lease.id, actualCost = 3L)
}
repeat(2) { build() } // web, then mobile
val before = heddle.explain(mobile, waiting = both, eligible = both)
build() // web again
val after = heddle.explain(mobile, waiting = both, eligible = both)
// The numbers moved, so the whole answers differ. The reason did not: web is still ahead at the farm.
check(before != after)
check(reason(before) == reason(after))
check(reason(after) == (WaitExplanation.Kind.BEHIND_AT_LEVEL to listOf(farm.groupId(), farm.groupId("web"))))
```

The numbers inside `BehindAtLevel` move with every pick under that group. A
reader comparing whole answers would see a new reason every time, which is
exactly what a status page must not do. `kind` is a stable name, such as
`BEHIND_AT_LEVEL`, safe to put in a log line or a telemetry attribute.

## When explain throws

One seam is worth knowing before you put `explain` on a page that must never
fail. Its numbers are exact fractions, and an exact fraction can outgrow a
64-bit number long before the engine itself runs out of room. When a
`BehindAtLevel` line does not fit, `explain` throws `ArithmeticException`
instead of answering. How big the fraction gets depends on the weights'
denominators, so fine fractional weights reach it soonest. `entitlement` can
throw it the same way.

The engine's own picks are not affected, only the explanation of them. So
catch it, and show something plainer, such as "waiting its turn". Two other
throws are your own mistakes and worth letting through. An `eligible` set that
is not inside `waiting` throws `IllegalArgumentException`, as `acquire` does,
and so does a `reservation` out of range.

## From a saved snapshot

An admin screen that holds only the engine's saved state can ask the same
questions with no engine. Decode the snapshot, and `view` answers `explain`,
`entitlement` and `previewPicks` exactly as the engine that took it would have:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleSnapshotView -->

```kotlin
// On the admin screen: decode the stored snapshot and view it with the engine's policy.
val decoded = LocalHeddle.decode(stored, Json)
check(decoded is RestoreResult.Restored)
val view = decoded.snapshot.view(policy)

val queued = setOf(heavy, light)
check(view.previewPicks(mapOf(heavy to 2, light to 1), eligible = queued, reservations = List(3) { 3L }) == listOf(heavy, light, heavy))
check(view.explain(heavy, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
check(view.entitlement(light, waiting = queued, totalSlots = 3L) == Rational.ONE)
```

This sample uses a smaller tree, `heavy` at weight 2 beside `light`. The
snapshot can also come straight from `persist`, with no decoding, and
`persist().view(policy)` asks it the same way. Pass the policy the engine ran
with, since a snapshot does not keep one.
[On a screen with no engine](heddle-local-preview.md#on-a-screen-with-no-engine)
says why a view beats restoring an engine for each question.

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
  Above it, this side has had more than its share. At or below it, no more.

The two lines differ when the agent cannot run everything. A lane below
`front` can still be above `eligibleFront`, when the lanes this agent cannot
run are the ones holding `front` up.

[Ask why a team is waiting](heddle-local.md#ask-why-a-team-is-waiting) shows
the numbers for a moment when the two teams are level.
[How the next turn is chosen](heddle-local-turns.md) gives the rule they come
from and works the first eight builds out by hand.
