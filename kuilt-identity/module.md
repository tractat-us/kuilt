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
the public key as a 33-byte compressed point, so every platform must read and write that form. The
module's tests check it on JVM and Android's host-JDK unit tests, iOS, macOS and headless Chrome;
compressed import in other browsers is unproven. The primitives come from
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin), which wraps each platform's
own implementation: JCA on JVM and Android, CryptoKit on Apple, WebCrypto in the browser. This
module only composes them and owns no math. It does not depend on `:kuilt-seal`, so a consumer of
one inherits nothing of the other.

One check is the module's own. The JDK decoder takes a square root without checking it, so on JVM
and Android a `did:key` whose `x` has no point on P-256 would decode into a key, while CryptoKit and
WebCrypto refuse it. `DidKey.parse` and `DidKey.verify` therefore check the point in common code,
`x < p` and then that `x³ − 3x + b` is a square mod `p`, so every platform gives the same answer.
That is validation only; computing `y` stays the provider's.
