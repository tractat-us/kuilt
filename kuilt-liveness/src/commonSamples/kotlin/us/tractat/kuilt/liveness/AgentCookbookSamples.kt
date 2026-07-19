package us.tractat.kuilt.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Samples for the liveness API used by the agent cookbook (`docs/agent-cookbook.md`).
 *
 * Every function here is compiled as part of commonTest so a typo or API change
 * breaks the build, not silently produces stale documentation.
 */

/**
 * Detect that a peer went silent using [HeartbeatPartitionDetector].
 *
 * Build one detector per monitored peer over its [Seam], `start` it on a scope, and
 * collect [PartitionDetector.events]. A silent peer surfaces as
 * [PartitionEvent.PeerUnresponsive] after [HeartbeatConfig.timeout], then either
 * [PartitionEvent.PeerRecovered] or [PartitionEvent.PeerLost]. Don't hand-roll a
 * `while (true) { delay(); ping() }` loop — the detector owns the ping/pong and the
 * timeout state machine. [clock] is injected so tests drive it under virtual time.
 */
public suspend fun detectSilentPeerSample(
    link: Seam,
    peerId: PeerId,
    scope: CoroutineScope,
    clock: () -> Instant,
) {
    val detector = HeartbeatPartitionDetector(link, peerId, HeartbeatConfig(), clock)
    detector.start(scope)
    detector.events.collect { event ->
        when (event) {
            is PartitionEvent.PeerUnresponsive -> Unit // pause app processing; reason says why
            is PartitionEvent.PeerRecovered -> Unit // peer came back within the reconnect window
            is PartitionEvent.PeerLost -> Unit // reconnect window elapsed — vacate the slot
        }
    }
}

/**
 * Reap a room, table or lobby that nobody else ever joined, using [SoloDeadlineDetector].
 *
 * Feed it the roster on every membership change. If the room reaches the minimum in time it
 * emits [SoloDeadlineEvent.Paired] and disarms forever; otherwise [SoloDeadlineEvent.NeverPaired]
 * fires once at the deadline and you apply your own reaping policy. Don't hand-roll a
 * `launch { delay(timeout); if (peers.size < 2) close() }` — and note this is *never* paired,
 * not *currently* solo: a room that fills and later empties is
 * [HeartbeatPartitionDetector]'s job.
 */
public suspend fun reapNeverPairedRoomSample(
    link: Seam,
    scope: CoroutineScope,
    clock: Clock,
    closeRoom: suspend () -> Unit,
) {
    val detector = SoloDeadlineDetector(
        minimumMembers = 2, // this peer plus one — "never paired"
        deadline = 5.minutes,
        clock = clock,
        scope = scope,
    )
    // Feed it the roster on every change.
    scope.launch { link.peers.collect { detector.observeMembership(it) } }
    when (detector.events.first()) {
        is SoloDeadlineEvent.NeverPaired -> closeRoom() // nobody came; reaping policy is yours
        is SoloDeadlineEvent.Paired -> Unit // someone joined in time; the detector is done
    }
}
