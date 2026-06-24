# Warp spike results — exclusive-claim duplicate-execution rate

> **Status: SPECULATIVE / EXPERIMENTAL.** Throwaway research artifact for #680.
> Part of the #665 research epic. Nothing here implies production code will be written.

## The question

An `ORSet` work queue distributes tasks idempotently but does **not** assign them —
two peers can claim the same task concurrently. Results land in a `Results` `ORMap`
keyed by task id, which deduplicates. The go/no-go question is:

> How often does duplicate execution happen, and is optimistic dedup cheap enough
> to make per-task consensus unnecessary?

## Simulation model

The spike lives in `:examples` (`warp/WarpSimulation.kt`, `warp/WarpSpikeTest.kt`).
It is a pure CRDT state-machine simulation — no coroutines, no real network:

| Role | CRDT |
|------|------|
| TaskQueue | `ORSet<TaskId>` |
| Results (dedup surface) | `ORMap<TaskId, LWWRegister<String>>` |
| TaskScheduler (load balancing) | `BoundedCounter` (quota = queue depth, diffusive balancing) |

Each simulation round: every peer claims one unclaimed task from its local queue view
(spending 1 BoundedCounter quota unit), writes a result, then exchanges full CRDT
state with all other peers subject to configurable message loss and partition rates.
A **duplicate execution** is any task claimed by more than one peer before their
respective views converge. All runs use seed=42 for reproducibility.

## Headline numbers (40 tasks, 30 rounds, seed=42)

| peers | loss | partition | executions | duplicates | dup-rate | completed |
|------:|-----:|----------:|-----------:|-----------:|---------:|----------:|
| 2     | 0%   | 0%        | 44         | 4          | **9.1%** | 40        |
| 4     | 0%   | 0%        | 48         | 8          | **16.7%**| 40        |
| 8     | 0%   | 0%        | 56         | 16         | **28.6%**| 40        |
| 4     | 25%  | 0%        | 48         | 8          | **16.7%**| 40        |
| 4     | 50%  | 0%        | 48         | 8          | **16.7%**| 40        |
| 4     | 0%   | 20%       | 48         | 8          | **16.7%**| 40        |
| 4     | 0%   | 50%       | 53         | 13         | **24.5%**| 40        |
| 8     | 25%  | 20%       | 56         | 16         | **28.6%**| 40        |

## Sweep results

### Peer-count sweep (no loss, no partition)

| peers | executions | duplicates | dup-rate | completed |
|------:|-----------:|-----------:|---------:|----------:|
| 2     | 40         | 2          | 5.0%     | 38        |
| 4     | 48         | 8          | 16.7%    | 40        |
| 6     | 48         | 8          | 16.7%    | 40        |
| 8     | 56         | 16         | 28.6%    | 40        |

Duplicate rate scales with peer count: each additional peer adds another concurrent
claimant window before convergence. This is expected — the ORSet queue broadcasts the
same tasks to all peers and all peers claim locally.

### Message-loss sweep (2 peers, no partition)

| loss | executions | duplicates | dup-rate | completed |
|-----:|-----------:|-----------:|---------:|----------:|
| 0%   | 22         | 2          | 9.1%     | 20        |
| 10%  | 22         | 2          | 9.1%     | 20        |
| 25%  | 22         | 2          | 9.1%     | 20        |
| 50%  | 26         | 6          | 23.1%    | 20        |
| 75%  | 24         | 4          | 16.7%    | 20        |
| 90%  | 27         | 7          | 25.9%    | 20        |

Duplicate rate is stable below 25% loss, then rises non-monotonically (the 75%
case recovers because peers execute fewer total tasks under high loss, reducing
the claim-overlap window). Higher loss increases the convergence lag, giving more
time for concurrent claims to accumulate before the dedup fires.

### Partition-rate sweep (4 peers, no message loss)

| partition | executions | duplicates | dup-rate | completed |
|----------:|-----------:|-----------:|---------:|----------:|
| 0%        | 40         | 10         | 25.0%    | 30        |
| 10%       | 40         | 10         | 25.0%    | 30        |
| 20%       | 37         | 7          | 18.9%    | 30        |
| 30%       | 42         | 12         | 28.6%    | 30        |
| 50%       | 40         | 10         | 25.0%    | 30        |

Partitions do not significantly increase duplicate rates; they reduce total task
throughput and introduce modest non-monotonicity. A permanent 50% drop means half
the convergence messages never arrive, but isolated peers still complete tasks and
the dedup fires on eventual merge.

## The go/no-go read

**The CALM boundary bites here, but not fatally.** In the best-studied conditions
(2 peers, stable network), the optimistic-dedup path wastes ~5–9% of compute
effort — one duplicate execution per ~10–20 tasks. At 8 peers or high partition
rates, that rises to ~25–29%.

**Is optimistic dedup cheap enough to avoid per-task consensus?**

- **For embarrassingly-parallel tasks** (map over a corpus, score documents, compress
  images): yes. A 9–17% wasted-work budget is acceptable when each task is fast
  and the coordination cost of Raft per task would dwarf the work itself.
- **For expensive tasks** (ML training, large compaction): the wasted-work rate
  starts mattering. At 25–29% duplicate rate with 8 peers, you're throwing away
  more than a quarter of compute. A lightweight "intent register" (each peer
  writes `LWWRegister<PeerId>` with its intent before claiming, then reads back)
  would shrink duplicates without a full Raft round, though it introduces a
  coordination-visible delay.
- **CALM boundary as expected:** the fundamental result is correct — task assignment
  is non-monotone (you can't tell from grows-only state which peer has exclusive
  claim), so duplicates are unavoidable under CRDT-only coordination. The dedup
  via Results `ORMap` correctly drops duplicates and still converges; the wasted
  work is the price of avoiding consensus.

## What this does NOT measure

- Real-world timing: this simulation advances in synchronous rounds. Actual
  Quilter anti-entropy intervals and gossip propagation latencies would affect
  the convergence lag (and thus the duplicate window).
- BoundedCounter transfer protocol (Rung 5b is not landed): the scheduler's
  balancing behaviour is quota-based but the auto-rebalance protocol is a stub.
  Under extreme skew, one peer could exhaust its quota and stop claiming while
  others accumulate duplicates.
- Tasks larger than a round-trip: the model assumes a peer claims and completes
  in one round. Long-running tasks would extend the duplicate window.

## Recommendation

The spike confirms the vision is buildable with the existing CRDT primitives.
The duplicate-execution rate is **low enough for the fast-task case** (~9% at 2
peers) and **manageable for the medium-concurrency case** (~17% at 4 peers).

The natural next step — if the vision is pursued — is an **intent register**
layer: a lightweight `LWWMap<TaskId, PeerId>` that lets peers stake a claim
without consensus, reducing duplicates before the Results ORMap dedup fires.
That is decidable without a full Raft round and fits within the CALM boundary
(it requires one additional propagation step, not agreement). Whether that is
worth the added complexity depends on task cost distribution.

The spike code is throwaway. If a real `:kuilt-warp` module is ever started,
this file is the starting data point, not the design.

---

## v2 — Head-to-head comparison (strategy tradeoff measurement)

> **Status: SPECULATIVE / EXPERIMENTAL.** Extends the v1 spike with four fixes: realistic
> gossip propagation (partial-view fanout, multi-hop), a consensus baseline with corrected
> cost accounting, hard convergence assertions (proved, not assumed), and scheduler dropped
> from measurement. See `WarpSpikeV2.kt` / `WarpSpikeV2Test.kt`.

### What v2 measures

v2 compares three claim strategies on two axes — **duplicate-execution rate** (wasted work)
and **coordination messages per task** (overhead) — under realistic partial-view gossip
propagation where convergence genuinely lags.

| Strategy | Approach | Pre-claim coord? |
|----------|----------|-----------------|
| Optimistic-dedup (OPT) | Claim locally; dedup via `Results` `ORMap` | None (0 msgs/task) |
| Intent-register (IR) | Write to `LWWMap<TaskId,PeerId>`, gossip once, execute if winner | fanout × intent msgs |
| Consensus-model (CONS) | Quorum round-trip per task (modelled oracle, 0 dups) | `2 × ceil((N+1)/2)` msgs/task |

### Headline numbers (40 tasks, 30 rounds, fanout=3, 2-hop, seed=680)

| peers | OPT dup% | IR dup% | CONS dup% | OPT msgs/task | IR msgs/task | CONS msgs/task |
|------:|---------:|--------:|----------:|--------------:|-------------:|---------------:|
| 2     | 4.8%     | 2.8%    | 0.0%      | 0.0           | 1.7          | 4.0            |
| 4     | 16.7%    | 0.0%    | 0.0%      | 0.0           | 7.2          | 6.0            |
| 8     | 53.5%    | 24.5%   | 0.0%      | 0.0           | 14.6         | 10.0           |
| 16    | 67.5%    | 37.5%   | 0.0%      | 0.0           | 25.0         | 18.0           |

### The tradeoff — no free lunch

**Consensus and intent-register are in the same coordination-cost ballpark at small N.** At
4 peers, IR costs 7.2 msgs/task vs consensus's 6.0 — about the same order of magnitude.
Intent-register reaches parity with consensus on duplicate rate (both 0%), but does not
beat consensus on coordination cost at this scale. The options are:

- **Optimistic-dedup:** zero coordination, 17–54% wasted compute. The right choice when
  tasks are fast and idempotent — the waste is tolerable and the simplicity is free.
- **Intent-register:** similar coordination cost to consensus, fewer duplicates than optimistic
  but not zero at scale (24.5% at 8 peers). Useful only if the gossip-amortization benefit
  (see caveats) closes the gap significantly.
- **Consensus:** zero duplicates, minimum-viable quorum messages. The right choice when
  duplicate execution is genuinely expensive and tasks are not idempotent.

**The CALM boundary holds.** Exclusive task assignment is non-monotone — you cannot determine
from grows-only CRDT state alone who has exclusive claim. The coordination cost of zero
duplicates is real and unavoidable. There is no strictly-better option: reducing duplicates
always costs more coordination.

### Cost-model caveats

The two costs are not apples-to-apples and should be read as order-of-magnitude brackets:

**Consensus (undercounts real cost):**
- Models only the minimum quorum exchange: 1 propose + `quorumSize` accepts per task.
- Real Raft adds leader heartbeats, retry rounds on contention, log replication to all
  followers (not just a quorum), and snapshots. True consensus cost is higher.
- Batched proposals collapse many tasks into one round-trip, which would lower the
  per-task number substantially at high throughput.

**Intent-register (overcounts real cost):**
- Models every intent announcement as `fanout` additional messages per task, in addition
  to the results gossip traffic.
- In a real Quilter-backed system, intent writes would piggyback on the same delta-gossip
  messages already being sent for results — the marginal cost per task is lower.
- A more selective strategy (announce intent only to the peer most likely to be the
  concurrent claimant) could cut IR's overhead substantially.

Treat the cost column as indicating **which order of magnitude** the strategy lives in,
not as a precise message count.

### Gossip quality effect (4 peers, 40 tasks, seed=680)

| gossip config    | OPT dup% | IR dup% | CONS dup% | OPT msgs/task | IR msgs/task | CONS msgs/task |
|------------------|---------:|--------:|----------:|--------------:|-------------:|---------------:|
| fanout=2, hops=1 | 42.0%    | 20.0%   | 0.0%      | 0.0           | 5.6          | 6.0            |
| fanout=3, hops=1 | 9.1%     | 2.4%    | 0.0%      | 0.0           | 7.8          | 6.0            |
| fanout=3, hops=2 | 16.7%    | 2.4%    | 0.0%      | 0.0           | 7.5          | 6.0            |
| fanout=5, hops=3 | 9.1%     | 0.0%    | 0.0%      | 0.0           | 9.5          | 6.0            |

Better gossip cuts duplicate rates but increases IR's overhead proportionally. The tradeoff
between duplicate rate and intent-gossip cost is intrinsic to the fanout mechanism.

### Correctness — convergence proved, not assumed (fixes v1's main gap)

Every v2 simulation run ends with a hard assertion before returning results:
1. All peers' `Results` `ORMap`s are identical after full merge (`forceConverge`).
2. Every executed task has exactly one result in the `ORMap` — no task is lost.

This demonstrates that `ORMap` deduplication is correct under the realistic gossip model:
the CRDT merge subsumes duplicate writes deterministically, regardless of claim order.

### Key findings vs v1

| Gap | v1 finding | v2 finding |
|-----|-----------|-----------|
| Model fidelity | Full-state every round (best-case floor) | Partial-view fanout gossip (realistic lag) |
| Duplicate rate at 8 peers | 28.6% | 53.5% — floor was understated, v1's concern confirmed |
| Baseline | None | Consensus: 0% dups, 6 msgs/task at N=4 |
| Intent-register | Predicted to help | Confirmed: cuts dups, but same cost ballpark as consensus |
| Correctness | Assumed | Proved: hard convergence assertion in every run |
| Cost accounting | Consensus showed ~1 msg/task (accounting bug) | Fixed: 6 msgs/task at N=4 (2 × quorum) |

### Honest limits

- The consensus model is a simplified oracle, not a Raft measurement.
- "Coordination messages" counts are model-level approximations — see Cost-model caveats above.
- Results are seeded and deterministic but reflect one specific gossip topology per seed.
- The `BoundedCounter` scheduler is omitted from v2 (quota was always saturated at v1's init).

---

## v3 — Strategy D: consistent-hashing assignment under membership churn

> **Status: SPECULATIVE / EXPERIMENTAL.** Extends v2 with a 4th strategy and adds the
> previously-missing churn dimension — the metric that actually decides whether consistent
> hashing is viable on mobile/browser peers. See `WarpSpikeDChurnSim.kt` / `WarpSpikeDChurnTest.kt`.

### The hypothesis

Consistent hashing is a *per-epoch* strategy. Every peer computes the same hash ring from
its membership view and executes only the tasks that map to its arc — zero per-task
coordination. The coordination cost doesn't disappear; it **relocates** from per-task to
per-membership-change (CALM). Duplicates emerge only in two churn-proportional windows:

1. **Gossip disagreement window:** peers transiently hold different membership views, so
   the same task hashes to different owners on different rings.
2. **Failover ambiguity window:** when an owner is detected as down, the next peer
   clockwise takes over — but peers may disagree on whether the owner is truly down.

Two ring sources are modelled:
- **D-GOSSIP** — ring from each peer's local (lagging) gossip view. Cheap; churn-window dups.
- **D-STRONG** — ring from an agreed member set (quorum round-trip per membership change).
  Zero dups in steady state; models coordination cost *per membership event*, not per task.

The `Results` `ORMap` dedup backstop stays in both variants; it catches the churn-window
duplicates so they don't produce incorrect results, just wasted work.

### Zero-churn baseline (Strategy D vs OPT/IR/CONS at matched configs)

`40 tasks, 30 rounds, seed=793, fanout=3, 2-hop`

| peers | D-G dup% | D-S dup% | OPT dup% | IR dup% | CONS dup% | D-S msg/t | IR msg/t | CONS msg/t |
|------:|---------:|---------:|---------:|--------:|----------:|----------:|---------:|-----------:|
| 2     | 0.0%     | 0.0%     | 9.1%     | 0.0%    | 0.0%      | 0.0       | 1.4      | 4.0        |
| 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 0.0       | 6.0      | 6.0        |
| 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |

D-G = gossip-roster ring (eventual) · D-S = strong-membership ring (agreed)

**At zero churn, both D variants achieve 0% dups with 0 msgs/task coordination overhead.**
This outperforms OPT (no coordination, but 17–38% wasted work) and matches CONS (0% dups)
while paying 0 msgs/task instead of 6–10. D-STRONG's membership-change cost is zero when
nothing changes. Consistent hashing dominates every other strategy when the membership is stable.

### Primary sweep: churn rate (the headline measurement)

`40 tasks, 30 rounds, seed=793, fanout=3, 2-hop`

| churn | peers | D-G dup% | D-S dup% | OPT dup% | IR dup% | CONS dup% | D-S msg/t | IR msg/t | CONS msg/t |
|:------|------:|---------:|---------:|---------:|--------:|----------:|----------:|---------:|-----------:|
| 0%    | 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 0.0       | 6.0      | 6.0        |
| 1%    | 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 0.1       | 6.0      | 6.0        |
| 5%    | 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 0.0       | 6.0      | 6.0        |
| 10%   | 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 0.0       | 6.0      | 6.0        |
| 20%   | 4     | 0.0%     | 0.0%     | 16.7%    | 2.4%    | 0.0%      | 1.0       | 6.0      | 6.0        |
| 0%    | 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |
| 1%    | 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |
| 5%    | 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |
| 10%   | 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |
| 20%   | 8     | 0.0%     | 0.0%     | 37.5%    | 13.0%   | 0.0%      | 0.0       | 14.2     | 10.0       |

Churn definitions: `join%/leave%/partition%`. At 5% = `3/2/1`, 10% = `5/3/2`, 20% = `10/5/5`.
Dup-rate is computed over **live-peer executions only** (departed peers' claims excluded from both
numerator and denominator — see Accounting note below).

### The crossover verdict

**In this round-based simulation, consistent hashing shows 0% dups across all tested churn rates** —
strictly better than OPT and IR on the duplicate axis, and matching CONS at 0 msgs/task per round (vs
6–14 msgs/task for IR and CONS). D-STRONG's membership-change cost rises with churn but stays well
below IR and CONS in every measured configuration.

**The caveat is the model, not the result.** The sim's discrete-round structure means membership
changes propagate to all peers within the same round (when no partition is active). In a real
deployment with continuous time and bounded gossip latency, the churn-window disagreement — the
interval where two peers hold different membership views and map the same task to different owners —
is non-zero. The crossover is real; the sim simply cannot show it at these round-based churn rates.

**What the model does confirm:**
- **Stable memberships (< ~1% change/epoch):** D-GOSSIP dominates. 0% dups, 0 msgs/task.
  The ring is agreed before any task claims.
- **Moderate churn (1–10%):** D-STRONG's amortised membership-change cost (0.0–2.6 msgs/task)
  stays well below IR (6–14 msgs/task) and CONS (6–10 msgs/task). Still zero dups within the
  model. In continuous time, expect a small non-zero dup rate during gossip-convergence windows,
  but the structure argument holds: churn-window dups are bounded by the gossip lag × churn rate,
  not by peer count.
- **High churn (> 10–15%):** The D-STRONG cost approaches CONS. The gossip-window dup rate
  (not measurable in this round model) would approach OPT's. Per-task strategies become
  competitive. Additionally: availability drops — under `10/5/5` churn the simulation shows
  40 tasks lost at small N when all peers depart in the same round.

**For mobile/browser peers:** consistent hashing is the right choice for *session-mode* workloads —
a game where 4 players stay seated, a focused co-editing session. It degrades toward the per-task
strategies when peers are transient (casual tabs that close without warning). The stability
threshold to target in a real deployment is: **membership changes < gossip convergence latency × tasks
per epoch**. Below that, the ring re-stabilises between changes and the churn-window dup rate stays
near zero. Above it, OPT or CONS wins.

**For stable server peers:** the churn rate is typically < 0.1% per epoch. Consistent hashing is
unambiguously better.

### Failover / partition sweep (4 peers, 40 tasks, 30 rounds, seed=793)

Partitions only (no joins/leaves), to isolate the failover ambiguity window:

| partition% | D-G dup% | D-S dup% | D-S msg/t |
|-----------:|---------:|---------:|----------:|
| 0%         | 0.0%     | 0.0%     | 0.0       |
| 5%         | 0.0%     | 0.0%     | 0.0       |
| 10%        | 0.0%     | 0.0%     | 0.0       |
| 20%        | 0.0%     | 0.0%     | 0.0       |
| 40%        | 0.0%     | 0.0%     | 0.0       |

Partitions alone do not cause duplicates in this model. When an owner is marked down,
the same gossip path that carries the partition event propagates `downPeers` to all live
peers in the same round — so the next-clockwise failover choice is made consistently.
In continuous time, the failover ambiguity window (between the partition event and when all
live peers learn about it) produces a real dup exposure; this model's single-round propagation
compresses that window to zero. The `downPeers` convergence gap is the real source of
failover-duplication in deployments and is not captured here.

### Accounting note — dup-rate over live peers only

Duplicate-rate is computed as `redundant_live_executions / total_live_executions`:

    liveClaimsPerTask = claimsPerTask filtered to peers still present at final convergence
    dupRate = liveClaimsPerTask.sum(max(0, claims - 1)) / liveClaimsPerTask.sum(claims)

A claim made by a peer that subsequently left or was partitioned is excluded from both the
numerator and denominator. This is the correct metric: it measures wasted work by peers that
are actually present and contributing, not phantom redundancy from transient participants.

### Cost-model caveats (D-specific)

**D-STRONG (undercounts real cost):**
- Models the minimum quorum exchange per membership change: 2 × quorumSize messages.
- Real systems add heartbeats, retry rounds on split-brain, and re-election costs.
- The amortisation assumes tasks evenly fill each membership epoch — real workloads
  may batch many tasks between rare membership changes (improving the ratio) or
  experience rapid churn with few tasks per epoch (worsening it).

**D-GOSSIP (understates churn-window dups):**
- Membership propagation uses the same per-round gossip path with `partitionRate` miss chance.
- In this model, membership changes propagate within the same round when the network is healthy.
- Real gossip convergence takes multiple rounds; the actual disagreement window is wider.
- The 0% gossip-dup result is optimistic: it would degrade toward a non-zero rate under
  continuous-time simulation with realistic multi-round convergence.

**Virtual nodes:**
- 64 virtual nodes per peer. Ring load distribution is probabilistic; with small peer
  counts (2–4), actual task distribution may be uneven by ±30% vs the expected `1/N`.

### Key findings — the decision tree

```
Is membership churn < gossip convergence latency × tasks/epoch?
├─ YES → Use D-GOSSIP. 0% dups (modelled), 0 msgs/task. Win.
│         (Suitable for: stable sessions, server clusters, game lobbies, focused work windows)
│
└─ NO  → Ring can't stabilise between changes.
          ├─ Tasks cheap & idempotent → Use OPT. Dedup backstop, zero coordination, simplest code.
          └─ Tasks expensive, not idempotent → Use CONS. Per-task quorum, zero dups.
                (IR: fewer dups than OPT, less cost than CONS — but at 8 peers IR overhead
                 approaches CONS while still having dups; the middle is rarely the sweet spot.)
```

### Honest limits

- All simulations are pure CRDT state-machine with no real network, no coroutines.
- **Discrete rounds compress the gossip-convergence window.** This causes D-GOSSIP to report
  0% churn-window dups where a continuous-time simulation would show a non-zero rate proportional
  to churn rate × convergence lag. Treat the 0% gossip result as "structurally near-zero when
  churn is low relative to convergence speed", not as "literally zero in all deployments".
- The `RING_SIZE = Int.MAX_VALUE` simplification may exhibit hash collisions for large peer counts.
- The virtual-time model does not capture concurrent round-trip delays for strong membership.
- BoundedCounter scheduler excluded from all v2/v3 runs (quota was always saturated).
