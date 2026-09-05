@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [CompositeSeam] publishes the tightest budget across its **attached** plies, less its own
 * `PlyFrame.Data` envelope (#2058).
 *
 * Both halves matter and they fail differently. One payload is wrapped once and handed to *every*
 * live ply on a `broadcast` (and to any of them in turn on a `sendTo` fall-through), so a frame
 * that overflows one ply is over budget for the composite — hence the minimum. And the envelope is
 * paid on every send, so its bytes come out of the caller's budget rather than being added to the
 * wire.
 *
 * The ply set is **live**, so the number moves as plies attach and detach — the composite is the
 * one decorator in #2058 whose fold has to be recomputed rather than captured.
 */
class CompositePayloadBudgetTest {

    /**
     * A **multi-ply** composite over plies with different ceilings publishes the tightest, less the
     * envelope. A single-ply fixture could not tell "the minimum across plies" from "the one ply's",
     * so the rig asserts there really are two.
     */
    @Test
    fun aCompositeIsBoundedByItsTightestPlyLessItsEnvelope() = runTest {
        val seam = compositeOver(
            PlyId("roomy") to WIDE_BUDGET,
            PlyId("cramped") to NARROW_BUDGET,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        seam.state.first { it is SeamState.Woven }

        val envelope = envelopeBytesFor(seam.selfId)
        assertAll(
            {
                assertEquals(
                    2,
                    seam.plies.value.size,
                    "rig: a single-ply composite cannot distinguish 'the tightest ply' from 'the only ply'",
                )
            },
            { assertTrue(NARROW_BUDGET < WIDE_BUDGET, "rig: the plies must differ, or min and max agree") },
            { assertTrue(envelope > 0, "rig: a zero-byte envelope is the one cost at which the subtraction cannot fail") },
            {
                assertEquals(
                    NARROW_BUDGET - envelope,
                    seam.maxPayloadBytes,
                    "a composite publishes its tightest ply's budget less the PlyFrame envelope it wraps every " +
                        "payload in — one payload goes to every live ply",
                )
            },
        )

        seam.close()
    }

    /**
     * A ply that names no ceiling does not erase what the others know — the call `MeshSeam` already
     * makes across a mesh of one bounded and one unknown link.
     */
    @Test
    fun aPlyThatNamesNoCeilingDoesNotEraseTheOnesThatDo() = runTest {
        val seam = compositeOver(
            PlyId("bounded") to WIDE_BUDGET,
            PlyId("unknown") to null,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        seam.state.first { it is SeamState.Woven }

        assertEquals(
            WIDE_BUDGET - envelopeBytesFor(seam.selfId),
            seam.maxPayloadBytes,
            "an unknown ply contributes no bound; the bounded ply's is still the composite's",
        )

        seam.close()
    }

    /** Every ply is unknown, so the composite has nothing to be bounded by and invents nothing. */
    @Test
    fun aCompositeOverWhollyUnknownPliesNamesNothing() = runTest {
        val seam = compositeOver(
            PlyId("a") to null,
            PlyId("b") to null,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        seam.state.first { it is SeamState.Woven }

        assertNull(seam.maxPayloadBytes, "unknown, not unbounded — the composite invents nothing")

        seam.close()
    }

    /**
     * The fold is over the **live** ply set, so detaching the tight ply loosens the composite. A
     * composite that captured its plies at construction would keep publishing the tighter number
     * for a transport it no longer holds.
     */
    @Test
    fun detachingTheTightPlyLoosensTheCompositeBudget() = runTest {
        val roomy = PlyId("roomy") to (BudgetedLoom(InMemoryLoom(), WIDE_BUDGET) as Loom)
        val cramped = PlyId("cramped") to (BudgetedLoom(InMemoryLoom(), NARROW_BUDGET) as Loom)
        val desired = MutableStateFlow(listOf(roomy, cramped))
        val seam = CompositeLoom(
            plies = desired,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).host(Pattern("host"))
        seam.state.first { it is SeamState.Woven }

        val envelope = envelopeBytesFor(seam.selfId)
        val whileCramped = seam.maxPayloadBytes

        desired.value = listOf(roomy)
        seam.plies.first { it.keys == setOf(PlyId("roomy")) }
        val afterDetach = seam.maxPayloadBytes

        assertAll(
            {
                assertNotEquals(
                    NARROW_BUDGET,
                    WIDE_BUDGET,
                    "rig: the two plies must differ, or a detach cannot move the number",
                )
            },
            { assertEquals(NARROW_BUDGET - envelope, whileCramped, "the tight ply bounds the composite while attached") },
            {
                assertEquals(
                    WIDE_BUDGET - envelope,
                    afterDetach,
                    "detaching the tight ply must loosen the budget — the fold is over the LIVE ply set",
                )
            },
        )

        seam.close()
    }

    /**
     * A ply tighter than the envelope floors at **0**, not at a negative number: nothing this
     * composite can wrap fits, and 0 is how [Seam.maxPayloadBytes] says so.
     */
    @Test
    fun aCompositeFloorsAtZeroRatherThanPublishingANegativeBudget() = runTest {
        val seam = compositeOver(
            PlyId("starved") to STARVED_BUDGET,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        seam.state.first { it is SeamState.Woven }

        assertAll(
            {
                assertTrue(
                    STARVED_BUDGET - envelopeBytesFor(seam.selfId) < 0,
                    "rig: the unfloored arithmetic must actually go negative, or the floor is never exercised",
                )
            },
            {
                assertEquals(
                    0,
                    seam.maxPayloadBytes,
                    "a composite whose envelope outgrows its tightest ply publishes 0 — floored, not negative",
                )
            },
        )

        seam.close()
    }

    private suspend fun compositeOver(
        vararg plies: Pair<PlyId, Int?>,
        dispatcher: kotlin.coroutines.CoroutineContext,
    ): Seam = CompositeLoom(
        plies = plies.map { (id, budget) -> id to (BudgetedLoom(InMemoryLoom(), budget) as Loom) },
        dispatcher = dispatcher,
    ).host(Pattern("host"))

    /**
     * The `PlyFrame.Data` envelope's cost for a composite whose own id is [selfId] — computed by
     * encoding an empty payload rather than restating the layout, so a wire change moves the
     * expectation with it instead of leaving this test asserting yesterday's arithmetic.
     */
    private fun envelopeBytesFor(selfId: PeerId): Int =
        PlyFrame.encode(PlyFrame.Data(originId = selfId, originSeq = 0L, payload = ByteArray(0))).size

    private companion object {
        const val WIDE_BUDGET = 4096
        const val NARROW_BUDGET = 1024

        /** Narrower than any composite envelope, so the floor is actually reached. */
        const val STARVED_BUDGET = 3
    }
}

/** A [Loom] whose woven seams publish [budget] and delegate everything else to [delegate]'s. */
private class BudgetedLoom(private val delegate: Loom, private val budget: Int?) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = BudgetedPly(delegate.weave(rendezvous), budget)

    override fun capability(): TransportCapability = delegate.capability()
}

/**
 * A ply seam that publishes [maxPayloadBytes] and is otherwise its [delegate].
 *
 * Deliberately local: [InMemoryLoom]'s seam correctly names no ceiling, and against a wholly
 * unknown ply set a composite that folds and one that gives up publish the identical `null`.
 */
private class BudgetedPly(
    private val delegate: Seam,
    override val maxPayloadBytes: Int?,
) : Seam {
    override val selfId: PeerId get() = delegate.selfId
    override val peers: StateFlow<Set<PeerId>> get() = delegate.peers
    override val state: StateFlow<SeamState> get() = delegate.state
    override val plies: StateFlow<Map<PlyId, SeamState>> get() = delegate.plies
    override val capability: StateFlow<TransportCapability> get() = delegate.capability
    override val incoming: Flow<Swatch> get() = delegate.incoming

    override suspend fun broadcast(payload: ByteArray): Unit = delegate.broadcast(payload)

    override suspend fun sendTo(peer: PeerId, payload: ByteArray): Unit = delegate.sendTo(peer, payload)

    override suspend fun close(reason: CloseReason): Unit = delegate.close(reason)
}
