package us.tractat.kuilt.identity

/**
 * Signs [message] for the app, under [context]: the signature [DidKey.verifyApp] accepts for the
 * same context and message, and never a signature over one of kuilt's own statements.
 *
 * This is how an app signs with the device's key. The raw [DeviceKey.sign] is kuilt's own and sits
 * behind [IdentityInternalApi], because kuilt's statements — the admission proof, the challenge, the
 * grant — are signatures over raw bytes. An app that signed raw bytes a peer handed it could be made
 * to sign an admission proof for that peer. Here the key signs a fixed prefix and then [message]:
 *
 * ```
 * u16 tag length ‖ ASCII "app/<context>" ‖ version byte 0x01 ‖ message
 * ```
 *
 * Every kuilt statement opens with the same `u16 tag length ‖ ASCII tag` shape, under a tag starting
 * `kuilt/`. The third byte of these bytes is always the `a` of `app/`, and of a kuilt statement
 * always the `k` of `kuilt/`, so no context and no message can make the two meet. The length prefix
 * also ends the context, so `("ab", "c…")` and `("a", "bc…")` sign different bytes.
 *
 * [context] names what the signature is for, such as `"chess/move"`, so a signature made for one
 * purpose is refused for another. A `"kuilt"` context, or one starting `"kuilt/"`, is refused: that
 * name is kuilt's. It is an extension, not a member of [DeviceKey], so no implementation can
 * override it to drop the prefix.
 *
 * @throws IllegalArgumentException when [context] is empty, not ASCII, longer than a layout tag can
 *   carry, or kuilt's own.
 * @throws IllegalStateException when this key cannot sign here.
 * @sample us.tractat.kuilt.identity.sampleDeviceKeySigns
 */
public suspend fun DeviceKey.signApp(context: String, message: ByteArray): ByteArray = sign(appSigned(context, message))

/**
 * Whether [signature] is this key's [signApp] signature over [message] under [context]. `false`,
 * never a throw, for a signature that is not, as with [DidKey.verify].
 *
 * @throws IllegalArgumentException when [context] is one [signApp] refuses: no signature can exist
 *   for it, so asking is the caller's mistake.
 * @throws IllegalStateException when this platform's cryptographic provider cannot run here.
 * @sample us.tractat.kuilt.identity.sampleDeviceKeySigns
 */
public suspend fun DidKey.verifyApp(context: String, message: ByteArray, signature: ByteArray): Boolean =
    verify(appSigned(context, message), signature)

/** The bytes [signApp] hands the key for [message] under [context], after checking [context]. */
private fun appSigned(context: String, message: ByteArray): ByteArray {
    require(context.isNotEmpty()) { "an app signature's context is empty" }
    require(context.all { it.code < ASCII_END }) { "an app signature's context is not ASCII: \"$context\"" }
    require(context != KUILT && !context.startsWith("$KUILT/")) {
        "an app signature's context may not be kuilt's own: \"$context\""
    }
    require(APP_PREFIX.length + context.length <= Layout.MAX_FIELD_SIZE) {
        "an app signature's context is ${context.length} characters; at most ${Layout.MAX_FIELD_SIZE - APP_PREFIX.length}"
    }
    return Layout.header(APP_PREFIX + context, APP_VERSION) + message
}

private const val APP_PREFIX = "app/"
private const val APP_VERSION = 1
private const val KUILT = "kuilt"
private const val ASCII_END = 0x80
