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
**Primitive:** pick the row that matches where the scheduling decision is made, and whether your
code waits for it.

| Your situation | Use | Module |
|---|---|---|
| One program hands out slots, workers or turns, and keeps the books | `LocalHeddle` | `:kuilt-heddle-core` |
| The same, with coroutines that wait for their turn | `FairSharePool.withSlot` over a `LocalHeddle` | `:kuilt-heddle-coroutines` |
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
estimated cost for it, and `complete(lease, actualCost, waiting)` charges what it really cost, hands the
unused part back, and reports any overrun, which is never refused.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleLocalLease -->
```kotlin
    // Both groups have work queued, and this slot can run either.
    val queued = setOf(heavy, light)
    val first = heddle.acquire(waiting = queued, eligible = queued, startedAt = 100L)
    check(first is AcquireOutcome.Acquired)

    // The job overran its 3-unit estimate: all 5 units are charged, 2 of them as overrun.
    check(heddle.complete(first.lease.id, actualCost = 5L, waiting = queued) == LeaseOutcome.Done(charged = 5L, overrun = 2L, unrecorded = 0L))

    // Over many slots the shares follow the weights, 2 : 1.
    val picks = (0 until 30).map {
        val lease = (heddle.acquire(queued, queued, startedAt = it.toLong()) as AcquireOutcome.Acquired).lease
        heddle.complete(lease.id, actualCost = 3L, waiting = queued)
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
    check(heddle.complete(first.lease.id, actualCost = 4L, waiting = emptySet()) is LeaseOutcome.Done)
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
    check(restored.complete(orphan.lease.id, orphan.defaultSettlement, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
    check(restored.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
```

Cost is time, in your own unit. `LocalPolicy.defaultReservation` is the estimate a lease
reserves when you pass none; pass a per-lease `reservation` when you know better. `extend`
reserves more for a lease that runs long, and `cancel` closes one that never ran and charges
nothing. Work that held its slot settles with `complete` at what it cost, however it ended.
`startedAt` is stored on the lease for your own reconciling and never read by a pick.

**Waiting and eligible are different sets on purpose.** `waiting` is every leaf with work
queued; `eligible` is the ones this particular slot can run, say the Mac builds on a Mac agent.
Only an eligible leaf is picked, but a waiting one that cannot run here keeps its claim, so it is
not treated as returning from idle when a slot it can run comes up. A leaf with nothing queued
is idle: it banks no credit, and when it comes back it is as far ahead of or behind its siblings
as it was when it left, moved by how far its parent's net-service clock ran meanwhile, give or
take a sibling's refund in that time ([#3239](https://github.com/tractat-us/kuilt/issues/3239)),
rather than owed the slots it could not have used. A child this engine never saw stop is clamped
level with its siblings instead, with the leases it still has open counted as its own. A new leaf starts level with its siblings. Work no
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

## Waiting your turn for a slot

**Intent:** a coroutine that should simply wait until its lane's fair share comes round, then
run — without hand-rolling a semaphore per lane, a queue of waiters, the wake-up of the right
one, the charge for a job that failed, or the hand-back of a slot whose waiter gave up at the
moment it was granted.
**Primitive:** `FairSharePool.withSlot` (`:kuilt-heddle-coroutines`), on a pool built over a
`LocalHeddle` and a slot count. `withSlot(leaf) { lease -> … }` suspends until fair share grants
that leaf a slot, runs the block, and charges the lease the time the block took, in whole cost
units rounded up. It throws `SlotRefusedException(reason)`, an `IllegalStateException`, for a name
no lease can end at or a lane at its lifetime limit, and the block never runs. Catch it by name,
`catch (e: SlotRefusedException)`, never as `IllegalStateException` around a suspend call: a
`CancellationException` is an `IllegalStateException`, so that arm swallows the caller's cancel
too. kuilt bans the shape in its own code with `forbidCancellationSwallowingCatch`.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleWithSlot -->
```kotlin
    val pool = FairSharePool(heddle, slots = 1, TimeSource.Monotonic, costUnit = 1.minutes)

    val built = mutableListOf<String>()
    coroutineScope {
        // One agent: whichever lane's turn comes second waits for the first build to finish.
        launch { built += pool.withSlot(web) { "web build" } }
        launch { built += pool.withSlot(mobile) { "mobile build" } }
    }
    check(built.sorted() == listOf("mobile build", "web build"))

    // A name the farm does not declare is refused before anything runs.
    val refused = try {
        pool.withSlot(GroupId("nightly")) { "never runs" }
    } catch (e: SlotRefusedException) {
        "refused: ${e.reason}"
    }
    check(refused.startsWith("refused"))
```

**Every exit is charged.** A block that returns, throws, or is cancelled part-way is charged the
time it held the slot, and the throwable or the cancellation goes on to the caller: a lane whose
work fails fast must not get the farm for free. The clock starts when the block starts, so the
moment between the grant and the caller resuming is never charged.

**The low-level form.** `acquire(leaf)` is the same wait without the block. It answers
`SlotOutcome.Granted(lease)`, or `SlotOutcome.Refused(reason)`, and the caller then `complete`s the
lease at its real cost, or `cancel`s it if the work never started.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleAcquireSlot -->
```kotlin
    // Two agents, and one unit of cost is one minute on an agent. The pool owns the engine now.
    val pool = FairSharePool(heddle, slots = 2, TimeSource.Monotonic, costUnit = 1.minutes)

    // Waits until fair share grants web a slot: at once, on an idle farm.
    val build = pool.acquire(web)
    check(build is SlotOutcome.Granted)
    // The build took five minutes against the default estimate of three: two are overrun.
    check(pool.complete(build.lease.id, actualCost = 5L) == LeaseOutcome.Done(charged = 5L, overrun = 2L, unrecorded = 0L))

    // A name the farm does not declare is refused, never left waiting.
    check(pool.acquire(GroupId("nightly")) is SlotOutcome.Refused)

    val next = pool.acquire(mobile)
    check(next is SlotOutcome.Granted)
    pool.cancel(next.lease.id)
```

**No slot leaks under cancellation.** A waiter cancelled while queued leaves its queue; one
cancelled after its grant was made but before it resumed hands the lease back with nothing
charged. A failure while handing out slots fails the waiters, never the `complete` or
cancellation that set it going; with nobody waiting, or every waiter cancelled before it took
the failure, it is logged at error with its trace, once.

**After a restart, settle what the crash left open.** The engine's open leases are adopted: a
restored engine's leases count against the slots from the start, and `now()` is the pool's clock in
cost units, from the `clockStart` you pass. Persist `now()` beside the snapshot and pass it back.
A `withSlot` caller that held a slot died with the old program, so nothing will settle its lease,
and a pool whose slots were all busy at the crash grants nothing at all. Once the pool is built,
reconcile with the leases whose work survived as `live`, and settle every orphan through the pool.
`now()` takes no lock, so it is safe inside the block. With a `clockStart` of `0`, every default
settlement reads `0`.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleRestartAPool -->
```kotlin
    // After the crash: restore under a raised epoch, your persisted boot counter, and start the
    // pool's clock where it stopped. Both leases are adopted: every slot is taken.
    val restored = LocalHeddle.restore(snapshot, policy, epoch = snapshot.epoch + 1)
    val pool = FairSharePool(restored, slots = 2, clock, costUnit = 1.minutes, clockStart = stoppedAt)

    coroutineScope {
        val next = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire(web) }
        check(!next.isCompleted) // both slots are held by builds that died with the old program

        // None of the old builds is running, so name none as live, and settle each orphan at the
        // default: the two minutes it ran. With the default clockStart of 0, now() would read 0,
        // and every default settlement would read 0 too.
        val found = pool.withEngine { engine, _ -> engine.reconcileLeases(live = emptySet(), now = pool.now()) }
        check(found.orphaned.map { it.defaultSettlement } == listOf(2L, 2L))
        found.orphaned.forEach { pool.complete(it.lease.id, it.defaultSettlement) }
```

**One writer: the pool owns the engine.** Once a `LocalHeddle` is in a pool, reach it only
through `pool.withEngine { engine, view -> … }`, which runs the block under the pool's lock:
`persist`, `explain`, `previewPicks`, `apply` for a reload, `reconcileLeases` for orphans. `view`
is a `PoolView`, a copy of the pool as the block starts: each lane's queued reservations head
first (`queues`), the leases open (`open`) and the slot count (`slots`). Pass `view.waiting`
wherever the engine asks for a waiting set, never a guess: `explain(lane, view.waiting,
view.waiting)` answers why a lane waits as the pool's next pick sees it, and an overrunning
`complete` or an `extend` observes it as demand, so a set missing a queued lane skews later picks.
To settle a pool lease, prefer `pool.complete` or `pool.cancel`, which work inside the block too
and use the live set. When the block ends, however it ends, the pool catches up. A pool lease the
block closed frees its slot, the waiters of a lane the reload removed are refused with
`NoLiveLineage`, and a waiter the change made grantable, such as one under a lifted ceiling, is
granted. The slot count is the pool's: change it with `pool.resize(n)`. A block's own
`reconcileCapacity` is undone when it ends. Lowering the count preempts nothing, and only holds
back the next grant.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#sampleReloadWhileWaiting -->
```kotlin
        val second = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire(web) }
        check(!second.isCompleted) // web is at its cap

        // Everything that touches the engine goes through the pool, which hands over a view of its
        // queues too: why web waits, as the pool's next pick sees it.
        val why = pool.withEngine { engine, view -> engine.explain(web, view.waiting, view.waiting) }
        check(why == WaitExplanation.AtCeiling(web, limit = 1L))

        // A reload, a snapshot, a search for orphans: the same way in.
        val uncapped = Topology("farm", listOf(Topology.Lane("web", Weight.of(3)), Topology.Lane("mobile")))
        val reload = pool.withEngine { engine, _ -> engine.apply(uncapped, generation = 1L) }
        check(reload.refusals.isEmpty())

        val granted = second.await()
        check(granted is SlotOutcome.Granted)
```

A lease the block opens itself with `acquire` or `admit` is never counted against the slots, and
the block runs while every other caller waits on the lock, so keep it short.

**A status page reads the pool, not a count of its own.** `pool.view()` is the same `PoolView`
without the engine, for a gauge of the open count or the queue depths. Who goes next is the
engine's answer, so ask it inside the block: `previewPicks(view.queues, view.waiting)`. Treat it
as a forecast. It is exact when `pool.resize` raises the slots and nothing else happens before the
grants. A lease completed at a cost other than its reservation refunds or overruns against the
waiting set of that moment, and a waiter arriving, leaving or being refused changes the queues, so
either can change the order. A view is a copy, so it never moves under you, and a count kept
beside the pool's would race it.

<!-- verbatim from kuilt-heddle-coroutines/src/commonSamples/kotlin/us/tractat/kuilt/heddle/FairSharePoolSamples.kt#samplePreviewThePool -->
```kotlin
        // A gauge needs no engine: each lane's queued estimates, head first, and the slots in use.
        val view = pool.view()
        check(view.queues == mapOf(web to listOf(3L, 12L, 3L), mobile to listOf(3L, 3L)))
        check(view.open == 2 && view.slots == 2)

        // Who goes next is a question for the engine, about the pool's own queues: ask it inside
        // withEngine, so the view and the answer are of the same moment.
        val next = pool.withEngine { engine, queued -> engine.previewPicks(queued.queues, queued.waiting) }
        check(next == listOf(web, mobile, web, mobile, web))
```

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

## A minimal editor, end to end

An admin page lets a person change who gets how much while work keeps running: rename a group,
add a lane, give a team more weight. Before saving, it tells them what the change will cost. heddle
ships no page, because every app brings its own screens. It ships the model the page drives, and
this is that model's loop, once through.

**Intent:** build the model behind an admin editor for a running `LocalHeddle`, without
hand-rolling a tree copier, a validator, a diff or a "has someone else saved" check.
**Primitive:** six steps, each covered in depth by its own section (`:kuilt-heddle-core`):

1. **Load** the running tree with `LocalHeddle.topology`, and write it out with `toDocument()`.
   Keep the loaded tree as `startedFrom`; see
   [Showing an admin what an edit changes](#showing-an-admin-what-an-edit-changes).
2. **Edit by path** with `setWeight`, `addChild`, `rename` and the rest, each returning a
   `TopologyEdit`. A `Refused` names the node to blame; see
   [Editing the tree as a document](#editing-the-tree-as-a-document).
3. **Validate** with `Topology.from`. Every `TopologyProblem` carries the path to edit; see
   [Loading the tree from a config file](#loading-the-tree-from-a-config-file).
4. **List what the save changes** with `startedFrom.changesTo(candidate)`, and warn on each change
   whose `history` is `LOST`; the list is under
   [Showing an admin what an edit changes](#showing-an-admin-what-an-edit-changes).
5. **Preview** with `previewApply(candidate, generation, startedFrom)`; see
   [Previewing a change to the tree](#previewing-a-change-to-the-tree).
6. **Save** with the checked `apply(candidate, generation, startedFrom)`. On
   `ApplyOutcome.TreeChanged`, reload from its `current` and go round again; see
   [two admins at once](#showing-an-admin-what-an-edit-changes).

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleMinimalEditor -->
```kotlin
    val (heddle, _) = LocalHeddle.fromTopology(twoLanes(), LocalPolicy(defaultReservation = 3L), epoch = 0L)

    // Load: the editor opens on the running tree, written out as a document.
    var startedFrom = heddle.topology
    var document = startedFrom.toDocument()

    // Edit by path. Renaming light to its sibling's name is refused, and the page marks that sibling.
    val clash = document.rename(listOf(1), "heavy")
    check(clash is TopologyEdit.DuplicateName && clash.path == listOf(0))
    document = (document.rename(listOf(1), "interactive") as TopologyEdit.Edited).document
    document = (document.addChild(emptyList(), TopologyDocument.Lane("batch", weight = "0")) as TopologyEdit.Edited).document

    // Validate. A problem's path is the path to edit, so the page fixes the weight where it points.
    val problem = (Topology.from(document) as TopologyParse.Invalid).problems.single()
    check(problem.path == listOf(2) && problem.kind == TopologyProblem.Kind.INVALID_WEIGHT)
    document = (document.setWeight(problem.path, "1") as TopologyEdit.Edited).document
    val candidate = (Topology.from(document) as TopologyParse.Valid).topology

    // List what the save changes against the tree the editor loaded. A new tree is refused, not
    // saved; the changes that lose history are the warning, and here the rename drains light.
    val changes = startedFrom.changesTo(candidate)
    check(changes.none { it.saveEffect == TopologyChange.SaveEffect.NEW_TREE })
    val warnings = changes.filter { it.saveEffect == TopologyChange.SaveEffect.LOST }
    check(warnings.single().names == listOf("pool", "light"))

    // Preview what the save would do to the running engine, checked against the same tree.
    val preview = heddle.previewApply(candidate, generation = 2L, startedFrom = startedFrom)
    check(preview is PreviewOutcome.Previewed && preview.preview.result.refusals.isEmpty())

    // Meanwhile another admin saves a new weight for heavy.
    val theirs = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(3)), Topology.Lane("light")))
    val theirSave = heddle.apply(theirs, generation = 1L, startedFrom = startedFrom)
    check(theirSave is ApplyOutcome.Applied && theirSave.result.refusals.isEmpty())

    // Checked apply. Saved as it stands, the edit would undo theirs, so it is refused and changes nothing.
    val refused = heddle.apply(candidate, generation = 2L, startedFrom = startedFrom)
    check(refused is ApplyOutcome.TreeChanged && refused.current == theirs)

    // So the page reloads from the running tree, the admin makes the edit again, and the save keeps theirs.
    startedFrom = refused.current
    document = (startedFrom.toDocument().rename(listOf(1), "interactive") as TopologyEdit.Edited).document
    document = (document.addChild(emptyList(), TopologyDocument.Lane("batch")) as TopologyEdit.Edited).document
    val redone = (Topology.from(document) as TopologyParse.Valid).topology
    check(startedFrom.changesTo(redone).none { it is TopologyChange.WeightChanged })
    check(heddle.previewApply(redone, generation = 2L, startedFrom = startedFrom) is PreviewOutcome.Previewed)
    val saved = heddle.apply(redone, generation = 2L, startedFrom = startedFrom)
    check(saved is ApplyOutcome.Applied && saved.result.refusals.isEmpty())
```

- **The page's state is two values:** the tree it loaded (`startedFrom`) and the document being
  edited. Every check after an edit is against `startedFrom`, never against the previous edit, so
  a rename and the rename back show no change at all.
- **Each change says what it costs in `history`.** The page warns on every `LOST`, which takes in
  a `Removed`, a `WeightChanged`, a `TemplateRemoved`, and a `TemplateChanged` with a new weight,
  without listing them itself. It refuses a `NEW_TREE`, a renamed root, before it previews,
  because `apply` throws on it.
- **Preview and save with the same `generation`**, and preview again after a reload: the tree the
  preview was taken against has gone.
- **A save refused as `TreeChanged` changed nothing.** The page does not retry it blind. It lists
  the changes again from `current`, and saves once the admin has confirmed them.

### Each group's share on the page

The page shows every row's share of the pool beside its weight: a team as well as its lanes.
`entitlement` answers only for a lane, so a team summed from its lanes would be fairness arithmetic
in the page. Read one `ShareLine` per table instead, and look each row up.

**Primitive:** `LocalHeddle.shareLine(waiting)` or `LocalHeddleView.shareLine(waiting)` →
`ShareLine`, then `share(group)` → `Rational`, a fraction of `1` (`:kuilt-heddle-core`).

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/ShareLineSamples.kt#sampleShareLine -->
```kotlin
    // Docs has nothing queued, so research (weight 2) and ops (weight 1) split the pool 2 : 1.
    val queued = setOf(gpu, cpu, ops)
    val line = heddle.shareLine(queued)
    check(line.share(pool) == Rational.ONE)
    check(line.share(research) == Rational.of(2L, 3L))
    check(line.share(ops) == Rational.of(1L, 3L))
    check(line.share(docs) == Rational.ZERO)
    // Research's share is its lanes' entitlements of one slot, added up.
    check(line.share(gpu) == heddle.entitlement(gpu, queued, totalSlots = 1L))
    check(line.share(research) == heddle.entitlement(gpu, queued, 1L) + heddle.entitlement(cpu, queued, 1L))

    // With nothing queued anywhere, nothing competes, and even the pool is owed nothing.
    check(heddle.shareLine(emptySet()).share(pool) == Rational.ZERO)
```

- **A share is its weight's fraction among the siblings that compete, times its parent's, up to
  the root.** A sibling competes when work it could run is waiting below it, below every ceiling,
  the same set `entitlement` counts; one that does not takes nothing from the rest. The root is
  `1` when anything competes and `0` when nothing does.
- **A ceiling is all or nothing.** A group at its `maxConcurrent` is owed `0`; one below it keeps
  its whole weight share, even when that is more than the leases it has room for. A share is not
  a capped rate.
- **A line keeps what it read.** Take a new one when the waiting set or the tree changes, and,
  for `lag`, after any lease, since it reads the books. Each row is worked out when the page asks
  for it, so a share too fine to fit a `Long` fraction throws `ArithmeticException` for that row
  alone.

### How far each group stands from its share

The page shows, beside each row's share, how far the row is from it: owed two units, or one unit
ahead. Don't subtract `explain`'s two virtual times, which it reports only for the one level where
a leaf loses. The same line answers it for every row.

**Primitive:** `ShareLine.lag(group)` → `Rational?`, in units of service (`:kuilt-heddle-core`).

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/ShareLineSamples.kt#sampleShareLineLag -->
```kotlin
    // Both wait, but this slot can run only web: web takes one unit, half a unit past its share.
    check(heddle.acquire(both, eligible = setOf(web), startedAt = 0L) is AcquireOutcome.Acquired)
    val line = heddle.shareLine(both)
    check(line.lag(web) == Rational.of(-1L, 2L))
    check(line.lag(mobile) == Rational.of(1L, 2L))
    // Docs has nothing queued and the pool has no siblings: neither stands anywhere.
    check(line.lag(docs) == null && line.lag(pool) == null)
```

- **Positive is owed, negative is ahead.** It is `weight × (front − position)` at the group's
  parent, so the lags of one parent's competing children add up to `0`. Where `explain` answers
  `BehindAtLevel` for a leaf beneath the group, it equals `weight × (frontWide −
  leafSideVirtualServiceWide)` for every outstanding below `Long.MAX_VALUE`. Only a competing
  child holding exactly `Long.MAX_VALUE`, far past the lifetime limit, is counted idle by the pick
  and not by the line (#3707).
- **It is read as the next pick there would read it.** A group that comes back after idling is
  moved first, as the pick moves it: one the engine saw stop keeps the lag it left with; one
  clamped, or a newborn, reads `0` or under a sixteenth of a unit below, or above zero under a
  `sleeperCredit`. So a group just served is not always below zero.
- **`null` where there is no distance to read:** the root, a group that does not compete (off the
  root, one whose `share` is `0`), and the children of a parent whose weights fail the weight
  budget or whose step passes the position limit, which still compete and have a share. It
  throws only when the lag itself does not fit a `Long` fraction, never because the parent has run
  a long time.

## Editing the tree as a document

**Intent:** an editor changes one weight three levels down, adds a lane, renames or moves a
group, or turns on children the app attaches while running, without copying every group on the
way to it by hand.
**Primitive:** the edits on `TopologyDocument` (`:kuilt-heddle-core`): `setWeight`,
`setMaxConcurrent` (`null` clears it), `addChild`, `remove`, `rename`, `move` and `setTemplate`
(`null` removes the template). Each takes a
path and returns a `TopologyEdit`: `Edited` with the new document, or a refusal naming the path to
blame. None throws, and the document it was asked of is unchanged.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyDocumentEdits -->
```kotlin
    var document = running.toDocument()

    // A path is child indices, as on a TopologyProblem: [0, 1] is acme's batch lane.
    val reweighted = document.setWeight(listOf(0, 1), "2")
    check(reweighted is TopologyEdit.Edited && reweighted.history == TopologyEdit.History.SHARE_RESTARTS)
    document = reweighted.document

    // history is what one edit does: a rename replaces hobby, and so does renaming it back.
    val renamed = document.rename(listOf(1), "hobbyists")
    check(renamed is TopologyEdit.Edited && renamed.history == TopologyEdit.History.REPLACED)
    val back = renamed.document.rename(listOf(1), "hobby")
    check(back is TopologyEdit.Edited && back.history == TopologyEdit.History.REPLACED)
    document = back.document

    // A template lets the app attach children to hobby while it runs, here two levels deep.
    // Adding one costs nobody history; removing it later would drain the attached children.
    val attaching = document.setTemplate(listOf(1), TopologyDocument.ChildTemplate("2", template = TopologyDocument.ChildTemplate()))
    check(attaching is TopologyEdit.Edited && attaching.history == TopologyEdit.History.KEPT)
    document = attaching.document

    // An edit that would give two siblings one name is refused, naming the sibling that has it.
    val refused = document.addChild(listOf(0), TopologyDocument.Lane("batch"))
    check(refused == TopologyEdit.DuplicateName(listOf(0, 1), "batch"))

    // The warning before saving compares with the tree the editor loaded: the round trip is gone.
    val candidate = Topology.from(document)
    check(candidate is TopologyParse.Valid)
    val attached = Topology.ChildTemplate(Weight.of(2), template = Topology.ChildTemplate(Weight.ONE))
    check(
        running.changesTo(candidate.topology) ==
            listOf(
                TopologyChange.WeightChanged(listOf(0, 1), listOf("pool", "acme", "batch"), Weight.ONE, Weight.of(2)),
                TopologyChange.TemplateAdded(listOf(1), listOf("pool", "hobby"), attached),
            ),
    )
```

- **A path is child indices, the address a `TopologyProblem` and a `TopologyChange` carry.** The
  path a problem comes back with is the path to edit. Two siblings of one name, or a child with
  none, still have an address each, so an editor can fix the duplicate it was just told about.
- **An index is a place, not a node.** `remove` and `move` shift later siblings up one, and
  `Edited.path` says where the edit landed in the new document. `addChild` and a move append, so
  they never shift an existing sibling.
- **`Edited.history` is what this one edit does, not the warning before saving.** `REPLACED` for
  a rename or a move to another parent, which drain the old group and start a new one with no
  history; `DRAINS` for a removal; `SHARE_RESTARTS` for a new weight; `NEW_TREE` for a renamed
  root, for which `LocalHeddle.apply` throws `IllegalArgumentException`. A ceiling, a reorder and
  an edit that changes nothing are `KEPT`. Edits cancel out: a rename and the rename back are
  `REPLACED` twice and change nothing. So the warning before saving is
  `loaded.changesTo(candidate)`, the tree the editor started from against the edited one, read
  through each change's `history`.
- **`setTemplate` replaces a group's whole template, nested ones included,** so change one field
  with `template.copy(...)`. Its `history` is what happens to the children attached under it:
  `KEPT` for a template added, a ceiling or a nested level added; `SHARE_RESTARTS` for a new
  weight at any level; `DRAINS` for a template or a nested level removed.
- **Edits check the shape, not the values.** A bad weight, an empty name, a negative ceiling or a
  group left empty go in as a file would put them, and `Topology.from` reports them. The refusals
  are `NoSuchNode`, `NotAGroup`, `DuplicateName`, `AtRoot` (the root has no weight, ceiling,
  template or parent) and `IntoItself` (a move under its own subtree).

These edits are one step of an admin page's loop. Listing what the edit changes, previewing it and
saving it only if nobody else saved first are in
[Showing an admin what an edit changes](#showing-an-admin-what-an-edit-changes).

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
    check(heddle.complete(running.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
    check(heddle.complete(leftover.id, actualCost = 2L, waiting = emptySet()) == LeaseOutcome.Done(charged = 2L, overrun = 0L, unrecorded = 0L))

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

For a reload's raw steps, `Topology.diff(new, ledger, generation)` lists the `TopologyStep`s from
what the ledger holds to `new` without applying them. It reads which edges exist from the ledger rather
than from the old tree, so it works after a restart, and after a partial apply it lists exactly
the rest. `apply` runs that same list. Those steps are for an interpreter; to show a person what
an edit changes, see the next section, and to see what applying it would do to the running
engine, see [Previewing a change to the tree](#previewing-a-change-to-the-tree).

## Showing an admin what an edit changes

**Intent:** an admin page that edits the tree while it runs — start the editor from the tree that
is running, and before applying an edit, show the admin what it changes in words they recognise,
each change beside the line it is at.
**Primitive:** `LocalHeddle.topology`, `Topology.toDocument()` and `Topology.changesTo(after)` →
`TopologyChange`s, and the checked `LocalHeddle.apply(new, generation, startedFrom)` →
`ApplyOutcome` (`:kuilt-heddle-core`). `heddle.topology` is the running tree, the same one
`persist()` stores as `lastApplied`. `toDocument` writes it out as the `TopologyDocument` an editor
starts from and edits by path ([Editing the tree as a document](#editing-the-tree-as-a-document)),
and `Topology.from` reads the edited one back, an exact round trip:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyToDocument -->
```kotlin
    val document = running.toDocument()
    // Weights are written the way an admin writes them: a whole number, or a ratio.
    val acme = document.children.single() as TopologyDocument.Group
    check(acme.weight == "3" && acme.children.first().weight == "3/2")
    // Reading it back gives the same tree.
    check(Topology.from(document) == TopologyParse.Valid(running))
```

`running.changesTo(edited)` then lists the changes in document order, each addressed like a
`TopologyProblem`: child indices from the root, and the names along them.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyChanges -->
```kotlin
    check(
        running.changesTo(edited) == listOf(
            TopologyChange.WeightChanged(listOf(0), listOf("pool", "acme"), Weight.of(3), Weight.of(4)),
            TopologyChange.CeilingChanged(listOf(0, 0), listOf("pool", "acme", "interactive"), null, 2L),
            // A removed child is addressed where it was, in the running tree.
            TopologyChange.Removed(listOf(0, 1), listOf("pool", "acme", "batch"), Topology.Lane("batch")),
            TopologyChange.Added(listOf(1, 1), listOf("pool", "hobby", "nightly"), Topology.Lane("nightly")),
        ),
    )
```

- **There is no "moved".** A group is named by its path, so a move or a rename is a `Removed` and
  an `Added`: the old child keeps its history and drains, the new one starts with none. Say so in
  the dialog rather than presenting a move as free.
- **A `WeightChanged` restarts that child's share**; a change of ceiling, of template ceiling, or
  of kind (`LaneBecameGroup`, `GroupBecameLane`) keeps its history.
- **A `TemplateRemoved` cannot be undone.** Every child the app attached under that group is
  detached for good, and putting the template back brings none of them back. A `TemplateChanged`
  with a new weight restarts every attached child's share; a new template ceiling keeps their
  history.
- **A `Removed` is addressed in the running tree**, since it is not in the edited one; every other
  change is addressed in the edited tree.
- **Reordering siblings is not a change**: nothing in heddle reads the order.
- **A `RootRenamed` replaces every child** and `Topology.diff` refuses it: it is a new tree, not an
  edit.

**Warn from `history`, not from a list of arms.** Each `TopologyChange` says what landing it costs
as `TopologyChange.SaveEffect`: `LOST` when a group loses its history, the changes above that drain,
restart a share or detach for good; `KEPT` when nobody loses any; and `NEW_TREE` for a renamed
root, which `apply` throws on. So a page warns on every `LOST` and refuses the save on a
`NEW_TREE`, without sorting the arms itself:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/TopologySamples.kt#sampleTopologyChangeHistory -->
```kotlin
    val running = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive")
            lane("batch")
        }
        group("tenants") { template(Weight.of(2)) }
    }
    val edited = draft("pool") {
        group("acme", Weight.of(3)) {
            lane("interactive", maxConcurrent = 2)
            lane("nightly")
        }
        // A new template ceiling keeps the attached children's history; a new weight would not.
        group("tenants") { template(Weight.of(2), maxConcurrent = 4) }
    }
    val changes = running.changesTo(edited)
    check(changes.none { it.saveEffect == TopologyChange.SaveEffect.NEW_TREE })
    // Of four changes, only the removal of batch costs anyone their history.
    val warnings = changes.filter { it.saveEffect == TopologyChange.SaveEffect.LOST }
    check(changes.size == 4 && warnings.map { it.names } == listOf(listOf("pool", "acme", "batch")))

    // A renamed root is a new tree, which apply throws on, so the save is refused, not warned about.
    val renamed = draft("farm") { lane("batch") }
    check(running.changesTo(renamed).first().saveEffect == TopologyChange.SaveEffect.NEW_TREE)
```

To compare two documents, check each with `Topology.from` first: a change is defined only
between valid trees.

**Two admins at once.** Keep the tree the edit was loaded from, and save with the checked
`apply(new, generation, startedFrom)`. It applies only while `heddle.topology` is still that tree.
If someone else applied a tree in between, the edit as it stands would undo their change too, so
the save returns `ApplyOutcome.TreeChanged` naming the running tree, and changes nothing. List the
changes again from that tree, and save from it once someone has confirmed them. The checked
`previewApply` takes the same `startedFrom`, so a page never previews what its save would refuse:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleCheckedApply -->
```kotlin
    // Both pages load the running tree, and each admin adds a lane.
    val startedFrom = heddle.topology
    val first = startedFrom.withLane("batch")
    val second = startedFrom.withLane("nightly")

    // Work goes on while they edit: a lease moves the revision, but not the tree.
    val seen = heddle.revision
    check(heddle.acquire(setOf(heavy), setOf(heavy), startedAt = 0L) is AcquireOutcome.Acquired)
    check(heddle.revision != seen && heddle.topology == startedFrom)

    // The first admin sees the change and its preview, confirms, and the save applies what was shown.
    check(startedFrom.changesTo(first) == listOf(TopologyChange.Added(listOf(2), listOf("pool", "batch"), Topology.Lane("batch"))))
    val preview = heddle.previewApply(first, generation = 1L, startedFrom = startedFrom)
    check(preview is PreviewOutcome.Previewed && preview.preview.result.refusals.isEmpty())
    check(heddle.apply(first, generation = 1L, startedFrom = startedFrom) == ApplyOutcome.Applied(preview.preview.result))

    // The second edit started from a tree that is no longer running. Saved as it stands, it would
    // also take away the lane the first admin just added, so the save refuses and changes nothing.
    val before = heddle.revision
    val refused = heddle.apply(second, generation = 2L, startedFrom = startedFrom)
    check(refused is ApplyOutcome.TreeChanged && refused.current == first && heddle.revision == before)
    check(
        refused.current.changesTo(second) == listOf(
            TopologyChange.Added(listOf(2), listOf("pool", "nightly"), Topology.Lane("nightly")),
            TopologyChange.Removed(listOf(2), listOf("pool", "batch"), Topology.Lane("batch")),
        ),
    )

    // So the page reloads: the admin makes the edit again on the running tree, and saves from that.
    val redone = refused.current.withLane("nightly")
    check(refused.current.changesTo(redone) == listOf(TopologyChange.Added(listOf(3), listOf("pool", "nightly"), Topology.Lane("nightly"))))
    check(heddle.apply(redone, generation = 2L, startedFrom = refused.current) is ApplyOutcome.Applied)
    check(heddle.topology == redone)
```

- **It compares trees, not revisions.** `heddle.revision` moves on every `acquire`, so a save
  checked against it would refuse every confirm on a busy engine. Leases, charges, `attach` and
  `detach` leave the tree as it is, so none of them refuses a save.
- **The tree is compared as written.** A change that only reordered siblings refuses a save too,
  though `changesTo` lists nothing for it. Listed again from the running tree, such an edit shows no
  change it did not show before.
- **`topology` is the tree last asked for**, so a group a reload left out is still in it;
  `ApplyResult` says what landed. Reading it moves nothing.

## Previewing a change to the tree

**Intent:** an admin edits the tree and wants to see what the edit would do before confirming it:
which change waits on which running job, and what each group's share is straight after. Do not
hand-roll a copy of the engine. Restoring a snapshot and applying on the copy gives the right
answer, but `restore` needs a fresh epoch above the snapshot's, and the copy has no `basis` to say
whether the engine has moved since. `previewApply` does the same with neither cost.
**Primitive:** `LocalHeddle.previewApply(new, generation)` → `TopologyPreview`. Its `result` is
exactly the `ApplyResult` that `apply` would return, and `after` is a `LocalHeddleView` of the
engine straight after it, to ask `entitlement`, `explain` and `previewPicks`. It is a read: the
engine, its `revision` and what it persists are unchanged.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#samplePreviewApply -->
```kotlin
    // The edit: heavy goes to weight 3 and spare is removed, while spare still runs a job.
    val edited = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(3)), Topology.Lane("light")))
    val seen = heddle.revision
    val preview = heddle.previewApply(edited, generation = 1L)

    // Spare would close at once and wait for its job; nothing else waits.
    val waits = preview.result.refusals.single()
    check(waits is StepRefusal.Draining && waits.child == spare && waits.blockingLeases == listOf(running))
    // Straight after, spare is owed nothing, and heavy and light share the slots 3 : 1.
    val queued = setOf(heavy, light, spare)
    check(preview.after.entitlement(heavy, queued, totalSlots = 4L) == Rational.of(3L))
    check(preview.after.entitlement(light, queued, totalSlots = 4L) == Rational.ONE)
    check(preview.after.entitlement(spare, queued, totalSlots = 4L) == Rational.ZERO)
    // The engine itself has not moved: spare keeps its share until the apply.
    check(heddle.revision == seen && preview.basis == seen)
    check(heddle.entitlement(spare, queued, totalSlots = 4L) == Rational.ONE)

    // The admin confirms. Nothing happened in between, so the apply does what the preview said.
    check(heddle.revision == preview.basis)
    check(heddle.apply(edited, generation = 1L) == preview.result)
```

- **It answers for straight after, not once things settle.** The two differ only where `result`
  says so, and no refused change lands by itself: `complete` and `cancel` never re-run a reload. A
  `StepRefusal.Draining` lands on a later `apply` of the same topology, once its `blockingLeases`
  have settled. A `StepRefusal.Refused` is for a reason that waiting on the engine's leases does
  not cure, save one: a reweight whose lease cannot move within `Long` is refused until that lease
  settles, and then lands on a later `apply` too. A removed lane is owed nothing at once; ceilings,
  lanes and moved leases are already in `after`.
- **Preview and apply with the same `generation`.** It names the edges a reload adds, so a
  different one gives different edge ids in `applied`.
- **`basis` is the `revision` it was worked out at.** While `heddle.revision == preview.basis`, the
  apply does what the preview said. A revision moves on every `acquire`, so on a busy engine it
  will have moved by the time a person confirms: preview again rather than refuse the edit.
  `apply` still diffs against the ledger it applies to, so applying anyway is correct, just not
  necessarily what was shown.
- **Whether someone else changed the tree is a different question**, and `basis` does not answer
  it. Pass the tree the edit was loaded from as `startedFrom` to both `previewApply` and `apply`,
  as [two admins at once](#showing-an-admin-what-an-edit-changes) shows.
- **A page with no engine previews a stored snapshot** the same way, with the policy the engine
  ran with. `basis` is then `null`.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#samplePreviewApplyFromSnapshot -->
```kotlin
    // On the admin screen: decode what was stored, and preview the edit with the engine's policy.
    val decoded = LocalHeddle.decode(stored, Json)
    check(decoded is RestoreResult.Restored)
    val edited = Topology("pool", listOf(Topology.Lane("heavy", Weight.of(3)), Topology.Lane("light")))
    val preview = decoded.snapshot.previewApply(policy, edited, generation = 1L)
```

- **Shares under a tree with nothing running yet need no preview.** Boot a throwaway engine from
  the candidate with `LocalHeddle.fromTopology` and ask it `entitlement`:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleSharesOverACandidate -->
```kotlin
    val (scratch, booted) = LocalHeddle.fromTopology(candidate, LocalPolicy(defaultReservation = 3L), epoch = 0L)
    check(booted.refusals.isEmpty())

    // Eight agents, both teams busy: web is owed six, mobile two.
    val busy = setOf(web, mobile)
    check(scratch.entitlement(web, busy, totalSlots = 8L) == Rational.of(6L))
    check(scratch.entitlement(mobile, busy, totalSlots = 8L) == Rational.of(2L))
```

## Children your app attaches while running

**Intent:** groups the program discovers as it runs — a tenant, a repository, a contributor's
fork — each with its own fair share, without rewriting the config.
**Primitive:** a group with a `template` in the `Topology`, then
`LocalHeddle.attach(group, name, generation)` and `detach(group, name)`, or `attach(path,
generation)` and `detach(path)` with a `GroupPath` for children attached beneath attached ones. The
template gives each attached child its weight and its own `maxConcurrent`; the names are opaque to
heddle, of any spelling, and never collide with a declared child.

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
edge once its books close, so detach a child whenever it goes idle; detaching a group forgets
the edges beneath it and then its own, in the same swap. Re-attaching a child whose
detach is still draining moves its leases onto the new edge; a group whose detach is draining
waits for the leases beneath it instead. A reload that keeps the template
keeps every attached child and its history; one that drops the template closes them.
`Topology.attachedChildId(group, name)` names the child's id.

**A tree you discover two levels deep** — a repository, then its authors — needs no reload per
repository. Give the template a `template` of its own: each attached repository is then a group,
never leased itself, and each author is attached beneath it with a `GroupPath.Nested`.
`attach(path, generation)` attaches the repository too if it is new, in the same unit; weights are
per template, so every repository weighs the same, and every author beside its siblings. Do not
re-`apply` the tree for each repository you find: that rewrites the admin's document, and an
admin's open save then refuses as `TreeChanged`.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleNestedAttach -->
```kotlin
    // Attaching an author attaches its repository too, in the same unit.
    val kuilt = GroupPath.Attached(listOf("repos"), "kuilt")
    val alice = GroupPath.Nested(kuilt, "alice")
    check(heddle.attach(alice, generation = 1L).refusals.isEmpty())
    val aliceId = checkNotNull(ci.findGroupId(alice))
    check(aliceId == GroupId("ci/repos/!kuilt/!alice") && ci.findPath(aliceId) == alice)
```

`detach(path)` on a repository closes every author beneath it at once and retires them bottom-up,
each `Draining` while its leases run; attaching the repository again brings no author back. In a
config file the same shape is `template: { weight: "2", template: { weight: "1" } }`.

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
`LifetimeExhausted(parent)` (the next pick stalls at `parent` at the lifetime limit), `BehindAtLevel(parent, nextChild, …)` at the first group where another
child goes first, or `WouldBePickedNext`. `BehindAtLevel` carries the leaf side's virtual service
beside two lines: `eligibleFront`, the line this pick drew over the children it could run (more
than one sixteenth of a unit of the side's own service above it, the side was not a candidate; at
or below, it lost on its deadline), and `front`, the share line over everyone waiting (above it,
the side has had more than its share). **Log `kind`**, a stable ordered enum, and the group ids,
not the rationals, which move every pick. `entitlement` is the leaf's fair share of the slots,
as an exact fraction, over the same competing set: work no pick can reach is owed nothing. For a
group's share, read a `ShareLine`; see [Each group's share on the page](#each-groups-share-on-the-page).

`previewPicks` comes in two shapes. Give it a count per leaf and one reservation per slot, or
give it each leaf's queue of job estimates, head first, and each pick does what your loop would:
`acquire` at the slot's reservation, then `extend` to the winner's head when that is larger, or
`shrink` to it straight after the pick when that is smaller (#3648). The slot's reservation decides
each pick, and the head decides what the lease holds.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#samplePreviewQueues -->
```kotlin
    // Each lane's queued builds, by their estimates in minutes, the next one first.
    val queues = mapOf(web to listOf(3L, 12L, 3L, 3L), mobile to listOf(3L, 1L, 3L))
    val both = setOf(web, mobile)
    val before = heddle.snapshot()
    val preview = heddle.previewPicks(queues, eligible = both)
    check(preview == listOf(web, mobile, web, mobile, mobile, web, web))
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

## Has anything changed since the last pass

**Intent:** a loop wakes up and wants to skip a scheduling pass when none of its inputs changed,
without copying the engine to find out. Do not hand-roll a dirty flag beside the engine or diff two
`snapshot()`s.
**Primitive:** `LocalHeddle.revision` → `LocalHeddleRevision(epoch, count)`. It costs one field
read. Every call that can change the engine moves it: `acquire`, `admit`, `extend`, `complete`,
`cancel`, `reconcileCapacity`, `apply`, `attach` and `detach`, refusals included. Reads and calls
that throw leave it alone.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleRevision -->
```kotlin
    // The last pass saw this. Asking who goes next changes nothing, so the next pass can skip.
    val seen = heddle.revision
    heddle.previewPicks(mapOf(heavy to 1, light to 1), queued, listOf(3L))
    check(heddle.revision == seen)

    // A lease moves it, so the next pass looks again.
    check(heddle.acquire(queued, queued, startedAt = 0L) is AcquireOutcome.Acquired)
    check(heddle.revision > seen)

    // It is not persisted, but a restore runs under a higher epoch, so the restored engine's
    // revision is above every one the old engine had: a pass that saw the old one sees a change.
    val restarted = LocalHeddle.restore(heddle.persist(), policy, epoch = 1L)
    check(restarted.revision > heddle.revision)
```

Equal revisions mean nothing changed. Unequal ones mean something may have: a refused `acquire`
can still move the wake memory, so it moves the revision too. Compare the whole value, not
`count` alone. `count` restarts at zero on a restored engine, and only the epoch keeps it above
the old one. Read it under your scheduling lock, as you read the rest of the engine.

## Charging work started elsewhere

**Intent:** someone started a job by hand, or another scheduler placed it, and it must still be
charged to its group like any other, with no pick.
**Primitive:** `LocalHeddle.admit(leaf, waiting, startedAt)`, with `waiting` every leaf that has
work queued. It opens a lease at the leaf without a pick and is never refused for supply or at a
ceiling, since the work is already running; a shortfall at the root is minted. Settle the lease with
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
    check(heddle.complete(another.lease.id, actualCost = 5L, waiting = emptySet()) == LeaseOutcome.Done(charged = 5L, overrun = 2L, unrecorded = 0L))

    // A group with a template and nothing attached is not somewhere a build can run.
    val forks = farm.groupId("forks")
    check(heddle.admit(forks, waiting = setOf(forks), startedAt = 2L) == AcquireOutcome.NoLiveLineage(forks))
```

**Every call that charges takes `waiting`.** `admit`, `extend` and `complete` each observe the
waiting set you pass before their charge lands (#3471), so a sibling that sat idle while you
charged is seen leaving first, and comes back without having banked your charge. `admit` leaves
the wake memory as `acquire(waiting, eligible = {leaf})` would have, wherever that acquire would
lease. `extend` and `complete` count the lease's own leaf as waiting, since the charge is its own running work's, even on its last job. `complete` observes only when it charges an overrun. The only refusal is `NoLiveLineage(leaf)`, for a name that is not leasable or has no
live path from the root.

## Handing back a reservation early

**Intent:** a slot is acquired at the longest job any group might send it, and the group the pick
chose has a shorter one. Holding the difference until `complete` reserves slack nobody can use, and
its refund there can move a sibling.
**Primitive:** `LocalHeddle.shrink(lease, amount)`. It hands `amount` of what the lease reserves
back up the path it was opened on, as `complete` hands back an unused remainder, and the lease stays
open at the smaller size. It charges nothing, observes nothing and is never refused.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleShrinkAfterPick -->
```kotlin
    val queued = setOf(heavy, light)
    val picked = heddle.acquire(waiting = queued, eligible = queued, startedAt = 0L)
    check(picked is AcquireOutcome.Acquired && picked.lease.reserved == 8L)

    // The winner's next job takes 2 units: hand back the other 6 before anything else happens.
    check(heddle.shrink(picked.lease.id, amount = 6L) == LeaseOutcome.Done(charged = 0L, overrun = 0L, unrecorded = 0L))
    check(heddle.snapshot().leases.single().reserved == 2L)
```

**Shrink straight after the pick.** Before any other call, a shrink leaves every position and the
wake memory as if the lease had been opened at the smaller size: the grant and the refund fall in
the same step of the parent's clock. Later it is a refund like any other, and it moves the lane
back as `complete`'s remainder would. `amount` must be in `1..lease.reserved`; outside that it
throws `IllegalArgumentException`. A shrunk lease reserves less wherever the engine counts open
leases, so the wake clamp (#3238) and a restored snapshot both read the smaller size.
`FairSharePool` and `previewPicks(queues, …)` already shrink at the grant: a waiter or a queued head
below the slot's reservation gets a lease holding exactly its own.

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
    check(restarted.complete(running.id, actualCost = 3L, waiting = queued) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
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
  `SUPPORTED_FORMAT_VERSIONS` what it reads: format 1, and only format 1, the first frozen format.
  Format 2, from a development build of 0.7.5, decodes as `UnsupportedVersion`. A snapshot 0.7.4
  stored also says format 1, in an older layout this release does not tell apart: do not decode
  it, boot from the topology again.
- **What `decode` checks.** The envelope's shape and version, then consistency: every open lease
  has a reservation that is not negative, an epoch no later than the snapshot's and a path that is its leaf's
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
the documented default is `complete(lease, orphan.defaultSettlement, waiting)`: the time it ran, capped at
`granted`, its reservation plus extensions, because a dead job's overrun cannot be checked. Work
that will resume belongs in `live`, so it never becomes an orphan. An engine inside a
`FairSharePool` is reconciled through `withEngine`, and its orphans settled with `pool.complete`:
see [Waiting your turn for a slot](#waiting-your-turn-for-a-slot), whose restarted pool grants
nothing until they are.

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/LocalHeddleSamples.kt#sampleReconcileLeases -->
```kotlin
    // The executor came back running only `kept`, and one job the engine never leased.
    val stranger = LeaseId(epoch = 7L, seq = 0L)
    val found = heddle.reconcileLeases(live = setOf(kept.id, stranger), now = 110L)
    check(found.unknown == listOf(stranger))
    val orphan = found.orphaned.single()
    check(orphan.lease == lost && orphan.elapsed == 9L && orphan.granted == 3L)

    // It ran 9 units against the 3 it was granted, which a dead job cannot prove: charge 3.
    check(heddle.complete(orphan.lease.id, orphan.defaultSettlement, waiting = queued) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))
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
| `acquire` | `AcquireOutcome`: `Acquired`, `NothingEligible` (empty eligible set, or every eligible leaf capped), `SupplyExhausted` (no capacity reconciled), `NoLiveLineage(root)`, `LifetimeExhausted(parent)` (the lifetime limit, or a child landing at `parent` past the position limit) | — |
| `admit` | `AdmitOutcome`: `Acquired`, `NoLiveLineage(leaf)`; past the lifetime limit `Acquired` with `reserved` 0 | supply, a concurrency ceiling, the lifetime limit |
| `extend` | `LeaseOutcome`: `Done` (with `unrecorded` the whole amount past the lifetime limit), `PathDraining(edge)` (a reload is closing the path), `SupplyExhausted`, `Unknown`, `Stale(currentEpoch)` | a concurrency ceiling, the lifetime limit |
| `complete` | `LeaseOutcome`: `Done(charged, overrun, unrecorded)`, `Unknown`, `Stale(currentEpoch)` | anything: a draining path, an overrun, a ceiling, the lifetime limit |
| `cancel` | `LeaseOutcome`: `Done`, `Unknown`, `Stale(currentEpoch)` | anything |
| `fromTopology` | `LocalHeddle.Boot(heddle, result)`: the engine, and an `ApplyResult` naming every group the boot left out, each a `StepRefusal.Refused` | — |
| `apply`, `attach`, `detach` | `ApplyResult(applied, refusals)`, each refusal a `StepRefusal.Draining` (waits on open leases) or `StepRefusal.Refused` (the ledger will not take it, or a joiner seated beside its furthest-ahead sibling would pass the position limit: that joiner alone) | — |
| `decode` | `RestoreResult`: `Restored`, `UnsupportedVersion`, `Corrupt` | — |

`Unknown` is an id from this engine's epoch that holds no open lease, a second `complete`
included; `Stale` is one from another epoch. On a draining path `extend` is refused but
`complete` still settles: the reservation is charged at the leaf, and an overrun at the deepest
group still reached through live edges. **`Done.unrecorded`** is what the ledger recorded on no
edge, so `charged + unrecorded` is the cost `complete` was given: an overrun under a draining
top-level group (#3493), one above `MAX_RECORDED_OVERRUN`, or one the lifetime limit leaves no
room for. It is `0` everywhere else.

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
    check(heddle.extend(lease.id, amount = 3L, waiting = emptySet()) is LeaseOutcome.PathDraining)
    check(heddle.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Done(charged = 3L, overrun = 0L, unrecorded = 0L))

    // Settled once, the id is unknown to this engine; after a restart it is from an older epoch.
    check(heddle.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Unknown)
    val restarted = LocalHeddle.restore(heddle.persist(), policy, epoch = 1L)
    check(restarted.complete(lease.id, actualCost = 3L, waiting = emptySet()) == LeaseOutcome.Stale(currentEpoch = 1L))

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
together it refuses the one whose id sorts last, and leaves the running tree as it was. A refused new
group takes its new descendants with it, each reported in `refusals` (as `Draining`, on the same
leases, when that group waits on a drain), so no lane activates below a group the root cannot
reach, and an `attach` under one is refused too; a running group whose reweight is refused keeps its old edge, and
a new lane under it lands. A boot
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

`LifetimeExhausted` is not in the sample, because a run reaches it only after about half a
million leases of the largest size (see **The lifetime limit** below).

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
ceiling all the same, past which the public `Rational` reads (`virtualService` and its kin)
report the ceiling, the penalty side. Nothing brings a busy parent's positions back down, so a
long-running parent passes it, and a heavy child joining lighter ones lands far up its own grid
at once. The picks carry on exactly there; only the reads saturate. **Read the wide siblings
instead**: `virtualServiceWide`, `grossVirtualServiceWide`, `frontWide`, `virtualTimeWide`,
`parentVirtualTimeWide`, and `BehindAtLevel`'s `leafSideVirtualServiceWide`, `frontWide` and
`eligibleFrontWide`. Each answers a `VirtualTime`, exact at every position the limit allows,
comparable, with `toDouble()` for a log line. The `Rational` reads stay, deprecated.

**The lifetime limit.** What a long-lived engine meets first is two of the ledger's `Long`
counters: an edge's effective issuance, which counts every reservation, extension and overrun
across it, and the root's minted supply, the sum of every epoch's mint record. A pick keeps
`LocalHeddle.LIFETIME_HEADROOM` (2^58 units) in hand on each: `acquire` answers
`AcquireOutcome.LifetimeExhausted(parent)` rather than carry either past
`Long.MAX_VALUE − LIFETIME_HEADROOM`, and changes nothing but the wake memory. Two weight-1 lanes
at the largest lease, charged in full, run 507,903 leases and then get that answer. Leave the work
queued: nothing clears it. The refusal belongs to the path: a sibling with room is leased when it
alone is eligible, but a full lane that is next in line stalls its parent, and `explain` answers
`WaitExplanation.LifetimeExhausted` for both. Work already running is never refused for the limit,
and no call throws at it. `admit`, `extend` and `complete` use the headroom, up to `Long.MAX_VALUE`,
and record a charge that still does not fit whole or not at all. An `admit` reserves nothing and
its cost arrives as an overrun. An `extend` answers `Done` with the whole amount `unrecorded`.
An overrun is charged at the deepest group every edge above has room for, or reported
`unrecorded`.

One absurd cost cannot end an engine. An overrun above `LocalHeddle.MAX_RECORDED_OVERRUN` (2^57,
half the headroom) is recorded nowhere and reported whole as `unrecorded`, so
`complete(lease, Long.MAX_VALUE)` closes the lease and the next `acquire` leases, even on an engine
that has served nothing else. An `extend`'s `unrecorded` is a reservation that was not made, not a
cost: the work's cost arrives at its `complete`, so sum `unrecorded` over completions only.

**The weight budget.** Keep every weight's denominator at or below 30,719. A weight set so fine
that one step or one full lease could carry a child past its ceiling is refused when it is
applied, as a `StepRefusal.Refused` for that child while its siblings land; so is one whose
weight products would leave `Long`. The limit holds at any lease size, because it is sized for
the largest lease, `MAX_RESERVATION`. It is necessary, not sufficient: several fine weights whose
denominators share no factor are refused below it too.

**Asking before proposing.** An admin form that takes a weight, or a peer about to propose an
activation, can ask the rule itself instead of re-deriving the arithmetic.
`weightFitsTheBudget(weight)` says whether a weight could be activated anywhere, and
`EntitlementLedger.activationFitsTheBudget(edge)` whether activating a prepared edge fits beside
the children already active at its parent. Both are the check `LocalHeddle.apply` and `HeddleNode`
make, asked without applying anything. A weight can pass alone and fail beside a sibling:

<!-- verbatim from kuilt-heddle-core/src/commonSamples/kotlin/us/tractat/kuilt/heddle/WeightBudgetSamples.kt#sampleWeightBudget -->
```kotlin
val heavy = Weight.of(185_000_000_000_000_000L)
val third = Weight.of(1L, 3L)
check(weightFitsTheBudget(heavy) && weightFitsTheBudget(third))

var ledger = EntitlementLedger.bootstrap(root, mapOf(alice to 100L), nonce = "genesis")
ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(toHeavy, root, GroupId("heavy"), heavy))))
ledger = ledger.piece(checkNotNull(ledger.activate(toHeavy)))
ledger = ledger.piece(checkNotNull(ledger.prepare(AttachmentRecord(toThird, root, GroupId("third"), third))))

// Each fits alone, but not together: ask before proposing the activation, not after.
check(!ledger.activationFitsTheBudget(toThird))
```

A `true` from `activationFitsTheBudget` answers the budget only; the position limit can still
refuse the activation.

**Two known fairness gaps**, both smallest when a lease settles at its estimate:

- **A wrong estimate reorders picks and never changes a charge.** An open lease counts at its
  reservation, and `complete` charges the leaf exactly the cost it is given, so a leaf's spend is
  what its completions reported. While a lease is open, a lane that reserves low looks cheaper and
  wins picks it should not, and one that reserves high loses them: closed-loop, about 0.01 of share
  on average at one level. On average a refund is what moves a sibling: on two-level rigs, a lane
  that reserves four times the cost on the job that empties its queue costs its honest sibling
  about 0.03, against 0.003 for one that reserves 1 and `extend`s as it runs. That is the means
  only. In its worst rig the sibling loses 0.0805 beside a lane reserving a quarter of the cost,
  about the 0.0844 it loses beside the refund, and a `FairSharePool.withSlot` caller at a low
  default, which overruns and never extends, is in that regime. So reserve at or below the
  cost: aim `defaultReservation` at or a little below a typical job, never the longest, pass a
  per-lease `reservation` when history gives a better one, and `extend` a lease that reaches what
  it holds. Accepted ([#3239](https://github.com/tractat-us/kuilt/issues/3239)):
  `ReservationLeanTest` pins ten rigs' slot times exactly on every target, and the means and
  worst rigs above are an ensemble benchmark, run on demand only
  ([#3724](https://github.com/tractat-us/kuilt/issues/3724)) with
  `./gradlew :kuilt-heddle-core:jvmTest -Pkuilt.benchmark.tests=true --tests '*ReservationLeanBenchmark*'`.
- **A sibling's refund leaks across a gap.** A *sibling's* reservation counts in the net-service
  clock at the weight of everyone asking, while its refund lands at the weight of whoever is still
  asking, so a lane away across a sibling's refund comes back owed more than it is: 19 / 10 on a
  rig whose fair split is 15 / 14. A lane's own refund across its own gap is read correctly. It is
  the bound [#3239](https://github.com/tractat-us/kuilt/issues/3239) accepted, above.
  `HeddleNode`'s `complete` keeps an unused earmark at the leaf, so `HeddleNode` does not take that
  path.

**`cancel` is for work that never ran** ([#3238](https://github.com/tractat-us/kuilt/issues/3238)).
Work that held its slot settles with `complete(lease, elapsed)`, so a charge is always the cost the
caller reported. A cancel of work that ran charges nothing, and its siblings pay for the ride: a
lane that never idles and cancels one job in four takes 4/7 of the slots beside an equal sibling,
where its share is a half. How much the lease reserved does not change that.

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
that are too fine for one full lease. It clears when one child closes. No position stops a pick:
a pick compares the full 128-bit position, so a long run keeps granting however far its children
climb, at weights as far apart as 60 to 1, across a restart, and with several schedulers granting
one child at once. What a long-running parent does meet is the lifetime limit:
`LifetimeExhausted(parent)` when the first grant would carry the picked edge's effective issuance
past `Long.MAX_VALUE − LocalHeddle.LIFETIME_HEADROOM` on this peer's view (#3475). It lasts while
that edge is picked. Peers that grant one edge before their writes merge can pass the watermark
together; the headroom absorbs 16,384 such grants of the largest size, and every read still
answers.

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
        // The pick chose an edge near the end of its Long lifetime counter. Leave the work queued: nothing clears it.
        is ScheduleOutcome.LifetimeExhausted -> "exhausted at ${outcome.parent.value}"
        // The active children's weights put a pick's sums out of Long, as gossip can merge. Close one of them.
        is ScheduleOutcome.WeightBudget -> "stuck at ${outcome.parent.value}: close a child, its weights cannot be summed"
        // Only a governed node has a write gate; a static node never answers this.
        is GateClosed -> "gate closed: ${outcome.reason}"
    }
```

`BoundMetrics.peakPositionFraction` from `boundMetrics(parent)` says how far the parent has
climbed: the highest child position as a fraction of its ceiling. A long-running parent passes
one; the ledger's virtual-time reads saturate there, and the picks do not change.

## Repairing a reweight on a static cluster

**Intent:** you changed the weight of a group that has children of its own, on peers booted with
`heddleStatic`, after work had already run under it. The group's holdings now read negative and
`validate()` reports `PersistentNegativeHoldings`, because that work was charged through the old
edge and the new one starts empty.
**Primitive:** `HeddleNode.reconcileOwn(child)` (`:kuilt-heddle`), called by **every** peer for
itself once the new edge is active. Each peer moves only its own share of the old edge onto the
new one, so the peers never have to agree on anyone else's numbers, and their repairs join cleanly
in any order, partitioned or not. The ledger validates clean once every peer that spent through
the old edge has called it.

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleReconcileOwn -->
```kotlin
internal fun HeddleNode.sampleReconcileOwn(child: GroupId): String =
    when (val outcome = reconcileOwn(child)) {
        // Moved: this peer's share of the old edges now rides the live edge.
        is ReconcileOwnOutcome.Repaired -> "moved ${outcome.credit} credit and ${outcome.spent} spent onto ${outcome.onto.value}"
        // This peer's row is already drained. Calling again is harmless.
        ReconcileOwnOutcome.NothingToRepair -> "nothing of mine to move"
        // The retire, or the new edge's activation, has not reached this peer yet. Call again later.
        ReconcileOwnOutcome.NoRetiredEdge -> "no retired edge yet"
        ReconcileOwnOutcome.NoLiveEdge -> "no live edge yet"
        // The rest write nothing and do not clear by themselves: the tree needs an operator.
        is ReconcileOwnOutcome.TwoLiveEdges -> "close all but one of ${outcome.edges.map { it.value }}"
        is ReconcileOwnOutcome.CrossParent -> "retired edges hang off ${outcome.foreign.values.map { it.value }}, not ${outcome.liveParent.value}"
        is ReconcileOwnOutcome.Refused -> "refused: ${outcome.reason}"
    }
```

- **A reservation still open across the old edge is safe.** Once the move is made, the call marks
  the old edge, so completing such a reservation afterwards charges the new edge instead. A
  refused call marks nothing. A restart forgets the mark, and the reservation with it.
- **Repair after a restart only once the peer has caught up.** A static peer gets its own earlier
  writes back from the others. If it repairs before they arrive, it moves too little, and a late
  write it made on the old edge stays stranded there for good.
- **A move across parents is refused.** Moving a group under another parent strands it the same
  way. Nothing conserves across two parents, so if any retired edge hangs off another parent,
  nothing moves and `CrossParent` names both parents.
- **Two live edges into one group** answer `TwoLiveEdges`: close all but one, then call again.
- **A governed node has no such call.** There, `GovernedHeddleNode.reconcile` re-homes the strand
  through the log, as the next section describes.

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
