# Fair Share

> **Playground.** The scheduler here is real and shipping, but pre-1.0 — its API can
> change and it sits outside kuilt's stability promise. It is the fair-share layer
> underneath [Warp](warp.md): warp decides *that* a job runs somewhere; fair share
> decides *whose turn* it is. Treat this whole area as a preview, not a foundation.

Picture the shared pile of work from [Warp](warp.md) — a roomful of devices, each
grabbing jobs and putting answers back. Now add a wrinkle: the work belongs to
different **tenants**, and they don't all deserve equal time. Team A paid for twice
the share of Team B. A background export shouldn't starve someone's interactive
click. You want the devices to honour those shares — Team A getting roughly twice the
throughput of Team B over time — **without a central scheduler**, and with everyone
still agreeing on who-owes-whom even after the network splits and heals.

That's fair share in one sentence: **weighted turns over shared compute, with no one
in charge.** And, like warp, most of it is parts kuilt already had.

## The pieces already ship

Each idea below is a role; underneath it is a kuilt primitive you've already met:

- The **allowance** — how much compute each tenant may spend — is a
  [`BoundedCounter`](crdt-bounded-counter.md) grown one extra dimension: instead of
  one shared budget, it tracks a whole *tree* of budgets, one per tenant edge. Every
  peer can merge its copy with any other and always agree, no clock, no referee.
- **Who wants work right now** is an [`EphemeralMap`](crdt-ephemeralmap.md) — the same
  presence board that tracks live peers — where each device posts its own appetite and
  a stale entry simply ages out.
- **Whose turn is next** is decided by *weighted fair queuing*: each tenant carries a
  running "virtual time," and the one furthest behind its fair share goes first. No
  vote, no lock.
- **Agreeing on new allowance** — minting fresh budget, or re-drawing the tenant tree —
  rides [Consensus](raft.md), so two halves of a split network can never both hand out
  the same supply.
- **Keeping copies in step** is [live replication](crdt-quilter.md), the same
  anti-entropy that carries every other shared value.

## How you'd use it

You bootstrap a node over a [Seam](contract.md) with a starting allowance and a tenant
tree. Every peer that starts from the same root, mint, and topology begins from an
identical ledger and stays in step over the fabric. A leaf advertises appetite, a
scheduling round flows allowance toward it, and then work **reserves** a slice, runs,
and **completes** — completing twice charges only once:

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleHeddleNode -->

```kotlin
val node = heddleStatic(
    seam = seam,
    self = self,
    root = root,
    mint = mapOf(self to 100L),        // this peer starts holding 100 units at the root
    topology = listOf(e),              // root → leaf, prepared and active at bootstrap
    clock = { Instant.fromEpochMilliseconds(0L) },
    config = HeddleConfig(policy = PolicyConfig(quantum = 10L), maxHoldingsPerPeer = 1_000L),
    epoch = 1L,                        // a persisted monotonic boot counter — bumped every restart
)

// The leaf wants work; one scheduling round delegates entitlement down toward it.
node.advertise(e.id, Demand(targetOutstanding = 100L, maximumUsefulGrant = 100L))
node.schedule(root)

// Leaf work reserves a slice, runs, then completes — completing twice charges once.
val reservation = node.reserve(leaf, maximumCost = 10L)
if (reservation != null) {
    node.complete(reservation, actualCost = 7L)
    node.complete(reservation, actualCost = 7L) // idempotent no-op
}
```

`reserve` sets aside a slice of the tenant's allowance so two devices can't spend the
same unit; `complete` charges the *actual* cost when the work finishes. Deliver that
completion once or five times — the books move exactly once.

## Whose turn is next

When several tenants want the same device, the policy picks the one furthest behind its
fair share. Give a child weight 3 and its sibling weight 1, start them level, and the
heavier one is served first; a tenant with no appetite never competes:

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#samplePolicyPick -->

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

The policy is *pure* — no clock, no randomness, no floating point — so every peer,
handed the same picture, picks the same winner. That determinism is what lets a
scheduler run everywhere at once without a coordinator. Under the hood it is weighted
fair queuing (the EEVDF discipline): each grant advances a tenant's virtual time, and
the earliest virtual deadline wins.

## Keeping the books

The allowance itself lives in an `EntitlementLedger` — the one genuinely new structure
here. Budget is *minted* at the root, *delegated* down tenant edges, *spent* by leaf
work, and *released* back when unused. Edges have a life cycle — **prepared → active →
closing → retired** — so a tenant tree can be re-drawn without losing history, and an
edge is only retired once its allowance has fully drained back:

<!-- verbatim from kuilt-heddle/src/commonSamples/kotlin/us/tractat/kuilt/heddle/EntitlementLedgerSamples.kt#sampleEntitlementLedgerLifecycle -->

```kotlin
// Close the edge, then try to retire it while entitlement is still outstanding — refused.
ledger = ledger.piece(checkNotNull(ledger.close(e)))
check(ledger.retire(e) == null) // 10 units still outstanding

// Drain it (return the grant), then retire succeeds; the register now reads RETIRED.
ledger = ledger.piece(checkNotNull(ledger.release(alice, e, 10L)))
ledger = ledger.piece(checkNotNull(ledger.retire(e)))
check(ledger.lifecycle(e) == Lifecycle.RETIRED)
```

Every operation conserves budget: the total minted always equals what's held plus what's
been spent, on every peer, after every merge — the invariant the whole layer is built to
protect.

## The honest seam

**Safety is a local check, not a global gate.** Before a peer spends, it checks its own
complete copy of the books — never a round-trip — so it can never overspend what it
holds. There's also a `validate()` report that flags integrity problems across the merged
state, but it is a *diagnostic, not a safety gate*: while a multi-hop transfer is still
propagating it can briefly flag a phantom problem that clears itself once copies reconcile.
Gate your work on the operation refusing (returning `null`), never on the report being
empty.

The "no central boss" trick holds as long as the tenant tree is stable. Re-drawing it —
moving a tenant, changing a weight — goes through [Consensus](raft.md) so two halves of a
split can't disagree on the shape; that's the `heddleGoverned` entry point, the governed
sibling of `heddleStatic` above. The one accepted rough edge: if an edge is retired while a
peer's still-in-flight grant hasn't caught up, that grant's budget is safely *stranded*
rather than double-spent — recoverable later, never lost to an overspend.

## The fantasy, last

The furthest-along idea ties fair share back to [Warp](warp.md): tag a job with a **lane**
and it spends against that tenant's allowance, so weighted shares govern a real warp
workload without warp knowing anything about entitlement —

<!-- verbatim from kuilt-warp-heddle/src/commonSamples/kotlin/us/tractat/kuilt/warp/heddle/WarpHeddleSamples.kt#sampleHeddleAdmissionControl -->

```kotlin
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

— and, further out, jobs that say *where* they may run at all. A composable predicate
rides the job to the mesh, and placement hashes over only the devices that qualify:

<!-- verbatim from kuilt-warp/src/commonSamples/kotlin/us/tractat/kuilt/warp/WarpSamples.kt#sampleAffinity -->

```kotlin
// "must run on a GPU node in us-east" — a composable predicate, not a lambda (it rides the wire).
val where = Affinity.has("GPU") and Affinity.attr("region", "us-east")

val gpuUsEast = CapSet(tokens = setOf("GPU"), attributes = mapOf("region" to "us-east"))
val cpuUsWest = CapSet(tokens = setOf("CPU"), attributes = mapOf("region" to "us-west"))
check(where.matches(gpuUsEast))       // eligible
check(!where.matches(cpuUsWest))      // not eligible
check(Affinity.Anywhere.matches(cpuUsWest)) // the default requires nothing
```

Weighted lanes and "run only where eligible" are the newest, least-settled pieces. The
honest first step is exactly what ships today: a correct, conserved, coordinator-free way
to give shared compute its fair turns. The full design — the ledger algebra, the fair-queue
policy, the consensus seam — is written up in
[the design doc](https://github.com/tractat-us/kuilt/blob/main/docs/heddle-design.md).
