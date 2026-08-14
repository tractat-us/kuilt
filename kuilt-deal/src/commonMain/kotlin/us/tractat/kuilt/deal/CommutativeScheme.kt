package us.tractat.kuilt.deal

public interface CommutativeScheme {
    public fun encrypt(plaintext: ByteArray, key: SchemeKey): Pair<ByteArray, EncryptProof>
    public fun strip(ciphertext: ByteArray, key: SchemeKey): Pair<ByteArray, StripProof>

    /**
     * Verify that [next] was produced by applying the key corresponding to [pubKey] to [prev].
     * Initial implementations may return true unconditionally — the GSet membership check
     * is the primary double-encode defence. A full ZK proof is a follow-up.
     *
     * **Nothing in this module calls this, deliberately.** `DealSession` computes a proof at every
     * encrypt and strip and applies a remote operation without consulting either verifier. Wiring
     * one in would be theatre while every shipped implementation returns `true`: it cannot reject
     * anything, so it would add a call site that looks like a defence and is not one. The order is
     * real proofs first, then the apply-path check.
     *
     * So a consumer must not read a green `CommutativeSchemeConformanceSuite` as cheat detection.
     * Which of the two an implementation is, is declared to that suite as a `ProofStrength` and
     * held to in both directions — an accept-only scheme has to say so, and a scheme that grows a
     * real verifier reds until it says *that*.
     */
    public fun verifyEncrypt(prev: ByteArray, next: ByteArray, proof: EncryptProof, pubKey: SchemeKey): Boolean

    /** The [verifyEncrypt] obligation for a strip, and everything said there applies unchanged. */
    public fun verifyStrip(prev: ByteArray, next: ByteArray, proof: StripProof, pubKey: SchemeKey): Boolean

    public fun generateKey(): SchemeKeyPair
}
