# gameNode Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one-call bootstrap entry points to `:kuilt-game` — `gameHost`/`gameJoin` (appoint-the-host) and `gameNode(seam, voterIds)` (roster-given) — that hide Raft `ClusterConfig`/transport/storage behind a `Seam`, per issue [#480](https://github.com/tractat-us/kuilt/issues/480).

**Architecture:** Appoint, don't elect (see `docs/superpowers/specs/2026-06-17-game-node-bootstrap-design.md`). The host bootstraps a singleton-voter Raft cluster and admits joiners as learner→voter over a `SeamRaftTransport`; joiners start as learners and never self-elect. A second entry point takes a known roster and constructs an identical static config on every peer. An `EphemeralMap`-over-`Quilter` presence layer carries a host-declaration flag so `gameHost` fails fast on a duplicate host. Exactly one host per session is a precondition.

**Tech Stack:** Kotlin Multiplatform (all targets), kotlinx-coroutines, `:kuilt-core` (`Seam`), `:kuilt-raft` (`RaftNode`, `SeamRaftTransport`, `ClusterConfig`), `:kuilt-quilter` + `:kuilt-crdt` (`EphemeralMap` presence), tests via `runTest(StandardTestDispatcher(), timeout = 5.seconds)` over `InMemoryLoom`.

---

## Design notes the implementer must internalise

- **Real `RaftNode` under virtual time needs `RaftConfig(expectVirtualTime = true)`.** `CoroutineScope.raftNode(...)` calls `checkNotUnderTestDispatcher` and throws under a `TestDispatcher` unless `expectVirtualTime = true`. Production entry points construct `RaftConfig()` (defaults, `expectVirtualTime = false`); they must expose a way for tests to inject `RaftConfig(expectVirtualTime = true)`. This is sub-spec D4: keep the flag out of the production overload, inject via a test-only parameter/overload.
- **`ServerCluster.admitLearner`/`changeMembershipWithRetry` live in `kuilt-cluster/jvmAndAndroidMain` and CANNOT be called from `:kuilt-game` (all targets).** Reuse the *pattern* (verbatim below), not the code — `:kuilt-game` gets its own common admit loop.
- **`incoming` is single-collection (ADR-034).** Once a `Seam` is wrapped in `SeamRaftTransport`, nobody else may collect `seam.incoming`. The presence `Quilter` therefore must run on a **separate** `Seam` (a `MuxSeam` channel), not the raft seam. The bootstrap API takes one seam and muxes internally, or documents that the caller passes a dedicated channel.
- **Phased PRs — one behaviour per PR.** Each task below is its own PR. TDD: failing test commit, then implementation commit (never squashed together).
- **Test seams:** `InMemoryLoom().host(Pattern("s"))` + `.join(tag)` give connected 2-peer seams (see `kuilt-quilter/src/commonSamples/.../QuilterSamples.kt:74`). Confirm N-peer support in Task 1 before relying on 3-peer tests.

---

## File Structure

- **Create** `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt` — entry points (`gameHost`, `gameJoin`, `gameNode`) + the common admit loop + fail-fast. One responsibility: bootstrap a `RaftNode` over a `Seam`.
- **Create** `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GamePresence.kt` — `EphemeralMap<HostDeclaration>`-over-`Quilter` wrapper: heartbeat, live-set read, `declaredHosts()` query for the fail-fast check.
- **Create** `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameBootstrapHarness.kt` — test helper: stand up N `InMemoryLoom` seats and run `gameHost`/`gameJoin` under virtual time.
- **Create** `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt` — bootstrap tests.
- **Create** `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GamePresenceTest.kt` — presence/fail-fast tests.
- **Modify** `kuilt-game/build.gradle.kts` — add `:kuilt-quilter`, `:kuilt-crdt` deps.
- **Modify** `kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt` — add a `gameHost`/`gameJoin` `@sample`.
- **Modify** `kuilt-game/module.md` — document the entry points (if the file exists; create if the convention requires it).

---

## Task 1: Module deps + Seam-based virtual-time test harness

**Files:**
- Modify: `kuilt-game/build.gradle.kts:8-14`
- Create: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameBootstrapHarness.kt`
- Create: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HarnessSmokeTest.kt`

- [ ] **Step 1: Add presence-layer dependencies**

In `kuilt-game/build.gradle.kts`, extend `commonMain.dependencies`:

```kotlin
commonMain.dependencies {
    api(project(":kuilt-core"))
    api(project(":kuilt-raft"))
    implementation(project(":kuilt-quilter"))
    implementation(project(":kuilt-crdt"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
}
```

- [ ] **Step 2: Write a smoke test that stands up two real RaftNodes over Seams and reaches a leader**

`HarnessSmokeTest.kt` — proves the harness wiring (InMemoryLoom + `SeamRaftTransport` + `raftNode(expectVirtualTime=true)` + `backgroundScope`) converges under virtual time. This de-risks every later task.

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessSmokeTest {
    @Test
    fun twoSeatStaticClusterElectsLeader() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val loom = InMemoryLoom()
        val hostSeam = loom.host(Pattern("smoke"))
        val joinSeam = loom.join(hostSeam.advertisement())   // confirm InMemoryLoom's tag accessor in Task 1
        val ids = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))
        val cfg = RaftConfig(expectVirtualTime = true)

        val a = backgroundScope.raftNode(ClusterConfig.ofVoters(ids), SeamRaftTransport(hostSeam), InMemoryRaftStorage(), cfg)
        val b = backgroundScope.raftNode(ClusterConfig.ofVoters(ids), SeamRaftTransport(joinSeam), InMemoryRaftStorage(), cfg)

        a.awaitLeadership()   // one of them wins; if it's b, swap — see note
        assertTrue(a.role.value is us.tractat.kuilt.raft.RaftRole.Leader)
    }
}
```

> **Note for the implementer:** the exact `InMemoryLoom` join API (how a joiner obtains the host's `Tag`) must be confirmed from `kuilt-core` — `loom.host(Pattern)` returns a `Seam`; find how the conformance suite (`InMemoryLoomConformanceTest`, `SeamConformanceSuite.newLoomPair()`) pairs host+joiner and copy that exact wiring. `awaitLeadership()` only completes on the node that wins; for a deterministic 2-node test, await leadership on *either* via `select`/race or assert `a.role` or `b.role` is Leader. Resolve this in Task 1 so later tasks inherit a working pattern.

- [ ] **Step 3: Run the smoke test**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-game:jvmTest --tests "*HarnessSmokeTest*"`
Expected: PASS. If it hangs, `jstack` the test JVM (see the multi-node-raft-test-discipline guidance) — a hang means non-convergence, not a slow runner.

- [ ] **Step 4: Extract the working wiring into `GameBootstrapHarness.kt`**

Factor the proven host+join pairing into a reusable helper:

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Seam

/** Stand up [n] connected InMemoryLoom seats for a virtual-time bootstrap test. */
internal suspend fun seats(loom: InMemoryLoom, n: Int): List<Seam> {
    // Implement using the exact host/join pairing proven in HarnessSmokeTest Step 2.
    // Returns [host, joiner1, joiner2, ...].
    TODO("fill from the confirmed InMemoryLoom pairing API")
}
```

> Replace the `TODO` with the real wiring before committing — no `TODO` survives into the commit. The `TODO` here marks Task-1 discovery work, not a plan placeholder.

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/build.gradle.kts kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HarnessSmokeTest.kt kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameBootstrapHarness.kt
git commit -m "test(kuilt-game): Seam-based virtual-time bootstrap harness (#480)"
```

---

## Task 2: `gameNode(seam, voterIds)` — roster-given static config

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt`
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt`

- [ ] **Step 1: Write the failing test (2-peer roster-given)**

```kotlin
@Test
fun rosterGivenTwoPeersConverge() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val (hostSeam, joinSeam) = seats(loom, 2)
    val voters = setOf(NodeId(hostSeam.selfId.value), NodeId(joinSeam.selfId.value))
    val cfg = RaftConfig(expectVirtualTime = true)

    val a = backgroundScope.gameNode(hostSeam, voters, raftConfig = cfg)
    val b = backgroundScope.gameNode(joinSeam, voters, raftConfig = cfg)

    val leader = awaitEitherLeader(a, b)   // helper in GameBootstrapHarness
    val proposed = TurnSequencer(leader, Int.serializer()).propose(42)
    assertEquals(42, proposed.action)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "*GameNodeTest.rosterGivenTwoPeersConverge*"`
Expected: FAIL — `gameNode` unresolved.

- [ ] **Step 3: Implement `gameNode` (static-config path)**

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.raft.ClusterConfig
import us.tractat.kuilt.raft.InMemoryRaftStorage
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.raft.RaftConfig
import us.tractat.kuilt.raft.RaftNode
import us.tractat.kuilt.raft.RaftStorage
import us.tractat.kuilt.raft.SeamRaftTransport
import us.tractat.kuilt.raft.raftNode

/**
 * Construct a [RaftNode] over [seam] for a session whose full voter roster is
 * already known to every peer (e.g. from matchmaking). Every peer builds the
 * identical [ClusterConfig.ofVoters] and Raft's own election picks the leader —
 * symmetric, no pre-Raft step.
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034 single-collection).
 *
 * @param raftConfig defaults to production [RaftConfig]; tests pass
 *   `RaftConfig(expectVirtualTime = true)` to run a real node under virtual time.
 */
public fun CoroutineScope.gameNode(
    seam: Seam,
    voterIds: Set<NodeId>,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(NodeId(seam.selfId.value) in voterIds) {
        "this peer (${seam.selfId.value}) must be in voterIds $voterIds"
    }
    return raftNode(ClusterConfig.ofVoters(voterIds), SeamRaftTransport(seam), storage, raftConfig)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "*GameNodeTest.rosterGivenTwoPeersConverge*"`
Expected: PASS.

- [ ] **Step 5: Add the 3-peer test** (only if Task 1 confirmed InMemoryLoom 3-seat support; otherwise note the limitation and defer to a fabric that supports it)

```kotlin
@Test
fun rosterGivenThreePeersQuorumTwo() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val s = seats(loom, 3)
    val voters = s.map { NodeId(it.selfId.value) }.toSet()
    val cfg = RaftConfig(expectVirtualTime = true)
    val nodes = s.map { backgroundScope.gameNode(it, voters, raftConfig = cfg) }
    val leader = awaitAnyLeader(nodes)
    val entry = TurnSequencer(leader, Int.serializer()).propose(7)
    assertEquals(7, entry.action)
}
```

- [ ] **Step 6: Commit (two commits — test first, then impl, already staged separately)**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt
git commit -m "feat(kuilt-game): gameNode(seam, voterIds) roster-given bootstrap (#480)"
```

---

## Task 3: `gameHost` / `gameJoin` — appoint-the-host happy path

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt`
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt`

- [ ] **Step 1: Write the failing test (host + one joiner)**

```kotlin
@Test
fun hostAdmitsOneJoiner() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val (hostSeam, joinSeam) = seats(loom, 2)
    val cfg = RaftConfig(expectVirtualTime = true)

    val host = backgroundScope.gameHost(hostSeam, peerCount = 2, raftConfig = cfg)
    val joiner = backgroundScope.gameJoin(joinSeam, raftConfig = cfg)

    // host suspends until membership reaches peerCount; both end as voters.
    val entry = TurnSequencer(host, Int.serializer()).propose(99)
    assertEquals(99, entry.action)
    // joiner sees the committed action too:
    val onJoiner = TurnSequencer(joiner, Int.serializer()).committed.first()
    assertEquals(99, onJoiner.action)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "*GameNodeTest.hostAdmitsOneJoiner*"`
Expected: FAIL — `gameHost`/`gameJoin` unresolved.

- [ ] **Step 3: Implement `gameHost` + `gameJoin` + the common admit loop**

Add to `GameNode.kt`. The admit loop mirrors `ServerCluster.changeMembershipWithRetry` (verbatim pattern, re-homed to commonMain):

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import us.tractat.kuilt.raft.MembershipChangeInProgressException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Host a game session over [seam]: bootstrap a singleton-voter cluster, then
 * admit each connecting peer as learner→voter until [peerCount] voters are
 * committed. Suspends until the cluster is at full membership, then returns the
 * leader [RaftNode].
 *
 * **Precondition: exactly one `gameHost` per session.** On unarbitrated fabrics
 * the application designates the host; this call fails fast if it observes a
 * second declared host (Task 6).
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034).
 */
public suspend fun CoroutineScope.gameHost(
    seam: Seam,
    peerCount: Int,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    require(peerCount >= 1) { "peerCount must be >= 1" }
    val self = NodeId(seam.selfId.value)
    val transport = SeamRaftTransport(seam)
    val node = raftNode(ClusterConfig.ofVoters(setOf(self)), transport, storage, raftConfig)
    node.awaitLeadership()

    // Admit peers as they appear until we have peerCount voters.
    val voters = mutableSetOf(self)
    while (voters.size < peerCount) {
        val next = transport.peers.first { (it - voters).isNotEmpty() }
        val joinerId = (next - voters).first()
        // learner first (no quorum disruption), then promote to voter.
        changeMembershipWithRetry(node, ClusterConfig(voters = voters, learners = setOf(joinerId)))
        voters += joinerId
        changeMembershipWithRetry(node, ClusterConfig(voters = voters))
    }
    return node
}

/**
 * Join a game session over [seam] hosted by exactly one [gameHost]. Starts as a
 * learner (empty self-config) and is admitted to voter by the host. Returns the
 * local [RaftNode] once it has been admitted to the cluster.
 *
 * **Do not collect `seam.incoming` after calling this** (ADR-034).
 */
public suspend fun CoroutineScope.gameJoin(
    seam: Seam,
    storage: RaftStorage = InMemoryRaftStorage(),
    raftConfig: RaftConfig = RaftConfig(),
): RaftNode {
    val self = NodeId(seam.selfId.value)
    // Start as a non-voting member; the host's changeMembership adds us. The
    // initial config names only self as a learner so we never self-elect.
    val node = raftNode(ClusterConfig(voters = emptySet(), learners = setOf(self)), SeamRaftTransport(seam), storage, raftConfig)
    // Suspend until the host has admitted us as a voter.
    node.commitIndex.first { it >= 0L }   // replaced by an explicit "admitted" await — see note
    return node
}

private suspend fun changeMembershipWithRetry(
    node: RaftNode,
    config: ClusterConfig,
    maxAttempts: Int = 20,
    retryDelay: kotlin.time.Duration = 200.milliseconds,
) {
    repeat(maxAttempts) {
        try {
            node.changeMembership(config)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: MembershipChangeInProgressException) {
            delay(retryDelay)
        }
    }
    error("changeMembership gave up after $maxAttempts attempts for $config")
}
```

> **Implementer decisions to resolve in this task (not placeholders — real design calls):**
> 1. **`gameJoin` "admitted" signal.** Confirm how a learner observes it has become a voter — likely `node.role` transitioning out of `RaftRole.Learner`, or the config appearing in a committed entry. Replace the `commitIndex.first` stub with the correct await (e.g. `node.role.first { it !is RaftRole.Learner } ` or observe `committed` for the config entry). Verify against `RaftNode` semantics; a learner never becomes Leader, so don't `awaitLeadership()` on a joiner.
> 2. **`gameJoin` initial config.** Confirm `raftNode` accepts a config where self is only a learner and the voters set is empty/unknown, and that such a node correctly receives AppendEntries from the host once admitted. If the engine requires the host in the joiner's initial config, derive the host id from `seam.peers` (the first/lowest, or the declared host from presence in Task 5) before constructing.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "*GameNodeTest.hostAdmitsOneJoiner*"`
Expected: PASS. On hang, `jstack` and inspect for an admission that never commits (quorum not reached) — do not widen the timeout.

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt
git commit -m "feat(kuilt-game): gameHost/gameJoin appoint-the-host bootstrap (#480)"
```

---

## Task 4: Latecomer admission

**Files:**
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt`

- [ ] **Step 1: Write the failing test** — a third peer joins after the first proposal commits and replays the log.

```kotlin
@Test
fun latecomerJoinsAfterFirstCommit() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val (hostSeam, j1) = seats(loom, 2)
    val cfg = RaftConfig(expectVirtualTime = true)
    val host = backgroundScope.gameHost(hostSeam, peerCount = 3, raftConfig = cfg)
    val joiner1 = backgroundScope.gameJoin(j1, raftConfig = cfg)
    // first proposal with 2 of 3 present (quorum) ...
    // then a third seat connects and is admitted; assert it replays the committed action.
    val j2 = loom.join(/* host advertisement */)   // from harness
    val joiner2 = backgroundScope.gameJoin(j2, raftConfig = cfg)
    val replayed = TurnSequencer(joiner2, Int.serializer()).committed.first()
    assertEquals(/* the earlier action */ 5, replayed.action)
}
```

> Confirm `gameHost(peerCount = 3)` admits the third seat whenever it appears (the admit loop in Task 3 already loops until `voters.size == peerCount`). If quorum-before-full-membership proposing is wanted, the host must be able to `propose` after the *first* admission — verify the loop returns/streams appropriately, or split "return at quorum" vs "return at full membership" per the design's readiness-gate note (D2). Resolve here.

- [ ] **Step 2: Run, implement any gap, re-run, commit** (TDD as above). If Task 3's loop already satisfies it, this task is test-only — still its own commit.

```bash
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt
git commit -m "test(kuilt-game): latecomer admission after first commit (#480)"
```

---

## Task 5: Presence layer — `EphemeralMap` host-declaration over `Quilter`

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GamePresence.kt`
- Create: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GamePresenceTest.kt`

- [ ] **Step 1: Write the failing test** — two presence replicas over a `MuxSeam` channel converge; a host declaration on one is visible on the other.

```kotlin
@Test
fun hostDeclarationConverges() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val (s1, s2) = seats(loom, 2)
    val p1 = GamePresence(s1, backgroundScope, expectVirtualTime = true)
    val p2 = GamePresence(s2, backgroundScope, expectVirtualTime = true)
    p1.declareHost()
    // advance virtual time so Quilter anti-entropy/delta delivery converges
    testScheduler.advanceUntilIdle()   // NOTE: only safe if no re-arming timers; else bounded advance
    assertEquals(setOf(ReplicaId(s1.selfId.value)), p2.declaredHosts())
}
```

> `advanceUntilIdle()` is acceptable for `Quilter` only if its timers quiesce; if `Quilter` re-arms anti-entropy forever under virtual time, use bounded `advanceTimeBy(...) + runCurrent()` instead. Confirm against `QuilterSamples`/Quilter tests in Task 5.

- [ ] **Step 2: Run to verify it fails.** `./gradlew :kuilt-game:jvmTest --tests "*GamePresenceTest.hostDeclarationConverges*"` → FAIL (`GamePresence` unresolved).

- [ ] **Step 3: Implement `GamePresence`**

```kotlin
package us.tractat.kuilt.game

import kotlinx.coroutines.CoroutineScope
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.quilter.QuiltMessage
import kotlinx.serialization.builtins.serializer

/** Marker value stored under a replica's slot to declare "I am the host". */
private const val HOST_DECLARED = "host"

/**
 * Lobby presence over [seam], backed by an [EphemeralMap] replicated by
 * [Quilter]. Carries each peer's host-declaration flag so [gameHost] can fail
 * fast on a duplicate host.
 *
 * Runs on a dedicated [seam] (pass a MuxSeam channel, not the raft seam) because
 * `incoming` is single-collection (ADR-034).
 */
public class GamePresence(
    seam: Seam,
    scope: CoroutineScope,
    expectVirtualTime: Boolean = false,
) {
    private val replica = ReplicaId(seam.selfId.value)
    private val quilter = Quilter(
        replica = replica,
        seam = seam,
        initial = EphemeralMap.empty<String>(),
        messageSerializer = QuiltMessage.serializer(EphemeralMap.serializer(String.serializer())),
        scope = scope,
        config = QuilterConfig(expectVirtualTime = expectVirtualTime),
    )

    /** Declare this peer the host. */
    public fun declareHost() {
        quilter.mutate { it.put(replica, HOST_DECLARED, clock = it.entries[replica]?.let { e -> e.clock + 1 } ?: 1L) }
    }

    /** Replicas currently declaring themselves host (converged view). */
    public fun declaredHosts(): Set<ReplicaId> =
        quilter.state.value.entries.filterValues { it.value == HOST_DECLARED }.keys
}
```

> **Confirm the exact `EphemeralMap.serializer(...)` and `QuiltMessage.serializer(...)` construction** against `QuilterSamples.kt:74` (the sample uses `GCounter.serializer()`); the `EphemeralMap<String>` analogue must compile. Confirm `mutate` vs `apply` (the sample uses `apply(patch)`; `put` returns a new map — wrap as a `Patch` if `mutate` isn't a fit). Resolve before committing — the sample is the authority.

- [ ] **Step 4: Run → PASS. Step 5: Commit.**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GamePresence.kt kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GamePresenceTest.kt
git commit -m "feat(kuilt-game): EphemeralMap host-declaration presence layer (#480)"
```

---

## Task 6: Fail-fast on duplicate host

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt`
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GamePresenceTest.kt`

- [ ] **Step 1: Write the failing test** — two `gameHost` calls on one session: the second fails fast.

```kotlin
@Test
fun secondHostFailsFast() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val (s1, s2) = seats(loom, 2)
    val cfg = RaftConfig(expectVirtualTime = true)
    backgroundScope.gameHost(s1, peerCount = 2, raftConfig = cfg)   // first host
    val ex = assertFailsWith<DuplicateHostException> {
        backgroundScope.gameHost(s2, peerCount = 2, raftConfig = cfg)   // second host on same session
    }
    assertTrue(ex.message!!.contains("host"))
}
```

- [ ] **Step 2: Run → FAIL** (`DuplicateHostException` unresolved).

- [ ] **Step 3: Implement the check.** Add `DuplicateHostException`, and have `gameHost` declare itself in presence and verify it is the sole declared host before bootstrapping.

```kotlin
public class DuplicateHostException(
    message: String = "another peer already declared host for this session — exactly one gameHost per session is required",
) : IllegalStateException(message)
```

In `gameHost`, before constructing the raft node: open a `GamePresence` on a presence channel (a `MuxSeam` channel derived from `seam`), `declareHost()`, let presence converge a bounded amount, then:

```kotlin
val others = presence.declaredHosts() - ReplicaId(seam.selfId.value)
if (others.isNotEmpty()) throw DuplicateHostException()
```

> **Resolve:** how `gameHost`/`gameJoin` obtain the presence channel without violating single-collection on the raft seam. Either (a) the public API takes a `MuxSeam` and `gameHost` uses `mux.channel("presence")` + `mux.channel("raft")`, or (b) `gameHost` takes the raw seam and internally muxes. Pick (a) if `MuxSeam` is the documented multiplex path (it is, per the sub-spec D5) — update the signatures in Tasks 2–3 to accept the channel the raft node uses, and document that the caller muxes. This is the one cross-task signature decision; make it here and back-propagate.

- [ ] **Step 4: Run → PASS. Step 5: Commit.**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GamePresenceTest.kt
git commit -m "feat(kuilt-game): fail fast on duplicate gameHost (#480)"
```

---

## Task 7: D4 test-entry hygiene, D5 incoming guard, samples + docs

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt` (KDoc)
- Modify: `kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt`
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt`
- Modify: `kuilt-game/module.md` (create if absent, matching a sibling module's `module.md` shape)

- [ ] **Step 1: `incoming`-guard test (D5)** — assert that a second collector of `seam.incoming` after `gameHost` drops raft messages (documents the constraint).

```kotlin
@Test
fun secondIncomingCollectorIsUnsupported() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    // Document via test: after gameHost wraps the seam, collecting seam.incoming
    // again races the raft engine. Assert the documented behaviour (dropped raft
    // frames / no second delivery), matching how :kuilt-core tests express the
    // single-collection contract.
}
```

- [ ] **Step 2: Confirm production overload exposes no `expectVirtualTime` (D4).** Add a test asserting the default `gameHost`/`gameNode` overload constructs `RaftConfig()` (so `expectVirtualTime == false`) — e.g. assert the test-dispatcher guard warning is *not* suppressed when the production overload runs under a `TestDispatcher`. Keep the `raftConfig` injection the only path to `expectVirtualTime = true`.

- [ ] **Step 3: Add a `@sample`** in `GameSamples.kt` showing `gameHost`/`gameJoin` (compiled as part of `commonTest`):

```kotlin
internal fun sampleGameHostJoin() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
    val loom = InMemoryLoom()
    val hostSeam = loom.host(Pattern("tic-tac-toe"))
    val host = backgroundScope.gameHost(hostSeam, peerCount = 2, raftConfig = RaftConfig(expectVirtualTime = true))
    val sequencer = TurnSequencer(host, Int.serializer())
    // sequencer.propose(...) drives the game
}
```

Reference it from `gameHost`'s KDoc with `@sample us.tractat.kuilt.game.sampleGameHostJoin`.

- [ ] **Step 4: Full build + detekt.**

Run: `./gradlew :kuilt-game:build detektAll`
Expected: BUILD SUCCESSFUL, zero detekt findings. (`detektAll`, never bare `detekt`.)

- [ ] **Step 5: Commit.**

```bash
git add kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/GameSamples.kt kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/GameNodeTest.kt kuilt-game/module.md
git commit -m "docs(kuilt-game): gameNode samples, incoming-guard + virtual-time test hygiene (#480)"
```

---

## Final verification (before PR / leaving draft)

- [ ] `./gradlew :kuilt-game:build detektAll` green.
- [ ] `./gradlew build` green locally (catches Android-variant + cross-module breaks `jvmTest` hides).
- [ ] Every entry point KDoc warns against collecting `seam.incoming` after the call.
- [ ] No `expectVirtualTime` reachable from a production overload.
- [ ] PR body: `Closes #480`, links the design doc, lists the two entry points.

## Self-review against the spec

- **(1) gameHost/gameJoin** → Tasks 3, 4, 6. **(2) gameNode(roster)** → Task 2. **Presence/EphemeralMap** → Task 5. **Host uniqueness / fail-fast** → Task 6. **D2 readiness gate** → Task 3/4 (admit-until-peerCount; quorum-return is the open call flagged in Task 4). **D4** → Task 7 Step 2. **D5 incoming guard** → Task 7 Step 1. **Recovery** → inherent (durable storage injectable via `storage` param; Raft handles partitions — no gameNode re-bootstrap). **Multi-host tiebreak** → out of scope, not built.
- **Open design calls deliberately left to implementation (flagged inline, each de-risked by Task 1's harness):** `gameJoin` admitted-signal + initial-config; quorum-return vs full-membership-return (D2); `MuxSeam` channel plumbing for presence vs raft (Task 6); `EphemeralMap`/`Quilter` serializer construction. These are genuine "verify against the engine" decisions, not placeholders — each names the authoritative source to confirm against.
