@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.fabric.MeshWire
import us.tractat.kuilt.core.fabric.hubMesh
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryConnectionSource
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [Seam] decorator publishes the budget it can derive from what it wraps, less its own framing
 * (#2058) — it does not fall back to the interface's `null`.
 *
 * `null` is *safe* (it means "unknown", so nobody is misled) but lossy: a decorator over a bounded
 * fabric throws away a bound the fabric underneath does know, and the consumer above it is left
 * sizing blind. Every arm here therefore rigs the wrapped seam to publish a **number**, because
 * against a `null`-publishing base the fixed and the broken decorator agree and the property cannot
 * fail.
 *
 * The subtraction is unconditional, per [Seam.maxPayloadBytes]: a decorator that only pays its
 * header on *some* routes still holds the bytes back on all of them, because a budget that moved
 * with routing would be a TOCTOU trap for a caller that read it and then sent.
 *
 * ## Negative is floored to **0**, never to 1
 *
 * `0` is a legitimate published budget — it is the honest answer from a decorator whose framing
 * eats the whole ceiling under it, and it is the idiom [Seam.maxPayloadBytes] prescribes
 * (`(it - cost).coerceAtLeast(0)`), matching `SeamRoom`, `RoomChannel`, `MeshSeam` and `NwSeam`.
 * The `coerceAtLeast(1)` in the cookbook's chunking sample is a **caller-side** floor — a chunk
 * loop must not spin on a zero-length chunk — and copying it here would publish a promise that one
 * byte crosses when nothing does.
 */
class DecoratorPayloadBudgetTest {

    // ── Mux channel views ────────────────────────────────────────────────────────

    /**
     * [MuxSeam] prefixes one tag byte, so a channel view over a bounded base publishes one byte
     * less than the base.
     */
    @Test
    fun aMuxSeamChannelViewHoldsBackItsTagByte() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = BASE_BUDGET)

        val view = MuxSeam(base, backgroundScope).channel(TAG)

        assertAll(
            {
                assertTrue(
                    BYTE_TAG_OVERHEAD > 0,
                    "rig: a zero-byte header is the one overhead at which 'subtracts its framing' cannot fail",
                )
            },
            {
                assertEquals(
                    BASE_BUDGET - BYTE_TAG_OVERHEAD,
                    view.maxPayloadBytes,
                    "a MuxSeam channel view must publish the base's budget less its own tag byte, not null",
                )
            },
        )
    }

    /**
     * [NamedMux] prefixes `[len:1][name UTF-8]`, so its reservation is wider than [MuxSeam]'s and
     * varies with the channel name. Asserted against *both* the base's own number and the byte-tag
     * reservation, so neither "forwarded unchanged" nor "subtracted somebody else's header" passes.
     */
    @Test
    fun aNamedMuxChannelViewHoldsBackItsNameHeader() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = BASE_BUDGET)

        val view = NamedMux(base, backgroundScope).channel(CHANNEL_NAME)

        val expected = BASE_BUDGET - NAMED_OVERHEAD
        assertAll(
            {
                assertNotEquals(
                    BASE_BUDGET,
                    expected,
                    "rig: the name header must cost bytes, or a plain passthrough passes this",
                )
            },
            {
                assertNotEquals(
                    BASE_BUDGET - BYTE_TAG_OVERHEAD,
                    expected,
                    "rig: the name header must differ from MuxSeam's tag byte, or the two framings are " +
                        "indistinguishable here",
                )
            },
            {
                assertEquals(
                    expected,
                    view.maxPayloadBytes,
                    "a NamedMux channel view must publish the base's budget less its `[len][name]` header",
                )
            },
        )
    }

    /** `null` means unknown, not unbounded — a view over a base that names no ceiling invents none. */
    @Test
    fun aChannelViewOverABaseThatNamesNoCeilingInventsNone() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = null)
        val mux = MuxSeam(base, backgroundScope).channel(TAG)
        val named = NamedMux(base, backgroundScope).channel(CHANNEL_NAME)

        assertAll(
            { assertNull(mux.maxPayloadBytes, "unknown, not unbounded — a MuxSeam view invents nothing") },
            { assertNull(named.maxPayloadBytes, "unknown, not unbounded — a NamedMux view invents nothing") },
        )
    }

    /**
     * A base tighter than the view's own framing floors at **0**, not at a negative number and not
     * at 1: nothing fits, and saying so is the honest answer.
     */
    @Test
    fun aChannelViewFloorsAtZeroRatherThanPublishingANegativeBudget() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = STARVED_BUDGET)

        val view = NamedMux(base, backgroundScope).channel(CHANNEL_NAME)

        assertAll(
            {
                assertTrue(
                    STARVED_BUDGET - NAMED_OVERHEAD < 0,
                    "rig: the unfloored arithmetic must actually go negative, or the floor is never exercised",
                )
            },
            {
                assertEquals(
                    0,
                    view.maxPayloadBytes,
                    "a decorator whose framing outgrows the ceiling under it publishes 0 — floored, not negative, " +
                        "and not the caller-side floor of 1",
                )
            },
        )
    }

    /**
     * The budget is a reading, not a lease: a mesh underneath tightens as a peer attaches over a
     * narrower transport. A view that snapshotted the base at construction would keep handing out
     * the pre-tightening number.
     */
    @Test
    fun aChannelViewTracksTheBaseRatherThanSnapshottingIt() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = BASE_BUDGET)
        val view = MuxSeam(base, backgroundScope).channel(TAG)

        val before = view.maxPayloadBytes
        base.publish(TIGHTER_BUDGET)
        val after = view.maxPayloadBytes

        assertAll(
            {
                assertNotEquals(
                    BASE_BUDGET,
                    TIGHTER_BUDGET,
                    "rig: the base's budget must actually move, or the reactivity arm cannot fail",
                )
            },
            { assertEquals(BASE_BUDGET - BYTE_TAG_OVERHEAD, before, "the view starts at the base's budget") },
            {
                assertEquals(
                    TIGHTER_BUDGET - BYTE_TAG_OVERHEAD,
                    after,
                    "the view must re-read the base per call — the budget is a reading, not a lease",
                )
            },
        )
    }

    // ── The tiered union ─────────────────────────────────────────────────────────

    /**
     * A payload may take **either** tier — `broadcast` tees to both, `sendTo` routes to whichever
     * owns the peer — so the union is bounded by the tighter of the two, whichever side that is.
     * Both orderings are asserted, because "the tighter one" and "the local one" agree on a single
     * arrangement and only disagree across the pair.
     */
    @Test
    fun aTieredUnionIsBoundedByTheTighterTierWhicheverSideItIsOn() = runTest(UnconfinedTestDispatcher()) {
        val tightOnPeer = tiered(local = BASE_BUDGET, peer = TIGHTER_BUDGET, scope = backgroundScope)
        val tightOnLocal = tiered(local = TIGHTER_BUDGET, peer = BASE_BUDGET, scope = backgroundScope)

        assertAll(
            {
                assertTrue(
                    TIGHTER_BUDGET < BASE_BUDGET,
                    "rig: the two tiers must differ, or min/max/first-tier all agree",
                )
            },
            {
                assertEquals(
                    TIGHTER_BUDGET,
                    tightOnPeer.maxPayloadBytes,
                    "the union must publish the tighter tier's budget when the peer tier is the tight one",
                )
            },
            {
                assertEquals(
                    TIGHTER_BUDGET,
                    tightOnLocal.maxPayloadBytes,
                    "…and when the local tier is: this is a minimum, not 'whichever tier we checked first'",
                )
            },
        )
    }

    /**
     * A tier that names no ceiling does not erase what the other one knows — the same call
     * `MeshSeam` makes across a mesh of one bounded and one unknown link.
     */
    @Test
    fun aTieredUnionIsStillBoundedByTheTierThatDoesKnow() = runTest(UnconfinedTestDispatcher()) {
        val unknownPeer = tiered(local = BASE_BUDGET, peer = null, scope = backgroundScope)
        val unknownLocal = tiered(local = null, peer = BASE_BUDGET, scope = backgroundScope)

        assertAll(
            { assertEquals(BASE_BUDGET, unknownPeer.maxPayloadBytes, "an unknown peer tier does not erase the local one's bound") },
            { assertEquals(BASE_BUDGET, unknownLocal.maxPayloadBytes, "…nor the other way round") },
        )
    }

    /** Neither tier names a ceiling, so the union has nothing to be bounded by. */
    @Test
    fun aTieredUnionOfTwoUnknownTiersNamesNothing() = runTest(UnconfinedTestDispatcher()) {
        val union = tiered(local = null, peer = null, scope = backgroundScope)

        assertNull(union.maxPayloadBytes, "unknown, not unbounded — the union invents nothing")
    }

    // ── MuxClientLoom's resumable channel handle ─────────────────────────────────

    /**
     * A resumable handle is a view onto the live generation's mux channel, so it publishes that
     * channel's budget — the base's, less the [NamedMux] header the generation applies. Asserted
     * against the raw base number so a handle that skipped the mux layer's reservation fails.
     */
    @Test
    fun aResumableChannelPublishesItsCurrentGenerationsBudget() = runTest(UnconfinedTestDispatcher()) {
        val base = BudgetedSeam(initial = BASE_BUDGET)
        val loom = MuxClientLoom(
            base = SingleSeamLoom(base),
            baseRendezvous = Rendezvous.New(Pattern("base")),
            scope = backgroundScope,
            nameOf = { CHANNEL_NAME },
        )

        val handle = loom.host(Pattern(CHANNEL_NAME))

        assertAll(
            {
                assertNotEquals(
                    BASE_BUDGET,
                    BASE_BUDGET - NAMED_OVERHEAD,
                    "rig: the generation's mux header must cost bytes, or 'forwards the channel's' and " +
                        "'forwards the base's' agree",
                )
            },
            {
                assertEquals(
                    BASE_BUDGET - NAMED_OVERHEAD,
                    handle.maxPayloadBytes,
                    "a resumable channel publishes the current generation's channel budget, not null",
                )
            },
        )
    }

    // ── The server-side room hub ─────────────────────────────────────────────────

    /**
     * The hub has no delegate [Seam] — it fans out through one [OutboundSender] per registered
     * connection — so its budget is the tightest across those connections, each already less the
     * room's own `[len][name]` header.
     *
     * Driven end-to-end through [MuxServerLoom] rather than against hand-built senders, so the
     * subtraction the *loom* performs is under test too, not just the hub's fold.
     */
    @Test
    fun aRoomHubIsBoundedByTheTightestOfItsRegisteredConnections() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = coroutineContext[ContinuationInterceptor]!!
            val source = InMemoryConnectionSource()
            val serverLoom = MuxServerLoom(
                source = source,
                scope = backgroundScope,
                selfId = PeerId("server"),
                authorizer = RoomAuthorizer.AllowAll,
                dispatcher = dispatcher,
                random = Random(13L),
            )
            val room = serverLoom.host(Pattern(CHANNEL_NAME))

            val roomy = PeerId("roomy-client")
            joinRoom(source, CHANNEL_NAME, roomy, WIDE_FRAME_BYTES, dispatcher, backgroundScope, Random(1L))
            room.peers.first { roomy in it }
            val withOneConnection = room.maxPayloadBytes

            val cramped = PeerId("cramped-client")
            joinRoom(source, CHANNEL_NAME, cramped, NARROW_FRAME_BYTES, dispatcher, backgroundScope, Random(2L))
            room.peers.first { cramped in it }
            val withBoth = room.maxPayloadBytes

            // Each link's frame ceiling first pays the mesh's own type byte, then the room's header.
            val wide = WIDE_FRAME_BYTES - MeshWire.TYPE_BYTES - NAMED_OVERHEAD
            val narrow = NARROW_FRAME_BYTES - MeshWire.TYPE_BYTES - NAMED_OVERHEAD
            assertAll(
                {
                    assertTrue(
                        narrow < wide,
                        "rig: the two connections must differ, or 'tightest' and 'most recent' agree",
                    )
                },
                { assertEquals(wide, withOneConnection, "one registered connection bounds the hub by its own ceiling") },
                {
                    assertEquals(
                        narrow,
                        withBoth,
                        "a second, narrower connection tightens the hub — a broadcast goes to every registered " +
                            "connection, so the hub is bounded by the tightest of them",
                    )
                },
            )
        }

    /** A hub nobody has joined has no connection to be bounded by, and says so. */
    @Test
    fun aRoomHubWithNoRegisteredConnectionsNamesNothing() = runTest(UnconfinedTestDispatcher()) {
        val hub = RoomHubSeam(CHANNEL_NAME, PeerId("server"), RoomAuthorizer.AllowAll)

        assertNull(
            hub.maxPayloadBytes,
            "unknown, not unbounded — an empty hub has no connection whose ceiling it could report",
        )
    }

    private companion object {
        /** The wrapped seam's published budget. Deliberately far from every framing cost here. */
        const val BASE_BUDGET = 1000

        /** A second, tighter budget — the base moving under a live decorator. */
        const val TIGHTER_BUDGET = 400

        /** A ceiling narrower than the name header, so the floor is actually reached. */
        const val STARVED_BUDGET = 4

        /** [MuxSeam]'s framing: one leading tag byte. */
        const val BYTE_TAG_OVERHEAD = 1

        const val TAG: Byte = 0x07

        /** Nine UTF-8 bytes, so [NamedMux]'s `[len:1][name]` header costs ten. */
        const val CHANNEL_NAME = "telemetry"

        const val NAMED_OVERHEAD = 1 + CHANNEL_NAME.length

        const val WIDE_FRAME_BYTES = 4096
        const val NARROW_FRAME_BYTES = 1024
    }
}

/** A tiered union over two [BudgetedSeam]s of the same node, publishing [local] and [peer]. */
private fun tiered(local: Int?, peer: Int?, scope: kotlinx.coroutines.CoroutineScope): Seam {
    val id = PeerId("tiered-self")
    return tieredSeam(
        local = BudgetedSeam(selfId = id, initial = local),
        peer = BudgetedSeam(selfId = id, initial = peer),
        scope = scope,
    )
}

/** Register [peerId] into [roomName] over a fresh connection whose frames cap at [frameBytes]. */
private suspend fun joinRoom(
    source: InMemoryConnectionSource,
    roomName: String,
    peerId: PeerId,
    frameBytes: Int,
    dispatcher: kotlin.coroutines.CoroutineContext,
    scope: kotlinx.coroutines.CoroutineScope,
    random: Random,
) {
    val (serverEnd, clientEnd) = connectionPair(maxFrameBytes = frameBytes)
    source.offer(serverEnd)
    val clientMesh = hubMesh(peerId, listOf(clientEnd), dispatcher, random)
    // The hub admits a connection on its FIRST frame for the room, so one empty frame is what
    // makes this connection a member — and therefore part of the hub's budget fold.
    NamedMux(clientMesh, scope).channel(roomName).broadcast(byteArrayOf())
}

/**
 * A base [Seam] whose published budget the test drives.
 *
 * Deliberately local rather than `us.tractat.kuilt.test.FakeSeam`: the whole point of the fixture
 * is a budget that is *published* and then *moves*, and the shared fake inherits the interface's
 * `null` — against which a decorator that forwards and one that gives up are the same value.
 */
private class BudgetedSeam(
    override val selfId: PeerId = PeerId("budget-base"),
    initial: Int?,
) : Seam {
    private var budget: Int? = initial

    override val maxPayloadBytes: Int? get() = budget

    /** Moves the published ceiling, as a mesh does when a peer attaches over a tighter transport. */
    fun publish(bytes: Int?) {
        budget = bytes
    }

    override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(setOf(selfId))
    override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
    override val incoming: Flow<Swatch> = emptyFlow()

    override suspend fun broadcast(payload: ByteArray) = Unit

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit

    override suspend fun close(reason: CloseReason) = Unit
}

/** A [Loom] handing back one fixed [seam], so [MuxClientLoom] has a base to wrap. */
private class SingleSeamLoom(private val seam: Seam) : Loom {
    override suspend fun weave(rendezvous: Rendezvous): Seam = seam
}
