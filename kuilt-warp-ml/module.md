# Module kuilt-warp-ml

Learning something from everybody's data without anybody handing over their data.

Say a hundred phones each hold a few private readings of the same thing — how long a task
usually takes, how a sensor drifts, what a person tends to tap next. You would like one
shared answer built from all of them. The obvious way is to collect the readings on a
server, and that is the way this module exists to avoid.

Here the *training step* travels instead of the data. Each device runs the same tiny
program on its own readings and publishes only what it learned — a short list of numbers
and how many examples it saw. Those summaries merge, in any order, with duplicates, across
a flaky network, and every device ends up holding the same learned answer. Nobody's raw
readings ever leave the device they were recorded on.

This is the classic **federated averaging** recipe, and on kuilt it is small: the merge is
a CRDT, the training step is a content-addressed program, and neither one needs a server.

## The pieces

| Piece | What it is |
|---|---|
| `FedAvg` | The shared answer. A CRDT holding one slot per device; reading it gives the count-weighted mean of every device's contribution. |
| `TrainingUpdate` | One device's finished step — how many examples it trained on and the numbers it came out with. Turns into a `FedAvg` contribution. |
| `ReferenceTrainer` | The training step itself, written in plain Kotlin. One gradient-descent step of a straight-line fit. |
| `FedAvgKernelCodec` | The byte layout the same training step uses when it travels as a WebAssembly program instead of Kotlin. |

A round is three sentences long: each device runs the step on its own batch, wraps the
result in a `TrainingUpdate`, and merges the contribution into `FedAvg`. Reading
`FedAvg.weights` gives the model.

## Why the merge is the interesting part

`FedAvg` stores each device's contribution **pre-multiplied** — `sampleCount × weights` —
so merging across devices is pure addition and the single division happens once, at read
time. That is what lets the merge be a join-semilattice: idempotent, commutative,
associative, and therefore correct under any delivery order, any duplication, and any
partition-then-heal. Two devices that saw the same updates in opposite orders hold the
same value.

Collisions inside one device's slot are resolved by a **total order** on
`(epoch, sampleCount, weightedSum)`, taking the maximum. A device bumps `epoch` when it
starts a new round; within a round, a re-broadcast with different content still merges
deterministically rather than depending on who received what first. The per-coordinate sum
is evaluated in canonical `ReplicaId` order, so the result is bit-for-bit identical on
every replica and every platform — including wasmJs.

Reading an empty `FedAvg`, or one whose devices disagree about how many numbers a model
has, throws. Both would otherwise produce a plausible-looking wrong answer: a divide by
zero, or a mean silently dragged toward zero by zero-padding a short vector.

## The training step, twice

The step exists in two forms that are proven **bit-for-bit identical**:

- `ReferenceTrainer.step` — plain Kotlin, and the oracle.
- `fedavg_train.wasm` — the same arithmetic, in the same operation order, as a
  content-addressed WebAssembly kernel that travels between peers through `:kuilt-warp`'s
  bobbin cache and runs under `:kuilt-warp-runtime`'s sandbox.

`FedAvgKernelEquivalenceTest` holds them to raw IEEE-754 bit equality, not a tolerance.
The operation order in `ReferenceTrainer` is therefore load-bearing: it is replicated
verbatim in `fedavg_train.wat`, and reordering it would break the equivalence even though
the arithmetic still "looks right".

Dimension is fixed at two — one feature and a bias — for the v1 kernel. `FedAvg` itself is
dimension-agnostic; only the kernel ABI pins it.

## What the end-to-end demo proves

`FedAvgWarpSimTest` (this module, JVM) and `FederatedLearningExampleTest` (`examples/`)
run the whole thing over `WarpNode`: three devices, each pinning its training task **to
itself** with `WarpNode.enqueueLocal`, each training on a batch captured in a node-local
closure that is never serialized. The claim that data stays put is asserted, not narrated
— the task descriptor's `args` are checked to be the 24-byte `(model, learnRate)` header
and strictly smaller than they would be if the batch rode along.

The demo also survives a Raft leader failover mid-round. The honest scope of that claim:
the training tasks run on warp's coordination-free path over an independent seam, so what
is proven is that consensus-layer churn never stalls the round and every device still
converges to the same model — not that Raft orders the training.

## Lane costing is per execution, not per task

If you gate this workload with `:kuilt-warp-heddle`'s `HeddleAdmissionControl`, know that
**a lane is charged once per execution, not once per task.** A task that gets run twice
costs its lane twice.

That is the specified behaviour, not a defect:
[`docs/heddle-design.md`](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md)
§14.4 states costing per execution, and §8.2 states the fairness-error bound over executions
for the same reason. Entitlement apportions capacity, and a second run really did consume a
second unit of it — charging once would let a churning lane push its waste onto every other
lane.

Where the second run comes from: `WarpNode` consults `AdmissionControl.admit(descriptor)` at
the single choke point immediately before it invokes a resolved op, and
`HeddleAdmissionControl` reserves and settles there. Warp's free claim path is
**at-least-once** — under ring or roster churn two peers can independently claim and run the
same `TaskId` before the results board converges (the benign failover race `WarpNode`'s own
churn tests measure). Every one of those executions reserves and settles, so one logical task
can spend two or more units from its lane. Two peers cannot share a charge even in principle:
they spend from their own local holdings, and §4.4 makes reservations deliberately node-local
("only the owning peer may complete or cancel its reservation").

So a lane that churns is charged more than a quiet lane for the same work, by the
duplicate-execution rate — normally zero, rising only during churn. `WarpNode.duplicates`
counts those executions across *all* lanes together (`warp.tasks.duplicate` via
`:kuilt-warp-otel`); the **per-lane** rate §8.2 asks for, reported alongside the bound's
other pieces, is not exposed yet — [#1756](https://github.com/tractat-us/kuilt/issues/1756).

Nothing in this module depends on heddle; `:kuilt-warp-ml` has no lane gating of its own.
It is recorded here because the federated-learning demo is what surfaced it.
