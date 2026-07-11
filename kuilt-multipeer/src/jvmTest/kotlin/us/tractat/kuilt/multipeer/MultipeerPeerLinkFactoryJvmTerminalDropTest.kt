package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * JVM-side counterpart of the apple `MultipeerPeerLinkFactoryTerminalDropTest`:
 * a self-disconnected session (remote peer terminally drops) must free the
 * factory's single-session slot so a reconnect works without an explicit
 * [MultipeerPeerLinkFactory.close] — and an explicit `Seam.close()` must free
 * it too, without the factory ever issuing a second `mc_session_close` for the
 * same handle (documented as a use-after-free by the native bridge).
 *
 * Drives the JNA peer-state callback directly via
 * [SessionTrackingFakeMultipeerNativeLib] — the same seam the macOS dylib
 * fires `peer:didChangeState:` transitions through.
 */
class MultipeerPeerLinkFactoryJvmTerminalDropTest {
    @Test
    fun factoryIsReusableAfterTerminalPeerDrop() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            // Successful connect, then the remote peer terminally drops.
            lib.firePeerState(session1, "guest", isConnected = 1)
            lib.firePeerState(session1, "guest", isConnected = 0)

            // Before the fix this throws "already has an active session".
            val second = factory.weave(Rendezvous.New(Pattern("room")))
            assertNotSame(first, second)
            // Close the dropped first seam too — the slot frees on drop, but the
            // native handle (and its drain coroutine) is disposed only by close().
            first.close()
            second.close()
            factory.close()
        }

    @Test
    fun terminalDropLatchesTornAndCompletesIncoming() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            // The Seam contract: `incoming` completes once the seam reaches Torn,
            // whether via local close OR a remote disconnect. Collect it — the
            // collector must terminate when the terminal drop tears the seam.
            val collector = launch { first.incoming.collect { } }

            // Successful connect, then the remote peer terminally drops.
            lib.firePeerState(session1, "guest", isConnected = 1)
            lib.firePeerState(session1, "guest", isConnected = 0)

            // Completes because the terminal drop closed the spool.
            collector.join()

            assertAll(
                { assertTrue(first.state.value is SeamState.Torn, "terminal drop must latch Torn") },
                { assertEquals(0, lib.closeCount(session1), "the drop path must not call mc_session_close") },
            )
            first.close()
            factory.close()
        }

    @Test
    fun failedJoinThatNeverConnectsFreesTheSlot() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            // The peer never reaches connected — the drop still frees the slot.
            lib.firePeerState(session1, "guest", isConnected = 0)

            val second = factory.weave(Rendezvous.New(Pattern("room")))
            first.close()
            second.close()
            factory.close()
        }

    @Test
    fun terminalDropFreesTheSlotButLeavesNativeHandleForConsumerClose() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            // Terminal peer drop: the slot frees so a reconnect is possible...
            lib.firePeerState(session1, "guest", isConnected = 1)
            lib.firePeerState(session1, "guest", isConnected = 0)

            // ...but the native handle is NOT yet disposed — freeing the slot from
            // inside the JNA callback must not re-enter mc_session_close.
            val second = factory.weave(Rendezvous.New(Pattern("room")))
            val nativeCloseCountRightAfterDrop = lib.closeCount(session1)

            // The consumer disposes the leaked handle by close()ing the torn seam.
            first.close(CloseReason.RemoteRequested)

            assertAll(
                { assertEquals(0, nativeCloseCountRightAfterDrop, "the drop path must not call mc_session_close") },
                { assertEquals(1, lib.closeCount(session1), "consumer close() disposes the native handle exactly once") },
            )
            second.close()
            factory.close()
        }

    @Test
    fun explicitSeamCloseFreesTheSlotAndNativeHandleClosesExactlyOnce() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            first.close(CloseReason.Normal)

            // Before the fix the slot stays occupied after an explicit seam close...
            val second = factory.weave(Rendezvous.New(Pattern("room")))
            val session2 = lib.lastOpenedSession()
            assertNotSame(first, second)

            // ...and factory.close() would then mc_session_close(session1) a second
            // time — a use-after-free per the native bridge contract.
            factory.close()
            assertAll(
                { assertEquals(1, lib.closeCount(session1), "session1 must be closed exactly once") },
                { assertEquals(1, lib.closeCount(session2), "session2 must be closed exactly once") },
            )
        }

    @Test
    fun partialDropKeepsTheSlotOccupied() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            lib.firePeerState(session1, "a", isConnected = 1)
            lib.firePeerState(session1, "b", isConnected = 1)
            // One of two peers drops — the session is still live, no free.
            lib.firePeerState(session1, "a", isConnected = 0)

            assertFailsWith<IllegalStateException> {
                factory.weave(Rendezvous.New(Pattern("room")))
            }
            factory.close()
        }

    @Test
    fun staleDropFromReplacedSessionIsANoOp() =
        runTest {
            val lib = SessionTrackingFakeMultipeerNativeLib()
            val factory = factory(lib)
            val first = factory.weave(Rendezvous.New(Pattern("room")))
            val session1 = lib.lastOpenedSession()

            lib.firePeerState(session1, "guest", isConnected = 1)
            lib.firePeerState(session1, "guest", isConnected = 0)
            val second = factory.weave(Rendezvous.New(Pattern("room")))

            // A stale terminal callback from the already-replaced session must not
            // free the slot now owned by the second session.
            lib.firePeerState(session1, "guest", isConnected = 0)

            assertFailsWith<IllegalStateException> {
                factory.weave(Rendezvous.New(Pattern("room")))
            }
            first.close()
            second.close()
            factory.close()
        }

    private fun factory(lib: MultipeerNativeLib): MultipeerPeerLinkFactory =
        MultipeerPeerLinkFactory(
            displayName = "host",
            serviceType = "kuilt-jvm-drop",
            injectedLib = lib,
            injectedRuntimeHandle = Pointer(0x1L),
        )
}

/**
 * Fake [MultipeerNativeLib] that mints a distinct session handle per
 * `mc_runtime_open`/`mc_runtime_join`, captures each session's
 * [MultipeerNativeLib.PeerStateCallback] so tests can fire peer-state
 * transitions, and counts `mc_session_close` calls per handle.
 */
internal class SessionTrackingFakeMultipeerNativeLib : MultipeerNativeLib by FakeMultipeerNativeLib() {
    private var nextSession = 0x100L
    private var lastOpened: Pointer? = null
    private val peerStateCallbacks = mutableMapOf<Pointer, MultipeerNativeLib.PeerStateCallback>()
    private val closes = mutableMapOf<Pointer, Int>()

    fun lastOpenedSession(): Pointer = checkNotNull(lastOpened) { "no session opened yet" }

    fun closeCount(session: Pointer): Int = closes[session] ?: 0

    fun firePeerState(
        session: Pointer,
        peerId: String,
        isConnected: Int,
    ) {
        peerStateCallbacks.getValue(session).invoke(peerId, isConnected)
    }

    override fun mc_runtime_open(handle: Pointer?): Pointer = mint()

    override fun mc_runtime_join(
        runtime: Pointer?,
        peerHandle: String,
    ): Pointer = mint()

    override fun mc_session_set_peer_state_callback(
        session: Pointer?,
        cb: MultipeerNativeLib.PeerStateCallback,
    ) {
        peerStateCallbacks[checkNotNull(session)] = cb
    }

    override fun mc_session_close(session: Pointer?) {
        val handle = checkNotNull(session)
        closes[handle] = (closes[handle] ?: 0) + 1
    }

    private fun mint(): Pointer = Pointer(nextSession++).also { lastOpened = it }
}
