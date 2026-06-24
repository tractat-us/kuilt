@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionName")

package us.tractat.kuilt.otel

import kotlinx.coroutines.CompletableDeferred
import kotlin.JsFun
import kotlin.js.JsAny

/**
 * A [DurableStore] backed by the browser's IndexedDB.
 *
 * Each store instance owns a named IDB database with one object store
 * (`"kv"`) that maps string keys to binary values (`Uint8Array`).
 *
 * **Durability contract:** [write] suspends until the IDB transaction
 * fires its `complete` event — that is the IndexedDB durability point,
 * not the `put` request's `onsuccess`.  A crash before `complete` does
 * **not** guarantee the write survived; a crash after `complete` does.
 *
 * **Usage:** call [IndexedDbDurableStore.open] to obtain a ready-to-use
 * instance.  The returned store has already opened the underlying IDB
 * database and is ready for reads and writes.  Call [close] when done.
 *
 * @param dbName The IndexedDB database name. Use a stable, app-specific
 *   name in production.  Tests should use unique names per test so that
 *   runs do not share state.
 */
public class IndexedDbDurableStore private constructor(private val db: JsAny) : DurableStore {

    public companion object {
        /** Open (or create) the IndexedDB database and return a ready store. */
        public suspend fun open(dbName: String): IndexedDbDurableStore {
            val deferred = CompletableDeferred<JsAny>()
            idbOpen(
                dbName,
                onSuccess = { db -> deferred.complete(db) },
                onError = { msg -> deferred.completeExceptionally(IndexedDbException(msg)) },
            )
            return IndexedDbDurableStore(deferred.await())
        }
    }

    override suspend fun read(key: StoreKey): ByteArray? {
        val deferred = CompletableDeferred<JsAny?>()
        idbGet(
            db,
            key.name,
            onSuccess = { value -> deferred.complete(value) },
            onError = { msg -> deferred.completeExceptionally(IndexedDbException(msg)) },
        )
        val result = deferred.await() ?: return null
        return uint8ArrayToByteArray(result)
    }

    override suspend fun write(key: StoreKey, bytes: ByteArray) {
        val jsBytes = byteArrayToUint8Array(bytes)
        val deferred = CompletableDeferred<Unit>()
        // Suspend until `oncomplete` — that is the IDB durability point.
        idbPut(
            db,
            key.name,
            jsBytes,
            onComplete = { deferred.complete(Unit) },
            onError = { msg -> deferred.completeExceptionally(IndexedDbException(msg)) },
        )
        deferred.await()
    }

    override suspend fun delete(key: StoreKey) {
        val deferred = CompletableDeferred<Unit>()
        idbDelete(
            db,
            key.name,
            onComplete = { deferred.complete(Unit) },
            onError = { msg -> deferred.completeExceptionally(IndexedDbException(msg)) },
        )
        deferred.await()
    }

    /** Close the underlying IDB database connection. */
    public fun close() {
        idbClose(db)
    }
}

/** Wraps an IndexedDB error message from the JS layer. */
internal class IndexedDbException(message: String) : Exception(message)

// ── IDB helper bridge functions ───────────────────────────────────────────────
//
// Each function accepts only types that cross the Kotlin/Wasm boundary:
// primitives, String, JsAny, and function types. Kotlin lambdas are
// accepted as @JsFun function-type parameters.
//
// js() is used only inside top-level bridge functions (one expression per
// function body), which is the only form supported by Kotlin/Wasm.

/**
 * Open (or upgrade) the IDB database at [dbName].
 *
 * Creates the `"kv"` object store on first open (or version upgrade).
 * Calls [onSuccess] with the `IDBDatabase` object, or [onError] with
 * an error message string.
 */
@JsFun(
    """
    (dbName, onSuccess, onError) => {
        const req = indexedDB.open(dbName, 1);
        req.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('kv')) {
                db.createObjectStore('kv');
            }
        };
        req.onsuccess = (e) => onSuccess(e.target.result);
        req.onerror = (e) => onError(e.target.error ? e.target.error.message : 'open error');
    }
    """,
)
private external fun idbOpen(
    dbName: String,
    onSuccess: (JsAny) -> Unit,
    onError: (String) -> Unit,
)

/**
 * Read the value under [key] from the `"kv"` store.
 *
 * Calls [onSuccess] with the stored [JsAny] (a `Uint8Array`), or `null`
 * if the key is absent. Calls [onError] with an error message on failure.
 */
@JsFun(
    """
    (db, key, onSuccess, onError) => {
        const tx = db.transaction('kv', 'readonly');
        const req = tx.objectStore('kv').get(key);
        req.onsuccess = (e) => onSuccess(e.target.result !== undefined ? e.target.result : null);
        req.onerror = (e) => onError(e.target.error ? e.target.error.message : 'get error');
    }
    """,
)
private external fun idbGet(
    db: JsAny,
    key: String,
    onSuccess: (JsAny?) -> Unit,
    onError: (String) -> Unit,
)

/**
 * Durably store [value] (a `Uint8Array`) under [key] in the `"kv"` store.
 *
 * Calls [onComplete] after the transaction's `oncomplete` event — that is
 * the IDB durability point. Calls [onError] if the transaction aborts.
 */
@JsFun(
    """
    (db, key, value, onComplete, onError) => {
        const tx = db.transaction('kv', 'readwrite');
        tx.oncomplete = () => onComplete();
        tx.onerror = (e) => onError(e.target.error ? e.target.error.message : 'put error');
        tx.onabort = (e) => onError(e.target.error ? e.target.error.message : 'put aborted');
        tx.objectStore('kv').put(value, key);
    }
    """,
)
private external fun idbPut(
    db: JsAny,
    key: String,
    value: JsAny,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
)

/**
 * Delete the entry under [key] from the `"kv"` store.
 *
 * Calls [onComplete] after the transaction's `oncomplete` event.
 * No-op if the key is absent. Calls [onError] if the transaction aborts.
 */
@JsFun(
    """
    (db, key, onComplete, onError) => {
        const tx = db.transaction('kv', 'readwrite');
        tx.oncomplete = () => onComplete();
        tx.onerror = (e) => onError(e.target.error ? e.target.error.message : 'delete error');
        tx.onabort = (e) => onError(e.target.error ? e.target.error.message : 'delete aborted');
        tx.objectStore('kv').delete(key);
    }
    """,
)
private external fun idbDelete(
    db: JsAny,
    key: String,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
)

/**
 * Close the IDB database connection.
 *
 * After calling this, no further operations may be performed on the
 * database object.
 */
@JsFun("(db) => db.close()")
private external fun idbClose(db: JsAny)

// ── ByteArray ↔ Uint8Array helpers ───────────────────────────────────────────
//
// Kotlin/Wasm cannot pass ByteArray directly across the JS boundary.
// We bridge through JS Uint8Array: allocate by size, set byte-by-byte,
// and read byte-by-byte on the return path.

/** Allocate a new JS `Uint8Array` of [length] bytes. */
@JsFun("(length) => new Uint8Array(length)")
private external fun newUint8Array(length: Int): JsAny

/** Write [byte] at [index] in a `Uint8Array` [view]. */
@JsFun("(view, index, byte) => { view[index] = byte & 0xff; }")
private external fun uint8ArraySet(view: JsAny, index: Int, byte: Byte)

/** Return the length of a `Uint8Array` [view]. */
@JsFun("(view) => view.length")
private external fun uint8ArrayLength(view: JsAny): Int

/** Read a byte at [index] from a `Uint8Array` [view] as a signed byte. */
@JsFun("(view, index) => (view[index] << 24 >> 24)")
private external fun uint8ArrayGet(view: JsAny, index: Int): Byte

private fun byteArrayToUint8Array(bytes: ByteArray): JsAny {
    val view = newUint8Array(bytes.size)
    for (i in bytes.indices) uint8ArraySet(view, i, bytes[i])
    return view
}

private fun uint8ArrayToByteArray(view: JsAny): ByteArray {
    val length = uint8ArrayLength(view)
    return ByteArray(length) { i -> uint8ArrayGet(view, i) }
}
