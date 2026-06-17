# Pluggable fabric kit — minimal-custom `Seam` over any RPC

**Status:** design approved, pre-implementation
**Date:** 2026-06-14
**Primary goal:** a `:kuilt-tcp` fabric whose worked example shows that adapting a
proprietary point-to-point RPC into a first-class kuilt fabric is on the order of
**30 lines**. Everything else in this spec is enabling infrastructure that exists
to make that example minimal.

## Motivation

Today every fabric (`:kuilt-websocket`, `:kuilt-multipeer`, …) hand-rolls the same
machinery against its own transport type: a receive loop, lifecycle/`SeamState`
bookkeeping, sequence stamping, peer-set management, write serialization. The
`WebSocketSeam` receive loop (`kuilt-websocket/.../WebSocketSeam.kt:94`) and the
in-memory reference impl duplicate the same shape, and lifecycle bugs recur in that
duplicated surface (e.g. the close-reason clobber, #351).

A consumer with an in-house RPC who wants kuilt's CRDT / Raft / session stack over
their own transport must currently re-derive all of it. The goal is to reduce the
implementer's job to: **supply a point-to-point connection (and, for stream
transports, a raw byte duplex); kuilt provides identity, framing, lifecycle,
serialization, roster, and dedup.**

## Contract recap (unchanged)

The `Seam`/`Loom`/`Swatch` contract is untouched. `Seam` is already N-peer
(`peers: StateFlow<Set<PeerId>>`, `broadcast` to all, `sendTo(peer)` to one); the
2-peer case is the degenerate `peers.size == 2`. This kit adds reusable *implementations*
beneath the contract — no contract change.

## Wire model

**Every link is point-to-point.** The first frame on a link is a one-shot
`Hello(senderPeerId)` preamble; every frame after it is a raw application payload.
There is **no per-frame discriminator** — `Hello` is a preamble, not an interleaved
frame type.

- `identified` links skip the preamble entirely (identity is known out-of-band) and
  are therefore **byte-transparent** — zero per-frame and zero per-link overhead.
- `handshaking` links send/await the preamble once at connect, then are byte-transparent.

Relay/multiplexed links (one `Connection` carrying frames from many peers) are **out of
scope**; that is the existing `core/composite/` + `PlyFrame` machinery
(`CompositeSeam.kt`). `MeshSeam` here is point-to-point dial-mesh only.

## Components

### `Connection` — the message SPI (`:kuilt-core`)

The interface a message-oriented transport (WebSocket, gRPC bidi stream, Multipeer,
Nearby) implements directly, because it already has message boundaries.

```kotlin
public interface Connection {
    public suspend fun send(frame: ByteArray)   // one whole message
    public val incoming: Flow<ByteArray>        // whole messages, single-collection
    public suspend fun close()
}
```

`send` is a `suspend fun`, the dual of the inbound `Flow`: inbound pushes (transport
produces), outbound is driven (Seam produces). It carries transport backpressure
natively. It is **not** a `StateFlow` (conflating/lossy — wrong for a message stream)
and not a `Channel` on the SPI surface (would push capacity/drain ownership onto the
implementer).

### `identified` — the primitive 2-peer Seam (`:kuilt-core`)

```kotlin
public fun identified(connection: Connection, selfId: PeerId, remoteId: PeerId): Seam
```

A byte-transparent 2-peer `Seam` over a `Connection` where both identities are known.
Owns: the receive loop, `SeamState` lifecycle (`Woven` at construction; `Torn` on
`connection` EOF/error or local `close`), receiver-local sequence stamping, the
`peers = {selfId, remoteId}` set, and **write serialization** — an internal
`Channel<ByteArray>` drained by a single writer coroutine, so concurrent
`broadcast`/`sendTo` from the app produce ordered frames on the wire.

`broadcast` == `sendTo(remoteId)` in the 2-peer case. This is `WebSocketSeam` with
Ktor lifted out (~80 lines).

### `handshaking` — wraps `identified` (`:kuilt-core`)

```kotlin
public suspend fun handshaking(connection: Connection, selfId: PeerId): Seam {
    connection.send(Hello(selfId).encode())                       // send preamble
    val remoteId = Hello.decode(connection.firstFrame())          // await peer preamble
    return identified(connection.afterPreamble(), selfId, remoteId)  // delegate
}
```

For transports that do **not** carry identity out-of-band (raw TCP, gRPC without
metadata). It performs the preamble exchange, learns `remoteId`, then **literally
delegates to `identified`** over the post-preamble connection. The only addition over
`identified` is one preamble frame each direction at connect; `state` stays `Weaving`
until the peer's `Hello` arrives, then `Woven`.

Available to any fabric that wants in-band identity — e.g. WebSocket's `anon-<uuid>`
placeholder path (`KtorServerLoom.kt:52`) could opt in to learn the client's real id.

### `framed()` — the stream SPI (`:kuilt-stream`, separate module)

For stream-oriented transports (TCP, Unix socket, QUIC) that provide a raw byte
duplex with no message boundaries. Adapts a `kotlinx-io` `Source`/`Sink` pair into a
`Connection` via a length-prefix codec:

```kotlin
public fun framed(source: Source, sink: Sink, maxFrameSize: Int = DEFAULT_MAX): Connection
```

Pull-based (`Source`) because framing is a pull-shaped problem: read the length
prefix, then read exactly that many bytes — no reassembly buffer, correct by
construction. The codec owns everything error-prone:
- length-prefix encode/decode;
- **reassembly across read boundaries** (a read may yield half a frame or several);
- a **`maxFrameSize` cap** — reject the prefix before allocating (OOM-DoS guard);
- **partial-frame-at-EOF** → loud error, never silent truncation.

`framed()` returns a `Connection` (kuilt's own type), so the `kotlinx-io` dependency is an
*input convenience* on the stream path, never part of the contract surface. It is
fenced to `:kuilt-stream` so `:kuilt-core` stays dependency-pure (coroutines +
serialization only) and message-transport implementers never see it.

### `MeshSeam` — N-peer cluster (`:kuilt-core`)

```kotlin
public fun meshSeam(
    selfId: PeerId,
    dialer: suspend (address) -> Connection,   // obtain a link to a seed peer
    acceptor: Flow<Connection>,                // inbound links
    seeds: Set<address>,
    dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
): Seam
```

Holds a `Map<PeerId, Connection>` of point-to-point links. Each dialed or accepted `Connection`
runs the `Hello` preamble to learn its remote id, then behaves as an identified link.
The mesh adds:
- **roster** — `peers` derived from connected links' learned ids, reactive to link
  up/down;
- **fan-out** — `broadcast` writes to all links; `sendTo` looks up the one link;
- **dedup** — two links to the same peer (simultaneous dial) resolved
  lower-`PeerId`-wins, the loser closed;
- membership model: **dial seeds + accept inbound + dedup**. A late joiner that knows
  the cluster's addresses works (dials in, everyone accepts). **Transitive gossip is
  out of scope** for v1 — named explicitly as a non-goal.

`broadcast` is **best-effort to currently-connected peers**; a peer mid-dial misses
it, and healing (CRDT FullState resync / Raft) is the upper layer's job — no
buffering for not-yet-connected peers.

## Cross-cutting decisions

- **Concurrency.** All Seam-internal state access is confined to a single-thread
  dispatcher (`Dispatchers.Default.limitedParallelism(1)`), injectable as
  `UnconfinedTestDispatcher(testScheduler)` for tests — per the `CompositeSeam`
  precedent and `docs/testing-coroutine-determinism.md`.
- **Errors — fail loud.** `connection.send` throw / read EOF: `identified`/`handshaking`
  → `Torn`; `MeshSeam` → drop that one peer, Seam stays live. No swallowed exceptions;
  `CancellationException` always rethrown.
- **Conformance.** `identified` and `handshaking` pass the existing 2-peer
  `SeamConformanceSuite` (run in both modes). `MeshSeam` gets a new
  `MeshConformanceSuite`: broadcast reaches all, `sendTo` routes, `peers` reflects
  join *and* leave, dedup holds under simultaneous dial.

## Module layout

| Module | Adds |
|--------|------|
| `:kuilt-core` | `Connection`, `Hello`, `identified`, `handshaking`, `MeshSeam`, `MeshConformanceSuite` |
| `:kuilt-stream` *(new)* | `framed()` + length-prefix codec over `kotlinx-io` |
| `:kuilt-tcp` *(new)* | TCP `Loom` (dial/accept) + the proprietary-RPC worked example |
| `:kuilt-websocket` | *(optional)* refactor `WebSocketSeam` onto `identified` |

## Scope

**In scope (v1):** `Connection`, `identified`, `handshaking`, `framed()`, `:kuilt-tcp`
fabric + example (the primary goal). `MeshSeam` as an explicit second phase.

**Out of scope:** transitive membership gossip; reconnect/link-flap policy (`Torn`
is terminal — reconnect is the `:kuilt-session` resume-token layer's job, possibly a
`ReconnectingConnection` wrapper later); relay/multiplexed links (use `core/composite/`);
NAT traversal.

## Epic decomposition

North star is the `:kuilt-tcp` example; slices 1–4 are the critical path to it.

| # | Slice | Module | Depends on |
|---|-------|--------|-----------|
| **0** | **Planning** — land this spec + impl plan (design PR; `Closes #<this sub-issue>`) | docs | — |
| 1 | `Connection` SPI + `identified`; passes 2-peer `SeamConformanceSuite` | `:kuilt-core` | foundation |
| 2 | `Hello` preamble + `handshaking` (wraps #1) | `:kuilt-core` | 1 |
| 3 | `framed()` over `kotlinx-io` (reassembly, size-cap, EOF) | `:kuilt-stream` | 1 |
| 4 | **`:kuilt-tcp` fabric + proprietary-RPC example/docs** — headline | `:kuilt-tcp` | 2, 3 |
| 5 | `MeshSeam` + `MeshConformanceSuite` + TCP cluster example (phase 2) | `:kuilt-core` (+tcp) | 2 |
| 6 | *(optional)* refactor `:kuilt-websocket` onto `identified` | `:kuilt-websocket` | 1 |

**Ordering.** Slice 1 lands first (harden the foundation), then 2 and 3 in parallel,
then 4 (the deliverable). Slice 5 (mesh) is the cluster follow-on and must not gate
the headline. Slice 6 is orthogonal and droppable.

**Closing keywords** go on sub-issue #0 only; the epic is referenced with non-closing
language ("Part of #<epic>") everywhere.

## Acceptance — primary goal

A new reader can implement a kuilt fabric over a hypothetical proprietary
point-to-point RPC by:
1. implementing `Connection` (message RPC) **or** providing a `kotlinx-io` `Source`/`Sink`
   and calling `framed()` (stream RPC);
2. writing a `Loom` that dials/accepts and wraps each connection in `handshaking`;

…and the `:kuilt-tcp` module is that example, verifiably passing
`SeamConformanceSuite`, in ~30 lines of transport-specific code.
