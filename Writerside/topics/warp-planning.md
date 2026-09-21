# Planning the work

A photo search scores images, drops weak matches, then picks a final list.
Doing less work before the devices must agree can make that search cheaper.
Warp lets you inspect and rewrite the plan before it runs.

## Describe the steps

A `Draft` records operations without executing them. Here a source feeds a
map, a filter, and an agreed final step called `embroider`:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleShuttle -->

```kotlin
val draft: Draft<ByteArray> = Warp.shuttle(OpId("docs"))
    .map(OpId("score"))
    .filter(OpId("above-threshold"))
    .embroider(OpId("rank"))

```

`isMonotone` means the plan needs no agreement step: incoming contributions
can extend the result without invalidating what is already known.
This draft has an embroidery, so that property is false.

## Reduce the work

`optimize()` applies three rewrites:

- **Delay agreement** until after work that can proceed independently.
- **Filter early** so fewer items reach expensive operations. This assumes
  the filter reads source data, not a preceding map's output; the planner
  lacks dependency metadata to prove that assumption.
- **Fuse adjacent maps or filters** into one symbolic stage. Callers still
  supply operation execution; fusion records an opportunity for one pass.

For a search, filtering a thousand photos down to fifty *before* agreement
means fewer results need to cross that boundary.

## Estimate the cost

`coordinationCost(stats)` compares plans using three numbers, in this order:

| Measure | Meaning |
| --- | --- |
| `rounds` | Agreement round-trips |
| `coupling` | Largest batch of agreements that must retry together |
| `coordinatedVolume` | Estimated items crossing agreement boundaries |

`WarpStats` estimates source sizes with compact approximate counts called
HyperLogLog sketches. They merge like other [replicated data](crdt-overview.md).
Your app collects and exchanges them, for example through `Quilter`;
`WarpNode` does not do this automatically. Planning uses the local statistics
without a network round-trip.

## Combine independent agreements

A draft can branch. `combine` joins independent drafts; `plan(stats)` can
batch their agreements into one round. A later agreement that needs an
earlier result must still wait. Thus dependency depth, rather than the
number of agreements, sets the minimum round count.


The tradeoff is retry size. If a batch proposal fails, every agreement in
it retries together. Inspect `coupling` and split the draft if that unit
is too large. These are plan costs, not promised workload timings.

## Read a growing result

Some results remain useful while more contributions arrive. For example,
a count that has reached five will stay at least five. `IncrementalResult`
merges contributions; `awaitThreshold` waits for a condition of this kind.
The condition must stay true as the result grows. Here `alice` and `bob`
identify the two peers:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleAwaitThreshold -->

```kotlin
val result = IncrementalResult(GCounter.ZERO)
result.contribute(GCounter.of(alice to 3L))
result.contribute(GCounter.of(bob to 2L))

```

`ConvergentExecution` stores a draft and merges submitted deltas asynchronously.
It does not execute the draft's map or filter operations; your code produces
those contributions. Supply the scope that owns this work.

Next: [follow a job](warp-jobs.md), or return to [Warp](warp.md).
These Playground APIs work today and can change.
