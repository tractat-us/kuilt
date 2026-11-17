@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.identity

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.test.assertAll
import kotlin.js.JsAny
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [WebCryptoDeviceKeyStore] promises beyond the conformance suite: the key cannot leave the browser,
 * its signatures are the raw `r ‖ s` every other platform's are, and `platform` keeps it where it says.
 */
class WebCryptoDeviceKeyStoreTest {
    /**
     * Spec Review Focus 4: the private half cannot be read out of the page — not through the key the
     * store hands out, and not through the record it keeps.
     *
     * The control is an extractable key made the same way: it exports in both formats, so a refusal
     * below is WebCrypto refusing this key, not the export probe failing for every key.
     */
    @Test
    fun webCryptoKeyIsNonExtractable() = runTest {
        val database = freshDatabase()
        val key = assertIs<WebCryptoDeviceKey>(store(database).deviceKey())
        val stored = assertNotNull(readRecord(database), "precondition: the store kept a record")
        val control = privateKeyOf(cryptoPair { onSuccess, onError -> generateExtractablePair(onSuccess, onError) })
        val controlJwk = exportRefusal(control, "jwk")
        val controlPkcs8 = exportRefusal(control, "pkcs8")
        val keyJwk = exportRefusal(key.privateKey, "jwk")
        val keyPkcs8 = exportRefusal(key.privateKey, "pkcs8")
        val storedPkcs8 = exportRefusal(privateKeyOf(stored), "pkcs8")

        assertAll(
            { assertTrue(isExtractable(control), "control: the extractable key says so") },
            { assertNull(controlJwk, "control: an extractable private key exports as a JWK") },
            { assertNull(controlPkcs8, "control: and as PKCS#8") },
            { assertFalse(isExtractable(key.privateKey), "the store's private key is not extractable") },
            { assertNotNull(keyJwk, "exporting it as a JWK rejects") },
            { assertNotNull(keyPkcs8, "exporting it as PKCS#8 rejects") },
            { assertFalse(isExtractable(privateKeyOf(stored)), "the kept record's private key is not extractable either") },
            { assertNotNull(storedPkcs8, "and exporting it rejects") },
        )
    }

    /**
     * A signature is the 64-byte raw `r ‖ s` [DeviceKey.sign] promises, the form the JVM, Apple and
     * Android stores return, and [DidKey.verify] — the check every other device runs — accepts it under
     * the key's name and refuses it for a flipped bit, another message, or another key's name.
     */
    @Test
    fun signaturesAreRawAndVerifyUnderTheName() = runTest {
        val key = store(freshDatabase()).deviceKey()
        val message = "admit me".encodeToByteArray()
        val signature = key.sign(message)
        val flipped = signature.copyOf().also { it[17] = (it[17].toInt() xor 0x01).toByte() }
        val stranger = DeviceKey.generate().id
        val verifies = key.id.verify(message, signature)
        val flippedVerifies = key.id.verify(message, flipped)
        val otherMessageVerifies = key.id.verify("admit someone else".encodeToByteArray(), signature)
        val strangerVerifies = stranger.verify(message, signature)

        assertAll(
            { assertEquals(64, signature.size, "raw r ‖ s, not DER") },
            { assertTrue(verifies, "verifies under the key's own name") },
            { assertFalse(flippedVerifies, "a flipped bit does not") },
            { assertFalse(otherMessageVerifies, "another message does not") },
            { assertFalse(strangerVerifies, "another key's name does not") },
        )
    }

    /** The store's name is the stored public half's, compressed: the name worked out by hand agrees. */
    @Test
    fun theNameIsTheStoredPublicHalf() = runTest {
        val database = freshDatabase()
        val id = store(database).deviceKey().id
        val stored = assertNotNull(readRecord(database))
        assertEquals(independentNameOf(publicKeyOf(stored)), id)
    }

    /**
     * A record holding an **extractable** private key is not one this store made, and is replaced: a
     * key that could be exported could be on another device too.
     */
    @Test
    fun anExtractablePrivateKeyIsReplaced() = runTest {
        val database = freshDatabase()
        val planted = cryptoPair { onSuccess, onError -> generateExtractablePair(onSuccess, onError) }
        putRecord(database, planted)
        val plantedName = independentNameOf(publicKeyOf(planted))
        var made = 0
        val replacement = WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) { made++ }.deviceKey().id
        val kept = assertNotNull(readRecord(database))

        assertAll(
            { assertEquals(1, made, "one new key made") },
            { assertNotEquals(plantedName, replacement, "under a new name") },
            { assertFalse(isExtractable(privateKeyOf(kept)), "and the record now holds a non-extractable key") },
        )
    }

    /** `platform` keeps the key in `kuilt-identity-<appId>`, and a second store over it is the same device. */
    @Test
    fun platformKeepsTheKeyInItsNamedDatabase() = runTest {
        val appId = "test-${Random.nextLong().toULong().toString(16)}"
        val first = DeviceKeyStore.platform(appId).deviceKey().id
        val again = DeviceKeyStore.platform(appId).deviceKey().id
        val stored = assertNotNull(readRecord("kuilt-identity-$appId"), "the record is in the named database")
        val storedName = independentNameOf(publicKeyOf(stored))
        assertAll(
            { assertEquals(first, again, "two stores over one app id are one device") },
            { assertEquals(first, storedName, "and the record is that device's key") },
        )
    }

    @Test
    fun platformRefusesAnAppIdThatIsNotOneSegment() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { DeviceKeyStore.platform("") } },
            { assertFailsWith<IllegalArgumentException> { DeviceKeyStore.platform("..") } },
            { assertFailsWith<IllegalArgumentException> { DeviceKeyStore.platform("a/b") } },
        )
    }

    private fun store(database: String) = WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) {}

    /** The DOM error name exporting [key] as [format] rejected with, or `null` when it exported. */
    private suspend fun exportRefusal(key: JsAny, format: String): String? =
        idbCall<String?>("exporting") { onSuccess, _ -> exportOutcome(key, format, onSuccess) }

    private suspend fun cryptoPair(start: (onSuccess: (JsAny) -> Unit, onError: (String) -> Unit) -> Unit): JsAny =
        idbCall("generating", start)
}

@JsFun("(key) => key.extractable === true")
private external fun isExtractable(key: JsAny): Boolean

/** Calls [onDone] with `null` when [key] exports as [format], or with the rejection's DOM error name. */
@JsFun(
    """
    (key, format, onDone) => {
        try {
            crypto.subtle.exportKey(format, key)
                .then(() => onDone(null), (e) => onDone(e && e.name ? e.name : String(e)));
        } catch (e) { onDone(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun exportOutcome(key: JsAny, format: String, onDone: (String?) -> Unit)

@JsFun(
    """
    (onSuccess, onError) => {
        try {
            crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
                .then((pair) => onSuccess(pair), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun generateExtractablePair(onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)
