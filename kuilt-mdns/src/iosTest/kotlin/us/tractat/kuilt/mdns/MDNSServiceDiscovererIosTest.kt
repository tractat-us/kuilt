@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION")

package us.tractat.kuilt.mdns

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject
import platform.posix.getenv
import us.tractat.kuilt.core.PeerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * iOS integration test for [MDNSServiceDiscoverer]'s callback→flow path.
 *
 * **Opt-in:** skipped unless the environment variable `MDNS_MULTICAST_TESTS`
 * is set to `true`. Run with:
 *
 * ```
 * ./gradlew :transport-mdns:iosSimulatorArm64Test -Pmdns.multicast.tests=true
 * ```
 *
 * (The `:transport-mdns` build file forwards the Gradle property to an env var
 * so the K/N test binary can read it via `platform.posix.getenv`.)
 *
 * **Why not a fake-based test?** The JVM side ([MDNSServiceDiscovererFlowTest])
 * isolates the callback→flow mechanics via [FakeEventJmDNS] because [JmDNS] is
 * an abstract class with a replaceable interface. On iOS, [NSNetServiceBrowser]
 * is a sealed Foundation type — there is no seam to inject a fake browser, and
 * [ServiceDelegate] is private. The only way to exercise the
 * `NSNetServiceBrowser delegate → callbackFlow → MDNSAdvertisement` path is to
 * run the real Bonjour stack.
 *
 * **Run-loop discipline:** K/N iOS tests run on the **main thread**.
 * `runBlocking` on the main thread blocks the main `CFRunLoop`, which starves
 * `NSNetServiceBrowser` (it relies on the main run-loop for Bonjour callbacks)
 * and `NSNetService` (which also registers via the run-loop). Instead, the test
 * uses [spinMainRunLoop] — identical in spirit to
 * `IosMultipeerPairHandshakeTest.spinMainRunLoop` — which pumps
 * `CFRunLoopRunInMode` in 100 ms slices and returns when a predicate is satisfied
 * or the timeout elapses. Flow collection runs on [Dispatchers.Default], which
 * does not block the main thread.
 *
 * **What this catches:** the dispatcher bug fixed in PR #728 —
 * `NSNetServiceBrowser` was being set up on a background dispatcher so
 * `scheduleInRunLoop(mainRunLoop)` never registered callbacks correctly.
 * A test collecting the flow with a real browser would have timed out and failed.
 */
class MDNSServiceDiscovererIosTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var advertisedService: NSNetService? = null

    @AfterTest
    fun tearDown() {
        stopAdvertisedService()
        testScope.cancel()
    }

    /**
     * Advertises a service via [NSNetService] and asserts that
     * [MDNSServiceDiscoverer.discoveries] emits a matching [MDNSAdvertisement].
     *
     * This exercises the full callback path:
     * `NSNetServiceBrowser` delegate → `callbackFlow` → parsed [MDNSAdvertisement].
     *
     * A regression of the PR #728 dispatcher bug would cause the browser to be
     * set up on a background thread, making `scheduleInRunLoop(mainRunLoop)` a
     * no-op — callbacks would never fire and this test would time out.
     */
    @Test
    fun discoverer_emits_advertisement_from_local_nsnetservice() {
        if (!isGated()) return

        val expectedPeerId = PeerId("ios-test-peer-9820")
        advertiseService(peerId = expectedPeerId)

        var discovered: MDNSAdvertisement? = null
        testScope.launch {
            MDNSServiceDiscoverer()
                .discoveries()
                .collect { ad ->
                    if (ad.serverPeerId == expectedPeerId) {
                        discovered = ad
                    }
                }
        }

        spinMainRunLoop(DISCOVERY_TIMEOUT_MS) { discovered != null }

        val ad =
            checkNotNull(discovered) {
                "MDNSServiceDiscoverer must emit an MDNSAdvertisement for the advertised service " +
                    "within ${DISCOVERY_TIMEOUT_MS}ms — callback-to-flow path may be broken " +
                    "(regression target: PR #728 dispatcher fix)"
            }
        assertTrue(
            ad.port == TEST_PORT,
            "Emitted advertisement port ${ad.port} must match advertised port $TEST_PORT",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns `true` when [GATE_ENV_VAR] is `"true"`. */
    private fun isGated(): Boolean = getenv(GATE_ENV_VAR)?.toKString() == "true"

    /**
     * Creates and publishes an [NSNetService] on [NSRunLoop.mainRunLoop] that
     * carries the minimal TXT record required by [MDNSServiceDiscoverer]:
     * `peerId` and `wsPath`.
     */
    private fun advertiseService(peerId: PeerId) {
        val ns =
            NSNetService(
                domain = "local.",
                type = SERVICE_TYPE_WITHOUT_DOMAIN,
                name = "MDNSDiscovererIosTest",
                port = TEST_PORT,
            )

        val txtDict: Map<Any?, *> =
            mapOf(
                MDNSAdvertisement.TXT_KEY_PEER_ID to peerId.value.toNSData(),
                MDNSAdvertisement.TXT_KEY_WS_PATH to MDNSAdvertisement.DEFAULT_WS_PATH.toNSData(),
            )
        ns.setTXTRecordData(NSNetService.dataFromTXTRecordDictionary(txtDict))
        ns.setDelegate(NoopNetServiceDelegate())
        ns.scheduleInRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
        ns.publish()
        advertisedService = ns
    }

    private fun stopAdvertisedService() {
        advertisedService?.stop()
        advertisedService?.removeFromRunLoop(NSRunLoop.mainRunLoop(), forMode = NSRunLoopCommonModes)
        advertisedService?.setDelegate(null)
        advertisedService = null
    }

    /**
     * Pumps the main `CFRunLoop` in [RUN_LOOP_SLICE_S]-second slices until
     * [predicate] returns `true` or [timeoutMs] elapses.
     *
     * This is the K/N replacement for `runBlocking { withTimeout(N) { poll } }`.
     * Blocking the main thread starves `NSNetServiceBrowser` and `NSNetService`
     * callbacks. Spinning via `CFRunLoopRunInMode` releases the run-loop between
     * slices so Bonjour can deliver its callbacks.
     */
    private fun spinMainRunLoop(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ) {
        val timeout = timeoutMs.milliseconds
        val mark = TimeSource.Monotonic.markNow()
        while (!predicate() && mark.elapsedNow() < timeout) {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, RUN_LOOP_SLICE_S, true)
        }
    }

    private companion object {
        /**
         * Env var name forwarded from `-Pmdns.multicast.tests=true` by
         * `:transport-mdns`'s `build.gradle.kts`.
         */
        const val GATE_ENV_VAR = "MDNS_MULTICAST_TESTS"

        /**
         * Service type for `NSNetService` init — without the trailing `local.`
         * domain. The [NSNetServiceBrowser] searches for this type in `"local."`.
         */
        const val SERVICE_TYPE_WITHOUT_DOMAIN = "_fireworks._tcp."

        const val TEST_PORT: Int = 19500
        const val DISCOVERY_TIMEOUT_MS: Long = 15_000L
        const val RUN_LOOP_SLICE_S: Double = 0.1
    }
}

/** No-op [NSNetServiceDelegateProtocol] used by the advertising [NSNetService]. */
private class NoopNetServiceDelegate :
    NSObject(),
    NSNetServiceDelegateProtocol

/** Encodes this string as UTF-8 NSData for use in mDNS TXT record dictionaries. */
@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toNSData(): NSData =
    (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        ?: error("UTF-8 encoding failed for: $this")
