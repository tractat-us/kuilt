package us.tractat.kuilt.core.partition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.FaultProfile
import us.tractat.kuilt.core.FaultyPeerLink
import us.tractat.kuilt.core.FaultyPeerLinkFactory
import us.tractat.kuilt.core.InMemoryPeerAdvertisement
import us.tractat.kuilt.core.InMemoryPeerLinkFactory
import us.tractat.kuilt.core.OpaqueFrame
import us.tractat.kuilt.core.SessionConfig
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Fast [HeartbeatConfig] for tests — much shorter than production defaults so
 * [runTest] virtual-time advancement stays small.
 */
val testHeartbeatConfig =
    HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

/**
 * In-memory N-peer mesh for scenario tests, modelling the host-as-hub topology (D-003).
 *
 * Created via [Mesh.build] (a suspend factory). All links start [FaultProfile.Healthy].
 *
 * **Multi-detector correctness:** The host's [FaultyPeerLink.incoming] is a channel-backed
 * cold flow — only one consumer sees each frame. To allow multiple [HeartbeatPartitionDetector]
 * instances to each observe the host's incoming stream, [Mesh.build] broadcasts the host's
 * incoming into a [MutableSharedFlow] and gives each detector a [FilteredPeerLink] that
 * filters the shared flow by sender. All detectors see all frames; each acts only on frames
 * from its monitored peer.
 *
 * [clock] drives all [HeartbeatPartitionDetector] instances so virtual time controls
 * timing without wall-clock dependency.
 */
class Mesh(
    val host: FaultyPeerLink,
    val joiners: List<FaultyPeerLink>,
    val detectors: List<HeartbeatPartitionDetector>,
    val factory: FaultyPeerLinkFactory,
) {
    val j0: FaultyPeerLink get() = joiners[0]
    val j1: FaultyPeerLink get() = joiners.getOrElse(1) { error("No joiner at index 1") }
    val j2: FaultyPeerLink get() = joiners.getOrElse(2) { error("No joiner at index 2") }

    val d0: HeartbeatPartitionDetector get() = detectors[0]
    val d1: HeartbeatPartitionDetector get() = detectors.getOrElse(1) { error("No detector at index 1") }
    val d2: HeartbeatPartitionDetector get() = detectors.getOrElse(2) { error("No detector at index 2") }

    /** Merged stream of all detector events across all peers. */
    val allEvents: Flow<PartitionEvent> = detectors.map { it.events }.merge()

    /** Start all detectors in [scope]. */
    fun startAllDetectors(scope: CoroutineScope) {
        detectors.forEach { it.start(scope) }
    }

    companion object {
        /**
         * Build a mesh with [peerCount] participants (1 host + [peerCount]-1 joiners).
         *
         * The host's incoming stream is broadcast into a [MutableSharedFlow] so all
         * detectors can independently observe it without consuming each other's frames.
         */
        suspend fun build(
            peerCount: Int,
            scope: CoroutineScope,
            clock: () -> Instant,
            config: HeartbeatConfig = testHeartbeatConfig,
            withPingResponders: Boolean = true,
        ): Mesh {
            require(peerCount >= 2) { "Mesh requires host + at least 1 joiner" }

            val factory = FaultyPeerLinkFactory(InMemoryPeerLinkFactory(), scope)
            val host = factory.open(SessionConfig("Host"))
            val joiners =
                (0 until peerCount - 1).map { i ->
                    factory.join(InMemoryPeerAdvertisement("J$i"))
                }

            // Broadcast the host's incoming into a shared flow so multiple detectors
            // can independently observe it. Channel.UNLIMITED means no frames are lost
            // while the replay buffer allows late subscribers to see recent frames.
            val hostIncomingShared = MutableSharedFlow<OpaqueFrame>(replay = 64, extraBufferCapacity = 256)
            scope.launch {
                host.incoming.collect { hostIncomingShared.emit(it) }
            }

            // Each detector gets a FilteredPeerLink: same host PeerLink for sends,
            // but the incoming flow is filtered to only frames from the monitored joiner.
            val detectors =
                joiners.map { joiner ->
                    val filteredLink = FilteredPeerLink(host, joiner.selfId, hostIncomingShared)
                    HeartbeatPartitionDetector(filteredLink, joiner.selfId, config, clock)
                }

            // Each joiner needs a ping responder: the heartbeat detector sends pings to
            // the joiner; the joiner must receive them and reply with pongs. Without a
            // responder running on each joiner, the host sees silence and all joiners
            // appear unresponsive even when their links are healthy.
            //
            // Responders share a per-joiner SharedFlow so both the responder and any
            // test-side collector can observe incoming frames independently.
            if (withPingResponders) {
                joiners.forEach { joiner ->
                    scope.launch {
                        joiner.incoming.collect { frame ->
                            val text = frame.payload.decodeToString()
                            val sender = frame.sender
                            if (sender != null && text.startsWith(HeartbeatPartitionDetector.PING_PREFIX)) {
                                runCatching {
                                    joiner.sendTo(sender, HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray())
                                }
                            }
                        }
                    }
                }
            }

            return Mesh(host, joiners, detectors, factory)
        }
    }
}
