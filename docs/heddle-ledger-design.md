# `EntitlementLedger` — implementation-ready design (heddle H1a/H1b)

> **Status: design, awaiting greenlight.** The internal representation of the one
> genuinely-new CRDT in the `:kuilt-heddle` epic (#1602), pinned down so H1a/H1b can be
> implemented mechanically. Produced by a design-night: four independent candidate
> representations (2 opus, 2 fable), a Fable adversarial review of **each**, and a
> throwaway math prototype (400 seeded randomized runs) that validates the result.
> Companion to the normative [`heddle-design.md`](heddle-design.md) §4/§10. Sub-issue:
> **#1604 (H1b)**; the module scaffold is **#1603 (H1a)**.

## How this was chosen

All four candidates independently converged on the same skeleton, so it is not a coin
toss — it is where the constraints force you. The reviews then found the sharp edges.

- **Unanimous core:** edge-keyed state; three-plus per-edge `GCounter`s; delegation is
  **one `issued(e).inc(r)` increment read with opposite signs at adjacent path levels**
  (no cross-object move — the "balanced pair in one lattice" the design requires);
  `holdings` is **derived**, not stored; transfers are a per-path donor-row matrix
  (verbatim `BoundedCounter`); `piece` is componentwise (the `LatticeProduct` idiom).
  Candidate C *proved* `BoundedCounter`-as-is per attachment cannot do it (its `initial`
  is immutable and it has no cross-scope flow) — the ledger is `BoundedCounter`
  generalized, with `BoundedCounter` its depth-1 instance.
- **Rejected:** candidate B's *stored* per-path aggregates (`delegatedOut`/`childReturned`).
  Its own Fable review broke them: `GCounter` deltas carry **absolute** slot values, so a
  later `delegate` patch's aggregate slot includes earlier ops while the sibling `issued`
  slots are still in flight — the stored aggregate diverges from its defining sum on honest
  partial delivery, breaking conservation on observable states and false-tripping B's
  integrity check. Deriving `holdings` (debit and credit are the *same slot read twice*)
  makes **every observable state internally balanced** — the decisive reason to derive.

## Representation

```kotlin
@Serializable
public class EntitlementLedger private constructor(
    // Immutable topology, keyed by generation. Each value is a grow-only SET of records
    // so a divergent same-id record is RETAINED, not collapsed — a healthy id has a
    // singleton set; size > 1 is exactly what validate reports as RecordDivergence. A
    // single value merged by priority (maxOf/LWW) would both destroy that evidence and
    // resolve a parent-pointer conflict by priority, which heddle-design.md §5.2 forbids.
    // Supplies parent/child/weight; H1b treats every present edge as ACTIVE (lifecycle H2).
    private val records: Map<AttachmentId, Set<AttachmentRecord>>,

    // Root supply, keyed by a unique MintId (NOT by ReplicaId) so mints UNION, never
    // max-collide — see fix 4. Value carries the holder + amount.
    private val minted: Map<MintId, MintRecord>,          // MintRecord(holder: ReplicaId, amount: Long)

    // Per-edge monotone counters. Every GCounter slot (edge e, replica r) is written
    // EXCLUSIVELY by r; merge = per-slot max.
    private val issued:      Map<AttachmentId, GCounter>,   // r delegated across e
    private val returned:    Map<AttachmentId, GCounter>,   // r returned across e (its own GCounter, never PNCounter)
    private val leafSpent:   Map<AttachmentId, GCounter>,   // service charged where e is the captured path's FINAL edge
    private val rollupSpent: Map<AttachmentId, GCounter>,   // service charged where e is a STRICT-PREFIX edge

    // Peer-to-peer transfers AT the path ending at this edge (root path keyed by a
    // sentinel). transfers(e)[from].count(to); row `from` owned exclusively by `from`.
    private val transfers: Map<PathKey, Map<ReplicaId, GCounter>>,

    // Relocation counters — the signed adjustment that lets an already-recorded quantity
    // MOVE between edges without any counter ever decreasing (#1665 slice 1, #1691).
    // Written EXCLUSIVELY by the control plane's log apply; the data plane never touches them.
    private val issuedRelocIn:   Map<AttachmentId, GCounter>,   // credit re-homed ONTO e
    private val leafRelocIn:     Map<AttachmentId, GCounter>,
    private val leafRelocOut:    Map<AttachmentId, GCounter>,
    private val rollupRelocIn:   Map<AttachmentId, GCounter>,
    private val rollupRelocOut:  Map<AttachmentId, GCounter>,
) : Quilted<EntitlementLedger>
```

`PathKey` = the path's final `AttachmentId`, with a `ROOT` sentinel — every path is its
final edge (each group has one active inbound edge; the edge *names* the path), so no
`List<AttachmentId>` is ever a map key. `VirtualTime`/`Weight`/ids per `heddle-design.md`
§2. All service units are overflow-checked `Long` (fix 6).

**The `spent` split is the load-bearing decision (fix 1).** A completed charge on a leaf
path `P` charges `leafSpent(finalEdge(P))` **and** `rollupSpent(e)` for every strict-prefix
edge `e` of `P`. Then:

- `holdings` subtracts `leafSpent(f)[r]` **unconditionally** (a single r-authored slot on
  the path's own final edge — no `isLeaf` test, no cross-edge subtraction);
- `outstanding(e) = issued(e) − returned(e) − leafSpent(e) − rollupSpent(e)` is correct at
  every level (`leafSpent(e)+rollupSpent(e)` = total service charged through `e`);
- `minted = Σ holdings + Σ_e leafSpent(e)` — **topology-independent**.

This is why candidate A's leaf→internal hole is gone: when a spending leaf later gains a
child, its historical `leafSpent(f)` stays subtracted from holdings and stays in the
conservation sum; nothing depends on `isLeaf` at read time. It also dissolves candidate
D's telescoping fragility (holdings no longer differences `spent` across children, so no
"sum-domination" precondition exists to violate).

### Relocation counters — a net decrease without a decrement (#1665 slice 1)

A generation is sometimes retired with entitlement still riding on it (the advisory-retire
race), and making the child whole means **moving** an already-recorded quantity onto the
generation that replaced it. A grow-only `GCounter` cannot go down, so the move rides a
*second* monotone counter that cancels the first — the `PNCounter` idiom, per edge per slot:

```
effIssued(e)[r]      = issued(e)[r]      + issuedRelocIn(e)[r]
effLeafSpent(e)[r]   = leafSpent(e)[r]   + leafRelocIn(e)[r]   − leafRelocOut(e)[r]
effRollupSpent(e)[r] = rollupSpent(e)[r] + rollupRelocIn(e)[r] − rollupRelocOut(e)[r]
```

Moving `δ` units from `s` to `t` bumps `reloc.out(s)[r] += δ` **and** `reloc.in(t)[r] += δ`.
Every *stored* component still only grows; the effective value is derived and may fall,
exactly as `outstanding`/`holdings` already do. There is no `issuedRelocOut` — issuance is
never net-decreased. Every read that used to name a base counter now names its effective
value: `edge()`/`EdgeSummary`, `netInflow`, `holdings`, `leafSpentTotal()`, and `validate()`'s
`PerEdgeSafety`.

**Slot ownership is what makes it sound.** The base counters on a *live* edge belong to the
data plane (replica `r` writes its own slot, only locally-derived values); the base counters
on a *retired* edge may be written by the control plane, because no data-plane mutator can
author them again; and the relocation counters belong to the control plane **exclusively**.
So the reconciliation re-home credits `issuedRelocIn(liveEdge)[r]` rather than fabricating an
absolute on the contended base `issued(liveEdge)[r]` that `r`'s own `delegate` writes
concurrently — two writers on one max-joined slot silently erase one side, with conservation
*and* per-edge safety blind to the loss (#1691).

**Spend relocation is enabled, behind the quiesce fence (#1693).** The move drains the retired
edge to zero headroom, so a straggler charge arriving afterwards would leave a permanently
unclearable per-edge-safety violation. What makes it safe is a per-peer barrier recorded in the
log: `Quiesce(s)` commits, every peer marks `s` locally unwritable *atomically with its own
mutator execution* and acks its own final slot values, and the relocation **magnitude is derived
at apply time from those log-recorded acks** — never read from a proposer's gossip view. The
whole recovery, through-service or not, rides that one path
([`heddle-ledger-relocation-design.md`](heddle-ledger-relocation-design.md) §6).

## Mutators

House idiom: check feasibility on `this`; return `Patch<EntitlementLedger>?`, `null` leaves
the caller untouched; the delta is a minimal `EntitlementLedger` carrying only the bumped
slot(s) at their new **absolute** value (max-merge → idempotent), **plus a self-justifying
witness** (fix 2). Overflow-checked adds throughout.

- **`mint(mintId, holder, amount)`** — control-plane only (§9). Delta: `minted[mintId] =
  MintRecord(holder, amount)`. Unique `mintId` per mint ⇒ union, never a lost mint under
  failover (fix 4). The one non-conserving op.
- **`delegate(r, edge, amount)`** — require ACTIVE; `if amount > holdings(parent(edge), r)
  return null`; delta bumps `issued(edge)[r] += amount`. One slot; read `+` at the child
  path, `−` at the parent path. Holder at the child is the same `r` (§4.2).
- **`release(r, edge, amount)`** — require ACTIVE/CLOSING; `if amount > holdings(child(edge),
  r) return null`; delta bumps `returned(edge)[r] += amount`. `−` at child, `+` at parent.
- **`transfer(group, from, to, amount)`** — `from != to`; `if amount > holdings(group, from)
  return null`; append to `from`'s own row `transfers[pathKey(group)][from][to] += amount`.
- **`spend(r, group, amount)`** — `require isLeaf(group)`; `if amount > holdings(group, r)
  return null`; `leafSpent(inbound(group))[r] += amount` and `rollupSpent(e)[r] += amount`
  for each strict-prefix edge `e`. One atomic patch. `cancel = spend(0)` (no-op);
  `actualCost > maximumCost` rejected, never recorded. (`reserve`/`complete` are the H4
  node layer over this; earmarks are node-local, not replicated.)

**Self-justifying witness (fix 2, from the C/A reviews) — and its honest boundary.**
Because a spend/delegate/release is causally *after* the `issued`/`transfers`/`minted`
slots that justify its holdings check, and that causality crosses writer streams (a
`GCounter` has no `causalDots`, and Quilter gives no cross-sender ordering), a naive
`validate` false-fires on honest partial delivery (sees the debit, not yet the credit →
"spent > issued" → quarantines a healthy lineage, divergently). Fix: each
feasibility-consuming patch **also carries the credit slots its check read** (the
observed `issued`/`returned`/`transfers`/`minted` values along the path — absolute
values, already in hand, max-safe), **plus a depth-1 backing of any donor who
transferred into the actor** (the donor's own `issued`/`returned` at that edge, or
`minted` at the root — the donor could only transfer what it held). Then any state
containing the debit also contains a credit ≥ its justification for the **direct and
single-hop-transfer** cases, so the integrity checks below do not fire on that honest
traffic.

**What the witness deliberately does *not* cover.** A transfer's funding is transitive,
and the witness stops at depth 1 (chasing it further is not paid for). So a
**multi-hop transfer-funded** charge — B receives from A, hands to C, C spends — can, on
a *partially-delivered* replica, transiently surface a false `PerEdgeSafety` /
`PersistentNegativeHoldings` until anti-entropy catches up. This is acceptable because
**`validate` is an eventually-consistent *diagnostic*, not a safety gate.** Safety is the
*local* holdings check the mutator runs on the actor's own complete state (every term of
which reads a slot only the actor writes); it never authorizes an overspend regardless of
what any partial `validate` reports. Consumers must gate on the mutator returning `null`,
**not** on `validate().isEmpty()` while rebalancing is in flight. (The alternative —
demote `validate` to converged-state-only, or pay for a full transitive witness — was
weighed and rejected; the depth-1 witness plus the diagnostic framing is the chosen point.)

## `piece` (the join)

Componentwise, every component a known join-semilattice — `records` a per-id grow-only
**set union** (retaining any divergent record for `validate`), `minted` a grow-only union
keyed by unique `MintId` (distinct mints never collide — see fix 4), per-edge `GCounter`
max (base **and** relocation families alike), nested `transfers` per-row `GCounter` max.
A finite product of semilattices is a semilattice, so
`piece` is idempotent/commutative/associative (the `LatticeProduct` argument, n-wise). A
`GCounter` delta carries the resulting absolute slot value, so re-delivering any patch is
absorbed idempotently by max — **duplicate-delivery idempotence comes from the lattice, not
event ids.** No component decrements; `outstanding`/`holdings` are derived and may fall.

## `holdings(group, r)`

Let `f = inbound(group)` (`ROOT`/`null` for the root):

```
holdings(group, r) =
      creditIn                                   // Σ minted amounts held by r   (root)
                                                 // effIssued(f)[r] − returned(f)[r] (non-root: r's net inflow across f)
    + transferNet(f, r)                          // Σ_s transfers[f][s][r] − Σ_t transfers[f][r][t]
    − Σ_{c ∈ childEdges(group)} (effIssued(c)[r] − returned(c)[r])   // r's net delegated-out
    − effLeafSpent(f)[r]                         // consumed here; 0 at root; UNCONDITIONAL
```

Every subtracted term reads a slot **only `r` writes**, so `r`'s local `null`-check is
sound with zero coordination (the `BoundedCounter` exclusive-slot discipline, per path).
Cost O(fan-out) — asymptotically free next to the EEVDF selection loop, which already scans
every child `EdgeSummary` per pick (so candidate B's stored-aggregate optimization was
rejected as buying ~nothing).

**Quarantine — narrower than "in the `validate()` conflict set".** An earlier draft of this
section said `holdings` returns 0 whenever any edge on `group`'s lineage appears in the
`validate()` report. The code deliberately does **not** do that, and the code is right: making
a *derivation* depend on the *diagnostic* is circular (`validate` calls `holdings` for its
`PersistentNegativeHoldings` check), and it would let a transient false report zero out a
healthy lineage. `holdings` returns 0 on exactly four **structural** conditions, all decided by
walking the lineage — never by consulting a report:

| Quarantine condition | Reported by `validate()`? |
|---|---|
| a divergent record (`>1` record under one id) on the path | `RecordDivergence(id)` |
| two **live** (ACTIVE\|CLOSING) inbound edges into one group | `DualActiveInbound(group)` |
| the live inbound edges loop instead of reaching a root | `LineageCycle(group)` |
| a group whose only inbound edges are all PREPARED/RETIRED | **no** — see below |

The last row is the standing exception to §10.11's *quarantine ⟺ explicit report*
correspondence. It is the normal window of an honest reshape (the old generation has retired,
the new one has not activated yet), so reporting it would fire on healthy traffic. It clears
when the new generation activates.

**Delta-state idiom — two patches from one base lose the first.** Every mutator reads its
receiver and emits **absolute** slot values, and the join is max. So two patches computed from
the *same* base ledger do not compose: `l.delegate(r, e, 10)` and `l.delegate(r, e, 5)`, both
written from `l`, merge to `issued(e)[r] = 10`, not 15. That is the price of the absolute-value
deltas that buy duplicate-delivery idempotence, and it is a live hazard for the H4 node layer:
each mutator must be called on a ledger that has already absorbed the previous patch, never
fanned out from one snapshot. `HeddleNode` satisfies this by running every op *inside* its
`Quilter.mutate` block, so the op always sees fresh state.

**One root per ledger.** `holdings` credits `creditIn` from the minted supply for any group
with **no inbound edge**, and a `MintRecord` carries only a holder and an amount — the root
reaches the state solely inside the generated `MintId` string. Merging two independently
bootstrapped ledgers therefore leaves two rootless groups, **each credited the whole
`mintedTotal`**, double-counting every mint in the Σ-holdings identity — and silently, since no
`validate()` check looks at root cardinality. This is a **caller invariant** (never merge
across bootstraps), not a structural guarantee. Binding a `MintRecord` to its root would make
it structural at the cost of a wire-format change; that call is deliberately deferred.

`edge(id) = EdgeSummary(effIssued(id), returned(id).value, effLeafSpent(id)+effRollupSpent(id))`
— a per-edge read, at effective values. `activeChildren(g)` = summaries of `childEdges(g)`.

## `validate(): List<LedgerConflict>` (fix 3)

A pure, deterministically-sorted fold over merged state — identical report on every replica,
never resolved by timestamp. It is an **eventually-consistent diagnostic, not a safety gate**
(safety is the mutators' local holdings check): on a fully-delivered state the report is exact,
and the depth-1 witness (fix 2) keeps the direct and single-hop-transfer cases honest under
partial delivery, but a partially-delivered multi-hop transfer-funded charge may transiently
list a false conflict that self-heals on anti-entropy. The checks:

- **`PerEdgeSafety(e)`** — `effLeafSpent(e)+effRollupSpent(e)+returned(e) > effIssued(e)`.
  **Sum-wise on aggregate values** (per-slot is legitimately violable: a peer may return
  entitlement it received by transfer, so per-slot `returned(e)[r] > issued(e)[r]` is fine).
- **`PersistentNegativeHoldings(group, r)`** — `holdings(group, r) < 0` on a
  **causally-complete** state (the self-justifying witness present). This is the real
  overspend net (the C review's finding: a bug spending beyond its pocket but within the
  edge *sum* passes the sum check yet strands `holdings < 0` forever — this catches it). A
  transient negative on an incomplete state is **not** a conflict.
- **`RecordDivergence(id)`** — two distinct immutable records under one `AttachmentId`.
- **`DualActiveInbound(group)`** / **`ClosureViolation(e)`** — H2 (lifecycle); shipped.
- **`OrphanedTransferPath(path)`** — transfer rows the topology moved out from under (#2366):
  `transfers` is keyed by `PathKey.of(edge)` — the *generation's* id — so replacing a group's
  inbound generation carries the counter families across and leaves the hand-offs behind, where
  `holdings` no longer reads them. The donor silently recovers what it gave away. **No other check
  can see it:** `Σ_r transferNet(k, r) = 0` on every key identically, so abandoning a key is
  sum-preserving and conservation is *structurally* blind, and the recipient lands on `0` rather
  than below it, so `PersistentNegativeHoldings` stays silent. Fires only when all three hold — the
  key is no longer read, a move did not carry its rows to the group's live key, and some party still
  has a non-zero balance stranded on the dead generation. Deliberately silent while the group has no
  live inbound at all (the honest-reshape window) and where the generation's record is divergent
  (`RecordDivergence` owns that state).
- **`LineageCycle(group)`** — the live inbound edges loop back instead of reaching a root.
  Reported once per loop member, never for a group merely below the loop. Records are
  grow-only, so a loop seen on any state is real; it can still be *transient* in the honest
  control plane (an inverting reparent closes a loop until the old edge retires), and that
  window is a real quarantine, so reporting it is the point.
- **`ConservationViolation(leafSpentTotal, mintedTotal)`** — the **global** backstop:
  `Σ effLeafSpent > mintedTotal`, i.e. more service charged than supply ever minted (§10.1).
  Every other check is per-edge or per-`(group, replica)` and so is only as good as the
  derivation it rests on; this one is read straight off the totals, which is what makes it a
  backstop for a regression *in* that derivation — the H1b divergent-child re-spend
  manufactured spendable authority the per-lineage checks then read as legitimate, but the
  units it charged were never minted. **Exact on a converged state** (there conservation is an
  identity, so it fires only when `Σ holdings` really went negative). Under partial delivery it
  inherits the accepted transient: charges travel with the witness carrying the **actor's own**
  minted supply, so a directly-funded charge always arrives with its backing; a charge funded by
  a transfer at a non-root path is backed only by the donor's `issued`, so a state can observe
  it without the root mint. Where that state also carries the topology, the same gap already
  strands a `PersistentNegativeHoldings` at the delegator — a second voice on one fault. On a
  bare delta carrying no records it can be the only report (`allGroups()` is empty, so no
  per-group check runs).

**Honest scope note (fix 6, from C/D reviews):** under the stated non-Byzantine model,
`piece`'s max erases the loser of an *equivocated* one-writer slot, so `heddle-design.md`
§4.6's "two different values for a slot" clause is **not deliverable from state alone**
without per-slot version dots — weaken §4.6 to match, or add dots (deliberately not paid
for here). Feasibility-*violating* equivocation is still caught via `PersistentNegativeHoldings`.

## Serialization

`@Serializable` on `EntitlementLedger` + `EdgeAccount`-shaped components + `AttachmentRecord`
+ `MintRecord`, matching the zoo (`GCounter` is already `@Serializable`; value-class map keys
encode as their underlying string). The nested `transfers` and value-class keys mirror
`DotMapSerializer`'s precedent; a hand-written `KSerializer` emitting each keyed component as
a list-of-entries is the fallback if a target rejects structured map keys. No
`canonicalStateHash` — cross-order convergence is asserted by **structural equality** of
merged states (the zoo's existing pattern). Empty components keep a `Patch`'s delta small.

## Prototype validation (throwaway, not committed)

A standalone model of this exact representation was run over **400 seeded randomized
op/merge sequences** (mint/delegate/release/transfer/spend + a mid-run leaf→internal
`grow`), asserting after every step and every partial-delivery merge:

- **conservation** `minted == Σ holdings + Σ leafSpent` on both live and **partially-merged**
  states — held in all 400;
- **no negative pocket** on causally-complete states — held;
- **merge laws** idempotent/commutative/associative — held;
- **projection homomorphism** (restriction to an edge's components commutes with the join) — held;
- the **leaf→internal reclassification** (candidate A's fatal case) was exercised in all 400
  runs **without breaking conservation**.

The model is a validation of the *math*, not the Kotlin; it lives in the design-night scratch
area and is not part of the module.

## Scope: H1a / H1b vs deferred

- **H1a** — module scaffold, ids, `AttachmentRecord`, the counters + `piece` + serializers +
  `module.md`/KDoc; the standard zoo lattice-law property suite. A merging-but-inert lattice.
- **H1b** — `holdings`, the mutators (with the self-justifying witness), `validate` (sum-wise
  + persistent-negative), the conservation/projection/overspend property tests. **Then the
  ledger spec-conformance review gate.**
- **Deferred:** lifecycle gating + `DualActiveInbound`/`ClosureViolation` (H2, §5.1);
  earmarks/reserve (H4); **mint-through-the-Raft-log applied deterministically** (H5 — the
  `MintId` keying makes the ledger side safe, but the commit path is H5, per fix 4); a
  `Quilter` **conformance test pinning patch-indivisibility** (fix 6 — verified true today:
  one `Patch` = one `Delta` frame, in-order per sender, `FullState` backstop, oversize throws
  rather than splits — but it is load-bearing and unstated).

## Invariant → test map (design §10)

| Invariant | How satisfied | Test |
|---|---|---|
| §10.1 conservation | `minted = Σ holdings + Σ leafSpent`, topology-independent (fix 1) | randomized op/merge incl. partial delivery + leaf→internal (prototyped) |
| §10.2 monotonicity/laws | componentwise join of `GCounter`s/union maps | standard zoo lattice-law suite |
| §10.3 per-edge safety | sum-wise `PerEdgeSafety` + `PersistentNegativeHoldings` (fix 3) | adversarial interleaving generator |
| §10.8 projection homomorphism | restriction to an edge's **components** (not value) commutes (fix 5) | property test (prototyped) |
| duplicate-delivery idempotence | absolute-value `GCounter` deltas + max | deliver-N-times, history rises once |

## Open items for the greenlight

1. **`spent` split** (fix 1) — the central change vs the naive single-`spent`; validated, but
   confirm you're happy freezing it into the wire format now (it must be decided before H1a).
2. **Self-justifying patch payload** (fix 2) — adds a few `Long` slots per patch; it's what
   makes `validate` honest under gossip. Confirm the cost is acceptable vs. the alternative
   (demote `validate` to converged-state-only and accept transient false quarantines).
3. **`MintId`-keyed `minted`** (fix 4) — small representation choice with an H5 payoff
   (failover can't lose a mint); confirm.
4. Weakening §4.6's equivocation clause (fix 6) is a **doc edit to the merged design** — do it
   when this lands, or add per-slot dots (not recommended for v1).
