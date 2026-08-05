@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class InstallSnapshotTest {

    /**
     * Headline scenario (#114): a node offline across a compaction boundary rejoins via
     * InstallSnapshot — the leader no longer holds entries at the node's prevLogIndex, so
     * AppendEntries alone can never catch it up.
     *
     * "Offline" is modelled as crash + restart ([RaftSimulation.crash]/[restart]) — the node's
     * scope is cancelled, so its election timer never fires and its term does NOT inflate. A
     * partition-while-running model would inflate the term and trigger the orthogonal
     * disruptive-rejoin problem (PreVote, #193), which is not what this test exercises.
     */
    @Test
    fun offlineFollower_rejoinsViaInstallSnapshot_afterCompaction() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.crash(offline)                               // truly offline — no term inflation
        repeat(20) { leader.propose(byteArrayOf(it.toByte())) }   // commit via the surviving quorum
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)      // a committed index past where the node left off

        leader.snapshots.value = Snapshot(through, sim.stateBytes(leaderId, through))
        leader.compactionFloor.first { it == through }   // leader compacts past the node's needed prefix

        sim.restart(offline)                             // back online, fresh from its (empty) persisted storage
        val installs = sim.collectInstalls(offline)
        sim.awaitCommit(finalCommit, on = setOf(offline))        // catches up — only possible via InstallSnapshot

        assertTrue(installs.isNotEmpty(), "rejoined node must receive a Committed.Install")
        assertEquals(through, installs.last().snapshot.throughIndex)
        assertContentEquals(
            sim.appliedState(leaderId), sim.appliedState(offline),
            "rejoined node's state machine must converge with the leader's",
        )
    }

    /**
     * A small snapshot still spans many chunks when the transport reports a tiny [maxPayloadBytes].
     *
     * 320 B is `HEADER_BUDGET` (256 B, for the CBOR envelope) plus 64 B, which `chunkBytes()` then
     * halves to **32 B of raw state per chunk** — CBOR renders a `ByteArray` as an array of integers,
     * so a byte can cost two and a chunk sized in raw bytes must be halved to fit a wire budget
     * (#2150). The 1000-byte snapshot below therefore spans ~32 chunks.
     *
     * The budget used to read `64` — *below* the envelope reserve, so `chunkBytes()` hit its floor of 1
     * and the transfer was silently 1000 one-byte chunks. It also left no room for a command, which the
     * propose-time bound of #2069 now says out loud: a transport whose whole budget is smaller than the
     * envelope cannot carry any entry, and every `propose` here was refused.
     */
    @Test
    fun chunkedTransfer_reassemblesUnderTinyMaxPayload() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 320)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        sim.crash(offline)
        repeat(10) { leader.propose(byteArrayOf(8)) }
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)

        val bigState = ByteArray(1000) { it.toByte() }   // ~16 chunks at 64 B
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }

        sim.restart(offline)
        val installs = sim.collectInstalls(offline)
        sim.awaitCommit(finalCommit, on = setOf(offline))

        assertEquals(through, installs.last().snapshot.throughIndex)
        assertEquals(1000, installs.last().snapshot.state.size, "all chunks reassembled in order")
        assertContentEquals(bigState, installs.last().snapshot.state, "bytes reassembled in order")
    }

    /**
     * §7 sender semantics (#1222): a heartbeat that fires **during** an in-flight snapshot transfer
     * must *resume* it from the follower's acked offset — never restart the stream from offset 0.
     *
     * [onHeartbeat] diverts to InstallSnapshot for every peer whose `nextIndex` is still below the
     * compaction floor, which holds for the whole transfer. The bug: that divert passed
     * `restart = true`, so each heartbeat reloaded the snapshot and reset `nextOffset = 0`, discarding
     * all reassembled progress — a livelock for any snapshot whose transfer spans more than one
     * heartbeat interval of chunk RTTs. The in-memory harness has RTT≈0 (a transfer completes within
     * one virtual instant, before any heartbeat), so the defect is invisible to the normal flow; we
     * reproduce it by hand-driving the divert and an ack, then firing a heartbeat mid-transfer.
     *
     * The follower is kept **crashed** so the only ack is the injected one and the only chunk sends
     * are the leader's own diverts — observed directly on the leader's [RaftTraceEvent.InstallSnapshot]
     * offsets. With `restart = true` the post-ack heartbeat re-emits offset 0; with `restart = false`
     * it re-emits the acked offset. The assertion is on that observable: once the transfer has
     * progressed past 0, no heartbeat drags it back to 0.
     */
    @Test
    fun heartbeatDuringTransfer_resumesInsteadOfRestartingFromOffsetZero() = raftRunTest {
        val hb = fastRaftConfig().heartbeatInterval.inWholeMilliseconds
        // 320 B = HEADER_BUDGET (256) + 64 B of state per chunk — see
        // [chunkedTransfer_reassemblesUnderTinyMaxPayload] for why a sub-256 budget is not a knob.
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 320)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val offline = sim.nodeIds.first { it != leaderId }

        // Observe every chunk offset the leader sends to `offline`, plus the leader's current term
        // (the injected ack must echo it or the leader ignores the ack). trace is a replay=0 hot
        // SharedFlow, so subscribe (via settle) before the events we care about.
        val sentOffsets = mutableListOf<Long>()
        var leaderTerm = -1L
        backgroundScope.launch {
            leader.trace.collect { event ->
                when (event) {
                    is RaftTraceEvent.InstallSnapshot -> if (event.to == offline) sentOffsets += event.offset
                    is RaftTraceEvent.AppendEntries -> if (event.from == leaderId) leaderTerm = event.term
                    is RaftTraceEvent.BecomeLeader -> if (event.node == leaderId) leaderTerm = event.term
                    else -> Unit
                }
            }
        }
        sim.settle()

        // Crash `offline` before proposing so its nextIndex stays below the compaction floor — the
        // leader then diverts to InstallSnapshot every heartbeat regardless of `offline` being absent.
        sim.crash(offline)
        repeat(10) { leader.propose(byteArrayOf(8)) }
        val through = sim.compactionFloorCandidate(leaderId)
        leader.snapshots.value = Snapshot(through, ByteArray(1000) { it.toByte() })
        leader.compactionFloor.first { it == through }

        // Heartbeat #1: the leader diverts and sends chunk 0 (offset 0) — the transfer starts.
        advanceTimeBy(hb); runCurrent(); sim.settle()
        assertTrue(0L in sentOffsets, "the transfer must start at offset 0 (sent=$sentOffsets)")
        assertTrue(leaderTerm > 0L, "captured the leader's term for the injected ack")

        // The follower acks partial progress — the in-flight transfer advances well past offset 0.
        val ackedOffset = 200L
        sim.deliverInstallSnapshotResponse(to = leaderId, from = offline, term = leaderTerm, nextOffset = ackedOffset)
        runCurrent(); sim.settle()
        assertTrue(ackedOffset in sentOffsets, "leader resumes the next chunk from the acked offset (sent=$sentOffsets)")

        val progressed = sentOffsets.size

        // Heartbeat #2..: fires DURING the still-in-flight transfer — the bug's trigger.
        advanceTimeBy(hb * 3); runCurrent(); sim.settle()

        val afterHeartbeat = sentOffsets.drop(progressed)
        assertAll(
            { assertTrue(afterHeartbeat.isNotEmpty(), "a heartbeat re-diverted during the transfer (sent=$sentOffsets)") },
            { assertTrue(afterHeartbeat.none { it == 0L }, "heartbeat must NOT restart the transfer from offset 0 (sent=$sentOffsets)") },
            { assertTrue(afterHeartbeat.all { it >= ackedOffset }, "transfer resumes from the acked offset, not the prefix (sent=$sentOffsets)") },
        )
    }

    /**
     * A snapshot chunk must fit the **wire** budget, not merely the raw one (#2150).
     *
     * `chunkBytes()` used to take `minOf(maxPayloadBytes, snapshotChunkCeiling)` and subtract the
     * envelope reserve — mixing two different units. `snapshotChunkCeiling` bounds the *raw* state bytes
     * in a chunk; `maxPayloadBytes` bounds the *encoded frame*. CBOR renders a `ByteArray` as an array
     * of integers, so raw bytes cost up to two on the wire and a chunk sized to fill the budget encodes
     * to roughly twice it.
     *
     * ### Why the other chunking tests here cannot see this
     *
     * They run at 296 B and 320 B, where the 256 B envelope reserve *dominates*: it leaves 40 B and
     * 64 B of raw state, which even at 2× encode to 80 B and 128 B — comfortably inside the budget. The
     * defect needs a chunk large **relative to the reserve**, which is every realistic transport: at the
     * 16 KiB default ceiling the old formula sized a chunk to 16128 B, encoding to as much as 32258 B.
     * Reverting `chunkBytes()` alone left all 518 tests in this module green, which is what this case
     * exists to close.
     *
     * [BIG_BUDGET] is deliberately the smallest round budget where the reserve no longer dominates, and
     * the snapshot state is high-valued bytes (`0x80 or …`) so every one of them costs two on the wire —
     * the worst case the sizing has to survive, not the average.
     */
    @Test
    fun aChunkIsSizedToTheWireBudget_notTheRawOne() = raftRunTest {
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = BIG_BUDGET)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val behind = sim.nodeIds.first { it != leaderId }

        sim.crash(behind)
        repeat(4) { leader.propose(ByteArray(64) { i -> (0x80 or (i and 0x3F)).toByte() }) }
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)

        // Every byte high-valued, so the encoding is at its 2x worst case throughout.
        val bigState = ByteArray(BIG_STATE) { (0x80 or (it and 0x3F)).toByte() }
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }

        sim.restart(behind)
        val installs = sim.collectInstalls(behind)
        sim.awaitCommit(finalCommit, on = setOf(behind))

        assertAll(
            { assertContentEquals(bigState, installs.last().snapshot.state, "the snapshot reassembles byte-for-byte") },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "no chunk may exceed the ${BIG_BUDGET} B budget the transport published: ${sim.network.overBudget}",
                )
            },
        )
    }

    /**
     * Completion under heartbeat interleaving (#1226): a **live** follower receives a multi-chunk
     * snapshot whose transfer spans several heartbeat intervals, and the transfer COMPLETES — the
     * follower installs the snapshot, then catches up the log tail via normal AppendEntries,
     * converging with the leader.
     *
     * The completion tests above run at RTT≈0, where the whole chunk/ack exchange finishes within a
     * single virtual instant — no heartbeat ever fires mid-transfer. The #1222 test above interleaves
     * a heartbeat but keeps the follower crashed, so it proves *non-reset*, not *completion*. Here
     * the leader↔follower link carries 1 ms one-way latency, so each one-chunk-in-flight ack cycle
     * costs a full heartbeat interval (2 ms): [onHeartbeat]'s InstallSnapshot divert fires repeatedly
     * DURING the transfer (the #1222 trigger) while the live follower's acks advance it. A regression
     * that *stalls* a heartbeat-spanning transfer fails the awaitCommit; a transfer that somehow
     * completed in one round fails the span/chunk-count assertions.
     */
    @Test
    fun multiHeartbeatSpanningChunkedTransfer_completesAndFollowerConverges() = raftRunTest {
        val hbMs = fastRaftConfig().heartbeatInterval.inWholeMilliseconds
        // maxPayloadBytes budgets HEADER_BUDGET (256 B) for the CBOR envelope, and chunkBytes() halves
        // what is left for CBOR's byte-array expansion (#2150) → 20 raw state bytes/chunk.
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 296)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val behind = sim.nodeIds.first { it != leaderId }

        sim.crash(behind)                             // fall behind the coming compaction boundary
        repeat(10) { leader.propose(ByteArray(30) { it.toByte() }) }  // fat commands → a multi-chunk snapshot
        val finalCommit = leader.commitIndex.value
        val through = sim.compactionFloorCandidate(leaderId)
        leader.snapshots.value = Snapshot(through, sim.stateBytes(leaderId, through))
        leader.compactionFloor.first { it == through }

        // RTT > 0 on the leader↔behind link — the transfer can no longer complete at one instant.
        sim.network.setLinkLatency(leaderId, behind, 1.milliseconds)
        sim.network.setLinkLatency(behind, leaderId, 1.milliseconds)
        sim.restart(behind)

        // (virtual ms, offset) of every chunk the leader sends to `behind` post-restart — the
        // observable proving the transfer was genuinely chunked AND spanned >1 heartbeat interval.
        val chunkSends = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch {
            leader.trace.collect { event ->
                if (event is RaftTraceEvent.InstallSnapshot && event.to == behind) {
                    chunkSends += testScheduler.currentTime to event.offset
                }
            }
        }
        val installs = sim.collectInstalls(behind)
        sim.settle()                                  // subscribe both collectors before time advances

        sim.awaitCommit(finalCommit, on = setOf(behind))  // reachable only via install + tail replication
        sim.settle()                                  // drain same-instant committed emissions

        assertTrue(chunkSends.isNotEmpty(), "leader must send snapshot chunks after the rejoin")
        val offsets = chunkSends.map { it.second }.distinct()
        val spanMs = chunkSends.last().first - chunkSends.first().first
        assertAll(
            { assertTrue(installs.isNotEmpty(), "live follower must complete the install (sends=$chunkSends)") },
            { assertEquals(through, installs.last().snapshot.throughIndex) },
            { assertTrue(through < finalCommit, "a log tail must remain beyond the snapshot (through=$through, commit=$finalCommit)") },
            { assertTrue(offsets.size > 1, "transfer must be genuinely chunked (offsets=$offsets)") },
            {
                assertTrue(
                    spanMs >= 2 * hbMs,
                    "transfer must span >1 heartbeat interval (span=${spanMs}ms, hb=${hbMs}ms, sends=$chunkSends)",
                )
            },
            {
                assertContentEquals(
                    sim.appliedState(leaderId), sim.appliedState(behind),
                    "follower's state machine must converge with the leader's",
                )
            },
        )
    }

    private companion object {
        /**
         * A transport budget large enough that the 256 B envelope reserve no longer dominates the chunk
         * — which is what the mixed-units sizing bug of #2150 needs in order to bite. At 4096 B the old
         * formula chose 3840 raw bytes per chunk, encoding to as much as 7682 B: nearly twice the
         * budget. Below roughly 768 B the reserve absorbs the expansion and the defect is invisible.
         */
        const val BIG_BUDGET = 4096

        /** Enough state to span several chunks at [BIG_BUDGET], so the sizing is exercised repeatedly. */
        const val BIG_STATE = 8000
    }
}
