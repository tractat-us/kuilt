package us.tractat.kuilt.session.election

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SeamElectionLobbyTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    private fun lobby(seam: Seam, loom: InMemoryLoom, scope: CoroutineScope) =
        SeamElectionLobby(seam = seam, factory = factory(loom, scope), scope = scope,
            clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }, roomKey = null)

    @Test
    fun `all peers elect the same lowest-id host`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            // Both see 2 peers; both elect min(peers). Peer ids are "peer-1","peer-2" (InMemoryLoom).
            val h1 = l1.host.first()
            val h2 = l2.host.first { l2.peers.value.size == 2 }
            assertAll(
                { assertEquals(h1, h2) },
                { assertEquals(electHost(l1.peers.value), h1) },
                { assertEquals(2, l1.peers.value.size) },
            )
            l1.leave(); l2.leave()
        }

    @Test
    fun `host updates when a lower-id peer joins`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            assertEquals(s1.selfId, l1.host.first()) // alone → self is host

            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l2 = lobby(s2, loom, backgroundScope)
            // host is now min of both; assert both agree once the second peer is visible.
            val settled = l1.host.first { l1.peers.value.size == 2 }
            assertEquals(electHost(setOf(s1.selfId, s2.selfId)), settled)
            l1.leave(); l2.leave()
        }
}
