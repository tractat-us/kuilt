package us.tractat.kuilt.deal

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Pins [SraScheme]'s input domain to the values that actually hide something.
 *
 * `0`, `1` and `p-1` are fixed by **every** key the scheme generates, so a card encoding to one of
 * them is "layered" by every player and still readable by all of them.
 * [pMinusOneIsAFixedPointOfEveryKeyTheSchemeGenerates] is the premise — it shows the algebra on
 * real generated keys rather than asserting it in a comment — and
 * [encryptAndStripRefuseEveryUniversalFixedPoint] is the exclusion that premise justifies.
 *
 * [theDomainStillAdmitsBothOfItsEndpoints] is the discriminator that keeps the exclusion honest: a
 * check tightened past `p-1` would satisfy the refusal test just as well while quietly shrinking
 * the deck's usable domain, and nothing else here would notice.
 *
 * `p-1` is the *only* fixed point besides `0` and `1`: the modulus is a safe prime, so the subgroup
 * orders are `1, 2, q, 2q` and `p-1` is the unique element of order 2. See #2363.
 */
class SraSchemeDomainTest {

    @Test
    fun pMinusOneIsAFixedPointOfEveryKeyTheSchemeGenerates() {
        val scheme = SraScheme()
        val pMinusOne = primeMinus(1)
        // Driven through sraModPowCanonical rather than encrypt/strip on purpose: the domain check
        // this file is about refuses p-1, so the only way to show WHY it must is to run the same
        // modular exponentiation encrypt/strip run, one layer below the guard.
        //
        // p-1 == -1 (mod p). generateKey forces the exponent e odd, and d*e == 1 (mod p-1) with
        // p-1 even forces d odd too, so both layers compute (-1)^odd == -1 == p-1.
        //
        // Two draws, not more: the claim is algebraic and holds for every odd exponent, and
        // SraScheme.generateKey is 2048-bit work that has to stay inside the wasmJs test budget.
        repeat(2) { draw ->
            val key = scheme.generateKey()
            assertAll(
                {
                    assertContentEquals(
                        pMinusOne,
                        sraModPowCanonical(pMinusOne, key.encryptKey.raw, primeBytes()),
                        "draw $draw: encrypting p-1 must return p-1 — that is what makes it a fixed point",
                    )
                },
                {
                    assertContentEquals(
                        pMinusOne,
                        sraModPowCanonical(pMinusOne, key.stripKey.raw, primeBytes()),
                        "draw $draw: stripping p-1 must return p-1 — every player's layer is a no-op on it",
                    )
                },
            )
        }
    }

    @Test
    fun encryptAndStripRefuseEveryUniversalFixedPoint() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        val fixedPoints = listOf(
            "0" to byteArrayOf(0),
            "1" to byteArrayOf(1),
            "p-1" to primeMinus(1),
        )
        assertAll(
            *fixedPoints.flatMap { (name, value) ->
                listOf<() -> Unit>(
                    {
                        assertFailsWith<IllegalArgumentException>(
                            "encrypt accepted $name, a value every key this scheme generates leaves unchanged",
                        ) { scheme.encrypt(value, key.encryptKey) }
                    },
                    {
                        assertFailsWith<IllegalArgumentException>(
                            "strip accepted $name, a value every key this scheme generates leaves unchanged",
                        ) { scheme.strip(value, key.stripKey) }
                    },
                )
            }.toTypedArray(),
        )
    }

    @Test
    fun theDomainStillAdmitsBothOfItsEndpoints() {
        val scheme = SraScheme()
        val key = scheme.generateKey()
        // Round-tripped rather than merely "did not throw": that proves admission AND that the
        // endpoints are ordinary group elements, so a future tightening past p-1 reds here.
        assertAll(
            *listOf("2" to byteArrayOf(2), "p-2" to primeMinus(2)).map { (name, m) ->
                {
                    assertContentEquals(
                        m,
                        scheme.strip(scheme.encrypt(m, key.encryptKey).first, key.stripKey).first,
                        "$name is an endpoint of the domain [2, p-2] and must still round-trip",
                    )
                }
            }.toTypedArray(),
        )
    }

    /**
     * `p - n` for a small [n], derived from [primeBytes] rather than written out a second time.
     *
     * The modulus' low byte is `0xFF`, so subtracting a small `n` touches that byte alone — checked
     * below, so the day the group parameters change this fails loudly instead of returning a
     * silently wrong constant.
     */
    private fun primeMinus(n: Int): ByteArray {
        val bytes = primeBytes()
        check(bytes.last() == 0xFF.toByte()) { "modulus no longer ends in 0xFF — primeMinus() would borrow" }
        return bytes.also { it[it.lastIndex] = (0xFF - n).toByte() }
    }

    /**
     * RFC 7919 `ffdhe2048`, the same 2048-bit safe prime [SraScheme] uses, written out here rather
     * than read from the scheme — its `PRIME` is private, and a test that derived the modulus from
     * the code under test could not tell a changed modulus from a correct one.
     */
    private fun primeBytes(): ByteArray = hex(
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
    )

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(radix = 16).toByte() }
    }
}
