@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.FaultySeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Host-authoritative **presence** fan-out on a star/host-relayed topology (#1557).
 *
 * ### How the star is modelled
 *
 * The defining property of a star is that a joiner has **no liveness edge against another
 * joiner** — only the host watches every member. These tests reproduce that with two
 * [HeartbeatConfig]s over one shared [InMemoryLoom]: the **host** room runs [hostConfig]
 * (sub-second detection), while the **joiner** rooms run [joinerConfig], whose timeout and
 * reconnect window are an order of magnitude longer than the whole test's virtual-time
 * budget. A joiner therefore cannot reach any conclusion about another joiner on its own
 * within the window under test, exactly as it cannot in a real star. Anything a joiner
 * observes about a *peer* joiner in these tests came from the host's authoritative fan-out.
 *
 * The dropped link is injected with [FaultySeam.partition] (frames silently dropped in both
 * directions) rather than a seam close, so the underlying peer set is untouched — a silent
 * partition, not a transport tear, which is the case the reconnect window exists for.
 */
class StarTopologyPresenceFanoutTest {

    /** Host-side detection: fast, so the whole partition→expiry arc fits in ~1.5 s of virtual time. */
    private val hostConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    /**
     * Joiner-side detection: deliberately far longer than any test's advancement budget, so a
     * joiner's own detector can never be the thing that produces an observation about a peer
     * joiner. This is what makes the star's "no joiner↔joiner heartbeat" property explicit.
     */
    private val joinerConfig = HeartbeatConfig(
        interval = 10.seconds,
        timeout = 60.seconds,
        reconnectWindow = 60.seconds,
    )

    /** Enough virtual time for the host to detect the partition and let the window expire. */
    private val expiryBudget = 2.seconds

    /**
     * A member whose reconnect window expires must be evicted from **every** member's roster,
     * not just the host's.
     *
     * `propagateFarewell` had exactly one call site — the `Goodbye` handler — so window expiry
     * propagated nothing. The documented fallback ("a lost Farewell degrades to that member's
     * heartbeat-window eviction") only holds on a mesh where every member heartbeats every
     * other; in a star a joiner has no heartbeat against another joiner, so the expired peer
     * sat in its roster forever.
     */
    @Test
    fun `a non-host member evicts a peer whose reconnect window expired`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val star = star()
            val observed = mutableListOf<MembershipEvent.Left>()
            backgroundScope.launch {
                star.bystander.events
                    .filterIsInstance<MembershipEvent.Left>()
                    .collect { if (it.peerId == star.droppedId) observed += it }
            }
            testScheduler.runCurrent()

            star.droppedLink.partition()
            testScheduler.advanceTimeBy(expiryBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        emptyList(),
                        star.host.roster.value.filter { it.id == star.droppedId },
                        "sanity: the host's own reconnect window must have expired the seat",
                    )
                },
                {
                    assertEquals(
                        1,
                        observed.size,
                        "a peer whose reconnect window expired must be evicted from a non-host " +
                            "member's roster too — observed $observed",
                    )
                },
                {
                    assertEquals(
                        LeaveReason.PartitionExpired,
                        observed.firstOrNull()?.reason,
                        "an expired seat is not a clean leave — the propagated eviction must " +
                            "keep the PartitionExpired reason",
                    )
                },
            )
            assertEquals(
                emptyList(),
                star.bystander.roster.value.filter { it.id == star.droppedId },
                "the expired peer must be gone from the bystander's roster",
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    /**
     * A three-peer star: a host, a [droppedLink] joiner whose link is faulted mid-test, and a
     * [bystander] joiner that must learn about it from the host alone.
     */
    private class Star(
        val host: Room,
        val droppedLink: FaultySeam,
        val droppedId: us.tractat.kuilt.core.PeerId,
        val bystander: Room,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.star(): Star {
        val loom = InMemoryLoom()
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val hostFactory = SeamRoomFactory(loom, backgroundScope, clock, hostConfig)
        val joinerFactory = SeamRoomFactory(loom, backgroundScope, clock, joinerConfig)

        val hostRoom = hostFactory.host(Pattern("Host"))
        val droppedLink = FaultySeam(loom.join(InMemoryTag("Dropped")), backgroundScope)
        val droppedRoom = joinerFactory.adopt(droppedLink, SessionRole.Joiner)
        val bystanderRoom = joinerFactory.join(InMemoryTag("Bystander"))

        hostRoom.roster.first { it.size == 2 }
        droppedRoom.roster.first { it.size == 2 }
        bystanderRoom.roster.first { it.size == 2 }

        return Star(hostRoom, droppedLink, droppedRoom.selfId, bystanderRoom)
    }
}
