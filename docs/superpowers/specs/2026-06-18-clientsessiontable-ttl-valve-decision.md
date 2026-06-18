# Decision — no index-horizon idle TTL valve for `ClientSessionTable`

- **Resolves:** #571 (`needs-design`) — opt-in index-horizon idle TTL as a last-resort
  `ClientSessionTable` size valve.
- **Supersedes:** the "policy B" deferral in
  [`2026-06-17-dedup-gc-v2-sub-spec.md`](2026-06-17-dedup-gc-v2-sub-spec.md) ("Out of
  scope — index-horizon idle TTL … may return *later* as an opt-in last-resort size valve").
- **Status:** Decided (2026-06-18) — **resolved-by-design, not implemented.**

## Decision

Do **not** build the opt-in index-horizon idle TTL. Dedup GC v2 — supersession prune plus
`closeSession()` — is the complete bounding story for every consumer kuilt has today. The
valve is not redundant defence-in-depth; it is a tool with **no quadrant where it is both
safe and necessary** for any controllable consumer.

## Why — the quadrant argument

A `ClientId` is *durable* precisely because its owner wants cross-crash exactly-once — i.e.
it reserves the right to replay the same `requestId` after an arbitrary gap. An
index-horizon TTL evicts on "this id's high-water-mark hasn't advanced within N committed
indices." But **"mark idle for N indices" is indistinguishable from "dormant but alive"** —
a durable client that crashed and is recovering, or simply quiet, looks identical to a dead
one. That distinction is knowable *only* by the consumer, which is exactly what
`closeSession()` already encodes. So:

| Consumer's knowledge of the durable id | Right tool | TTL verdict |
|---|---|---|
| **Knows it is finished** | `closeSession(id)` — deterministic, shipped, exact | **Redundant** |
| **Cannot know** | nothing is safe — the id may still retry | **Violates exactly-once** |

There is no middle. And the apparent fourth option — "willing to accept at-least-once for
unbounded distinct ids" — is *already the auto path*, which is self-bounded by supersession
prune (a new `auto:$nodeId-…` incarnation evicts its dead same-family siblings). Choosing a
**durable** id over an auto id already encodes the exactly-once-over-memory trade the TTL
would re-litigate. A consumer that wants memory bounded above exactly-once should use auto
ids; one that wants exactly-once should not have its ids silently evicted by a timer.

## The one trigger that would reopen this

A consumer brokering **externally-owned durable ids with no lifecycle signal**: a
multi-tenant relay/gateway where remote clients supply their own durable id (cross-crash
exactly-once *while active*) and may vanish forever (app uninstalled) — so no
`closeSession` ever arrives, auto ids don't apply, and the table grows for the life of the
cluster. There an operator would knowingly accept "a client dormant for a huge index span
that somehow returns may double-apply once" in exchange for bounded memory.

**Confirmed hypothetical as of 2026-06-18** — no current or near-term kuilt consumer is
that gateway. Per the issue's own gate ("a valve nobody can trigger is dead weight; want a
concrete scenario that overflows *despite* correct `closeSession` use before committing"),
this is a YAGNI close, not a denial that the scenario could ever exist.

## Ready-to-build shape, if that trigger ever lands

Recorded so a future session does not re-derive it:

- **Opt-in, default off** — no behaviour change for existing consumers; a constructor/config
  knob enables it.
- **Index-horizon only, never wall-clock** — evict a durable entry whose high-water-mark
  hasn't advanced within a committed-index horizon, so pruning stays a deterministic pure
  function of the committed stream (replica-identical; a snapshot is always already-pruned).
  The horizon source is the last-applied index delta available in the apply loop.
- **Forces a snapshot format bump** to `Map<ClientId, (requestId, index)>` — GC v2's central
  win was keeping the v1 `Map<ClientId, Long>` format byte-for-byte. Re-introducing the valve
  pays that cost; weigh it then.
- **Documented at-least-once floor** for evicted entries (a subsequent request re-opens at
  mark 0 and at worst re-applies — never a silent drop); durable exactly-once is preserved
  for every non-evicted id.

## Out of scope / unchanged

- Supersession prune and `closeSession` — the shipped v2 mechanisms — are unchanged.
- Wiring `closeSession` end-to-end on a committed close op (path C) remains tracked by #570.
- Snapshot / `InstallSnapshot` format — unchanged; no version bump lands with this decision.
