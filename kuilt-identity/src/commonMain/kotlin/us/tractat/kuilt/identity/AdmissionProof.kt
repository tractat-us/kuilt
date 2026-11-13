package us.tractat.kuilt.identity

/**
 * A joiner's answer to a [Challenge]: "I hold this key, and I am asking to join this room, from this
 * host, right now." The joiner signs it with its own [DeviceKey], so a host that checks it with
 * [isSignedBy] knows the device asking is the device that holds that key.
 *
 * It binds everything a replay could vary: the room, the host's key and peer id, the joiner's own
 * peer id, the app's [deviceId], and the [hostNonce] from the challenge it answers. A proof captured
 * from one admission is therefore worth nothing in another room, to another host, or under another
 * nonce.
 *
 * Its [wire] form is the signed bytes themselves — the layout below, then the 64-byte signature —
 * and two proofs are equal when their wire bytes are. The arrays it hands out are copies.
 *
 * ```
 * Layout tag "kuilt/admit-proof", version 1, seven fields in this order:
 *   room id (UTF-8) ‖ host key (33-byte compressed point) ‖ host peer id (UTF-8) ‖
 *   joiner peer id (UTF-8) ‖ device id (UTF-8, empty when absent) ‖ host nonce (16 bytes) ‖
 *   channel binding (reserved: always empty)
 * ```
 *
 * The channel-binding field is reserved for a value tying the proof to the fabric connection it
 * travels on, such as a TLS exporter. Nothing fills it yet, so [sign] writes it empty and [decode]
 * refuses a proof where it is not.
 *
 * The proof does not carry the joiner's key: the host learns that from the joiner's Hello and checks
 * the proof under it with [isSignedBy].
 */
@IdentityInternalApi
public class AdmissionProof internal constructor(
    /** The room the joiner asks to join. */
    public val roomId: String,
    /** The host's key, as the joiner's verified [Challenge] named it. */
    public val hostKey: DidKey,
    /** The host's peer id: the fabric sender of the challenge this answers. */
    public val hostPeerId: String,
    /** The joiner's own peer id. */
    public val joinerPeerId: String,
    /** The app's own id for this device, or the empty string when the app supplied none. */
    public val deviceId: String,
    hostNonce: ByteArray,
    wire: ByteArray,
) {
    private val hostNonceBytes = hostNonce.copyOf()
    private val wireBytes = wire.copyOf()

    init {
        require(hostNonceBytes.size == Challenge.NONCE_SIZE) {
            "the host nonce is ${hostNonceBytes.size} bytes, not ${Challenge.NONCE_SIZE}"
        }
    }

    /** The nonce from the challenge this proof answers: a copy. */
    public val hostNonce: ByteArray get() = hostNonceBytes.copyOf()

    /** The layout followed by the joiner's signature: what is sent. A copy. */
    public val wire: ByteArray get() = wireBytes.copyOf()

    /**
     * Whether the signature on [wire] is [joiner]'s over the layout. `false`, never a throw, for a
     * bad signature or a [joiner] key whose point is not on the curve.
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun isSignedBy(joiner: DidKey): Boolean = StatementCodec.isSignedBy(wireBytes, joiner)

    override fun equals(other: Any?): Boolean = other is AdmissionProof && wireBytes.contentEquals(other.wireBytes)

    override fun hashCode(): Int = wireBytes.contentHashCode()

    override fun toString(): String =
        "AdmissionProof(roomId=$roomId, hostKey=${hostKey.value}, hostPeerId=$hostPeerId, joinerPeerId=$joinerPeerId, deviceId=$deviceId)"

    public companion object {
        private const val TAG = "kuilt/admit-proof"
        private const val VERSION = 1
        private const val FIELD_COUNT = 7
        private const val HOST_NONCE_FIELD = 5
        private const val CHANNEL_BINDING_FIELD = 6

        /**
         * The proof [joiner] signs to answer a challenge from [hostKey] at [hostPeerId], carrying
         * that challenge's [hostNonce]. A `null` [deviceId] is written as the empty string, so the
         * two cannot be told apart afterwards.
         *
         * @throws IllegalArgumentException when [hostNonce] is not [Challenge.NONCE_SIZE] bytes, or
         *   a string's UTF-8 is longer than a layout field can carry (65 535 bytes), or a string
         *   holds an unpaired surrogate, which has no UTF-8 and would otherwise be signed as a replacement.
         * @throws IllegalStateException when [joiner] cannot sign here.
         */
        public suspend fun sign(
            joiner: DeviceKey,
            roomId: String,
            hostKey: DidKey,
            hostPeerId: String,
            joinerPeerId: String,
            deviceId: String?,
            hostNonce: ByteArray,
        ): AdmissionProof {
            require(hostNonce.size == Challenge.NONCE_SIZE) {
                "the host nonce is ${hostNonce.size} bytes, not ${Challenge.NONCE_SIZE}"
            }
            val layout = Layout.encode(
                TAG,
                VERSION,
                listOf(
                    StatementCodec.text(roomId),
                    StatementCodec.key(hostKey),
                    StatementCodec.text(hostPeerId),
                    StatementCodec.text(joinerPeerId),
                    StatementCodec.text(deviceId.orEmpty()),
                    hostNonce,
                    NO_CHANNEL_BINDING,
                ),
            )
            // Read back through decode, so a signed proof and a received one are built one way.
            return checkNotNull(decode(StatementCodec.signed(joiner, layout))) { "kuilt-identity: a proof just signed does not decode" }
        }

        /**
         * The proof [wire] holds, or `null` when [wire] is not a version-1 `kuilt/admit-proof`
         * layout followed by 64 bytes. Checks the layout and every field's form — the key's syntax,
         * well-formed UTF-8, a [Challenge.NONCE_SIZE]-byte nonce, an empty channel binding — but not
         * the signature: that is [isSignedBy]. Never throws.
         */
        public fun decode(wire: ByteArray): AdmissionProof? {
            val (layout, _) = Layout.split(wire) ?: return null
            val fields = Layout.decode(layout, TAG, VERSION, FIELD_COUNT) ?: return null
            val (roomId, hostKey, hostPeerId, joinerPeerId, deviceId) = fields
            val hostNonce = fields[HOST_NONCE_FIELD]
            if (hostNonce.size != Challenge.NONCE_SIZE) return null
            if (fields[CHANNEL_BINDING_FIELD].isNotEmpty()) return null
            return AdmissionProof(
                roomId = StatementCodec.textOrNull(roomId) ?: return null,
                hostKey = StatementCodec.keyOrNull(hostKey) ?: return null,
                hostPeerId = StatementCodec.textOrNull(hostPeerId) ?: return null,
                joinerPeerId = StatementCodec.textOrNull(joinerPeerId) ?: return null,
                deviceId = StatementCodec.textOrNull(deviceId) ?: return null,
                hostNonce = hostNonce,
                wire = wire,
            )
        }

        private val NO_CHANNEL_BINDING = ByteArray(0)
    }
}
