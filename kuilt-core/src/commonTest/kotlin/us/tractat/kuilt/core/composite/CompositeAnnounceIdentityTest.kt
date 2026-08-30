package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a `PlyFrame.Announce` is allowed to assert about composite identity (#1815).
 *
 * An `Announce` carries a composite [PeerId] the **sender** chose, and before this the handler wrote it
 * into `idMap` unconditionally. Three claims a peer must not get to make, and one it must keep making:
 *
 * - **The empty id.** `PeerId("")` reaching the published `peers` set propagates to every consumer that
 *   treats a peer id as a non-empty key.
 * - **Our own `selfId`.** `reachablePeersLocked` folds starting from `add(selfId)`, so this one is
 *   *absorbed*: the peer holds a live `idMap` entry and a routable ply while never appearing in `peers` —
 *   invisible, and `resolveSendTargets` would resolve `selfId` to it.
 * - **A second, different id from a slot it already claimed.** The first `Announce` from a given
 *   `(plyId, transportId)` pins that slot; a later one naming a different id is refused.
 * - …but an **identical** re-announce from a pinned slot must still be accepted, because it is a normal
 *   hot path: `attachPly` re-announces on every `Woven` transition *and* on peer-set growth.
 *
 * ### Why pinning does not break multipath bonding
 * *Many transport peers → one composite id* is the entire point of `idMap` and stays legal — each
 * `(plyId, transportId)` is its own slot and several slots converging on one composite id is what the fold
 * resolves ([severalTransportPeersMayBondOntoOneCompositeId]). Only *one transport peer → different
 * composite ids over time* is refused, and a genuinely restarted peer arrives on a **fresh** transport
 * connection, hence a fresh slot ([aReAttachedPlyMayLearnADifferentIdForTheSameTransportPeer]).
 *
 * ### The refusal is a drop, and `:kuilt-core` has no logger
 * A refused `Announce` is neither thrown nor reported through `onPlyFailure`: a peer's bad input is not
 * this ply's failure, and burning a `PlyReconcileException` on it would report a fault the composite does
 * not have. It is not silent either — a silent drop would make `peers` stall short of the expected set
 * with no observable saying why, and `CompositeSeam.peersStrandOrNull`'s diagnosis "`idMap` lacks the
 * entry ⇒ the announce was never processed" would have become plainly wrong. The refusal is recorded on
 * [PeersStrand.refusedAnnounces] instead, which is also the only observable that separates "the identical
 * re-announce was applied" from "it was refused" — see [anIdenticalReAnnounceFromAPinnedSlotIsAccepted].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeAnnounceIdentityTest {

    @Test
    fun aSecondAnnounceFromOnePinnedSlotNamingADifferentIdIsRefused() = runTest {
        val ply = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A))
        val composite = weaveOverPlies(UnconfinedTestDispatcher(testScheduler), PLY to ply)

        ply.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()
        ply.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(IMPOSTOR)))
        runCurrent()

        val strand = composite.strand()
        assertAll(
            {
                assertEquals(
                    setOf(composite.selfId, FIRST),
                    composite.peers.value,
                    "a live transport peer rebound its own slot onto a second composite identity",
                )
            },
            {
                assertEquals(
                    mapOf((PLY to TRANSPORT_A) to FIRST),
                    strand.idMap,
                    "the pin lives in idMap itself — the first Announce from a slot fixes it",
                )
            },
            {
                assertEquals(
                    listOf(PLY to TRANSPORT_A to IMPOSTOR),
                    strand.refusedAnnounces.map { it.plyId to it.transportId to it.claimed },
                    "the refusal must be recorded, naming the ply, the sender's transport id and the claim",
                )
            },
            {
                assertEquals(
                    listOf(RefusedAnnounce.Reason.REBIND),
                    strand.refusedAnnounces.map { it.reason },
                    "…and it must say WHY, or a reader cannot tell a rebind from a degenerate value",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * The direction a refusal-only guard passes vacuously.
     *
     * `attachPly` re-announces on every `Woven` transition and again whenever the ply's peer set grows, so
     * the *identical* re-announce is the common case, not an edge one. A guard written `if (slot in idMap)
     * refuse` satisfies the sibling test above and breaks this path — and it breaks it **invisibly**
     * through `peers`, because the slot is already pinned to the right value, so the published set is
     * unchanged either way. What actually goes missing is the `recomputePeers` request the accepted
     * announce makes. Hence the assertion is on the refusal record: production's own account of whether it
     * applied the frame or dropped it.
     */
    @Test
    fun anIdenticalReAnnounceFromAPinnedSlotIsAccepted() = runTest {
        val ply = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A))
        val composite = weaveOverPlies(UnconfinedTestDispatcher(testScheduler), PLY to ply)

        repeat(3) {
            ply.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(FIRST)))
            runCurrent()
        }

        val strand = composite.strand()
        assertAll(
            {
                assertEquals(
                    emptyList(),
                    strand.refusedAnnounces,
                    "an idempotent re-announce is a normal hot path and must not be refused",
                )
            },
            { assertEquals(0L, strand.refusedAnnounceCount, "…so nothing was counted either") },
            {
                assertEquals(
                    setOf(composite.selfId, FIRST),
                    composite.peers.value,
                    "the peer stays reachable across its own re-announces",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    @Test
    fun anAnnounceCarryingTheEmptyPeerIdIsDropped() = runTest {
        val ply = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A, TRANSPORT_B))
        val composite = weaveOverPlies(UnconfinedTestDispatcher(testScheduler), PLY to ply)

        ply.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(PeerId(""))))
        runCurrent()
        // A legitimate Announce AFTER it, on the same ply and therefore the same sequential inbound pump:
        // observing this one proves the empty-id frame was processed and not merely still in flight.
        ply.deliver(TRANSPORT_B, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()

        val strand = composite.strand()
        assertAll(
            {
                assertEquals(
                    setOf(composite.selfId, FIRST),
                    composite.peers.value,
                    "the empty composite id must never reach the published peer set",
                )
            },
            {
                assertNull(
                    strand.idMap[PLY to TRANSPORT_A],
                    "…nor be learned into idMap, where resolveSendTargets would still route to it",
                )
            },
            {
                assertEquals(
                    listOf(RefusedAnnounce.Reason.EMPTY),
                    strand.refusedAnnounces.map { it.reason },
                    "the drop must be recorded, not silent",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * The absorbed one. `reachablePeersLocked` folds from `add(selfId)`, so a peer announcing our own id
     * lands in a set that already contains it: `peers` looks untouched while the slot is live and routable.
     * The observable is therefore `idMap`, not `peers` — a test asserting only on `peers` here would pass
     * against the unfixed code.
     */
    @Test
    fun anAnnounceCarryingTheCompositesOwnSelfIdIsDropped() = runTest {
        val ply = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A, TRANSPORT_B))
        val composite = weaveOverPlies(UnconfinedTestDispatcher(testScheduler), PLY to ply)

        ply.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(composite.selfId)))
        runCurrent()
        ply.deliver(TRANSPORT_B, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()

        val strand = composite.strand()
        assertAll(
            {
                assertNull(
                    strand.idMap[PLY to TRANSPORT_A],
                    "a peer claiming our own selfId must not hold an idMap slot — invisible in peers, " +
                        "yet resolveSendTargets would resolve selfId to it",
                )
            },
            {
                assertEquals(
                    listOf(RefusedAnnounce.Reason.SELF),
                    strand.refusedAnnounces.map { it.reason },
                    "the drop must be recorded, not silent",
                )
            },
            {
                assertEquals(
                    mapOf((PLY to TRANSPORT_B) to FIRST),
                    strand.idMap,
                    "the well-behaved sender on the same ply is unaffected",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * Pinning must not touch multipath bonding, which is the whole reason `idMap` is keyed by a pair.
     * Two plies, two transport peers, one composite identity — the case that MUST stay legal.
     */
    @Test
    fun severalTransportPeersMayBondOntoOneCompositeId() = runTest {
        val fast = FakeSeam(selfId = PeerId("fast-self"), initialPeers = setOf(PeerId("fast-self"), TRANSPORT_A))
        val slow = FakeSeam(selfId = PeerId("slow-self"), initialPeers = setOf(PeerId("slow-self"), TRANSPORT_B))
        val composite = weaveOverPlies(UnconfinedTestDispatcher(testScheduler), PLY to fast, OTHER_PLY to slow)

        fast.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()
        slow.deliver(TRANSPORT_B, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()

        val strand = composite.strand()
        assertAll(
            {
                assertEquals(
                    setOf(composite.selfId, FIRST),
                    composite.peers.value,
                    "two plies bonded onto one composite peer is multipath bonding, not a rebind",
                )
            },
            {
                assertEquals(
                    mapOf((PLY to TRANSPORT_A) to FIRST, (OTHER_PLY to TRANSPORT_B) to FIRST),
                    strand.idMap,
                    "both slots must be learned — each (plyId, transportId) is its own pin",
                )
            },
            { assertTrue(strand.refusedAnnounces.isEmpty(), "bonding must refuse nothing") },
        )

        composite.close(CloseReason.Normal)
    }

    /**
     * The pin has exactly `idMap`'s lifetime, so it is released on detach. A genuinely restarted peer
     * arrives on a **fresh transport connection**, hence a fresh slot — this is the shape that proves the
     * refusal above is scoped to a *live* connection mutating an identity it already claimed, rather than
     * freezing a `(plyId, transportId)` pair forever.
     */
    @Test
    fun aReAttachedPlyMayLearnADifferentIdForTheSameTransportPeer() = runTest {
        val before = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A))
        val after = FakeSeam(selfId = PLY_SELF, initialPeers = setOf(PLY_SELF, TRANSPORT_A))
        val desired = MutableStateFlow(listOf(PLY to OneSeamLoom(before) as Loom))
        val composite = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host")) as CompositeSeam

        before.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(FIRST)))
        runCurrent()
        val learnedFirst = composite.peers.value

        // Detach, then re-attach the same PlyId over a fresh transport — two emissions, because one
        // reconcile pass detaches only ids absent from the desired set and skips ids already live.
        desired.value = emptyList()
        runCurrent()
        desired.value = listOf(PLY to OneSeamLoom(after) as Loom)
        runCurrent()

        after.deliver(TRANSPORT_A, PlyFrame.encode(PlyFrame.Announce(SECOND)))
        runCurrent()

        val strand = composite.strand()
        assertAll(
            { assertEquals(setOf(composite.selfId, FIRST), learnedFirst, "precondition: the first id was learned") },
            {
                assertTrue(
                    SECOND in composite.peers.value,
                    "a detach purges the slot, so a reconnecting peer may announce a fresh identity",
                )
            },
            {
                assertFalse(
                    FIRST in composite.peers.value,
                    "…and the identity it announced over the detached ply is gone with that ply",
                )
            },
            {
                assertTrue(
                    strand.refusedAnnounces.isEmpty(),
                    "a pin outliving its ply would refuse the reconnecting peer's announce",
                )
            },
        )

        composite.close(CloseReason.Normal)
    }

    private suspend fun weaveOverPlies(
        dispatcher: CoroutineContext,
        vararg plies: Pair<PlyId, Seam>,
    ): CompositeSeam =
        CompositeLoom(
            plies = plies.map { (id, seam) -> id to OneSeamLoom(seam) as Loom },
            dispatcher = dispatcher,
        ).host(Pattern("host")) as CompositeSeam

    /** The diagnostic snapshot, asserted to be readable — a busy lock here would be a test bug, not a finding. */
    private fun CompositeSeam.strand(): PeersStrand =
        requireNotNull(peersStrandOrNull()) { "peersStrandOrNull returned null — the lock was held" }

    /** A [Loom] weaving one given ply seam. */
    private class OneSeamLoom(private val seam: Seam) : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = seam

        override fun capability(): TransportCapability =
            TransportCapability(setOf(TransportRole.Data), FabricAvailability.Available)
    }

    private companion object {
        val PLY = PlyId("first-ply")
        val OTHER_PLY = PlyId("second-ply")
        val PLY_SELF = PeerId("ply-self")
        val TRANSPORT_A = PeerId("transport-a")
        val TRANSPORT_B = PeerId("transport-b")
        val FIRST = PeerId("remote-composite")
        val SECOND = PeerId("remote-composite-after-restart")
        val IMPOSTOR = PeerId("impostor-composite")
    }
}
