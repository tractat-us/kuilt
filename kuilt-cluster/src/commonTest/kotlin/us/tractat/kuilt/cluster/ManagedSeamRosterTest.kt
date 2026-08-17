@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.cluster

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftEnvelope
import us.tractat.kuilt.raft.RaftTransport
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ManagedSeam]'s published roster, across swaps (#2436).
 *
 * `Seam.peers` is *"Live set of peers currently connected. Includes `selfId`"*, and it carries a
 * second obligation this seam is the awkward case for: *"A peer in `peers` must be addressable by
 * `sendTo` … an implementation that publishes a peer it has no route to is the bug, not the caller
 * that believed it."*
 *
 * [ManagedSeam] publishes its **own** `selfId` — a constructor parameter, the client's stable id,
 * deliberately unchanged across reconnects — while delegating traffic to a backing seam whose
 * `selfId` is the fabric's own choosing. So its roster is neither its own nor the backing seam's
 * verbatim: it is the backing seam's **remotes**, re-attributed to this seam's identity. Copying
 * the backing roster through breaches both clauses at once — it drops `selfId`, and it publishes
 * the backing seam's id, which nothing can address (a `sendTo` to it is refused by the backing
 * seam and swallowed into a debug line here).
 *
 * Every fixture below therefore gives the backing seam an id that **differs** from the managed one.
 * They coincide in the intended `clusterClient` wiring (`KtorClientLoom(selfPeerId = clientNodeId)`
 * — see `ClusterClientFailoverE2ETest`), and a fixture that inherits that alignment cannot tell a
 * re-attributed roster from a copied one: every assertion here passes either way.
 */
class ManagedSeamRosterTest {

    /** The MANAGED id — what this seam publishes and what a caller addresses it by. */
    private val stableId = PeerId("stable-client")

    /** The BACKING seam's id — fabric-minted, deliberately different. */
    private val backingSelf = PeerId("fabric-minted-id")

    private val relay = PeerId("relay-server")

    private fun backingSeam(remote: PeerId) =
        FakeSeam(selfId = backingSelf, initialPeers = setOf(backingSelf, remote))

    /**
     * The continuous-monitor property `SeamConformanceSuite.monitorSelfAlwaysInPeers` would run if
     * [ManagedSeam] had a harness: `selfId` is in **every** emission, not merely in the settled
     * value. Two write sites have to honour it — [ManagedSeam.swap]'s synchronous priming write and
     * the per-swap tracker coroutine — and only a collector spanning both can see them both.
     */
    @Test
    fun selfIdIsInEveryPeersEmissionAcrossSwapsAndBackingRosterChanges(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)
            val observed = mutableListOf<Set<PeerId>>()
            val monitor = backgroundScope.launch { managed.peers.collect { observed += it } }
            testScheduler.runCurrent()

            val first = backingSeam(relay)
            managed.swap(first)
            // Sampled before the scheduler runs: this is `swap`'s own synchronous write, distinct
            // from the tracker's. A sample taken only after `runCurrent()` cannot tell them apart.
            val rightAfterSwap = managed.peers.value
            testScheduler.runCurrent()

            // A roster change on the live backing seam — the per-swap tracker's write.
            first.addPeer(PeerId("co-spoke"))
            testScheduler.runCurrent()

            val second = backingSeam(PeerId("relay-b"))
            managed.swap(second)
            testScheduler.runCurrent()
            monitor.cancel()

            assertAll(
                {
                    assertNotEquals(
                        stableId,
                        backingSelf,
                        "precondition: the managed id and the backing seam's id must DIFFER, or a " +
                            "copied roster is indistinguishable from a re-attributed one",
                    )
                },
                {
                    assertTrue(
                        stableId in rightAfterSwap,
                        "swap's own write must keep selfId in the roster; got $rightAfterSwap",
                    )
                },
                {
                    val offending = observed.filterNot { stableId in it }
                    assertTrue(
                        offending.isEmpty(),
                        "every peers emission must contain selfId ($stableId); these did not: $offending",
                    )
                },
                {
                    // The rig fired: four distinct rosters (initial, swap-1, tracker, swap-2). A
                    // StateFlow dedups equal values, so a monitor that saw fewer than four sampled
                    // fewer than four states and the property above would be green by absence.
                    assertTrue(
                        observed.size >= 4,
                        "the monitor must have sampled the initial roster, both swaps and the " +
                            "tracker's update; saw ${observed.size}: $observed",
                    )
                },
            )
        }

    /**
     * *"A peer in `peers` must be addressable by `sendTo`"* — asserted over the roster this seam
     * **actually publishes**, by sending to every id in it and checking each frame lands. Written
     * that way rather than as `backingSelf !in peers` on purpose: it is the contract clause itself,
     * so it reds for any unaddressable id the roster grows, not only for the one known today.
     *
     * The failure it catches is silent. [ManagedSeam.sendTo] wraps the delegated send in
     * `runCatchingCancellable { … }.onFailure { log.debug { … } }`, so the backing seam's refusal
     * of its own id never reaches the caller — the frame simply disappears.
     */
    @Test
    fun everyPeerTheRosterNamesIsAddressableSoTheBackingSeamsOwnIdIsNotAmongThem(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)
            val first = backingSeam(relay)
            managed.swap(first)
            testScheduler.runCurrent()

            val rosterAfterSwap = managed.peers.value
            val remotes = rosterAfterSwap - stableId
            remotes.forEach { managed.sendTo(it, byteArrayOf(1)) }
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        remotes.isNotEmpty(),
                        "the rig fired: there is at least one non-self id in the roster to address",
                    )
                },
                {
                    assertEquals(
                        remotes,
                        first.directed.map { it.first }.toSet(),
                        "every peer the roster names must be addressable — these were named but " +
                            "carried nothing: ${remotes - first.directed.map { it.first }.toSet()}",
                    )
                },
                {
                    assertEquals(
                        setOf(stableId, relay),
                        rosterAfterSwap,
                        "the roster is the backing seam's remotes re-attributed to THIS seam's id",
                    )
                },
            )
        }

    /**
     * The downstream consequence, and the reason this is not a paperwork breach.
     * `RoutedRaftTransport.playerServerHop` picks a player's single upstream server as *the sole
     * non-self peer of the relay channel* — filtering against the **client's** node id. A roster
     * that names the backing seam's id instead of the client's leaves **two** candidates, which
     * that method reads as a mis-wired multi-peer relay channel and drops every relayed send.
     */
    @Test
    fun aPlayerRelaySendFindsItsSingleHopWhenTheBackingSeamsIdDiffers(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val clientNode = NodeId(stableId.value)
            val leader = NodeId("distant-leader-voter")
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)
            val transport = playerRelayTransport(
                inner = NoPeerInner(clientNode),
                relayChannel = managed,
                voters = { setOf(leader) },
                scope = backgroundScope,
            )
            val first = backingSeam(relay)
            managed.swap(first)
            testScheduler.runCurrent()

            val payload = "forward-to-leader".encodeToByteArray()
            transport.sendTo(leader, payload)
            testScheduler.runCurrent()

            assertEquals(
                1,
                first.directed.size,
                "the relay hop must be found and exactly one wrapped frame handed to it; " +
                    "roster was ${managed.peers.value}",
            )
            val (hop, bytes) = first.directed.single()
            val relayed = RaftRelay.decode(bytes)
            assertAll(
                { assertEquals(relay, hop, "the sole remote is the next hop") },
                { assertEquals(leader, relayed.dest, "the wrap carries dest = the leader") },
                { assertEquals(clientNode, relayed.origin, "origin = the true client") },
                { assertContentEquals(payload, relayed.bytes, "the payload rides inside the envelope") },
            )
        }

    /**
     * After [ManagedSeam.close] there is no backing seam again, and every send is dropped — so the
     * roster owes the same `{ selfId }` the constructor publishes before the first swap. Leaving
     * the closed seam's remotes behind would advertise peers with no route, the same breach the
     * swap path had.
     */
    @Test
    fun closingCollapsesTheRosterBackToSelfBecauseThereIsNoBackingSeamLeft(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val managed = ManagedSeam(scope = backgroundScope, selfId = stableId)
            val first = backingSeam(relay)
            managed.swap(first)
            testScheduler.runCurrent()
            val rosterWhileBacked = managed.peers.value

            managed.close(CloseReason.Normal)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        relay in rosterWhileBacked,
                        "precondition: the remote WAS published while a backing seam was installed",
                    )
                },
                {
                    assertEquals(
                        setOf(stableId),
                        managed.peers.value,
                        "a closed ManagedSeam reaches nobody, so its roster is exactly { selfId }",
                    )
                },
            )
        }

    /** A no-peer inner transport: forces every send through the relay channel. */
    private class NoPeerInner(override val selfId: NodeId) : RaftTransport {
        override val peers: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet())
        override val incoming: Flow<RaftEnvelope> = emptyFlow()
        override suspend fun sendTo(peer: NodeId, message: ByteArray) = Unit
    }
}
