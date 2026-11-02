package us.tractat.kuilt.multipeer.bridge

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.staticCFunction
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import platform.posix.usleep
import us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory
import us.tractat.kuilt.test.assertAll
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
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
 * **A wedge is a named red.** `mc_browser_stop` is unbounded on purpose, so [stop] runs it on a
 * [Worker] and the test thread polls the future under [AWAIT_BOUND]. Called on the test thread, a join
 * that deadlocked would sit until the CI job's timeout with no test named.
 *
 * **A red here is an assertion, not an abort.** Each test installs an unhandled-exception hook that
 * records instead of terminating, so a failure on the route that would kill a shipped process comes
 * back as a list entry naming the exception. `MultipeerBridgeRestartTest` is the same bridge from the
 * JVM with no hook.
 *
 * **Timing can only weaken a red.** The hold exists so a stop that does not wait returns visibly early.
 * A stalled box can at worst let an unfixed stop miss the window; a fixed stop passes at any speed,
 * so these belong in the ordinary build rather than behind `-Pconcurrency.stress.tests`.
 *
 * **What this cannot see:** the JNA trampoline. These callbacks are `staticCFunction`s with nothing to
 * collect, so what is proven is the premise the JVM relies on: once `mc_browser_stop` returns, no
 * callback of that session is running or will run.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ObsoleteWorkersApi::class)
class BridgeBrowserQuiesceTest {
    private val uncaughtLock = reentrantLock()
    private val uncaught = mutableListOf<Throwable>()
    private var previousHook: ReportUnhandledExceptionHook? = null
    private val stopper = Worker.start(name = "quiesce-stopper")

    private fun uncaughtSoFar(): List<Throwable> = uncaughtLock.withLock { uncaught.toList() }

    @BeforeTest
    fun recordInsteadOfAborting() {
        Probe.reset()
        previousHook = setUnhandledExceptionHook { failure -> uncaughtLock.withLock { uncaught += failure } }
    }

    @AfterTest
    fun restoreTheHook() {
        setUnhandledExceptionHook(previousHook)
        // Not awaited: after a wedge the worker never gets as far as terminating.
        stopper.requestTermination(processScheduledJobs = false)
    }

    @Test
    fun stopWaitsForAFoundCallbackAlreadyInFlight() = withRuntime { factory, runtime ->
        val handle = assertNotNull(mc_browser_start(runtime, Probe.foundHeld))
        val delegate = awaitDelegate(factory)
        delegate.deliverFound("held#12345678")
        await("the found callback is entered") { Probe.entered.value == 1 }
        val exitedBeforeStop = Probe.exited.value

        Probe.release.value = true
        val atReturn = stop(factory, handle)

        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeStop, "the rig must hold the callback across the stop") },
            { assertEquals(1, atReturn.exited, "a callback in flight must finish before stop returns") },
            { assertNull(atReturn.delegate, "the collector's teardown must finish before stop returns") },
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
        val atReturn = stop(factory, handle)

        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeStop, "the rig must hold the callback across the stop") },
            { assertEquals(1, atReturn.exited, "stop must wait for the lost collector as well as the found one") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * The issue's crash, forced. The new browser has a fresh lock and count, so the only thing between
     * its `startBrowsing` and `check(browser == null)` is the old collector having reached `stopBrowsing`,
     * which it cannot do while held in the callback. A stop that did not wait now shows as a refused
     * restart, so the red is the null rather than an abort.
     */
    @Test
    fun restartWhileACallbackIsInFlightStartsTheNewSession() = withRuntime { factory, runtime ->
        val old = assertNotNull(mc_browser_start(runtime, Probe.foundHeld))
        val oldDelegate = awaitDelegate(factory)
        oldDelegate.deliverFound("held#12345678")
        await("the found callback is entered") { Probe.entered.value == 1 }
        val exitedBeforeStop = Probe.exited.value

        Probe.release.value = true
        stop(factory, old)
        val restarted = mc_browser_start(runtime, Probe.foundCounted)
        await("the held callback finishes before cleanup") { Probe.exited.value == 1 }
        assertNotNull(restarted, "a restart must not be refused; uncaught=${uncaughtSoFar()}")
        assertEquals(emptyList(), uncaughtSoFar(), "a restart must not reach the unhandled-exception route")

        val newDelegate = assertNotNull(factory.activeBrowserDelegate, "the restart must leave a live browser")
        newDelegate.deliverFound("next#12345678")
        await("the restarted session delivers") { Probe.counted.value == 1 }
        stop(factory, restarted)
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
            val handle = assertNotNull(mc_browser_start(runtime, Probe.foundIgnored), "start $index was refused")
            mc_browser_set_peer_lost_callback(handle, Probe.lostIgnored)
            awaitDelegate(factory)
            if (stop(factory, handle).delegate != null) stillBrowsingAtReturn++
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
     * own lock, so a join cannot help here and `check(browser == null)` still fires. It has to fire
     * where the JVM can hear it, so the second start returns null rather than a handle to a dead session.
     *
     * This is read straight after the start, with no wait, because the promise is that the refusal has
     * already happened by the time the call returns. The first owner holds its lost callback the way
     * production attaches one; the second has no handle to attach one to, which is what stops it
     * hearing the first owner's departures through a lost pump of its own.
     */
    @Test
    fun aSecondOwnerOfOneRuntimeIsRefusedBeforeStartReturns() = withRuntime { factory, runtime ->
        val first = assertNotNull(mc_browser_start(runtime, Probe.foundIgnored))
        mc_browser_set_peer_lost_callback(first, Probe.lostIgnored)
        val delegate = assertNotNull(factory.activeBrowserDelegate, "a session must hold the browser before start returns")

        val second = mc_browser_start(runtime, Probe.foundIgnored)
        val delegateAfterRefusal = factory.activeBrowserDelegate
        val uncaughtAfterRefusal = uncaughtSoFar()

        val atReturn = stop(factory, first)
        assertAll(
            { assertNull(second, "a second owner must be refused before start returns") },
            { assertEquals(emptyList(), uncaughtAfterRefusal, "the refused start must not reach the unhandled route") },
            { assertSame(delegate, delegateAfterRefusal, "the first owner's browser survives the refused start") },
            { assertNull(atReturn.delegate, "the first owner's stop releases the browser") },
        )
    }

    /**
     * What a refusal must not cost. The JVM half of this harm is `clearVisiblePeers` on the refused
     * browser's teardown, which `MultipeerSharedBrowseTest` pins; this is the native half, both of the
     * first owner's feeds still delivering afterwards.
     */
    @Test
    fun theFirstOwnerKeepsDeliveringWhenASecondIsRefused() = withRuntime { factory, runtime ->
        val first = assertNotNull(mc_browser_start(runtime, Probe.foundCounted))
        mc_browser_set_peer_lost_callback(first, Probe.lostCounted)
        val delegate = awaitDelegate(factory)
        await("the lost collector subscribes") { factory.lostPeerHandles.subscriptionCount.value > 0 }

        val second = mc_browser_start(runtime, Probe.foundIgnored)

        delegate.deliverFound("kept#12345678")
        await("the first owner's found feed delivers") { Probe.counted.value == 1 }
        delegate.deliverLost("kept#12345678")
        await("the first owner's lost feed delivers") { Probe.lostCount.value == 1 }
        stop(factory, first)
        assertAll(
            { assertNull(second, "the rig must have been refused") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /** What held at the moment `mc_browser_stop` returned, read on the stopping thread. */
    private class StopWitness(val exited: Int, val delegate: MCNearbyServiceBrowserDelegateProtocol?)

    /** `mc_browser_stop`, bounded from outside. See the class KDoc for why it is not called directly. */
    private fun stop(factory: MultipeerPeerLinkFactory, handle: COpaquePointer): StopWitness {
        val returned = stopper.execute(TransferMode.SAFE, { factory to handle }) { (stopped, session) ->
            mc_browser_stop(session)
            StopWitness(Probe.exited.value, stopped.activeBrowserDelegate)
        }
        await("mc_browser_stop returns") { returned.state != FutureState.SCHEDULED }
        return returned.result
    }

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
    val lostCount = atomic(0)
    val release = atomic(false)

    val foundHeld = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? -> hold() }
    val lostHeld = staticCFunction { _: CPointer<ByteVar>? -> hold() }
    val foundIgnored = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? -> }
    val lostIgnored = staticCFunction { _: CPointer<ByteVar>? -> }
    val foundCounted = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? ->
        counted.incrementAndGet()
        Unit
    }
    val lostCounted = staticCFunction { _: CPointer<ByteVar>? ->
        lostCount.incrementAndGet()
        Unit
    }

    fun reset() {
        entered.value = 0
        exited.value = 0
        counted.value = 0
        lostCount.value = 0
        release.value = false
    }

    private fun hold() {
        entered.incrementAndGet()
        while (!release.value) usleep(100u)
        usleep(HOLD_MICROS)
        exited.incrementAndGet()
    }
}
