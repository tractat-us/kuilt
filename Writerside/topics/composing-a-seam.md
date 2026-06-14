# Composing a Seam

Several `kuilt-core` types sit around the `Seam` boundary and all "combine"
something, which makes them easy to confuse. The clarifying lens is **what each
one consumes and what it produces** — they line up on a single `Conn`↔`Seam`
axis:

| Type | Direction | Role |
|------|-----------|------|
| `Conn` | — | The point-to-point SPI a transport implements: a duplex, message-framed link between *exactly two* peers. **Not** a `Seam`. |
| [`identified()`](fabric-kit.md#identified-a-2-peer-link) → `LinkSeam` | **Conn → Seam** | One link whose two identities are known, presented as a 2-peer `Seam` (`broadcast == sendTo(remote)`). |
| [`meshSeam()`](fabric-kit.md#meshseam-an-n-peer-mesh) → `Mesh` | **Conns → Seam** | *Topology builder.* N point-to-point links woven into one fully-connected N-peer `Seam`. |
| [`CompositeLoom`](multipath.md) → `CompositeSeam` | **Seams → Seam** | *Transport multiplexer.* Several `Seam`s (plies) for the **same** logical session, bonded into one multipath `Seam`. |
| `MuxSeam` | **Seam → Seams** | *Channel splitter.* One `Seam` fanned into several byte-tagged logical-channel `Seam` views over a single collection. |

## Mesh vs. composite — opposite sides of the boundary

The two that invite the most confusion are **`meshSeam()` and
`CompositeLoom`**, because both sound like "join several things into one
`Seam`". They sit on **opposite sides** of the `Seam` boundary:

- `meshSeam()` is a **topology builder** (`Conn → Seam`) — it *creates* a `Seam`
  out of raw links that aren't `Seam`s yet.
- `CompositeLoom` is a **transport multiplexer** (`Seam → Seam`) — it *consumes*
  finished `Seam`s and bonds them.

So they don't compete: a mesh turns links *into* a session, while a composite
bonds several sessions for *one* peer-set into a single multipath view. They
don't even compose by type today — `meshSeam()` takes `List<Conn>`, not
`List<Seam>` — so "a mesh whose every link is itself multipath" would be a new
abstraction, not a tweak to either.

## Where each one is documented

- `Conn`, `identified()`, and `meshSeam()` — the [Fabric kit](fabric-kit.md).
- `CompositeLoom` and bonding several transports — [Multipath](multipath.md).
- `MuxSeam` channel multiplexing — see
  [SeamReplicator](crdt-seamreplicator.md), which uses it to let several
  replicators share one transport.
- Writing the `Conn` SPI for your own transport — the implementer tutorial
  `docs/extending-fabrics.md` in the repository.
