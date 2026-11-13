@file:OptIn(TestOnlyKeyScheme::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.identity.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.identity.AdmissionProof
import us.tractat.kuilt.identity.Challenge
import us.tractat.kuilt.identity.DeviceKey
import us.tractat.kuilt.identity.Grant
import us.tractat.kuilt.identity.GrantScope
import us.tractat.kuilt.identity.GrantTrust
import us.tractat.kuilt.identity.GrantVerdict
import us.tractat.kuilt.identity.InMemoryDeviceKeyStore
import us.tractat.kuilt.identity.KeyScheme
import us.tractat.kuilt.identity.RevocationList
import us.tractat.kuilt.test.assertAll
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The fake must refuse what a room test asserts a room refuses, or those tests pass for nothing; and
 * a room on the real scheme must never take a fake signature. Each refusal test first checks that the
 * same signature verifies unaltered, so a scheme that refuses everything cannot pass it.
 */
class FakeKeySchemeTest {
    private val scheme = FakeKeyScheme(Random(3280))
    private val message = "kuilt/test-message".encodeToByteArray()

    @Test
    fun signatureVerifiesUnderItsOwnKeyAndMessage() = runTest {
        val key = scheme.generate()
        val signature = key.sign(message)
        val verifies = scheme.verify(key.id, message, signature)
        val secondVerifies = scheme.verify(key.id, message, key.sign(message))

        assertAll(
            { assertEquals(64, signature.size, "a statement's signature is 64 bytes, and the fake's must split like one") },
            { assertTrue(verifies) },
            { assertTrue(secondVerifies, "signing twice gives a signature that also verifies") },
        )
    }

    @Test
    fun refusesASignatureUnderAnotherKey() = runTest {
        val signer = scheme.generate()
        val other = scheme.generate()
        val signature = signer.sign(message)
        val underSigner = scheme.verify(signer.id, message, signature)
        val underOther = scheme.verify(other.id, message, signature)

        assertAll(
            { assertTrue(underSigner, "precondition: it verifies under the signer") },
            { assertNotEquals(signer.id, other.id, "precondition: two keys, not one") },
            { assertFalse(underOther) },
        )
    }

    @Test
    fun refusesASignatureOverAnotherMessage() = runTest {
        val key = scheme.generate()
        val signature = key.sign(message)
        val other = message.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val overSigned = scheme.verify(key.id, message, signature)
        val overOther = scheme.verify(key.id, other, signature)
        val overLonger = scheme.verify(key.id, message + 0.toByte(), signature)

        assertAll(
            { assertTrue(overSigned, "precondition: it verifies over the signed message") },
            { assertFalse(overOther) },
            { assertFalse(overLonger, "one byte appended") },
        )
    }

    @Test
    fun refusesASignatureWithAnyByteChanged() = runTest {
        val key = scheme.generate()
        val signature = key.sign(message)
        val accepted = signature.indices.filter { index ->
            val tampered = signature.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            scheme.verify(key.id, message, tampered)
        }
        val untampered = scheme.verify(key.id, message, signature)
        val short = scheme.verify(key.id, message, signature.copyOf(63))

        assertAll(
            { assertTrue(untampered, "precondition: the untampered signature verifies") },
            { assertEquals(emptyList(), accepted, "byte positions whose flipped bit still verified") },
            { assertFalse(short, "a short signature") },
        )
    }

    @Test
    fun theRealSchemeRefusesAFakelySignedChallenge() = runTest {
        val host = scheme.generate()
        val challenge = challengeSignedBy(host)
        val inFake = challenge.isSignedByHost(scheme)
        val inReal = challenge.isSignedByHost(KeyScheme.P256)
        val byDefault = challenge.isSignedByHost()

        assertAll(
            { assertTrue(inFake, "precondition: it verifies in the fake scheme") },
            { assertFalse(inReal) },
            { assertFalse(byDefault, "the no-argument check is the real scheme") },
        )
    }

    @Test
    fun theFakeSchemeRefusesARealSignature() = runTest {
        val host = DeviceKey.generate()
        val challenge = challengeSignedBy(host)
        val inReal = challenge.isSignedByHost()
        val inFake = challenge.isSignedByHost(scheme)

        assertAll(
            { assertTrue(inReal, "precondition: it verifies in the real scheme") },
            { assertFalse(inFake) },
        )
    }

    @Test
    fun aProofChecksAgainstTheJoinerKeyInTheRoomsScheme() = runTest {
        val host = scheme.generate()
        val joiner = scheme.generate()
        val proof = AdmissionProof.sign(joiner, ROOM, host.id, "host", "joiner", deviceId = null, hostNonce = NONCE_B)
        val byJoiner = proof.isSignedBy(joiner.id, scheme)
        val byHost = proof.isSignedBy(host.id, scheme)
        val inReal = proof.isSignedBy(joiner.id)

        assertAll(
            { assertTrue(byJoiner) },
            { assertFalse(byHost, "under another key") },
            { assertFalse(inReal, "in the real scheme") },
        )
    }

    @Test
    fun aGrantIsValidOnlyToATrustBuiltOnTheFakeScheme() = runTest {
        val host = scheme.generate()
        val joiner = scheme.generate()
        val grant = Grant.issue(host, joiner.id, GrantScope.Room(ROOM), issuedAt = 0, notAfter = Grant.NO_EXPIRY)
        val revocations = RevocationList.inMemory()
        val inFake = GrantTrust(host.id, revocations, scheme).verify(grant, joiner.id, ROOM, now = 1)
        val inReal = GrantTrust(host.id, revocations).verify(grant, joiner.id, ROOM, now = 1)

        assertAll(
            { assertEquals(GrantVerdict.Valid, inFake) },
            { assertEquals(GrantVerdict.BadSignature, inReal, "a trust on the real scheme") },
        )
    }

    @Test
    fun aFakeStoreAnswersItsSchemeAndAProductionStoreAnswersP256() = runTest {
        val fake = fakeKeyStore(1, scheme)
        val key = fake.deviceKey()
        val again = fake.deviceKey()
        val verifies = fake.keyScheme.verify(key.id, message, key.sign(message))
        val otherDevice = fakeKeyStore(2, scheme).deviceKey()

        assertAll(
            { assertSame(scheme, fake.keyScheme) },
            { assertSame(KeyScheme.P256, InMemoryDeviceKeyStore().keyScheme) },
            { assertSame(key, again, "one store, one key") },
            { assertTrue(verifies) },
            { assertNotEquals(key.id, otherDevice.id, "two stores are two devices") },
        )
    }

    /**
     * A store's index is its identity: the same index is the same device in every run, and two indices
     * are two devices. The default schemes are distinct too, seeded from the index, so a test that
     * makes extra keys through a store's scheme replays as well.
     */
    @Test
    fun aStoreIndexFixesItsKeyAndTellsDevicesApart() = runTest {
        val one = fakeKeyStore(1).deviceKey()
        val oneAgain = fakeKeyStore(1).deviceKey()
        val two = fakeKeyStore(2).deviceKey()
        val oneOnSharedScheme = fakeKeyStore(1, scheme).deviceKey()
        val extraOne = fakeKeyStore(1).keyScheme.generate()
        val extraOneAgain = fakeKeyStore(1).keyScheme.generate()
        val extraTwo = fakeKeyStore(2).keyScheme.generate()

        assertAll(
            { assertEquals(fakeDidKey(1), one.id, "a store's key is fakeDeviceKey(index)") },
            { assertEquals(one.id, oneAgain.id, "the same index is the same key") },
            { assertNotEquals(one.id, two.id, "two indices are two keys") },
            { assertEquals(one.id, oneOnSharedScheme.id, "the scheme passed does not change the key") },
            { assertEquals(extraOne.id, extraOneAgain.id, "the default scheme's draws repeat for one index") },
            { assertNotEquals(extraOne.id, extraTwo.id, "and differ between indices") },
        )
    }

    @Test
    fun aSeededSchemeMakesTheSameKeysEveryRun() = runTest {
        assertEquals(FakeKeyScheme(Random(7)).generate().id, FakeKeyScheme(Random(7)).generate().id)
    }

    @Test
    fun parseChecksSyntaxOnly() = runTest {
        val key = scheme.generate()

        assertEquals(key.id, scheme.parse(key.id.value))
        assertFailsWith<IllegalArgumentException> { scheme.parse("did:key:not-a-key") }
    }

    /**
     * The point of the scheme: nothing in it waits. Each step runs in a coroutine started bare, with no
     * dispatcher, so if any of them suspended the block would not have finished when `startCoroutine`
     * returned. On wasmJs the real scheme fails this, since each of these steps is a WebCrypto promise.
     */
    @Test
    fun noStepEverSuspends() {
        var finished = false
        var failure: Throwable? = null
        suspend {
            val host = scheme.generate()
            val joiner = scheme.generate()
            val challenge = challengeSignedBy(host)
            check(challenge.isSignedByHost(scheme))
            val proof = AdmissionProof.sign(joiner, ROOM, host.id, "host", "joiner", "device", NONCE_B)
            check(proof.isSignedBy(joiner.id, scheme))
            val grant = Grant.issue(host, joiner.id, GrantScope.AnyRoomOfIssuer, issuedAt = 0, notAfter = 10)
            check(GrantTrust(host.id, RevocationList.inMemory(), scheme).verify(grant, joiner.id, ROOM, now = 1) == GrantVerdict.Valid)
            check(scheme.parse(host.id.value) == host.id)
            val store = fakeKeyStore(9, scheme)
            check(store.deviceKey() === store.deviceKey())
            check(scheme.verify(fakeDidKey(4), message, fakeDeviceKey(4).sign(message)))
        }.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
            failure = result.exceptionOrNull()
            finished = true
        })

        assertAll(
            { assertTrue(finished, "a step suspended: the fake scheme waited on something") },
            { assertEquals(null, failure, "a step failed") },
        )
    }

    @Test
    fun fixedKeysAreStableDistinctAndVerifyInAnyFakeScheme() = runTest {
        val key = fakeDeviceKey(1)
        val signature = key.sign(message)
        val inThisScheme = scheme.verify(fakeDidKey(1), message, signature)
        val inAnotherScheme = FakeKeyScheme(Random(2)).verify(key.id, message, signature)
        val underKeyTwo = scheme.verify(fakeDidKey(2), message, signature)

        assertAll(
            { assertEquals(fakeDidKey(1), key.id) },
            { assertEquals(fakeDidKey(1), scheme.key(1).id, "a scheme's key(n) is fakeDeviceKey(n)") },
            { assertNotEquals(fakeDidKey(1), fakeDidKey(2)) },
            { assertTrue(inThisScheme) },
            { assertTrue(inAnotherScheme) },
            { assertFalse(underKeyTwo) },
        )
    }

    /** Pinned so the fixed keys are the same on every target: a test on one may print a key a test on another expects. */
    @Test
    fun fixedKeyZeroIsTheSameEverywhere() {
        assertEquals(FIXED_KEY_ZERO, fakeDidKey(0).value)
    }

    @Test
    fun aSuspendingHookHoldsTheOperationUntilReleased() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<FakeKeyOperation>()
        val gated = FakeKeyScheme(Random(1)) { operation ->
            seen += operation
            if (operation is FakeKeyOperation.Verify) gate.await()
        }
        val key = gated.key(7)
        val signature = key.sign(message)
        val verdict = async { gated.verify(key.id, message, signature) }
        runCurrent()
        val heldWhileGated = !verdict.isCompleted
        gate.complete(Unit)
        val verified = verdict.await()

        assertAll(
            { assertTrue(heldWhileGated, "the verify waited on the gate") },
            { assertTrue(verified) },
            { assertEquals(listOf("Sign", "Verify"), seen.map { it::class.simpleName }) },
        )
    }

    @Test
    fun aThrowingHookEscapesFromTheStoreAndFromAStatementCheck() = runTest {
        val failing = FakeKeyScheme(Random(1)) { operation ->
            if (operation !is FakeKeyOperation.Sign) throw IllegalStateException("provider down: $operation")
        }
        val challenge = challengeSignedBy(failing.key(3))

        assertFailsWith<IllegalStateException> { fakeKeyStore(0, failing).deviceKey() }
        assertFailsWith<IllegalStateException> { challenge.isSignedByHost(failing) }
        assertFailsWith<IllegalStateException> { failing.parse(fakeDidKey(0).value) }
    }

    private suspend fun challengeSignedBy(host: DeviceKey): Challenge =
        Challenge.sign(host, ROOM, "host", "joiner", joinerNonce = NONCE_A, hostNonce = NONCE_B)

    private companion object {
        const val ROOM = "room-3280"
        val NONCE_A = ByteArray(Challenge.NONCE_SIZE) { 1 }
        val NONCE_B = ByteArray(Challenge.NONCE_SIZE) { 2 }
        const val FIXED_KEY_ZERO = "did:key:zDnaeXhvSC6Vo7tUVhkmLV6sfZpLLZSH9MsDtXBAc5cvUy4fY"

    }
}
