package us.tractat.kuilt.nw

import com.sun.jna.Pointer
import org.junit.Assume.assumeTrue
import us.tractat.kuilt.test.assertAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nw_runtime_destroy` against `libkuilt.dylib`, with real JNA callbacks on a real `127.0.0.1` TLS-PSK
 * link. `NwBridgeRuntimeQuiesceTest` proves the native half with `staticCFunction`s, which have no JNA
 * trampoline. This is the part that needs one: the call from inside a callback leaves Kotlin/Native
 * through JNA, runs on the JVM, and comes back on one OS thread, which is what the reentrant lock rests on.
 *
 * Like the rest of this module's real-dylib tests it needs macOS, and no-ops on Linux, so a green
 * `ci-required` says nothing about it.
 *
 * Real threads, because each wait is a JNA callback thread. The bounds turn a wedge into a named red.
 */
class NwBridgeDestroyRealDylibTest {
    private val lib: NwNativeLib? = NwNativeLib.load()
    private var rendezvous: Pointer? = null
    private var joiner: Pointer? = null

    // Strong references: JNA releases a callback's trampoline once its object is collected.
    private val callbacks = mutableListOf<Any>()

    @AfterTest
    fun destroyWhatTheTestLeft() {
        joiner?.let { lib?.nw_runtime_destroy(it) }
        rendezvous?.let { lib?.nw_loopback_rendezvous_destroy(it) }
    }

    /** A wedge here is the thread this callback runs on waiting for itself, in the real dylib. */
    @Test
    fun destroyFromInsideARealJnaCallbackReturns() {
        val native = loadedOrSkip()
        val destroyed = CountDownLatch(1)
        val firedAlready = AtomicInteger(0)
        val runtime = newHost(native)
        native.nw_set_connection_viability_callback(
            runtime,
            NwNativeLib.ViabilityCallback { _, _ ->
                if (firedAlready.getAndIncrement() == 0) {
                    native.nw_runtime_destroy(runtime)
                    destroyed.countDown()
                }
            }.also { callbacks += it },
        )

        connectHostToJoiner(native, runtime)

        assertTrue(destroyed.await(BOUND_SECONDS, TimeUnit.SECONDS), "the destroy must return inside the callback")
    }

    @Test
    fun destroyWaitsForARealJnaCallbackAlreadyInFlight() {
        val native = loadedOrSkip()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = AtomicInteger(0)
        val runtime = newHost(native)
        native.nw_set_connection_opened_callback(
            runtime,
            NwNativeLib.ConnectionOpenedCallback { _, _, _ ->
                entered.countDown()
                release.await(BOUND_SECONDS, TimeUnit.SECONDS)
                Thread.sleep(HOLD_MILLIS)
                exited.incrementAndGet()
            }.also { callbacks += it },
        )

        connectHostToJoiner(native, runtime)
        assertTrue(entered.await(BOUND_SECONDS, TimeUnit.SECONDS), "the rig must reach the callback")
        val exitedBeforeDestroy = exited.get()

        var exitedAtReturn = -1
        val destroyer = thread {
            // Released from this thread, so a destroyer that starts late cannot miss the hold.
            release.countDown()
            native.nw_runtime_destroy(runtime)
            exitedAtReturn = exited.get()
        }
        destroyer.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))

        assertAll(
            { assertEquals(0, exitedBeforeDestroy, "the rig must hold the callback across the destroy") },
            { assertEquals(false, destroyer.isAlive, "the destroy must return") },
            { assertEquals(1, exitedAtReturn, "a callback in flight must finish before destroy returns") },
        )
    }

    private fun loadedOrSkip(): NwNativeLib {
        assumeTrue("libkuilt.dylib is macOS-only; this test no-ops elsewhere.", lib != null)
        return checkNotNull(lib)
    }

    private fun newHost(native: NwNativeLib): Pointer {
        val key = NwPsk.derive(ROOM_KEY, SERVICE_TYPE)
        val shared = checkNotNull(native.nw_loopback_rendezvous_create())
        rendezvous = shared
        joiner = checkNotNull(native.nw_runtime_create_loopback(key.psk, key.psk.size, key.identity, key.identity.size, shared, 1))
        return checkNotNull(native.nw_runtime_create_loopback(key.psk, key.psk.size, key.identity, key.identity.size, shared, 0))
    }

    /** The joiner awaits the host's bound port, is told of one endpoint, and dials it. */
    private fun connectHostToJoiner(native: NwNativeLib, runtime: Pointer) {
        val dialler = checkNotNull(joiner)
        native.nw_set_endpoint_found_callback(
            dialler,
            NwNativeLib.EndpointFoundCallback { id, _ -> native.nw_connect(dialler, id) }.also { callbacks += it },
        )
        assertEquals(0, native.nw_start_listening(runtime, "host", SERVICE_TYPE), "nw_start_listening")
        assertEquals(0, native.nw_start_browsing(dialler, SERVICE_TYPE), "nw_start_browsing")
    }

    private companion object {
        const val ROOM_KEY = "destroy-secret"
        const val SERVICE_TYPE = "_kuilt._tcp"
        const val BOUND_SECONDS = 60L
        const val HOLD_MILLIS = 200L
    }
}
