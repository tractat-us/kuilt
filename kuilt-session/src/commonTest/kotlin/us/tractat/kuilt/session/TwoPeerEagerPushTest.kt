@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #2104: a write made *before* a channel view's collector has been dispatched must still reach the
 * other peer by eager delta push, not a minute later via the anti-entropy backstop.
 *
 * ## What this pins, and why the suites around it could not
 *
 * A `Quilter` subscribes to [Room.channel]'s `incoming` from a coroutine it `launchIn`s, so on a
 * non-eager dispatcher the subscription lands a dispatch turn *after* construction returns. A
 * consumer that constructs its replicator and writes in the same block — the natural way to write
 * it, and what the reporting consumer does — therefore emits its first delta into that window.
 *
 * The room's inbound stream is `replay = 0` by contract, so the question is only ever **whether the
 * subscription is registered by the time the collector coroutine first runs**. Collecting a
 * `SharedFlow` registers the slot synchronously on first collect; collecting `merge(a, b)` does
 * not — `merge` subscribes from child coroutines it launches, one more dispatch turn out. #2026
 * changed the channel view's upstream from the former to the latter, and every frame in the widened
 * window was dropped: convergence fell back to anti-entropy, ~30 s of virtual time.
 *
 * `StarQuilterConvergenceTest` and `RoomChannelReplicatorTest` both cover convergence over a
 * `Room.channel` and are structurally blind to this: the first calls `runCurrent()` between
 * constructing its replicators and writing, which flushes the subscription, and the second runs
 * under `UnconfinedTestDispatcher`, where every launch is eager. The uncovered configuration is a
 * write on a `StandardTestDispatcher` with nothing pumped in between.
 *
 * So this test deliberately does **not** pump the scheduler between construction and the write.
 * Adding a `runCurrent()` there makes it pass against the bug — that is the whole point of it.
 */
class TwoPeerEagerPushTest {

    /**
     * Comfortably inside [QuilterConfig.antiEntropyInterval] (one minute), so the backstop cannot
     * satisfy this. *Virtual* time — unlike [TEST_WEDGE_BACKSTOP] this one is a real bound.
     */
    private val eagerBudget = 1.seconds
    private val round = 50.milliseconds

    private fun replicator(room: Room, scope: CoroutineScope): Quilter<GSet<String>> = Quilter(
        replica = ReplicaId(room.selfId.value),
        seam = room.channel("two-peer-set"),
        initial = GSet.empty(),
        messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
        scope = scope,
        config = QuilterConfig(expectVirtualTime = true),
    )

    @Test
    fun `a write issued before the channel collector is dispatched still converges eagerly`() =
        runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
            val loom = InMemoryLoom()
            val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            val hostRoom = SeamRoomFactory(loom, backgroundScope, clock).host(Pattern("agree"))
            val joinerRoom = SeamRoomFactory(loom, backgroundScope, clock).join(InMemoryTag("agree"))
            testScheduler.runCurrent()

            val onHost = replicator(hostRoom, backgroundScope)
            val onJoiner = replicator(joinerRoom, backgroundScope)
            // NOTHING pumped here — see the class KDoc. This is the window under test.
            onHost.apply(onHost.state.value.add("from-host"))
            onJoiner.apply(onJoiner.state.value.add("from-joiner"))

            // Bounded advancement — never advanceUntilIdle(); the anti-entropy timer re-arms forever.
            repeat((eagerBudget / round).toInt()) {
                testScheduler.advanceTimeBy(round)
                testScheduler.runCurrent()
            }

            assertAll(
                {
                    assertTrue(
                        "from-joiner" in onHost.state.value.elements,
                        "the joiner's write must reach the host by eager delta push, well inside the " +
                            "anti-entropy interval (#2104). Host holds ${onHost.state.value.elements}",
                    )
                },
                {
                    assertTrue(
                        "from-host" in onJoiner.state.value.elements,
                        "and symmetrically. Joiner holds ${onJoiner.state.value.elements}",
                    )
                },
                // Positive control on the harness: a run where neither replica saw its OWN write
                // would be measuring nothing at all.
                { assertTrue("from-host" in onHost.state.value.elements) },
                { assertTrue("from-joiner" in onJoiner.state.value.elements) },
            )
        }
}
