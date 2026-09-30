# Agent workspace: what we expect, written before we measure

This page fixes the questions, the scoring and the pass marks for the M0 comparison in #2869
**before any number exists**. The results go in `RESULTS.md`, and they are scored against this
page as merged, unedited. If a criterion turns out wrong, the fix is a new PR here that says so,
never a quiet edit after the numbers arrive.

## The story in one paragraph

Alex and Sam are out looking for dinner. Sam hears their first choice has just closed. Alex, on a
patchy signal, lowers the budget. Meanwhile a remote agent is still researching the old plan, and
its answer lands late: Trattoria Uno, a two-minute walk, at 40 a head. It closed an hour ago, and it
was never in budget. Could the answer have known that? Only if the report reached it. Whichever way
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

The one outage action is Alex's budget edit, so `outageActions == 1`.

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

### S6: control, one user, always connected

S3's steps with one human, who stays connected throughout.

```
StartAgent(r1, host = remote)              // sees nothing → v1
Edit(closeV1, alex, Report.Closed(alex, v1))
ReleaseAgent(r1)                           // returns v1
```

Actors are `alex` and `remote` only. See open question Q3 about what this controls for.

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
- **`falseInvalidations`**: the presentations flagged for review where that same rerun returns the
  *same* recommendation. Each one is human attention spent for nothing.
- **`missedInvalidations`**: presentations the backend's own relevance policy called applicable that
  ground truth calls stale. For the kuilt backend this equals `staleTreatedAsCurrent`; it is
  reported separately so the policy's error has its own row.
- **`humanPrompts`**: the times a person had to choose, whether to review a proposal or settle a
  conflict.
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
queued edit locally before the server sees it. Which variant a hypothesis is scored against is open
question Q2; until Iain rules, each is scored against both and reported per variant.

| id | claim | metric | pass if | stop if |
|---|---|---|---|---|
| H1 | kuilt never treats a stale proposal as current where the baseline does or must rerun to avoid it | `staleTreatedAsCurrent`, `agentRuns` | kuilt has `staleTreatedAsCurrent == 0` on each of S2, S3 and S5, **and** on each of them the baseline has `staleTreatedAsCurrent ≥ 1` or a non-empty `reruns` list | kuilt has `staleTreatedAsCurrent ≥ 1` on any of S2, S3 or S5 |
| H1b | the relevance policy does not ask people too often | `falseInvalidations` | kuilt's total across S1–S6 is ≤ 1 | kuilt's total across S1–S6 is > 1 |
| H2 | kuilt spends fewer agent runs on changes that do not matter | `unnecessaryReruns` | kuilt's value is lower than the baseline's on S1 and on S5 | kuilt's value is ≥ the baseline's on S1 or S5. **Unscorable as the step lists stand: see Q1** |
| H3 | kuilt serves every offline action locally, and the baseline does not | `outageActionsServed / outageActions` | on S2 and S4, kuilt's ratio is 1.0 and the baseline's is below 1.0 | kuilt's ratio is below 1.0 on S2 or S4 |
| H4 | kuilt asks people no more often | `humanPrompts` | kuilt's value is ≤ the baseline's on each of S1–S6 | kuilt's value is > the baseline's on any of S1–S6 |
| H0 | the simpler design suffices | H1–H3 | not applicable: H0 is a stop rule | the baseline's scoped version checks match kuilt on H1, H2 and H3. Then record that the simpler design suffices, and recommend narrowing the epic |

H3 is scored only on scenarios with `outageActions > 0`: S2 (one budget edit) and S4 (two
accepts). S3 has none, because Sam is on the network throughout, so its ratio would be 0/0 and H3 is
not scored there. S1, S5 and S6 have no partition either.

One prediction worth stating before approval, so it cannot look like an excuse afterwards: H4 counts
prompts but not reruns, and the two designs trade one for the other. On S3 the baseline reruns the
agent silently where kuilt asks for review, so H4 may fail on S3 by design rather than by defect.

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

## Open questions for Iain

These came up while fixing the step lists. Each one changes what a criterion can measure, so they
belong in the approval rather than after it.

**Q1. H2 cannot pass as written.** A rerun happens only when a backend throws a result away and asks
again. On S1 no agent runs, so both backends score 0. On S5 the baseline does not rerun: its result
depends on the scopes `budget` and `venue:v2`, and the reopening touches `venue:v1`. The kuilt
backend never reruns at all; it asks for review instead. So both backends score 0 on both scenarios,
and "lower" is unreachable. The case H2 is after is the baseline rerunning for a change that does
not matter. That happens when a note lands on the *recommended* venue while the agent is out: the
note bumps `venue:v1`, the baseline reruns, and it gets `v1` again. One fix is to move S1's note
onto `v1` and wrap the edits in `StartAgent(r1)` … `ReleaseAgent(r1)`, keeping the two edits but
adding the agent. Another is a seventh scenario, leaving S1 as it is. S5 stays at 0 against 0 under
either fix, so it should leave H2's list. Either way H2's scenarios change, which is why this needs
a ruling before measurement.

**Q2. Which baseline variant is the comparator?** With `optimisticLocal = true` the baseline shows
an offline edit locally, so on S2 its ratio is 1.0 and H3 fails against that variant, while H0's
"matches on H3" holds. This page proposes that a hypothesis passes only if it holds against **both**
variants, so the baseline gets credit wherever it solves a problem. That is a stricter test than
the default variant alone, and it is Iain's call.

**Q3. S6 "with no partition" changes nothing.** S3 already has no partition: Sam is on the network
throughout. So S6 differs from S3 only in having one human instead of two. If the control was meant
to remove an outage, S2 is the scenario that has one, and "S2 without the partition" would show
what the outage alone costs each design.

**Q4. Offline accepts and what an actor knows.** These definitions reach into the baseline, and its
current design notes leave them open. `outageActions` counts partitioned `Accept` steps as well as
edits. If accepts are left out, S4 has no outage actions and H3 rests on S2 alone. And an actor
knows the inputs it created itself, even when they were never served. The baseline's design derives
what an actor has seen from the server's set or its last sync, which would leave Alex's own queued
budget edit out of what Alex knew, and so undercount staleness on S2.
