# Heddle

A build farm has four agents and two teams. Web paid for three parts of it, mobile for one.
Some hours mobile has nothing queued, and web should get the idle agents; when mobile comes
back it should get its share again, without being owed for the hours it sat out. Every build
should be charged once, for what it really cost.

That is a fair-share scheduler, and heddle ships two of them. One runs inside a single
program and needs no network. The other runs across peers with no referee, keeps working
while the network is split, and still charges every use exactly once. Don't hand-roll a
weighted round-robin, a quota counter, a "reserve then charge" bookkeeper, a config
validator for the tree, or a "why is my job waiting" screen: each has a section below.

## Which engine: one process or many peers

**Intent:** pick the right heddle entry point before writing any scheduling code.
**Primitive:** one of three, by where the scheduling decision is made.

| Your situation | Use | Module |
|---|---|---|
| One program hands out slots, workers or turns, and keeps the books | `LocalHeddle` | `:kuilt-heddle-core` |
| Several peers share one budget over a `Seam`, and no peer is in charge | `HeddleNode` (`heddleStatic`, or `heddleGoverned` to mint and reshape with agreement) | `:kuilt-heddle` |
| You already have your own ledger and loop, and want only the pick | `HeddlePolicy.pick`, `FairShareAllocator` | `:kuilt-heddle-core` |

Most consumers want `LocalHeddle`: every section from here to
[Refusals and limits](#refusals-and-limits) is about it. It needs no `Seam`, no Raft and no
coroutines, and the whole tree, its leases and its history fit in one snapshot.

## A fair-share queue in one process

**Intent:** when a slot frees, decide whose work runs in it so that over time each group gets
the share its weight promises; then charge what the work really cost.
**Primitive:** `LocalHeddle` (`:kuilt-heddle-core`). Start one with
`LocalHeddle.fromTopology(topology, policy, epoch)`, which returns the engine with what booting
it did (`val (heddle, booted) = …`; `booted.refusals` is empty when the whole tree booted), and tell it how many slots you run with
`reconcileCapacity(totalSlots)`. Each free slot is one **lease**:
`acquire(waiting, eligible, startedAt)` names the leaf whose work runs next and reserves an
estimated cost for it, and `complete(lease, actualCost)` charges what it really cost, hands the
unused part back, and reports any overrun, which is never refused.

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

The whole loop in one place, from the admin's file to a restart:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleFairShareQueue -->
```kotlin
    // The admin's file: web gets three parts to mobile's one, and forks are attached as they open.
    val text = """
        {"root": "farm", "children": [
          {"type": "lane", "name": "web", "weight": "3"},
          {"type": "lane", "name": "mobile"},
          {"type": "group", "name": "forks", "template": {"maxConcurrent": 1}}
        ]}
    """
    val parsed = Topology.from(Json.decodeFromString<TopologyDocument>(text))
    // Invalid would carry every problem in the file, each with its address, for the admin to fix.
    check(parsed is TopologyParse.Valid)
    val farm = parsed.topology
    val policy = LocalPolicy(defaultReservation = 3L)
    // The epoch is a boot counter you persist and raise on every start.
    val (heddle, booted) = LocalHeddle.fromTopology(farm, policy, epoch = 0L)
    check(booted.refusals.isEmpty())
    heddle.reconcileCapacity(totalSlots = 4L)

    // A contributor opens a fork while the program runs.
    check(heddle.attach(listOf("forks"), "ana", generation = 1L).refusals.isEmpty())
    val web = farm.groupId("web")
    val mobile = farm.groupId("mobile")
    val ana = farm.attachedChildId(listOf("forks"), "ana")

    // Each free slot: lease it, run the work, charge what it really cost.
    val queued = setOf(web, mobile, ana)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 0L)
    check(first is AcquireOutcome.Acquired)
    check(heddle.complete(first.lease.id, actualCost = 4L) is LeaseOutcome.Done)
    val running = (heddle.acquire(queued, queued, startedAt = 4L) as AcquireOutcome.Acquired).lease
    val lost = (heddle.acquire(queued, queued, startedAt = 4L) as AcquireOutcome.Acquired).lease

    // Under the scheduling lock, persist. Encode and store the text exactly as it comes.
    val stored = Json.encodeToString(LocalHeddleSnapshot.serializer(), heddle.persist())

    // After the restart: decode, restore under the raised counter, and settle what did not survive.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val restored = LocalHeddle.restore(decoded.snapshot, policy, epoch = 1L)
    val orphan = restored.reconcileLeases(live = setOf(running.id), now = 10L).orphaned.single()
    check(orphan.lease == lost)
    check(restored.complete(orphan.lease.id, orphan.defaultSettlement) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
    check(restored.complete(running.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))
```

Cost is time, in your own unit. `LocalPolicy.defaultReservation` is the estimate a lease
reserves when you pass none; pass a per-lease `reservation` when you know better. `extend`
reserves more for a lease that runs long, and `cancel` closes one and charges nothing.
`startedAt` is stored on the lease for your own reconciling and never read by a pick.

**Waiting and eligible are different sets on purpose.** `waiting` is every leaf with work
queued; `eligible` is the ones this particular slot can run, say the Mac builds on a Mac agent.
Only an eligible leaf is picked, but a waiting one that cannot run here keeps its claim, so it is
not treated as returning from idle when a slot it can run comes up. A leaf with nothing queued
is idle: it banks no credit, and when it comes back it is as far ahead of or behind its siblings
as it was when it left, moved by how far its parent's net-service clock ran meanwhile, give or
take a sibling's refund in that time ([#3239](https://github.com/tractat-us/kuilt/issues/3239)),
rather than owed the slots it could not have used. A child this engine never saw stop is clamped
level with its siblings instead. A new leaf starts level with its siblings. Work no
pick could reach counts as idle even when you list it as waiting: a leaf behind an edge a reload
is closing, or a group that has gained a child.

**Supply is the engine's own.** Until `reconcileCapacity` is called it mints nothing ahead, so
`acquire` and `extend` answer `SupplyExhausted`. `complete` and `admit` are never refused and
mint any shortfall at the root. Once capacity is reconciled, `acquire` and `extend` top the root
up inside the same operation whenever it would drop below the low watermark of
`LocalPolicy.supply` (a `SupplyPolicy`, counted in rounds of every slot leased once at the
default reservation). The low watermark is also the headroom an overrun or an `admit` mints
past. Call `reconcileCapacity` again whenever your slot count changes. Every mint of an epoch
rewrites one record, so the ledger never grows with service.

**A lease ends only at a lane**, or at an attached child of a group with a template. A group
that happens to have nothing attached right now is never leased, and `admit` and `explain`
treat it as `NoLiveLineage`.

**One writer.** The engine reads no clock and no random source, so a replay of the same calls
gives the same picks on every target. It is not thread-safe: call every method under your
scheduling lock, `snapshot()` and `persist()` included, and hand status readers the immutable
value they return. Every call that changes the engine is all or nothing.

## Loading the tree from a config file

**Intent:** load the fair-share tree — groups, lanes and their weights — from a file an admin
edits, and show every mistake in it at once, each beside the line it belongs to, instead of
failing on the first.
**Primitive:** `Topology.from(TopologyDocument)` → `TopologyParse` (`:kuilt-heddle-core`).
Decode the file into a `TopologyDocument` in whatever format you already read, with weights as
text (`"3"`, `"3/2"`). `from` returns `TopologyParse.Valid` with a checked `Topology`, or
`TopologyParse.Invalid` listing every `TopologyProblem` in walk order.

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
            // A template: the app attaches children here while running, each at weight 1.
            TopologyDocument.Group(name = "tenants", template = TopologyDocument.ChildTemplate()),
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

`from` reports wrong *values*: empty or repeated names, bad weight text, empty groups. Whether a
wrong *shape* decodes is up to your format: under a default `Json`, a bare-number weight
(`"weight": 3`) or a misspelt key throws before `from` runs, so quote weights or decode with
`Json { isLenient = true }`. A problem's address is its `path`, the child indices from the root
down (`[]` is the root, `[0, 2]` the root's first child's third child), so every subtree is
reported even beneath two children named alike or not named at all, and a child named `#3` or
`a/b` is never mistaken for an index or a second level. `names` along the path is for display
only. A `Topology` that exists is valid: its constructor, `copy` and its decoder all throw
`TopologyException` on the same problems.

**Your own settings in the same file.** When the file carries your app's settings beside the
tree, such as rules per lane, decode it **once**, strictly, into a type of your own that is
heddle's shape plus your fields, and build the `TopologyDocument` from it through its public
constructors. Report what is wrong with your fields as `TopologyProblem`s of kind `CONSUMER`,
addressed by the same child-index `path`, so they sit in one list with what `Topology.from`
found; heddle never reports that kind itself. Do not decode the file twice instead, once into
`TopologyDocument` and once into your own type: the decode into heddle's type has to ignore your
keys, so it ignores a misspelt `weigth` too, and that lane quietly gets the default weight `"1"`.
Here `PoolGroup` is the superset of a group (its sibling `PoolLane` is the same for a lane, and
`PoolFile` holds the root and the children):

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#PoolGroup -->
```kotlin
@Serializable
@SerialName("group")
internal data class PoolGroup(
    override val name: String,
    override val weight: String = "1",
    val children: List<PoolNode> = emptyList(),
    val template: TopologyDocument.ChildTemplate? = null,
    override val rules: List<String> = emptyList(),
) : PoolNode
```

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleConsumerProblems -->
```kotlin
    // One decode, under a default `Json`: a key neither heddle nor the app knows is an error.
    val file = Json.decodeFromString<PoolFile>(text)

    fun PoolNode.document(): TopologyDocument.Node = when (this) {
        is PoolGroup -> TopologyDocument.Group(name, weight, children.map { it.document() }, template)
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

**A fixed tree in code.** Use `draft(root) { group(name, weight) { … } }`. It builds through the
same check and throws `TopologyException` with the problems `from` would list.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyDraft -->
```kotlin
    val fairness = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive", Weight.of(3, 2))
            // A ceiling: never more than two batch jobs at once, however idle the pool is.
            lane("batch", maxConcurrent = 2)
        }
        group("tenants") { template() }
    }
```

**Your config paths and heddle's ids.** To turn your own paths into ledger ids and back, without
keeping a name map that has to track heddle's escaping, use `Topology.findGroupId(GroupPath)` and
`Topology.findPath(GroupId)`. Neither throws, and they are exact inverses: each returns `null` for
anything this topology has no group for, including an id a reload has since removed. A `GroupPath`
is `Declared(names)` or `Attached(group, name)`, so an attached child stays apart from a declared
sibling of the same name, and its `names` read either arm back from just below the root.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleGroupPathRoundTrip -->
```kotlin
    // A declared lane and an attached child share the name `x`, and their paths stay apart.
    val lane = GroupPath.Declared(listOf("tenants", "x"))
    val tenant = GroupPath.Attached(group = listOf("tenants"), name = "x")
    val laneId = checkNotNull(pool.findGroupId(lane))
    val tenantId = checkNotNull(pool.findGroupId(tenant))
    check(laneId == pool.groupId("tenants", "x") && tenantId == pool.attachedChildId(listOf("tenants"), "x"))
    check(laneId != tenantId)

    // Back from an id: the arm says whether the last name is an attached child's.
    check(pool.findPath(laneId) == lane && pool.findPath(tenantId) == tenant)
    check(pool.findPath(tenantId)?.names == listOf("tenants", "x"))
```

`Topology.lower(generation)` turns the checked tree into the `AttachmentRecord`s a ledger is
prepared with, under ids that are the same on every target and never collide however the names
are spelt: `root/acme/batch@1:1#0` is `batch` in `acme`, at weight 1, generation 0, and
`groupId("acme", "batch")` names the group. `LocalHeddle.fromTopology` does this for you.

## Changing the tree while work runs

**Intent:** reload the config while leases are open, without leasing against a half-applied
tree and without losing a charge.
**Primitive:** `LocalHeddle.apply(newTopology, generation)` → `ApplyResult`, under your
scheduling lock. It diffs the new tree against the ledger it holds and applies the whole reload
as one unit, swapped in once. Open leases survive it and are still charged once, at `complete`.

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

- **A reweight resets that group's fairness history.** A new weight is a new edge: the leases
  open under the old one move onto it, keeping their ids, and the group restarts at its parent's
  front, neither owed for its past nor charged for it.
- **A removal drains.** The group closes at once and retires when its leases finish. Until then
  `ApplyResult.refusals` holds a `StepRefusal.Draining` naming the edge and the blocking leases,
  and every other child's change still lands. Apply the same topology again later and only what
  is left runs. A removal under a reweighted group wins: the reweight waits too. Settle a dead
  job's lease through `reconcileLeases` (see [Surviving a restart](#surviving-a-restart)) so it
  never holds a reload up.
- **A rename or a move is a delete plus an add.** The new group starts with no history, and the
  old one drains under its old name.
- **A change the ledger will not take** is a `StepRefusal.Refused`, and that child is left as it
  was while its siblings land; see [Refusals and limits](#refusals-and-limits).

To preview a reload, `Topology.diff(new, ledger, generation)` lists the `TopologyStep`s from what
the ledger holds to `new` without applying them. It reads which edges exist from the ledger rather
than from the old tree, so it works after a restart, and after a partial apply it lists exactly
the rest. `apply` runs that same list.

## Children your app attaches while running

**Intent:** groups the program discovers as it runs — a tenant, a repository, a contributor's
fork — each with its own fair share, without rewriting the config.
**Primitive:** a group with a `template` in the `Topology`, then
`LocalHeddle.attach(group, name, generation)` and `detach(group, name)`. The template gives each
attached child its weight and its own `maxConcurrent`; the names are opaque to heddle, of any
spelling, and never collide with a declared child.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleAttachedChildren -->
```kotlin
    // Named like the declared lane `x`, and still its own child: attached names have their own namespace.
    val tenant = pool.attachedChildId(listOf("tenants"), "x")
    check(tenant != pool.groupId("tenants", "x"))
    val attach = pool.attach(listOf("tenants"), "x", ledger, generation = 0)
    check(attach.map { it::class } == listOf(TopologyStep.Prepare::class, TopologyStep.Activate::class))
    check((attach.first() as TopologyStep.Prepare).record.weight == Weight.of(2))
    apply(attach)
    // Asking again is harmless, and a reload that keeps the template keeps the child.
    check(pool.attach(listOf("tenants"), "x", ledger, generation = 0).isEmpty())
    check(Topology.diff(pool, ledger, generation = 0).isEmpty())
```

`Topology.attach` and `detach`, as in the sample, list the same steps without applying them; on
an engine, `LocalHeddle.attach` and `detach` apply them as one unit, like `apply`. Attaching
twice does nothing. A detach drains like a removal, and its own swap forgets the child's retired
edge once its books close, so detach a child whenever it goes idle. Re-attaching a child whose
detach is still draining moves its leases onto the new edge. A reload that keeps the template
keeps every attached child and its history; one that drops the template closes them.
`Topology.attachedChildId(group, name)` names the child's id.

An attached child is always a leaf: it carries no template of its own, so a tree you discover
while running is one level deep under its group. For two levels, such as a repository and then
its authors, declare each repository as a group with a `template`, and `apply` a new topology as
each repository appears; its authors are then attached children. Letting an attached child have
attached children of its own is tracked by
[#3314](https://github.com/tractat-us/kuilt/issues/3314).

## Capping and pausing a group

**Intent:** run no more than N of one part of the tree at once, however idle the rest is —
"one deploy at a time", "two jobs per repository" — or pause a group entirely.
**Primitive:** `maxConcurrent` on a group or lane of the `Topology`, or inside a group's
`template` to cap each attached child on its own; `0` pauses it. Do not count leases in your own
loop beside the engine: a gate there could do none of the reporting below.

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

A lease counts at every group its path passes through, a draining one included. A pick skips
every leaf beneath a node whose open leases have reached its ceiling, so the slot goes to a
sibling, and the capped node counts as idle at its parent: it banks no credit, and comes back as
far ahead of its siblings as it was when it reached the ceiling. `explain` tells such a leaf
`AtCeiling(group, limit)`, naming the capped node nearest the root, before any supply check;
`entitlement` owes it nothing; `previewPicks` counts the leases it opens against the ceiling;
and `acquire` returns `NothingEligible` when every eligible leaf is capped. `admit`, `extend`
and `complete` are never refused by a ceiling, and lowering one preempts nothing. A reload that
changes only ceilings changes no edge.

On a group with a template, the two ceilings stack: the template's caps each attached child, and
the group's caps them all together. `explain` names whichever one holds a child back:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleTemplateCeiling -->
```kotlin
    val ci = draft("ci") {
        group("repos", maxConcurrent = 2) { template(maxConcurrent = 1) }
        lane("nightly")
    }
```

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleTemplateCeiling -->
```kotlin
    // `a` runs one job, its own ceiling.
    check((heddle.acquire(setOf(a), setOf(a), startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == a)
    check(heddle.explain(a, waiting = all, eligible = all) == WaitExplanation.AtCeiling(a, limit = 1L))
    // `b` runs one too, which puts the group at its ceiling of two: `c` waits on the group.
    check((heddle.acquire(setOf(b), setOf(b), startedAt = 0L) as AcquireOutcome.Acquired).lease.leaf == b)
    check(heddle.explain(c, waiting = all, eligible = all) == WaitExplanation.AtCeiling(ci.groupId("repos"), limit = 2L))
    // So the next slot goes to the nightly lane, however much the repositories have queued.
    check((heddle.acquire(all, all, startedAt = 1L) as AcquireOutcome.Acquired).lease.leaf == nightly)
```

In a config file, `maxConcurrent` beside a group with a template caps all its attached children
together, and `maxConcurrent` inside the template caps each one:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleChildTemplateDocument -->
```kotlin
    val text = """
        {"root": "pool", "children": [
          {"type": "group", "name": "repos", "maxConcurrent": 8, "template": {"maxConcurrent": 2}}
        ]}
    """
    val written = Json.decodeFromString<TopologyDocument>(text)
    val repos = (Topology.from(written) as TopologyParse.Valid).topology.children.single() as Topology.Group
    check(repos.maxConcurrent == 8L)
    check(repos.template == Topology.ChildTemplate(Weight.ONE, maxConcurrent = 2L))
```

## Who goes next, and why not me

**Intent:** show a person why their work is waiting, what share they are owed, and who runs in
the next few slots — on an admin screen, in a log line, in a support answer.
**Primitive:** `LocalHeddle.explain(leaf, waiting, eligible)` → `WaitExplanation`;
`entitlement(leaf, waiting, totalSlots)` → `Rational`; `previewPicks(…)` → the next leaves in
order. None of them changes anything, the wake memory included.

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

`explain` runs the real pick on a throwaway copy, so it agrees with the next `acquire` on the
same arguments. It answers one of `NotWaiting`, `NotEligible`, `NoLiveLineage(at)` (no pick can
end there, the same test `acquire` applies), `AtCeiling(group, limit)`, `SupplyExhausted`,
`PositionCeiling(parent)` (not returned today), `BehindAtLevel(parent, nextChild, …)` at the first group where another
child goes first, or `WouldBePickedNext`. `BehindAtLevel` carries the leaf side's virtual service
beside two lines: `eligibleFront`, the line this pick drew over the children it could run (more
than one sixteenth of a unit of the side's own service above it, the side was not a candidate; at
or below, it lost on its deadline), and `front`, the share line over everyone waiting (above it,
the side has had more than its share). **Log `kind`**, a stable ordered enum, and the group ids,
not the rationals, which move every pick. `entitlement` is the leaf's fair share of the slots,
as an exact fraction, over the same competing set: work no pick can reach is owed nothing.

`previewPicks` comes in two shapes. Give it a count per leaf and one reservation per slot, or
give it each leaf's queue of job estimates, head first, and each pick does what your loop would:
`acquire` at the slot's reservation, then `extend` to the winner's head when that is larger.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#samplePreviewQueues -->
```kotlin
    // Each lane's queued builds, by their estimates in minutes, the next one first.
    val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
    val both = setOf(web, mobile)
    val before = heddle.snapshot()
    val preview = heddle.previewPicks(queues, eligible = both)
    check(preview == listOf(web, mobile, web, mobile, web, web, mobile))
    check(heddle.snapshot() == before)
```

**From another process.** A reader that holds only the stored snapshot, such as an admin screen,
does not need an engine. `snapshot.view(policy)` returns a `LocalHeddleView` with the same
`previewPicks`, `explain` and `entitlement`, and it answers exactly as the engine that took the
snapshot would have: the engine's own methods are that view over its live state. Do not restore
a throwaway engine under `epoch + 1` for each question. The policy is required, since a snapshot
does not keep one; a policy that cannot size the snapshot's `totalSlots` throws, as `restore`
does.

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

The view takes each lane's queue of estimates as the engine does:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleSnapshotPreviewQueues -->
```kotlin
    // On the admin screen: each lane's queued builds by their estimates in minutes, the next one first.
    val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 3L, 3L))
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val view = decoded.snapshot.view(policy)
    val both = setOf(web, mobile)
    check(view.previewPicks(queues, eligible = both) == listOf(web, mobile, web, mobile, web, web, mobile))
    check(view.previewPicks(queues, eligible = both) == heddle.previewPicks(queues, eligible = both))
```

## Charging work started elsewhere

**Intent:** someone started a job by hand, or another scheduler placed it, and it must still be
charged to its group like any other, with no pick.
**Primitive:** `LocalHeddle.admit(leaf, startedAt)`, or `admit(leaf, waiting, startedAt)`. Both
open a lease at the leaf without a pick and are never refused for supply or at a ceiling, since
the work is already running; a shortfall at the root is minted. Settle the lease with
`complete` as usual.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleAdmitExternal -->
```kotlin
    // No capacity is reconciled, so there is nothing to hand out; an admitted build is charged anyway.
    val both = setOf(web, mobile)
    check(heddle.acquire(both, both, startedAt = 0L) == AcquireOutcome.SupplyExhausted)
    val byHand = heddle.admit(mobile, waiting = both, startedAt = 0L)
    check(byHand is AcquireOutcome.Acquired)

    // Mobile is now at its ceiling of one, and a second build started by hand is still charged.
    val another = heddle.admit(mobile, waiting = both, startedAt = 1L)
    check(another is AcquireOutcome.Acquired)
    check(heddle.complete(another.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L))

    // A group with a template and nothing attached is not somewhere a build can run.
    val forks = farm.groupId("forks")
    check(heddle.admit(forks, startedAt = 2L) == AcquireOutcome.NoLiveLineage(forks))
```

**Pass `waiting` when your work is always started elsewhere.** The plain `admit` observes nothing,
so a sibling it saw idle that comes back is never treated as returning from idle. The overload
leaves the wake memory as `acquire(waiting, eligible = {leaf})` would have, wherever that acquire
would lease. The only refusal is `NoLiveLineage(leaf)`, for a name that is not leasable or has no
live path from the root.

## Surviving a restart

**Intent:** the scheduler's program restarts, or its executors crash, and the books must carry on
where they stopped: open leases still valid, the next pick the one the old engine would have
made, and the work that did not survive settled.
**Primitive:** `persist()` → `LocalHeddleSnapshot`, `LocalHeddle.decode(bytes or text, format)` →
`RestoreResult`, `LocalHeddle.restore(snapshot, policy, epoch)`, then
`reconcileLeases(live, now)` → `LeaseReconciliation`.

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

The encoding is a two-element array, `[formatVersion, state]`, so a store that reorders object
keys, such as Postgres `jsonb`, cannot scramble it. `decode` returns `Restored(snapshot)`,
`UnsupportedVersion(found, supported)` or `Corrupt(reason)`, never an exception. After `restore`,
every open lease is valid again under its old id, with its path and its `startedAt`, and the wake
memory, the reconciled capacity and the topology last applied come back.

- **`snapshot.epoch + 1` is the sample's shorthand, not the pattern.** Lease ids count from zero
  in each epoch, so restoring one snapshot twice under the same epoch (a crash straight after the
  first restore, say) gives two engines that number leases alike, and a job from the first run
  can settle the second's lease. Keep a boot counter in storage and raise it on every start;
  `restore` throws `IllegalArgumentException` when the epoch is not above the snapshot's, or when
  the policy cannot size the snapshot's `totalSlots`.
  An old id that is no longer open answers `Stale`.
- **Formats.** `:kuilt-heddle-core` brings no format: add `kotlinx-serialization-json` or
  `kotlinx-serialization-cbor`. Both are tested, CBOR's definite- and indefinite-length arrays
  included. **Not ProtoBuf**: it encodes a snapshot, but `decode` answers `Corrupt`, since it
  cannot read back the ledger's maps whose values are maps.
- **Versions.** `LocalHeddleSnapshot.FORMAT_VERSION` is what this release writes and
  `SUPPORTED_FORMAT_VERSIONS` what it reads: format 2, and only format 2. A snapshot an earlier
  release stored decodes as `UnsupportedVersion`; boot from the topology again.
- **What `decode` checks.** The envelope's shape and version, then consistency: every open lease
  has a positive reservation, an epoch no later than the snapshot's and a path that is its leaf's
  one live lineage from the root, over edges the ledger holds and none retired; leases in id
  order; a valid topology. It does **not** bound values an engine could never reach, such as a
  reservation above `MAX_RESERVATION`, which decodes as `Restored` (tracked by
  [#3261](https://github.com/tractat-us/kuilt/issues/3261)). Decode only bytes your own program
  persisted.
- **What was left draining stays draining.** The snapshot keeps the topology last applied, so
  after the old leases settle, `apply` the same topology again to land a removal that was still
  waiting. The `LocalPolicy` is not stored: pass the one the old engine ran with.

**Settling the work that did not survive.** `reconcileLeases(live, now)` compares the leases the
engine holds open with the ones whose work you still have running, reports both directions, and
changes nothing. `orphaned` lists each open lease you did not name, with `elapsed`, which is
`now − startedAt` and never below zero if your clock went back. `unknown` lists the ids you named
that the engine holds no lease for. Which orphans to settle, and at what cost, is yours to decide;
the documented default is `complete(lease, orphan.defaultSettlement)`: the time it ran, capped at
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

## Refusals and limits

**Intent:** know every answer a `LocalHeddle` call can give, so a loop branches on a typed outcome
instead of catching exceptions, and know the few limits a caller can reach.
**Primitive:** the outcome types. A refusal is a value, never an exception, and a refused step or call
leaves the ledger and the leases as they were. Only the wake memory may move: a `NoLiveLineage`
from `acquire`, or from the `admit` that takes a waiting set, keeps what its pick observed about
who was waiting.

| Call | Answers | Never refused for |
|---|---|---|
| `acquire` | `AcquireOutcome`: `Acquired`, `NothingEligible` (empty eligible set, or every eligible leaf capped), `SupplyExhausted` (no capacity reconciled), `NoLiveLineage(root)`; `PositionCeiling(parent)`, not returned today | — |
| `admit` | `AdmitOutcome`: `Acquired`, `NoLiveLineage(leaf)` | supply, a concurrency ceiling |
| `extend` | `LeaseOutcome`: `Done`, `PathDraining(edge)` (a reload is closing the path), `SupplyExhausted`, `Unknown`, `Stale(currentEpoch)`; `PositionCeiling(parent)`, not returned today | a concurrency ceiling |
| `complete` | `LeaseOutcome`: `Done(charged, overrun)`, `Unknown`, `Stale(currentEpoch)` | anything: a draining path, an overrun, a ceiling |
| `cancel` | `LeaseOutcome`: `Done`, `Unknown`, `Stale(currentEpoch)` | anything |
| `fromTopology` | `LocalHeddle.Boot(heddle, result)`: the engine, and an `ApplyResult` naming every group the boot left out, each a `StepRefusal.Refused` | — |
| `apply`, `attach`, `detach` | `ApplyResult(applied, refusals)`, each refusal a `StepRefusal.Draining` (waits on open leases) or `StepRefusal.Refused` (the ledger will not take it) | — |
| `decode` | `RestoreResult`: `Restored`, `UnsupportedVersion`, `Corrupt` | — |

`Unknown` is an id from this engine's epoch that holds no open lease, a second `complete`
included; `Stale` is one from another epoch. On a draining path `extend` is refused but
`complete` still settles: the reservation is charged at the leaf, and an overrun at the deepest
group still reached through live edges.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRefusals -->
```kotlin
    // A ceiling of 0 pauses a lane: with only it eligible for this slot, there is nobody to pick.
    val all = setOf(live, paused, retiring)
    check(heddle.acquire(waiting = all, eligible = setOf(paused), startedAt = 0L) == AcquireOutcome.NothingEligible)
    check(heddle.explain(paused, waiting = all, eligible = all) == WaitExplanation.AtCeiling(paused, limit = 0L))

    // A reload removes `retiring` while its lease runs: the extend is refused, the complete settles.
    val lease = (heddle.acquire(setOf(retiring), setOf(retiring), startedAt = 0L) as AcquireOutcome.Acquired).lease
    val without = draft("pool") {
        lane("live")
        lane("paused", maxConcurrent = 0)
    }
    check(heddle.apply(without, generation = 1L).refusals.single() is StepRefusal.Draining)
    check(heddle.extend(lease.id, amount = 3L) is LeaseOutcome.PathDraining)
    check(heddle.complete(lease.id, actualCost = 3L) == LeaseOutcome.Done(charged = 3L, overrun = 0L))

    // Settled once, the id is unknown to this engine; after a restart it is from an older epoch.
    check(heddle.complete(lease.id, actualCost = 3L) == LeaseOutcome.Unknown)
    val restarted = LocalHeddle.restore(heddle.persist(), policy, epoch = 1L)
    check(restarted.complete(lease.id, actualCost = 3L) == LeaseOutcome.Stale(currentEpoch = 1L))

    // Keep every weight's denominator at or below 30,719. A finer one is refused when it is
    // applied, and the rest of the reload lands.
    val finer = draft("pool") {
        lane("live")
        lane("paused", maxConcurrent = 0)
        lane("fine", Weight.of(1, 30_720))
        lane("coarse", Weight.of(1, 2))
    }
    val refused = restarted.apply(finer, generation = 2L).refusals.single()
    check(refused is StepRefusal.Refused && refused.child == finer.groupId("fine"))
    val coarse = finer.groupId("coarse")
    check(restarted.acquire(setOf(coarse), setOf(coarse), startedAt = 10L) is AcquireOutcome.Acquired)
```

**A boot is not a reload.** The weight budget belongs to a whole sibling set, and a reload
activates a set's new children in id order, so of two new siblings that fit alone but not
together it refuses the one whose id sorts last, and leaves the running tree as it was. A boot
has no running tree to keep. Where a group's children's weights do not fit together, it leaves
out that group and every group beneath it, reports each one, and boots the rest; at the root,
nothing boots. The answer is the same whatever the children are named and in whatever order they
are written. Apply a tree that fits to bring the left-out groups back.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalBootRefusal -->
```kotlin
    val tree = draft("farm") {
        group("builds") {
            lane("fast")
            lane("fine", Weight.of(1, 30_720))
        }
        lane("docs")
    }
    val (heddle, booted) = LocalHeddle.fromTopology(tree, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    val left = setOf(tree.groupId("builds"), tree.groupId("builds", "fast"), tree.groupId("builds", "fine"))
    check(booted.refusals.map { it.child }.toSet() == left)
    check(booted.refusals.all { it is StepRefusal.Refused })
```

`PositionCeiling` is not in the sample, because nothing returns it today: a pick compares the
full 128-bit position, so no position refuses one. The arm is kept for the limit a long-lived
engine does reach, its `Long` counters, which throw for now.

**What throws.** A bad argument throws `IllegalArgumentException`: a reservation or extension
outside `1..LocalHeddle.MAX_RESERVATION` (2^44 cost units), an `eligible` set that is not a
subset of `waiting`, a negative `actualCost`, a topology whose root is not the engine's, an
`attach` to a group with no template, a restore epoch not above the snapshot's, a policy that cannot size the snapshot's
`totalSlots` at restore. Arithmetic that
would leave `Long` throws `ArithmeticException` rather than wrapping; the diagnostics can reach it
well before any ceiling, since they answer in exact `Rational`s: `explain`'s `BehindAtLevel`
fronts, whose numerator carries the least common multiple of the sibling weights' denominators,
and `entitlement`. `IllegalStateException` is left for a ledger conflict the engine did not
create, such as two live edges into one group, a split record or a cycle on a lease's path. A
weight set that does not fit is never one: a boot reports it in `Boot.result`, and a reload in
its `ApplyResult`.

**The position ceiling.** Each child's progress is a count of sixteenths of a unit, stored exactly
in 128 bits and compared by a pick in 128 bits too, so no position ever refuses a pick. It has a
ceiling all the same, past which the public virtual-time reads (`virtualService` and its kin)
report the ceiling, the penalty side. A busy parent never gets near it by itself: long before, a
pick **rebases** the parent's children, all by the same virtual time, so no pick changes, and so
does a charging call. The rebase aims at the demanding children's average and stops at each one's
floor, the mirror of its ceiling (the window rule), so a heavy child joining lighter ones lands
near zero, not past its ceiling. A parent still climbs past the ceiling where no whole step fits:
a demanding child that is never served drags that average down with it until no step fits, and
so does a child a charging call counts as demanding while it is idle. The picks carry on exactly
there; only the reads saturate.

**The lifetime limit.** What a long-lived engine meets first is the ledger's `Long` counters: an
edge's issuance and an epoch's mint, about 9.2 × 10¹⁸ units each. The call that would pass one
throws `ArithmeticException` and changes nothing. That is two weight-1 lanes running about half a
million leases of the largest size.

**The weight budget.** Keep every weight's denominator at or below 30,719. A weight set so fine
that one step or one full lease could carry a child past its ceiling is refused when it is
applied, as a `StepRefusal.Refused` for that child while its siblings land; so is one whose
weight products would leave `Long`. The limit holds at any lease size, because it is sized for
the largest lease, `MAX_RESERVATION`. It is necessary, not sufficient: several fine weights whose
denominators share no factor are refused below it too.

**Two known fairness gaps**, both smallest when a lease settles at its estimate:

- **An estimate's size leans share.** A lease counts at its reservation until `complete`, so a
  lane that under-reserves looks cheaper while it runs and gains share; one that over-reserves
  loses it. Most callers send `defaultReservation`, so its size picks the side. Aim it at a
  typical job, and pass a per-lease `reservation` when history gives a better one. Tracked by
  [#3239](https://github.com/tractat-us/kuilt/issues/3239).
- **A cancel is charged nothing, and a sibling's refund leaks across a gap.** A lane that cancels
  the job ending each burst gains share (+0.015 to +0.044 on the slot probe). And a *sibling's*
  reservation counts in the net-service clock at the weight of everyone asking, while its refund
  lands at the weight of whoever is still asking, so a lane away across a sibling's refund comes
  back owed more than it is: 19 / 10 on a rig whose fair split is 15 / 14. A lane's own refund
  across its own gap is read correctly. Tracked by
  [#3238](https://github.com/tractat-us/kuilt/issues/3238) and
  [#3239](https://github.com/tractat-us/kuilt/issues/3239). `HeddleNode`'s `complete` keeps an
  unused earmark at the leaf, so `HeddleNode` does not take either path.

## Fair share across peers

**Intent:** put the fair-share tally on a live connection between peers with no one in charge —
advertise appetite, allocate entitlement down a tree, then reserve a slice before running work
and charge it exactly once on completion, even if `complete` is called twice.
**Primitive:** `HeddleNode` via `heddleStatic(...)` (`:kuilt-heddle`), over a `Seam` from a fixed
roster. Every peer that bootstraps with the same root, mint and topology begins from an identical
ledger and stays in step over the wire on its own; the spend path takes no coordination.

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

`reserve` answers `ReserveOutcome.Reserved(id)` or `NoHoldings` (the lane is out of entitlement
until a scheduling round delegates more), and on a governed node `GateClosed` too. `schedule`
answers a `ScheduleOutcome`; the next section reads it.

## Telling an idle scheduler from a stuck one

**Intent:** a node stopped handing out entitlement, and you need to know whether the tree is
idle or something at a parent is wrong, without reading the ledger by hand.
**Primitive:** the `ScheduleOutcome` that `HeddleNode.schedule(parent)` returns, on a static node
and a governed one alike. `NothingToDelegate` is the idle answer. `WeightBudget(parent)` says the
parent's children cannot be ordered: weights gossip merged that a pick cannot sum in `Long`, or
that are too fine for one full lease. It clears when one child closes. `PositionCeiling(parent)`
is not returned today: a pick compares the full 128-bit position, so a long run keeps granting
however far its children climb, at weights as far apart as 60 to 1, across a restart, and with
several schedulers granting one child at once. What a long-running parent does meet is the
lifetime limit: a grant that would carry an edge's issuance past `Long` throws
`ArithmeticException`, and the rounds the call made before it stay landed (#3475).

**A refusal writes nothing.** The first round of a call, with the newborns it seats before that
pick, lands as one patch or not at all, so a refused call leaves the ledger and the wake clamps as
they were. A call with nothing to grant writes nothing either. A call that granted and then met a
refusal answers `Delegated`, and the next call answers the refusal.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleScheduleOutcomes -->
```kotlin
internal fun HeddleNode.sampleScheduleOutcomes(root: GroupId): String =
    when (val outcome = schedule(root)) {
        // Granted this many rounds. A call that granted and then met a refusal answers this; the next call answers the refusal.
        is ScheduleOutcome.Delegated -> "delegated ${outcome.grants}"
        // Nothing to move toward any advertised demand: an idle tree, or no holdings at the root.
        ScheduleOutcome.NothingToDelegate -> "idle"
        // Not returned today: a pick orders the full position. Kept for the lifetime counters.
        is ScheduleOutcome.PositionCeiling -> "stuck at ${outcome.parent.value}"
        // The active children's weights put a pick's sums out of Long, as gossip can merge. Close one of them.
        is ScheduleOutcome.WeightBudget -> "stuck at ${outcome.parent.value}: close a child, its weights cannot be summed"
        // Only a governed node has a write gate; a static node never answers this.
        is GateClosed -> "gate closed: ${outcome.reason}"
    }
```

`BoundMetrics.peakPositionFraction` from `boundMetrics(parent)` says how far the parent has
climbed: the highest child position as a fraction of its ceiling, held well below one by the
rebase. Past one the ledger's virtual-time reads saturate; the picks do not change.

## Minting quota and reshaping with agreement

**Intent:** mint new entitlement or re-parent a group *while the system runs*, and need
everyone to agree on the order so a split-brain can't double-mint and two overlapping
reshapes don't corrupt the tree.
**Primitive:** `heddleGoverned(...)` → `GovernedHeddleNode` (`:kuilt-heddle`) — the same data
plane as `heddleStatic`, but `mint`/`prepare`/`activate`/`close`/`retire` are serialized
through `:kuilt-raft`. The spend path (`schedule`/`reserve`/`complete`) never touches the log.

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

- **Every control act answers a `ControlOutcome`**: `Applied`, `Conflict` with the structured
  reason when it loses a race or names a weight set the weight budget refuses
  (`ControlConflict.WeightBudget`), or `Indeterminate` when this peer halted with the call in
  flight, so the act may still commit and a blind retry can double-mint. Keep every weight's
  denominator at or below 30,719, as on `LocalHeddle`; a finer set is refused at
  `prepare`/`activate`.
- **`enroll(self)` opens a node's write gate.** Until it applies, `reserve` and `schedule` answer
  `GateClosed` (with the replica and a `Reason`), a separate arm from `NoHoldings` and
  `NothingToDelegate`, so a node that never enrolled cannot pass for an idle one.
  `enroll(replica)`/`depart()` keep the **agreed participant list** (`enrolledReplicas()` reads it
  back); only a peer may depart itself.
- **Re-homing stranded budget.** If a gossip-lagged peer's `retire` races a delegate and strands
  budget on a since-reparented child, `reconcile(child)` re-homes it — net inflow *and* any
  service already charged through it, with any transfer rows — onto the child's live lineage
  through the log, minting nothing, which clears the resulting
  `PersistentNegativeHoldings`/`PerEdgeSafety`/`ClosureViolation`. **It sends no magnitudes:** it
  opens a `quiesce(edge)` barrier over each retired inbound edge, every peer promises never to
  write that edge again and acks its own final values, and the move is *derived at apply time*
  from those promises — so a lagged or deposed proposer cannot commit a wrong amount. Expect to
  call it twice: the first call is usually refused naming the peers it waits on
  (`pendingAcks(edge)` reads that set). It refuses when the arithmetic or the fence says so: a
  replica net-negative on the strand, fenced edges that cannot cover what was charged through
  them, a cross-parent re-home, or a carried hand-off whose donor is no longer on the roster. And
  it **blocks while any enrolled peer is down** — that peer may hold an unreplicated reservation,
  so the wait is the safety property, not a bug.
- **A peer that cannot read a log entry stops there.** `GateClosed.Reason.UnreadableControlEntry`
  names the log index and format version, `enroll` is refused, and a restart stops at the same
  entry, so it takes a build that can read the entry or a fresh log. One peer on a newer build
  stops older ones, so upgrade a cluster together. That includes bytes heddle never wrote: give the
  control plane a Raft node of its own, with nothing else proposing into it, on the default
  `ClientIdentity.Auto` (the plane replays from index 1 and restarts its serials every boot). A
  durable log from v0.7.3 stops at its first `Mint` or `Prepare`: start it fresh.
- **A `timeout` or cancel stops only the wait.** The act can still apply, and an
  `enroll`/`depart` that does moves the gate then, so read before resubmitting and never re-issue
  one to repair the gate.

## Weighted lanes over warp

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

## The bare policy and allocator

**Intent:** you keep your own ledger and loop, and want only heddle's decision — which of several
competing children gets the next slice, weighted (3:1) and hoarder-proof; EEVDF.
**Primitive:** `HeddlePolicy.pick(edges, config, localHoldings)` (`:kuilt-heddle-core`), a
**pure** function: no wall clock, no randomness, no floating point, so every replica picks the
same winner. It serves the eligible child (not running ahead of its fair share) whose next grant
finishes soonest in virtual time, and returns `null` when nobody is both eligible and demanding.

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

One step up, `FairShareAllocator.schedule(parent, ledger, earmarked, demand)` runs the whole
fair-share loop over a `LedgerCell` — the latest ledger plus one write step that decides each
grant on the state it lands on — with newcomers seated level and a child back from idle where it
left off. For a single process the cell is a `var`. It is the loop `HeddleNode.schedule` runs. An
allocator is not thread-safe: one per scheduling peer. It has no leases, no reloads and no
snapshot; for those, use `LocalHeddle`
([A fair-share queue in one process](#a-fair-share-queue-in-one-process)).

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
