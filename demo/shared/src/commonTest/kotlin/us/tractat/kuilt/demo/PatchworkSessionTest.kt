package us.tractat.kuilt.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Convergence tests for [PatchworkSession] over an [InMemoryLoom] — the
 * behaviour the whole Patchwork demo showcases, asserted under virtual time.
 *
 * Ceremony per docs/testing-coroutine-determinism.md: `StandardTestDispatcher`,
 * bounded `delay` settles (never `advanceUntilIdle` — the replicator re-arms
 * timers forever), sessions on `backgroundScope`, injected deterministic clock,
 * `expectVirtualTime = true` to acknowledge the Quilter test-dispatcher guard.
 */
class PatchworkSessionTest {

    private val red = Colour("#e94f37")
    private val green = Colour("#57a773")
    private val blue = Colour("#4062bb")
    private val gold = Colour("#f2c14e")

    /** Deterministic wall clock: each session's stitches start at [now]. */
    private class FakeClock(var now: Long) : StitchClock {
        override fun nowMillis(): Long = now
    }

    private fun TestScope.session(loom: Loom, name: String, clock: StitchClock) = PatchworkSession(
        loom = loom,
        stitcher = ReplicaId(name),
        scope = backgroundScope,
        clock = clock,
        quilterConfig = QuilterConfig(expectVirtualTime = true),
    )

    @Test
    fun threePeersConvergeToOneQuilt() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val alice = session(loom, "alice", FakeClock(1_000))
        val bob = session(loom, "bob", FakeClock(1_000))
        val carol = session(loom, "carol", FakeClock(1_000))

        alice.host(Pattern("patchwork"))
        bob.join(InMemoryTag("bob"))
        carol.join(InMemoryTag("carol"))
        delay(1) // let the replicators' collectors subscribe

        alice.stitch(Cell(0, 0), red)
        bob.stitch(Cell(1, 0), green)
        carol.stitch(Cell(2, 0), blue)
        delay(10) // deliver the delta broadcasts

        val expected = mapOf(Cell(0, 0) to red, Cell(1, 0) to green, Cell(2, 0) to blue)
        assertAll(
            { assertEquals(expected, alice.quilt.value, "alice") },
            { assertEquals(expected, bob.quilt.value, "bob") },
            { assertEquals(expected, carol.quilt.value, "carol") },
        )
    }

    @Test
    fun lateJoinerReceivesTheWholeQuilt() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val alice = session(loom, "alice", FakeClock(1_000))
        alice.host(Pattern("patchwork"))
        delay(1)
        alice.stitch(Cell(0, 0), red)
        alice.stitch(Cell(1, 1), green)
        delay(10)

        val bob = session(loom, "bob", FakeClock(2_000))
        bob.join(InMemoryTag("bob"))
        delay(10) // full-state exchange on first contact

        assertEquals(mapOf(Cell(0, 0) to red, Cell(1, 1) to green), bob.quilt.value)
    }

    /**
     * The headline: convergence under partition. Bob tunnels offline, keeps
     * stitching, and his patches merge into every peer's canvas on reconnect —
     * and the patches he missed merge into his.
     */
    @Test
    fun offlineStitchesMergeOnReconnect() = runTest(StandardTestDispatcher(), timeout = TEST_WEDGE_BACKSTOP) {
        val loom = InMemoryLoom()
        val alice = session(loom, "alice", FakeClock(1_000))
        val bob = session(loom, "bob", FakeClock(1_000))

        alice.host(Pattern("patchwork"))
        bob.join(InMemoryTag("bob"))
        delay(1)

        alice.stitch(Cell(0, 0), red)
        delay(10)
        assertEquals(red, bob.quilt.value[Cell(0, 0)], "pre-partition stitch reaches bob")

        // Bob enters the tunnel.
        bob.disconnect()
        assertFalse(bob.connected.value)

        // Both sides keep stitching across the partition.
        bob.stitch(Cell(2, 2), blue)
        bob.stitch(Cell(3, 3), gold)
        alice.stitch(Cell(0, 1), green)
        delay(10)

        // The partition is real: neither side sees the other's new patches.
        assertAll(
            { assertEquals(null, alice.quilt.value[Cell(2, 2)], "alice must not see bob's offline patch") },
            { assertEquals(blue, bob.quilt.value[Cell(2, 2)], "bob sees his own offline patch") },
            { assertEquals(null, bob.quilt.value[Cell(0, 1)], "bob must not see alice's patch while offline") },
        )

        // Bob leaves the tunnel — his patches merge in, nothing lost, nothing doubled.
        bob.join(InMemoryTag("bob-again"))
        delay(10)

        val expected = mapOf(
            Cell(0, 0) to red,
            Cell(0, 1) to green,
            Cell(2, 2) to blue,
            Cell(3, 3) to gold,
        )
        assertAll(
            { assertEquals(expected, alice.quilt.value, "alice") },
            { assertEquals(expected, bob.quilt.value, "bob") },
            { assertTrue(bob.connected.value) },
        )
    }

    /**
     * Concurrent stitches to the *same* cell across a partition resolve
     * without conflict: per-cell last-writer-wins, identical on every peer.
     */
    @Test
    fun concurrentStitchesToOneCellResolveIdentically() = runTest(
        StandardTestDispatcher(),
        timeout = TEST_WEDGE_BACKSTOP,
    ) {
        val loom = InMemoryLoom()
        val aliceClock = FakeClock(1_000)
        val bobClock = FakeClock(2_000) // bob's clock is ahead — his write wins LWW
        val alice = session(loom, "alice", aliceClock)
        val bob = session(loom, "bob", bobClock)

        alice.host(Pattern("patchwork"))
        bob.join(InMemoryTag("bob"))
        delay(1)

        bob.disconnect()
        alice.stitch(Cell(5, 5), red)
        bob.stitch(Cell(5, 5), blue)
        delay(10)

        bob.join(InMemoryTag("bob-again"))
        delay(10)

        assertAll(
            { assertEquals(blue, alice.quilt.value[Cell(5, 5)], "alice converges to the later write") },
            { assertEquals(blue, bob.quilt.value[Cell(5, 5)], "bob converges to the later write") },
            { assertEquals(alice.quilt.value, bob.quilt.value, "identical canvases") },
        )
    }
}
