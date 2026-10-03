# Agent workspace: looking again when the news arrives late

Sam's phone shows the agent's pick, Trattoria Uno at 40 a head, and says it fits. A minute later
Alex's new budget of 30 reaches Sam's phone. Uno no longer fits, but the screen still says it does,
because the phone judged the answer once, when it first arrived, and never looked again. This page
decides how a phone looks again, which part of that kuilt owns, and what it costs. (Design for
#2880, part of #2869.)

## Current design

**Implemented by tractat-us/kuilt#2893** (`Presenter`). This section records the design as it was
before that change.

`KuiltBackend` judges each answer once per phone. `presentNew` assesses an `AgentProposal` the first
time a replica holds it, records one `Presentation`, and never revisits it (`presented` is a
first-time set). The verdict is stored in that record. Nothing re-runs `Assessment.assess` when the
replica's log grows.

Two consequences, both measured in `RESULTS.md` under *How firm*:

- **S2.** Sam and the remote machine see `r1` as fitting before Alex's budget reaches them, and it
  stays standing after. Scored on each actor's standing answer at the end of the run (ruled on
  2026-10-01: the latest proposal shown), kuilt has 2 stale on S2 and both baseline variants 0. H1
  stops.
- **Unknown never resolves.** A phone that judged an answer *unknown*, because it had not yet
  received the whole basis, keeps that verdict after the missing inputs arrive. The backend never
  reaches unknown (`RESULTS.md`, *What these numbers rest on*), so no metric shows it. The test
  mesh shows what a re-judge would find: in `AdversarialTraceTest.heldFramesLeaveTheProposalUnknown`
  Alex's verdict is unknown while Sam's frames are held, and a fresh `assess` after they land says
  the answer fits. Only that hand-written second call sees it; the backend's presentation would
  keep the flag up.

The cause is that a verdict is stored when it is a function. `Assessment.assess(p, known)` is pure:
the same proposal and the same log always give the same verdict. Storing its result at first
delivery turns a derived value into state, and state goes stale.

## Decision

### Ruling 1: yes, re-judge; the relevance rule does not change

A shown answer is re-judged whenever the viewer's replica delivers anything new. **The trigger is
mechanical. The judgement is the existing one.** The same `Assessment.assess`, with the same
relevance clauses (a) to (d) of `HYPOTHESES.md`, decides the new verdict. There is no second,
"relevant enough to re-check" rule: an input that would have flagged the answer on arrival flags it
later, and one that would not, does not.

So the app still owns relevance, exactly as `CONTRACTS.md` draws the line. What changes is *when*
the app's rule is consulted: on every change to what the phone holds, not once.

Precisely, for the prototype:

1. **Only the standing answer is re-judged**: per actor, the latest proposal it has been shown, as
   the 2026-10-01 ruling defines it. A superseded answer is not re-judged and costs nothing.
2. **The verdict is derived, never stored.** A standing answer holds the proposal and no verdict
   field; its verdict is `assess(proposal, currentLog)` read when needed. This is the type-shaped
   half: a record with no verdict field cannot carry a stale one.
3. **Every verdict change is a presentation.** When a step settles, each actor's standing answer is
   re-assessed. If the verdict differs from the one last shown, a new `Presentation` is recorded with
   the current `known`, so the oracle scores the re-judgement like any other: an earned flag is
   free, a wrong flag counts in `falseInvalidations` (H1b), a wrong "fits" counts in
   `staleTreatedAsCurrent` (H1).
4. **A prompt is charged on entering a flagged state.** Fits → needs review or unknown costs one
   `humanPrompt` for a person (never for an agent host, as today). Needs review → needs review with
   more inputs, unknown → needs review, and anything → fits cost nothing: the person is already
   looking, or has nothing to look at.
5. **Order within a step:** present new proposals first, then re-judge standing answers. A proposal
   that arrives in the same step as the news supersedes the old answer before the old one is
   re-judged.

### Ruling 2: app policy in the prototype; kuilt adds nothing for it

The re-check lives in the prototype, as the presentation logic above. **No new kuilt primitive.**
The reasoning, candidate by candidate:

- **A "re-assess on delivery" signal** ("tell me when a new dot bears on an answer shown") cannot be
  kuilt's. "Bears on" is relevance, and relevance is the app's. Strip the relevance out and what is
  left is "tell me when the state changes", which kuilt already ships: `Quilter.state` is a
  `StateFlow`.
- **A flow of newly delivered dots** is derivable from that `StateFlow` and needs no new surface.
  Delivery only grows: a join moves up the lattice, and `Quilted`'s contract keeps compaction from
  un-delivering a dot (a `Compact` re-emits what it recorded, a window raises the floor). So the
  news between any two observed states is the difference of their delivered sets, and `StateFlow`
  conflation drops nothing a later read cannot recover. A separate event flow would bring a replay
  and buffering policy, and with it the "lost before you subscribed" trap the state flow does not
  have. The re-check as decided does not even need the difference: it re-runs `assess` on the whole
  current log.
- **`Quilted.delivered(dot)`** stays what `CONTRACTS.md` already confirmed it as: the right kuilt
  addition, independent of this design. The re-check reaches it only through `Assessment.assess`
  step 1, which already holds one of the four hand-written copies, so #2880 adds no fifth copy and
  does not wait on it.

What would change ruling 2: a second consumer that re-checks incrementally (only the new entries,
for a log too large to re-assess whole) and hand-rolls "entries delivered since the last state".
That is the shape that would justify a kuilt helper, and it would be built on `delivered(dot)`.

## How the fix is proven

The re-score PR runs M1 again against the amended criteria, unedited. Expected movement, reasoned
from the step lists and `Relevance`, to be confirmed by the measurement rather than taken from here:

| Scenario | What the re-check does | end-of-run stale (kuilt) | `humanPrompts` (kuilt) |
|---|---|---|---|
| S2 | At `Reconnect(alex)` Sam's and the remote's `r1` meets budget 30 < 40, clause (c): flagged. Sam is asked. | 2 → **0** | 1 → **2** |
| S4 | At `Edit(budget30)` Alex's standing `r1` meets his own budget 30 < 40: flagged. Alex is asked. Sam is partitioned; at `Reconnect(sam)`, `r2` arrives with the budget and supersedes `r1` first (rule 5). | 0 → 0 | 1 → **2** |
| S1, S3, S5, S6, S7 | No input reaches any phone after its answer is shown. | unchanged | unchanged |

Tests the implementation PR must carry, each with a control that has to go red:

- **The fix.** `KuiltMeasurementTest` pins S2's end-of-run stale at 0. **Positive control:** judge
  at first delivery only (skip the re-judge pass) and it must read 2 again.
- **The prompt rule.** A test where a `Note` reaches a phone after its answer is shown: 0 new
  presentations, 0 prompts. Control: charge a prompt on every re-judge, not only on entering a
  flagged state, and it reds. A second test re-flags an already flagged answer with a further
  relevant input: 0 new prompts.
- **Unknown resolves.** A test on the same held-frames rig, driven through the presentation logic
  rather than a hand call to `assess`: after the frames land, Alex's standing answer reads as
  fitting, and no prompt is added. Control: store the first verdict, and it stays unknown.
- **Order within a step.** S4 pins that Sam is not prompted about `r1` at `Reconnect(sam)`.
  Control: re-judge before presenting new proposals, and Sam's prompt count rises.

## What it costs: H4 on S4

**The re-check trades S2's stale answer for a prompt, and on S4 it costs one H4 does not
forgive.** H4 scores S4 like with like, and as registered kuilt passes when its prompts are ≤ the
baseline's. With the re-check, kuilt asks Alex about `r1` the moment his own lower budget makes it
over budget: 2 prompts against the baseline's 1. **H4 would stop on S4.**

The flag is earned. Ground truth for Alex after his edit is Due Fratelli, not Uno. The baseline does
not ask because it never looks again: Alex's screen shows `r1` as fitting until `r2` replaces it.
That stale interval is real but invisible to every metric, because per-presentation scoring sees
only first arrivals and end-of-run scoring sees only `r2`. S4 stays in H4 under the rule as written,
because neither design has a *scored* stale presentation there.

So fixing #2880 moves the stop from H1 to H4, unless a ruling is made **before** the re-score:

- **Accept it.** H4 stops on S4, and the record says the extra prompt buys a screen that is never
  wrong. Honest, and costs the H4 pass.
- **Amend H4 before measuring** so a scenario where one design shows a stale answer at any settled
  step is not like with like. S4 would then drop out of H4 and leave it scored on S1 alone. This is a
  criteria change, so it belongs in `HYPOTHESES.md` and must land before the numbers, as the
  2026-10-01 amendments did.

**Ruled 2026-10-01 (Iain): accept it.** H4 stays as written. If the re-score stops H4 on S4, the stop
is recorded as measured, and `RESULTS.md` says the extra prompt is a correct flag.

## What the fix is unpinned on

- **Delivery never shrinks.** The decision rests on a replica's delivered set only growing. If a
  compaction path ever dropped a dot from both `causalDots()` and the floor, a derived verdict could
  fall back to unknown, and rule 4 would charge a second prompt. `compactedReportLeavesBasisReadable`
  pins the one compaction path the prototype uses; `dropWindow` is pinned only by direct-call tests.
- **Observation granularity.** Prompts are charged on verdict transitions, so the count depends on
  how often the verdict is read. The backend reads once per settled step. A real app collecting a
  conflated `StateFlow` could fold "fits → needs review → fits" (only reachable through a removal)
  into no transition at all. No scenario removes an input.
- **The relevance policy.** The re-check is exactly as good as clauses (a) to (d). Their misses stay
  misses, now repeated on every delivery.
- **The standing-answer definition.** Re-judging only the latest answer shown is what keeps S4's
  superseded `r1` from prompting Sam. Re-scoring every answer ever shown would change both the
  numbers above and rule 1.
- **The end-of-run metric reading the current verdict.** The S2 fix moves the number only if
  end-of-run scoring reads the standing answer's latest verdict, not the one it was first shown
  with. The amended `HYPOTHESES.md` must say so; under the other reading the fix is invisible.

## Settled — don't reopen

- **A kuilt "re-assess on delivery" signal.** Its trigger is relevance, which is app policy; without
  relevance it is `Quilter.state`.
- **A kuilt flow of newly delivered dots.** Derivable from `Quilter.state` because delivery is
  monotone; a separate event flow adds a buffering policy and a subscribe-late loss mode.
- **A second, narrower relevance rule for re-checks.** One rule, consulted more often, keeps the
  arrival verdict and the re-check verdict from disagreeing about the same log.
- **Re-check only on inputs from other replicas, never on the viewer's own edit.** It makes S4's
  prompt disappear by leaving Alex's own screen stale, which end-of-run scoring misses only because
  `r2` happens to follow. It games the scenario rather than fixing the screen, and it contradicts the
  existing rule that a phone holding its own unseen input flags on arrival (S2, Alex).
- **Rerun the agent instead of asking.** kuilt's design never reruns; that is what H2 measures.
- **Storing the verdict and invalidating it on change.** A stored verdict plus an invalidation hook
  is the current bug with one more place to forget; deriving it removes the field.

- **Amending H4 to drop S4.** Ruled 2026-10-01: H4 stays as written and a stop on S4 is recorded,
  not designed away (above).
- **End-of-run scoring reads the verdict each standing answer was last shown with.** Defined in the
  amended `HYPOTHESES.md` (PR #2886), and pinned by
  `EndOfRunScoringTest.laterFlaggedRowForTheSameProposalStands`. So the re-check must emit a new
  flagged `Presentation` row for **every** actor holding the stale answer, the agent's host
  included. On S2 that means both Sam and the remote machine.
