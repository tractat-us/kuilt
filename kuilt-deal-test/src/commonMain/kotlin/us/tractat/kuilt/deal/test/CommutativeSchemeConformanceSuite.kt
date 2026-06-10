package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Conformance TCK for [CommutativeScheme]. Validate a new scheme by subclassing
 * this suite and overriding [newScheme]:
 *
 * ```kotlin
 * class MySchemeConformanceTest : CommutativeSchemeConformanceSuite() {
 *     override fun newScheme() = MyScheme()
 * }
 * ```
 *
 * The suite verifies the commutative-encryption laws the card-deal protocol
 * relies on (round-trip, commutativity, multi-layer strip-order independence,
 * key distinctness). It tests the raw [CommutativeScheme] contract on in-domain
 * byte messages — plaintext domain encoding (e.g. SRA's marker codec) is a
 * scheme-layer concern and is out of scope here; override [validPlaintexts] if
 * your scheme's valid domain differs from short ASCII.
 */
public abstract class CommutativeSchemeConformanceSuite {

    /** A fresh scheme instance under test. */
    public abstract fun newScheme(): CommutativeScheme

    /** Sample messages guaranteed to lie in the scheme's valid input domain. */
    public open fun validPlaintexts(): List<ByteArray> = listOf(
        "card:ACE_OF_SPADES".encodeToByteArray(),
        "card:KING_OF_HEARTS".encodeToByteArray(),
        "7".encodeToByteArray(),
    )

    @Test
    public fun encryptThenStripRecoversPlaintext() {
        val scheme = newScheme()
        val key = scheme.generateKey()
        for (m in validPlaintexts()) {
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertEquals(m.toList(), recovered.toList(), "round-trip failed for ${m.toList()}")
        }
    }

    @Test
    public fun encryptionIsCommutative() {
        val scheme = newScheme()
        val a = scheme.generateKey()
        val b = scheme.generateKey()
        for (m in validPlaintexts()) {
            val ab = scheme.encrypt(scheme.encrypt(m, a.encryptKey).first, b.encryptKey).first
            val ba = scheme.encrypt(scheme.encrypt(m, b.encryptKey).first, a.encryptKey).first
            assertEquals(ab.toList(), ba.toList(), "commutativity failed for ${m.toList()}")
        }
    }

    @Test
    public fun multiLayerDealRecoversPlaintextRegardlessOfStripOrder() {
        val scheme = newScheme()
        val keys = List(3) { scheme.generateKey() }
        // One representative plaintext: three independent keys exercising a deranged
        // strip order is what proves order-independence — extra plaintexts add cost
        // without strengthening the law (and overrun the wasmJs 2s test budget, since
        // a heavyweight scheme like SRA-2048 does real big-integer work per layer).
        val m = validPlaintexts().first()
        // Layer all three encryptions (order k0, k1, k2).
        var cipher = m
        for (k in keys) cipher = scheme.encrypt(cipher, k.encryptKey).first
        // Strip in a fully deranged order (k2, k0, k1) — no layer in its encryption
        // position — so commutativity is genuinely exercised.
        for (k in listOf(keys[2], keys[0], keys[1])) cipher = scheme.strip(cipher, k.stripKey).first
        assertEquals(m.toList(), cipher.toList(), "multi-layer recovery failed for ${m.toList()}")
    }

    @Test
    public fun distinctKeysProduceDistinctCiphertexts() {
        val scheme = newScheme()
        val a = scheme.generateKey()
        val b = scheme.generateKey()
        val m = validPlaintexts().first()
        assertNotEquals(
            scheme.encrypt(m, a.encryptKey).first.toList(),
            scheme.encrypt(m, b.encryptKey).first.toList(),
            "distinct keys produced identical ciphertext",
        )
    }

    @Test
    public fun generatedKeyPairsAreUsable() {
        val scheme = newScheme()
        // Two independently generated pairs — enough to show generateKey() yields
        // usable, distinct pairs. (Kept low so a heavyweight scheme's key generation
        // stays within the wasmJs 2s test budget.)
        repeat(2) {
            val key = scheme.generateKey()
            val m = validPlaintexts().first()
            val (cipher, _) = scheme.encrypt(m, key.encryptKey)
            val (recovered, _) = scheme.strip(cipher, key.stripKey)
            assertTrue(m.contentEquals(recovered), "generated key pair failed to round-trip")
        }
    }

    /**
     * Honest-path verification: an [CommutativeScheme.encrypt]/[CommutativeScheme.strip]
     * transition that the scheme itself produced must verify. Current schemes stub the
     * verify methods to `true`, so this pins the baseline a future (e.g. ZK-proof)
     * implementation must also satisfy — honest transitions are always accepted.
     */
    @Test
    public fun verifyAcceptsHonestTransitions() {
        val scheme = newScheme()
        val key = scheme.generateKey()
        val m = validPlaintexts().first()
        val (cipher, encryptProof) = scheme.encrypt(m, key.encryptKey)
        assertTrue(
            scheme.verifyEncrypt(m, cipher, encryptProof, key.encryptKey),
            "verifyEncrypt rejected an honestly-produced encryption",
        )
        val (recovered, stripProof) = scheme.strip(cipher, key.stripKey)
        assertTrue(
            scheme.verifyStrip(cipher, recovered, stripProof, key.encryptKey),
            "verifyStrip rejected an honestly-produced strip",
        )
    }
}
