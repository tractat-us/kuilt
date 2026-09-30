# Agent workspace: what kuilt already had, and what it lacks

An agent's answer arrives late, and a phone has to decide whether it still fits. To make that call
honestly the phone needs three things: a name for every input the agent was given, a way to tell
whether it has received each of those inputs itself, and a rule for which missing inputs matter.
This page sorts the pieces the prototype used into three piles. Some kuilt already ships. Some the
prototype had to hand-roll, and a kuilt module should probably own them. The rest is the app's
business and should stay that way.

Everything here comes from the `:demo-workspace` prototype: scripted actors, headless, JVM only.
It makes no claim about any other target.

## Three piles

| Existing primitive used | Missing canonical API | Application policy |
|---|---|---|
| **Identity.** `Rga<WorkspaceEntry>`: every input is one element, read through `Rga.entries`. Its identity is `RgaId.dot`, so a corrected report is a new identity, not an edit of the old one. | None. The dot is the right identity, and nothing here wanted another. | What counts as an input at all. Proposals and accepts are elements too, but they are outputs and choices, so the capture leaves them out. |
| **Delivery.** `Quilted.causalDots()` and `Quilted.causalFloor()`, read as a union, and `VersionVector.contains`. | **`Quilted.delivered(dot)`: confirmed** (evidence below). A per-dot "has this state delivered that dot" helper. A second candidate, "these two states delivered the same dots", has one call site and is not confirmed. | None. Delivery is a fact about the log, not a judgement. |
| **Replication.** `Quilter`, with `Quilter.mutate` for an atomic append under the replicator's lock, anti-entropy for catch-up, and `QuilterConfig(expectVirtualTime = true)` in tests. | None required. The prototype's convergence check ("every connected copy delivered the same dots") is test support and could live beside `drainAntiEntropy` in `:kuilt-test`; not confirmed. | When a returning phone catches up. Healing a link starts no sync, so catch-up waits for the next anti-entropy round. |
| **Compaction.** `RgaGcCoordinator` for tombstone collection; `Rga.dropWindow` exercised only in tests. | None, but see the compaction finding below: the floor half of the delivery rule is reached only through `dropWindow`. | Whether and how far to window history. The prototype never windows. |
| **Faults.** `FaultyLoom`, `FaultySeam` and `FaultProfile` over `InMemoryLoom` (test side), with `drainAntiEntropy`. | A fix to `FaultySeam`'s outbound `ReorderWindow`, which misroutes held frames and never counts them as delayed (tractat-us/kuilt#2879). | None. |
| **Capture.** The prototype's own `InputCapture.capture` and `Proposal.Pending`: one read of an immutable `Rga` value fixes the basis. | **Rejected for now.** The generic core is one line (`entries().map { it.first.dot }`); the rest is shaped by this app's entry types. One consumer is too few to name the API. | Which inputs an agent is shown (`selectExcluding`), and which step a tool result joins. |
| | | **Relevance.** Which missing inputs matter to an answer: clauses (a) to (d) of `HYPOTHESES.md`. A note never matters. |
| | | **Authority.** Who settles a conflict between two accepts. The prototype counts one prompt per conflicting pair and does not decide who is asked. An agent's host is never asked. |
| | | **Presentation.** When an answer is shown: once per phone, at first delivery, never re-checked. An unknown verdict is shown flagged for review, like a needs-review one. Accepts and proposals stay out of the final views. |

## `Quilted.delivered(dot)`: confirmed

`Quilted`'s own documentation says the delivered surface is `causalDots()` together with every dot
at or below `causalFloor()`, that "the union is the contract", and that "every consumer only ever
asks 'was this dot delivered'". Nothing in kuilt answers that question directly, so the prototype
spells it out by hand in four places:

- `Assessment.assess`, once per basis dot;
- `KuiltBackend`'s `delivers`, which builds each presentation's `known`;
- `KuiltBackend`'s `deliveredWithin`, twice: once as a per-dot test, once to expand the floor back
  into dots so two copies can be compared.

Every tempting shortcut is wrong, and mutation runs show it. The first was re-run for this page;
the other two are Task 8's recorded evidence, not re-run here:

- **`causalDots()` alone** misses a dot folded into the floor. Dropping the floor arm reds
  `AssessmentTest.flooredBasisEntryIsStillDelivered` (Task 8) and
  `KuiltBackendTest.backendDeliveryHelpersReadTheFloor` (re-run here, each arm alone).
- **The floor alone** misses every dot above it. Delivery by floor only reds
  `compactedReportLeavesBasisReadable` and the reorder trace (Task 8's evidence).
- **Live entries** miss anything removed and compacted. That reds `compactedReportLeavesBasisReadable`
  (Task 8's evidence).
- **A contiguous frontier** (`Quilter.deliveredLocal`, `VersionVector.contiguous`) is the highest
  gap-free sequence per author, so by definition it calls a dot delivered above a gap undelivered.
  That case is inferred from the definition, not measured here.

Four hand-written copies of a two-armed rule, three wrong ways to shorten it, and a contract that
already names the question: a consumer should not have to rediscover it. The helper belongs on
`Quilted`, beside the two halves it unions, as a non-abstract member so every CRDT gets it.

## The compaction finding

`RgaGcCoordinator` never raises `causalFloor()`. Its tombstone collection records an
`RgaOp.Compact`, and `Rga.causalDots()` keeps re-emitting every dot a `Compact` recorded, so a
compacted input stays delivered through `causalDots()`. `AdversarialTraceTest.compactedReportLeavesBasisReadable`
pins it: after the purge, the closure's dot is in `causalDots()` and not under the floor.

Only `Rga.dropWindow` raises the floor, and nothing in the prototype's replication calls it. So the
floor half of the delivery rule is exercised only by tests that call `dropWindow` directly:
`AssessmentTest.flooredBasisEntryIsStillDelivered` for the judge, and
`KuiltBackendTest.backendDeliveryHelpersReadTheFloor` for the backend's own helpers. Each floor arm
reds alone when removed. No end-to-end run reaches a floored dot.

## The trust boundary the traces establish

The promise: **a late agent result keeps its original input history. Merging it cannot make it
look as if it received newer reports.** Here is what holds it up, and what does not.

**Proven by construction** (the types refuse the bad state):

- A `Proposal` has a private constructor, and a `data class` `copy` is ruled out on purpose. On the
  path from `InputCapture.capture` to `Pending.complete`, the basis is the dots read once from an
  immutable `Rga` value, plus one step per recorded tool result.
- An `InputRecord` refuses a malformed history: steps indexed out of order, or one dot in two steps.
- An input is an `Rga` element, so it always has a dot. A scalar never gets one (below).

**Proven by test** (a named test reds when the property breaks):

- **Capture timing is the backend's obligation**, not the type's. The type cannot stop a backend
  capturing late, at release, or through a tool result that carries later host updates.
  `KuiltBackendTest.capturedBasisIsTheHostStateAtStart` and `toolResultJoinsTheBasisAsItsOwnStep`
  pin it; capturing at release reds both.
- The basis survives Quilter's wire and a merge into another copy, step by step:
  `leakyStampFailsOverTheWire`, eight cells.
- A reordered and duplicated delivery leaves one proposal with its basis unchanged:
  `reorderedAndDuplicatedProposalKeepsBasis`. Its reorder rig also misdelivers frames
  (tractat-us/kuilt#2879), which adds adversity rather than removing it.
- A removed and compacted input still reads as delivered: `compactedReportLeavesBasisReadable`.
- A phone that lacks part of the basis says unknown, never applicable:
  `AssessmentTest.unreceivedBasisIsUnknownNotApplicable` at the judge, and
  `AdversarialTraceTest.heldFramesLeaveTheProposalUnknown` end to end. The backend itself never
  reaches this state, so its `unknownPresentations` of 0 is not evidence.
- A receipt is not a fact: the same fact under a second dot is a new missing input
  (`sameFactTwoIdentities`), and so is a correction (`correctedReportIsANewIdentity`).
- A reply for a request that never started, or already finished, is refused:
  `unknownRequestReplyIsRejected`.
- An input the agent's selection left out counts as missing even though the host held it:
  `AssessmentTest.excludedHostInputCountsAsMissing`.

**Not established:**

- Anything about a dishonest host. The boundary is the instrumented runtime.
- That a run did not *know* a fact, only that it did not *receive* it through tracked inputs.
- That receiving an input means the agent understood it.
- Anything on a target other than the JVM.

## Unsupported shapes, and how each one fails

- **A scalar input has no identity.** `LWWRegister`, `ORMap`, `MVRegister` and `JsonNode.Leaf`
  contribute no dots (`Quilted.causalDots()` defaults to empty), so nothing stored in one can be
  named in a basis or counted as missing. The failure is silent: a budget kept in a register would
  be invisible to review. By construction, the prototype stores a preference as an appended
  `PreferenceSet` entry instead.
- **`toProposal` accepts any well-shaped basis.** It checks only that no dot repeats. A host that
  stamps a basis naming inputs the agent never saw is read faithfully:
  `leakyStampFailsOverTheWire` shows the leaky stamp arriving intact in all eight cells. The failure
  is a false "applicable". An adapter that brings proposals from outside needs its own reason to
  trust the basis.
- **`withToolResult` checks presence only.** It requires the new ids to be in the log and not
  already in the basis, nothing more. A caller could pass later host updates off as a tool result,
  widening the basis silently. Only the backend test above catches it.
- **An answer is judged once, at first delivery.** Relevant news that reaches a phone after it was
  shown an answer is never re-checked, and no metric sees it. On S2, Sam and the remote machine keep
  showing the first answer as fitting after Alex's budget reaches them.
- **Catch-up waits for anti-entropy.** Healing a link starts no sync, so a returning phone's view is
  stale for up to one anti-entropy interval.
- **Identical entries are one entry to the oracle and to `selectExcluding`.** Both match by value.
  No scenario creates two identical entries, and a selection that excluded one would exclude both.
- **An agent never builds on another agent's answer.** Proposals are left out of every capture, so
  a second agent cannot take the first one's proposal as an input.
- **Removal is not a scenario step.** `KuiltBackend` has no `Remove`, so removal and compaction are
  exercised only on the test mesh, which shares the backend's replica wiring.
