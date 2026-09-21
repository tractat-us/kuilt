# Following a job

One photo, two willing devices. Who starts—and who takes over if the
answer never comes back?

## From the list to an answer

A job has an identity (`TaskId`) and a description (`TaskDescriptor`): the
operation to run and its input bytes. Each device's `OpRegistry` maps operation
names to code. A shared queue carries jobs between devices.

Warp maps jobs and devices onto a ring. The next device around the ring owns
that job. This **consistent hashing** lets devices with the same membership
view choose the same owner without negotiating each assignment.

You choose the membership source: a room's changing connection view or an
agreed [Raft roster](raft.md). The default claim strategy also shares an
intention to run, helping devices avoid races when membership changes.
`ClaimStrategy.Ring` uses the ring alone.

The owner runs the job and writes its answer. Underneath, the queue is an
`ORSet` and the answer board an `ORMap` with `LWWRegister` values.
[`Quilter`](crdt-quilter.md) exchanges their changes and repairs missed updates.
The [two-device test](https://github.com/tractat-us/kuilt/blob/main/kuilt-warp/src/commonTest/kotlin/us/tractat/kuilt/warp/WarpNodeTest.kt)
shows the wiring. In an app, persist and advance `epoch` on each restart;
the test's zero is only a fixture.

## A lost connection can mean a repeated job

A device that goes quiet may still be working. Another device can take over
before its answer arrives. The answer board merges results to one value per
job; it neither checks that two answers match nor undoes external effects.
Your operation must make that merge meaningful.

For work needing an agreed order, `CoordinationKind.Coordinated` uses Raft.
The leader starts only after the task commits and a majority of voters
confirms its authority. This check is a **quorum fence**.

The fence cannot stop an action already in progress. A leader replaced
mid-action may still finish while its successor retries. Make outside actions
safe to repeat, or give the destination an identity it can deduplicate.
Merging two email receipts cannot unsend the extra message.

## Charge the right team

A `Lane` labels whose budget pays for the work. Connect
`HeddleAdmissionControl(heddle)` to the node's `admissionControl`, then label
the task:

<!-- verbatim from kuilt-warp-heddle/src/commonSamples/kotlin/us/tractat/kuilt/warp/heddle/WarpHeddleSamples.kt#sampleHeddleAdmissionControl -->

```kotlin
val interactive: TaskDescriptor =
    TaskDescriptor(op = OpId("score"), args = "doc-1".encodeToByteArray())
        .inLane("acme/interactive")
```

The adapter reserves allowance before running the job and charges its cost
afterward. Insufficient allowance defers the job. **Untagged tasks bypass
this gate**, so label every task whose share you intend to control.
[Inside Heddle](heddle-accounting.md) follows the accounting.

## Choose where it may run

Devices advertise capabilities with `CapSet`. A task's `Affinity` selects
which of those devices are eligible:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleAffinity -->

```kotlin
val where = Affinity.has("GPU") and Affinity.attr("region", "us-east")
```

The condition travels with the task and works alongside its lane.
Warp takes peers at their word; it does not prove hardware or location.

Jobs can also carry WebAssembly code. Warp fetches and caches it;
`kuilt-warp-runtime` runs it with memory and time limits and no host file or
network access. A [compiler node](warp-compiler.md) can share an optimized
copy. Arbitrary local functions moving freely across devices remain research.

Next: [plan the work](warp-planning.md), or return to [Warp](warp.md).
These APIs remain part of the Playground and can change.
