package us.tractat.kuilt.seal

/**
 * A [SealedBox] operation failed. [reason] says which way.
 *
 * The message is built only from [reason] and, for a failure inside the cryptographic provider, the
 * provider exception's class name. It never carries a byte of plaintext, ciphertext or key, and it
 * has no [cause]: a provider's own message is text this module does not control, and a caller's
 * crash report records the whole message chain.
 */
public class SealedBoxException internal constructor(
    public val reason: Reason,
    detail: String? = null,
) : Exception(if (detail == null) reason.summary else "${reason.summary} ($detail)") {

    /** Why a [SealedBox] operation failed. */
    public enum class Reason(internal val summary: String) {
        /** The sealed bytes are too short to hold a header, a nonce and a tag. */
        MALFORMED("sealed box is malformed"),

        /** The sealed bytes start with a format version this build does not read. */
        UNSUPPORTED_VERSION("sealed box has an unsupported format version"),

        /**
         * The box did not open: the wrong private key, different associated data, or bytes that were
         * changed after sealing. Deliberately one reason, so a failure says nothing about which.
         */
        NOT_AUTHENTIC("sealed box failed authentication"),

        /** Sealing failed inside the platform's cryptographic provider. */
        SEAL_FAILED("sealing failed in the cryptographic provider"),
    }
}
