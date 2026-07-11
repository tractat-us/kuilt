package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the **loud-gap** guarantee of [SeamConformanceSuite.everyFalseCapabilityDeclaresAGap]:
 * a fabric can declare a capability `false`, but only if it also records a tracking URL for it.
 * A `false` flag with no URL fails the inherited guard — the gap can never be silent.
 *
 * These tests exercise the guard against purpose-built harnesses rather than a real fabric, so
 * the guarantee is verified directly (a real in-tree fabric declares [SeamCapabilities.FULL] and
 * would only prove the vacuous no-false-flags path).
 *
 * The harness is an **anonymous** [SeamConformanceSuite] built by a factory, not a named subclass —
 * a named concrete subclass inherits the suite's `@Test` methods and the JUnit4 (Android) runner
 * would try to run it as its own test class and fail to construct it.
 */
class SeamConformanceGapDeclarationTest {

    private fun harness(caps: SeamCapabilities, gaps: Map<String, String>): SeamConformanceSuite =
        object : SeamConformanceSuite() {
            private val loom = InMemoryLoom()
            override fun newLoomPair(): Pair<Loom, Loom> = loom to loom
            override fun capabilities(): SeamCapabilities = caps
            override fun capabilityGaps(): Map<String, String> = gaps
        }

    @Test
    fun fullCapabilitiesWithEmptyGapsPasses() {
        // No false flags → nothing to declare → the guard passes.
        harness(SeamCapabilities.FULL, emptyMap()).everyFalseCapabilityDeclaresAGap()
    }

    @Test
    fun falseFlagWithoutGapFailsTheGuard() {
        val h = harness(SeamCapabilities.FULL.copy(meshDelivery = false), emptyMap())
        assertFailsWith<AssertionError>("an undeclared false flag must fail the guard") {
            h.everyFalseCapabilityDeclaresAGap()
        }
    }

    @Test
    fun falseFlagWithGapPasses() {
        // Same false flag, but now with a tracking URL — the guard is satisfied.
        harness(
            caps = SeamCapabilities.FULL.copy(meshDelivery = false),
            gaps = mapOf("meshDelivery" to "https://github.com/tractat-us/kuilt/issues/1404"),
        ).everyFalseCapabilityDeclaresAGap()
    }

    @Test
    fun partiallyDeclaredGapsStillFailForTheMissingFlag() {
        // Two flags off, only one declared — the undeclared one must still trip the guard.
        val h = harness(
            caps = SeamCapabilities.FULL.copy(meshDelivery = false, throwsOnSendToTorn = false),
            gaps = mapOf("meshDelivery" to "https://github.com/tractat-us/kuilt/issues/1404"),
        )
        assertFailsWith<AssertionError> {
            h.everyFalseCapabilityDeclaresAGap()
        }
    }
}
