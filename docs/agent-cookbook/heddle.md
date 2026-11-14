# Heddle

Several groups can draw on one shared pool — computing time, task slots, a rate budget —
and each is owed a fair slice, say three parts to one, with no referee watching over the
split. This page is about keeping that fair even when a group goes idle and its spare
share gets lent to a busy one, and even while the network is split into pieces that cannot
currently talk to each other. And when the sharing has to happen across a network rather
than on one machine, every use of the shared resource still has to be charged exactly
once, never twice.

## Fair share & placement

When several groups draw from one shared pool — computing time, task slots, a rate
budget — and you want each to get the slice it was promised (say three parts to one),
a spare-capacity lender when someone is idle, and all of it holding up while the
network is flaky with **no central referee**, that is `:kuilt-heddle`. Don't hand-roll
a weighted round-robin, a quota counter, or a "reserve then charge" bookkeeper — the
pieces below already converge across partitioned peers with no coordination on the
spend path.

### Who runs the next quantum (weighted fair share)

**Intent:** decide which of several competing children gets the next slice of a shared
budget, weighted (3:1) and hoarder-proof — "who runs next", a weighted scheduler, EEVDF.
**Primitive:** `HeddlePolicy.pick(edges, config, localHoldings)` (`:kuilt-heddle-core`) — a
**pure** function: no wall clock, no randomness, no floating point, so every replica picks
the same winner. It serves the eligible child (not running ahead of its fair share) whose
next grant finishes soonest in virtual time. Returns `null` when nobody is both eligible
and demanding.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#samplePolicyPick -->
```kotlin
    // Both start level (no service yet); the heavier-weighted child has the earliest
    // virtual deadline, so it is served first.
    val grant = HeddlePolicy.pick(
        edges = listOf(edge("heavy", Weight.of(3), issued = 0L), edge("light", Weight.of(1), issued = 0L)),
        config = PolicyConfig(quantum = 6L),
        localHoldings = 1_000L,
    )
    check(grant == Grant(AttachmentId("heavy"), 6L))
```

### Scheduling without a network

**Intent:** run the fair-share loop in one process — hand a budget down to the children that
want it, 3:1 by weight, with newcomers and wakers seated level rather than given a head start —
without a `Seam`, Raft or coroutines.
**Primitive:** `FairShareAllocator.schedule(parent, ledger, earmarked, demand)`
(`:kuilt-heddle-core`) over a `LedgerCell`: the latest ledger plus one write step that decides
each grant on the state it lands on. For a single process the cell is a `var`. It is the same
loop `HeddleNode.schedule` runs. An allocator is not thread-safe: one per scheduling peer.
This is the bare loop over a ledger you hold yourself. For a whole scheduler — a lease per free
slot, the real cost charged after, reloads, and a snapshot across restarts — use `LocalHeddle`
instead (see [A fair-share tree in one process](#a-fair-share-tree-in-one-process)).

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleFairShareAllocator -->
```kotlin
    // The whole "network": the latest ledger, and one write step that decides on what it lands on.
    val ledger = object : LedgerCell {
        override var current = EntitlementLedger.bootstrap(root, mapOf(self to 400L), nonce = "genesis")
        override fun mutateOrSkip(transform: (EntitlementLedger) -> Patch<EntitlementLedger>?): Boolean {
            val patch = transform(current) ?: return false
            current = current.piece(patch.delta)
            return true
        }
    }
    for (record in listOf(heavy, light)) {
        ledger.mutateOrSkip { it.prepare(record) }
        ledger.mutateOrSkip { it.activate(record.id) }
    }

    val allocator = FairShareAllocator(self, PolicyConfig(quantum = 10L))
    val wantsEverything = Demand(targetOutstanding = 1_000L, maximumUsefulGrant = 10L)
    allocator.schedule(root, ledger, earmarked = 0L, demand = { wantsEverything })

    check(ledger.current.edge(heavy.id)?.issued == 300L)
    check(ledger.current.edge(light.id)?.issued == 100L)
```

### A fair-share tree in one process

**Intent:** load the fair-share tree from a config file — groups, lanes and their weights —
and show an admin every mistake in it at once, each beside the line it belongs to, instead of
failing on the first.
**Primitive:** `Topology.from(TopologyDocument)` (`:kuilt-heddle-core`). Decode the file into a
`TopologyDocument` in whatever format you already read, with weights as text (`"3"`, `"3/2"`).
`from` reports wrong *values* — empty or repeated names, bad weight text, empty groups — and
returns `TopologyParse.Valid` with a checked `Topology`, or `TopologyParse.Invalid` listing every
`TopologyProblem` in walk order. Whether a wrong *shape* decodes is up to your format: under a
default `Json`, a bare-number weight (`"weight": 3`) or a misspelt key throws before `from` runs,
so quote weights or decode with `Json { isLenient = true }`. A problem's address is its `path`,
the child indices from the root down (`[]` is the root, `[0, 2]` the root's first child's third
child), so every subtree is reported even beneath two children named alike or not named at all, and
a child named `#3` or `a/b` is never mistaken for an index or a second level. `names` along the
path is for display only. A `Topology` that exists is valid: its constructor,
`copy` and its decoder all throw `TopologyException` on the same problems. Mark a group with
`runtimeChildren` when the app creates its children while running; their names are opaque to
heddle. Renaming or moving a child is a delete plus an add, and the new child starts with no
fairness history. `Topology.lower(generation)` turns the checked tree into the `AttachmentRecord`s
a ledger is prepared with, sorted, under ids that are the same on every target and never collide
however the names are spelt: `root/acme/batch@1:1#0` is `batch` in `acme`, at weight 1,
generation 0, and `groupId("acme", "batch")` names the group. On a reload,
`Topology.diff(new, ledger, generation)` lists the `TopologyStep`s from what the ledger holds to
`new`, reading which edges exist from the ledger rather than from the old tree, so it works after
a restart, retires what an earlier reload left draining, and after a partial apply lists exactly
the rest; a new weight is a new edge, so the group's fairness history restarts at its parent's
front. You do not apply the steps yourself: `LocalHeddle.apply(new, generation)` diffs and applies
them in one call, under your lock, against the ledger it diffed (see
[A fair-share tree in one process](#a-fair-share-tree-in-one-process)). The list is what it runs,
and what a preview would show.

Children the app creates while running go under a group marked `runtimeChildren`. On an engine,
`LocalHeddle.attachRuntimeChild(group, name, generation)` and `detachRuntimeChild(group, name)`
attach and detach one child, at the marker's weight, under a name of any spelling that never
collides with a declared child; a detach drains like a removal. `Topology.attachRuntimeChild` and
`detachRuntimeChild` list the same steps without applying them. A reload that keeps the marker
keeps the child and its history; one that drops the marker closes it. A detach's own swap forgets
the child's retired edge once its books close, so detach a child whenever it goes idle.
A runtime child is always a leaf: it cannot be marked in turn, so a tree you discover while running
is one level deep under its marked group. For two levels, such as a repository and then its
authors, declare each repository as a group marked `runtimeChildren`, and `LocalHeddle.apply` a
new topology as each repository appears; its authors are then runtime children. Letting a runtime
child have runtime children is tracked by #3314.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleRuntimeChildren -->
```kotlin
    // Named like the declared lane `x`, and still its own child: runtime names have their own namespace.
    val tenant = pool.runtimeChildId(listOf("tenants"), "x")
    check(tenant != pool.groupId("tenants", "x"))
    val attach = pool.attachRuntimeChild(listOf("tenants"), "x", ledger, generation = 0)
    check(attach.map { it::class } == listOf(TopologyStep.Prepare::class, TopologyStep.Activate::class))
    check((attach.first() as TopologyStep.Prepare).record.weight == Weight.of(2))
    apply(attach)
    // Asking again is harmless, and a reload that keeps the marker keeps the child.
    check(pool.attachRuntimeChild(listOf("tenants"), "x", ledger, generation = 0).isEmpty())
    check(Topology.diff(pool, ledger, generation = 0).isEmpty())
```

To turn your own config paths into ledger ids and back, without keeping a name map that has to
track heddle's escaping, use `Topology.findGroupId(GroupPath)` and `Topology.findPath(GroupId)`.
Neither throws, and they are exact inverses: each returns `null` for anything this topology has no
group for, including an id a reload has since removed. A `GroupPath` is `Declared(names)` or
`RuntimeChild(group, name)`, so a runtime child stays apart from a declared sibling of the same
name, and its `names` read either arm back from just below the root.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleGroupPathRoundTrip -->
```kotlin
    // A declared lane and a runtime child share the name `x`, and their paths stay apart.
    val lane = GroupPath.Declared(listOf("tenants", "x"))
    val tenant = GroupPath.RuntimeChild(group = listOf("tenants"), name = "x")
    val laneId = checkNotNull(pool.findGroupId(lane))
    val tenantId = checkNotNull(pool.findGroupId(tenant))
    check(laneId == pool.groupId("tenants", "x") && tenantId == pool.runtimeChildId(listOf("tenants"), "x"))
    check(laneId != tenantId)

    // Back from an id: the arm says whether the last name is a runtime child's.
    check(pool.findPath(laneId) == lane && pool.findPath(tenantId) == tenant)
    check(pool.findPath(tenantId)?.names == listOf("tenants", "x"))
```

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyFromDocument -->
```kotlin
    // As decoded from the file: names and weight text, nothing checked yet.
    val written = TopologyDocument(
        root = "pool",
        children = listOf(
            TopologyDocument.Group(
                name = "acme",
                weight = "3",
                children = listOf(
                    TopologyDocument.Lane("interactive", weight = "3/2"),
                    TopologyDocument.Lane("batch"),
                ),
            ),
            TopologyDocument.Group(name = "tenants", runtimeChildren = "1"),
        ),
    )
    val parsed = Topology.from(written)
    check(parsed is TopologyParse.Valid)
    check(parsed.topology.children.first().weight == Weight.of(3))

    // A typo is reported, not thrown: the second child of `acme` reuses a name.
    val typo = written.copy(
        children = listOf(
            TopologyDocument.Group("acme", children = listOf(TopologyDocument.Lane("batch"), TopologyDocument.Lane("batch"))),
        ),
    )
    val problem = (Topology.from(typo) as TopologyParse.Invalid).problems.single()
    // Addressed by child indices: the root's first child, then that child's second.
    check(problem.path == listOf(0, 1) && problem.names == listOf("pool", "acme", "batch"))
    check(problem.kind == TopologyProblem.Kind.DUPLICATE_NAME)
```

When the same file carries your app's own settings beside the tree, such as rules per lane, decode
it **once**, strictly, into a type of your own that is heddle's shape plus your fields, and build the
`TopologyDocument` from it through its public constructors. Check your own fields however you like
and report what is wrong as `TopologyProblem`s of kind `CONSUMER`, addressed by the same child-index
`path`, so they sit in one list with what `Topology.from` found; heddle never reports that kind
itself. Do not decode the file twice instead, once into `TopologyDocument` and once into your own
type. The decode into heddle's type has to ignore your keys, so it ignores a misspelt `weigth` too,
and that lane quietly gets the default weight `"1"`.

The type is heddle's shape plus your fields. Here `PoolGroup` is the superset of a group (its
sibling `PoolLane` is the same for a lane, and `PoolFile` holds the root and the children):

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#PoolGroup -->
```kotlin
@Serializable
@SerialName("group")
internal data class PoolGroup(
    override val name: String,
    override val weight: String = "1",
    val children: List<PoolNode> = emptyList(),
    val runtimeChildren: String? = null,
    override val rules: List<String> = emptyList(),
) : PoolNode
```

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleConsumerProblems -->
```kotlin
    // One decode, under a default `Json`: a key neither heddle nor the app knows is an error.
    val file = Json.decodeFromString<PoolFile>(text)

    fun PoolNode.document(): TopologyDocument.Node = when (this) {
        is PoolGroup -> TopologyDocument.Group(name, weight, children.map { it.document() }, runtimeChildren)
        is PoolLane -> TopologyDocument.Lane(name, weight)
    }
    val document = TopologyDocument(file.root, file.children.map { it.document() })

    // The app's own check, addressed the same way: child indices from the root, in written order.
    fun MutableList<TopologyProblem>.checkRules(nodes: List<PoolNode>, path: List<Int>, names: List<String>) {
        nodes.forEachIndexed { index, node ->
            node.rules.filter { it !in listOf("nightly", "weekly") }.forEach { rule ->
                add(TopologyProblem(path + index, names + node.name, TopologyProblem.Kind.CONSUMER, "no rule is called \"$rule\""))
            }
            if (node is PoolGroup) checkRules(node.children, path + index, names + node.name)
        }
    }
    val ours = buildList { checkRules(file.children, emptyList(), listOf(file.root)) }
    val heddles = (Topology.from(document) as? TopologyParse.Invalid)?.problems.orEmpty()
    val problems = heddles + ours
    // One list, one addressing: the second `batch` is both a duplicate and has an unknown rule.
    check(problems.map { it.path to it.kind } == listOf(listOf(0, 1) to TopologyProblem.Kind.DUPLICATE_NAME, listOf(0, 1) to TopologyProblem.Kind.CONSUMER))

    // Why one strict decode and not two lenient ones: to decode the same file into heddle's type,
    // the app's `rules` key must be ignored, and so is a misspelt `weigth`, which then reads as "1".
    val typo = """{"root": "pool", "children": [{"type": "lane", "name": "batch", "weigth": "3"}]}"""
    check(Json { ignoreUnknownKeys = true }.decodeFromString<TopologyDocument>(typo).children.single().weight == "1")
    val refused = try {
        Json.decodeFromString<PoolFile>(typo)
        null
    } catch (e: SerializationException) {
        e
    }
    check(refused != null)
```

To write a fixed tree in code instead, use `draft(root) { group(name, weight) { … } }`. It builds
through the same check and throws `TopologyException` with the problems `from` would list.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyDraft -->
```kotlin
    val fairness = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive", Weight.of(3, 2))
            lane("batch")
        }
        group("tenants") { runtimeChildren() }
    }
```

To schedule work against the tree in one process, `LocalHeddle` (`:kuilt-heddle-core`) hands out
one **lease** per free slot. When a slot frees, `acquire(waiting, eligible, startedAt)` names the
leaf whose work runs next and reserves an estimated cost for it; when the work ends,
`complete(lease, actualCost)` charges what it really cost, hands the unused part back, and reports
any overrun, which is never refused. Cost is time, in your own unit. `waiting` is every leaf with
work queued and `eligible` the ones this slot can run. A waiting leaf that cannot run on this slot
keeps its claim, so it is not treated as returning from idle when a slot it can run comes up. Work
no pick could reach counts as idle, even when you list it as waiting: a leaf behind an edge a
reload is closing, or a group that has gained a child. Its subtree builds up no credit while it is
cut off, so when it can be reached again it starts level with its siblings. A new leaf starts level
with its siblings too, rather than owed their whole history. `extend` reserves
more for a lease that runs long, `cancel` charges nothing, `admit(leaf, startedAt)` charges work
another scheduler already started (`admit(leaf, waiting, startedAt)` also observes the waiting set
as the equivalent `acquire` would, so a sibling returning from idle is clamped), and `previewPicks`
answers "who goes next" without changing anything. Give it each leaf's queue of job estimates, head
first, and each pick does what your loop would: `acquire` at the slot's reservation, then `extend`
to the winner's head when that is larger. `startedAt` is stored on the lease and never read by a
pick. A pick only goes down a
subtree it can finish in, so a group whose only leaf sits behind a closing edge never takes a slot
from a sibling that can run. A refusal is a typed outcome (`NothingEligible`, `SupplyExhausted`,
`NoLiveLineage`, `PositionCeiling`, `PathDraining`, `Unknown`, `Stale`), never an exception: an
`extend` on a lease whose path a reload is closing is `PathDraining`, and its `complete` still
settles. A bad argument throws `IllegalArgumentException`, such as a reservation above
`LocalHeddle.MAX_RESERVATION` (2^44 cost units). Each child's progress is a `Long` count of
sixteenths of a unit, with a ceiling. A pick rebases a busy parent's children long before it, all
by the same virtual time, so no pick changes. A parent still reaches the ceiling while a demanding
child sits at zero and is never served, when it is only ever charged through `admit`, `extend` or
an overrun and never picked at, or once an edge's lifetime returned service (reserved but not
charged) passes about `5.8 × 10¹⁷ / w.denominator` units (#3360). A workload that charges what it
reserves never meets the last. At the ceiling, `acquire` and `extend` return
`PositionCeiling(parent)` and change nothing, while `admit` and `complete` still charge. `IllegalStateException` is left only for a conflicted ledger, such as two live edges into
one group, a split record or a cycle, which the public surface cannot reach today. The
engine reads no clock and no random source, and it is not thread-safe: call every method under your
scheduling lock, `snapshot()` included, and hand status and persistence readers the immutable value
it returns.

The engine mints its own supply. Call `reconcileCapacity(totalSlots)` with how many slots you run,
at start and whenever that changes. It mints nothing itself; it sizes the watermarks of
`LocalPolicy.supply` (`SupplyPolicy`, counted in rounds of every slot leased once at the default
reservation). From then on `acquire` and `extend` top the root up inside the same operation whenever
it would drop below the low watermark, so supply never runs out between calls, and a refused
operation leaves no mint behind. `SupplyExhausted` now means no capacity was reconciled. The low
watermark is also the headroom for an overrun or an `admit`, which are never refused and mint for
themselves past it. Every mint in an epoch rewrites one record, so the ledger never grows with
service.

Start an engine with `LocalHeddle.fromTopology(topology, policy, epoch)`. A lease only ever ends at
a lane, or at a runtime child of a marked group: a group that happens to have nothing attached,
such as a marked group before its first runtime child, is never leased, and `admit` and `explain`
treat it as `NoLiveLineage`.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalLease -->
```kotlin
    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // Over many slots the shares follow the weights, 2 : 1.
    val picks = (0 until 30).map {
        val lease = (heddle.acquire(queued, queued, startedAt = it.toLong()) as AcquireOutcome.Acquired).lease
        heddle.complete(lease.id, actualCost = 3L)
        lease.leaf
    }
    check(picks.count { it == heavy } in 19..21)
```

To show someone why their work is waiting, `explain(leaf, waiting, eligible)` gives one
`WaitExplanation`: `NotWaiting`, `NotEligible`, `NoLiveLineage(at)` (no pick can end there, the
same test `acquire` applies), `AtCeiling(group, limit)` (see the ceiling below), `SupplyExhausted`,
`PositionCeiling(parent)`, `BehindAtLevel(parent, nextChild, …)` at the first
group where another child goes first, or `WouldBePickedNext`. It runs the real pick on a throwaway
copy, wake memory included, so it agrees with the next `acquire` on the same arguments and changes
nothing. `BehindAtLevel` carries the leaf side's virtual service beside two lines: `eligibleFront`,
the line this pick drew over the children it could run (more than one sixteenth of a unit of the
side's own service above it, the side was not a candidate; at or below, it lost on its deadline), and `front`, the share line over everyone waiting (above
it, the side has had more than its share). Log `kind`, a stable ordered enum, and the group ids,
not the rationals, which move every pick. `entitlement(leaf, waiting, totalSlots)` is the leaf's fair share of the slots, as an exact
fraction, over the same competing set: work no pick can reach is owed nothing and takes nothing.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleExplainWait -->
```kotlin
    val queued = setOf(heavy, light)
    // Light is behind at the pool: at twice the weight, heavy's turn comes due sooner.
    val why = heddle.explain(light, waiting = queued, eligible = queued)
    check(why is WaitExplanation.BehindAtLevel && why.parent == root && why.nextChild == heavy)
    check(heddle.explain(heavy, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
    check((heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == heavy)

    // While both stay queued, heavy is owed 2 slots of every 3 and light 1.
    check(heddle.entitlement(heavy, waiting = queued, totalSlots = 3L) == Rational.of(2L))
    check(heddle.entitlement(light, waiting = queued, totalSlots = 3L) == Rational.ONE)
```

To run no more than N of one part of the tree at once, however idle the rest is, do not count leases
in your own loop beside the engine: set `maxConcurrent` on that group, lane or runtime marker (`0`
pauses it). A pick skips every leaf beneath a node whose open leases have reached it, draining ones
included, so the slot goes to a sibling, and the capped node counts as idle at its parent: it banks
no credit and wakes clamped. `explain` tells such a leaf `AtCeiling(group, limit)`, naming the
capped node nearest the root, before any supply check; `entitlement` owes it nothing; `previewPicks`
counts the leases it opens against the ceiling; and `acquire` returns `NothingEligible` when every
eligible leaf is capped. `admit`, `extend` and `complete` are never refused, and lowering a ceiling
preempts nothing. A gate in your loop could do none of the reporting.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleConcurrencyCeiling -->
```kotlin
    val tree = draft("pool") {
        lane("deploys", Weight.of(3), maxConcurrent = 1)
        lane("builds")
    }
```

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleConcurrencyCeiling -->
```kotlin
    val deploy = (heddle.acquire(queued, queued, startedAt = 0L) as AcquireOutcome.Acquired).lease
    check(deploy.leaf == deploys)
    // Deploys weigh three times as much, but one is already running, its ceiling.
    check(heddle.explain(deploys, waiting = queued, eligible = queued) == WaitExplanation.AtCeiling(deploys, limit = 1L))
    check(heddle.entitlement(builds, waiting = queued, totalSlots = 4L) == Rational.of(4L))
    check((heddle.acquire(queued, queued, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf == builds)
```

After a crash or a restart of your executors, `reconcileLeases(live, now)` compares the leases the
engine holds open with the ones whose work you still have running. It reports both directions and
changes nothing. `orphaned` lists each open lease you did not name, with `elapsed`, which is
`now − startedAt` and never below zero if your clock went back. `unknown` lists the ids you named
that the engine holds no lease for. Which orphans to settle, and at what cost, is yours to decide.
The documented default is `complete(lease, orphan.defaultSettlement)`: the time it ran, capped at
`granted`, its reservation plus extensions, because a dead job's overrun cannot be checked. Work
that will resume belongs in `live`, so it never becomes an orphan.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleReconcileLeases -->
```kotlin
    // The executor came back running only `kept`, and one job the engine never leased.
    val stranger = LeaseId(epoch = 7L, seq = 0L)
    val found = heddle.reconcileLeases(live = setOf(kept.id, stranger), now = 110L)
    check(found.unknown == listOf(stranger))
    val orphan = found.orphaned.single()
    check(orphan.lease == lost && orphan.elapsed == 9L && orphan.granted == 3L)

    // It ran 9 units against the 3 it was granted, which a dead job cannot prove: charge 3.
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(heddle.reconcileLeases(live = setOf(kept.id), now = 110L).orphaned.isEmpty())
```

To change the tree while work runs, call `apply(newTopology, generation)` under the same lock.
It diffs and applies the whole reload as one unit, swapped in once, so nothing is leased against
a half-applied tree. Open leases survive it and are still charged once, at `complete`. A reweight
gives the group a new edge and moves the leases open under it onto that edge, keeping their ids.
**The reweighted group's fairness history resets**: it restarts at its parent's front, neither
owed for its past nor charged for it. A removal closes the group at once and retires it when its
leases finish. Until then `ApplyResult.refusals` holds a `StepRefusal.Draining` naming the edge
and the blocking leases, and every other child's change still lands. Apply the same topology
again later and only what is left runs. A removal under a reweighted group wins: the reweight
waits too, and changes nothing until those leases finish. Settle a dead job's lease through
`reconcileLeases` so it never holds a reload up. **A rename or a move is a delete plus an add**:
the new group starts with no history, and the old one drains under its old name.
`attachRuntimeChild` and `detachRuntimeChild` run the same way. Re-attaching a child whose detach
is still draining moves its leases onto the new edge.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalReload -->
```kotlin
    // While both leases are open, heavy goes to weight 3 and spare is removed.
    val after = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(3)), Topology.Lane("light")))
    val reload = heddle.apply(after, generation = 1L)

    // Spare is closed to new work and waits for its lease; heavy's lease moved onto its new edge.
    val waiting = reload.refusals.single()
    check(waiting is StepRefusal.Draining && waiting.child == spare && waiting.blockingLeases == listOf(leftover))
    check(heddle.complete(running.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(heddle.complete(leftover.id, actualCost = 2L) == LeaseOutcome.Done(charged = 2L, overrun = 0L))

    // Once the drain is over, the same reload finishes the job.
    check(heddle.apply(after, generation = 1L).refusals.isEmpty())
```

To survive a restart, call `persist()` under your scheduling lock and store the
`LocalHeddleSnapshot` it returns, encoded as JSON or CBOR; encoding and writing can happen off
the lock. The encoding is a two-element array, `[formatVersion, state]`, so a store that reorders
object keys, such as Postgres `jsonb`, cannot scramble it. On the way back, `LocalHeddle.decode(bytes, format)` (or `decode(text, format)` for a text
format) returns `Restored(snapshot)`, `UnsupportedVersion(found, supported)` or `Corrupt(reason)`,
never an exception. Then `LocalHeddle.restore(snapshot, policy, epoch)` carries on where the old
engine stopped: every open lease is valid again under its old id, with its path and its
`startedAt`, and the wake memory, the reconciled capacity and the topology last applied come
back, so the next picks and top-ups are the ones the old engine would have made, over the same
leasable groups. The epoch must be above the snapshot's.
Treat it as a boot counter you persist and only ever raise, since lease ids count from zero in
each epoch; an old id that is no longer open answers `Stale`. Each release reads its own format
and the one before it (`LocalHeddleSnapshot.SUPPORTED_FORMAT_VERSIONS`), so stored state survives
one upgrade. After a restore, settle the work that did not survive with `reconcileLeases`.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalPersistence -->
```kotlin
    // Under your scheduling lock: take the snapshot. Encoding and storing it can happen off the lock.
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // After the restart: decode, then restore under a later epoch, your persisted boot counter.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val restarted = LocalHeddle.restore(decoded.snapshot, policy, epoch = decoded.snapshot.epoch + 1)

    // The lease that was running is still open, and settles as it would have.
    check(restarted.complete(running.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
```

Four persistence gotchas the sample is too short to show:

- **`snapshot.epoch + 1` is the sample's shorthand, not the pattern.** Restoring one snapshot
  twice under the same epoch (a crash straight after the first restore, say) gives two engines
  that number leases alike, so a job from the first run can settle the second's lease. Keep a
  boot counter in storage and raise it on every start; `restore` throws
  `IllegalArgumentException` only when the epoch is not above the snapshot's.
- **Formats.** `:kuilt-heddle-core` brings no format: add `kotlinx-serialization-json` or
  `kotlinx-serialization-cbor`. Both are tested, CBOR's definite- and indefinite-length arrays
  included. **Not ProtoBuf**: it encodes a snapshot, but `decode` answers `Corrupt`, since it
  cannot read back the ledger's maps whose values are maps. `FORMAT_VERSION` is what this
  release writes and `SUPPORTED_FORMAT_VERSIONS` what it reads: format 2 today, reading 1 and 2,
  so a snapshot v0.7.4 stored as format 1 still restores.
- **What `decode` checks.** The envelope's shape and version, then consistency: every open lease
  has a positive reservation, an epoch no later than the snapshot's and a path that is its leaf's
  one live lineage from the root, over edges the ledger holds and none retired; leases in id order;
  a valid topology. It does **not** bound values an engine could never reach, such as a
  reservation above `MAX_RESERVATION`, which decodes as `Restored`
  (tracked by [#3261](https://github.com/tractat-us/kuilt/issues/3261)). Decode only bytes your
  own program persisted.
- **What is left draining stays draining.** The snapshot keeps the topology last applied, so after
  the old leases settle, `apply` the same topology again to land a removal that was still waiting.
  The `LocalPolicy` is not stored: pass the one the old engine ran with.

A reader that holds only the stored snapshot, such as an admin screen in another process, does not
need an engine to ask who goes next. `snapshot.view(policy)` returns a `LocalHeddleView` with
`previewPicks`, `explain` and `entitlement`, and it answers exactly as the engine that took the
snapshot would have: the engine's own methods are that view over its live state. Do not restore a
throwaway engine under `epoch + 1` for each question. The policy is required, since a snapshot does
not keep one; a policy that cannot size the snapshot's `totalSlots` throws, as `restore` does.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleSnapshotView -->
```kotlin
    // On the admin screen: decode the stored snapshot and view it with the engine's policy.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val view = decoded.snapshot.view(policy)

    val queued = setOf(heavy, light)
    check(view.previewPicks(mapOf(heavy to 2, light to 1), eligible = queued, reservations = List(3) { 3L }) == listOf(heavy, light, heavy))
    check(view.explain(heavy, waiting = queued, eligible = queued) == WaitExplanation.WouldBePickedNext)
    check(view.entitlement(light, waiting = queued, totalSlots = 3L) == Rational.ONE)
```

Two fairness gaps are known, both in `LocalHeddle` and both smallest when a lease
settles at its estimate:

- **An estimate's size leans share.** A lease counts at its reservation until `complete`, so a lane
  that under-reserves looks cheaper while it runs and gains share; one that over-reserves loses it.
  Most callers send `defaultReservation`, so its size picks the side. Aim it at a typical job, and
  pass a per-lease `reservation` when history gives a better one. This is tracked by
  [#3239](https://github.com/tractat-us/kuilt/issues/3239).
- **A lease that straddles a wake hands idle credit back.** A lane with a lease still running but
  nothing in `waiting` is observed idle, so its next demand is a wake and is clamped to the front,
  with that lease counted at its full reservation. When the lease then completes under its
  reservation, the remainder walks the lane's virtual time back below the clamp; a `cancel` walks
  back all of it. The credit is at most `reserved / weight` per such lease, and only a lane whose
  queue empties while its own work still runs can earn it. `sleeperCredit = 0` does not prevent it.
  This is tracked by [#3238](https://github.com/tractat-us/kuilt/issues/3238). `HeddleNode` does not take
  this path: its `complete` keeps an unused earmark at the leaf.

### Run it over a network — reserve, run, charge once

**Intent:** put the fair-share tally on a live connection between peers — advertise appetite,
allocate entitlement down a tree, then reserve a slot before running work and charge it
exactly once on completion (even if `complete` is called twice).
**Primitive:** `HeddleNode` via `heddleStatic(...)` (`:kuilt-heddle`) — hands you the tally,
the demand board, reservations, and liveness over a `Seam` from a fixed roster. Every peer
that bootstraps with the same root, mint, and topology begins from an identical ledger and
stays in step over the wire on its own.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleNode -->
```kotlin
    // The leaf wants work; one scheduling round delegates entitlement down toward it.
    node.advertise(e.id, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root)

    // Leaf work reserves a slice, runs, then completes — completing twice charges once.
    val outcome = node.reserve(leaf, maximumCost = 10L)
    if (outcome is ReserveOutcome.Reserved) {
        node.complete(outcome.id, actualCost = 7L)
        node.complete(outcome.id, actualCost = 7L) // idempotent no-op
    }
```

### Creating quota or reshaping the tree at runtime (with agreement)

**Intent:** mint new entitlement or re-parent a group *while the system runs*, and need
everyone to agree on the order so a split-brain can't double-mint and two overlapping
reshapes don't corrupt the tree.
**Primitive:** `heddleGoverned(...)` → `GovernedHeddleNode` (`:kuilt-heddle`) — the same data
plane as `heddleStatic`, but `mint`/`prepare`/`activate`/`close`/`retire` are serialized
through `:kuilt-raft`; each returns a `ControlOutcome` (`Applied`, `Conflict` with the
structured reason when it loses a race, or `Indeterminate` when this peer halted with the call in
flight, so the act may still commit and a blind retry can double-mint). If a gossip-lagged peer's `retire` races a delegate
and strands budget on a since-reparented child, `reconcile(child)` re-homes it — net inflow
*and* any service already charged through it — onto the child's live lineage through the log,
conservingly (mints nothing), clearing the resulting
`PersistentNegativeHoldings`/`PerEdgeSafety`/`ClosureViolation`. **It sends no magnitudes:** it
opens a `quiesce(edge)` barrier over each retired inbound edge, every peer promises never to
write that edge again and acks its own final values, and the move is *derived at apply time*
from those recorded promises — so a lagged or deposed proposer cannot commit a wrong amount.
Expect to call it twice: the acks are separate committed acts, so the first call is usually
refused naming the peers it waits on (`pendingAcks(edge)` reads that set). A **transfer-tangled
strand is re-homed with its hand-offs**, not refused: the three terms of a pocket — net inflow,
already-charged service, and the transfer rows — travel together, so a recipient who merely holds
handed-off credit keeps it across the move. It still refuses when the arithmetic or the fence says
so: a replica net-negative on the strand, fenced edges that together cannot cover what was charged
through them, a cross-parent re-home, or a carried hand-off whose donor is no longer on the roster
to ack it. And it **blocks while any enrolled peer is down** — that peer is
exactly the one that may hold an unreplicated reservation, so the wait is the safety property,
not a bug. `enroll(replica)`/`depart()` keep the **agreed participant list** the barrier
quantifies over (`enrolledReplicas()` reads it back); only a peer may depart itself, and
**`enroll(self)` is what opens a node's write gate** — until it applies, `reserve` and
`schedule` answer `GateClosed` (with the replica and a `Reason`), which is a separate arm from
`ReserveOutcome.NoHoldings` and `ScheduleOutcome.NothingToDelegate`, so a node that never
enrolled can no longer pass for an idle one. **A peer that cannot read a log entry stops there**:
`GateClosed.Reason.UnreadableControlEntry` names the log index and format version, `enroll` is
refused, and a restart stops at the same entry, so it takes a build that can read the entry or a
fresh log. The same bytes stop every peer on that build, and one peer on a newer build stops older
ones, with no way to skip. That includes bytes heddle never wrote, so give the control plane a Raft
node of its own, with nothing else proposing into it and on the default `ClientIdentity.Auto` (the
plane replays from index 1 and restarts its serials every boot), and upgrade a cluster together.
**A `timeout` or cancel stops only the wait**: the act can still apply, and an `enroll`/`depart` that
does moves the gate then, so read before resubmitting and never re-issue one to repair the gate. A durable log
from v0.7.3 stops at its first `Mint` or `Prepare`: start it fresh, as there is no migrator. The spend path (`schedule`/`reserve`/`complete`) never touches the log.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleGoverned -->
```kotlin
    // Before enrolling, the write gate is closed and both spend verbs say so rather than looking idle.
    check(node.schedule(root) is GateClosed)

    // Enrolling self is what opens this node's write gate: until it applies, `reserve` and `schedule`
    // answer GateClosed, so an unenrolled peer can never author entitlement (#1693, #1892).
    check(node.enroll(self) is ControlOutcome.Applied)

    // Mint and reshape are serialized through the Raft log — each returns a structured outcome.
    check(node.mint(self, 100L) is ControlOutcome.Applied)
    node.prepare(AttachmentRecord(edge, root, leaf, Weight.ONE))
    node.activate(edge)

    // The spend path is coordination-free — it issues no consensus messages.
    node.advertise(edge, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
    node.schedule(root) // Delegated, or NothingToDelegate if no holdings can move
    when (val outcome = node.reserve(leaf, maximumCost = 10L)) {
        is ReserveOutcome.Reserved -> node.complete(outcome.id, actualCost = 7L)
        ReserveOutcome.NoHoldings -> Unit // lane exhausted — try again once entitlement flows in
        is GateClosed -> error("enrolled above, yet the write gate is closed: ${outcome.reason}")
    }
```

### Weighted lanes over a warp workload

**Intent:** you already run tasks across peers with `:kuilt-warp` and want to say
"interactive work gets 3× the pool that batch work does" — without changing how warp picks
who runs what.
**Primitive:** `HeddleAdmissionControl(heddle)` plugged into `WarpNode`'s admission gate,
plus `TaskDescriptor.inLane("...")` on the producer side (`:kuilt-warp-heddle`). A lane maps
to a fair-share leaf; the task reserves that leaf's entitlement before it runs and is charged
on completion. Out of entitlement ⇒ the task **defers** (never dropped). An untagged task
rides the root lane and is admitted for free — warp's fast path stays exactly as cheap. It takes
either node (`FairShareExecution`); a governed node's `GateClosed` defers too, logged with its
reason. A lane is charged **per execution**, so a task two peers both run under churn costs twice.

<!-- verbatim from kuilt-warp-heddle/src/commonSamples/kotlin/us/tractat/kuilt/warp/heddle/WarpHeddleSamples.kt#sampleHeddleAdmissionControl -->
```kotlin
    // 1. Build the adapter — warp's opaque AdmissionControl, backed by the fair-share ledger.
    val admission = HeddleAdmissionControl(heddle)
    // Pass it to a node:  WarpNode(selfId, seam, roster, scope, clock = …, registry = …,
    //                              admissionControl = admission, epoch = <per-boot counter>)

    // 2. Tag a task into a lane on the producer side.
    val interactive: TaskDescriptor =
        TaskDescriptor(op = OpId("score"), args = "doc-1".encodeToByteArray())
            .inLane("acme/interactive")
    check(interactive.lane == Lane("acme/interactive"))

    // 3. An untagged task rides the default root lane and is admitted un-gated.
    val untagged = TaskDescriptor(op = OpId("score"), args = ByteArray(0))
    check(untagged.lane == Lane.ROOT)
    check(admission.admit(untagged) === AdmissionTicket.NOOP)
```
