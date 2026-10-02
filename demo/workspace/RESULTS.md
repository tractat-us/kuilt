# Agent workspace: what we measured

Alex, Sam and a remote agent plan dinner through seven small scripted stories, and we count what
happens: whose edits survive, how often the agent has to think again, when someone is asked to
choose, and whether an old answer is shown as if it were still right. This page records those
counts for two designs: a server that owns the shared state, and kuilt, where every phone keeps its
own copy and nobody is in charge. The stories, the counts and the rules for judging them are fixed
in `HYPOTHESES.md`, written before any number here existed.

These are **counts over scripted actors, not a user study**. Nobody tapped a screen: each number is
what one deterministic run of one scenario produced. They are also **JVM-only**, and make no claim
about any other target.

The baseline's numbers come first, then kuilt's, then the scoring. It is scored twice: first
against `HYPOTHESES.md` as merged and unedited, then against the criteria as amended on
2026-10-01, after M1. The amended scoring is the current verdict, and under it **H1 stops on S2**.
**Every hypothesis is scored against both baseline variants**, and a hypothesis passes only if it
passes against both.

## Baseline: a server that owns the model

The baseline guards each part of the state with a version number and queues an offline person's
edits until they reconnect. When an agent's answer comes back, the server checks whether anything
that answer depends on has changed, and quietly reruns the agent if so. It runs in two variants:
`optimisticLocal = false`, where nothing a person does offline shows until reconnect, and
`optimisticLocal = true`, where a queued action shows on their own screen at once.

The table is pinned by `BaselineMeasurementTest`: a change to the baseline's numbers fails the
build. This page is kept in step by hand.

| Scenario | optimisticLocal | editsMade | editsPreserved | agentRuns | unnecessaryReruns | staleTreatedAsCurrent | staleAtEnd | falseInvalidations | humanPrompts | outageActions | outageActionsServed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | false | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S1 | true | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S2 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| S2 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 1 | 1 |
| S3 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S3 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S4 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 1 | 2 | 0 |
| S4 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 1 | 2 | 2 |
| S5 | false | 2 | 2 | 1 | 0 | 3 | 3 | 0 | 0 | 0 | 0 |
| S5 | true | 2 | 2 | 1 | 0 | 3 | 3 | 0 | 0 | 0 | 0 |
| S6 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S6 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S7 | false | 1 | 1 | 2 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| S7 | true | 1 | 1 | 2 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |

One column is zero everywhere, by design rather than luck. `falseInvalidations` counts answers
flagged for review, and the baseline never flags anything: it either reruns or shows the answer as
current. One pair always matches: `editsPreserved` equals `editsMade` in every row, because the
server keeps every entry and every actor is back online by the end.

## Why each number is what it is

Where a line covers both variants, the two rows are identical.

**S1: independent edits.**
- `editsMade` 2, `editsPreserved` 2: Alex's note and Sam's report land in different parts of the
  state, and both end up in everyone's view.

**S2: budget changed during inference, by an offline Alex.**
- `editsMade` 1, `editsPreserved` 1: Alex's budget of 30 reaches the server when Alex reconnects.
- `agentRuns` 2: the first run, and one rerun. When Alex reconnects the server checks the pending
  answer again, sees the budget has moved, and reruns the agent, which now picks Due Fratelli.
- `outageActions` 1: the budget edit, made while Alex was offline.
- `outageActionsServed` 0 (false) or 1 (true): without optimistic display the edit shows nowhere
  until reconnect; with it, Alex sees the new budget straight away.
- `staleAtEnd` 0: Sam and the remote machine were shown Trattoria Uno before the budget arrived,
  but the rerun's Due Fratelli is pushed to everyone connected, so it is every actor's standing
  answer when the run ends, and it fits.

**S3: a closure reported during inference.**
- `editsMade` 1, `editsPreserved` 1: Sam's closure report.
- `agentRuns` 2: the closure reaches the server before the answer does, so the check reruns the
  agent, which picks Due Fratelli instead of the closed Trattoria Uno. Nobody sees the stale pick.

**S4: two conflicting human choices.**
- `editsMade` 1, `editsPreserved` 1: Alex's budget of 30.
- `agentRuns` 2: two requests, `r1` and `r2`, and neither needs a rerun.
- `humanPrompts` 1: Alex's offline accept replays first and applies. Sam's replays second, finds
  the plan has moved since Sam last saw it, and Sam is asked to choose.
- `outageActions` 2: the two accepts, each made offline.
- `outageActionsServed` 0 (false) or 2 (true): with optimistic display each person's screen would
  show "you chose this" at once. No entry records an accept, so nothing visible in a final view
  earns this credit. It is a counting choice made in the baseline's favour; `HYPOTHESES.md` does
  not settle it. It cannot change H3's verdict: under `optimisticLocal = true` S2's ratio is already
  1.0, from an edit that really shows, and that settles the S2 comparison whichever way S4 falls.
  It decides only whether that variant can *match* on H3, one of the three matches H0 needs
  alongside H1 and H2.

**S5: a report corrected later.**
- `editsMade` 2, `editsPreserved` 2: the closure and the reopening.
- `agentRuns` 1: the agent picks Due Fratelli, and no rerun follows. The reopening is about
  Trattoria Uno, and the check only watches the budget and the venue it recommended.
- `staleTreatedAsCurrent` 3: Due Fratelli is shown as current to Alex, Sam and the remote machine.
  Each of them already knows Trattoria Uno has reopened, and it is nearer.
- `staleAtEnd` 3: nothing replaces that answer, so it is still stale for all three when the run
  ends.

**S6: S2 without the outage.**
- `editsMade` 1, `editsPreserved` 1: Alex's budget of 30.
- `agentRuns` 2: the budget reaches the server before the answer, so the check reruns the agent
  there and then, and it picks Due Fratelli. Compared with S2 the outage changes when the rerun
  happens and adds the one outage action. Under `optimisticLocal = true` it also changes
  `outageActionsServed` (1 in S2, 0 here). Nothing else in this table differs.

**S7: a harmless note while the agent is out.**
- `editsMade` 1, `editsPreserved` 1: Sam's note about Trattoria Uno.
- `agentRuns` 2: the note touches Trattoria Uno's part of the state, which the answer depends on,
  so the check reruns the agent.
- `unnecessaryReruns` 1: the rerun picks Trattoria Uno again. A version number cannot tell a
  note from a closure.

## kuilt: every phone keeps its own copy

In the kuilt design there is no server. Alex, Sam and the remote machine each keep a full copy of
one shared log, and kuilt stitches the copies together whenever they can reach each other. An
offline edit lands in its author's copy at once. When an agent's answer comes back it carries the
list of inputs it was given, so each phone can compare that list with what it holds and decide:
this still fits, or someone should look again. Nothing reruns quietly. A doubtful answer is flagged
for a person instead.

The table is pinned by `KuiltMeasurementTest`: a change to kuilt's numbers fails the build. This
page is kept in step by hand. Like the baseline's, these are counts over scripted actors, JVM only,
not a user study. There is one row per scenario, since kuilt has no `optimisticLocal`
variant: an offline action always shows on its author's phone. The last column,
`unknownPresentations`, counts answers a phone could not judge because it had not yet received
everything the agent saw. Since the 2026-10-01 amendment it is reported beside H1b and is not
scored. It is zero in every row, and that zero is not evidence: this backend cannot produce an
unknown at all.

| Scenario | editsMade | editsPreserved | agentRuns | unnecessaryReruns | staleTreatedAsCurrent | staleAtEnd | falseInvalidations | humanPrompts | outageActions | outageActionsServed | unknownPresentations |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S2 | 1 | 1 | 1 | 0 | 0 | 2 | 0 | 1 | 1 | 1 | 0 |
| S3 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 |
| S4 | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 1 | 2 | 2 | 0 |
| S5 | 2 | 2 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 |
| S6 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 |
| S7 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Two columns are zero by construction. `unnecessaryReruns` is zero because kuilt never reruns an
agent, so `agentRuns` is always the number of `StartAgent` steps. `unknownPresentations` is zero
because no phone here can ever be shown an answer built from inputs it has not received (see *What
these numbers rest on*, below). That zero is vacuous: it says nothing about whether the unknown
verdict works. As in the baseline, `editsPreserved` equals `editsMade` in every row.

### Why each kuilt number is what it is

**S1: independent edits.**
- `editsMade` 2, `editsPreserved` 2: the note and the report merge into every copy.

**S2: budget changed during inference, by an offline Alex.**
- `agentRuns` 1: the agent starts with the remote machine's empty copy and returns Trattoria Uno.
- `outageActions` 1, `outageActionsServed` 1: Alex's budget of 30 lands in his own copy the moment
  he makes it.
- `humanPrompts` 1: when Alex reconnects, his copy receives the answer. He holds a budget the agent
  never saw, and 30 is below Trattoria Uno's 40, so the answer is flagged and Alex is asked. The
  truth for him is Due Fratelli, so the flag is earned, not a false invalidation. Sam and the remote
  machine saw the answer earlier, before the budget reached them, and it fitted what they knew,
  and it is never re-checked.
- `staleAtEnd` 2: so when the run ends, Sam and the remote machine still have Trattoria Uno shown as
  fitting, while each holds a budget of 30. Alex's standing answer is the flagged one, which does
  not count.

**S3: a closure reported during inference.**
- `agentRuns` 1, `humanPrompts` 2: every copy holds Sam's closure of Trattoria Uno when the answer
  arrives, so all three flag it. Alex and Sam are the two people asked; the remote machine is shown
  the flag but hosts the agent and is not a person. The truth is Due Fratelli for all three.

**S4: two conflicting human choices.**
- `agentRuns` 2: `r1` and `r2`, one run each. Each answer fits what its first viewers know, so
  nobody is asked about either.
- `outageActions` 2, `outageActionsServed` 2: each offline accept is a real entry in its chooser's
  own copy.
- `humanPrompts` 1: when the phones meet, both copies hold Alex's accept of `r2` and Sam's accept of
  `r1`. That pair conflicts, and it is counted once.
- `staleAtEnd` 0: everyone's standing answer at the end is `r2` (Due Fratelli), which fits. Sam's
  accept of `r1` is a choice, not an answer shown to him, so it does not change which answer stands.

**S5: a report corrected later.**
- `editsMade` 2, `editsPreserved` 2: the closure and the reopening.
- `agentRuns` 1, `humanPrompts` 2: the agent saw the closure and picked Due Fratelli. Every copy
  also holds the reopening, which the agent never saw, and a reopening anywhere is always worth a
  look. All three flag it, and the two people are asked. The truth is Trattoria Uno, so each flag is
  earned.

**S6: S2 without the outage.**
- `agentRuns` 1, `humanPrompts` 2: Alex's budget reaches every copy before the answer does. All
  three flag the answer, and the two people are asked. The truth is Due Fratelli.

**S7: a harmless note while the agent is out.**
- `agentRuns` 1, `humanPrompts` 0: a note never changes the pick, so every copy shows Trattoria Uno
  as fitting, and it does.

## Side by side

`k` is kuilt, `f` the baseline with `optimisticLocal = false`, `t` the baseline with
`optimisticLocal = true`. `editsMade` and `editsPreserved` are left out: they are identical across
all three in every scenario. Counts over scripted actors, JVM only.

| Scenario | agentRuns k / f / t | unnecessaryReruns k / f / t | staleTreatedAsCurrent k / f / t | staleAtEnd k / f / t | falseInvalidations k / f / t | humanPrompts k / f / t | served / outage k · f · t |
|---|---|---|---|---|---|---|---|
| S1 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0/0 · 0/0 · 0/0 |
| S2 | 1 / 2 / 2 | 0 / 0 / 0 | 0 / 0 / 0 | 2 / 0 / 0 | 0 / 0 / 0 | 1 / 0 / 0 | 1/1 · 0/1 · 1/1 |
| S3 | 1 / 2 / 2 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 2 / 0 / 0 | 0/0 · 0/0 · 0/0 |
| S4 | 2 / 2 / 2 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 1 / 1 / 1 | 2/2 · 0/2 · 2/2 |
| S5 | 1 / 1 / 1 | 0 / 0 / 0 | 0 / 3 / 3 | 0 / 3 / 3 | 0 / 0 / 0 | 2 / 0 / 0 | 0/0 · 0/0 · 0/0 |
| S6 | 1 / 2 / 2 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 2 / 0 / 0 | 0/0 · 0/0 · 0/0 |
| S7 | 1 / 2 / 2 | 0 / 1 / 1 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0/0 · 0/0 · 0/0 |

The shape in one sentence: where the baseline quietly reruns the agent (S2, S3, S6, S7) or shows a
stale answer (S5), kuilt runs the agent once and asks a person when the answer is in doubt on arrival;
news that arrives after an answer was shown is never re-checked (S2). That is
a trade, not a free win. On S2, S3, S5 and S6 kuilt asks people once or twice where the baseline
asks nobody. The hypotheses weigh that on purpose outside the verdicts (H4 compares prompts only
where neither design reruns or goes stale), so it shows up here instead.

## Scoring against `HYPOTHESES.md` as first merged

**Superseded.** This is the M1 scoring as it first merged, kept unedited apart from pointers to the
re-score. The current verdicts are in *Re-scored against the amended criteria*, below.

Scored as merged, unedited. Each hypothesis gets a verdict against each baseline variant, then a
combined verdict: it **passes** only if it passes against both, **stops** if it stops against
either, and is otherwise **no difference**.

| id | vs `optimisticLocal = false` | vs `optimisticLocal = true` | combined |
|---|---|---|---|
| H1 | pass (per-presentation scoring; stops under end-of-run scoring, see *How firm*) | pass (per-presentation scoring; stops under end-of-run scoring, see *How firm*) | **pass (per-presentation scoring; stops under end-of-run scoring, see *How firm*)** |
| H1b | pass (no baseline comparison) | pass (no baseline comparison) | **pass** |
| H2 | pass | pass | **pass** |
| H3 | pass | no difference (S2 and S4) | **no difference** |
| H4 | pass (no difference on S1 and S4) | pass (no difference on S1 and S4) | **pass** |
| H0 | does not match | does not match (matches on H3 only) | **not triggered** |

- **H1, pass (per-presentation scoring; stops under end-of-run scoring, see *How firm*).** kuilt has `staleTreatedAsCurrent` 0 on each of S2, S3 and S5. On each of them both
  variants either reran (`agentRuns` 2 against one `StartAgent` on S2 and S3) or went stale (3 on
  S5), so no scenario is *no difference* for either variant.
- **H1b, pass.** kuilt's `falseInvalidations` total across S1 to S7 is 0, within the budget of 1:
  every answer kuilt flagged would really have come out differently.
- **H2, pass.** On S7 kuilt's `unnecessaryReruns` is 0 against 1 for both variants. kuilt's 0 is
  structural: it never reruns an agent at all. The evidence that the note was rightly ignored is
  elsewhere in S7's row: 0 prompts and 0 false invalidations.
- **H3, no difference.** kuilt's ratio is 1.0 on S2 (1/1) and S4 (2/2). That served count holds
  by construction of local-first apply (an entry is in its author's copy the moment it is made),
  and the backend does not check it separately. Against `false` the
  baseline's is 0 on both, a pass. Against `true` it is 1.0 on both, *no difference* on S2 and S4,
  so the combined verdict is *no difference*, naming S2 and S4 under `optimisticLocal = true`.
- **H4, pass.** Neither design reruns or goes stale on S1 or S4, so both stay in. `humanPrompts` is
  0 against 0 on S1 and 1 against 1 on S4, for both variants: *no difference* on each, which H4
  counts as a pass, since its claim is "no more often".
- **H0, not triggered.** A variant matches kuilt only if it matches on all of H1, H2 and H3.
  `optimisticLocal = false` matches on none of them. `optimisticLocal = true` matches on H3 (S2 and
  S4 are both *no difference*) but not on H1 (it reruns on S2 and S3 and goes stale on S5) or H2 (1
  unnecessary rerun on S7 against 0). So the stop rule does not fire, and these runs give no grounds
  to narrow the epic.

### How firm each verdict is

- **The optimistic accept credit changes no verdict.** Under `optimisticLocal = true` the baseline
  counts an offline accept as served though it stores no accept entry. That credit was reviewed and
  kept, and kuilt is held to the same standard (next section). Withdraw it and that variant's S4
  ratio drops to 0: H3 against `true` becomes a pass on S4 but stays *no difference* on S2, so H3
  stays *no difference*. H0 still does not fire, because H1 and H2 do not match.
- **H4 on S4 rests on how a conflict is counted.** kuilt counts one prompt per conflicting pair of
  accepts, once across the run. That coincides with the baseline's count on S4, but the rules
  differ: the baseline prompts once per replayed accept whose plan version moved, so the two agree
  at two accepts and can differ at three. kuilt's prompt also goes to no specific person. Both
  Alex's and Sam's copies hold the conflict, and which of them settles it is application policy
  that this backend does not decide. A design that asks both people would score 2 on S4 and **stop** H4. The
  count is not vacuous: `KuiltBackendTest.s4AcceptConflictIsSurfacedOnce` goes red if conflict
  detection is disabled (0 prompts) or its de-duplication is dropped (4 prompts).
- **H1 on S2 holds only because answers are scored when first shown.** In kuilt, Sam and the
  remote machine are shown `r1` (Trattoria Uno) as fitting before Alex's budget reaches them, and it
  stays standing after the budget arrives; the baseline replaces it with the rerun its deferred
  re-check produced. Scored at first delivery, kuilt's S2 staleness is 0. Scored on each phone's
  standing answer at the end of the run (the latest proposal it has been shown), it is 2, which
  meets H1's stop condition as amended on 2026-10-01 (see the re-score). S3 and S5 are unaffected, since every copy holds
  the missing input before the answer arrives there. H0 is unaffected too: it does not fire because
  the baseline goes stale on S5 (and reruns needlessly on S7), not because of S2.
- **H2 cannot fail for kuilt as registered.** kuilt never reruns, so its `unnecessaryReruns` is 0 on
  every scenario by construction. H2's pass says the baseline pays for reruns kuilt does not make;
  whether kuilt judged the note correctly rests on S7's 0 prompts and 0 false invalidations, which
  H1b and H4 look at, not H2.
- **H1 on S2 and S3 is a flag, not a fix.** kuilt shows no stale answer as current, but it gets
  there by asking. The baseline gets there by rerunning, with nobody asked and a fresh answer on
  screen. H1 as registered counts both as avoiding staleness. The prompt cost is the side-by-side
  `humanPrompts` column. S5 is different: there the baseline really does show a stale answer, three
  times.
- **H1b's budget and the unknown verdict.** H1b's 0 rests on needs-review flags alone, because this
  backend cannot produce an unknown. A flagged answer whose rerun would come out the same counts
  against H1b even when it was flagged only because a phone had not yet received everything. So
  H1b has not been tested against the case where kuilt says "unknown": a network that lets one
  sender's news through before another's could add to the total. The 2026-10-01 amendment now
  scores unknowns separately; see the re-score.

## Re-scored against the amended criteria

**Post-M1 amendment, made 2026-10-01, before any fix for tractat-us/kuilt#2880.** After M1, Iain
accepted two changes to `HYPOTHESES.md` (see its *Amendment* section). H1b now scores needs-review
flags only. And each actor's standing answer, the latest proposal it has been shown, is now also
scored when the run ends, as `staleAtEnd`. Either scoring can stop a hypothesis on its own. The
numbers are the same runs as above; only the criteria changed.

| id | vs `optimisticLocal = false` | vs `optimisticLocal = true` | combined |
|---|---|---|---|
| H1 | **stop** (S2) | **stop** (S2) | **stop** |
| H1b | pass (no baseline comparison) | pass (no baseline comparison) | **pass** |
| H2 | pass | pass | **pass** |
| H3 | pass | no difference (S2 and S4) | **no difference** |
| H4 | pass (no difference on S1 and S4) | pass (no difference on S1 and S4) | **pass** |
| H0 | does not match | does not match (matches on H3 only) | **not triggered** |

- **H1, stop, on S2.** kuilt's `staleAtEnd` on S2 is 2: Sam and the remote machine end the run with
  Trattoria Uno shown as fitting while each holds Alex's budget of 30. Both baseline variants score
  0, because the rerun's Due Fratelli replaces the old answer on every screen. One stale count on
  S2, S3 or S5 stops H1. S3 and S5 are clean for kuilt under both scorings (0 and 0). The cause is
  the one *What these numbers rest on* names: kuilt judges an answer once, when it first arrives.
  Re-checking a shown answer is tractat-us/kuilt#2880.
- **H1b, pass.** kuilt's needs-review `falseInvalidations` total across S1 to S7 is 0. The
  `unknownPresentations` row beside it is also 0, and that 0 is vacuous: this backend cannot
  produce an unknown at all.
- **H2, H3, unchanged.** Neither reads the counts the amendment touched.
- **H4, pass, unchanged.** `staleAtEnd` is 0 on S1 and S4 in all three designs, so both stay like
  with like. On S4 everyone's standing answer is `r2`, which fits.
- **H0, not triggered.** A variant matches kuilt on H1 only if all of S2, S3 and S5 are *no
  difference*. S2 is not: kuilt is worse there, which is a stop, not a tie. H0 would not fire
  anyway, since neither variant matches on H2 (1 unnecessary rerun on S7 against 0). H0 asks
  whether the simpler design does as well, and on S2 it does better; H1's stop is where that shows.

The headline: **H1 stops on S2**. This is the order the epic set: fix tractat-us/kuilt#2880, then
re-score M1 against these criteria, unedited, before M2 begins.

`staleAtEnd` is computed in the harness (`Oracle.standingAnswers`) for both backends and both
baseline variants, and pinned by `KuiltMeasurementTest`, `BaselineMeasurementTest` and
`EndOfRunScoringTest`. Its numbers match the scratch probe the proposal quoted: S2 2 / 0 / 0, S4
0 / 0 / 0, and S3, S5 and S7 unchanged. The verdict it scores is the one each answer was **last
shown with**, never a fresh re-assessment at the end. So a re-check that re-shows `r1` to Sam
flagged would bring S2 to 0, and a design that never re-checks cannot.

## What these numbers rest on

- **An answer is judged once, when a phone first receives it.** A later arrival is not re-checked.
  On S2 this matters: after Alex reconnects, Sam's and the remote machine's copies hold his budget,
  but the answer they were shown earlier as fitting is not re-examined.
  The baseline never re-checks an answer it has already shown either. It re-checks only when it
  delivers a *deferred* presentation, to a phone that was offline at release; on S2 that is Alex's,
  and the rerun it triggers is pushed to everyone connected. That is why S2 looks one-sided: the
  baseline has a deferred delivery to hang the re-check on, and kuilt's re-check-free rule does not.
  `staleTreatedAsCurrent` and `humanPrompts` score each presentation at the moment it is made, so
  neither sees this. `staleAtEnd`, added by the 2026-10-01 amendment, does: it is 2 on S2.
- **Catching up after a reconnect waits for the background sync.** Healing a link starts no sync of
  its own. A returning phone catches up on the replicas' next anti-entropy round, so how soon it
  sees news is bounded by that interval, not by the moment it reconnects. The tests drive ten rounds
  per step.
- **An "unknown" verdict cannot happen at the backend level here.** Links are cut for a whole actor
  at once, the replicator applies each sender's news in that sender's order, and every step ends
  with a check that all connected copies hold the same entries; the run throws if they do not
  (`KuiltBackendTest.tooFewRoundsThrowsInsteadOfScoringLow` and
  `laggingConnectedReplicaThrowsBeforeAnyPresentation`). So a phone holding an answer has always
  received everything the agent was given, and `unknownPresentations` is 0 by construction. The
  verdict is tested elsewhere: `AssessmentTest.unreceivedBasisIsUnknownNotApplicable` at the judge,
  and `AdversarialTraceTest.heldFramesLeaveTheProposalUnknown` end to end on a test mesh, where
  Sam's frames to Alex are held back so the answer reaches Alex before Sam's closure does.
- **Prompts count people only.** The remote machine hosts the agent. It is shown a flag like
  everyone else, but a flag shown to it is never counted as a person being asked. This is why S3, S5
  and S6 score 2 prompts, not 3.
- **kuilt's offline accept counts as served under the same standard as the baseline's.** Each one is
  a real `Accept` entry, present in its chooser's own copy the moment it is made. The baseline under
  `optimisticLocal = true` gets the same credit for what an optimistic screen would show. Accepts
  stay out of the final views in both designs, since `editsPreserved` compares edits only.
- **One known fault in the test network, since fixed.** When these results were measured, the
  kuilt-test reorder fault (`ReorderWindow` in `FaultySeam`) delivered held frames to the wrong peer
  and never counted them as delayed (tractat-us/kuilt#2879). It touched one trace,
  `AdversarialTraceTest.reorderedAndDuplicatedProposalKeepsBasis`: its reordered frames were also
  misdelivered, which was extra adversity rather than less, and the copies still converged. The
  table above ran on a healthy network and was not affected. tractat-us/kuilt#2881 fixed the fault:
  held frames reach their own peers and count as delayed, and the trace still passes.

## Proposed changes to `HYPOTHESES.md`: applied

M1 proposed two changes to `HYPOTHESES.md`: score H1b on needs-review flags only, and score each
actor's standing answer at the end of the run as well. Iain accepted both on 2026-10-01, defining
the standing answer as the latest proposal an actor has been shown, not every answer it was ever
shown. Both now live in `HYPOTHESES.md` § *Amendment (2026-10-01)*, and *Re-scored against the
amended criteria*, above, applies them.
