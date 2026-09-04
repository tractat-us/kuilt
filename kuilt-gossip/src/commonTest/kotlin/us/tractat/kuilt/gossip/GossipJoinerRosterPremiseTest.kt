package us.tractat.kuilt.gossip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.JoinerRosterOrigin
import us.tractat.kuilt.conformance.SeamConformanceSuite
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.fabric.peerMesh
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
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
 * The third test is the one that actually **binds the declaration to the harness**, and it is here
 * because the first two do not: they construct their own subjects and never touch
 * [gossipLoomPair], so reverting the factory to an `InMemoryLoom` base while leaving
 * [JoinerRosterOrigin.TheJoinPath] declared would leave both of them green. So would the
 * conformance suite, and so would
 * [SeamConformanceSuite.joinerRosterOriginIsDeclaredAndHonest], which only asserts the string is
 * non-blank. That is exactly the mis-declaration [JoinerRosterOrigin]'s own KDoc warns buys back
 * the silence the type exists to remove.
 *
 * [theHarnessJoinerCannotWeaveWithoutAHostToHandshakeWith] closes it by driving [gossipLoomPair]
 * itself: over `peerMesh` the joiner cannot complete a weave with no host to handshake with, and
 * over any shared-registry base it can. **Honest limit, unchanged:** who owns a roster is not
 * observable from outside a [Seam], so this pins the harness's *base*, not the declaration's
 * wording — a swap to a different start-empty base would pass, and should.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GossipJoinerRosterPremiseTest {

    /**
     * The converted harness's premise: a [GossipSeam] over a [peerMesh] that has completed no join
     * advertises **no remote at all** — so the joiner arm of the conformance suite is a real
     * obligation there, satisfied only by a handshake the joiner ran itself.
     */
    @Test
    fun overlayOverAnUnjoinedPeerMeshHoldsOnlyItself(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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
    fun aSharedInMemoryRosterNamesTheHostBeforeTheJoinerExists(): TestResult = runTest(timeout = TEST_WEDGE_BACKSTOP) {
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

    /**
     * The binding test: drive [gossipLoomPair] — the conformance harness's own factory — and show
     * that its joiner **cannot complete a weave** unless a host is there to handshake with it.
     *
     * That is the discriminator the first two tests lack. Over the `peerMesh` base the joiner's
     * `weave` suspends inside the link handshake waiting for a first frame that never comes, so the
     * bound expires and this returns `null`. Over the shared-registry base this harness swapped away
     * from, `weave` would return immediately with the host already in its roster, and the assertion
     * would red — which is what makes a silent revert of the factory impossible while
     * [GossipSeamConformanceTest] still declares [JoinerRosterOrigin.TheJoinPath].
     *
     * The second arm is a **control, and it is required**: without it the first arm passes just as
     * well if [gossipLoomPair] were broken outright, or if `join` never returned under any
     * conditions. Same factory, host weaving concurrently — the joiner returns and its roster names
     * the host, so the `null` above is attributable to the absent host and to nothing else.
     *
     * The bound is virtual (`runTest`'s scheduler), so the minute costs no wall-clock; `runTest`'s
     * own ceiling stays the shared wedge backstop.
     */
    @Test
    fun theHarnessJoinerCannotWeaveWithoutAHostToHandshakeWith(): TestResult =
        runTest(timeout = TEST_WEDGE_BACKSTOP) {
            val (_, lonelyJoiner) = gossipLoomPair(testScope = this)
            assertNull(
                withTimeoutOrNull(1.minutes) { lonelyJoiner.join(InMemoryTag("joiner")) },
                "the harness's joiner must not be able to weave while the host never does — it has " +
                    "nobody to run peerMesh's link handshake with, so it cannot learn a peer. Over " +
                    "the InMemoryLoom base this harness swapped away from (#2605) join() returns at " +
                    "once with the host already in its roster, which is the vacuity TheJoinPath " +
                    "would then be mis-declaring",
            )

            coroutineScope {
                val (hostLoom, joinerLoom) = gossipLoomPair(testScope = this@runTest)
                val hostSeam = async { hostLoom.host(Pattern("host")) }
                val joinerSeam = async { joinerLoom.join(InMemoryTag("joiner")) }
                val host: Seam = hostSeam.await()
                val joiner: Seam = joinerSeam.await()
                assertEquals(
                    setOf(joiner.selfId, host.selfId),
                    joiner.peers.value,
                    "control: the SAME factory, with a host weaving concurrently, does complete the " +
                        "joiner's weave and does grow its roster to name the host — so the null " +
                        "above is the absent host, not a broken factory or a join that never returns",
                )
                host.close()
                joiner.close()
            }
        }
}
