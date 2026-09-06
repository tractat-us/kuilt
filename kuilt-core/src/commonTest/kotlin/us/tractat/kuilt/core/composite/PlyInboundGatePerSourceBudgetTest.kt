package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The origin cap has a **per-source** dimension, so a flooder can only spend its own share (#1874).
 *
 * The source is `(plyId, transportSender)` — the pair `CompositeSeam` already holds at the
 * `onPlyFrame` call site and already uses as the slot key for `idMap`. Neither half is frame-chosen:
 * `plyId` is named by the local consumer's `CompositeLoom` desired set and never appears on the
 * wire, and `transportSender` is the ply fabric's own view of who sent the frame. The one thing that
 * *is* attacker-chosen is `originId`, which is what the cap bounds.
 *
 * Each test isolates one dimension so a green cannot come from the wrong half of the key:
 * [aFloodDoesNotRefuseAnHonestFirstFrameOnAnotherPly] is the headline (both halves differ),
 * [aFloodDoesNotRefuseTheSameSenderOnAnotherPly] varies only the ply, and
 * [aFloodDoesNotRefuseADifferentSenderOnTheSamePly] varies only the sender. The negative arm
 * [aFloodStillRefusesTheNextOriginFromItsOwnSource] is what separates "per-source budgeting works"
 * from "the cap was removed", and
 * [oneSourceCannotSpendTheWholePoolWhichIsStillSeamWideBounded] reds in *both* directions: on the
 * left if there is no per-source dimension, on the right if the per-source share made the table
 * unbounded — which would be #1814's hole one level up.
 *
 * The last two are about the fix rather than the property.
 * [refusedSourcesGetNoBucketSoTheFixesOwnTableStaysBounded] covers the one hole no behavioural test
 * here can see, and [theSameOriginOverTwoPliesIsStillDeduplicated] the one thing a per-source key
 * would have destroyed.
 */
class PlyInboundGatePerSourceBudgetTest {
    private fun data(seq: Long, origin: String, payload: Byte = seq.toByte()) =
        PlyFrame.Data(PeerId(origin), seq, byteArrayOf(payload))

    private fun payloads(out: List<ByteArray>) = out.map { it.toList() }

    /** What one flood actually did, so a test can assert its rig fired rather than assume it. */
    private data class Flood(val admitted: Int, val refusals: Int)

    /**
     * Spend one source's admission share by offering [FLOOD_ATTEMPTS] distinct, well-formed origins
     * through it, and report how many were admitted and how many refused. Setup only — the
     * assertions belong to the tests, including the one that checks [Flood.refusals] is non-zero.
     *
     * [FLOOD_ATTEMPTS] deliberately exceeds the seam-wide cap, so this exhausts *whatever* share the
     * gate grants a source without the test having to know the number: a helper that stopped at a
     * hardcoded count would report `refusals == 0` the moment the share was raised, and the rig
     * precondition would then be the only thing failing while the real property went untested.
     *
     * The catch is narrowed to the refusal, as in `PlyInboundGateTest.fillOneSourcesShare` and for
     * the same reason: a `runCatching` would also swallow the [OutOfMemoryError] an unbounded table
     * can raise — the very defect this cap exists to prevent — and report a bogus share.
     */
    private fun PlyInboundGate.floodShareOf(plyId: PlyId, sender: PeerId?, tag: String): Flood {
        var admitted = 0
        var refusals = 0
        repeat(FLOOD_ATTEMPTS) { i ->
            try {
                accept(plyId, sender, data(seq = 0, origin = "$tag-$i"))
                admitted++
                // ALLOW-ise: nothing in the `try` can suspend — `floodShareOf` and `accept` are both non-`suspend`
            } catch (_: IllegalStateException) {
                refusals++
            }
        }
        return Flood(admitted, refusals)
    }

    /**
     * The headline property. A flooder on ply A must not be able to refuse an honest peer's **first**
     * `Data` frame arriving on ply B — the seam-wide pool of #1814 let it, for the life of the seam.
     *
     * The assertion is the *outcome*: the honest payload comes back for delivery. `CompositeSeam`
     * hands exactly this list to `spool.deliver` and drops the frame if `accept` throws, so an empty
     * or absent list is a dropped message, not a moved counter.
     */
    @Test
    fun aFloodDoesNotRefuseAnHonestFirstFrameOnAnotherPly() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val flood = gate.floodShareOf(PLY_A, FLOODER, tag = "flood")
        assertAll(
            // Rig precondition: an unreached rig is green by absence. Without this the honest arm
            // below would pass just as happily against a gate the flood never filled.
            {
                assertTrue(
                    flood.refusals > 0,
                    "the flood never exhausted its share: ${flood.admitted} admitted, ${flood.refusals} refused",
                )
            },
            {
                assertEquals(
                    listOf(listOf(HONEST_PAYLOAD)),
                    payloads(gate.accept(PLY_B, HONEST, data(seq = 7, origin = "honest", payload = HONEST_PAYLOAD))),
                    "an honest peer's first frame on ply B must still be delivered after a flood on ply A",
                )
            },
        )
    }

    /** Isolates the ply half of the key: same transport id, different ply, still admitted. */
    @Test
    fun aFloodDoesNotRefuseTheSameSenderOnAnotherPly() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val flood = gate.floodShareOf(PLY_A, FLOODER, tag = "flood")
        assertAll(
            { assertTrue(flood.refusals > 0, "rig: the flood must have exhausted ply A's share for this source") },
            {
                assertEquals(
                    listOf(listOf(HONEST_PAYLOAD)),
                    payloads(gate.accept(PLY_B, FLOODER, data(seq = 0, origin = "honest", payload = HONEST_PAYLOAD))),
                    "a ply the flood never touched must still admit a new origin",
                )
            },
        )
    }

    /** Isolates the sender half of the key: same ply, different transport id, still admitted. */
    @Test
    fun aFloodDoesNotRefuseADifferentSenderOnTheSamePly() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val flood = gate.floodShareOf(PLY_A, FLOODER, tag = "flood")
        assertAll(
            { assertTrue(flood.refusals > 0, "rig: the flood must have exhausted its own share on ply A") },
            {
                assertEquals(
                    listOf(listOf(HONEST_PAYLOAD)),
                    payloads(gate.accept(PLY_A, HONEST, data(seq = 0, origin = "honest", payload = HONEST_PAYLOAD))),
                    "a different transport-attributed sender on the same ply must still admit a new origin",
                )
            },
        )
    }

    /**
     * The negative arm. Budgeting per source is not the same as not budgeting: the source that spent
     * its share is still refused, so "a flooder can only exhaust its own share" keeps its second half.
     */
    @Test
    fun aFloodStillRefusesTheNextOriginFromItsOwnSource() {
        val gate = PlyInboundGate(maxBuffered = 8)
        val flood = gate.floodShareOf(PLY_A, FLOODER, tag = "flood")
        assertAll(
            { assertTrue(flood.refusals > 0, "rig: the flood must have exhausted its own share") },
            {
                assertFailsWith<IllegalStateException>("the source that spent its share gets nothing more") {
                    gate.accept(PLY_A, FLOODER, data(seq = 0, origin = "one-more"))
                }
            },
        )
    }

    /**
     * What the fix is now unpinned on. A per-source share with no seam-wide ceiling would let an
     * attacker who can present many transport identities — trivial on a relay ply — multiply the
     * table by the number of sources, reintroducing #1814's unbounded growth one level up.
     *
     * Reds in both directions, which is why it is one test: on the left if a single source can still
     * spend the whole pool (no per-source dimension), on the right if fresh sources are admitted
     * forever (no seam-wide ceiling).
     */
    @Test
    fun oneSourceCannotSpendTheWholePoolWhichIsStillSeamWideBounded() {
        val oneSourceShare = PlyInboundGate(maxBuffered = 8).floodShareOf(PLY_A, FLOODER, tag = "solo").admitted

        val gate = PlyInboundGate(maxBuffered = 8)
        var total = 0
        var sources = 0
        while (sources < SOURCE_PROBE_CEILING) {
            val flood = gate.floodShareOf(PLY_A, PeerId("flooder-$sources"), tag = "s$sources")
            if (flood.admitted == 0) break // the seam-wide pool is spent: a fresh source gets nothing
            total += flood.admitted
            sources++
        }
        assertAll(
            { assertTrue(oneSourceShare > 0, "rig: a fresh source must be admitted at least one origin") },
            {
                assertTrue(
                    total > oneSourceShare,
                    "one source spent the whole pool ($oneSourceShare of $total origins): the cap has no per-source dimension",
                )
            },
            {
                assertTrue(
                    sources < SOURCE_PROBE_CEILING,
                    "fresh sources were still being admitted after $sources of them ($total origins): " +
                        "the per-source share made the table unbounded — #1814's hole one level up",
                )
            },
        )
    }

    /**
     * The other thing the fix is unpinned on, and the one that needed an accessor to see at all.
     *
     * `admittedBySource` is bounded only because an entry is written on the **admission** path and
     * never on the refusal path. Write it with a `getOrPut` instead — the obvious spelling — and a
     * flood rotating its *transport identity* after the pool is spent grows that map without limit
     * while every behavioural test in this class stays green (measured). So this asserts the
     * invariant directly: source buckets can never outnumber the admissions that created them.
     */
    @Test
    fun refusedSourcesGetNoBucketSoTheFixesOwnTableStaysBounded() {
        val gate = PlyInboundGate(maxBuffered = 8)

        // Spend the seam-wide pool first, so every probe below can only ever be refused.
        var poolSources = 0
        while (poolSources < SOURCE_PROBE_CEILING) {
            if (gate.floodShareOf(PLY_A, PeerId("flooder-$poolSources"), tag = "s$poolSources").admitted == 0) break
            poolSources++
        }
        val admittedWhenSpent = gate.admittedOriginCount
        val bucketsWhenSpent = gate.sourceBucketCount

        var refused = 0
        repeat(REFUSAL_PROBE_SOURCES) { i ->
            try {
                gate.accept(PLY_A, PeerId("rotating-$i"), data(seq = 0, origin = "rot-$i"))
                // ALLOW-ise: nothing in the `try` can suspend — this test and `accept` are both non-`suspend`
            } catch (_: IllegalStateException) {
                refused++
            }
        }

        assertAll(
            // Rig: the pool really was spent, and every probe really was refused. Either failing
            // would make the invariant below hold vacuously.
            { assertTrue(poolSources in 1 until SOURCE_PROBE_CEILING, "rig: the pool must spend within the probe ceiling, took $poolSources sources") },
            { assertEquals(REFUSAL_PROBE_SOURCES, refused, "rig: every rotating source must have been refused") },
            { assertEquals(admittedWhenSpent, gate.admittedOriginCount, "a refusal must record nothing") },
            {
                assertEquals(
                    bucketsWhenSpent,
                    gate.sourceBucketCount,
                    "$REFUSAL_PROBE_SOURCES refused sources added buckets: the source table grows on the refusal path",
                )
            },
            {
                assertTrue(
                    gate.sourceBucketCount <= gate.admittedOriginCount,
                    "${gate.sourceBucketCount} source buckets for ${gate.admittedOriginCount} admitted origins: " +
                        "the table the per-source share adds is not bounded by the ceiling that bounds admissions",
                )
            },
        )
    }

    /**
     * Non-regression, and the reason the per-source dimension is *accounting* rather than a finer key
     * on the state maps. The gate exists to collapse the same `(originId, originSeq)` arriving over
     * several plies; partitioning [PlyInboundGate]'s per-origin state by source would give one origin
     * two independent sequence baselines and deliver every frame twice.
     */
    @Test
    fun theSameOriginOverTwoPliesIsStillDeduplicated() {
        val gate = PlyInboundGate(maxBuffered = 8)
        assertAll(
            {
                assertEquals(
                    listOf(listOf(HONEST_PAYLOAD)),
                    payloads(gate.accept(PLY_A, FLOODER, data(seq = 0, origin = "honest", payload = HONEST_PAYLOAD))),
                    "first copy, over ply A",
                )
            },
            {
                assertTrue(
                    gate.accept(PLY_B, HONEST, data(seq = 0, origin = "honest", payload = HONEST_PAYLOAD)).isEmpty(),
                    "the same (origin, seq) over ply B is the multipath duplicate this gate collapses",
                )
            },
        )
    }

    private companion object {
        val PLY_A = PlyId("ply-a")
        val PLY_B = PlyId("ply-b")

        /** Transport-attributed sender ids — the ply fabric's view, never read off the frame. */
        val FLOODER = PeerId("transport-flooder")
        val HONEST = PeerId("transport-honest")

        const val HONEST_PAYLOAD: Byte = 42

        /**
         * Offers per flood. Must exceed whatever share one source is granted, or a flood ends without
         * ever being refused and every rig precondition here fails instead of the property. Chosen
         * above the seam-wide cap, which is an upper bound on any per-source share.
         */
        const val FLOOD_ATTEMPTS = 512

        /**
         * Distinct sources probed before the table is declared unbounded. Far above the number a
         * seam-wide ceiling can ever admit (that ceiling divided by the per-source share), so
         * "ran to the ceiling" can only mean fresh sources are admitted without limit.
         */
        const val SOURCE_PROBE_CEILING = 1024

        /**
         * Distinct transport identities offered *after* the pool is spent. Only has to exceed the
         * number of buckets a spent pool legitimately holds (the seam-wide cap divided by the
         * per-source share) by enough that an accidental insert is unmistakable.
         */
        const val REFUSAL_PROBE_SOURCES = 512
    }
}
