package us.tractat.kuilt.multipeer

import org.junit.Assume.assumeTrue
import us.tractat.kuilt.core.freshPeerId
import java.lang.ref.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The shipped path, end to end: JNA into the real `libkuilt.dylib`, restarting a browse session as
 * fast as the JVM can ask for it. No fake stands in, because the defect is an abort inside the native
 * runtime, which nothing short of that runtime can produce.
 *
 * **The red is the test JVM dying**, with `Uncaught Kotlin exception: kotlin.IllegalStateException:
 * MultipeerPeerLinkFactory is already browsing` on stderr, and no results XML for this class. A clean
 * assertion failure cannot exist here: the abort takes the assertion with it. The forced, assertable
 * form of the same interleaving is `BridgeBrowserQuiesceTest` on `macosArm64Test`, which can drive the
 * browser delegate and observe the factory; this class cannot see either from the JVM, so a green here
 * says only that the process survived [RESTARTS] restarts, which is why the loop counts them.
 *
 * What it cannot do is exercise a callback: without a second device nothing is found, so the JNA
 * trampolines are registered and never entered.
 */
class MultipeerBridgeRestartTest {
    @Test
    fun aTightRestartOnTheRealBridgeDoesNotAbortTheProcess() {
        assumeTrue("libkuilt.dylib is macOS-only.", System.getProperty("os.name").orEmpty().lowercase().contains("mac"))
        val lib = assertNotNull(MultipeerNativeLib.load(), "Native.load returned null on macOS")
        val runtime = assertNotNull(lib.mc_runtime_create("Restart", "kuilt-test", freshPeerId().value))
        var restarts = 0
        try {
            repeat(RESTARTS) {
                val found = MultipeerNativeLib.PeerFoundCallback { _, _ -> }
                val lost = MultipeerNativeLib.PeerLostCallback { }
                val browser = assertNotNull(lib.mc_browser_start(runtime, found))
                lib.mc_browser_set_peer_lost_callback(browser, lost)
                // Long enough for the collector to take the browser, or `mc_browser_stop` cancels a
                // coroutine that never started and the next start has nothing to collide with.
                Thread.sleep(1)
                lib.mc_browser_stop(browser)
                Reference.reachabilityFence(found)
                Reference.reachabilityFence(lost)
                restarts++
            }
        } finally {
            lib.mc_runtime_destroy(runtime)
        }
        assertEquals(RESTARTS, restarts, "every restart must have run")
    }

    private companion object {
        const val RESTARTS = 300
    }
}
