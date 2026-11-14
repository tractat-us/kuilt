package us.tractat.kuilt.identity

import dev.whyoleg.cryptography.bigint.BigInt
import dev.whyoleg.cryptography.serialization.asn1.Der
import dev.whyoleg.cryptography.serialization.asn1.modules.DssSignatureValue
import kotlinx.serialization.SerializationException

/** Bytes in each of `r` and `s` in the raw form [DeviceKey.sign] returns. */
private const val SCALAR_BYTES = 32

/**
 * [der], an ASN.1 `ECDSA-Sig-Value` (`SEQUENCE { r INTEGER, s INTEGER }`) as JCA's
 * `SHA256withECDSA` writes it, as the 64-byte raw `r ‖ s` that [DidKey.verify] reads: each integer's
 * magnitude, left-padded with zeros to 32 bytes.
 *
 * The padding is the point. DER writes an integer in as few bytes as it can, plus a `0x00` when its
 * top bit is set, so `r` and `s` each arrive as anything from 1 to 33 bytes; about one signature in
 * 128 has an `r` or `s` shorter than 32 bytes, and a conversion that dropped the padding would fail
 * those, and only those.
 *
 * Encoding work, not curve math: the DER codec is cryptography-kotlin's. `null` when [der] is not an
 * `ECDSA-Sig-Value`, or an integer in it is not positive or does not fit 32 bytes. Lenient about
 * anything a decoder tolerates beyond that, since its input is this device's own Keystore's output,
 * not a peer's.
 */
internal fun derSignatureToRaw(der: ByteArray): ByteArray? {
    // The decoder refuses three ways: a SerializationException for a wrong tag, an
    // IllegalStateException ("Unexpected EOF") for input cut short, an IndexOutOfBoundsException for
    // a length running past the end.
    val value = try {
        Der.decodeFromByteArray(DssSignatureValue.serializer(), der)
        // ALLOW-ise: nothing in the `try` can suspend — this function and the DER decoder are both non-`suspend`
    } catch (_: IllegalStateException) {
        return null
    } catch (_: SerializationException) {
        return null
    } catch (_: IndexOutOfBoundsException) {
        return null
    }
    val r = fixedWidth(value.r) ?: return null
    val s = fixedWidth(value.s) ?: return null
    return r + s
}

private fun fixedWidth(scalar: BigInt): ByteArray? {
    if (scalar.sign <= 0) return null
    val magnitude = scalar.magnitudeToByteArray()
    if (magnitude.size > SCALAR_BYTES) return null
    return ByteArray(SCALAR_BYTES - magnitude.size) + magnitude
}
