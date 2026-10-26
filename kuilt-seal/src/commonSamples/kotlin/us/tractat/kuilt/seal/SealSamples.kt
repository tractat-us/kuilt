package us.tractat.kuilt.seal

/** @suppress — sample only */
internal suspend fun sampleSealedBox() {
    // Once, on the recipient's side: make a key, keep the private half secret, publish the public one.
    val recipient = SealPrivateKey.generate()
    val storedPrivateKey: ByteArray = recipient.encodePkcs8() // secret — store it as such
    val publishedKey: ByteArray = recipient.publicKey.encoded() // 65 bytes, safe to compile into an app

    // On the sender's side: only the public key is needed, and the sender cannot read the box afterwards.
    val reportId = "report-42".encodeToByteArray() // authenticated, not encrypted; must match on open
    val sealed = SealedBox.seal(SealPublicKey.decode(publishedKey), "the hidden position".encodeToByteArray(), reportId)

    // Back on the recipient's side, from the stored key.
    val opened = SealedBox.open(SealPrivateKey.decodePkcs8(storedPrivateKey), sealed, reportId)
    check(opened.decodeToString() == "the hidden position")
}
