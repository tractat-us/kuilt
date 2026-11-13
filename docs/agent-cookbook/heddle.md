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
front. Nothing public applies the steps yet: the single-writer engine's `apply` (Task L3 of #3173)
will, under one lock, against the ledger it diffed.

Children the app creates while running go under a group marked `runtimeChildren`.
`Topology.attachRuntimeChild(group, name, ledger, generation)` and
`detachRuntimeChild(group, name, ledger)` list the steps for one child, at the marker's weight,
under a name of any spelling that never collides with a declared child. A reload that keeps the
marker keeps the child and its history; one that drops the marker closes it. Every detach leaves a
retired edge in the ledger for good, so keep a child that comes back often attached between uses.

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
another scheduler already started, and `previewPicks` answers "who goes next" without changing
anything. `startedAt` is stored on the lease and never read by a pick. A pick only goes down a
subtree it can finish in, so a group whose only leaf sits behind a closing edge never takes a slot
from a sibling that can run. A refusal is a typed outcome (`NothingEligible`, `SupplyExhausted`,
`NoLiveLineage`, `PathDraining`, `Unknown`, `Stale`), never an exception: an `extend` on a lease
whose path a reload is closing is `PathDraining`, and its `complete` still settles. A bad argument
throws `IllegalArgumentException`, such as a reservation above `LocalHeddle.MAX_RESERVATION`
(2^44 cost units). `ArithmeticException` is reachable too: a pick compares exact fractions, so
the total service under one parent has a ceiling. The ceiling depends on the children's weights:
whole-number weights reach much further than fractional ones. Past it, every `acquire` there
throws. `IllegalStateException` is left only for a conflicted ledger, such as two live edges into
one group, a split record or a cycle, which the public surface cannot reach today. The
engine reads no clock and no random source, and it is not thread-safe: call every method under your
scheduling lock, `snapshot()` included, and hand status and persistence readers the immutable value
it returns.

**Today's public surface cannot yet run one.** `LocalHeddle.fresh` is the only public way to make an
engine, and it has no topology and no supply, so every `acquire` on it is refused. Booting from a
`Topology` and topping up supply are not public yet. The sample below builds its tree and its supply
through module-internal seams, which a consumer cannot call.

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
