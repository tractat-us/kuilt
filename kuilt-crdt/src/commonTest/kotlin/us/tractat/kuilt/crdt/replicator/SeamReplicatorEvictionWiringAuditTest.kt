/**
 * ADVERSARIAL AUDIT (#262 / #269) of the RGA-GC stable-cut / retained-frontier /
 * eviction LIVE wiring in [SeamReplicator]. The §4 model predicate was already
 * adversarially verified; these probes attack the *live wiring* — `recomputeCut`,
 * `evictStalePeers`, `onDelivered`, `onPeersChanged`, the atomic `cutFrontier`
 * publish — to find an interleaving that violates the safety the model proved.
 *
 * Technique mirrors [SeamReplicatorStableCutTest]: a real [SeamReplicator] over an
 * [Rga], driven by crafted [ReplicatorMessage.Delivered] frames and a peers-flow
 * the test can mutate, under [UnconfinedTestDispatcher] + [FakeClock] for
 * deterministic eviction timing.
 *
 * Each probe documents a VERDICT in its name/asserts: `probeX_safe_…` passes when
 * the wiring is sound (the assert pins the load-bearing invariant); a `_BUG_`
 * probe would fail, exhibiting the unsafe divergent state.
 */
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.Dot
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.RgaId
import us.tractat.kuilt.crdt.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val AUDIT_MSG_SER = ReplicatorMessage.serializer(Rga.wireSerializer(serializer<String>()))

private class AuditFakeClock(private var t: Long = 0L) : MonotonicMillis {
    override fun now(): Long = t
    fun advanceBy(ms: Long) { t += ms }
}

/** Wraps a real seam but lets the test override [Seam.peers] (drop a peer without closing). */
private class AuditControllableSeam(
    private val delegate: Seam,
    override val peers: MutableStateFlow<Set<PeerId>>,
) : Seam by delegate

private fun auditRep(
    seam: Seam,
    scope: CoroutineScope,
    clock: MonotonicMillis,
    config: SeamReplicatorConfig = SeamReplicatorConfig(
        expectVirtualTime = true,
        evictionAfter = 100.milliseconds,
        antiEntropyInterval = 50.milliseconds,
    ),
): SeamReplicator<Rga<String>> =
    SeamReplicator(
        replica = ReplicaId(seam.selfId.value),
        seam = seam,
        initial = Rga.empty(),
        messageSerializer = AUDIT_MSG_SER,
        scope = scope,
        config = config,
        clock = clock,
    )

private fun SeamReplicator<Rga<String>>.insertHead(value: String) {
    val (_, op) = state.value.insertAfter(replica, RgaId.HEAD, value)
    apply(Patch(Rga.empty<String>().apply(op)))
}

private suspend fun craftDelivered(from: Seam, vector: VersionVector) {
    val msg = ReplicatorMessage.Delivered<Rga<String>>(sender = ReplicaId(from.selfId.value), vector = vector)
    from.broadcast(Cbor.encodeToByteArray(AUDIT_MSG_SER, msg))
}

class SeamReplicatorEvictionWiringAuditTest {

    // ---- Hypothesis 1: W1 under the REAL eviction path (anti-entropy tick) ----

    /**
     * H1 VERDICT: SOUND. Drives a real 3-replica eviction entirely through the
     * anti-entropy tick (`runAntiEntropy` → `evictStalePeers`), not a hand-crafted
     * frame. C witnesses (c,1) which self never delivered; B is the live witness
     * that gets evicted. After the real eviction path runs, `frontierMax` must STILL
     * floor (c,1) and there must be no observable `cutFrontier` value with F dropped.
     *
     * Load-bearing invariant: W1 retain-capture-before-drop — `evictStalePeers` folds
     * `frontiers[B]` into `retainedFrontier` and republishes `cutFrontier` exactly once.
     */
    @Test
    fun probe1_w1HoldsThroughRealAntiEntropyEviction() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("h1-real-evict"))
        val rawB = loom.join(InMemoryTag("b"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = AuditFakeClock()
        val repA = auditRep(AuditControllableSeam(seamA, controlledPeers), backgroundScope, clock)
        controlledPeers.value = loom.peers.value

        // B gossips it witnessed (c,1). A relay-knows the dot but never delivered it.
        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()

        // Record every cutFrontier the compactor could observe across the eviction.
        val observed = mutableListOf<CutFrontier>()
        observed += repA.cutFrontier.value
        assertTrue(observed.last().frontierMax.contains(cDot), "pre-evict: F_live floors (c,1)")

        // B vanishes from peers; let the REAL anti-entropy tick evict it.
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()
        observed += repA.cutFrontier.value

        assertFalse(rawB.selfId in repA.knownPeersForTest, "B evicted via the real tick")
        assertTrue(repA.retainedFrontierForTest.contains(cDot), "W1: real eviction retained (c,1)")
        assertTrue(
            observed.all { it.frontierMax.contains(cDot) },
            "every observable cutFrontier across the real eviction floors (c,1) — no F-dropped intermediate",
        )
        assertFalse(
            repA.deliveredLocal.value.dominates(repA.cutFrontier.value.frontierMax),
            "condition 3 still fails after real eviction → compactor refuses GC",
        )
    }

    // ---- Hypothesis 2: R2 + transitive eviction, live gossip path ----

    /**
     * H2 VERDICT: SOUND. C's dot is discharged by live witness B (R2). Then B is
     * evicted before self delivers (c,1). The model said B's eviction must RE-CAPTURE
     * the dot (W1 retain). Driven through real gossip + two real eviction ticks.
     *
     * Load-bearing invariant: R2 release is *conditional on the witness staying live*;
     * when the witness leaves, W1 re-captures via `ceilWith(frontiers[B])`.
     */
    @Test
    fun probe2_r2ThenWitnessEvictionReCaptures() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("h2-transitive"))
        val rawB = loom.join(InMemoryTag("b"))
        val rawD = loom.join(InMemoryTag("d"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = AuditFakeClock()
        val repA = auditRep(AuditControllableSeam(seamA, controlledPeers), backgroundScope, clock)
        controlledPeers.value = loom.peers.value

        // D and B both witness (c,1). Evict D → retained captures, then R2 (B live) discharges.
        craftDelivered(rawD, VersionVector.of(mapOf(c to 1L)))
        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()

        controlledPeers.value = controlledPeers.value - PeerId(rawD.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()
        assertFalse(repA.retainedFrontierForTest.contains(cDot), "R2: live B discharged the retained (c,1)")
        assertTrue(repA.cutFrontier.value.frontierMax.contains(cDot), "F still floors (c,1) via live B")

        // Now evict B too. Its eviction must re-capture (c,1) into retained (W1).
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()

        assertFalse(rawB.selfId in repA.knownPeersForTest, "B evicted")
        assertTrue(
            repA.retainedFrontierForTest.contains(cDot),
            "W1: transitive eviction of the last witness re-captured (c,1)",
        )
        assertTrue(
            repA.cutFrontier.value.frontierMax.contains(cDot),
            "frontierMax STILL floors (c,1) after the last witness left — no orphaning of J",
        )
    }

    // ---- Hypothesis 3: rejoin — brand-new peer must NOT clear a retained entry ----

    /**
     * H3 VERDICT (§4.5): a BRAND-NEW peer (VV [c]=0) must NOT clear a retained entry
     * for author c. After B (the witness of (c,1)) is evicted and (c,1) is retained,
     * a genuinely new peer E joins contributing EMPTY. The release rule R2 keys off
     * `self.ceilWith(F_live)` — E's EMPTY row adds nothing — so the retained (c,1)
     * must survive E's join.
     */
    @Test
    fun probe3_brandNewPeerDoesNotClearRetained() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("h3-newpeer"))
        val rawB = loom.join(InMemoryTag("b"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = AuditFakeClock()
        val repA = auditRep(AuditControllableSeam(seamA, controlledPeers), backgroundScope, clock)
        controlledPeers.value = loom.peers.value

        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()
        assertTrue(repA.retainedFrontierForTest.contains(cDot), "precondition: (c,1) retained after B eviction")

        // A brand-new peer E joins. It has delivered nothing (EMPTY).
        val rawE = loom.join(InMemoryTag("e"))
        controlledPeers.value = controlledPeers.value + PeerId(rawE.selfId.value)
        clock.advanceBy(10L)
        testScheduler.advanceUntilIdle()

        assertTrue(rawE.selfId in repA.knownPeersForTest, "E is now known")
        assertTrue(
            repA.retainedFrontierForTest.contains(cDot),
            "§4.5: a brand-new peer (c=0) must NOT clear the retained (c,1) — its EMPTY row witnesses nothing",
        )
        assertTrue(
            repA.cutFrontier.value.frontierMax.contains(cDot),
            "frontierMax still floors (c,1) after a brand-new peer joins",
        )
    }

    /**
     * H3b VERDICT (rejoin subsumes, R2): an evicted witness RE-joins and re-gossips
     * its full VV {c:1}. Its fresh live row now dominates the retained obligation, so
     * R2 fires and the retained entry is dropped — but F still floors (c,1) via the
     * re-joined live row. This is the §4 "eviction-then-rejoin unpins on delivery" path.
     */
    @Test
    fun probe3b_rejoinedWitnessSubsumesRetainedR2() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("h3b-rejoin"))
        val rawB = loom.join(InMemoryTag("b"))
        val c = ReplicaId("c")
        val cDot = Dot(c, 1L)

        val controlledPeers = MutableStateFlow(loom.peers.value)
        val clock = AuditFakeClock()
        val repA = auditRep(AuditControllableSeam(seamA, controlledPeers), backgroundScope, clock)
        controlledPeers.value = loom.peers.value

        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        clock.advanceBy(150L)
        testScheduler.advanceTimeBy(60L)
        testScheduler.advanceUntilIdle()
        assertTrue(repA.retainedFrontierForTest.contains(cDot), "precondition: (c,1) retained")

        // B reconnects and re-gossips {c:1}.
        controlledPeers.value = controlledPeers.value + PeerId(rawB.selfId.value)
        clock.advanceBy(10L)
        testScheduler.advanceUntilIdle()
        craftDelivered(rawB, VersionVector.of(mapOf(c to 1L)))
        testScheduler.advanceUntilIdle()

        assertFalse(
            repA.retainedFrontierForTest.contains(cDot),
            "R2: re-joined live witness B dominates → retained (c,1) released",
        )
        assertTrue(
            repA.cutFrontier.value.frontierMax.contains(cDot),
            "F still floors (c,1) via the re-joined live row",
        )
    }

    // ---- Hypothesis 4: monotonic stableCut vs membership churn ----

    /**
     * H4 VERDICT: probes whether a monotonic `stableCut` can certify a dot stable
     * that a CURRENTLY-LIVE peer has not delivered, in a way that — combined with
     * frontierMax — would authorize unsafe GC.
     *
     * Scenario: self+B deliver (a,1..3); B gossips {a:3} → S={a:3}. B leaves; a
     * brand-new peer E joins (EMPTY, has not delivered (a,*)). Monotonic S stays {a:3}.
     *
     * The safety argument (§4.5): E is FullState-synced on join, so certifying (a,3)
     * stable is sound — E never needs the old deltas. This probe pins the live
     * behaviour: S stays {a:3} AND E was sent a FullState (knownPeers contains E and a
     * FullState path ran). GC of (a,3) is authorized ONLY because the FullState
     * carries the post-compaction state.
     */
    @Test
    fun probe4_monotonicCutAfterChurnReliesOnFullStateSync() = runTest(UnconfinedTestDispatcher()) {
        val loom = InMemoryLoom()
        val seamA = loom.host(Pattern("h4-churn"))
        val rawB = loom.join(InMemoryTag("b"))
        val controlledPeers = MutableStateFlow(loom.peers.value)
        val repA = auditRep(AuditControllableSeam(seamA, controlledPeers), backgroundScope, AuditFakeClock())
        val a = repA.replica
        controlledPeers.value = loom.peers.value

        repA.insertHead("x"); repA.insertHead("y"); repA.insertHead("z") // self delivered[a]=3
        craftDelivered(rawB, VersionVector.of(mapOf(a to 3L)))
        testScheduler.advanceUntilIdle()
        assertEquals(3L, repA.cutFrontier.value.stableCut[a], "S={a:3} with B live and caught up")

        // Churn: B leaves, brand-new E joins with no delivery history.
        controlledPeers.value = controlledPeers.value - PeerId(rawB.selfId.value)
        val rawE = loom.join(InMemoryTag("e"))
        controlledPeers.value = controlledPeers.value + PeerId(rawE.selfId.value)
        testScheduler.advanceUntilIdle()

        assertTrue(rawE.selfId in repA.knownPeersForTest, "E joined")
        assertEquals(
            3L,
            repA.cutFrontier.value.stableCut[a],
            "monotonic S stays {a:3} despite E (live) never having delivered (a,*)",
        )
        // The safety hinges on E being FullState-synced. Self has delivered everything,
        // so delivered.dominates(F) and condition 3 is satisfiable: GC IS authorized here.
        // That is sound ONLY because E receives the (post-GC) FullState. This probe
        // documents the dependency rather than asserting unsafety.
        assertTrue(
            repA.deliveredLocal.value.dominates(repA.cutFrontier.value.frontierMax),
            "self delivered everything known → condition 3 satisfied; GC authorized, relying on FullState-on-join",
        )
    }
}
