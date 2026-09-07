# A departing peer declares what it wrote

When somebody closes the app and leaves a shared session, the work they were part of should carry
on without them. Today, in one specific and avoidable case, it doesn't: their departure can leave a
pool of shared allowance frozen — not just theirs, but everyone else's at the same table too, and
with no way back except that person returning.

This document proposes a small change that prevents it: **when a peer leaves cleanly, it says on the
way out what it had already spent and handed on.** Everyone else can then settle up without it.

The rest of this document is the mechanism, and it gets technical quickly. The one-sentence version
is above.

---

## Status

**⛔ UNSOUND AS WRITTEN — do not implement.** Written 2026-09-06 for [#2600]; an adversarial review
the same day found two design defects and one premise inversion. The mechanism is repairable, but
this document is not the repaired version.

| | finding | where |
|---|---|---|
| **C1** | The fold bypasses the enrolled-at-barrier gate. A peer that departs, **re-enrolls**, writes more, and is still enrolled when the barrier commits lands in `acksOn(e)` anyway, so `pendingAcks` reads empty and the move drains from a **stale** declaration. §5.3's mechanism 2 is false *under this proposal's own mechanism*, and the proposal **converts a safe freeze into a wrong move**. | §5.3, and Task 3 of the plan |
| **C2** | The declaration is **not** "honest by construction". It is read with the writability gate still open, so `reserve`/`schedule`/`complete` can raise the peer's own slots between the read and the applied `Depart` — plus the documented `complete`-after-depart leak. The ack path avoids exactly this by marking and reading under one lock. | §3, §5.4 |
| **C3** | **No shipped configuration can reach the freeze this prevents.** `EntitlementLedger.transfer` has zero production callers, `HeddleNode` states there is no public `delegate`/`transfer`, and `HeddleControlPlane` is `internal`. | inverts Open Question 1 |

**What the repair looks like** (not applied here): filter the fold by `replica !in roster.enrolledAt(index)`; specify close-gate-then-flush-then-read under the node lock; drop the control-plane `ControlDepartureSink` entirely and let the governed node build its own declaration; and lead the document with C3's reachability fact.

**What survives review:** there is no third route to the refusal; the fold is arithmetically correct
on the [#2610] fixture; log-purity holds; and §5.3's mechanism 3 — the boot gate, which this document
originally admitted it had not located in source — **does exist**
(`GovernedHeddleNode.writable`/`isWritable`). Mechanism 3 was never the problem; mechanism 2 is.

## 1. The failure, concretely

A group's entitlement is re-homed from a retired inbound edge onto a live one by a `Reconcile`,
which derives the move from the per-peer finals a §6.2 fence recorded. `EntitlementLedger`'s
`relocationPatch` refuses that move when a **still-uncancelled carried row**'s donor is absent from
the acks (`unackedCarriedDonors`).

Refusing is correct — moving a fraction of a key splits a pocket across two generations, putting the
credit on the live key and its funding on the dead one. But the refusal is **per edge**, so *every*
peer on that edge is frozen, and `holdings` reads counter terms at the group's live generation only,
so each of them reads **0**.

Measured on the [#2610] fixture — a chain `carol → alice → bob` at group `h`, plus an unrelated
`dave`:

| Peer | Units | Standing |
|---|---|---|
| `alice` | 60 | the departed donor |
| `bob` | 40 | funded *through* alice's unacked row |
| `carol` | 20 | holds an outflow row **to alice** |
| `dave` | 30 | no transfer row anywhere |
| **total** | **150** | the group's entire live supply, all reading 0 |

Only the donor returning and re-acking clears it.

## 2. Why this is reachable at all — the ordering that matters

This is the load-bearing observation, and it narrows the problem sharply.

`ControlPlane`'s reconcile gate quantifies the fence over `roster.enrolledAt(quiesceIndex)` — the
roster **at the barrier's commit index** — and refuses while any of that set has not acked. Then, and
only then, it calls `relocationPatch` with `finals[s] = fence.acksOn(s)`. So there are two orderings,
and `ControlCommand.Depart` is **self-service only**, which means a *crashed* peer never departs:

- **(a) The peer was still enrolled when `Quiesce(e)` committed** — it crashed, partitioned, or
  departed *after* the barrier. It is in `enrolledAt`, it has not acked, and the gate refuses
  *before `relocationPatch` ever runs*. Everything at the group freezes, but **no carried row is
  involved**. This is the standing §6.5 residual-1 trade, not this document's problem.
- **(b) The peer's `Depart` committed *before* `Quiesce(e)`** — it is absent from `enrolledAt`, so
  the fence **completes without it**, and `relocationPatch` then refuses on its carried row.

**(b) is the only production route to the refusal in §1.** And (b) is precisely the case where the
departing peer was *present and cooperative* — it executed a self-service `Depart`, which is already
its own promise never to write again.

So the refusal in ordering (b) is not waiting on the peer's **promise**. It already has that. It is
waiting on the peer's **finals**, which nothing ever asked it for.

## 3. The proposal

**Extend `Depart` to carry the departing peer's finals, and satisfy a later barrier from them.**

```
ControlCommand.Depart(replica)
  → ControlCommand.Depart(replica, finals: Map<AttachmentId, SlotFinals> = emptyMap())
```

Three parts:

1. **At depart**, the peer reads its own `baseFinalsOn(edge, self)` for each edge it has a stake in
   and carries the result on the act. It is present and holds its own store, so this is honest by
   construction in a way a *rejoin* ack can never be.
2. **On apply**, the control plane records the declaration beside `FenceState` — a new
   `departedFinals: Map<ReplicaId, Map<AttachmentId, SlotFinals>>`. Nothing else changes; `Depart`
   still moves no entitlement.
3. **When a later `Quiesce(e)` commits**, any departed peer with a declaration for `e` has it folded
   into `acks[e]` exactly as if it had acked, through the existing `FenceState.acked` path — so it
   joins by per-slot max like every other ack.

`fence.acksOn(e)` then contains the departed peer, so `perReplica.keys` contains it, so
`unackedCarriedDonors` does not refuse, and the move proceeds for everyone.

### Why this does not trade fabricate-beyond-owner

§6.1's rule is that a promise about the future can only be made by the promiser — which is why
`Depart` and `QuiesceAck` are self-service and `Enroll` is not. **This proposal does not violate that
rule; it satisfies it.** The declaration is made *by the departing peer, about itself, at a moment
when it is present and holds its own state*, and it is carried on the same self-service act that
already asserts "I will never write again". No third party asserts anything on anyone's behalf.

Contrast the alternative under consideration, a **governed write-off** ([#2600] option 3): that is
functionally a *synthetic zero-ack* authored by a quorum for an absent peer, and it does trade the
property. This proposal makes the write-off unnecessary in ordering (b) rather than safer.

### Precedent for the payload

A declaration read off the peer's own data-plane view is not log-pure, and that is *already* the
established shape: `ControlCommand.QuiesceAck` carries `finals: SlotFinals` read exactly the same
way, accepted because the ack declares what the data plane wrote, under the module's self-asserted,
crash-fault (not Byzantine) trust model. This proposal reuses that shape; it does not introduce it.

**But note the asymmetry that makes this stronger than an ack:** `Reconcile` derives *magnitudes*
purely from the log prefix, which is what makes them consensus facts. A depart declaration is an
*input* to that derivation, exactly as an ack is — not a magnitude asserted by a proposer. The
`Reconcile` act itself still carries a child and nothing else.

## 4. What it does not fix — stated plainly

- **Ordering (a) is untouched.** A crashed or partitioned peer declares nothing, because it never
  departs. If (a) is the common case in practice, this proposal's value is small. That is
  [Open question 1](#open-question-1--is-the-ordering-premise-true).
- **It is prevention, not remedy.** It does nothing for an edge already frozen by a peer that
  departed before this ships. A remedy for the existing state is still [#2600] option 3, and this
  document does not argue for or against it.
- **It does not make the per-edge refusal finer.** `dave`, whose only crime is sharing an edge, is
  still frozen in ordering (a). That is a separate question ([#2600] option 2), and its blast radius
  is smaller than it first appears — `carol` is *not* separable, because separability is decided by
  the **outflow row**, not by where the funding came from, and her row's recipient is the unacked
  donor.

## 5. Costs and hazards

### 5.1 Payload size — the main cost

`Depart` becomes an unbounded-payload act. A peer with a stake in many edges could produce an entry
that exceeds the Raft batch bound — a live concern, not hypothetical ([#2720], [#2721]).

**Recommended narrowing:** declare finals only for edges where the peer holds a **transfer row**
(base or carried) at the edge's path key. Those are the only edges that can trigger
`unackedCarriedDonors`. For an edge where the peer has counters but no row, omitting the declaration
leaves the status quo exactly — today it is not in `finals` there either — so the omission is not a
regression.

⚠ **Do not narrow to a partial `SlotFinals`.** It is tempting to carry only the `transfers` map,
since that is what the refusal reads. Do not: `relocationPatch` drains each acked replica's slots
from its *full* finals, so a zero-filled declaration would under-declare the peer's counters — which
is the amnesiac failure mode, reintroduced deliberately. Narrow the **set of edges**, never the
contents of a declaration.

### 5.2 Unbounded retained state

`departedFinals` must survive until every edge it names has been quiesced and reconciled, which may
be never. `FenceState.acks` already grows monotonically, so this is consistent with what is there —
but it adds a second such map, and neither has a retirement rule. The spec's implementation must
either bound it or state explicitly that it does not, and file the follow-up.

### 5.3 Re-enrollment makes a declaration stale — in the safe direction

A departed peer may re-enroll (`Enroll` is open to anyone). Its declaration then describes state
that may since have been added to. Two things contain this, and both should be asserted by a test:

- `FenceState.acked` joins by **per-slot max**, so a later honest ack can only ever *raise* a
  declared final;
- once re-enrolled, the peer is in `enrolledAt` for every **subsequent** barrier, so ordering (a)'s
  gate applies and the fence waits for its real ack;
- for a barrier that committed **while it was away**, the reconcile gate's own reasoning covers it:
  a peer enrolled after a barrier committed is excluded because *"it cannot have written the edge
  before the barrier it was not yet a member for, and the boot gate stops it writing afterwards"*.
  So the window in which a stale declaration could be the last word is one in which the peer is
  barred from writing anyway.

The dangerous direction would be a declaration that is too **low** and never corrected. Those three
mechanisms are what rule it out, and the third is the one this proposal newly leans on — it was
written to justify *excluding* a late enroller, not to justify *trusting* a stale declaration, so it
should be re-read against this use and pinned rather than inherited.

### 5.4 A dishonest declaration is possible, and in scope

A buggy or malicious peer can declare wrong finals on the way out. This is the same exposure
`QuiesceAck` already carries, under the same self-asserted crash-fault trust model, and it is not a
new class. It should nonetheless be *named* in the KDoc rather than left implicit — the amnesiac
rejoiner is documented on `SlotFinals.transfers` and this is its sibling.

## 6. Open questions

### Open question 1 — is the ordering premise true?

Everything above rests on §2: that (b) is the only production route to the refusal, and that clean
departures are frequent enough for prevention to be worth shipping. §2's *mechanism* is confirmed
from source. What is **not** established is the empirical split between (a) and (b) in real
deployments — and no instrument exists to measure it. If real departures are overwhelmingly (a),
this proposal prevents a case that rarely happens.

An investigation is in flight under [#2600] to establish the ordering behaviour end-to-end. **Do not
implement ahead of it.**

### Open question 2 — does the recovery path this replaces even work?

[#2610] recorded, and did not resolve: *"Not verified: that a departed peer can in practice rejoin
and re-ack a quiesced edge."* `HeddleGoverned.reconcile` opens a barrier only where the edge has
never been quiesced, so a rejoined peer is **not** re-asked through that path; an ack could reach the
log only via the peer's own replay of the original `Quiesce` entry, or an operator re-proposing
`quiesce(e)` (idempotent, and deliberately re-fires the barrier).

If recovery does *not* work, the freeze is permanent in practice rather than merely inconvenient,
and prevention becomes considerably more valuable than this document currently claims.

⚠ **A trap for whoever tests it:** on the [#2610] fixture the departed peer's honest finals on the
quiesced edge are **all zeros** — its base row sits on a different edge — so an honest re-ack is
byte-indistinguishable from the amnesiac `emptyMap()` one. A green test on that fixture proves an
ack arrived, not that it was honest. A fixture where the departing peer's finals on the *quiesced*
edge are non-zero is required for that arm to mean anything.

### Open question 3 — should `Depart` refuse when it cannot declare?

If the narrowing in §5.1 still yields an over-large payload, `Depart` must either refuse or ship an
incomplete declaration. Refusing makes a peer unable to leave cleanly, which is worse than the
problem. Shipping incompletely reintroduces the freeze silently for the omitted edges. **Neither is
obviously right, and this document does not settle it.**

## 7. Testing obligations

- The two orderings in §2, each constructed explicitly, asserting *which* refusal fires and quoting
  its reason — (a) at the fence gate, (b) at `unackedCarriedDonors`.
- A clean depart with a declaration unfreezes the group; the same depart without one does not. The
  negative arm is what makes the positive one a claim.
- The stale-declaration path of §5.3: re-enroll after departing, confirm the later barrier waits for
  a real ack rather than accepting the stale declaration.
- A declaration whose finals are *lower* than the peer's true state must not silently pass — pin
  whichever of §5.3's two mechanisms is doing the work.
- Per the repo's conformance discipline: assert the **precondition** in each arm — that the carried
  row really is present and uncancelled at the moment of the move — or a green proves the fixture
  never reached the state.

## 8. Relationship to the other options

| Option | What it does | Trades the property? | Helps ordering (a)? |
|---|---|---|---|
| **This document** | prevents the freeze for clean departures | **No** — the promiser declares | No |
| [#2600] option 2 — per-pocket refusal | frees uninvolved peers on a frozen edge | No | No — (a) refuses before the ledger is consulted |
| [#2600] option 3 — governed write-off | remedies an already-frozen edge | **Yes** — a synthetic zero-ack by quorum | Yes |

These are complementary rather than competing. This proposal is the cheapest and the only one that
is purely preventive; option 3 remains the only remedy for state already frozen.

[#2600]: https://github.com/tractat-us/kuilt/issues/2600
[#2610]: https://github.com/tractat-us/kuilt/issues/2610
[#2720]: https://github.com/tractat-us/kuilt/issues/2720
[#2721]: https://github.com/tractat-us/kuilt/issues/2721
