package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

/**
 * Pins the premise [GossipSeamConformanceTest.joinerRosterOrigin] rests on (#2605).
 *
 * That harness declares [JoinerRosterOrigin.TheJoinPath], whose contract is *"the joiner's roster
 * started at `{ selfId }` and only a completed join grew it"*. That is a claim about the **base**
 * the harness weaves over, and a declaration is not machine-checkable — so the two facts it stands
 * on are asserted here instead of merely asserted about:
 *
 *  - the base the harness uses now ([peerMesh]) opens at exactly `{ selfId }`, and the overlay over
 *    it publishes that roster unchanged;
 *  - the base it used before (`InMemoryLoom`) does not, and — this is the vacuity, not a detail —
 *    the entry that satisfied the joiner arm was put there by the **host's** weave, before the
 *    joiner existed at all. No behaviour of the joiner's own join path could have removed it.
 *
 * Neither test drives [GossipSeamConformanceTest]; they exist so that swapping the base back is met
 * with a red that says why, rather than with a green suite and an unfalsifiable declaration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipJoinerRosterPremiseTest {

    /**
     * The converted harness's premise: a [GossipSeam] over a [peerMesh] that has completed no join
     * advertises **no remote at all** — so the joiner arm of the conformance suite is a real
     * obligation there, satisfied only by a handshake the joiner ran itself.
     */
    @Test
    fun overlayOverAnUnjoinedPeerMeshHoldsOnlyItself(): Unit = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val self = PeerId("joiner")
        val base = peerMesh(
            selfId = self,
            connections = emptyList(),
            dispatcher = requireNotNull(currentCoroutineContext()[ContinuationInterceptor]),
        )
        val overlay = GossipSeam(
            base = base,
            random = Random(1),
            clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) },
            jitter = ZERO..ZERO,
        )
        overlay.start(backgroundScope)

        assertEquals(
            setOf(self),
            overlay.peers.value,
            "a GossipSeam over a peerMesh that joined nobody must advertise no remote — the overlay " +
                "delegates peers to its base and must not manufacture a peer the base never had",
        )
        overlay.close()
    }

    /**
     * The base the harness used until #2605, and why the joiner arm could not fail over it: one
     * `InMemoryLoom` owns a single roster every seam it weaves reads, so the **host's** registration
     * — which happens before the joiner is woven — is already a remote in the joiner's roster.
     *
     * The assertion is deliberately ordered to show that: the registry names the host while the
     * joiner does not yet exist, and the joiner's own weave adds only its own id.
     */
    @Test
    fun aSharedInMemoryRosterNamesTheHostBeforeTheJoinerExists(): Unit = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val host = loom.host(Pattern("host"))

        assertEquals(
            setOf(host.selfId),
            loom.peers.value,
            "the shared registry names the host before any joiner is woven",
        )

        val joiner = loom.join(InMemoryTag("joiner"))
        assertEquals(
            setOf(host.selfId, joiner.selfId),
            joiner.peers.value,
            "the joiner's roster is that same registry: the only entry its own weave contributed is " +
                "its OWN id, and the host was already in it — which is why the joiner arm of the " +
                "conformance suite could not fail over this base (#2605)",
        )

        host.close()
        joiner.close()
    }
}
