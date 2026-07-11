/**
 * Tests for [tieredSeam] — the tiered-union `Seam` that bonds a local-tier and a
 * peer-tier `Seam` (disjoint rosters) into one: union roster, teed broadcast,
 * single-addressee unicast, and a merged single-collection `incoming`.
 *
 * The two tiers are two independent [InMemoryLoom] meshes (each is one flat mesh),
 * standing in for slice 6's `RoomHubSeam` (local room) and `NamedMux` core channel
 * (other servers). The peer mesh's id counter is advanced (throwaway joins that are
 * closed) so the two members have **disjoint** [PeerId]s — the invariant this
 * primitive assumes and the only way to prove single-addressee routing.
 *
 * Uses [UnconfinedTestDispatcher] so the seam's internal pumps run eagerly inside
 * [runTest]; the injected scope is [backgroundScope] so the infinite union/incoming
 * pumps cancel cleanly at teardown.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TieredSeamTest {

    /** Bundles the two-tier fixture: the node-under-test's tiered seam plus a member on each tier. */
    private class Fixture(
        val tiered: Seam,
        val localMember: Seam,
        val peerMember: Seam,
        val selfLocal: Seam,
        val selfPeer: Seam,
    )

    /**
     * Build a node-under-test whose local tier and peer tier are separate [InMemoryLoom]
     * meshes. The self seam is hosted on each mesh (both mint `peer-1`, so `selfId`
     * matches across the tiers, as slice 6 requires). The peer mesh's counter is advanced
     * so [Fixture.localMember] and [Fixture.peerMember] have disjoint ids.
     */
    private suspend fun buildFixture(
        scope: kotlinx.coroutines.CoroutineScope,
    ): Fixture {
        val loomLocal = InMemoryLoom()
        val selfLocal = loomLocal.host(Pattern("tiered-local"))
        val localMember = loomLocal.join(InMemoryTag("local-member"))

        val loomPeer = InMemoryLoom()
        val selfPeer = loomPeer.host(Pattern("tiered-peer"))
        // Advance the peer mesh's id counter so peerMember != localMember's id, then drop
        // the throwaways so they don't pollute the peer roster.
        loomPeer.join(InMemoryTag("burn-1")).close()
        loomPeer.join(InMemoryTag("burn-2")).close()
        val peerMember = loomPeer.join(InMemoryTag("peer-member"))

        val tiered = tieredSeam(local = selfLocal, peer = selfPeer, scope = scope)
        return Fixture(tiered, localMember, peerMember, selfLocal, selfPeer)
    }

    // ── 1 · peers is the union, and updates when either tier's roster changes ──

    @Test
    fun peersIsTheUnionOfBothTiers() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        assertEquals(
            f.localMember.selfId, // peer-2 on the local mesh
            f.tiered.peers.value.intersect(setOf(f.localMember.selfId)).firstOrNull(),
            "the local member must appear in the union roster",
        )
        assertAll(
            { assertTrue(f.tiered.selfId in f.tiered.peers.value, "self is in the union") },
            { assertTrue(f.localMember.selfId in f.tiered.peers.value, "local member is in the union") },
            { assertTrue(f.peerMember.selfId in f.tiered.peers.value, "peer member is in the union") },
            {
                assertEquals(
                    setOf(f.tiered.selfId, f.localMember.selfId, f.peerMember.selfId),
                    f.tiered.peers.value,
                    "union is exactly self ∪ localMember ∪ peerMember (ids disjoint)",
                )
            },
        )
    }

    @Test
    fun peersUpdatesWhenALocalTierRosterGrows() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loomLocal = InMemoryLoom()
        val selfLocal = loomLocal.host(Pattern("tiered-grow-local"))
        val loomPeer = InMemoryLoom()
        val selfPeer = loomPeer.host(Pattern("tiered-grow-peer"))

        val tiered = tieredSeam(local = selfLocal, peer = selfPeer, scope = backgroundScope)
        val before = tiered.peers.value

        val newLocal = loomLocal.join(InMemoryTag("late-local"))

        assertAll(
            { assertTrue(newLocal.selfId !in before, "new member wasn't in the roster before joining") },
            { assertTrue(newLocal.selfId in tiered.peers.value, "union recomputes when the local tier's roster grows") },
        )
    }

    // ── 2 · broadcast tees to BOTH tiers ──────────────────────────────────────

    @Test
    fun broadcastTeesToBothTiers() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)
        val payload = byteArrayOf(7, 7, 7)

        val onLocal = async { f.localMember.incoming.first() }
        val onPeer = async { f.peerMember.incoming.first() }

        f.tiered.broadcast(payload)

        val gotLocal = onLocal.await().toByteArray()
        val gotPeer = onPeer.await().toByteArray()
        assertAll(
            { assertTrue(gotLocal.contentEquals(payload), "broadcast reaches the local tier") },
            { assertTrue(gotPeer.contentEquals(payload), "broadcast reaches the peer tier") },
        )
    }

    // ── 3 · sendTo is single-addressee across the union ───────────────────────

    @Test
    fun sendToLocalMemberReachesOnlyTheLocalTier() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        val peerInbox = f.peerMember.incoming.produceIn(this)
        val onLocal = async { f.localMember.incoming.first() }

        f.tiered.sendTo(f.localMember.selfId, byteArrayOf(1))

        val gotLocal = onLocal.await().toByteArray()
        assertAll(
            { assertTrue(gotLocal.contentEquals(byteArrayOf(1)), "the local member receives the unicast") },
            { assertTrue(peerInbox.tryReceive().isFailure, "the peer tier must NOT receive a unicast addressed to a local member") },
        )
        peerInbox.cancel()
    }

    @Test
    fun sendToPeerMemberReachesOnlyThePeerTier() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        val localInbox = f.localMember.incoming.produceIn(this)
        val onPeer = async { f.peerMember.incoming.first() }

        f.tiered.sendTo(f.peerMember.selfId, byteArrayOf(2))

        val gotPeer = onPeer.await().toByteArray()
        assertAll(
            { assertTrue(gotPeer.contentEquals(byteArrayOf(2)), "the peer member receives the unicast") },
            { assertTrue(localInbox.tryReceive().isFailure, "the local tier must NOT receive a unicast addressed to a peer member") },
        )
        localInbox.cancel()
    }

    @Test
    fun sendToUnknownPeerIsDroppedNothingSent() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        val localInbox = f.localMember.incoming.produceIn(this)
        val peerInbox = f.peerMember.incoming.produceIn(this)

        // No exception, and nothing delivered to either tier.
        f.tiered.sendTo(PeerId("nobody"), byteArrayOf(9))

        assertAll(
            { assertTrue(localInbox.tryReceive().isFailure, "unknown-peer unicast must not reach the local tier") },
            { assertTrue(peerInbox.tryReceive().isFailure, "unknown-peer unicast must not reach the peer tier") },
        )
        localInbox.cancel()
        peerInbox.cancel()
    }

    // ── 4 · incoming merges both underlying seams, exactly once each ──────────

    @Test
    fun incomingMergesFramesFromEitherTier() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        val inbox = f.tiered.incoming.produceIn(this)

        f.localMember.broadcast(byteArrayOf(10))
        f.peerMember.broadcast(byteArrayOf(20))

        val a = inbox.receive().toByteArray()
        val b = inbox.receive().toByteArray()

        assertAll(
            {
                assertEquals(
                    setOf(listOf<Byte>(10), listOf<Byte>(20)),
                    setOf(a.toList(), b.toList()),
                    "both tiers' frames surface on the tiered incoming (order-independent)",
                )
            },
            { assertTrue(inbox.tryReceive().isFailure, "each frame surfaces exactly once — no duplication") },
        )
        inbox.cancel()
    }

    @Test
    fun selfIdMismatchIsRejected() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val loomLocal = InMemoryLoom()
        val selfLocal = loomLocal.host(Pattern("tiered-mismatch-local"))
        val loomPeer = InMemoryLoom()
        // Advance the peer mesh so its host mints a different id than selfLocal's.
        loomPeer.join(InMemoryTag("burn")).close()
        val selfPeer = loomPeer.host(Pattern("tiered-mismatch-peer"))

        kotlin.test.assertFailsWith<IllegalArgumentException>("both tiers must be views of the SAME node") {
            tieredSeam(local = selfLocal, peer = selfPeer, scope = backgroundScope)
        }
    }

    // ── 4b · both tiers torn is TERMINAL, latched Torn — never a revivable rollup (#1367) ──

    /**
     * When BOTH tiers reach [SeamState.Torn] — driven directly on the underlying tiers, NOT via
     * [tieredSeam]'s own `close()` — the composed lifecycle is **terminal** [SeamState.Torn], and it
     * **latches**: a tier subsequently flapping back to [SeamState.Woven] must NOT revive the union.
     *
     * Unlike [us.tractat.kuilt.core.composite.CompositeSeam] (whose persistent spool survives ply
     * churn, so its all-plies-torn rollup is recoverable `Weaving`), a tiered union's [incoming] is a
     * one-shot merge that completes permanently when both tiers' `incoming` complete — so both-tiers-
     * torn is genuinely terminal, and the merged `incoming` must complete consistently with the
     * terminal `state` (#1367). A revivable `Weaving` here would contradict a terminally-completed
     * `incoming` and hang a `state.first { it is Torn }` waiter — the exact bug class this closes.
     */
    @Test
    fun bothTiersTornIsTerminalLatchedTornAndIncomingCompletes() =
        runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
            // Two FakeSeams sharing one selfId (both tiers are views of the SAME node) whose state and
            // incoming we drive directly, so we can tear both then flap one back to Woven.
            val node = PeerId("node")
            val local = FakeSeam(selfId = node, initialState = SeamState.Woven)
            val peer = FakeSeam(selfId = node, initialState = SeamState.Woven)
            val tiered = tieredSeam(local = local, peer = peer, scope = backgroundScope)

            // Track merged-incoming completion — it must complete when both tiers' incoming complete.
            var incomingCompleted = false
            backgroundScope.launch {
                tiered.incoming.collect { }
                incomingCompleted = true
            }

            assertTrue(tiered.state.value is SeamState.Woven, "both tiers Woven ⇒ union Woven")

            // Tear ONE tier — the surviving tier still carries, so state stays Woven.
            local.close(CloseReason.Unreachable)
            assertTrue(tiered.state.value is SeamState.Woven, "one tier torn ⇒ union stays Woven (survivor carries)")

            // Tear the SECOND tier — both torn ⇒ terminal Torn, and the merged incoming completes.
            peer.close(CloseReason.Unreachable)

            assertAll(
                { assertTrue(tiered.state.value is SeamState.Torn, "both tiers torn ⇒ terminal Torn (#1367)") },
                { assertTrue(incomingCompleted, "merged incoming must complete when both tiers' incoming complete") },
            )

            // Flap a tier back to Woven — the latched terminal Torn must NOT revive.
            local.weave()
            assertTrue(
                tiered.state.value is SeamState.Torn,
                "a latched terminal Torn must NOT revert when a tier flaps back to Woven (no revive)",
            )
        }

    // ── 5 · close() lifecycle ─────────────────────────────────────────────────

    @Test
    fun closeTearsDownBothTiers() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        f.tiered.close(CloseReason.Normal)

        assertAll(
            { assertTrue(f.tiered.state.value is SeamState.Torn, "tiered state is Torn after close") },
            { assertTrue(f.tiered.peers.value.isEmpty(), "tiered peers empties after close") },
            { assertTrue(f.selfLocal.state.value is SeamState.Torn, "the local tier is closed") },
            { assertTrue(f.selfPeer.state.value is SeamState.Torn, "the peer tier is closed") },
        )
    }

    /**
     * Regression for the `close()` scope bug: the internal child scope must NOT be the caller's
     * scope, so `close()` cancels only the seam's own pumps — never the scope the caller passed
     * in. Fails on the old code (SupervisorJob dropped by `plus`, so `scope.cancel()` tore the
     * parent); passes on the fix (SupervisorJob wins the Job key).
     */
    @Test
    fun closeDoesNotCancelTheCallerScope() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        // A scope we own (not backgroundScope, which the harness cancels at teardown anyway).
        val callerScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val loomLocal = InMemoryLoom()
        val selfLocal = loomLocal.host(Pattern("tiered-close-scope-local"))
        val loomPeer = InMemoryLoom()
        val selfPeer = loomPeer.host(Pattern("tiered-close-scope-peer"))

        // A long-lived sibling in the SAME scope; it must survive the tiered seam's close().
        val stillRunning = CompletableDeferred<Unit>()
        val sibling = callerScope.launch { stillRunning.await() }

        val tiered = tieredSeam(local = selfLocal, peer = selfPeer, scope = callerScope)
        tiered.close(CloseReason.Normal)

        assertAll(
            { assertTrue(callerScope.isActive, "the caller's scope must stay active after close()") },
            { assertTrue(sibling.isActive, "a sibling coroutine in the caller's scope must survive close()") },
        )
        callerScope.cancel() // clean up the sibling + the seam's pumps' parent
    }

    @Test
    fun closeIsIdempotent() = runTest(UnconfinedTestDispatcher(), timeout = 5.seconds) {
        val f = buildFixture(backgroundScope)

        f.tiered.close(CloseReason.Normal)
        f.tiered.close(CloseReason.Normal) // second close must be a no-op — no throw, no double-close.

        assertAll(
            { assertTrue(f.tiered.state.value is SeamState.Torn, "state stays Torn after a second close") },
            { assertFalse(f.tiered.peers.value.isNotEmpty(), "peers stays empty after a second close") },
        )
    }
}
