# Module kuilt-seal

**Lock some bytes so that only one recipient can open them.**

The recipient publishes a public key. Anyone holding it can seal bytes to it; only the holder of
the matching private key can open the result. The sender needs no secret of their own and keeps
nothing afterwards: once sealed, the sender cannot read the box either. That is the point. A game
client can attach the full hidden position to a problem report, and the player who filed it still
cannot see the cards they were never shown.

## The promise

- **Only the private key opens a box.** A wrong key, different associated data, or one changed
  byte all fail the same way, with `SealedBoxException.Reason.NOT_AUTHENTIC`.
- **No two seals are alike.** Every seal draws a fresh ephemeral key and a fresh nonce from the
  platform's secure random source, so sealing the same bytes twice gives two different boxes. There
  is no deterministic mode, for tests or anything else: a reproducible seal lets someone who knows
  all but one input confirm a guess at it.
- **Failures never quote your data.** An exception's message names the reason and nothing else,
  because crash reports keep the whole message chain.

## Using it

A recipient makes its key once, outside the app, as PKCS #8 DER that carries its public key, which
is what `openssl ecparam -name prime256v1 -genkey | openssl pkcs8 -topk8 -nocrypt -outform DER`
writes. It loads that key with `SealPrivateKey.decodePkcs8` and hands out `publicKey.encoded()`, a
fixed 65 bytes. `SealPrivateKey.generate()` makes a key that lives only in memory. There is no
encoder, because the JVM provider writes PKCS #8 without the public key and then cannot read it
back. A sender reads those bytes with `SealPublicKey.decode` and calls `SealedBox.seal(recipient,
plaintext, associatedData)`. The recipient calls `SealedBox.open(privateKey, sealed,
associatedData)`. Both calls suspend, because the browser's WebCrypto has only asynchronous
operations.

The associated data is authenticated but not encrypted. Use it for whatever the box must stay
attached to, such as a report id or a format tag, and pass the same bytes to `open`.

Because the public key's bytes are stable, an app can compile its production key in as a constant
and check at startup that it is not running with a test key.

## What it leaves to you

- **Length.** Nothing is compressed, deliberately: compression makes the length depend on the
  content. If the length of what you seal is itself a secret, pad it to a fixed size first. A box
  is always `plaintext.size + SealedBox.OVERHEAD` bytes.
- **Key storage.** The module reads a private key but never writes one. Where it lives, and who
  may read it, is up to the application.

## Underneath

P-256 ECDH, HKDF-SHA-256 and AES-256-GCM, the combination every target's platform cryptography
supports. The primitives come from [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin),
which wraps each platform's own implementation: JCA on JVM and Android, CryptoKit on Apple, WebCrypto
in the browser. This module only composes them. The byte format is in `SealedBox`'s API docs.
