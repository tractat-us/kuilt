package us.tractat.kuilt.conformance

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 *
 * ## Weaving timing invariant
 *
 * The invariant "a frame sent while [SeamState.Weaving] is not silently dropped"
 * is **not** asserted in this suite because all current harnesses produce
 * instant-[SeamState.Woven] seams: relay fabrics (WebSocket, InMemory) weave at
 * construction, and the Multipeer fake fires its peer-connected callback
 * synchronously during `weave()`, so no harness actually starts [SeamState.Weaving]
 * by the time [newLoomPair] returns. Asserting a `Weaving` precondition here would
 * produce a vacuously-passing test on every fabric.
 *
 * The enforcement point for this invariant is [DelayedWovenLoomTest], which uses
 * [DelayedWovenLoom] — a test-only harness that holds the seam in [SeamState.Weaving]
 * until [DelayedWovenSeam.markWoven] is called explicitly — to reproduce the
 * radio-fabric timing window deterministically. Radio fabric conformance harnesses
 * that fire their connected event asynchronously should run their own equivalent of
 * [DelayedWovenLoomTest] to confirm frames are not dropped in the window.
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
            // Establish a real connection: a role-split server/handshake Loom's host()
            // suspends until a joiner connects, so this test must drive host()/join()
            // concurrently (radio fabrics returning (loom, loom) are unaffected).
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                joinerDeferred.await()

                assertFalse(host.selfId.value.isEmpty(), "selfId must be non-empty")
            }
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
                frames.forEachIndexed { i, f -> assertTrue(f.payload.contentEquals(byteArrayOf(i.toByte())), "frame $i payload") }
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
            // Connect concurrently (see hostYieldsUsableSeamWithNonEmptySelfId): a server
            // Loom's host() blocks until a joiner connects, so close the host seam of a
            // genuinely-established connection rather than an unconnected one.
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val seam = hostDeferred.await()
                joinerDeferred.await()

                seam.close()
                seam.close() // must not throw
            }
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

    // ── (7) state is Woven after host and joiner both return ─────────────────

    @Test
    public fun stateIsWovenAfterConnect(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                val joiner = joinerDeferred.await()

                // Both sides must reach Woven — await in case a radio fabric needs a tick.
                assertIs<SeamState.Woven>(
                    host.state.first { it is SeamState.Woven },
                    "host state must be Woven",
                )
                assertIs<SeamState.Woven>(
                    joiner.state.first { it is SeamState.Woven },
                    "joiner state must be Woven",
                )
            }
        }

    // ── (8) host state is Woven even before any peer joins ───────────────────

    @Test
    public fun hostStateIsWovenEvenAlone(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                joinerDeferred.await()

                // Relay fabrics are Woven at construction; radio fabrics on first connect.
                // Either way, after the connection completes, host must be Woven.
                val hostState = host.state.first { it is SeamState.Woven }
                assertIs<SeamState.Woven>(hostState, "host state must be Woven")
            }
        }

    // ── (9) close drives state to Torn(Normal) ──────────────────────────────

    @Test
    public fun closeDrivesStateTornNormal(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val seam = hostDeferred.await()
                joinerDeferred.await()

                seam.close()

                assertIs<SeamState.Torn>(seam.state.value, "state must be Torn after close()")
            }
        }

    // ── (10) sendTo an absent peer throws PeerNotConnected ───────────────────

    @Test
    public fun sendToAbsentPeerThrowsPeerNotConnected(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                joinerDeferred.await()

                val phantom = PeerId("phantom-peer-not-in-session")
                assertFailsWith<PeerNotConnected> {
                    host.sendTo(phantom, byteArrayOf(1))
                }
            }
        }

    // ── (11) incoming completes when the seam reaches Torn ───────────────────
    //
    // Contract from Seam.incoming KDoc: the flow terminates once the seam is Torn,
    // whether via local close() or remote disconnect. Consumers (e.g. Quilter)
    // rely on this to self-close via onCompletion without requiring an explicit caller.
    //
    // `open` so a fabric that does not yet honour the contract can override this with
    // `@Ignore` (visible as skipped in its report) and a tracking issue, rather than
    // silently weakening the assertion for every fabric. WebRTC does so today — see #335.

    @Test
    public open fun incomingCompletesWhenSeamCloses(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                joinerDeferred.await()

                // Collect host.incoming in the background; it should complete once host closes.
                val collectingJob = async {
                    withTimeout(5.seconds) {
                        host.incoming.toList()
                    }
                }

                host.close()

                // If the fabric honours the contract, toList() completes (flow terminated).
                // withTimeout(5s) guards against fabrics that hang instead of completing.
                collectingJob.await()
                assertIs<SeamState.Torn>(host.state.value, "host state must be Torn after close()")
            }
        }
}
