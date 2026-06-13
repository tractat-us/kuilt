@file:Suppress("ForbiddenImport") // deliberate: GCD-thread/flowOn regression test — Dispatchers.Unconfined required to reproduce the callback-thread inline-resume scenario

package us.tractat.kuilt.multipeer

import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the Darwin GCD stack-overflow crash.
 *
 * ## Root cause
 *
 * On Kotlin/Native, `Dispatchers.Default` maps to Darwin GCD `default-qos`
 * (544 K stack + 16 K guard). `BridgeBrowser.kt` runs its browser-collect
 * coroutine on this dispatcher. When apple MC fires `foundPeer`, the K/N
 * collect lambda calls `peerFoundCb.invoke(...)` (inside `memScoped`), which
 * crosses the JNA boundary onto the same GCD thread.
 *
 * Inside that JNA callback, `trySend(ad)` fires into the JVM `callbackFlow`.
 * If the downstream collector uses `Dispatchers.Unconfined` (or if the K/N
 * coroutine runtime performs an **inline resume**), the entire collection chain
 * runs on the GCD thread. Those JVM frames stack on top of the already-deep
 * K/N frames and overflow the 544 K guard.
 *
 * ## Fix
 *
 * `.flowOn(Dispatchers.IO)` fuses the `callbackFlow` producer onto IO. The
 * `trySend` in the JNA callback places items into an IO-backed buffer. Even
 * with `Dispatchers.Unconfined` downstream, the first `emit` comes from the
 * IO thread, so the `collect { }` body runs on IO — not the GCD callback thread.
 *
 * ## Test
 *
 * Uses `Dispatchers.Unconfined` downstream to create a scenario where, without
 * `.flowOn(Dispatchers.IO)`, `trySend` from the callback thread ("fake-gcd-thread")
 * would inline-resume the collector and run `collect { }` on "fake-gcd-thread".
 *
 * With `.flowOn(Dispatchers.IO)`, items come through the IO boundary, so the
 * `collect { }` body starts on an IO-pool thread (not "fake-gcd-thread").
 *
 * Uses [FakeMultipeerNativeLib] — no real macOS MC bootstrap required.
 */
class MultipeerBrowserDispatcherTest {
    @Test
    fun `discoveries collector never runs on the JNA callback thread`() {
        val callbackThreadName = "fake-gcd-thread"
        val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, callbackThreadName) }

        val fakeLib = FakeMultipeerNativeLib()
        val factory =
            MultipeerPeerLinkFactory(
                displayName = "test-device",
                serviceType = "fireworks-mc",
                injectedLib = fakeLib,
                injectedRuntimeHandle = Pointer(0x42L),
            )
        val browser = MultipeerServiceBrowser(factory, libLoader = { fakeLib })

        val collectionThread = AtomicReference<String>()
        val itemReceived = CountDownLatch(1)

        runBlocking {
            // Dispatchers.Unconfined means the first collect { } emission runs
            // on whatever thread delivers the item. Without .flowOn(Dispatchers.IO),
            // trySend() from "fake-gcd-thread" would inline-resume this coroutine
            // and run collect { } on "fake-gcd-thread". With the fix, the item
            // arrives from an IO thread, so collect { } starts on IO.
            val collectJob =
                launch(Dispatchers.Unconfined) {
                    try {
                        withTimeout(3_000) {
                            browser.discoveries().collect { _ ->
                                collectionThread.set(Thread.currentThread().name)
                                itemReceived.countDown()
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        // expected
                    }
                }

            // Let the producer start (on IO with .flowOn) and block on awaitClose.
            Thread.sleep(300)

            // Simulate Darwin GCD firing a peer-found event.
            callbackExecutor.submit {
                fakeLib.fireFoundPeer(handle = "peer-1", displayName = "iPhone Test")
            }

            val arrived = itemReceived.await(3, TimeUnit.SECONDS)
            collectJob.cancel()
            assertTrue(arrived, "No peer-found item arrived within 3 s")
        }

        callbackExecutor.shutdown()

        val thread = checkNotNull(collectionThread.get()) { "collection thread was never recorded" }

        // The collector must not have been inline-resumed on the callback thread.
        // Failure here means trySend() from "fake-gcd-thread" drove the collect { }
        // body directly — the JVM equivalent of the K/N GCD stack-overflow.
        assertFalse(
            thread.startsWith(callbackThreadName),
            "collect { } ran on the callback thread ('$thread'), reproducing the " +
                "GCD stack-overflow scenario. " +
                "Fix: .flowOn(Dispatchers.IO) in MultipeerServiceBrowser.jvm.kt discoveries().",
        )
    }
}
