package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
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
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
 *  - **(d) Ingress** — the mirror of (a) and (c): a frame a non-member *sends into* a room reaches
 *    neither the hub's own inbox nor the room's members ([aNonMembersFrameNeverEntersTheRoom]).
 *  - **(e) Departure** — a client whose link tears leaves every room it joined, and the room keeps
 *    working for whoever is left ([aDepartedClientLeavesEveryRoomItJoined]).
 *
 * ## Why (d) and (e) were missing, and what that cost — #2307
 *
 * (a)–(c) are all **egress**: every one of them originates its frame at a *server room seam* and
 * asks where it landed. A client-side send appeared in this suite only as a registration signal, and
 * nothing asserted where a client's frame went — so an implementation that admitted **anything** into
 * a room's inbox passed all four properties. That is half a membership boundary: the direction an
 * authorizer exists to stop was the unasserted one.
 *
 * Nobody wrote it because the reference [us.tractat.kuilt.core.MuxServerLoom] cannot represent it.
 * There, *sending on a room's channel **is** the join*, so "a non-member sends into a room" has no
 * construction — the sender becomes a member. And registration and ingress are **one code path**:
 * [us.tractat.kuilt.core.RoomHubSeam] returns on a rejected authorization *before* it spools the
 * frame, so the reference gets its ingress guard for free from the registration guard it had to
 * write anyway. Any real server with an explicit admit step and a session store has these as two
 * separate code paths, and only one of them had a property. This is the general shape stated in the
 * repo guide: *a conformance property is only as strong as the weakest failure the reference
 * implementation can reach* — here in its **half-a-boundary** form, one direction asserted and the
 * mirror direction not.
 *
 * (d) reaches the state the reference makes unrepresentable by using the [RoomAuthorizer] the suite
 * already owns: it refuses exactly one `(peer, room)` pair, which makes that peer a **live, admitted,
 * otherwise-legitimate** connection that is nonetheless not a member of the room it sends into. That
 * subsumes #2307's two ingress items rather than splitting them, and deliberately: a backend gating
 * ingress on "is this connection in *any* room" passes the stranger case and fails this one, so this
 * is the strictly stronger of the two and there is no backend that passes it and fails the other.
 *
 * ## Mutation receipt
 *
 * Against the reference `MuxServerLoomFanoutIsolationTest` (6 tests), JVM. "four" = the pre-existing
 * properties (a)–(c). **Real** = a defect a backend could plausibly ship; **synthetic** = code added
 * to the reference purely to reach an assertion the reference cannot otherwise falsify; **rig** =
 * a mutation of this suite itself, checking that a rig-fired counter is not decorative.
 *
 * | # | Mutation | Kind | (d) | (e) | four |
 * |---|----------|------|-----|-----|------|
 * | 1 | `RoomHubSeam.deliver` spools the frame on the **rejected** branch — registration guard kept, ingress guard dropped | real | RED — hub-inbox assertion **only**, drained `[JOIN, INTRUSION, MEMBER_INGRESS]` | green | **green** |
 * | 2 | `MuxServerLoom.teardownConnection` drops its per-room deregistration loop | real | green | RED — roster, `peers (3): [server, client-leaver, client-stayer]` | **green** |
 * | 3 | `deliver` ignores the authorization verdict entirely | real (control) | RED — 3 of 7 | green | (c) RED |
 * | 4 | a refused peer is added to fanout + roster, its frame still not spooled | synthetic | RED — fanout-silence + roster; hub-inbox correctly **stays green** | green | (c) RED |
 * | 5 | a refused frame is relayed to the room's members | synthetic | RED — member-relay assertion **only** | green | (c) RED, but on an unrelated assertion (blast radius, not a diagnosis) |
 * | 6 | the intrusion send is deleted from this test | rig | RED — rig counter only, naming the log `[client-intruder@table-7, client-member@table-9]` | green | green |
 *
 * Row 1 is the whole argument: the defect #2307 describes, invisible to every pre-existing property,
 * named in one assertion by the new one. **Row 2's claim is narrower** and the KDoc on
 * [aDepartedClientLeavesEveryRoomItJoined] states it: that mutation also reds five tests outside this
 * suite, so it is not unseen — only unseen *here*, which is what a second backend inherits.
 *
 * **The green cells are the interesting ones.** Row 1's (e), rows 2/4/5/6's greens and every row's
 * untouched assertions are what make this table a set of diagnoses rather than a blast radius. One
 * assertion has no red anywhere: the precondition that the intruder is absent from `table-9`'s roster
 * *before* it sends. That is correct — it does not describe behaviour under test, and it can only red
 * on a harness that hands back an already-admitted peer, which is the one thing it exists to catch.
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
     * How long an **absence** assertion waits for the frame it expects never to arrive
     * ([awaitSilence]).
     *
     * Deliberately **not** nullable, where [awaitBudget] is: a wait for something to happen may
     * legitimately be unbounded and lean on `runTest`'s [TEST_WEDGE_BACKSTOP], but a wait for
     * *nothing* to happen that never expires never returns. So a real-I/O subclass that sets
     * [awaitBudget] to `null` still gets a terminating absence check — this budget is then real
     * wall-clock rather than virtual, and such a subclass should raise it to whatever its transport's
     * worst-case one-way latency is. Under an in-memory harness it is virtual and free.
     *
     * **What it cannot do is turn absence into proof.** "No frame arrived in N" is green when the
     * frame was merely slow, and no value of N fixes that. What makes the absence assertions here
     * load-bearing is not this number but the ordering around them: every one is preceded by a
     * *positive control* on the same path — a frame that demonstrably **did** arrive after the one
     * under test was demonstrably dispatched — so the check cannot pass merely by nothing having
     * happened yet. Raising this budget is a latency accommodation, never a strengthening.
     */
    public open val absenceBudget: Duration = 1.seconds

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

    // ── (d) ingress: a non-member's frame never enters the room ───────────────

    /**
     * **The mirror of (a) and (c), and the half of the membership boundary #2307 found unasserted.**
     *
     * A peer the policy admits to `table-7` and **refuses** for `table-9` puts a frame on `table-9`'s
     * channel — which a [NamedMux] client can do at will over the one connection it already holds.
     * That frame must reach neither `table-9`'s hub inbox ([Seam.incoming]) nor `table-9`'s members,
     * and must not put its sender in `table-9`'s roster or its fanout.
     *
     * ### The anchor — why "it was dropped" is provable here rather than assumed
     *
     * Every absence assertion below would be green if the intruding frame had merely not been *read*
     * yet, and a bounded wait cannot tell those apart. So the frame's disposition is *settled* before
     * anything is asserted, by a **sequencing anchor**: immediately after the intrusion the same
     * connection sends a frame on `table-7`, a room that peer **is** in, and the test waits for that
     * frame at `table-7`'s hub inbox. A server reads one connection with a single sequential
     * collection, so the anchor's arrival proves the intrusion was already dequeued and dispatched.
     * That is a happens-before, not a timing guess, and it holds for a real-I/O backend as strongly as
     * for an in-memory one — unlike anything keyed to a second connection's ordering, which nothing
     * guarantees. It is also the **rig-fired** receipt for the send itself: without it, a harness whose
     * `channel("table-9").broadcast` silently did nothing would pass this test with no ingress attempt
     * ever made. What the anchor deliberately does **not** claim is *which* branch dropped the frame —
     * it proves the server's read path handled it, and the room boundary is what is under assertion.
     *
     * The second rig receipt is [RefusingAuthorizer.refusals], which counts the consultations that
     * returned `false` for the refused pair. That pins [RoomAuthorizer]'s own documented invocation
     * contract ("invoked once per connection per room tag when a connection first emits a frame on
     * that channel") rather than adding an obligation, and it is what distinguishes "the frame was
     * refused" from "the frame never reached the room's gate at all".
     *
     * ### The positive controls
     *
     * Each of the three destinations is proved *observable* by a frame that does arrive on it, sent
     * only after the anchor:
     *  - the hub inbox — a member's own frame, drained for and found;
     *  - the member's inbox — a hub broadcast, drained for and found;
     *  - the intruder's own `table-9` view — the only one with no positive control available, since
     *    nothing may legitimately be delivered to it. Its silence is bounded by [absenceBudget] and
     *    is the weakest assertion here; it is anchored by the *member* having received the very
     *    broadcast whose fanout list the intruder must be absent from, so a broadcast provably
     *    happened between the intrusion and the check.
     *
     * ### Mutation receipt
     *
     * Reference: [us.tractat.kuilt.core.RoomHubSeam.deliver]. Spooling the frame on the rejected
     * branch — `if (!authorizer.authorize(…)) { inboundSpool.deliver(frame); return }`, i.e. keeping
     * the *registration* guard and dropping only the *ingress* guard, which is exactly the
     * admitted-to-inbox-while-excluded-from-fanout backend #2307 describes — reddens this test on the
     * hub-inbox assertion and leaves all four pre-existing properties green. That single row is the
     * whole argument for this property's existence. Deleting the authorization check outright reddens
     * this test *and* [rejectedConnectionIsStructurallyExcluded], which is the weaker control.
     */
    @Test
    public fun aNonMembersFrameNeverEntersTheRoom(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val intruder = PeerId("client-intruder")
            val member = PeerId("client-member")
            // The intruder is a fully legitimate peer everywhere EXCEPT table-9. That is what makes it
            // a non-member of a room it can still physically reach, which the reference loom — where
            // sending on a channel IS joining it — otherwise cannot represent.
            val authorizer = RefusingAuthorizer(intruder, "table-9")
            val harness = newHarness(backgroundScope, dispatcher, authorizer, Random(2307L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))
            val serverRoom9 = harness.serverLoom.host(Pattern("table-9"))

            val intruderMux = NamedMux(harness.connectClient(intruder, Random(1L)), backgroundScope)
            val memberMux = NamedMux(harness.connectClient(member, Random(2L)), backgroundScope)

            val intruderRoom7 = intruderMux.channel("table-7")
            // Materialise the intruder's table-9 view BEFORE any table-9 frame exists: a mux channel
            // view spools from the moment it is created, so a view that exists early cannot miss a
            // leak, and the later silence check is about delivery rather than about subscription
            // timing.
            val intruderRoom9 = intruderMux.channel("table-9")
            val memberRoom9 = memberMux.channel("table-9")

            intruderRoom7.broadcast(JOIN)
            memberRoom9.broadcast(JOIN)

            serverRoom7.awaitPeers("table-7 admits the intruder") { it.contains(intruder) }
            val room9Before = serverRoom9.awaitPeers("table-9 admits its member") { it.contains(member) }

            // The intrusion, then the anchor — same connection, so the anchor's arrival at table-7
            // proves the intrusion was already read and dispatched.
            intruderRoom9.broadcast(INTRUSION)
            intruderRoom7.broadcast(ANCHOR)
            val room7Saw = serverRoom7.drainUntil(ANCHOR, "the intruder's anchor frame on table-7")

            // Positive controls, only now that the intrusion's fate is settled.
            memberRoom9.broadcast(MEMBER_INGRESS)
            val hubSaw = serverRoom9.drainUntil(MEMBER_INGRESS, "the member's own frame at table-9's hub inbox")
            serverRoom9.broadcast(HUB_EGRESS)
            val memberSaw = memberRoom9.drainUntil(HUB_EGRESS, "table-9's broadcast at its member")
            val leakedToIntruder = intruderRoom9.awaitSilence()

            assertAll(
                {
                    assertTrue(
                        intruder !in room9Before,
                        "precondition: the intruder must NOT be in table-9's roster when it sends; got $room9Before",
                    )
                },
                {
                    assertTrue(
                        authorizer.refusals() > 0,
                        "rig: the room's membership gate must have been consulted for the refused pair and " +
                            "said no — otherwise the intruding frame never reached the room at all. " +
                            "Consultations: ${authorizer.log()}",
                    )
                },
                {
                    assertTrue(
                        room7Saw.any { it.contentEquals(ANCHOR) },
                        "the anchor frame must arrive on the room the intruder IS in; saw ${render(room7Saw)}",
                    )
                },
                {
                    assertTrue(
                        hubSaw.none { it.contentEquals(INTRUSION) },
                        "a non-member's frame must never reach the room's own inbox; saw ${render(hubSaw)}",
                    )
                },
                {
                    assertTrue(
                        memberSaw.none { it.contentEquals(INTRUSION) },
                        "a non-member's frame must never be relayed to the room's members; saw ${render(memberSaw)}",
                    )
                },
                {
                    assertNull(
                        leakedToIntruder,
                        "sending into a room must not add the sender to its fanout: the intruder received a frame " +
                            "on table-9 after a broadcast its member did receive",
                    )
                },
                {
                    assertTrue(
                        intruder !in serverRoom9.peers.value,
                        "sending into a room must not add the sender to its roster; got ${serverRoom9.peers.value}",
                    )
                },
            )
        }

    // ── (e) departure: a dropped client leaves every room it joined ───────────

    /**
     * A client whose link tears leaves every room it joined, and the room keeps serving whoever is
     * left.
     *
     * [closingOneRoomDoesNotAffectSibling] closes a room from the **server** side; nothing in this
     * suite closed a *client*. A hub that leaks a dead connection into its fanout list keeps a stale
     * roster and writes to a dead socket forever, and every other property here stays green — the
     * departure path is reached only by a client going away, which no egress-only property does.
     *
     * **What this one is and is not.** Unlike [aNonMembersFrameNeverEntersTheRoom], this is not a
     * defect nothing in the tree can see: the reference loom's own unit tests cover deregistration
     * directly, and two shared suites red on it as collateral. What was missing is narrower and worth
     * stating exactly — it was not an obligation **of this suite**, so a second server-fanout backend
     * subclassing [RoomFanoutIsolationConformanceSuite] inherited no departure property at all. The
     * two suites that do catch it catch it for reasons a fanout backend cannot rely on:
     * `SeamConformanceSuite`'s drain property is gated behind an opt-in injection hook, and
     * `PrincipalAttestationConformanceSuite` is subclassed only by a backend that attests principals.
     * Both also red here as a **wedge** — a timeout or an `UncompletedCoroutinesError` — rather than
     * as a diagnosis; this property reds in one line with the offending roster printed.
     *
     * ### What keeps each assertion from being vacuous
     *
     * [awaitPeers] is itself the precondition gate at both ends: it fails with the roster it actually
     * observed, so "both joined" and "the leaver left" cannot pass by never having been true. The
     * remaining risk is a room that answers "the leaver is gone" by collapsing entirely — a torn hub,
     * a cleared roster, a dead fanout all satisfy that. Three assertions close it: the surviving
     * member is still in the roster, the hub is still in its own roster (the `{ selfId }` collapse a
     * closed [Seam] publishes would otherwise read as success), and a broadcast issued *after* the
     * departure still reaches the survivor. The rig itself is receipted by the departing seam's own
     * terminal [SeamState] — without it, a harness whose `close()` did nothing would leave this test
     * asserting a departure that never happened.
     *
     * ### Mutation receipt
     *
     * Reference: deleting the per-room deregistration loop in
     * [us.tractat.kuilt.core.MuxServerLoom.teardownConnection] reddens this test's roster assertion —
     * `peers never satisfied: the departed client leaves table-7 … peers (3): [server, client-leaver,
     * client-stayer]` — and leaves all four pre-existing properties of this suite green. It also reds
     * five tests elsewhere, per the paragraph above; the row's claim is that this suite could not see
     * it, not that nothing could.
     */
    @Test
    public fun aDepartedClientLeavesEveryRoomItJoined(): TestResult =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
                "weave/handshake: no dispatcher (ContinuationInterceptor) in coroutine context"
            }
            val leaver = PeerId("client-leaver")
            val stayer = PeerId("client-stayer")
            val harness = newHarness(backgroundScope, dispatcher, RoomAuthorizer.AllowAll, Random(2307L))

            val serverRoom7 = harness.serverLoom.host(Pattern("table-7"))

            val leaverSeam = harness.connectClient(leaver, Random(1L))
            val leaverMux = NamedMux(leaverSeam, backgroundScope)
            val stayerMux = NamedMux(harness.connectClient(stayer, Random(2L)), backgroundScope)
            val stayerRoom7 = stayerMux.channel("table-7")

            leaverMux.channel("table-7").broadcast(JOIN)
            stayerRoom7.broadcast(JOIN)

            // Precondition, enforced by the wait itself: both really are members before one departs.
            serverRoom7.awaitPeers("table-7 registers both clients") {
                it.containsAll(setOf(leaver, stayer))
            }

            leaverSeam.close()

            val after = serverRoom7.awaitPeers("the departed client leaves table-7") { leaver !in it }

            // The room must still work for whoever is left — a room that answered by collapsing would
            // satisfy the assertion above.
            serverRoom7.broadcast(AFTER_DEPARTURE)
            val stayerSaw = stayerRoom7.drainUntil(AFTER_DEPARTURE, "table-7's broadcast after the departure")

            assertAll(
                { assertIs<SeamState.Torn>(leaverSeam.state.value, "rig: the departing client's seam must be torn") },
                { assertTrue(leaver !in after, "a departed client must leave every room it joined; got $after") },
                { assertTrue(stayer in after, "the surviving member must remain in the roster; got $after") },
                {
                    assertTrue(
                        serverRoom7.selfId in after,
                        "the hub must remain in its own roster — a collapsed roster is not a departure; got $after",
                    )
                },
                {
                    assertTrue(
                        stayerSaw.any { it.contentEquals(AFTER_DEPARTURE) },
                        "the room must still fan out to the survivor; saw ${render(stayerSaw)}",
                    )
                },
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

    /**
     * Drain frames from this seam until one equals [sentinel], returning **everything** seen along
     * the way, sentinel included.
     *
     * This, rather than an emptiness check on a snapshot, is how the ingress properties assert a
     * negative: the sentinel is sent only after the frame under test was provably dispatched, so any
     * leak is necessarily *ahead of* the sentinel in this seam's own FIFO and appears in the returned
     * list. A snapshot check has to guess when to look; this one is ordered by construction. It also
     * carries its own positive control — the sentinel arriving at all proves this destination is
     * observable, so "no leak" cannot be an artefact of a destination nobody was reading.
     *
     * Each individual wait is bounded by [awaitBudget] via [awaitFrame], and a failure names every
     * frame drained so far.
     */
    private suspend fun Seam.drainUntil(sentinel: ByteArray, expected: String): List<ByteArray> {
        val seen = mutableListOf<ByteArray>()
        while (true) {
            val frame = awaitFrame("$expected — drained ${render(seen)} so far").toByteArray()
            seen += frame
            if (frame.contentEquals(sentinel)) return seen
        }
    }

    /**
     * Wait [absenceBudget] for a frame that must never come, returning it if it does and `null` if
     * the seam stayed silent.
     *
     * The bound is [absenceBudget] and not [awaitBudget] because this wait must terminate even for a
     * subclass that sets [awaitBudget] to `null`. On its own it proves only that nothing arrived
     * *yet*; what makes it an assertion is the ordering its caller establishes around it.
     */
    private suspend fun Seam.awaitSilence(): Swatch? = withTimeoutOrNull(absenceBudget) { incoming.first() }

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

    private companion object {
        /**
         * Distinct payloads, because every ingress assertion is "this exact frame is absent from
         * that exact destination". The empty registration frame the older properties use would make
         * a leak indistinguishable from a join.
         */
        val JOIN = byteArrayOf(0x0A)
        val INTRUSION = byteArrayOf(0x66, 0x66, 0x66)
        val ANCHOR = byteArrayOf(0x0B)
        val MEMBER_INGRESS = byteArrayOf(0x0C)
        val HUB_EGRESS = byteArrayOf(0x0D)
        val AFTER_DEPARTURE = byteArrayOf(0x0E)
    }
}

/** Render drained frames for a failure message. */
private fun render(frames: List<ByteArray>): String =
    frames.joinToString(prefix = "[", postfix = "]") { it.contentToString() }

/**
 * A [RoomAuthorizer] that admits everything except one `(peer, room)` pair, and **counts** what it
 * was asked.
 *
 * The refusal is what makes [RoomFanoutIsolationConformanceSuite.aNonMembersFrameNeverEntersTheRoom]
 * constructible at all: it turns a peer that is live, handshaked and admitted elsewhere into a
 * non-member of one specific room, which is a state a loom whose join *is* a send cannot otherwise
 * reach.
 *
 * The count is the rig receipt. A refusal that never happened and a frame that never left the client
 * produce the same silence at the room, and only [refusals] tells them apart. It counts refusals
 * rather than inferring them from the absence they are supposed to cause — an inference that stays
 * true when the guard under test is deleted, which is the whole failure mode.
 *
 * [log] records every consultation, refused or not, so a failure can say what the gate was actually
 * asked instead of only that it was not asked the expected thing.
 */
private class RefusingAuthorizer(
    private val refusedPeer: PeerId,
    private val refusedRoom: String,
) : RoomAuthorizer {

    private val consultations = MutableStateFlow<List<String>>(emptyList())
    private val refusalCount = MutableStateFlow(0)

    override suspend fun authorize(peerId: PeerId, channelName: String): Boolean {
        consultations.update { it + "${peerId.value}@$channelName" }
        val refused = peerId == refusedPeer && channelName == refusedRoom
        if (refused) refusalCount.update { it + 1 }
        return !refused
    }

    /** How many times this gate was consulted for the refused pair and said no. */
    fun refusals(): Int = refusalCount.value

    /** Every consultation, in order — for a failure message. */
    fun log(): List<String> = consultations.value
}
