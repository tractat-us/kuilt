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

What it does not promise: **a box proves nothing about who sealed it.** Sealing needs only the
public key, and an app that compiles that key in hands it to every user. Opening a box shows it was
sealed to your key and not changed since, not who sent it. Check the sender some other way where
that matters.

## Using it

<!-- verbatim from kuilt-seal/src/commonSamples/kotlin/us/tractat/kuilt/seal/SealSamples.kt#sampleSealedBox -->
```kotlin
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
```

`encodePkcs8` writes standard PKCS #8 that carries the public key, on every platform, so a key
written on one reads back on any other. A key made with OpenSSL reads back too. `decodePkcs8`
refuses a file whose public key does not belong to its private key, including one carrying the
key's negation: the private half signs and the public half must verify, since a key agreement
cannot tell a point from its negation. Every call suspends, because
the browser's WebCrypto has only asynchronous operations.

The associated data is authenticated but not encrypted. Use it for whatever the box must stay
attached to, such as a report id or a format tag, and pass the same bytes to `open`.

Because the public key's bytes are stable, an app can compile its production key in as a constant
and check at startup that it is not running with a test key.

## What it leaves to you

- **Length.** Nothing is compressed, deliberately: compression makes the length depend on the
  content. If the length of what you seal is itself a secret, pad it to a fixed size first. A box
  is always `plaintext.size + SealedBox.OVERHEAD` bytes.
- **Key storage.** The module encodes a private key; where those bytes live, and who may read
  them, is up to the application.

## Underneath

P-256 ECDH, HKDF-SHA-256 and AES-256-GCM, the combination every target's platform cryptography
supports. The primitives come from [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin),
which wraps each platform's own implementation: JCA on JVM and Android, CryptoKit on Apple, WebCrypto
in the browser. This module only composes them. The byte format is in `SealedBox`'s API docs.
