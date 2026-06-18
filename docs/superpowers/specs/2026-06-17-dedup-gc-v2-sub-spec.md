# Design — kuilt-raft: ClientSessionTable dedup GC v2 (supersession prune)

- **Issue:** #495 (`needs-design`) — `ClientSessionTable` size/age cap.
- **Builds on:** #484 / #493 — the exactly-once client-serial dedup that shipped
  `ClientSessionTable`, `ClientId`, `DedupKey`. See
  [`2026-06-16-raft-exactly-once-dedup-design.md`](2026-06-16-raft-exactly-once-dedup-design.md),
  whose "Session lifecycle — no GC in v1" section deferred exactly this work.
- **Status:** approved design (policy fork resolved with the user), pre-implementation.

## Problem

`ClientSessionTable` holds a per-`ClientId` high-water-mark `Map<ClientId, Long>`
with **no GC (v1, by design)**. It is serialized into **every** consumer snapshot via
`toBytes()`, so unbounded growth compounds across `InstallSnapshot`.

Stable/durable client ids self-bound the table — their entry updates **in place**. The
entire growth driver is the **auto/ephemeral** path: `ClientId.auto` mints
`"$nodeId-$randomHex"` with a fresh suffix per process incarnation, so a consumer that
restarts (or mints a fresh id per connection) accumulates one **dead** entry per
incarnation, forever.

## The binding insight

The leak is **entirely dead incarnations**, and a dead incarnation can never retry —
its process is gone, and the next incarnation draws a new suffix. So evicting a dead
incarnation re-opens **nothing**. The whole problem reduces to: *prove an incarnation is
dead, deterministically* (the same decision on every replica, from committed state only —
never a local wall-clock or local timer; repo policy "time is a dependency").

The v1 spec already rejected naive LRU/TTL for the right reason: it can evict a **live**
client and re-open its dedup window — "a silent correctness regression for bounded memory,
the wrong trade." v2 must evict only the **provably dead**.

## Decision — supersession prune (A), plus optional explicit close (C)

Two deterministic eviction triggers, both driven from the consumer's apply loop. **No
heuristic, no horizon, no clock.** The map stays `Map<ClientId, Long>`; the snapshot
format is **unchanged**.

### A. Supersession prune (primary — kills the driver)

A `NodeId` is cluster-unique: two **incarnations** of one node are never live at the same
time (incarnation 2 exists only because incarnation 1's process died). So the *arrival* of
a new auto-incarnation **is** the proof of death — no index comparison needed:

> When `shouldApply` records an auto-shaped key `$nodeId-$suffix₂`, evict every other entry
> whose key is auto-shaped with the **same `$nodeId` family** and a **different suffix**, in
> the same apply step.

Because the prune is synchronous with the new incarnation's first applied entry, the map
**never simultaneously holds two siblings** of one family (except transiently inside one
`shouldApply` call). Consequences:

- **Deterministic.** Every replica applies the same committed `shouldApply` stream in the
  same order → the same prunes. Pure function of committed state.
- **Snapshot-stable.** Any snapshot is already pruned, so `toBytes()`/`fromBytes()` keep
  the v1 `Map<ClientId, Long>` shape — **no format version bump, no `lastAppliedIndex`
  field.** (This is why A beats the index-horizon idle policy, which would have forced a
  `Map<ClientId, (requestId, index)>` format v2.)
- **Bounded.** The table holds at most one entry per *live* auto family (one per nodeId)
  plus the durable/stable ids (already self-bounding). Restart churn no longer accumulates.

### C. Explicit close (optional, consumer-driven)

For consumers that *know* a logical client is finished (e.g. a session/`Room` layer that
observes a peer leave), a committed close op evicts cleanly:

```kotlin
/** Drop [clientId]'s high-water-mark. Drive from the apply loop on a committed close op. */
public fun closeSession(clientId: ClientId)
```

Deterministic for the same reason `shouldApply` is — it runs inside the single apply loop
over `RaftNode.committed`. It complements A: A reclaims silent crashes automatically; C
gives a clean teardown for durable ids and graceful disconnects. C alone would **not** fix
#495 (a silent crash never sends a close), which is why A is primary.

## Recognizing the auto family without breaking opacity

Raft-the-transport must keep treating `ClientId.value` as opaque (it "never parses it").
The GC's one structural assumption — *what is the auto family of an id* — is localized to
`ClientId`, the single place that already **owns** the format:

```kotlin
public value class ClientId(public val value: String) {
    public companion object {
        public fun auto(nodeId: NodeId, random: Random): ClientId { /* "$nodeId-$randomHex" */ }
    }
    /**
     * The nodeId family of an auto-minted id, or null if this id is not auto-shaped.
     * Two auto ids share a family iff they are incarnations of the same node.
     * Durable/custom ids return null and are never GC-eligible.
     */
    internal fun autoFamily(): String?
}
```

The auto suffix is a fixed 16 lowercase-hex characters (`ByteArray(8)` → `%02x`×8), so the
shape is unambiguous: `value` matches `…-[0-9a-f]{16}$`, and the family is `value` minus the
trailing `-` + 16 chars. `ClientSessionTable` calls `key.clientId.autoFamily()`; only
non-null families participate in the prune. Everything else (durable, custom, legacy) is
opaque and **never** auto-evicted.

**Recommended hardening (impl decision, not blocking):** to make the family test
*unambiguous* rather than shape-inferred, prefix auto ids with a reserved sentinel
(`"auto:$nodeId-$suffix"`) so `autoFamily()` keys off the sentinel, not a hex-tail regex.
This is a small, additive change to the just-landed (#493) auto format and is safe pre-1.0
(no external consumers pin the auto string; durable ids are unaffected). Without it, the
residual risk is a *durable* id that coincidentally matches `…-[0-9a-f]{16}$` **and** shares
a prefix with a sibling — astronomically unlikely, but the sentinel removes it entirely.
Resolve at implementation; the spec accepts either.

## Safety argument

Eviction is safe ⟺ the evicted entry has no possible future retry the system must
deduplicate.

- **Supersession (A):** the evicted sibling is a *prior* incarnation of a node whose *new*
  incarnation just committed. The prior incarnation's process is gone; it issues no new
  proposals. The only residual is an **in-flight straggler** — a forwarded copy of one of
  its already-applied requests that commits *after* the new incarnation appeared. If that
  straggler is a duplicate (requestId ≤ the evicted high-water-mark), the evicted entry no
  longer suppresses it → one double-apply. **This is exactly the floor the v1 spec already
  documents for the auto path** (Failure modes, "Proposer crash, auto/ephemeral id …
  in-flight straddling command may double-apply (at-least-once). Never a silent drop."). A
  does **not** lower the guarantee — it reclaims memory at precisely the boundary where the
  auto path's exactly-once had already lapsed to at-least-once. **Durable/stable ids are
  never auto-shaped → never pruned → their cross-crash exactly-once is untouched.**
- **Explicit close (C):** the consumer asserts the client is done. If it lies (closes a
  still-active client), the next request re-opens at high-water-mark 0 and at worst
  re-applies — same at-least-once floor, never a silent drop. Document that close means
  "this logical client will not retry."

The cardinal invariant from v1 holds: the system **never** silently drops a real command;
every degraded path is at-least-once, never zero.

## Snapshot / InstallSnapshot integration

**None.** The table remains `Map<ClientId, Long>`; `toBytes()`/`fromBytes()` are byte-for-byte
the v1 format. Pruning is reproducible on replay (it is a function of the committed stream),
and a restored snapshot is already pruned. A follower receiving `Committed.Install` inherits
an already-bounded table. (Contrast the index-horizon policy, which would have required a
format bump.)

## API surface (delta on v1)

```kotlin
public class ClientSessionTable {
    public fun shouldApply(key: DedupKey?): Boolean   // v1 + supersession prune of same-family siblings
    public fun closeSession(clientId: ClientId)        // NEW (C): drop one client's mark
    public fun toBytes(): ByteArray                    // unchanged format
    public companion object { public fun fromBytes(bytes: ByteArray): ClientSessionTable }
}
// ClientId gains internal fun autoFamily(): String?  (format-owning helper; raft stays opaque)
```

`shouldApply` keeps its signature — the prune is internal; no caller change. The consumer
opts into C only if it has a close op; A is automatic.

## Out of scope (still deferred)

- **Index-horizon idle TTL** (policy B). Rejected as primary: it is heuristic, can weaken a
  live durable client's exactly-once, and forces a snapshot-format v2. May return *later* as
  an **opt-in last-resort size valve** with the at-least-once degradation documented — not
  in this issue.
- Raft-owned durable session table (placement A from #484 — raft still owns no compactable
  consumer state).
- Persisting the auto serial counter (a separate exactly-once axis, deferred in #484).

## Testing (drives the impl issue's acceptance criteria)

All raft tests stay on `StandardTestDispatcher` + `FakeRaftNode`; RNG seeded; multi-node via
the canonical `RaftSimulation`/`raftRunTest` harness — never hand-rolled.

- **Supersession evicts the prior incarnation:** apply `nodeA-aaaa…` (reqId 3), then apply
  `nodeA-bbbb…` (reqId 1) → table holds only `nodeA-bbbb…`; the old family member is gone.
- **No cross-family eviction:** `nodeA-…` and `nodeB-…` coexist; applying a new `nodeA-…`
  evicts only `nodeA` siblings, never `nodeB`.
- **Durable ids never pruned:** a stable `ClientId("svc-1")` (non-auto-shaped) survives any
  number of auto incarnations applying around it; its high-water-mark is intact.
- **Snapshot round-trip is already-pruned & format-stable:** `toBytes()` after a supersession
  equals the format a fresh v1 table would emit for the surviving entries; `fromBytes` on a
  pre-prune v1 blob still loads (back-compat), then re-prunes on the next sibling.
- **Determinism under replay:** two independent `ClientSessionTable`s fed the *same*
  committed `DedupKey` stream end byte-identical (`toBytes()` equal) — the prune is a pure
  function of the stream.
- **closeSession (C):** after `closeSession(id)`, a subsequent `shouldApply(DedupKey(id, n))`
  applies (mark reset); driven from a committed close op in an integration test.
- **At-least-once floor preserved (not a regression):** a straggler duplicate of an evicted
  incarnation re-applies (documented at-least-once) — it must **never** be silently dropped
  *and* must never corrupt a live family's mark.
- **autoFamily shape:** auto ids → non-null family equal to the nodeId; durable/custom ids →
  null; (if the sentinel hardening is taken) only sentinel-prefixed ids are families.

## Implementation plan (one PR, TDD)

1. **Failing tests first** (the supersession + determinism cases above), then `ClientId.autoFamily()`,
   then the prune inside `shouldApply`, then `closeSession`. Two commits min (tests, then impl) —
   never squashed.
2. Update `ClientSessionTable` KDoc: replace the "No GC (v1)" paragraph with the v2 contract
   (supersession prune + optional close; durable ids self-bound; the at-least-once floor for
   evicted incarnations).
3. If the type has a Writerside topic or `@sample`, update it to match.
4. The impl issue (`ready`) **closes #495**; this spec PR is docs-only and uses **non-closing**
   language ("Part of #495") per repo rule — never a closing keyword for an implementation issue
   on a docs-only PR.
