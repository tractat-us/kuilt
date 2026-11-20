@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.algorithms.EC
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.IndexedDbDurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsAny

private val logger = KotlinLogging.logger("us.tractat.kuilt.identity.WebCryptoDeviceKeyStore")

/**
 * This device's key store in a browser: a WebCrypto P-256 key made **non-extractable**, so the page can
 * ask it to sign and can never read its private half, kept in IndexedDB beside the grants.
 *
 * The key pair is the `CryptoKeyPair` WebCrypto made, stored as it is: IndexedDB keeps a `CryptoKey` by
 * structured clone, private half and all, without the page ever seeing the bytes. Both live in one
 * database, `kuilt-identity-<appId>`, in its `kv` object store — the key pair under `device-key`, the
 * grants under `grants` through [IndexedDbDurableStore] — so clearing the site's data clears both and
 * the next visit is a new device. The name comes from the public half, which WebCrypto always lets the
 * page export: its raw point, compressed.
 *
 * Every tab of one browser profile on one origin shares the database and so is one device, which is
 * the point (spec Decided 30). Another profile, another browser or a private window is another device.
 *
 * ## Damage is replaced; a database that cannot answer is not
 *
 * A stored record that is not a usable key pair — not a pair of `CryptoKey`s, not ECDSA on P-256, a
 * private half that cannot sign or that could be exported, a public half that is not a P-256 point —
 * or whose private half does not sign for the name its public half gives, is overwritten with a new key,
 * with one warning in the log and no throw: the app carries on as a new device.
 *
 * Anything IndexedDB itself refuses throws [IllegalStateException] and writes nothing: a database a
 * newer page upgraded past this one (`VersionError`), a connection the browser is closing
 * (`InvalidStateError`), a full disk (`QuotaExceededError`), a browser with no IndexedDB here. Only a
 * read that **succeeds** and finds nothing under `device-key` means "no key yet". WebCrypto works the
 * same way: a fault exporting, decoding or importing the public half, or signing or verifying the
 * self-test, is WebCrypto failing, not the key, and throws too, keeping the key. Only a verify that
 * runs and answers `false` refuses the record. The grants
 * get the same rule from [IndexedDbDurableStore], whose failures are exceptions and whose `null` means
 * only "no record".
 *
 * ## Several stores, and several tabs
 *
 * Every call holds a mutex shared by every store over the same database in this page, and then the
 * browser's Web Lock of the same name, which every tab and worker of the origin shares. Both are read
 * afresh inside, so two stores, or two tabs opened at once on a first visit, are one device: the first
 * to make a key makes the only key, and neither loses the grants the other kept. Where the page has no
 * Web Locks (it is not a secure context, which also leaves it no WebCrypto) only the in-page mutex is
 * held. A store remembers the key it read, so a key another tab replaced afterwards is seen at the next
 * page load.
 *
 * Every IndexedDB connection is closed when the call that opened it returns, and closes itself when
 * another tab asks to upgrade the database, so this store never blocks a newer page's upgrade.
 *
 * @param database the IndexedDB database the key pair and the grants are kept in.
 * @param grantMedium how a call reaches the grants record; the real one opens [database] through
 *   [IndexedDbDurableStore]. A seam only so the conformance suite can make the grants record
 *   unreadable without touching the key.
 * @param beforeKeyIsMade called each time this store is about to make a key, and at no other time:
 *   the conformance suite's gate.
 */
internal class WebCryptoDeviceKeyStore(
    private val database: String,
    private val grantMedium: GrantMedium,
    private val beforeKeyIsMade: suspend () -> Unit,
) : DeviceKeyStore {
    private val inPage: Mutex = mutexFor(database)

    /** Read and written only inside [exclusively] or as a plain read: wasmJs runs one thread per page. */
    private var key: DeviceKey? = null

    /** Guarded by [inPage]: one warning per store for an unreadable grants record. */
    private var warnedAboutGrants = false

    override suspend fun deviceKey(): DeviceKey = key ?: exclusively { key ?: readOrMake().also { key = it } }

    override suspend fun grants(): List<Grant> = exclusively { grantMedium.use { readGrants(it) } }

    override suspend fun keepGrant(grant: Grant): Unit = exclusively {
        grantMedium.use { grants ->
            val current = readGrants(grants)
            val next = current.keeping(grant)
            if (next !== current) grants.write(GRANTS, StoredGrants.encode(next))
        }
    }

    private suspend fun <T> exclusively(block: suspend () -> T): T = inPage.withLock { withWebLock(database, block) }

    private suspend fun readOrMake(): DeviceKey {
        val stored = idbCall<JsAny?>("reading the device key") { onSuccess, onError ->
            idbGetValue(database, DEVICE_KEY, onSuccess, onError)
        } ?: return make()
        return when (val read = readBack(stored)) {
            is WebCryptoRead.Usable -> read.key
            is WebCryptoRead.Refused -> make().also {
                logger.warn {
                    "kuilt-identity: the device key in IndexedDB database \"$database\" cannot be used: ${read.reason}. " +
                        "It is overwritten with a new key, so this device is a new device to every room it returns to."
                }
            }
        }
    }

    private suspend fun make(): DeviceKey {
        beforeKeyIsMade()
        val pair = generateKeyPair()
        val id = nameOf(publicKeyOf(pair)) ?: throw IllegalStateException(
            "kuilt-identity: WebCrypto made a P-256 key whose public half is not a P-256 point",
        )
        idbCall<Unit>("writing the device key") { onSuccess, onError ->
            idbPutValue(database, DEVICE_KEY, pair, { onSuccess(Unit) }, onError)
        }
        return WebCryptoDeviceKey(id, privateKeyOf(pair))
    }

    private suspend fun readGrants(grants: DurableStore): List<Grant> {
        val record = grants.read(GRANTS) ?: return emptyList()
        return StoredGrants.decode(record) ?: emptyList<Grant>().also {
            if (!warnedAboutGrants) {
                warnedAboutGrants = true
                logger.warn {
                    "kuilt-identity: the grants kept in IndexedDB database \"$database\" cannot be read and are " +
                        "dropped; this device returns to each room as an ordinary admission until it is granted again."
                }
            }
        }
    }

    internal companion object {
        /** The record the key pair is kept under, in the database's `kv` object store. */
        const val DEVICE_KEY: String = "device-key"

        /** The [StoreKey] the grants record is kept under, beside the key pair. */
        val GRANTS: StoreKey = StoreKey("grants")

        private val registryLock = reentrantLock()

        /** One mutex per database for the life of the page; a handful of entries. */
        private val mutexes = HashMap<String, Mutex>()

        private fun mutexFor(database: String): Mutex = registryLock.withLock { mutexes.getOrPut(database) { Mutex() } }
    }
}

/** How a [WebCryptoDeviceKeyStore] call reaches the grants record: open, run [use]'s block, close. */
internal interface GrantMedium {
    suspend fun <T> use(block: suspend (DurableStore) -> T): T
}

/** The real [GrantMedium]: [database] through [IndexedDbDurableStore], one connection per call. */
internal class IndexedDbGrantMedium(private val database: String) : GrantMedium {
    override suspend fun <T> use(block: suspend (DurableStore) -> T): T {
        val store = IndexedDbDurableStore.open(database)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }
}

/**
 * What a stored record turned out to be. [readBack] answers [Refused] only for what the record holds;
 * an IndexedDB or WebCrypto call that fails throws.
 */
internal sealed interface WebCryptoRead {
    class Usable(val key: WebCryptoDeviceKey) : WebCryptoRead

    /** Not a usable key, and [reason] says why in words that carry no key material. */
    class Refused(val reason: String) : WebCryptoRead {
        override fun toString(): String = "Refused($reason)"
    }
}

/**
 * The key [stored] holds, checked as a key this store would have made and against the name its own
 * public half gives — never one derived from the private half: sign [SELF_TEST] with the private half
 * and verify it under the name, through the point [decompress] computes from the name, so through the
 * on-curve check every other device applies to it.
 *
 * Refused: a record that is not a pair of ECDSA P-256 `CryptoKey`s with a non-extractable private half
 * that may sign and an exportable public half; a public half whose raw export is not an uncompressed
 * point, or a name whose point is not on P-256; and a signature WebCrypto, having run, says does not
 * verify. Thrown: WebCrypto failing to export, decode or import the public half, to sign, or to verify,
 * which says nothing about the record. [DidKey.verify] is not the check here for that reason: it
 * answers `false` for a provider that failed, and a store that read that `false` as a bad key would
 * overwrite a good one on a transient browser fault.
 */
internal suspend fun readBack(stored: JsAny): WebCryptoRead {
    if (!isUsableKeyPair(stored)) {
        return WebCryptoRead.Refused("it is not a non-extractable ECDSA P-256 key pair that may sign")
    }
    val id = nameOf(publicKeyOf(stored))
        ?: return WebCryptoRead.Refused("its public half is not an uncompressed P-256 point")
    val point = decompress(id.compressedPoint())
        ?: return WebCryptoRead.Refused("its name, ${id.value}, is not a point on P-256")
    val key = WebCryptoDeviceKey(id, privateKeyOf(stored))
    val signature = key.sign(SELF_TEST)
    val named = cryptoCall<JsAny>("importing the public key its name gives") { onSuccess, onError ->
        subtleImportRawPublicKey(point.toUint8Array(), onSuccess, onError)
    }
    val verifies = cryptoCall<Boolean>("verifying the self-test") { onSuccess, onError ->
        subtleVerify(named, signature.toUint8Array(), SELF_TEST.toUint8Array(), onSuccess, onError)
    }
    if (!verifies) {
        return WebCryptoRead.Refused("its private half does not sign for the name its public half gives, ${id.value}")
    }
    return WebCryptoRead.Usable(key)
}

/**
 * The `did:key` [publicKey] names: its raw `0x04 ‖ x ‖ y` export, decoded and compressed through
 * cryptography-kotlin. `null` only when the export is not 65 bytes led by `0x04`, which is what the
 * record holds.
 *
 * @throws IllegalStateException when WebCrypto cannot export the public half, or the decoder fails on
 *   the point it exported: a `CryptoKey` holds a point WebCrypto already accepted, so a refusal there is
 *   WebCrypto failing, not the record.
 */
internal suspend fun nameOf(publicKey: JsAny): DidKey? {
    val raw = cryptoCall<JsAny>("exporting the public key") { onSuccess, onError -> subtleExportRaw(publicKey, onSuccess, onError) }
        .toByteArray()
    if (raw.size != UNCOMPRESSED_POINT_BYTES || raw[0] != UNCOMPRESSED) return null
    val ecdsa = algorithms().ecdsa
    val compressed = try {
        ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(EC.PublicKey.Format.RAW, raw)
            .encodeToByteArray(EC.PublicKey.Format.RAW.Compressed)
    } catch (e: CancellationException) {
        // My own cancellation propagates; one the provider minted while this job is alive is WebCrypto
        // failing, like every other failure below.
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("kuilt-identity: WebCrypto failed decoding a public key (${e::class.simpleName})")
    } catch (e: Error) {
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: a WebCrypto rejection arrives on wasmJs as a bare JsException.
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("kuilt-identity: WebCrypto failed decoding a public key (${e::class.simpleName})")
    }
    return DidKey.ofCompressedPoint(compressed)
}

/**
 * A key WebCrypto holds and will not hand over: it signs through `crypto.subtle.sign`, whose ECDSA
 * output is already the 64-byte raw `r ‖ s` [DeviceKey.sign] promises. Holds a `CryptoKey`, never key
 * bytes.
 */
internal class WebCryptoDeviceKey(override val id: DidKey, internal val privateKey: JsAny) : DeviceKey {
    override suspend fun sign(message: ByteArray): ByteArray {
        val signature = cryptoCall<JsAny>("signing") { onSuccess, onError ->
            subtleSign(privateKey, message.toUint8Array(), onSuccess, onError)
        }.toByteArray()
        check(signature.size == RAW_SIGNATURE_BYTES) {
            "kuilt-identity: WebCrypto returned a ${signature.size}-byte ECDSA signature, not raw r ‖ s"
        }
        return signature
    }

    override fun toString(): String = "WebCryptoDeviceKey(${id.value})"
}

/**
 * This device's key store in the browser: a non-extractable WebCrypto key in the IndexedDB database
 * `kuilt-identity-<appId>`, with the grants beside it. Opening it again with the same [appId] after a
 * reload is the same device, and so is every tab of the origin; calling it twice gives two stores that
 * are one device.
 *
 * The private half cannot leave the browser: WebCrypto made it non-extractable, so no script on the
 * page, this library's included, can export it, and a browser does not sync IndexedDB between
 * machines. Clearing the site's data deletes the key, and the next visit is a new device.
 *
 * A damaged record is overwritten with a new key, with a warning, and the app carries on as a new
 * device. An IndexedDB that cannot answer right now — a database a newer page upgraded, a closing
 * connection, a full disk — or a WebCrypto call that rejects makes [DeviceKeyStore.deviceKey] throw
 * [IllegalStateException] and keeps the key.
 *
 * Needs a secure context (`https:` or `localhost`), where WebCrypto and Web Locks exist; elsewhere the
 * first [DeviceKeyStore.deviceKey] throws [IllegalStateException]. Works in a page and in a worker.
 * Nothing touches IndexedDB until the first call.
 *
 * @param appId one segment naming the app: letters, digits, `.`, `_` and `-`, and neither `.` nor `..`,
 *   the same rule as every other platform's `platform`, so one id works everywhere.
 * @throws IllegalArgumentException when [appId] is not one such segment.
 */
public fun DeviceKeyStore.Companion.platform(appId: String): DeviceKeyStore {
    require(APP_ID.matches(appId) && appId != "." && appId != "..") {
        "appId must be one segment of letters, digits, '.', '_' and '-', and not '.' or '..': \"$appId\""
    }
    val database = "kuilt-identity-$appId"
    return WebCryptoDeviceKeyStore(database, IndexedDbGrantMedium(database)) {}
}

private val APP_ID = Regex("[A-Za-z0-9._-]+")

/** What the self-test signs: fixed, since it proves only that the key signs for its name. */
private val SELF_TEST = "kuilt-identity device-key self-test".encodeToByteArray()

private const val UNCOMPRESSED_POINT_BYTES = 65
private const val UNCOMPRESSED: Byte = 0x04
private const val RAW_SIGNATURE_BYTES = 64

/** A new non-extractable P-256 signing pair, as WebCrypto's `CryptoKeyPair`. */
internal suspend fun generateKeyPair(): JsAny =
    cryptoCall<JsAny>("generating a P-256 key") { onSuccess, onError -> subtleGenerateKeyPair(onSuccess, onError) }

// ── Awaiting the bridges ──────────────────────────────────────────────────────

/**
 * Runs an IndexedDB bridge and awaits its callback. A failure throws [IllegalStateException] carrying
 * the DOM error's name and message: text about the database, never key material.
 */
internal suspend fun <T> idbCall(doing: String, start: (onSuccess: (T) -> Unit, onError: (String) -> Unit) -> Unit): T {
    val result = CompletableDeferred<T>()
    start({ result.complete(it) }, { result.completeExceptionally(IllegalStateException("kuilt-identity: IndexedDB failed $doing ($it)")) })
    return result.await()
}

/**
 * Runs a WebCrypto bridge and awaits its callback. A failure throws [IllegalStateException] carrying
 * only the DOM error's name, as every provider call in this module does.
 */
private suspend fun <T> cryptoCall(doing: String, start: (onSuccess: (T) -> Unit, onError: (String) -> Unit) -> Unit): T {
    val result = CompletableDeferred<T>()
    start({ result.complete(it) }, { result.completeExceptionally(IllegalStateException("kuilt-identity: WebCrypto failed $doing ($it)")) })
    return result.await()
}

/**
 * Runs [block] holding the Web Lock [name], which every tab and worker of the origin shares, or holds
 * nothing where the page has no Web Locks. The lock is released when [block] returns or throws, and
 * a lock granted after this coroutine was cancelled is released at once rather than held forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <T> withWebLock(name: String, block: suspend () -> T): T {
    val held = CompletableDeferred<JsAny?>()
    webLockRequest(
        name,
        onHeld = { release -> if (!held.complete(release)) release?.let(::callRelease) },
        onError = { held.completeExceptionally(IllegalStateException("kuilt-identity: the Web Lock \"$name\" failed ($it)")) },
    )
    val release = try {
        held.await()
    } catch (e: CancellationException) {
        // Cancelled first, so a lock granted later finds `complete` refused and releases itself in onHeld;
        // granted already, the release is ours to call.
        held.cancel()
        if (held.isCompleted && !held.isCancelled) held.getCompleted()?.let(::callRelease)
        throw e
    }
    try {
        return block()
    } finally {
        release?.let(::callRelease)
    }
}

// ── Bridges ───────────────────────────────────────────────────────────────────
//
// Every call that can throw synchronously sits inside a try/catch that hands the error to `onError`: a
// JS throw left to cross the bridge would arrive as a JsException, which is not an Exception (#3143).
// Every IndexedDB connection is closed before its callback runs, and on another tab's upgrade.

@JsFun(
    """
    (dbName, key, onSuccess, onError) => {
        const describe = (e) => (e && e.name ? e.name + ': ' + e.message : String(e));
        let req;
        try {
            req = indexedDB.open(dbName, 1);
        } catch (e) { onError(describe(e)); return; }
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
        };
        req.onerror = (e) => onError(e.target.error ? describe(e.target.error) : 'open error');
        req.onblocked = () => {};
        req.onsuccess = (e) => {
            const db = e.target.result;
            db.onversionchange = () => db.close();
            let get;
            try {
                get = db.transaction('kv', 'readonly').objectStore('kv').get(key);
            } catch (err) { db.close(); onError(describe(err)); return; }
            get.onsuccess = (ev) => { const v = ev.target.result; db.close(); onSuccess(v === undefined ? null : v); };
            get.onerror = (ev) => { db.close(); onError(ev.target.error ? describe(ev.target.error) : 'get error'); };
        };
    }
    """,
)
internal external fun idbGetValue(dbName: String, key: String, onSuccess: (JsAny?) -> Unit, onError: (String) -> Unit)

/** Stores [value] under [key], calling [onComplete] once the transaction commits. */
@JsFun(
    """
    (dbName, key, value, onComplete, onError) => {
        const describe = (e) => (e && e.name ? e.name + ': ' + e.message : String(e));
        let req;
        try {
            req = indexedDB.open(dbName, 1);
        } catch (e) { onError(describe(e)); return; }
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
        };
        req.onerror = (e) => onError(e.target.error ? describe(e.target.error) : 'open error');
        req.onsuccess = (e) => {
            const db = e.target.result;
            db.onversionchange = () => db.close();
            try {
                const tx = db.transaction('kv', 'readwrite');
                tx.oncomplete = () => { db.close(); onComplete(); };
                tx.onerror = (ev) => { db.close(); onError(ev.target.error ? describe(ev.target.error) : 'put error'); };
                tx.onabort = (ev) => { db.close(); onError(ev.target.error ? describe(ev.target.error) : 'put aborted'); };
                if (value === null) tx.objectStore('kv').delete(key); else tx.objectStore('kv').put(value, key);
            } catch (err) { db.close(); onError(describe(err)); }
        };
    }
    """,
)
internal external fun idbPutValue(dbName: String, key: String, value: JsAny?, onComplete: () -> Unit, onError: (String) -> Unit)

@JsFun(
    """
    (onSuccess, onError) => {
        try {
            crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify'])
                .then((pair) => onSuccess(pair), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun subtleGenerateKeyPair(onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)

@JsFun(
    """
    (key, onSuccess, onError) => {
        try {
            crypto.subtle.exportKey('raw', key)
                .then((buffer) => onSuccess(new Uint8Array(buffer)), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun subtleExportRaw(key: JsAny, onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)

@JsFun(
    """
    (key, data, onSuccess, onError) => {
        try {
            crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, data)
                .then((buffer) => onSuccess(new Uint8Array(buffer)), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun subtleSign(key: JsAny, data: JsAny, onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)

/** Imports a 65-byte uncompressed P-256 point as an ECDSA public key that may verify. */
@JsFun(
    """
    (point, onSuccess, onError) => {
        try {
            crypto.subtle.importKey('raw', point, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
                .then((key) => onSuccess(key), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun subtleImportRawPublicKey(point: JsAny, onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)

/**
 * Whether [signature] verifies over [data] under [key]: [onSuccess] with WebCrypto's answer once the
 * verify has run, [onError] when it rejected, which is no answer at all.
 */
@JsFun(
    """
    (key, signature, data, onSuccess, onError) => {
        try {
            crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signature, data)
                .then((ok) => onSuccess(ok === true), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun subtleVerify(key: JsAny, signature: JsAny, data: JsAny, onSuccess: (Boolean) -> Unit, onError: (String) -> Unit)

/**
 * Whether [value] is a key pair this store would have made: two `CryptoKey`s, ECDSA on P-256, an
 * exportable public half and a non-extractable private half that may sign.
 */
@JsFun(
    """
    (value) => {
        try {
            if (typeof CryptoKey === 'undefined' || value === null || typeof value !== 'object') return false;
            const pub = value.publicKey, priv = value.privateKey;
            if (!(pub instanceof CryptoKey) || !(priv instanceof CryptoKey)) return false;
            const p256 = (k) => k.algorithm && k.algorithm.name === 'ECDSA' && k.algorithm.namedCurve === 'P-256';
            return pub.type === 'public' && priv.type === 'private' && p256(pub) && p256(priv)
                && pub.extractable === true && priv.extractable === false && priv.usages.includes('sign');
        } catch (e) { return false; }
    }
    """,
)
private external fun isUsableKeyPair(value: JsAny): Boolean

@JsFun("(pair) => pair.publicKey")
internal external fun publicKeyOf(pair: JsAny): JsAny

@JsFun("(pair) => pair.privateKey")
internal external fun privateKeyOf(pair: JsAny): JsAny

/**
 * Asks for the Web Lock [name] and calls [onHeld] with the function that releases it, or with `null`
 * at once where there are no Web Locks.
 */
@JsFun(
    """
    (name, onHeld, onError) => {
        const describe = (e) => (e && e.name ? e.name + ': ' + e.message : String(e));
        try {
            const locks = globalThis.navigator && globalThis.navigator.locks;
            if (!locks) { onHeld(null); return; }
            locks.request(name, () => new Promise((release) => onHeld(() => release()))).catch((e) => onError(describe(e)));
        } catch (e) { onError(describe(e)); }
    }
    """,
)
private external fun webLockRequest(name: String, onHeld: (JsAny?) -> Unit, onError: (String) -> Unit)

@JsFun("(release) => { try { release(); } catch (e) {} }")
private external fun callRelease(release: JsAny)

// ── ByteArray ↔ Uint8Array ────────────────────────────────────────────────────

@JsFun("(length) => new Uint8Array(length)")
private external fun newUint8Array(length: Int): JsAny

@JsFun("(view, index, byte) => { view[index] = byte & 0xff; }")
private external fun uint8ArraySet(view: JsAny, index: Int, byte: Byte)

@JsFun("(view) => view.length")
private external fun uint8ArrayLength(view: JsAny): Int

@JsFun("(view, index) => (view[index] << 24 >> 24)")
private external fun uint8ArrayGet(view: JsAny, index: Int): Byte

internal fun ByteArray.toUint8Array(): JsAny {
    val view = newUint8Array(size)
    for (i in indices) uint8ArraySet(view, i, this[i])
    return view
}

internal fun JsAny.toByteArray(): ByteArray = ByteArray(uint8ArrayLength(this)) { i -> uint8ArrayGet(this, i) }
