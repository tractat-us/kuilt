# #2366 — design for the real fix (read-only investigation, 2026-08-14)

Nothing written; design only. Read against `origin/main` @ `9993a4d2`. **A decision is needed before any
code is written** — see §3.

## 1. The finding that reframes the issue

**`relocationPatch` cannot see `transfers` in production, and never will as currently wired.**

`ControlPlane.kt:831` calls `fence.relocations.relocationPatch(liveEdge, finals)`. `FenceState.relocations`
starts at `EntitlementLedger.ZERO` (`FenceState.kt:107`) and only ever absorbs published relocation patches
(`FenceState.kt:99-100`), which `EdgePatchBuilder.build()` (`EntitlementLedger.kt:1567-1577`) builds from
**nine per-edge counter families and nothing else** — no `records`, no `lifecycle`, no `transfers`.

So `this.transfers` is empty on every production call. The test helper is the opposite:
`FenceTestSupport.kt:32` passes the full data-plane ledger.

Two independent agents reached this conclusion separately (the option-2 implementer filed it as **#2377**;
this design derived it from `EdgePatchBuilder`). Treat it as established.

**Consequence:** option 1 cannot be implemented by reading `this.transfers`. Magnitudes must arrive through
`finals` — i.e. a new field on `SlotFinals` / `ControlCommand.QuiesceAck`, which is **a wire change to a Raft
log type**.

## 2. The scoping fact that lowers the urgency

**`EntitlementLedger.transfer` (`:973`, public) has NO production caller.** Verified by grep: the only
`.transfer(` hits in production `*Main` sources are `BoundedCounter.transfer` in `:kuilt-quilter`, an
unrelated type. `HeddleNode.kt:568` says so in prose too.

So today, `transfers` rows exist **only** when a consumer drives the raw `EntitlementLedger` API. Which means:

- #2366 is reachable only through that surface.
- **Option 2's guard (#2376) fires exactly there** — its receiver is a real ledger. It is correct containment
  for the only currently-reachable path.
- **#2377 is a real future hazard, not a present hole**: the control plane has no rows to abandon because
  nothing creates them there. It goes live the moment the control plane gains transfers.

This does not lower the correctness cost of leaving a public mutator whose effect a control-plane operation
silently discards. It does mean nothing is on fire.

## 3. THE DECISION — three shapes, and the issue only listed two of them

### Option 1 — move the rows (what #2366 proposed)

Add a **reloc pair** `transferRelocIn/Out: Map<PathKey, Map<ReplicaId, GCounter>>`, joined per-slot max like
the nine counter families, read as `effTransfer = base + relIn − relOut`. **Not** a literal key move into
`transfers[PathKey.of(t)]` — that is #1691 verbatim (the donor writes that same slot concurrently through
ordinary `transfer`, and two writers on one max-joined slot silently erase one side).

The `Out` half is **mandatory**, not symmetry: it is what makes a second `Reconcile` idempotent, and what
lets a *pure-recipient* donor (`n = 0, sp = 0`) be seen at all — the `:738` skip predicate must widen to
`n == 0 && sp == 0 && tOut == 0`.

- **Cost:** ~4 PRs. Two new ledger components, `SlotFinals.transferRow`, the derivation, `EdgePatchBuilder`
  widening, `replicasOnEdge` widening, a new `LedgerConflict` variant.
- **Wire:** breaking, on **three** surfaces — `EntitlementLedger` (data plane *and* inside the Raft log via
  `ControlCommand.Retire(witness)`), `SlotFinals` (in the Raft log via `QuiesceAck`), and the sealed
  `LedgerConflict` hierarchy. **Wants `kuiltVersionLine` 0.7 → 0.8**, same treatment as #1752 PR 3.
- **Fails LOUDLY** in a mixed-version cluster (CBOR `ignoreUnknownKeys = false` ⇒ decode throws).
- **Does not close the straggler residual:** a lagging peer that writes at `PathKey.of(s)` after the fence
  strands its δ. New named residual, same shape as §6.5.2's cross-incarnation ack gap.

### Option 3 as costed in the issue — rekey the stored map to `GroupId`

Largest diff, wire break, and it *does* touch `spendCaptured`/`witness` (option 1 does not).

### ★ The option the issue never enumerated — widen the READ, store nothing new

Leave the stored map exactly as it is. Make `holdings`' transfer term sum `transferNet` over **every**
`PathKey.of(e)` whose `recordOf(e)?.child == group` (plus `ROOT` at the root).

- **Zero** new components, **zero** wire change, zero control-plane change, no fence involvement, no
  log-purity question. `piece` untouched ⇒ convergence trivially preserved.
- Every key is read at exactly one group, always — **the orphan state becomes unrepresentable rather than
  repaired**.
- **Closes the straggler residual** option 1 cannot.
- A cross-parent reparent keeps rows with the group — arguably more correct than option 1, which refuses the
  re-home (`ControlPlane.kt:801-810`) and strands them.
- **Costs:** `holdings` becomes O(#generations) on the hot path with an unbounded ladder; `witness` must
  widen the same way; divergent records need `recordOf`'s `singleOrNull` guard so one edge is never read at
  two groups.
- **The real risk:** it changes a *derived read* with **no wire change**, so two peers on different versions
  compute different `holdings` from identical bytes — it fails **SILENTLY** where option 1 fails loudly.

**Not proved correct.** Flagged because it is strictly smaller than option 1 and the issue's option list does
not contain it. It deserves a decision, not a default.

## 4. Land-regardless, under any option

**A diagnostic PR, first.** `validate()` gains `OrphanedTransferPath(path: PathKey)` — no existing
`LedgerConflict` variant fits (all eight are keyed by edge/group/total, none has a transfer term;
`PersistentNegativeHoldings` catches only the loud half — the issue's recipient lands on `0`, not below).

A key is **live** iff it is `ROOT`, or `PathKey.of(e)` where `recordOf(e)?.child` has
`lineageEdges(child)?.lastOrNull() == e`. A non-live key with any `effTransferNet(k, r) != 0` is reported.

It **reds nothing** on today's `main`, makes #2366 *visible* (the repro's `validate()` stops returning `[]`),
and gives whichever fix wins its acceptance signal. Adding a sealed subtype is source-breaking for a
downstream exhaustive `when` and needs a `compareTo` arm (`LedgerConflict.kt:31-48`).

## 5. Why conservation can never catch this — do not add a Σ-assertion

`Σ_r transferNet(k, r) = 0` for every key, identically. So abandoning, halving, **or double-moving** a key's
rows is sum-preserving. `assertConservation` and `assertConservationWithRelocation` are blind to all three
and will stay blind after any fix. Per-edge safety reads issued/spent/returned only — no transfer term.

**The assertion that actually works is per-pocket preservation:** for every `(group, replica)`, `holdings`
immediately before `reshape` equals `holdings` immediately after `reshape + relocationPatch`. The two differ
by exactly `transferNet(key(t), r) − transferNet(key(s), r)`, so it holds **iff** the rows move. It reds on
the issue's repro, on a half-move, and on a double-move.

## 6. Fixture knobs that would make the property vacuous

- **`allGroups.random(rnd)` in the transfer arm** — ~¼ of transfers land at `g3`, and must *survive* until a
  relocate draw hits the current rung. This is the master knob. Do **not** assert a count of transfers —
  assert a row was present **at the moment of a move**.
- **Donor identity** — if the donor is also the delegator, `n > 0` covers the pocket through the issuance
  path and the per-pocket check passes with the transfer half broken. A **pure recipient** must occur.
- **`rnd.nextLong(1L, 40L)` vs holdings** — an over-large transfer returns `null` and silently does nothing.
  Clamp, or the arm starves.
- **Ladder depth** — the existing `rig.nested` counts *issuance* nesting; a rung can be issuance-nested
  without ever carrying a row. Transfers need their own counter.
- **`g3` is a leaf** — add the transfer analogue of the existing `ROLLUP_RELOC_IN + OUT == 0` tripwire.

Counters with floors, each naming an arm otherwise green by absence: `movedWithTransferAtStrand` (master),
`movedWithPureRecipientDonor`, `movedTransferOnNestedRung`, `accumulatedTransferOntoOneEdge`.

## 7. Traps for whoever implements

- **`baseFinalsOn(edge)` enumerates `replicasOnEdge`, which walks the COUNTER families only.** A
  pure-recipient-funded donor with a transfer row and no counter slot is **absent** — silently dropping
  exactly the donor the fix is about. Must union `transfers[PathKey.of(edge)].keys`. Production is
  unaffected (the ack set is the roster), which is why it would go unnoticed.
- **The `n < 0` KDoc justification evaporates.** `EntitlementLedger.kt:682-683`/`:733-736` justify the
  refusal as "needs its transfer rows moved too (out of scope)". After option 1 the rows *do* move and the
  guard is still correct — but for a different reason: `n < 0` arises from a `release` debit on `returned`,
  and there is **no `returnedRelocOut` family**, so the debit cannot be moved. Rewrite the reason or the next
  reader deletes a guard whose stated purpose is gone. `break4_transferTangledStrandIsRefused` stays green;
  its *comment* is wrong.
- **`EntitlementLedgerLawsTest.pieceNeverLowersAStoredCounterSlotInAnyFamily` iterates `CounterFamily.entries`,
  which is edge-keyed** — the new path-keyed families are silently exempt unless a sibling loop is added.
- **§12.1 nesting:** the derivation must read `+ transferRelocIn(PathKey.of(s))`, not the base. Omitting it
  under-moves every rung after the first while rung 0 stays correct — **invisible without a ladder**.
- **§12.3 accumulation:** the `t`-side write must be `stored + tv`, not `max`. Only differs when both a
  standing credit and an incoming amount are non-zero.

## 8. Mutation receipts required (record the SHAPE of each red, not just its presence)

| mutation | should red |
|---|---|
| delete the transfer writes | repro + per-pocket vector + orphan report |
| drop `+ transferRelocIn(of(s))` | per-pocket vector **only on nested rungs** |
| `max` instead of `stored + tv` at `t` | per-pocket **only** with two rowed strands on one `t` |
| write base `transfers[t]` not `transferRelocIn[t]` | **only** the concurrent-donor test |
| narrow skip back to `n == 0 && sp == 0` | **only** the pure-recipient-donor case |
| delete the `Out` write | the double-`Reconcile` idempotence test |
| omit the base-row republish | the negative-`effTransfer` observer test |

A green row names an unproven assertion. A fully-red table proves only the mutations chosen — record which
cases were **not** mutated.

## 9. Open risks

1. **The read-side widening (§3★) is unproven.** Strictly smaller than option 1; fails silently across
   versions where option 1 fails loudly. Needs a decision.
2. **The straggler residual is real and option 1 cannot close it.** Must be documented as a named residual
   alongside §6.5.2's, not quietly omitted.
3. **Nothing here depends on `AttachmentRecord.initialVirtualTime`** (which #1752 PR 3 deletes) — confirmed,
   the transfer path never reads it.
4. **Not verified by execution** — no builds or tests were run. The §5 arithmetic is derived by hand and
   should be confirmed by the first red.
