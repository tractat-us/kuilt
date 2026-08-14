package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.PrincipalRoster
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * The per-room mux hub ([us.tractat.kuilt.core.RoomHubSeam], reached through
 * [us.tractat.kuilt.core.MuxServerLoom]) satisfies the principal-attestation contract — the path
 * that once shipped the hole. A client whose server-end connection is stamped with a [Principal]
 * (via [InMemoryRoomFabric.clientSeam]) has that identity reported on the room's
 * [PrincipalRoster], keyed by the client's peer id, updated on reconnect and cleared on drop/close.
 *
 * The harness hosts one room over an [InMemoryRoomFabric] server and admits a peer by connecting a
 * client and sending its first frame on the room's channel (which is what registers it). Dropping a
 * peer closes its client seam, tearing the server-side link.
 */
class RoomHubSeamPrincipalAttestationTest : PrincipalAttestationConformanceSuite() {

    override suspend fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        random: Random,
    ): AttestationHarness {
        val fabric = InMemoryRoomFabric(scope, dispatcher, random = random)
        val room = fabric.serverLoom.host(Pattern(ROOM))
        return RoomHubHarness(scope, fabric, room)
    }

    private class RoomHubHarness(
        private val scope: CoroutineScope,
        private val fabric: InMemoryRoomFabric,
        private val room: Seam,
    ) : AttestationHarness {

        override val seam: Seam get() = room

        override val roster: PrincipalRoster get() = room as PrincipalRoster

        /** Client seams, one per admitted peer — kept so [drop] can tear the link. */
        private val clientByPeer = mutableMapOf<PeerId, Seam>()
        private var seed = 200

        override suspend fun admit(peer: PeerId, principal: Principal?): Unit =
            connect(peer, verified = principal, firstFrame = byteArrayOf())

        /**
         * The joiner's channel for asserting an identity is the very frame that registers it into
         * the room — the hop where [us.tractat.kuilt.core.MuxServerLoom] pairs a connection's
         * payload with the principal it read off that connection. Sending `claimed` there is what
         * makes "the hub believed the client's announcement instead of the host's stamp" a
         * *reachable* state for this fabric, rather than one the suite has to take on trust.
         */
        override suspend fun admitClaiming(peer: PeerId, verified: Principal?, claimed: Principal): Unit =
            connect(peer, verified, firstFrame = claimed.value.encodeToByteArray())

        private suspend fun connect(peer: PeerId, verified: Principal?, firstFrame: ByteArray) {
            if (peer in room.peers.value) drop(peer) // a repeat admit is a reconnect: replace the link
            val client = fabric.clientSeam(peer, Random((seed++).toLong()), verified)
            clientByPeer[peer] = client
            // The first frame on the room's channel is what registers the connection into the room.
            NamedMux(client, scope).channel(ROOM).broadcast(firstFrame)
            room.peers.first { peer in it }
        }

        override suspend fun drop(peer: PeerId) {
            clientByPeer.remove(peer)?.close()
            room.peers.first { peer !in it }
        }

        override suspend fun close() {
            room.close()
        }
    }

    private companion object {
        const val ROOM = "table-7"
    }
}
