# Heddle's flight recorder: record every decision, replay it later

> **Status: design, for the release after 0.7.6.** Part of #3480. Merging this page decides the
> design. Nothing in it is built yet, so every type and call named in *The design* is proposed
> unless it links to source. The heddle model it relies on is
> [`heddle-design.md`](heddle-design.md); this page does not restate it.

## The one idea

A build farm shares its machines between teams. One morning the small team's job sits in the
queue for an hour while the big team's jobs keep starting. Was that fair, or a bug?

Today nobody can say afterwards. The scheduler decided, and the decision is gone.

So give the scheduler a flight recorder. Each time the program asks it something that changes
its books, the recorder writes down the question and the answer. Later you download the
recording, hand the same questions to a copy of the scheduler on your laptop, and watch where
the copy answers differently. If it never does, the scheduler did what its rules say, and you
can ask the copy *why* at the exact moment that puzzled you. If it does, the recording points
at the first question where the two parted.

![The recording travels in the log buffer the device already keeps, and the replay runs on any
machine that can read it.](images/heddle-trace-replay/pipeline.svg)

## Most of this is already on the shelf

Three pieces are missing, and they are small because of what they stand on. Everything in the
right-hand column is built, except the last row, which is planned.

| A flight recorder needs… | …and kuilt already has |
|---|---|
| a copy that gives the same answer to the same question | `LocalHeddle` reads no clock and no random source, so the same calls make the same picks on every target |
| a place to start the copy from, part-way through a run | `LocalHeddle.persist()` and `LocalHeddleSnapshot`, frozen at format 1 |
| a way to ask "why?" at any moment | `explain`, `previewPicks` and `entitlement`, which a snapshot answers too, through `LocalHeddleSnapshot.view(policy)` |
| somewhere on the device that survives being offline | `:kuilt-otel`'s `WarpLogRecordExporter`, a durable log buffer |
| a way to pull that off a running device | `:kuilt-otel-tap`'s `LogTapClient`, which joins the device as a peer, and `:kuilt-otel-otlp` for a collector |
| a longer memory than the buffer | `BoltDecorator`, fed through the exporter's `AppliedOpSink` |
| a home for heddle's telemetry | `:kuilt-heddle-otel`, already planned for heddle's metrics and spans (#3184) |

## Three pieces, and one verdict

What is left fits in three small pieces:

1. **A recorder in the engine.** A `DecisionSink` that the engine hands one entry per call: what
   was asked, and what it answered. Now and then it also hands over a snapshot, an *anchor*, so a
   replay can start part-way through.
2. **A stream of log records.** `:kuilt-heddle-otel` turns each entry into one `LogRecord` in an
   exporter's buffer, and reads a pulled list of them back into a `DecisionTrace`.
3. **A replay.** `DecisionTrace.replay(policy)` starts a twin engine from an anchor, asks it each
   recorded question in order, and answers with one `ReplayReport`: every answer matched, or here
   is the first that did not, with the twin stopped just before it so you can ask it why.

Turning it on should take a few lines, and so should a replay. The rest of this page is about
what those lines must promise.

## The honest seam

Four limits come with this design. None of them is a missing feature.

- **Record before you need it.** A replay needs every question, in order. A question that was
  never written down cannot be reconstructed from the answers around it. So recording is on
  before the surprise, or the surprise is not replayable.
- **A gap ends a stretch of the recording.** The log buffer is a window, and it can drop records.
  When it does, the replay cannot cross the hole. It picks up again at the next anchor and says
  exactly which entries it could not check.
- **A replay stops at the first difference.** After the copy answers one question differently, it
  has a different history. Its answers to later questions are answers about some other morning.
- **A many-peer scheduler is not deterministic on its own.** `HeddleNode` decides from a ledger
  that other peers keep writing into. Its questions are not its only inputs. This page records the
  one-process engine first and leaves the many-peer form to its own design (§10).

## Where this could go

Once the recording exists, the same replay answers questions nobody has to stage. Run the trace
of yesterday's farm against a changed policy and see the first pick that changes. Replay on a
laptop what an iPhone recorded, and check the cross-target promise on real traffic. Follow a
live device's log tail and replay it as it arrives, a twin that raises its hand the moment the
real scheduler does something its rules do not explain. The same three pieces fit `Quilter`,
`GameSession` and `Room` too (§11).

---

## The design

### 1. What a decision record is

**A decision record is an input log with the answers kept beside it.** It is not a trace of
derived values.

- **The inputs are the calls that change the engine.** Nine public calls do: `apply`, `attach`,
  `detach`, `reconcileCapacity`, `acquire`, `admit`, `extend`, `complete` and `cancel`. Each runs
  inside the engine's private `atomically` block in
  [`LocalHeddle.kt`](../kuilt-heddle-core/src/commonMain/kotlin/us/tractat/kuilt/heddle/LocalHeddle.kt).
  Two other things run there and are **not** recorded: `fromTopology`'s boot, which happens before
  a recording can be attached and which the first anchor captures instead (§3), and an `internal`
  hook only tests call. The policy is the one other input, and it is fixed for an engine's life.
- **The answers are kept so the replay has something to check.** Each entry carries the call's
  outcome: the `AcquireOutcome`, `AdmitOutcome`, `LeaseOutcome` or `ApplyResult` it returned, or
  the kind of exception it threw. An answer is derived, and a correct twin would recompute it. It
  is kept because it is the live system's testimony, the thing the replay checks.
- **Reads are not recorded.** `explain`, `previewPicks`, `entitlement`, `reconcileLeases`,
  `snapshot` and `persist` change nothing. A replay recomputes any of them at any step, so writing
  them down buys nothing and costs a record each.
- **Derived values are not recorded either.** Virtual times, positions, fronts and hold reasons
  are all recomputable from the inputs. A *derived trace*, such as #3184's hold reason emitted when
  it changes, is a separate stream for dashboards, never an input to a replay.
- **A thrown call is recorded too.** `atomically` puts the state back on a throw, so the call
  changed nothing. Its entry still says it threw, as one of four kinds (`IllegalArgument`,
  `IllegalState`, `Arithmetic`, `Other`), because a class name does not read the same on every
  target, and because a call that threw is often the surprise.

An entry is written **after** the call's state is kept, or after its throw has put the state back.
So no entry describes a state the engine did not end in.

### 2. The entries

Every entry carries `seq`, a number the engine counts up from zero for each recording, one per
entry, with no gaps at the source. A gap downstream is therefore always visible.

| Entry | What it carries |
|---|---|
| `Begin` | the `streamId` the recording was started with (the adapter mints a random one; core reads no random source), a `recordedBy` label the consumer supplies (say, its `KuiltVersion.VERSION`), the `LocalPolicy`, and the name of the digester the recording's digests are hashed with (§8) |
| `Anchor` | a `LocalHeddleSnapshot` taken by `persist()`, and the engine's next lease sequence number |
| `Call` | one of the nine calls with its arguments, and its `DecisionOutcome` |
| `Digest` | a 64-bit hash of the state at this point, by the digester `Begin` names, written every `digestEvery` calls between anchors (§8) |
| `End` | written when a recording is closed, so a reader can tell a finished trace from a cut one |

The arguments are copied when the call is made. A caller may reuse one mutable `waiting` set
across calls, and the entry must hold what the engine saw, not what the set holds later.

`DecisionOutcome` is the trace's own type, not the public outcome types made `@Serializable`.
Making `AcquireOutcome` a wire format would mean renaming one of its fields breaks every stored
trace. A trace type mapped from the public one lets each change on its own. The mapping lives in
`:kuilt-heddle-core`, beside the outcome types it reads, and the replay uses the same mapping, so
the recorder and the replay cannot read one outcome two ways.

One part of an outcome is kept but **not compared**: `StepRefusal.Refused.reason`, which is
prose. A thrown call records its kind and no message, for the same reason. A changed sentence is
not a changed decision.

### 3. Anchors, and why the twin keeps the same epoch

An anchor is the snapshot `persist()` takes, plus the one piece of state the snapshot leaves out
on purpose: the counter lease ids are numbered from. `LocalHeddle.restore` must run under a later
epoch, and that changes two things a twin has to keep:

- **Lease ids.** A restored engine counts its lease ids from zero under the new epoch, so a
  recorded `complete(lease)` would name a lease the twin never opened.
- **The ledger's bytes.** Each epoch mints under its own `MintId`, `local#<epoch>`. A twin under a
  later epoch mints the same amounts under another key, so its snapshot differs from the live
  one's even when every pick agrees.

So the replay starts its twin through an `internal` resume in `:kuilt-heddle-core` that continues
the anchor's own epoch at the recorded lease number. A twin resumed from an anchor then takes the
same snapshot as the engine that wrote the anchor, byte for byte, and the replay can check state as
well as answers (§8). That resume is `internal` and not behind `@HeddleInternalApi`, because a
second engine resumed under a live engine's epoch would hand out the lease ids the live one goes
on issuing. That is one
reason the replay lives in the same module (§7).

**When an anchor is written:**

- at the start of a recording, right after `Begin`;
- every `anchorEvery` calls, a recording parameter;
- at the next call after the sink reports that it dropped an entry (§5), so a gap is followed by
  a fresh starting point as soon as possible. If that anchor is dropped too, it stays owed, but
  the engine does not try again until the sink next answers `Kept`. A sink that is dropping
  everything would otherwise cost a `persist()` on every call while it recovers;
- when the consumer asks for one, on the next call. An anchor is taken under the caller's own
  scheduling lock, inside a call, because `LocalHeddle` has one writer.

A boot is not a call. `LocalHeddle.fromTopology` returns an engine with no recording, and the
first anchor holds what the boot built, including any group it left out. A restart is a new
engine and a new recording, with its own `Begin` and first anchor. A long story across restarts is
several traces, each anchored.

### 4. The wire format, and how it versions

**One entry is one `LogRecord`.** Its body is the entry encoded as JSON, in the same two-element
envelope the snapshot uses, `[traceFormat, entry]`, so a store that reorders an object's keys
cannot put the entry ahead of its format.

- **JSON, not CBOR.** A trace that reaches a collector is read by people as often as by the
  replay, and a log backend shows text. CBOR would need base64 in a string body, about a third
  larger and unreadable. The snapshot already encodes as JSON and is checked in that format.
- **`DecisionTrace.FORMAT_VERSION` starts at 1 and is independent of the snapshot's.** An anchor
  carries its snapshot encoded by the snapshot's own serializer, which writes the snapshot's own
  format number inside it. So the trace never changes snapshot format 1, which 0.7.5 froze, and
  a snapshot format change never needs a trace format change. A trace reader decodes the anchor
  through `LocalHeddle.decode`, which refuses a snapshot format it does not read by name, as
  `RestoreResult.UnsupportedVersion`.
- **A reader halts on what it cannot read.** An unknown trace format, an unknown entry kind, or
  an unknown outcome kind ends that stretch of the trace with a typed `Unreadable` verdict. It is
  never skipped. A skipped call is a missing input, and a replay over a missing input checks a
  history that never happened. This is the rule `.claude/rules/heddle.md` already sets for the
  control log, for the same reason.
- **The bytes are pinned by checked-in golden vectors,** one per entry kind and outcome kind, in
  the shape of `HeddleControlLogCodecTest`. A change that moves them bumps `FORMAT_VERSION`. A new
  outcome arm is such a change.

**`waiting` and `eligible` are written as changes,** with the whole set written now and then, the
way a delta-state CRDT ships deltas and the occasional full state. A tree with thousands of lanes
would otherwise make every call entry as large as the tree.

- A `Call` entry writes each set as `{added, removed}` against the same set in the previous `Call`
  of its stretch. A stretch is contiguous by construction, so the chain has no hole to fall into.
- The whole set is written at the first `Call` after every anchor, every `fullSetEvery` calls,
  and whenever the change would be larger than the set.
- At a whole set the reader checks the set it rebuilt from changes against the one written, and a
  mismatch is `Unreadable`. That turns a bug in the change encoding into a verdict, not a quietly
  wrong replay. It also means a person scrolling a collector sees a whole set every
  `fullSetEvery` records.
- The arguments the engine saw are still copied when the call is made (§2). The change is
  computed from that copy, so a reused mutable set cannot leak a later state into it.

The record around the body:

| `LogRecord` field | Holds |
|---|---|
| `recordId` | FNV-1a 64 over `streamId` and `seq`, 8 bytes. The exporter skips a record whose id it has already taken, so re-exporting an entry is harmless. Hashing in `streamId` keeps two recordings that share one exporter from taking each other's ids, short of a 64-bit collision |
| `severityNumber`, `severityText` | 1, `TRACE`, so a collector can filter the stream out of an operator's view |
| `attributes` | `heddle.trace.stream`, `heddle.trace.seq`, `heddle.trace.kind`, `heddle.trace.format` |
| `body` | the JSON envelope |
| `timestampEpochNanos` | the adapter's clock when its sink queued the entry, the nearest it gets to when the call was made |
| `observedEpochNanos` | the adapter's clock when the pump exported the entry, so the difference between the two is time spent in the queue |

Both times come from a clock the adapter is given, so a test can fix them. They are for people
only: the engine reads no clock, and the replay never reads either.

**An anchor is split into parts.** A snapshot grows with the tree, and one record per anchor
would make the largest record in the buffer as large as the whole ledger. So an anchor above
`ANCHOR_PART_BYTES` of JSON is written as several `AnchorPart` entries, each with its own `seq`,
reassembled by the reader. A missing part is a gap like any other. The constant is set from the
measurement in slice 3.

### 5. Where it is buffered, and how much

The record makes three hops, and only the last one exists today.

1. **The engine calls the sink, synchronously, under the caller's lock.** `LocalHeddle` has no
   coroutines and one writer, so the sink must not block and must not suspend. It answers each
   entry `Kept` or `Dropped`. A sink that throws is counted as `Dropped`, and the call's result
   stands: recording never changes a decision. `Dropped` makes the engine owe an anchor, which it
   tries at its next call and, if that is dropped too, not again until the sink answers `Kept`
   (§3).
2. **The adapter's sink puts the entry on a bounded queue,** and a long-lived pump, launched
   through `Flow.pumpIn`, exports from that queue. When the queue is full the adapter drops the
   arriving entry and counts it. The engine has already numbered the entry, so the drop is a gap
   the reader will see.
3. **The pump exports into a `WarpLogRecordExporter`** with its own `maxRecords`, the buffer's
   existing bound. That exporter is where the record lives, durable across a restart.

**Use a dedicated exporter.** The type is the same one the app's logs use, and so is the tap and
the OTLP drain. The instance is separate. The default buffer holds 10,000 records
(`DEFAULT_MAX_LOG_RECORDS`) and evicts the oldest when full, so a chatty app shares its window
with the trace and pushes anchors out. The adapter still works against a shared exporter, and the
`seq` numbers make every eviction visible, but the guide will say to give the trace its own. The
cost is one more call that already exists: `installLogTap` takes one exporter, so the trace's
exporter gets its own tap session, on its own `LogTapConfig.pattern`.

**The bound is in records.** With a buffer of `M` records and an anchor every `K` calls, the
oldest stretch can be partly evicted, and every stretch after it is whole. Each stretch holds
more than its calls: its anchor's `P` parts, and a digest every `d` calls (`digestEvery`). So a
stretch is `K + P + K/d` records, and roughly `M·K/(K + P + K/d) − K` calls are replayable. For
`M = 10,000`, `K = 1,000`, ten parts to an anchor and `d = 100`, that is about 8,800 calls. A
lease costs two calls, an `acquire` and a `complete`, so a farm starting one lease a second makes
7,200 calls an hour, and that buffer holds about 73 minutes of it.

**The recorder is meant to stay on.** The first limit above is that a surprise nobody was recording
cannot be replayed, so a recorder switched on afterwards always arrives too late. Recording stays
opt-in in core, which has no buffer to default to, but the guide tells consumers to leave it on,
once slice 3 shows it meets this budget:

- recording adds at most **10%** to a mutating call's median time on the JVM, for a 1,000-lane tree,
  counting the sink, the entry's copies and its digest share;
- a steady-state call entry for that tree, with set changes (§4), is at most **512 bytes** of JSON at
  the median;
- the default dedicated buffer keeps at least **an hour** of a farm starting one lease a second.

The numbers are this page's to change, not slice 3's. A slice that misses one reports the miss,
the guide keeps recording opt-in, and the budget is revisited here.

**The bytes are not measured yet.** Twenty lanes with names around fifteen characters come to
under a kilobyte of JSON written whole. That figure is arithmetic, not a measurement, and slice 3
measures it before any default is set.

**For longer than the window, archive it.** The dedicated exporter takes an `AppliedOpSink`, and a
`BoltDecorator` there archives entries past the buffer's cap. The archive is best effort.
`AppliedOpSink` returns nothing, so when the archive fails to write an entry the exporter is never
told, and only the decorator's `health` counts it. The entry is not lost silently, though: its
`seq` is missing from the archive, and the reader reports that gap like any other. Nothing in this
design changes for it; slice 6's cookbook entry shows the wiring.

### 6. Riding the otel buffer and the tap

Nothing new moves bytes. A trace leaves the device three ways, all existing:

- **Pulled by a peer.** A test or a support tool joins the session `installLogTap` opened for the
  trace's exporter and calls `LogTapClient.pull()`. The result is a `List<LogRecord>`.
- **Tailed live.** `LogTapClient.tail()` streams records as they arrive, which is what lets a replay
  run beside the live system.
- **Drained to a collector.** `:kuilt-otel-otlp` forwards the records to any OTLP/HTTP endpoint
  when the network returns. They come back as log records, and the reader takes them in any order.

`:kuilt-heddle-otel` reads records back with `DecisionTraceReader`: it keeps the records of one
`streamId`, sorts them by `seq`, decodes each, reassembles anchor parts, and builds a
`DecisionTrace` of contiguous *stretches*, each starting at an anchor. Wherever `seq` skips, the
reader records a gap and starts the next stretch at the next anchor. It never reads a record's
timestamp.

### 7. The simulator's surface, and which module

Three modules, each doing what its dependencies already allow:

| Module | Gains | Why there |
|---|---|---|
| `:kuilt-heddle-core` | `DecisionSink`, the entry and outcome types, `DecisionTrace`, `SnapshotDigester`, the recording hook on `LocalHeddle`, the replay (`DecisionTrace.replay`, `ReplayResult`, `ReplayReport`, `ReplayVerdict`), and the estimate (`DecisionTrace.estimate`, `WorkloadEstimate`) | The recorder has to be inside the engine, and the replay needs the `internal` same-epoch resume. Both need nothing beyond what core already depends on: no coroutines, no format, no network. The one thing that would need a format, hashing a snapshot for a digest, is a digester the caller passes in (§8) |
| `:kuilt-heddle-otel` | the JSON codec, the `LogRecord` mapping, the adapter sink with its queue and pump, the digester the adapter records with, and `DecisionTraceReader` | It depends on `:kuilt-otel`, which core may not. #3184 already plans this module for heddle's metrics and spans. Whichever lands first creates it |
| `:kuilt-heddle-test` | the many-peer form, later (§10) | It will need `:kuilt-raft-test`'s `MultiNodeRaftSim`, which no production module may depend on |

The replay surface, sketched (names are proposals):

```text
SnapshotDigester(name: String, digest: (LocalHeddleSnapshot) -> Long)
DecisionTrace.replay(policy = <the recorded one>, digester): ReplayResult
ReplayResult.DigesterMismatch(recorded: String, given: String)  // §8, before any call
ReplayResult.Replayed(report: ReplayReport)
DecisionTrace.compare(policyA, policyB): ReplayReport
DecisionTrace.estimate(policies): WorkloadEstimate  // §8, aggregates, calibrated first
ReplayReport.stretches: List<StretchReplay>      // one per stretch, in seq order
ReplayReport.firstDivergence: ReplayVerdict.Diverged?
ReplayReport.unchecked: List<LongRange>           // the gaps, as seq ranges
StretchReplay.before(seq): LocalHeddleSnapshot     // the twin just before entry seq
TraceReplay.offer(entry): ReplayStep               // the incremental form, for a live tail
```

`before(seq)` returns a public snapshot, so asking *why* at any step is a call that already
exists: `before(seq).view(policy).explain(leaf, waiting, eligible)`.

`compare` runs two twins from the same anchor under two policies and checks them against each
other, not against the recording. That is "what would this policy have picked" on real traffic.
"What would this *build* have picked" needs no special call: run the replay on the other build. A
trace is the first build's testimony, so the second build checks itself against it.

### 8. How a replay diverges, and how it says so

A replay checks two things: each call's answer, and the state at each anchor.

- **The answer.** For each `Call` entry the twin makes the same call and maps its result the same
  way. If the two `DecisionOutcome`s differ, the stretch ends with
  `Diverged(seq, call, recorded, replayed)`, and the twin is kept just before that call. For
  `LocalHeddle` this check sees the order of picks, because one `acquire` is one pick. That is the
  property #3504 found a call-for-call comparison over `HeddleNode` lacks, since there one
  `schedule` call meets all demand in any order.
- **The state.** At each anchor after the first, the twin takes its own snapshot and compares it
  with the anchor's through `LocalHeddleSnapshot.equals`, so core encodes nothing to check it.
  Equal snapshots encode to equal bytes, so this is the same check as comparing the bytes. A
  mismatch with every answer matching is `StateDiverged(anchorSeq)`:
  some call between the two anchors changed state that no answer showed yet, such as a wake clamp.
  The replay narrows it to that stretch of `K` calls. Narrowing further needs a closer anchor, so
  a consumer chasing one can lower `anchorEvery`.
- **The rest.** A stretch ends `Matched` when it runs out, `Unreadable(seq, reason)` when the
  reader could not decode an entry, and a gap is reported in `unchecked`. A trace that reaches
  `End` with every stretch `Matched` and nothing unchecked is a full match, and the report says so
  in one field. A replay handed the wrong digester makes no report at all: it refuses before the
  first call (below).

A replay never continues past a divergence in the same stretch, because the recorded calls after
it name leases the twin may not have opened. Going further is a different tool, below.

**Digests narrow a `StateDiverged` between anchors.** Every `digestEvery` calls the recorder writes a
`Digest` entry: a 64-bit hash of the snapshot `persist()` would take at that point. Core has no
format to encode a snapshot with, so it does not hash one itself. The recording hook takes a
*digester* as a required parameter, and the replay takes one too. A digester is a
`SnapshotDigester`: a function from `LocalHeddleSnapshot` to `Long`, and a stable name for it,
which its `init` refuses to leave blank. It is a class holding both, not a `fun interface`,
because a `fun interface` has one abstract member and the name would be a second.
`:kuilt-heddle-otel` supplies the one it records with: FNV-1a 64 over the snapshot's JSON
encoding, the same hash the `recordId` uses. The replay hashes its twin's snapshot at the same
`seq`, and a mismatch is `StateDiverged(digestSeq)`, narrowed to the `digestEvery` calls since the
last check.

**A replay handed the wrong digester refuses before it starts.** Two different functions hash the
same state to different numbers, so a replay checking one against the other would report
`StateDiverged` at the first digest where the states agree. That is a mistake in how the replay
was set up, and it would read as testimony about the engine. So `Begin` records the digester's
name, and before it resumes a twin or replays a call, `replay` compares that name with the name of
the digester it was handed. On a difference it returns `ReplayResult.DigesterMismatch(recorded,
given)` and no report. The incremental form makes the same check at the `Begin` it is offered,
before any call. Core compares names, not functions, because the trace holds only the name. So a
name is a promise that the output has not changed, and invariant 7 is what keeps the adapter's
promise.

A digest saves buffer space, not work: the adapter's digester still encodes the whole snapshot to
hash it, so slice 3 measures its cost beside an anchor's. It is evidence, not proof. Two different
states share a hash once in 2⁶⁴, and nothing here is adversarial. It relies on a snapshot encoding
to the same bytes on every target, and a golden vector pins the hash on JVM, native and wasm
(§12).

#### Beyond the first difference: the workload, not the picks

Change the policy and the first pick differs within a few calls. After that the recorded calls
describe a morning the twin did not have. So the counterfactual does not try to follow the
recording call by call. It reads the **shape of the workload** out of the trace, runs that shape
through a twin under each policy, and compares the aggregates. The question it answers is "would
`sleeperCredit = 4` have treated the small team better on a morning like this one?", and the answer
is a tuning suggestion with error bars, never a prediction of a pick.

**The model is three things the trace already holds:**

- **When each leaf wanted work.** Each recorded `acquire` and `admit` carries `waiting` and the
  caller's `startedAt`. Together they say which leaves were queued at each moment, on the caller's
  own clock, in the cost unit.
- **How long each leaf's work ran.** Each `complete` carries the `actualCost` of a lease on a known
  leaf. Those are the leaf's run-time samples.
- **How much capacity there was.** `reconcileCapacity` and the topology calls, as recorded.

**The simulation drives a twin from that model.** It starts at an anchor, under the policy being
tried. At each recorded moment a slot was offered, it offers the twin the recorded `waiting` and
`eligible`, and the twin picks. Each lease the twin opens finishes after a run time drawn from its
leaf's samples, taken in their recorded order so the run reads no random source and gives the same
numbers on every target. The simulated `complete` lands before the first offer at or after its
finish time. It is an estimate twice over: run times are resampled, and `waiting` is taken as
recorded, so a queue that would have drained sooner under the new policy is not modelled.

**What it reports is aggregate,** per leaf and per stretch: the share of cost charged, how long a
leaf sat in `waiting` before its next pick, and the longest such wait. Those are the numbers a
tuning decision rests on.

**It checks itself before it advises.** The same simulation, run under the *recorded* policy, has
to reproduce the recorded aggregates within a tolerance. That calibration run is what makes the
estimate worth reading. If the model cannot reproduce the morning that actually happened, its
answer about a different policy is noise. The report then says `Uncalibrated`, with the gap it
measured, and gives no suggestion. It also gives up when a picked leaf has no run-time sample in
the trace, since its work would have to be invented.

`compare(policyA, policyB)` is the replay, stopping at the first difference. `estimate(policies)`
is this, and returns a `WorkloadEstimate`, a separate type from `ReplayReport`, so an estimate
cannot be read as testimony. A tuning sweep is `estimate` over a list of policies. It cannot cross
a gap: the next stretch starts from a recorded anchor, which is not the state the simulation
reached.

### 9. What step B changes

0.7.6 removes the rebase and the origin and adds a position limit, in
[`heddle-design.md` §7.1, *Future: positions with no origin (step B)*](heddle-design.md#future-positions-with-no-origin-step-b).
Step B has merged. The recorder ships in the release after it, which keeps one constraint and
touches four parts of this design.

- **No public signature changes.** The recorder breaks none, and its API dumps may only gain lines. So the
  recording hook is a new member or overload on `LocalHeddle`, never a default parameter added to
  `fromTopology` or `restore`, and the public outcome types are not made `@Serializable`.
- **Anchors.** Slice 4 takes the origin out of the ledger, and 0.7.6 writes format 1 with no
  origins. An anchor needs no change, because it is whatever snapshot `persist()` takes, encoded
  in format 1.
- **An engine restored from a 0.7.5 snapshot records the translated state.** No trace predates
  step B: the recorder ships after it, so no trace holds an origin. A snapshot 0.7.5 wrote can,
  and restoring it translates each origin into its children's positions and discards it
  (`LedgerFormat1` in `:kuilt-heddle-core`), before a recording can start. So that engine's first
  anchor is written origin-free, and its bytes differ from the snapshot the consumer restored. The
  replay starts its twin from the anchor, never from the 0.7.5 bytes, so the translation cannot
  show up as a divergence.
- **The position limit refuses through existing arms,** `AcquireOutcome.LifetimeExhausted` and
  `StepRefusal.Refused`, so the trace format does not move for it. If §7.1's open question about
  telling a parent stalled at the limit apart from one at a lifetime counter is answered with a
  new arm, that arm is a new outcome kind and bumps the trace format (§4).
- **Wide virtual times (slice 6) touch nothing here.** The trace records no virtual time. The
  replay reaches them through `explain` on a snapshot view, as any caller does.
- **#3480's "never-rebasing twin on real traffic" goes away.** It needs a build that both records
  and still rebases, and slice 3 (#3495) has deleted the rebase, so no such build will exist. The
  twin proof (§7.1, slice 1) stays the synthetic one.

### 10. The many-peer form: what it would need

#3480 proposes merging per-node traces by control-log index. That ordering is too weak. The
control log orders the topology acts: `Prepare`, `Activate` and the rest. Grants, charges and
demand reach a `HeddleNode` through `Quilter` delta exchange and the demand board, which the log
does not order. A node's `schedule` reads whatever has merged by then. So a node's input log has
to record, in the order its lock applied them:

- its own calls: `prepare`, `activate`, `close`, `retire`, `advertise`, `schedule`, `reserve` and
  `complete`;
- every remote ledger delta it merged;
- every remote demand update it merged;
- every control-log entry it applied.

Each node then replays alone, deterministically. Checking peers against each other is a separate
question, and the convergence checks `HeddleAcceptanceTest` already makes answer it. Recording
merged deltas costs far more than recording calls, and a delta may be the whole ledger. That cost
needs measuring before a design, so the many-peer form is its own issue, designed when the
single-engine form is built.

### 11. What is generic

The pieces this design adds come apart into two layers:

- **A numbered stream of records over the otel buffer:** a `streamId`, a gap-free `seq`, a
  derived `recordId`, parts for large entries, and a reader that sorts, reassembles and reports
  gaps. Nothing in it is heddle's.
- **Anchor, input log, first difference:** the replay. Its shape is generic, but what counts as an
  input is each family's own. `Quilter` has merges as inputs, `GameSession` has Raft, and
  `LocalHeddle` has only its calls.

Both stay in heddle's modules for now. The stream layer moves to `:kuilt-otel` when a second
family needs it, with heddle's codec as its first user and its golden vectors as its tests. A
generic stream designed before its second user would be designed around a guess.

### 12. Invariants

Each names what enforces it. An enforcer that is not built yet names the slice that builds it.

1. **Every call that changes a `LocalHeddle` is recorded.** Enforced by a required parameter:
   `atomically`, the one block every mutating call already runs in, takes the call's entry, so a
   mutating call cannot be written without naming one (slice 1). The boot and the `internal` test
   hook, the two runs that are not recorded (§1), say so by name at that parameter, so the
   exclusion shows at the call site. Backed by the round-trip property in slice 2: a seeded driver
   making every mutating call, whose twin must end in the same snapshot as the live engine.
2. **Recording never changes a decision.** The sink is called after the state is kept, a throwing
   sink counts as `Dropped`, and the engine's view engines are built with no recording. Enforced by
   a twin test: the same seeded calls with recording on and off make the same picks and end in
   the same snapshot (slice 1).
3. **A trace's `seq` has no gap at the source.** The engine numbers entries; nothing downstream
   does. `DecisionTrace`'s stretches are built only by the reader, and each stretch requires
   contiguous `seq` in its `init`, so a stretch with a hole cannot exist (slice 2).
4. **A twin resumed from an anchor takes the same snapshot as the engine that wrote it.** Enforced
   by keeping the resume `internal` to `:kuilt-heddle-core` and by a round-trip test over every
   seeded driver of slice 2.
5. **A reader halts at what it cannot decode.** `ReplayVerdict` is sealed and `Unreadable` is one
   of its arms; the codec has no skipping path. The golden vectors red on any layout change
   (slice 3).
6. **The trace format is independent of the snapshot format.** An anchor nests the snapshot's own
   envelope, read through `LocalHeddle.decode`. Pinned by a golden anchor vector that carries a
   snapshot of format 1 (slice 3).
7. **A digest is the same number on every target.** A golden vector for the adapter's digester,
   run on JVM, `macosArm64` and `wasmJs`, read as a union (slice 3). Core cannot pin it: the
   digester is a parameter there, and the one that ships is the adapter's.
8. **A set rebuilt from changes equals the set written whole.** Checked by the reader at every
   whole set, as an `Unreadable` verdict; pinned by a seeded round trip whose sets change on most
   calls, with `fullSetEvery` large enough that a chain of changes is actually walked (slice 3).
9. **An estimate is never read as testimony.** `WorkloadEstimate` and `ReplayReport` are separate
   types, and an estimate whose calibration run misses the record carries no suggestion
   (slice 5).
10. **A sink that keeps dropping costs one anchor, not one per call.** After a drop the engine owes
    an anchor and tries it once; a dropped anchor is not tried again until the sink answers
    `Kept`, and the next call after that carries it. Enforced by a slice 1 test: a sink that drops
    every entry for fewer than `anchorEvery` calls sees one `persist()`, and the first `Kept` after
    it is followed by an anchor.

### 13. Slices: issues to file

One behaviour move each, in order. None is filed yet.

1. **Record (`:kuilt-heddle-core`).** `DecisionSink`, the entry and `DecisionOutcome` types and
   their mapping, the required-parameter `atomically`, the recording hook with its required
   digester, anchors and digests. Tests: the on/off twin (invariant 2), sink drop and throw, an
   anchor after a drop and none again until `Kept` (invariant 10), a reused mutable `waiting` set.
   The tests pass a digester of their own, since core has no format. ABI dump gains lines only.
2. **Replay (`:kuilt-heddle-core`).** The `internal` same-epoch resume, `DecisionTrace` and its
   stretches, `replay` with its digester, `compare`, `before`, `ReplayReport`, and the anchor check
   by `LocalHeddleSnapshot.equals`. Tests: a seeded driver over all nine
   calls replays `Matched` on JVM, `macosArm64` and `wasmJs`, read as a union, sorting what it
   walks (#2592). A changed `sleeperCredit` reports `Diverged` at the first differing `seq`, and the
   row asserts that a pick actually differs at that `seq` before it acts. Mutations that must red:
   one call left unrecorded; an anchor resumed under a later epoch; an outcome mapped without its
   lease id; a digest compared at the wrong `seq`.
3. **The wire (`:kuilt-heddle-otel`, created here or by #3184).** The JSON codec with golden
   vectors, set changes and whole sets, the `LogRecord` mapping, anchor parts, the adapter sink,
   queue and pump, the digester with its golden vector (invariant 7), and `DecisionTraceReader`
   with gap reporting. A measurement against the budget
   in §5 sets `anchorEvery`, `digestEvery`, `fullSetEvery`, the queue size and
   `ANCHOR_PART_BYTES`, and says whether the guide can tell consumers to leave recording on.
   Includes the four edits a
   new published module needs: `settings.gradle.kts`, its `AGENTS.md` row, its branch in
   `moduleDescription()`, and a regenerated `llms.txt`.
4. **End to end.** #3480's done-when: a test records a running `LocalHeddle`, pulls the trace
   through `LogTapClient`, replays it with every pick matching, and reports the first differing
   step under a changed policy. A second row drops records from the buffer and asserts the gap is
   reported and the next stretch still replays.
5. **Estimate (`:kuilt-heddle-core`).** The workload model, the simulation, the calibration run
   and `WorkloadEstimate` (§8). Tests: a synthetic farm whose small team starves under one
   `sleeperCredit` and not under another, where `estimate` must name the better one; a trace
   whose run times are too noisy to calibrate, which must come back `Uncalibrated`; a picked leaf
   with no sample, which must give up. The calibration tolerance is set from the first of those.
6. **Routes and the guide.** A symptom→primitive entry in `docs/agent-cookbook/heddle.md`, the
   matching route in the `kuilt-primitives` skill (a trigger in, one out), and a guide topic,
   *When a pick surprises you*, written top-down.
7. **The many-peer form.** A design first (§10), then `:kuilt-heddle-test`.

Slice 5 needs only slices 1 and 2, and can run on synthetic traces before the wire exists.

#### Settled — don't reopen

- **Recording derived values, positions or hold reasons as the trace.** They are recomputable
  from the inputs, and recording them would make the trace larger and no more checkable. A
  derived stream for dashboards is #3184's.
- **Recording reads.** They change nothing, and a replay recomputes any of them at any step.
- **Making the public outcome types `@Serializable`.** It would turn them into a wire format, so
  renaming a field would strand stored traces. The trace maps them into its own types.
- **CBOR bodies.** A log body is a string. CBOR needs base64, a third larger and unreadable in a
  collector.
- **Folding the trace format into the snapshot format.** Snapshot format 1 is frozen, and the two
  change for different reasons. An anchor nests the snapshot's own envelope instead.
- **Skipping an entry a reader cannot decode.** A missing input makes a replay check a history
  that did not happen.
- **Restoring the twin under a later epoch and mapping lease ids.** The answers could still be
  compared, but the snapshots could not, since each epoch mints under its own `MintId`.
- **A same-epoch resume behind `@HeddleInternalApi`, with the replay in a test module.** An
  opted-in caller could resume a live engine under an epoch it already used and hand out lease
  ids twice. `internal` closes that, which puts the replay in core.
- **A new transport, or a new kind of buffer, for the trace.** The otel buffer, the tap and the
  OTLP drain already carry records post hoc. A second exporter instance is the same kind.
- **A sink that suspends, or a sink in core that buffers.** `LocalHeddle` has no coroutines and
  one writer, so the sink answers at once, and the adapter owns the queue.
- **Ordering many-peer traces by control-log index.** The log does not order the ledger deltas
  and demand updates a `schedule` reads.
- **A generic replay in `:kuilt-core` or `:kuilt-otel` now.** One user only. The stream layer
  moves out when a second family needs it.
- **Reordering step B so a recording build still rebases.** It would delay a release to feed a
  proof that already has synthetic traffic.

- **Recording as a tool you switch on while investigating.** A surprise nobody was recording
  cannot be replayed. The recorder is meant to stay on, under the budget in §5.
- **Writing `waiting` and `eligible` whole in every entry.** It makes each entry as large as the
  tree. Changes with periodic whole sets cost a second encoding, which the reader checks itself.
- **A counterfactual that follows the recording pick by pick past a difference.** Divergence comes
  within a few calls, and after it the recorded calls name leases the twin never opened. The
  counterfactual models the workload's shape instead, and checks the model against the record
  first.
- **Only anchors for state checks.** Lowering `anchorEvery` to narrow a `StateDiverged` fills the
  buffer with snapshots. A digest costs eight bytes.

#### Open

- **Modelling queues that drain.** The estimate takes `waiting` as recorded, so a leaf whose queue
  would have emptied sooner under the new policy still looks queued. Calibration bounds the error
  for the recorded policy only. A queue-depth input would need the caller to report counts, which
  `acquire` does not take today.
