package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.multipeer.internal.BridgePeerLink
import us.tractat.kuilt.test.assertAll
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the JVM half has to do for `mc_session_close` to wait for callbacks in flight, and the one route by
 * which a wait could never finish. `BridgeSessionQuiesceTest` proves the native half on macOS. These run
 * against a fake lib, so they say nothing about a real trampoline.
 */
class BridgePeerLinkTeardownTest {
    /**
     * The fake's close does what the native one now does: it waits for a peer-state callback running on
     * another thread. That callback takes the roster lock, so a teardown holding it would wedge a real
     * close for good. Here the wait is bounded, which turns that wedge into this assertion.
     */
    @Test
    fun aPeerStateCallbackCanFinishWhileMcSessionCloseWaitsForIt() = runBlocking {
        val lib = TeardownFakeNativeLib()
        val link = BridgePeerLink(lib, Pointer(SESSION), PeerId("self"))
        lib.firePeerState("guest", 1)
        var callbackThreadFinished = false
        lib.onClose = {
            val callback = thread { lib.firePeerState("guest", 0) }
            callback.join(JOIN_BOUND_MILLIS)
            callbackThreadFinished = !callback.isAlive
        }

        link.close()

        assertAll(
            { assertEquals(1, lib.closes, "the rig's close must have run") },
            { assertTrue(callbackThreadFinished, "a callback must never wait on teardown") },
        )
    }

    /**
     * The route that rules out a plain join in the native close. The factory's KDoc tells a consumer to
     * close a seam it sees go `Torn`, and a collector on an unconfined dispatcher runs inside the `Torn`
     * write. On a remote drop that write is inside the peer-state callback, so the consumer's close arrives
     * on the very thread the native side would be waiting for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnconfinedCollectorClosingATornSeamReachesMcSessionCloseInsideTheCallback() = runTest {
        val lib = TeardownFakeNativeLib()
        val link = BridgePeerLink(lib, Pointer(SESSION), PeerId("self"))
        var insideCallback = false
        var insideCallbackAtClose = false
        lib.onClose = { insideCallbackAtClose = insideCallback }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            link.state.first { it is SeamState.Torn }
            link.close()
        }
        lib.firePeerState("guest", 1)
        val closesBeforeDrop = lib.closes

        insideCallback = true
        lib.firePeerState("guest", 0)
        insideCallback = false

        assertAll(
            { assertEquals(0, closesBeforeDrop, "the rig's drop must be what provokes the close") },
            { assertEquals(1, lib.closes, "the collector must have closed the seam") },
            { assertTrue(insideCallbackAtClose, "the close must have arrived inside the callback") },
            { assertFalse(insideCallback) },
        )
    }

    private companion object {
        const val SESSION = 0x100L
        const val JOIN_BOUND_MILLIS = 30_000L
    }
}

private class TeardownFakeNativeLib : MultipeerNativeLib by FakeMultipeerNativeLib() {
    var closes = 0
    var onClose: () -> Unit = {}
    private lateinit var peerStateCallback: MultipeerNativeLib.PeerStateCallback

    fun firePeerState(peerId: String, isConnected: Int) = peerStateCallback.invoke(peerId, isConnected)

    override fun mc_session_set_peer_state_callback(session: Pointer?, cb: MultipeerNativeLib.PeerStateCallback) {
        peerStateCallback = cb
    }

    override fun mc_session_close(session: Pointer?) {
        closes++
        onClose()
    }
}
