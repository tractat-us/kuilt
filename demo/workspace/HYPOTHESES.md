# Agent workspace: what we expect, written before we measure

> **Frozen evidence (2026-10-05).** This page records M0/M1 of epic #2869 as measured. The code it
> cites (`KuiltBackend`, the scenarios, the oracle and every named test) is at commit `53f89b34` in
> `demo/workspace/src/`. It was removed from this repository afterwards, when the work moved to an
> application prototype outside kuilt. Read code citations against that commit.

Before anyone measures anything, this page sets down what we will count and which results would
change our minds. The numbers come later, in `RESULTS.md`, and are scored against this page as
merged, unedited. If a criterion turns out wrong, the fix is a new PR here that says so, never a
quiet edit after the numbers arrive. (This is the M0 pre-registration for #2869.) One such change
was made after M1; *Amendment (2026-10-01)* at the end of this page says what changed and why.

## The story in one paragraph

Alex and Sam are out looking for dinner. Sam hears their first choice has just closed. Alex, on a
patchy signal, lowers the budget. Meanwhile a remote agent is still researching the old plan, and
its answer lands late: Trattoria Uno, a two-minute walk, at 40 a head. It has closed, and at 40 it
is now over budget. Could the answer have known that? Only if the report reached it. Whichever way
the app keeps shared state, the friends should keep their edits, reuse advice that still fits, and
be asked only when there is a real choice.

## Scenarios

Four restaurants, fixed so live search quality cannot sway the result:

| Venue | Name | Price per head | Walk |
|---|---|---|---|
| `v1` | Trattoria Uno | 40 | 2 min |
| `v2` | Due Fratelli | 25 | 5 min |
| `v3` | Tre Sorelle | 30 | 8 min |
| `v4` | Quattro | 20 | 15 min |

The agent is scripted and deterministic: it picks the **nearest open venue within the last budget
set**. The same inputs always give the same pick, which is what lets us score staleness exactly.

Actors are `alex` (A), `sam` (B) and `remote`, the machine that hosts the remote agent. Each step
list below is exact: `Scenarios.all` implements it step for step. Every step that creates an input
names its key; later tests refer to these keys.

### S1: independent edits

Alex adds a note about Due Fratelli. Sam reports Tre Sorelle full. No agent runs. Both edits should
simply survive.

```
Edit(noteV2, alex, Note(alex, v2, "Quiet back room"))
Edit(fullV3, sam,  Report.Full(sam, v3))
```

### S2: budget changed during inference

Alex is offline. The remote agent starts with nothing to go on and will pick `v1`. While it thinks,
Alex lowers the budget to 30, still offline. The agent returns `v1` at 40, now over budget. Then
Alex reconnects.

```
Partition(alex)
StartAgent(r1, host = remote)              // sees nothing → v1
Edit(budget30, alex, PreferenceSet(alex, budget = 30))   // offline
ReleaseAgent(r1)                           // returns v1 (40)
Reconnect(alex)
```

The one outage action is Alex's budget edit, so `outageActions == 1`. The baseline holds `r1` for
Alex and re-checks it when Alex reconnects, after the queue replays: `budget` has moved, so it
reruns `r1` and gets `v2`. kuilt flags `r1` for Alex under clause (c) below.

### S3: closure report during inference

The remote agent starts and will pick `v1`. Sam, on the network the whole time, reports `v1`
closed. The agent returns `v1` anyway.

```
StartAgent(r1, host = remote)              // sees nothing → v1
Edit(closeV1, sam, Report.Closed(sam, v1))
ReleaseAgent(r1)                           // returns v1
```

Nobody is partitioned, so S3 has no outage actions.

### S4: two conflicting human choices

A first proposal picks `v1`. Sam then drops off the network. Alex lowers the budget, and a second
proposal picks `v2`, which Sam never sees. Alex goes offline too. Each friend accepts the newest
proposal they know of, and each choice is right for what its chooser knew. They reconnect with two
different plans.

```
StartAgent(r1, host = remote)              // sees nothing → v1
ReleaseAgent(r1)                           // v1, seen by alex and sam
Partition(sam)
Edit(budget30, alex, PreferenceSet(alex, budget = 30))   // alex is connected
StartAgent(r2, host = remote)              // sees budget30 → v2
ReleaseAgent(r2)                           // v2, seen by alex only
Partition(alex)
Accept(alex, r2)                           // offline
Accept(sam, r1)                            // offline
Reconnect(alex)
Reconnect(sam)
```

The outage actions are the two accepts, so `outageActions == 2`. This order differs from the
simplest one, where both friends learn about the new budget before splitting up. There, whoever
accepts `r1` accepts a proposal they already know is stale, which mixes S4's question into S2's.
Here, neither accept is stale for its chooser, so the conflict is purely one of choice.

### S5: a report corrected later

Sam reports `v1` closed. The agent starts, sees the closure, and will pick `v2`. Sam corrects the
report: `v1` has reopened. The agent returns `v2`, which is stale, because `v1` is open and nearer.
The missing input concerns a venue *other than* the one recommended.

```
Edit(closeV1, sam, Report.Closed(sam, v1))
StartAgent(r1, host = remote)              // sees closeV1 → v2
Edit(reopenV1, sam, Report.Reopened(sam, v1))
ReleaseAgent(r1)                           // returns v2; truth is now v1
```

### S6: S2 without the outage

S2's steps with Alex connected throughout, which isolates what the outage costs. The agent starts
and will pick `v1`. Alex lowers the budget to 30, online. The agent returns `v1` at 40.

```
StartAgent(r1, host = remote)              // sees nothing → v1
Edit(budget30, alex, PreferenceSet(alex, budget = 30))   // alex is connected
ReleaseAgent(r1)                           // returns v1 (40); truth is now v2
```

The baseline's `budget` scope moves before the release, so it reruns `r1` and gets `v2`. kuilt
flags `r1` for review under clause (c) below. Nobody is partitioned, so S6 has no outage actions.

### S7: a harmless note while the agent is out

The agent starts and will pick `v1`. Sam adds a note about `v1`. Notes never change the pick, so
the agent's `v1` is still right when it returns.

```
StartAgent(r1, host = remote)              // sees nothing → v1
Edit(noteV1, sam, Note(sam, v1, "Book the window table"))
ReleaseAgent(r1)                           // returns v1; truth is still v1
```

The baseline's note bumps its `venue:v1` scope, so it reruns `r1` and gets `v1` again: an
unnecessary rerun. kuilt shows `v1` as applicable, since a note is never relevant. S7 has no outage
actions.

## Metrics

Every metric is a count over scripted actors in one run of one scenario. None is a user study.

- **`editsMade`**: the scenario's `Edit` steps. **`editsPreserved`**: those whose entry is present
  in every actor's final view.
- **`agentRuns`**: every run of the agent, including reruns. **`unnecessaryReruns`**: reruns that
  return the same recommendation as the run they replaced.
- **`staleTreatedAsCurrent`** (ground truth, not policy): the presentations of a proposal to an
  actor *as applicable* where rerunning the scripted agent on *basis ∪ the inputs that actor knew*
  returns a different recommendation. The rerun takes inputs in scenario order. An actor knows every
  input shown to it **and every input it created itself**, served or not. Since the agent is
  deterministic, the oracle computes this exactly.
- **`staleAtEnd`** (end-of-run scoring, added 2026-10-01): the actors whose **standing answer** is
  stale treated as current when the run ends. An actor's standing answer is the latest proposal it
  has been shown, latest by the order the proposals were returned: once `r2` is shown, `r2` stands
  over `r1`. It carries **the verdict it was last shown with**, meaning the last presentation of
  that proposal to that actor. It counts when that verdict was "applicable" and rerunning the
  scripted agent on *basis ∪ every input the actor knows at the end of the run* returns a different
  recommendation. "Knows" has the same meaning as above, read from the actor's final view. The
  verdict is never re-assessed at the end: that would score what a screen *would* say, not what it
  showed. A later presentation of the same proposal, flagged by a re-check, replaces the earlier
  verdict and is what clears the count.
- **`falseInvalidations`**: the presentations flagged **needs review** where that same rerun returns
  the *same* recommendation. Each one is human attention spent for nothing. An *unknown* verdict
  (the presenter had not received the whole basis) is not counted here (amended 2026-10-01): it
  reports network lag, not a relevance call.
- **`unknownPresentations`** (reported from 2026-10-01): presentations whose verdict was *unknown*.
  Reported beside H1b as its own row, and not scored.
- **`missedInvalidations`**: presentations the backend's own relevance policy called applicable that
  ground truth calls stale. In M0 it equals `staleTreatedAsCurrent` for **both** backends: the
  kuilt backend shows as applicable exactly what its policy passes, and the baseline has no
  relevance policy at all, only its version check. So it is defined here for later milestones and
  is **not separately scored** in M0.
- **`humanPrompts`**: the times a person had to choose, whether to review a proposal or settle a
  conflict. Scripted `Accept` steps are not prompts: they are the scripted person's choice, not a
  question the app asked.
- **`outageActions`**: `Edit` and `Accept` steps taken by an actor while it is partitioned.
  **`outageActionsServed`**: those that took visible effect for that actor locally, without the
  network.
- **Tracked, not scored**: wire bytes, from `FaultySeam` delivered-frame counts.

## Relevance policy

Rerunning the agent to check every proposal would defeat the point, so a backend gets a cheap rule
instead. An input the proposal did not receive is **relevant** when it is:

- (a) a `Report` about the recommended venue;
- (b) a `Report.Reopened` about *any* venue, since a nearer place may be back;
- (c) a `PreferenceSet` below the recommended venue's price; or
- (d) a `PreferenceSet` that makes a nearer venue affordable.

A `Note` is never relevant.

This is **policy**. Staleness is scored against ground truth, so the policy's misses and over-flags
are measured, not assumed. S5 exists to test clause (b): its missing input is about a venue other
than the recommended one, which a rule limited to clause (a) would miss.

## Hypotheses

"Baseline" is the server-owned model with scoped version checks and an offline edit queue. It runs
in two variants: `optimisticLocal = false` (the default) and `optimisticLocal = true`, which shows a
queued edit locally before the server sees it. Every hypothesis is scored against **both**
variants, and each variant's result is reported. A hypothesis passes only if it passes against
both, so the baseline gets credit wherever either variant solves the problem.

| id | claim | metric | pass if | stop if |
|---|---|---|---|---|
| H1 | kuilt never treats a stale proposal as current where the baseline does or must rerun to avoid it | `staleTreatedAsCurrent`, `staleAtEnd`, `agentRuns` (`staleAtEnd` added 2026-10-01) | kuilt has `staleTreatedAsCurrent == 0` **and** `staleAtEnd == 0` on each of S2, S3 and S5, **and** on each of them the baseline has `staleTreatedAsCurrent ≥ 1`, `staleAtEnd ≥ 1`, or `agentRuns` greater than the scenario's number of `StartAgent` steps | kuilt has `staleTreatedAsCurrent ≥ 1` **or** `staleAtEnd ≥ 1` on any of S2, S3 or S5 |
| H1b | the relevance policy does not ask people too often | `falseInvalidations`, needs-review flags only (amended 2026-10-01); `unknownPresentations` reported beside it, not scored | kuilt's total across S1–S7 is ≤ 1 | kuilt's total across S1–S7 is > 1 |
| H2 | kuilt spends fewer agent runs on changes that do not matter | `unnecessaryReruns` | on S7, kuilt's value is lower than the baseline's | on S7, kuilt's value is higher than the baseline's |
| H3 | kuilt serves every offline action locally, and the baseline does not | `outageActionsServed / outageActions` | on S2 and S4, kuilt's ratio is 1.0 and the baseline's is below 1.0 | kuilt's ratio is below 1.0 on S2 or S4 |
| H4 | kuilt asks people no more often | `humanPrompts`, like with like only | kuilt's value is ≤ the baseline's on each of S1 and S4 | kuilt's value is > the baseline's on S1 or S4 |
| H0 | the simpler design suffices | H1–H3 | not applicable: H0 is a stop rule | either baseline variant **matches** kuilt (defined below) on all of H1, H2 and H3. Each variant is reported. Then record that the simpler design suffices, and recommend narrowing the epic |

### Three verdicts, and what "matches" means

Each of H1–H4 ends in exactly one of three verdicts: **pass**, **stop**, or **no difference**. The
third is not a soft pass. It says the baseline got the same result without kuilt's machinery, and
it is recorded for H0. A verdict names the scenarios it rests on. Against the two baseline
variants, a hypothesis passes only if it passes against both, stops if it stops against either,
and is otherwise *no difference*.

- **H1.** *No difference* on a scenario: kuilt has `staleTreatedAsCurrent == 0` and
  `staleAtEnd == 0`, and so does the baseline, with `agentRuns` equal to the number of `StartAgent`
  steps (no rerun). If kuilt is at 0
  on all three but one or more scenarios are *no difference*, H1's verdict is *no difference*,
  naming them. A variant **matches on H1** when all three of S2, S3 and S5 are *no difference*.
- **H2.** Scored on S7 alone. S1 runs no agent, so both designs score 0 there by construction, and
  S5 has no rerun in either design. *No difference* is equal `unnecessaryReruns` on S7, and a
  variant **matches on H2** when S7 is *no difference*.
- **H3.** *No difference* on a scenario: both kuilt's and the baseline's ratios are 1.0. If kuilt
  is at 1.0 on both S2 and S4 but either is *no difference*, H3's verdict is *no difference*,
  naming it. A variant **matches on H3** when both S2 and S4 are *no difference*. Under
  `optimisticLocal = true` this is the expected result on S2, where the queued edit shows locally.
- **H4.** *No difference* on a scenario: equal `humanPrompts`. This is a pass for H4, since the
  claim is "no more often", and H4 does not feed H0.
- **H1b** has no comparison with the baseline, so it is only pass or stop.

H3 is scored only on scenarios with `outageActions > 0`: S2 (one budget edit) and S4 (two
accepts). S3 has none, because Sam is on the network throughout, so its ratio would be 0/0 and H3 is
not scored there. S1, S5, S6 and S7 have no partition either.

H4 compares like with like. It is scored only on scenarios where neither design has
`staleTreatedAsCurrent > 0`, `staleAtEnd > 0` (added 2026-10-01) or a rerun, so a prompt is weighed against a prompt, never against a
silent rerun or a stale answer. As the step lists stand, that is S1 and S4. S2, S3, S5, S6 and S7
are excluded, because the baseline reruns or goes stale on each of them. If a run shows a stale
presentation or a rerun on S1 or S4, that scenario drops out of H4 and the result says so.
`humanPrompts` is still reported for every scenario. What **would** count against kuilt on H4: any
prompt on S1, where nothing an agent said is in doubt, or more prompts than the baseline on S4,
where both designs face the same conflict.

## Trust boundary

The promise under test: **a late agent result keeps its original input history. Merging it cannot
make it look as if it received newer reports.**

The claim is negative, and its limits matter as much as the claim:

- With complete tracked inputs, we can establish that a run **did not receive a report through those
  inputs**. We cannot establish that it **did not know** the fact. A model may know from elsewhere
  that Trattoria Uno closes on Mondays.
- Receiving an input does not prove the model understood it.
- Missing or incomplete evidence yields **unknown**, never proof of absence.
- The boundary is the instrumented runtime. Nothing here proves anything about a dishonest remote
  host.

## Rulings (2026-09-29)

Iain ruled on the open questions in a comment on the pull request that added this page. They are
folded in above.

- **S7 added.** A harmless note on `v1` while `r1` is out. S1 stays agent-free; H2 is scored on S7
  only, and S5 leaves H2.
- **Both baseline variants.** A hypothesis passes only if it holds against `optimisticLocal = false`
  and `true`, and each variant is reported.
- **S6 replaced** by "S2 without the outage", with Alex connected throughout.
- **Four definitions approved.** An offline `Accept` counts toward `outageActions`. An actor knows
  the inputs it created itself, served or not. `missedInvalidations` is not scored in M0. The kuilt
  backend's design (Task 7) decides its S4 `Accept` numbers.
- **H4 is like with like.** Scored only where neither design has `staleTreatedAsCurrent > 0` or a
  rerun: S1 and S4 as the lists stand. `humanPrompts` is reported for every scenario.

## Amendment (2026-10-01)

Made **after M1 was measured**, and before any fix for tractat-us/kuilt#2880 (re-checking an answer
already shown). `RESULTS.md` proposed both changes. Iain accepted both on 2026-10-01, in a comment
on #2869. `RESULTS.md` keeps the M1 verdicts as first scored, beside the re-score.

**Why.** M1 showed two places where the criteria above measured the wrong thing.

- An answer was scored only when first shown. In S2, Sam and the remote machine are shown `r1`
  (Trattoria Uno, 40) as fitting, then Alex's budget of 30 reaches them. Nobody looks again, so they
  end the run with an answer that no longer fits. No metric saw that.
- H1b charged an *unknown* verdict to the relevance policy. An unknown means the news had not
  arrived yet. That is network lag, and the trust boundary promises exactly this answer when evidence
  is missing. Under delay it could stop H1b on a run where the policy never misjudged anything.

**What changed.**

1. **H1b scores needs-review flags only.** `falseInvalidations` no longer counts an unknown
   verdict. `unknownPresentations` is reported beside H1b as its own row and is not scored.
2. **End-of-run scoring.** The new metric `staleAtEnd` scores each actor's standing answer at the
   end of the run (see *Metrics*). The standing answer is the latest proposal the actor has been
   shown. The "every answer ever shown" variant was considered and rejected: it would re-score a
   superseded `r1` in S4 and so push S4 out of H4's like-with-like set. The verdict scored is the
   one the answer was last shown with, never a fresh re-assessment at the end. A fresh re-assessment
   would pass a design that never re-checks anything.

**How the two scorings combine.** Each scoring stops a hypothesis on its own. This is the stricter
reading, chosen on purpose. For H1, kuilt must have `staleTreatedAsCurrent == 0` **and**
`staleAtEnd == 0` on each of S2, S3 and S5 to pass, and `≥ 1` on **either** count, on any of them,
stops it. The baseline side reads both counts the same way, since a stale answer is the same failure
whichever design shows it. That half is the less strict choice, and it moves no M1 verdict: the
baseline's only stale scenario is S5, which it already fails per presentation. H4's like-with-like
rule and H0's *matches* likewise read both counts.

**Original wording**, as merged before this amendment:

- H1, pass: kuilt has `staleTreatedAsCurrent == 0` on each of S2, S3 and S5, **and** on each of
  them the baseline has `staleTreatedAsCurrent ≥ 1` or `agentRuns` greater than the scenario's
  number of `StartAgent` steps. Stop: kuilt has `staleTreatedAsCurrent ≥ 1` on any of S2, S3 or S5.
- H1b, metric: `falseInvalidations`, defined as the presentations flagged for review where the
  rerun returns the same recommendation, which counted an unknown verdict as a flag.
- H4 excluded scenarios where either design had `staleTreatedAsCurrent > 0` or a rerun.
