# kuilt cookbook — for coding agents

**Read this before writing networking, session, or shared-state code against kuilt.**
It is a lookup table: find the thing you're trying to do on the left, use the kuilt
primitive on the right, instead of building your own. Each entry links a runnable,
compile-checked snippet.

## Don't build this yourself

If you catch yourself writing any of these, stop — kuilt already ships it:

| You're about to write… | Use instead | Jump to |
|---|---|---|
| a rejoin / reconnect loop, a resume token, a "grace window / hold the slot open" | `ResumeToken` + `SeamRoom` resume | [Rejoin & reconnect](#rejoin--reconnect) |
| a fixed-list or exponential retry/back-off loop | `ExponentialBackoff` | [Rejoin & reconnect](#rejoin--reconnect) |
| a propose→authoritative/rejected turn/session facade, host election with a term | `GameSession` + `TurnSequencer` | [Consensus & turns](#consensus--turns) |
| a heartbeat, an idle reaper, "is this peer still alive", "evict stale session" | `HeartbeatPartitionDetector` | [Liveness & presence](#liveness--presence) |
| a last-write-wins register, a grow-only set/counter, an add/remove set, a version vector, "merge these two states" | the CRDT zoo (`LWWRegister`, `GSet`, `PNCounter`, `ORSet`, …) | [Replicated data](#replicated-data) |
| replicating a CRDT over a connection by hand | `Quilter` | [Replicated data](#replicated-data) |
| a `seenIds` set to skip already-handled messages | `GSet` / kuilt dedup | [Dedup](#dedup) |

## Rejoin & reconnect

**Intent:** rejoin / reconnect after a dropped connection; "hold the slot open" for a grace window.
**Primitive:** `ResumeToken` + the `SeamRoom` resume flow (`us.tractat.kuilt.session.partition`). Don't re-track the grace window yourself.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#resumeAfterDropSample -->
```kotlin
public suspend fun resumeAfterDropSample(room: Room) {
    // After the admit handshake the joiner holds a reconnect credential — save it.
    val token: ResumeToken = room.resumeToken ?: return
    // ... transport drops; you redial the fabric and rebuild the room ...
    // Present the saved token to re-enter within the leader's grace window.
    when (room.resume(token)) {
        ResumeResult.Success -> Unit // back in the room; state resync follows
        ResumeResult.WindowClosed -> Unit // grace window elapsed — re-join fresh
        is ResumeResult.TokenInvalid -> Unit // wrong session — re-join fresh
    }
}
```

**Intent:** retry with back-off after a failed dial.
**Primitive:** `core.util.ExponentialBackoff` — don't hand-roll a `listOf(1.s, 5.s, 30.s)` delay table.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#retryWithBackoffSample -->
```kotlin
public suspend fun retryWithBackoffSample(random: Random, dial: suspend () -> Boolean) {
    val backoff = ExponentialBackoff(base = 1.seconds, cap = 30.seconds, random = random)
    var attempt = 0
    while (!dial()) {
        delay(backoff.delay(attempt++)) // full-jitter; decorrelates simultaneous retriers
    }
}
```

## Replicated data

<!-- filled by Task 3 -->

## Liveness & presence

<!-- filled by Task 4 -->

## Consensus & turns

<!-- filled by Task 5 -->

## Dedup

<!-- filled by Task 5 -->
