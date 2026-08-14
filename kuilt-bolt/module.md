# Module kuilt-bolt

A phone can only keep so much. It holds the last few thousand records, and when it runs out of room
it forgets the oldest ones — which is the right trade on a small device. A server has no such
problem: it could happily keep a year.

Today it cannot, because the two of them share their forgetting. When the phone drops a record it
also writes down *that it dropped it*, and that decision travels with the data — so the moment the
server syncs with the phone, the server forgets the same records. How much you keep ends up being a
property of the group, not of each machine.

A **bolt** is a rolled-up store of everything that has been woven, kept *beside* the live copy
rather than inside it. Edits flow into it and never back out. Because nothing flows back, how much
it keeps is nobody else's business: a server's bolt can hold a year while the phone that fed it
holds an hour, and neither one changes the other's mind.

You can ask a bolt what it holds — "everything this machine wrote last Tuesday" — but you can never
merge it back into the live system. That restriction is the whole reason the design works, and it is
enforced by the types, not by a comment.

## The moving parts

| Type | What it is |
|------|-----------|
| `Bolt<Op>` | The archive. `append` puts edits in, `replay` reads them back, `availability` says whether this machine can store anything at all, and `durability` says whether it is still keeping the promise it made about them. |
| `BoltArchiveFormat` | How edits are classified and turned into bytes. Build one with `BoltArchiveFormat.rga(…)` or `.fugue(…)`. |
| `InMemoryBolt` | The reference archive — real bytes, real segments, bounded memory. Available on every platform, and on wasmJs it is the only one there is, which its own docs are blunt about. |
| `MappedBolt` | The archive that survives a restart, on JVM and Android: one memory-mapped file per segment in a directory you name. **This is the server's backend** — the machine that keeps a year while the phone keeps an hour. |
| `PosixMappedBolt` | The same thing on iOS and macOS. **Not the default on a phone** — its own docs say why at length, and the short version is that the server is its customer here too. |
| `BoltDecorator` | The wiring. A replica's owner hands it the edits it applied; it archives them and suppresses the ones it has kept before. Reach for this rather than calling `append` by hand — see below. |

Three smaller types round it out, all three covered under **Failure posture** below. `AppendResult`
is what one `append` did — written, skipped, or refused-with-identities. `BoltAvailability` answers
"can this archive be written to right now". `DurabilityState` answers the quieter question
underneath it: is this archive still keeping the promise *it* made about the records it accepted.

Starting one takes a format and an archive. What you then hand it is **operations** — the edits a
replica applied — never a state:

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

## Feeding it: `BoltDecorator`, and the mistake to avoid

`append` is the raw surface. In practice a replica's owner hands its edits to a `BoltDecorator`,
which archives them and remembers what it has already kept, so a peer re-offering the same log every
anti-entropy round does not write a fresh copy each time.

Remembering costs almost nothing, because an *addition* to a replicated list carries a unique name of
its own: the decorator keeps a **frontier** of the names it has archived — one entry per peer, not
one per edit — so a peer's whole log is suppressed at a fixed price however long it grows. A
*deletion* carries no name of its own (it points at the addition it undoes), so those are remembered
one at a time. Deletions are the minority of what a peer is holding at any moment, and the source's
own housekeeping collects them, so they are a small residual rather than the main cost.

Both halves have a ceiling — `frontierWindow` counts *entries* in the frontier, `removalWindow`
counts remembered deletions — and both err the same way when you hit one: something is forgotten and
then archived twice. That is bytes. Neither can lose a record.

**Feed it the merges as well as the local edits, or the archive is nearly empty.** Merging a peer's
replica is a *state* join: it produces no edits to hand over. Since syncing with a peer is exactly
how another device's history arrives, an archive fed only by local edits holds this machine's own
history and nobody else's — which is the thing this module exists to fix. So the owner enumerates
the remote replica's operations (`OpLogCrdt.operations()`) and publishes those too;
`WarpLogRecordExporter` in `:kuilt-otel` does it on both paths and is the worked example.

Publication happens *before* the owner's own durable write, so the archive is a **superset** of the
live replica rather than a subset — the right direction for something whose product is "I still hold
what you forgot". And forgetting does not travel: emptying the live replica leaves the archive alone.

**One qualification on "a year here beside an hour there", and it is not small.** A peer can only
hand over what it *still holds*. A peer that forgets its oldest records before you next sync with it
has already dropped them from the log it offers, with nothing marking the gap — so the archive's
completeness is bounded by **how often you sync**, not by how much the archive can keep. A replay's
truncation verdict will not tell you: it reports damage to the archive, never a gap at the source.
Sync more often than the peer's own buffer turns over, or accept that the history is as complete as
the schedule allowed.

## Reading it back

A replay always ends with exactly one verdict — `CleanTail` or `Truncated`. That is deliberate: a
replay that just stopped at damage and completed normally would hand back an incomplete history
indistinguishable from a complete one, and "I still hold what the live replica forgot" is the only
thing a bolt sells. Call `.frames()` to discard the verdict when you genuinely do not need it — an
explicit opt-out, not an oversight.

The verdict is an element of the stream rather than a separate `verify()` call for two reasons: it is
bound to the exact bytes *this* replay read, where a second call would race a concurrent append; and
it cannot be forgotten, because you have to name the frame case to get at frames at all. It arrives
only on a replay collected to completion — a `take(n)` or a `first()` gets no verdict, which is the
honest answer to having stopped reading before the archive said how it ended.

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

**`TruncationReason` splits on the remedy, not on the layer.** `SegmentHeader` and `Frame` both mean
the bytes at `atOffset` are not readable *yet* — a writer part-way through an append, a file a locked
device will not open — so resuming from that offset later can work. `MissingRegion` means they are
**gone**: a deleted segment file, a region that never reached disk. Nothing there failed a checksum,
because a hole presents no bad bytes to fail one; it is caught by noticing that the next segment's
header does not begin where the previous segment's frames ended. Retrying the first two is sensible.
Retrying the third will never produce the records.

### Four scopes, of which two are cursors

Only a **cursor** is safe to resume from, because only a cursor is total over the frames it has not
yet seen. The other two answer questions.

| Scope | What it selects |
|-------|-----------------|
| `ReplayScope.All` | Every frame, oldest first. A cursor over the whole archive. |
| `ReplayScope.FromOffset` | **The resume cursor.** Hand back `AppendResult.Written.endOffset`, or the `endOffset` of the last frame you consumed. An offset falling inside a frame yields that frame from its start, so a cursor never points at half a record. |
| `ReplayScope.Arrived` | A query by **arrival** time — when the archive was *told*, which for anything that arrived by merge is arbitrarily later than when it happened. "Everything this machine wrote last Tuesday" is answerable; "everything that happened last Tuesday" is not. |
| `ReplayScope.InsertsAbove` | A query over causal coverage, **inserts only**. A `Remove` mints no dot — it reuses its target `Insert`'s id — so a frame of pure removes is selected by no dot scope at all, however recent it is. Not a resume cursor: one would skip that frame and replay a removed record as live. |

## The invariant

> **A bolt consumes operations, never states, and never joins the lattice.**

Three consequences, each load-bearing:

1. **It is fed operations, not deltas.** A `Patch` is a state fragment of the same lattice, so
   absorbing one would mean `piece`, which means inheriting the source's suppression. A compaction
   *floor* is state rather than an operation, so an op stream cannot carry one even by mistake — the
   firewall is structural rather than enforced.
2. **It discards `LogOp.Compact`.** Of the three op shapes, `Insert` and `Remove` are content and
   `Compact` is a record of forgetting. Keeping the first two and dropping the third is the only
   deliberate divergence from CRDT semantics here, and it is what lets an archive outlive its
   source's memory.
3. **It never merges back.** `Bolt` does not implement `Quilted` and exposes no `piece`. The absence
   is enforced by the root build's `forbidBoltRejoiningTheLattice` source scan.

**A replay may be read. It must never be authored from.** Folding a replay into a fresh replica
produces a structurally valid state, so nothing stops you — the damage appears one step later and is
permanent. A replica seeded from a replay missing frames at its tail re-mints an already-used
`(replica, seq)` dot carrying different content, which breaks the dense per-author delivery counter
every causal-stability version vector depends on, mesh-wide, with nothing to purge it.

## The archive format

Each **segment** opens with a header: a magic number, a **format version**, and what the archive
holds (which canonical op serializer, which element type). A format whose rationale is "read by
future versions of the code that wrote it" must be able to say which version wrote it.

Each **frame** then carries an append offset, an arrival timestamp, the dots its inserts minted, a
reserved key slot, a length prefix and a CRC-32 **covering the prefix as well as the body**.

Covering the prefix is load-bearing, not belt-and-braces. A disk-backed segment is eagerly,
physically pre-allocated at roll time, so every live segment ends in a zero-filled region — and a run
of zeroes would otherwise decode as a *valid* frame (length `0`, stored checksum `0`, and CRC-32 of
an empty body is `0`, so it matches). Folding the prefix in makes a zero run checksum to `0x2144DF1C`,
which no zero field can equal; a minimum body length is the second, independent guard.

Three things about that list are easy to misread:

- **Dots are informational; the append offset is the resume cursor.** A `Remove` mints no dot — it
  reuses its target `Insert`'s id — so scoped replay by dot range is defined over **inserts only**,
  and a frame of pure removes is selected by no dot scope at all. Resume with
  `ReplayScope.FromOffset`.
- **Arrival time is not event time.** A frame is stamped when the archive was *told* about the ops,
  which for anything that arrived by merge is arbitrarily later than when it happened.
- **Arrival timestamps are stored to millisecond resolution.** Sub-millisecond precision on the
  appending clock is truncated — always earlier, never rounded. A property of the format, not of any
  one backend.

## Failure posture

Best-effort. A full archive must not take down the application whose records it is archiving, so a
failed `append` returns `AppendResult.Failed` rather than throwing — but it reports the **dots and
the offset range** it lost, never a bare count. The live replica will window those records away
next, so a failed append loses them from both sides; a consumer holding the identities can defer
windowing, re-feed, or correlate the gap, and a consumer holding a tally can do none of those.

There is a second, quieter failure, and it is *not* a failed append. A disk-backed archive can be
told to push every record all the way to the disk before it answers — and the machine can refuse.
The record is still there: whole, checksummed, readable by anything that opens the file. What was
lost is the *promise*, so the append still says it wrote, and `durability()` is where the shortfall
is reported.

It reports **relative to what that archive promised**, which is the only reading a consumer can act
on. An in-memory archive promised nothing, so it is never falling short. An archive told to let the
operating system flush in its own time promised only that, so it is never falling short either. Only
one that promised to flush per record, and then could not, says so — and it keeps saying so, over
the whole range of records left in doubt, until a later flush covers that range. That stickiness is
the point: on Linux a disk error from `msync` may be reported **once and then cleared**, so an
archive that swallowed it would destroy the only notification anyone was ever going to get.

A third question is separate from both, and `availability()` answers it: can this archive be written
to *at all* right now. `Unavailable` is a settled no — a read-only volume, a directory that could
not be created. `Unknown` is not a hedge but a real state: an iOS file whose Data Protection class
makes it unreadable while the device is locked is neither available nor permanently unavailable, and
the next unlock may resolve it. A bolt reporting `Available` must accept an append.

**`BoltDecorator.health` is the same news, one level up, and it is lossy.** `ArchiveHealth` carries
the `AppendResult.Failed` values themselves — identities, not a tally — plus the forwarded
`DurabilityState`. But it keeps only the most recent handful of failures and drops the *oldest*
first, which under sustained failure are the identities with the least time left before the live
replica windows them away; and it rides a `StateFlow`, which conflates. The complete channel is the
`AppendResult` that `BoltDecorator.publish` returns. A consumer that must not lose an identity calls
`publish` itself rather than routing through a `Unit`-returning sink, and the shipped wiring — every
example adapts the decorator as `{ ops -> publish(ops) }` — deliberately does not.

## Adding a backend

Subclass `BoltConformanceSuite` and implement its six fixture hooks. It pins the properties every
archive must satisfy — chief among them the reason the module exists: a bolt fed a `Compact` keeps
the ops that `Compact` suppresses and never replays the `Compact` itself. (Neither a count of those
properties nor a position in the list is named here on purpose: the suite grows — #2331 added two —
and a number in prose rots silently the moment it does. The hook count is named because the table
below enumerates them, so the two cannot drift apart unseen.)

**No hook is nullable**, and for the four damage fixtures that is the point of them: an "I cannot
reach this state" opt-out moves the vacuity one level up, where it is harder to see — the suite would
go green for a backend that never exercised the path at all. Each asserts its own precondition too,
so a backend handing back a healthy bolt fails loudly rather than passing quietly. The durability
fixture could not be made non-nullable the same way, and its row says what it does instead.

| Hook | What it must produce |
|------|----------------------|
| `newBolt(clock)` | A fresh, empty archive. The clock is a parameter because one property scopes a replay by arrival time, which a backend reaching for the wall clock could not be asked about deterministically. |
| `newExhaustedBolt(clock)` | One that is **already out of room**, however this backend runs out. |
| `newTruncatedBolt(clock, intactFrames)` | One damaged *within* a frame after `intactFrames` good ones — and the damage must be followed by a **healthy** region, or "stop at the damage" and "skip to the next region" emit identical events and the property stops discriminating. |
| `newDiscontinuousBolt(clock, intactFrames, lostSegments)` | One missing a whole region out of the **middle**, with frames surviving behind the hole — **and the appends that produced both its edges**. Two disk-backed backends independently replayed a hole as a `CleanTail` before this hook existed (#2240) — the in-memory reference's segments are a list that cannot lose an element, so it satisfied the older suite's silence for free. The far edge is the one thing the suite cannot work out for itself: every scope scans forward and stops at the hole, so nothing a conformance test can call names its far side, and the far side is where a *retention sweep* leaves a consumer's cursor. `lostSegments` is a parameter because a hole is two offsets through this contract, so no assertion can tell one destroyed segment from four — a fixture left to choose picks one, and a check that recovers across a wider gap passes (#2331). |
| `newBackwardsJumpBolt(clock, intactFrames)` | One whose segment sequence runs **backwards**: a header claiming an absolute `baseOffset` *below* where its predecessor's frames ended. Losing a segment can widen a gap without limit and can never make it negative, so every other fixture agrees with a backend spelling its continuity check `header.baseOffset > resumeOffset` — which reads perfectly and lets this archive replay as a `CleanTail`, handing a consumer the same records twice at offsets it has already consumed. Narrowing that comparison on any of the three backends reddens this property and nothing else in the module (#2331). |
| `newBoltThatCannotFlush(clock)` | A `DurabilityFixture` declaring which of three cases this backend is: promised per-record durability and cannot deliver it; promised nothing but still flushes; or promised nothing and never flushes. Nullable would hand every backend a silent skip, and non-nullable would demand a degraded bolt from a backend for which none can correctly exist. |

The general lesson `newDiscontinuousBolt` encodes is worth carrying to any new property here: **a
conformance property is only as strong as the weakest failure the reference implementation can
reach.** And a fixture free to choose its own configuration will, left alone, choose the one in which
the failure cannot occur — so say in the hook's contract which configurations are legitimate, and
where the suite can *check* the configuration rather than describe it, take the fixture's **inputs**
and derive the answer instead of accepting a derived one: `DiscontinuousFixture` and
`BackwardsJumpFixture` both take `AppendResult.Written` evidence, so a mid-hole cursor and a forward
"backwards" jump are values their constructors cannot be handed.
