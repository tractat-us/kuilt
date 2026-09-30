# How the dinner workspace merges

Two friends are planning dinner on two phones. On the train, one of them deletes a
restaurant from the shortlist. At the same moment, in a basement with no signal, the other
changes that restaurant's price. When the phones meet again, what should the list say? Is
the restaurant gone, back, or listed twice? Nobody is wrong, and nobody is in charge, so the
app needs a rule it can apply the same way on both phones, whichever message arrives first.

This page records the rule each candidate design actually produced, and which design the
next prototype (M2) builds on. Every result below comes from a test that ran the merge. Source
lines appear only to explain a pinned result, never to supply one.

## The short answer

Build M2 on the **typed single-log model**: one shared log of small typed entries ("venue
added", "price set", "venue removed", "budget set"). It keeps a restaurant's identity through
every edit, gives every input an identity of its own, and sends about a tenth of the JSON
model's bytes per edit on a three-venue list (135 B against 1572 B), and a shrinking fraction
as the list grows (139 B against 10988 B at 30 venues). The JSON-document model loses a
restaurant's identity the moment someone edits its price, and its budget has no identity at
all. The full recommendation, with its costs, is at the end.

## The two candidates

Both are pure values that merge with `piece`. There is no network here; replication is
`Quilter`'s job and has its own tests.

- **Typed** (`TypedModel`) is one `Rga<Entry>` log. Every change appends an entry, so every
  change is an `Rga` element and carries a `Dot`, the library's unit of identity. A removal is
  itself an appended entry. The model reads the log to work out the current list.
- **JSON** (`JsonModel`) is one `JsonCrdt` document,
  `{"shortlist": Array[Object{name, price}], "budget": Leaf}`. A restaurant is an element of
  the `shortlist` array; the budget is a single leaf (a multi-value register).

A restaurant's **handle** is the `Dot` of the element that added it. The app holds handles,
never list positions, because a position shifts under a concurrent change.

The ruling on #2869 is that identities come from dot-carrying types only. The last column of
the table asks whether each result honours that identity requirement. The ruling's other half,
what happens once old history is garbage-collected, is not tested here; see the end.

## The decision table

Every probe starts from three restaurants added on replica `a` (Uno 40, Due 25, Tre 30),
forks into `x` on `a` and `y` on `b`, and merges both ways. `MergeProbe.bothOrders` asserts
that `x.piece(y)` and `y.piece(x)` give the same shortlist, budget and budget candidates, for
every probe and both models. The model tests are `TypedModelProbeTest` (typed) and
`JsonModelProbeTest` (JSON); test names below are methods on those classes unless another
class is named.

| Question | Typed result | JSON result | Pinned by | Fits the dot-only ruling? |
|---|---|---|---|---|
| **Identity.** Does every input carry a dot? | Yes. The venue and the budget write each add a dot. | The venue does. The budget `Leaf` write adds none: the document's dots are identical before and after it. | `IdentityTest.typedModelGivesEveryInputADot`, `IdentityTest.jsonModelBudgetLeafHasNoDot` | Typed yes. JSON **no** for the budget; that this holds for any scalar leaf is source reasoning, not tested. |
| **Identity across logs.** Can two `Rga` logs, joined in a `LatticeProduct`, supply the identities? | Ruled out. A replica's first insert into each log is `Dot(a, 1)` in both, so the product holds one dot for two inputs. Hence one log. | Not applicable. | `TwoLogProductTest.twoRgaLogsMintTheSameDot` | A dot is unique **within one log**, not across a workspace. Only a single log fits. |
| **P1 — edit through a handle while another venue is added.** | Due shows 22 and its handle still reaches it. Rows `Uno@40, Due@22, Tre@30, Zero@10`. | Due shows 22 in the right place, but its old handle no longer resolves; Due now has a new dot `(a, 4)`. Uno's and Tre's handles survive. | `listIdentityEditLandsOnDue`; `listIdentityEditLandsButTheHandleNoLongerResolves` | Typed yes. JSON no: an edit changes the identity. |
| *P1 caveat* | `addVenue` only appends, so P1 never puts a venue *ahead* of Due and cannot tell a handle from an index. The typed test pins that Due stayed at index 1; the JSON rows show Due still second. P1b is the arm that shifts the index. | | `listIdentityEditLandsOnDue` (index precondition); `listIdentityEditLandsButTheHandleNoLongerResolves` (rows) | — |
| **P1b — edit through a handle while the venue ahead is removed.** Due moves from index 1 to 0. | Due at 22 at index 0, still reached by its handle. Rows `Due@22, Tre@30`. A later action on `b` through the same handle lands on Due. | Rows `Due@22, Tre@30`, but the old handle no longer resolves. The same later action on `b`, on this merged state, **throws** (see Unsupported shapes). | `listIdentityHoldsWhenTheVenueAheadIsRemoved`, `actionThroughHandleAfterShiftLandsOnDue`; `listIdentityAfterShiftAlsoLosesTheHandle`, `staleHandleActionOnTheShiftedMergeThrows` | Typed yes. JSON no. |
| **P2 — two concurrent prices on one venue** (Uno: `a` 35, `b` 45). | One Uno, at 45. Both `PriceSet`s get Lamport 4 and both stay in the log; `(4, b)` beats `(4, a)`. The shortlist shows no trace of the loser. | **Two Unos**, at 45 then 35, each under a new dot `(b, 1)` and `(a, 4)`; the original is gone. The prices never meet in one register, so the conflict shows up as a duplicate venue. | `concurrentFieldEditKeepsOneVenueAtTheGreaterRgaIdPrice`, `TypedModelOrderTest.concurrentPriceSetsGoToTheGreatestRgaIdInBothMergeOrders`; `concurrentFieldEditDuplicatesTheVenue` | Typed yes. JSON no: one venue became two identities. |
| **P3 — delete versus a concurrent price edit** (`a` removes Tre, `b` sets it to 28). | **Remove wins.** The shortlist hides Tre whatever its edits. Rows `Uno@40, Due@25`. | **Edit wins**, as a new venue. Tre survives at 28 under `b`'s new dot `(b, 1)`; the remove only reached the original element. Rows `Uno@40, Due@25, Tre@28`. | `deleteVersusEditRemoveWins`; `deleteVersusEditTheEditSurvivesAsANewVenue` | Typed yes: one defined outcome in both orders. JSON: defined and order-independent, but the survivor is a new identity. |
| **P4 — two concurrent budgets** (`a` 30, `b` 50). | Effective budget 50, by greatest `RgaId`. Candidates `[50, 30]`, winner first, so the loser stays visible. | Effective budget `null`: no winner. Candidates `[30, 50]`. Both values are recoverable, so the conflict is visible. | `concurrentBudgetPicksTheGreaterRgaIdAndKeepsTheLoser`, `TypedModelOrderTest.concurrentBudgetsGoToTheGreatestRgaIdInBothMergeOrders`; `concurrentBudgetHasNoWinnerAndKeepsBoth` | Typed yes. JSON keeps the conflict visible but the budget has no dot (Identity row), so no. |
| **Typed ordering trap.** Is "last in the log" the last writer? | **No.** `Rga.entries()` walks concurrent siblings in *descending* id order, so "last in `entries()`" picks the lower id. The model sorts by `RgaId` explicitly. | Not applicable. | `TypedModelOrderTest` (both tests pin the raw `entries()` order as a precondition) | Fits, but only because the model sorts; see Missing canonical APIs. |
| **P5 — bytes on the wire for one price edit** on a 3-venue list (JVM CBOR), and at 1, 10 and 30 venues. | 135 B: one inserted entry. 135 B at 1 and at 10 venues, 139 B at 30. The delta never carries the list; only its two ids' integers widen as the log's history grows (a byte per field past 23 entries, and history counts every edit, not just venues). | 1572 B when the author of the venues edits; 1656 B when a non-author does. The delta still carries the whole `shortlist` array, so it grows: 843 B at 1 venue, 3997 B at 10, 10988 B at 30. | `fieldEditDeltaIs135Bytes`, `fieldEditDeltaCarriesOnlyItsIdsWidening`; `fieldEditDeltaIs1572Bytes`, `fieldEditDeltaByANonAuthor`, `fieldEditDeltaGrowsWithTheList`, `fieldEditDeltaIsLargerThanTyped` | Size is not a ruling question; it matters for the M4 phone. |

### Where the JSON bytes come from

The whole-array delta is the **model's** doing. `JsonModel.setPrice` rebuilds the venue's
element and writes the whole `shortlist` back. `ORMap.put` also folds the caller's own earlier
contributions into its delta (`foldOwn`), which would produce the same shape. The
non-author arm separates them: `b` has nothing of its own to fold, and its delta is still the
whole array (`fieldEditDeltaByANonAuthor`), so the rebuild alone produces it. That the fold
is redundant for the author, adding 0 B, is **source reasoning, not a measurement**: the author's earlier
contribution is the base array, whose ops are a subset of the rebuilt one's, and `Rga.piece`
is an op union.

The non-author's extra 84 B is measured. Its attribution to two co-causes, both needed, is
**source reasoning**, from the test's KDoc:

- the model: `old.map.piece { put }` at `JsonModel.kt:79` keeps every tag of the old
  element's map, `a`'s price tag included;
- the map: `ORMap.put` supersedes only the caller's own tags (`ORMap.kt:209`), so `b`'s put
  leaves `a`'s price beside its own.

These numbers are JVM-only. No claim is made for other targets.

## Unsupported shapes

Each is a shape a caller might reach for, with what they would actually get.

- **An in-place edit of a JSON array element.** `JsonModel.setPrice`'s `SHAPE:` line: an `Rga`
  has no in-place update, so a price edit inserts a rebuilt element after the old one and
  removes the old one. The venue gets a new dot. Pinned by P1, P1b, P2 and P3 above.
- **Acting through a handle after a remote edit replaced its element (JSON).** The call throws
  `IllegalStateException` with the message `… is not visible (never added here, removed, or
  replaced by a setPrice)`. Pinned on the P1b merged state (`staleHandleActionOnTheShiftedMergeThrows`)
  and on a plain non-concurrent merge (`staleHandleActionAfterMergeThrows`). The typed model has
  no counterpart: the same action on the P1b merged state lands
  (`actionThroughHandleAfterShiftLandsOnDue`).
- **A dot for a JSON scalar (`Leaf`).** A leaf write mints no dot the document exposes
  (`IdentityTest.jsonModelBudgetLeafHasNoDot`). Nothing throws. A caller that needs the
  budget's identity gets an empty contribution to `causalDots()` and no error, so it must
  refuse at its own boundary.
- **Identity across two `Rga` logs.** Two logs mint colliding dots
  (`TwoLogProductTest.twoRgaLogsMintTheSameDot`). Nothing throws; the product silently holds
  fewer dots than inputs.

The typed model has no `SHAPE:` line. It refuses a handle that is not on the local shortlist
(`requireVisible`), but no test pins that refusal.

## What the library is missing, and what the app decides

### Missing canonical APIs

1. **An in-place update of an `Rga` element's value that keeps its identity.** Confirmed. It is
   the root of the JSON identity loss in P1, P1b and P3, and of the stale-handle throw. P2 would
   additionally need a merge rule for two concurrent in-place updates of one element. The typed
   model does not need it, because it never edits an element; it appends.
2. **"Last writer wins by `RgaId`" over an `Rga` log, as a reusable primitive.** Confirmed. The
   obvious reading, "last in `entries()`", is wrong for concurrent writes
   (`TypedModelOrderTest`), and every consumer of a single log will hit it. The typed model
   hand-rolls the sort today.
3. **A per-op causal context (a version vector on each insert).** Confirmed, but not blocking.
   "Which concurrent writes did the effective value beat" needs to know what each author had
   seen. The `Rga` records only one predecessor, so `TypedModel.budgetCandidates` walks the
   `after` chain plus the author's own earlier inserts. That is an over-approximation: it
   never drops a concurrent budget, but it can keep one the author had in fact seen. This rests
   on the model's `NEEDS:` line; the P4 tests pin the concurrent case, and **no test exercises
   the over-approximating case**.
4. **A path-addressed nested mutator for `JsonCrdt` (#2469).** Related work, not a
   prerequisite. It would shrink the JSON delta (P5), but to keep a venue's identity it would
   still need item 1, and the budget leaf would still have no dot. The recommended model does
   not use `JsonCrdt`.
5. **A workspace-wide dot space across several `Rga` logs.** Rejected for M2. The single log
   sidesteps it (`TwoLogProductTest`). It becomes a real need only if the state is ever split
   into several logs.

Items 2 and 3 are needed for the app's **reads** of the typed log (which price wins, which
budgets are still in conflict), not for identity. Identity needs neither.

### Application policy

These are choices the app makes on top of the typed log. The tests pin today's choice; none is
the library's to make.

- **Delete versus edit: remove wins** (P3). The typed model appends every change and deletes
  nothing (its class KDoc; P3 does not assert the edit's entry survives), so the app could
  surface it ("Tre was removed; Sam had changed its price") without changing the merge.
- **Concurrent prices: greatest `RgaId` wins** (P2). The loser stays in the log
  (`concurrentFieldEditKeepsOneVenueAtTheGreaterRgaIdPrice`) but the shortlist shows no trace
  of it. Showing the loser is a policy the app can add, the same way the budget does.
- **Concurrent budgets: pick one, keep the rest visible** (P4). The typed model answers 50 and
  lists `[50, 30]`; "ask a person" is the app acting when that list has more than one value.
  JSON's `null` is the opposite policy, "no answer until someone decides", and is equally
  visible.
- **Entry encoding.** Short `@SerialName` discriminators keep the class name off the wire, and
  are part of why P5's typed edit is 135 B.

## Recommendation

M2 adopts the **typed single-log model**, one `Rga<Entry>` log. The deciding rows are
Identity (every input has a dot; the JSON budget leaf has none), P1b and the stale-handle
action (a typed handle survives an index shift and a remote edit; a JSON handle is lost to its
own venue's first price edit), P2 (typed keeps one venue; JSON splits it into two), and P5
(the typed delta never carries the list: 135 B at 1 and 10 venues and 139 B at 30, as only its ids'
integers widen with history, against 843 B, 3997 B and 10988 B for JSON). It fits the
ruling's identity requirement (Identity row) without a new primitive. The cost is that the app,
not the library, owns the reading of the log. It must sort for last-writer-wins by `RgaId`,
because sequence order is not id order (`TypedModelOrderTest`). It must choose remove-wins and
its conflict display. And it must accept that `budgetCandidates` over-approximates concurrency
until the `Rga` carries a per-op causal context. The JSON model would need an in-place `Rga`
element update to keep venue identities, and still could not give the budget leaf a dot, so it does
not fit the ruling's identity requirement as the library stands.

### Not tested here: garbage collection

**Everything in this subsection is untested by this probe, and is reasoning from the model's
shape and the library's source.** The ruling also asks that garbage-collected reports degrade
to a readable basis rather than to "unknown", and the epic asks that identities survive
deletion, compaction and restart. This probe covers none of that.

- The typed log never tombstones: a removal is an appended `Removed` entry. So `Rga.compact`,
  which reclaims removed elements, has nothing to reclaim, and only `Rga.dropWindow` can bound
  the log.
- A window is not neutral for this model. One that drops a `Removed` entry but keeps its
  `VenueAdded` would bring the venue back. One that drops a `VenueAdded` would orphan its
  `PriceSet`s.
- How the model should read a windowed log is open. The causality track inherits exactly this
  question when it adopts the model.
