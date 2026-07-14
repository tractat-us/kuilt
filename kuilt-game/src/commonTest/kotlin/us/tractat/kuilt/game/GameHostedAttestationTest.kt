@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.fabric.LinkAdmission
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.core.withPrincipal
import us.tractat.kuilt.gossip.GossipSeam
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * End-to-end acceptance for the hosted-path attestation design (#839, restated for the roster
 * landing spot): a principal attached at accept time is observable on the [GameSession] for the
 * admitted peer, and a spoofed `MeshHello` (verified principal ↔ claimed PeerId mismatch) under a
 * binding [LinkAdmission] never joins — the seat it tried to steal stays open for the legitimate
 * player.
 *
 * Drives [gameHosted] over an [InMemoryConnectionSource] under virtual time — the same accept
 * path a real `KtorConnectionSource` feeds, minus the wire (principals attached directly via
 * [withPrincipal] stand in for the extractor).
 */
class GameHostedAttestationTest {

    @Test
    fun principalsLandOnTheSessionAndSpoofedHelloNeverJoins() =
        runTest(StandardTestDispatcher(), timeout = 30.seconds) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }
            val source = InMemoryConnectionSource()
            // The consumer's binding policy: the verified principal must be "user-<claimed id>".
            val binding = LinkAdmission { principal, remoteId -> principal?.value == "user-${remoteId.value}" }

            val hostDeferred = async {
                backgroundScope.gameHosted(
                    selfId = PeerId("hub"),
                    source = source,
                    peerCount = 3,
                    raftConfig = fastRaftConfig(seed = 1L),
                    clock = clock,
                    admission = binding,
                )
            }
            runCurrent()

            // client-0: attested to the id it claims — admitted.
            val client0 = joinClient(source, "client-0", dispatcher, clock, seedBase = 10L)
            advanceTimeBy(1000)
            runCurrent()

            // The spoof: verified as mallory, MeshHello claims client-1 — rejected at the hub mesh
            // before the link is published; its connection is closed and the hub keeps serving.
            val (spoofHubEnd, spoofClientEnd) = connectionPair()
            val spoofMesh = backgroundScope.async {
                hubMesh(PeerId("client-1"), listOf(spoofClientEnd), dispatcher, Random(20L))
            }
            source.offer(spoofHubEnd.withPrincipal(Principal("user-mallory")))
            advanceTimeBy(500)
            runCurrent()
            // The spoofed link handshakes and is then torn down: the spoof's own mesh briefly saw
            // the hub during the preamble and now sees it gone — it never held a live link.
            spoofMesh.await().peers.first { PeerId("hub") !in it }

            // client-1 (legit): the seat the spoof failed to steal is still open.
            val client1 = joinClient(source, "client-1", dispatcher, clock, seedBase = 30L)
            advanceTimeBy(2000)
            runCurrent()

            val host = hostDeferred.await()
            val client0Session = client0.await()
            client1.await()

            assertAll(
                { assertEquals(Principal("user-client-0"), host.principalFor(PeerId("client-0"))) },
                {
                    assertEquals(
                        Principal("user-client-1"),
                        host.principalFor(PeerId("client-1")),
                        "the legitimate client — not the spoof — must hold the client-1 seat",
                    )
                },
                {
                    assertEquals(
                        setOf(PeerId("client-0"), PeerId("client-1")),
                        host.attestedPrincipals.value.keys,
                        "exactly the two admitted, attested peers appear on the session roster",
                    )
                },
                { assertEquals(null, client0Session.principalFor(PeerId("hub")), "a spoke seam has no roster") },
            )
        }

    /**
     * Offer an attested hub-end for [id] (principal `user-<id>`) and join the game from the client
     * end over a fresh [GossipSeam]. Returns the join as a [Deferred] so the host's admission loop
     * and the client's admit wait run concurrently.
     */
    private fun TestScope.joinClient(
        source: InMemoryConnectionSource,
        id: String,
        dispatcher: CoroutineContext,
        clock: () -> Instant,
        seedBase: Long,
    ): Deferred<GameSession> {
        val (hubEnd, clientEnd) = connectionPair()
        val join = backgroundScope.async {
            val gossip = GossipSeam(
                base = hubMesh(PeerId(id), listOf(clientEnd), dispatcher, Random(seedBase)),
                random = Random(seedBase + 1),
                clock = clock,
            ).also { it.start(backgroundScope) }
            backgroundScope.gameJoin(gossip, raftConfig = fastRaftConfig(seed = seedBase + 2))
        }
        source.offer(hubEnd.withPrincipal(Principal("user-$id")))
        return join
    }
}
