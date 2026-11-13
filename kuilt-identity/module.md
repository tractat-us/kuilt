# Module kuilt-identity

**Let a room recognise a device when it comes back.**

A phone drops off the Wi-Fi in the middle of a game and rejoins a minute later. Today the room sees
a stranger. This module gives each device one key of its own, kept on that device, and a short
public name for it. When the device returns it proves it still holds the key, and the room can hand
back its seat without asking anyone to do anything.

## What is here so far

Nothing public yet. This first piece is the module's foundation: the per-platform cryptography it
will build on, and a check that every platform can do what a device name needs. The key, its name,
the signed statements a room's admission is made of, and the per-platform key stores arrive in the
pieces that follow.

## Underneath

One P-256 key per device, signing with ECDSA over SHA-256. Its name is a `did:key`, which carries
the public key as a 33-byte compressed point, so every platform must read and write that form; the
module's tests check it on JVM, Android, iOS, macOS and the browser. The primitives come from
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin), which wraps each platform's
own implementation: JCA on JVM and Android, CryptoKit on Apple, WebCrypto in the browser. This
module only composes them and owns no math. It does not depend on `:kuilt-seal`, so a consumer of
one inherits nothing of the other.
