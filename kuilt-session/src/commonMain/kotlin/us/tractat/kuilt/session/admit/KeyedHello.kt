package us.tractat.kuilt.session.admit

import us.tractat.kuilt.identity.DidKey

/**
 * A [AdmitMessage.Hello] that names both a device key and a joiner nonce, with both no longer
 * optional: what admission holds once the host's gate has let a Hello through (#3210).
 *
 * The Hello's two fields are nullable on the wire only so a v2 Hello decodes and can be refused.
 * Every step past the gate needs both, so the gate produces this type once rather than each step
 * re-checking the Hello.
 */
internal class KeyedHello(
    val hello: AdmitMessage.Hello,
    val key: DidKey,
    val joinerNonce: ByteArray,
)

/** This Hello as a [KeyedHello], or `null` when it is missing its key or its joiner nonce. */
internal fun AdmitMessage.Hello.keyed(): KeyedHello? {
    val key = key ?: return null
    val joinerNonce = joinerNonce ?: return null
    return KeyedHello(this, key, joinerNonce)
}
