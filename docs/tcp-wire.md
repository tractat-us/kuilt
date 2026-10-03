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

- All integers are **unsigned 32-bit big-endian** (network byte order), written
  `u32`.
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

There is no other header, no checksum, no type byte and no padding. A body may
be empty: `00000000` is a complete frame with zero bytes of body.

**Maximum frame size.** Each receiver has a maximum body length. In kuilt it is
`DEFAULT_MAX_FRAME_SIZE`, **16 MiB = 16,777,216 bytes**, inclusive: a body of
exactly 16,777,216 bytes is legal. kuilt's `TcpLoom` always uses the default.
The limit is not negotiated, so:

- A sender MUST NOT send a body longer than 16,777,216 bytes.
- A receiver MUST refuse a length prefix over its maximum **before allocating
  space for the body**. Read the prefix as unsigned. `80000000` and `ffffffff`
  are over the limit, not negative.

## 2. The Hello

The first frame each peer sends is its **Hello**. Its body names the peer:

| Bytes | Field | Value |
|---|---|---|
| 4 | magic | `6b 75 69 6c` (ASCII `kuil`) |
| 1 | version | `01` |
| 4 | `idLen` | `u32`, the id's length **in bytes** |
| `idLen` | id | the peer id, UTF-8 |

The first three fields form a **9-byte header**. A Hello is always exactly
`9 + idLen` bytes. The fixture's `helloLayout` lists these fields in order, and
the test composes every Hello vector from that table. A future change to the
header's shape therefore edits the table, the hex, and nothing else.

The id is any non-empty string, encoded as UTF-8. `idLen` counts bytes, not
characters: the four-character id `Zoë🧵` is eight bytes long. The id is
compared byte for byte. kuilt applies no Unicode normalisation, so two
spellings of the same accented letter are two different peers.

For the id `alice`, the Hello body and its full frame are:

```
body   6b75696c 01 00000005 616c696365
frame  0000000e 6b75696c 01 00000005 616c696365
```

### Checking a received Hello

A receiver checks a Hello body in this order. It stops at the first failure and
refuses the Hello with the error named in that row. The order is part of the
contract, because one malformed body can fail several checks at once.

| # | Check | On failure | kuilt throws |
|---|---|---|---|
| 1 | Every byte present among the first four equals the magic. Compare only the bytes that arrived. | foreign peer | `HelloBadMagicException` |
| 2 | The body has at least 5 bytes (magic and version). | truncated | `HelloTruncatedException` |
| 3 | The version byte is `01`. | unsupported version | `HelloUnsupportedVersionException` |
| 4 | The body has at least 9 bytes (the whole header). | truncated | `HelloTruncatedException` |
| 5 | `idLen`, read unsigned, equals the body length minus 9. Bytes beyond the id are an error too. | length mismatch | `HelloIdLengthMismatchException` |
| 6 | `idLen` is not zero. | empty id | `HelloEmptyIdException` |
| 7 | The id bytes are valid UTF-8, strictly. An invalid sequence, an overlong form or an encoded surrogate fails. Never substitute U+FFFD. | invalid UTF-8 | `HelloInvalidUtf8Exception` |

All six kuilt exceptions extend `HelloFormatException`, an
`IllegalArgumentException`. Check 1 is why the pre-v1 greeting is refused. That
greeting was the bare UTF-8 id, such as `616c696365` for `alice`, and it fails
the magic test. A pre-v1 id that happens to begin with `kuil` passes check 1 and
is refused at check 3, because its fifth byte is a letter and not `01`.

Strict UTF-8 matters because ids are identities. If two different byte strings
could decode to one id, two peers could share a name.

## 3. The handshake

Each peer, as soon as the TCP connection is open:

1. **MUST send its own Hello as its first frame, without waiting** for anything
   from the other side. A peer that waits for the other's Hello first will
   deadlock against another peer doing the same.
2. Reads the other side's first frame and checks it as a Hello (section 2).
3. **MUST refuse a Hello whose id equals its own**: it has connected to itself.
   kuilt throws an `IllegalArgumentException` whose message contains
   `self-connection`.
4. Once the other Hello is accepted, the connection is established. Every later
   frame in either direction is a payload.

A peer MUST NOT send a payload before it has accepted the other side's Hello.
kuilt never does. A peer MUST still accept payload frames that arrive right
behind the other side's Hello, in the same TCP segment.

There is no acknowledgement and no third message. Each side learns the other's
name from one frame, and that is the whole handshake.

## 4. Payloads

After the handshake, **each frame body is one message, verbatim**. kuilt adds no
envelope, type byte, sequence number or sender field. The sender is the peer at
the other end of the socket. Message boundaries are frame boundaries, and an
empty message is an empty frame. Frames are delivered in order, because TCP
delivers them in order.

## 5. Closing

There is **no close frame**. A peer ends the session by closing its side of the
TCP connection after the last byte of a complete frame.

- **EOF at a frame boundary** is a clean close. Every complete frame before it
  is delivered.
- **EOF inside a frame body** is an error: the stream was cut. The partial body
  MUST NOT be delivered. kuilt raises an `EOFException`.
- **EOF inside a length prefix**, after one to three bytes of the four, MUST NOT
  deliver anything for the partial prefix. Version 1 lets the receiver report it
  either as an error or as a clean close. kuilt reports a clean close. A sender
  never produces this case by closing normally.
- **EOF before any Hello** fails the handshake. kuilt surfaces it as a
  `NoSuchElementException`.

There is **no heartbeat** at this layer. If a peer needs to notice a silent
partner, it does so above the wire, or with TCP keep-alive.

## 6. Refusals

When a peer refuses the other side for any reason above, it MUST stop: it
delivers nothing further and closes the TCP connection. It sends no error frame,
because the wire has none. The other side sees a close.

| Refusal | Where | Detected by |
|---|---|---|
| frame too large | any frame | length prefix over the maximum |
| truncated frame | any frame | EOF inside a body |
| foreign peer | first frame | Hello check 1 |
| truncated Hello | first frame | Hello checks 2 and 4 |
| unsupported version | first frame | Hello check 3 |
| length mismatch | first frame | Hello check 5 |
| empty id | first frame | Hello check 6 |
| invalid UTF-8 | first frame | Hello check 7 |
| self-connection | first frame | handshake step 3 |
| no Hello | before the first frame | EOF |

**Known gap in kuilt.** kuilt refuses each case above and delivers nothing
further. But it does not yet close the socket itself. A refused handshake throws
and leaves the connection open, with no seam for anyone to close. A read error
tears the seam down without closing it, so the socket stays open until the
application closes the seam. This gap is tracked by #2898. Until it is fixed, a peer talking to kuilt can see a
silent, open connection where this contract promises a close.

## 7. Versions

This is version 1, carried in the Hello's version byte.

- A version 1 peer sends `01` and MUST refuse every other version. That includes
  `00` and anything higher.
- There is **no negotiation** in version 1. A peer cannot offer several versions
  or fall back to an older one. Negotiation is future work, and a later version
  can add it, because the magic and version come before anything else.
- A future version that changes the Hello or the framing will use a new version
  number. Version 1 will never change: the golden vectors pin it.

## 8. The golden vectors

[`tcp-wire-v1.vectors.json`](../kuilt-stream/wire/tcp-wire-v1.vectors.json) is
plain JSON. Byte strings are lowercase hex, and `note` fields are commentary for
humans. An implementation can run every section as a table test.

| Section | Each entry gives | A conforming implementation |
|---|---|---|
| `helloLayout` | one Hello field, in order | composes each `hello` body from it |
| `hello` | `peerId`, `body`, `frame` | encodes `peerId` to exactly `body` and `frame`, and decodes `frame` back to `peerId` |
| `frames` | `payload`, `frame` | frames `payload` as exactly `frame`, and reads `frame` back as `payload` |
| `helloRefusals` | a Hello `body` and its `refusal` | refuses `body` with that refusal |
| `streams` | raw `bytes`, the `frames` delivered, and the `end` | delivers exactly `frames`, then ends as `end` |
| `handshake` | sides `a` and `b`: `peerId`, payloads `sends`, and `bytes` written | given the other side's `bytes` as input, writes exactly its own `bytes` and receives the other's `sends` |
| `handshakeRefusals` | `selfId`, the `received` bytes, and the `refusal` | fails the handshake with that refusal |

The `refusal` names map to section 2: `bad-magic`, `truncated`,
`unsupported-version`, `id-length-mismatch`, `empty-id`, `invalid-utf8`. The
handshake adds `self-connection` and `no-hello`. Stream `end` values are
`clean-close`, `truncated-frame`, `frame-too-large`, and `truncated-prefix`,
where either reaction is allowed (section 5).

kuilt keeps an exact copy of the file in its test sources, and a JVM test fails
if the two ever differ. Edit the JSON, then paste it into
`TcpWireV1Vectors.kt` whole.
