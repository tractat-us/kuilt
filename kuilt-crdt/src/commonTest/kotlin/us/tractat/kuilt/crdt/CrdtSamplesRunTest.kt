package us.tractat.kuilt.crdt

import kotlin.test.Test

/**
 * Runs every `@sample` in `CrdtSamples.kt`.
 *
 * A sample is *compiled* as part of `commonTest`, so a stale API breaks the build — but its
 * `check(…)` calls are only executed if something calls the function, and until this test nothing
 * did. A sample whose claim had quietly become false therefore stayed green, and `verifyDocCitations`
 * would go on faithfully proving that the cookbook and the Writerside pages quote it **verbatim**.
 *
 * That is not hypothetical: writing #2044's Task 7 I produced an `ORSet` sample asserting that a
 * concurrent add survives a remove, built from a re-add that reused a dot the remover had already
 * witnessed — so the element was in fact dropped. It compiled, it was quoted verbatim in
 * `docs/agent-cookbook.md` and `Writerside/topics/crdt-orset.md`, and nothing anywhere disagreed.
 *
 * New samples go in the list below. A sample that cannot run un-parameterised does not belong in
 * `CrdtSamples.kt`.
 */
class CrdtSamplesRunTest {

    @Test
    fun everySampleHolds() {
        val samples: List<Pair<String, () -> Unit>> = listOf(
            "sampleGCounter" to ::sampleGCounter,
            "sampleGCounterPiece" to ::sampleGCounterPiece,
            "sampleGCounterDouble" to ::sampleGCounterDouble,
            "samplePNCounter" to ::samplePNCounter,
            "sampleGSet" to ::sampleGSet,
            "sampleTwoPhaseSet" to ::sampleTwoPhaseSet,
            "sampleORSet" to ::sampleORSet,
            "sampleLWWRegister" to ::sampleLWWRegister,
            "sampleMVRegister" to ::sampleMVRegister,
            "sampleLWWMap" to ::sampleLWWMap,
            "sampleORMap" to ::sampleORMap,
            "sampleBoundedCounter" to ::sampleBoundedCounter,
            "sampleCausal" to ::sampleCausal,
            "sampleResettableCounter" to ::sampleResettableCounter,
            "sampleBloomFilter" to ::sampleBloomFilter,
            "sampleFugue" to ::sampleFugue,
            "sampleRga" to ::sampleRga,
            "sampleMovableTree" to ::sampleMovableTree,
            "sampleHyperLogLog" to ::sampleHyperLogLog,
            "sampleHyperLogLogMerge" to ::sampleHyperLogLogMerge,
            "sampleCountMinSketch" to ::sampleCountMinSketch,
            "sampleCountMinSketchMerge" to ::sampleCountMinSketchMerge,
            "sampleDDSketch" to ::sampleDDSketch,
            "sampleDDSketchMerge" to ::sampleDDSketchMerge,
            "sampleGauge" to ::sampleGauge,
            "sampleHistogram" to ::sampleHistogram,
            "sampleHistogramMerge" to ::sampleHistogramMerge,
            "sampleLatticeProduct" to ::sampleLatticeProduct,
            "sampleEphemeralMapTrackerChannels" to ::sampleEphemeralMapTrackerChannels,
        )
        us.tractat.kuilt.test.assertAll(
            *samples.map { (name, run) -> { runNamed(name, run) } }.toTypedArray(),
        )
    }

    private fun runNamed(name: String, run: () -> Unit) {
        try {
            run()
        } catch (failure: IllegalStateException) {
            throw AssertionError("$name failed its own check(): ${failure.message}", failure)
        }
    }
}
