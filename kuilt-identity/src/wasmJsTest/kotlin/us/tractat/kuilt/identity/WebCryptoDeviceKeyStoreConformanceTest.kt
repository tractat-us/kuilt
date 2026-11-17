@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.identity

import us.tractat.kuilt.conformance.DeviceKeyStoreConformanceSuite
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.KeyWriteInterruption
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableGrants
import us.tractat.kuilt.conformance.DeviceKeyStoreFixture.UnreadableKey
import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.IndexedDbDurableStore
import us.tractat.kuilt.store.StoreKey
import kotlin.js.JsAny
import kotlin.random.Random

/**
 * [WebCryptoDeviceKeyStore] against the whole [DeviceKeyStoreConformanceSuite], as a
 * [DeviceKeyStoreFixture.Durable] store over a fresh IndexedDB database per fixture, in the browser the
 * wasmJs tests run in. Every reopen is a new store over the same database, as a page reload is.
 *
 * The key hooks all act on the real database and real WebCrypto keys:
 *
 * - **corrupt key:** three bytes stored where the key pair should be;
 * - **mismatched key:** one non-extractable pair's public half beside another's private half, both made
 *   by WebCrypto, so the record passes every structural check and fails only the self-test. WebCrypto
 *   checks nothing about a pair it did not make, so this is a state a real database can hold;
 * - **cleared key:** the `device-key` record deleted, leaving the grants;
 * - **interrupted write** (`AtomicMedium`): for the reason the hook gives;
 * - **unreadable key** (`Rigged`): the database upgraded to version 2, as a newer page would, so the
 *   store's `indexedDB.open(name, 1)` fails with a real `VersionError`. Restoring copies every record,
 *   the `CryptoKeyPair` included, into a recreated version-1 database: a `CryptoKey` survives structured
 *   clone, so the very same key comes back;
 * - **corrupt grants:** three bytes that are not a grants record, through [IndexedDbDurableStore].
 *
 * **The one hook that does not act on the real database: unreadable grants.** IndexedDB has no fault a
 * test can raise on one record while the database still takes another: its failures are per database
 * (a version, a closing connection, quota) or come from inside the browser's own storage. So the
 * fixture reaches the grants through a [FaultingGrantMedium] over the real [IndexedDbDurableStore],
 * which throws on reading the grants record while the rig is set and passes everything else through.
 * The store's half — a read that throws is not "no grants", and nothing is written over it — is what
 * the property tests, and a store that read the throw as an empty list reds. What it cannot see is
 * whether [IndexedDbDurableStore] itself turns a real IndexedDB read failure into a throw; that is
 * `:kuilt-store`'s own suite's question (#3143).
 */
class WebCryptoDeviceKeyStoreConformanceTest : DeviceKeyStoreConformanceSuite() {
    override suspend fun newFixture(): DeviceKeyStoreFixture {
        val database = freshDatabase()
        val grants = FaultingGrantMedium(IndexedDbGrantMedium(database))
        return DeviceKeyStoreFixture.Durable(
            reopen = { beforeKeyIsMade -> WebCryptoDeviceKeyStore(database, grants, beforeKeyIsMade) },
            corruptKey = { putRecord(database, byteArrayOf(9, 9, 9).toUint8Array()) },
            plantMismatchedKey = {
                val named = generateKeyPair()
                val signer = generateKeyPair()
                putRecord(database, pairOf(publicKeyOf(named), privateKeyOf(signer)))
                independentNameOf(publicKeyOf(named))
            },
            clearKey = {
                check(readRecord(database) != null) { "precondition: there was a key to clear" }
                putRecord(database, null)
            },
            interruptKeyWrite = {
                KeyWriteInterruption.AtomicMedium(
                    "The key pair is one IndexedDB record, written by one put in one readwrite transaction, and " +
                        "IndexedDB commits a transaction whole or not at all: a crash before it commits leaves the " +
                        "record as it was, and one after leaves the new pair. Nothing else is written for a key.",
                )
            },
            corruptGrants = {
                val store = IndexedDbDurableStore.open(database)
                try {
                    store.write(WebCryptoDeviceKeyStore.GRANTS, byteArrayOf(9, 9, 9))
                } finally {
                    store.close()
                }
            },
            makeKeyUnreadable = {
                idbCall<Unit>("upgrading the database") { onSuccess, onError -> upgradeToVersion2(database, { onSuccess(Unit) }, onError) }
                // The rig must fire: a read straight off the database now fails as the store's will.
                val probe = try {
                    readRecord(database)
                    null
                } catch (e: IllegalStateException) {
                    e
                }
                check(probe?.message.orEmpty().contains("VersionError")) { "rig: the upgraded database still opened at version 1 ($probe)" }
                UnreadableKey.Rigged {
                    idbCall<Unit>("restoring the database") { onSuccess, onError -> restoreToVersion1(database, { onSuccess(Unit) }, onError) }
                }
            },
            makeGrantsUnreadable = { grants.fault() },
        )
    }
}

/** A database no fixture has used, so no test sees another's key. */
internal fun freshDatabase(): String = "kuilt-identity-test-${Random.nextLong().toULong().toString(16)}"

/** The `device-key` record of [database], or `null` where there is none. */
internal suspend fun readRecord(database: String): JsAny? = idbCall<JsAny?>("reading the record") { onSuccess, onError ->
    idbGetValue(database, WebCryptoDeviceKeyStore.DEVICE_KEY, onSuccess, onError)
}

/** Stores [value] as [database]'s `device-key` record, or deletes the record for `null`. */
internal suspend fun putRecord(database: String, value: JsAny?) {
    idbCall<Unit>("writing the record") { onSuccess, onError ->
        idbPutValue(database, WebCryptoDeviceKeyStore.DEVICE_KEY, value, { onSuccess(Unit) }, onError)
    }
}

/**
 * The name [publicKey] should carry, worked out apart from the store's own `nameOf`: its raw export
 * compressed by hand, `x` behind the lead byte `y`'s parity picks.
 */
internal suspend fun independentNameOf(publicKey: JsAny): DidKey {
    val raw = idbCall<JsAny>("exporting the public key") { onSuccess, onError -> exportRawPublicKey(publicKey, onSuccess, onError) }
        .toByteArray()
    check(raw.size == 65 && raw[0] == 0x04.toByte()) { "not an uncompressed P-256 point: ${raw.size} bytes" }
    val lead: Byte = if (raw[64].toInt() and 1 == 1) 0x03 else 0x02
    return DidKey.ofCompressedPoint(byteArrayOf(lead) + raw.copyOfRange(1, 33))
}

/**
 * The grants record reached through [inner], made unreadable on demand: while [fault]'s rig is set,
 * reading the grants record throws as a failed IndexedDB read would, and every other call, a write
 * of the grants record included, goes through. Writes and deletes of the grants record made during
 * the fault are counted, so the rig can say whether the store rewrote a record it could not read.
 */
internal class FaultingGrantMedium(private val inner: GrantMedium) : GrantMedium {
    private var faulted = false
    private var writesWhileFaulted = 0

    override suspend fun <T> use(block: suspend (DurableStore) -> T): T = inner.use { block(Faulting(it)) }

    fun fault(): UnreadableGrants {
        faulted = true
        writesWhileFaulted = 0
        return UnreadableGrants(
            probe = { use { it.read(WebCryptoDeviceKeyStore.GRANTS) } },
            probeWrite = { use { it.write(PROBE_WRITE, byteArrayOf(1)) } },
            restore = {
                faulted = false
                writesWhileFaulted > 0
            },
        )
    }

    private inner class Faulting(private val store: DurableStore) : DurableStore {
        override suspend fun read(key: StoreKey): ByteArray? {
            if (faulted && key == WebCryptoDeviceKeyStore.GRANTS) {
                throw IllegalStateException("UnknownError: the grants record cannot be read (rigged)")
            }
            return store.read(key)
        }

        override suspend fun write(key: StoreKey, bytes: ByteArray) {
            if (faulted && key == WebCryptoDeviceKeyStore.GRANTS) writesWhileFaulted++
            store.write(key, bytes)
        }

        override suspend fun delete(key: StoreKey) {
            if (faulted && key == WebCryptoDeviceKeyStore.GRANTS) writesWhileFaulted++
            store.delete(key)
        }
    }

    private companion object {
        /** A record beside the grants, written only by the unreadable-grants rig's write probe. */
        val PROBE_WRITE = StoreKey("probe-write")
    }
}

@JsFun("(publicKey, privateKey) => ({ publicKey: publicKey, privateKey: privateKey })")
internal external fun pairOf(publicKey: JsAny, privateKey: JsAny): JsAny

@JsFun(
    """
    (key, onSuccess, onError) => {
        try {
            crypto.subtle.exportKey('raw', key)
                .then((b) => onSuccess(new Uint8Array(b)), (e) => onError(e && e.name ? e.name : String(e)));
        } catch (e) { onError(e && e.name ? e.name : String(e)); }
    }
    """,
)
private external fun exportRawPublicKey(key: JsAny, onSuccess: (JsAny) -> Unit, onError: (String) -> Unit)

/** Upgrades [dbName] to version 2, changing nothing in it, as a newer page's upgrade would. */
@JsFun(
    """
    (dbName, onDone, onError) => {
        const describe = (e) => (e && e.name ? e.name + ': ' + e.message : String(e));
        try {
            const req = indexedDB.open(dbName, 2);
            // Not a failure: WebKit fires it for a connection whose close is still pending, and the
            // upgrade goes ahead once it has closed.
            req.onblocked = () => {};
            req.onerror = (e) => onError(describe(e.target.error));
            req.onsuccess = (e) => { e.target.result.close(); onDone(); };
        } catch (e) { onError(describe(e)); }
    }
    """,
)
private external fun upgradeToVersion2(dbName: String, onDone: () -> Unit, onError: (String) -> Unit)

/**
 * Copies every record of [dbName]'s `kv` store, `CryptoKey`s included, deletes the database, and
 * writes them back into a new version-1 database: the version the store opens.
 */
@JsFun(
    """
    (dbName, onDone, onError) => {
        const describe = (e) => (e && e.name ? e.name + ': ' + e.message : String(e));
        const fail = (e) => onError(describe(e));
        try {
            const open = indexedDB.open(dbName);
            open.onerror = (e) => fail(e.target.error);
            open.onsuccess = (e) => {
                const db = e.target.result;
                const keys = [], values = [];
                const read = db.transaction('kv', 'readonly');
                const cursor = read.objectStore('kv').openCursor();
                cursor.onerror = (ev) => { db.close(); fail(ev.target.error); };
                cursor.onsuccess = (ev) => {
                    const c = ev.target.result;
                    if (c) { keys.push(c.key); values.push(c.value); c.continue(); }
                };
                // Deleted only once the read transaction is over, so no connection of ours is open.
                read.oncomplete = () => {
                    db.close();
                    const del = indexedDB.deleteDatabase(dbName);
                    del.onblocked = () => {}; // as above: it waits for a closing connection, then deletes.
                    del.onerror = (dv) => fail(dv.target.error);
                    del.onsuccess = () => {
                        const reopen = indexedDB.open(dbName, 1);
                        reopen.onupgradeneeded = (uv) => uv.target.result.createObjectStore('kv');
                        reopen.onerror = (rv) => fail(rv.target.error);
                        reopen.onsuccess = (rv) => {
                            const fresh = rv.target.result;
                            const tx = fresh.transaction('kv', 'readwrite');
                            keys.forEach((k, i) => tx.objectStore('kv').put(values[i], k));
                            tx.oncomplete = () => { fresh.close(); onDone(); };
                            tx.onabort = (tv) => { fresh.close(); fail(tv.target.error); };
                        };
                    };
                };
            };
        } catch (e) { fail(e); }
    }
    """,
)
private external fun restoreToVersion1(dbName: String, onDone: () -> Unit, onError: (String) -> Unit)
