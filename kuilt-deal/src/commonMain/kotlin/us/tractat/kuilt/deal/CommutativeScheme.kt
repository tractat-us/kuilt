package us.tractat.kuilt.deal

public interface CommutativeScheme {
    public fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof>
    public fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof>

    /**
     * Verify that [next] was produced by applying the key corresponding to [pubKey] to [prev].
     * Initial implementations may return true unconditionally — the GSet membership check
     * is the primary double-encode defence. A full ZK proof is a follow-up.
     */
    public fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: SchemeKey): Boolean
    public fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: SchemeKey): Boolean

    public fun generateKey(): SchemeKeyPair
}
