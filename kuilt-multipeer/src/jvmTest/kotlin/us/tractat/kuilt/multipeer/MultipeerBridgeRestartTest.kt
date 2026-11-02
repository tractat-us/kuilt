package us.tractat.kuilt.multipeer

import org.junit.Assume.assumeTrue
import org.junit.Test
import us.tractat.kuilt.core.freshPeerId
import java.lang.ref.Reference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shipped path, end to end: JNA into the real `libkuilt.dylib`, restarting a browse session as
 * fast as the JVM can ask for it. No fake stands in, because the defect is an abort inside the native
 * runtime, which nothing short of that runtime can produce.
 *
 * Gated on `-Pmultipeer.realnet.tests=true`, like every other test here that starts real browsing:
 * each start opens a real `MCNearbyServiceBrowser`, and one of the reds below takes the test JVM with it.
 *
 * **There are two reds, and which one you get says what broke.** Take away `mc_browser_stop`'s join
 * alone and the next start finds the old session still holding the browser, so it returns null and
 * [aTightRestartOnTheRealBridgeDoesNotAbortTheProcess] fails on a clean assertion. Take away the
 * collectors' `pumpIn` as well and the same collision is an abort: the test JVM dies with
 * `Uncaught Kotlin exception: kotlin.IllegalStateException: MultipeerPeerLinkFactory is already browsing`
 * on stderr and exit value 134. The results XML shows no failure, only the test it died in as `skipped`,
 * so read the task's failure rather than counting failure rows.
 *
 * A wedged `mc_browser_stop` is the third failure this can show, as a JUnit timeout, which is why these
 * use `org.junit.Test` rather than `kotlin.test.Test`. `BridgeBrowserQuiesceTest` on `macosArm64Test`
 * forces the same interleavings and can drive the browser delegate and observe the factory, neither of
 * which this class can see from the JVM.
 *
 * What it cannot do is exercise a callback: without a second device nothing is found, so the JNA
 * trampolines are registered and never entered.
 */
class MultipeerBridgeRestartTest {
    @Test(timeout = TIMEOUT_MILLIS)
    fun aTightRestartOnTheRealBridgeDoesNotAbortTheProcess() = withRealRuntime { lib, runtime ->
        var restarts = 0
        repeat(RESTARTS) { index ->
            val found = MultipeerNativeLib.PeerFoundCallback { _, _ -> }
            val lost = MultipeerNativeLib.PeerLostCallback { }
            val browser = assertNotNull(lib.mc_browser_start(runtime, found), "start $index was refused")
            lib.mc_browser_set_peer_lost_callback(browser, lost)
            lib.mc_browser_stop(browser)
            Reference.reachabilityFence(found)
            Reference.reachabilityFence(lost)
            restarts++
        }
        assertEquals(RESTARTS, restarts, "every restart must have run")
    }

    /**
     * The JVM's half of the refusal rests on the dylib's null, which the fakes in this module only
     * imitate. The repeated start shows a refusal does not release the first session's browser, and
     * the restart afterwards shows the first session's stop still gives it back.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    fun aSecondStartOnTheRealBridgeReturnsNull() = withRealRuntime { lib, runtime ->
        val found = MultipeerNativeLib.PeerFoundCallback { _, _ -> }
        val first = assertNotNull(lib.mc_browser_start(runtime, found), "the first start must succeed")
        val second = lib.mc_browser_start(runtime, found)
        val stillRefusedAfterASecondTry = lib.mc_browser_start(runtime, found)
        lib.mc_browser_stop(first)
        val restarted = lib.mc_browser_start(runtime, found)
        if (restarted != null) lib.mc_browser_stop(restarted)
        Reference.reachabilityFence(found)
        assertNull(second, "a second start must be refused while the first session holds the browser")
        assertNull(stillRefusedAfterASecondTry, "a refusal must not release the first session's browser")
        assertNotNull(restarted, "the first session's stop must release the browser")
    }

    private fun withRealRuntime(block: (MultipeerNativeLib, com.sun.jna.Pointer) -> Unit) {
        assumeTrue("Starts real MC browsing; gate behind -Pmultipeer.realnet.tests=true.", realNetEnabled())
        val lib = assertNotNull(MultipeerNativeLib.load(), "Native.load returned null on macOS")
        val runtime = assertNotNull(lib.mc_runtime_create("Restart", "kuilt-test", freshPeerId().value))
        try {
            block(lib, runtime)
        } finally {
            lib.mc_runtime_destroy(runtime)
        }
    }

    private fun realNetEnabled(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac") &&
            System.getProperty("multipeer.realnet.tests") == "true"

    private companion object {
        const val RESTARTS = 300
        const val TIMEOUT_MILLIS = 120_000L
    }
}
