package us.tractat.kuilt.identity

/**
 * How one device makes keys, reads a key named from outside, and checks a signature. A room uses one
 * scheme for everything it signs and checks, and takes it from its [DeviceKeyStore.keyScheme].
 *
 * There is one scheme for real use, [P256]: ECDSA on P-256, through the platform's own provider. It
 * is what every [DeviceKeyStore] answers unless it says otherwise, and [InMemoryDeviceKeyStore]'s
 * public constructor answers nothing else.
 *
 * **Why the seam exists.** On wasmJs every [P256] operation is a WebCrypto promise. A room test
 * running under `runTest` that waits on one stops being a virtual-time test, so the room and timer
 * tests in other kuilt modules check signatures with a synchronous fake scheme instead. That fake
 * lives in `:kuilt-identity-test`, behind its own tests-only opt-in, and never in this module.
 * Real P-256 stays covered here, by this module's own tests and its signature vectors.
 *
 * **The scheme belongs to the room, never to the key.** A [DidKey] carries no scheme: a key read off
 * the wire is checked under whatever scheme the reading room was built with. So a room on [P256]
 * checks every signature with ECDSA, whatever a peer sends, and a fake signature is just a wrong one.
 *
 * Every member may be called from many coroutines at once, on a multi-threaded dispatcher.
 */
@IdentityInternalApi
public interface KeyScheme {
    /**
     * A new key in this scheme, held in memory only.
     *
     * @throws IllegalStateException when this scheme cannot make a key on this platform.
     */
    public suspend fun generate(): DeviceKey

    /**
     * The key [value] names, as far as this scheme can check it from outside. [P256] is [DidKey.parse].
     *
     * A key inside a received frame does not come through here: it decodes with [DidKey]'s serializer,
     * which checks its syntax only, and [verify] does the rest.
     *
     * @throws IllegalArgumentException when [value] does not name a key this scheme accepts.
     */
    public suspend fun parse(value: String): DidKey

    /**
     * Whether [signature] is [key]'s signature over [message] in this scheme. Never throws on bad
     * input: a malformed key or signature is `false`.
     *
     * @throws IllegalStateException when this scheme cannot run on this platform at all, which is not
     *   a verdict on the signature.
     */
    public suspend fun verify(key: DidKey, message: ByteArray, signature: ByteArray): Boolean

    public companion object {
        /**
         * ECDSA on P-256 over SHA-256, through the platform's own provider: [DeviceKey.generate],
         * [DidKey.parse] and [DidKey.verify], unchanged.
         */
        public val P256: KeyScheme = P256KeyScheme
    }
}

/** The real scheme: each member is the public operation it names, so the seam adds no behaviour. */
@IdentityInternalApi
private object P256KeyScheme : KeyScheme {
    override suspend fun generate(): DeviceKey = DeviceKey.generate()

    override suspend fun parse(value: String): DidKey = DidKey.parse(value)

    override suspend fun verify(key: DidKey, message: ByteArray, signature: ByteArray): Boolean = key.verify(message, signature)

    override fun toString(): String = "KeyScheme.P256"
}
