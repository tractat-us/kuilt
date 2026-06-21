@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for [ClientSessionTable.closeSession] driven from a real
 * [RaftNode]'s `committed` stream — the "dedup path C" documented in the KDoc:
 * "Drive from the apply loop on a committed close op."
 *
 * **Close op encoding:** a close command is a [CLOSE_PREFIX] sentinel followed by the UTF-8
 * bytes of the [ClientId.value]. Normal application commands must not start with this prefix.
 * This is a minimal test-local convention modelling the real-world pattern where a consumer
 * embeds intent into the command payload.
 *
 * **Apply loop contract (ADR-034 single-collection):** `node.committed` is collected exactly once
 * per node, inside a `backgroundScope.launch { … }` child. Close ops call
 * [ClientSessionTable.closeSession]; normal entries gate on [ClientSessionTable.shouldApply].
 *
 * **Collector timing:** under `StandardTestDispatcher`, a launched collector does not run until
 * virtual time advances. A [settle] call after `launch { collect }` lets the collector subscribe
 * before the first `propose`, so no emission is missed (ADR-034 + see [RaftTestFixtures] banner).
 */
class ClientSessionCloseIntegrationTest {

    // ── Close op encoding ─────────────────────────────────────────────────────

    private fun closeCommand(clientId: ClientId): ByteArray =
        CLOSE_PREFIX + clientId.value.encodeToByteArray()

    private fun parseCloseId(command: ByteArray): ClientId? {
        if (!command.startsWith(CLOSE_PREFIX)) return null
        return ClientId(command.copyOfRange(CLOSE_PREFIX.size, command.size).decodeToString())
    }

    /**
     * Advance the current virtual instant without advancing the clock: yield-only so
     * already-scheduled coroutines (e.g. a freshly launched collector) run at *this* instant.
     * Analogous to [RaftSimulation.settle] but callable without a [RaftSimulation] instance.
     */
    private suspend fun settle() = repeat(10) { yield() }

    // ── Test 1: core proof — re-open after close ──────────────────────────────

    /**
     * Proves the at-least-once re-open contract documented in [ClientSessionTable.closeSession]:
     *
     * 1. A client commits requests 1..3, advancing its high-water-mark to 3.
     * 2. A close op is committed; the apply loop calls [ClientSessionTable.closeSession].
     * 3. A subsequent request from the same client at serial ≤ 3 **re-applies** (is NOT
     *    suppressed) — the mark was reset by the close.
     */
    @Test
    fun clientReOpensAfterCloseOp() = raftRunTest {
        val clientId = ClientId("durable-client")
        val h = singleVoterNode(backgroundScope, identity = ClientIdentity.Durable(clientId))
        h.node.awaitLeadership()

        val table = ClientSessionTable()
        val appliedIndices = mutableListOf<Long>()
        val closeObservedAt = mutableListOf<Long>()

        backgroundScope.launch {
            h.node.committed.collect { c ->
                when (c) {
                    is Committed.Install -> Unit // no compaction in this test
                    is Committed.Entry -> {
                        val closeId = parseCloseId(c.entry.command)
                        if (closeId != null) {
                            table.closeSession(closeId)
                            closeObservedAt += c.entry.index
                        } else if (table.shouldApply(c.entry.dedupKey)) {
                            appliedIndices += c.entry.index
                        }
                    }
                }
            }
        }
        // Let the collector subscribe before the first proposal — under StandardTestDispatcher,
        // launched coroutines don't run until virtual time advances. See RaftTestFixtures banner.
        settle()

        // Advance the high-water-mark to 3.
        val e1 = h.node.propose("cmd-a".encodeToByteArray(), requestId = 1)
        val e2 = h.node.propose("cmd-b".encodeToByteArray(), requestId = 2)
        val e3 = h.node.propose("cmd-c".encodeToByteArray(), requestId = 3)
        h.awaitCommit(e3.index)

        // Commit the close op at a requestId above the data range (4) so the leader dedup cache
        // does not coalesce it with a prior entry (data used requestIds 1..3; auto-serial would
        // start at 1 and conflict). The consumer's apply loop gates on parseCloseId, not dedupKey.
        val closeEntry = h.node.propose(closeCommand(clientId), requestId = 4L)
        h.awaitCommit(closeEntry.index)
        // Let the apply loop drain the close op emission under StandardTestDispatcher.
        settle()

        // After closeSession: serial 3 must re-apply (mark was evicted).
        assertTrue(
            table.shouldApply(DedupKey(clientId, 3)),
            "serial 3 must re-apply after closeSession — at-least-once re-open contract"
        )

        // Sanity: data entries were applied; the close op was not applied as data.
        assertAll(
            { assertTrue(e1.index in appliedIndices, "entry 1 applied as data") },
            { assertTrue(e2.index in appliedIndices, "entry 2 applied as data") },
            { assertTrue(e3.index in appliedIndices, "entry 3 applied as data") },
            { assertFalse(closeEntry.index in appliedIndices, "close op not applied as data") },
            { assertTrue(closeObservedAt.isNotEmpty(), "close op observed by apply loop") },
        )
    }

    // ── Test 2: snapshot round-trip — closed mark not serialized ─────────────

    /**
     * A [ClientSessionTable] serialized via [ClientSessionTable.toBytes] after [ClientSessionTable.closeSession]
     * does NOT carry the closed mark — the round-trip reflects the eviction. This means a follower that
     * installs a snapshot taken after a close op correctly re-opens the client's slot.
     *
     * Fast table-level unit assertion (no RaftNode needed).
     */
    @Test
    fun snapshotAfterCloseDropsTheMark() {
        val t = ClientSessionTable()
        val id = ClientId("stable-client")

        t.shouldApply(DedupKey(id, 7)) // mark → 7
        t.closeSession(id)             // evict the mark

        val restored = ClientSessionTable.fromBytes(t.toBytes())

        // Restored table must NOT suppress serial 7 — the mark was evicted before serialization.
        assertTrue(
            restored.shouldApply(DedupKey(id, 7)),
            "serial 7 must re-apply from restored snapshot — closed mark must not persist"
        )
    }

    // ── Test 3: replica determinism — every node's apply loop agrees ──────────

    /**
     * All nodes in a 3-voter cluster run independent apply loops with their own [ClientSessionTable].
     * After a close op commits, every replica calls [ClientSessionTable.closeSession]. The close is
     * driven purely from the deterministic committed stream, so all tables converge identically:
     * a subsequent request at the old serial re-applies on every node.
     */
    @Test
    fun replicaTablesAgreeAfterCloseOp() = raftRunTest {
        val clientId = ClientId("shared-client")
        val sim = raftSim(scope = this, nodeScope = backgroundScope)
        val leader = awaitLeader(sim)

        val tables = sim.nodeIds.associateWith { ClientSessionTable() }
        val closeObservedOn = sim.nodeIds.associateWith { mutableListOf<Long>() }

        // Wire one apply loop per node — single collection per node (ADR-034).
        sim.nodeIds.forEach { id ->
            val table = tables.getValue(id)
            val observed = closeObservedOn.getValue(id)
            backgroundScope.launch {
                sim.nodes.getValue(id).committed.collect { c ->
                    when (c) {
                        is Committed.Install -> Unit
                        is Committed.Entry -> {
                            val closeId = parseCloseId(c.entry.command)
                            if (closeId != null) {
                                table.closeSession(closeId)
                                observed += c.entry.index
                            } else {
                                table.shouldApply(c.entry.dedupKey)
                            }
                        }
                    }
                }
            }
        }
        // Settle so all collectors subscribe before the first proposal.
        sim.settle()

        // Advance the high-water-mark to 3 across all replicas.
        val e3 = leader.propose("data-3".encodeToByteArray(), requestId = 3)
        sim.awaitCommit(e3.index)

        // Commit the close op at a requestId above the data range so the leader dedup cache
        // does not coalesce it with a prior entry (data used requestId 3; auto-serial would conflict).
        val closeEntry = leader.propose(closeCommand(clientId), requestId = 10L)
        sim.awaitCommit(closeEntry.index)
        // Let every apply loop drain the close op emission.
        sim.settle()
        delay(1) // advance virtual time so backgroundScope collectors process the last emission

        // Every replica's table must have evicted the mark.
        assertAll(
            *sim.nodeIds.map { id ->
                {
                    assertTrue(
                        tables.getValue(id).shouldApply(DedupKey(clientId, 3)),
                        "node $id: serial 3 must re-apply after closeSession"
                    )
                }
            }.toTypedArray()
        )
    }

    private companion object {
        /** Sentinel prefix that marks a close op in the command payload. 0x00 is not valid UTF-8 start. */
        val CLOSE_PREFIX = byteArrayOf(0x00, 'C'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte())

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (this.size < prefix.size) return false
            return prefix.indices.all { this[it] == prefix[it] }
        }
    }
}
