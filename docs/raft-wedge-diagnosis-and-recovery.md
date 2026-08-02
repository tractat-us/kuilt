# When a peer lies: staying diagnosable and recoverable

A group of devices running a cluster together keeps one shared history, and they
agree on it by voting. That works beautifully as long as every device is honest —
including the honest failures, the phone in the tunnel and the laptop with its lid
shut. It does not work if one of them is taken over and starts *talking*, but
lying. Someone who controls a voting device can corrupt what the group agrees on,
and no amount of care inside this layer changes that; the security decision is who
you let in, and it is made before any of this code runs.

So this design is not about surviving a liar. It is about the two things you can
still be owed when one shows up:

> **If a lie jams one of your devices, that device can tell you it is jammed, and
> there is a documented way to bring it back.**

Neither of those needs a shared secret, a signature, or a change to what goes over
the wire. That is the whole point — they are properties a device can establish on
its own, from what it already knows.

> Status: **design accepted** (Iain, 2026-07-31). The threat model it sits inside
> is [`kuilt-raft/module.md` → "Trust between peers"](../kuilt-raft/module.md);
> read that first, this doc only decides what to do about the gaps it records.

## The one idea

Two properties, and the constraint that shapes both:

| | |
|---|---|
| **Diagnosable** | A device that is stuck refusing frames it needs says so, once, naming who and what — never silently. |
| **Recoverable** | Every wedge has a written way out that does not violate the algorithm's own safety rules. |
| **Not survivable** | We do not try to keep working *through* a hostile peer. That needs authorization, and authorization is out of scope here. |

Everything below follows from those three lines.

## Refusing a frame must not create a cliff

Devices number their elections: term 1, term 2, and so on, forever upward. A frame
carrying a wildly implausible term is good evidence of garbage or forgery, so the
code refuses one above a ceiling.

A fixed ceiling has a cliff at the top. Once a cluster's own numbering reaches it —
by ordinary elections, or because a liar dragged it there — the *honest* frames get
refused too, by every device at once, and the cluster stops for good. Worse, the
successor of any accepted value fails the same check, so moving the ceiling higher
just moves the cliff. There is no value that removes it.

**The bound becomes relative.** What the receiving device actually knows is its own
term. It can bound the *jump* rather than the destination:

```
m.term > currentTerm + maxTermJump   →  refuse
```

There is no boundary any more. A device sitting at exactly the old ceiling proposes
one more, and every peer at that same number accepts it, because the step is small.
The check refuses a jump no honest election sequence could have produced, which is
the property that was actually wanted all along. `maxTermJump` defaults to `10_000`
on `RaftConfig`; where it is consumed it is a **required** constructor parameter, not
a defaulted knob — the precedent set by the snapshot ceiling. The existing
"a negative term is malformed" guard is unaffected and stays.

The knob has a validated range — `1..2^20`, checked when the config is constructed —
because both ends of it turn the safeguard off. Set to zero it refuses a step of
exactly one, so no device can ever be told about a new election and the group stops
electing; set high enough it stops refusing anything, and the single hostile frame is
back. One is the smallest setting that still lets the group elect; the ceiling is
where a fabricated climb to the top of the number range still costs more than a
trillion accepted frames, while remaining a hundred times the largest absence anyone
would call recoverable. The derivation, in numbers, is on `RaftConfig.maxTermJump`.

### The absolute ceiling keeps a different job

It does not disappear; it stops being the *adoption* rule and stays where it always
belonged — on the storage and well-formedness paths, where a value is being read back
from a disk or unpacked from a frame rather than compared to our own progress.

It also stays at `2^60`, deliberately, and it is worth recording why the obvious
tightening is wrong. The argument for a lower ceiling was that it would catch a
storage adapter that loses precision — a column that round-trips terms through a
floating-point type. It does not. `2^60` is a power of two, so a `Double` stores it
*exactly*; a ceiling-only test passes the very adapter the tightening was meant to
catch. What reddens such an adapter is a value that needs all its mantissa bits, and
the conformance suite pins one beside the ceiling for exactly that reason. Range
checks catch wild garbage; they do not catch lossy columns, and asking them to is how
you get a check that looks protective and is not.

What `2^60` *is* good for is headroom. Terms get incremented, and an unchecked
increment near `Long.MAX_VALUE` overflows; `2^60` leaves a factor of eight below it.
That is the ceiling's real remaining job, and it is a good one.

## A jammed device must say so

Both ways a device can get stuck refusing frames it needs are, from the inside, the
same shape.

One: it has been away a long time, the set of voting members rotated while it was
gone, and the frames that would teach it the new set are precisely the frames it
refuses — because they come from senders it does not recognise as voters. Two: an
honest jump lands beyond the bound above, because the cluster really did hold that
many elections without it. Different causes, one symptom:

> **I am persistently refusing frames that would otherwise let me make progress.**

That is checkable locally, with no new trust and no new wire field: a run of dropped
leader→peer frames from a sender claiming a term at least as high as ours, while our
own commit index does not move. Neither half alone is interesting — dropping frames is
normal, and a stalled commit index is normal — but together, sustained, they are the
signature of a device that has argued itself out of the conversation.

When it fires it emits a `RaftMetric` naming **identities and state, not counts**: the
sender, their term, our term, the voter set we are holding, and which gate did the
dropping. A count tells you that something is wrong; the identities tell you *what*,
and this is a diagnosis you will be reading from a log after the fact, not watching
live.

**Latched once per device, per voter-set epoch.** The temptation is a finer latch —
per sender, say — and it is a trap: a hostile peer that alternates between two
identities mints a fresh warning per alternation, and you have handed unbounded log
volume to the exact sender the check exists to contain. A once-per-epoch latch closes
that and costs nothing, because the thing it reports does not change until the voter
set does.

Note what this section does **not** do. The gates themselves are unchanged — nothing
is relaxed, no frame that was refused is now accepted. Only the silence is fixed.

## The way back is a new identity, not a wiped disk

A device that has wedged needs a route back into the cluster. The route is:

**Start it as a genuinely new member — a fresh `NodeId`, empty storage — and admit it
with an ordinary single-server membership change.**

This is documentation, not code. Nothing in the library needs to detect the wedge and
self-heal; the operator (or the app's own supervision) does the two steps, and the
existing membership machinery does the rest.

It is the right route because of what it preserves. A device promises never to vote
twice in the same term, and it keeps that promise by writing its vote down before
answering. A brand-new identity has genuinely never voted for anything, so admitting
one breaks nothing.

> ⚠ **Never wipe storage under the same `NodeId`.** It looks like the same fix and it
> is the opposite of it: the device comes back with no memory of a term it already
> voted in, and votes again. Two leaders in one term, and the guarantee the whole
> algorithm rests on is gone. The identity change is not cosmetic — it is the entire
> reason the recovery is safe.

**The accepted residual, stated plainly:** admitting a new member needs a quorum of the
existing ones. So this recovers devices one at a time, against a cluster that is still
working. If a *majority* wedges at once there is no path back except re-bootstrapping
the whole cluster. A per-device one-shot relaxation of the gates would have recovered
that case too; it was considered and not taken, because it buys the rare case by
weakening the common one.

## What is deliberately not built

Everything above is what a device can do alone. The thing that would dissolve the
residual — and close the accepted exposures the threat model records — is
**authorization**: some proof travelling with a frame that a quorum really did agree
to what it claims. Signed configurations, a quorum witness, an end-to-end digest.

That is a real design and it is deliberately not being built. It is tracked as
[#1907](https://github.com/tractat-us/kuilt/issues/1907), which stays open as the
place that decision lives. The reason for naming it here rather than quietly leaving
it out: every accepted exposure in the threat model points at the same missing
mechanism, and a reader who works that out for themselves deserves to find it already
written down.

## Why these decisions were not something else

Three alternatives were live and are recorded here so they are not re-litigated.

- **Relaxing a gate on suspicion of being wedged.** Rejected. The wedge is detectable
  but the *cause* is not — the same symptom is produced by an honest long absence and
  by a hostile peer, and a relaxation that fires on the symptom fires for both.
  Detection is safe precisely because it changes no decision.
- **Containing the ceiling boundary rather than removing it.** Rejected as subsumed.
  Once adoption is relative there is no boundary left to contain, so the containment
  work, and the "three boundaries must move together" coordination it implied, both
  evaporate. The prior investigation stands as the record of why the absolute-ceiling
  approach was abandoned rather than improved.
- **Lowering the plausibility ceiling.** Rejected on evidence, above. It was the
  author's own recommendation and the conformance work refuted it.
