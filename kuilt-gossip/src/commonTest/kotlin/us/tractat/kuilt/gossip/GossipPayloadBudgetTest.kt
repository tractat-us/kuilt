@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.gossip

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.assertAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The gossip overlay publishes the budget its base names, less the relay header it adds (#2058).
 *
 * #2058's table filed `GossipSeam` under "delegate's unchanged — neither adds bytes", and that is
 * **wrong for `GossipSeam`**: `broadcast` wraps the payload in a [GossipFrame] (magic, version, TTL,
 * origin id, sequence) so receivers can dedup and re-flood it. Only `sendTo` passes through
 * unwrapped. [Seam.maxPayloadBytes] settles which of the two the published number follows — subtract
 * **unconditionally**, "even when the overhead is only paid on some routes", because a budget that
 * moved with the route is a TOCTOU trap for a caller that reads it and then sends.
 *
 * `PerPeerSeam` (the per-neighbour view in `GossipView.kt`) *is* the pass-through case: it re-filters
 * an existing fan-out and both its send paths delegate verbatim.
 */
class GossipPayloadBudgetTest {

    /**
     * The overlay reserves the relay header on every send, so its budget is strictly below its
     * base's — even though the `sendTo` route never pays it.
     */
    @Test
    fun theOverlayHoldsBackItsRelayHeaderOnEveryRoute() = runTest {
        val base = BudgetedSeam(initial = BASE_BUDGET)
        val overlay = gossipOver(base)

        val header = relayHeaderBytesFor(base.selfId)
        assertAll(
            {
                assertTrue(
                    header > 0,
                    "rig: a zero-byte relay header is the one cost at which 'subtracts its framing' cannot fail",
                )
            },
            {
                assertNotEquals(
                    BASE_BUDGET,
                    BASE_BUDGET - header,
                    "rig: the header must actually cost bytes, or a plain passthrough passes this",
                )
            },
            {
                assertEquals(
                    BASE_BUDGET - header,
                    overlay.maxPayloadBytes,
                    "broadcast wraps every payload in a GossipFrame, so those bytes come out of the caller's " +
                        "budget — unconditionally, even though sendTo passes through unwrapped",
                )
            },
        )
    }

    /** `null` means unknown, not unbounded — an overlay over an unbounded-unknown base invents none. */
    @Test
    fun anOverlayOverABaseThatNamesNoCeilingInventsNone() = runTest {
        val overlay = gossipOver(BudgetedSeam(initial = null))

        assertNull(overlay.maxPayloadBytes, "unknown, not unbounded — the overlay invents nothing")
    }

    /** A base tighter than the relay header floors at 0, not at a negative number. */
    @Test
    fun anOverlayFloorsAtZeroRatherThanPublishingANegativeBudget() = runTest {
        val base = BudgetedSeam(initial = STARVED_BUDGET)
        val overlay = gossipOver(base)

        assertAll(
            {
                assertTrue(
                    STARVED_BUDGET - relayHeaderBytesFor(base.selfId) < 0,
                    "rig: the unfloored arithmetic must actually go negative, or the floor is never exercised",
                )
            },
            {
                assertEquals(
                    0,
                    overlay.maxPayloadBytes,
                    "an overlay whose relay header outgrows the ceiling under it publishes 0 — floored, not negative",
                )
            },
        )
    }

    /**
     * The per-neighbour view adds no bytes on either send path, so it publishes its delegate's
     * number verbatim — and must not fall back to `null`, which would discard a bound one layer down.
     */
    @Test
    fun aPerPeerViewForwardsItsDelegatesBudgetUnchanged() = runTest {
        val delegate = BudgetedSeam(initial = BASE_BUDGET)
        val view = PerPeerSeam(delegate, PeerId("neighbour"), MutableSharedFlow<Swatch>().asSharedFlow())

        assertEquals(
            BASE_BUDGET,
            view.maxPayloadBytes,
            "a per-peer view wraps nothing, so it forwards its delegate's budget rather than giving up on it",
        )
    }

    private fun gossipOver(base: Seam): GossipSeam =
        GossipSeam(base = base, random = Random(7L), clock = { Instant.fromEpochMilliseconds(0L) })

    /**
     * The [GossipFrame] header's cost for an origin of [selfId] — measured by encoding an empty
     * payload rather than restating the wire layout, so a format change moves the expectation with
     * it instead of leaving this test asserting yesterday's arithmetic.
     */
    private fun relayHeaderBytesFor(selfId: PeerId): Int =
        GossipFrame.origin(origin = selfId, seq = 0L, ttl = 1, payload = ByteArray(0)).encode().size

    private companion object {
        const val BASE_BUDGET = 1000

        /** Narrower than the relay header, so the floor is actually reached. */
        const val STARVED_BUDGET = 4
    }
}

/**
 * A base [Seam] that publishes a fixed budget.
 *
 * Deliberately local rather than `us.tractat.kuilt.test.FakeSeam`, which inherits the interface's
 * `null` — against a `null` base, an overlay that forwards and one that gives up publish the same
 * value and every arm above would pass vacuously.
 */
private class BudgetedSeam(initial: Int?) : Seam {
    override val selfId: PeerId = PeerId("gossip-base")
    override val maxPayloadBytes: Int? = initial
    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    override val incoming: Flow<Swatch> = emptyFlow()

    override suspend fun broadcast(payload: ByteArray) = Unit

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) = Unit
}
