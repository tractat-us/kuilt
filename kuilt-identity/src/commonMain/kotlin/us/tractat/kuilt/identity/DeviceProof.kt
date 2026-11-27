package us.tractat.kuilt.identity

/**
 * A device's one-shot proof, to a server, that it holds its key: "I am [subject], dialling
 * [audience] at [resource], answering [nonce]." The device signs it with its own [DeviceKey], so a
 * server that checks it with [isSignedBySubject] knows the device knocking holds that key, before
 * any room has started.
 *
 * It is for a request that carries nothing but a URL, such as a browser's WebSocket upgrade: the
 * device fetches a fresh nonce from the server, signs this statement over it, and sends the [wire]
 * bytes in the query. The server checks the audience and the path, spends the nonce so the proof
 * cannot be used twice, and checks the signature. This type is the statement alone; the nonce and
 * the order of those checks are not its job.
 *
 * It binds everything a replay or a relay could vary:
 *
 * - **[subject]**, the signer's own key. It rides beside the signature, so it is signed too: an
 *   ECDSA signature also verifies under a second key anyone can compute from it, and signing the
 *   key rules that key out.
 * - **[audience]**, the origin the device dials, as `scheme://host[:port]`. The device takes it
 *   from its own target URL, never from anything the server sends, so a hostile server relaying
 *   another server's nonce gets a proof made out to itself, which the real server refuses.
 * - **[resource]**, the request path with no query, such as `/board`, so a proof for one endpoint
 *   is not spent on another.
 * - **[nonce]**, as the server served it. Its bytes are the server's business; here it is opaque
 *   and never empty.
 *
 * Its [wire] form is the signed bytes themselves — the layout below, then the 64-byte signature —
 * and two proofs are equal when their wire bytes are. The arrays it hands out are copies.
 *
 * ```
 * Layout tag "kuilt/device-proof", version 1, five fields in this order:
 *   subject key (33-byte compressed point) ‖ audience (UTF-8) ‖ resource (UTF-8) ‖
 *   server nonce (as served, at least one byte) ‖ channel binding (reserved: always empty)
 * ```
 *
 * **It is a kuilt statement, not an app signature.** [signApp] refuses every `kuilt` context, so a
 * proof under an app context would be one any app's own [signApp] call could mint. The `kuilt/`
 * tag keeps it kuilt's: it never decodes as an [AdmissionProof], a [Challenge] or a [Grant], nor
 * they as it, and an app signature's signed bytes differ from it at the third byte.
 *
 * The channel-binding field is reserved, as in [AdmissionProof], for a value tying the proof to one
 * TLS connection. A browser could never fill it, and nothing does yet, so [sign] writes it empty and
 * [decode] refuses a proof where it is not.
 *
 * @sample us.tractat.kuilt.identity.sampleDeviceProof
 */
public class DeviceProof internal constructor(
    /** The key of the device that signed this proof. [isSignedBySubject] checks the signature under it. */
    public val subject: DidKey,
    /** The origin the device dialled, as `scheme://host[:port]`. */
    public val audience: String,
    /** The request path the device dialled, with no query. */
    public val resource: String,
    nonce: ByteArray,
    wire: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()
    private val wireBytes = wire.copyOf()

    init {
        require(nonceBytes.isNotEmpty()) { "a device proof's nonce is empty" }
    }

    /** The server's nonce this proof answers, as served: a copy. */
    public val nonce: ByteArray get() = nonceBytes.copyOf()

    /** The layout followed by the subject's signature: what is sent. A copy. */
    public val wire: ByteArray get() = wireBytes.copyOf()

    /**
     * Whether the signature on [wire] is [subject]'s over the layout. `false`, never a throw, for a
     * bad signature or a [subject] whose point is not on the curve.
     *
     * There is no form taking another key: the only key a device proof can be checked under is the
     * one it names, and a proof answering `true` here proves that key and nothing else. Whether the
     * key is one the server knows is a pass's question, not this one's.
     *
     * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
     */
    public suspend fun isSignedBySubject(): Boolean = isSignedBySubject(KeyScheme.P256)

    /**
     * [isSignedBySubject], checked in [scheme] rather than always in [KeyScheme.P256]. A server
     * passes its own [DeviceKeyStore.keyScheme], so a test on a fake scheme stays on virtual time.
     */
    @IdentityInternalApi
    public suspend fun isSignedBySubject(scheme: KeyScheme): Boolean = StatementCodec.isSignedBy(wireBytes, subject, scheme)

    override fun equals(other: Any?): Boolean = other is DeviceProof && wireBytes.contentEquals(other.wireBytes)

    override fun hashCode(): Int = wireBytes.contentHashCode()

    override fun toString(): String =
        "DeviceProof(subject=${subject.value}, audience=$audience, resource=$resource, nonce=${nonceBytes.toHexString()})"

    public companion object {
        private const val TAG = "kuilt/device-proof"
        private const val VERSION = 1
        private const val FIELD_COUNT = 5
        private const val NONCE_FIELD = 3
        private const val CHANNEL_BINDING_FIELD = 4

        /**
         * The proof [device] signs to answer [nonce] from the server at [audience], for [resource].
         * The subject is [device]'s own [DeviceKey.id].
         *
         * Take [audience] from the URL being dialled, never from the server's answer: that is what
         * makes a proof relayed through another server worthless.
         *
         * @throws IllegalArgumentException when [nonce] is empty, a string's UTF-8 or the nonce is
         *   longer than a layout field can carry (65 535 bytes), or a string holds an unpaired
         *   surrogate, which has no UTF-8 and would otherwise be signed as a replacement.
         * @throws IllegalStateException when [device] cannot sign here.
         */
        public suspend fun sign(device: DeviceKey, audience: String, resource: String, nonce: ByteArray): DeviceProof {
            require(nonce.isNotEmpty()) { "a device proof's nonce is empty" }
            val layout = Layout.encode(
                TAG,
                VERSION,
                listOf(
                    StatementCodec.key(device.id),
                    StatementCodec.text(audience),
                    StatementCodec.text(resource),
                    nonce,
                    NO_CHANNEL_BINDING,
                ),
            )
            // Read back through decode, so a signed proof and a received one are built one way.
            return checkNotNull(decode(StatementCodec.signed(device, layout))) { "kuilt-identity: a device proof just signed does not decode" }
        }

        /**
         * The proof [wire] holds, or `null` when [wire] is not a version-1 `kuilt/device-proof`
         * layout followed by 64 bytes. Checks the layout and every field's form — the key's syntax,
         * well-formed UTF-8, a nonce of at least one byte, an empty channel binding — but not the
         * signature: that is [isSignedBySubject]. Never throws.
         */
        public fun decode(wire: ByteArray): DeviceProof? {
            // One copy, taken first: what is parsed is what is kept, whatever the caller does to [wire] after.
            val bytes = wire.copyOf()
            val (layout, _) = Layout.split(bytes) ?: return null
            val fields = Layout.decode(layout, TAG, VERSION, FIELD_COUNT) ?: return null
            val (subject, audience, resource) = fields
            val nonce = fields[NONCE_FIELD]
            if (nonce.isEmpty() || fields[CHANNEL_BINDING_FIELD].isNotEmpty()) return null
            return DeviceProof(
                subject = StatementCodec.keyOrNull(subject) ?: return null,
                audience = StatementCodec.textOrNull(audience) ?: return null,
                resource = StatementCodec.textOrNull(resource) ?: return null,
                nonce = nonce,
                wire = bytes,
            )
        }

        private val NO_CHANNEL_BINDING = ByteArray(0)
    }
}
