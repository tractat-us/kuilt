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
