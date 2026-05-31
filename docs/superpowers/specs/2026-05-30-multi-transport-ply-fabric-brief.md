# Many-transport ("Ply") fabric — spec-for-spec / dispatch brief

**Status:** brief only — not yet specced. Dispatch this through its own
brainstorming → spec → plan cycle.
**Date:** 2026-05-30
**Depends on:** `2026-05-30-seam-lifecycle-design.md` (axis-1 `SeamState`) landing first.

## One-line

Today a `Seam` rides exactly one transport. This work lets one logical session be
woven from **several transports at once** — a *composite fabric* — and exposes the
health of each constituent link (a **ply**) without changing the meaning of the
aggregate lifecycle.

## Why

A real session may want, simultaneously:

- a globally-reachable relay link (e.g. WebSocket) that persists for long periods
  and lets peers anywhere join asynchronously;
- a server acting as a bridge between regions;
- a local low-latency radio/mesh link between co-present peers.

These are not "pick one fabric"; they are layers of one cloth. A peer should be
able to participate over whichever plies are live, and a consumer should be able
to see *which* plies are up — so a dropped regional bridge is distinguishable from
peers individually leaving (axis 2 alone conflates them).

## The shape it must fit (already reserved by axis-1)

The lifecycle design already defines `SeamState` as a **rollup over plies**, so
this work is intended to be *additive*, not a contract break:

```kotlin
// axis 1 — aggregate (already shipped)
val state: StateFlow<SeamState>                  // any ply Woven ⇒ Woven

// axis 3 — per-ply breakdown (this work)
val plies: StateFlow<Map<PlyId, SeamState>>      // additive
// invariant: state == reduce(plies.values) under "any Woven ⇒ Woven"
```

`PlyId` is the per-link identity (`Ply` = one strand woven with others into the
cloth; chosen over "strand" deliberately; **not** `Pattern`, which is already the
rendezvous descriptor).

## Open questions for the brainstorming session (non-exhaustive)

- **Loom composition.** How is a composite fabric constructed — a `CompositeLoom`
  that wraps N `Loom`s? How does `weave()` fan out, and what does it mean for the
  aggregate to be `Woven` when only some plies have woven?
- **Frame de-duplication / fan-out.** If the same peer is reachable over two plies,
  is a broadcast sent over both? How are duplicate inbound frames collapsed so the
  single-collection `incoming` contract still holds exactly-once-ish semantics?
- **Per-ply addressing.** Does `sendTo(peer)` pick a ply, or is ply selection
  always internal? Is there ever a reason to expose "send over this ply"?
- **Membership reconciliation.** `peers` is the union across plies. How is churn on
  one ply (bridge drop) reflected without flapping the whole set?
- **Failure semantics.** One ply `Torn(Unreachable)` while another stays `Woven` —
  the aggregate stays `Woven`; how/where is the per-ply failure surfaced to a
  consumer that cares (axis 3 is exactly this), and what's actionable?
- **Conformance.** What does the conformance suite assert for a composite fabric,
  and how do we build a multi-ply test harness (two delayed-`Woven` fakes bonded)?
- **Ordering across plies.** `incoming` preserves send order to a single collector
  today; across two independent transports there is no global order. What does the
  contract promise — per-ply order only? This may bound what the abstraction can
  honestly guarantee.

## Scope guardrails

- Do **not** start before axis-1 `SeamState` has landed and at least the
  websocket + one radio fabric drive it — the rollup definition is the foundation
  this builds on.
- This is `kuilt-core` contract surface + a composing `Loom`; keep `kuilt-core`
  free of fabric-specific imports (the existing dependency-direction rule holds).
- Application-level quorum/checkpoint is **not** part of this; that's a session
  concern (noted in the lifecycle design).
