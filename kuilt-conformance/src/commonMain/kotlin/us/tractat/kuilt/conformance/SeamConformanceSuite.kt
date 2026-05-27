package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reusable contract test suite for [Loom] implementations.
 *
 * Subclass and implement [newLoomPair] to bind any fabric under test.
 * Every [Test] in this class encodes a required invariant of the seam
 * contract — a conforming implementation must pass all of them.
 *
 * Lives in `commonMain` of `:kuilt-conformance` (not a module's `commonTest`)
 * so every fabric adapter can subclass it from its own test source set —
 * realising the "one conformance suite, every fabric passes it" invariant.
 *
 * Provide a fresh host/joiner pair per test via [newLoomPair]:
 *  - `.first`  hosts via [Loom.host] (i.e. `weave(Rendezvous.New(pattern))`).
 *  - `.second` joins via [Loom.join] with [joinTag] (i.e. `weave(Rendezvous.Existing(joinTag()))`).
 *
 * In-process radio fabrics return the **same** instance twice: `(loom, loom)`.
 * Role-split fabrics (websocket, mdns, webrtc, multipeer) return **distinct**
 * host/joiner Looms wired to reach each other.
 */
public abstract class SeamConformanceSuite {

    /**
     * Provide a fresh host/joiner Loom pair per test.
     *  - `.first`  hosts via host(pattern)  (weave(Rendezvous.New(pattern)))
     *  - `.second` joins via join(joinTag()) (weave(Rendezvous.Existing(joinTag())))
     * In-process radio fabrics return the SAME instance twice: (loom, loom).
     * Role-split fabrics return DISTINCT host/joiner Looms wired to reach each other.
     */
    public abstract fun newLoomPair(): Pair<Loom, Loom>

    /** The advertisement the joiner uses. Defaults to the in-memory tag. */
    public open fun joinTag(): Tag = InMemoryTag("joiner")

    // ── (1) host yields a usable Seam with a non-empty selfId ───────────────

    @Test
    public fun hostYieldsUsableSeamWithNonEmptySelfId(): TestResult =
        runTest {
            val (hostLoom, _) = newLoomPair()
            val seam = hostLoom.host(Pattern("host"))

            assertFalse(seam.selfId.value.isEmpty(), "selfId must be non-empty")
        }

    // ── (2) broadcast from host delivers to a joined peer ───────────────────

    @Test
    public fun broadcastFromHostDeliversToJoinedPeer(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                val joiner = joinerDeferred.await()

                val received = async { joiner.incoming.take(1).toList() }

                val payload = byteArrayOf(10, 20, 30)
                host.broadcast(payload)

                val frames = received.await()
                assertEquals(1, frames.size)
                assertTrue(frames[0].payload.contentEquals(payload), "payload must match")
                assertEquals(host.selfId, frames[0].sender)
            }
        }

    // ── (3) incoming preserves send order to a single collector ─────────────

    @Test
    public fun incomingPreservesSendOrderToSingleCollector(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                val joiner = joinerDeferred.await()

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
        }

    // ── (4) peers reports selfId and ≥2 after a join ────────────────────────

    @Test
    public fun peersReportsSelfIdAndAtLeastTwoAfterJoin(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                val joiner = joinerDeferred.await()

                assertTrue(host.selfId in host.peers.value, "host peers must include its own selfId")
                assertTrue(joiner.selfId in joiner.peers.value, "joiner peers must include its own selfId")
                assertTrue(host.peers.value.size >= 2, "peer set must have ≥2 after join")
            }
        }

    // ── (5) close is idempotent — calling twice must not throw ──────────────

    @Test
    public fun closeIsIdempotent(): TestResult =
        runTest {
            val (hostLoom, _) = newLoomPair()
            val seam = hostLoom.host(Pattern("host"))

            seam.close()
            seam.close() // must not throw
        }

    // ── (6) availability returns Available or Unavailable ───────────────────

    @Test
    public fun availabilityReturnsAKnownVariant() {
        val (hostLoom, _) = newLoomPair()
        val availability = hostLoom.availability()

        assertTrue(
            availability is FabricAvailability.Available || availability is FabricAvailability.Unavailable,
            "availability() must return Available or Unavailable, got $availability",
        )
    }
}
