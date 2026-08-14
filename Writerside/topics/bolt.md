# Bolt

Your phone can only hold so much. It keeps the last few thousand messages, and when it runs out of
room it drops the oldest ones — which is the right trade on a small device. A server has no such
problem. It could happily keep a year.

Today it cannot, because the two of them share their forgetting. When the phone drops a message it
also writes down *that it dropped it*, and that note travels with the data — so the next time the
server catches up with the phone, the server drops the same message too. How much you keep ends up
being a property of the group rather than of each machine.

A **bolt** is the fix: a rolled-up store of everything that has come past, kept *beside* the live
copy rather than inside it. Edits flow in and never flow back out. Because nothing flows back, how
much a bolt keeps is nobody else's business — the server's bolt can hold a year while the phone that
fed it holds an hour, and neither one changes the other's mind.

You can ask a bolt what it holds — "everything this machine wrote last Tuesday" — but you can never
fold it back into the live data. That restriction is the entire reason the design works, and the
types enforce it rather than a comment: there is no method to call.

## The pieces

| Type | What it is |
|------|------------|
| `Bolt<Op>` | The archive. `append` puts edits in, `replay` reads them back, `availability()` says whether this machine can store anything at all, and `durability()` says whether it is still keeping the promise it made about what it stored. |
| `BoltArchiveFormat` | How edits are sorted into kinds and turned into bytes. Build one with `BoltArchiveFormat.rga(…)` or `.fugue(…)`. |
| `InMemoryBolt` | The reference archive — real bytes, real segments, bounded memory. Available on every platform, and in the browser it is the only one there is. |
| `MappedBolt` | The archive that survives a restart, on JVM and Android: one memory-mapped file per segment, in a directory you name. This is the server's backend — the machine keeping a year while the phone keeps an hour. |
| `PosixMappedBolt` | The same thing on iOS and macOS. Not the default on a phone; the server is its customer here too. |
| `BoltDecorator` | The wiring. Whatever owns the live copy hands it the edits it applied; it archives them and suppresses the ones it has kept before. Reach for this rather than calling `append` by hand. |

Starting one takes a format and an archive. What you hand it afterwards is **operations** — the
edits the live copy applied — never a snapshot of that copy's value:

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltArchiveFormat -->

```kotlin
val server = ReplicaId("server-uuid-abc123")

// You pass the ELEMENT serializer. The op serializer comes from the CRDT's own
// `opSerializer` and cannot be overridden — the compiler-generated one for `RgaOp`
// writes a different wire format, and an archive exists to be read by a later build.
val format = BoltArchiveFormat.rga(String.serializer())
val bolt = InMemoryBolt(format, Clock.System)

// Feed it the OPERATIONS a replica applied, never a state fragment. A `Compact` among
// them is dropped and the ops it suppresses are kept — which is what lets this archive
// outlive the replica that fed it.
var live = Rga.empty<String>()
val ops = List(3) { index ->
    val (next, op) = live.insertAt(server, live.size, "record-$index")
    live = next
    op
}
bolt.append(ops)
```

The archive rides beside an [op-log structure](crdt-overview.md) — an [`Rga`](crdt-rga.md) or a
[`Fugue`](crdt-fugue.md) — because those are the ones whose state *is* a list of edits, and so the
ones that have edits to hand over.

## Feeding it

`append` is the raw surface. In practice the owner of a replica hands its edits to a
`BoltDecorator`, which archives them and remembers what it has already kept, so a peer re-offering
the same log every catch-up round does not write a fresh copy each time.

**Hand it what arrives from other devices, not only what you typed yourself.** Catching up with a
peer merges their whole copy at once, which produces no stream of edits to tee off — and catching up
is exactly how another device's history reaches you. An archive fed only by local edits holds this
machine's own history and nobody else's, which is the thing the module exists to fix. So the owner
also enumerates the remote replica's operations (`OpLogCrdt.operations()`) and publishes those.
`WarpLogRecordExporter` in [`kuilt-otel`](observability.md) does it on both paths, and is the worked
example:

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleArchivingExporter -->

```kotlin
val format = BoltArchiveFormat.rga(LogRecord.serializer())
val bolt = InMemoryBolt(format, Clock.System)
val archive = BoltDecorator(bolt, format)

// The exporter publishes the operations it applied; the decorator archives them. Neither
// knows the other's job, so the same decorator serves any Rga/Fugue owner.
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("server-uuid-abc123"),
    store = InMemoryDurableStore(),
    appliedOps = { ops -> archive.publish(ops) },
)

// Records that arrived by GOSSIP are archived too: a merge publishes the remote log, which
// is the only reason a server's archive ever holds a phone's records. Re-merging the same
// peer costs nothing — the decorator suppresses what it has already kept. Merge OFTEN
// ENOUGH, though: this only ever carries what the peer has not yet windowed away.
exporter.merge(peersLog)

// And the archive keeps them after the live replica has forgotten them.
exporter.clear()
val kept = bolt.replay(ReplayScope.All).frames().toList().flatMap { it.ops }
check(kept.isNotEmpty()) { "a clear empties the replica, never the archive" }
```

Remembering what it kept costs almost nothing, because an *addition* to a replicated list carries a
unique name of its own: the decorator keeps a frontier of the names it has archived — one entry per
peer, not one per edit — so a peer's whole log is suppressed at a fixed price however long it grows.
A *deletion* carries no name of its own, since it points at the addition it undoes, so those are
remembered one at a time. Deletions are the minority of what a peer is holding at any moment, so
they are a small residual rather than the main cost.

Both halves have a ceiling — `frontierWindow` counts entries in the frontier, `removalWindow` counts
remembered deletions — and both err the same way when you hit one: something is forgotten and then
archived twice. That is bytes. Neither can lose a record.

Publication happens *before* the owner's own durable write, so the archive is a **superset** of the
live replica rather than a subset — the right direction for something whose product is "I still hold
what you forgot". And forgetting does not travel: emptying the live replica leaves the archive
alone.

## How complete it really is

One qualification on "a year here beside an hour there", and it is not small. A peer can only hand
over what it *still holds*. A peer that forgets its oldest records before you next catch up with it
has already dropped them from the log it offers, with nothing marking the gap — so the archive's
completeness is bounded by **how often you sync**, not by how much the archive can keep. A replay's
verdict will not tell you: it reports damage to the archive, never a gap at the source. Sync more
often than the peer's own buffer turns over, or accept that the history is as complete as the
schedule allowed.

## Reading it back

A replay always ends with exactly one verdict — `CleanTail` or `Truncated`. That is deliberate: a
replay that simply stopped at damage and ended normally would hand back an incomplete history
indistinguishable from a complete one, and "I still hold what the live replica forgot" is the only
thing a bolt sells. Call `.frames()` to discard the verdict when you genuinely do not need it — an
explicit opt-out, not an oversight.

The verdict arrives only on a replay collected to completion. A `take(n)` or a `first()` gets none,
which is the honest answer to having stopped reading before the archive said how it ended.

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltReplayVerdict -->

```kotlin
var records = 0
var complete = false

// Collect to COMPLETION. The terminal verdict is what a replay sells — a history that
// stopped at damage, and one that did not, are otherwise indistinguishable. A consumer
// that cuts the flow short (take, first, an early return) gets no verdict, honestly.
bolt.replay(ReplayScope.All).collect { event ->
    when (event) {
        is Archived -> records += event.ops.size
        CleanTail -> complete = true
        is Truncated -> when (event.reason) {
            // Not readable YET — a writer mid-append, a device still locked. Resuming
            // from atOffset later can work.
            TruncationReason.SegmentHeader, TruncationReason.Frame -> retryFrom(event.atOffset)
            // GONE. atOffset is the honest end of the readable history and is NOT a
            // resume cursor: nothing will ever produce the records behind it.
            TruncationReason.MissingRegion -> reportPermanentGap(event.atOffset)
        }
    }
}

if (!complete) reportPartialHistory(records)
```

`TruncationReason` splits on the **remedy**, not on the layer. `SegmentHeader` and `Frame` both mean
the bytes are not readable *yet* — a writer part-way through an append, a file on a device still
locked — so resuming from that offset later can work. `MissingRegion` means they are **gone**: a
deleted segment file, a region that never reached disk. Retrying the first two is sensible. Retrying
the third will never produce the records.

### Four scopes, of which two are cursors

Only a **cursor** is safe to resume from, because only a cursor is total over the frames it has not
yet seen. The other two answer questions.

| Scope | What it selects |
|-------|-----------------|
| `ReplayScope.All` | Every frame, oldest first. A cursor over the whole archive. |
| `ReplayScope.FromOffset` | **The resume cursor.** Hand back the `endOffset` of the last frame you consumed. An offset falling inside a frame yields that frame from its start, so a cursor never points at half a record. |
| `ReplayScope.Arrived` | A query by **arrival** time — when the archive was *told*, which for anything that came from another device is arbitrarily later than when it happened. "Everything this machine wrote last Tuesday" is answerable; "everything that happened last Tuesday" is not. |
| `ReplayScope.InsertsAbove` | A query over causal coverage, **additions only**. A deletion mints no name of its own, so a frame of pure deletions is selected by no such scope at all, however recent. Not a resume cursor: one would skip that frame and replay a deleted record as live. |

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltResumeCursor -->

```kotlin
// Consume what the archive holds now, remembering where each frame ended. `.frames()`
// deliberately drops the terminal verdict — fine for a cursor walk, not for anything
// that acts on the history being complete.
var cursor = 0L
bolt.replay(ReplayScope.All).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}

// Later — after more appends — pick up exactly there. An offset that falls inside a frame
// yields that frame from its start, so a cursor can never point at half a record.
bolt.replay(ReplayScope.FromOffset(cursor)).frames().collect { frame ->
    ship(frame.ops)
    cursor = frame.endOffset
}
```

## The one rule

> **A bolt consumes operations, never states, and never joins back.**

Three consequences, each load-bearing:

1. **It is fed operations, not state fragments.** A state fragment belongs to the same structure the
   live replica is, so absorbing one would mean merging — and merging means inheriting the source's
   forgetting. A record of forgetting is state rather than an operation, so a stream of operations
   cannot carry one even by mistake. The firewall is structural, not enforced by a check.
2. **It throws away the records of forgetting.** Of the three shapes an operation comes in, two are
   content and one is a note saying "this was dropped". Keeping the first two and discarding the
   third is the only deliberate divergence from ordinary replication semantics here, and it is what
   lets an archive outlive its source's memory.
3. **It never merges back.** `Bolt` exposes no merge at all, and the absence is enforced by a
   source scan in the build rather than by anyone remembering.

**A replay may be read. It must never be authored from.** Folding a replay into a fresh replica
produces a structurally valid value, so nothing stops you — the damage appears one step later and is
permanent. A replica seeded from a replay missing frames at its tail re-uses a name that has already
been used for different content, which breaks the bookkeeping every peer's garbage collection
depends on, mesh-wide, with nothing to purge it.

## When something goes wrong

Best-effort, on purpose. A full archive must not take down the application whose records it is
archiving, so a failed `append` returns `AppendResult.Failed` rather than throwing — and it reports
the **identities and the offset range** it lost, never a bare count. The live replica will forget
those records next, so a failed append loses them from both sides; a consumer holding the identities
can defer that forgetting, re-feed, or correlate the gap, and a consumer holding a tally can do none
of those.

There is a second, quieter failure, and it is *not* a failed append. A disk-backed archive can be
told to push every record all the way to the disk before it answers — and the machine can refuse.
The record is still there: whole, checksummed, readable by anything that opens the file. What was
lost is the *promise*. So the append still says it wrote, and `durability()` is where the shortfall
is reported — relative to what *that* archive promised, which is the only reading a consumer can act
on:

<!-- verbatim from kuilt-bolt/src/commonSamples/kotlin/us/tractat/kuilt/bolt/BoltSamples.kt#sampleBoltDurability -->

```kotlin
// ASK, don't infer from an append. A flush covers a RANGE, so the frames a failed one
// puts in doubt are everything since the last good flush — not the append that triggered
// it, whose result is already in your past.
when (val state = bolt.durability()) {
    // Meeting the level IT promised, including where it promised nothing at all.
    DurabilityState.AsPromised -> trimTheLiveReplicaWindow()
    // Written and readable, but not confirmed durable. Trimming the live replica now
    // would leave those records held nowhere but an unflushed page. Sticky and widening:
    // it clears only when a later flush covers the whole range.
    is DurabilityState.Degraded -> holdTheWindow(state.fromOffset, state.toOffset, state.reason)
}
```

An in-memory archive promised nothing, so it is never falling short. One told to let the operating
system flush in its own time promised only that, so it is never falling short either. Only one that
promised to flush per record and then could not says so — and it keeps saying so, over the whole
range of records left in doubt, until a later flush covers that range. That stickiness is the point:
on some systems a disk error is reported once and then cleared, so an archive that swallowed it
would destroy the only notification anyone was ever going to get.

A third question is separate from both, and `availability()` answers it: can this archive be written
to *at all* right now. `Unavailable` is a settled no — a read-only volume, a directory that could
not be created. `Unknown` is not a hedge but a real state: an iOS file that cannot be opened while
the device is locked is neither available nor permanently unavailable, and the next unlock may
resolve it. A bolt reporting `Available` must accept an append.

`BoltDecorator.health` carries the same news one level up, and it is lossy — it keeps only the most
recent handful of failures. The complete channel is the `AppendResult` that `BoltDecorator.publish`
returns. A consumer that must not lose an identity calls `publish` itself rather than routing
through a sink that returns nothing.

## Adding your own backend

Subclass `BoltConformanceSuite` and implement its five fixture hooks — a fresh archive, one already
out of room, one damaged inside a frame, one missing a whole region out of the middle, and one that
cannot flush. It pins seven properties, and the second is the reason the module exists: a bolt fed a
record of forgetting keeps the edits that record suppresses and never replays the record itself.

No hook is optional, and for the three damage fixtures that is the point of them. An "I cannot reach
this state" opt-out moves the hole one level up, where it is harder to see: the suite would go green
for a backend that never exercised the path at all. See [Testing](testing.md) for how the other
conformance suites in kuilt are used, and `kuilt-bolt/module.md` in the repository for the archive's
byte format and the full contract of each hook.

## When to use

Reach for a bolt when one machine should remember more than the machines it syncs with — a server
keeping a year of a chat log, an audit trail, an on-device buffer you want to drain somewhere
roomier. It is the answer to "our records vanish from the server once the phone forgets them".

It is not a replica and not a backup: it never participates in convergence, and it holds operations
rather than the current value. If what you want is the *current* shared value on every device, that
is ordinary [replication](crdt-overview.md), kept in step by [Quilter](crdt-quilter.md).
