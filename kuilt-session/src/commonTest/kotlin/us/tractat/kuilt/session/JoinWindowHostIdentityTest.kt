@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A joiner must identify its host from the **first** Welcome it accepts, not from the host's
 * self-introduction — which `admitPeer` sends *last*, after the roster-sync Welcome and one
 * bootstrap Welcome per pre-existing member (`SeamRoom.kt:1250-1270`).
 *
 * Across that K+1-send window the joiner holds co-members in its roster with `hostPeerId == null`.
 * A `Quilter` collecting `rosterPeers` fires `onPeersChanged` -> `sendFullStateTo(coJoiner)` into
 * `PeerNotConnected` — #1994's own symptom, transiently reintroduced while convergence is being
 * established — and it is the capture window for a forged host identity.
 */
class JoinWindowHostIdentityTest {

    /**
     * A generous wedge backstop, NOT an assertion. It is wall-clock over a virtual-time
     * trajectory, so tightening it measures the host machine, not this code (#1739, #1891).
     * Fast failure comes from the bounded `first { }` awaits below.
     */
    private val backstop = 30.seconds

    @Test
    fun `a joiner knows its host before it holds any co-member`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = star(coJoiners = 3)

            // Wait for the admit burst to finish, then read the samples the harness took DURING it.
            // The host enters a joiner's roster only via the host-intro Welcome — the last of the
            // burst's K+1 sends — so holding the host means the burst is done.
            star.lastJoiner.roster.first { members -> members.any { it.id == star.hostId } }

            // Vacuity guard, and the whole reason the subject is the LAST joiner.
            //
            // `Room.roster` excludes self, and the FIRST joiner is admitted while `existingMembers`
            // is empty — so it receives only the self-admission Welcome (early return, no roster
            // add) and then the host-intro, which sets `hostPeerId` and adds the host in the same
            // call. Its roster therefore never holds a co-member before `hostPeerId` is set: the
            // window is structurally unreachable for that subject.
            //
            // The last joiner is admitted with two pre-existing members, so `admitPeer` sends
            // self-admission, then a bootstrap Welcome per existing member, and only THEN the
            // host-intro. Its roster holds a non-host member across that gap — the window. If this
            // assertion ever fires, the test stopped observing the window and everything below it
            // is worthless.
            val firstCoMemberJoin = assertNotNull(
                star.joinSamples.firstOrNull { it.member != star.hostId },
                "the last joiner never admitted a co-member, so this test observed nothing — " +
                    "the window is unreachable for this subject. samples=${star.joinSamples}",
            )

            assertAll(
                {
                    assertNotNull(
                        firstCoMemberJoin.hostPeerId,
                        "hostPeerId was still null at the instant the roster took on " +
                            "${firstCoMemberJoin.member.value} — this is the window in which a " +
                            "Quilter's sendFullStateTo hits PeerNotConnected, and in which a " +
                            "forged Welcome can capture the host. samples=${star.joinSamples}",
                    )
                },
                {
                    // Positive control: the host it identified is the real one. Without this a
                    // change that set hostPeerId to some arbitrary non-null peer would pass above.
                    assertEquals(
                        star.hostId,
                        firstCoMemberJoin.hostPeerId,
                        "the identified host must be the actual host",
                    )
                },
            )
        }

    /**
     * The #1180 gate still holds afterwards: once identified, a Welcome from anyone else changes
     * nothing. Pinning both halves means neither a permissive nor a reject-everything
     * implementation passes.
     *
     * Runs on a **flat mesh**, not the star: a spoke has no route to a co-spoke — that is #1994
     * itself — so a forged spoke-to-spoke frame is undeliverable on the hub fabric and the test
     * would assert nothing there. A flat loom is also the exact topology #1180 was written for
     * ("a foreign host on a flat loom must not […] hijack hostPeerId", `SeamRoom.kt:1344-1349`).
     */
    @Test
    fun `a second sender cannot displace an identified host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val mesh = flatMesh(coJoiners = 3)
            // Deliberately NOT the window predicate of the test above: this test is the control,
            // and must pass both before and after the fix. The host enters a joiner's roster only
            // via the host-intro Welcome, which is also what sets `hostPeerId` under the old rule,
            // so holding the host is the one wait that means "identified" under either rule.
            mesh.lastJoiner.roster.first { members -> members.any { it.id == mesh.hostId } }
            val identified = assertNotNull(mesh.lastJoinerHostPeerId())

            // A co-joiner forges a host self-introduction naming itself.
            mesh.forgeWelcomeFromCoJoiner()
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        identified,
                        mesh.lastJoinerHostPeerId(),
                        "a co-joiner's forged Welcome must not move hostPeerId (#1180)",
                    )
                },
                {
                    assertEquals(
                        mesh.hostId,
                        identified,
                        "sanity: the identified host was the real one to begin with",
                    )
                },
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /** [hostPeerId] as it stood at the **instant** [member] entered the subject's roster. */
    private data class JoinSample(val member: PeerId, val hostPeerId: PeerId?) {
        override fun toString(): String = "${member.value}@host=${hostPeerId?.value}"
    }

    private class Fixture(
        val hostId: PeerId,
        /** The joiner admitted LAST — the only one whose admit burst has bootstrap sends. */
        val lastJoiner: Room,
        /** A co-joiner of [lastJoiner], used to forge frames at it. */
        private val coJoiner: Room,
        /** [lastJoiner]'s roster additions, each paired with the host identity of that moment. */
        val joinSamples: List<JoinSample>,
    ) {
        fun lastJoinerHostPeerId(): PeerId? = (lastJoiner as SeamRoom).hostPeer()

        /**
         * The co-joiner broadcasts a Welcome naming *itself* — the host-self-introduction shape,
         * which is precisely the shape that used to capture `hostPeerId`.
         */
        suspend fun forgeWelcomeFromCoJoiner() {
            coJoiner.broadcast(
                AdmitMessage.encode(
                    AdmitMessage.Welcome(
                        assignedPeerId = coJoiner.selfId.value,
                        displayName = "forged",
                        sessionId = "forged",
                    ),
                ),
            )
        }
    }

    /** A host plus [coJoiners] spokes over the real hub fabric — one edge per spoke, to the host. */
    private suspend fun TestScope.star(coJoiners: Int): Fixture {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))
        val hostRoom = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock).host(Pattern(ROOM))
        return admitSequentially(hostRoom, coJoiners) { index ->
            val loom = fabric.clientLoom(PeerId("joiner-$index"), Random(index.toLong() + 1L))
            // `adopt` (rather than `join`) mirrors LivenessRouteGateTest.star(): the spoke's own
            // client loom is its single edge, and the room is built over that seam.
            SeamRoomFactory(loom, backgroundScope, clock)
                .adopt(loom.join(InMemoryTag(ROOM)), SessionRole.Joiner)
        }
    }

    /** The same membership shape on a flat [InMemoryLoom], where every member can address every other. */
    private suspend fun TestScope.flatMesh(coJoiners: Int): Fixture {
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val factory = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock)
        val hostRoom = factory.host(Pattern(ROOM))
        return admitSequentially(hostRoom, coJoiners) { index -> factory.join(InMemoryTag("joiner-$index")) }
    }

    /**
     * Sequential admission is **load-bearing, not incidental**. Admitting concurrently lets the
     * interleaving decide whether `existingMembers` is populated when the subject is admitted, so
     * the window would be present on some runs and absent on others.
     *
     * The last joiner is deliberately **not** awaited: awaiting its admission runs `admitPeer`'s
     * whole burst to completion, which closes the very window this harness samples.
     */
    private suspend fun TestScope.admitSequentially(
        hostRoom: Room,
        coJoiners: Int,
        newJoiner: suspend (Int) -> Room,
    ): Fixture {
        require(coJoiners >= 2) {
            "the window only exists for a joiner admitted with pre-existing members to bootstrap"
        }
        val joiners = mutableListOf<Room>()
        val samples = mutableListOf<JoinSample>()
        repeat(coJoiners) { index ->
            val joiner = newJoiner(index)
            joiners += joiner
            if (index == coJoiners - 1) sampleJoins(joiner, samples) else hostRoom.roster.first { it.size == index + 1 }
        }
        return Fixture(
            hostId = hostRoom.selfId,
            lastJoiner = joiners.last(),
            coJoiner = joiners.first(),
            joinSamples = samples,
        )
    }

    /**
     * Records `hostPeerId` **at the instant** each member enters [room]'s roster.
     *
     * Deliberately [UnconfinedTestDispatcher] — the one place in this test where eager, inline
     * resumption is the point rather than a hazard. `addToRoster` emits `Joined` (via `tryEmit`)
     * from inside the room's own lock, so an unconfined collector runs there and reads the host
     * identity of that exact moment. A `StandardTestDispatcher` collector is instead *dispatched*,
     * and by the time it runs the joiner's incoming-collect loop has already drained the rest of
     * the admit burst — so it can only ever observe the post-burst state, and a test built on one
     * is green whether or not the window exists. (`roster` is worse still: a `StateFlow` conflates,
     * so the intermediate values are not merely late but gone.)
     *
     * Subscribed synchronously — an unconfined `launch` runs its body up to the first suspension
     * before returning — and installed the instant the room exists, before any Welcome can arrive.
     */
    private fun TestScope.sampleJoins(room: Room, into: MutableList<JoinSample>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            room.events.collect { event ->
                if (event is MembershipEvent.Joined) {
                    into += JoinSample(event.member.id, (room as SeamRoom).hostPeer())
                }
            }
        }
    }

    private companion object {
        const val ROOM = "table"
    }
}
