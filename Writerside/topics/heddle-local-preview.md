# Showing who goes next

Someone on the mobile team has a build queued, and they want to know when it
runs. "Second in line. Web goes first: it pays for three parts of the farm to
your one." A line like that saves a support question, and Heddle can supply
every part of it.

This page covers the first half: who goes next, and when your turn comes.
Asking changes nothing, so a screen can ask on every refresh. Only a real build
moves the line. The second half, why it is not you yet, has a page of its own:
[Explaining a wait](heddle-local-explain.md).

> **Beta.** This engine is built and heavily tested, and its API can still change.

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
default estimate, then stretches it with `extend` to the winner's own
estimate. So the default decides who wins each pick, and the twelve minutes
count against web from then on.

## On a screen with no engine

An admin screen often runs in another process, holding only the engine's last
saved state. It needs no engine of its own. Decode the saved snapshot, and
`view` answers just as the engine would:

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
  negative count or a reservation outside `1..MAX_RESERVATION`, and the queue
  form checks every queued estimate, not only the next.
- **The reasons are a separate call.** `explain` says why a lane is not next.
  Its answers carry exact fractions, which can overflow where the pick itself
  does not. [Explaining a wait](heddle-local-explain.md) covers both.
- **The tree can change.** A reload or an attach changes the tree itself, so
  ask again after an `apply` or an `attach`.
