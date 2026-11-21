package us.tractat.kuilt.identity

/**
 * Throws unless [value] can be signed as a string field of a statement such as a [Challenge] or an
 * [AdmissionProof]: well-formed UTF-16, with no unpaired surrogate, whose UTF-8 fits one layout field
 * (65 535 bytes). [name] says which value, in the message.
 *
 * It is the rule those statements' `sign` apply, not a copy of it. It is here for a caller that takes
 * a string now and signs it later, so the caller hears about a value that can never be signed when
 * it passes it in, rather than as a failure later.
 *
 * @throws IllegalArgumentException when [value] cannot be signed.
 */
@IdentityInternalApi
public fun requireSignableText(value: String, name: String) {
    try {
        Layout.requireFieldSize(StatementCodec.text(value).size) { "its UTF-8" }
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$name cannot be signed: ${e.message}", e)
    }
}
