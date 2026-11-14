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

    // ── Decided 29: which failure replaces the key ───────────────────────────

    /**
     * On Android 13 and later, the failures that are the Keystore's own trouble keep the key — including
     * `SYSTEM_ERROR`, which `isTransientFailure()` calls *not* transient, the reason that predicate alone
     * is not the rule.
     */
    @Test
    fun onAndroid13WhatTheKeystoreCallsItsOwnTroubleKeepsTheKey() {
        val systemError = AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR)
        val busy = AospKeystore.signFailure(AospKeystore.SECURE_HW_BUSY)
        assertAll(
            { assertFalse(AospKeystore.keystoreException(AospKeystore.SYSTEM_ERROR).isTransientFailure, "precondition: AOSP calls SYSTEM_ERROR not transient") },
            { assertTrue(AospKeystore.keystoreException(AospKeystore.SECURE_HW_BUSY).isTransientFailure, "precondition: and SECURE_HW_BUSY transient") },
            { assertIs<FailureVerdict.Keep>(judge(thrown(systemError), 33), "SYSTEM_ERROR while loading") },
            { assertIs<FailureVerdict.Keep>(judge(thrown(busy), 33), "SECURE_HW_BUSY while signing") },
            {
                assertIs<FailureVerdict.Keep>(
                    judge(thrown(AospKeystore.signFailure(AospKeystore.UNKNOWN_ERROR)), 33),
                    "KM_ERROR_UNKNOWN_ERROR: ERROR_KEYMINT_FAILURE like a bad blob, but flagged a system error",
                )
            },
        )
    }

    /**
     * On Android 13 and later, what names neither the key nor the Keystore counts a failed launch
     * (Decided 29, amended): kept forever, a failure that never goes away would strand the device.
     */
    @Test
    fun onAndroid13WhatNothingExplainsCounts() {
        assertAll(
            {
                assertIs<FailureVerdict.Count>(
                    judge(thrown(AospKeystore.loadFailure(UNMAPPED_CODE)), 33),
                    "a code AOSP has no entry for: ERROR_KEYSTORE_FAILURE with no flags",
                )
            },
            { assertIs<FailureVerdict.Count>(judge(thrown(ProviderException("no cause")), 33), "a failure with no Keystore cause") },
            { assertIs<FailureVerdict.Count>(judge(KeystoreFailure.NoCertificate, 33), "a certificate that did not come back") },
        )
    }

    /** On Android 13 and later, what the Keystore attributes to the key replaces it. */
    @Test
    fun onAndroid13WhatTheKeystoreCallsTheKeysReplacesIt() {
        val badBlob = AospKeystore.signFailure(AospKeystore.INVALID_KEY_BLOB)
        assertAll(
            { assertIs<FailureVerdict.Replace>(judge(thrown(badBlob), 33), "INVALID_KEY_BLOB while signing") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(UnrecoverableKeyException("no cause")), 33), "UnrecoverableKeyException, no Keystore cause") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(KeyPermanentlyInvalidatedException()), 33), "permanently invalidated") },
        )
    }

    /** Below Android 13 nothing says which kind a failure is, so all of them count — except permanent invalidation. */
    @Test
    fun belowAndroid13EveryFailureCountsButPermanentInvalidation() {
        val failures = listOf(
            thrown(AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR)),
            thrown(AospKeystore.signFailure(AospKeystore.INVALID_KEY_BLOB)),
            thrown(UnrecoverableKeyException("no cause")),
            thrown(ProviderException("no cause")),
            KeystoreFailure.NoCertificate,
            KeystoreFailure.NoKeyThoughOneWasMade,
        )
        val verdicts = listOf(24, 30, 32).flatMap { sdk -> failures.map { judge(it, sdk) } }
        assertAll(
            { assertTrue(verdicts.all { it is FailureVerdict.Count }, "every failure counts below 33: $verdicts") },
            { assertIs<FailureVerdict.Replace>(judge(thrown(KeyPermanentlyInvalidatedException()), 24), "permanent invalidation replaces at once") },
        )
    }

    // ── The store, failure by failure ────────────────────────────────────────

    /**
     * Below Android 13 a Keystore failure throws and keeps the key, counted once per launch however many
     * times the launch asks; the third consecutive failed launch replaces it.
     */
    @Test
    fun belowAndroid13TheThirdConsecutiveFailedLaunchReplacesTheKey() = runTest {
        val rig = Rig(sdk = 32)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }

        val second = rig.launch()
        val first = failureOf { second.deviceKey() }
        val firstAgain = failureOf { second.deviceKey() } // the same launch asking again
        val third = failureOf { rig.launch().deviceKey() }
        val madeBefore = rig.keystore.generated
        val countBefore = rig.failedLaunches()
        // The fault still holds when the fourth launch reads, so its replacement is the count's doing; it
        // is cleared only once the store has decided, so the new key can be read back.
        val fourth = rig.launch(clearFaultAfterJudging = true).deviceKey().id
        val countAfter = rig.failedLaunches()

        assertAll(
            { assertTrue(first is IllegalStateException && firstAgain is IllegalStateException && third is IllegalStateException, "each failed launch throws: $first, $firstAgain, $third") },
            { assertEquals(1, madeBefore, "and makes no key in two failed launches") },
            { assertEquals(2, countBefore, "asking twice in one launch counts once: two launches, count 2") },
            { assertNotEquals(held, fourth, "the third failed launch replaces the key") },
            { assertEquals(2, rig.keystore.generated, "with one new key") },
            { assertEquals(null, countAfter, "and the count is cleared") },
        )
    }

    /** A launch that reads the key resets the count, so failures must be consecutive to replace it. */
    @Test
    fun belowAndroid13ALaunchThatReadsTheKeyResetsTheCount() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        val fault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        rig.keystore.loadFault = fault
        failureOf { rig.launch().deviceKey() }
        failureOf { rig.launch().deviceKey() }
        val countBeforeSuccess = rig.failedLaunches()
        rig.keystore.loadFault = null
        val readBetween = rig.launch().deviceKey().id
        val countAfterSuccess = rig.failedLaunches()
        rig.keystore.loadFault = fault
        failureOf { rig.launch().deviceKey() }
        failureOf { rig.launch().deviceKey() }
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id

        assertAll(
            { assertEquals(2, countBeforeSuccess, "precondition: two failed launches were counted") },
            { assertEquals(held, readBetween, "a launch that reads the key gets it") },
            { assertEquals(null, countAfterSuccess, "and clears the count") },
            { assertEquals(held, after, "so two more failed launches, four in all, do not replace it") },
            { assertEquals(1, rig.keystore.generated, "no key was made after the first") },
        )
    }

    /** On Android 13 and later a failure the Keystore calls its own never replaces the key, however long it lasts. */
    @Test
    fun onAndroid13AKeystoreFailureNeverReplacesTheKey() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val failures = List(5) { failureOf { rig.launch().deviceKey() } }
        val counted = rig.failedLaunches()
        rig.keystore.loadFault = null
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(failures.all { it is IllegalStateException }, "five launches throw: $failures") },
            { assertEquals(null, counted, "nothing is counted on 33 and later") },
            { assertEquals(held, after, "and the key is the same key") },
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
     * key with no certificate under the alias. A failing certificate read looks the same, so it is
     * counted like any failure: the third consecutive launch replaces it, and the replacement is kept.
     */
    @Test
    fun belowAndroid12AHalfMadeKeyIsReplacedOnTheThirdLaunch() = runTest {
        val rig = Rig(sdk = 30)
        rig.keystore.plant(rig.alias, null, FakeKeystore.newPair().private)
        val early = List(2) { failureOf { rig.launch().deviceKey() } }
        val madeEarly = rig.keystore.generated
        val third = rig.launch().deviceKey().id
        val fourth = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(early.all { it is IllegalStateException }, "the first two launches throw: $early") },
            { assertEquals(0, madeEarly, "and make no key") },
            { assertEquals(1, rig.keystore.generated, "the third makes one") },
            { assertEquals(third, fourth, "which is kept") },
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
        val counted = rig.failedLaunches()
        rig.keystore.certificateLost = false
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(failure is IllegalStateException, "throws: $failure") },
            { assertEquals(1, rig.keystore.generated, "makes no key") },
            { assertEquals(1, counted, "and counts a failed launch") },
            { assertEquals(held, after, "and the key is the same key") },
        )
    }

    /** On Android 13 and later a code that names neither key nor Keystore replaces on the third failed launch. */
    @Test
    fun onAndroid13AnUnexplainedCodeReplacesOnTheThirdFailedLaunch() = runTest {
        val rig = Rig(sdk = 33)
        val held = rig.launch().deviceKey().id
        rig.keystore.loadFault = { AospKeystore.loadFailure(UNMAPPED_CODE) }
        val early = List(2) { failureOf { rig.launch().deviceKey() } }
        val madeEarly = rig.keystore.generated
        val third = rig.launch(clearFaultAfterJudging = true).deviceKey().id
        assertAll(
            { assertTrue(early.all { it is IllegalStateException }, "two failed launches throw: $early") },
            { assertEquals(1, madeEarly, "and make no key") },
            { assertNotEquals(held, third, "the third replaces it") },
        )
    }

    // ── The made-here marker (Android 11 and earlier) ────────────────────────

    /**
     * On Android 11 and earlier `getKey` answers "no key" when the Keystore fails. Where this store has
     * made a key, that counts a failed launch instead of making a new one; the outage ending brings the
     * same key back.
     */
    @Test
    fun onAndroid11NoKeyWhereOneWasMadeCountsRatherThanMakingOne() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        val marked = rig.marked()
        rig.keystore.legacyOutage = true
        val failures = List(2) { failureOf { rig.launch().deviceKey() } }
        val counted = rig.failedLaunches()
        rig.keystore.legacyOutage = false
        val after = rig.launch().deviceKey().id
        assertAll(
            { assertTrue(marked, "precondition: making the key marked it") },
            { assertTrue(failures.all { it is IllegalStateException }, "two launches in the outage throw: $failures") },
            { assertEquals(2, counted, "and are counted") },
            { assertEquals(1, rig.keystore.generated, "no key is made") },
            { assertEquals(held, after, "and the key is the same key once the Keystore answers") },
        )
    }

    /** The outage lasting three launches replaces the key, as any counted failure does. */
    @Test
    fun onAndroid11NoKeyForThreeLaunchesReplacesIt() = runTest {
        val rig = Rig(sdk = 30)
        val held = rig.launch().deviceKey().id
        rig.keystore.legacyOutage = true
        repeat(2) { failureOf { rig.launch().deviceKey() } }
        val third = rig.launch(clearOutageBeforeMaking = true).deviceKey().id
        val marked = rig.marked()
        assertAll(
            { assertNotEquals(held, third, "the third failed launch makes a new key") },
            { assertTrue(marked, "and marks it") },
        )
    }

    /** No marker and no key is a first launch, on every version: the key is made at once. */
    @Test
    fun noKeyAndNoMarkerIsAFirstLaunch() = runTest {
        val made = listOf(24, 30, 33).map { sdk ->
            val rig = Rig(sdk)
            val before = rig.marked()
            rig.launch().deviceKey()
            !before && rig.keystore.generated == 1 && rig.marked()
        }
        assertEquals(listOf(true, true, true), made, "made at once and marked, at API 24, 30 and 33")
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
        val counted = rig.failedLaunches()
        assertAll(
            { assertTrue(marked, "precondition: the marker is there") },
            { assertNotEquals(held, again, "a new key is made at once") },
            { assertEquals(null, counted, "and nothing is counted") },
        )
    }

    /**
     * The marker is written after the key, never before: a key that fails to be made leaves no marker,
     * so the next launch makes one at once rather than counting a "missing" key that never existed.
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
     * launch that reads it, so a later outage counts rather than replaces it.
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
        val replaced = listOf(24, 30, 33).map { sdk ->
            val rig = Rig(sdk)
            val held = rig.launch().deviceKey().id
            rig.keystore.damage(rig.alias) { KeyPermanentlyInvalidatedException() }
            val replacement = rig.launch().deviceKey().id
            replacement != held && rig.keystore.generated == 2
        }
        assertEquals(listOf(true, true, true), replaced, "replaced on the first launch after, at API 24, 30 and 33")
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
     * A Keystore failure is logged with its cause, Keystore code and all, while the exception the caller
     * sees carries only the class: the code is what a device check needs (review I1).
     */
    @Test
    fun aFailureIsLoggedWithItsCause() = runTest {
        val rig = Rig(sdk = 33)
        val key = rig.launch().deviceKey()
        rig.keystore.loadFault = { AospKeystore.loadFailure(AospKeystore.SYSTEM_ERROR) }
        val (loadWarnings, loadFailure) = capturing { failureOf { rig.launch().deviceKey() } }
        rig.keystore.loadFault = null
        rig.keystore.signFault = { AospKeystore.signFailure(AospKeystore.SECURE_HW_BUSY) }
        val (signWarnings, signFailure) = capturing { failureOf { key.sign(MESSAGE) } }

        fun causes(event: ILoggingEvent): String =
            generateSequence(event.throwableProxy) { it.cause }.joinToString(" <- ") { it.className }

        assertAll(
            { assertTrue(loadFailure is IllegalStateException, "a failed load throws IllegalStateException: $loadFailure") },
            { assertTrue(signFailure is IllegalStateException, "a failed signature too: $signFailure") },
            { assertEquals(1, loadWarnings.size, "one warning for the failed load") },
            { assertTrue("android.security.KeyStoreException" in causes(loadWarnings[0]), "carrying the Keystore cause: ${causes(loadWarnings[0])}") },
            { assertEquals(1, signWarnings.size, "one warning for the failed signature") },
            { assertTrue("android.security.KeyStoreException" in causes(signWarnings[0]), "carrying the Keystore cause: ${causes(signWarnings[0])}") },
        )
    }

    // ── The one-line default ─────────────────────────────────────────────────

    /** The grants live in `<noBackupDir>/kuilt-identity-<appId>`, and the key under the alias of that name. */
    @Test
    fun platformKeepsGrantsUnderTheNoBackupDirectory() = runTest {
        val noBackup = tempDirectory()
        val keystore = FakeKeystore()
        val store = platformStore("my-app", noBackup, keystore, 33)
        val me = store.deviceKey().id
        store.keepGrant(Grant.issue(DeviceKey.generate(), me, GrantScope.AnyRoomOfIssuer, T, T + HOUR))

        val grantsDirectory = File(noBackup, "kuilt-identity-my-app")
        val keptAfterReopen = platformStore("my-app", noBackup, keystore, 33).grants().size
        val otherApp = platformStore("other-app", noBackup, keystore, 33).deviceKey().id
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
                platformStore(appId, noBackup, FakeKeystore(), 33)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
        assertEquals(listOf("", ".", "..", "a/b", "a b", "../x"), refused)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** One fake Keystore, alias and directory at [sdk]; each [launch] is a new process opening the store. */
    private class Rig(val sdk: Int) {
        val keystore = FakeKeystore()
        val alias = "kuilt-identity-test-${UUID.randomUUID()}"
        val directory: File = Files.createTempDirectory("kuilt-keystore-test").toFile()

        /**
         * A new launch's store. With [clearFaultAfterJudging], the load fault is cleared as soon as the
         * store has made its decision to replace — before it makes the new key — so the replacement is
         * not itself refused by the fault.
         */
        fun launch(clearFaultAfterJudging: Boolean = false, clearOutageBeforeMaking: Boolean = false): DeviceKeyStore =
            AndroidKeystoreDeviceKeyStore(alias, directory, keystore, sdk, UUID.randomUUID().toString()) {
                if (clearFaultAfterJudging) keystore.loadFault = null
                if (clearOutageBeforeMaking) keystore.legacyOutage = false
            }

        /** Whether the made-here marker is beside the grants. */
        suspend fun marked(): Boolean =
            FileChannelDurableStore(directory).read(AndroidKeystoreDeviceKeyStore.KEY_MADE) != null

        /** The failed-launch count on disk, or `null` when none is kept. */
        suspend fun failedLaunches(): Int? =
            FileChannelDurableStore(directory).read(AndroidKeystoreDeviceKeyStore.FAILED_LAUNCHES)
                ?.let(FailedLaunches::decode)?.count
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
        val MESSAGE = "signed by the Keystore key".encodeToByteArray()

        /** A DER INTEGER with [content] as its contents octets, exactly as given. */
        fun integer(content: ByteArray): ByteArray = byteArrayOf(0x02, content.size.toByte()) + content

        fun derSequence(vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
