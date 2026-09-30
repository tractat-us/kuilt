# Agent workspace: what we measured

Alex, Sam and a remote agent plan dinner through seven small scripted stories, and we count what
happens: whose edits survive, how often the agent has to think again, when someone is asked to
choose, and whether an old answer is shown as if it were still right. This page records those
counts for the simple design first, a server that owns the shared state. The kuilt design joins it
in the next milestone. The stories, the counts and the rules for judging them are fixed in
`HYPOTHESES.md`, written before any number here existed.

These are **counts over scripted actors, not a user study**. Nobody tapped a screen: each number is
what one deterministic run of one scenario produced. They are also **JVM-only**, and make no claim
about any other target.

This page records numbers. It does not score the hypotheses. That happens once the kuilt backend
has numbers too, against `HYPOTHESES.md` as merged. **Every hypothesis is scored against both
baseline variants** below, and a hypothesis passes only if it passes against both.

## Baseline: a server that owns the model

The baseline guards each part of the state with a version number and queues an offline person's
edits until they reconnect. When an agent's answer comes back, the server checks whether anything
that answer depends on has changed, and quietly reruns the agent if so. It runs in two variants:
`optimisticLocal = false`, where nothing a person does offline shows until reconnect, and
`optimisticLocal = true`, where a queued action shows on their own screen at once.

The table is pinned by `BaselineMeasurementTest`: a change to the baseline's numbers fails the
build. This page is kept in step by hand.

| Scenario | optimisticLocal | editsMade | editsPreserved | agentRuns | unnecessaryReruns | staleTreatedAsCurrent | falseInvalidations | humanPrompts | outageActions | outageActionsServed |
|---|---|---|---|---|---|---|---|---|---|---|
| S1 | false | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S1 | true | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| S2 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 1 | 0 |
| S2 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 1 | 1 |
| S3 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| S3 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| S4 | false | 1 | 1 | 2 | 0 | 0 | 0 | 1 | 2 | 0 |
| S4 | true | 1 | 1 | 2 | 0 | 0 | 0 | 1 | 2 | 2 |
| S5 | false | 2 | 2 | 1 | 0 | 3 | 0 | 0 | 0 | 0 |
| S5 | true | 2 | 2 | 1 | 0 | 3 | 0 | 0 | 0 | 0 |
| S6 | false | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| S6 | true | 1 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| S7 | false | 1 | 1 | 2 | 1 | 0 | 0 | 0 | 0 | 0 |
| S7 | true | 1 | 1 | 2 | 1 | 0 | 0 | 0 | 0 | 0 |

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

## kuilt backend

Pending M1.
