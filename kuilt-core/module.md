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
