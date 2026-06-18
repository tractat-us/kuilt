# Design — `gameNode` bootstrap, resolved (#480)

_Resolves the bootstrap-enumeration question left open by the
[sub-spec](2026-06-17-game-node-bootstrap-sub-spec.md). Part of epic
[#474](https://github.com/tractat-us/kuilt/issues/474). **Amends sub-spec
decision D1.**_

## The open question

The sub-spec fixed the *shape* of `gameNode` but deferred the hard part: how a
peer that does **not** know the other peers' `NodeId`s bootstraps a single Raft
cluster over a `Seam`, symmetrically, without two clusters forming. D1 assumed a
symmetric one-call API (`gameNode(seam, peerCount)`) where every peer discovers
the roster and they converge on one cluster — leaning on the claim that "Raft's
joint consensus ensures only one topology wins."

That claim is **false**, and the brainstorm that produced this doc proved why.

## The barrier (why symmetric-discovery can't be cheap)

Independently-bootstrapped Raft clusters cannot be merged. Joint consensus
reconfigures *one* cluster; it does not fuse two. Raft has no log-merge — by
design, its State Machine Safety property *forbids* two different entries
committed at the same index. So the only way to be safe is to never let two
clusters form in the first place. That requires the peers to **agree on exactly
one seed** before anyone bootstraps.

Agreeing on one seed from a contended, discovered membership is **consensus**,
and consensus is provably not coordination-free:

- **CALM theorem** (Hellerstein & Alvaro, *CACM* 2020): a problem is
  coordination-free iff it is *monotone*. "Accumulate presence" is monotone (a
  CRDT can do it). "Elect exactly one seed" / "freeze the roster" requires a
  negation ("no other seed, no later member") — non-monotone — so it provably
  needs coordination.
- **Consensus number 1** (Herlihy): a join-semilattice (which *every* CRDT in
  `:kuilt-crdt` is) cannot solve consensus for n ≥ 2. Not an engineering limit —
  an impossibility.

We surveyed the CRDT zoo against "elect one seed." Every type is a
join-semilattice; none can ratify a single contended choice. `BoundedCounter` is
the nearest (a `≤ 1` bound = a single token) and is instructive: it enforces
invariants coordination-free **only** by pre-distributing quota, and contended
acquisition routes through `transfer` — a coordinated round-trip. It relocates
the consensus; it doesn't remove it.

So a symmetric, no-roster, discover-then-elect bootstrap costs **one irreducible
consensus round** (plus retry/fallback for a seed that dies before quorum). That
is the only shape that hits the wall.

## The resolution: appoint, don't elect

Election hits the wall; **appointment walks around it.** And every session
already carries an appointment:

> A `Seam` is symmetric, but the `Rendezvous` that created it is **not** — one
> peer called `weave(Rendezvous.New)` (created the session) and the rest called
> `weave(Rendezvous.Existing)` (joined it). This is true on every fabric:
> WebSocket host/client, mDNS advertise/discover, WebRTC offer/answer. There is
> always a first mover, and the application already knows which it was.

Appointing the session's creator as the seed gives everything the symmetric
discover-and-elect path wanted — no roster up front, early start, latecomer
admission — with **zero consensus round**, because there is exactly one creator
by construction and therefore no two-seed race.

## Decision: two entry points, drop the third shape

### (1) Host / join — honor the session asymmetry _(primary)_

```kotlin
// creator of the session:
val node = scope.gameHost(seam, /* admit policy */)   // bootstraps {self}, admits joiners
// everyone who joined the session:
val node = scope.gameJoin(seam)                        // starts as learner, host admits to voter
```

The host bootstraps a singleton-voter cluster (`ClusterConfig.ofVoters({self})`),
elects itself immediately, and admits each joiner as **learner → voter** via
`changeMembership` — this is exactly `ServerCluster.admitLearner`, reused
verbatim. Joiners never self-elect, so no rival cluster can form. No roster is
needed up front; latecomers (even first-time-after-start) are admitted as they
arrive. Maps directly to "create a game, share a code, others enter it."

### (2) Symmetric — roster given _(when matchmaking provides it)_

```kotlin
val node = scope.gameNode(seam, voterIds)   // every peer: identical config, Raft elects leader
```

When a lobby/matchmaking layer hands every peer the same `Set<NodeId>`, there is
no seed to pick: every peer constructs the identical
`ClusterConfig.ofVoters(voterIds)` and Raft's **own** election chooses the
leader. Symmetric, no pre-Raft step, no consensus round. Requires the roster to
exist before connecting.

(1)'s joiner-admit and (2)'s static-config converge on the same end state — a
single Raft cluster — and share most of their implementation.

### (3) Symmetric, discover, elect — **dropped**

The only shape requiring the consensus round. Its *unique* benefit over (1) is
"symmetric **and** no roster **and** no designated host, simultaneously" — and
since every session already has a creator, that third clause is free to give up.
Closed-seat games never need it. Documented here as rejected so a future reader
does not re-derive it; if open-membership-without-a-host ever becomes a real
requirement, this is where the `EphemeralMap` presence layer (below) plus a
quorum-`ACCEPT` round would be added.

## Presence / lobby layer (not consensus)

`EphemeralMap<Ready>` replicated over a `Seam` by `Quilter` remains the right
substrate for the **lobby view** — "who is here, who is ready" — *before* anyone
calls `gameHost`/`gameJoin`: heartbeats, TTL-reaping of dropped peers, an
app-level `ready` flag, a roster everyone converges on. It is a presence display,
**not** the bootstrap mechanism. (This is the RedBlue split: presence is the
coordination-free "blue" part; the seed appointment is the ordered "red" part,
and appointment makes the red part a no-op.)

## Recovery / partitions

Bootstrap runs **once**. After the cluster exists with a durable config + log,
a `Seam` tear is an ordinary partition, resolved by standard Raft: a minority
side cannot reach quorum so it *waits* (never commits divergent state); the
majority progresses; on heal the stale tail is overwritten by AppendEntries.
Inject a persistent `RaftStorage` for crash recovery; `InMemoryRaftStorage`
(the default) suffices for in-process games. `gameHost`/`gameJoin` must **not**
re-run bootstrap on `peers` changes — that would manufacture the split-brain
they avoid. The "3 nodes, 1 survives, recover when quorum returns" scenario is
covered here, identically for (1) and (2).

## Relationship to the sub-spec's other decisions

- **D1 (symmetric one call):** **amended** — replaced by (1) host/join +
  (2) roster-given. Symmetric-discovery-elect is dropped.
- **D2 (`peerCount` required):** retained as a **readiness gate**, not a config
  source. `gameHost` can suspend its return until committed membership reaches a
  caller-supplied minimum (e.g. all seats filled) before the game starts.
- **D3 (learner-then-promote):** retained for (1) — the host admits each joiner
  as learner then promotes. (2) skips it (all voters from t0).
- **D4 (`expectVirtualTime` kept out of production):** unchanged — production
  entry points construct `RaftConfig()` with defaults; a `@TestOnly` overload or
  `:kuilt-raft-test` builder accepts an explicit `RaftConfig`.
- **D5 (`incoming` single-collection):** unchanged — `gameHost`/`gameJoin`/
  `gameNode` own the seam; a game needing chat multiplexes via `MuxSeam`.

## Out of scope / deferred

- **Symmetric discover-and-elect (option 3)** — rejected above; the documented
  home for any future open-membership-without-a-host requirement.
- **Open lobby / dynamic `peerCount`** — (1) already admits latecomers; an
  unbounded open lobby (no target seat count) is a follow-on.
- **Leadership re-balancing** — a host stays leader; `transferLeadership` exists
  if a consumer wants to move it. Not automated here.
- **Persistent storage defaults** — consumer concern; `InMemoryRaftStorage` is
  the in-process default.

## Testing (acceptance criteria for the impl issue)

- **(1) host + join, 2 peers:** `gameHost` on one seat, `gameJoin` on the other
  (`InMemoryLoom`); converge to an all-voters cluster; `TurnSequencer.propose`
  from either seat commits on both `committed` flows. Drive with
  `MultiNodeRaftSim` / `raftRunTest`; tight 5 s timeout.
- **(1) latecomer:** a third `gameJoin` arriving after the first proposal commits
  is admitted and replays the committed log.
- **(2) roster given, 3 peers:** three `gameNode(seam, voterIds)` calls with the
  same roster converge; quorum = 2.
- **No `expectVirtualTime` in production path:** the production entry points
  construct `RaftConfig()` with `expectVirtualTime = false`; assert the standard
  test-dispatcher guard warning is **not** suppressed.
- **`incoming` guard:** a second collector of `seam.incoming` after `gameHost`
  drops Raft messages (documents the constraint).
