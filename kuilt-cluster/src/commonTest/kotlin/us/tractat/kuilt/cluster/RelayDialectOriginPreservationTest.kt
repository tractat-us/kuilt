@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end proof that the relay dialect keeps the Raft `from` intact **in both
 * directions** across the client [playerRelayTransport] ↔ server [RaftRelayHub]
 * boundary — the whole point of the #1360 cutover, and the property the old
 * identity-erasing `ManagedRaftTransport`/`LearnerRouter` dialect could not provide.
 *
 * The up leg (client → voter) must surface at the voter as `from = clientId`, and the
 * down leg (voter → client) must surface at the client as `from = voterId` — never the
 * relay server's id. Drives real [Seam]s over [InMemoryLoom] under
 * `UnconfinedTestDispatcher`; the transport + hub are the units under test.
 */
class RelayDialectOriginPreservationTest {

    @Test
    fun originSurvivesBothLegsOfTheClientToVoterRelay() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val voterA = NodeId("voter-a")

            // A 2-peer spoke: server side (host) collected by the hub, client side driven by the player transport.
            val loom = InMemoryLoom()
            val serverSpoke = loom.host(Pattern("spoke"))
            val clientSpoke = loom.join(InMemoryTag("spoke"))
            val clientId = NodeId(clientSpoke.selfId.value)

            // Server: the hub is the sole collector of the spoke; voterA's inbound is observed.
            val hub = RaftRelayHub(voters = setOf(voterA))
            val atVoterA = MutableSharedFlow<RaftEnvelope>(extraBufferCapacity = Int.MAX_VALUE)
            hub.registerVoterInbound(voterA, atVoterA)
            val voterReceived = mutableListOf<RaftEnvelope>()
            backgroundScope.launch { atVoterA.collect { voterReceived += it } }
            hub.addSpoke(clientId, serverSpoke, backgroundScope)

            // Client: the player relay transport over the spoke; voterA is the trusted origin.
            val clientTransport = playerRelayTransport(
                inner = NoPeerInner(clientId),
                relayChannel = clientSpoke,
                voters = { setOf(voterA) },
                scope = backgroundScope,
            )
            val clientReceived = mutableListOf<RaftEnvelope>()
            backgroundScope.launch { clientTransport.incoming.collect { clientReceived += it } }
            testScheduler.advanceUntilIdle()

            // Up leg: client proposes to voterA (not a direct peer ⇒ relayed).
            clientTransport.sendTo(voterA, "propose".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            // Down leg: voterA replies to the client.
            hub.sendToLearner(fromVoter = voterA, learnerId = clientId, bytes = "reply".encodeToByteArray())
            testScheduler.advanceUntilIdle()

            assertAll(
                { assertEquals(1, voterReceived.size, "the up leg reaches exactly the addressed voter") },
                { assertEquals(clientId, voterReceived.single().from, "up leg: from = the true client, not the relay") },
                { assertContentEquals("propose".encodeToByteArray(), voterReceived.single().bytes) },
                { assertEquals(1, clientReceived.size, "the down leg reaches the client") },
                { assertEquals(voterA, clientReceived.single().from, "down leg: from = the true voter, not the relay id") },
                { assertContentEquals("reply".encodeToByteArray(), clientReceived.single().bytes) },
            )
        }

    /** A no-peer inner transport: forces every send through the relay (mirrors the production wiring). */
    private class NoPeerInner(override val selfId: NodeId) : RaftTransport {
        override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
        override val incoming: Flow<RaftEnvelope> = emptyFlow()
        override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
    }
}
