# kuilt-raft Exactly-Once Forwarded Proposals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Raft §8 client-serial dedup so a forwarded proposal retried after a lost ack or leader change is not appended/applied twice, without forbidding retries.

**Architecture:** Placement C from the design spec. Raft stamps every application proposal with a stable `DedupKey(clientId, requestId)`, threads it unchanged through the `Forward` hop, and runs a best-effort non-durable leader-side dedup cache. The authoritative `lastAppliedSerial` table lives in the consumer's state machine and rides the consumer's existing snapshot — raft's `InstallSnapshot` carries it with no raft-side change. A reusable `ClientSessionTable` helper is provided so consumers don't hand-roll it.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization (CBOR on the wire), kotlinx.atomicfu, kotlinx-coroutines-test (`StandardTestDispatcher`), `FakeRaftNode` from `:kuilt-raft-test`.

**Spec:** `docs/superpowers/specs/2026-06-16-raft-exactly-once-dedup-design.md`

**Precondition:** #483 (propose-forwarding) must be merged before Task 5. Tasks 1–4, 7, 8 are independent of #483 and may proceed immediately.

---

### Task 1: `ClientId` and `DedupKey` value types

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/DedupKey.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/DedupKeyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DedupKeyTest {
    @Test
    fun roundTripsThroughCbor() {
        val key = DedupKey(ClientId("client-1"), 42L)
        val bytes = Cbor.encodeToByteArray(DedupKey.serializer(), key)
        val decoded = Cbor.decodeFromByteArray(DedupKey.serializer(), bytes)
        assertEquals(key, decoded)
    }

    @Test
    fun autoClientIdIsDistinctPerDrawAndCarriesTheNodeIdPrefix() {
        val seeded = Random(7)
        val a = ClientId.auto(NodeId("a"), seeded)
        val b = ClientId.auto(NodeId("a"), seeded)   // same instance advances the RNG
        assertNotEquals(a, b)                         // per-incarnation suffix differs
        assertTrue(a.value.startsWith("a-"))          // NodeId prefix, readable in logs
    }

    @Test
    fun autoClientIdsDifferAcrossNodesEvenUnderTheSameSeed() {
        // The seeded-RNG collision the NodeId prefix exists to prevent.
        assertNotEquals(ClientId.auto(NodeId("a"), Random(7)), ClientId.auto(NodeId("b"), Random(7)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*DedupKeyTest"`
Expected: FAIL — `ClientId` / `DedupKey` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Opaque, comparable client identity for Raft §8 client-serial dedup.
 *
 * The auto form ([auto]) is `"$nodeId-$randomHex"`: the cluster-unique [NodeId] prefixed to a
 * per-incarnation random suffix. A caller that wants cross-crash exactly-once passes any **stable**
 * opaque string it persists itself. Raft never parses the value.
 */
@Serializable
public value class ClientId(public val value: String) {
    public companion object {
        /**
         * Auto identity: [nodeId] prefixed to a per-incarnation random hex suffix from [random]
         * (inject the engine's seeded RNG; never the global). The [nodeId] prefix keeps two nodes
         * distinct even under the same seed, and makes the id readable in logs.
         */
        public fun auto(nodeId: NodeId, random: Random): ClientId {
            val suffix = ByteArray(8).also { random.nextBytes(it) }
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return ClientId("${nodeId.value}-$suffix")
        }
    }
}

/** A proposal's end-to-end dedup identity: a [clientId] plus a per-client monotonic [requestId]. */
@Serializable
public data class DedupKey(val clientId: ClientId, val requestId: Long)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*DedupKeyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/DedupKey.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/DedupKeyTest.kt
git commit -m "feat(kuilt-raft): ClientId + DedupKey value types for client-serial dedup (#484)"
```

---

### Task 2: carry `dedupKey` on `LogEntry`

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/LogEntry.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LogEntryDedupTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LogEntryDedupTest {
    @Test
    fun dedupKeyDefaultsNullAndSurvivesCbor() {
        val plain = LogEntry(index = 1, term = 1, command = byteArrayOf(1))
        assertEquals(null, plain.dedupKey)

        val keyed = plain.copy(dedupKey = DedupKey(ClientId("c"), 5))
        val decoded = Cbor.decodeFromByteArray(
            LogEntry.serializer(),
            Cbor.encodeToByteArray(LogEntry.serializer(), keyed),
        )
        assertEquals(keyed, decoded)
    }

    @Test
    fun dedupKeyParticipatesInEquality() {
        val base = LogEntry(index = 1, term = 1, command = byteArrayOf(1))
        assertNotEquals(base.copy(dedupKey = DedupKey(ClientId("c"), 1)), base)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*LogEntryDedupTest"`
Expected: FAIL — `dedupKey` is not a parameter of `LogEntry`.

- [ ] **Step 3: Write minimal implementation**

In `LogEntry.kt`, add the constructor parameter (after `config`) and extend `equals`/`hashCode`:

```kotlin
@Serializable
public data class LogEntry(
    val index: Long,
    val term: Long,
    val command: ByteArray,
    val isNoOp: Boolean = false,
    val config: ConfigPayload? = null,
    val dedupKey: DedupKey? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEntry) return false
        return index == other.index && term == other.term &&
            command.contentEquals(other.command) && isNoOp == other.isNoOp &&
            config == other.config && dedupKey == other.dedupKey
    }
    override fun hashCode(): Int {
        var r = index.hashCode(); r = 31 * r + term.hashCode()
        r = 31 * r + command.contentHashCode(); r = 31 * r + isNoOp.hashCode()
        r = 31 * r + config.hashCode(); r = 31 * r + dedupKey.hashCode(); return r
    }
}
```

Update the KDoc on `LogEntry` to document `dedupKey`: "Non-null for stamped application entries; `null` for internal no-op/config entries and for legacy entries decoded before this field existed."

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*LogEntryDedupTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/LogEntry.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LogEntryDedupTest.kt
git commit -m "feat(kuilt-raft): add optional dedupKey to LogEntry (#484)"
```

---

### Task 3: node identity + serial counter (`FakeRaftNode` first)

`FakeRaftNode` in `:kuilt-raft-test` is the unit-test surface and must learn the same API before the real engine. This task adds identity + the auto-serial counter there.

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` (interface)
- Modify: `kuilt-raft-test/src/commonMain/kotlin/us/tractat/kuilt/raft/FakeRaftNode.kt`
- Test: `kuilt-raft-test/src/commonTest/kotlin/us/tractat/kuilt/raft/FakeRaftNodeDedupTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FakeRaftNodeDedupTest {
    @Test
    fun autoStampsMonotonicSerialsUnderTheNodeClientId() = runTest {
        val node = FakeRaftNode(NodeId("a"), clientId = ClientId("fixed"))
        node.becomeLeader()
        val first = node.propose(byteArrayOf(1))
        val second = node.propose(byteArrayOf(2))
        assertEquals(DedupKey(ClientId("fixed"), 1), first.dedupKey)
        assertEquals(DedupKey(ClientId("fixed"), 2), second.dedupKey)
    }

    @Test
    fun explicitRequestIdIsHonoured() = runTest {
        val node = FakeRaftNode(NodeId("a"), clientId = ClientId("fixed"))
        node.becomeLeader()
        val entry = node.propose(byteArrayOf(1), requestId = 99)
        assertEquals(99L, entry.dedupKey?.requestId)
    }

    @Test
    fun defaultClientIdIsPresentAndStablePerInstance() = runTest {
        val node = FakeRaftNode(NodeId("a"))
        node.becomeLeader()
        val a = node.propose(byteArrayOf(1)).dedupKey?.clientId
        val b = node.propose(byteArrayOf(2)).dedupKey?.clientId
        assertNotNull(a); assertEquals(a, b)
    }
}
```

> Adapt `becomeLeader()`/constructor calls to `FakeRaftNode`'s existing test API if the names differ — match what the current `FakeRaftNode` exposes.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft-test:jvmTest --tests "*FakeRaftNodeDedupTest"`
Expected: FAIL — `propose` has no `requestId` overload; constructor has no `clientId`.

- [ ] **Step 3: Write minimal implementation**

In `RaftNode.kt`, add the overload to the interface and document it:

```kotlin
/**
 * Proposes [command] with a caller-pinned [requestId] (Raft §8 client serial) under this node's
 * `clientId`. A durable client replays the *same* [requestId] on a post-crash retry to get
 * exactly-once across the crash. See [propose] for the auto-serial form.
 */
public suspend fun propose(command: ByteArray, requestId: Long): LogEntry
```

In `FakeRaftNode.kt`: add a `clientId: ClientId = ClientId.auto(nodeId, Random(<fixed-test-seed>))` constructor parameter (seeded `Random` so the fake is deterministic; `nodeId` is the fake's existing id arg), a `private val serial = atomic(0L)` counter (`import kotlinx.atomicfu.atomic`), and implement both `propose` overloads so the auto form stamps `DedupKey(clientId, serial.incrementAndGet())` and the explicit form stamps `DedupKey(clientId, requestId)` onto the returned/committed `LogEntry`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft-test:jvmTest --tests "*FakeRaftNodeDedupTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt \
        kuilt-raft-test/src/commonMain/kotlin/us/tractat/kuilt/raft/FakeRaftNode.kt \
        kuilt-raft-test/src/commonTest/kotlin/us/tractat/kuilt/raft/FakeRaftNodeDedupTest.kt
git commit -m "feat(kuilt-raft-test): FakeRaftNode client identity + auto/explicit serials (#484)"
```

---

### Task 4: real engine stamps the key + `clientId` construction param

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt` (`CoroutineScope.raftNode` factory)
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftEngineDedupStampTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftEngineDedupStampTest {
    @Test
    fun singleNodeLeaderStampsAutoSerialOnTheCommittedEntry() = runTest {
        // Single-voter cluster commits immediately; uses the in-process loopback harness used
        // by the existing engine tests (mirror their setup helper).
        val h = singleVoterHarness(NodeId("a"), clientId = ClientId("c"))
        launch { h.node.awaitLeadership(); h.node.propose("x".encodeToByteArray()) }
        val committed = h.node.committed.first() as Committed.Entry
        assertEquals(DedupKey(ClientId("c"), 1), committed.entry.dedupKey)
        h.close()
    }
}
```

> `singleVoterHarness` stands in for whatever single-node construction helper the existing
> `kuilt-raft` engine tests use (search `:kuilt-raft` commonTest for an existing one-voter test and
> reuse it; thread the new `clientId` param through). Do **not** construct a real node under a
> `TestDispatcher` — the engine uses real-clock delays; use the same real-dispatcher harness the
> current engine tests use, or keep this assertion in `FakeRaftNode` (Task 3) and make this a smoke
> test of the factory param only.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftEngineDedupStampTest"`
Expected: FAIL — factory has no `clientId` param / entry has no stamped key.

- [ ] **Step 3: Write minimal implementation**

In `RaftNode.kt`, add `clientId: ClientId? = null` to `CoroutineScope.raftNode(...)` (placed before `onMetric`) and pass it into `RaftEngine`. Document: "`null` mints `ClientId.auto(thisNodeId, raftConfig.random)`; pass a stable `ClientId` for cross-crash exactly-once."

In `RaftEngine.kt`:
- Resolve identity once at construction: `private val clientId: ClientId = clientId ?: ClientId.auto(nodeId, raftConfig.random)` (where `nodeId` is this engine's own `NodeId`).
- Add `private val serial = atomic(0L)` (`import kotlinx.atomicfu.atomic`).
- In the `propose(command)` entry point, compute `val key = DedupKey(this.clientId, serial.incrementAndGet())` and attach it to the `LogEntry` the leader appends. Add the `propose(command, requestId)` overload that builds `DedupKey(this.clientId, requestId)` instead. Internal no-op/config entries keep `dedupKey = null`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*RaftEngineDedupStampTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftEngineDedupStampTest.kt
git commit -m "feat(kuilt-raft): engine stamps DedupKey; clientId construction param (#484)"
```

---

### Task 5: thread `dedupKey` through `Forward` *(requires #483)*

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt` (`Forward`)
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt` (forward send + receive)
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ForwardDedupThreadingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardDedupThreadingTest {
    @Test
    fun forwardCarriesProposerKeyByteEqualToLeaderAppend() = runTest_or_harness {
        // Two-node loopback (mirror #483's forwarding test harness): follower proposes, leader appends.
        val (follower, leader) = forwardingPair(
            followerClientId = ClientId("proposer"),
        )
        follower.becomeFollowerOf(leader)
        val committed = proposeViaFollowerAndAwaitCommit(follower, "m".encodeToByteArray(), requestId = 7)
        // The key stamped at the proposer must arrive unchanged at the leader's log.
        assertEquals(DedupKey(ClientId("proposer"), 7), committed.dedupKey)
    }
}
```

> Reuse #483's forwarding test harness verbatim (`forwardingPair`/equivalent). The assertion is the
> only new thing: the proposer-stamped `DedupKey` must equal the committed entry's `dedupKey` — i.e.
> the forwarding leader did **not** re-stamp it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*ForwardDedupThreadingTest"`
Expected: FAIL — `Forward` has no `dedupKey`; leader stamps its own.

- [ ] **Step 3: Write minimal implementation**

In `RaftMessage.kt`, add `val dedupKey: DedupKey? = null` to the `Forward` data class (from #483).

In `RaftEngine.kt`:
- When a non-leader `propose` forwards, stamp the `DedupKey` **at the proposer** (it already does in Task 4) and put it on the outgoing `Forward`.
- When the leader receives a `Forward`, it must append using `forward.dedupKey` **unchanged** — it must NOT mint a new key. The per-hop `clientRequestId` correlation nonce from #483 stays exactly as-is for response routing; `dedupKey` is the separate end-to-end field.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*ForwardDedupThreadingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ForwardDedupThreadingTest.kt
git commit -m "feat(kuilt-raft): thread end-to-end dedupKey through Forward unchanged (#484)"
```

---

### Task 6: best-effort leader-side dedup cache *(requires #483)*

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/LeaderDedupCache.kt`
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LeaderDedupCacheTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.LeaderDedupCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LeaderDedupCacheTest {
    private val k = DedupKey(ClientId("c"), 1)
    private val entry = LogEntry(index = 5, term = 2, command = byteArrayOf(9), dedupKey = k)

    @Test
    fun firstSightReturnsNullThenRecordsResult() {
        val cache = LeaderDedupCache()
        assertNull(cache.lookup(k))      // miss: caller proceeds to append
        cache.record(k, entry)
        assertSame(entry, cache.lookup(k)) // retry of the same key coalesces onto the recorded result
    }

    @Test
    fun unkeyedEntriesAreNeverCached() {
        val cache = LeaderDedupCache()
        assertNull(cache.lookup(null))
    }

    @Test
    fun clearDropsAllEntriesOnLeadershipLoss() {
        val cache = LeaderDedupCache()
        cache.record(k, entry)
        cache.clear()
        assertNull(cache.lookup(k))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*LeaderDedupCacheTest"`
Expected: FAIL — `LeaderDedupCache` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.DedupKey
import us.tractat.kuilt.raft.LogEntry

/**
 * Best-effort, non-durable leader-side dedup of recently-committed proposals. Confined to the
 * engine's single dispatch loop (no internal locking) and cleared whenever this node loses
 * leadership — a new leader starts cold and the consumer's durable table is the backstop.
 *
 * Keeps the most-recent committed entry per client; because serials are monotonic per client, a
 * lost-ack retry is for the most-recent serial, so a single slot per client catches the common case.
 */
internal class LeaderDedupCache {
    private val lastCommitted = HashMap<DedupKey, LogEntry>()

    /** The recorded result for [key], or null on a miss (or a null/unkeyed proposal). */
    fun lookup(key: DedupKey?): LogEntry? = if (key == null) null else lastCommitted[key]

    /** Records the committed [entry] for [key] so a later retry coalesces. */
    fun record(key: DedupKey?, entry: LogEntry) {
        if (key != null) lastCommitted[key] = entry
    }

    /** Drops all entries — call on every step-down / leadership loss. */
    fun clear() = lastCommitted.clear()
}
```

In `RaftEngine.kt`:
- Hold `private val dedupCache = LeaderDedupCache()`.
- In the leader's propose/append path (both direct and `Forward`-received), before appending consult `dedupCache.lookup(key)`; on a hit, return that `LogEntry` (and route the `ForwardResponse` with its index/term) **without** appending.
- After an entry commits, call `dedupCache.record(entry.dedupKey, entry)`.
- On any transition out of `Leader` (step-down, higher term), call `dedupCache.clear()`.

- [ ] **Step 4: Run unit + an integration test to verify**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*LeaderDedupCacheTest"`
Expected: PASS.

Add one integration test `proposeRetryOnSameLeaderAppendsOnce` to the engine/forwarding harness: propose with `requestId = 1`, drop the response, re-`propose` with `requestId = 1`, assert the leader's log grew by exactly one entry and `committed` emitted once. Run it; expect PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/LeaderDedupCache.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LeaderDedupCacheTest.kt
git commit -m "feat(kuilt-raft): best-effort leader-side dedup cache (#484)"
```

---

### Task 7: reusable consumer-side `ClientSessionTable` (durable enforcement helper)

This is the authoritative dedup table the spec assigns to the consumer's state machine. Providing it as a small, snapshot-serializable helper means consumers (e.g. a `TurnSequencer`) compose it instead of hand-rolling, and it is testable inside `:kuilt-raft`.

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/ClientSessionTable.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ClientSessionTableTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientSessionTableTest {
    @Test
    fun firstSerialAppliesAndIsRecorded() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 1)))
    }

    @Test
    fun serialAtOrBelowHighWaterMarkIsSkipped() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("c"), 5))
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 5))) // exact retry
        assertFalse(t.shouldApply(DedupKey(ClientId("c"), 3))) // stale
        assertTrue(t.shouldApply(DedupKey(ClientId("c"), 6)))  // advances
    }

    @Test
    fun unkeyedEntriesAlwaysApply() {
        val t = ClientSessionTable()
        assertTrue(t.shouldApply(null))
        assertTrue(t.shouldApply(null))
    }

    @Test
    fun snapshotRoundTripPreservesHighWaterMarks() {
        val t = ClientSessionTable()
        t.shouldApply(DedupKey(ClientId("a"), 4))
        t.shouldApply(DedupKey(ClientId("b"), 9))
        val restored = ClientSessionTable.fromBytes(t.toBytes())
        assertFalse(restored.shouldApply(DedupKey(ClientId("a"), 4)))
        assertEquals(true, restored.shouldApply(DedupKey(ClientId("a"), 5)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*ClientSessionTableTest"`
Expected: FAIL — `ClientSessionTable` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.raft

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * The authoritative client-serial dedup table for a consumer's state machine (Raft §8).
 *
 * Fold it into your apply loop: call [shouldApply] for each `Committed.Entry.entry.dedupKey`; apply
 * the command only when it returns `true`. Serialize it **into your snapshot** via [toBytes] and
 * restore with [fromBytes] on `Committed.Install`, so a follower that joins mid-stream inherits the
 * high-water-marks and rejects stale retries.
 *
 * Not thread-safe — drive it from the same single apply loop that consumes `RaftNode.committed`.
 *
 * **No GC (v1):** ephemeral clients accumulate dead entries; long-lived clients should reuse a stable
 * [ClientId] so their entry updates in place. See the design spec.
 */
public class ClientSessionTable private constructor(
    private val highWaterMarks: MutableMap<ClientId, Long>,
) {
    public constructor() : this(mutableMapOf())

    /**
     * Returns `true` if the entry carrying [key] has not yet been applied (and records it), `false`
     * if it is a duplicate to skip. A `null` key (unkeyed/idempotent entry) always returns `true`.
     */
    public fun shouldApply(key: DedupKey?): Boolean {
        if (key == null) return true
        val seen = highWaterMarks[key.clientId] ?: 0L
        if (key.requestId <= seen) return false
        highWaterMarks[key.clientId] = key.requestId
        return true
    }

    /** Serialize for inclusion in the consumer's snapshot bytes. */
    public fun toBytes(): ByteArray =
        Cbor.encodeToByteArray(Snapshot.serializer(), Snapshot(highWaterMarks))

    @Serializable
    private data class Snapshot(val marks: Map<ClientId, Long>)

    public companion object {
        /** Restore from bytes produced by [toBytes]. */
        public fun fromBytes(bytes: ByteArray): ClientSessionTable =
            ClientSessionTable(Cbor.decodeFromByteArray(Snapshot.serializer(), bytes).marks.toMutableMap())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*ClientSessionTableTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/ClientSessionTable.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ClientSessionTableTest.kt
git commit -m "feat(kuilt-raft): ClientSessionTable — snapshot-serializable consumer dedup table (#484)"
```

---

### Task 8: collision detection — catch two live writers sharing one identity

#483-independent (the engine observes its own committed stream regardless of forwarding), so it
belongs in slice 1.

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/CollisionDetector.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/ClientIdCollisionException.kt`
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/CollisionDetectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.raft

import us.tractat.kuilt.raft.internal.CollisionDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollisionDetectorTest {
    @Test
    fun ownIssuedSerialIsNotForeign() {
        val d = CollisionDetector(ClientId("a-1"))
        d.issued(1); d.issued(2)
        assertFalse(d.isForeign(DedupKey(ClientId("a-1"), 2))) // my own committed entry
    }

    @Test
    fun anotherClientIdIsNeverMyCollision() {
        val d = CollisionDetector(ClientId("a-1"))
        assertFalse(d.isForeign(DedupKey(ClientId("b-1"), 99))) // someone else's id — not my concern
    }

    @Test
    fun mySerialAboveMaxIssuedIsForeign() {
        val d = CollisionDetector(ClientId("a-1"))
        d.issued(3)
        assertTrue(d.isForeign(DedupKey(ClientId("a-1"), 4))) // I never issued 4 under my own id
    }

    @Test
    fun nullKeyIsNeverForeign() {
        assertFalse(CollisionDetector(ClientId("a-1")).isForeign(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*CollisionDetectorTest"`
Expected: FAIL — `CollisionDetector` unresolved.

- [ ] **Step 3: Write minimal implementation**

`CollisionDetector.kt`:

```kotlin
package us.tractat.kuilt.raft.internal

import us.tractat.kuilt.raft.ClientId
import us.tractat.kuilt.raft.DedupKey

/**
 * Passive, exact detector for two live writers sharing one [ClientId]. The owning engine records
 * every serial it issues; a committed entry under the engine's own id bearing a serial above that
 * high-water-mark proves a foreign writer — a node can never legitimately observe its own id paired
 * with a serial it never issued. Confined to the engine's single dispatch loop; no internal locking.
 */
internal class CollisionDetector(private val myClientId: ClientId) {
    private var maxIssued = 0L

    /** Record a serial this engine issued (auto-counter bump or explicit requestId). */
    fun issued(requestId: Long) { if (requestId > maxIssued) maxIssued = requestId }

    /** True iff [key] is under this engine's identity with a serial it never issued. */
    fun isForeign(key: DedupKey?): Boolean =
        key != null && key.clientId == myClientId && key.requestId > maxIssued
}
```

`ClientIdCollisionException.kt`:

```kotlin
package us.tractat.kuilt.raft

/**
 * Thrown by a node using a caller-supplied **durable** [ClientId] when it observes another live
 * writer committing under that same identity (Raft §8 collision). Indicates an operational error —
 * two processes were handed one durable id. Fail loud; do not retry under the same id.
 */
public class ClientIdCollisionException(message: String) : IllegalStateException(message)
```

In `RaftEngine.kt`:
- Hold `private val collisions = CollisionDetector(clientId)` and remember `private val isDurableId = clientIdParam != null` (the constructor arg before defaulting).
- Wherever a key is stamped (both `propose` overloads), call `collisions.issued(requestId)`.
- In the committed-apply path (same place that records the leader cache), call
  `collisions.isForeign(entry.dedupKey)`; if true:
  - **durable id** (`isDurableId`): throw `ClientIdCollisionException(...)` from the engine scope so it propagates and fails loud.
  - **auto id** (`!isDurableId`): re-mint `clientId = ClientId.auto(nodeId, raftConfig.random)`, reset the serial counter and a fresh `CollisionDetector`, and log a warning via the engine's logger (kotlin-logging — a real sink, not the trace flow).

- [ ] **Step 4: Run unit + one integration test to verify**

Run: `./gradlew :kuilt-raft:jvmTest --tests "*CollisionDetectorTest"`
Expected: PASS.

Add an integration test `sharedDurableClientIdSurfacesCollision` to the engine harness: two nodes constructed with the *same* stable `ClientId("shared")`; both commit an entry. The second writer's entry trips the first node's detector → assert a `ClientIdCollisionException` surfaces (or the equivalent collision signal). Run it; expect PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/CollisionDetector.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/ClientIdCollisionException.kt \
        kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt \
        kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/CollisionDetectorTest.kt
git commit -m "feat(kuilt-raft): detect two live writers sharing one ClientId (#484)"
```

---

### Task 9: docs — KDoc, module.md, and the no-GC contract

**Files:**
- Modify: `kuilt-raft/module.md`
- Modify: KDoc on `RaftNode.propose`, `CoroutineScope.raftNode`, `ClientId`, `ClientSessionTable` (done inline in earlier tasks — verify and fill gaps)
- Create (optional): `kuilt-raft/src/commonSamples/kotlin/DedupSample.kt` if `module.md` references a `@sample`

- [ ] **Step 1: Document the dedup model in `module.md`**

Add a section "Exactly-once forwarded proposals (§8)" covering: the three rungs (auto / durable-stable-id / ignore-the-key), the aliasing trap and why the auto id is `NodeId`-prefixed + per-incarnation (and why a bare random GUID would collide under seeded test RNGs), **collision detection** (durable id fails loud with `ClientIdCollisionException`; auto id re-mints + warns), the leader cache being best-effort, the consumer owning `ClientSessionTable` in its snapshot, and the **no-GC v1** bound with "use a stable `ClientId` for long-lived clients" as the self-bounding remedy. Keep it short and link the design spec.

- [ ] **Step 2: If a `@sample` is referenced, add it under `commonSamples`**

Write a compiling sample showing: construct a node with a stable `ClientId`; in the apply loop, `if (table.shouldApply(c.entry.dedupKey)) apply(c.entry.command)`; publish `table.toBytes()` as part of the snapshot. Samples compile as `commonTest` — a broken sample breaks the build.

- [ ] **Step 3: Build the module to verify docs/samples compile**

Run: `./gradlew :kuilt-raft:compileTestKotlinJvm`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add kuilt-raft/module.md kuilt-raft/src/commonSamples
git commit -m "docs(kuilt-raft): document exactly-once dedup model + no-GC v1 contract (#484)"
```

---

### Final verification

- [ ] **Full local build (catches Android-variant, check-guard, and cross-module `:kuilt-raft-test` breakage that `jvmTest` hides):**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew build detektAll
```
Expected: BUILD SUCCESSFUL.

- [ ] **Open the PR** with `Closes #484`, body summarizing placement C, the three rungs, the `NodeId`-prefixed auto id + aliasing-safety guarantee, collision detection (fail-loud durable / re-mint auto), and the no-GC-v1 decision. Reference epic #474 with non-closing language ("Part of #474"). Enable auto-merge once `ci-required` is green.

---

## Self-review notes

- **Spec coverage:** identity / `NodeId`-prefix / aliasing (Tasks 1,3,4) · `dedupKey` on log+wire (Tasks 2,5) · leader cache (Task 6) · consumer durable table + snapshot carry (Task 7) · collision detection (Task 8) · no-GC + rationale docs (Task 9) · API rungs (Tasks 3,4). The snapshot/`InstallSnapshot` requirement needs **no** raft change — Task 7's `toBytes`/`fromBytes` is the consumer-side mechanism, and the spec's "zero raft-side change" is verified by the absence of any `InstallSnapshot.kt` edit.
- **#483 dependency:** only Tasks 5–6 are gated; everything else stands alone, so the PR can land in two slices if #483 is still in flight (**slice 1**: Tasks 1–4, 7, 8, 9 — stamping + collision detection + helper + docs; **slice 2**: Tasks 5–6 — forward threading + leader cache).
- **Test determinism:** no real production dispatcher under `runTest`; `FakeRaftNode` + seeded `Random` throughout. The real-engine assertions (Tasks 4,5,6,8) reuse the existing engine/forwarding harness rather than constructing a node under a `TestDispatcher`.
- **Type consistency:** `ClientId.auto(nodeId, random)` (not `.random`) is used in Tasks 1, 3, 4; `DedupKey(clientId, requestId)`, `ClientSessionTable.shouldApply`/`toBytes`/`fromBytes`, `CollisionDetector.issued`/`isForeign`, and `ClientIdCollisionException` names match across tasks.
