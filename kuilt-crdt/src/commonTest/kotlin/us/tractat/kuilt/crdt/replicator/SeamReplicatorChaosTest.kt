// TODO: If a test starts flaking, try seeds 1..100 to find a stable reproducer.

@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.GCounter
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SeamReplicatorChaosTest {

    private val gcounterSer = ReplicatorMessage.serializer(GCounter.serializer())

    // ---- helpers ----

    private fun gcounterReplicator(
        seam: Seam,
        scope: kotlinx.coroutines.CoroutineScope,
        config: SeamReplicatorConfig = SeamReplicatorConfig(expectVirtualTime = true),
        clock: MonotonicMillis = MonotonicMillis { 0L },
    ) = SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = GCounter.ZERO,
        messageSerializer = gcounterSer,
        scope = scope,
        config = config,
        clock = clock,
    )

    /**
     * Wrap [rawSeam] with [ChaosSeam]. The [seed] is fixed per test so failures are
     * deterministically reproducible.
     */
    private fun chaosWrap(
        rawSeam: Seam,
        config: ChaosConfig,
        scope: kotlinx.coroutines.CoroutineScope,
        seed: Long,
    ) = ChaosSeam(delegate = rawSeam, config = config, scope = scope, seed = seed)

    // ---- scenario 1: random drop on the outbound path ----

    /**
     * Two peers sharing a GCounter with a 20% outbound drop rate.
     *
     * The replicator's Resend path is the recovery mechanism: when B receives seq N+2
     * before N+1 it requests a Resend; A replays from pendingDeltas. Both sides must
     * converge to the expected sum.
     *
     * A "kickstart" op is applied after the first idle to give a second Resend cycle —
     * ensuring convergence even if the first Resend was itself dropped.
     */
    @Test
    fun convergesUnderRandomDrop() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-drop"))
        val rawB = loom.join(InMemoryTag("b"))

        val chaosA = chaosWrap(rawA, ChaosConfig(dropProbability = 0.2), backgroundScope, seed = 0xC0FFEEL)
        val chaosB = chaosWrap(rawB, ChaosConfig(dropProbability = 0.2), backgroundScope, seed = 0xBEEFL)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)

        // Apply 20 increments on each peer (1 unit each)
        repeat(20) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }

        // Recovery rounds: each kickstart op re-triggers gap detection for any still-missing deltas.
        // Three rounds are sufficient to push past the probabilistic drop ceiling with this seed
        // (p=0.2 drop rate → P(all 3 resend attempts fail) ≈ 0.8%).
        repeat(3) {
            testScheduler.advanceUntilIdle()
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }
        testScheduler.advanceUntilIdle()

        // A: 20 + 3 = 23, B: 20 + 3 = 23 → total = 46
        val expectedTotal = 46L
        assertAll(
            { assertEquals(expectedTotal, repA.state.value.value, "A must converge to $expectedTotal") },
            { assertEquals(expectedTotal, repB.state.value.value, "B must converge to $expectedTotal") },
        )
    }

    // ---- scenario 2: reordered delivery ----

    private val gSetSer = ReplicatorMessage.serializer(
        GSet.serializer(kotlinx.serialization.serializer<String>()),
    )

    /**
     * GSet with a reorder window of 5: frames are accumulated and flushed in shuffled
     * order, exercising the gap-detection buffer (out-of-order deltas get buffered and
     * applied once the missing seq arrives via Resend).
     *
     * GSet is used instead of ORSet because each GSet delta carries exactly one element
     * (a minimal delta). Reordering these deltas creates genuine sequence gaps that the
     * replicator must detect and recover from, whereas ORSet deltas (which carry the full
     * accumulated state) would self-heal without gap detection.
     */
    @Test
    fun convergesUnderReorderedDelivery() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-reorder"))
        val rawB = loom.join(InMemoryTag("b"))

        val chaosA = chaosWrap(rawA, ChaosConfig(reorderWindow = 5), backgroundScope, seed = 0xDEADBEEFL)
        val chaosB = chaosWrap(rawB, ChaosConfig(reorderWindow = 5), backgroundScope, seed = 0xFACEL)

        val repA = SeamReplicator(
            replica = ReplicaId(rawA.selfId.value),
            seam = chaosA,
            initial = GSet.empty<String>(),
            messageSerializer = gSetSer,
            scope = backgroundScope,
            config = SeamReplicatorConfig(expectVirtualTime = true),
        )
        val repB = SeamReplicator(
            replica = ReplicaId(rawB.selfId.value),
            seam = chaosB,
            initial = GSet.empty<String>(),
            messageSerializer = gSetSer,
            scope = backgroundScope,
            config = SeamReplicatorConfig(expectVirtualTime = true),
        )

        // 10 ops per peer — multiple of window=5, so both reorder buffers flush completely
        val aElements = (1..10).map { "a-$it" }
        val bElements = (1..10).map { "b-$it" }

        aElements.forEach { elem -> repA.apply(repA.state.value.add(elem)) }
        bElements.forEach { elem -> repB.apply(repB.state.value.add(elem)) }

        testScheduler.advanceUntilIdle()

        val expected = (aElements + bElements).toSet()
        assertAll(
            { assertEquals(expected, repA.state.value.elements, "A must converge under reordered delivery") },
            { assertEquals(expected, repB.state.value.elements, "B must converge under reordered delivery") },
        )
    }

    // ---- scenario 3: duplicate delivery ----

    /**
     * A 50% duplicate rate exercises the idempotent merge property: duplicate deltas
     * (seq < expected) must be re-acked and not re-applied. GCounter is the ideal
     * subject because double-applying an inc delta would inflate the count if
     * idempotency were broken.
     */
    @Test
    fun convergesUnderDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-dup"))
        val rawB = loom.join(InMemoryTag("b"))

        val chaosA = chaosWrap(rawA, ChaosConfig(duplicateProbability = 0.5), backgroundScope, seed = 0xABCDL)
        val chaosB = chaosWrap(rawB, ChaosConfig(duplicateProbability = 0.5), backgroundScope, seed = 0x1234L)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)

        repeat(20) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }

        testScheduler.advanceUntilIdle()

        // With GCounter max-merge, duplicates are absorbed idempotently — total must be 40, not inflated
        assertAll(
            { assertEquals(40L, repA.state.value.value, "A must not double-count duplicates") },
            { assertEquals(40L, repB.state.value.value, "B must not double-count duplicates") },
        )
    }

    // ---- scenario 4: partition and heal ----

    /**
     * Partitions the fabric for 200ms (virtual time), then heals.
     *
     * During the partition, ops on each side are invisible to the other.
     * After healing, the replicator re-broadcasts pending deltas (triggered by
     * peer re-contact / Resend) and both sides must converge.
     */
    @Test
    fun survivesPartitionAndHeal() = runTest(UnconfinedTestDispatcher()) {
        var isPartitioned = false
        val partitionFlag: () -> Boolean = { isPartitioned }

        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-partition"))
        val rawB = loom.join(InMemoryTag("b"))

        val chaosA = chaosWrap(rawA, ChaosConfig(partitioned = partitionFlag), backgroundScope, seed = 0xCA11L)
        val chaosB = chaosWrap(rawB, ChaosConfig(partitioned = partitionFlag), backgroundScope, seed = 0xBABEL)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)

        // Exchange initial state
        testScheduler.advanceUntilIdle()

        // Partition: ops during this window are invisible cross-peer
        isPartitioned = true
        repeat(5) { repA.apply(repA.state.value.inc(repA.replica, 1L)) }
        repeat(3) { repB.apply(repB.state.value.inc(repB.replica, 1L)) }
        testScheduler.advanceTimeBy(200L)

        // Heal: both peers can exchange again.
        // Apply one more op on each side so the new delta (with a higher seq than the peer
        // expects) triggers gap detection → Resend → replay of all partition-era deltas.
        isPartitioned = false
        repA.apply(repA.state.value.inc(repA.replica, 1L)) // triggers B's gap detection for A's deltas
        repB.apply(repB.state.value.inc(repB.replica, 1L)) // triggers A's gap detection for B's deltas
        testScheduler.advanceUntilIdle()

        // A: 5 + 1 = 6; B: 3 + 1 = 4 → total = 10
        assertAll(
            { assertEquals(10L, repA.state.value.value, "A must see B's partition-era ops after heal+resend") },
            { assertEquals(10L, repB.state.value.value, "B must see A's partition-era ops after heal+resend") },
        )
    }

    // ---- scenario 5: three-peer combined chaos ----

    /**
     * Three peers with combined drop (20%) + reorder window (3) + duplicate (10%).
     * All three must converge to the same GCounter total after generous virtual time.
     */
    @Test
    fun threePeersChaosConvergence() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-3peer"))
        val rawB = loom.join(InMemoryTag("b"))
        val rawC = loom.join(InMemoryTag("c"))

        val chaosConfig = ChaosConfig(
            dropProbability = 0.2,
            reorderWindow = 3,
            duplicateProbability = 0.1,
        )

        val chaosA = chaosWrap(rawA, chaosConfig, backgroundScope, seed = 0xA0L)
        val chaosB = chaosWrap(rawB, chaosConfig, backgroundScope, seed = 0xB0L)
        val chaosC = chaosWrap(rawC, chaosConfig, backgroundScope, seed = 0xC0L)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)
        val repC = gcounterReplicator(chaosC, backgroundScope)

        // Apply 12 ops (multiple of reorderWindow=3) so reorder buffers flush completely during ops.
        // With drop=20% and reorder=3, some of these may be dropped and need recovery.
        repeat(12) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
            repC.apply(repC.state.value.inc(repC.replica, 1L))
        }

        // Recovery rounds: idle → flush residual → kickstart ops (exact multiple of window=3).
        // After each round, the new delta triggers gap detection for any remaining missing seqs.
        // 5 rounds is generous — with drop=20%, P(all 5 resend attempts fail) ≈ 0.03%.
        repeat(5) {
            testScheduler.advanceUntilIdle()
            chaosA.flushReorderBuffer()
            chaosB.flushReorderBuffer()
            chaosC.flushReorderBuffer()
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
            repC.apply(repC.state.value.inc(repC.replica, 1L))
            repC.apply(repC.state.value.inc(repC.replica, 1L))
            repC.apply(repC.state.value.inc(repC.replica, 1L))
        }
        testScheduler.advanceUntilIdle()
        chaosA.flushReorderBuffer()
        chaosB.flushReorderBuffer()
        chaosC.flushReorderBuffer()
        testScheduler.advanceUntilIdle()

        // A: 12 + 5*3 = 27, B: 27, C: 27 → total 81
        assertAll(
            { assertEquals(81L, repA.state.value.value, "A must converge to 81") },
            { assertEquals(81L, repB.state.value.value, "B must converge to 81") },
            { assertEquals(81L, repC.state.value.value, "C must converge to 81") },
        )
    }

    // ---- scenario 7: control-plane Ack chaos — convergence is unaffected ----

    /**
     * Acks travel via [sendTo]; with a 30% Ack drop rate the sender's GC is delayed
     * (it never learns all peers have caught up) but the deltas themselves are still
     * delivered and applied. Convergence must be unaffected — data-plane replication
     * does not depend on Acks arriving.
     *
     * GCounter is the right subject: if duplicate or out-of-order deltas were
     * re-applied, the count would be inflated. Its idempotent max-merge means
     * even if control messages are lost, the state arrives correctly via the data path.
     */
    @Test
    fun convergesWithDroppedAcks() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-control-ack"))
        val rawB = loom.join(InMemoryTag("b"))

        // Drop only control-plane (Acks/Resends/FullState); data path is clean.
        val controlChaos = ChaosConfig(controlPlaneDropProbability = 0.3)
        val chaosA = chaosWrap(rawA, controlChaos, backgroundScope, seed = 0xAC_A1L)
        val chaosB = chaosWrap(rawB, controlChaos, backgroundScope, seed = 0xAC_B1L)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)

        repeat(20) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }
        testScheduler.advanceUntilIdle()

        // 20 increments per peer → total 40; Ack drops don't prevent convergence
        assertAll(
            { assertEquals(40L, repA.state.value.value, "A must converge to 40 despite Ack drops") },
            { assertEquals(40L, repB.state.value.value, "B must converge to 40 despite Ack drops") },
        )
    }

    // ---- scenario 8: control-plane Resend chaos — retry timer bridges the gap ----

    /**
     * Combines broadcast drop (creating delta gaps) with control-plane drop (Resend messages
     * are themselves lost). The replicator's [SeamReplicatorConfig.resendRetryInterval] timer
     * must fire and re-issue the Resend, eventually reaching the sender, who retransmits
     * the missing delta. Both peers must converge.
     *
     * This exercises the highest-risk control-plane path: silent loss of a Resend leaves
     * a gap open indefinitely without the retry timer.
     */
    @Test
    fun convergesWithDroppedResendViaRetry() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-control-resend"))
        val rawB = loom.join(InMemoryTag("b"))

        // Broadcast drops create gaps; control-plane drops lose Resends (retry must heal).
        val combinedChaos = ChaosConfig(
            dropProbability = 0.2,
            controlPlaneDropProbability = 0.3,
        )
        // Short retry interval so virtual-time advancement is minimal.
        val replicatorConfig = SeamReplicatorConfig(
            resendRetryInterval = 50.milliseconds,
            expectVirtualTime = true,
        )
        val chaosA = chaosWrap(rawA, combinedChaos, backgroundScope, seed = 0x5E_A1L)
        val chaosB = chaosWrap(rawB, combinedChaos, backgroundScope, seed = 0x5E_B1L)

        val repA = gcounterReplicator(chaosA, backgroundScope, config = replicatorConfig)
        val repB = gcounterReplicator(chaosB, backgroundScope, config = replicatorConfig)

        repeat(20) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }

        // Recovery rounds: advance virtual time past the retry interval to let retry timers fire.
        // 5 rounds: P(all 5 retries dropped by 30% control-plane chaos) ≈ 0.2%
        repeat(5) {
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(60L) // past the 50ms retry interval
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()

        // 20 + 5 = 25 increments per peer → total 50
        assertAll(
            { assertEquals(50L, repA.state.value.value, "A must converge to 50 after retry-healed Resends") },
            { assertEquals(50L, repB.state.value.value, "B must converge to 50 after retry-healed Resends") },
        )
    }

    // ---- scenario 9: control-plane duplicate Acks are absorbed idempotently ----

    /**
     * With 50% Ack duplication, each Ack may arrive twice. The GC protocol's
     * `ackedThrough[acker] = maxOf(current, seq)` guard must absorb duplicates without
     * over-advancing GC or corrupting per-peer tracking. GCounter convergence to the
     * correct total confirms no spurious mutation.
     */
    @Test
    fun convergesWithDuplicatedControlPlaneMessages() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-control-dup"))
        val rawB = loom.join(InMemoryTag("b"))

        // Duplicate Acks and Resends; no data-plane chaos so all deltas arrive cleanly.
        val controlDupChaos = ChaosConfig(controlPlaneDuplicateProbability = 0.5)
        val chaosA = chaosWrap(rawA, controlDupChaos, backgroundScope, seed = 0xD0_A1L)
        val chaosB = chaosWrap(rawB, controlDupChaos, backgroundScope, seed = 0xD0_B1L)

        val repA = gcounterReplicator(chaosA, backgroundScope)
        val repB = gcounterReplicator(chaosB, backgroundScope)

        repeat(20) {
            repA.apply(repA.state.value.inc(repA.replica, 1L))
            repB.apply(repB.state.value.inc(repB.replica, 1L))
        }
        testScheduler.advanceUntilIdle()

        // Duplicate Acks must not inflate the counter — max-merge is idempotent
        assertAll(
            { assertEquals(40L, repA.state.value.value, "A must not double-count due to duplicated Acks") },
            { assertEquals(40L, repB.state.value.value, "B must not double-count due to duplicated Acks") },
        )
    }

    // ---- scenario 6: eviction under partition, then FullState recovery ----

    /**
     * B disappears from A's peer view for longer than [SeamReplicatorConfig.evictionAfter].
     * A evicts B. When B reappears, A treats it as first-contact and sends a fresh
     * [ReplicatorMessage.FullState]. B converges despite having missed all deltas.
     */
    @Test
    fun evictionUnderPartitionThenFullStateRecovery() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val rawA = loom.host(Pattern("chaos-evict"))
        val rawB = loom.join(InMemoryTag("b"))

        val clock = object : MonotonicMillis {
            var time = 0L
            override fun now(): Long = time
        }
        val config = SeamReplicatorConfig(
            evictionAfter = 100.milliseconds,
            antiEntropyInterval = 50.milliseconds,
            expectVirtualTime = true,
        )

        // Give A a controllable peers view so we can simulate B disappearing
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val controllableA = object : us.tractat.kuilt.core.Seam by rawA {
            override val peers = controlledPeers
        }

        val repA = SeamReplicator(
            replica = ReplicaId(rawA.selfId.value),
            seam = controllableA,
            initial = GCounter.ZERO,
            messageSerializer = gcounterSer,
            scope = backgroundScope,
            config = config,
            clock = clock,
        )
        val repB = gcounterReplicator(rawB, backgroundScope, config, clock)

        // A applies state while B is present
        repA.apply(repA.state.value.inc(repA.replica, 10L))
        testScheduler.advanceUntilIdle()
        assertEquals(10L, repB.state.value.value, "B should receive A's initial delta")

        // B disappears from A's peer view
        controlledPeers.value = setOf(rawA.selfId)
        testScheduler.advanceUntilIdle()

        // Advance clock past eviction TTL + trigger anti-entropy
        clock.time += 150L
        testScheduler.advanceTimeBy(config.antiEntropyInterval.inWholeMilliseconds + 1L)

        assertFalse(repA.knownPeersForTest.contains(rawB.selfId), "B must be evicted after TTL")

        // A applies more state while B is partitioned away
        repA.apply(repA.state.value.inc(repA.replica, 5L))
        testScheduler.advanceUntilIdle()

        // B rejoins A's peer view — A sends FullState
        controlledPeers.value = setOf(rawA.selfId, rawB.selfId)
        testScheduler.advanceUntilIdle()

        // B must converge to A's full state (10 + 5 = 15) via FullState
        assertTrue(repA.knownPeersForTest.contains(rawB.selfId), "B must be re-known after rejoin")
        assertEquals(15L, repB.state.value.value, "B must converge to 15 via FullState after eviction")
    }
}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
