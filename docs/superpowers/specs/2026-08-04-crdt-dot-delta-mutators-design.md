# Sending the change instead of the whole thing

**Issue:** [#2044](https://github.com/tractat-us/kuilt/issues/2044) · **Date:** 2026-08-04 ·
**Status:** design proposed, not implemented. One decision needs Iain (see "Decisions Iain owns").
Every figure below was measured on this branch with throwaway probes; "How the figures were
produced" says how to re-take them.

## What this changes, in plain language

Two devices sharing a list keep their copies in step by telling each other what changed. kuilt's
shared shopping-list type does not do that. When you tick one item off a four-hundred-item list, your
device sends **the entire list** to everybody, and the network layer above it copies that around the
room several times over. One tick of one box currently costs more than letting a brand-new device
join and download everything from scratch.

The fix is to send the change — the one item and a short note about which older versions of it this
replaces. That note is the whole difficulty, and it is where the plan written into #2044 goes wrong:
its version of the note would, in one case, quietly delete every item on the receiver's list.

The rest of this page is technical.

## The premise, verified — and one headline number corrected

#2044's core claim holds. `Quilter.apply` broadcasts the patch's delta verbatim:

<!-- verbatim from kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt#apply -->
```kotlin
public fun apply(patch: Patch<S>): Unit = lock.withLock {
    check(!closed) { "Quilter($replica) is closed" }
    _state.update { it.piece(patch) }
    val seq = ++nextSeq
    pendingDeltas[seq] = patch.delta
    recomputeDeliveredLocal()
    broadcastDelta(seq, patch.delta)
}
```

…and `ORSet.add` hands it the whole new set, because the only way to build a `Patch<ORSet<E>>` today
is `Patch(state.add(…))`:

<!-- verbatim from kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORSet.kt#add -->
```kotlin
public fun add(replica: ReplicaId, element: E): ORSet<E> {
    val dot = causal.context.nextDot(replica)
    val entries = causal.store.entries + (element to DotSet(setOf(dot)))
    return ORSet(Causal(DotMap(entries), causal.context.add(dot)))
}
```

Measured end to end on a 4-node `GossipSeam` mesh through the real `Cbor` codec, with a `MeteredSeam`
counting every byte the cluster puts on the wire — the same harness as
`DotWireEncodingCostModelTest`'s part (I), with the delta mutators temporarily added:

| `ORSet` entries | one add, today | one add, delta'd | one remove, today | one remove, delta'd |
|---|---|---|---|---|
| 100 | 56,325 b | **1,605 b** | 56,179 b | **1,612 b** |
| 400 | 221,025 b | **1,605 b** | 220,879 b | **1,612 b** |
| 1,600 | 890,481 b | **1,605 b** | 890,335 b | **1,612 b** |

The delta path's metered cost is **flat** — 1,605 b at every size, because the frame is the same size
and the flood crosses the same links. The full-state path is O(entries). That is the shape of the
win: not a constant factor, a change of order. At 400 entries it is **138×**; at 1,600 it is **555×**;
extrapolating the flat numerator, at 100,000 entries it is roughly 34,000×.

**#2044's "1,585×" is not a like-for-like ratio and should not be quoted.** It divides a *metered,
gossip-flooded* 228,297 b by a *single un-flooded* 144 b frame. The delta is flooded too. The
honest, both-sides-metered figure at that shape is **138×**. The issue is not wrong that this is the
dominant cost; it overstates the multiple by roughly an order of magnitude, and the corrected number
is still the largest win on this track by a wide margin.

## The crux: #2044's stated delta shapes are both wrong

#2044 proposes:

> for `add` it is `Causal(DotMap(mapOf(e to DotSet(setOf(dot)))), DotContext.of(dot))`, and for
> `remove` it is the empty store with the context unchanged

Neither converges. Both were built and measured.

### The remove delta deletes the receiver's whole set

"Empty store, context unchanged" is not the delta of *a* removal — it is the **full state of a set
from which everything has been removed**. Joining it into a converged peer retires every dot that
peer holds, because the sender's context witnesses all of them.

The join rule is the one thing every argument here rests on, so it is worth having in front of you:

<!-- verbatim from kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotSet.kt#join -->
```kotlin
override fun join(other: DotSet, context: DotContext, otherContext: DotContext): DotSet {
    val kept = LinkedHashSet<Dot>()
    for (dot in dots) {
        if (dot in other.dots || !otherContext.contains(dot)) kept.add(dot)
    }
    for (dot in other.dots) {
        if (!context.contains(dot)) kept.add(dot)
    }
    return DotSet(kept)
}
```

A receiver's dot survives only if the sender's store still holds it **or** the sender's context has
never seen it. A delta with an empty store and a complete context satisfies neither for anything.
**Measured: a 5-element set, one element removed, receiver left with 0 of 5.** The `ORMap` analogue
loses 4 of 4. This is not a corner case — it is what the shape does on its first use.

Where the shape comes from is worth naming, because it is a reasonable mistake:
`ResettableCounter` already ships exactly it, and there it is **correct** —

<!-- verbatim from kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ResettableCounter.kt#reset -->
```kotlin
public fun reset(): Patch<ResettableCounter> =
    Patch(ResettableCounter(Causal(DotFun(), causal.context)))
```

`reset()` *means* "retire everything I have observed". `remove(e)` means "retire what I have observed
**on e**". The shape is the same; the intent is the opposite of general.

### The add delta resurrects a removed element

`ORSet.add` replaces the element's dot set rather than growing it — the new dot **supersedes** the
old ones. (That is deliberate: without it, an element re-added `k` times locally accumulates `k` dots
forever.) A delta whose context carries only the minted dot never tells the receiver about the
supersession, so the receiver keeps both dots.

That is a permanent divergence, not a transient one, and it has a second-order consequence that is
worse than the byte cost:

1. `alpha` and `bravo` converge on `{e ↦ (alpha,1)}`.
2. `alpha` re-adds `e` → local `{e ↦ (alpha,2)}`. It ships #2044's delta, whose context is `{(alpha,2)}`.
3. `bravo` joins it → `{e ↦ (alpha,1), (alpha,2)}`. **Diverged.**
4. `alpha` removes `e` and ships a *correct* remove delta retiring `(alpha,2)`.
5. `bravo` drops `(alpha,2)`, keeps `(alpha,1)` — **`e` is still present on `bravo`.**

Measured: `alpha=[] bravo=[e]`. The element is resurrected. It heals eventually, via the
anti-entropy full-state fallback — which means **every write costs a full state anyway**, just
later, and #1955's root-hash gate stops matching for that pair in the meantime. #2044's fix would
have paid the full-state cost *and* introduced a user-visible wrong answer in the window before the
heal.

## The delta shapes that do work

The property to hold is the **delta-mutator law**: for a mutator `m` and its delta `mᵟ`,

```
X ⊔ mᵟ(X) = m(X)      for every state X
```

Get this exactly and everything else follows: the sender and receiver land on byte-identical states,
so the root-hash gate keeps engaging; and because each delta is an element of the same
join-semilattice, any set of them converges under any delivery order.

| mutator | delta store | delta context |
|---|---|---|
| `ORSet.add(r, e)` | `{e ↦ DotSet({d})}` | `{d} ∪ dots(store[e])` |
| `ORSet.remove(e)` | `{}` (bottom) | `dots(store[e])` |
| `ORMap.put(r, k, v)` | `{k ↦ ORMapEntry(DotSet({d}), v)}` | `{d} ∪ tags(store[k])` |
| `ORMap.remove(k)` | `{}` (bottom) | `tags(store[k])` |
| `LWWMap.set/remove` | the one cell | *(none — `LWWMap` has no causal context)* |

`d = context.nextDot(r)`. The **only** difference from #2044 is the underlined term: the dots the
operation supersedes or retires travel with it. That is what makes it a delta rather than a
description of the sender's state.

Three details that are easy to get wrong and are load-bearing:

- **`ORMap.put`'s delta carries the *supplied* value `v`, not the locally-merged one.** `put` merges
  locally because a put is additive over the value lattice:

  <!-- verbatim from kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/ORMap.kt#put -->
  ```kotlin
  public fun put(replica: ReplicaId, key: K, value: S): ORMap<K, S> {
      val dot = causal.context.nextDot(replica)
      val existing = causal.store.entries[key]
      val mergedValue = existing?.value?.piece(value) ?: value
      val newEntry = ORMapEntry(DotSet(setOf(dot)), mergedValue)
      return ORMap(
          Causal(
              DotMap(causal.store.entries + (key to newEntry)),
              causal.context.add(dot),
          ),
      )
  }
  ```

  `ORMapEntry.join` re-does that merge at the receiver against *its* value, which is the right one.
  Shipping the sender's merged value would work but is O(value) instead of O(change) and would
  re-transmit history the receiver already has.

- **A no-op mutator returns an empty patch, not the state.** `remove` on an absent key yields
  `Causal(DotMap(), DotContext.EMPTY)`, which is the lattice identity — verified as such, not
  assumed.

- **The superseded dots may belong to *other* replicas.** If `e` carries `{A1, B1}` and `alpha`
  re-adds it, the delta's context is `{A1, B1, A2}`. That is not overreach: `alpha` genuinely
  observed `B1` and superseded it, and transmitting that is the only way a third replica learns it.
  A replica that has `C1` on `e` and has never seen `A1`/`B1` keeps `C1` — the concurrent add
  survives. Add-wins is preserved precisely because the context is *observation*, not authority.

### Why it converges — the argument, then the receipts

**The lattice.** A causal state is a pair `(S, C)` with `S ⊆ C` (the `Causal` invariant, stated in
its KDoc and maintained by every factory path). Under the join above, these pairs form a
join-semilattice: idempotent and commutative by inspection of the symmetric formula, associative by
the standard result for causal CRDTs. Every delta above is such a pair — `⊥ ⊆ D` for a remove,
`{d} ⊆ {d} ∪ D_old` for an add. So *any* multiset of deltas, folded in *any* order, with *any*
repeats, reaches one value.

**The law, per mutator.** For `remove(e)` with `D = dots(store[e])`: (i) every dot of `e` is in the
delta's context, so `e`'s dot set joins to empty and `DotMap.join` drops the key; (ii) no other key's
dots are in `D`, because a dot is minted for exactly one key and no join ever moves one; (iii)
`C ∪ D = C` since `D ⊆ C`. So `X ⊔ (⊥, D) = (S − e, C)`, which is exactly what `remove` returns. The
`add` case is the same argument with `{d}` surviving instead of nothing.

**Add-wins under concurrency.** A concurrent add on another replica mints a dot the remover never
witnessed, so it is absent from the remove delta's context and survives the join — in either order.

**The receipts** (all from the throwaway probes; counts are trials, not assertions):

| probe | result |
|---|---|
| `X.piece(addDelta) == X.add`, **byte-for-byte**, over randomised states | 0 failures / 400 |
| `X.piece(removeDelta) == X.remove`, byte-for-byte | 0 failures / 400 |
| `ORMap` put-law and remove-law | 0 / 300 each |
| `LWWMap` set-law with a single-cell delta | 0 / 300 |
| 3-replica random op streams, deltas delivered **shuffled and duplicated** | 0 mismatches / 200, byte-identical |
| concurrent add vs remove, both orders | identical, add wins |
| a remove delta applied **before** the add it retires, 4 orders | all 4 byte-identical |
| #2044's add delta | leaves the superseded dot; resurrects a removed element |
| #2044's remove delta | wipes 5 of 5 elements (`ORSet`), 4 of 4 keys (`ORMap`) |

## Causal-context transmission: none needed beyond the dots the op touches

This is where the literature's advice does not apply to kuilt, and the difference is worth stating
because it removes a whole subsystem from the design.

Delta-state CRDTs are usually described as requiring **causal delivery** — buffer a delta until its
predecessors arrive. That requirement comes from compressing the causal context down to a bare
version vector, which cannot express "I have seen dot 7 but not dot 5". kuilt does not do that.
`DotContext` is **dot-exact**: a contiguous-prefix vector *plus* a cloud of the non-contiguous
remainder.

<!-- verbatim from kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/DotContext.kt#contains -->
```kotlin
public fun contains(dot: Dot): Boolean =
    dot.seq <= (vv[dot.replica] ?: 0L) || dot in cloud
```

Its own KDoc already says why the cloud exists — "kuilt fabrics reorder and duplicate frames, so dots
can arrive out of order". This is the payoff for that cost. So:

- **A delta carries exactly the dots it asserts or retires. Nothing else.** Never the sender's full
  context, never a version vector.
- **A receiver applies every delta immediately.** There is no such thing as a delta it "cannot yet
  causally apply" — it lands in the cloud and compacts when the gap fills. Measured: a remove delta
  applied before the add it retires converges byte-identically in all four orders tried.
- **No buffering, no full-state request, no new message.** Nothing is added to `QuiltMessage`.

**Interaction with `Quilter`'s existing delta/ack/resend machinery: none.** Sequence numbers, gap
detection, `Resend`, the `FullState` fallback, `Delivered` gossip and the pending-delta GC are all
untouched and all still useful — they buy *promptness* and bound the buffer, they were never what
made this correct. One consequence worth writing down: after this change, out-of-order application
is safe, so `pendingInbound`'s in-order drain becomes an optimisation rather than a requirement.
**Do not remove it** — it keeps `expectedReceiveSeq` meaningful, which is what `resyncReceiveCursor`
and the GC watermark are built on.

## The anti-entropy gate is not disturbed

`Quilter.stateRoot()` hashes the state as it appears on the wire, so the question is whether the
delta path can leave two peers logically equal but bytewise different. It cannot, and for a reason
stronger than inspection: the delta-mutator law is asserted **on encoded bytes**, not on `equals`.
0 failures in 800 randomised cases. A receiver that absorbs a delta reaches a state that encodes
identically to the sender's, so their roots agree.

Measured directly: converged anti-entropy costs **94.0 b/node/round on both paths**, at every state
size tried. The gate engages exactly as it does today.

The negative result matters more. Under #2044's add delta the receiver keeps a superseded dot
forever, the roots never match again, and **every** anti-entropy round that draws that pair falls
back to shipping a full state — i.e. the issue's proposed fix would have silently switched #1955 off
for the very consumers it was meant to help.

## Canonical encoding: no new wire type exists

The hard constraint is satisfied by construction, and the reason is the design's best property:
**the delta is an `ORSet<E>` / `ORMap<K,S>` / `LWWMap<K,V>` — the same type, the same serializer, the
same `QuiltMessage.Delta<S>` envelope.** There is no new wire type to canonicalise, no table, no
index, no second encoding. The frame is smaller; nothing about it is new. Every candidate in #2037,
by contrast, adds a wire type and inherits that whole risk.

One shape does change, and it needs a pin:

**`DotContext.cloud` becomes non-empty on `ORSet`/`ORMap` frames for the first time.** A delta's
context is typically a single non-contiguous dot, which `DotContext.compact` leaves in the cloud.
`CanonicalGoldenVectorTest` records — as of #2038 — that `cloud` is empty in the `ORSET` and `ORMAP`
vectors and that the cloud sort is pinned only by the standalone `DOT_CONTEXT` vector. So the sort is
implemented and pinned, but never on an `ORSet` frame. **Deliverable: `ORSET_DELTA` and `ORMAP_DELTA`
golden vectors.**

Why it is canonical today, measured rather than argued: `DotContextSerializer` sorts `vv` by
`ReplicaId` and `cloud` by `Dot` before encoding, and `DotContext.compact` is a fixpoint (per
replica, `vv` is the largest gap-free prefix; the cloud is the rest) — a pure function of the dot
set, not of insertion order. Two replicas reaching the same delta by different construction orders
produced identical bytes; the 800 byte-level law comparisons are the broader version of the same
check. **Nothing here is insertion- or merge-ordered, so nothing is disqualified.**

## Which other types are in the same class

#2044 asks for `LWWMap`/`GSet` to be audited and asserts `MVRegister` is affected. Measured:

| type | mutator returns | full state | minimal delta | ratio | verdict |
|---|---|---|---|---|---|
| `ORSet` | full state | O(entries) — 478,990 b @ 10k | 120 b | 3,926× | **in scope** |
| `ORMap` | full state | O(entries × value) | ~flat | ≥ `ORSet` | **in scope** |
| `LWWMap` | full state | O(keys) — 578,620 b @ 10k | 63 b | 9,184× | **in scope, and the easiest** |
| `EphemeralMap` | full state | O(replicas) — 8,350 b @ R=256 | 43 b | 194× | follow-up |
| `MVRegister` | full state | O(replicas) — 970 b @ R=64 | 86 b | 11.3× | **not this class** |
| `LWWRegister`, `Gauge` | full state | O(1) — one tagged cell | itself | 1× | already minimal |
| `GSet`, `TwoPhaseSet`, counters, sketches | `Patch` | — | — | — | already minimal |

Two corrections to the issue:

- **`MVRegister` is not the same defect.** `set` already replaces the whole store with a single
  dot — the state that ships is one write plus an O(replicas) context, never O(entries). 11.3× at 64
  replicas is real but it is a different term, and bundling it would blur the story. Follow-up.
- **`LWWMap` *is* the same defect and is not mentioned as such.** Its state is O(keys) and its
  minimal delta is `LWWMap(mapOf(key to cell))` with **no causal reasoning at all** — `piece` is a
  per-key max-tag merge. Highest ratio measured, lowest risk, fully parallel with the other two.

`GSet` is already minimal (`Patch(GSet(setOf(element)))`), so #2044's suspicion there is unfounded.

## API shape

### The delta type

`Patch<ORSet<E>>`, `Patch<ORMap<K,S>>`, `Patch<LWWMap<K,V>>`. No new public type. `Quilter`,
`QuiltMessage` and every serializer are untouched. The `explicitApi()` bar applies to the mutators
themselves: explicit `public`, consumer-facing KDoc, and a `@sample` in
`kuilt-crdt/src/commonSamples/kotlin/` (samples compile as part of `commonTest`, so a broken one
breaks the build). The sample must show the property that makes it worth having — that the delta
retires the dots it supersedes — not merely that `add` returns something.

### Does `add`/`remove`/`put` change signature? — this is Iain's call

**Recommendation: yes.** Change the return type to `Patch<…>` and let call sites migrate. Reasons:

- **The repo already answered this exact question once.** #739's item 2 was "`HyperLogLog.add()`
  returns `HyperLogLog`, not `Patch<HyperLogLog>` — align it with the `Patch`-returning siblings",
  and it landed as PR #744, *"HyperLogLog sparse delta and Patch return type"*. Same defect, same
  resolution.
- **`ResettableCounter` is the in-tree `Causal`-backed precedent** and has only the `Patch` form.
- **A sibling method leaves the footgun on the obvious name.** #739's whole structural point was
  "make 'delta = minimal cell-level fragment' the actual idiom". `addDelta` next to `add` means the
  next consumer still writes `Patch(state.add(…))` and still pays O(n).
- **`Quilted`'s own KDoc is currently wrong** and should be corrected in the same change. It says the
  `Patch` wrapper "is reserved for CRDTs whose delta is a strict, non-obvious subset of their state"
  and offers registers and maps as types where the whole state *is* minimal. For `LWWRegister` and
  `Gauge` that is true; for `ORSet`, `ORMap` and `LWWMap` it is exactly false, and the KDoc is why
  nobody looked.
- **The break is compile-time, never silent.** A type change fails every call site, including
  `Patch(it.add(…))`, which becomes `Patch(Patch(…))`. There is no path where old code compiles and
  behaves differently.

**Blast radius:** in-tree only, spread across `:kuilt-crdt` tests/samples, `:kuilt-conformance`,
`:kuilt-scale`, `:kuilt-warp`, `:kuilt-otel`, `examples/`, and the `crdt-orset` / `crdt-ormap` /
`crdt-overview` Writerside topics. The exact number is whatever the compiler reports, and the plan
makes counting it a step rather than a guess.

**If the answer is no**, the fallback is additive `addDelta` / `removeDelta` / `putDelta` siblings.
Everything else in this design is unchanged — the delta computation, the laws, the golden vectors and
the `kuilt-warp` migration all land either way. **The naming decision gates one PR, not the win.**

### The idiom this pushes consumers toward

`quilter.mutate { it.add(replica, element) }` — read-modify-write inside `Quilter`'s lock. Today's
`quilter.apply(Patch(quilter.state.value.add(…)))` reads outside it, and that is a live hazard
independent of this design (see below). The KDoc and `@sample` should show `mutate`, never `apply`.

## A pre-existing defect this design did not introduce, and must not be blamed for

Two mutations computed from **one snapshot** mint the same dot. On the full-state path *and* on the
delta path, identically. The consequence is worse than aliasing:

```
base = {seed ↦ (a,1)}
t1: base.add(a, "x")   → dot (a,2)
t2: base.add(a, "y")   → dot (a,2)      ← same snapshot, same dot
base ⊔ t1 ⊔ t2         → x GONE, y GONE
```

Each join sees the other's dot in its context but not in its store, reads that as a deliberate
remove, and drops it. **Both writes are lost.** Measured: `x=[] y=[]`.

`Quilter.mutate` prevents it — its KDoc already names this class for counters ("the lost-update class
that bit same-replica counter increments"); for dot-based types the outcome is annihilation rather
than a lost max. **`WarpNode.enqueue(taskId, CoordinationKind.Coordinated)` reads
`coordQueueQuilter.state.value` outside `WarpNode`'s lock** — every other mutation site in that file
holds it. That is a live bug on a shipped consumer and gets its own issue; it is *not* in scope here,
and this design neither creates nor worsens it. It does make it easier to avoid, because `mutate`
becomes the natural spelling.

## What is deliberately unchanged

- **`QuiltMessage`, `Quilter`, and every serializer.** No protocol change, no wire-format change.
- **`ORSet.add`'s superseding behaviour.** Without it an element re-added `k` times carries `k` dots
  forever. Preserving it is *why* the delta context needs the superseded dots.
- **`pendingInbound`'s in-order drain.** Now an optimisation rather than a correctness requirement;
  removing it would break `expectedReceiveSeq`, which the GC watermark depends on.
- **`ResettableCounter.reset`.** Correct as it stands — "retire everything I have observed" is its
  actual meaning.
- **`MVRegister`, `EphemeralMap`.** Measured as a different, much smaller term. Follow-ups.

## Risks

| Risk | Handling |
|---|---|
| A future mutator is added with a delta that violates the law | The law test is generic over randomised states and runs per type; a new mutator without a law case is the reviewable omission |
| `cloud`-carrying `ORSet` frames encode differently on another target | The two new golden vectors are cross-target by construction (`CanonicalGoldenVectorTest` runs on every target and forbids per-target recording) |
| The `kuilt-warp` migration changes behaviour, not just cost | `WarpNode`'s mutation sites move to `mutate {}`; the E2E cluster tests are the gate, and per repo convention a consensus/runtime-behaviour change runs the **full** `./gradlew build`, not a module build |
| The measured saving does not survive real transports | The numbers are encoded-frame bytes through the real codec on a real `GossipSeam`; transport framing is excluded on both sides, so the ratio holds |
| Iain declines the source-breaking change and the plan stalls | Sequencing puts the naming decision in the last, smallest PR; every other slice lands regardless |

## Testing

1. **Reproduce both defects first**, with #2044's stated shapes, before writing a line of the fix.
   The corrected shapes are only meaningful against a recorded red.
2. **The law, on bytes, per mutator**, over randomised states — `equals` alone would pass a state
   that encodes two ways.
3. **Shuffled + duplicated + partial delivery** convergence, byte-compared.
4. **Add-wins in both orders**, and a remove delta applied before the add it retires.
5. **Golden vectors** for delta-shaped `ORSet`/`ORMap` frames, cross-target.
6. **Re-meter** in `:kuilt-scale`. `DotWireEncodingCostModelTest` already asserts one write costs
   more than a join and says in its own message *"if this ever inverts, `ORSet` grew a delta mutator
   and part (H)'s budget is stale"* — that assertion must be inverted deliberately, not deleted.
7. **Full `./gradlew build detektAll --rerun-tasks`** before the `kuilt-warp` slice merges.

## Success criteria

1. `X.piece(mᵟ(X))` equals `m(X)` **byte-for-byte** for every mutator on `ORSet`, `ORMap`, `LWWMap`.
2. Reinstating either of #2044's shapes turns a named test red — the resurrection for `add`, the
   whole-set wipe for `remove`.
3. Metered cluster egress per write is flat in state size, and converged anti-entropy is unchanged
   at ~94 b/node/round.
4. `CanonicalGoldenVectorTest` carries a delta-shaped vector for `ORSet` and `ORMap`, green on every
   target.
5. `kuilt-warp`'s four `Quilter`s write deltas, and the full build is green.

## Decisions Iain owns

1. **Source-breaking return-type change on `ORSet.add/remove`, `ORMap.put/remove`,
   `LWWMap.set/remove`?** Recommendation: **yes** — the #739 / PR #744 precedent is this exact
   question, already answered this way, and a sibling method leaves the O(n) footgun on the obvious
   name. Fallback (`*Delta` siblings) is one PR's difference and gates nothing else.
2. **`MVRegister` and `EphemeralMap` in this track or a follow-up?** Recommendation: **follow-up.**
   Both are O(replicas), not O(entries) — 11.3× at R=64 and 194× at R=256 against 3,926× and 9,184×
   for the types in scope. Different term, different argument, and bundling them dilutes the
   before/after.

## How the figures were produced

Two throwaway probes, run on `jvmTest`, deleted before commit. The plan restates both as required
implementation steps, because a measurement nobody can re-run is a claim.

- **`:kuilt-crdt`'s `jvmTest`** — the delta mutators added to `ORSet`/`ORMap` temporarily alongside
  `*IssueShape` negative controls; then the law over 400/300 randomised states per mutator with byte
  comparison, the shuffle/duplication convergence trial, the add-wins and out-of-order orders, the
  encoded-size sweep, and the cross-type audit table.
- **`:kuilt-scale`'s `test`** — a copy of `DotWireEncodingCostModelTest`'s part (I) harness
  (`buildInMemoryMesh` + `GossipSeam` + `MeteredSeam`, `UnconfinedTestDispatcher`, seeded RNG,
  heartbeats pushed past the window) parameterised on full-state vs delta and on 100 / 400 / 1,600
  entries, metering one add, one remove, and twenty converged anti-entropy rounds on each path.
