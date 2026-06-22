# Composing a Seam

When building custom transport stacks, several `kuilt-core` APIs can look
similar because they all "combine" something. The practical way to choose is:
**what goes in, and what comes out**. They line up on one `Connection`↔`Seam` axis:

| Type | Direction | Role |
|------|-----------|------|
| `Connection` | — | The point-to-point SPI a transport implements: a duplex, message-framed link between *exactly two* peers. **Not** a `Seam`. |
| [`identified()`](fabric-kit.md#identified-a-2-peer-link) → `LinkSeam` | **Connection → Seam** | One link whose two identities are known, presented as a 2-peer `Seam` (`broadcast == sendTo(remote)`). |
| [`meshSeam()`](fabric-kit.md#meshseam-an-n-peer-mesh) → `Mesh` | **Connections → Seam** | *Topology builder.* N point-to-point links woven into one fully-connected N-peer `Seam`. |
| [`CompositeLoom`](multipath.md) → `CompositeSeam` | **Seams → Seam** | *Transport multiplexer.* Several `Seam`s (plies) for the **same** logical session, bonded into one multipath `Seam`. |
| `MuxSeam` | **Seam → Seams** | *Channel splitter (byte-tagged).* One `Seam` fanned into several `Seam` views, each keyed by a 1-byte tag (hard ceiling: 256 channels). Fixed internal channels; single upstream collection. |
| `NamedMux` | **Seam → Seams** | *Channel splitter (string-keyed).* Like `MuxSeam` but frames carry a UTF-8 name prefix — effectively unlimited application namespace. Used by `gameHost`/`gameJoin` to multiplex the Raft channel + app envelope over one session. |
| [`GossipSeam`](partial-mesh.md) | **Seam → Seam** | *Partial-mesh overlay.* Wraps a full-membership `Seam` so `broadcast` floods only to ~k neighbours and disseminates across the room — the O(N)→O(k) scaling decorator for large sessions. |

## Mesh vs. composite — opposite sides of the boundary

The two that invite the most confusion are **`meshSeam()` and
`CompositeLoom`**, because both sound like "join several things into one
`Seam`". They sit on **opposite sides** of the `Seam` boundary:

- `meshSeam()` is a **topology builder** (`Connection → Seam`) — it *creates* a `Seam`
  out of raw links that aren't `Seam`s yet.
- `CompositeLoom` is a **transport multiplexer** (`Seam → Seam`) — it *consumes*
  finished `Seam`s and bonds them.

So they don't compete: a mesh turns links *into* a session, while a composite
bonds several sessions for *one* peer-set into a single multipath view. They
don't even compose by type today — `meshSeam()` takes `List<Connection>`, not
`List<Seam>` — so "a mesh whose every link is itself multipath" would be a new
abstraction, not a tweak to either.

## Where each one is documented

- `Connection`, `identified()`, and `meshSeam()` — the [Fabric kit](fabric-kit.md).
- `CompositeLoom` and bonding several transports — [Multipath](multipath.md).
- `MuxSeam` channel multiplexing — see
  [Quilter](crdt-quilter.md), which uses it to let several
  replicators share one transport.
- `NamedMux` — used internally by `gameHost`/`gameJoin`/`gameNode` (see [Consensus (Raft)](raft.md)) to multiplex the application-envelope channel over the game session's single `Seam`. Consumers access it via `GameSession.appChannel(name)`.
- `GossipSeam` and scaling to many peers — [Scaling to many peers](partial-mesh.md).
- Writing the `Connection` SPI for your own transport — the implementer tutorial
  `docs/extending-fabrics.md` in the repository.
