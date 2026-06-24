# Bounded, backpressured frame delivery for in-memory fabrics

**Status:** proposed (epic). **Date:** 2026-06-23. **Tracks:** #701 (from the #655 investigation).

## Problem

Every in-process fabric and test double moves frames between peers through an
**unbounded** `Channel(capacity = UNLIMITED)` and pushes with `trySend` whose result is
ignored. Two coupled defects fall out of that one pattern:

1. **Unbounded growth → OOM.** A producer that outruns its consumer piles frames in heap
   with no ceiling. This was the proximate cause of the intermittent `QuilterConcurrencyTest`
   OOM (#655): a deliberate 2,400-apply flood, buffered through `InMemoryLoom`'s unbounded
   channel, tipped a contended CI test fork over its heap.
2. **Silent loss the moment it is bounded.** Naively capping the channel turns the ignored
   `trySend` into a *silent drop*, which surfaces downstream as a mysterious convergence
   failure rather than a clear signal.

The pattern is not isolated to `InMemoryLoom`. A survey ("make the class impossible") found
the same unbounded-channel + ignored-`trySend` shape in ~13 sites spanning production fabrics
and test doubles (see [Migration surface](#migration-surface)).

## What the contract already says

`Seam` (`kuilt-core`) is not silent about delivery:

- `incoming` delivers frames "**in send order**, to a single collector."
- `broadcast` / `sendTo` "**Suspend until accepted by the local transport.**"

So the contract-faithful behaviour is **bounded, ordered, lossless, with backpressure** — and
crucially the backpressure point is the **local** transport, not the remote peer's inbox. That
single word ("local") is what makes a safe design possible (see [Deadlock](#deadlock-the-trap-and-the-escape)).

`Swatch.sequence` is a transport-level monotonic tag (asserted by `InMemoryLoomTest`, used by
nothing in the protocol layers for ordering) — so the redesign must preserve per-sender FIFO
and monotonic sequence under *sequential* sends, but is not constrained to a global total
order under concurrent senders (real transports aren't either).

## Goals / non-goals

**Goals**
- Make unbounded frame buffers **structurally impossible** for in-process delivery.
- Default to contract-faithful **bounded + backpressure (suspend)**: lossless, ordered.
- Offer a **configurable overflow policy** (Reactor-style) so a fabric can also model loss or
  fail loud.
- Never hold a lock across the backpressure suspension (ADR-style W2: locks guard synchronous
  state only).
- Preserve `SeamConformanceSuite` / `RoomConformanceSuite` green at every step.

**Non-goals**
- Changing real network transports (WebSocket/TCP/Multipeer/Nearby/WebRTC) — they already have
  OS/library buffers; this is about the *in-process* fabrics and doubles.
- Replacing `FaultySeam`. Deterministic, targeted loss/partition injection is a different job
  from capacity-overflow policy (though a test may now use `DROP_*` for incidental loss).
- Wire-format or protocol changes.

## Design

### Delivery policy (configurable, Reactor-style)

```kotlin
public data class DeliveryPolicy(
    val capacity: Int = DEFAULT_CAPACITY,     // bounded; UNLIMITED is not expressible
    val overflow: Overflow = Overflow.SUSPEND,
) {
    public companion object {
        public val Reliable: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.SUSPEND)
        public val Lossy: DeliveryPolicy    = DeliveryPolicy(overflow = Overflow.DROP_OLDEST)
        public val Strict: DeliveryPolicy   = DeliveryPolicy(overflow = Overflow.FAIL)
    }
}

public enum class Overflow { SUSPEND, DROP_OLDEST, DROP_LATEST, FAIL }
```

This maps almost 1:1 onto Reactor's `onBackpressure{Buffer,Drop,Latest,Error}` and onto
Kotlin's native `kotlinx.coroutines.channels.BufferOverflow` (`SUSPEND` / `DROP_OLDEST` /
`DROP_LATEST`). Therefore `SUSPEND` and `DROP_*` are free — they pass straight through to
`Channel(capacity, onBufferOverflow = …)`. Only `FAIL` (throw `FrameOverflow` on a full
buffer) is custom.

Beyond fixing #701 this buys, for free:
- `Reliable` (`SUSPEND`) — the default; lossless/ordered/contract-faithful; fixes #655 structurally.
- `Lossy` (`DROP_OLDEST`/`DROP_LATEST`) — models a UDP/radio fabric so tests can exercise the
  protocol's loss-healing (FullState + resend) without a bespoke fault seam.
- `Strict` (`FAIL`) — a test asserts "this fabric must never overflow"; a flood becomes a loud
  error, not a silent heap climb.

### The `Spool` primitive

One blessed type owns per-receiver inbound delivery; it is the *only* sanctioned way to wire
frame delivery, so `Channel.UNLIMITED`-for-delivery disappears from the codebase. It is **public**
in `:kuilt-core` — alongside the already-public `DeliveryPolicy`/`Overflow`/`FrameOverflow` — so
every kuilt module *and* third-party fabric implementors (the headline extension point) share the
one primitive rather than each re-deriving a bounded channel. It is **generic in the frame type**
because kuilt delivers at two layers with the same unbounded-growth risk: the multi-peer Seam layer
(`Spool<Swatch>`) and the point-to-point `Connection` transport SPI (`Spool<ByteArray>`).

```kotlin
public class Spool<T>(private val policy: DeliveryPolicy) {
    // bounded Channel(policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
    suspend fun deliver(frame: Swatch)   // SUSPEND ⇒ suspends; DROP_* ⇒ returns; FAIL ⇒ throws
    val incoming: Flow<Swatch>           // single-collection, FIFO
    fun close()
}
```

A `DeliveryPolicy.Reliable` Spool uses `Channel(capacity, SUSPEND)`; `deliver` therefore
suspends the caller when full. **The send must be performed outside any lock** — each fabric
assigns sequence numbers and snapshots its target set under its existing lock, releases it,
then `deliver`s. This is the same "I/O outside the lock" rule `Quilter`/`SeamRoom` already follow.

A detekt rule (or convention-plugin check) forbids `Channel(... UNLIMITED ...)` in fabric
`commonMain`/test-double sources, so the class cannot reappear.

### Deadlock: the trap and the escape

Pure remote-inbox backpressure deadlocks a *cyclic* mesh: peer A and B each `broadcast` to the
other; if both bounded inboxes fill while both peers are suspended mid-`broadcast` (and thus
not draining their own inbox), neither progresses — turning the #655 OOM into a hang.

The contract's "suspend until accepted by the **local** transport" is the escape. Two
candidate implementations, **decided empirically in Phase 1** rather than committed up front:

- **(a) Generous bounded `SUSPEND`, no pump.** Simplest. A producer suspends only when the
  remote inbox is genuinely saturated; with an actively-draining consumer this is rare.
  Acceptance gate: `QuilterConcurrencyTest` converges with **no OOM and no hang** at a 16 MB
  test fork (the #655 experiment — backpressure should bound memory so even a tiny heap passes).
- **(b) Per-link outbound pump.** `send` enqueues to a bounded *local outbound* buffer
  (suspends only if local outbound full — literally "accepted by the local transport") and a
  per-link pump forwards outbound → remote inbox. The application keeps draining regardless of
  any peer's fullness, so the cycle cannot deadlock. More machinery; adopt only if (a) hangs.

If a particular fabric proves deadlock-prone under `SUSPEND` and the pump is unwarranted, that
fabric may default to `DROP_OLDEST` and rely on loss-healing — the configurable policy is the
escape hatch either way.

## Migration surface

Grouped for phasing. Each adopts `Spool` + a sensible default policy.

| Group | Sites | Default policy |
|---|---|---|
| Hot path | `InMemoryLoom` | `Reliable` |
| Production fabrics | `MeshSeam`, `LinkSeam`, `SingleCollectionConnection`, `CompositeSeam` (+ retire its legacy `limitedParallelism(1)` confinement) | `Reliable` |
| Test doubles | `FakeSeam`, `FakeRoom`, `ControllableLoom`, `ConnectionPair`, `MultiNodeRaftNetwork`, `FakeRaftNode` | `Reliable` (test may opt to `Strict` to catch overflow bugs) |

`FaultySeam` is out of scope (deterministic injection, not capacity policy).

## Decomposition (epic + phased sub-issues)

Per the EPIC convention — one behaviour move per PR, each independently revertable.

- **Phase 0 (planning sub-issue):** land this spec + the implementation plan + the `DeliveryPolicy`
  / `Spool` primitive + the detekt guard. Closes the *planning* sub-issue (not the epic).
- **Phase 1:** `InMemoryLoom` → `Spool` (`Reliable`). Acceptance: the 16 MB-heap experiment
  passes; decides generous-bound-vs-pump for `SUSPEND`.
- **Phases 2…k:** one production fabric per PR; `CompositeSeam`'s phase also retires
  `limitedParallelism(1)`.
- **Phases k+1…n:** one test double per PR.

Each phase keeps both conformance suites green and carries a one-line rollback note.

## Testing

- **Unchanged:** `SeamConformanceSuite` / `RoomConformanceSuite` must stay green throughout —
  `Reliable` preserves lossless ordered delivery, so existing assertions (including
  `InMemoryLoomTest`'s `sequence == 1,2,3`) hold.
- **New, per primitive:** policy behaviour — `SUSPEND` suspends a full mailbox (virtual-time,
  bounded `advanceTimeBy`); `DROP_OLDEST`/`DROP_LATEST` bound memory and drop the documented
  end; `FAIL` throws `FrameOverflow`.
- **New, Phase 1 acceptance:** `QuilterConcurrencyTest` (or a focused variant) green at a 16 MB
  fork — proving backpressure makes the OOM structurally impossible, not merely papered over by
  the #655 heap pin. Once green, the `:kuilt-quilter` heap pin (#655) can be revisited/relaxed.
- **No `advanceUntilIdle`** on any timer-bearing system; bounded time-advance only.

## Risks

- **Deadlock under `SUSPEND`** — mitigated by the empirical Phase-1 gate and the pump/`DROP`
  fallbacks.
- **Capacity tuning** — too small throttles legitimate bursts; `DEFAULT_CAPACITY` chosen with a
  comment and revisited if a conformance test slows. Generous by default.
- **Behavioural change in production fabrics** — `MeshSeam`/`LinkSeam`/`CompositeSeam` are real
  code; phasing one per PR keeps any regression attributable to a single move.

## Open question for the plan

Whether `DEFAULT_CAPACITY` is one global constant or per-group (a 2-peer `LinkSeam` may want a
different default than a wide `MeshSeam`). Resolve in the implementation plan.
