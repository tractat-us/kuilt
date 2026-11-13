# Module kuilt-identity

**Let a room recognise a device when it comes back.**

A phone drops off the Wi-Fi in the middle of a game and rejoins a minute later. Today the room sees
a stranger. This module gives each device one key of its own, kept on that device, and a short
public name for it. When the device returns it proves it still holds the key, and the room can hand
back its seat without asking anyone to do anything.

## What is here so far

The name. `DidKey` is the public name of one device's key, a string another device can store,
compare, and check a signature against. Read one from outside with `DidKey.parse`, which checks
that it really names a key. The key that signs, the signed statements a room's admission is made
of, and the per-platform key stores arrive in the pieces that follow.

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
