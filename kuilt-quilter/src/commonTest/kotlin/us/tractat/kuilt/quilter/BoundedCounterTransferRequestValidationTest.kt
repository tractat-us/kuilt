@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A transfer request asks for at least one unit, and a coordinator's config can only build such a
 * request and keeps a floor of at least 0.
 *
 * The bound lives on [BoundedCounterCoordMessage.TransferRequest] itself, so a frame from a peer that
 * asks for 0 or fewer units is refused while it is decoded, and never reaches
 * [us.tractat.kuilt.crdt.BoundedCounter.transfer], whose own `require` used to throw on it once per
 * frame. The config's bounds are what keep our own borrow from minting a request the type refuses.
 */
class BoundedCounterTransferRequestValidationTest {

    private val requester = ReplicaId("validation-requester")
    private val serializer = BoundedCounterCoordMessage.serializer()

    @Test
    fun theMirrorIsByteIdenticalToTheRealEncoderAtAValidAmount() {
        val real = Cbor.encodeToByteArray(serializer, BoundedCounterCoordMessage.TransferRequest(requester, 10L))
        val decoded = Cbor.decodeFromByteArray(serializer, unvalidatedTransferRequestFrame(requester, 10L))
        assertAll(
            {
                assertTrue(
                    real.contentEquals(unvalidatedTransferRequestFrame(requester, 10L)),
                    "the rig: the mirror writes exactly what the real encoder writes",
                )
            },
            {
                val request = decoded as BoundedCounterCoordMessage.TransferRequest
                assertEquals(10L, request.amount, "the rig: a valid mirror frame decodes as a request")
            },
        )
    }

    @Test
    fun aRequestForNoUnitsIsRefusedByItsConstructor() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { BoundedCounterCoordMessage.TransferRequest(requester, 0L) } },
            { assertFailsWith<IllegalArgumentException> { BoundedCounterCoordMessage.TransferRequest(requester, -1L) } },
            { assertEquals(1L, BoundedCounterCoordMessage.TransferRequest(requester, 1L).amount) },
        )
    }

    /**
     * The decoder runs the constructor's `init`, so the refusal reaches a frame off the wire too. The
     * message is checked as well as the type: a `SerializationException` is an
     * [IllegalArgumentException], so a mirror that drifted from the real encoding would also throw
     * one, and the type alone could not tell the two apart.
     */
    @Test
    fun aDecodedRequestForNoUnitsIsRefused() {
        listOf(0L, -1L).forEach { amount ->
            val failure = assertFailsWith<IllegalArgumentException> {
                Cbor.decodeFromByteArray(serializer, unvalidatedTransferRequestFrame(requester, amount))
            }
            assertTrue(
                failure.message.orEmpty().contains("at least 1"),
                "amount $amount must be refused by TransferRequest's own bound, got: $failure",
            )
        }
    }

    @Test
    fun aConfigThatWouldRequestNoUnitsIsRefused() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { BoundedCounterTransferConfig(requestedAmount = 0L) } },
            { assertFailsWith<IllegalArgumentException> { BoundedCounterTransferConfig(requestedAmount = -1L) } },
            { assertEquals(1L, BoundedCounterTransferConfig(requestedAmount = 1L).requestedAmount) },
        )
    }

    @Test
    fun aNegativeSurplusFloorIsRefused() {
        assertAll(
            { assertFailsWith<IllegalArgumentException> { BoundedCounterTransferConfig(surplusFloor = -1L) } },
            { assertFailsWith<IllegalArgumentException> { BoundedCounterTransferConfig(surplusFloor = Long.MIN_VALUE) } },
            { assertEquals(0L, BoundedCounterTransferConfig(surplusFloor = 0L).surplusFloor) },
        )
    }
}

/**
 * A transfer request frame with any amount, including the ones [BoundedCounterCoordMessage.TransferRequest]
 * refuses. Our own encoder runs that constructor on the way out, so it cannot write them; a peer
 * sending one is not running our encoder either.
 */
internal fun unvalidatedTransferRequestFrame(requester: ReplicaId, amount: Long): ByteArray =
    Cbor.encodeToByteArray(
        UnvalidatedCoordMessage.serializer(),
        UnvalidatedCoordMessage.TransferRequest(requester, amount),
    )

/**
 * A bound-free mirror of [BoundedCounterCoordMessage]. The polymorphic discriminator is the subclass's
 * serial name, so this one-armed copy encodes as the real one does, which
 * [BoundedCounterTransferRequestValidationTest.theMirrorIsByteIdenticalToTheRealEncoderAtAValidAmount]
 * checks rather than assumes.
 */
@Serializable
internal sealed class UnvalidatedCoordMessage {
    @Serializable
    @SerialName("transferRequest")
    internal class TransferRequest(val requester: ReplicaId, val amount: Long) : UnvalidatedCoordMessage()
}
