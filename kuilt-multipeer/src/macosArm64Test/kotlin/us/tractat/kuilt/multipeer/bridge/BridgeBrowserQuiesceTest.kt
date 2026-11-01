package us.tractat.kuilt.multipeer.bridge

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.job
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import platform.posix.usleep
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.test.assertAll
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The lifetime half of `mc_browser_stop`, against the real exports, the real `MultipeerPeerLinkFactory`
 * and a real `MCNearbyServiceBrowser` whose delegate is driven by hand, since a `foundPeer` needs a
 * second device. These collectors run on the bridge's production `Dispatchers.Default`, because the
 * export injects nothing; no test dispatcher could hold a C callback in flight.
 *
 * **The window is forced, not raced.** [Probe.foundHeld] parks inside the C callback until the test
 * says stop is coming, then holds for [HOLD_MICROS] more, so at the moment `mc_browser_stop` is called
 * the old collector is provably inside the callback and provably has not reached its `finally`.
 *
 * **A red here is an assertion, not an abort.** Each test installs an unhandled-exception hook that
 * records instead of terminating, so the check that would kill a shipped process comes back as a list
 * entry naming the exception. `MultipeerBridgeRestartTest` on the JVM is the same defect with no hook,
 * where the red is the process dying.
 *
 * **Timing can only weaken a red.** The hold exists so a stop that does not wait returns visibly early.
 * A stalled box can at worst let an unfixed stop miss the window; a fixed stop passes at any speed,
 * so these belong in the ordinary build rather than behind `-Pconcurrency.stress.tests`.
 *
 * **What this cannot see:** the JNA trampoline. These callbacks are `staticCFunction`s with nothing to
 * collect, so what is proven is the premise the JVM relies on: once `mc_browser_stop` returns, no
 * callback of that session is running or will run.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class BridgeBrowserQuiesceTest {
    private val uncaughtLock = reentrantLock()
    private val uncaught = mutableListOf<Throwable>()
    private var previousHook: ReportUnhandledExceptionHook? = null

    private fun uncaughtSoFar(): List<Throwable> = uncaughtLock.withLock { uncaught.toList() }

    @BeforeTest
    fun recordInsteadOfAborting() {
        Probe.reset()
        previousHook = setUnhandledExceptionHook { failure -> uncaughtLock.withLock { uncaught += failure } }
    }

    @AfterTest
    fun restoreTheHook() {
        setUnhandledExceptionHook(previousHook)
    }

    @Test
    fun stopWaitsForAFoundCallbackAlreadyInFlight() = withRuntime { factory, runtime ->
        val handle = assertNotNull(mc_browser_start(runtime, Probe.foundHeld))
        val delegate = awaitDelegate(factory)
        delegate.deliverFound("held#12345678")
        await("the found callback is entered") { Probe.entered.value == 1 }
        val exitedBeforeStop = Probe.exited.value

        Probe.release.value = true
        mc_browser_stop(handle)
        val exitedAtReturn = Probe.exited.value
        val delegateAtReturn = factory.activeBrowserDelegate

        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeStop, "the rig must hold the callback across the stop") },
            { assertEquals(1, exitedAtReturn, "a callback in flight must finish before stop returns") },
            { assertNull(delegateAtReturn, "the collector's teardown must finish before stop returns") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    @Test
    fun stopWaitsForALostCallbackAlreadyInFlight() = withRuntime { factory, runtime ->
        val handle = assertNotNull(mc_browser_start(runtime, Probe.foundIgnored))
        mc_browser_set_peer_lost_callback(handle, Probe.lostHeld)
        val delegate = awaitDelegate(factory)
        await("the lost collector subscribes") { factory.lostPeerHandles.subscriptionCount.value > 0 }
        delegate.deliverLost("held#12345678")
        await("the lost callback is entered") { Probe.entered.value == 1 }
        val exitedBeforeStop = Probe.exited.value

        Probe.release.value = true
        mc_browser_stop(handle)
        val exitedAtReturn = Probe.exited.value

        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeStop, "the rig must hold the callback across the stop") },
            { assertEquals(1, exitedAtReturn, "stop must wait for the lost collector as well as the found one") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * The issue's crash, forced. The new browser has a fresh lock and count, so the only thing between
     * its `startBrowsing` and `check(browser == null)` is the old collector having reached `stopBrowsing`,
     * which it cannot do while held in the callback.
     */
    @Test
    fun restartWhileACallbackIsInFlightStartsTheNewSession() = withRuntime { factory, runtime ->
        val old = assertNotNull(mc_browser_start(runtime, Probe.foundHeld))
        val oldDelegate = awaitDelegate(factory)
        oldDelegate.deliverFound("held#12345678")
        await("the found callback is entered") { Probe.entered.value == 1 }
        val exitedBeforeStop = Probe.exited.value

        Probe.release.value = true
        mc_browser_stop(old)
        val restarted = assertNotNull(mc_browser_start(runtime, Probe.foundCounted))
        await("a new browser or a failed start") {
            factory.activeBrowserDelegate.let { it != null && it !== oldDelegate } || uncaughtSoFar().isNotEmpty()
        }
        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertEquals(emptyList(), uncaughtSoFar(), "a restart must not reach the unhandled-exception route")

        val newDelegate = assertNotNull(factory.activeBrowserDelegate, "the restart must leave a live browser")
        newDelegate.deliverFound("next#12345678")
        await("the restarted session delivers") { Probe.counted.value == 1 }
        mc_browser_stop(restarted)
        assertAll(
            { assertEquals(0, exitedBeforeStop, "the rig must hold the callback across the stop") },
            { assertNotSame(oldDelegate, newDelegate, "the restart must own a new browser") },
        )
    }

    /** The same collision unforced: nothing held, every session owning the browser before it stops. */
    @Test
    fun aTightRestartLoopNeverCollidesWithThePreviousSession() = withRuntime { factory, runtime ->
        var restarts = 0
        var stillBrowsingAtReturn = 0
        repeat(RESTARTS) { index ->
            val handle = assertNotNull(mc_browser_start(runtime, Probe.foundIgnored))
            mc_browser_set_peer_lost_callback(handle, Probe.lostIgnored)
            await("session $index takes the browser") {
                factory.activeBrowserDelegate != null || uncaughtSoFar().isNotEmpty()
            }
            mc_browser_stop(handle)
            if (factory.activeBrowserDelegate != null) stillBrowsingAtReturn++
            restarts++
        }
        assertAll(
            { assertEquals(emptyList(), uncaughtSoFar(), "no restart may reach the unhandled-exception route") },
            { assertEquals(RESTARTS, restarts, "every restart must have run") },
            { assertEquals(0, stillBrowsingAtReturn, "each stop must leave the browser released") },
        )
    }

    /**
     * Nothing in the bridge serialises two owners of one runtime: separate JVM browsers each hold their
     * own lock, so join cannot help here and `check(browser == null)` still fires. It must cost that
     * owner its session and nothing else.
     *
     * The second session's job having no children left is the witness that the check fired: a collector
     * that had subscribed would park there until stopped.
     */
    @Test
    fun aSecondOwnerOfOneRuntimeDoesNotReachTheUnhandledRoute() = withRuntime { factory, runtime ->
        val first = assertNotNull(mc_browser_start(runtime, Probe.foundCounted))
        val delegate = awaitDelegate(factory)

        val second = assertNotNull(mc_browser_start(runtime, Probe.foundIgnored))
        await("the second owner's collector ends") { second.sessionHasNoCollector() }
        val uncaughtAfterSecondStart = uncaughtSoFar()
        val delegateAfterSecondStart = factory.activeBrowserDelegate

        delegate.deliverFound("kept#12345678")
        await("the first owner keeps delivering") { Probe.counted.value == 1 }
        mc_browser_stop(second)
        val delegateAfterSecondStop = factory.activeBrowserDelegate
        mc_browser_stop(first)

        assertAll(
            { assertEquals(emptyList(), uncaughtAfterSecondStart, "the refused start must not reach the unhandled route") },
            { assertSame(delegate, delegateAfterSecondStart, "the first owner's browser survives the refused start") },
            { assertSame(delegate, delegateAfterSecondStop, "stopping an owner that never browsed must not stop the other") },
            { assertNull(factory.activeBrowserDelegate, "the first owner's stop releases the browser") },
        )
    }

    private fun COpaquePointer.sessionHasNoCollector(): Boolean =
        asStableRef<BrowserState>().get().scope.coroutineContext.job.children.none()

    private fun withRuntime(block: (MultipeerPeerLinkFactory, COpaquePointer) -> Unit) {
        val factory = MultipeerPeerLinkFactory("local", "kuilt-test")
        val ref = StableRef.create(factory)
        try {
            block(factory, ref.asCPointer())
        } finally {
            factory.close()
            ref.dispose()
        }
    }

    private fun awaitDelegate(factory: MultipeerPeerLinkFactory): MCNearbyServiceBrowserDelegateProtocol {
        await("the collector takes the browser") { factory.activeBrowserDelegate != null }
        return assertNotNull(factory.activeBrowserDelegate)
    }

    private fun MCNearbyServiceBrowserDelegateProtocol.deliverFound(name: String) {
        val peer = MCPeerID(displayName = name)
        browser(MCNearbyServiceBrowser(peer = peer, serviceType = "kuilt-test"), foundPeer = peer, withDiscoveryInfo = null)
    }

    private fun MCNearbyServiceBrowserDelegateProtocol.deliverLost(name: String) {
        val peer = MCPeerID(displayName = name)
        browser(MCNearbyServiceBrowser(peer = peer, serviceType = "kuilt-test"), lostPeer = peer)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + AWAIT_BOUND
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting until $what; uncaught=${uncaughtSoFar()}")
            usleep(POLL_MICROS)
        }
    }

    private companion object {
        const val RESTARTS = 200
        const val POLL_MICROS: UInt = 500u
        val AWAIT_BOUND = 60.seconds
    }
}

private const val HOLD_MICROS: UInt = 200_000u

/** Global because a `staticCFunction` can capture nothing. */
@OptIn(ExperimentalForeignApi::class)
private object Probe {
    val entered = atomic(0)
    val exited = atomic(0)
    val counted = atomic(0)
    val release = atomic(false)

    val foundHeld = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? -> hold() }
    val lostHeld = staticCFunction { _: CPointer<ByteVar>? -> hold() }
    val foundIgnored = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? -> }
    val lostIgnored = staticCFunction { _: CPointer<ByteVar>? -> }
    val foundCounted = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? ->
        counted.incrementAndGet()
        Unit
    }

    fun reset() {
        entered.value = 0
        exited.value = 0
        counted.value = 0
        release.value = false
    }

    private fun hold() {
        entered.incrementAndGet()
        while (!release.value) usleep(100u)
        usleep(HOLD_MICROS)
        exited.incrementAndGet()
    }
}
