@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    us.tractat.kuilt.identity.IdentityInternalApi::class,
    us.tractat.kuilt.identity.test.TestOnlyKeyScheme::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKeyStore
import us.tractat.kuilt.identity.test.fakeKeyStore
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Room tests that do key work still run on virtual time, on every target, when their devices' stores
 * are fake (#3280). The key work is what an admitting room does from Task 1.4 on: get the device key,
 * sign a challenge, check it in the store's scheme.
 *
 * With `InMemoryDeviceKeyStore()` in place of [store], both tests are red on wasmJs, where each of
 * those steps is a WebCrypto promise, and green on JVM and native, which is why the measurement had to
 * be taken on wasmJs. They are red for two different reasons, one per test:
 *
 * - **Unconfined:** [aRoomTestThatSignsFirstStillDeliversItsFrame] runs on `UnconfinedTestDispatcher`.
 *   The test body resumes from the JS promise outside the scheduler, and from then on its eager-inline
 *   assumptions fail: measured, virtual time never moved (0 ms throughout) and the joiner's frame never
 *   reached the host's channel (`received` empty).
 * - **Fast-forward:** [keyWorkDoesNotAdvanceTheClock] runs on the default `StandardTestDispatcher`.
 *   While the body waits on the promise the scheduler has nothing else to run, so it skips ahead and
 *   fires a pending timer.
 *
 * So these are the wasmJs pins on the fake: if it ever waits on the provider, they go red there.
 */
class FakeKeyVirtualTimeTest {
    private val store: DeviceKeyStore = fakeKeyStore(0)

    @Test
    fun aRoomTestThatSignsFirstStillDeliversItsFrame() = runTest(UnconfinedTestDispatcher()) {
        val key = store.deviceKey()
        val nonce = ByteArray(Challenge.NONCE_SIZE)
        val challenge = Challenge.sign(key, "room", "host", "joiner", joinerNonce = nonce, hostNonce = nonce)
        val signedAndChecked = challenge.isSignedByHost(store.keyScheme)

        val loom = InMemoryLoom()
        val factory = { device: Int ->
            SeamRoomFactory(loom, backgroundScope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = fakeKeyStore(device))
        }
        val host = factory(1).host(Pattern("Alice"))
        val joiner = factory(2).join(InMemoryTag("Bob"))
        host.roster.first { it.size == 1 }
        joiner.roster.first { it.isNotEmpty() }

        val received = mutableListOf<ByteArray>()
        val collector = launch { host.channel("data").incoming.collect { received += it.toByteArray() } }
        joiner.channel("data").broadcast("hello".encodeToByteArray())
        testScheduler.advanceUntilIdle()

        assertAll(
            { assertTrue(signedAndChecked, "precondition: the key work ran and its signature checked") },
            { assertEquals(listOf("hello"), received.map { it.decodeToString() }, "the joiner's frame reached the host's channel") },
        )

        collector.cancel()
        joiner.leave()
        host.leave()
    }

    @Test
    fun keyWorkDoesNotAdvanceTheClock() = runTest {
        val loom = InMemoryLoom()
        val factory = { device: Int ->
            SeamRoomFactory(loom, backgroundScope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }, deviceKeyStore = fakeKeyStore(device))
        }
        val host = factory(1).host(Pattern("Alice"))
        val joiner = factory(2).join(InMemoryTag("Bob"))
        host.roster.first { it.size == 1 }
        val admittedAt = testScheduler.currentTime
        var timerFired = false
        backgroundScope.launch {
            delay(1.seconds)
            timerFired = true
        }
        runCurrent()

        val key = store.deviceKey()
        val nonce = ByteArray(Challenge.NONCE_SIZE)
        val challenge = Challenge.sign(key, "room", "host", "joiner", joinerNonce = nonce, hostNonce = nonce)
        val signedAndChecked = challenge.isSignedByHost(store.keyScheme)
        val afterKeyWork = testScheduler.currentTime

        assertAll(
            { assertTrue(signedAndChecked, "precondition: the key work ran and its signature checked") },
            { assertEquals(admittedAt, afterKeyWork, "virtual time moved while the key work ran") },
            { assertFalse(timerFired, "a timer due 1 s later fired during the key work") },
        )

        joiner.leave()
        host.leave()
    }
}
