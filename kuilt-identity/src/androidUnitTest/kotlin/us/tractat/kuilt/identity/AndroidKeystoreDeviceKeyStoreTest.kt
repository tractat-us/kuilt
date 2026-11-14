package us.tractat.kuilt.identity

import android.security.keystore.KeyPermanentlyInvalidatedException
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.slf4j.LoggerFactory
import us.tractat.kuilt.store.FileChannelDurableStore
import us.tractat.kuilt.test.assertAll
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.ProviderException
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Android Keystore store promises beyond the shared contract
 * ([AndroidKeystoreDeviceKeyStoreConformanceTest], which runs Android 13 and later only): the DER-to-raw
 * conversion, Decided 29's rule for which Keystore failure replaces the key on each Android version, the
 * failed-launch count below 13, the half-made and foreign entries a Keystore can hold, and the one-line
 * default's directory. Under Robolectric, so the Keystore exceptions are the framework's own classes.
 *
 * Like the conformance test, it runs over [FakeKeystore], never the real Keystore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidKeystoreDeviceKeyStoreTest {

    // ── DER → raw r ‖ s ─────────────────────────────────────────────────────

    /**
     * DER writes `r` and `s` in as few bytes as they need, plus `0x00` when the top bit is set, so they
     * arrive as 1 to 33 bytes. The raw form is always 32 + 32, left-padded: hand-built vectors with a
     * one-byte `r`, a 33-byte `s` (top bit set), and a 31-byte `r` whose padding is one zero byte.
     */
    @Test
    fun derToRawIsLeftPaddedAndFixedWidth() {
        val highS = byteArrayOf(0x80.toByte()) + ByteArray(31) { 0x11 }
        val oneByteR = derSequence(integer(byteArrayOf(0x01)), integer(byteArrayOf(0x00) + highS))
        val shortR = ByteArray(31) { 0x7F }
        val thirtyOneByteR = derSequence(integer(shortR), integer(ByteArray(32) { 0x22 }))

        val first = derSignatureToRaw(oneByteR)
        val second = derSignatureToRaw(thirtyOneByteR)

        assertAll(
            { assertContentEquals(ByteArray(31) + byteArrayOf(0x01) + highS, first, "a one-byte r is padded to 32, and s loses DER's sign byte") },
            { assertContentEquals(byteArrayOf(0x00) + shortR + ByteArray(32) { 0x22 }, second, "a 31-byte r gains one leading zero") },
            { assertEquals(64, first?.size, "always 64 bytes") },
        )
    }

    /** What is not an `ECDSA-Sig-Value` with two positive integers of at most 32 bytes is refused, not reshaped. */
    @Test
    fun derToRawRefusesWhatIsNotAP256Signature() {
        val fine = integer(byteArrayOf(0x05))
        assertAll(
            { assertNotNull(derSignatureToRaw(derSequence(fine, fine)), "control: a well-formed pair converts") },
            { assertNull(derSignatureToRaw(derSequence(integer(byteArrayOf(0x00)), fine)), "r = 0") },
            { assertNull(derSignatureToRaw(derSequence(fine, integer(byteArrayOf(0xFF.toByte())))), "a negative s") },
            { assertNull(derSignatureToRaw(derSequence(integer(byteArrayOf(0x01) + ByteArray(32)), fine)), "an r of 33 magnitude bytes") },
            { assertNull(derSignatureToRaw(byteArrayOf(0x30, 0x06, 0x02, 0x01)), "truncated") },
            { assertNull(derSignatureToRaw(byteArrayOf(1, 2, 3)), "not DER at all") },
        )
    }

    /**
     * JCA's own DER signatures, converted, verify under the key's name — and a flipped bit does not.
     * 64 signatures, so a short `r` or `s` is likely (not certain) among them; the vectors above are
     * the deterministic half.
     */
    @Test
    fun jcaSignaturesConvertedToRawVerify() = runTest {
        val pair = FakeKeystore.newPair()
        val name = independentNameOf(pair.public)
        val messages = List(64) { "message $it".encodeToByteArray() }
        val raws = messages.map { message ->
            val der = Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(pair.private)
                update(message)
                sign()
            }
            checkNotNull(derSignatureToRaw(der)) { "JCA's DER converts" }
        }
        val verified = messages.zip(raws).count { (message, raw) -> name.verify(message, raw) }
        val flipped = raws[0].copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        val flippedVerifies = name.verify(messages[0], flipped)
        assertAll(
            { assertEquals(messages.size, verified, "every converted signature verifies") },
            { assertFalse(flippedVerifies, "negative control: a flipped bit does not") },
        )
    }

    /** The store names a Keystore key exactly as the platform provider names its public half. */
    @Test
    fun nameIsTheCertificatesPublicHalf() = runTest {
        val pairs = List(16) { FakeKeystore.newPair() }
        var mismatches = 0
        for (pair in pairs) if (nameOf(pair.public) != independentNameOf(pair.public)) mismatches++
        assertAll(
            { assertEquals(0, mismatches, "the store's name agrees with the provider's, for both y parities") },
            { assertNull(nameOf(FakeKeystore.newPair("secp384r1").public), "a P-384 key has no P-256 name") },
        )
    }

    // ── Decided 29: whose failure it is ──────────────────────────────────────

    /**
     * On Android 13 and later, the failures the Keystore calls its own keep the key — including
     * `SYSTEM_ERROR`, which `isTransientFailure()` calls *not* transient, the reason that predicate alone
     * is not the rule — and `LOCKED`, which waits for the user.
     */
    @Test
    fun onAndroid13WhatTheKeystoreCallsItsOwnTroubleKeepsTheKey() {
        val systemError = AospKeystore.keystoreException(AospKeystore.SYSTEM_ERROR)
        val locked = AospKeystore.keystoreException(AospKeystore.LOCKED)
        assertAll(
            { assertFalse(systemError.isTransientFailure, "precondition: AOSP calls SYSTEM_ERROR not transient") },
            { assertTrue(AospKeystore.keystoreException(AospKeystore.SECURE_HW_BUSY).isTransientFailure, "precondition: and SECURE_HW_BUSY transient") },
            { assertTrue(locked.requiresUserAuthentication() && !locked.isSystemError && !locked.isTransientFailure, "precondition: LOCKED only waits for the user") },
            { assertIs<FailureVerdict.Keep>(judge(thrown(AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR)), 33), "SYSTEM_ERROR while loading") },
            { assertIs<FailureVerdict.Keep>(judge(thrown(AospKeystore.signFailure(AospKeystore.SECURE_HW_BUSY)), 33), "SECURE_HW_BUSY while signing") },
            { assertIs<FailureVerdict.Keep>(judge(thrown(AospKeystore.loadFailure(AospKeystore.LOCKED)), 33), "LOCKED while loading") },
            {
                assertIs<FailureVerdict.Keep>(
                    judge(thrown(AospKeystore.signFailure(AospKeystore.UNKNOWN_ERROR)), 33),
                    "KM_ERROR_UNKNOWN_ERROR: code 10, flagged a system error",
                )
            },
        )
    }

    /** On Android 13 and later, what the Keystore blames on the key replaces it — each code read by name. */
    @Test
    fun onAndroid13WhatTheKeystoreBlamesOnTheKeyReplacesIt() {
        assertAll(
            { assertIs<FailureVerdict.Replace>(judge(thrown(AospKeystore.loadFailure(AospKeystore.KEY_NOT_FOUND)), 33), "KEY_NOT_FOUND (code 6)") },
            {
                assertIs<FailureVerdict.Replace>(
                    judge(thrown(AospKeystore.loadFailure(AospKeystore.KEY_PERMANENTLY_INVALIDATED)), 33),
                    "KEY_PERMANENTLY_INVALIDATED (code 6)",
                )
            },
            { assertIs<FailureVerdict.Replace>(judge(thrown(AospKeystore.signFailure(AospKeystore.VALUE_CORRUPTED)), 33), "VALUE_CORRUPTED (code 7)") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(UnrecoverableKeyException("no cause")), 33), "UnrecoverableKeyException, no Keystore cause") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(KeyPermanentlyInvalidatedException()), 33), "permanently invalidated") },
        )
    }

    /**
     * On Android 13 and later, what names neither the key nor the Keystore is of unknown cause — code 10
     * with no flag included: AOSP's KeyMint bucket holds `INVALID_KEY_BLOB` and passing TEE faults alike.
     */
    @Test
    fun onAndroid13WhatNothingExplainsIsOfUnknownCause() {
        val badBlob = AospKeystore.keystoreException(AospKeystore.INVALID_KEY_BLOB)
        assertAll(
            { assertEquals(10, badBlob.numericErrorCode, "precondition: INVALID_KEY_BLOB is public code 10") },
            { assertFalse(badBlob.isSystemError || badBlob.isTransientFailure, "precondition: with no flag") },
            { assertIs<FailureVerdict.Unknown>(judge(thrown(AospKeystore.signFailure(AospKeystore.INVALID_KEY_BLOB)), 33), "INVALID_KEY_BLOB") },
            { assertIs<FailureVerdict.Unknown>(judge(thrown(AospKeystore.loadFailure(UNMAPPED_CODE)), 33), "a code AOSP has no entry for") },
            { assertIs<FailureVerdict.Unknown>(judge(thrown(ProviderException("no cause")), 33), "a failure with no Keystore cause") },
            { assertIs<FailureVerdict.Unknown>(judge(KeystoreFailure.NoCertificate, 33), "a certificate that did not come back") },
        )
    }

    /** Below Android 13 nothing says whose failure it is: all are of unknown cause, but permanent invalidation. */
    @Test
    fun belowAndroid13EveryFailureIsOfUnknownCauseButPermanentInvalidation() {
        val failures = listOf(
            thrown(AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR)),
            thrown(AospKeystore.signFailure(AospKeystore.VALUE_CORRUPTED)),
            thrown(UnrecoverableKeyException("no cause")),
            thrown(ProviderException("no cause")),
            KeystoreFailure.NoCertificate,
            KeystoreFailure.NoKeyThoughOneWasMade,
        )
        val verdicts = listOf(26, 30, 32).flatMap { sdk -> failures.map { judge(it, sdk) } }
        assertAll(
            { assertTrue(verdicts.all { it is FailureVerdict.Unknown }, "every failure is of unknown cause below 33: $verdicts") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(KeyPermanentlyInvalidatedException()), 26), "permanent invalidation replaces at once") },
        )
    }

    // ── A failure of unknown cause: one hour ─────────────────────────────────

    /** A run of failures of unknown cause shorter than an hour throws and keeps the key. */
    @Test
    fun aRunUnderAnHourKeepsTheKey() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val first = failureOf { rig.launch().deviceKey() }
        val since = rig.failingSince()
        rig.clock += MINUTE * 59
        val later = failureOf { rig.launch().deviceKey() }
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(first is IllegalStateException && later is IllegalStateException, "both throw: $first, $later") },
            { assertEquals(T, since, "the first failure starts the run") },
            { assertEquals(1, rig.keystore.generated, "no key is made in 59 minutes") },
            { assertEquals(held, after, "and the key is the same key") },
        )
    }

    /** A failure of unknown cause once the run is an hour old replaces the key. */
    @Test
    fun aRunAnHourOldReplacesTheKey() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        failureOf { rig.launch().deviceKey() }
        rig.clock += HOUR
        val replaced = rig.launch(clearFaultAfterJudging = true).deviceKey().id
        val since = rig.failingSince()
        assertAll(
            { assertNotEquals(held, replaced, "an hour of failures replaces the key") },
            { assertEquals(2, rig.keystore.generated, "with one new key") },
            { assertNull(since, "and the run is cleared") },
        )
    }

    /** A successful read clears the run, so the hour is counted from the first failure after it. */
    @Test
    fun aSuccessfulReadClearsTheRun() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        val fault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        rig.keystore.loadFault = fault
        failureOf { rig.launch().deviceKey() }
        rig.clock += MINUTE * 30
        rig.keystore.loadFault = null
        rig.launch().deviceKey()
        val clearedBySuccess = rig.failingSince()
        rig.clock += MINUTE * 20
        rig.keystore.loadFault = fault
        failureOf { rig.launch().deviceKey() }
        val restarted = rig.failingSince()
        rig.clock += MINUTE * 40 // 90 minutes since the first failure, 40 since the run restarted
        val late = failureOf { rig.launch().deviceKey() }
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertNull(clearedBySuccess, "a successful read clears the run") },
            { assertEquals(T + MINUTE * 50, restarted, "the next failure starts a new one") },
            { assertTrue(late is IllegalStateException, "40 minutes into it, a failure throws: $late") },
            { assertEquals(held, after, "and the key is kept") },
        )
    }

    /** Failures from different processes — different store instances — are one run: the hour is the Keystore's, not a process's. */
    @Test
    fun failuresFromDifferentProcessesShareOneRun() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val ui = rig.launch()
        val sync = rig.launch()
        failureOf { ui.deviceKey() }
        rig.clock += MINUTE * 20
        failureOf { sync.deviceKey() }
        rig.clock += MINUTE * 20
        failureOf { ui.deviceKey() }
        val since = rig.failingSince()
        rig.clock += MINUTE * 20
        val replaced = rig.launch(clearFaultAfterJudging = true).deviceKey().id
        assertAll(
            { assertEquals(T, since, "three failures from two processes, one run, started by the first") },
            { assertNotEquals(held, replaced, "and an hour after it began the key is replaced") },
        )
    }

    /** A run that started in the future — the clock moved back — is read as starting now. */
    @Test
    fun aRunStartedInTheFutureIsReadAsNow() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.plantFailingSince(T + HOUR * 10)
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val first = failureOf { rig.launch().deviceKey() }
        val since = rig.failingSince()
        rig.clock += MINUTE * 59
        val later = failureOf { rig.launch().deviceKey() }
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(first is IllegalStateException && later is IllegalStateException, "both throw: $first, $later") },
            { assertEquals(T, since, "the future start is replaced by now") },
            { assertEquals(held, after, "and the key is kept") },
        )
    }

    /** On Android 13 and later a failure the Keystore calls its own never starts a run, however long it lasts. */
    @Test
    fun onAndroid13AKeystoreFailureNeverReplacesTheKey() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val failures = List(3) {
            failureOf { rig.launch().deviceKey() }.also { rig.clock += HOUR }
        }
        val since = rig.failingSince()
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(failures.all { it is IllegalStateException }, "three hours of failures throw: $failures") },
            { assertNull(since, "and start no run") },
            { assertEquals(held, after, "and the key is the same key") },
        )
    }

    /** On Android 13 and later code 10 with no flag — a bad blob or a passing TEE fault — waits the hour. */
    @Test
    fun onAndroid13AnUnflaggedKeyMintFailureWaitsTheHour() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.signFault = { AospKeystore.signFailure(AospKeystore.INVALID_KEY_BLOB) }
        val early = failureOf { rig.launch().deviceKey() }
        rig.clock += HOUR
        val replaced = rig.launch(clearSignFaultAfterJudging = true).deviceKey().id
        assertAll(
            { assertTrue(early is IllegalStateException, "the first failure throws: $early") },
            { assertNotEquals(held, replaced, "an hour later the key is replaced") },
        )
    }

    /** A Keystore that is busy while signing the self-test, on Android 13 and later, throws and keeps the key. */
    @Test
    fun onAndroid13ATransientSigningFailureThrowsAndKeepsTheKey() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.signFault = { AospKeystore.signFailure(AospKeystore.SECURE_HW_BUSY) }
        val failure = failureOf { rig.launch().deviceKey() }
        rig.keystore.signFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(failure is IllegalStateException, "throws: $failure") },
            { assertEquals(1, rig.keystore.generated, "makes no key") },
            { assertEquals(held, after, "and the key is the same key") },
        )
    }

    /**
     * Android 11 and earlier store a key and its certificate separately, so a crash between them leaves a
     * key with no certificate under the alias. A failing certificate read looks the same, so it waits the
     * hour like any failure of unknown cause, and then is replaced.
     */
    @Test
    fun belowAndroid12AHalfMadeKeyIsReplacedAfterAnHour() = runTest {
        val rig = Rig(sdk = 30)
        rig.keystore.plant(rig.alias, null, FakeKeystore.newPair().private)
        val early = failureOf { rig.launch().deviceKey() }
        val madeEarly = rig.keystore.generated
        rig.clock += HOUR
        val made = rig.launch().deviceKey().id
        val again = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(early is IllegalStateException, "the first launch throws: $early") },
            { assertEquals(0, madeEarly, "and makes no key") },
            { assertEquals(1, rig.keystore.generated, "an hour later one is made") },
            { assertEquals(made, again, "and kept") },
        )
    }

    /**
     * On Android 12 and later the Keystore cannot hold an EC key without its certificate, so a key that
     * comes back without one is a certificate read that failed: it throws and keeps the key, never
     * "half-made".
     */
    @Test
    fun onAndroid13AMissingCertificateIsAFailedReadNotAHalfMadeKey() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.certificateLost = true
        val failure = failureOf { rig.launch().deviceKey() }
        val since = rig.failingSince()
        rig.keystore.certificateLost = false
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(failure is IllegalStateException, "throws: $failure") },
            { assertEquals(1, rig.keystore.generated, "makes no key") },
            { assertEquals(T, since, "and starts a run") },
            { assertEquals(held, after, "and the key is the same key") },
        )
    }

    // ── The made-here marker (Android 11 and earlier) ────────────────────────

    /**
     * On Android 11 and earlier `getKey` answers "no key" when the Keystore fails. Where this store has
     * made a key, that is a failure of unknown cause, not a first launch; the outage ending brings the
     * same key back.
     */
    @Test
    fun onAndroid11NoKeyWhereOneWasMadeIsAFailureNotAFirstLaunch() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        val marked = rig.marked()
        rig.keystore.legacyOutage = true
        val failure = failureOf { rig.launch().deviceKey() }
        val since = rig.failingSince()
        rig.keystore.legacyOutage = false
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(marked, "precondition: making the key marked it") },
            { assertTrue(failure is IllegalStateException, "a launch in the outage throws: $failure") },
            { assertEquals(T, since, "and starts a run") },
            { assertEquals(1, rig.keystore.generated, "no key is made") },
            { assertEquals(held, after, "and the key is the same key once the Keystore answers") },
        )
    }

    /** "No key" with the marker for an hour replaces the key, as any failure of unknown cause does. */
    @Test
    fun onAndroid11NoKeyForAnHourReplacesIt() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.keystore.legacyOutage = true
        failureOf { rig.launch().deviceKey() }
        rig.clock += HOUR
        val replaced = rig.launch(clearOutageBeforeMaking = true).deviceKey().id
        val marked = rig.marked()
        assertAll(
            { assertNotEquals(held, replaced, "an hour later a new key is made") },
            { assertTrue(marked, "and marked") },
        )
    }

    /** No marker and no key is a first launch, on every version: the key is made at once. */
    @Test
    fun noKeyAndNoMarkerIsAFirstLaunch() = runTest {
        val made = listOf(26, 30, 33).map { sdk ->
            val rig = Rig(sdk)
            val before = rig.marked()
            rig.launch().deviceKey()
            !before && rig.keystore.generated == 1 && rig.marked()
        }
        assertEquals(listOf(true, true, true), made, "made at once and marked, at API 26, 30 and 33")
    }

    /**
     * On Android 12 and later "no key" means `KEY_NOT_FOUND` and nothing else, so a key deleted from under
     * the store is made again at once, marker or not.
     */
    @Test
    fun onAndroid12NoKeyIsMadeAtOnceWhateverTheMarker() = runTest {
        val rig = Rig(sdk = 31)
        val held = rig.launch().deviceKey().id
        rig.keystore.delete(rig.alias)
        val marked = rig.marked()
        val again = rig.launch().deviceKey().id
        val since = rig.failingSince()
        assertAll(
            { assertTrue(marked, "precondition: the marker is there") },
            { assertNotEquals(held, again, "a new key is made at once") },
            { assertNull(since, "and no run is started") },
        )
    }

    /**
     * The marker is written after the key, never before: a key that fails to be made leaves no marker,
     * so the next launch makes one at once rather than waiting out a "missing" key that never existed.
     */
    @Test
    fun aKeyThatFailedToBeMadeLeavesNoMarker() = runTest {
        val rig = Rig(sdk = 30)
        rig.keystore.generateFault = { ProviderException("test: generation failed") }
        val failure = failureOf { rig.launch().deviceKey() }
        val markedAfterFailure = rig.marked()
        val made = failureOf { rig.launch().deviceKey() }
        assertAll(
            { assertNotNull(failure, "precondition: the first launch failed to make a key") },
            { assertFalse(markedAfterFailure, "and left no marker") },
            { assertNull(made, "so the next launch makes one at once: $made") },
            { assertEquals(1, rig.keystore.generated, "one key") },
        )
    }

    /**
     * A key with no marker beside it — a crash between making it and marking it — is marked by the next
     * launch that reads it, so a later outage is a failure rather than a first launch.
     */
    @Test
    fun aLaunchThatReadsAnUnmarkedKeyMarksIt() = runTest {
        val rig = Rig(sdk = 30)
        val pair = FakeKeystore.newPair()
        rig.keystore.plant(rig.alias, pair.public, pair.private)
        val unmarkedBefore = !rig.marked()
        val held = rig.launch().deviceKey().id
        val markedAfter = rig.marked()
        rig.keystore.legacyOutage = true
        val failure = failureOf { rig.launch().deviceKey() }
        val planted = independentNameOf(pair.public)
        assertAll(
            { assertTrue(unmarkedBefore, "precondition: no marker") },
            { assertEquals(planted, held, "the planted key is read") },
            { assertTrue(markedAfter, "and marked") },
            { assertTrue(failure is IllegalStateException, "so an outage after throws rather than making a key: $failure") },
            { assertEquals(0, rig.keystore.generated, "no key is made") },
        )
    }

    // ── Other entries ────────────────────────────────────────────────────────

    /** A certificate with no key is what `getKey` answers `null` for: no key, so one is made at once. */
    @Test
    fun aCertificateWithoutAKeyIsNoKey() = runTest {
        val rig = Rig(sdk = 33)
        rig.keystore.plant(rig.alias, FakeKeystore.newPair().public, null)
        val made = rig.launch().deviceKey().id
        val again = rig.launch().deviceKey().id
        assertAll(
            { assertEquals(1, rig.keystore.generated, "one key made") },
            { assertEquals(made, again, "and kept") },
        )
    }

    /** `KeyPermanentlyInvalidatedException` replaces the key at once, on every version. */
    @Test
    fun aPermanentlyInvalidatedKeyIsReplacedAtOnceOnEveryVersion() = runTest {
        val replaced = listOf(26, 30, 33).map { sdk ->
            val rig = Rig(sdk)
            val held = rig.launch().deviceKey().id
            rig.keystore.damage(rig.alias) { KeyPermanentlyInvalidatedException() }
            val replacement = rig.launch().deviceKey().id
            replacement != held && rig.keystore.generated == 2
        }
        assertEquals(listOf(true, true, true), replaced, "replaced on the first launch after, at API 26, 30 and 33")
    }

    /** A certificate on another curve is not this device's key: it is replaced, with one warning naming the alias. */
    @Test
    fun aKeyOffP256IsReplacedWithOneWarning() = runTest {
        val rig = Rig(sdk = 33)
        val foreign = FakeKeystore.newPair("secp384r1")
        rig.keystore.plant(rig.alias, foreign.public, foreign.private)

        val (warnings, replacement) = capturing { rig.launch().deviceKey() }
        val text = warnings.joinToString("\n") { it.formattedMessage }
        val signs = replacement.id.verify(MESSAGE, replacement.sign(MESSAGE))

        assertAll(
            { assertEquals(1, rig.keystore.generated, "one new key") },
            { assertTrue(signs, "which signs under its name") },
            { assertEquals(1, warnings.size, "one warning for one replacement: $text") },
            { assertTrue("\"${rig.alias}\"" in text, "the warning names the alias: $text") },
            { assertTrue("P-256" in text, "and the reason: $text") },
        )
    }

    /**
     * Every Keystore failure is logged with its cause, Keystore code and all — a kept key's warning, a
     * replaced key's, and a failed signature's — and the exception the caller sees carries it too.
     */
    @Test
    fun aFailureIsLoggedAndThrownWithItsCause() = runTest {
        val rig = Rig(sdk = 33)
        val key = rig.launch().deviceKey()
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val (loadWarnings, loadFailure) = capturing { failureOf { rig.launch().deviceKey() } }
        rig.keystore.loadFault = null
        rig.keystore.signFault = { AospKeystore.signFailure(AospKeystore.SECURE_HW_BUSY) }
        val (signWarnings, signFailure) = capturing { failureOf { key.sign(MESSAGE) } }
        rig.keystore.signFault = null
        rig.keystore.damage(rig.alias) { AospKeystore.signFailure(AospKeystore.VALUE_CORRUPTED) }
        val (replaceWarnings, _) = capturing { rig.launch().deviceKey() }

        fun logged(event: ILoggingEvent): String = generateSequence(event.throwableProxy) { it.cause }.joinToString(" <- ") { it.className }
        fun thrown(failure: Throwable?): String = generateSequence(failure) { it.cause }.joinToString(" <- ") { it::class.java.name }
        val keystoreCause = "android.security.KeyStoreException"

        assertAll(
            { assertTrue(loadFailure is IllegalStateException, "a failed load throws IllegalStateException: $loadFailure") },
            { assertTrue(keystoreCause in thrown(loadFailure), "carrying the Keystore cause: ${thrown(loadFailure)}") },
            { assertTrue(signFailure is IllegalStateException, "a failed signature too: $signFailure") },
            { assertTrue(keystoreCause in thrown(signFailure), "carrying the Keystore cause: ${thrown(signFailure)}") },
            { assertEquals(1, loadWarnings.size, "one warning for the failed load") },
            { assertTrue(keystoreCause in logged(loadWarnings[0]), "logged with its cause: ${logged(loadWarnings[0])}") },
            { assertEquals(1, signWarnings.size, "one warning for the failed signature") },
            { assertTrue(keystoreCause in logged(signWarnings[0]), "logged with its cause: ${logged(signWarnings[0])}") },
            { assertEquals(1, replaceWarnings.size, "one warning for the replacement") },
            { assertTrue(keystoreCause in logged(replaceWarnings[0]), "logged with its cause: ${logged(replaceWarnings[0])}") },
        )
    }

    // ── The one-line default ─────────────────────────────────────────────────

    /** The grants live in `<noBackupDir>/kuilt-identity-<appId>`, and the key under the alias of that name. */
    @Test
    fun platformKeepsGrantsUnderTheNoBackupDirectory() = runTest {
        val noBackup = tempDirectory()
        val keystore = FakeKeystore()
        val store = platformStore("my-app", noBackup, keystore, 33, ::fixedClock)
        val me = store.deviceKey().id
        store.keepGrant(Grant.issue(DeviceKey.generate(), me, GrantScope.AnyRoomOfIssuer, T, T + HOUR))

        val grantsDirectory = File(noBackup, "kuilt-identity-my-app")
        val keptAfterReopen = platformStore("my-app", noBackup, keystore, 33, ::fixedClock).grants().size
        val otherApp = platformStore("other-app", noBackup, keystore, 33, ::fixedClock).deviceKey().id
        assertAll(
            { assertTrue(keystore.holds("kuilt-identity-my-app"), "the key is under alias kuilt-identity-my-app") },
            { assertTrue(grantsDirectory.isDirectory, "the grants directory is under the no-backup directory") },
            { assertEquals(1, keptAfterReopen, "and the grant is kept there") },
            { assertNotEquals(me, otherApp, "another appId is another device") },
        )
    }

    @Test
    fun platformRefusesAnAppIdThatIsNotOneSegment() {
        val noBackup = tempDirectory()
        val refused = listOf("", ".", "..", "a/b", "a b", "../x").filter { appId ->
            try {
                platformStore(appId, noBackup, FakeKeystore(), 33, ::fixedClock)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
        assertEquals(listOf("", ".", "..", "a/b", "a b", "../x"), refused)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * One fake Keystore, alias, directory and clock at [sdk]; each [launch] is a new store over them, as a
     * new process would open. The clock starts at [T] and moves only when a test moves it.
     */
    private class Rig(val sdk: Int) {
        val keystore = FakeKeystore()
        val alias = "kuilt-identity-test-${UUID.randomUUID()}"
        val directory: File = Files.createTempDirectory("kuilt-keystore-test").toFile()
        var clock: Long = T

        /**
         * A new store. Each `clear…` flag lifts that fault inside `beforeKeyIsMade` — once the store has
         * decided to make a key, before it makes one — so a replacement is not itself refused by the fault.
         */
        fun launch(
            clearFaultAfterJudging: Boolean = false,
            clearOutageBeforeMaking: Boolean = false,
            clearSignFaultAfterJudging: Boolean = false,
        ): DeviceKeyStore =
            AndroidKeystoreDeviceKeyStore(alias, directory, keystore, sdk, { clock }) {
                if (clearFaultAfterJudging) keystore.loadFault = null
                if (clearOutageBeforeMaking) keystore.legacyOutage = false
                if (clearSignFaultAfterJudging) keystore.signFault = null
            }

        /** Whether the made-here marker is beside the grants. */
        suspend fun marked(): Boolean =
            FileChannelDurableStore(directory).read(AndroidKeystoreDeviceKeyStore.KEY_MADE) != null

        /** The start of the current run of failures on disk, or `null` when none is running. */
        suspend fun failingSince(): Long? =
            FileChannelDurableStore(directory).read(AndroidKeystoreDeviceKeyStore.FAILING_SINCE)?.let { ByteBuffer.wrap(it).long }

        suspend fun plantFailingSince(at: Long) {
            FileChannelDurableStore(directory).write(
                AndroidKeystoreDeviceKeyStore.FAILING_SINCE,
                ByteBuffer.allocate(Long.SIZE_BYTES).putLong(at).array(),
            )
        }
    }

    private fun thrown(error: Throwable) = KeystoreFailure.Thrown("testing", error)

    /** What [block] threw, or `null` if it returned. This test's own cancellation is never a verdict. */
    private suspend fun failureOf(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    private fun tempDirectory(): File = Files.createTempDirectory("kuilt-keystore-test").toFile()

    private suspend fun <T> capturing(block: suspend () -> T): Pair<List<ILoggingEvent>, T> {
        val logger = LoggerFactory.getLogger("us.tractat.kuilt.identity.AndroidKeystoreDeviceKeyStore") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val result = block()
            return appender.list.filter { it.level == Level.WARN } to result
        } finally {
            logger.detachAppender(appender)
        }
    }

    private companion object {
        /** A positive code in no AOSP mapping, which falls back to ERROR_KEYSTORE_FAILURE with no flags. */
        const val UNMAPPED_CODE = 9_999
        const val T = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L

        fun fixedClock(): Long = T
        val MESSAGE = "signed by the Keystore key".encodeToByteArray()

        /** A DER INTEGER with [content] as its contents octets, exactly as given. */
        fun integer(content: ByteArray): ByteArray = byteArrayOf(0x02, content.size.toByte()) + content

        fun derSequence(vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
