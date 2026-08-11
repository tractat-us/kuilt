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
| `InMemoryBolt` | The reference archive — real bytes, real segments, bounded memory. Available on every platform. |
| `PosixMappedBolt` | The archive that survives a restart, on iOS and macOS: one memory-mapped file per segment in a directory you name. **Not the default on a phone** — its own docs say why at length, and the short version is that the server is its customer. |
| `BoltDecorator` | The wiring. A replica's owner hands it the edits it applied; it archives them and suppresses the ones it has kept before. Reach for this rather than calling `append` by hand — see below. |

```kotlin
val bolt = InMemoryBolt(BoltArchiveFormat.rga(serializer<String>()), clock)
bolt.append(opsTheReplicaJustApplied)
bolt.replay(ReplayScope.All).collect { event ->
    when (event) {
        is Archived -> handle(event.ops)
        CleanTail -> /* the whole archive was intact */
        is Truncated -> /* stopped at event.atOffset — the history is SHORT */
    }
}
```

A replay always ends with exactly one verdict — `CleanTail` or `Truncated`. That is deliberate: a
replay that just stopped at damage and completed normally would hand back an incomplete history
indistinguishable from a complete one, and "I still hold what the live replica forgot" is the only
thing a bolt sells. Call `.frames()` to discard the verdict when you genuinely do not need it — an
explicit opt-out, not an oversight.

## Feeding it: `BoltDecorator`, and the mistake to avoid

`append` is the raw surface. In practice a replica's owner hands its edits to a `BoltDecorator`,
which archives them and remembers — within a bounded window — what it has already kept, so a peer
re-offering the same log every anti-entropy round does not write a fresh copy each time.

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
