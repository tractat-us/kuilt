@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.builtins.ByteArraySerializer
import us.tractat.kuilt.raft.internal.raftCbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
     * 320 B is `HEADER_BUDGET` (256 B, for the CBOR envelope around an empty payload) plus a 64-byte
     * window, which carries **63 B of raw state per chunk** and the two-byte byte-string header that
     * a 63-byte payload needs. It used to be 32 B: `chunkBytes()` halved what the reserve left,
     * because CBOR rendered a `ByteArray` as an array of integers and a byte could cost two (#2150).
     * #2160's byte-string framing removed the halving, so the 1000-byte snapshot below spans 16
     * chunks rather than ~32. The exact stride is pinned by [aChunkIsSizedToTheWireBudget_notTheRawOne],
     * not here.
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

        val bigState = ByteArray(1000) { it.toByte() }   // 16 chunks at 63 raw B
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
        // 320 B = HEADER_BUDGET (256) + 63 raw B of state per chunk and its 2-byte header — see
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
     * in a chunk; `maxPayloadBytes` bounds the *encoded frame*.
     *
     * ### #2160 removed the factor this case was built around; what it still holds is stated here
     *
     * The gap between those two units was a **2×** ratio while CBOR rendered a `ByteArray` as an array
     * of integers: at the 16 KiB default ceiling the old formula sized a chunk to 16128 B, encoding to
     * as much as 32258 B, and reverting `chunkBytes()` alone left all 518 tests in this module green —
     * which is what this case was written to close. Byte-string framing makes the gap a 1–5 byte
     * length header instead, which the engine charges explicitly (`snapshotSliceBytes`), so the
     * division is gone and a factor-of-two error is no longer the one to look for here.
     *
     * What survives is two assertions. **No chunk this engine mints may exceed the budget its
     * transport published**, at a budget large enough that the reserve does not dominate — which reds
     * if the reserve is dropped or the envelope outgrows it, but has about 177 B of slack here (the
     * config-free reserve is floored at 256 B, while this transfer's real envelope, small `Long`s and
     * all, is about 78 B), so it cannot see a byte or two of header miscounted. And **every
     * chunk carries exactly the stride the floor implies**, which can: the offsets are pinned against a
     * stride found by searching the codec, so a slice that forgets the byte-string header steps, a
     * re-introduced divisor, or a dropped `HEADER_BUDGET` floor each move it. The header steps
     * themselves are pinned by `RaftWireGoldenVectorTest.anOpaquePayloadCostsItsOwnLengthPlusAByteStringHeader`.
     *
     * This is the **config-free** case: the snapshot here carries no `ConfigPayload`, asserted below,
     * so the reserve is the `HEADER_BUDGET` floor rather than a measured envelope. The config-carrying
     * case that overran the flat reserve (#2720) is `SnapshotEnvelopeReserveTest`'s.
     *
     * [BIG_BUDGET] is deliberately the smallest round budget where the reserve no longer dominates.
     * The high-valued state bytes (`0x80 or …`) are a leftover from the 2× era, when they were the
     * worst case; the encoded size is content-independent now and they are kept only so the state is
     * not a run of one value.
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

        // High-valued bytes: the 2x worst case before #2160, content-independent since.
        val bigState = ByteArray(BIG_STATE) { (0x80 or (it and 0x3F)).toByte() }
        leader.snapshots.value = Snapshot(through, bigState)
        leader.compactionFloor.first { it == through }
        assertNull(
            sim.storages.getValue(leaderId).loadSnapshot()?.meta?.config,
            "rig: this arm is the config-free case, where the HEADER_BUDGET floor is the reserve; a " +
                "config on the snapshot would make the stride below a statement about a measured envelope",
        )

        val chunkOffsets = mutableListOf<Long>()
        backgroundScope.launch {
            leader.trace.collect { event ->
                if (event is RaftTraceEvent.InstallSnapshot && event.to == behind) chunkOffsets += event.offset
            }
        }
        sim.settle()                                  // subscribe before the transfer starts
        sim.restart(behind)
        val installs = sim.collectInstalls(behind)
        sim.awaitCommit(finalCommit, on = setOf(behind))

        val stride = configFreeStride(BIG_BUDGET)
        assertAll(
            { assertContentEquals(bigState, installs.last().snapshot.state, "the snapshot reassembles byte-for-byte") },
            {
                assertTrue(
                    sim.network.overBudget.isEmpty(),
                    "no chunk may exceed the ${BIG_BUDGET} B budget the transport published: ${sim.network.overBudget}",
                )
            },
            {
                assertEquals(
                    (0 until BIG_STATE step stride).map { it.toLong() },
                    chunkOffsets.distinct().sorted(),
                    "every config-free chunk must carry exactly $stride raw bytes — the largest slice whose " +
                        "byte string fits the window a ${HEADER_BUDGET_BY_VALUE} B floor leaves in a " +
                        "${BIG_BUDGET} B budget",
                )
            },
        )
    }

    /**
     * The raw bytes per chunk a **config-free** snapshot gets at [budget], found by searching the
     * codec rather than by restating the engine's closed form.
     *
     * A config-free envelope costs well under 256 B even at the plausibility ceiling, so the engine's
     * `HEADER_BUDGET` floor is the reserve — and that reserve includes the empty payload's own
     * one-byte header, which a real chunk replaces with a wider one. So the window is
     * `budget − (floor − wire(empty))`, and the stride is the largest slice whose encoding fits it.
     */
    private fun configFreeStride(budget: Int): Int {
        val overhead = HEADER_BUDGET_BY_VALUE - wireBytes(ByteArray(0))
        return (budget - overhead downTo 1).first { raw -> overhead + wireBytes(ByteArray(raw)) <= budget }
    }

    private fun wireBytes(data: ByteArray): Int = raftCbor.encodeToByteArray(ByteArraySerializer(), data).size

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
        // maxPayloadBytes budgets HEADER_BUDGET (256 B) for the CBOR envelope around an empty
        // payload; the 41 B window left carries 39 raw state bytes per chunk plus their 2-byte
        // header. It was 20 until #2160, when chunkBytes() stopped halving for CBOR's byte-array
        // expansion (#2150).
        val sim = raftSim(this, backgroundScope, n = 3, maxPayloadBytes = 296)
        val leader = awaitLeader(sim)
        val leaderId = sim.nodes.entries.first { it.value === leader }.key
        val behind = sim.nodeIds.first { it != leaderId }

        sim.crash(behind)                             // fall behind the coming compaction boundary
        // 20 raw bytes (21 on the wire since #2160: length plus a one-byte header) — fat enough for
        // a multi-chunk snapshot at 39 raw state bytes/chunk, with headroom under the propose limit.
        // It was 30 until #2156: the propose gate now reserves the MEASURED worst-case envelope
        // rather than a flat 256 B, which on this deliberately tiny budget leaves 26 wire bytes
        // rather than 40. The budget is left alone on purpose — raising it would change
        // `chunkBytes()` and with it the chunk count this test is actually about.
        repeat(10) { leader.propose(ByteArray(20) { it.toByte() }) }  // fat commands → a multi-chunk snapshot
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
         * — which is what the mixed-units sizing bug of #2150 needed in order to bite. At 4096 B the
         * pre-#2150 formula chose 3840 raw bytes per chunk, encoding to as much as 7682 B: nearly twice
         * the budget. Since #2160 a config-free chunk here carries 3838 raw bytes and encodes to 3841,
         * so what this budget now buys is a chunk far larger than the reserve rather than a doubling.
         */
        const val BIG_BUDGET = 4096

        /**
         * `RaftEngine.HEADER_BUDGET`, restated by value — the engine's copy is `private` to its
         * companion, and a test that read it would agree with the engine by construction.
         */
        const val HEADER_BUDGET_BY_VALUE = 256

        /** Enough state to span several chunks at [BIG_BUDGET], so the sizing is exercised repeatedly. */
        const val BIG_STATE = 8000
    }
}
