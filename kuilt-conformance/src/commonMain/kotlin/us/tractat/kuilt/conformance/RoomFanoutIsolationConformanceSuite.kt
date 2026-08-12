package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.NamedMux
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RoomAuthorizer
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reusable contract test suite for **server-fanout [Loom]s** — a server-side [Loom] whose
 * [Loom.host] returns a per-room star [Seam] that forwards broadcasts only to the connections
 * admitted to that room.
 *
 * Subclass and implement [newHarness] to bind any server-fanout [Loom] under test. Every [Test]
 * encodes a required invariant of **structural per-room isolation**: a non-member is never in a
 * room's fanout list, so a cross-room leak is unrepresentable.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`) so every
 * server-fanout [Loom] adapter can subclass it from its own test source set — the same
 * "one suite, every fabric passes it" pattern as [SeamConformanceSuite] and [RoomConformanceSuite].
 *
 * The gate this suite pins:
 *  - **(a) Zero-frames-on-non-member** — clients A, B on `table-7` and client C on `table-9`;
 *    a broadcast on `table-7` reaches A and B and **never** C ([broadcastOnRoomReachesOnlyRoomMembers]),
 *    and each room's [Seam.peers] reflects only its own members plus the hub's own [Seam.selfId] ([perRoomPeersReflectsOnlyRoomMembers]).
 *  - **(b) Per-room teardown** — closing one room leaves a sibling room fully usable
 *    ([closingOneRoomDoesNotAffectSibling]).
 *  - **(c) Auth-reject exclusion** — a connection the [RoomAuthorizer] rejects is structurally
 *    absent from the room ([rejectedConnectionIsStructurallyExcluded]).
 *
 * **Virtual time convention:** every test runs under [StandardTestDispatcher] with the
 * [TEST_WEDGE_BACKSTOP] wedge ceiling, and awaits registration on observable state ([awaitPeers])
 * rather than polling after `advanceUntilIdle`, so the data path is driven deterministically.
 *
 * **Every wait is bounded, and names what it saw.** Registration and delivery waits go through
 * [awaitPeers] / [awaitFrame], bounded by [awaitBudget] in **virtual** time, and fail with an
 * [AssertionError] quoting the peer set actually observed — a fanout [Loom] that never registers a
 * client is the normal state of one under development, and an unbounded `peers.first { … }` turns
 * that into a silent wall-clock burn ending in `UncompletedCoroutinesError` (#2284).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public abstract class RoomFanoutIsolationConformanceSuite {

    /**
     * How long [awaitPeers] / [awaitFrame] wait before failing with the state they observed —
     * **virtual** time, `null` to wait unbounded.
     *
     * Virtual is safe here by contract: [newHarness] is handed the test `dispatcher` precisely so
     * server- and client-side seams share the virtual clock, so nothing under this suite advances
     * real time and the trajectory is identical on every run. A harness that nonetheless does real
     * I/O must override this to `null` — virtual time would fast-forward the whole budget while the
     * frame is still on the wire, the way it red-lit a working `TcpConformanceTest` in #2069/#2115 —
     * and take `runTest`'s own [TEST_WEDGE_BACKSTOP] as its backstop.
     */
    public open val awaitBudget: Duration? = 2.seconds

    /**
     * Binds a server-fanout [Loom] under test to a way of connecting client [Seam]s to it.
     *
     * @property serverLoom the loom whose [Loom.host] returns per-room fanout [Seam]s.
     * @param connect connect a fresh client with the given [PeerId] (seeded [Random] for any
     *   nonce generation) and return that client's [Seam] wired to [serverLoom].
     */
    public class FanoutHarness(
        public val serverLoom: Loom,
        private val connect: suspend (peerId: PeerId, random: Random) -> Seam,
    ) {
        /** Connect a fresh client [Seam] wired to [serverLoom]. */
        public suspend fun connectClient(peerId: PeerId, random: Random): Seam = connect(peerId, random)
    }

    /**
     * Provide a fresh harness for one test.
     *
     * @param scope the test's coroutine scope (typically `backgroundScope`) for the server's
     *   accept pump and per-connection read loops.
     * @param dispatcher the test dispatcher, so server- and client-side seams share the virtual clock.
     * @param authorizer the room-membership gate under test — [RoomAuthorizer.AllowAll] for the
     *   isolation/teardown tests, a rejecting policy for [rejectedConnectionIsStructurallyExcluded].
     * @param random seeded [Random] for the server's seam nonce generation.
     */
    public abstract fun newHarness(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        authorizer: RoomAuthorizer,
        random: Random,
    ): FanoutHarness

    // ── (a) zero-frames-on-non-member: broadcast reaches only room members ────

    /**
     * Core isolation gate: clients A and B on `table-7`; client C on `table-9`.
     * A broadcast on `table-7` is observed by B and NEVER by C. The assertion on C is structural:
     * C's inbox has zero frames — it was never in the `table-7` fanout list. `table-9` still
     * delivers its own broadcast to C, proving the sibling room is unaffected.
     */
    @Test
    public fun broadcastOnRoomReachesOnlyRoomMembers(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val harness = newHarness(backgroundScope, dispatcher, RoomAuthorizer.AllowAll, Random(42L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))
            val serverRoom9 = harness.serverLoom.host(Pattern("table-9"))

            val muxA = NamedMux(harness.connectClient(PeerId("client-a"), Random(1L)), backgroundScope)
            val muxB = NamedMux(harness.connectClient(PeerId("client-b"), Random(2L)), backgroundScope)
            val muxC = NamedMux(harness.connectClient(PeerId("client-c"), Random(3L)), backgroundScope)

            // Signal room membership: each client sends its first frame on its room's channel.
            muxA.channel("table-7").broadcast(byteArrayOf())
            muxB.channel("table-7").broadcast(byteArrayOf())
            muxC.channel("table-9").broadcast(byteArrayOf())

            // Await registration on observable state: A and B into table-7, C into table-9.
            // Wait on member containment, not set size — [Seam.peers] also carries the hub's own
            // selfId (contract; #1506), so the roster is never just the remote spokes.
            serverRoom7.awaitPeers("table-7 registers client-a and client-b") {
                it.containsAll(setOf(PeerId("client-a"), PeerId("client-b")))
            }
            serverRoom9.awaitPeers("table-9 registers client-c") { it.contains(PeerId("client-c")) }

            // C starts collecting on table-7 BEFORE the broadcast (so it can't merely miss frames).
            val cTable7Inbox = muxC.channel("table-7").incoming.produceIn(backgroundScope)

            val payload = byteArrayOf(1, 2, 3)
            serverRoom7.broadcast(payload)

            val bFrame = muxB.channel("table-7").awaitFrame("B's copy of the table-7 broadcast")

            // table-9 still works: C receives its own room's broadcast.
            val cPayload = byteArrayOf(9, 8, 7)
            serverRoom9.broadcast(cPayload)
            val cFrame = muxC.channel("table-9").awaitFrame("C's copy of the table-9 broadcast")

            assertAll(
                { assertTrue(bFrame.toByteArray().contentEquals(payload), "B must receive the table-7 broadcast") },
                { assertTrue(cTable7Inbox.isEmpty, "C must receive ZERO frames on table-7 (structural isolation)") },
                { assertTrue(cFrame.toByteArray().contentEquals(cPayload), "C must receive the table-9 broadcast") },
            )
        }

    /**
     * [Seam.peers] on each room reflects that room's registered members plus the hub's own
     * [Seam.selfId] (the hub is a peer in its own roster; contract; #1506): `table-7` sees the hub,
     * A and B; `table-9` sees the hub and only C. No spoke leaks across rooms.
     */
    @Test
    public fun perRoomPeersReflectsOnlyRoomMembers(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val harness = newHarness(backgroundScope, dispatcher, RoomAuthorizer.AllowAll, Random(99L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))
            val serverRoom9 = harness.serverLoom.host(Pattern("table-9"))

            val muxA = NamedMux(harness.connectClient(PeerId("client-a"), Random(1L)), backgroundScope)
            val muxB = NamedMux(harness.connectClient(PeerId("client-b"), Random(2L)), backgroundScope)
            val muxC = NamedMux(harness.connectClient(PeerId("client-c"), Random(3L)), backgroundScope)

            muxA.channel("table-7").broadcast(byteArrayOf())
            muxB.channel("table-7").broadcast(byteArrayOf())
            muxC.channel("table-9").broadcast(byteArrayOf())

            val room7Peers = serverRoom7.awaitPeers("table-7 registers client-a and client-b") {
                it.containsAll(setOf(PeerId("client-a"), PeerId("client-b")))
            }
            val room9Peers = serverRoom9.awaitPeers("table-9 registers client-c") { it.contains(PeerId("client-c")) }

            assertAll(
                {
                    assertEquals(
                        setOf(serverRoom7.selfId, PeerId("client-a"), PeerId("client-b")),
                        room7Peers,
                        "table-7 roster = hub selfId + its 2 members; got $room7Peers",
                    )
                },
                {
                    assertEquals(
                        setOf(serverRoom9.selfId, PeerId("client-c")),
                        room9Peers,
                        "table-9 roster = hub selfId + its 1 member; got $room9Peers",
                    )
                },
            )
        }

    // ── (b) per-room teardown: closing one room leaves the sibling usable ─────

    /**
     * Closing room `table-7` does not drop room `table-9` or prevent further broadcasts on it.
     */
    @Test
    public fun closingOneRoomDoesNotAffectSibling(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val harness = newHarness(backgroundScope, dispatcher, RoomAuthorizer.AllowAll, Random(7L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))
            val serverRoom9 = harness.serverLoom.host(Pattern("table-9"))

            val muxC = NamedMux(harness.connectClient(PeerId("client-c"), Random(3L)), backgroundScope)
            muxC.channel("table-9").broadcast(byteArrayOf())

            // Await C's registration in table-9 deterministically (wait on membership, not size —
            // the roster also carries the hub's own selfId, #1506).
            serverRoom9.awaitPeers("table-9 registers client-c") { it.contains(PeerId("client-c")) }

            // Close table-7 — table-9 must remain usable.
            serverRoom7.close()

            val cPayload = byteArrayOf(42, 43)
            serverRoom9.broadcast(cPayload)

            val cFrame = muxC.channel("table-9").awaitFrame("C's copy of the table-9 broadcast")
            assertTrue(cFrame.toByteArray().contentEquals(cPayload), "table-9 must work after table-7 is closed")
        }

    // ── (c) auth-reject: a rejected connection is structurally excluded ───────

    /**
     * A connection the [RoomAuthorizer] rejects for `table-7` is structurally excluded: it never
     * appears in [Seam.peers] and observes ZERO frames on `table-7`. A second, admitted connection
     * on the same room still works.
     */
    @Test
    public fun rejectedConnectionIsStructurallyExcluded(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val rejectedPeer = PeerId("client-rejected")
            // Authorize everyone for table-7 EXCEPT the rejected peer.
            val authorizer = RoomAuthorizer { peer, tag -> !(tag == "table-7" && peer == rejectedPeer) }
            val harness = newHarness(backgroundScope, dispatcher, authorizer, Random(11L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))

            val okPeer = PeerId("client-ok")
            val okMux = NamedMux(harness.connectClient(okPeer, Random(1L)), backgroundScope)
            val noMux = NamedMux(harness.connectClient(rejectedPeer, Random(2L)), backgroundScope)

            // Both clients try to join table-7.
            okMux.channel("table-7").broadcast(byteArrayOf())
            noMux.channel("table-7").broadcast(byteArrayOf())

            // Await the admitted peer's registration; the rejected one must never register.
            serverRoom7.awaitPeers("table-7 registers the admitted client") { it.contains(okPeer) }

            // Rejected client begins collecting BEFORE the broadcast so it cannot merely miss a frame.
            val rejectedInbox = noMux.channel("table-7").incoming.produceIn(backgroundScope)

            val payload = byteArrayOf(7, 7, 7)
            serverRoom7.broadcast(payload)

            val okFrame = okMux.channel("table-7").awaitFrame("the admitted client's copy of the broadcast")

            assertAll(
                { assertTrue(okFrame.toByteArray().contentEquals(payload), "admitted client must receive the broadcast") },
                { assertTrue(rejectedInbox.isEmpty, "rejected client must receive ZERO frames on table-7") },
                { assertEquals(setOf(serverRoom7.selfId, okPeer), serverRoom7.peers.value, "only the hub selfId and the admitted peer are in table-7; the rejected peer never registers") },
            )
        }

    // ── Bounded waits ─────────────────────────────────────────────────────────

    /**
     * Suspend until [predicate] holds over this seam's [Seam.peers] and return that set; on expiry
     * of [awaitBudget] fail with an [AssertionError] quoting the peers actually observed.
     *
     * [expected] states the predicate in words, because the predicate is a lambda and cannot print
     * itself.
     */
    private suspend fun Seam.awaitPeers(
        expected: String,
        predicate: (Set<PeerId>) -> Boolean,
    ): Set<PeerId> {
        val budget = awaitBudget ?: return peers.first(predicate)
        return withTimeoutOrNull(budget) { peers.first(predicate) }
            ?: fail(
                "peers never satisfied: $expected",
                budget,
                "A room's peer set carries the hub's own selfId plus its registered members " +
                    "(#1506), so a set holding only the hub means the client's first frame on that " +
                    "channel never reached the server's per-room accept path.",
            )
    }

    /** Suspend until this seam delivers a frame; on expiry name the room and the peers it had. */
    private suspend fun Seam.awaitFrame(expected: String): Swatch {
        val budget = awaitBudget ?: return incoming.first()
        return withTimeoutOrNull(budget) { incoming.first() }
            ?: fail(
                "no frame arrived: $expected",
                budget,
                "A per-room seam forwards only to the connections registered in THAT room, so an " +
                    "unexpected peer set above explains a missing frame before the transport does.",
            )
    }

    /**
     * The failure renderer both helpers share. The interpretive line is the caller's [hint], not a
     * fixed tail: the peer-registration hint is wrong on a delivery failure and vice versa.
     */
    private fun Seam.fail(headline: String, budget: Duration, hint: String): Nothing =
        throw AssertionError(
            buildString {
                appendLine("RoomFanoutIsolationConformanceSuite: $headline")
                appendLine("  waited $budget of VIRTUAL time (RoomFanoutIsolationConformanceSuite.awaitBudget)")
                appendLine("  seam: selfId=${selfId.value}")
                appendLine(
                    "  peers (${peers.value.size}): " +
                        peers.value.joinToString(prefix = "[", postfix = "]") { it.value },
                )
                append("  $hint")
            },
        )
}
