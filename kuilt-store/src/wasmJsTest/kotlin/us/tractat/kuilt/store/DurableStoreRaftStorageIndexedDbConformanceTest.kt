package us.tractat.kuilt.store

import us.tractat.kuilt.conformance.RaftStorageConformanceSuite
import us.tractat.kuilt.raft.DurableStoreRaftStorage
import us.tractat.kuilt.raft.RaftStorage

/**
 * Verifies [DurableStoreRaftStorage] satisfies the whole [RaftStorageConformanceSuite] over
 * [IndexedDbDurableStore] — in a real browser, the only place IndexedDB exists.
 *
 * The fixture is [IndexedDbDurableStoreTest]'s, reused rather than re-derived: a fresh database per
 * storage, wiped first because a browser's IndexedDB outlives the page (so a name from a counter
 * alone comes back holding the last run's records and every absence assertion would be checking
 * them), and a restart modelled as **close the connection, open a fresh one against the same
 * database**, then [DurableStoreRaftStorage.open] on it. `deleteDatabase` was widened from
 * file-private to `internal` for this; copying its `@JsFun` body here instead is the duplicated
 * fixture shape #2515 removed from the two file backends.
 *
 * The connection each storage was opened over is tracked here rather than read back off the
 * adapter — exposing the store on [DurableStoreRaftStorage] would be a knob on the public API that
 * exists only so a test could reach it, which is the trade [RaftStorageConformanceSuite]'s own
 * `reopen` KDoc declines to make for `medium()`. The database-name prefix differs from
 * [IndexedDbDurableStoreTest]'s, so the two counters cannot collide.
 */
class DurableStoreRaftStorageIndexedDbConformanceTest : RaftStorageConformanceSuite() {

    private val connections = mutableMapOf<RaftStorage, Pair<String, IndexedDbDurableStore>>()

    override suspend fun newStorage(): RaftStorage {
        val name = "kuilt-raft-storage-conformance-${nextRaftDatabaseId++}"
        deleteDatabase(name)
        return openOver(name)
    }

    override suspend fun reopen(storage: RaftStorage): RaftStorage {
        val (name, connection) = requireNotNull(connections[storage]) {
            "reopen() was handed a storage this fixture did not create"
        }
        connection.close()
        return openOver(name)
    }

    private suspend fun openOver(name: String): RaftStorage {
        val connection = IndexedDbDurableStore.open(name)
        return DurableStoreRaftStorage.open(connection).also { connections[it] = name to connection }
    }
}

/**
 * File-level, not a class property: the test framework builds a fresh instance of the test class for
 * every test function, so a per-instance counter would restart at zero in each of them and hand every
 * test the same database.
 */
private var nextRaftDatabaseId = 0
