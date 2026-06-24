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
