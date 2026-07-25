package us.tractat.kuilt.warp

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the location-eligibility predicate ([Affinity]) and the peer capability
 * set ([CapSet]) it evaluates against — H8, design §14.6.
 *
 * These are pure and deterministic: an [Affinity] is a serializable expression over the
 * opaque capability tokens a peer advertises. Warp core evaluates it with [Affinity.matches]
 * and never interprets the tokens themselves (they are strings supplied by the caller).
 */
class AffinityTest {

    private val gpuUsEast = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))
    private val cpuUsWest = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))

    @Test
    fun anywhereMatchesEveryCapSet() {
        assertAll(
            { assertTrue(Affinity.Anywhere.matches(gpuUsEast)) },
            { assertTrue(Affinity.Anywhere.matches(CapSet.EMPTY)) },
        )
    }

    @Test
    fun hasTokenMatchesOnlyWhenPresent() {
        assertAll(
            { assertTrue(Affinity.has("GPU").matches(gpuUsEast)) },
            { assertFalse(Affinity.has("GPU").matches(cpuUsWest)) },
            { assertFalse(Affinity.has("GPU").matches(CapSet.EMPTY)) },
        )
    }

    @Test
    fun attrMatchesOnlyOnExactValue() {
        assertAll(
            { assertTrue(Affinity.attr("region", "us-east").matches(gpuUsEast)) },
            { assertFalse(Affinity.attr("region", "us-east").matches(cpuUsWest)) },
            { assertFalse(Affinity.attr("region", "us-east").matches(CapSet.EMPTY)) },
        )
    }

    @Test
    fun andRequiresBothTerms() {
        val predicate = Affinity.has("GPU") and Affinity.attr("region", "us-east")
        assertAll(
            { assertTrue(predicate.matches(gpuUsEast)) },
            { assertFalse(predicate.matches(cpuUsWest)) },
            { assertFalse(predicate.matches(CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-west")))) },
        )
    }

    @Test
    fun orAndNotCompose() {
        assertAll(
            { assertTrue((Affinity.has("GPU") or Affinity.has("TPU")).matches(gpuUsEast)) },
            { assertFalse((Affinity.has("TPU") or Affinity.has("FPGA")).matches(gpuUsEast)) },
            { assertTrue(not(Affinity.has("TPU")).matches(gpuUsEast)) },
            { assertFalse(not(Affinity.has("GPU")).matches(gpuUsEast)) },
        )
    }

    @Test
    fun matchesIsPureAndRepeatable() {
        val predicate = Affinity.has("GPU") and Affinity.attr("region", "us-east")
        assertEquals(predicate.matches(gpuUsEast), predicate.matches(gpuUsEast))
    }
}
