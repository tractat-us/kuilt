# Module kuilt-store

**Put some bytes somewhere they will still be after the app restarts.**

Give it a name and a blob of bytes; ask for that name later — after a reload, a
force-quit, or a crash — and the bytes are still there. That is the whole idea.
There is one small interface, and one implementation of it per platform that knows
how to make a write really stick on that platform.

## The promise

The point of the interface is a single, load-bearing promise:

> When the write call returns, the bytes are safe.

Not "queued", not "probably written soon" — safe. That is what lets a caller treat
its own job as done at that moment, rather than holding everything open until some
slower thing further downstream confirms it. Storage is not the interesting part of
most programs, but *when* a write became permanent usually is.

A failed or cancelled call gives no such answer. Its bytes may already be stored,
or may appear after the caller stops waiting. Even reading the old value immediately
is not proof that a pending write will leave it alone. The store has no method for
waiting for an abandoned operation to settle. Recovery and retry rules belong to the
caller; see the caller audit below.

## Quick start

<!-- verbatim from kuilt-store/src/commonSamples/kotlin/us/tractat/kuilt/store/Samples.kt#sampleDurableStore -->
```kotlin
// Every platform has its own crash-safe implementation; a test uses the in-memory one.
val store: DurableStore = InMemoryDurableStore()
val key = StoreKey("draft")

// A successful `write` returns only once the bytes are committed.
store.write(key, byteArrayOf(1, 2, 3))

// A later session (a fresh store over the same backing directory or database)
// reads back exactly what was committed; an unwritten key reads back null.
val recovered: ByteArray? = store.read(key)
check(recovered.contentEquals(byteArrayOf(1, 2, 3)))
check(store.read(StoreKey("never-written")) == null)

store.delete(key)
check(store.read(key) == null)
```

## What's here

| Type | Targets | What it does |
|---|---|---|
| `DurableStore` | all | The interface: `read`, `write`, `delete`, keyed by `StoreKey`. |
| `StoreKey` | all | A named key. A `value class` over `String`, so two keys cannot be swapped by accident. |
| `InMemoryDurableStore` | all | Keeps everything in a map. **Not** crash-safe — for tests and anywhere a restart doesn't matter. |
| `FileChannelDurableStore` | JVM, Android | Temp file → `FileChannel.force(true)` → atomic rename → force the directory (skipped on Windows). |
| `NSFileManagerDurableStore` | iOS, macOS | Temp file → `fcntl(F_FULLFSYNC)` → POSIX `rename(2)`. |
| `IndexedDbDurableStore` | wasmJs | An IndexedDB transaction, awaited to its `complete` event. |

Each implementation's KDoc names the exact instant it treats as the commit, and where
its guarantee stops. Both file stores force the new bytes to the device before they
rename, so power loss (as opposed to process death) can never leave a record empty or
torn. They differ on what comes after the rename. On Linux, macOS and Android,
`FileChannelDurableStore` also forces the directory, so once `write` returns the new
record is the one a power cut brings back; if it cannot force the directory, `write`
throws instead of returning. On Windows the JVM cannot force a directory, and
`NSFileManagerDurableStore` does not, so on those two a power cut just after `write`
returns can bring back the *previous* record instead of the new one — old or new, never
torn. The in-memory store, of course, keeps nothing at all across a process exit.

## Key names

A key is just a name you pick. Any name — with dots, spaces, a slash, an accent, an
emoji, capital letters — addresses its own entry, and two names that are not identical
are never the same entry. The file-backed stores manage that by escaping anything
unusual out of the filename, so the key `otel/spans.v1` is one file called
`otel%2Fspans%2Ev1`, not a file called `spans.v1` inside a directory called `otel`.

Two things to know before generating key names from data rather than writing them by
hand. **Length is not promised** — escaping can triple a name's length while the
filesystem's limit stays where it is, so a very long key, especially a non-ASCII one,
can fail to write. And keys stored under the **previous** scheme are **not migrated**.
That scheme folded every unusual character onto `_`, which meant `a.b` and `a/b` were
one file and writing either destroyed the other (#2506); the files it left behind are
abandoned rather than renamed, and the new escaping is chosen so that an abandoned file
can never be picked up as some *other* key's data. A key that was already plain
lowercase letters, digits and dashes is unaffected and keeps its data — though none of
the keys kuilt's own telemetry stores is in that shape, so all of those do move.

Two smaller edges. On a **case-insensitive filesystem** (APFS by default) the "never
picked up as another key's data" guarantee holds up to case: if you previously stored
`Config` and now introduce a *new* key `config`, the new one will find the old one's
abandoned file. Don't introduce a case-variant of a key you used to store. And the
file-backed stores **reject a key name that is not well-formed text** — a lone
surrogate has no UTF-8 form, so it throws rather than being quietly mangled; the
in-memory and IndexedDB stores accept it, so build key names from data with that in
mind.

## What it deliberately is not

No iteration, no querying, no transaction spanning two keys, and no opinion about what
the bytes mean. The expected shape is a handful of fixed, hand-written key names holding
serialized state a caller already knows how to encode. Anything richer — an op log with
history you can replay and forget, a replicated structure that merges across peers — is a
different module's job (`kuilt-bolt` and `kuilt-crdt` respectively).

## Dependencies

Coroutines and atomicfu. That is the entire bill: no CRDT lattice, no serialization
format, no logging facade. A consumer that wants a durable key→bytes store on one
platform should not inherit a telemetry stack along with it — which is exactly why this
module was split out of `kuilt-otel` (#2497).

## Failed-write caller audit

A missing acknowledgement is not an undo. The tests named below inject a write that
lands before throwing an exception or cancellation, and assert that the injection
fired. They characterize recovery as well as the result the caller reported.
The in-memory backing models visibility between handles, not a physical crash.

| Caller | Verdict for a landed, unacknowledged write | Coverage |
|---|---|---|
| `DurableStoreRaftStorage` | **Unsafe to reuse the handle.** Meta, log and snapshot caches change only after acknowledgement. A later meta or log update can overwrite landed state using the stale cache. Once outstanding writes have settled, a fresh handle reads the landed state. An immediate reopen cannot prove settlement. | `DurableStoreIndeterminateWriteTest` demonstrates stale meta and a later overwrite. Log and snapshot verdicts follow the same write-then-adopt ordering. |
| `WarpSpanExporter` | **Rollback does not undo persistence.** An ordinary failure removes a newly stamped span from memory, though recovery can still find it. Cancellation bypasses that rollback. Retrying an unstamped span after recovery mints another dot and can duplicate the span. Merge and clear rewrite state; neither can interpret failure as absence. | `IndeterminateWriteAuditTest.stampedSpanRollbackDoesNotUndoTheDurableWriteAndRestartRetryDuplicates` exercises ordinary failure, cancellation and recovery. |
| `WarpMetricExporter` | **Snapshot rewrite is benign once writes settle; replaying an operation is different.** Memory already holds each mutation. Recovery reads the landed snapshot. Re-merging the same snapshot does not increment again; retrying an increment or sample does. This does not establish ordering against a late, outstanding write. | `IndeterminateWriteAuditTest.metricSnapshotsRecoverAndRewriteWithoutReapplyingTheOperation` covers each persisted metric kind. |
| `WarpCausalClock` | **A landed high-water mark is recoverable.** Persist does not roll back the live clock; recovery advances beyond the landed dot. An outstanding old snapshot still needs settlement before restart or later persistence. | `IndeterminateWriteAuditTest.clockCommitBeforeFailureRecoversItsHighWaterMark`. |
| `WarpLogRecordExporter` | **Adopted-segment cancellation and retirement are both covered.** The merge records the segment before awaiting its write and repairs bookkeeping on failure, which prevents the orphan/phantom cases. A segment retires only on durable suppression, which only an acknowledged covering write or recovery advances; a refused or cancelled write never does, even if it landed (#2988). This does not prove every ledger schedule safe. | `WarpLogRecordExporterRetirementTest.aMergeCancelledAfterItsSegmentLandedLeavesNoOrphanKey` and `aMergeCancelledBeforeItsSegmentLandedLeavesNoPhantomAcrossARestart` assert landed/cancelled counts and inspect the next roll and restart. `WarpLogRecordExporterDurableSuppressionTest` covers refused and cancelled covering writes, before and after landing. |
| `OtlpHttpEdge` | **A landed sent-set is benign for the completed delivery.** Persistence follows a successful HTTP response. The next digest reads the store, so a landed sent-set suppresses a redundant send even if the call failed. Span/log ids and metric versions use this ordering. This is not an exactly-once collector guarantee or a proof about concurrent sends. | `IndeterminateWriteAuditTest.committedSentSetSurvivesFailureAfterSuccessfulPost` exercises spans; the log and metric verdicts follow the same POST-then-record ordering. |
| `AppliedOpSink` | **No store write.** Receives operations when applied in memory, before the exporter's write returns; receipt is not a durability acknowledgement. | `AppliedOpSink` contract and exporter call sites. |
| `ExporterHealth` | **No store write.** Reports exporter health. A write-failure report does not establish that the bytes are absent. | `ExporterHealth` declarations and exporter failure paths. |

These are audit verdicts, not changes to recovery policy. In particular, the Raft
cache and stamped-span retry hazards need caller-specific remedies; this contract
clarification introduces no settlement API or serialization change.

There is no universal conformance property requiring a cancelled write to land:
both leaving the old value intact and committing the candidate are permitted. Adding
that requirement to `DurableStoreConformanceSuite` would reject valid backends, and
wrapping its in-memory reference would test the wrapper alone. Instead,
`IndeterminateWriteTest.cancellationCanPrecedeDurableVisibility` demonstrates the
permitted late-commit schedule explicitly, while the IndexedDB test exercises a real
transaction whose caller abandons the await.
