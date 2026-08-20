@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.store

import kotlinx.coroutines.CompletableDeferred
import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.RestartFixture
import kotlin.JsFun

/**
 * Verifies [IndexedDbDurableStore] satisfies the whole [DurableStoreConformanceSuite] — in a real
 * browser, which is the only place IndexedDB exists.
 *
 * Every test this class used to hold by hand — absent key, round trip, overwrite, delete, independent
 * keys, all 256 byte values, and the "crash" simulated by closing one connection and opening another
 * against the same database — is now a property of the shared contract, checked here and on the other
 * three backends alike. Nothing was dropped; several are now stated more strongly.
 */
class IndexedDbDurableStoreTest : DurableStoreConformanceSuite() {

    private val databases = mutableMapOf<DurableStore, String>()

    override suspend fun newStore(): DurableStore {
        val name = "kuilt-store-conformance-${nextDatabaseId++}"
        // A browser's IndexedDB outlives the page, so a name derived from a counter alone comes back
        // on the next run holding the last run's records — and every absence assertion in the suite
        // would then be checking a previous run's state. Wipe first; the counter is what keeps two
        // stores alive inside one test apart.
        deleteDatabase(name)
        return IndexedDbDurableStore.open(name).also { databases[it] = name }
    }

    /**
     * A restart, modelled the way this store's own contract defines one: **close the connection and
     * open a fresh one against the same database**. The new handle shares no state with the closed
     * one, so everything it answers came out of IndexedDB.
     */
    override suspend fun restart(store: DurableStore): RestartFixture {
        val name = requireNotNull(databases[store]) { "restart() was handed a store this fixture did not create" }
        (store as IndexedDbDurableStore).close()
        return RestartFixture.Durable(IndexedDbDurableStore.open(name))
    }
}

/** Drop the whole database at [name], whether or not it exists. */
private suspend fun deleteDatabase(name: String) {
    val done = CompletableDeferred<Unit>()
    idbDeleteDatabase(name) { done.complete(Unit) }
    done.await()
}

/**
 * Delete the IndexedDB database [name], calling [onDone] once the request settles.
 *
 * `onblocked` completes too, not just `onsuccess`: a delete blocked by a still-open connection would
 * otherwise never call back and hang the test rather than failing it. Nothing here holds a connection
 * to a name it is about to delete, so that path is a backstop rather than an expected one.
 */
@JsFun(
    """
    (name, onDone) => {
        const req = indexedDB.deleteDatabase(name);
        req.onsuccess = () => onDone();
        req.onerror = () => onDone();
        req.onblocked = () => onDone();
    }
    """,
)
private external fun idbDeleteDatabase(name: String, onDone: () -> Unit)

/**
 * File-level, not a class property: the test framework builds a fresh instance of the test class for
 * every test function, so a per-instance counter would restart at zero in each of them and hand every
 * test the same database.
 */
private var nextDatabaseId = 0
