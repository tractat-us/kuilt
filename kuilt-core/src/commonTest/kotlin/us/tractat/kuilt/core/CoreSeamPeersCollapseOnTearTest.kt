@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [Seam.peers] collapse obligation, for the two `:kuilt-core` seams **no bound conformance suite
 * reaches**: [TieredSeam] and [RoomHubSeam].
 *
 * `Seam.peers` requires a `Torn` seam's roster to be **exactly `{ selfId }`**, published **before, or
 * atomically with**, the terminal `Torn` latch. `SeamConformanceSuite.peersCollapseToSelfIdWhenTorn`
 * asserts the value half — but only against the fabrics some subclass actually **binds**, and no
 * subclass binds either of these. That is a wiring gap, **not** a structural one, and the distinction
 * matters: `MuxServerLoom` *is* a `Loom` and its `host()` hands back exactly this [RoomHubSeam], so a
 * conformance harness for it looks constructible (`kuilt-conformance` already wires `MuxServerLoom` in
 * `MuxServerLoomFanoutIsolationTest`). [TieredSeam] is the harder case — it comes from the
 * `tieredSeam(...)` composition function over two arbitrary member seams rather than from a `Loom`.
 *
 * Either way the TCK's enumeration — *"a fabric that cannot honour it yet declares
 * `collapsesPeersOnTear = false` with a tracking issue, so the exposure is enumerated instead of
 * unknown"* — never covered either, and both collapsed to `emptySet()` while latching `Torn` **first**.
 * This file is their standing check until that binding exists.
 *
 * ### Why both halves matter, and why the ordering needs its own probe
 * A seam that drops `selfId` has collapsed **too far**: "always including this peer's own id" is an
 * unconditional [Seam.peers] invariant, and [RoomHubSeam] is public API whose own `_peers` KDoc cites
 * it. The TCK spells the two deviations out separately for that reason, and so does this.
 *
 * The *ordering* half is invisible to a dispatched collector: `peers` is a conflating `StateFlow`, so a
 * collector resumed after `close()` returns always reads the settled value and would pass against any
 * implementation. Both ordering probes below therefore collect on an [UnconfinedTestDispatcher], which
 * resumes them **inline** inside `SeamStateGate.tear`'s `_state` write — what they read from `peers` is
 * the value at exactly the instant `Torn` became observable. Same shape, and same reason, as
 * `CompositeCloseCollapseOrderTest` (#1816).
 *
 * Note what *cannot* be fixed by simply swapping the two statements: both seams have a live writer that
 * republishes the roster (`TieredSeam`'s union pump, `RoomHubSeam`'s `deliver` registration) and each
 * guarded itself by reading `state`. Collapsing before the latch without moving that guard opens a
 * window in which the writer sees a not-yet-`Torn` seam and resurrects the roster **after** the
 * collapse, permanently — trading one violation for a worse one. The no-resurrection property is pinned
 * by [TieredSeamTest] and `RoomHubSeamCloseTest.deliverInFlightDuringCloseDoesNotResurrectRoster`;
 * both must stay green alongside this file.
 */
class CoreSeamPeersCollapseOnTearTest {

    /** A tiered union over two independent in-memory meshes, plus a member on the local one. */
    private class Tiered(val tiered: Seam, val localMember: Seam)

    private suspend fun buildTiered(scope: kotlinx.coroutines.CoroutineScope): Tiered {
        val loomLocal = InMemoryLoom()
        val selfLocal = loomLocal.host(Pattern("tiered-local"))
        val localMember = loomLocal.join(InMemoryTag("local-member"))
        val loomPeer = InMemoryLoom()
        val selfPeer = loomPeer.host(Pattern("tiered-peer"))
        return Tiered(tieredSeam(local = selfLocal, peer = selfPeer, scope = scope), localMember)
    }

    // ── TieredSeam ────────────────────────────────────────────────────────────────────────────

    @Test
    fun aTornTieredSeamAdvertisesExactlyItsOwnId() = runTest {
        val fixture = buildTiered(backgroundScope)
        val tiered = fixture.tiered

        // A roster worth collapsing — otherwise the assertion cannot tell a correct collapse from a
        // seam that never had a remote peer to lose.
        val before = tiered.peers.first { it.size > 1 }
        assertTrue(fixture.localMember.selfId in before, "precondition: the union must have seen the member")

        tiered.close()

        val peers = tiered.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - tiered.selfId,
                    "a Torn seam must advertise NO reachable remote peer (Seam.peers): a decorator " +
                        "folding this one reads what is left here as still reachable until it detaches",
                )
            },
            {
                assertTrue(
                    tiered.selfId in peers,
                    "a Torn seam's collapsed roster is { selfId }, not empty — dropping selfId collapses " +
                        "too far (got ${peers.map { it.value }})",
                )
            },
        )
        fixture.localMember.close()
    }

    @Test
    fun theTieredCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val fixture = buildTiered(backgroundScope)
        val tiered = fixture.tiered
        tiered.peers.first { it.size > 1 }

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tiered.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(tiered.peers.value)
        }
        runCurrent()

        tiered.close()

        assertAll(
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — close() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(tiered.selfId),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers): " +
                        "a consumer woken by the terminal state must not read the pre-close roster",
                )
            },
        )
        fixture.localMember.close()
    }

    // ── RoomHubSeam ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aTornRoomHubAdvertisesExactlyItsOwnId() = runTest {
        val self = PeerId("server")
        val room = RoomHubSeam("table-7", self, RoomAuthorizer.AllowAll)
        val spoke = PeerId("joiner")
        room.deliver(spoke, Swatch(payload = byteArrayOf(1), sender = spoke), OutboundSender { }, principal = null)
        assertTrue(spoke in room.peers.value, "precondition: the spoke must be registered before the tear")

        room.close()

        val peers = room.peers.value
        assertAll(
            {
                assertEquals(
                    emptySet(),
                    peers - self,
                    "a Torn hub must advertise NO reachable remote peer (Seam.peers)",
                )
            },
            {
                assertTrue(
                    self in peers,
                    "a Torn hub's collapsed roster is { selfId }, not empty — the hub is a peer in its " +
                        "own room roster (got ${peers.map { it.value }})",
                )
            },
        )
    }

    @Test
    fun theRoomHubCollapsedRosterIsAlreadyPublishedWhenTornBecomesObservable() = runTest {
        val self = PeerId("server")
        val room = RoomHubSeam("table-7", self, RoomAuthorizer.AllowAll)
        val spoke = PeerId("joiner")
        room.deliver(spoke, Swatch(payload = byteArrayOf(1), sender = spoke), OutboundSender { }, principal = null)

        val peersWhenTornBecameVisible = CompletableDeferred<Set<PeerId>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            room.state.first { it is SeamState.Torn }
            peersWhenTornBecameVisible.complete(room.peers.value)
        }
        runCurrent()

        room.close()

        assertAll(
            {
                assertTrue(
                    peersWhenTornBecameVisible.isCompleted,
                    "the probe must have observed the terminal Torn — close() did not latch it",
                )
            },
            {
                assertEquals(
                    setOf(self),
                    peersWhenTornBecameVisible.getCompleted(),
                    "peers must ALREADY be collapsed at the instant Torn becomes observable (Seam.peers)",
                )
            },
        )
    }
}
