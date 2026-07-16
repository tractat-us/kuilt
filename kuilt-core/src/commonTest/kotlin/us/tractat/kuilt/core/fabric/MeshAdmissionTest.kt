@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core.fabric

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Principal
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.withPrincipal
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.connectionPair
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LinkAdmission] enforcement inside [Mesh.addLink] / [meshSeam] construction, and the
 * [us.tractat.kuilt.core.PrincipalRoster] the mesh maintains atomically with its link set.
 *
 * The check runs BETWEEN the `MeshHello` handshake and link publication — the only point where
 * the host-verified principal (riding the connection) and the self-asserted peer id (claimed in
 * the preamble) coexist before the link can contend in dedup or receive frames.
 */
class MeshAdmissionTest {

    /** The consumer-side spoofing check: verified principal must match the claimed peer id. */
    private val binding = LinkAdmission { principal, remoteId -> principal?.value == remoteId.value }

    // ── P1: closed mode — reject-and-continue ─────────────────────────────────

    /**
     * A rejected joiner is dropped, but the seam and a concurrently-admitted legitimate joiner
     * stay intact: the hub keeps serving. The rejection is a non-fatal per-link
     * [LinkRejectedException] signal (the accept-pump absorbs and debug-logs it); it never tears
     * down the seam or the good link.
     */
    @Test
    fun requireAttestedDropsUnattestedButKeepsTheSeamAndValidLinks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val good = PeerId("good")
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0), admission = LinkAdmission.RequireAttested)

        // A legitimate attested joiner is admitted and stays admitted.
        val (goodHubEnd, goodFarEnd) = connectionPair()
        val goodHandshake = launch { handshakeRemote(goodFarEnd, good, nonce = byteArrayOf(2)) }
        mesh.addLink(goodHubEnd.withPrincipal(Principal("user-good")))
        goodHandshake.join()
        assertEquals(setOf(hub, good), mesh.peers.value)

        // An unattested joiner is rejected — closed, never published — but the seam survives.
        val (badHubEnd, badFarEnd) = connectionPair()
        val badHandshake = launch { handshakeRemote(badFarEnd, PeerId("joiner"), nonce = byteArrayOf(1)) }
        val rejection = assertFailsWith<LinkRejectedException> { mesh.addLink(badHubEnd) }
        badHandshake.join()
        val badRemainingFrames = badFarEnd.incoming.toList()

        // The good link still works after the rejection: a frame to it still lands.
        val payload = byteArrayOf(4, 2)
        val received = async { goodFarEnd.incoming.first() }
        mesh.sendTo(good, payload)
        val goodGotFrame = received.await()

        assertAll(
            { assertEquals(PeerId("joiner"), rejection.remoteId) },
            { assertFalse(rejection.attested, "the rejected link carried no principal") },
            { assertEquals(setOf(hub, good), mesh.peers.value, "rejected link absent; good link intact") },
            { assertEquals(mapOf(good to Principal("user-good")), mesh.attestedPrincipals.value) },
            { assertTrue(badRemainingFrames.isEmpty(), "the rejected connection must be closed") },
            { assertContentEquals(payload, goodGotFrame, "the seam keeps serving the admitted link after a rejection") },
            { assertEquals(SeamState.Woven, mesh.state.value, "one rejection must not tear down the seam") },
        )
    }

    @Test
    fun requireAttestedAdmitsAttestedLink() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val joiner = PeerId("joiner")
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0), admission = LinkAdmission.RequireAttested)

        val (hubEnd, farEnd) = connectionPair()
        val far = launch { handshakeRemote(farEnd, joiner, nonce = byteArrayOf(1)) }
        mesh.addLink(hubEnd.withPrincipal(Principal("user-7")))
        far.join()

        assertAll(
            { assertEquals(setOf(hub, joiner), mesh.peers.value) },
            { assertEquals(mapOf(joiner to Principal("user-7")), mesh.attestedPrincipals.value) },
        )
    }

    // ── P2: the spoofing hole — rejected BEFORE the dedup lottery ────────────

    /**
     * An attacker claiming a victim's PeerId, with a link nonce crafted to WIN the canonical
     * dedup tiebreak (see [controlSpoofedLinkWinsDedupLotteryUnderAcceptAll], which proves the
     * same nonce displaces the victim when no policy rejects it), must be rejected before the
     * lottery ever runs: the victim's live link survives and keeps receiving `sendTo` frames.
     */
    @Test
    fun bindingMismatchIsRejectedBeforeDedupLottery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val victim = PeerId("victim")
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0), admission = binding)

        // The victim's legitimate, attested link.
        val (victimHubEnd, victimFarEnd) = connectionPair()
        val victimHandshake = launch { handshakeRemote(victimFarEnd, victim, nonce = byteArrayOf(-1)) }
        mesh.addLink(victimHubEnd.withPrincipal(Principal("victim")))
        victimHandshake.join()
        assertEquals(setOf(hub, victim), mesh.peers.value)

        // The attacker: verified as "mallory", claims "victim", nonce crafted to win dedup.
        val (attackerHubEnd, attackerFarEnd) = connectionPair()
        val attackerHandshake = launch { handshakeRemote(attackerFarEnd, victim, nonce = ByteArray(16)) }
        val rejection = assertFailsWith<LinkRejectedException> {
            mesh.addLink(attackerHubEnd.withPrincipal(Principal("mallory")))
        }
        attackerHandshake.join()

        // The victim's link must be untouched: a point-to-point frame still lands on it.
        val payload = byteArrayOf(9, 8, 7)
        val received = async { victimFarEnd.incoming.first() }
        mesh.sendTo(victim, payload)
        val victimGotFrame = received.await()
        val attackerFrames = attackerFarEnd.incoming.toList()

        assertAll(
            { assertEquals(victim, rejection.remoteId) },
            { assertTrue(rejection.attested, "the attacker's link WAS attested — just to the wrong subject") },
            { assertContentEquals(payload, victimGotFrame, "the victim's live link must survive the spoof attempt") },
            { assertTrue(attackerFrames.isEmpty(), "the attacker's connection must be closed, having received nothing") },
            { assertEquals(mapOf(victim to Principal("victim")), mesh.attestedPrincipals.value) },
            { assertEquals(setOf(hub, victim), mesh.peers.value, "the spoof must not disturb the live roster") },
            { assertEquals(SeamState.Woven, mesh.state.value, "a rejected spoof must not tear down the seam") },
        )
    }

    /**
     * The control for [bindingMismatchIsRejectedBeforeDedupLottery]: under [LinkAdmission.AcceptAll]
     * (today's behaviour) the identical spoofed link — same claimed id, same crafted nonce — WINS
     * the dedup tiebreak and displaces the victim's live link, hijacking `sendTo(victim, …)`.
     * This proves the crafted nonce really wins the lottery, so the binding test above passes
     * because the check runs *before* dedup, not because the attacker happened to lose the flip.
     */
    @Test
    fun controlSpoofedLinkWinsDedupLotteryUnderAcceptAll() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val victim = PeerId("victim")
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0))

        val (victimHubEnd, victimFarEnd) = connectionPair()
        val victimHandshake = launch { handshakeRemote(victimFarEnd, victim, nonce = byteArrayOf(-1)) }
        mesh.addLink(victimHubEnd)
        victimHandshake.join()

        val (attackerHubEnd, attackerFarEnd) = connectionPair()
        val attackerHandshake = launch { handshakeRemote(attackerFarEnd, victim, nonce = ByteArray(16)) }
        mesh.addLink(attackerHubEnd)
        attackerHandshake.join()

        // The victim's frames now land on the attacker's link — threat 2 of the design's model.
        val payload = byteArrayOf(9, 8, 7)
        val received = async { attackerFarEnd.incoming.first() }
        mesh.sendTo(victim, payload)
        assertContentEquals(payload, received.await(), "the spoofed link displaced the victim (dedup lottery won)")
    }

    // ── Construction-time symmetry — reject-and-continue, no sibling teardown ──

    /**
     * Construction applies the policy per link and **rejects-and-continues**: given a batch of
     * concurrently-handshaking connections — one attested (admitted), one unattested (rejected) —
     * `meshSeam` does NOT throw, does NOT cancel the sibling handshake, and returns a live mesh
     * built from the survivor. One unauthorized joiner never tears down the concurrent legitimate
     * handshake or the seam.
     */
    @Test
    fun constructionRejectsAndContinuesWithoutTearingDownSiblings() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val good = PeerId("good")

        val (goodMine, goodTheirs) = connectionPair()
        val (badMine, badTheirs) = connectionPair()
        val goodHandshake = launch { handshakeRemote(goodTheirs, good, nonce = byteArrayOf(1)) }
        val badHandshake = launch { handshakeRemote(badTheirs, PeerId("joiner"), nonce = byteArrayOf(2)) }

        // Mixed batch: the good link is attested, the bad one is not. Construction must not throw.
        val mesh = hubMesh(
            hub,
            listOf(goodMine.withPrincipal(Principal("user-good")), badMine),
            dispatcher,
            Random(0),
            admission = LinkAdmission.RequireAttested,
        )
        goodHandshake.join()
        badHandshake.join()
        val badRemainingFrames = badTheirs.incoming.toList()

        assertAll(
            { assertEquals(setOf(hub, good), mesh.peers.value, "the survivor is admitted; the rejected link is dropped") },
            { assertEquals(mapOf(good to Principal("user-good")), mesh.attestedPrincipals.value) },
            { assertEquals(SeamState.Woven, mesh.state.value, "construction rejection must not tear down the seam") },
            { assertTrue(badRemainingFrames.isEmpty(), "the rejected construction connection must be closed") },
        )
    }

    // ── Self-connection guard (#1488) — a peer dialing its own endpoint ───────

    /**
     * A self-dial — a connection whose remote resolves to the mesh's own [PeerId] — is dropped at
     * the admission choke point: it never joins `peers`/`links` (which would echo the node's own
     * broadcasts), never lands in the roster, and never surfaces as a [LinkRejectedException] (it is
     * not a policy decision). The seam stays [SeamState.Woven] and keeps serving legitimate peers.
     */
    @Test
    fun addLinkDropsAConnectionResolvingToSelf() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val good = PeerId("good")
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0))

        // A legitimate peer first, so we can prove the self-dial leaves it undisturbed.
        val (goodHubEnd, goodFarEnd) = connectionPair()
        val goodHandshake = launch { handshakeRemote(goodFarEnd, good, nonce = byteArrayOf(2)) }
        mesh.addLink(goodHubEnd)
        goodHandshake.join()
        assertEquals(setOf(hub, good), mesh.peers.value)

        // The self-dial: the far end claims the hub's OWN id. addLink returns without throwing.
        val (selfHubEnd, selfFarEnd) = connectionPair()
        val selfHandshake = launch { handshakeRemote(selfFarEnd, hub, nonce = byteArrayOf(1)) }
        mesh.addLink(selfHubEnd)
        selfHandshake.join()
        val selfRemainingFrames = selfFarEnd.incoming.toList()

        // The good link still works after the self-dial.
        val payload = byteArrayOf(4, 2)
        val received = async { goodFarEnd.incoming.first() }
        mesh.sendTo(good, payload)
        val goodGotFrame = received.await()

        assertAll(
            { assertEquals(setOf(hub, good), mesh.peers.value, "self must not join the roster") },
            { assertFalse(hub in mesh.attestedPrincipals.value, "self must not land in the roster") },
            { assertTrue(selfRemainingFrames.isEmpty(), "the self-connection must be closed") },
            { assertContentEquals(payload, goodGotFrame, "the seam keeps serving after a self-dial") },
            { assertEquals(SeamState.Woven, mesh.state.value, "a self-dial must not tear the seam") },
        )
    }

    /**
     * A peer-mesh self-dial must not skew the drain latch. Were the self-link admitted, losing it
     * would read as a "was non-empty, now empty" transition and spuriously latch [SeamState.Torn].
     * Dropped at admission, the self-dial never enters the link set: closing it drains nothing and
     * the still-empty peer-mesh stays [SeamState.Woven], free to grow via a real [Mesh.addLink].
     */
    @Test
    fun peerMeshSelfDialDoesNotSpuriouslyTearViaDrain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val self = PeerId("self")
        val mesh = peerMesh(self, emptyList(), dispatcher, Random(0))

        val (selfHubEnd, selfFarEnd) = connectionPair()
        val selfHandshake = launch { handshakeRemote(selfFarEnd, self, nonce = byteArrayOf(1)) }
        mesh.addLink(selfHubEnd)
        selfHandshake.join()

        // Close the self-dial's far end and let any read loop drain — the drain path is exactly what
        // would latch Torn if the self-link had ever been registered. (MeshSeam has no re-arming
        // timers, so advancing to idle terminates.)
        selfFarEnd.close()
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertEquals(setOf(self), mesh.peers.value, "self-dial adds no peer") },
            { assertEquals(SeamState.Woven, mesh.state.value, "self-dial must not drain-latch Torn") },
        )
    }

    // ── P3: the roster — atomic with the link set ────────────────────────────

    @Test
    fun rosterTracksAttestedLinksAndCleansUpOnDrop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val alice = PeerId("alice")
        val bob = PeerId("bob")

        // Construction-time attested link.
        val (aliceHubEnd, aliceFarEnd) = connectionPair()
        val aliceHandshake = launch { handshakeRemote(aliceFarEnd, alice, nonce = byteArrayOf(1)) }
        val mesh = hubMesh(
            hub,
            listOf(aliceHubEnd.withPrincipal(Principal("user-alice"))),
            dispatcher,
            Random(0),
        )
        aliceHandshake.join()
        assertEquals(mapOf(alice to Principal("user-alice")), mesh.attestedPrincipals.value)

        // An unattested link under AcceptAll joins peers but stays absent from the roster.
        val (bobHubEnd, bobFarEnd) = connectionPair()
        val bobHandshake = launch { handshakeRemote(bobFarEnd, bob, nonce = byteArrayOf(2)) }
        mesh.addLink(bobHubEnd)
        bobHandshake.join()
        assertAll(
            { assertEquals(setOf(hub, alice, bob), mesh.peers.value) },
            { assertEquals(mapOf(alice to Principal("user-alice")), mesh.attestedPrincipals.value) },
        )

        // Dropping alice's link removes her roster entry along with her peer entry.
        aliceFarEnd.close()
        mesh.attestedPrincipals.first { alice !in it }
        assertAll(
            { assertFalse(alice in mesh.peers.value, "dropped peer must leave the peer set") },
            { assertTrue(mesh.attestedPrincipals.value.isEmpty(), "dropped peer must leave the roster") },
        )
    }

    @Test
    fun rosterEmptiesOnSeamClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hub = PeerId("hub")
        val alice = PeerId("alice")
        val (aliceHubEnd, aliceFarEnd) = connectionPair()
        val aliceHandshake = launch { handshakeRemote(aliceFarEnd, alice, nonce = byteArrayOf(1)) }
        val mesh = hubMesh(hub, emptyList(), dispatcher, Random(0))
        val add = launch { mesh.addLink(aliceHubEnd.withPrincipal(Principal("user-alice"))) }
        aliceHandshake.join()
        add.join()
        assertEquals(mapOf(alice to Principal("user-alice")), mesh.attestedPrincipals.value)

        mesh.close()
        assertTrue(mesh.attestedPrincipals.value.isEmpty(), "teardown must clear the roster with the links")
    }

    /** Drive the far end of a [connectionPair] through the mesh handshake for [remoteId]. */
    private suspend fun handshakeRemote(theirs: Connection, remoteId: PeerId, nonce: ByteArray) {
        theirs.incoming.first() // the mesh's own MeshHello preamble
        theirs.send(MeshHello.encode(remoteId, nonce))
    }
}
