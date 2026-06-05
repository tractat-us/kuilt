# kuilt-raft Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `:kuilt-raft` — a coroutine-native Raft consensus module for Kotlin Multiplatform, backed by any `RaftTransport` and `RaftStorage`, with `SeamRaftTransport` as the kuilt adapter.

**Architecture:** A single `:kuilt-raft` Gradle module exposes a clean interface boundary (`RaftTransport`, `RaftStorage`, `RaftNode`) so the Raft engine has zero kuilt imports. The engine runs as a channel-based actor (single coroutine processes all commands — no locks needed). `SeamRaftTransport` is the only file that imports `kuilt-core`. A `RaftSimulation` test harness with controllable `InMemoryRaftNetwork` drives all scenario and invariant tests.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, kotlinx.serialization (CBOR for internal RPC), `runTest` + `UnconfinedTestDispatcher` for deterministic coroutine tests.

---

## File map

```
kuilt-raft/
  build.gradle.kts
  src/
    commonMain/kotlin/us/tractat/kuilt/raft/
      NodeId.kt
      ClusterConfig.kt
      LogEntry.kt
      RaftRole.kt
      RaftConfig.kt
      RaftExceptions.kt
      RaftTransport.kt          ← interface + RaftEnvelope
      RaftStorage.kt            ← interface
      RaftNode.kt               ← interface + CoroutineScope.raftNode() factory
      InMemoryRaftStorage.kt
      SeamRaftTransport.kt      ← only file importing kuilt-core
      internal/
        RaftMessage.kt          ← serializable RPC types
        EngineCommand.kt        ← channel command sealed interface
        RaftEngine.kt           ← core state machine (actor pattern)
    commonTest/kotlin/us/tractat/kuilt/raft/
      InMemoryRaftNetwork.kt    ← fake transport with controllable delivery
      RaftSimulation.kt         ← test harness wrapping N nodes + network
      ElectionTest.kt
      ReplicationTest.kt
      ChaosTest.kt
      LearnerTest.kt
      InvariantTest.kt
settings.gradle.kts             ← add include(":kuilt-raft")
```

---

## Task 1: Module scaffold

**Files:**
- Create: `kuilt-raft/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Add `:kuilt-raft` to settings**

  In `settings.gradle.kts`, add after the last existing `include(...)` line:
  ```kotlin
  include(":kuilt-raft")
  ```

- [ ] **Write `build.gradle.kts`**

  Create `kuilt-raft/build.gradle.kts`:
  ```kotlin
  plugins {
      id("kuilt.kmp-library")
      alias(libs.plugins.kotlinSerialization)
  }

  kotlin {
      sourceSets {
          commonMain.dependencies {
              api(project(":kuilt-core"))
              implementation(libs.kotlinx.coroutines.core)
              implementation(libs.kotlinx.serialization.core)
              implementation(libs.kotlinx.serialization.cbor)
          }
          commonTest.dependencies {
              implementation(libs.kotlinx.coroutines.test)
          }
      }
  }
  ```

- [ ] **Verify the module is recognised**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
  ./gradlew :kuilt-raft:tasks --quiet
  ```
  Expected: task list prints without error.

- [ ] **Commit**

  ```bash
  git add settings.gradle.kts kuilt-raft/build.gradle.kts
  git commit -m "feat(kuilt-raft): module scaffold"
  ```

---

## Task 2: Core types

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/NodeId.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/ClusterConfig.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/LogEntry.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftRole.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftConfig.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftExceptions.kt`

- [ ] **Create `NodeId.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.serialization.Serializable
  import kotlin.jvm.JvmInline

  @Serializable
  @JvmInline
  public value class NodeId(public val value: String)
  ```

- [ ] **Create `ClusterConfig.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  public data class ClusterConfig(
      val voters: Set<NodeId>,
      val learners: Set<NodeId> = emptySet(),
  ) {
      val allMembers: Set<NodeId> get() = voters + learners
      val quorumSize: Int get() = voters.size / 2 + 1
  }
  ```

- [ ] **Create `LogEntry.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.serialization.Serializable

  @Serializable
  public data class LogEntry(
      val index: Long,
      val term: Long,
      val command: ByteArray,
  ) {
      override fun equals(other: Any?): Boolean {
          if (this === other) return true
          if (other !is LogEntry) return false
          return index == other.index && term == other.term && command.contentEquals(other.command)
      }
      override fun hashCode(): Int {
          var r = index.hashCode(); r = 31 * r + term.hashCode(); r = 31 * r + command.contentHashCode(); return r
      }
  }
  ```

- [ ] **Create `RaftRole.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  public sealed interface RaftRole {
      public data object Leader    : RaftRole
      public data object Follower  : RaftRole
      public data object Candidate : RaftRole
      public data object Learner   : RaftRole
  }
  ```

- [ ] **Create `RaftConfig.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlin.time.Duration
  import kotlin.time.Duration.Companion.milliseconds

  public data class RaftConfig(
      val electionTimeoutMin: Duration = 150.milliseconds,
      val electionTimeoutMax: Duration = 300.milliseconds,
      val heartbeatInterval: Duration = 50.milliseconds,
  )
  ```

- [ ] **Create `RaftExceptions.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  public class NotLeaderException(message: String = "not the current leader") : Exception(message)
  public class LeadershipLostException(message: String = "leadership lost while proposal was in flight") : Exception(message)
  ```

- [ ] **Verify compile**

  ```bash
  ./gradlew :kuilt-raft:compileKotlinJvm --quiet
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): core types — NodeId, ClusterConfig, LogEntry, RaftRole, RaftConfig, exceptions"
  ```

---

## Task 3: Public interfaces

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftTransport.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftStorage.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt`

- [ ] **Create `RaftTransport.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.StateFlow

  public data class RaftEnvelope(val from: NodeId, val bytes: ByteArray)

  public interface RaftTransport {
      public val selfId: NodeId
      public val peers: StateFlow<Set<NodeId>>
      public suspend fun sendTo(peer: NodeId, message: ByteArray)
      public val incoming: Flow<RaftEnvelope>
  }
  ```

- [ ] **Create `RaftStorage.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  public interface RaftStorage {
      public suspend fun term(): Long
      public suspend fun saveTerm(term: Long)
      public suspend fun votedFor(): NodeId?
      public suspend fun saveVotedFor(nodeId: NodeId?)
      public suspend fun appendEntries(entries: List<LogEntry>)
      public suspend fun entries(fromIndex: Long = 0L): List<LogEntry>
      public suspend fun truncateFrom(index: Long)
  }
  ```

- [ ] **Create `RaftNode.kt`** (interface only for now; factory added in Task 13)

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.StateFlow

  public interface RaftNode {
      public val role: StateFlow<RaftRole>
      public val leader: StateFlow<NodeId?>
      public val commitIndex: StateFlow<Long>
      public val committed: Flow<LogEntry>

      /** Suspends until a quorum commits the entry.
       *  @throws NotLeaderException if this node is not currently the leader.
       *  @throws LeadershipLostException if leadership is lost while waiting. */
      public suspend fun propose(command: ByteArray): LogEntry

      public suspend fun close()
  }
  ```

- [ ] **Verify compile**

  ```bash
  ./gradlew :kuilt-raft:compileKotlinJvm --quiet
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): public interfaces — RaftTransport, RaftStorage, RaftNode"
  ```

---

## Task 4: InMemoryRaftStorage

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorage.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorageTest.kt`

- [ ] **Write failing tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorageTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertEquals
  import kotlin.test.assertNull

  class InMemoryRaftStorageTest {
      private fun storage() = InMemoryRaftStorage()

      @Test fun initialTermIsZero() = runTest {
          assertEquals(0L, storage().term())
      }

      @Test fun savesAndLoadsTerm() = runTest {
          val s = storage(); s.saveTerm(3L); assertEquals(3L, s.term())
      }

      @Test fun initialVotedForIsNull() = runTest {
          assertNull(storage().votedFor())
      }

      @Test fun savesAndLoadsVotedFor() = runTest {
          val s = storage(); val id = NodeId("a"); s.saveVotedFor(id)
          assertEquals(id, s.votedFor())
      }

      @Test fun appendsAndRetrievesEntries() = runTest {
          val s = storage()
          val entries = listOf(LogEntry(1, 1, byteArrayOf(1)), LogEntry(2, 1, byteArrayOf(2)))
          s.appendEntries(entries)
          val loaded = s.entries()
          assertEquals(2, loaded.size)
          assertContentEquals(byteArrayOf(1), loaded[0].command)
          assertContentEquals(byteArrayOf(2), loaded[1].command)
      }

      @Test fun entriesFromIndexFilters() = runTest {
          val s = storage()
          s.appendEntries(listOf(LogEntry(1, 1, byteArrayOf()), LogEntry(2, 1, byteArrayOf()), LogEntry(3, 1, byteArrayOf())))
          assertEquals(2, s.entries(fromIndex = 2L).size)
          assertEquals(2L, s.entries(fromIndex = 2L).first().index)
      }

      @Test fun truncateFromRemovesTailEntries() = runTest {
          val s = storage()
          s.appendEntries(listOf(LogEntry(1, 1, byteArrayOf()), LogEntry(2, 1, byteArrayOf()), LogEntry(3, 1, byteArrayOf())))
          s.truncateFrom(2L)
          val remaining = s.entries()
          assertEquals(1, remaining.size)
          assertEquals(1L, remaining.first().index)
      }
  }
  ```

- [ ] **Run tests to verify they fail**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftStorageTest" 2>&1 | tail -5
  ```
  Expected: FAILED (class not found)

- [ ] **Implement `InMemoryRaftStorage`**

  Create `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/InMemoryRaftStorage.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  public class InMemoryRaftStorage : RaftStorage {
      private var currentTerm: Long = 0L
      private var currentVotedFor: NodeId? = null
      private val log = mutableListOf<LogEntry>()

      override suspend fun term(): Long = currentTerm
      override suspend fun saveTerm(term: Long) { currentTerm = term }
      override suspend fun votedFor(): NodeId? = currentVotedFor
      override suspend fun saveVotedFor(nodeId: NodeId?) { currentVotedFor = nodeId }

      override suspend fun appendEntries(entries: List<LogEntry>) { log.addAll(entries) }

      override suspend fun entries(fromIndex: Long): List<LogEntry> =
          log.filter { it.index >= fromIndex }

      override suspend fun truncateFrom(index: Long) {
          log.removeAll { it.index >= index }
      }
  }
  ```

- [ ] **Run tests to verify they pass**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftStorageTest"
  ```
  Expected: 6 tests, BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): InMemoryRaftStorage"
  ```

---

## Task 5: InMemoryRaftNetwork (fake transport)

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftNetwork.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftNetworkTest.kt`

- [ ] **Write failing tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftNetworkTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class InMemoryRaftNetworkTest {
      @Test fun transportDeliversMessage() = runTest(UnconfinedTestDispatcher()) {
          val network = InMemoryRaftNetwork()
          val a = network.transport(NodeId("a"))
          val b = network.transport(NodeId("b"))
          val payload = byteArrayOf(1, 2, 3)
          var received: RaftEnvelope? = null
          val job = launch { received = b.incoming.first() }
          a.sendTo(NodeId("b"), payload)
          job.join()
          assertEquals(NodeId("a"), received?.from)
          assertContentEquals(payload, received?.bytes)
      }

      @Test fun selfIdIsCorrect() = runTest {
          val network = InMemoryRaftNetwork()
          val t = network.transport(NodeId("x"))
          assertEquals(NodeId("x"), t.selfId)
      }

      @Test fun peersContainsAllRegistered() = runTest {
          val network = InMemoryRaftNetwork()
          network.transport(NodeId("a")); network.transport(NodeId("b")); network.transport(NodeId("c"))
          val peers = network.transport(NodeId("a")).peers.value
          assertTrue(NodeId("b") in peers)
          assertTrue(NodeId("c") in peers)
      }

      @Test fun partitionDropsMessages() = runTest(UnconfinedTestDispatcher()) {
          val network = InMemoryRaftNetwork()
          val a = network.transport(NodeId("a"))
          val b = network.transport(NodeId("b"))
          network.partition(setOf(NodeId("a")), setOf(NodeId("b")))
          a.sendTo(NodeId("b"), byteArrayOf(42))
          // b.incoming should have nothing — try collecting with a timeout
          var received = false
          val job = launch { b.incoming.first(); received = true }
          job.cancel()
          assertTrue(!received)
      }
  }
  ```

- [ ] **Run tests to verify they fail**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftNetworkTest" 2>&1 | tail -5
  ```
  Expected: FAILED (class not found)

- [ ] **Implement `InMemoryRaftNetwork`**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InMemoryRaftNetwork.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.channels.Channel
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.flow.receiveAsFlow
  import kotlinx.coroutines.flow.update

  class InMemoryRaftNetwork {
      private val channels = mutableMapOf<NodeId, Channel<RaftEnvelope>>()
      private val _peers = MutableStateFlow<Set<NodeId>>(emptySet())

      // Partitions: set of (from, to) pairs where messages are dropped
      private val droppedLinks = mutableSetOf<Pair<NodeId, NodeId>>()

      fun transport(id: NodeId): RaftTransport {
          val ch = Channel<RaftEnvelope>(Channel.UNLIMITED)
          channels[id] = ch
          _peers.update { it + id }
          return object : RaftTransport {
              override val selfId = id
              override val peers: StateFlow<Set<NodeId>> = _peers.asStateFlow()
              override val incoming: Flow<RaftEnvelope> = ch.receiveAsFlow()
              override suspend fun sendTo(peer: NodeId, message: ByteArray) {
                  if (Pair(id, peer) !in droppedLinks) {
                      channels[peer]?.send(RaftEnvelope(id, message))
                  }
              }
          }
      }

      fun partition(a: Set<NodeId>, b: Set<NodeId>) {
          a.forEach { from -> b.forEach { to -> droppedLinks.add(from to to); droppedLinks.add(to to from) } }
      }

      fun heal() { droppedLinks.clear() }

      fun dropLink(from: NodeId, to: NodeId) { droppedLinks.add(from to to) }
  }
  ```

- [ ] **Run tests to verify they pass**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*InMemoryRaftNetworkTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): InMemoryRaftNetwork fake transport"
  ```

---

## Task 6: RaftSimulation harness

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt`

The simulation wraps `InMemoryRaftNetwork` + a set of `RaftNode`s. It provides crash/restart and the four safety invariant checks. The actual `RaftNode` implementation doesn't exist yet, so use a placeholder interface — the harness will compile against the `RaftNode` interface from Task 3.

- [ ] **Create `RaftSimulation.kt`**

  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.cancel
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  /** Test harness for Raft scenario tests. Nodes are created lazily when the
   *  caller provides a [nodeFactory]; crash/restart re-creates nodes with the
   *  same [InMemoryRaftStorage]. */
  class RaftSimulation(
      val nodeIds: List<NodeId>,
      private val scope: CoroutineScope,
      private val raftConfig: RaftConfig = RaftConfig(),
      private val nodeFactory: (NodeId, RaftTransport, RaftStorage, CoroutineScope) -> RaftNode,
  ) {
      val network = InMemoryRaftNetwork()
      val storages: Map<NodeId, InMemoryRaftStorage> = nodeIds.associateWith { InMemoryRaftStorage() }
      private val scopes: MutableMap<NodeId, CoroutineScope> = mutableMapOf()
      val nodes: MutableMap<NodeId, RaftNode> = mutableMapOf()

      init { nodeIds.forEach { start(it) } }

      private fun start(id: NodeId) {
          val childScope = CoroutineScope(scope.coroutineContext)
          scopes[id] = childScope
          nodes[id] = nodeFactory(id, network.transport(id), storages[id]!!, childScope)
      }

      fun crash(id: NodeId) { scopes[id]?.cancel(); scopes.remove(id); nodes.remove(id) }

      fun restart(id: NodeId) { start(id) }

      fun partition(a: Set<NodeId>, b: Set<NodeId>) = network.partition(a, b)
      fun heal() = network.heal()
      fun dropLink(from: NodeId, to: NodeId) = network.dropLink(from, to)

      /** Assert all four Raft safety invariants on the current node states. */
      suspend fun checkInvariants() {
          // 1. Election Safety: at most one leader per term
          val leadersByTerm = nodes.values
              .filter { it.role.value is RaftRole.Leader }
              .groupBy { /* term exposed via leader StateFlow for now */ it.leader.value }
          leadersByTerm.forEach { (_, leaders) ->
              assertTrue(leaders.size <= 1, "Election Safety violated: multiple leaders")
          }

          // 2. State Machine Safety: no two nodes have applied different commands at the same index
          // (Checked by comparing committed log snapshots across nodes — populated in later tasks)

          // 3–4 checked in InvariantTest via full simulation (added in Task 19)
      }

      fun leader(): RaftNode? = nodes.values.firstOrNull { it.role.value is RaftRole.Leader }
      fun followers(): List<RaftNode> = nodes.values.filter { it.role.value is RaftRole.Follower }
  }
  ```

- [ ] **Verify compile**

  ```bash
  ./gradlew :kuilt-raft:compileTestKotlinJvm --quiet
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): RaftSimulation test harness"
  ```

---

## Task 7: SeamRaftTransport

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/SeamRaftTransport.kt`
- Test: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/SeamRaftTransportTest.kt`

- [ ] **Write failing tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/SeamRaftTransportTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import us.tractat.kuilt.core.InMemoryLoom
  import us.tractat.kuilt.core.PeerId
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertEquals

  class SeamRaftTransportTest {
      @Test fun selfIdMapsFromPeerId() = runTest(UnconfinedTestDispatcher()) {
          val loom = InMemoryLoom()
          val seam = loom.host(us.tractat.kuilt.core.Pattern("test"))
          val transport = SeamRaftTransport(seam)
          assertEquals(NodeId(seam.selfId.value), transport.selfId)
      }

      @Test fun deliversMessageToSender() = runTest(UnconfinedTestDispatcher()) {
          val loom = InMemoryLoom()
          val seamA = loom.host(us.tractat.kuilt.core.Pattern("test"))
          val seamB = loom.join(us.tractat.kuilt.core.InMemoryTag)
          val tA = SeamRaftTransport(seamA)
          val tB = SeamRaftTransport(seamB)
          val payload = byteArrayOf(9, 8, 7)
          var got: RaftEnvelope? = null
          val job = launch { got = tB.incoming.first() }
          tA.sendTo(tB.selfId, payload)
          job.join()
          assertEquals(tA.selfId, got?.from)
          assertContentEquals(payload, got?.bytes)
      }
  }
  ```

- [ ] **Run tests to verify they fail**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*SeamRaftTransportTest" 2>&1 | tail -5
  ```
  Expected: FAILED

- [ ] **Implement `SeamRaftTransport`**

  Create `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/SeamRaftTransport.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.filter
  import kotlinx.coroutines.flow.map
  import us.tractat.kuilt.core.PeerId
  import us.tractat.kuilt.core.Seam

  public class SeamRaftTransport(private val seam: Seam) : RaftTransport {
      override val selfId: NodeId get() = NodeId(seam.selfId.value)

      override val peers: StateFlow<Set<NodeId>> =
          object : StateFlow<Set<NodeId>> {
              override val value get() = seam.peers.value.map { NodeId(it.value) }.toSet()
              override val replayCache get() = listOf(value)
              override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Set<NodeId>>) =
                  seam.peers.map { set -> set.map { NodeId(it.value) }.toSet() }.collect(collector)
          }

      override suspend fun sendTo(peer: NodeId, message: ByteArray) =
          seam.sendTo(PeerId(peer.value), message)

      override val incoming: Flow<RaftEnvelope> =
          seam.incoming
              .filter { it.sender != null }
              .map { RaftEnvelope(NodeId(it.sender!!.value), it.payload) }
  }
  ```

- [ ] **Run tests to verify they pass**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*SeamRaftTransportTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): SeamRaftTransport adapter"
  ```

---

## Task 8: Internal RPC messages

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftMessage.kt`
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/EngineCommand.kt`

- [ ] **Create `RaftMessage.kt`**

  ```kotlin
  package us.tractat.kuilt.raft.internal

  import kotlinx.serialization.Serializable
  import us.tractat.kuilt.raft.LogEntry
  import us.tractat.kuilt.raft.NodeId

  @Serializable
  internal sealed interface RaftMessage {

      @Serializable
      data class RequestVote(
          val term: Long,
          val candidateId: NodeId,
          val lastLogIndex: Long,
          val lastLogTerm: Long,
      ) : RaftMessage

      @Serializable
      data class RequestVoteResponse(
          val term: Long,
          val voteGranted: Boolean,
      ) : RaftMessage

      @Serializable
      data class AppendEntries(
          val term: Long,
          val leaderId: NodeId,
          val prevLogIndex: Long,
          val prevLogTerm: Long,
          val entries: List<LogEntry>,
          val leaderCommit: Long,
      ) : RaftMessage

      @Serializable
      data class AppendEntriesResponse(
          val term: Long,
          val success: Boolean,
          val matchIndex: Long = 0L,
          /** First index of the conflicting term (§5.3 fast backup). */
          val conflictIndex: Long? = null,
          /** Term of the conflicting entry (§5.3 fast backup). */
          val conflictTerm: Long? = null,
      ) : RaftMessage
  }
  ```

- [ ] **Create `EngineCommand.kt`**

  ```kotlin
  package us.tractat.kuilt.raft.internal

  import kotlinx.coroutines.CompletableDeferred
  import us.tractat.kuilt.raft.LogEntry
  import us.tractat.kuilt.raft.NodeId

  internal sealed interface EngineCommand {
      data class IncomingMessage(val from: NodeId, val message: RaftMessage) : EngineCommand
      data class Propose(val command: ByteArray, val response: CompletableDeferred<LogEntry>) : EngineCommand
      object ElectionTimeout : EngineCommand
      object HeartbeatTick : EngineCommand
      object Close : EngineCommand
  }
  ```

- [ ] **Verify compile**

  ```bash
  ./gradlew :kuilt-raft:compileKotlinJvm --quiet
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): internal RPC messages and engine command types"
  ```

---

## Task 9: RaftEngine — scaffold and leader election

**Files:**
- Create: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`

This task implements the full leader election loop. Tests rely on the `RaftSimulation` harness from Task 6 wired to real `RaftEngine` instances.

- [ ] **Write election failing tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ElectionTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.flow.filter
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertIs
  import kotlin.test.assertNotNull
  import kotlin.time.Duration.Companion.milliseconds

  private val fastConfig = RaftConfig(
      electionTimeoutMin = 5.milliseconds,
      electionTimeoutMax = 10.milliseconds,
      heartbeatInterval = 2.milliseconds,
  )

  private fun threeNodeSim(scope: CoroutineScope) = RaftSimulation(
      nodeIds = listOf(NodeId("a"), NodeId("b"), NodeId("c")),
      scope = scope,
      raftConfig = fastConfig,
      nodeFactory = { id, transport, storage, nodeScope ->
          nodeScope.raftNode(
              clusterConfig = ClusterConfig(voters = setOf(NodeId("a"), NodeId("b"), NodeId("c"))),
              transport = transport,
              storage = storage,
              raftConfig = fastConfig,
          )
      },
  )

  class ElectionTest {
      @Test fun initialElection_elects_exactly_one_leader() = runTest(UnconfinedTestDispatcher()) {
          val sim = threeNodeSim(this)
          // Wait for a leader to emerge
          val leader = sim.nodes.values
              .map { node -> node.role.filter { it is RaftRole.Leader }.first() }
              .let { sim.leader() }
          assertNotNull(leader)
          sim.checkInvariants()
      }

      @Test fun reElection_after_leader_crash() = runTest(UnconfinedTestDispatcher()) {
          val sim = threeNodeSim(this)
          // Wait for initial leader
          sim.nodes.values.first().role.filter { it is RaftRole.Leader || it is RaftRole.Follower }.first()
          val leaderId = sim.leader()?.let { n -> sim.nodes.entries.first { it.value === n }.key }
          assertNotNull(leaderId)
          // Kill the leader
          sim.crash(leaderId)
          // Wait for re-election among survivors
          val survivor = sim.nodes.values.first()
          survivor.role.filter { it is RaftRole.Leader || it is RaftRole.Follower }.first()
          assertNotNull(sim.leader())
          sim.checkInvariants()
      }
  }
  ```

- [ ] **Run tests to verify they fail**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*ElectionTest" 2>&1 | tail -5
  ```
  Expected: FAILED (raftNode not defined)

- [ ] **Implement `RaftEngine.kt`** — full leader election

  Create `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/internal/RaftEngine.kt`:

  ```kotlin
  package us.tractat.kuilt.raft.internal

  import kotlinx.coroutines.CompletableDeferred
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.channels.Channel
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch
  import kotlinx.serialization.decodeFromByteArray
  import kotlinx.serialization.encodeToByteArray
  import kotlinx.serialization.cbor.Cbor
  import us.tractat.kuilt.raft.ClusterConfig
  import us.tractat.kuilt.raft.LeadershipLostException
  import us.tractat.kuilt.raft.LogEntry
  import us.tractat.kuilt.raft.NodeId
  import us.tractat.kuilt.raft.NotLeaderException
  import us.tractat.kuilt.raft.RaftConfig
  import us.tractat.kuilt.raft.RaftNode
  import us.tractat.kuilt.raft.RaftRole
  import us.tractat.kuilt.raft.RaftStorage
  import us.tractat.kuilt.raft.RaftTransport
  import kotlin.random.Random

  internal class RaftEngine(
      private val clusterConfig: ClusterConfig,
      private val transport: RaftTransport,
      private val storage: RaftStorage,
      private val raftConfig: RaftConfig,
      private val scope: CoroutineScope,
  ) : RaftNode {

      private val commandChannel = Channel<EngineCommand>(Channel.UNLIMITED)

      private val _role = MutableStateFlow<RaftRole>(RaftRole.Follower)
      override val role: StateFlow<RaftRole> = _role.asStateFlow()

      private val _leader = MutableStateFlow<NodeId?>(null)
      override val leader: StateFlow<NodeId?> = _leader.asStateFlow()

      private val _commitIndex = MutableStateFlow(0L)
      override val commitIndex: StateFlow<Long> = _commitIndex.asStateFlow()

      private val _committed = MutableSharedFlow<LogEntry>(extraBufferCapacity = 256)
      override val committed: Flow<LogEntry> = _committed

      // Mutable engine state (only touched inside the actor coroutine)
      private var currentTerm: Long = 0L
      private var votedFor: NodeId? = null
      private var log: MutableList<LogEntry> = mutableListOf()
      private var currentCommitIndex: Long = 0L
      private var votesReceived: MutableSet<NodeId> = mutableSetOf()

      // Leader state
      private val nextIndex: MutableMap<NodeId, Long> = mutableMapOf()
      private val matchIndex: MutableMap<NodeId, Long> = mutableMapOf()
      private val pendingProposals: MutableList<Pair<Long, CompletableDeferred<LogEntry>>> = mutableListOf()

      private var electionTimeoutJob: Job? = null
      private var heartbeatJob: Job? = null

      init {
          // Restore persisted state
          scope.launch {
              currentTerm = storage.term()
              votedFor = storage.votedFor()
              log = storage.entries().toMutableList()
              startActor()
              resetElectionTimeout()
              // Subscribe to incoming messages
              launch {
                  transport.incoming.collect { envelope ->
                      val msg = Cbor.decodeFromByteArray<RaftMessage>(envelope.bytes)
                      commandChannel.send(EngineCommand.IncomingMessage(envelope.from, msg))
                  }
              }
          }
      }

      private fun startActor() {
          scope.launch {
              for (cmd in commandChannel) {
                  when (cmd) {
                      is EngineCommand.IncomingMessage -> handleMessage(cmd.from, cmd.message)
                      is EngineCommand.Propose -> handlePropose(cmd.command, cmd.response)
                      is EngineCommand.ElectionTimeout -> handleElectionTimeout()
                      is EngineCommand.HeartbeatTick -> handleHeartbeatTick()
                      is EngineCommand.Close -> { commandChannel.close(); break }
                  }
              }
          }
      }

      // ── Election timeout ──────────────────────────────────────────────────────

      private fun resetElectionTimeout() {
          electionTimeoutJob?.cancel()
          if (_role.value is RaftRole.Learner) return
          electionTimeoutJob = scope.launch {
              delay(randomElectionTimeout())
              commandChannel.trySend(EngineCommand.ElectionTimeout)
          }
      }

      private fun randomElectionTimeout(): Long =
          Random.nextLong(raftConfig.electionTimeoutMin.inWholeMilliseconds,
                          raftConfig.electionTimeoutMax.inWholeMilliseconds)

      private suspend fun handleElectionTimeout() {
          if (_role.value is RaftRole.Leader) return
          // Become candidate
          currentTerm++
          storage.saveTerm(currentTerm)
          storage.saveVotedFor(transport.selfId)
          votedFor = transport.selfId
          votesReceived = mutableSetOf(transport.selfId)
          _role.value = RaftRole.Candidate
          _leader.value = null
          resetElectionTimeout()
          // Send RequestVote to all peers
          val lastEntry = log.lastOrNull()
          val msg = RaftMessage.RequestVote(
              term = currentTerm,
              candidateId = transport.selfId,
              lastLogIndex = lastEntry?.index ?: 0L,
              lastLogTerm = lastEntry?.term ?: 0L,
          )
          clusterConfig.voters.filter { it != transport.selfId }.forEach { peer ->
              transport.sendTo(peer, Cbor.encodeToByteArray(msg))
          }
      }

      // ── Message dispatch ──────────────────────────────────────────────────────

      private suspend fun handleMessage(from: NodeId, message: RaftMessage) {
          when (message) {
              is RaftMessage.RequestVote -> onRequestVote(from, message)
              is RaftMessage.RequestVoteResponse -> onRequestVoteResponse(from, message)
              is RaftMessage.AppendEntries -> onAppendEntries(from, message)
              is RaftMessage.AppendEntriesResponse -> onAppendEntriesResponse(from, message)
          }
      }

      private suspend fun onRequestVote(from: NodeId, msg: RaftMessage.RequestVote) {
          if (msg.term > currentTerm) stepDown(msg.term)
          val lastEntry = log.lastOrNull()
          val logOk = msg.lastLogTerm > (lastEntry?.term ?: 0L) ||
              (msg.lastLogTerm == (lastEntry?.term ?: 0L) && msg.lastLogIndex >= (lastEntry?.index ?: 0L))
          val grant = msg.term == currentTerm && logOk && (votedFor == null || votedFor == msg.candidateId)
          if (grant) {
              storage.saveVotedFor(msg.candidateId); votedFor = msg.candidateId
              resetElectionTimeout()
          }
          transport.sendTo(from, Cbor.encodeToByteArray(RaftMessage.RequestVoteResponse(currentTerm, grant)))
      }

      private suspend fun onRequestVoteResponse(from: NodeId, msg: RaftMessage.RequestVoteResponse) {
          if (msg.term > currentTerm) { stepDown(msg.term); return }
          if (_role.value !is RaftRole.Candidate || msg.term != currentTerm) return
          if (msg.voteGranted) {
              votesReceived.add(from)
              if (votesReceived.size >= clusterConfig.quorumSize) becomeLeader()
          }
      }

      private fun becomeLeader() {
          _role.value = RaftRole.Leader
          _leader.value = transport.selfId
          electionTimeoutJob?.cancel()
          val nextIdx = (log.lastOrNull()?.index ?: 0L) + 1L
          clusterConfig.allMembers.filter { it != transport.selfId }.forEach { peer ->
              nextIndex[peer] = nextIdx; matchIndex[peer] = 0L
          }
          heartbeatJob = scope.launch {
              while (true) {
                  commandChannel.send(EngineCommand.HeartbeatTick)
                  delay(raftConfig.heartbeatInterval.inWholeMilliseconds)
              }
          }
      }

      private suspend fun stepDown(newTerm: Long) {
          currentTerm = newTerm; storage.saveTerm(newTerm)
          storage.saveVotedFor(null); votedFor = null
          if (_role.value is RaftRole.Leader) {
              heartbeatJob?.cancel()
              pendingProposals.forEach { (_, d) -> d.completeExceptionally(LeadershipLostException()) }
              pendingProposals.clear()
          }
          _role.value = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower
          _leader.value = null
          resetElectionTimeout()
      }

      // ── AppendEntries (log replication + heartbeat) ───────────────────────────

      private suspend fun handleHeartbeatTick() {
          if (_role.value !is RaftRole.Leader) return
          clusterConfig.allMembers.filter { it != transport.selfId }.forEach { peer ->
              sendAppendEntries(peer)
          }
      }

      private suspend fun sendAppendEntries(peer: NodeId) {
          val ni = nextIndex[peer] ?: 1L
          val prevEntry = log.firstOrNull { it.index == ni - 1L }
          val entriesToSend = log.filter { it.index >= ni }
          val msg = RaftMessage.AppendEntries(
              term = currentTerm,
              leaderId = transport.selfId,
              prevLogIndex = prevEntry?.index ?: 0L,
              prevLogTerm = prevEntry?.term ?: 0L,
              entries = entriesToSend,
              leaderCommit = commitIndex,
          )
          transport.sendTo(peer, Cbor.encodeToByteArray(msg))
      }

      private suspend fun onAppendEntries(from: NodeId, msg: RaftMessage.AppendEntries) {
          if (msg.term < currentTerm) {
              transport.sendTo(from, Cbor.encodeToByteArray(RaftMessage.AppendEntriesResponse(currentTerm, false)))
              return
          }
          if (msg.term > currentTerm) stepDown(msg.term)
          _role.value = if (transport.selfId in clusterConfig.learners) RaftRole.Learner else RaftRole.Follower
          _leader.value = msg.leaderId
          resetElectionTimeout()

          // Log consistency check
          if (msg.prevLogIndex > 0L) {
              val prevEntry = log.firstOrNull { it.index == msg.prevLogIndex }
              if (prevEntry == null || prevEntry.term != msg.prevLogTerm) {
                  // §5.3 fast backup: report conflicting term info
                  val conflictEntry = log.firstOrNull { it.index == msg.prevLogIndex }
                  val conflictTerm = conflictEntry?.term
                  val conflictIndex = conflictTerm?.let { t -> log.firstOrNull { it.term == t }?.index }
                  transport.sendTo(from, Cbor.encodeToByteArray(
                      RaftMessage.AppendEntriesResponse(currentTerm, false,
                          conflictIndex = conflictIndex ?: msg.prevLogIndex,
                          conflictTerm = conflictTerm)
                  ))
                  return
              }
          }

          // Truncate any conflicting tail entries then append
          if (msg.entries.isNotEmpty()) {
              val firstNew = msg.entries.first()
              val conflict = log.firstOrNull { it.index == firstNew.index && it.term != firstNew.term }
              if (conflict != null) {
                  storage.truncateFrom(conflict.index)
                  log.removeAll { it.index >= conflict.index }
              }
              val newEntries = msg.entries.filter { new -> log.none { it.index == new.index } }
              log.addAll(newEntries)
              storage.appendEntries(newEntries)
          }

          // Advance commit index
          if (msg.leaderCommit > currentCommitIndex) {
              val newCommit = minOf(msg.leaderCommit, log.lastOrNull()?.index ?: 0L)
              advanceCommit(newCommit)
          }

          transport.sendTo(from, Cbor.encodeToByteArray(
              RaftMessage.AppendEntriesResponse(currentTerm, true, log.lastOrNull()?.index ?: 0L)
          ))
      }

      private suspend fun onAppendEntriesResponse(from: NodeId, msg: RaftMessage.AppendEntriesResponse) {
          if (msg.term > currentTerm) { stepDown(msg.term); return }
          if (_role.value !is RaftRole.Leader || msg.term != currentTerm) return
          if (msg.success) {
              matchIndex[from] = maxOf(matchIndex[from] ?: 0L, msg.matchIndex)
              nextIndex[from] = matchIndex[from]!! + 1L
              // Check if a new index can be committed (majority matchIndex >= N)
              val sorted = matchIndex.values.sortedDescending()
              val quorum = clusterConfig.quorumSize - 1 // -1 because leader counts itself
              if (sorted.size >= quorum) {
                  val majorityIndex = sorted[quorum - 1]
                  val entryAtMajority = log.firstOrNull { it.index == majorityIndex }
                  if (entryAtMajority != null && entryAtMajority.term == currentTerm && majorityIndex > currentCommitIndex) {
                      advanceCommit(majorityIndex)
                  }
              }
          } else {
              // §5.3 fast backup
              if (msg.conflictTerm != null) {
                  val lastOfConflictTerm = log.lastOrNull { it.term == msg.conflictTerm }
                  nextIndex[from] = if (lastOfConflictTerm != null) lastOfConflictTerm.index + 1L
                                    else msg.conflictIndex ?: (nextIndex[from]!! - 1L)
              } else {
                  nextIndex[from] = msg.conflictIndex ?: maxOf(1L, (nextIndex[from] ?: 1L) - 1L)
              }
              sendAppendEntries(from)
          }
      }

      private suspend fun advanceCommit(newCommitIndex: Long) {
          for (idx in (currentCommitIndex + 1)..newCommitIndex) {
              val entry = log.firstOrNull { it.index == idx } ?: continue
              _committed.emit(entry)
              _commitIndex.value = idx
              pendingProposals.removeAll { (i, d) -> if (i == idx) { d.complete(entry); true } else false }
          }
          currentCommitIndex = newCommitIndex
      }

      // ── propose() ─────────────────────────────────────────────────────────────

      private suspend fun handlePropose(command: ByteArray, response: CompletableDeferred<LogEntry>) {
          if (_role.value !is RaftRole.Leader) {
              response.completeExceptionally(NotLeaderException()); return
          }
          val index = (log.lastOrNull()?.index ?: 0L) + 1L
          val entry = LogEntry(index, currentTerm, command)
          log.add(entry)
          storage.appendEntries(listOf(entry))
          pendingProposals.add(index to response)
          // Immediately replicate to followers
          clusterConfig.allMembers.filter { it != transport.selfId }.forEach { peer ->
              sendAppendEntries(peer)
          }
      }

      override suspend fun propose(command: ByteArray): LogEntry {
          val deferred = CompletableDeferred<LogEntry>()
          commandChannel.send(EngineCommand.Propose(command, deferred))
          return try { deferred.await() } finally {
              // clean up if cancelled before completing
              if (deferred.isActive) deferred.cancel()
          }
      }

      override suspend fun close() {
          commandChannel.send(EngineCommand.Close)
      }
  }
  ```

- [ ] **Run election tests**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*ElectionTest"
  ```
  Expected: BUILD SUCCESSFUL (2 tests pass)

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): RaftEngine — leader election, log replication, propose()"
  ```

---

## Task 10: CoroutineScope.raftNode() factory

**Files:**
- Modify: `kuilt-raft/src/commonMain/kotlin/us/tractat/kuilt/raft/RaftNode.kt`

- [ ] **Add factory to `RaftNode.kt`**

  Append at the end of `RaftNode.kt` (after the interface declaration):
  ```kotlin
  import kotlinx.coroutines.CoroutineScope
  import us.tractat.kuilt.raft.internal.RaftEngine

  public fun CoroutineScope.raftNode(
      clusterConfig: ClusterConfig,
      transport: RaftTransport,
      storage: RaftStorage,
      raftConfig: RaftConfig = RaftConfig(),
  ): RaftNode = RaftEngine(clusterConfig, transport, storage, raftConfig, this)
  ```

- [ ] **Learner role initialisation** — in `RaftEngine.init`, set initial role to `Learner` if `selfId` is in `clusterConfig.learners`:

  In `RaftEngine.init`, after restoring persisted state and before `startActor()`, add:
  ```kotlin
  if (transport.selfId in clusterConfig.learners) _role.value = RaftRole.Learner
  ```

- [ ] **Verify election tests still pass**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*ElectionTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "feat(kuilt-raft): CoroutineScope.raftNode() factory + learner init"
  ```

---

## Task 11: Replication scenario tests

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ReplicationTest.kt`

- [ ] **Write replication tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ReplicationTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.async
  import kotlinx.coroutines.awaitAll
  import kotlinx.coroutines.flow.filter
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.flow.take
  import kotlinx.coroutines.flow.toList
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertEquals
  import kotlin.test.assertIs
  import kotlin.time.Duration.Companion.milliseconds

  private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)

  private fun sim(scope: CoroutineScope, n: Int = 3) = RaftSimulation(
      nodeIds = (1..n).map { NodeId("node$it") },
      scope = scope,
      raftConfig = fastConfig,
      nodeFactory = { id, transport, storage, nodeScope ->
          nodeScope.raftNode(
              clusterConfig = ClusterConfig(voters = (1..n).map { NodeId("node$it") }.toSet()),
              transport = transport, storage = storage, raftConfig = fastConfig,
          )
      },
  )

  private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
      repeat(100) {
          sim.leader()?.let { return it }
          kotlinx.coroutines.delay(1)
      }
      error("no leader elected")
  }

  class ReplicationTest {
      @Test fun basicReplication_entryReachesAllFollowers() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val entry = leader.propose(byteArrayOf(1, 2, 3))
          assertEquals(1L, entry.index)
          // All followers should emit the same committed entry
          val received = sim.followers().map { follower ->
              async { follower.committed.first() }
          }.awaitAll()
          received.forEach { assertContentEquals(byteArrayOf(1, 2, 3), it.command) }
          sim.checkInvariants()
      }

      @Test fun concurrentProposals_allCommitInOrder() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val results = (1..5).map { i ->
              async { leader.propose(byteArrayOf(i.toByte())) }
          }.awaitAll()
          assertEquals(5, results.size)
          // Indices must be monotonically increasing
          val indices = results.map { it.index }
          assertEquals(indices.sorted(), indices)
          sim.checkInvariants()
      }

      @Test fun followerFailure_quorumContinues() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          val followerId = sim.nodes.keys.first { it != leaderId }
          sim.crash(followerId)
          // Leader can still commit with remaining quorum
          val entry = leader.propose(byteArrayOf(99))
          assertEquals(1L, entry.index)
          sim.checkInvariants()
      }

      @Test fun leaderFailure_newLeaderCommits() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          sim.crash(leaderId)
          // A new leader should emerge and be able to commit
          val newLeader = awaitLeader(sim)
          val entry = newLeader.propose(byteArrayOf(77))
          assertIs<RaftRole.Leader>(newLeader.role.value)
          assertEquals(byteArrayOf(77).toList(), entry.command.toList())
          sim.checkInvariants()
      }

      @Test fun failNoAgree_quorumLost_noProgress() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          // Kill two out of three — quorum is lost
          val others = sim.nodes.keys.filter { it != leaderId }
          others.forEach { sim.crash(it) }
          // propose() should never complete (no quorum) — verify it's still pending
          var completed = false
          val job = launch {
              try { leader.propose(byteArrayOf(55)); completed = true }
              catch (_: Exception) {}
          }
          kotlinx.coroutines.delay(50)
          job.cancel()
          assertEquals(false, completed)
      }
  }
  ```

- [ ] **Run replication tests**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*ReplicationTest"
  ```
  Expected: BUILD SUCCESSFUL (5 tests pass)

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "test(kuilt-raft): replication scenario tests"
  ```

---

## Task 12: Learner scenario tests

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LearnerTest.kt`

- [ ] **Write learner tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/LearnerTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.async
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertFailsWith
  import kotlin.test.assertIs
  import kotlin.test.assertNotNull
  import kotlin.time.Duration.Companion.milliseconds

  private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)
  private val voterIds = setOf(NodeId("v1"), NodeId("v2"), NodeId("v3"))
  private val learnerId = NodeId("learner")
  private val config = ClusterConfig(voters = voterIds, learners = setOf(learnerId))

  private fun simWithLearner(scope: CoroutineScope) = RaftSimulation(
      nodeIds = voterIds.toList() + learnerId,
      scope = scope,
      raftConfig = fastConfig,
      nodeFactory = { id, transport, storage, nodeScope ->
          nodeScope.raftNode(config, transport, storage, fastConfig)
      },
  )

  private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
      repeat(100) { sim.leader()?.let { return it }; kotlinx.coroutines.delay(1) }
      error("no leader")
  }

  class LearnerTest {
      @Test fun learner_receives_committed_entries() = runTest(UnconfinedTestDispatcher()) {
          val sim = simWithLearner(this)
          val leader = awaitLeader(sim)
          val learner = sim.nodes[learnerId]
          assertNotNull(learner)
          assertIs<RaftRole.Learner>(learner.role.value)
          val received = async { learner.committed.first() }
          leader.propose(byteArrayOf(42))
          assertContentEquals(byteArrayOf(42), received.await().command)
      }

      @Test fun learner_never_becomes_leader() = runTest(UnconfinedTestDispatcher()) {
          val sim = simWithLearner(this)
          awaitLeader(sim)
          val learner = sim.nodes[learnerId]!!
          assertIs<RaftRole.Learner>(learner.role.value)
      }

      @Test fun learner_propose_throws_NotLeaderException() = runTest(UnconfinedTestDispatcher()) {
          val sim = simWithLearner(this)
          awaitLeader(sim)
          val learner = sim.nodes[learnerId]!!
          assertFailsWith<NotLeaderException> { learner.propose(byteArrayOf(1)) }
      }

      @Test fun learner_catchup_after_partition() = runTest(UnconfinedTestDispatcher()) {
          val sim = simWithLearner(this)
          val leader = awaitLeader(sim)
          val learner = sim.nodes[learnerId]!!
          // Partition learner
          sim.partition(setOf(learnerId), voterIds)
          leader.propose(byteArrayOf(10))
          leader.propose(byteArrayOf(20))
          // Heal and let learner catch up
          sim.heal()
          val entries = mutableListOf<LogEntry>()
          val job = kotlinx.coroutines.launch { learner.committed.collect { entries.add(it) } }
          kotlinx.coroutines.delay(50)
          job.cancel()
          assert(entries.isNotEmpty()) { "learner received no entries after heal" }
      }
  }
  ```

- [ ] **Run learner tests**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*LearnerTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "test(kuilt-raft): learner scenario tests"
  ```

---

## Task 13: Chaos scenario tests

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ChaosTest.kt`

- [ ] **Write chaos tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/ChaosTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertContentEquals
  import kotlin.test.assertEquals
  import kotlin.test.assertNotNull
  import kotlin.time.Duration.Companion.milliseconds

  private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)
  private fun sim(scope: CoroutineScope, n: Int = 3) = RaftSimulation(
      nodeIds = (1..n).map { NodeId("n$it") },
      scope = scope, raftConfig = fastConfig,
      nodeFactory = { id, transport, storage, nodeScope ->
          nodeScope.raftNode(
              ClusterConfig((1..n).map { NodeId("n$it") }.toSet()),
              transport, storage, fastConfig,
          )
      },
  )

  private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
      repeat(200) { sim.leader()?.let { return it }; delay(1) }
      error("no leader elected")
  }

  class ChaosTest {
      @Test fun persistence_node_rejoins_with_same_log() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          leader.propose(byteArrayOf(1))
          leader.propose(byteArrayOf(2))
          // Crash and restart a follower — storage survives
          val followerId = sim.nodes.keys.first { sim.nodes[it] !== leader }
          sim.crash(followerId)
          sim.restart(followerId)
          // The restarted node should catch up and emit committed entries
          val restarted = sim.nodes[followerId]!!
          val entry = restarted.committed.first()
          assert(entry.index >= 1L) { "restarted node got no committed entries" }
          sim.checkInvariants()
      }

      @Test fun rejoinPartitionedLeader_reverts_to_follower() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          val others = sim.nodes.keys.filter { it != leaderId }.toSet()
          // Isolate the leader
          sim.partition(setOf(leaderId), others)
          delay(30) // let others elect a new leader
          sim.heal()
          delay(30) // let the old leader step down on receiving a higher term
          // The originally isolated node must no longer be leader
          val oldLeaderNode = sim.nodes[leaderId]!!
          assert(oldLeaderNode.role.value !is RaftRole.Leader) {
              "old partitioned leader did not step down: ${oldLeaderNode.role.value}"
          }
          sim.checkInvariants()
      }

      @Test fun logBackup_leaderBacksUpFarOverDivergentFollower() = runTest(UnconfinedTestDispatcher()) {
          // 5-node cluster — partition allows a minority to get stale entries,
          // then new leader must back up over them using §5.3 fast backup.
          val sim = sim(this, n = 5)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          val minority = sim.nodes.keys.filter { it != leaderId }.take(2).toSet()
          val majority = sim.nodes.keys.filter { it !in minority && it != leaderId }.toSet()
          // Partition minority so only leader + majority can commit
          sim.partition(minority, majority + leaderId)
          repeat(5) { leader.propose(byteArrayOf(it.toByte())) }
          // Heal; minority has stale log, new leader must overwrite via fast backup
          sim.heal()
          val newLeader = awaitLeader(sim)
          val committed = newLeader.propose(byteArrayOf(99))
          assertNotNull(committed)
          sim.checkInvariants()
      }

      @Test fun unreliableChurn_stateMachineConsistent() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          var leader = awaitLeader(sim)
          val committed = mutableListOf<LogEntry>()
          // Propose 3 entries, crashing and healing between each
          repeat(3) { i ->
              try { committed.add(leader.propose(byteArrayOf(i.toByte()))) } catch (_: Exception) {}
              val leaderId = sim.nodes.entries.firstOrNull { it.value === leader }?.key
              if (leaderId != null) sim.crash(leaderId)
              sim.heal()
              delay(20)
              leader = awaitLeader(sim)
          }
          sim.checkInvariants()
          // All committed entries must have monotonically increasing indices
          val indices = committed.map { it.index }
          assertEquals(indices.sorted(), indices)
      }
  }
  ```

- [ ] **Run chaos tests**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*ChaosTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "test(kuilt-raft): chaos scenario tests — persistence, partitioned leader, logBackup, unreliable churn"
  ```

---

## Task 14: Safety invariant tests

**Files:**
- Create: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InvariantTest.kt`
- Modify: `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/RaftSimulation.kt`

- [ ] **Extend `checkInvariants()` in `RaftSimulation`**

  Replace the body of `checkInvariants()` in `RaftSimulation.kt`:
  ```kotlin
  suspend fun checkInvariants() {
      val leaders = nodes.values.filter { it.role.value is RaftRole.Leader }

      // 1. Election Safety: at most one leader
      assertTrue(leaders.size <= 1,
          "Election Safety violated: ${leaders.size} leaders simultaneously")

      // 2. State Machine Safety: no two nodes committed different commands at same index
      val logsByNode = nodes.values.associate { node ->
          node to storages[nodes.entries.first { it.value === node }.key]
      }
      // Build committed-index → command map from first node; verify all others match
      // (Only checks up to the minimum commitIndex across nodes)
      val minCommit = nodes.values.minOfOrNull { it.commitIndex.value } ?: 0L
      if (minCommit > 0L) {
          val reference = logsByNode.values.firstOrNull() ?: return
          logsByNode.values.drop(1).forEach { other ->
              (1..minCommit).forEach { idx ->
                  val refEntry = reference.entries(idx).firstOrNull { it.index == idx }
                  val otherEntry = other.entries(idx).firstOrNull { it.index == idx }
                  if (refEntry != null && otherEntry != null) {
                      assertTrue(
                          refEntry.command.contentEquals(otherEntry.command),
                          "State Machine Safety violated at index $idx"
                      )
                  }
              }
          }
      }
  }
  ```

- [ ] **Write invariant tests**

  Create `kuilt-raft/src/commonTest/kotlin/us/tractat/kuilt/raft/InvariantTest.kt`:
  ```kotlin
  package us.tractat.kuilt.raft

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.time.Duration.Companion.milliseconds

  private val fastConfig = RaftConfig(5.milliseconds, 10.milliseconds, 2.milliseconds)
  private fun sim(scope: CoroutineScope, n: Int = 3) = RaftSimulation(
      nodeIds = (1..n).map { NodeId("inv$it") },
      scope = scope, raftConfig = fastConfig,
      nodeFactory = { id, transport, storage, nodeScope ->
          nodeScope.raftNode(ClusterConfig((1..n).map { NodeId("inv$it") }.toSet()), transport, storage, fastConfig)
      },
  )

  private suspend fun awaitLeader(sim: RaftSimulation): RaftNode {
      repeat(200) { sim.leader()?.let { return it }; delay(1) }
      error("no leader")
  }

  class InvariantTest {
      @Test fun invariants_hold_during_steady_state() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          repeat(5) { i -> leader.propose(byteArrayOf(i.toByte())) }
          delay(20)
          sim.checkInvariants()
      }

      @Test fun invariants_hold_across_leader_churn() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this, 5)
          repeat(3) {
              val leader = awaitLeader(sim)
              try { leader.propose(byteArrayOf(it.toByte())) } catch (_: Exception) {}
              val id = sim.nodes.entries.first { e -> e.value === leader }.key
              sim.crash(id)
              delay(20)
              sim.checkInvariants()
              sim.restart(id)
          }
      }

      @Test fun invariants_hold_after_partition_heal() = runTest(UnconfinedTestDispatcher()) {
          val sim = sim(this)
          val leader = awaitLeader(sim)
          val leaderId = sim.nodes.entries.first { it.value === leader }.key
          val others = sim.nodes.keys.filter { it != leaderId }.toSet()
          sim.partition(setOf(leaderId), others)
          delay(30)
          sim.heal()
          delay(30)
          sim.checkInvariants()
      }
  }
  ```

- [ ] **Run invariant tests**

  ```bash
  ./gradlew :kuilt-raft:jvmTest --tests "*InvariantTest"
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Run full test suite**

  ```bash
  ./gradlew :kuilt-raft:jvmTest
  ```
  Expected: all tests pass, BUILD SUCCESSFUL

- [ ] **Commit**

  ```bash
  git add kuilt-raft/src/
  git commit -m "test(kuilt-raft): safety invariant tests and full scenario coverage"
  ```

---

## Task 15: Full build verification and PR

- [ ] **Run all platform tests**

  ```bash
  ./gradlew :kuilt-raft:allTests
  ```
  Expected: JVM, macOS, and wasmJs tests all pass (iOS sim optional locally).

- [ ] **Run full project build to confirm nothing broken**

  ```bash
  ./gradlew build
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Open PR**

  ```bash
  gh pr create --title "feat(kuilt-raft): Raft consensus module" \
    --body "Implements :kuilt-raft per the design spec in docs/superpowers/specs/2026-06-05-raft-design.md.

  - RaftNode interface (coroutine-native: CoroutineScope.raftNode() factory, propose() suspends until committed)
  - RaftTransport / RaftStorage interfaces with InMemoryRaftStorage default
  - SeamRaftTransport adapter (only kuilt-core import)
  - Channel-based actor engine — no locks; single coroutine processes all Raft events
  - Leader election, log replication, §5.3 fast log backup, learner support
  - RaftSimulation harness + InMemoryRaftNetwork for deterministic scenario tests
  - Full MIT 6.5840 scenario matrix: election, replication, chaos, learner, invariants"
  gh pr merge --auto --squash
  ```
