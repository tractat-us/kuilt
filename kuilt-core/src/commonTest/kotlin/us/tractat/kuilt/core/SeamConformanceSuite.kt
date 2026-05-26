package us.tractat.kuilt.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for [Loom] implementations.
 *
 * Subclass and implement [newLoom] to bind any [Loom] under test.
 * Every [Test] in this class encodes a required invariant of the seam
 * contract — a conforming implementation must pass all of them.
 *
 * @see InMemoryLoomConformanceTest
 */
abstract class SeamConformanceSuite {

    /** Provide a fresh [Loom] for each test. */
    abstract fun newLoom(): Loom

    // ── (1) open yields a usable Seam with a non-empty selfId ────────────────

    @Test
    fun openYieldsUsableSeamWithNonEmptySelfId() =
        runTest {
            val loom = newLoom()
            val seam = loom.open(Pattern("host"))

            assertFalse(seam.selfId.value.isEmpty(), "selfId must be non-empty")
        }

    // ── (2) broadcast from host delivers to a joined peer ───────────────────

    @Test
    fun broadcastFromHostDeliversToJoinedPeer() =
        runTest {
            val loom = newLoom()
            val host = loom.open(Pattern("host"))
            val joiner = loom.join(InMemoryTag("joiner"))

            val received = async { joiner.incoming.take(1).toList() }

            val payload = byteArrayOf(10, 20, 30)
            host.broadcast(payload)

            val frames = received.await()
            assertEquals(1, frames.size)
            assertTrue(frames[0].payload.contentEquals(payload), "payload must match")
            assertEquals(host.selfId, frames[0].sender)
        }

    // ── (3) incoming preserves send order to a single collector ─────────────

    @Test
    fun incomingPreservesSendOrderToSingleCollector() =
        runTest {
            val loom = newLoom()
            val host = loom.open(Pattern("host"))
            val joiner = loom.join(InMemoryTag("joiner"))

            val received = async { joiner.incoming.take(5).toList() }

            repeat(5) { i -> host.broadcast(byteArrayOf(i.toByte())) }

            val frames = received.await()
            assertEquals(5, frames.size)
            assertTrue(frames[0].payload.contentEquals(byteArrayOf(0)), "frame 0 payload")
            assertTrue(frames[1].payload.contentEquals(byteArrayOf(1)), "frame 1 payload")
            assertTrue(frames[2].payload.contentEquals(byteArrayOf(2)), "frame 2 payload")
            assertTrue(frames[3].payload.contentEquals(byteArrayOf(3)), "frame 3 payload")
            assertTrue(frames[4].payload.contentEquals(byteArrayOf(4)), "frame 4 payload")
        }

    // ── (4) peers reports selfId and ≥2 after a join ────────────────────────

    @Test
    fun peersReportsSelfIdAndAtLeastTwoAfterJoin() =
        runTest {
            val loom = newLoom()
            val host = loom.open(Pattern("host"))
            val joiner = loom.join(InMemoryTag("joiner"))

            assertTrue(host.selfId in host.peers.value, "host peers must include its own selfId")
            assertTrue(joiner.selfId in joiner.peers.value, "joiner peers must include its own selfId")
            assertTrue(host.peers.value.size >= 2, "peer set must have ≥2 after join")
        }

    // ── (5) close is idempotent — calling twice must not throw ──────────────

    @Test
    fun closeIsIdempotent() =
        runTest {
            val loom = newLoom()
            val seam = loom.open(Pattern("host"))

            seam.close()
            seam.close() // must not throw
        }

    // ── (6) availability returns Available or Unavailable ───────────────────

    @Test
    fun availabilityReturnsAKnownVariant() {
        val loom = newLoom()
        val availability = loom.availability()

        assertTrue(
            availability is FabricAvailability.Available || availability is FabricAvailability.Unavailable,
            "availability() must return Available or Unavailable, got $availability",
        )
    }
}
