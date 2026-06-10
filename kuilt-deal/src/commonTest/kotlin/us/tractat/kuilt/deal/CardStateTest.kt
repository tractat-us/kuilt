package us.tractat.kuilt.deal

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardStateTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")
    private val allPlayers = setOf(alice, bob, carol)
    private val quorumAlice = setOf(alice)   // poker: only alice sees her card

    private fun emptyCard(quorum: Set<PlayerId> = quorumAlice) = CardState(
        ciphertext = byteArrayOf(42),
        encryptedBy = GSet.empty(),
        strippedBy = GSet.empty(),
        visibilityQuorum = quorum,
        allPlayers = allPlayers,
    )

    @Test
    fun phaseIsUnencryptedWhenNobodyHasEncrypted() {
        assertEquals(CardPhase.UNENCRYPTED, emptyCard().phase())
    }

    @Test
    fun phaseIsShufflingWhenSomeButNotAllHaveEncrypted() {
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice),
        )
        assertEquals(CardPhase.SHUFFLING, state.phase())
    }

    @Test
    fun phaseIsFullyEncryptedWhenAllPlayersHaveEncrypted() {
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice, bob, carol),
        )
        assertEquals(CardPhase.FULLY_ENCRYPTED, state.phase())
    }

    @Test
    fun phaseIsRevealingWhenSomeNonQuorumPlayersHaveStripped() {
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice, bob, carol),
            strippedBy = GSet.of(bob),
        )
        assertEquals(CardPhase.REVEALING, state.phase())
    }

    @Test
    fun phaseIsRevealedWhenAllNonQuorumPlayersHaveStripped() {
        // quorum = {alice}, so bob and carol must strip
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice, bob, carol),
            strippedBy = GSet.of(bob, carol),
        )
        assertEquals(CardPhase.REVEALED, state.phase())
    }

    @Test
    fun mergeIsSetUnionOnBothGSets() {
        val left = emptyCard().copy(
            encryptedBy = GSet.of(alice),
            strippedBy = GSet.empty(),
        )
        val right = emptyCard().copy(
            encryptedBy = GSet.of(bob),
            strippedBy = GSet.of(carol),
        )
        val merged = left.merge(right)
        assertAll(
            { assertEquals(setOf(alice, bob), merged.encryptedBy.elements) },
            { assertEquals(setOf(carol), merged.strippedBy.elements) },
        )
    }

    @Test
    fun hanabiphaseRevealedMeansAllExceptHolderHaveStripped() {
        // Hanabi: quorum = {alice, bob, carol} except carol (carol holds this card)
        val hanabi = emptyCard(quorum = setOf(alice, bob))  // carol NOT in quorum
        val state = hanabi.copy(
            encryptedBy = GSet.of(alice, bob, carol),
            strippedBy = GSet.of(carol),  // only carol needs to strip
        )
        assertEquals(CardPhase.REVEALED, state.phase())
    }

    @Test
    fun mergeIsIdempotent() {
        val card = emptyCard().copy(encryptedBy = GSet.of(alice), strippedBy = GSet.of(bob))
        assertEquals(card, card.merge(card))
    }

    @Test
    fun mergeIsCommutative() {
        // Same ciphertext on both sides so full CardState equality holds; the
        // tie-break path (equal encryptor count, different members) is exercised.
        val left = emptyCard().copy(encryptedBy = GSet.of(alice))
        val right = emptyCard().copy(encryptedBy = GSet.of(bob))
        assertEquals(left.merge(right), right.merge(left))
    }

    @Test
    fun mergeIsAssociative() {
        val a = emptyCard().copy(encryptedBy = GSet.of(alice))
        val b = emptyCard().copy(encryptedBy = GSet.of(bob))
        val c = emptyCard().copy(encryptedBy = GSet.of(carol))
        assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
    }

    @Test
    fun encryptOpIsRejectedIfPlayerAlreadyEncrypted() {
        val state = emptyCard().copy(encryptedBy = GSet.of(alice))
        val op = CardOp.Encrypt(alice, byteArrayOf(1), EncryptProof(ByteArray(0)))
        assertFalse(state.canApply(op))
    }

    @Test
    fun encryptOpIsAcceptedIfPlayerHasNotYetEncrypted() {
        val state = emptyCard()
        val op = CardOp.Encrypt(alice, byteArrayOf(1), EncryptProof(ByteArray(0)))
        assertTrue(state.canApply(op))
    }

    @Test
    fun stripOpIsRejectedIfPlayerIsInQuorum() {
        // alice is in the quorum — she must NOT strip
        val state = emptyCard(quorum = quorumAlice).copy(
            encryptedBy = GSet.of(alice, bob, carol),
        )
        val op = CardOp.Strip(alice, byteArrayOf(1), StripProof(ByteArray(0)))
        assertFalse(state.canApply(op))
    }

    @Test
    fun stripOpIsRejectedIfPlayerHasNotEncrypted() {
        val state = emptyCard().copy(encryptedBy = GSet.empty())
        val op = CardOp.Strip(bob, byteArrayOf(1), StripProof(ByteArray(0)))
        assertFalse(state.canApply(op))
    }

    @Test
    fun stripOpIsRejectedIfPlayerAlreadyStripped() {
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice, bob, carol),
            strippedBy = GSet.of(bob),
        )
        val op = CardOp.Strip(bob, byteArrayOf(1), StripProof(ByteArray(0)))
        assertFalse(state.canApply(op))
    }

    @Test
    fun stripOpIsAcceptedForNonQuorumPlayerWhoHasEncrypted() {
        val state = emptyCard().copy(
            encryptedBy = GSet.of(alice, bob, carol),
        )
        val op = CardOp.Strip(bob, byteArrayOf(1), StripProof(ByteArray(0)))
        assertTrue(state.canApply(op))
    }

    @Test
    fun applyEncryptAddsPlayerAndSwapsCiphertext() {
        val next = emptyCard().applyOp(CardOp.Encrypt(alice, byteArrayOf(7), EncryptProof(ByteArray(0))))
        assertAll(
            { assertTrue(next != null) },
            { assertEquals(setOf(alice), next!!.encryptedBy.elements) },
            { assertEquals(listOf<Byte>(7), next!!.ciphertext.toList()) },
        )
    }

    @Test
    fun applyStripAddsPlayerAndSwapsCiphertext() {
        val card = emptyCard().copy(encryptedBy = GSet.of(alice, bob, carol))
        val next = card.applyOp(CardOp.Strip(bob, byteArrayOf(9), StripProof(ByteArray(0))))
        assertAll(
            { assertTrue(next != null) },
            { assertEquals(setOf(bob), next!!.strippedBy.elements) },
            { assertEquals(listOf<Byte>(9), next!!.ciphertext.toList()) },
        )
    }

    @Test
    fun applyInvalidOpReturnsNull() {
        val card = emptyCard().copy(encryptedBy = GSet.of(alice))
        // alice already encrypted — re-encrypt is invalid
        assertEquals(null, card.applyOp(CardOp.Encrypt(alice, byteArrayOf(1), EncryptProof(ByteArray(0)))))
    }

    @Test
    fun depositKeyRejectedBeforeFullyEncrypted() {
        val card = emptyCard().copy(encryptedBy = GSet.of(alice))  // SHUFFLING (not all encrypted)
        assertFalse(card.canApply(CardOp.DepositKey(alice, EncryptedKey(ByteArray(0)))))
    }

    @Test
    fun depositKeyAcceptedWhenFullyEncrypted() {
        val card = emptyCard().copy(encryptedBy = GSet.of(alice, bob, carol))  // FULLY_ENCRYPTED
        assertTrue(card.canApply(CardOp.DepositKey(alice, EncryptedKey(ByteArray(0)))))
    }

    @Test
    fun encodeDecodePlaintextRoundTripsLeadingZeros() {
        val original = byteArrayOf(0, 0, 5, 7)
        assertEquals(original.toList(), decodePlaintext(encodePlaintext(original)).toList())
    }

    @Test
    fun sraRoundTripsLeadingZeroPlaintextViaCodec() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        val original = byteArrayOf(0, 0, 42)  // leading zeros — would corrupt without the codec
        val encoded = encodePlaintext(original)
        val (encrypted, _) = scheme.encrypt(encoded, key.encryptKey)
        val (recoveredEncoded, _) = scheme.strip(encrypted, key.stripKey)
        assertEquals(original.toList(), decodePlaintext(recoveredEncoded).toList())
    }

    @Test
    fun sraEncryptRejectsOutOfDomainValues() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        assertFailsWith<IllegalArgumentException> { scheme.encrypt(byteArrayOf(0), key.encryptKey) }  // m=0
        assertFailsWith<IllegalArgumentException> { scheme.encrypt(byteArrayOf(1), key.encryptKey) }  // m=1
    }

    @Test
    fun encodePlaintextRejectsEmpty() {
        assertFailsWith<IllegalArgumentException> { encodePlaintext(ByteArray(0)) }
    }

}

private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }
