package us.tractat.kuilt.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A transport that tears on its own (relay crash, socket drop) must free the
 * session's single-seam slot: [PatchworkSession.connected] flips to offline and
 * a fresh [PatchworkSession.join] works — without the user ever calling
 * [PatchworkSession.disconnect]. Before the fix the slot stayed occupied and
 * every reconnect threw `"already connected — disconnect() first"`.
 */
class PatchworkSessionSeamTearTest {

    private val red = Colour("#e94f37")
    private val blue = Colour("#4062bb")

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
    fun transportTearGoesOfflineAndKeepsTheBoard() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val mesh = InMemoryLoom()
        val tearable = TearableLoom(mesh)
        val alice = session(mesh, "alice", FakeClock(1_000))
        val bob = session(tearable, "bob", FakeClock(1_000))

        alice.host(Pattern("patchwork"))
        bob.join(InMemoryTag("bob"))
        delay(1)
        alice.stitch(Cell(0, 0), red)
        delay(10)

        // The transport dies under bob — no disconnect() call anywhere.
        tearable.tearAll(CloseReason.RemoteRequested)
        delay(10)

        assertAll(
            { assertFalse(bob.connected.value, "a torn transport must flip connected to false") },
            { assertEquals(red, bob.quilt.value[Cell(0, 0)], "the board survives the tear") },
        )
    }

    @Test
    fun reconnectAfterTransportTearMergesOfflineStitches() = runTest(
        StandardTestDispatcher(),
        timeout = 5.seconds,
    ) {
        val mesh = InMemoryLoom()
        val tearable = TearableLoom(mesh)
        val alice = session(mesh, "alice", FakeClock(1_000))
        val bob = session(tearable, "bob", FakeClock(1_000))

        alice.host(Pattern("patchwork"))
        bob.join(InMemoryTag("bob"))
        delay(1)

        tearable.tearAll(CloseReason.RemoteRequested)
        delay(10)

        // Stitching offline still lands locally.
        bob.stitch(Cell(2, 2), blue)

        // Before the fix: IllegalStateException("already connected — disconnect() first").
        bob.join(InMemoryTag("bob-again"))
        delay(10)

        assertAll(
            { assertTrue(bob.connected.value, "rejoin after a tear must come back online") },
            { assertEquals(blue, alice.quilt.value[Cell(2, 2)], "offline stitch merges into alice") },
            { assertEquals(blue, bob.quilt.value[Cell(2, 2)], "offline stitch survives on bob") },
        )
    }
}

/**
 * Wraps a [Loom] so tests can simulate the transport tearing on its own:
 * [tearAll] latches every woven seam's state to [SeamState.Torn] without any
 * `close()` call — exactly what a remote drop looks like to the consumer.
 */
private class TearableLoom(private val delegate: Loom) : Loom {
    private val seams = mutableListOf<TearableSeam>()

    override suspend fun weave(rendezvous: Rendezvous): Seam =
        TearableSeam(delegate.weave(rendezvous)).also { seams += it }

    fun tearAll(reason: CloseReason) {
        seams.forEach { it.tear(reason) }
    }
}

private class TearableSeam(private val delegate: Seam) : Seam by delegate {
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    /** Simulates a self-driven transport termination: Torn with no close() call. */
    fun tear(reason: CloseReason) {
        _state.value = SeamState.Torn(reason)
    }

    override suspend fun close(reason: CloseReason) {
        _state.value = SeamState.Torn(reason)
        delegate.close(reason)
    }
}
