# Module kuilt-core

The kuilt contract: the types every fabric and consumer depends on.

## The contract at a glance

`Loom` is a factory — `host(Pattern)` opens a new session, `join(Tag)` joins an
existing one. Both return a `Seam`.

`Seam` is one peer's symmetric view of a session. There is no client or server:
a two-peer connection is the degenerate `peers.size == 2` case. Key properties:

- `peers: StateFlow<Set<PeerId>>` — the live peer set, including self.
- `state: StateFlow<SeamState>` — `Weaving`, `Woven`, or `Torn`.
- `incoming: Flow<Swatch>` — **collect once per Seam**. Fan out with `shareIn`.
- `broadcast(ByteArray)` / `sendTo(PeerId, ByteArray)` — send frames.
- `close(CloseReason)` — disconnect; idempotent.

`Swatch` is the opaque binary frame. The transport stamps `sender` and `sequence`
on receipt; senders leave them unset.

`InMemoryLoom` is the reference implementation used in tests and integration
harnesses. All Seams from the same factory share one in-process mesh.

## Multiplexing and bonding

Three types sit around the `Seam` boundary and let consumers share or combine
transports without violating the single-collection contract:

**`MuxSeam` — byte-tagged channel splitter (`Seam → Seams`).** Fans one `Seam`
into up to 256 independent `Seam` views, each prefixed with a 1-byte tag. Use it
to separate internal protocol traffic (Raft, CRDT, presence) over one underlying
transport. `MuxSeam` is the sole collector of its delegate's `incoming`; each
channel view subscribes to the internally-shared flow.

```kotlin
val mux = MuxSeam(seam, scope)
val raftSeam: Seam = mux.channel(0x00.toByte())
val crdtSeam: Seam = mux.channel(0x01.toByte())
```

**`NamedMux` — string-keyed channel splitter (`Seam → Seams`).** The
unbounded-namespace sibling of `MuxSeam`: frames carry a UTF-8 name prefix
(1–255 bytes) rather than a single byte, giving an effectively unbounded
application namespace. Compose by nesting — a `MuxSeam` byte-tag can carry a
whole `NamedMux` subtree so only that subtree pays the wider header:

```kotlin
val named = NamedMux(mux.channel(0x03.toByte()), scope)
val chatSeam: Seam = named.channel("chat")
val cursorSeam: Seam = named.channel("cursors")
```

`channel()` is idempotent and thread-safe on both types: the same name or tag
always returns the same `Seam` instance. Both use `replay = 0` — frames emitted
before a channel view starts collecting are not replayed.

**`CompositeLoom` / `CompositeSeam` — multipath transport bonding (`Seams →
Seam`).** Bonds several `Loom`s (called *plies*) into one `Seam` over the union.
The composite keeps a stable `selfId`, collapses a remote multi-homed peer to one
`peers` entry, fans `broadcast` across all live plies, and deduplicates frames
that arrive via multiple paths. A ply failing over is not a membership event. See
[architecture.md](../docs/architecture.md#multipath-one-peer-several-transports)
for the design rationale.

## Transport SPI (`Connection`)

`Connection` is the point-to-point SPI a stream-based transport implements: a
duplex, message-framed link between exactly two peers. It is not a `Seam`.
`meshSeam()` weaves a list of `Connection`s into a fully-connected N-peer `Seam`
via a `MeshHello` preamble that exchanges peer identities and deduplicates
simultaneous dials. See `:kuilt-stream` for the `framed()` adapter that wraps a
byte-stream source/sink as a `Connection` with 4-byte length-prefix framing.
