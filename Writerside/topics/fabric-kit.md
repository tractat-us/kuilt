# Fabric kit

Most users should start with packaged fabrics and never touch this page. Reach
for the **fabric kit** when you already have raw peer links and want to expose
them as a standard kuilt `Seam` without reinventing session semantics.

It is the layer *below* packaged `Loom`s ([WebSocket, mDNS, Near
fabrics](fabrics.md)): a small set of `kuilt-core` primitives that assemble a
`Seam` from point-to-point links.

Three pieces, two of them topology builders:

- **`Connection`** — the SPI one duplex link implements.
- **`identified()`** — one `Connection` → a 2-peer `Seam`.
- **`meshSeam()`** — N `Connection`s → one fully-connected N-peer `Seam`.

See [Composing a Seam](composing-a-seam.md) for how these sit alongside
`CompositeLoom` (multipath) and `MuxSeam` (channel splitting) on the
`Connection`↔`Seam` axis.

## `Connection` — the point-to-point SPI

A `Connection` is a duplex, message-framed link between **exactly two** peers. It is
the minimal contract a message transport (WebSocket, a gRPC bidi stream,
Multipeer, Nearby) implements to become a kuilt fabric — it is **not** a `Seam`
itself:

```kotlin
```
{ src="../../kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/Connection.kt" include-symbol="Connection" }

Each frame is a whole message; the link preserves frame boundaries and FIFO
order. Stream transports (TCP) don't implement `Connection` directly — they expose a
kotlinx-io `Source`/`Sink` and use `framed()` to obtain one. Writing a `Connection`
for your own transport is the subject of the implementer tutorial
(`docs/extending-fabrics.md` in the repository); this page is about *consuming*
the kit once you have `Connection`s in hand.

### Stream transports: `:kuilt-stream` and `:kuilt-tcp`

**`:kuilt-stream`** provides `framed(source, sink)`, which adapts a kotlinx-io
`Source`/`Sink` byte-stream into a `Connection` using a 4-byte big-endian length
prefix per frame. Oversize prefixes throw `FrameTooLargeException` before any
allocation; a clean EOF at a frame boundary completes `incoming` normally.

**`:kuilt-tcp`** (`TcpLoom.host` / `TcpLoom.join`, JVM/Android only) is the worked
example: it wires a Ktor socket's channels through `framed()` into a `Connection`,
then hands that to `handshaking` for in-band identity negotiation, yielding a
2-peer `Seam`. The pattern — obtain a socket, call `framed()`, call `handshaking` —
is everything a stream transport needs to become a kuilt fabric.

## `identified()` — a 2-peer link

When you already know **both** identities on a single link, `identified()`
presents it as a 2-peer `Seam`. There is no handshake and no discovery — you
supply `selfId` and `remoteId` directly. The result is `Woven` at construction,
`broadcast` is the same as `sendTo(remoteId)`, and it goes `Torn` on the connection's
EOF/error or on `close()`:

```kotlin
val seam: Seam = identified(connection, selfId = me, remoteId = peer, dispatcher)
```

The `dispatcher` is **required** — it is the scope for the seam's read/write
loops, never a substitute for mutual exclusion. Production passes a real
dispatcher; tests pass one derived from the test scheduler. Woven-at-construction
and delivery from the remote, proven by the contract test:

```kotlin
```
{ src="../../kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/fabric/LinkSeamTest.kt" include-symbol="wovenAtConstructionAndDeliversFromRemote" }

(`connectionPair()` is a test helper that returns the two ends of an in-memory `Connection`;
production supplies a real link.)

## `meshSeam()` — an N-peer mesh

Given one `Connection` to each prospective peer, `meshSeam()` weaves them into a single
fully-connected N-peer `Seam`. Unlike `identified()` it does **not** need you to
name the remotes: it exchanges a short preamble on each link to learn the remote
`PeerId`, and dedups duplicate links from a simultaneous dial by a canonical,
order-independent nonce both ends agree on — so two peers that dial each other at
the same moment converge on the same single link with no coordination.

```kotlin
val mesh: Mesh = meshSeam(selfId = me, connections = listOf(connectionToB, connectionToC), dispatcher)
```

`meshSeam()` suspends until every handshake completes, then returns a `Mesh` (a
`Seam` plus `addLink`). Each peer's roster includes itself and every reachable
remote:

```kotlin
```
{ src="../../kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/MeshConformanceSuite.kt" include-symbol="eachPeerSeesMeshSize" }

A `broadcast` from any peer reaches every other peer, stamped with the
broadcaster as `sender`:

```kotlin
```
{ src="../../kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/MeshConformanceSuite.kt" include-symbol="broadcastReachesAllPeers" }

(`newMeshOfSize(n)` is the conformance harness that builds an `n`-peer in-memory
mesh and returns one `Seam` per peer. Any mesh binding proves itself by
subclassing `MeshConformanceSuite`.)

A peer that errors or disconnects is dropped from `peers` and the mesh keeps
running — it stays `Woven` until you call `close()`. A peer that dials in *after*
construction is admitted live with `Mesh.addLink(connection)`, which runs the same
preamble exchange and dedup before adding it to the roster.

## Beyond two topologies

- To bond several **finished** `Seam`s for one peer-set into a single multipath
  `Seam`, see [Multipath](multipath.md) — that's the `Seam → Seam` direction, the
  mirror of the mesh builder.
- To prove your own `Connection`/`Loom` against the contract, subclass
  `SeamConformanceSuite` (or `MeshConformanceSuite` for a mesh binding) — see
  [Connections](fabrics.md#writing-your-own-fabric).
