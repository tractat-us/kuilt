# Seam multiplexing — running a SeamReplicator alongside a Room

**Date:** 2026-06-08
**Status:** proposed
**Modules:** `:kuilt-core`, `:kuilt-crdt`, `:kuilt-session`
**Issue:** (epic — filed alongside this doc)

## Problem

`Seam.incoming` is **single-collection** by contract (`docs/architecture.md`,
the cohered ADR-034/-002 decision): exactly one collector per `Seam`; fan-out
consumers must `shareIn` themselves. But three components each want to own that
collection:

- `SeamReplicator` — `seam.incoming.onEach { … }.launchIn(scope)`.
- `SeamRoom` — its main loop *is* the sole collector.
- `BoundedCounterTransferCoordinator` — needs its own incoming.

Consequently a consumer holding a `Room` **cannot also run a `SeamReplicator`
over the same transport**: the `Room` owns the only `Seam` and exposes
`Flow<RoomFrame>` + `broadcast`/`sendTo` — not a `Seam` — so there is nothing to
hand the replicator. This is why `MemberMetadata` is a plain value type the
application merges by hand instead of converging live over the session.

## What already exists (and why it is not enough)

| Primitive | Where | Limit |
|-----------|-------|-------|
| `RoutingSeam` | `kuilt-crdt/.../replicator/RoutingSeam.kt` | Owns the single collect via `shareIn`, splits into **exactly two** hardcoded channels by a 1-byte prefix (`0x00` replicator / `0x01` coordinator). Hardcoded to 2; lives in `kuilt-crdt`; forwards raw `delegate.peers`. |
| `PerPeerSeam` | `kuilt-session/.../SeamRoom.kt` | Fans the raw seam into a `MutableSharedFlow` and filters *by sender* for heartbeat detectors. A per-sender filter, not a channel mux; Room-private. |

Frame namespacing is already content-sniffed by prefix: admit frames carry
`0x61` ('a', deliberately outside the CBOR major-type-7 range); heartbeat frames
carry the strings `kuilt.heartbeat.ping`/`pong`; application frames are
everything else. Any mux scheme must avoid colliding with these.

## Design — two layers

### Layer A — `MuxSeam` (kuilt-core)

Generalize `RoutingSeam` into a reusable N-way demultiplexer. It is a pure `Seam`
combinator with no CRDT dependency, so it belongs in `:kuilt-core`:

```kotlin
public class MuxSeam(private val delegate: Seam, scope: CoroutineScope) {
    /** A Seam view carrying only frames tagged with [tag]; one shared upstream collect. */
    public fun channel(tag: Byte): Seam
}
```

Each `channel(tag)` view prefixes outbound frames with `tag` and strips it on
inbound; a single `shareIn(replay = 0)` owns the one collection of
`delegate.incoming`. `RoutingSeam` is migrated to `MuxSeam.channel(0x00)` /
`channel(0x01)` and deleted; the `BoundedCounter` coordinator keeps working
unchanged. This unblocks "two `SeamReplicator`s over one raw seam." It does
**not** solve the Room case — `Room` weaves its own seam internally, so a
consumer cannot interpose a `MuxSeam` beneath it.

### Layer B — `Room.channel(id)` (kuilt-session)

The goal. Add to `Room`:

```kotlin
public fun channel(id: String): Seam
```

The returned `Seam` view:

- **`peers` = the admitted roster (+ self), reactive to `Joined`/`Left`** — *not*
  raw transport peers. This is the central correctness point: a replicator
  FullState-ing to unadmitted peers would leak state past the admit gate.
  Partitioned-but-still-admitted members remain in `peers` (the replicator's own
  eviction TTL handles their silence).
- `incoming` = frames tagged with this channel `id`, from admitted members only,
  payload de-framed.
- `broadcast` / `sendTo` = the Room's, with channel framing prepended, gated on
  terminal (`HostLost` / `closed`) state.
- `state` forwards the underlying seam state (so a torn seam winds the
  replicator's collectors down); `close` is a no-op (the Room owns lifecycle,
  exactly as `PerPeerSeam` does).

`SeamRoom`'s main-loop `dispatchIncoming` gains a fourth branch: a reserved
channel-frame prefix (distinct from admit `0x61`) carrying a channel sub-id,
routed to the matching channel view. It is built on the same framing helper as
Layer A.

### MemberMetadata payoff

With Layer B, live convergence is wiring, not new CRDT code:

```kotlin
val md = SeamReplicator<LWWMap<PeerId, String>>(
    replica = …, seam = room.channel("member-metadata"),
    initial = LWWMap.empty(), …)
// md.state is the live-converging map; the value-type MemberMetadata
// stays as the pure CRDT.
```

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Build order | **Layer A first, then B on top** | Harden the foundation before stacking; A removes `RoutingSeam` duplication and is independently useful. |
| Channel identity | **App-facing `String` id**, registered to a short internal tag | Readable call sites; the wire cost (a short tag, not the full string per frame) stays bounded. |
| Channel-view `peers` | **Admitted roster, partitioned members included** | Admit-gating must hold; eviction TTL handles silence. |
| Where `MuxSeam` lives | `:kuilt-core` | Pure `Seam` combinator, no CRDT dependency. |
| Late-subscriber semantics | Best-effort (`replay = 0`) | Fine for `SeamReplicator` (FullState + resend heal gaps); documented as **not** at-least-once for raw consumers. |

## Risks

- **`peers` semantics (raw vs roster)** — the primary correctness trap; gets a
  dedicated test asserting a replicator on a Room channel never sends FullState,
  Ack, or Resend to an unadmitted peer. Note the precise boundary: Delta frames
  go out via `seam.broadcast` and reach all transport peers (including unadmitted
  ones); FullState / Ack / Resend are `sendTo` gated on the admitted roster. An
  unadmitted peer has no FullState base and therefore cannot reconstruct state.
- **Prefix-namespace collision** with admit/heartbeat — pick and document a
  reserved channel prefix ("applications must not emit this namespace," mirroring
  the existing heartbeat note).
- **Late-subscriber frame loss** — `shareIn(replay = 0)` drops frames emitted
  before a channel attaches; safe for `SeamReplicator`, documented as unsafe for
  raw at-least-once consumers.
- **Virtual-time guard** — `SeamReplicator`'s real-clock anti-entropy `delay`
  guard still fires inside a Room channel; tests use `UnconfinedTestDispatcher` /
  `backgroundScope` as usual.

## Delivery

Three independently-mergeable PRs (aggressive pre-1.0 posture: small PRs,
auto-merge once green):

1. **`MuxSeam` (kuilt-core)** — N-way mux; migrate `RoutingSeam` / `BoundedCounter`
   onto it; round-trip + channel-isolation tests.
2. **`Room.channel(id)` (kuilt-session)** — channel views + main-loop dispatch
   branch + roster-backed `peers`; test a `SeamReplicator` converging over a
   channel in a 2-peer room with admit-gating respected.
3. **MemberMetadata live convergence** — wiring + dog-fooding test; update
   `architecture.md` and `crdt/bounded-counter-rebalancing.md`.
