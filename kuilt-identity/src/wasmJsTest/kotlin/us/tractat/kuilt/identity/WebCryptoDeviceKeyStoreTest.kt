@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.identity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.StoreKey
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

    /**
     * WebCrypto refusing to verify the self-test's signature is the browser failing, not the record:
     * [DeviceKeyStore.deviceKey] throws, and the key stays. Read as a failed self-test, it would be
     * overwritten, and a transient browser fault would cost the device its name and every grant.
     */
    @Test
    fun aVerifyFaultThrowsAndKeepsTheKey() = runTest { assertAWebCryptoFaultKeepsTheKey("verify") }

    /** The same for the decode that names the stored public half, the other call a fault reached. */
    @Test
    fun aKeyImportFaultThrowsAndKeepsTheKey() = runTest { assertAWebCryptoFaultKeepsTheKey("importKey") }

    /**
     * The control: a signing fault already threw before the verify and decode arms above were fixed, so
     * this arm is green either way and shows the rig and the assertions pass a store that keeps its key.
     */
    @Test
    fun aSignFaultThrowsAndKeepsTheKey() = runTest { assertAWebCryptoFaultKeepsTheKey("sign") }

    /**
     * Makes `crypto.subtle.<method>` reject once, while a second store reads the key the first one made,
     * and checks the read threw, no key was made, and the record still names the first key.
     */
    private suspend fun assertAWebCryptoFaultKeepsTheKey(method: String) {
        val database = freshDatabase()
        val original = store(database).deviceKey().id
        var made = 0
        val failure = try {
            faultOnce(method)
            try {
                WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) { made++ }.deviceKey()
                null
            } catch (e: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                e
            }
        } finally {
            clearFault(method)
        }
        val firings = faultFirings(method)
        val kept = independentNameOf(publicKeyOf(assertNotNull(readRecord(database), "a record is still there")))
        val afterwards = WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) { made++ }.deviceKey().id

        assertAll(
            { assertEquals(1, firings, "rig: the $method fault fired once") },
            { assertNotNull(failure, "a WebCrypto $method failure throws rather than replacing the key") },
            { assertEquals(0, made, "no key is made") },
            { assertEquals(original, kept, "the record still holds the original key") },
            { assertEquals(original, afterwards, "and once WebCrypto answers again it is the same device") },
        )
    }

    /**
     * Two stores that share no in-page mutex, as two tabs share none, still make one key between them:
     * the Web Lock alone orders them.
     *
     * The interleaving is forced: the first store to reach the gate waits there, in wall-clock time,
     * for the other store to reach it too. Without the Web Lock the other store reads "no key" and
     * arrives, so two keys are made, every time. With it, the other store waits for the lock, the gate
     * gives up after [OVERLAP_MS], and the test checks that both calls were still in flight then.
     */
    @Test
    fun twoStoresWithTheirOwnMutexesMakeOneKey() = runTest {
        val database = freshDatabase()
        val meeting = Meeting()
        var made = 0
        var inFlight = 0
        var inFlightWhileMaking = -1
        val gate: suspend () -> Unit = {
            made++
            meeting.arrive()
            if (inFlightWhileMaking < 0) inFlightWhileMaking = inFlight
        }
        val stores = List(2) { WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database), Mutex(), gate) }
        val ids = stores.map { store ->
            async {
                inFlight++
                try {
                    store.deviceKey().id
                } finally {
                    inFlight--
                }
            }
        }.awaitAll()
        val kept = independentNameOf(publicKeyOf(assertNotNull(readRecord(database), "a key is kept")))

        assertAll(
            { assertEquals(2, inFlightWhileMaking, "precondition: both calls were in flight while the key was made") },
            { assertEquals(1, made, "one key made between two stores that share no in-page mutex") },
            { assertEquals(1, ids.toSet().size, "both stores name the same device") },
            { assertEquals(ids.first(), kept, "and that device's key is the one kept") },
        )
    }

    /**
     * Two stores that share no in-page mutex, keeping a grant each at once, lose neither: the Web Lock
     * holds the grants record's read, merge and write together.
     *
     * Forced as above, at the grants read: each store, having read the record, waits for the other to
     * have read it too. Without the Web Lock both read the empty record and the second write drops the
     * first grant. With it, the second read comes after the first write.
     */
    @Test
    fun twoStoresWithTheirOwnMutexesKeepBothGrants() = runTest {
        val database = freshDatabase()
        val me = store(database).deviceKey().id
        val meeting = Meeting()
        var inFlight = 0
        var inFlightAtFirstRead = -1
        val medium = AfterGrantsRead(IndexedDbGrantMedium(database)) {
            meeting.arrive()
            if (inFlightAtFirstRead < 0) inFlightAtFirstRead = inFlight
        }
        val stores = List(2) { WebCryptoDeviceKeyStore(database, medium, Mutex()) {} }
        val host = DeviceKey.generate()
        val grants = listOf("A", "B").map { room -> Grant.issue(host, me, GrantScope.Room(room), ISSUED_AT, ISSUED_AT + HOUR) }
        stores.zip(grants) { store, grant ->
            async {
                inFlight++
                try {
                    store.keepGrant(grant)
                } finally {
                    inFlight--
                }
            }
        }.awaitAll()
        val kept = store(database).grants().toSet()

        assertAll(
            { assertEquals(2, inFlightAtFirstRead, "precondition: both calls were in flight at the first grants read") },
            { assertEquals(2, meeting.arrivals, "precondition: both stores read the grants record") },
            { assertEquals(grants.toSet(), kept, "neither store's grant is lost to the other's write") },
        )
    }

    private fun store(database: String) = WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) {}

    /** The DOM error name exporting [key] as [format] rejected with, or `null` when it exported. */
    private suspend fun exportRefusal(key: JsAny, format: String): String? =
        idbCall<String?>("exporting") { onSuccess, _ -> exportOutcome(key, format, onSuccess) }

    private suspend fun cryptoPair(start: (onSuccess: (JsAny) -> Unit, onError: (String) -> Unit) -> Unit): JsAny =
        idbCall("generating", start)
}

/** Long enough for an IndexedDB read and a WebCrypto key to finish many times over. */
private const val OVERLAP_MS = 300

private const val ISSUED_AT = 1_700_000_000_000L
private const val HOUR = 3_600_000L

/**
 * Two parties meeting in wall-clock time: the first to [arrive] waits for the second, or for
 * [OVERLAP_MS] of real time, whichever comes first. Real time, not the test's virtual clock, because
 * the other party is held up by the browser — a Web Lock, an IndexedDB callback — which virtual time
 * does not advance.
 */
private class Meeting {
    var arrivals = 0
        private set
    private val met = CompletableDeferred<Unit>()

    suspend fun arrive() {
        arrivals++
        if (arrivals >= 2) {
            met.complete(Unit)
        } else {
            afterMillis(OVERLAP_MS) { met.complete(Unit) }
        }
        met.await()
    }
}

/** [inner], with [afterRead] run each time a call has read the grants record and before it goes on. */
private class AfterGrantsRead(private val inner: GrantMedium, private val afterRead: suspend () -> Unit) : GrantMedium {
    override suspend fun <T> use(block: suspend (DurableStore) -> T): T = inner.use { block(Reading(it)) }

    private inner class Reading(private val store: DurableStore) : DurableStore by store {
        override suspend fun read(key: StoreKey): ByteArray? =
            store.read(key).also { if (key == WebCryptoDeviceKeyStore.GRANTS) afterRead() }
    }
}

@JsFun("(ms, done) => { setTimeout(() => done(), ms); }")
private external fun afterMillis(ms: Int, done: () -> Unit)

/**
 * Makes the next `crypto.subtle[method]` call reject with an `OperationError`, as a browser's own
 * fault would, and count it. An own property on the `SubtleCrypto` instance shadows the prototype's
 * method; the one call it answers deletes it again.
 */
@JsFun(
    """
    (method) => {
        const subtle = crypto.subtle;
        globalThis['kuiltFaultFirings_' + method] = 0;
        subtle[method] = function () {
            delete subtle[method];
            globalThis['kuiltFaultFirings_' + method] += 1;
            return Promise.reject(new DOMException('rigged ' + method + ' fault', 'OperationError'));
        };
    }
    """,
)
private external fun faultOnce(method: String)

/** Removes [faultOnce]'s rig if it never fired. */
@JsFun("(method) => { if (Object.prototype.hasOwnProperty.call(crypto.subtle, method)) delete crypto.subtle[method]; }")
private external fun clearFault(method: String)

/** How many calls [faultOnce]'s rig answered. */
@JsFun("(method) => globalThis['kuiltFaultFirings_' + method] | 0")
private external fun faultFirings(method: String): Int

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
