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
    fun aVerifyFaultThrowsAndKeepsTheKey() = runTest {
        assertAWebCryptoFaultKeepsTheKey("verify", passing = 0, failedAt = "verifying the self-test")
    }

    /** The same for the decode that names the stored public half: the first `importKey` the read makes. */
    @Test
    fun aKeyImportFaultThrowsAndKeepsTheKey() = runTest {
        assertAWebCryptoFaultKeepsTheKey("importKey", passing = 0, failedAt = "decoding a public key")
    }

    /**
     * The same for the second: importing the point the name gives, to verify the self-test under. The
     * rig lets the decode's `importKey` through, and the failure's text says which call it reached.
     */
    @Test
    fun aNamedKeyImportFaultThrowsAndKeepsTheKey() = runTest {
        assertAWebCryptoFaultKeepsTheKey("importKey", passing = 1, failedAt = "importing the public key its name gives")
    }

    /**
     * The control: a signing fault already threw before the verify and decode arms above were fixed, so
     * this arm is green either way and shows the rig and the assertions pass a store that keeps its key.
     */
    @Test
    fun aSignFaultThrowsAndKeepsTheKey() = runTest {
        assertAWebCryptoFaultKeepsTheKey("sign", passing = 0, failedAt = "signing")
    }

    /**
     * Makes `crypto.subtle.<method>` reject once, after [passing] calls to it have gone through, while a
     * second store reads the key the first one made, and checks the read threw from the call [failedAt]
     * names, no key was made, and the record still names the first key.
     */
    private suspend fun assertAWebCryptoFaultKeepsTheKey(method: String, passing: Int, failedAt: String) {
        val database = freshDatabase()
        val original = store(database).deviceKey().id
        var made = 0
        val failure = try {
            faultOnce(method, passing)
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
            {
                val message = failure?.message.orEmpty()
                assertTrue("WebCrypto failed $failedAt" in message, "rig: the fault reached the call $failedAt, not another ($message)")
            },
            { assertEquals(0, made, "no key is made") },
            { assertEquals(original, kept, "the record still holds the original key") },
            { assertEquals(original, afterwards, "and once WebCrypto answers again it is the same device") },
        )
    }

    /**
     * Two stores that share no in-page mutex, as two tabs share none, still make one key between them:
     * the Web Lock alone orders them.
     *
     * The witness is the browser's own lock manager. The first store to reach the gate is about to make
     * the key, and holds there until `navigator.locks.query()` shows the other store's request waiting
     * on the database's lock. With the Web Lock it is: one held, one pending. Without it nothing holds
     * or waits on that name at all, so the rig reds whenever the other store gets there — a lagging
     * store cannot let the missing lock pass by reading the first store's key after it is written.
     */
    @Test
    fun twoStoresWithTheirOwnMutexesMakeOneKey() = runTest {
        val database = freshDatabase()
        var made = 0
        var whileMaking: WebLockState? = null
        val gate: suspend () -> Unit = {
            made++
            if (made == 1) whileMaking = awaitAWaiter(database)
        }
        val stores = List(2) { WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database), Mutex(), gate) }
        val ids = stores.map { store -> async { store.deviceKey().id } }.awaitAll()
        val kept = independentNameOf(publicKeyOf(assertNotNull(readRecord(database), "a key is kept")))
        val witness = assertNotNull(whileMaking, "rig: a key was made")

        assertAll(
            { assertEquals(1, witness.held, "rig: the store making the key holds the Web Lock") },
            { assertEquals(1, witness.pending, "rig: and the other store is waiting on it") },
            { assertEquals(1, made, "one key made between two stores that share no in-page mutex") },
            { assertEquals(1, ids.toSet().size, "both stores name the same device") },
            { assertEquals(ids.first(), kept, "and that device's key is the one kept") },
        )
    }

    /**
     * Two stores that share no in-page mutex, keeping a grant each at once, lose neither: the Web Lock
     * holds the grants record's read, merge and write together.
     *
     * Witnessed as above, at the first grants read: the store that has read the record holds there
     * until the lock manager shows the other store waiting on the database's lock.
     */
    @Test
    fun twoStoresWithTheirOwnMutexesKeepBothGrants() = runTest {
        val database = freshDatabase()
        val me = store(database).deviceKey().id
        var reads = 0
        var atFirstRead: WebLockState? = null
        val medium = AfterGrantsRead(IndexedDbGrantMedium(database)) {
            reads++
            if (reads == 1) atFirstRead = awaitAWaiter(database)
        }
        val stores = List(2) { WebCryptoDeviceKeyStore(database, medium, Mutex()) {} }
        val host = DeviceKey.generate()
        val grants = listOf("A", "B").map { room -> Grant.issue(host, me, GrantScope.Room(room), ISSUED_AT, ISSUED_AT + HOUR) }
        stores.zip(grants) { store, grant -> async { store.keepGrant(grant) } }.awaitAll()
        val kept = store(database).grants().toSet()
        val witness = assertNotNull(atFirstRead, "rig: a store read the grants record")

        assertAll(
            { assertEquals(1, witness.held, "rig: the store reading the grants holds the Web Lock") },
            { assertEquals(1, witness.pending, "rig: and the other store is waiting on it") },
            { assertEquals(2, reads, "precondition: both stores read the grants record") },
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

private const val ISSUED_AT = 1_700_000_000_000L
private const val HOUR = 3_600_000L

/** How long [awaitAWaiter] looks for a waiting request before it answers with what it last saw. */
private const val WAITER_CEILING_MS = 10_000
private const val WAITER_POLL_MS = 10

/** The requests the browser's lock manager reports for one Web Lock name: holding it, and waiting on it. */
private class WebLockState(val held: Int, val pending: Int) {
    override fun toString(): String = "WebLockState(held=$held, pending=$pending)"
}

/**
 * The Web Lock [name] as `navigator.locks.query()` reports it, once that shows a request waiting on it.
 * It answers at once when nothing holds the lock, since then nothing can be waiting on it, and with
 * the last snapshot after [WAITER_CEILING_MS] of real time. Real time, not the test's virtual clock:
 * the other request is held up by the browser, which virtual time does not advance.
 */
private suspend fun awaitAWaiter(name: String): WebLockState {
    var waited = 0
    while (true) {
        val state = webLockState(name)
        if (state.held == 0 || state.pending > 0 || waited >= WAITER_CEILING_MS) return state
        realDelay(WAITER_POLL_MS)
        waited += WAITER_POLL_MS
    }
}

private suspend fun webLockState(name: String): WebLockState {
    val state = CompletableDeferred<WebLockState>()
    queryWebLock(
        name,
        onDone = { held, pending -> state.complete(WebLockState(held, pending)) },
        onError = { state.completeExceptionally(IllegalStateException("navigator.locks.query() failed ($it)")) },
    )
    return state.await()
}

private suspend fun realDelay(ms: Int) {
    val done = CompletableDeferred<Unit>()
    afterMillis(ms) { done.complete(Unit) }
    done.await()
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

/** Counts the requests `navigator.locks.query()` shows holding, and waiting on, the Web Lock [name]. */
@JsFun(
    """
    (name, onDone, onError) => {
        try {
            navigator.locks.query().then((snapshot) => {
                const named = (requests) => (requests || []).filter((request) => request.name === name).length;
                onDone(named(snapshot.held), named(snapshot.pending));
            }, (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun queryWebLock(name: String, onDone: (Int, Int) -> Unit, onError: (String) -> Unit)

/**
 * Lets [passing] `crypto.subtle[method]` calls through, then makes the next one reject with an
 * `OperationError`, as a browser's own fault would, and counts it. An own property on the
 * `SubtleCrypto` instance shadows the prototype's method; the one call it rejects deletes it again.
 */
@JsFun(
    """
    (method, passing) => {
        const subtle = crypto.subtle;
        const real = Object.getPrototypeOf(subtle)[method];
        let left = passing;
        globalThis['kuiltFaultFirings_' + method] = 0;
        subtle[method] = function (...args) {
            if (left > 0) { left -= 1; return real.apply(subtle, args); }
            delete subtle[method];
            globalThis['kuiltFaultFirings_' + method] += 1;
            return Promise.reject(new DOMException('rigged ' + method + ' fault', 'OperationError'));
        };
    }
    """,
)
private external fun faultOnce(method: String, passing: Int)

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
