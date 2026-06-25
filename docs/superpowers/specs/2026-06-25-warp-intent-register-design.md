# Warp intent-register — design spec

_2026-06-25. Module: `:kuilt-warp`. Status: approved design, pre-implementation._

## Problem

`WarpNode`'s claim path is consistent-hash assignment (Strategy D): each peer executes
only the tasks where `ring.owner(taskId) == selfId`. In steady state with an agreed ring
this is ~0% duplicate execution at zero coordination cost (the Phase 1 verdict measured
0.0% over real WebSocket sockets with the strong/Raft ring). Duplicates arise **only in the
transient disagreement window** — during membership churn or partition-detection lag, two
peers' rings briefly disagree on a task's owner and both execute it before the `Results`
ORMap dedup converges. The warp spike (`docs/warp-spike-results.md`) flagged this
"`downPeers` convergence gap" as the real-deployment duplicate source it did not model.

This work adds a lightweight **intent-register** layer that catches those window conflicts
before the work runs, without per-task consensus, exposed as a selectable `ClaimStrategy`
with a smart adaptive default.

## Non-goals

- **No `Coordinated` (Raft exactly-once) member.** That is slice B; the sealed type is
  designed to grow into it but we do not build it here.
- **No `Optimistic`/`Consensus` members** (the spike's OPT/CONS). YAGNI — the ring path
  already dominates OPT, and CONS is slice B's concern.
- **No change to correctness.** The `WorkQueue` ORSet + `Results` ORMap remain the source
  of truth and the final dedup backstop. The intent-register reduces *wasted work*; it
  never changes which results converge.

## Hard invariant

**The intent-register must never lose a task.** Trading a duplicate (cheap, already
backstopped) for a stuck task (work silently never happens) is a strict regression. Every
failure mode degrades to *at worst one duplicate execution*, never to a task that no peer
runs. This is the load-bearing constraint behind the liveness backstop (§5).

## 1. The strategy seam

```kotlin
public sealed interface ClaimStrategy {
    /** Pure consistent-hash assignment — today's behavior, no intent layer. */
    public data object Ring : ClaimStrategy

    /**
     * Consistent-hash assignment plus an intent-register that catches
     * disagreement-window conflicts. The default.
     */
    public data class RingWithIntent(
        val settleWindow: Duration = DEFAULT_SETTLE_WINDOW,
        val claimLease: Duration = DEFAULT_CLAIM_LEASE,
    ) : ClaimStrategy
}
```

`WarpNode` gains a constructor parameter `strategy: ClaimStrategy = ClaimStrategy.RingWithIntent()`
— a **named default, never nullable**. Absence cannot silently disable the net
("optional ≠ tuning"). `settleWindow`/`claimLease` are genuine tuning knobs (bounded
waits), defaulted standalone but documented relative to `heartbeatConfig` (the settle
window should be ≪ the heartbeat timeout, the lease comfortably > a normal task duration).

## 2. The intent register (CRDT)

A **third Quilter** over a new mux channel `CHANNEL_INTENT = 0x04`, replicating
`ORMap<TaskId, GSet<PeerId>>` — per task, a grow-only set of claimant peer IDs.

- **Monotone, clock-free, CALM-clean:** claims only ever *add* a peer; a resend is a set
  union; no LWW timestamp, no clock skew to reason about.
- **Bounded growth:** the whole `intent[taskId]` entry is tombstoned in the *same locked
  section* as `removeFromQueue(taskId)`, so the register tracks pending tasks, not lifetime
  task count.
- Replicated by its own `Quilter` with a distinct seeded RNG (as the queue/results
  Quilters already are), config from the shared `quilterConfig`.

### Winner rule (every peer computes identically)

```
winner(task) = min( claimants(task) ∩ effectiveRoster )   by PeerId
effectiveRoster = rosterPeers − partitionedPeers
```

Only a peer that believes it owns `task` on its *own* ring view ever announces (joins
`claimants`), so `claimants` is usually a single peer and occasionally two during the
disagreement window. The tiebreak keys off the **converged** claimant set intersected with
the **effective roster** — deliberately *not* each peer's per-task ring view, which is the
very thing that disagrees in the window. Intersecting with `effectiveRoster` (which converges
as roster + partition events gossip) drops dead/departed claimants so the winner is live; the
lease (§5) covers any residual roster disagreement. Losers **stand down** — they do not add
the task to the local `claimed` set, so they remain eligible to re-home it later if ownership
shifts.

## 3. Claim lifecycle (adaptive path)

For `RingWithIntent`, the body of `claimOwned` becomes, per owned + unclaimed task:

1. **Announce (always, free):** add `selfId` to `intent[task]` and apply. Piggybacks on the
   delta gossip the Quilters already send — near-zero marginal cost.
2. **Decide whether to settle:**
   `inDisagreementWindow = (now − lastRingChangeAt) < settleWindow` **OR** a competing
   claimant is already visible in `intent[task]`. `lastRingChangeAt` is stamped (via the
   injected `clock`) in `onPeersChanged` and `rebuildRingAndClaim`.
   - **Not in window** (steady state) → execute immediately. Zero added latency — today's
     path plus one free write.
   - **In window** → `delay(settleWindow)` on the injected scheduler, then re-read
     `intent[task]`.
3. **Resolve:** if `winner(task) == selfId`, mark `claimed` and execute; else stand down.

For `Ring`, `claimOwned` is exactly today's logic (no announce, no settle).

## 4. Adaptive default rationale

Writing a claim is cheap (one piggybacked entry); *waiting* is the cost. Steady state pays
no wait (the common path stays zero-latency), so `RingWithIntent` is safe as the default —
coordination cost relocates to membership-change events (CALM), not per-task. This is what
makes the default *smart* rather than merely *a default*.

## 5. Liveness backstop (never lose a task) — two layers

- **Liveness-drop (fast path, no new code):** the winner rule intersects `claimants` with
  `effectiveRoster` (`rosterPeers − partitionedPeers`). So when the existing
  `HeartbeatPartitionDetector` marks a won-but-dead winner lost, it leaves `effectiveRoster`,
  the surviving peers re-resolve the winner to the next-lowest live claimant, and the
  re-evaluation fires from `rebuildRingAndClaim` — this falls out of §2's rule, no separate
  path.
- **Lease (catch-all for slow-but-alive winner):** a won claim is valid for `claimLease`.
  If `task` is still pending `claimLease` after a peer lost the tiebreak, that peer
  re-evaluates and proceeds if it is now the lowest *live* claimant past its lease window.
  Worst case: one duplicate after the lease, absorbed by the `Results` ORMap. The lease is
  measured against the injected `clock`, recorded when intent is first observed for the task.

Together these cover dead winners (fast) and stuck-but-alive winners (lease), satisfying the
hard invariant: the worst outcome is a single duplicate, never a stuck task.

## 6. Thread-safety & exception discipline

Unchanged regime. New `intent` reads/writes go through the existing `WarpNode` `lock`
(atomicfu `reentrantLock`); the settle `delay` is kept *outside* the locked section (suspend
calls never hold the lock). No `limitedParallelism(1)` confinement — correct under a
multi-threaded dispatcher. Best-effort sends wrapped in `runCatchingCancellable`. The new
Quilter is closed in `WarpNode.close()` alongside the other two.

## 7. Testing (virtual time)

`StandardTestDispatcher` + injected `clock` + short-cadence config, per repo convention.
Bounded `advanceTimeBy` + `runCurrent`; never `advanceUntilIdle()`; seeded RNG.

- **Steady-state no-tax:** stable ring, `RingWithIntent`; each task executes without
  advancing past `settleWindow` (proves the adaptive skip — no latency regression).
- **Window conflict:** two peers transiently both own a task (disjoint roster views);
  exactly one executes after settle, the other stands down — dup avoided.
- **Won-but-died:** winner announces then is partitioned before executing; the successor
  executes (liveness-drop).
- **Won-but-stuck:** winner announces, its executor never completes but it stays "alive";
  the successor proceeds after `claimLease` (lease backstop).
- **`Ring` strategy regression:** behavior identical to today.
- **Intent GC:** completing a task removes its `intent[task]` entry.

## 8. Delivery — one behavior per PR

A stack of three, each its own green checkpoint:

- **PR 1 — strategy seam.** Introduce `ClaimStrategy`, wire `Ring` as a behavior-identical
  refactor of today's `claimOwned`. Default `Ring`. Pure refactor; proves the seam without
  changing semantics. _(Collapsible into PR 2 if a pure-refactor PR is unwanted.)_
- **PR 2 — intent register + `RingWithIntent`.** New Quilter/channel, announce + settle +
  winner rule + liveness-drop. Default flips to `RingWithIntent`. Includes the
  steady-state-no-tax, window-conflict, won-but-died, and intent-GC tests.
- **PR 3 — lease backstop.** The `claimLease` catch-all + the won-but-stuck test.

Each PR: `explicitApi()` clean, `detektAll` clean, full `./gradlew build` locally before
push, auto-merge once `ci-required` green.

## 9. Docs

- Extend `docs/warp-foundation.md` with a "The intent-register layer" section (accessible →
  technical, per repo doc rules), cross-linking `docs/warp-spike-results.md`'s intent-register
  recommendation.
- KDoc on `ClaimStrategy` + `WarpNode.strategy`; a `commonSamples` function showing the
  strategy choice (compiles as `commonTest`).
- References policy: keep it standalone — no cross-repo / external-tracker citations.

## Open tuning calls (resolved at implementation, low-risk)

- `DEFAULT_SETTLE_WINDOW` / `DEFAULT_CLAIM_LEASE` concrete values — derive sensible defaults
  from `HeartbeatConfig` cadence; pin in PR 2/3 with a comment on the reasoning.
- Whether to fold PR 1 into PR 2 (pure-refactor-PR aversion) — dispatcher's call at plan time.
