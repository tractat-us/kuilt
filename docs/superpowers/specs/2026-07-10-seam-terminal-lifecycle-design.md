# The seam terminal-lifecycle gap — design (re #1135)

**Status:** design. The tactical fix for the #1135 instance (CompositeSeam's lost-Torn race)
lands separately; this document is about the *class*.

## What this is about, in plain language

When a network session ends, everyone watching it must see it end — and see it *stay* ended. In
kuilt, "ended" is one value: `SeamState.Torn`. A consumer that observes `Torn` releases
resources, stops retrying, completes its flows. If a seam ever reports "alive" *after* it was
closed, everything above it waits forever: that is exactly the intermittent CI hang in #1135,
where `CompositeSeamConcurrencyTest` wedged at `awaitTorn` because a close raced an internal
status update and lost.

The instance is fixable in one method. The reason it happened — and will happen again — is
structural: **every seam hand-rolls its own teardown against a raw `MutableStateFlow<SeamState>`,
and nothing in the codebase enforces that the terminal state, once published by `close()`, cannot
be overwritten.** Five implementations solve the same problem four different ways; two of them
are wrong in the same way. This document proposes the primitive that makes the whole class
unrepresentable, and the conformance obligation that keeps it that way for fabrics we haven't
written yet.

## The class, precisely

The lost-Torn race needs three ingredients, all present today:

1. A raw `MutableStateFlow<SeamState>` with **more than one writer** — `close()` plus an
   internal "rollup" pump that derives the aggregate state from constituent parts.
2. A `close()` that stops the pump with a **non-joining** `scope.cancel()` — cancellation is
   asynchronous; an already-running `onEach` body is not interrupted mid-flight.
3. **No latch**: nothing distinguishes the terminal write from any other write, so the in-flight
   pump write lands after `Torn` and the state wedges at `Woven` forever.

The tell that a point-fix cannot kill the class: `TieredSeam` already *has* the "fix" one would
naively write — its pumps guard every write with `if (!closed.value)` — and it is still racy,
because the flag check and the state write are not atomic. On a multi-threaded dispatcher the
pump can read `closed == false`, get preempted, lose to a complete `close()` (CAS, cancel,
publish `Torn`), then resume and write `Woven`. Check-a-flag-then-write is the race, restated.

### Survey: every seam-like re-derives teardown, and they disagree

All paths under `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/` unless noted.

| Impl | Writers to `_state` | Teardown ordering | Verdict |
|------|--------------------|-------------------|---------|
| `composite/CompositeSeam.kt` | `close()` + rollup pump (`_plies.onEach { _state.value = rollup(…) }`) | CAS → `scope.cancel()` (no join) → publish `Torn` | **Lost-Torn race — the #1135 instance.** |
| `TieredSeam.kt` | `close()` + rollup pump (closed-guarded) | CAS → `scope.cancel()` (no join) → publish `Torn` | **Same race.** The `if (!closed.value)` guard is a TOCTOU, not a fix. `_peers` has the identical hazard. |
| `fabric/MeshSeam.kt` | `tearDown()` only | CAS → collapse rosters under lock → publish `Torn` → `spool.close()` → `scope.cancel()` | Correct today — because `_state` happens to have a single writer and the author chose publish-before-cancel. Correctness by memory, not by structure. |
| `fabric/LinkSeam.kt` | `tearDown()` only | CAS → collapse peers → publish `Torn` → close channels; scope never cancelled (loops end naturally) | Correct on `state`; a third teardown variant. |
| `RoomHubSeam.kt` | `close()` only, **non-CAS** (`if (_state.value is Torn) return`) | publish `Torn` → clear rosters under lock → close spool | Two concurrent `close()` calls can both run teardown; worse, `deliver()`'s Torn pre-check is a TOCTOU — an in-flight delivery can **re-register a peer and republish `_peers`/`attestedPrincipals` after close cleared them**. Same class, one field over. |
| `kuilt-gossip/…/GossipSeam.kt` | none — `state` delegates to `base` | inbound-loop `finally { spool.close() }` | Out of the class for `state`; inherits whatever guarantee the base seam provides. |
| `kuilt-session/…/SeamRoom.kt` | n/a (Room, not Seam) | lock-guarded `closed` flip, snapshot jobs under lock, cancel outside | Disciplined — and a fourth hand-rolled variant of the same protocol. |
| `MuxServerLoom.kt` | n/a (Loom) | **none** — no close path at all; `pumpScope = CoroutineScope(scope.coroutineContext + SupervisorJob())` creates a **parentless** job, so cancelling the caller's scope does not stop the accept/read pumps | Adjacent finding: unowned pumps, no teardown. Filed as follow-up, not solved here. |

Five hand-rolled teardowns, four orderings, two carrying the live race and one carrying its
roster-shaped sibling. `ScopedCloseable` (in `:kuilt-core`) already exists as a lifecycle base but
addresses only job ownership — it says nothing about terminal-state publication, and no seam uses
it.

### The nuance the primitive must respect: two kinds of Torn

`SeamState`'s KDoc declares `Torn` terminal. `CompositeSeam`'s rollup quietly disagrees: when
every still-desired ply is torn, the aggregate rolls up to `Torn(reason)` — but a later
attach/detach recomputes the rollup and the aggregate can move back to `Weaving`/`Woven`
(`detachPly` even documents dodging "a transient terminal Torn" for the empty case). So the
codebase already contains a **revivable** Torn (derived, from rollup) and a **terminal** Torn
(decided, from `close()`), and the type distinguishes neither.

Consequence for the design: the latch must key on the **close decision**, never on the `Torn`
*value*. "Once any Torn, freeze" would wedge a multipath seam that legitimately recovers a ply.
"Once `close()` has run, freeze" is the actual invariant. (Whether a derived all-plies-torn
aggregate should even *be* `Torn` is a real modeling question — parked as a follow-up, below.)

## Options

### A — a terminal-latching state gate (recommended)

A small `internal` class in `:kuilt-core`, owning both the flow and the close decision:

```kotlin
internal class SeamStateGate(initial: SeamState) {
    val state: StateFlow<SeamState>          // the seam exposes this
    fun update(next: SeamState)              // pump writes; silent no-op once latched
    fun tear(reason: CloseReason): Boolean   // single-shot: latches, publishes Torn(reason),
                                             // returns true only for the winner
}
```

Implementation: one atomicfu `reentrantLock` guarding a `latched` boolean plus the
`MutableStateFlow` write — the check and the write become one atomic step, which is precisely
what every existing flag-guard fails to do. No suspension inside the critical section; a real
mutual-exclusion primitive, not dispatcher confinement — squarely inside the repo's
confinement-ban policy. `tear()` returning the CAS verdict **subsumes the per-seam `closed`
atomic**, so migrating seams delete a field rather than gain one.

- **Kills the class:** completely, for `state`. Ordering becomes irrelevant — cancel before or
  after publishing, join or don't: no later `update()` can move the state off `Torn`. The
  in-flight rollup write in #1135's trace becomes a harmless no-op.
- **Latch-on-close, not latch-on-Torn:** a rollup that *derives* `Torn` goes through `update()`
  and stays revivable; only `tear()` latches. The two-kinds-of-Torn nuance is handled by
  construction.
- **Cost:** ~60 lines + unit tests; zero public API under `explicitApi()` (internal to
  `:kuilt-core`); mechanical migration of 5 call sites, each a net simplification.
- **What it does not cover:** sibling published state (`_peers`, `attestedPrincipals`,
  registration maps). `RoomHubSeam`'s post-close roster resurrection needs its per-impl critical
  sections fixed regardless. Named as the residual, below.

### B — a shared close skeleton (quiesce → publish → release)

One inherited teardown protocol: `cancelAndJoin` all internal pumps, then publish `Torn`, then
release resources. This is the "obviously correct" ordering — and it is the option we
deliberately demote, for two reasons found in the code, not in taste:

1. **Self-deadlock on the pump-initiated path.** Teardown does not only start from `close()`. In
   `LinkSeam` and `MeshSeam` the `readLoop`'s `finally` triggers teardown when the remote drops —
   from *inside* the scope the skeleton would join. A universal join-before-publish either
   deadlocks on itself or grows a "join everything except the calling job" carve-out, at which
   point the skeleton no longer encodes one correct ordering.
2. **Torn latency becomes coupled to pump backpressure.** A pump parked in `spool.deliver`
   under a SUSPEND overflow policy delays the join, and with it every observer's `Torn` — the
   terminal signal should not wait behind a full delivery buffer.

Quiesce-then-publish remains the right *local* choice where a specific resource genuinely needs
pump quiescence before release (and it is what the tactical #1135 fix does inside
`CompositeSeam.close()`, where close-initiated teardown can safely join). But it is a per-site
optimization, not the correctness mechanism. With the gate in place it is never *needed* for
state correctness.

### C — make `SeamState` a monotonic lattice (LUB merge)

Order `Weaving < Woven < Torn` and merge concurrent writes by least-upper-bound so no writer can
regress the state. Rejected: the premise is false. `Woven → Weaving` is a documented, legitimate
transition (re-establishment), and composite rollups legitimately move in both directions as
plies churn. The state is not monotonic; the invariant is not "never go back" — it is "after the
local close *decision*, never change". That is a property of an event, not of the value ordering,
and the gate expresses it directly.

### D — a positive conformance obligation (recommended, with A)

`SeamConformanceSuite` today proves `closeDrivesStateTornNormal` and `closeIsIdempotent` — both
single-shot observations. Nothing asserts the state **stays** Torn. Add:

> **`stateStaysTornAfterClose`** — close the host seam, assert `Torn`; then drive churn the
> fabric supports (joiner activity, frames in flight, joiner close) and re-assert `state.value`
> is still `Torn` (same reason) after the churn settles.

- **Why it earns its place even with A:** the gate protects in-tree seams; the TCK protects the
  *contract* — including out-of-tree fabrics that subclass the suite and cannot use an
  `internal` primitive. It converts "the next seam ships the bug silently" into a red test.
- **Honest limits:** under the TCK's virtual-time harness this is a deterministic
  ordering check, not a real-threaded race detector. The stress-grade version belongs with the
  gated real-threaded concurrency probes (the `-P`-isolated job from #1174), where
  `CompositeSeamConcurrencyTest.awaitTorn` already lives — that probe becomes the regression
  test for the gate itself.

## Recommendation

**A + D, in that order.** Land the `SeamStateGate` and migrate the five carriers
(`CompositeSeam`, `TieredSeam` first — they hold the live race; then `MeshSeam`, `LinkSeam`,
`RoomHubSeam`); add `stateStaysTornAfterClose` to the TCK. Capture B's quiesce guidance as KDoc
on the gate ("join only when a resource needs quiescence; never required for state
correctness"). Reject C.

The one tradeoff this resolves that matters most: **correct-by-ordering versus
correct-by-construction.** Every existing teardown tries to be correct by ordering (publish
before cancel, guard with a flag, clear under the lock) and the survey shows ordering does not
survive contact with five authors — the two newest multi-writer seams both got it wrong, one of
them *with* the guard. The gate removes ordering from the correctness argument entirely; the
review question "is the teardown sequence right?" becomes "does the state go through the gate?",
which is answerable by grep.

## Residual seam (named, not hidden)

The gate latches `state` — nothing else. Post-close resurrection of **sibling published state**
is the same disease one field over, and it is live today: `RoomHubSeam.deliver()` can re-register
a peer and republish `_peers`/`attestedPrincipals` after `close()` cleared them;
`TieredSeam`'s `_peers` pump has the same TOCTOU as its `_state` pump. Fixing those is per-impl
lock discipline (fold the closed check into the same critical section that mutates the roster —
`MeshSeam.removePeer` is the exemplar), not a shared primitive; it rides the same migration
slices. A future generalization (a gate that owns a *bundle* of terminal-collapsible flows) is
possible but not justified by five call sites.

Two adjacent findings are explicitly out of scope and filed as follow-ups:
`MuxServerLoom`'s unowned, uncloseable pumps (parentless `SupervisorJob`), and the
`SeamState.Torn` modeling bifurcation (derived-revivable vs decided-terminal — whether the
aggregate all-plies-torn state should be a distinct value).

## Non-goals

- **Not** the tactical #1135 fix — the `CompositeSeam.close()` cancel-and-join TDD fix is in
  flight separately; the gate makes its ordering irrelevant but does not conflict with it.
- **Not** a `SeamState` type change or any public API change; the gate is `internal`.
- **Not** a rewrite of `ScopedCloseable` or a universal lifecycle base class (Option B's
  skeleton) — the survey shows teardown steps genuinely differ per seam; only the terminal
  publication is common.
- **Not** touching `GossipSeam`/`SeamRoom`/consumer modules beyond the follow-up audits named
  above.

## Migration slices

1. **`SeamStateGate`** in `:kuilt-core` internal + unit tests (latch semantics, single-shot
   `tear`, update-after-tear no-op, multi-threaded stress under the gated probe job).
2. **`CompositeSeam` + `TieredSeam`** — the two live carriers. Replace `_state` and the `closed`
   atomic with the gate; delete the TOCTOU guards; extend `TieredSeam`'s peers-collapse into the
   locked pattern while there.
3. **`MeshSeam` + `LinkSeam` + `RoomHubSeam`** — mechanical migration for the first two (they
   are correct today; the gate makes them structurally so); `RoomHubSeam` additionally gets a
   real single-shot close and the deliver/close critical-section fix (the roster-resurrection
   residual).
4. **TCK:** `stateStaysTornAfterClose` in `SeamConformanceSuite`; stress twin in the gated
   real-threaded probes.
5. **Follow-up issues:** sibling-state resurrection audit across seam-likes; `MuxServerLoom`
   lifecycle ownership; the Torn modeling bifurcation.

Each slice is independently mergeable; slice 2 alone retires the #1135 class from the two seams
that carry it.
