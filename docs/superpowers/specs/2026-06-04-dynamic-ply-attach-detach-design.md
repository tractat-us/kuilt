# Dynamic ply attach/detach — design

**Status:** design, pre-implementation.
**Date:** 2026-06-04.
**Roadmap:** item 1 of `docs/ply-roadmap.md`.
**Builds on:** `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`
(the composite/ply MVP — `CompositeLoom`, `CompositeSeam`, the `Announce`/`Data`
envelope, `PlyInboundGate`), which shipped with the ply set **fixed at `weave()`**.
**Scope:** `kuilt-core` only — a flow-driven `CompositeLoom` surface plus an
internal reconcile engine in `CompositeSeam`, with composite conformance
coverage. No `Seam` contract change; no fabric-module change.

## Goal

Let a composite session's plies **join and leave while it is live**, instead of
being frozen at `weave()`. An overlay (a platform radio, WebRTC-LAN, an extra
relay a device multi-homes across) can attach when peers come into proximity and
detach when they leave — the scenario the MVP design's "Why" describes but does
not realize. The consumer keeps seeing one ordinary `Seam`: the dynamism is
hidden behind the same single peer set, single-collection `incoming`, single
`broadcast`. The `plies` map — already on the contract — is the surface that
reflects which plies are currently live.

## Why this is item 1

It is the most architecturally foundational of the three deferred Ply
capabilities. It reshapes `CompositeSeam` from a fixed-`List` constructor into a
reconcile-based core, and the other two roadmap items (single-hop gateway
forwarding; primary-ply-per-peer send) are both cleaner to build on a composite
whose ply set is already mutable — a forwarding bridge *is* a ply that attaches,
and per-peer send bookkeeping is simpler when attach/detach is already modeled.

## Surface (declarative desired-set)

The composite is driven by a **`StateFlow` of the desired ply set**. The consumer
(or a discovery source feeding it) pushes a new set; the composite diffs it and
reconciles. The existing static-list constructor becomes sugar over a
never-changing flow, so current callers are untouched and the static case is just
the degenerate dynamic case.

```kotlin
public class CompositeLoom(
    private val plies: StateFlow<List<Pair<PlyId, Loom>>>,   // the *desired* ply set
    private val dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) : Loom {
    // Static convenience — current callers unchanged:
    public constructor(
        plies: List<Pair<PlyId, Loom>>,
        dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    ) : this(MutableStateFlow(plies), dispatcher)

    override suspend fun weave(rendezvous: Rendezvous): Seam = /* CompositeSeam over the flow */
    override fun availability(): FabricAvailability = /* Available if any current ply is */
}
```

No new method appears on the `Seam` contract. The public surface stays exactly
`Loom`/`Seam`; "attach a ply" is "emit a new desired set." List order remains the
send-preference hint, exactly as in the MVP.

> **Why declarative over an imperative `attach`/`detach` handle.** A `StateFlow`
> matches the rest of the composite's reactive design, keeps one code path for
> static and dynamic, and avoids adding imperative surface the consumer must
> sequence by hand. The reconcile engine (diff added/removed/kept) is identical
> either way; the flow is just the cleaner mouth for it.

## Reconcile engine (the core change)

`CompositeSeam` stops taking a frozen `List` and instead collects the desired-set
flow. On each emission it diffs the desired set against the currently-live set,
keyed by `PlyId`:

```
desired = { relay, ble }    live = { relay }
        ⇒ ATTACH ble:  loom.weave(rendezvous) → Seam; launch its pumps;
                       on its first Woven, broadcast Announce(selfId) over it.

desired = { relay }         live = { relay, ble }
        ⇒ DETACH ble:  cancel its pumps; seam.close(Normal);
                       purge idMap entries for ble; recompute peers.

PlyId present in both ⇒ keep (no-op).
```

Topology this enables — the MVP "Why" made real:

```
   weave()              peer walks into BLE range        peer walks out
 ┌──────────┐           ┌──────────────────┐          ┌───────────────┐
 │ relay     │   ──►     │ relay   (Woven)  │   ──►     │ relay  (Woven)│
 │ (Woven)   │           │ ble     (Woven)  │           │   [ble gone]  │
 └──────────┘            └──────────────────┘          └───────────────┘
 plies={relay}           plies={relay, ble}            plies={relay}
                         lower-latency overlay          no membership flap:
                         added live                     peer still on relay
```

### Four required internal changes

Each is forced by "the set can change," and none touches the public contract.

1. **Per-ply cancellation.** Today the per-ply state/peers/incoming pumps are
   `launchIn(scope)` with no individual handle. Give each ply its own child `Job`
   (or child scope) stored in a `live: MutableMap<PlyId, PlyHandle>`, so a detach
   cancels exactly that ply's pumps and nothing else. The top-level `scope` still
   owns the desired-set collector and is cancelled wholesale only on `close()`.

2. **`idMap` rekey by `PlyId`.** The MVP keys the identity map
   `(plyIndex, transportId) → compositeId` by **positional index**. Index is
   meaningless once plies are added/removed, so rekey to
   `(PlyId, transportId) → compositeId`. A detach purges every entry whose
   `PlyId` matches, so a later re-attach of the same `PlyId` starts clean.

3. **Stable `selfId`.** `mintCompositeId` currently derives the composite id by
   joining the constituent plies' self-ids — that cannot survive a changing set.
   Mint `selfId` **once at `weave()`**, ply-independent (a fresh opaque id). It
   never changes across attach/detach, so peers' learned mappings stay valid.

4. **`_state` derived from `_plies`.** Replace the fixed
   `combine(constituents.map { it.state })` with `_state` computed from the live
   `_plies` map (`rollup(plies.values)`), recomputed on any ply-state change *or*
   any set change. `rollup` is unchanged (any `Woven` ⇒ `Woven`; all `Torn` ⇒
   `Torn(first reason)`); the **empty** ply set maps to `Weaving` (see below).

## Lifecycle semantics

- **Zero plies ⇒ `Weaving`, recoverable.** When every ply is torn or the desired
  set transiently empties, the aggregate returns to `Weaving` ("no connectivity,
  awaiting a ply") rather than dying. Re-attaching any ply brings it back to
  `Woven`. Only explicit `close()` makes it `Torn`. This is the mobile-correct
  choice: a walk through a dead zone must not permanently kill a session that can
  recover. It requires **no contract change** — `SeamState` already documents
  `Woven → Weaving` as "permitted if a fabric supports re-establishment," and the
  static fabric simply never exercises it.
- **Re-attach** of a previously-detached `PlyId` is allowed and starts clean: the
  detach purged its `idMap` entries, so the new `weave()` re-announces and
  rebuilds the mapping from scratch with no stale-id delivery.
- **No membership flap.** Detaching one ply never removes a peer still reachable
  on another. This is the MVP's existing "present iff reachable on ≥1 ply" rule;
  reconcile only feeds it a changed live set. A peer drops from `peers` solely
  when its transport ids are absent from **all** live plies.

## Concurrency

All reconcile work runs on the existing confined dispatcher
(`Dispatchers.Default.limitedParallelism(1)` in production), exactly like every
other `CompositeSeam` mutation — so attach/detach cannot race the rollup,
announce, or inbound pumps. The desired-set collector is launched into the same
`scope`. Tests inject `UnconfinedTestDispatcher(testScheduler)` so reconciliation
runs eagerly and synchronous `.value` reads observe the settled state, per
`docs/testing-coroutine-determinism.md`.

## Conformance & tests

The universal `SeamConformanceSuite` is unchanged. The composite conformance
harness gains dynamic cases:

- **Attach mid-session.** Start on one ply; emit a second into the desired set →
  it appears in `plies`, its peers merge into the aggregate `peers` (once, via the
  learned mapping), and a frame reachable on both plies is delivered **exactly
  once** (dedup spans the newly-attached ply).
- **Detach with survivor.** Detach an overlay while a peer is still on the relay →
  `plies` shrinks, the aggregate stays `Woven`, and the peer does **not** drop
  from `peers` (no flap).
- **Detach to empty, then recover.** Detach every ply → aggregate `Weaving`;
  re-attach a ply → back to `Woven` and traffic resumes.
- **Re-attach same `PlyId`.** Detach then re-attach the same id → a clean
  `Announce` rebuilds the mapping; no stale-id frame is delivered.
- **Static is degenerate dynamic.** A `CompositeLoom` built from a single-element,
  never-changing flow still passes the full `SeamConformanceSuite` — proving the
  static path is the dynamic path with one emission.

## Explicitly out of scope

- **App-layer single-hop gateway forwarding** and **primary-ply-per-peer send** —
  roadmap items 2 and 3; built later, on top of this.
- **The discovery source that produces the desired-set flow.** Deciding *when* a
  ply becomes reachable (radio proximity, network change) is a consumer/fabric
  concern. kuilt consumes a `StateFlow`; it does not generate one.
- **Arbitrary multi-hop mesh routing** — out of scope for the whole Ply line, per
  the MVP design.

## Sequencing

Detailed PR breakdown is deferred to the implementation plan (writing-plans). At a
glance: (1) refactor `CompositeSeam` to a reconcile core fed by a single static
emission — `_state` from `_plies`, per-ply handles, `idMap` rekey, stable
`selfId` — with all existing composite tests still green (pure refactor, no
behaviour change); (2) add the `StateFlow` `CompositeLoom` constructor and wire
the collector + attach/detach diff; (3) add the dynamic conformance cases.
