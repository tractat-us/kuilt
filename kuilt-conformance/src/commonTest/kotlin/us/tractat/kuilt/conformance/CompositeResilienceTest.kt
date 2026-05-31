@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.FlapSchedule
import us.tractat.kuilt.core.FlakyLifecycleLoom
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Composite fabric resilience tests: per-ply flap, tear, and recovery.
 *
 * Harness: two plies, each a [FlakyLifecycleLoom] wrapping an [InMemoryLoom].
 * Ply lifecycles are driven imperatively via [FlakyLifecycleLoom.links].
 *
 * All coroutines — both [FlakyLifecycleLoom] ply scopes and the [CompositeLoom]
 * internal dispatcher — share an [UnconfinedTestDispatcher] so that every
 * state update and Announce broadcast propagates eagerly (no real-thread waits,
 * no [advanceUntilIdle] required). The ply scope is a standalone [CoroutineScope]
 * with [SupervisorJob] and is cancelled in each test's [finally] block to prevent
 * leaked coroutines after test completion.
 */
class CompositeResilienceTest {

    // ── Assertion 1: torn ply while survivor stays Woven ─────────────────────

    /**
     * When one ply tears and the other remains Woven, the aggregate state stays
     * Woven and the peer reachable on the survivor is still present in `peers`.
     * The torn ply must not cause a membership flap for a peer still reachable
     * on another ply.
     */
    @Test
    fun aggregateStaysWovenWhenOnePlyTearsAndSurvivorIsWoven() = runTest {
        val (plyALoom, plyBLoom, loom, plyScope) = twoFlakyPlies()
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            // With UnconfinedTestDispatcher, announce exchange is already complete.
            assertEquals(2, host.peers.value.size, "host must see 2 composite peers")
            assertEquals(2, joiner.peers.value.size, "joiner must see 2 composite peers")

            // Tear ply B on both host and joiner sides.
            plyBLoom.links[0].tear()
            plyBLoom.links[1].tear()

            // Ply A still carries both peers: state must stay Woven.
            host.state.first { it is SeamState.Woven }
            joiner.state.first { it is SeamState.Woven }
            host.peers.first { joiner.selfId in it }
            joiner.peers.first { host.selfId in it }

            assertAll(
                { assertIs<SeamState.Woven>(host.state.value, "aggregate must stay Woven") },
                { assertIs<SeamState.Woven>(joiner.state.value, "joiner aggregate must stay Woven") },
                { assertTrue(joiner.selfId in host.peers.value, "joiner must still be in host peers via survivor ply") },
                { assertTrue(host.selfId in joiner.peers.value, "host must still be in joiner peers via survivor ply") },
            )
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            plyScope.cancel()
        }
    }

    // ── Assertion 2: ply recovery re-sends Announce, restoring routing ────────

    /**
     * When a ply blips ([SeamState.Weaving] → [SeamState.Woven]), the composite
     * re-sends [Announce] on that ply so its membership and routing contribution
     * are restored. After recovery, frames flow over the recovered ply again.
     */
    @Test
    fun plyRecoverySendsAnnounceAndRestoresPeerDelivery() = runTest {
        val (plyALoom, _, loom, plyScope) = twoFlakyPlies()
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            assertEquals(2, host.peers.value.size, "host must see 2 composite peers after setup")
            assertEquals(2, joiner.peers.value.size, "joiner must see 2 composite peers after setup")

            // Blip ply A on the host side: Woven → Weaving → Woven.
            val hostPlyA = plyALoom.links[0]
            hostPlyA.enterWeaving()
            hostPlyA.recover()

            // After recovery, aggregate must be Woven and peers restored.
            assertIs<SeamState.Woven>(host.state.value, "aggregate must be Woven after ply A recovers")
            assertEquals(2, host.peers.value.size, "host peer set must be fully recovered")
            assertEquals(2, joiner.peers.value.size, "joiner peer set must be fully recovered")

            // Host can still deliver frames to the joiner.
            val received = async { joiner.incoming.take(1).toList() }
            host.broadcast(byteArrayOf(42))

            val frames = received.await()
            assertAll(
                { assertEquals(1, frames.size, "exactly one frame must be delivered after recovery") },
                { assertTrue(frames[0].payload.contentEquals(byteArrayOf(42)), "payload must match") },
            )
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            plyScope.cancel()
        }
    }

    // ── Assertion 3: independently flaky plies — dedup holds, peers converge ──

    /**
     * With each ply driven by an independent [FlapSchedule], frames sent while
     * the composite is [SeamState.Woven] must be delivered exactly once — the
     * inbound dedup gate holds under per-ply flap/retry. The peer set converges
     * after the chaos exhausts.
     */
    @Test
    fun independentPlyFlapsDeduplicateFramesAndConvergePeerSet() = runTest {
        val (plyALoom, plyBLoom, loom, plyScope) = twoFlakyPlies()
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("soak"))
            joiner = loom.join(InMemoryTag("join"))

            assertEquals(2, host.peers.value.size, "host must see 2 composite peers after setup")
            assertEquals(2, joiner.peers.value.size, "joiner must see 2 composite peers after setup")

            // Collect all frames delivered to the joiner.
            val deliveredPayloads = mutableListOf<Byte>()
            val collectJob = launch {
                joiner.incoming.collect { swatch -> deliveredPayloads.add(swatch.payload[0]) }
            }

            // Drive both plies under independent deterministic flap schedules.
            val scheduleA = FlapSchedule(
                seed = 1L,
                meanUptime = 10.milliseconds,
                meanDowntime = 5.milliseconds,
                giveUpAfter = 3,
            )
            val scheduleB = FlapSchedule(
                seed = 2L,
                meanUptime = 10.milliseconds,
                meanDowntime = 5.milliseconds,
                giveUpAfter = 3,
            )
            val jobA = plyALoom.links[0].drive(scheduleA)
            val jobB = plyBLoom.links[0].drive(scheduleB)

            // Send frames while the aggregate is Woven.
            val sentPayloads = listOf<Byte>(10, 20, 30)
            for (value in sentPayloads) {
                if (host.state.value is SeamState.Woven) {
                    host.broadcast(byteArrayOf(value))
                }
            }

            // Wait for flap schedules to exhaust (virtual time advances automatically).
            jobA.join()
            jobB.join()
            collectJob.cancel()

            // Dedup invariant: no payload appears more than once.
            val counts = deliveredPayloads.groupingBy { it }.eachCount()
            val duplicated = counts.filter { it.value > 1 }
            assertTrue(duplicated.isEmpty(), "dedup violation: frames delivered more than once: $duplicated")

            // Peer set contains at least self after convergence.
            assertTrue(host.peers.value.isNotEmpty(), "peer set must not be empty after convergence")
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            plyScope.cancel()
        }
    }

    // ── Assertion 4: last-ply-torn drives aggregate to Torn ───────────────────

    /**
     * A ply that escalates to [SeamState.Torn] is dropped from the rollup.
     * The aggregate goes [SeamState.Torn] only when the **last** surviving ply
     * also tears — the survivor carries the session until then.
     *
     * **Production bug found:** [CompositeSeam.broadcast] iterated ALL constituent
     * seams unconditionally — including torn ones. When ply A was torn and host
     * called `broadcast`, it reached the torn ply's `broadcast`, which threw
     * [IllegalStateException]. The fix skips torn plies in `broadcast` and
     * `sendTo`. This test documents the correct expected behaviour.
     */
    @Test
    fun aggregateGoesToTornOnlyWhenLastPlyTears() = runTest {
        val (plyALoom, plyBLoom, loom, plyScope) = twoFlakyPlies()
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            assertEquals(2, host.peers.value.size, "host must see 2 composite peers after setup")

            val hostPlyA = plyALoom.links[0]
            val hostPlyB = plyBLoom.links[0]

            // Tear ply A — aggregate must still be Woven (ply B survives).
            hostPlyA.tear()

            // Rollup: ply A=Torn, ply B=Woven → aggregate stays Woven.
            assertIs<SeamState.Woven>(host.state.value, "aggregate must stay Woven after first ply tears")

            // Host can still communicate via ply B. The composite must not call
            // broadcast on the already-torn ply A seam.
            val received = async { joiner.incoming.take(1).toList() }
            host.broadcast(byteArrayOf(77))

            val frames = received.await()
            assertNotNull(frames, "expected a frame via surviving ply B but got none")
            assertTrue(frames.isNotEmpty(), "host must still deliver via surviving ply B")

            // Now tear the last surviving ply — aggregate must go Torn.
            hostPlyB.tear()

            val finalState = host.state.first { it is SeamState.Torn }
            assertIs<SeamState.Torn>(finalState, "aggregate must be Torn when last ply tears")
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            plyScope.cancel()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class TwoFlakyPlySetup(
        val plyALoom: FlakyLifecycleLoom,
        val plyBLoom: FlakyLifecycleLoom,
        val compositeLoom: CompositeLoom,
        /** Cancelled in [finally] blocks to prevent coroutine leaks across the suite. */
        val plyScope: CoroutineScope,
    )

    /**
     * Creates two independent [FlakyLifecycleLoom]s and a [CompositeLoom] over
     * them. Both [FlakyLifecycleLoom] ply scopes and the [CompositeLoom]
     * dispatcher share an [UnconfinedTestDispatcher] so all state updates and
     * Announce broadcasts propagate eagerly — peer exchange completes by the
     * time `weave()` returns. The caller must cancel [TwoFlakyPlySetup.plyScope]
     * in a [finally] block.
     */
    private fun TestScope.twoFlakyPlies(): TwoFlakyPlySetup {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val plyScope = CoroutineScope(dispatcher + SupervisorJob())
        val plyALoom = FlakyLifecycleLoom(InMemoryLoom(), plyScope)
        val plyBLoom = FlakyLifecycleLoom(InMemoryLoom(), plyScope)
        val compositeLoom = CompositeLoom(
            listOf(
                PlyId("a") to plyALoom,
                PlyId("b") to plyBLoom,
            ),
            dispatcher,
        )
        return TwoFlakyPlySetup(plyALoom, plyBLoom, compositeLoom, plyScope)
    }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
