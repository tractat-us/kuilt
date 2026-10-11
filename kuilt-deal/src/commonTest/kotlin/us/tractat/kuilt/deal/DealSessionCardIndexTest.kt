@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DealSessionCardIndexTest {
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test
    fun negativeIndexDoesNotStopDelivery() = rejectsIndex(-1)

    @Test
    fun skippedIndexDoesNotGrowEmptyDeck() = rejectsIndex(1)

    @Test
    fun skippedIndexDoesNotGrowPopulatedDeck() = rejectsIndex(3, initialCards = 2)

    @Test
    fun minimumIndexDoesNotStopDelivery() = rejectsIndex(Int.MIN_VALUE)

    // The modest skipped-index cases above are safe positive controls when the
    // guard is removed; running Int.MAX_VALUE against that mutant would OOM.
    @Test
    fun maximumIndexDoesNotGrowDeckOrStopDelivery() = rejectsIndex(Int.MAX_VALUE)

    private fun rejectsIndex(index: Int, initialCards: Int = 0) = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(coroutineContext + CoroutineExceptionHandler { _, failure -> failures.add(failure) })
        val seam = FakeSeam(alice, setOf(alice, bob))
        val scheme = SraScheme()
        val session = DealSession(seam, scheme, scheme.generateKey(), setOf(alice, bob), alice, scope)
        try {
            repeat(initialCards) { card -> seam.deliver(bob, frame(card)) }
            runCurrent()
            assertEquals(initialCards, session.state.value.cards.size, "fixture must populate the deck")
            val before = session.state.value

            seam.deliver(bob, frame(index))
            runCurrent()
            assertAll(
                { assertEquals(before, session.state.value, "invalid index $index must not grow or change the deck") },
                { assertTrue(failures.isEmpty(), "invalid index $index escaped the pump: $failures") },
                { assertTrue(session.incomingJobForTest.isActive, "invalid index $index stopped delivery") },
            )

            seam.deliver(bob, frame(initialCards))
            runCurrent()
            assertAll(
                { assertEquals(initialCards + 1, session.state.value.cards.size, "next valid frame must be delivered") },
                { assertEquals(setOf(bob), session.state.value.cards.last().encryptedBy.elements) },
            )
        } finally {
            session.close()
        }
    }

    private fun frame(index: Int): ByteArray = Cbor.encodeToByteArray(
        IndexedCardOp(
            index,
            CardOp.Encrypt(bob, byteArrayOf(1), EncryptProof(byteArrayOf()), emptySet(), emptySet()),
        ),
    )
}
