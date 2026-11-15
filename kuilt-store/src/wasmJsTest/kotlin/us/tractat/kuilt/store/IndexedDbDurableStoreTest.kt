@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.conformance.DurableStoreConformanceSuite
import us.tractat.kuilt.conformance.MediumFault
import us.tractat.kuilt.conformance.RestartFixture
import us.tractat.kuilt.test.TEST_WEDGE_BACKSTOP
import us.tractat.kuilt.test.assertAll
import kotlin.JsFun
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /**
     * Close the store's connection. Every later `db.transaction(…)` on it throws `InvalidStateError`
     * **synchronously**, inside the bridge — the throw #3143 is about, which used to escape as a
     * `JsException` that no `catch (e: Exception)` sees. The database itself is untouched, so healing
     * is opening a fresh connection onto it.
     *
     * This rig has no separate check that it took: a closed connection cannot be probed without the
     * very call under test. It needs none, because a rig that failed to break anything leaves the
     * suite's three properties RED (the read returns the value, the write and delete return normally),
     * never green.
     */
    override suspend fun faultMedium(store: DurableStore): MediumFault {
        val name = requireNotNull(databases[store]) { "faultMedium() was handed a store this fixture did not create" }
        (store as IndexedDbDurableStore).close()
        return MediumFault.Broken { IndexedDbDurableStore.open(name) }
    }

    /**
     * Opening a store where the page has no IndexedDB must throw an [Exception] (#3143).
     *
     * `indexedDB.open` throws **synchronously** — a `TypeError` when `indexedDB` is undefined, a
     * `SecurityError` on an opaque origin or with storage blocked — and that throw used to escape the
     * bridge as a `JsException`, which extends [Throwable] and not [Exception]. A page whose
     * `catch (e: Exception)` was meant to fall back to an empty board crashed instead.
     *
     * The rig hides `indexedDB` behind an own property of `undefined` on the global object, checks
     * that it took, and puts the original back in a `finally`. `SecurityError` cannot be produced from
     * inside a test page; the `TypeError` takes the same path through the bridge.
     */
    @Test
    fun openWhereIndexedDbIsMissingThrowsAnException() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        hideIndexedDb()
        val failure = try {
            check(!indexedDbVisible()) { "rig: indexedDB is still visible after hiding it" }
            try {
                IndexedDbDurableStore.open("kuilt-store-no-indexeddb")
                null
            } catch (thrown: Throwable) {
                currentCoroutineContext().ensureActive()
                thrown
            }
        } finally {
            restoreIndexedDb()
        }

        assertAll(
            { assertTrue(indexedDbVisible(), "rig: indexedDB is visible again once restored") },
            { assertNotNull(failure, "open must fail when the page has no IndexedDB") },
            {
                assertTrue(
                    failure is Exception,
                    "open threw ${failure?.let { it::class.simpleName }}, which is not an Exception — a " +
                        "caller's catch (e: Exception) cannot see it: $failure",
                )
            },
        )
    }
}

/**
 * Shadow the global `indexedDB` with an own property holding `undefined`, keeping the original
 * descriptor (if it was an own property) for [restoreIndexedDb].
 */
@JsFun(
    """
    () => {
        globalThis.__kuiltHiddenIndexedDb = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
        Object.defineProperty(globalThis, 'indexedDB', { value: undefined, configurable: true, writable: true });
    }
    """,
)
private external fun hideIndexedDb()

/** Undo [hideIndexedDb]: put the original own property back, or drop the shadow if there was none. */
@JsFun(
    """
    () => {
        const original = globalThis.__kuiltHiddenIndexedDb;
        if (original) {
            Object.defineProperty(globalThis, 'indexedDB', original);
        } else {
            delete globalThis.indexedDB;
        }
        delete globalThis.__kuiltHiddenIndexedDb;
    }
    """,
)
private external fun restoreIndexedDb()

/** Whether a bare `indexedDB` reference, as the store's bridge makes, sees an IndexedDB factory. */
@JsFun("() => typeof indexedDB !== 'undefined'")
private external fun indexedDbVisible(): Boolean

/**
 * Drop the whole database at [name], whether or not it exists.
 *
 * `internal` rather than file-private because [DurableStoreRaftStorageIndexedDbConformanceTest]
 * needs exactly this, and the alternative is a second copy of [idbDeleteDatabase]'s `@JsFun` body
 * in another file — the duplicated-fixture shape #2515 removed from the two file backends' tests.
 */
internal suspend fun deleteDatabase(name: String) {
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
