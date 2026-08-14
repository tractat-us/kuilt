package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.Mesh
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.core.withPrincipal
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * The flat in-memory mesh ([meshSeam]) satisfies the principal-attestation contract: a link admitted
 * carrying a [Principal] (via [withPrincipal]) is reported on the mesh's [PrincipalRoster], updates on
 * reconnect, and clears on drop/close.
 *
 * The harness models each admitted peer as a real far-end 2-node mesh over one [connectionPair]: the
 * hub-end connection is stamped with the peer's principal, and the far end handshakes concurrently so
 * the `MeshHello` preambles cross (serial construction would deadlock). Dropping a peer closes its
 * far-end seam, tearing the hub-side link.
 */
class MeshPrincipalAttestationTest : PrincipalAttestationConformanceSuite() {

    override suspend fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        random: Random,
    ): AttestationHarness = MeshHarness(
        scope,
        dispatcher,
        hub = hubMesh(PeerId("hub"), emptyList(), dispatcher, random),
    )

    private class MeshHarness(
        private val scope: CoroutineScope,
        private val dispatcher: CoroutineContext,
        private val hub: Mesh,
    ) : AttestationHarness {

        override val seam: Seam get() = hub

        override val roster: PrincipalRoster get() = hub

        /** Far-end seams, one per admitted peer — kept so [drop] can tear the link. */
        private val farByPeer = mutableMapOf<PeerId, Mesh>()

        /**
         * Second, concurrent far ends claiming an already-live peer id. Held apart from [farByPeer]
         * so an impostor never becomes the seam [drop] tears — [admitConcurrentClaim] exists
         * precisely because the *legitimate* link stays up.
         */
        private val impostors = mutableListOf<Mesh>()
        private var seed = 100

        /**
         * The mesh preamble carries only a [PeerId], so the joiner's channel for asserting an
         * identity is the frame body: the far end broadcasts `claimed` as the first thing it says
         * after the handshake. A hub that re-stamped its roster from an inbound frame — or that took
         * the self-asserted preamble id as an identity, which the suite spells into [peer] — would
         * report it.
         */
        override suspend fun admitClaiming(peer: PeerId, verified: Principal?, claimed: Principal) {
            admit(peer, verified)
            farByPeer.getValue(peer).broadcast(claimed.value.encodeToByteArray())
        }

        override suspend fun admit(peer: PeerId, principal: Principal?) {
            if (peer in hub.peers.value) drop(peer) // a repeat admit is a reconnect: replace the link
            val (hubEnd, farEnd) = connectionPair()
            // The far end is a real mesh so its MeshHello crosses the hub's — run it concurrently.
            val far: Deferred<Mesh> = scope.async {
                hubMesh(peer, listOf(farEnd), dispatcher, Random((seed++).toLong()))
            }
            hub.addLink(hubEnd.withPrincipal(principal))
            farByPeer[peer] = far.await()
            hub.peers.first { peer in it }
        }

        /**
         * A *second* link to the hub announcing [peer]'s id and carrying no attestation, admitted
         * while [peer]'s legitimate link stays open — so the two contend in the mesh's duplicate-link
         * dedup rather than one replacing the other by teardown.
         *
         * **The impostor is rigged to WIN that contest, deterministically.** Its preamble nonce is
         * all-zero bytes ([AllZeroNonces]), so its canonical link nonce — the sorted hex pair both
         * ends derive — is strictly the smallest unless the hub itself drew an all-zero nonce, which
         * a seeded [Random] does not. This is the same craft `MeshAdmissionTest`'s
         * `controlSpoofedLinkWinsDedupLotteryUnderAcceptAll` uses, and for the same reason: a
         * property about what survives a displacement is worth nothing if the attacker quietly lost
         * the flip. The rig proves itself — a loser's connection is closed by the hub, so the claim
         * broadcast below would never reach the suite and the property would wedge rather than pass.
         */
        override suspend fun admitConcurrentClaim(peer: PeerId, claimed: Principal) {
            val (hubEnd, farEnd) = connectionPair()
            val far: Deferred<Mesh> = scope.async { hubMesh(peer, listOf(farEnd), dispatcher, AllZeroNonces) }
            hub.addLink(hubEnd) // unattested: no withPrincipal
            val impostor = far.await()
            impostors += impostor
            impostor.broadcast(claimed.value.encodeToByteArray())
        }

        override suspend fun drop(peer: PeerId) {
            farByPeer.remove(peer)?.close()
            hub.peers.first { peer !in it }
        }

        override suspend fun close() {
            hub.close()
        }
    }

    /**
     * A [Random] that hands out nothing but zero bytes — a far-end mesh built on it sends an
     * all-zero `MeshHello` nonce, which is the smallest the canonical dedup identity can be.
     *
     * Deliberately degenerate, and used at exactly one call site: [MeshHarness.admitConcurrentClaim]
     * needs the impostor's link to win the dedup tiebreak *by construction*, not by the luck of a
     * seed, so that the property it feeds is testing a displacement that really happened. Every
     * other far end keeps its seeded [Random].
     */
    private object AllZeroNonces : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray =
            array.also { it.fill(0, fromIndex, toIndex) }
    }
}
