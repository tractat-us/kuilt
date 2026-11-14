package us.tractat.kuilt.identity

/** [jwk] with its `d` replaced by the `d` in [of]: a pair whose halves belong to different keys. */
internal fun withPrivatePart(jwk: String, of: String): String {
    val d = Regex("\"d\"\\s*:\\s*\"([^\"]+)\"")
    val signerD = checkNotNull(d.find(of)) { "no d in the signer's JWK" }.groupValues[1]
    val namedD = checkNotNull(d.find(jwk)) { "no d in the named JWK" }.groupValues[1]
    check(signerD != namedD) { "precondition: two different keys" }
    return jwk.replace(namedD, signerD)
}

/**
 * A well-formed JWK whose `x` and `y` are one fresh key's and whose `d` is another's, and the name its
 * `x` and `y` claim: what a store's self-test must refuse.
 */
internal suspend fun mismatchedJwk(): Pair<ByteArray, DidKey> {
    val named = SoftwareDeviceKey.generate()
    val signer = SoftwareDeviceKey.generate()
    val spliced = withPrivatePart(named.encodeJwk().decodeToString(), of = signer.encodeJwk().decodeToString())
    return spliced.encodeToByteArray() to named.id
}
