package us.tractat.kuilt.identity

import us.tractat.kuilt.store.DurableStore
import us.tractat.kuilt.store.InMemoryDurableStore

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

    // The app signs its own bytes under a context naming what they are for; anyone holding only the
    // device's public name can check the signature.
    val move = "move 12: e4".encodeToByteArray()
    val signature = key.signApp("chess/move", move)
    check(key.id.verifyApp("chess/move", move, signature))

    // A signature proves this key, this context and this message, nothing else.
    val someoneElse = DeviceKey.generate()
    check(!someoneElse.id.verifyApp("chess/move", move, signature))
    check(!key.id.verifyApp("chess/resign", move, signature))
    check(!key.id.verifyApp("chess/move", "move 12: d4".encodeToByteArray(), signature))

    // "kuilt" is kuilt's own context, so no app signature can pass for one of its statements.
    val refused = try {
        key.signApp("kuilt/admit-proof", move)
        false
    } catch (_: IllegalArgumentException) {
        true
    }
    check(refused)
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

    // Damage it — here the last byte of the signature — and it is no longer the host's pass.
    val edited = kept.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    check(Grant.decode(edited)?.isSignedByIssuer() == false)
}

/** @suppress — sample only */
internal suspend fun sampleDeviceKeyStore() {
    // One store per device. This one forgets with the process; a platform store keeps the key on disk.
    val store: DeviceKeyStore = InMemoryDeviceKeyStore()

    // The key is made on first use, and every call after returns the same one.
    val me = store.deviceKey()
    check(store.deviceKey().id == me.id)

    // A host lets this device back in; the device keeps the newest pass per host and scope.
    val host = DeviceKey.generate()
    val now = 1_700_000_000_000L
    val first = Grant.issue(host, me.id, GrantScope.AnyRoomOfIssuer, issuedAt = now, notAfter = now + 3_600_000)
    val renewed = Grant.issue(host, me.id, GrantScope.AnyRoomOfIssuer, issuedAt = now + 1_800_000, notAfter = now + 5_400_000)
    store.keepGrant(renewed)
    store.keepGrant(first) // older than the one held from this host: ignored
    check(store.grants() == listOf(renewed))
}

/** @suppress — sample only */
internal suspend fun sampleGrantTrust() {
    val host = DeviceKey.generate() // the host's own key: the only issuer it trusts
    val phone = DeviceKey.generate() // a device the host let in once
    val revocations = RevocationList.inMemory()
    val trust = GrantTrust(host.id, revocations)
    val now = 1_700_000_000_000L // epoch ms, from your clock: the check never reads one

    // The phone comes back with the pass the host gave it, having proved it holds `phone`'s key.
    val pass = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt = now, notAfter = now + 3_600_000)
    check(trust.verify(pass, phone.id, "table-7", now + 60_000) == GrantVerdict.Valid)

    // The first thing wrong is the answer: another room, another device, too late.
    check(trust.verify(pass, phone.id, "table-8", now + 60_000) == GrantVerdict.WrongScope)
    check(trust.verify(pass, host.id, "table-7", now + 60_000) == GrantVerdict.WrongSubject)
    check(trust.verify(pass, phone.id, "table-7", now + 3_600_000) == GrantVerdict.Expired)

    // Too early: a pass dated more than five minutes past the clock, issued while it ran ahead.
    val postDated = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt = now + 600_000, notAfter = now + 3_600_000)
    check(trust.verify(postDated, phone.id, "table-7", now) == GrantVerdict.NotYetValid)

    // The phone is lost: revoke it. A pass issued exactly at the cutoff stays good, so the cutoff is
    // one past the clock, or one issued in this same millisecond would slip through.
    val lostAt = now + 120_000
    val sameMillisecond = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt = lostAt, notAfter = lostAt + 3_600_000)
    revocations.revoke(phone.id, notBefore = lostAt + 1)
    check(trust.verify(pass, phone.id, "table-7", lostAt + 60_000) == GrantVerdict.Revoked)
    check(trust.verify(sameMillisecond, phone.id, "table-7", lostAt + 60_000) == GrantVerdict.Revoked)

    // A fresh pass, issued after the cutoff, works again.
    val fresh = Grant.issue(host, phone.id, GrantScope.Room("table-7"), issuedAt = lostAt + 120_000, notAfter = lostAt + 3_720_000)
    check(trust.verify(fresh, phone.id, "table-7", lostAt + 180_000) == GrantVerdict.Valid)
}

/** @suppress — sample only */
internal suspend fun sampleDurableRevocations() {
    // The host app's store: a file, a directory, IndexedDB. In memory here.
    val store: DurableStore = InMemoryDurableStore()
    val lostPhone = DeviceKey.generate().id
    val now = 1_700_000_000_000L

    // Revoke through a list opened on the store: `revoke` returns once the list is written.
    val revocations = RevocationList.open(store)
    revocations.revoke(lostPhone, notBefore = now + 1) // one past the clock, as for any revocation

    // The host restarts. The list it opens holds the revocation, so the lost phone's old pass stays void.
    val afterRestart = RevocationList.open(store)
    check(afterRestart.notBefore(lostPhone) == now + 1)
}

/** @suppress — sample only */
internal suspend fun sampleDeviceKeyStoreRemembers(openAtLaunch: suspend () -> DeviceKeyStore) {
    // At launch the app opens its platform store, e.g. DeviceKeyStore.platform("my-app", dir) on the desktop.
    val today = openAtLaunch()
    val me = today.deviceKey()
    val host = DeviceKey.generate()
    val now = 1_700_000_000_000L
    val pass = Grant.issue(host, me.id, GrantScope.Room("table-7"), issuedAt = now, notAfter = now + 3_600_000)
    today.keepGrant(pass)

    // The app is closed, and opened again tomorrow: the same device, still holding its pass.
    val tomorrow = openAtLaunch()
    check(tomorrow.deviceKey().id == me.id)
    check(tomorrow.grants() == listOf(pass))
}

/** @suppress — sample only */
internal suspend fun sampleDeviceProof() {
    // The device, about to dial wss://boards.example/board. It fetched a fresh nonce from the server first.
    val device = DeviceKey.generate()
    val nonce = "a nonce the server served".encodeToByteArray()

    // The audience comes from the URL being dialled, wss mapped to https, never from the server's answer.
    val proof = DeviceProof.sign(device, audience = "https://boards.example", resource = "/board", nonce = nonce)
    val sent: ByteArray = proof.wire // base64url, in the query

    // The server reads it back, and checks the origin and path it answers on, and its own nonce.
    val received = checkNotNull(DeviceProof.decode(sent))
    check(received.audience == "https://boards.example" && received.resource == "/board")
    check(received.nonce.contentEquals(nonce))

    // It proves the device holds the key it names, and nothing more: whether the server knows that
    // key is a pass's question.
    check(received.subject == device.id && received.isSignedBySubject())

    // Change one byte of the signature, and it no longer proves the key.
    val edited = sent.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    check(DeviceProof.decode(edited)?.isSignedBySubject() == false)
}

/** @suppress — sample only */
internal suspend fun sampleProofNonces() {
    // One per server process, made at start-up. Its secret never leaves it.
    val nonces = ProofNonces.create() // 60 s each, at most 65 536 spent ones kept
    val now = 1_700_000_000_000L // epoch ms, from your clock: it never reads one

    // A device asks for a nonce, and the server keeps no record of handing it out.
    val served = nonces.issue(now)

    // The device dials with a proof over it. The server checks the nonce is its own and fresh…
    val fresh = nonces.check(served, now + 200)
    check(fresh is NonceCheck.Fresh)
    // …checks the proof's signature (not shown), and only then spends the nonce.
    check(nonces.spend(fresh, now + 210) == NonceSpend.Spent)

    // The same proof again is a replay, whoever sends it.
    val again = nonces.check(served, now + 500)
    check(again is NonceCheck.Fresh && nonces.spend(again, now + 500) == NonceSpend.Replayed)

    // A minute on, the nonce has run out: the device fetches another.
    check(nonces.check(served, now + 60_000) == NonceCheck.Expired)
}
