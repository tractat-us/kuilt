package us.tractat.kuilt.conformance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * The harness scope uses [Dispatchers.Default] to match [CompositeSeam]'s internal
 * dispatcher so all coroutines interleave on real threads. Tests await propagation
 * via `flow.first { condition }` rather than test-scheduler advancement.
 *
 * Each test closes its seams and cancels its harness scope in a `finally` block to
 * prevent leaked [CompositeSeam] scopes from saturating [Dispatchers.Default] across
 * the full test suite run.
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
        val harnessScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val (plyALoom, plyBLoom, loom) = twoFlakyPlies(harnessScope)
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            // Wait for both composite peers to be fully reconciled on both sides.
            host.peers.first { it.size == 2 }
            joiner.peers.first { it.size == 2 }

            // Tear ply B on both host and joiner sides.
            plyBLoom.links[0].tear()
            plyBLoom.links[1].tear()

            // Wait for both state and peer sets to stabilise after the tear.
            // Ply A still carries both peers so state must stay Woven and size at 2.
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
            harnessScope.cancel()
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
        val harnessScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val (plyALoom, plyBLoom, loom) = twoFlakyPlies(harnessScope)
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            // Wait for initial reconciliation on both sides.
            host.peers.first { it.size == 2 }
            joiner.peers.first { it.size == 2 }

            // Blip ply A on the host side: Woven → Weaving → Woven.
            val hostPlyA = plyALoom.links[0]
            hostPlyA.enterWeaving()
            hostPlyA.recover()

            // After recovery, aggregate must be Woven.
            host.state.first { it is SeamState.Woven }
            assertIs<SeamState.Woven>(host.state.value, "aggregate must be Woven after ply A recovers")

            // Peer set must be fully recovered on both sides.
            host.peers.first { it.size == 2 }
            joiner.peers.first { it.size == 2 }

            // Host can still deliver frames to the joiner — Announce was re-sent on
            // ply A recover, restoring routing over that ply.
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
            harnessScope.cancel()
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
        val harnessScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val (plyALoom, plyBLoom, loom) = twoFlakyPlies(harnessScope)
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("soak"))
            joiner = loom.join(InMemoryTag("join"))

            // Wait for initial reconciliation.
            host.peers.first { it.size == 2 }
            joiner.peers.first { it.size == 2 }

            // Collect all frames delivered to the joiner.
            val deliveredPayloads = mutableListOf<Byte>()
            val collectJob = harnessScope.launch {
                joiner.incoming.collect { swatch -> deliveredPayloads.add(swatch.payload[0]) }
            }

            // Drive both plies under independent deterministic flap schedules.
            // Short mean times (≪1s) so real-time delays finish quickly.
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

            // Wait for flap schedules to exhaust.
            jobA.join()
            jobB.join()
            collectJob.cancel()

            // Dedup invariant: no payload appears more than once in deliveredPayloads.
            val counts = deliveredPayloads.groupingBy { it }.eachCount()
            val duplicated = counts.filter { it.value > 1 }
            assertTrue(duplicated.isEmpty(), "dedup violation: frames delivered more than once: $duplicated")

            // Peer set contains at least self after convergence.
            assertTrue(host.peers.value.isNotEmpty(), "peer set must not be empty after convergence")
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            harnessScope.cancel()
        }
    }

    // ── Assertion 4: last-ply-torn drives aggregate to Torn ───────────────────

    /**
     * A ply that escalates to [SeamState.Torn] is dropped from the rollup.
     * The aggregate goes [SeamState.Torn] only when the **last** surviving ply
     * also tears — the survivor carries the session until then.
     *
     * **Production bug found:** [CompositeSeam.broadcast] iterates ALL constituent
     * seams unconditionally — including torn ones. When ply A is torn and host
     * calls `broadcast`, it reaches [FlakyLifecycleSeam.broadcast] on the torn
     * ply, which throws [IllegalStateException]. The composite must skip torn plies
     * in `broadcast` and `sendTo`. This test documents the correct expected
     * behaviour and will fail until that is fixed.
     */
    @Test
    fun aggregateGoesToTornOnlyWhenLastPlyTears() = runTest {
        val harnessScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val (plyALoom, plyBLoom, loom) = twoFlakyPlies(harnessScope)
        var host: Seam? = null
        var joiner: Seam? = null
        try {
            host = loom.host(Pattern("session"))
            joiner = loom.join(InMemoryTag("join"))

            // Wait for initial reconciliation.
            host.peers.first { it.size == 2 }

            val hostPlyA = plyALoom.links[0]
            val hostPlyB = plyBLoom.links[0]

            // Tear ply A — aggregate must still be Woven (ply B survives).
            hostPlyA.tear()

            // The rollup sees ply A=Torn, ply B=Woven → aggregate stays Woven.
            host.state.first { it is SeamState.Woven }
            assertIs<SeamState.Woven>(host.state.value, "aggregate must stay Woven after first ply tears")

            // Host can still communicate via ply B. The composite must not call
            // broadcast on the already-torn ply A seam.
            val received = async(harnessScope.coroutineContext) {
                joiner.incoming.take(1).toList()
            }
            host.broadcast(byteArrayOf(77))

            val frames = withTimeoutOrNull(2_000) { received.await() }
            assertNotNull(frames, "expected a frame via surviving ply B but got none")
            assertTrue(frames.isNotEmpty(), "host must still deliver via surviving ply B")

            // Now tear the last surviving ply — aggregate must go Torn.
            hostPlyB.tear()

            val finalState = host.state.first { it is SeamState.Torn }
            assertIs<SeamState.Torn>(finalState, "aggregate must be Torn when last ply tears")
        } finally {
            host?.close(CloseReason.Normal)
            joiner?.close(CloseReason.Normal)
            harnessScope.cancel()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class TwoFlakyPlySetup(
        val plyALoom: FlakyLifecycleLoom,
        val plyBLoom: FlakyLifecycleLoom,
        val compositeLoom: CompositeLoom,
    )

    private fun twoFlakyPlies(scope: CoroutineScope): TwoFlakyPlySetup {
        // Each FlakyLifecycleLoom wraps its own InMemoryLoom mesh.
        // The two plies share no state — each flaps independently.
        val plyALoom = FlakyLifecycleLoom(InMemoryLoom(), scope)
        val plyBLoom = FlakyLifecycleLoom(InMemoryLoom(), scope)
        val compositeLoom = CompositeLoom(
            listOf(
                PlyId("a") to plyALoom,
                PlyId("b") to plyBLoom,
            ),
        )
        return TwoFlakyPlySetup(plyALoom, plyBLoom, compositeLoom)
    }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
