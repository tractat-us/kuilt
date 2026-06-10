# TurnSequencer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `TurnSequencer<A, S>` — a typed, game-author-facing facade over `RaftNode` — in a new `:kuilt-game` module, in three independently-mergeable phases (A: leader-only mapping, B: forward-to-leader, C: snapshot lifecycle).

**Architecture:** A new `:kuilt-game` library module depends on `:kuilt-raft`. `TurnSequencer` wraps a `RaftNode`: `propose` CBOR-encodes a typed action and calls `RaftNode.propose`; consumers collect `events(from)`, which maps `Committed.Entry → Sequenced.Action(turn, action)` (a dense turn ordinal) and `Committed.Install → Sequenced.Reset(turn, state)`. An internal `SequencerCore` owns a single scope-launched subscription to `RaftNode.committedFrom(1)` that maintains the dense turn counter and a raft-index→turn map, so `propose` can report the turn its action landed at.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-serialization-cbor, `:kuilt-raft`, `:kuilt-raft-test` (`FakeRaftNode`). Build via `kuilt.kmp-library` convention plugin.

**Reference spec:** `docs/superpowers/specs/2026-06-10-turnsequencer-design.md`. Issue #314.

**Before you start — environment:**
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```
Fast inner loop is `./gradlew :kuilt-game:jvmTest`. Run the full `./gradlew build` before opening each phase's PR (catches Android-variant + cross-module failures `jvmTest` hides).

---

## Phase 0 — module scaffolding

### Task 0.1: Create the `:kuilt-game` and `:kuilt-game-test` modules

**Files:**
- Modify: `settings.gradle.kts` (after line `include(":kuilt-deal-test")`)
- Create: `kuilt-game/build.gradle.kts`
- Create: `kuilt-game-test/build.gradle.kts`

- [ ] **Step 1: Register both modules in settings**

In `settings.gradle.kts`, after the `include(":kuilt-deal-test")` line, add:

```kotlin
include(":kuilt-game")
include(":kuilt-game-test")
```

- [ ] **Step 2: Write `kuilt-game/build.gradle.kts`**

```kotlin
plugins {
    id("kuilt.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-raft"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-game-test"))
            implementation(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

- [ ] **Step 3: Write `kuilt-game-test/build.gradle.kts`**

```kotlin
plugins { id("kuilt.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kuilt-game"))
            api(project(":kuilt-raft-test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

- [ ] **Step 4: Verify the modules resolve**

Run: `./gradlew :kuilt-game:help :kuilt-game-test:help -q`
Expected: both succeed (no source files yet, so nothing to compile).

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts kuilt-game/build.gradle.kts kuilt-game-test/build.gradle.kts
git commit -m "feat(kuilt-game): scaffold :kuilt-game and :kuilt-game-test modules (#314)"
```

---

## Phase A — pure mapping, leader-only, uncompacted

This is the first mergeable PR. `propose` is leader-only (propagates `NotLeaderException`); `Committed.Install` is unsupported (throws). Dense turn ordinals; `events(from)` replays from turn 1 then tails.

### Task A.1: The `Sequenced` event type

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/Sequenced.kt`
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/SequencedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.game

import kotlin.test.Test
import kotlin.test.assertEquals

class SequencedTest {
    @Test
    fun actionCarriesTurnAndPayload() {
        val a: Sequenced<String, Nothing> = Sequenced.Action(turn = 3, action = "play")
        assertEquals(3, (a as Sequenced.Action).turn)
        assertEquals("play", a.action)
    }

    @Test
    fun resetCarriesTurnAndState() {
        val r: Sequenced<Nothing, Int> = Sequenced.Reset(turn = 5, state = 42)
        assertEquals(5, (r as Sequenced.Reset).turn)
        assertEquals(42, r.state)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "*SequencedTest"`
Expected: FAIL — `Sequenced` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package us.tractat.kuilt.game

/**
 * One ordered event in a [TurnSequencer] stream — the game-terms analogue of
 * raft's `Committed` (`Entry` | `Install`). A [Reset] arrives in order relative
 * to the [Action]s around it, preserving the same in-order guarantee raft gives.
 */
public sealed interface Sequenced<out A, out S> {
    /** The next game action, at a dense turn ordinal (1, 2, 3, …). */
    public data class Action<out A>(val turn: Long, val action: A) : Sequenced<A, Nothing>

    /**
     * The underlying log was compacted: discard local state and reset to [state]
     * as of [turn], then continue applying [Action]s above it. Emitted only when
     * a snapshot policy is configured (phase C).
     */
    public data class Reset<out S>(val turn: Long, val state: S) : Sequenced<Nothing, S>
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "*SequencedTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/Sequenced.kt \
        kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/SequencedTest.kt
git commit -m "feat(kuilt-game): Sequenced event type (#314)"
```

### Task A.2: `TurnSequencer` interface + `turnSequencer` factory (leader-only core)

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/TurnSequencer.kt`
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/internal/SequencerCore.kt`
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/TurnSequencerProposeTest.kt`

The `SequencerCore` owns one subscription to `node.committedFrom(1)` launched in the construction scope. It maintains: a running `turnCounter`, a `history: MutableList<Sequenced.Action<A>>` for replay, an `indexToTurn: MutableMap<Long, Long>`, and a `MutableSharedFlow` live tail. `propose` resolves the turn by awaiting the index→turn map.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TurnSequencerProposeTest {
    @Test
    fun proposeOnLeaderReturnsDenseTurnOne() = runTest {
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        val scope = this
        val seq = scope.turnSequencer(node, String.serializer(), Int.serializer())

        val committed = seq.propose("play")
        assertEquals(1, committed.turn)
        assertEquals("play", committed.action)

        node.close()
    }

    @Test
    fun proposeOnFollowerThrowsNotLeader() = runTest {
        val node = FakeRaftNode(initialRole = RaftRole.Follower)
        val seq = this.turnSequencer(node, String.serializer(), Int.serializer())
        assertFailsWith<us.tractat.kuilt.raft.NotLeaderException> { seq.propose("play") }
        node.close()
    }
}
```

> Note: `runTest` provides a `TestScope`; `FakeRaftNode` is virtual-time-safe (no real-clock delays), so no dispatcher injection is needed for these tests. `UnconfinedTestDispatcher(testScheduler)` is imported for later tasks that launch collectors.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "*TurnSequencerProposeTest"`
Expected: FAIL — `turnSequencer` / `TurnSequencer` unresolved.

- [ ] **Step 3: Write the `TurnSequencer` interface + factory**

`TurnSequencer.kt`:

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import us.tractat.kuilt.game.internal.SequencerCore
import us.tractat.kuilt.raft.RaftNode

/**
 * A typed, game-author-facing facade over a [RaftNode]. Propose typed actions
 * and consume them back as a totally-ordered, gap-free stream of typed
 * [Sequenced] events at dense turn ordinals — no raft concepts (indices, terms,
 * snapshots, leader/follower) leak out.
 *
 * Every peer's [events] yields identical [Sequenced.Action] turn ordinals and
 * identical [Sequenced.Reset] points; this is the property a replay or spectator
 * relies on.
 */
public interface TurnSequencer<A, S> {
    /**
     * Proposes [action] and suspends until it is committed and ordered, returning
     * it with its dense turn.
     *
     * In phase A this is leader-only: on a non-leader it throws
     * [us.tractat.kuilt.raft.NotLeaderException]. Phase B forwards to the leader so
     * any peer may call it.
     */
    public suspend fun propose(action: A): Sequenced.Action<A>

    /**
     * Replays from [from] (default: turn 1), then tails the live stream —
     * gap-free and exactly-once. If [from] was compacted away (phase C), a
     * [Sequenced.Reset] is emitted first.
     */
    public fun events(from: Long = 1L): Flow<Sequenced<A, S>>
}

/**
 * Constructs a [TurnSequencer] whose internal coroutines (the committed-stream
 * subscription, plus phase-B forwarding and phase-C snapshot publishing) are tied
 * to this [CoroutineScope] — matching `CoroutineScope.raftNode(...)`.
 *
 * @param snapshot   phase C; `null` disables compaction (a `Committed.Install`
 *                   would throw).
 * @param forwarding phase B; `null` = leader-only (propose on a follower throws).
 */
public fun <A, S> CoroutineScope.turnSequencer(
    node: RaftNode,
    actions: KSerializer<A>,
    state: KSerializer<S>,
    snapshot: GameSnapshotPolicy<S>? = null,
    forwarding: Forwarding? = null,
): TurnSequencer<A, S> = SequencerCore(this, node, actions, state, snapshot, forwarding)
```

Add placeholder declarations so the signature compiles now; they are fleshed out in later phases. Create `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/SnapshotPolicy.kt`:

```kotlin
package us.tractat.kuilt.game

/**
 * Phase C — supplies state snapshots so the underlying raft log can compact.
 * Implement [snapshotState] to return the game's current state; the sequencer
 * turn-stamps it and publishes it to `RaftNode.snapshots` on [cadence].
 */
public interface GameSnapshotPolicy<S> {
    public suspend fun snapshotState(): S
    public val cadence: SnapshotCadence
}

/** How often the sequencer samples [GameSnapshotPolicy.snapshotState]. */
public sealed interface SnapshotCadence {
    /** Snapshot after every [turns] committed turns. */
    public data class EveryNTurns(val turns: Long) : SnapshotCadence
}
```

Create `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/Forwarding.kt`:

```kotlin
package us.tractat.kuilt.game

/**
 * Phase B — the channel over which a non-leader peer forwards a proposal to the
 * current leader. Shape is defined by the phase-B sub-design (see the design
 * spec's "Open items"); declared here so the [turnSequencer] signature is stable
 * across phases. Pass `null` for leader-only (phase A) behavior.
 */
public interface Forwarding
```

- [ ] **Step 4: Write `SequencerCore` (phase-A behavior)**

`internal/SequencerCore.kt`:

```kotlin
package us.tractat.kuilt.game.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.game.Forwarding
import us.tractat.kuilt.game.GameSnapshotPolicy
import us.tractat.kuilt.game.Sequenced
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.raft.Committed
import us.tractat.kuilt.raft.RaftNode

internal class SequencerCore<A, S>(
    private val scope: CoroutineScope,
    private val node: RaftNode,
    private val actions: KSerializer<A>,
    private val state: KSerializer<S>,
    private val snapshot: GameSnapshotPolicy<S>?,
    private val forwarding: Forwarding?,
) : TurnSequencer<A, S> {

    private val cbor = Cbor { }

    // Dense turn bookkeeping, mutated only on the single subscriber coroutine below.
    private var turnCounter = 0L
    private val history = mutableListOf<Sequenced.Action<A>>()
    private val indexToTurn = mutableMapOf<Long, Long>()
    private val tail = MutableSharedFlow<Sequenced.Action<A>>(extraBufferCapacity = Int.MAX_VALUE)
    // Surfaces the raft index just assigned a turn, so propose() can await its turn.
    private val assigned = MutableSharedFlow<Long>(extraBufferCapacity = Int.MAX_VALUE)

    init {
        scope.launch {
            node.committedFrom(1L).collect { committed ->
                when (committed) {
                    is Committed.Entry -> {
                        val turn = ++turnCounter
                        val action = Sequenced.Action(turn, cbor.decodeFromByteArray(actions, committed.entry.command))
                        history += action
                        indexToTurn[committed.entry.index] = turn
                        tail.emit(action)
                        assigned.emit(committed.entry.index)
                    }
                    is Committed.Install ->
                        error("TurnSequencer: Committed.Install received but no snapshot policy is configured (phase C).")
                }
            }
        }
    }

    override suspend fun propose(action: A): Sequenced.Action<A> {
        val entry = node.propose(cbor.encodeToByteArray(actions, action))
        // Wait until the subscriber has surfaced our entry and assigned it a turn.
        assigned.first { it == entry.index }
        val turn = indexToTurn.getValue(entry.index)
        return Sequenced.Action(turn, action)
    }

    override fun events(from: Long): Flow<Sequenced<A, S>> = flow {
        // Phase A: replay from turn 1 (uncompacted log always starts at turn 1),
        // filtering to turn >= from, then tail live without gap/dup.
        var lastEmitted = 0L
        for (a in history.toList()) {
            if (a.turn >= from) { emit(a); lastEmitted = a.turn }
        }
        tail.collect { a ->
            if (a.turn >= from && a.turn > lastEmitted) { emit(a); lastEmitted = a.turn }
        }
    }
}
```

> The `state` serializer and `snapshot`/`forwarding` params are wired but unused in phase A; they are exercised in phases B and C. `cbor.encodeToByteArray`/`decodeFromByteArray` are `Cbor` extension functions from `kotlinx-serialization-cbor`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :kuilt-game:jvmTest --tests "*TurnSequencerProposeTest"`
Expected: PASS (both cases).

- [ ] **Step 6: Commit**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/TurnSequencerProposeTest.kt
git commit -m "feat(kuilt-game): TurnSequencer facade — leader-only propose + dense turns (#314)"
```

### Task A.3: Dense turn ordinals skip withheld entries

`FakeRaftNode.committedFrom` already filters `isNoOp` entries, so a no-op never reaches the core. This test pins that the dense counter does not skip when raft indices are non-contiguous (the real engine leaves index gaps for withheld no-op/config entries).

**Files:**
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/DenseTurnTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.raft.LogEntry
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals

class DenseTurnTest {
    @Test
    fun turnsStayDenseDespiteIndexGaps() = runTest {
        val node = FakeRaftNode()
        this.turnSequencer(node, String.serializer(), Int.serializer())

        // Application entries land at non-contiguous raft indices (2 and 5);
        // indices 1, 3, 4 stand in for withheld no-op/config entries.
        node.pushCommitted(LogEntry(index = 2, term = 1, command = enc("a")))
        node.pushCommitted(LogEntry(index = 5, term = 1, command = enc("b")))

        val seq = this.turnSequencer(node, String.serializer(), Int.serializer())
        val got = seq.events(1).take(2).toList()
        assertEquals(listOf(1L, 2L), got.map { (it as Sequenced.Action).turn })
        assertEquals(listOf("a", "b"), got.map { (it as Sequenced.Action).action })

        node.close()
    }

    private fun enc(s: String) =
        kotlinx.serialization.cbor.Cbor.encodeToByteArray(String.serializer(), s)
}
```

> Note: this subscribes a *second* sequencer after the entries are already in `FakeRaftNode`'s replay history (`committedFrom` replays them), verifying replay assigns dense turns. The first `turnSequencer` call is only to mirror real usage; you may drop it if `explicitApi` complains about the unused val — assign to `_`.

- [ ] **Step 2: Run test to verify it fails, then passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "*DenseTurnTest"`
Expected: PASS immediately (behavior already implemented in A.2). If it fails, the bug is in the turn counter — fix `SequencerCore` before continuing.

- [ ] **Step 3: Commit**

```bash
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/DenseTurnTest.kt
git commit -m "test(kuilt-game): dense turns survive raft index gaps (#314)"
```

### Task A.4: `events(from)` replays then tails gap-free

**Files:**
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/EventsReplayTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode
import kotlin.test.Test
import kotlin.test.assertEquals

class EventsReplayTest {
    @Test
    fun replaysHistoryThenTailsLive() = runTest {
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        val seq = this.turnSequencer(node, String.serializer(), Int.serializer())

        seq.propose("a")  // turn 1 (committed via FakeRaftNode leader behavior)

        val collected = async(UnconfinedTestDispatcher(testScheduler)) {
            seq.events(1).take(2).toList()
        }
        seq.propose("b")  // turn 2, live

        val turns = collected.await().map { (it as Sequenced.Action).turn }
        assertEquals(listOf(1L, 2L), turns)
        node.close()
    }

    @Test
    fun fromSkipsEarlierTurns() = runTest {
        val node = FakeRaftNode(initialRole = RaftRole.Leader)
        val seq = this.turnSequencer(node, String.serializer(), Int.serializer())
        seq.propose("a"); seq.propose("b"); seq.propose("c")

        val fromTwo = seq.events(2).take(2).toList().map { (it as Sequenced.Action).turn }
        assertEquals(listOf(2L, 3L), fromTwo)
        node.close()
    }
}
```

- [ ] **Step 2: Run, fix if needed, verify pass**

Run: `./gradlew :kuilt-game:jvmTest --tests "*EventsReplayTest"`
Expected: PASS. If `replaysHistoryThenTailsLive` shows a duplicate or gap at the seam, the `lastEmitted` guard in `events` is wrong — fix it.

- [ ] **Step 3: Commit**

```bash
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/EventsReplayTest.kt
git commit -m "test(kuilt-game): events(from) replays then tails gap-free (#314)"
```

### Task A.5: `FakeTurnSequencer` test support + real-engine integration test

**Files:**
- Create: `kuilt-game-test/src/commonMain/kotlin/us/tractat/kuilt/game/test/FakeTurnSequencer.kt`
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RealEngineRoundTripTest.kt`

- [ ] **Step 1: Write `FakeTurnSequencer` builder**

A convenience wrapper bundling a `FakeRaftNode` + a `TurnSequencer` so consumers test game logic without a real cluster:

```kotlin
package us.tractat.kuilt.game.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import us.tractat.kuilt.game.TurnSequencer
import us.tractat.kuilt.game.turnSequencer
import us.tractat.kuilt.raft.RaftRole
import us.tractat.kuilt.raft.test.FakeRaftNode

/**
 * Bundles a [FakeRaftNode] (started as leader) with a [TurnSequencer] over it,
 * for unit-testing game logic without a real cluster.
 */
public class FakeTurnSequencer<A, S>(
    scope: CoroutineScope,
    actions: KSerializer<A>,
    state: KSerializer<S>,
    public val node: FakeRaftNode = FakeRaftNode(initialRole = RaftRole.Leader),
) {
    public val sequencer: TurnSequencer<A, S> = scope.turnSequencer(node, actions, state)
}
```

- [ ] **Step 2: Write the real-engine integration test**

Drives a genuine `raftNode` cluster over loopback `InMemoryLoom` seams, so the facade is verified against the real engine — not only the fake. Use the existing raft test harness pattern; consult `kuilt-raft/src/commonTest` (`RaftSimulation`/`RaftTestFixtures`) for the canonical 3-node in-process cluster setup and copy its construction. Skeleton:

```kotlin
package us.tractat.kuilt.game

// Build a 3-node cluster with real CoroutineScope.raftNode(...) over InMemoryLoom
// seams (see kuilt-raft commonTest harness). Await leadership, wrap the leader's
// node in turnSequencer, propose a few actions, and assert every node's
// events(1) yields identical (turn, action) pairs.
```

> If standing up the real-engine harness in `:kuilt-game` proves heavy, file it as a follow-up sub-issue of #314 and keep phase A's gate on the `FakeRaftNode` tests — but do not skip it silently; note the deferral in the PR body.

- [ ] **Step 3: Run the module's full test suite**

Run: `./gradlew :kuilt-game:jvmTest`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add kuilt-game-test/src/ kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RealEngineRoundTripTest.kt
git commit -m "feat(kuilt-game): FakeTurnSequencer + real-engine round-trip test (#314)"
```

### Task A.6: Full build + open phase-A PR

- [ ] **Step 1: Full multiplatform build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. This catches Android-variant, wasmJs, and `explicitApi()` failures that `jvmTest` hides. Fix any missing `public`/`internal` modifiers.

- [ ] **Step 2: Open the PR (docs posture: auto-merge once green)**

```bash
git push -u origin <phase-a-branch>
gh pr create --title "feat(kuilt-game): TurnSequencer phase A — leader-only mapping (#314)" \
  --body "Phase A of TurnSequencer (#314). Leader-only propose, dense turn ordinals, events(from) replay+tail. Does not close #314 — phases B and C follow."
gh pr merge <n> --auto --squash
```

---

## Phase B — forward-to-leader

> **This phase needs its own sub-design before implementation.** The request/reply wire format, leader-change retry policy, and (the hard part) idempotency across leader changes are not pinned down. Before writing code, produce a phase-B sub-spec (brainstorming → writing-plans) and ideally a dedicated follow-up issue. The tasks below are the skeleton that sub-spec will refine, not a complete recipe.

**Design constraints to carry into the sub-spec:**
- `RaftNode.propose` is **not** idempotent across leader changes (see its `LeadershipLostException` KDoc). A retried `ProposeRequest` must commit the action **exactly once** — the correlation id is the dedup key, but the dedup mechanism (leader-side seen-set in the replicated log? application-level idempotency token?) must be designed.
- Routing uses `RaftNode.leader: StateFlow<NodeId?>`; addressing a specific peer over a *symmetric* `Seam` needs a node-id→peer mapping (see how `SeamRaftTransport` already addresses peers — reuse that approach, do not reinvent).

### Task B.1: Define the `Forwarding` contract (replace the phase-A marker)

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/Forwarding.kt`

- [ ] **Step 1:** Replace the marker interface with the real contract from the sub-spec — minimally a `suspend fun forward(actionBytes: ByteArray): Long` (returns the committed raft index) plus a leader-side handler registration. Write its KDoc to state the exactly-once guarantee and the retry/timeout policy.

- [ ] **Step 2:** Commit.

### Task B.2: Forwarding request/reply protocol over a dedicated `Seam`

- [ ] Implement `ProposeRequest(correlationId, actionBytes)` → leader → `ProposeReply(correlationId, raftIndex)` over a `Seam` on a Swatch tag distinct from raft's traffic. TDD against a two-node in-process harness. Leader short-circuits (no network hop). On `leader == null` or a leader change mid-flight, retry against the new leader with a bounded timeout.

### Task B.3: Wire forwarding into `SequencerCore.propose`

- [ ] When `forwarding != null` and this node is not the leader, route `propose` through the forwarding channel instead of calling `node.propose` directly. After forwarding returns the committed raft index, resolve the dense turn via the existing `assigned`/`indexToTurn` path (the action surfaces on this node's own `committedFrom` stream).

### Task B.4: Idempotency test + full build + PR

- [ ] Tests: follower forwards and commits; leader short-circuits; leader-change-mid-propose retries; **a retried request commits exactly once**. Then `./gradlew build`, open the phase-B PR (does not close #314).

---

## Phase C — snapshot lifecycle + reconnect

### Task C.1: Snapshot envelope (turn-stamped state)

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/internal/SnapshotEnvelope.kt`

- [ ] **Step 1: Write the type**

```kotlin
package us.tractat.kuilt.game.internal

import kotlinx.serialization.Serializable

/**
 * Wraps a game state snapshot with the dense turn it covers, so the turn counter
 * survives compaction. Serialized to the bytes published to `RaftNode.snapshots`.
 */
@Serializable
internal class SnapshotEnvelope(val throughTurn: Long, val stateBytes: ByteArray)
```

- [ ] **Step 2:** Commit.

### Task C.2: Publish snapshots on cadence

- [ ] When `snapshot != null`, `SequencerCore` launches a coroutine (in `scope`) that, per `SnapshotCadence.EveryNTurns`, calls `snapshot.snapshotState()`, encodes it into a `SnapshotEnvelope(throughTurn = turnCounter, stateBytes = cbor.encode(state, …))`, and sets `node.snapshots.value = Snapshot(throughIndex = <raft index for that turn>, state = cbor.encode(envelope))`. TDD with `FakeRaftNode` (assert `node.snapshots.value` is set with the right `throughIndex`). Map turn→raft-index via `indexToTurn` (reverse lookup) — keep a `turnToIndex` map alongside it.

### Task C.3: `Committed.Install` → `Sequenced.Reset`

- [ ] In the `init` subscriber, replace the phase-A `error(...)` for `Committed.Install`: decode the `SnapshotEnvelope`, set `turnCounter = envelope.throughTurn`, decode the typed `S`, and emit `Sequenced.Reset(envelope.throughTurn, state)` into the tail (a `MutableSharedFlow<Sequenced<A, S>>` now, not `<Action>`). TDD: `node.pushInstall(Snapshot(...))` → `events(1)` first yields `Reset` then subsequent `Action`s; turn counter resumes from `throughTurn`.

### Task C.4: `events(from)` honors arbitrary `from` below the floor

- [ ] When `from <= compactionFloor`'s turn, `events(from)` emits the latest `Reset` first, then replays `Action`s above it — mirroring `RaftNode.committedFrom`. TDD the reconnect-from-arbitrary-turn case. Then `./gradlew build`, open the phase-C PR.

---

## Self-review notes (author → worker)

- **Spec coverage:** A.1–A.5 cover the phase-A spec (types, leader-only propose, dense turns, events replay/tail, test support, real-engine verification). B.1–B.4 cover forward-to-leader (flagged as needing its own sub-design — this is intentional per the spec). C.1–C.4 cover the snapshot lifecycle + reconnect.
- **Type consistency:** `Sequenced.Action(turn, action)` / `Sequenced.Reset(turn, state)`; `turnSequencer(node, actions, state, snapshot, forwarding)`; `GameSnapshotPolicy.snapshotState()` + `cadence`; `SnapshotCadence.EveryNTurns(turns)`; `SnapshotEnvelope(throughTurn, stateBytes)` are used consistently across tasks.
- **Known deferral:** phase C changes the `tail` `MutableSharedFlow` element type from `Sequenced.Action<A>` to `Sequenced<A, S>` (Task C.3). Phase A deliberately uses the narrower type; widening it is part of C's diff. The `events` flow's `lastEmitted` gap/dup guard must be revisited when `Reset` enters the stream.
- **Coroutine determinism:** all tests run under `runTest` against the virtual-time-safe `FakeRaftNode`; collectors that must run concurrently with proposes use `async(UnconfinedTestDispatcher(testScheduler))`. No real dispatcher under `runTest`.
