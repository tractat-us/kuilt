package us.tractat.kuilt.deal

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins op-application convergence under cross-sender reorder (issue #1273).
 *
 * `Seam.incoming` guarantees only per-sender FIFO, so ops from different senders
 * can arrive in different orders at different peers. Op application must converge
 * to the same, layer-complete ciphertext regardless of that interleaving.
 */
class CardOpConvergenceTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")

    /** Apply [op], treating an inapplicable (already-subsumed) op as a no-op. */
    private fun CardState.applied(op: CardOp): CardState = applyOp(op) ?: this

    @Test
    fun crossSenderEncryptReorderConvergesToBothLayers() {
        val scheme = SraScheme()
        val aliceKey = scheme.generateKey()
        val bobKey = scheme.generateKey()
        val plaintext = "ACE_OF_SPADES".encodeToByteArray()
        val c0 = encodePlaintext(plaintext)
        // Honest sequential chain: alice encrypts c0 -> c1, bob encrypts on top c1 -> c2.
        val c1 = scheme.encrypt(c0, aliceKey.encryptKey).first
        val c2 = scheme.encrypt(c1, bobKey.encryptKey).first
        val op1 = CardOp.Encrypt(
            player = alice,
            newCiphertext = c1,
            proof = EncryptProof(ByteArray(0)),
        )
        val op2 = CardOp.Encrypt(
            player = bob,
            newCiphertext = c2,
            proof = EncryptProof(ByteArray(0)),
        )
        // A third peer's virgin placeholder card (remote ops can arrive before local shuffle).
        val virgin = CardState(
            ciphertext = ByteArray(0),
            encryptedBy = GSet.empty(),
            strippedBy = GSet.empty(),
            visibilityQuorum = emptySet(),
            allPlayers = setOf(alice, bob),
        )

        val inOrder = virgin.applied(op1).applied(op2)
        val reordered = virgin.applied(op2).applied(op1)

        assertAll(
            { assertEquals(inOrder, reordered, "op application must be order-independent") },
            { assertEquals(c2.toList(), reordered.ciphertext.toList(), "converged ciphertext must carry BOTH layers") },
            { assertEquals(setOf(alice, bob), reordered.encryptedBy.elements) },
            {
                // The converged ciphertext must physically round-trip: strip both layers, decode.
                val minusAlice = scheme.strip(reordered.ciphertext, aliceKey.stripKey).first
                val minusBoth = scheme.strip(minusAlice, bobKey.stripKey).first
                assertEquals(plaintext.toList(), decodePlaintext(minusBoth).toList())
            },
        )
    }

    @Test
    fun mergePrefersStateFurtherAlongTheStripChain() {
        // After full encryption both sides have equal |encryptedBy|; the side that has
        // additionally applied a strip is strictly further along the op chain and its
        // ciphertext must win the merge — regardless of ciphertext byte order.
        val fullyEncrypted = CardState(
            ciphertext = byteArrayOf(1), // byte-smaller than the stripped ciphertext on purpose
            encryptedBy = GSet.of(alice, bob, carol),
            strippedBy = GSet.empty(),
            visibilityQuorum = setOf(alice),
            allPlayers = setOf(alice, bob, carol),
        )
        val afterBobStrip = fullyEncrypted.copy(
            ciphertext = byteArrayOf(2),
            strippedBy = GSet.of(bob),
        )
        assertAll(
            { assertEquals(listOf<Byte>(2), fullyEncrypted.merge(afterBobStrip).ciphertext.toList()) },
            { assertEquals(listOf<Byte>(2), afterBobStrip.merge(fullyEncrypted).ciphertext.toList()) },
        )
    }
}
