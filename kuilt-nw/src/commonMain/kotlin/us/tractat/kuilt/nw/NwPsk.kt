package us.tractat.kuilt.nw

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * The TLS-PSK key material used to secure a Network.framework link on the kuilt-nw
 * fabric.
 *
 * [psk] is the 32-byte pre-shared key both peers feed into the TLS handshake; it
 * never leaves the device in cleartext.
 *
 * [identity] is the PSK identity that travels **in cleartext** in the TLS
 * ClientHello (`psk_identity`). Because it is observable on the wire it is derived
 * with a distinct info label from [psk] and MUST NOT equal or reveal the `roomKey`
 * it was derived from — it is an opaque, roomKey-independent handle, not the secret.
 *
 * Both fields carry value semantics over their byte contents (mirroring
 * [NwBytesReceived]) so two derivations of the same inputs compare equal.
 *
 * `internal`: nothing outside this module consumes PSK material — the public entry
 * points ([nwHost]/[nwJoin]) take a `roomKey: String`, and the only type that holds
 * an [NwPskMaterial] ([RealNwApi]) is itself `internal`. Keeping it off the public
 * surface also keeps mutable key bytes unreachable to consumers.
 */
internal class NwPskMaterial(
    val psk: ByteArray,
    val identity: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NwPskMaterial) return false
        return psk.contentEquals(other.psk) && identity.contentEquals(other.identity)
    }

    override fun hashCode(): Int {
        var result = psk.contentHashCode()
        result = 31 * result + identity.contentHashCode()
        return result
    }
}

/**
 * Derives TLS-PSK key material for the kuilt-nw fabric from the out-of-band
 * `roomKey` bearer secret via HKDF-SHA256 (RFC 5869).
 *
 * Design (decision B): the PSK is derived from kuilt's **`roomKey`** — the secret
 * the host shares out-of-band with joiners — NOT from anything advertised over
 * Bonjour. This makes `securesTransport = true` honest: only a peer that already
 * holds the shared secret can complete the handshake, so the derived key
 * cryptographically scopes the session.
 *
 * Rationale for the construction:
 * - **Never use the raw secret bytes as the PSK.** A raw-secret-as-PSK is banned;
 *   a compromise of the PSK would then be a compromise of the bearer secret, and
 *   the secret gets no domain separation. HKDF gives a fixed-length, uniformly
 *   random key with clean domain separation instead.
 * - **Salt with [serviceType].** The HKDF salt is the serviceType-qualified label,
 *   so two apps or fabrics that happen to share a `roomKey` string but advertise
 *   under different Bonjour service types derive different keys — keys are
 *   partitioned across codes.
 * - **`sessionName` is deliberately NOT bound in.** Host and joiner advertise
 *   different Bonjour service names, so `sessionName` is not a value both sides
 *   share; binding it in would break agreement. Only the `roomKey` (shared secret)
 *   and `serviceType` (shared constant) participate.
 * - **[NwPskMaterial.identity] is derived separately** with a distinct info label,
 *   so the cleartext identity is independent of the [NwPskMaterial.psk] and never
 *   leaks the `roomKey`.
 *
 * KMP-uniform: HMAC-SHA256 comes from KotlinCrypto, so the derivation computes
 * byte-identically on JVM, Android, iOS, macOS, and wasmJs.
 *
 * `internal`: the derivation is a module-internal detail of [nwHost]/[nwJoin]; no
 * public consumer can build a secured `NwApi` from an [NwPskMaterial] today, so it
 * stays off the pre-1.0 public surface until one exists.
 */
internal object NwPsk {
    private const val SALT_PREFIX = "kuilt-nw|"
    private val PSK_INFO = "tls-psk|v1".encodeToByteArray()
    private val IDENTITY_INFO = "psk-id|v1".encodeToByteArray()
    private const val COUNTER_BYTE: Byte = 0x01
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Derive the [NwPskMaterial] for [roomKey] under [serviceType].
     *
     * `PRK = HMAC-SHA256(salt = "kuilt-nw|<serviceType>", ikm = roomKey)`, then a
     * single-block HKDF-Expand for each output with a distinct info label:
     * `psk = HMAC-SHA256(PRK, "tls-psk|v1" || 0x01)` and
     * `identity = HMAC-SHA256(PRK, "psk-id|v1" || 0x01)` — 32 bytes each.
     */
    fun derive(roomKey: String, serviceType: String): NwPskMaterial {
        val salt = (SALT_PREFIX + serviceType).encodeToByteArray()
        val prk = HmacSHA256(salt).doFinal(roomKey.encodeToByteArray())
        val psk = HmacSHA256(prk).doFinal(PSK_INFO + COUNTER_BYTE)
        // The identity goes on the wire as `psk_identity` and MUST be lowercase-hex ASCII, never the
        // raw 32 HMAC bytes (#1577). RFC 4279 §5.1 — the governing spec for the TLS 1.2 external-PSK
        // path Apple's `sec_protocol_options_add_pre_shared_key` uses — requires the identity be "first
        // converted to a character string, and then encoded to octets using UTF-8"; raw HMAC output is
        // not a character string. Worse, that path is C-string-based end-to-end (as are OpenSSL's
        // pre-1.3 PSK callback and wolfSSL, which both take `char*`), so an embedded 0x00 TRUNCATES the
        // identity in transit: the acceptor compares a truncated prefix against its full identity,
        // finds no match, and answers unknown_psk_identity (errSSLUnknownPSKIdentity, OSStatus -9864).
        // A random 32-byte identity contains a NUL with probability 1-(255/256)^32 ≈ 11.8%, so ~1 room
        // in 8 could NEVER connect — weave just timed out with "no peer reached" and no surfaced error.
        // Proven on two iPhones and reproduced on the loopback path (NwPskNulIdentityTest).
        // Hex keeps all 32 bytes of entropy; 64 octets sits inside the 128-octet identity length every
        // conforming implementation must support (RFC 4279 §5.3).
        val identity = HmacSHA256(prk).doFinal(IDENTITY_INFO + COUNTER_BYTE).toLowerHexAscii()
        return NwPskMaterial(psk = psk, identity = identity)
    }

    /** Lowercase-hex ASCII bytes — printable, valid UTF-8, and structurally NUL-free for any input. */
    private fun ByteArray.toLowerHexAscii(): ByteArray {
        val out = ByteArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4].code.toByte()
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F].code.toByte()
        }
        return out
    }
}
