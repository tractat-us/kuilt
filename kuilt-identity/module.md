# Module kuilt-identity

**Let a room recognise a device when it comes back.**

A phone drops off the Wi-Fi in the middle of a game and rejoins a minute later. Today the room sees
a stranger. This module gives each device one key of its own, kept on that device, and a short
public name for it. When the device returns it proves it still holds the key, and the room can hand
back its seat without asking anyone to do anything.

## What is here so far

The name. `DidKey` is the public name of one device's key, a string another device can store,
compare, and check a signature against. Read one from outside with `DidKey.parse`, which checks
that it really names a key.

The key. `DeviceKey` signs, and its `id` is the `DidKey` that checks those signatures. It has no
accessor for the private half, so a key that a hardware or browser store will not hand out is
still a `DeviceKey`. `DeviceKey.generate()` makes one in memory.

The pass. A `Grant` is a host's signed promise that one device may come back: to one room, or to
any room that host runs, until a time the host picks, or for good. The device keeps it and shows it
when it returns, and `isSignedByIssuer` checks the host really signed it. It is not a secret,
because it names the device's key and is useless without it.

The check. When a device shows a grant, `GrantTrust` decides whether it is good in this room now:
issued and signed by this host's own key, naming the device that proved it holds that key, for this
room, not revoked, not run out. The answer is a `GrantVerdict`, `Valid` or the first thing wrong. A
host that no longer wants a device back on an old pass revokes it in its `RevocationList`: every
grant issued to that key before the cutoff stops working, and a fresh one issued after works again.
Revoking voids passes; it is not a ban. A revoked device that knocks again is a newcomer, and the
host decides it like any other, so an app that wants a device kept out keeps its own blocked set,
which it can undo. Revoking does not remove a device that is in the room now.

A host keeps its revocations across a restart with `RevocationList.open(store)`, over a
`DurableStore` from `:kuilt-store` that the app supplies. Each `revoke` writes the whole list and
returns once the write is committed. `RevocationList.inMemory()` keeps nothing, so every restart
forgets every revocation.

The design names two ways to lose state, and they go in opposite directions: "losing the store
forgets every revocation (revoked friends return); losing the host key is the safe direction (every
grant becomes `UntrustedIssuer`)." A store that comes back empty looks exactly like a host that
never revoked anyone, so `open` cannot tell and opens an empty list. A store that comes back
damaged is different: `open` throws an `IllegalStateException` naming the store key, rather than
quietly starting from nothing, and leaves the bytes where they are.

The statements a room's admission exchange is made of arrive in the pieces that follow.

The store. A device is recognised only if it comes back with the same key, so the key has to
outlive the app. `DeviceKeyStore` is where it lives: the first `deviceKey()` call makes it and every
call after returns it, and beside it the store keeps the device's grants, the newest from each host
for each scope (one room, or any room it hosts), since one host can run many rooms.
The key is the secret, and how well a store keeps it is what tells one store from another.
`InMemoryDeviceKeyStore` keeps both in memory and forgets them with the process, so a device using
it is a new device every time the app starts. That suits tests, and an app that has chosen not to
be recognised.

On the desktop, `DeviceKeyStore.platform("my-app", directory)` keeps the key in a file under that
directory, so the app is the same device after a restart. Only this user can read the file. If the
file is damaged — cut short, edited, or holding two halves of different keys — the store moves it
aside, makes a new key and logs a warning: the app keeps working, as a new device, and never
crashes over it. A file it cannot read at all is different: that throws, because becoming a new
device over a passing fault would be worse. Give it a folder that stays on this computer, one that
backups, migration and roaming profiles do not copy to another: a copied key makes two computers one
device. On an iPhone or iPad, `DeviceKeyStore.platform("my-app")` keeps the key in the Keychain, set
so it never leaves this device: iCloud Keychain does not sync it and a backup does not restore it to
another phone. On a Mac the same call keeps the key in a file in the app's Application Support folder
instead, which only this user can read. The trade is stated plainly: Time Machine and Migration
Assistant copy that folder to the next Mac, and the two Macs are then one device until one of them
deletes its key file. The Android and browser stores follow, one at a time. Every store, including one you write
yourself, proves it keeps its promises by passing `DeviceKeyStoreConformanceSuite` from
`:kuilt-conformance`.

## What this protects against, and what it does not

The room exchange these keys are for is not built yet. When it is, its promise is the design's,
quoted here in its own words:

> - **Protected:** a bystander in the room — who knows every peer id, the room id and every key —
>   cannot resume as, return as, or lock out another member. A captured proof cannot be replayed.
> - **Not protected:** an active relay sitting on the victim's own link over an unauthenticated
>   fabric. Peer ids are self-chosen on WebSocket and star fabrics, so such a relay can forward a
>   challenge and its answer. Closing that needs channel binding (a TLS exporter from #3157, or
>   `kuilt-nw`'s PSK session); the proof layout reserves a field for it, empty in this slice.
> - **The key binds the handshake, not later frames.** On a `Mesh`, a link that later takes a
>   member's peer id (#2387) sends frames under that member's `Member.key`. Deployments on
>   `HostedOverlay` should use a binding `LinkAdmission` until #2387 lands.

Every store answers to one more line of the design: "A credential many hold never resumes; one a
single device holds does." A key copied to a second device, by a synced keychain or a restored
backup, is a credential two hold, so a store keeps its key on the device that made it.

## Underneath

One P-256 key per device, signing with ECDSA over SHA-256. Its name is a `did:key`, which carries
the public key as a 33-byte compressed point. The primitives come from
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin), which wraps each platform's
own implementation: JCA on JVM and Android, CryptoKit on Apple, WebCrypto in the browser. This
module composes them and owns no cryptographic math beyond decompressing a point, described below.
It does not depend on `:kuilt-seal`, so a consumer of one inherits nothing of the other.

The module decompresses the point itself and hands every provider the 65-byte uncompressed form,
because Safari refuses compressed import. Safari's and WebKit's WebCrypto throw a `DataError` on a
33-byte `importKey("raw")`, while Chromium and Firefox accept it and the uncompressed form imports
in all four; every browser on iOS is WebKit. Doing it in common code fixes a second problem too: the
JDK decoder takes a square root without checking it, so on JVM and Android a `did:key` whose `x` has
no point on P-256 would decode into a key, while CryptoKit and WebCrypto refuse it. So
`DidKey.parse` and `DidKey.verify` check `x < p` and that `x³ − 3x + b` is a square mod `p`, take its
root, pick the root whose parity the lead byte names, and import `0x04 ‖ x ‖ y`. Every platform gets
the same bytes and the same answer. Exporting a key in compressed form stays the library's.

The desktop store writes its key as a JWK carrying `x`, `y` and `d`, created with POSIX mode `0600`
through a forced temporary file and an atomic rename. It reads the key back with a self-test: sign
with `d`, then verify under the name `x` and `y` give, through the same `DidKey.verify` every other
device uses. A pair whose halves belong to different keys fails it and is replaced like a truncated
file. PKCS#8 is not the format because the JDK provider writes it without the public half, and
reading it back then needs a public half only BouncyCastle can derive; BouncyCastle is not an option,
since two registered JCA providers stop this module's provider from starting. The grants sit beside
the key in a `FileChannelDurableStore`, as one strict record that is dropped whole if it cannot be
read.
