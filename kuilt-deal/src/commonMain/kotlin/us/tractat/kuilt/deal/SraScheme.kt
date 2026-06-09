package us.tractat.kuilt.deal

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import kotlin.random.Random

/**
 * SRA (Shamir–Rivest–Adleman) commutative encryption scheme.
 *
 * Encryption: m^e mod p  (m = BigInteger of plaintext bytes)
 * Stripping:  c^d mod p  (d = modular inverse of e, mod p-1)
 *
 * Commutativity: E_A(E_B(m)) = m^(e_A·e_B) mod p = E_B(E_A(m))
 *
 * Proof is a stub (ByteArray(0)). The GSet membership check is the
 * primary double-encode defence. Full ZK proofs are a follow-up.
 */
public class SraScheme : CommutativeScheme {

    override fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof> {
        val result = modPow(plaintext, key.raw)
        return result to EncryptProof(ByteArray(0))
    }

    override fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof> {
        val result = modPow(ciphertext, key.raw)
        return result to StripProof(ByteArray(0))
    }

    override fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: SchemeKey): Boolean = true

    override fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: SchemeKey): Boolean = true

    override fun generateKey(): SchemeKeyPair {
        val pMinus1 = PRIME - BigInteger.ONE
        var e: BigInteger
        do {
            // Pick a random 512-bit odd number as the exponent
            val bytes = Random.nextBytes(64)  // 512 bits
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or 1).toByte()  // force odd
            e = BigInteger.fromByteArray(bytes, Sign.POSITIVE)
        } while (e.gcd(pMinus1) != BigInteger.ONE)

        val d = e.modInverse(pMinus1)
        return SchemeKeyPair(
            encryptKey = SchemeKey(e.toByteArray()),
            stripKey = SchemeKey(d.toByteArray()),
        )
    }

    private fun modPow(base: ByteArray, exponent: ByteArray): ByteArray {
        val m = BigInteger.fromByteArray(base, Sign.POSITIVE)
        val e = BigInteger.fromByteArray(exponent, Sign.POSITIVE)
        return m.toModularBigInteger(PRIME).pow(e).toBigInteger().toByteArray()
    }

    public companion object {
        // RFC 7919 ffdhe2048 — a 2048-bit safe prime where (p-1)/2 is also prime.
        // Source: decoded from OpenSSL `genpkey -genparam -algorithm DH -pkeyopt group:ffdhe2048`.
        private val PRIME: BigInteger = BigInteger.parseString(
            "FFFFFFFFFFFFFFFFADF85458A2BB4A9AAFDC5620273D3CF1" +
            "D8B9C583CE2D3695A9E13641146433FBCC939DCE249B3EF9" +
            "7D2FE363630C75D8F681B202AEC4617AD3DF1ED5D5FD6561" +
            "2433F51F5F066ED0856365553DED1AF3B557135E7F57C935" +
            "984F0C70E0E68B77E2A689DAF3EFE8721DF158A136ADE735" +
            "30ACCA4F483A797ABC0AB182B324FB61D108A94BB2C8E3FB" +
            "B96ADAB760D7F4681D4F42A3DE394DF4AE56EDE76372BB19" +
            "0B07A7C8EE0A6D709E02FCE1CDF7E2ECC03404CD28342F61" +
            "9172FE9CE98583FF8E4F1232EEF28183C3FE3B1B4C6FAD73" +
            "3BB5FCBC2EC22005C58EF1837D1683B2C6F34A26C1B2EFFA" +
            "886B423861285C97FFFFFFFFFFFFFFFF",
            16,
        )
    }
}
