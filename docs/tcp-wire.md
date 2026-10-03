# The TCP wire, byte by byte

> **In one sentence.** Two programs connect over a plain TCP socket, each says
> who it is, and then they pass messages back and forth. This page lists every
> byte they send, so a program written in any language can join in.

kuilt's TCP fabric sends very little. Each message goes out with its length in
front. The first message from each side is a short greeting carrying that side's
name. Nothing else is on the wire: no headers, no keep-alive, no goodbye. Can
another language talk to kuilt over TCP? Yes, and the rest of this page is the
recipe.

The rest is a contract for implementers. It is exact at the byte level and is
backed by golden vectors that kuilt's own build checks.

| | |
|---|---|
| **Wire version** | 1 |
| **Golden vectors** | [`kuilt-stream/wire/tcp-wire-v1.vectors.json`](../kuilt-stream/wire/tcp-wire-v1.vectors.json) |
| **Drift test** | `TcpWireVectorsTest` in `:kuilt-stream`, run by `./gradlew check` on every target |
| **kuilt code** | `framed()` in `:kuilt-stream`, then `Hello` and `handshaking()` in `:kuilt-core`; `TcpLoom` in `:kuilt-tcp` joins the two |

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## Conventions

- Integers are **unsigned and big-endian** (network byte order): `u8`, `u16`
  and `u32` are 1, 2 and 4 bytes wide.
- Byte strings are written in lowercase hex, two digits per byte.
- A *peer* is one end of the connection. The two peers are symmetric. Whichever
  side dialled and whichever side accepted, both follow exactly the same rules.

## 1. Frames

The TCP byte stream is a sequence of **frames**. A frame is a length and then
that many bytes:

| Bytes | Field |
|---|---|
| 4 | `len`, `u32` |
| `len` | the frame body |

There is no other header, no checksum, no type byte and no padding. A data
frame's body may be empty: `00000000` is a complete frame with zero bytes of
body. (A Hello may not be empty; see section 2.)

**The frame ceiling.** The frame ceiling is local receive policy, not negotiated
and not on the wire. The default is 16 MiB. A receiver MUST refuse a length above
its ceiling before allocating, then close. A sender cannot learn the peer's
ceiling, so a frame that fits the sender's ceiling but not the receiver's shows
the sender success followed by a torn seam.

kuilt's default is `DEFAULT_MAX_FRAME_SIZE`, 16,777,216 bytes, inclusive: a body
of exactly that length is accepted. `TcpLoom` always uses the default. Read the
length prefix as unsigned, so `80000000` and `ffffffff` are over the ceiling,
not negative.

## 2. The Hello

The first frame each peer sends is its **Hello**. Its body names the peer:

| Bytes | Field | Value |
|---|---|---|
| 4 | magic | `6b 75 69 6c` (ASCII `kuil`) |
| 1 | version | `u8`, `01` |
| 2 | flags | `u16`, `0000` from a v1 sender |
| 2 | `idLen` | `u16`, the id's length **in bytes**, 1 to 65,535 |
| `idLen` | id | the peer id, UTF-8 |

The first four fields form a **9-byte header**, so a Hello is always exactly
`9 + idLen` bytes. The fixture's `helloLayout` lists these fields in order, and
the drift test composes every Hello vector from that table.

**Flags.** A v1 sender MUST send `0000`. A receiver MUST ignore bits it does not
know, so a Hello with flags `8000` or `ffff` is accepted. Flags are how a later
version will add optional capabilities. A capability is on only when **both**
Hellos advertise it, so a peer that ignores a bit never has that capability
switched on.

**The id.** The id is a non-empty, well-formed UTF-8 string, as Unicode §3.9
defines it. `idLen` counts bytes, not characters: the four-character id `Zoë🧵`
is eight bytes long. **Identity is byte equality.** Nothing is normalised, so two
ids that differ only in Unicode normalisation form are two different peers. A
string holding a lone surrogate has no UTF-8 form at all. Encoding one is the
sender's error, and kuilt refuses to send it.

For the id `alice`, the vector reads:

<!-- verbatim from kuilt-stream/src/commonTest/kotlin/us/tractat/kuilt/stream/TcpWireV1Vectors.kt -->
```json
    {
      "name": "hello-ascii",
      "note": "PeerId 'alice'. Body = magic 6b75696c, version 01, flags 0000, idLen 0005, id.",
      "peerId": "alice",
      "body": "6b75696c0100000005616c696365",
      "frame": "0000000e6b75696c0100000005616c696365"
    },
```

### Checking a received Hello

A receiver checks a Hello body in this order. It stops at the first failure and
refuses the Hello with the error named in that row. The order is part of the
contract, because one malformed body can fail several checks at once.

| # | Check | Refusal | kuilt throws |
|---|---|---|---|
| 1 | Every byte present among the first four equals the magic. Compare only the bytes that arrived. | `bad-magic` | `HelloBadMagicException` |
| 2 | The body has at least 5 bytes (magic and version). | `truncated` | `HelloTruncatedException` |
| 3 | The version byte is `01`. | `unsupported-version` | `HelloUnsupportedVersionException` |
| 4 | The body has at least 9 bytes (the whole header). | `truncated` | `HelloTruncatedException` |
| 5 | `idLen` equals the body length minus 9. Bytes beyond the id are an error too. | `id-length-mismatch` | `HelloIdLengthMismatchException` |
| 6 | `idLen` is not zero. | `empty-id` | `HelloEmptyIdException` |
| 7 | The id bytes are well-formed UTF-8. An overlong form, an encoded surrogate, a code point past U+10FFFF, a stray continuation byte and a cut-off sequence all fail. Never substitute U+FFFD. | `invalid-utf8` | `HelloInvalidUtf8Exception` |

The flags field is never checked. Check 1 is why the pre-v1 greeting is refused.
That greeting was the bare UTF-8 id, such as `616c696365` for `alice`, and it
fails the magic test. A pre-v1 id that happens to begin with `kuil` passes check 1
and is refused at check 3, because its fifth byte is a letter and not `01`.

Strict UTF-8 matters because ids are identities. If two different byte strings
could decode to one id, two peers could share a name.

## 3. The handshake

Each peer, as soon as the TCP connection is open:

1. **MUST send its own Hello immediately, before reading anything.** If both
   sides read first, each waits for the other forever. Sending first is always
   safe, because a Hello is at most 9 + 65,535 bytes. That fits in a socket's
   send buffer, so both sides can write theirs without either one reading. This
   is what the `u16` id cap buys.
2. Reads the other side's first frame and checks it as a Hello (section 2).
3. **MUST refuse a Hello whose id equals its own.** That is a self-connection:
   the peer has dialled itself. Both ends refuse it. It is not a format error,
   since the Hello itself is valid.
4. Once the other side's Hello is accepted, the connection is established, and
   every later frame in either direction is a data frame.

A peer **MAY** send data frames right after its own Hello, without waiting for
the other side's. The receiver **MUST** accept them. kuilt itself waits for the
other Hello before it sends data, but it accepts data that arrives right behind
a Hello.

A connection that ends before a Hello arrives is a refused handshake.

There is no acknowledgement and no third message. Each side learns the other's
name from one frame, and that is the whole handshake.

## 4. Data frames

After the handshake, **each frame body is one message, verbatim**. kuilt adds no
envelope, type byte, sequence number or sender field. The sender is the peer at
the other end of the socket. Message boundaries are frame boundaries, and an
empty message is an empty frame. Frames are delivered in order, because TCP
delivers them in order.

## 5. Closing

A peer ends the session by closing its side of the TCP connection (FIN) right
after the last byte of a complete frame. Version 1 has **no goodbye message and
no heartbeat**. A future capability bit, typed control frames, would add them.
Until then, a peer that needs to notice a silent partner does so above the wire,
or with TCP keep-alive.

- **EOF at a frame boundary** is a clean close. Every complete frame before it
  is delivered.
- **EOF inside a frame body** is an error: the stream was cut. The partial body
  MUST NOT be delivered. kuilt raises an `EOFException`.
- **EOF inside a length prefix**, after one to three bytes of the four, MUST NOT
  deliver anything for the partial prefix. Version 1 lets the receiver report it
  either as an error or as a clean close. kuilt reports a clean close. A sender
  never produces this case by closing normally.
- **EOF before any Hello** is a refused handshake (section 6).

## 6. Refusals

On any refusal, a peer closes the transport and sends nothing more. There is no
error frame, so the other side sees a close.

| Refusal | Where | Detected by | kuilt throws |
|---|---|---|---|
| frame too large | any frame | length over the ceiling | `FrameTooLargeException` |
| truncated frame | any frame | EOF inside a body | `EOFException` |
| a malformed Hello | first frame | Hello checks 1 to 7 | a `HelloFormatException` subclass |
| self-connection | first frame | handshake step 3 | `HelloSelfConnectionException` |
| absent Hello | before the first frame | EOF | `HelloAbsentException`, also a `HelloFormatException` |

kuilt's `handshaking()` closes the connection on every handshake refusal. After
the handshake, kuilt does not yet close the socket itself. An oversize length or
a cut body tears the seam down, but the socket stays open until the application
closes the seam. This gap is tracked by #2898. Until it is fixed, a peer talking
to kuilt can see a silent, open connection where this contract promises a close.

## 7. Versions

This is version 1, carried in the Hello's version byte.

- A version 1 peer sends `01` and MUST refuse every other version. That includes
  `00` and anything higher.
- There is **no negotiation** in version 1. A peer cannot offer several versions
  or fall back to an older one. Negotiation is future work, and a later version
  can add it, because the magic and version come before anything else.
- Optional additions that an older peer can safely ignore arrive as flag bits,
  not as a new version (section 2).
- Version 1 will never change: the golden vectors pin it.

## 8. The golden vectors

[`tcp-wire-v1.vectors.json`](../kuilt-stream/wire/tcp-wire-v1.vectors.json) is
plain JSON. Byte strings are lowercase hex, and `note` fields are commentary for
humans. An implementation can run every section as a table test.

| Section | Each entry gives | A conforming implementation |
|---|---|---|
| `helloLayout` | one Hello field, in order | composes each `hello` body from it |
| `hello` | `peerId`, `body`, `frame` | encodes `peerId` to exactly `body` and `frame`, and decodes `frame` back to `peerId` |
| `helloAccepts` | a Hello `body` kuilt never sends, and its `peerId` | decodes `body` to `peerId` |
| `frames` | `payload`, `frame` | frames `payload` as exactly `frame`, and reads `frame` back as `payload` |
| `helloRefusals` | a Hello `body` and its `refusal` | refuses `body` with that refusal |
| `streams` | raw `bytes`, the `frames` delivered, and the `end` | delivers exactly `frames`, then ends as `end` |
| `handshake` | sides `a` and `b`: `peerId`, payloads `sends`, and `bytes` written | given the other side's `bytes` as input, writes exactly its own `bytes` and receives the other's `sends` |
| `handshakeRefusals` | `selfId`, the `received` bytes, and the `refusal` | refuses the handshake with that refusal, and closes |

The `refusal` names in `helloRefusals` are the ones in section 2's table. The
handshake adds `self-connection` and `absent`. Stream `end` values are
`clean-close`, `truncated-frame`, `frame-too-large`, and `truncated-prefix`,
where either reaction is allowed (section 5).

kuilt keeps an exact copy of the file in its test sources, and a JVM test fails
if the two ever differ. Edit the JSON, then paste it into
`TcpWireV1Vectors.kt` whole.
