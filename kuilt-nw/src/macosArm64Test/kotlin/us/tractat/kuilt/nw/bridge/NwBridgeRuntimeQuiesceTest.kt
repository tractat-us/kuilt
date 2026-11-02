package us.tractat.kuilt.nw.bridge

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.job
import platform.posix.usleep
import us.tractat.kuilt.nw.NwConnectionId
import us.tractat.kuilt.nw.NwPsk
import us.tractat.kuilt.nw.RealNwApi
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
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The lifetime half of `nw_runtime_destroy`, against the real export, a real [NwBridgeRuntime] and a real
 * [RealNwApi]. Connections are the binding's own inert test entries, so its real close and viability paths
 * publish with no socket and no GCD callback to outlive a test.
 *
 * The rig is `:kuilt-multipeer`'s `BridgeBrowserQuiesceTest`: a window forced inside a `staticCFunction`,
 * a wedge bounded on a [Worker] into a named red, and an unhandled-exception hook that records what would
 * abort a shipped process. These callbacks have no JNA trampoline, so what is proven is the premise the
 * JVM relies on: once the destroy returns, no callback of that runtime is running or will run.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ObsoleteWorkersApi::class)
class NwBridgeRuntimeQuiesceTest {
    private val uncaughtLock = reentrantLock()
    private val uncaught = mutableListOf<Throwable>()
    private var previousHook: ReportUnhandledExceptionHook? = null
    private val caller = Worker.start(name = "nw-quiesce-caller")

    private fun uncaughtSoFar(): List<Throwable> = uncaughtLock.withLock { uncaught.toList() }

    @BeforeTest
    fun recordInsteadOfAborting() {
        NwProbe.reset()
        previousHook = setUnhandledExceptionHook { failure -> uncaughtLock.withLock { uncaught += failure } }
    }

    @AfterTest
    fun restoreTheHook() {
        setUnhandledExceptionHook(previousHook)
        // Not awaited: after a wedge the worker never gets as far as terminating.
        caller.requestTermination(processScheduledJobs = false)
    }

    @Test
    fun destroyWaitsForAConnectionClosedCallbackAlreadyInFlight() = withRuntime { api, handle, runtime ->
        runtime.setConnectionClosedCallback(NwProbe.twoStringsHeld)
        val id = api.registerInertConnectionForTest(endpoint = null)
        api.driveCloseForTest(id, failed = false)
        assertDestroyWaitsForTheHeldCallback(handle)
    }

    @Test
    fun destroyWaitsForAClosedStateCallbackAlreadyInFlight() = withRuntime { api, handle, runtime ->
        runtime.setConnectionClosedStateCallback(NwProbe.twoStringsHeld)
        val id = api.registerInertConnectionForTest(endpoint = null)
        api.driveCloseForTest(id, failed = false)
        assertDestroyWaitsForTheHeldCallback(handle)
    }

    @Test
    fun destroyWaitsForAViabilityCallbackAlreadyInFlight() = withRuntime { api, handle, runtime ->
        runtime.setConnectionViabilityCallback(NwProbe.viabilityHeld)
        api.driveSetViabilityForTest(api.registerInertConnectionForTest(endpoint = null), viable = true)
        assertDestroyWaitsForTheHeldCallback(handle)
    }

    /** What a join cannot do: a forwarder waiting for itself never returns, and this callback is that forwarder. */
    @Test
    fun destroyFromInsideACallbackOfThatRuntimeReturns() = withRuntime { api, handle, runtime ->
        NwProbe.runtime.value = handle
        runtime.setConnectionViabilityCallback(NwProbe.viabilityDestroys)
        api.driveSetViabilityForTest(api.registerInertConnectionForTest(endpoint = null), viable = true)

        await("nw_runtime_destroy returns inside the callback") { NwProbe.destroyedInside.value == 1 }

        assertEquals(emptyList(), uncaughtSoFar())
    }

    /**
     * A destroy from inside one callback gives up only on waiting for that callback. Every other forwarder
     * can be inside its own, and the guarantee still covers it.
     */
    @Test
    fun destroyFromInsideACallbackStillWaitsForTheOtherForwarders() = withRuntime { api, handle, runtime ->
        NwProbe.runtime.value = handle
        runtime.setConnectionClosedCallback(NwProbe.twoStringsHeld)
        runtime.setConnectionViabilityCallback(NwProbe.viabilityDestroys)
        api.driveCloseForTest(api.registerInertConnectionForTest(endpoint = null), failed = false)
        await("the closed callback is entered") { NwProbe.entered.value == 1 }
        val exitedBeforeDestroy = NwProbe.exited.value

        NwProbe.release.value = true
        api.driveSetViabilityForTest(api.registerInertConnectionForTest(endpoint = null), viable = true)
        await("nw_runtime_destroy returns inside the callback") { NwProbe.destroyedInside.value == 1 }

        await("the held callback finishes before cleanup") { NwProbe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeDestroy, "the rig must hold the callback across the destroy") },
            {
                assertEquals(
                    1,
                    NwProbe.exitedWhenDestroyedInside.value,
                    "a destroy from inside one callback must still wait for the other forwarders'",
                )
            },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /**
     * `bytesReceived` refuses a second collector by throwing from the flow. Under a `SupervisorJob` that is
     * the Kotlin/Native abort route. The hook turns the abort into a list entry, and the forwarder count
     * shows the second one really ended, so the rig cannot pass by never having collected.
     */
    @Test
    fun aRefusedSecondBytesForwarderDoesNotReachTheUnhandledExceptionRoute() = withRuntime { _, handle, runtime ->
        runtime.setBytesReceivedCallback(NwProbe.bytesIgnored)
        runtime.setBytesReceivedCallback(NwProbe.bytesIgnored)

        await("the second forwarder ends") { runtime.scope.coroutineContext.job.children.count() == 1 }

        val uncaughtAtEnd = uncaughtSoFar()
        destroy(handle)
        assertEquals(emptyList(), uncaughtAtEnd, "a refused collector must end its forwarder, not the process")
    }

    private fun assertDestroyWaitsForTheHeldCallback(handle: COpaquePointer) {
        await("the callback is entered") { NwProbe.entered.value == 1 }
        val exitedBeforeDestroy = NwProbe.exited.value

        NwProbe.release.value = true
        val exitedAtReturn = destroy(handle)

        await("the held callback finishes before cleanup") { NwProbe.exited.value == 1 }
        assertAll(
            { assertEquals(0, exitedBeforeDestroy, "the rig must hold the callback across the destroy") },
            { assertEquals(1, exitedAtReturn, "a callback in flight must finish before destroy returns") },
            { assertEquals(emptyList(), uncaughtSoFar()) },
        )
    }

    /** `nw_runtime_destroy`, bounded from outside. Returns the exit count read on the destroying thread. */
    private fun destroy(handle: COpaquePointer): Int {
        val returned = caller.execute(TransferMode.SAFE, { handle }) { runtime ->
            nw_runtime_destroy(runtime)
            NwProbe.exited.value
        }
        await("nw_runtime_destroy returns") { returned.state != FutureState.SCHEDULED }
        return returned.result
    }

    private fun withRuntime(block: (RealNwApi, COpaquePointer, NwBridgeRuntime) -> Unit) {
        val api = RealNwApi(NwPsk.derive("quiesce-secret", "_kuilt._tcp"))
        val runtime = NwBridgeRuntime(api)
        block(api, StableRef.create(runtime).asCPointer(), runtime)
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + AWAIT_BOUND
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting until $what; uncaught=${uncaughtSoFar()}")
            usleep(POLL_MICROS)
        }
    }

    private companion object {
        const val POLL_MICROS: UInt = 500u
        val AWAIT_BOUND = 60.seconds
    }
}

private const val NW_HOLD_MICROS: UInt = 200_000u

/** Global because a `staticCFunction` can capture nothing. */
@OptIn(ExperimentalForeignApi::class)
private object NwProbe {
    val entered = atomic(0)
    val exited = atomic(0)
    val destroyedInside = atomic(0)
    val exitedWhenDestroyedInside = atomic(-1)
    val release = atomic(false)
    val runtime = atomic<COpaquePointer?>(null)

    val twoStringsHeld = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>? -> hold() }
    val viabilityHeld = staticCFunction { _: CPointer<ByteVar>?, _: Int -> hold() }
    val bytesIgnored = staticCFunction { _: CPointer<ByteVar>?, _: CPointer<ByteVar>?, _: Int -> }

    val viabilityDestroys = staticCFunction { _: CPointer<ByteVar>?, _: Int ->
        nw_runtime_destroy(runtime.value)
        exitedWhenDestroyedInside.value = exited.value
        destroyedInside.value = 1
    }

    fun reset() {
        entered.value = 0
        exited.value = 0
        destroyedInside.value = 0
        exitedWhenDestroyedInside.value = -1
        release.value = false
        runtime.value = null
    }

    private fun hold() {
        entered.incrementAndGet()
        while (!release.value) usleep(100u)
        usleep(NW_HOLD_MICROS)
        exited.incrementAndGet()
    }
}
