# `:kuilt-bolt` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A write-only archive for op-log CRDTs, so a server can retain a year of history beside a phone that retains an hour — which is impossible today at any price, because suppression is contagious through `piece`.

**Architecture:** A new `:kuilt-bolt` module consuming **operations** (never states, never `piece`) and appending them to an append-only frame log. Content ops are kept; `Compact` is discarded, which is what lets a bolt outlive its source's forgetting. A bolt is not a `Quilted`, exposes no `piece`, and can never merge back. Backends are one mmap mechanism with a flush flag; `availability()` reports where that is real.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-serialization-cbor, kotlinx-atomicfu. JVM/Android `FileChannel.map()`; Apple `platform.posix` mmap; wasmJs unavailable in v1.

**Design:** [`docs/superpowers/specs/2026-08-08-kuilt-bolt-design.md`](../specs/2026-08-08-kuilt-bolt-design.md). Read it before Task 1 — particularly "Why the obvious approach does not work" and "The invariant". This plan implements that spec and does not re-argue it.

## A note on how this plan is written

The sibling #2194 plan prescribed complete implementation bodies, and **eleven defects were found in it by the reviewers and workers who executed it** — several in prescribed test code that did not compile or asserted nothing. Transcribing code I cannot compile is what produced those.

So this plan is precise about **contracts, test properties, hazards and ordering**, and deliberately lighter on implementation bodies. Where it shows code it is a signature or a shape, not a transcript. **Treat every prescribed detail as a strong proposal and report anything that turns out to be wrong** — that instruction is what surfaced all eleven.

## Global Constraints

- **`explicitApi()` is enforced.** Every public declaration needs explicit visibility.
- **`:kuilt-crdt` must stay dependency-free** — `api(kotlinx-serialization-core)` and nothing else. No coroutines, no I/O. Task 1 adds a contract there; it must add no dependency.
- **`detektAll`, never bare `detekt`** (bare is `NO-SOURCE` here — a false green).
- **Full `./gradlew build detektAll --max-workers=6`** before any merge; never module-scoped.
- **`runTest(timeout = TEST_WEDGE_BACKSTOP)`** on every coroutine test; `forbidTightRunTestTimeout` enforces it.
- **`assertAll` takes NON-suspending lambdas** — drive the subject first, assert after.
- **No production dispatchers in test sources**; never `advanceUntilIdle()`.
- **`runCatchingCancellable`, never bare `runCatching`** in suspend contexts — except inside a `withContext(NonCancellable)` cleanup shield, where a plain `try`/`catch (Throwable)` per item is correct.
- **Thread safety by explicit primitives.** `limitedParallelism(1)` confinement is banned. A bolt owning a scope must be correct under a multi-threaded dispatcher: atomicfu `reentrantLock` with no suspend calls inside the locked section, or a single dedicated writer coroutine draining a `Channel`.
- **Run every new test alone**; read the **results XML**, not the console line (#2185).
- **Never `git stash`** — `refs/stash` is repo-global across linked worktrees.
- **Close-keyword audit before every push** — `fix #N` fires even when "fix" is a noun:
  ```bash
  git log origin/main..HEAD --format=%B | grep -inE '\b(close[sd]?|fix(e[sd])?|resolve[sd]?)[[:space:]]+#[0-9]+'
  ```

---

### Task 1: `OpLogCrdt` — the public contract

**This task's job is to argue a decision, not just land code.** It is the one open question the spec deliberately left for the plan.

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/OpLogCrdt.kt`
- Modify: `Rga.kt`, `Fugue.kt` (conform), `OpLogEngine.kt` (`LogOp` visibility)
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/OpLogCrdtTest.kt`

**Interfaces — produced, and depended on by every later task:**

```kotlin
/** How one operation is classified, independent of the concrete op-log CRDT. */
public sealed interface LogOp<out Id> {
    public data class Insert<Id>(val id: Id) : LogOp<Id>
    public data class Remove<Id>(val id: Id) : LogOp<Id>
    public data class Compact<Id>(val compactedIds: Set<Id>) : LogOp<Id>
}

/** An op-log CRDT, viewed as the sequence of operations it holds. */
public interface OpLogCrdt<Id : Any, Op : Any> {
    public fun operations(): Sequence<Op>
    public fun classify(op: Op): LogOp<Id>
    public fun dotOf(id: Id): Dot
}
```

**The argument this task must make, in the PR body.**

`LogOp`, `OpLogEngine`'s adapters and `Rga.ops` are all `internal`, so `:kuilt-bolt` can see nothing. Something must be exposed and `explicitApi()` makes it a compatibility commitment. Three options:

1. **Make `Rga.ops` public.** Smallest diff, worst outcome — it publishes the concrete
   `Set<RgaOp<V>>` *field*, which pins the representation and forecloses both #2193's Phase 3B
   `RgaCache` work and any future backing change.
2. **Promote `LogOp` and add `OpLogCrdt` (recommended).** Exposes exactly the three-way
   classification and the dot projection an archive needs.
3. **Duplicate the classification inside `:kuilt-bolt`.** No new public surface in `:kuilt-crdt`, but
   the archive would need the ops anyway, so it does not actually avoid option 1 — and it puts the
   safety-critical `Compact` classification in two places.

**Make the argument precisely, because the obvious version of it is self-defeating.** "Option 1
exposes too much" cannot be the reason to prefer option 2 while option 2 offers
`operations(): Sequence<Op>`, which also yields every retained op — a reviewer applying that
rationale consistently would reject both. The real distinction is **representation independence**: a
`Sequence` is a *view* any future backing can stream, where `internal val ops: Set<RgaOp<V>>` is a
concrete field whose type is the representation. Say that, not "less surface".

Note also that `classify`/`dotOf` are instance methods, so an archive cannot classify an op without a
live CRDT in hand. Acceptable for v1 — only the append path classifies, and it always has one — but
state it, because a future replay-side validator would want them free-standing.

**The op-serializer question belongs in this task, not Task 2.** The design rests future readability
on the canonical serializers. `RgaOpSerializer` is `public` (`RgaOpSerializer.kt:32`), but
**`FugueOpSerializer` and `FugueSerializer` are `internal`** (`FugueSerializer.kt:153`, `:35`) — so
`:kuilt-bolt` cannot canonically encode a `FugueOp` at all. A worker who hits that will reach for the
compiler-generated sealed serializer, which has a **different wire format** (class-discriminator
polymorphism rather than the canonical `t`-tag) and the CBOR polymorphic-`V` limitation the canonical
serializers exist to bypass — producing an archive silently outside the golden-vector guarantee.

Resolve it here: either promote `FugueOpSerializer`, or put `opSerializer(vSerializer): KSerializer<Op>`
on `OpLogCrdt` so a consumer cannot wire the wrong one for either CRDT. The second is preferable —
it makes the correct serializer unmissable rather than merely available.

- [ ] **Step 1** — write `OpLogCrdtTest` asserting, for both `Rga` and `Fugue`: `operations()` yields every op in the log; `classify` returns `Insert`/`Remove`/`Compact` matching the concrete op type; `dotOf` agrees with `RgaId.dot` / the `Fugue` equivalent. **Cover `Fugue` explicitly** — the two are not symmetric (`Rga` has a `compactedBelow` floor, `Fugue` has none), and a test written only against `Rga` would let a `Fugue`-shaped bug through.
- [ ] **Step 2** — run, confirm unresolved-reference failures.
- [ ] **Step 3** — implement. `Rga`/`Fugue` already build the adapter pair (`Rga.kt:1090`, `Fugue.kt:1036`); conform via those, do not write a second classifier.
- [ ] **Step 4** — green; then confirm `:kuilt-crdt` gained **no** dependency: `./gradlew :kuilt-crdt:dependencies --configuration jvmRuntimeClasspath` must be unchanged.
- [ ] **Step 5** — every existing `Rga`/`Fugue` suite passes **unmodified**; full build; commit; PR says `part of` the bolt epic.

---

### Task 2: The module, the SPI, and the in-memory bolt

**Files:**
- Create: `kuilt-bolt/build.gradle.kts`, `settings.gradle.kts` entry, `:kuilt-bom` entry, `kuilt-bolt/module.md`
- Create: `Bolt.kt`, `BoltFrame.kt`, `ReplayScope.kt`, `BoltAvailability.kt`, `AppendResult.kt`, `InMemoryBolt.kt`
- Create: `BoltConformanceSuite.kt` in `commonMain` (subclassable, per the `SeamConformanceSuite` precedent)
- Test: `InMemoryBoltConformanceTest.kt`

**Interfaces produced:**

```kotlin
public interface Bolt<Op> {
    public suspend fun append(ops: List<Op>): AppendResult
    public fun replay(scope: ReplayScope): Flow<Archived<Op>>
    public fun availability(): BoltAvailability
}
```

**Segment header and frame fields are fixed now** — this is the expensive-to-change part.

Each **segment** opens with a magic number, a **format version**, and a self-description of what the
archive holds (op serializer, element type). A format justified by "read by future versions" must be
able to say which version wrote it; retrofitting that later is the expensive change this fixes.

Each **frame** carries: append offset; arrival timestamp; **insert-only** dots; a reserved key slot.

**Dots are informational; the append offset is the resume cursor.** A `Remove` mints no dot — it
reuses its target `Insert`'s id — so a frame of removes either claims its targets' *old* dots, in
which case a resume-from-dot cursor skips it and **replays a removed record as live**, or claims
nothing. Scoped replay by dot range is therefore defined over **inserts only**, and property 4 below
must test it that way.

**The clock is injected.** Arrival timestamps come from an injected `Clock`, never `Clock.System` reached for directly — time is a dependency in this repo, and a bolt with a wall-clock read inside it cannot be tested deterministically.

**Serialization uses the canonical serializers** (`RgaOpSerializer` is public), not compiler-generated ones — because an archive is read by future versions of the code that wrote it, and the canonical form is the one with golden vectors behind it.

**The conformance suite is where the invariant lives.** Every backend subclasses it. It must pin:

1. **Round-trip** — appended ops replay identically, in order.
2. **The firewall** — a bolt fed ops including a `Compact` **retains** the ops that `Compact` suppresses. *Mutation-check it: remove the discard and this test must redden.* This is the safety property of the whole module.
3. **Asymmetric retention, end-to-end** — a small-`maxRecords` live `Rga` plus a bolt; window the live replica; assert the bolt still replays the windowed records **and** that the live replica no longer holds them. This is the capability the module exists for; pin it directly rather than inferring it.
   **This property must ALSO be exercised through a gossiping `WarpLogRecordExporter`, not only a hand-driven `Rga`** — see Task 6. A bare-`Rga` version of this test passes while the shipped wiring archives nothing that arrived by merge, which is precisely the headline scenario.
4. **Scoped replay** — by arrival-time range, and by dot range **over inserts only**, each returning exactly the frames in scope. A test that scopes removes by dot is testing something the format deliberately does not promise.
5. **Empty append is a no-op** that writes no frame.
6. **`availability()` is honest** — a bolt reporting `Available` must accept an append.

**The one-way edge is enforced by absence.** `Bolt` must not implement `Quilted` and must expose no `piece`. Add a source-scan guard in the root build alongside the existing `forbid*` tasks — the property is "no path returns a live CRDT state from a bolt", and a compile-level absence is worth more than a runtime test.

- [ ] **Step 1** — write `BoltConformanceSuite` with the six properties above, as an abstract class over `newBolt()`.
- [ ] **Step 2** — run against a stub; confirm failures are the ones expected.
- [ ] **Step 3** — implement `InMemoryBolt` and the frame types; go green.
- [ ] **Step 4** — mutation-check property 2 and quote the red in the PR.
- [ ] **Step 5** — full build, tests alone, `detektAll`, commit.

---

### Task 3: mmap backend — JVM / Android

**Files:** `kuilt-bolt/src/jvmAndAndroidMain/.../MappedBolt.kt`, plus a `jvmTest` conformance subclass.

`FileChannel.map()` → `MappedByteBuffer`. **Synchronous vs asynchronous is one flag**: `force()` per append is synchronous; omitting it is asynchronous. Two implementations would be two things to keep in agreement; one flag is not.

Hazards to handle and to say out loud in KDoc:

- **Disk-full under mmap is SIGBUS, not an exception — and it makes the module's stated failure posture unachievable if unhandled.** Extending a mapped file, or `ftruncate`ing to segment size (which allocates **sparsely**), defers physical allocation to first page-touch. On a full disk that touch is a SIGBUS on POSIX and an unspecified VM error on the JVM — the process dies, taking the application's logging with it, which is the one outcome "best-effort" promises to avoid. **Eagerly, physically pre-allocate each segment at roll time — a real write, not `ftruncate`** — so exhaustion surfaces as a catchable I/O failure at a segment boundary. External truncation of a mapped file is the same class and gets the same answer.
- **Remapping.** A `MappedByteBuffer` is a fixed-size window; growing the archive means mapping a new region. Chunk into fixed-size segments rather than remapping one growing file. (This is also what makes eager pre-allocation affordable — one allocation per segment, not per append.)
- **Durability is `force()`, not `write()`.** Bytes in a mapped buffer are not durable until msync. The synchronous backend's contract — "fsync'd before `append` returns" — is exactly this call.
- **Torn frames.** A crash mid-append leaves a partial frame. Frames carry a length prefix and a checksum; replay stops at the first frame that does not validate and reports how far it got. **A truncated archive must not throw** — it must replay what is intact, on the same reasoning `WarpLogRecordExporter.recover()` never throws.
- **Windows/JVM unmapping** is not portable; do not add a `close()` contract that depends on prompt unmapping.

Test through `BoltConformanceSuite` plus crash-recovery tests in the style of `FileChannelDurableStoreTest` (construct a second instance over the same directory).

---

### Task 4: mmap backend — Apple

**Files:** `kuilt-bolt/src/appleMain/.../MappedBolt.apple.kt`, `appleTest` conformance subclass.

`platform.posix` mmap. The interop path is proven in-tree — `NSFileManagerDurableStore` already imports `memcpy`, `rename`, `errno`.

**This backend is not the default on Apple, and the KDoc must say why:**

- **SIGBUS on page-touch**, exactly as in Task 3, and worse here: an iOS device that is full is a routine state, not an edge case. Eager physical pre-allocation per segment is mandatory on this backend, not advisory.
- **Jetsam.** Mapped dirty pages count against an iOS app's memory footprint. A growing mapped archive on a phone is a way to get the app killed. A phone should retain least anyway — the server is this backend's customer.
- **Data Protection.** A file's protection class can make it unreadable while the device is locked. A background-writing archive needs an explicit class chosen deliberately, and this is a failure that appears only on real hardware.

**`detektAll` lints nothing in `appleMain`** — detekt's type resolution is JVM-only. Do not cite a green `detektAll` as coverage for this task's code; review it by eye.

---

### Task 5: wasmJs — honest unavailability

**Files:** `kuilt-bolt/src/wasmJsMain/.../MappedBolt.wasmJs.kt`.

No filesystem exists. v1 returns `BoltAvailability.Unavailable` with a reason, so a consumer learns by asking rather than by crashing — the `Loom.availability()` pattern.

`InMemoryBolt` still works on wasm and the conformance suite still runs there, so the module is not dead on that target. **File a follow-up issue for a chunked-IndexedDB backend** rather than leaving the gap undocumented — a deferred alternative gets an issue in the same turn as the decision to defer it.

---

### Task 6: The decorator, and publishing applied ops

**Files:** `kuilt-bolt/src/commonMain/.../BoltDecorator.kt`; modify `WarpLogRecordExporter` to publish applied ops.

**There are two paths and the second is the one that matters. Do not ship only the first.**

`export()` holds its ops already, returned from `insertAllAfter` / `removeFirst` — a straightforward tee.

**`merge()` produces no op stream.** It is `log = log.piece(remote)` (`mergeTurn`), a state join. Gossip is how a phone's records reach a server, so a decorator that tees only `export()` gives a server-side bolt containing the server's own telemetry and **zero phone records** — the exact capability this module's Goal line calls impossible today. The merge path must publish too, by enumerating `remote`'s operations through `OpLogCrdt.operations()`.

**And that raises deduplication, which append-only does not give for free.** Anti-entropy re-merges the same remote log repeatedly, so appending `remote`'s ops per round writes one full copy of the peer's log **per round**. The frame's dots cannot dedup a `Remove` (it mints no dot), so identity must include an `Insert`/`Remove` discriminator on the same id. Cheapest sound design: an in-memory set of appended op identities, rebuilt from the archive's tail on open. **Unbounded growth per merge round is the failure to design against**, and it must have a test that merges the same remote twice and asserts the archive did not double.

**Do not put a `Bolt` parameter on `WarpLogRecordExporter`.** The exporter stays ignorant of archiving and the bolt ignorant of telemetry, so the same decorator serves any `Rga`/`Fugue` owner.

**Failure semantics — best-effort, reporting identities, not a tally.** A full archive disk must not take down the application's logging, and a failed append must not fail the export. But a failed append here means the record is lost from *both* sides once the live replica windows it away — so the health surface carries the failed frames' **dots and offset range**, per this repo's #1466/#1860 rule to log identities and state rather than sizes. A bare `failed++` makes every recovery — defer windowing, re-feed, correlate against a backend — unimplementable.

**Ordering hazard to pin:** if the decorator publishes ops before the exporter's durable write returns, a failed export leaves the bolt holding records the live replica does not. That is *acceptable* for an archive — a superset is the point — but it must be a stated property with a test, not an accident.

---

### Task 7: Docs

`kuilt-bolt/module.md`, a `docs/agent-cookbook.md` entry, `@sample` functions, and a routing check in `.claude/skills/kuilt-primitives/SKILL.md`.

- **Accessible-first is enforced.** The top of `module.md` must read for a non-engineer: lead with "a phone can only keep so much; a server can keep a year", not with `Quilted` or `LogOp`.
- **`verifyDocCitations` is wired into `check` and runs as its own CI job.** A `<!-- verbatim from … -->` block must match character-for-character. If it fails, **re-copy** — relabelling to `condensed from` is a one-way door after which the block is never content-checked again.
- **A dangling `@sample` link is NOT caught by any build.** `compileTestKotlinJvm` compiles sample *bodies* but never reads the KDoc tag; Dokka only warns and `failOnWarning` is unset repo-wide. Verify by eye: `grep -rn "sampleBolt" kuilt-bolt/src/`.
- **Dokka does not expand `@sample` inside a `module.md`** (#2206) — the tag renders literally. Point at the declaration's own KDoc instead.

---

## Verification before any PR leaves draft

- [ ] `./gradlew build detektAll --max-workers=6` green, tasks `EXECUTED`.
- [ ] `./gradlew verifyDocCitations` green.
- [ ] Every new test alone, verdict from the results XML.
- [ ] The firewall mutation receipt (Task 2 property 2) quoted in the PR.
- [ ] `./gradlew :kuilt-crdt:dependencies` proves `:kuilt-crdt` gained nothing.
- [ ] Close-keyword audit clean.

## Explicitly out of scope

- **Retention policy.** A bolt stores what it is given.
- **Gossip or anti-entropy from a bolt.** It is not a peer — that is the invariant, not a gap.
- **Event-time indexing.** The frame reserves a key slot; the index is a follow-up.
- **Chunked IndexedDB on wasm.** Follow-up issue from Task 5.
- **#2193's Phase 3A/3B.** Different subsystem; see `2026-08-08-otel-export-path-residual.md`.
