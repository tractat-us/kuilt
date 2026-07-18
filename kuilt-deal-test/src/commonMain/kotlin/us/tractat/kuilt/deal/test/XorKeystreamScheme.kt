package us.tractat.kuilt.deal.test

import us.tractat.kuilt.deal.CommutativeScheme
import us.tractat.kuilt.deal.EncryptProof
import us.tractat.kuilt.deal.SchemeKey
import us.tractat.kuilt.deal.SchemeKeyPair
import us.tractat.kuilt.deal.StripProof
import kotlin.random.Random

/**
 * A fast, dependency-free [CommutativeScheme] **test double** for driving the deal
 * session / CRDT reveal logic WITHOUT the cost of real 2048-bit SRA modular
 * exponentiation.
 *
 * Both [encrypt] and [strip] XOR the message with a per-key keystream. XOR is
 * commutative (`m ^ ks_a ^ ks_b == m ^ ks_b ^ ks_a`) and self-inverse
 * (`m ^ ks ^ ks == m`), so this satisfies the commutative-encryption laws the
 * deal protocol relies on — round-trip recovery, layer commutativity, and
 * strip-order independence — while running in microseconds on every platform,
 * including Apple/Kotlin-Native and wasmJs where `SraScheme` falls back to the
 * ~40× slower pure-Kotlin big-integer path. It is verified against
 * [CommutativeSchemeConformanceSuite] exactly like `SraScheme`.
 *
 * The keystream depends only on the key and the byte index — never on message
 * content — so layering commutes and strips in any order. Output length equals
 * input length, so the marker + card domain of `encodePlaintext` round-trips.
 *
 * **This is a TEST DOUBLE — it provides NO cryptographic secrecy** (the keystream
 * is a fast non-cryptographic PRNG, not a CSPRNG). Real-crypto coverage stays in
 * `SraSchemeConformanceTest`; use `SraScheme` for anything security-bearing.
 *
 * A generated pair reuses the same secret for [SchemeKeyPair.encryptKey] and
 * [SchemeKeyPair.stripKey] because XOR is its own inverse; distinct pairs draw
 * independent random secrets, so distinct keys produce distinct ciphertexts.
 *
 * Pure and stateless apart from the injected [random] (used solely by
 * [generateKey]), so it is correct under a multi-threaded dispatcher.
 */
public class XorKeystreamScheme(private val random: Random = Random.Default) : CommutativeScheme {

    override fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof> =
        xorKeystream(plaintext, key.raw) to EncryptProof(ByteArray(0))

    override fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof> =
        xorKeystream(ciphertext, key.raw) to StripProof(ByteArray(0))

    override fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: SchemeKey): Boolean = true

    override fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: SchemeKey): Boolean = true

    override fun generateKey(): SchemeKeyPair {
        val secret = random.nextBytes(SECRET_BYTES)
        val key = SchemeKey(secret)
        // encrypt and strip are the same self-inverse XOR, so both halves share the secret.
        return SchemeKeyPair(encryptKey = key, stripKey = key)
    }

    // XOR each message byte with a keystream byte derived deterministically from the key.
    private fun xorKeystream(message: ByteArray, keyBytes: ByteArray): ByteArray {
        var state = seedFrom(keyBytes)
        return ByteArray(message.size) { i ->
            state += SPLITMIX_INCREMENT
            (message[i].toInt() xor nextByte(state)).toByte()
        }
    }

    // FNV-1a fold of the key bytes into a 64-bit seed.
    private fun seedFrom(keyBytes: ByteArray): ULong {
        var seed = SEED_BASIS
        for (b in keyBytes) {
            seed = (seed xor (b.toULong() and BYTE_MASK)) * FNV_PRIME
        }
        return seed
    }

    // One splitmix64 output, reduced to a byte.
    private fun nextByte(state: ULong): Int {
        var z = state
        z = (z xor (z shr SHIFT_A)) * MIX_A
        z = (z xor (z shr SHIFT_B)) * MIX_B
        z = z xor (z shr SHIFT_C)
        return (z and BYTE_MASK).toInt()
    }

    private companion object {
        const val SECRET_BYTES = 16
        const val BYTE_MASK = 0xFFuL

        // splitmix64 / FNV-1a mixing constants (non-cryptographic; this is a test double).
        const val SEED_BASIS = 0x9E3779B97F4A7C15uL
        const val FNV_PRIME = 0x100000001B3uL
        const val SPLITMIX_INCREMENT = 0x9E3779B97F4A7C15uL
        const val MIX_A = 0xBF58476D1CE4E5B9uL
        const val MIX_B = 0x94D049BB133111EBuL
        const val SHIFT_A = 30
        const val SHIFT_B = 27
        const val SHIFT_C = 31
    }
}
