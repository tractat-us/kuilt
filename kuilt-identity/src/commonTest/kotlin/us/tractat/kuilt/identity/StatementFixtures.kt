package us.tractat.kuilt.identity

/**
 * A [DeviceKey] with a fixed name and a fixed signature, so a statement's layout can be pinned to
 * exact bytes. It records what it was asked to sign, which is how a test shows the signer was handed
 * the layout itself and nothing re-encoded.
 */
internal class FixedDeviceKey(override val id: DidKey, private val signature: ByteArray) : DeviceKey {
    val signed = mutableListOf<ByteArray>()

    override suspend fun sign(message: ByteArray): ByteArray {
        signed += message.copyOf()
        return signature.copyOf()
    }
}

/** A key that names itself [claimed] but signs with [actual]: a statement it signs is not [claimed]'s. */
internal class Impostor(private val claimed: DidKey, private val actual: DeviceKey) : DeviceKey {
    override val id: DidKey get() = claimed

    override suspend fun sign(message: ByteArray): ByteArray = actual.sign(message)
}

/** SEC 2's P-256 generator `G`, compressed: `y` is odd, so the lead byte is `0x03`. */
internal const val GENERATOR_COMPRESSED_HEX: String =
    "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"

internal val GENERATOR_KEY: DidKey = DidKey.ofCompressedPoint(GENERATOR_COMPRESSED_HEX.hexToByteArray())

/** A 64-byte signature no key made, easy to spot in a hex dump. */
internal val PLACEHOLDER_SIGNATURE: ByteArray = ByteArray(Layout.SIGNATURE_SIZE) { 0x5a }

/** `count` bytes counting up from [from]: a nonce whose every byte is visible in the vector. */
internal fun countingBytes(from: Int, count: Int): ByteArray = ByteArray(count) { (from + it).toByte() }

/** A copy with the low bit of byte [index] flipped. */
internal fun ByteArray.withLowBitFlipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

/** [fields] with field [index] replaced by [replacement]. */
internal fun List<ByteArray>.withField(index: Int, replacement: ByteArray): List<ByteArray> =
    mapIndexed { i, field -> if (i == index) replacement else field }
