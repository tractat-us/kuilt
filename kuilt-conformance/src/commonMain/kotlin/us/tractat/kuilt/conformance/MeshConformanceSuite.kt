package us.tractat.kuilt.conformance

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Reusable contract test suite for N-peer mesh [Seam] implementations.
 *
 * Subclass and implement [newMeshOfSize] to bind any N-peer mesh under test.
 * Every [Test] encodes a required invariant of the mesh contract.
 *
 * Lives in `commonMain` of `:kuilt-conformance` so every mesh implementation
 * can subclass it from its own test source set.
 *
 * [newMeshOfSize] returns a list of [n] fully-connected [Seam]s — one per
 * peer. The harness must not return until all inter-peer connections are
 * established (i.e. every returned seam has already completed its handshakes
 * with every other peer).
 *
 * ## Why the delivery awaits here are deliberately UNBOUNDED
 *
 * [RoomConformanceSuite] and [RoomFanoutIsolationConformanceSuite] bound every wait in **virtual**
 * time so a fabric that never delivers fails fast and names what it saw (#2284). This suite
 * deliberately does not, and the difference is its harness contract: [newMeshOfSize] is a plain
 * `suspend fun` — it is handed neither the test scope nor the test dispatcher, so nothing here
 * requires the mesh to run on the test scheduler, and a real-socket mesh is a first-class harness.
 * A `withTimeout` in a `runTest` body is measured in *virtual* time, which a real socket does not
 * advance: `runTest` fast-forwards the whole bound while the frame is still on the wire and a
 * working fabric fails. That is exactly what a `withTimeout` added to a [SeamConformanceSuite]
 * obligation did to `TcpConformanceTest` on a 16 MiB loopback frame (#2069 / #2115).
 *
 * So the awaits below match [SeamConformanceSuite]'s: a bare `await()` / `first()`, with `runTest`'s
 * own ceiling as the wedge backstop. Do not "fix" them into bounded ones without first giving this
 * suite a way to know its harness is virtual-time-only — the two Room suites have that in a
 * `awaitBudget` an implementor can null out; this one does not.
 */
public abstract class MeshConformanceSuite {

    /**
     * Build a fully-connected N-peer mesh and return one [Seam] per peer.
     * The returned list must have exactly [n] elements.
     * All inter-peer connections must be established before this returns.
     */
    public abstract suspend fun newMeshOfSize(n: Int): List<Seam>

    // ── (1) every peer's `peers` converges to the other n−1 ─────────────────

    @Test
    public fun eachPeerSeesMeshSize(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        seams.forEach { seam ->
            val allPeers = seam.peers.value
            assertEquals(3, allPeers.size, "each peer must see all 3 peers (including self); got $allPeers on ${seam.selfId}")
            assertTrue(seam.selfId in allPeers, "selfId must be in peers")
        }
    }

    // ── (2) broadcast from one peer reaches all others ───────────────────────

    @Test
    public fun broadcastReachesAllPeers(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        coroutineScope {
            val receivers = seams.drop(1).map { seam ->
                async { seam.incoming.first() }
            }
            val payload = byteArrayOf(42, 43)
            seams[0].broadcast(payload)
            receivers.forEach { deferred ->
                val swatch = deferred.await()
                assertTrue(swatch.toByteArray().contentEquals(payload), "payload must match")
                assertEquals(seams[0].selfId, swatch.sender, "sender must be the broadcaster")
            }
        }
    }

    // ── (3) sendTo routes to exactly one peer ───────────────────────────────
    //
    // Start collecting on bystander BEFORE sending, then send a direct message to
    // target, then broadcast a sentinel to all. The first frame bystander sees must
    // be the sentinel — proving the sendTo never reached it.

    @Test
    public fun sendToRoutesToExactlyOnePeer(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        coroutineScope {
            val sender = seams[0]
            val target = seams[1]
            val bystander = seams[2]

            // Start collecting on both receivers before any sends.
            val targetReceived = async { target.incoming.first() }
            // Bystander expects exactly one frame (the sentinel broadcast).
            val bystanderReceived = async { bystander.incoming.first() }

            val directPayload = byteArrayOf(7, 8, 9)
            sender.sendTo(target.selfId, directPayload)

            val targetSwatch = targetReceived.await()
            assertTrue(targetSwatch.toByteArray().contentEquals(directPayload), "target must receive the direct payload")
            assertEquals(sender.selfId, targetSwatch.sender)

            // Now broadcast a sentinel — bystander must see this as its FIRST frame,
            // proving the sendTo never reached it.
            val sentinel = byteArrayOf(99)
            sender.broadcast(sentinel)

            val bystanderSwatch = bystanderReceived.await()
            assertTrue(
                bystanderSwatch.toByteArray().contentEquals(sentinel),
                "bystander's first frame must be the broadcast sentinel, not the sendTo payload",
            )
        }
    }

    // ── (4) a peer leaving updates every survivor's roster ──────────────────

    @Test
    public fun peerLeaveUpdatesSurvivorRosters(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        try {
            val leavingPeer = seams[0]
            val survivors = seams.drop(1)

            leavingPeer.close()

            // Every survivor must eventually see the peer count drop to 2.
            survivors.forEach { survivor ->
                survivor.peers
                    .filter { peers -> leavingPeer.selfId !in peers }
                    .first()
                assertEquals(2, survivor.peers.value.size, "survivor must see 2 peers after one leaves; got ${survivor.peers.value}")
            }
        } finally {
            // Tear every seam down so a fabric that treats peer loss as *recoverable* (e.g. NwSeam since
            // #1513) does not leave survivors' redial loops re-arming against the departed peer — an
            // unbounded re-arm would spin runTest's terminal advanceUntilIdle. close() is idempotent.
            // BOUNDED best-effort: NonCancellable shields cleanup from outer cancellation; withTimeoutOrNull
            // bounds a close() that can wedge on a dead transport so one fabric can't hang the suite; a
            // timeout/error is deliberately swallowed (real close bugs surface in the close-obligation tests).
            //
            // The per-seam guard sits OUTSIDE the bound and is a plain `catch (Throwable)`, not a
            // `runCatchingCancellable` inside it (#1803). `withTimeoutOrNull` absorbs its own timeout; what it
            // rethrows is a `CancellationException` the fabric's `close` minted itself, and inside the shield
            // ours is never cancelled, so that is the only kind reachable. Letting it out would escape the
            // whole block, leaving every later seam in the mesh un-closed with its redial loop re-arming.
            withContext(NonCancellable) {
                seams.forEach { seam ->
                    try {
                        withTimeoutOrNull(2.seconds) { seam.close() }
                    } catch (_: Throwable) {
                        // Best-effort: one seam refusing to close must not strand the rest of the mesh.
                    }
                }
            }
        }
    }

    // ── (5) a broadcast sent once is delivered ONCE to every peer ────────────
    //
    // The exactly-once obligation, and until #2309 the TCK had none at ANY peer count. Every other
    // delivery property here reads `incoming.first()`, which is satisfied by the FIRST of two copies —
    // so a fabric that delivered every frame twice passed the whole suite. That is not a theoretical
    // shape: it is what a surviving duplicate link between two peers produces, and dedup-to-one-link is
    // exactly what the property below this one was named for and could never see (a `Set<PeerId>`
    // cannot hold a peer twice, so no reading of `peers` can distinguish one link from two).
    //
    // ## The idiom, and what makes each part load-bearing
    //
    // [sendToRoutesToExactlyOnePeer] already uses a sentinel broadcast to prove a frame did NOT arrive;
    // this uses one to prove a frame arrived exactly once. The sender broadcasts `PAYLOADS + SENTINEL`;
    // each receiver takes exactly that many frames and must see precisely that sequence. The window and
    // the expectation are the same value, so neither can be narrowed without the other.
    //
    //  - **The sentinel is the window's closing brace, and its arrival is the proof the rig fired.**
    //    A bare "collect until the sentinel and check what preceded it" cannot tell "stopped at the
    //    sentinel" from "the flow ended first" — a torn seam would close the window early and the
    //    property would pass having observed nothing. Taking a FIXED count and asserting the sentinel
    //    is the LAST element converts both failures into a red: one extra frame pushes the sentinel out
    //    of the window, and a short flow leaves a payload (or nothing) in its place.
    //  - **[PAYLOADS] has more than one element on purpose.** With a single payload the only duplicate
    //    the window can hold is a duplicate of that payload; a defect that starts duplicating only
    //    after the first frame — a link that goes double partway through, a redial that re-attaches —
    //    would land entirely outside a two-frame window. Three payloads keep a duplicate of any frame
    //    inside it. Enlarging it further only buys depth, so this is a floor rather than a tuned value:
    //    what must not happen is it being reduced to one.
    //  - **Every receiver is checked, not just one.** A duplicate link is between a PAIR, so a mesh can
    //    duplicate to one peer and not another; asserting on `seams[1]` alone would be green whenever
    //    the doubled link happened to be elsewhere.
    //
    // ## What it rests on, and what it cannot see
    //
    // The assertion is an ordered list rather than a multiset, and that is a deliberate strengthening
    // rather than an accident: `incoming` is one ordered flow of one session's frames, pinned by
    // [SeamConformanceSuite.incomingPreservesSendOrderToSingleCollector], which is UNGATED CORE with no
    // capability flag to excuse it — the `SeamCapabilities.ordersDelivery` flag that once looked like an
    // opt-out was deleted in #2371 precisely because ordering is a contract property no fabric may
    // honestly lack. Every fabric with a mesh harness here also has a [SeamConformanceSuite] subclass
    // holding it to that obligation, so writing the expectation as a multiset would assert strictly
    // less than the contract already guarantees.
    //
    // It cannot see a duplicate delivered AFTER the sentinel — the window closes there. A fabric that
    // replays a whole batch late escapes it. Closing that would need a quiesce (wait for the frame
    // count to stop moving), which this suite cannot express: its awaits are deliberately unbounded
    // because a real-socket mesh is a first-class harness here and `runTest`'s clock is virtual (see
    // the class KDoc). The in-window guarantee is what a fixed-count take can honestly give.
    //
    // Nor does it see the failure the property it replaced was NAMED for. A surviving duplicate link
    // only becomes duplicate delivery in a fabric that fans out over LINKS; `NwSeam` — the one place a
    // genuine double-dial happens — fans out over `registry`, one connection per peer, so its second
    // link carries no application frames at all. Deleting `NwSeam`'s dedup outright (row 4 below) is
    // green across the whole suite, and the branch is genuinely reached. Dedup-to-one-link is
    // therefore still unheld by this TCK; what is held now is the observable the issue named.
    //
    // ## Mutation receipt (#2309)
    //
    // JVM, source-changed so every row EXECUTED. "pre-existing" is the same mutation with this property
    // removed from the picture — it MEASURES the hole rather than asserting it. **real** = a defect that
    // could ship; **rig** = a mutation of this property's own fixture, checking each knob is load-bearing.
    //
    // | # | Mutation | Kind | this property | pre-existing suite |
    // |---|----------|------|---------------|--------------------|
    // | 1 | `MeshSeam.broadcast` sends every frame twice per link | real (fabric) | **RED — `MeshConformanceTest`, 1 of 6** | **green — all 5 mesh obligations**; 2 of 637 elsewhere in `:kuilt-conformance`, both `SeamConformanceSuite.incomingPreservesSendOrderToSingleCollector` |
    // | 2 | `NwSeam.broadcast` sends every frame twice per link | real (fabric) | **RED — `NwMeshConformanceTest`, 1 of 6** | green — all 5 mesh obligations (6 of 186 elsewhere in `:kuilt-nw`) |
    // | 3 | `InMemoryLoom.dispatch` delivers every frame twice | real (reference) | RED — in-memory mesh; **green — composite mesh (correct: `PlyInboundGate` absorbs it)** | RED — 6, blast radius |
    // | 3b | row 3 **plus** `PlyInboundGate` stops dropping duplicates | real (fabric) | RED — composite mesh too | RED — 8, blast radius |
    // | 4 | `NwSeam.resolveIdentity` does no dedup — BOTH links survive, neither is disconnected | real (fabric) | **green** | **green — all 186** |
    // | 5 | duplication starts at the 2nd frame; `PAYLOADS` = 3 | rig | RED | green |
    // | 6 | same defect as row 5; `PAYLOADS` = **1** | rig | **green** | green |
    // | 7 | only the LAST payload is duplicated; sentinel in the window | rig | RED | green |
    // | 8 | same defect as row 7; window shortened to drop the sentinel | rig | **green** | green |
    //
    // **Rows 1 and 2 are the argument** — the decisive shape, on the reference fabric and on the real
    // one: this property reds while `everyPeerSeesTheWholeRosterInAFourPeerMesh` (the test that used to
    // claim this coverage) and `broadcastReachesAllPeers` both stay green. Row 1's "pre-existing"
    // cell is the hole measured rather than asserted: at THREE peers nothing saw the defect, while the
    // two 2-peer reds show `SeamConformanceSuite` already catches interleaved duplication through its
    // ordering obligation — which is why this landed here and not there.
    //
    // **Rows 6 and 8 are why the fixture's two numbers are floors, not taste.** Each pairs with the row
    // above it: the SAME defect, one knob moved, and the red disappears. One payload cannot hold a
    // duplicate that begins after the first frame; a window that stops before the sentinel cannot hold a
    // duplicate of the last payload. Both are the shape this epic keeps finding — a fixture configured
    // at exactly the value where the property cannot fail — caught here by moving the knob rather than
    // by reasoning about it.
    //
    // **Row 4 is the honest green** and is discussed above: the mutation IS reached (a probe placed in
    // that branch fires 42 times across this suite's 6 tests on `NwMeshConformanceTest`, counted rather
    // than inferred), so it is a real green, not an unexecuted mutation.
    //
    // The roster precondition has **no red in any row**, and that is correct rather than a gap: on every
    // in-tree fabric `peers` is derived from the same link/registry map the send path fans out over, so
    // the two cannot diverge. It exists to convert a harness-contract violation — `newMeshOfSize`
    // returning peers that never met — from an unbounded wedge into a named failure, and only an
    // out-of-tree harness can produce that.

    @Test
    public fun broadcastIsDeliveredExactlyOnceToEveryPeer(): TestResult = runTest {
        val seams = newMeshOfSize(3)
        val sender = seams[0]
        val receivers = seams.drop(1)

        // Preconditions. Without them the property can go green on a mesh that never carried the
        // broadcast at all: an empty receiver list asserts nothing, and a sender that does not hold
        // the receivers in its roster would leave every collector below waiting on a frame that was
        // never addressed to it — an unbounded wedge reporting nothing but `runTest`'s ceiling.
        assertEquals(3, seams.size, "harness contract: newMeshOfSize(3) must return exactly 3 seams")
        assertTrue(receivers.isNotEmpty(), "precondition: there must be a peer to deliver to")
        receivers.forEach { receiver ->
            assertTrue(
                receiver.selfId in sender.peers.value,
                "precondition: the sender must hold ${receiver.selfId} in its roster before broadcasting, " +
                    "or the frames below are never addressed to it and the collector wedges; " +
                    "sender peers: ${sender.peers.value}",
            )
        }
        assertTrue(
            PAYLOADS.none { it.contentEquals(SENTINEL) },
            "rig: the sentinel must be distinguishable from every payload, or the window closes on the " +
                "first payload and the property asserts nothing",
        )
        // Rows 5/6 of the receipt above: with ONE payload the same defect goes green, because the only
        // duplicate a two-frame window can hold is a duplicate of the very first frame. Nothing else in
        // the suite fails when this floor is lowered, so it is asserted here rather than left as prose
        // for a future editor to re-derive.
        assertTrue(
            PAYLOADS.size >= 2,
            "rig: at least two payloads are required — with one, a defect that starts duplicating after " +
                "the first frame lands entirely outside the window and this property passes (got ${PAYLOADS.size})",
        )

        // The window and the expectation are ONE value. Rows 7/8 of the receipt are the edit that
        // shortens the window past the sentinel, and it is green; deriving both from `sent` makes that
        // edit unrepresentable rather than merely untested.
        val sent = PAYLOADS + SENTINEL
        val expected = sent.map { it.toList() }

        coroutineScope {
            // Subscribe every receiver BEFORE the first send — `incoming` is single-collection
            // (ADR-034) and these are its sole collectors for the whole test.
            val collected = receivers.map { receiver ->
                async { receiver.incoming.take(sent.size).toList().map { it.toByteArray().toList() } }
            }

            sent.forEach { frame -> sender.broadcast(frame) }

            receivers.forEachIndexed { i, receiver ->
                val frames = collected[i].await()
                assertEquals(
                    expected,
                    frames,
                    "a frame broadcast ONCE must be delivered exactly ONCE: ${receiver.selfId} saw " +
                        "$frames where the sender sent $expected. A repeated payload here is duplicate " +
                        "delivery — the observable signature of a surviving duplicate link (which " +
                        "`Seam.peers`, being a Set, can never reveal). The sentinel " +
                        "${SENTINEL.toList()} not arriving last means an EXTRA frame pushed it out of " +
                        "the window, or `incoming` completed before it could arrive",
                )
            }
        }
    }

    // ── (6) every peer sees the whole roster, at an arity above the others ───
    //
    // **Renamed from `simultaneousDialsDedupToOneLink` (#2309), which is not what it checks and never
    // could be.** It reads only `Seam.peers` — a `StateFlow<Set<PeerId>>` — and a surviving duplicate
    // link between two peers changes nothing a set can express. The old inline comment had the causal
    // claim backwards ("no duplicate links causing wrong peer counts or missing peers"): a duplicate
    // link does not cause a wrong peer count, it causes duplicate frame DELIVERY, which is what
    // [broadcastIsDeliveredExactlyOnceToEveryPeer] above now observes. The harness contract sealed it
    // further — [newMeshOfSize] must not return until every handshake is complete, so any dedup race is
    // already over before the first assertion runs.
    //
    // It is renamed rather than deleted because it is NOT subsumed by [eachPeerSeesMeshSize], which was
    // the other option #2309 offered. Two things here are absent there: the arity (four peers, not
    // three — a mesh whose wiring is quadratic in n has bugs that only appear above three), and the
    // identity check. `eachPeerSeesMeshSize` asserts a peer's roster has the right SIZE and contains
    // itself; this asserts it contains exactly the right IDS, so a roster of the right size holding a
    // phantom peer — the shape a stale or misattributed link produces — fails here and passes there.
    // What was worth deleting was the claim in the name, not the coverage.

    @Test
    public fun everyPeerSeesTheWholeRosterInAFourPeerMesh(): TestResult = runTest {
        val n = 4
        val seams = newMeshOfSize(n)
        seams.forEach { seam ->
            assertEquals(
                n,
                seam.peers.value.size,
                "each peer must see all $n peers; got ${seam.peers.value} on ${seam.selfId}",
            )
        }

        // Confirm all peer ids are distinct.
        val allSelfIds = seams.map { it.selfId }.toSet()
        assertEquals(n, allSelfIds.size, "all peer ids must be distinct")

        // Each peer must see all other peers' ids — the check a size-only assertion cannot make.
        seams.forEach { seam ->
            allSelfIds.forEach { peerId ->
                assertTrue(peerId in seam.peers.value, "${seam.selfId} must see $peerId in peers")
            }
        }
    }

    private companion object {
        /**
         * The frames whose exactly-once delivery is under test. More than one on purpose — see
         * [broadcastIsDeliveredExactlyOnceToEveryPeer]'s comment: a single payload cannot hold a
         * duplicate that only starts after the first frame.
         */
        val PAYLOADS: List<ByteArray> = listOf(
            byteArrayOf(0x11, 0x01),
            byteArrayOf(0x11, 0x02),
            byteArrayOf(0x11, 0x03),
        )

        /** The window's closing brace. Distinct from every entry in [PAYLOADS] in its first byte. */
        val SENTINEL: ByteArray = byteArrayOf(0x22, 0x00)
    }
}
