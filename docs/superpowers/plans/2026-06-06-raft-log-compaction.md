# kuilt-raft Log Compaction & InstallSnapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add §7 log compaction to `:kuilt-raft` — opaque consumer-owned snapshots, log-prefix discard, and a chunked `InstallSnapshot` RPC so a node offline across a compaction boundary can rejoin.

**Architecture:** The consumer publishes its latest state-machine snapshot into a conflated `MutableStateFlow<Snapshot?>` (push on its clock; raft samples on its clock). Raft persists it, discards the log prefix, and exposes the retained floor as `compactionFloor: StateFlow<Long>`. A lagging follower whose `nextIndex` falls at/below the leader's compaction floor is caught up via a chunked `InstallSnapshot` RPC (chunk size derived from `RaftTransport.maxPayloadBytes`). Inbound, both `committed` and `committedFrom` carry a unified ordered `Flow<Committed>` (`Entry | Install`) so a reset arrives in order with the entries around it.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (actor over `Channel`, `StateFlow`/`SharedFlow`), kotlinx.serialization (CBOR for the wire), kotlin.test + `UnconfinedTestDispatcher`.

**Spec:** `docs/superpowers/specs/2026-06-06-raft-log-compaction-design.md`

---

## File structure

| File | Responsibility | Task |
|------|----------------|------|
| `kuilt-raft/.../raft/RaftStorage.kt` | + `SnapshotMeta`, `StoredSnapshot`, `saveSnapshot`/`loadSnapshot`/`discardLogPrefix` | 1 |
| `kuilt-raft/.../raft/InMemoryRaftStorage.kt` | implement the three new methods | 1 |
| `kuilt-raft/.../raft/Snapshot.kt` (new) | `Snapshot(throughIndex, state)` value type | 2 |
| `kuilt-raft/.../raft/Committed.kt` (new) | `sealed interface Committed { Entry, Install }` | 2 |
| `kuilt-raft/.../raft/RaftNode.kt` | `committed`/`committedFrom` → `Flow<Committed>`; + `snapshots`, `compactionFloor` | 2 |
| `kuilt-raft/.../raft/RaftTransport.kt` | + `maxPayloadBytes: Int? get() = null` | 2 |
| `kuilt-raft/.../raft/internal/RaftEngine.kt` | wrap emissions; compaction; boundary math; install RPC wiring | 2,3,4,5 |
| `kuilt-raft/.../raft/internal/EngineCommand.kt` | + `Compact`; extend `CommitCutResult` with an optional install | 3 |
| `kuilt-raft/.../raft/internal/RaftMessage.kt` | + `InstallSnapshot`, `InstallSnapshotResponse` | 4 |
| `kuilt-raft/.../raft/RaftConfig.kt` | + `snapshotChunkCeiling: Int` | 4 |
| `kuilt-raft/.../raft/RaftTraceEvent.kt` | + `Compacted`, `InstallSnapshot`, `InstallSnapshotAccepted` | 3,4 |
| `kuilt-raft/src/commonTest/.../raft/*` | new tests + update call sites for the `Committed` wrapper | all |

Each task is a self-contained, green-building checkpoint suitable for its own stacked PR. Run the JVM suite (`./gradlew :kuilt-raft:jvmTest`) as the fast inner loop; run `./gradlew :kuilt-raft:allTests` before opening each PR.

**Environment:** non-interactive shells must first run
`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.

---

## Task 1: Snapshot persistence in RaftStorage

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftStorage.kt`
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorage.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorageSnapshotTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorageSnapshotTest.kt`:

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class InMemoryRaftStorageSnapshotTest {

    @Test
    fun loadSnapshot_isNullBeforeAnySave() = runTest {
        assertNull(InMemoryRaftStorage().loadSnapshot())
    }

    @Test
    fun saveThenLoad_roundTripsMetaAndBytes() = runTest {
        val s = InMemoryRaftStorage()
        s.saveSnapshot(SnapshotMeta(lastIncludedIndex = 5L, lastIncludedTerm = 2L), byteArrayOf(1, 2, 3))
        val loaded = s.loadSnapshot()!!
        assertEquals(SnapshotMeta(5L, 2L), loaded.meta)
        assertContentEquals(byteArrayOf(1, 2, 3), loaded.state)
    }

    @Test
    fun saveSnapshot_overwritesPrevious() = runTest {
        val s = InMemoryRaftStorage()
        s.saveSnapshot(SnapshotMeta(5L, 2L), byteArrayOf(1))
        s.saveSnapshot(SnapshotMeta(9L, 3L), byteArrayOf(2))
        assertEquals(SnapshotMeta(9L, 3L), s.loadSnapshot()!!.meta)
    }

    @Test
    fun discardLogPrefix_removesEntriesUpToAndIncludingIndex() = runTest {
        val s = InMemoryRaftStorage()
        s.appendEntries((1L..5L).map { LogEntry(it, term = 1L, command = byteArrayOf(it.toByte())) })
        s.discardLogPrefix(throughIndex = 3L)
        val remaining = s.entries()
        assertEquals(listOf(4L, 5L), remaining.map { it.index })
    }

    @Test
    fun discardLogPrefix_isIdempotentAndToleratesGapBelowFloor() = runTest {
        val s = InMemoryRaftStorage()
        s.appendEntries((4L..5L).map { LogEntry(it, 1L, byteArrayOf()) })
        s.discardLogPrefix(3L) // floor below the first retained entry — no-op
        assertEquals(listOf(4L, 5L), s.entries().map { it.index })
        s.discardLogPrefix(5L)
        assertTrue(s.entries().isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftStorageSnapshotTest*"`
Expected: FAIL — `SnapshotMeta` / `saveSnapshot` / `loadSnapshot` / `discardLogPrefix` unresolved.

- [ ] **Step 3: Add the types and interface methods**

In `RaftStorage.kt`, above the `RaftStorage` interface, add:

```kotlin
/** Identifies the log position a snapshot covers: everything with `index <= lastIncludedIndex`. */
public data class SnapshotMeta(val lastIncludedIndex: Long, val lastIncludedTerm: Long)

/** A persisted snapshot: its [meta] plus the opaque application [state] bytes. */
public class StoredSnapshot(public val meta: SnapshotMeta, public val state: ByteArray)
```

Inside the `RaftStorage` interface, after `truncateFrom`, add:

```kotlin
    /**
     * Persists [state] as the snapshot covering all entries with `index <= meta.lastIncludedIndex`.
     *
     * Crash-safety: this MUST be durable before [discardLogPrefix] runs. A crash between the two
     * leaves the snapshot plus the full log — redundant but safe and recoverable. Overwrites any
     * previously stored snapshot (an older snapshot is strictly dominated).
     */
    public suspend fun saveSnapshot(meta: SnapshotMeta, state: ByteArray)

    /** Returns the stored snapshot, or `null` if none has been saved. */
    public suspend fun loadSnapshot(): StoredSnapshot?

    /** Removes all log entries with `index <= throughIndex`. Idempotent; tolerates a floor below the first retained entry. */
    public suspend fun discardLogPrefix(throughIndex: Long)
```

- [ ] **Step 4: Implement in InMemoryRaftStorage**

In `InMemoryRaftStorage.kt`, add a field and the three methods:

```kotlin
    private var snapshot: StoredSnapshot? = null

    override suspend fun saveSnapshot(meta: SnapshotMeta, state: ByteArray) {
        snapshot = StoredSnapshot(meta, state)
    }
    override suspend fun loadSnapshot(): StoredSnapshot? = snapshot
    override suspend fun discardLogPrefix(throughIndex: Long) { log.removeAll { it.index <= throughIndex } }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftStorageSnapshotTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftStorage.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorage.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorageSnapshotTest.kt
git commit -m "feat(kuilt-raft): snapshot persistence in RaftStorage (#114)"
```

---

## Task 2: Public types + unified `Committed` stream (breaking, no compaction yet)

This task introduces `Snapshot`/`Committed`, changes `committed`/`committedFrom` to `Flow<Committed>`, adds the `snapshots`/`compactionFloor` properties and `RaftTransport.maxPayloadBytes`, and rewires `RaftEngine` to wrap every application emission as `Committed.Entry`. **No compaction behavior** — `snapshots` is collected but ignored, `compactionFloor` stays `0`. The whole module compiles and every existing test passes after its call sites are updated to unwrap `Committed.Entry`.

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/Snapshot.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/Committed.kt`
- Modify: `RaftNode.kt`, `RaftTransport.kt`, `internal/RaftEngine.kt`, `internal/EngineCommand.kt`
- Modify (tests): `ChaosTest.kt`, `CommittedReplayTest.kt`, `DocumentedUsageTest.kt`, `EngineCorrectnessTest.kt`, `LearnerTest.kt`, `NoOpEntryTest.kt`, `ReplicationTest.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/CommittedTypeTest.kt` (new)

- [ ] **Step 1: Write the new public types**

Create `Snapshot.kt`:

```kotlin
package us.tractat.kuilt.raft

/**
 * A state-machine snapshot. Opaque to kuilt-raft — it never deserializes [state]; only the consumer
 * can collapse many log entries into compact state. The same payload flows both directions: the
 * consumer publishes it via [RaftNode.snapshots] (outbound), and a lagging node receives it wrapped
 * in [Committed.Install] (inbound).
 *
 * @param throughIndex the highest committed log index this state reflects.
 * @param state opaque application bytes.
 */
public class Snapshot(public val throughIndex: Long, public val state: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Snapshot) return false
        return throughIndex == other.throughIndex && state.contentEquals(other.state)
    }
    override fun hashCode(): Int = 31 * throughIndex.hashCode() + state.contentHashCode()
    override fun toString(): String = "Snapshot(throughIndex=$throughIndex, state=${state.size}B)"
}
```

Create `Committed.kt`:

```kotlin
package us.tractat.kuilt.raft

/**
 * One ordered instruction in the committed stream delivered to a consumer's state machine.
 *
 * A reset ([Install]) always arrives in order relative to the [Entry]s around it: apply nothing with
 * `index <= snapshot.throughIndex` after an install, and miss nothing before it. This in-order
 * guarantee is why the stream is a single sealed type rather than two parallel flows.
 */
public sealed interface Committed {
    /** Apply this committed application entry. */
    public data class Entry(val entry: LogEntry) : Committed
    /** Discard current state and reset the state machine to [snapshot]. Rare — only after a real install. */
    public data class Install(val snapshot: Snapshot) : Committed
}
```

- [ ] **Step 2: Change the `RaftNode` interface**

In `RaftNode.kt`: add the imports `import kotlinx.coroutines.flow.MutableStateFlow`. Change the two flow declarations and add the two new properties. Replace the `committed` property type and the `committedFrom` return type:

```kotlin
    public val committed: Flow<Committed>            // was Flow<LogEntry>
```
```kotlin
    public fun committedFrom(fromIndex: Long): Flow<Committed>   // was Flow<LogEntry>
```

After `committedFrom`, add:

```kotlin
    /**
     * The outbound snapshot channel. The consumer publishes its latest durable state-machine snapshot
     * here on its own clock (`snapshots.value = Snapshot(appliedIndex, bytes)`); raft samples it on its
     * own clock to compact the log prefix and to serve a lagging follower. Conflated — only the newest
     * snapshot matters. Leaving it `null` disables compaction (the pre-compaction behavior).
     */
    public val snapshots: MutableStateFlow<Snapshot?>

    /** The `lastIncludedIndex` of the most recent compaction, or `0` if nothing has been compacted. */
    public val compactionFloor: StateFlow<Long>
```

Update the KDoc on `committed`/`committedFrom` so the `Flow<LogEntry>` prose now reads `Flow<Committed>` and mentions that `committedFrom(fromIndex)` with `fromIndex <= compactionFloor` emits `Committed.Install` first. (Edit the existing doc comments; do not leave stale `LogEntry` references.)

- [ ] **Step 3: Add `maxPayloadBytes` to `RaftTransport`**

In `RaftTransport.kt`, inside the interface, add:

```kotlin
    /**
     * The maximum number of bytes a single [sendTo] payload can carry, or `null` if effectively
     * unbounded (e.g. WebSocket). Fabrics with hard framing limits (e.g. ~32 KiB on some LAN radios)
     * return that limit so kuilt-raft can size InstallSnapshot chunks to fit. Defaulted — existing
     * transports need no change.
     */
    public val maxPayloadBytes: Int? get() = null
```

- [ ] **Step 4: Rewire `RaftEngine` emissions to `Committed` (no compaction)**

In `RaftEngine.kt`:

Change the backing flow and the two public members. Replace:
```kotlin
    private val _committed = MutableSharedFlow<LogEntry>(
```
with
```kotlin
    private val _committed = MutableSharedFlow<Committed>(
```
Replace `override val committed: Flow<LogEntry> = _committed` with `override val committed: Flow<Committed> = _committed`.

Add the two new properties near the other `MutableStateFlow`s:
```kotlin
    override val snapshots = MutableStateFlow<Snapshot?>(null)

    private val _compactionFloor = MutableStateFlow(0L)
    override val compactionFloor: StateFlow<Long> = _compactionFloor.asStateFlow()
```

In `advanceCommit`, change the emit to wrap:
```kotlin
            if (!entry.isNoOp) _committed.emit(Committed.Entry(entry))
```

In `committedFrom`, the replay loop and tail must emit `Committed.Entry`. Replace `cut.replay.forEach { emit(it) }` with:
```kotlin
            cut.install?.let { emit(Committed.Install(it)) }     // null until Task 3 wires it
            cut.replay.forEach { emit(Committed.Entry(it)) }
```
and inside the tail `for (entry in buffer)` loop change `emit(entry)` to `emit(Committed.Entry(entry))`.

In `EngineCommand.kt`, extend `CommitCutResult` with the install slot (set `null` for now):
```kotlin
internal class CommitCutResult(
    val replay: List<LogEntry>,
    val cutIndex: Long,
    val install: Snapshot? = null,
)
```
Add `import us.tractat.kuilt.raft.Snapshot` to `EngineCommand.kt`.

- [ ] **Step 5: Update existing test call sites**

These tests consume `committed`/`committedFrom` as `Flow<LogEntry>`. Update each to unwrap entries. The mechanical transform: a collected element is now `Committed`; project to entries with `filterIsInstance<Committed.Entry>().map { it.entry }`. Concretely:

In `ReplicationTest.kt:29`, replace:
```kotlin
            async { f.committed.filter { it.command.isNotEmpty() }.first() }
```
with:
```kotlin
            async {
                f.committed.filterIsInstance<Committed.Entry>().map { it.entry }
                    .filter { it.command.isNotEmpty() }.first()
            }
```
Add `import kotlinx.coroutines.flow.filterIsInstance` and `import kotlinx.coroutines.flow.map` where needed.

In `NoOpEntryTest.kt`, the two `committed.collect { seen += it }` sites (lines ~144, ~177) collect into a `List<LogEntry>`. Change the collect to unwrap:
```kotlin
        backgroundScope.launch { node.committed.collect { if (it is Committed.Entry) seen += it.entry } }
```
The assertions on `seen` (`.index`, `.command`) then work unchanged.

For every other flagged test file (`ChaosTest.kt`, `CommittedReplayTest.kt`, `DocumentedUsageTest.kt`, `EngineCorrectnessTest.kt`, `LearnerTest.kt`), apply the same rule wherever an element of `committed`/`committedFrom` is used as a `LogEntry`: collect/await the flow through `filterIsInstance<Committed.Entry>().map { it.entry }` first. Compile after each file (`./gradlew :kuilt-raft:compileTestKotlinJvm`) and fix the next unresolved reference until the module compiles. Do not change any assertion semantics — only unwrap the element type.

- [ ] **Step 6: Write the new type test**

Create `CommittedTypeTest.kt`:

```kotlin
package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CommittedTypeTest {
    @Test
    fun snapshot_valueEquality() {
        assertEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(3L, byteArrayOf(1, 2)))
        assertNotEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(3L, byteArrayOf(9)))
        assertNotEquals(Snapshot(3L, byteArrayOf(1, 2)), Snapshot(4L, byteArrayOf(1, 2)))
    }

    @Test
    fun committed_entryWrapsLogEntry() {
        val e = LogEntry(7L, 2L, byteArrayOf(42))
        assertEquals(e, (Committed.Entry(e) as Committed.Entry).entry)
    }
}
```

- [ ] **Step 7: Run the full module JVM suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS — all pre-existing tests green under the new `Committed` element type, plus `CommittedTypeTest`.

- [ ] **Step 8: Commit**

```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft)!: unified Committed stream + snapshot API surface (#114)

BREAKING: committed/committedFrom now emit Flow<Committed> (Entry | Install).
No compaction behavior yet — snapshots is sampled-but-ignored, compactionFloor stays 0."
```

---

## Task 3: Compaction — produce snapshot, discard prefix, boundary math

Wire the actor to compact when `snapshots.value` advances, expose `compactionFloor`, teach the engine the snapshot boundary, and make `committedFrom` below the floor lead with `Committed.Install`. The leader→laggard path still cannot catch a peer up (that is Task 4) — so the headline offline-rejoin test stays red until Task 4.

**Files:**
- Modify: `internal/RaftEngine.kt`, `internal/EngineCommand.kt`, `RaftTraceEvent.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/CompactionTest.kt` (new)

- [ ] **Step 1: Write the failing tests**

Create `CompactionTest.kt`. These drive a single-voter node (commits immediately, no peers) so compaction is observable without the install path:

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {

    @Test
    fun publishingSnapshot_advancesCompactionFloorAndDiscardsPrefix() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(this)          // see RaftTestFixtures (Step 4 helper)
        h.node.awaitLeadership()
        repeat(5) { h.node.propose(byteArrayOf(it.toByte())) }
        h.node.commitIndex.first { it >= 5L }

        h.node.snapshots.value = Snapshot(throughIndex = 3L, state = byteArrayOf(99))
        h.node.compactionFloor.first { it == 3L }   // suspends until compaction runs

        assertEquals(3L, h.node.compactionFloor.value)
        assertEquals(listOf(4L, 5L), h.storage.entries().map { it.index }, "prefix <= 3 discarded")
        assertEquals(SnapshotMeta(3L, h.storage.entries(0).firstOrNull()?.term ?: 1L).lastIncludedIndex,
                     h.storage.loadSnapshot()!!.meta.lastIncludedIndex)
    }

    @Test
    fun snapshotBeyondCommitIndex_isIgnoredUntilCommitCatchesUp() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(this)
        h.node.awaitLeadership()
        h.node.propose(byteArrayOf(1))
        h.node.commitIndex.first { it >= 1L }
        h.node.snapshots.value = Snapshot(throughIndex = 99L, state = byteArrayOf(0)) // beyond commit
        // floor must NOT advance to 99
        repeat(3) { h.node.propose(byteArrayOf(it.toByte())) }
        assertTrue(h.node.compactionFloor.value < 99L)
    }

    @Test
    fun committedFrom_belowFloor_leadsWithInstall() = runTest(UnconfinedTestDispatcher()) {
        val h = singleVoterNode(this)
        h.node.awaitLeadership()
        repeat(5) { h.node.propose(byteArrayOf(it.toByte())) }
        h.node.commitIndex.first { it >= 5L }
        h.node.snapshots.value = Snapshot(3L, byteArrayOf(123))
        h.node.compactionFloor.first { it == 3L }

        val seen = h.node.committedFrom(1L).take(3).toList()   // Install + entries 4,5
        assertTrue(seen.first() is Committed.Install, "below floor must lead with Install")
        assertEquals(Snapshot(3L, byteArrayOf(123)), (seen.first() as Committed.Install).snapshot)
        assertEquals(listOf(4L, 5L),
            seen.drop(1).filterIsInstance<Committed.Entry>().map { it.entry.index })
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*CompactionTest*"`
Expected: FAIL — `compactionFloor` never advances; `committedFrom` emits no `Install` (and `singleVoterNode` may need adding in Step 4).

- [ ] **Step 3: Add the `Compacted` trace event**

In `RaftTraceEvent.kt`, add a variant matching the file's existing event style (each event carries a `clock: Long` and `node: NodeId`):

```kotlin
    public data class Compacted(
        override val clock: Long,
        val node: NodeId,
        val throughIndex: Long,
        val throughTerm: Long,
    ) : RaftTraceEvent
```
(Match the exact shape of the surrounding events — copy the `override val clock` pattern used by e.g. `AdvanceCommitIndex`.)

- [ ] **Step 4: Add the `singleVoterNode` test helper**

In `RaftTestFixtures.kt`, add (reuse whatever single-node construction the file already provides; this wrapper just exposes `storage` alongside `node`):

```kotlin
class SingleVoterHarness(val node: RaftNode, val storage: InMemoryRaftStorage)

// storage is injectable so the recovery test (Task 5) can pre-load a persisted snapshot + log.
fun singleVoterNode(
    scope: kotlinx.coroutines.CoroutineScope,
    storage: InMemoryRaftStorage = InMemoryRaftStorage(),
): SingleVoterHarness {
    val self = NodeId("solo")
    val cluster = ClusterConfig.ofVoters(listOf(self))
    val transport = LoopbackTransport(self)          // existing in-test transport; if absent, reuse the
                                                     // in-memory transport RaftSimulation builds for one node
    val node = scope.raftNode(cluster, transport, storage)
    return SingleVoterHarness(node, storage)
}
```
If `RaftTestFixtures.kt` already exposes a single-node builder, wrap that instead of duplicating transport wiring — do not introduce a second transport type. Whatever single-node transport this resolves to is the **one** construction point reused by Tasks 3 and 5.

- [ ] **Step 5: Add the `Compact` engine command**

In `EngineCommand.kt`, add to the sealed interface:
```kotlin
    data object Compact : EngineCommand
```

- [ ] **Step 6: Implement compaction + boundary math in `RaftEngine`**

Add actor state near `currentCommitIndex`:
```kotlin
    private var snapshotIndex = 0L
    private var snapshotTerm = 0L
```

Add a collector in `init` (after the `transport.incoming` collect launch) so a published snapshot wakes the actor:
```kotlin
            launch { snapshots.collect { cmd.trySend(EngineCommand.Compact) } }
```

Add the dispatcher arm in `startActor`'s `when`:
```kotlin
                        is EngineCommand.Compact -> onCompact()
```

Add the boundary-aware log helpers (replace the existing `lastLogIndex`/`lastLogTerm`):
```kotlin
    private val lastLogIndex: Long get() = log.lastOrNull()?.index ?: snapshotIndex
    private val lastLogTerm: Long get() = log.lastOrNull()?.term ?: snapshotTerm

    /** Term at [index], or null if [index] is in the compacted prefix (unknowable). */
    private fun termAt(index: Long): Long? = when {
        index == snapshotIndex -> snapshotTerm
        index < snapshotIndex  -> null
        else                   -> entryAt(index)?.term
    }
```

Implement `onCompact`:
```kotlin
    private suspend fun onCompact() {
        val s = snapshots.value ?: return
        if (s.throughIndex <= snapshotIndex || s.throughIndex > currentCommitIndex) return
        val term = termAt(s.throughIndex) ?: return    // must be a live, committed entry
        storage.saveSnapshot(SnapshotMeta(s.throughIndex, term), s.state)   // durable FIRST
        storage.discardLogPrefix(s.throughIndex)                            // then drop prefix
        log.removeAll { it.index <= s.throughIndex }
        snapshotIndex = s.throughIndex
        snapshotTerm = term
        _compactionFloor.value = snapshotIndex
        emitTrace(RaftTraceEvent.Compacted(nextClock(), transport.selfId, snapshotIndex, snapshotTerm))
    }
```

Make `onCommitCut` lead with the install when the request starts below the floor:
```kotlin
    private suspend fun onCommitCut(c: EngineCommand.CommitCut) {
        val install = if (c.fromIndex <= snapshotIndex && snapshotIndex > 0L)
            storage.loadSnapshot()?.let { Snapshot(it.meta.lastIncludedIndex, it.state) } else null
        val from = maxOf(c.fromIndex, snapshotIndex + 1)
        val replay = log.filter { it.index in from..currentCommitIndex && !it.isNoOp }
        c.response.complete(CommitCutResult(replay, currentCommitIndex, install))
    }
```
(`onCommitCut` becomes `suspend` because of `loadSnapshot()` — update its call site in the actor `when` if the compiler flags it; the actor body is already a suspend context.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*CompactionTest*"`
Expected: PASS.

- [ ] **Step 8: Run the full module suite (no regressions)**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft): log compaction — prefix discard + boundary math (#114)"
```

---

## Task 4: Chunked InstallSnapshot RPC (the offline-rejoin fix)

Add the RPC, the leader transfer state machine, follower reassembly, and the
`nextIndex <= snapshotIndex ⇒ InstallSnapshot` leader branch. The headline
reproduction — *a node offline across a compaction boundary cannot rejoin* — is
written **red first**, then this task makes it green (per the TDD convention:
test commit first, fix commit second, same PR).

**Files:**
- Modify: `internal/RaftMessage.kt`, `internal/RaftEngine.kt`, `RaftConfig.kt`, `RaftTraceEvent.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InstallSnapshotTest.kt` (new)

- [ ] **Step 1: Write the failing headline reproduction (commit it red)**

Create `InstallSnapshotTest.kt`. Use the existing `RaftSimulation` harness (a multi-node in-memory cluster with controllable delivery/partitions — inspect `RaftSimulation.kt` for its exact API and mirror the patterns the other simulation tests use):

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallSnapshotTest {

    /**
     * Headline repro (#114): a follower that is offline across a compaction boundary must be able to
     * rejoin via InstallSnapshot — the leader no longer holds entries at the follower's prevLogIndex.
     */
    @Test
    fun offlineFollower_rejoinsViaInstallSnapshot_afterCompaction() = runTest(UnconfinedTestDispatcher()) {
        val sim = RaftSimulation(this, voters = listOf("a", "b", "c"))   // mirror existing sim setup
        val leader = sim.electLeader("a")

        sim.partitionOff("c")                       // c goes offline
        repeat(20) { leader.propose(byteArrayOf(it.toByte())) }
        sim.awaitCommit(index = 20L)

        // leader's consumer snapshots through 15 and raft compacts past where c left off
        leader.snapshots.value = Snapshot(15L, sim.stateBytes("a"))
        leader.compactionFloor.first { it == 15L }

        // c rejoins — it cannot be caught up by AppendEntries (prefix <= 15 is gone)
        val install = sim.collectInstalls("c")      // background collector of Committed.Install on c
        sim.heal("c")
        sim.awaitCommit(node = "c", index = 20L)    // c reaches 20 only if InstallSnapshot worked

        assertTrue(install.isNotEmpty(), "c must receive a Committed.Install")
        assertEquals(15L, install.last().snapshot.throughIndex)
        assertEquals(sim.appliedState("a"), sim.appliedState("c"), "c's state machine matches the leader's")
    }

    @Test
    fun chunkedTransfer_reassemblesUnderTinyMaxPayload() = runTest(UnconfinedTestDispatcher()) {
        // transport reports a tiny maxPayloadBytes so a small snapshot still spans many chunks
        val sim = RaftSimulation(this, voters = listOf("a", "b", "c"), maxPayloadBytes = 64)
        val leader = sim.electLeader("a")
        sim.partitionOff("c")
        repeat(10) { leader.propose(ByteArray(8) { 1 }) }
        sim.awaitCommit(10L)
        leader.snapshots.value = Snapshot(10L, ByteArray(1000) { it.toByte() })  // ~16 chunks at 64B
        leader.compactionFloor.first { it == 10L }
        val install = sim.collectInstalls("c")
        sim.heal("c")
        sim.awaitCommit(node = "c", index = 10L)
        assertEquals(10L, install.last().snapshot.throughIndex)
        assertEquals(1000, install.last().snapshot.state.size, "all chunks reassembled in order")
    }
}
```

If `RaftSimulation` lacks `partitionOff`/`heal`/`maxPayloadBytes`/`collectInstalls`/`appliedState`/`stateBytes`, add the minimal helpers to `RaftSimulation.kt` to support these (a per-node `installs` collector list; a `maxPayloadBytes` ctor param threaded into the in-memory transport; partition controls likely already exist — reuse them). Keep additions small and named as above so later tasks reuse them.

- [ ] **Step 2: Run to verify it fails, then commit the red test**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*InstallSnapshotTest*"`
Expected: FAIL — `c` never reaches index 20/10; no `Committed.Install` (leader can't catch it up).

```bash
git add kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InstallSnapshotTest.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt
git commit -m "test(kuilt-raft): red repro — offline follower can't rejoin after compaction (#114)"
```

- [ ] **Step 3: Add the wire messages**

In `RaftMessage.kt`, add two variants to the sealed interface:

```kotlin
    @Serializable
    data class InstallSnapshot(
        val term: Long,
        val leaderId: NodeId,
        val lastIncludedIndex: Long,
        val lastIncludedTerm: Long,
        val offset: Long,
        val data: ByteArray,
        val done: Boolean,
    ) : RaftMessage

    @Serializable
    data class InstallSnapshotResponse(
        val term: Long,
        val nextOffset: Long,   // bytes the follower has stored — resyncs the leader after a dropped chunk
    ) : RaftMessage
```

- [ ] **Step 4: Add config + trace events**

In `RaftConfig.kt`, add a constructor param (with KDoc matching the file's style):
```kotlin
    val snapshotChunkCeiling: Int = 16 * 1024,
```

In `RaftTraceEvent.kt`, add:
```kotlin
    public data class InstallSnapshot(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
        val offset: Long,
        val done: Boolean,
    ) : RaftTraceEvent

    public data class InstallSnapshotAccepted(
        override val clock: Long,
        val from: NodeId,
        val to: NodeId,
        val lastIncludedIndex: Long,
    ) : RaftTraceEvent
```

- [ ] **Step 5: Implement the leader transfer state + send branch**

In `RaftEngine.kt`, add a `HEADER_BUDGET` constant and leader-only transfer state:
```kotlin
    private companion object { const val HEADER_BUDGET = 256 }

    private class SnapshotXfer(val meta: SnapshotMeta, val state: ByteArray, var nextOffset: Long)
    private val snapshotXfer = mutableMapOf<NodeId, SnapshotXfer>()
```

Compute the chunk size:
```kotlin
    private fun chunkBytes(): Int {
        val cap = transport.maxPayloadBytes?.let { minOf(it, raftConfig.snapshotChunkCeiling) }
            ?: raftConfig.snapshotChunkCeiling
        return maxOf(1, cap - HEADER_BUDGET)
    }
```

In `sendAppendEntries(peer)`, at the very top, divert to the snapshot path when the needed prefix is gone:
```kotlin
        val ni = nextIndex[peer] ?: 1L
        if (ni <= snapshotIndex) { sendSnapshotChunk(peer, restart = true); return }
        val prevIndex = ni - 1L
        val prevTerm = if (prevIndex == snapshotIndex) snapshotTerm else entryAt(prevIndex)?.term ?: 0L
```
(Replace the existing `prev = entryAt(ni - 1L)` usage so `prevLogTerm` uses `prevTerm` and `prevLogIndex` uses `prevIndex`. Keep the rest of the method — `entries = log.filter { it.index >= ni }` — unchanged.)

Add the chunk sender:
```kotlin
    private suspend fun sendSnapshotChunk(peer: NodeId, restart: Boolean) {
        val xfer = if (restart || snapshotXfer[peer] == null) {
            val stored = storage.loadSnapshot() ?: return   // nothing to send yet
            SnapshotXfer(stored.meta, stored.state, 0L).also { snapshotXfer[peer] = it }
        } else snapshotXfer.getValue(peer)
        val start = xfer.nextOffset.toInt()
        val end = minOf(start + chunkBytes(), xfer.state.size)
        val done = end >= xfer.state.size
        emitTrace(RaftTraceEvent.InstallSnapshot(nextClock(), transport.selfId, peer,
            xfer.meta.lastIncludedIndex, xfer.nextOffset, done))
        send(peer, RaftMessage.InstallSnapshot(
            term = currentTerm, leaderId = transport.selfId,
            lastIncludedIndex = xfer.meta.lastIncludedIndex, lastIncludedTerm = xfer.meta.lastIncludedTerm,
            offset = xfer.nextOffset, data = xfer.state.copyOfRange(start, end), done = done,
        ))
    }
```

- [ ] **Step 6: Implement the response handler + follower reassembly**

Add follower reassembly state:
```kotlin
    private class SnapshotReassembly(val meta: SnapshotMeta, val buffer: ArrayList<Byte> = ArrayList())
    private var incomingSnapshot: SnapshotReassembly? = null
```

Extend the message dispatcher `onMessage` `when` with the two new arms:
```kotlin
        is RaftMessage.InstallSnapshot         -> onInstallSnapshot(from, m)
        is RaftMessage.InstallSnapshotResponse -> onInstallSnapshotResponse(from, m)
```

Leader response handler — advance or finish the transfer:
```kotlin
    private suspend fun onInstallSnapshotResponse(from: NodeId, m: RaftMessage.InstallSnapshotResponse) {
        if (m.term > currentTerm) { stepDown(m.term, StepDownReason.HigherTermObserved); return }
        if (_role.value !is RaftRole.Leader || m.term != currentTerm) return
        val xfer = snapshotXfer[from] ?: return
        xfer.nextOffset = m.nextOffset
        if (xfer.nextOffset >= xfer.state.size) {                 // fully received
            matchIndex[from] = maxOf(matchIndex[from] ?: 0L, xfer.meta.lastIncludedIndex)
            nextIndex[from] = xfer.meta.lastIncludedIndex + 1L
            snapshotXfer.remove(from)
            sendAppendEntries(from)                               // resume normal replication
            tryAdvanceLeaderCommit()
        } else {
            sendSnapshotChunk(from, restart = false)              // next chunk
        }
    }
```

Follower handler — reassemble, then install on `done`:
```kotlin
    private suspend fun onInstallSnapshot(from: NodeId, m: RaftMessage.InstallSnapshot) {
        if (m.term < currentTerm) { send(from, RaftMessage.InstallSnapshotResponse(currentTerm, 0L)); return }
        if (m.term > currentTerm) stepDown(m.term, StepDownReason.HigherTermObserved)
        _role.value = followerRole
        _leader.value = m.leaderId
        resetElectionTimeout()

        val meta = SnapshotMeta(m.lastIncludedIndex, m.lastIncludedTerm)
        val r = if (m.offset == 0L) SnapshotReassembly(meta).also { incomingSnapshot = it }
                else incomingSnapshot
        // ignore an out-of-order/stale chunk: re-advertise where we actually are
        if (r == null || r.meta != meta || m.offset != r.buffer.size.toLong()) {
            val have = if (r?.meta == meta) r.buffer.size.toLong() else 0L
            if (have == 0L) incomingSnapshot = null
            send(from, RaftMessage.InstallSnapshotResponse(currentTerm, have)); return
        }
        r.buffer.addAll(m.data.asList())

        if (!m.done) { send(from, RaftMessage.InstallSnapshotResponse(currentTerm, r.buffer.size.toLong())); return }

        // finalize
        val bytes = r.buffer.toByteArray()
        storage.saveSnapshot(meta, bytes)
        val keepSuffix = entryAt(m.lastIncludedIndex)?.term == m.lastIncludedTerm
        if (keepSuffix) {
            storage.discardLogPrefix(m.lastIncludedIndex); log.removeAll { it.index <= m.lastIncludedIndex }
        } else {
            storage.truncateFrom(0L); log.clear()
        }
        snapshotIndex = m.lastIncludedIndex
        snapshotTerm = m.lastIncludedTerm
        if (currentCommitIndex < m.lastIncludedIndex) { currentCommitIndex = m.lastIncludedIndex; _commitIndex.value = m.lastIncludedIndex }
        _compactionFloor.value = snapshotIndex
        _committed.emit(Committed.Install(Snapshot(m.lastIncludedIndex, bytes)))
        incomingSnapshot = null
        emitTrace(RaftTraceEvent.InstallSnapshotAccepted(nextClock(), from, transport.selfId, m.lastIncludedIndex))
        send(from, RaftMessage.InstallSnapshotResponse(currentTerm, bytes.size.toLong()))
    }
```

Note: `truncateFrom(0L)` removes all entries (index >= 0). Confirm `InMemoryRaftStorage.truncateFrom` matches; it does (`removeAll { it.index >= index }`).

- [ ] **Step 7: Run the headline tests to verify they pass**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*InstallSnapshotTest*"`
Expected: PASS — `c` rejoins and reaches the leader's index; chunked transfer reassembles under a 64-byte cap.

- [ ] **Step 8: Run the full module suite**

Run: `./gradlew :kuilt-raft:jvmTest`
Expected: PASS.

- [ ] **Step 9: Commit the fix**

```bash
git add kuilt-raft/src
git commit -m "feat(kuilt-raft): chunked InstallSnapshot RPC — offline rejoin after compaction (#114)"
```

---

## Task 5: Recovery on restart, cross-platform validation, PBT invariants, docs

**Files:**
- Modify: `internal/RaftEngine.kt` (init recovery)
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/SnapshotRecoveryTest.kt` (new); extend the existing PBT/property suite
- Docs: `kuilt-raft` module KDoc / any `docs/` raft usage notes

- [ ] **Step 1: Write the failing recovery test**

Create `SnapshotRecoveryTest.kt`:

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotRecoveryTest {

    @Test
    fun nodeRestart_recoversSnapshotBaseline_andCommitIndex() = runTest(UnconfinedTestDispatcher()) {
        val storage = InMemoryRaftStorage()
        // simulate a prior life: a snapshot through 7 plus entries 8,9 persisted
        storage.saveSnapshot(SnapshotMeta(7L, 3L), byteArrayOf(1, 2, 3))
        storage.appendEntries(listOf(LogEntry(8L, 3L, byteArrayOf(8)), LogEntry(9L, 3L, byteArrayOf(9))))

        // construct over the pre-loaded storage via the same single-node helper used in Task 3
        val node = singleVoterNode(this, storage).node
        // commitIndex must start at the snapshot baseline (a snapshot is by definition committed)
        node.commitIndex.first { it >= 7L }
        assertEquals(7L, node.compactionFloor.value)

        // resuming below the floor leads with the stored snapshot, then entries 8,9
        val seen = node.committedFrom(1L)
        val list = mutableListOf<Committed>()
        kotlinx.coroutines.withTimeout(2000) {
            seen.collect { list += it; if (list.size == 3) throw kotlinx.coroutines.CancellationException() }
        }
        assertEquals(Snapshot(7L, byteArrayOf(1, 2, 3)), (list[0] as Committed.Install).snapshot)
        assertEquals(listOf(8L, 9L), list.drop(1).map { (it as Committed.Entry).entry.index })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*SnapshotRecoveryTest*"`
Expected: FAIL — `commitIndex`/`compactionFloor` start at 0 (recovery not wired).

- [ ] **Step 3: Wire recovery in `RaftEngine.init`**

In the `init` block, after `log.addAll(storage.entries())`, add snapshot recovery (load meta, set baseline, set commit floor):
```kotlin
            storage.loadSnapshot()?.let { stored ->
                snapshotIndex = stored.meta.lastIncludedIndex
                snapshotTerm = stored.meta.lastIncludedTerm
                _compactionFloor.value = snapshotIndex
                if (currentCommitIndex < snapshotIndex) { currentCommitIndex = snapshotIndex; _commitIndex.value = snapshotIndex }
            }
```
Because the persisted log already excludes the discarded prefix, `log.addAll(storage.entries())` loads only entries `> snapshotIndex`. Confirm ordering: load snapshot meta either before or after the `entries()` load — both are fine since `entries()` is already prefix-free; place the snapshot block immediately after the `entries()` load for readability.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*SnapshotRecoveryTest*"`
Expected: PASS.

- [ ] **Step 5: Add a PBT / property invariant for compaction**

Locate the existing property-based suite (the synchronous PBT model added in #154, and `ChaosTest`). Add an invariant asserting **State-Machine-Safety under compaction**: a node that applies the `Committed` stream (folding `Entry` and resetting on `Install`) ends in the same state as a node that replayed the full uncompacted log. Mirror the existing PBT harness's shape — do not invent a new framework. Concretely, extend the chaos/PBT scenario to: randomly compact some nodes mid-run, then assert all nodes' applied state (and final `commitIndex`) converge. Also assert **Leader Completeness still holds** (a leader's retained log + snapshot covers every committed index).

Run: `./gradlew :kuilt-raft:jvmTest --tests "*Chaos*" --tests "*Pbt*"` (adjust to the actual class names)
Expected: PASS.

- [ ] **Step 6: Cross-platform determinism check**

The install path emits on `_committed` and uses the actor; validate it under the non-JVM schedulers that previously caught flakes (see `docs/testing-coroutine-determinism.md`).

Run: `./gradlew :kuilt-raft:wasmJsTest :kuilt-raft:macosArm64Test`
Expected: PASS. If a Native/wasm flake appears in the install or `committedFrom`-with-install path, apply the established remedy — inject `UnconfinedTestDispatcher(testScheduler)` / `UNDISPATCHED` subscribe discipline — rather than a sleep.

- [ ] **Step 7: Update module docs**

Update the `:kuilt-raft` package KDoc (`RaftNode.kt` header) "Quick start" / "Apply" section to show the `Committed` `when` and the `node.snapshots.value = …` trigger (the spec's "Consumer usage" block is the source of truth). Ensure no doc still shows `committed` as `Flow<LogEntry>`.

- [ ] **Step 8: Full multi-platform suite + commit**

Run: `./gradlew :kuilt-raft:allTests`
Expected: PASS.

```bash
git add kuilt-raft/src docs
git commit -m "feat(kuilt-raft): snapshot recovery on restart + compaction invariants (#114)"
```

---

## Done criteria

- `RaftStorage` persists snapshots; the log prefix is discarded on compaction.
- `committed`/`committedFrom` deliver a unified ordered `Flow<Committed>`; installs arrive in order.
- A node offline across a compaction boundary rejoins via chunked `InstallSnapshot`, sized to the transport's `maxPayloadBytes`.
- Restart recovers the snapshot baseline and `commitIndex`.
- PBT asserts State-Machine-Safety and Leader Completeness under compaction; `allTests` green on all platforms.

## Follow-up issues (out of scope — file under the same area)

- Streaming reassembly to disk for snapshots that exceed memory.
- Snapshot compression.
- §6 dynamic membership / joint consensus.
- Optional raft→consumer "please snapshot ≥ K" back-channel to enforce a hard storage ceiling.
