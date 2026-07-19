package us.tractat.kuilt.liveness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
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
