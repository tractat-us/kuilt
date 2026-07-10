@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Terminal-lifecycle tests for [RoomHubSeam] — the roster-shaped member of the lost-terminal-Torn
 * class (#1364). Unlike the pump-driven seams, [RoomHubSeam]'s hazard is that an in-flight
 * [RoomHubSeam.deliver] can re-register a peer and republish `_peers`/`attestedPrincipals` **after**
 * `close()` cleared them — the `deliver()` `Torn` pre-check is a TOCTOU against the roster mutation.
 *
 * These run deterministically under [StandardTestDispatcher]: a controllable suspending authorizer
 * parks a `deliver()` past its `Torn` pre-check, then `close()` runs, then the authorizer is released
 * so the in-flight `deliver()` reaches its registration block **after** the roster was cleared.
 */
class RoomHubSeamCloseTest {

    @Test
    fun deliverInFlightDuringCloseDoesNotResurrectRoster() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val authGate = CompletableDeferred<Unit>()
        // A suspending authorizer we control: the deliver() parks here, past its Torn pre-check.
        val authorizer = RoomAuthorizer { _, _ -> authGate.await(); true }
        val room = RoomHubSeam(channelName = "table-7", selfId = PeerId("server"), authorizer = authorizer)

        val peer = PeerId("joiner")
        val sender = OutboundSender { /* no-op outbound */ }
        val frame = Swatch(payload = byteArrayOf(1, 2, 3), sender = peer)

        // Start a first-frame deliver for a not-yet-registered peer; it suspends in the authorizer.
        val deliverJob = launch { room.deliver(peer, frame, sender, principal = null) }
        runCurrent()

        // Close the room while the deliver is parked. This latches Torn and clears the roster.
        room.close()
        assertAll(
            { assertIs<SeamState.Torn>(room.state.value, "close() must latch Torn") },
            { assertTrue(room.peers.value.isEmpty(), "close() must clear the roster") },
        )

        // Release the authorizer: the in-flight deliver now reaches its registration block, AFTER
        // close() cleared the roster. It must NOT resurrect membership.
        authGate.complete(Unit)
        deliverJob.join()

        assertAll(
            { assertTrue(room.peers.value.isEmpty(), "a post-close deliver must not re-register a peer") },
            { assertTrue(room.attestedPrincipals.value.isEmpty(), "…nor republish attested principals") },
            { assertIs<SeamState.Torn>(room.state.value, "state must stay Torn") },
        )
    }

    @Test
    fun closeIsSingleShotAndIdempotent() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val room = RoomHubSeam("table-7", PeerId("server"), RoomAuthorizer.AllowAll)
        room.close(CloseReason.Normal)
        val first = room.state.value
        // A second close (even a different reason) must not overwrite the terminal state.
        room.close(CloseReason.RemoteRequested)
        assertAll(
            { assertIs<SeamState.Torn>(room.state.value) },
            { assertTrue(room.state.value === first, "a second close() must not republish a new Torn") },
        )
    }
}
