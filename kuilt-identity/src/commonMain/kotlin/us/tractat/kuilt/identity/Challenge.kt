package us.tractat.kuilt.identity

/**
 * A host's answer to a device asking to join: "prove you hold your key, and here is proof that I
 * hold mine." The host signs it, so the joiner learns the host's key from the host itself, and the
 * fresh [hostNonce] it carries is what the joiner's [AdmissionProof] must sign over.
 *
 * It names the room, both ends by their peer ids, and both nonces: the [joinerNonce] the joiner sent
 * in its Hello, so the joiner knows this challenge answers its own Hello and is not a replay, and the
 * [hostNonce] the host chose, so the proof answering it is good only once.
 *
 * Its [wire] form is the signed bytes themselves — the layout below, then the 64-byte signature —
 * and two challenges are equal when their wire bytes are. The arrays it hands out are copies.
 *
 * ```
 * Layout tag "kuilt/challenge", version 1, six fields in this order:
 *   host key (33-byte compressed point) ‖ room id (UTF-8) ‖ host peer id (UTF-8) ‖
 *   joiner peer id (UTF-8) ‖ joiner nonce (16 bytes) ‖ host nonce (16 bytes)
 * ```
 *
 * [decode] reads the layout only; it does not check the signature. [isSignedByHost] does that, under
 * the [hostKey] the challenge itself names — which proves the challenge came from whoever holds that
 * key, not that the key belongs to anyone in particular.
 */
@IdentityInternalApi
public class Challenge internal constructor(
    /** The key the host signed this with, and claims as its own. */
    public val hostKey: DidKey,
    /** The room the joiner is being admitted to. */
    public val roomId: String,
    /** The host's peer id on the fabric. */
    public val hostPeerId: String,
    /** The joiner's peer id: the fabric sender of the Hello this answers. */
    public val joinerPeerId: String,
    joinerNonce: ByteArray,
    hostNonce: ByteArray,
    wire: ByteArray,
) {
    private val joinerNonceBytes = joinerNonce.copyOf()
    private val hostNonceBytes = hostNonce.copyOf()
    private val wireBytes = wire.copyOf()

    init {
        require(joinerNonceBytes.size == NONCE_SIZE) { "the joiner nonce is ${joinerNonceBytes.size} bytes, not $NONCE_SIZE" }
        require(hostNonceBytes.size == NONCE_SIZE) { "the host nonce is ${hostNonceBytes.size} bytes, not $NONCE_SIZE" }
    }

    /** The nonce the joiner sent in its Hello, echoed back: a copy. */
    public val joinerNonce: ByteArray get() = joinerNonceBytes.copyOf()

    /** The nonce the host chose for this challenge, which the joiner's proof signs: a copy. */
    public val hostNonce: ByteArray get() = hostNonceBytes.copyOf()

    /** The layout followed by the host's signature: what is sent. A copy. */
    public val wire: ByteArray get() = wireBytes.copyOf()

    /**
     * Whether the signature on [wire] is [hostKey]'s over the layout. `false`, never a throw, for a
     * bad signature or a [hostKey] whose point is not on the curve.
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun isSignedByHost(): Boolean = StatementCodec.isSignedBy(wireBytes, hostKey)

    override fun equals(other: Any?): Boolean = other is Challenge && wireBytes.contentEquals(other.wireBytes)

    override fun hashCode(): Int = wireBytes.contentHashCode()

    override fun toString(): String =
        "Challenge(hostKey=${hostKey.value}, roomId=$roomId, hostPeerId=$hostPeerId, joinerPeerId=$joinerPeerId)"

    public companion object {
        /** The width of both nonces, in bytes. */
        public const val NONCE_SIZE: Int = 16

        private const val TAG = "kuilt/challenge"
        private const val VERSION = 1
        private const val FIELD_COUNT = 6
        private const val HOST_NONCE_FIELD = 5

        /**
         * The challenge [host] signs for the joiner [joinerPeerId], answering the [joinerNonce] it
         * sent with a fresh [hostNonce]. Its [hostKey] is [host]'s [DeviceKey.id].
         *
         * @throws IllegalArgumentException when a nonce is not [NONCE_SIZE] bytes, or a string's
         *   UTF-8 is longer than a layout field can carry (65 535 bytes), or a string holds an
         *   unpaired surrogate, which has no UTF-8 and would otherwise be signed as a replacement.
         * @throws IllegalStateException when [host] cannot sign here.
         */
        public suspend fun sign(
            host: DeviceKey,
            roomId: String,
            hostPeerId: String,
            joinerPeerId: String,
            joinerNonce: ByteArray,
            hostNonce: ByteArray,
        ): Challenge {
            require(joinerNonce.size == NONCE_SIZE) { "the joiner nonce is ${joinerNonce.size} bytes, not $NONCE_SIZE" }
            require(hostNonce.size == NONCE_SIZE) { "the host nonce is ${hostNonce.size} bytes, not $NONCE_SIZE" }
            val layout = Layout.encode(
                TAG,
                VERSION,
                listOf(
                    StatementCodec.key(host.id),
                    StatementCodec.text(roomId),
                    StatementCodec.text(hostPeerId),
                    StatementCodec.text(joinerPeerId),
                    joinerNonce,
                    hostNonce,
                ),
            )
            // Read back through decode, so a signed challenge and a received one are built one way.
            return checkNotNull(decode(StatementCodec.signed(host, layout))) { "kuilt-identity: a challenge just signed does not decode" }
        }

        /**
         * The challenge [wire] holds, or `null` when [wire] is not a version-1 `kuilt/challenge`
         * layout followed by 64 bytes. Checks the layout and every field's form — the key's syntax,
         * well-formed UTF-8, both nonces [NONCE_SIZE] bytes — but not the signature: that is
         * [isSignedByHost]. Never throws.
         */
        public fun decode(wire: ByteArray): Challenge? {
            val (layout, _) = Layout.split(wire) ?: return null
            val fields = Layout.decode(layout, TAG, VERSION, FIELD_COUNT) ?: return null
            val (hostKey, roomId, hostPeerId, joinerPeerId, joinerNonce) = fields
            val hostNonce = fields[HOST_NONCE_FIELD]
            if (joinerNonce.size != NONCE_SIZE || hostNonce.size != NONCE_SIZE) return null
            return Challenge(
                hostKey = StatementCodec.keyOrNull(hostKey) ?: return null,
                roomId = StatementCodec.textOrNull(roomId) ?: return null,
                hostPeerId = StatementCodec.textOrNull(hostPeerId) ?: return null,
                joinerPeerId = StatementCodec.textOrNull(joinerPeerId) ?: return null,
                joinerNonce = joinerNonce,
                hostNonce = hostNonce,
                wire = wire,
            )
        }
    }
}
