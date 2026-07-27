package us.tractat.kuilt.conformance

import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Connection
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor

/**
 * The two-peer `peerMesh` satisfies the [SeamConformanceSuite].
 *
 * A peer-mesh over a single [connectionPair] is the degenerate `peers.size == 2` mesh. Beyond the
 * usual conformance obligations, this harness can **inject a mid-session transport death** — it
 * holds both ends of the underlying link, so [injectMidSessionDeath] drops the transport out from
 * under the live session, exercising [SeamConformanceSuite.incomingCompletesOnInjectedMidSessionDeath]
 * (the remote-disconnect half of the `incoming`-completes-on-Torn contract, which `peerMesh`'s
 * drain-latch honours).
 */
class PeerMeshConformanceTest : SeamConformanceSuite() {

    // The two ends of the in-memory link under the current pair, captured so injectMidSessionDeath
    // can drop the transport. Tests run one pair at a time, sequentially.
    private var hostConn: Connection? = null
    private var joinerConn: Connection? = null

    override fun newLoomPair(): Pair<Loom, Loom> {
        val (h, j) = connectionPair()
        hostConn = h
        joinerConn = j
        return PeerMeshLoom(PeerId("host"), h) to PeerMeshLoom(PeerId("joiner"), j)
    }

    /** In-memory: not a secured transport, and no path observer — mirrors [InMemoryLoomConformanceTest]. */
    override fun capabilities() =
        SeamCapabilities.FULL.copy(securesTransport = false, reportsLiveCapability = false)

    override fun capabilityGaps() = mapOf(
        "securesTransport" to CapabilityGaps.SECURES_TRANSPORT,
        "reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY,
    )

    override suspend fun injectMidSessionDeath(host: Seam, joiner: Seam): Boolean {
        // Drop BOTH ends so each side observes its peer's disconnect (a remote death, not a local
        // close()). Closing a peer's end completes the other's read loop → its last link drops →
        // peerMesh drains to empty → latches Torn + closes the inbound spool. Symmetric: both ends die.
        joinerConn?.close()
        hostConn?.close()
        return true
    }

    /** Proven: this harness overrides [injectMidSessionDeath] to drop the transport, so no gap. */
    override fun midSessionDeathGap(): String? = null

    /** Weaves a [peerMesh] over one [Connection] — a 2-peer peer-mesh (self + the remote it dials). */
    private class PeerMeshLoom(private val self: PeerId, private val conn: Connection) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam =
            peerMesh(
                selfId = self,
                connections = listOf(conn),
                dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]) {
                    "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
                },
            )
    }
}
