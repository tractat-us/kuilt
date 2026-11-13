package us.tractat.kuilt.identity

/** @suppress — sample only */
internal suspend fun sampleParseDidKey() {
    // A device's name, as it arrives from somewhere you do not control: a peer, a QR code, a saved file.
    val received = "did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169"
    val device = DidKey.parse(received) // syntax, and that the key really is a point on P-256
    check(device.value == received)
    check(device.compressedPoint().size == 33)

    // Well-formed but naming no key at all: refused the same way on every platform.
    val noSuchKey = "did:key:zDnaeQRy3dcKsKa1zmKtVKsTy3m2HYoQnFnfKuxD6HfSTQgYg"
    val refused = try {
        DidKey.parse(noSuchKey)
        false
    } catch (_: IllegalArgumentException) {
        true
    }
    check(refused)
}

/** @suppress — sample only */
internal suspend fun sampleDeviceKeySigns() {
    // This device's key. A real app gets it from a key store, so it is the same key tomorrow.
    val key = DeviceKey.generate()

    // The device signs something fresh; anyone holding only its public name can check the signature.
    val challenge = "a fresh challenge from the room".encodeToByteArray()
    val signature = key.sign(challenge)
    check(key.id.verify(challenge, signature))

    // A signature proves this key and this message, nothing else.
    val someoneElse = DeviceKey.generate()
    check(!someoneElse.id.verify(challenge, signature))
    check(!key.id.verify("a different message".encodeToByteArray(), signature))
}

/** @suppress — sample only */
internal suspend fun sampleIssueGrant() {
    val host = DeviceKey.generate() // the host's own key
    val phone = DeviceKey.generate() // the device being let in
    val now = 1_700_000_000_000L // epoch ms, from your clock: a grant never reads one

    // On admitting the phone, the host signs it a pass back into this room for the next hour.
    val grant = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt = now, notAfter = now + 3_600_000)
    val kept: ByteArray = grant.wire // what the phone stores, and presents when it returns

    // When the phone comes back, the host reads the pass and checks it signed exactly these terms.
    val presented = checkNotNull(Grant.decode(kept))
    check(presented.isSignedByIssuer() && presented.issuer == host.id)
    check(presented.subject == phone.id && presented.scope == GrantScope.Room("table-7"))
    check(now + 60_000 < presented.notAfter) // a minute later, still good

    // Change any byte — here the last byte of the signature — and it is no longer the host's pass.
    val edited = kept.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    check(Grant.decode(edited)?.isSignedByIssuer() == false)
}
