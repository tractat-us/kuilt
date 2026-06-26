@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.warp

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpResultTest {

    // ── failure() discriminator ───────────────────────────────────────────────

    @Test
    fun failureIsErrorWithEmptyBytes() = assertAll(
        { assertTrue(OpResult.failure("boom").isError, "isError should be true for failure()") },
        { assertEquals(0, OpResult.failure("boom").bytes.size, "failure bytes should be empty") },
    )

    @Test
    fun successIsNotError() {
        assertFalse(OpResult(byteArrayOf(1, 2, 3)).isError)
    }

    // ── equals / hashCode — CRDT convergence ─────────────────────────────────

    @Test
    fun twoFailuresWithSameMessageAreEqual() = assertAll(
        { assertEquals(OpResult.failure("x"), OpResult.failure("x")) },
        { assertEquals(OpResult.failure("x").hashCode(), OpResult.failure("x").hashCode()) },
    )

    @Test
    fun failuresWithDifferentMessagesAreNotEqual() {
        assertNotEquals(OpResult.failure("x"), OpResult.failure("y"))
    }

    @Test
    fun successResultIsNotEqualToFailureResult() {
        // A zero-byte success result must not equal a failure result (same bytes, different error).
        val success = OpResult(ByteArray(0))
        val failure = OpResult.failure("oops")
        assertNotEquals(success, failure)
    }

    @Test
    fun bytesOnlyResultsWithSameContentAreEqual() = assertAll(
        {
            val a = OpResult(byteArrayOf(1, 2, 3))
            val b = OpResult(byteArrayOf(1, 2, 3))
            assertEquals(a, b)
        },
        {
            val a = OpResult(byteArrayOf(1, 2, 3))
            val b = OpResult(byteArrayOf(1, 2, 3))
            assertEquals(a.hashCode(), b.hashCode())
        },
    )

    @Test
    fun bytesOnlyResultsWithDifferentContentAreNotEqual() {
        assertNotEquals(OpResult(byteArrayOf(1, 2, 3)), OpResult(byteArrayOf(1, 2, 4)))
    }

    // ── serialization round-trip ──────────────────────────────────────────────

    @Test
    fun successRoundTripsThroughCbor() {
        val original = OpResult(byteArrayOf(9, 8, 7))
        val restored = Cbor.decodeFromByteArray(OpResult.serializer(), Cbor.encodeToByteArray(original))
        assertEquals(original, restored)
    }

    @Test
    fun failureRoundTripsThroughCbor() {
        val original = OpResult.failure("kernel panicked")
        val restored = Cbor.decodeFromByteArray(OpResult.serializer(), Cbor.encodeToByteArray(original))
        assertAll(
            { assertEquals(original, restored) },
            { assertTrue(restored.isError) },
            { assertEquals("kernel panicked", restored.error) },
        )
    }
}
