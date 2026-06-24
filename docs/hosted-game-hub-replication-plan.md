# Hosted-game Hub Replication — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a single-server hosted game flood each client's updates promptly to all other clients, by adding a `FullFanout` active-view policy to the gossip overlay and proving `gameHost`-over-a-`meshSeam`-hub converges — with no spoke↔spoke and no Quilter change.

**Architecture:** Factor `GossipView`'s hardcoded active-view sizing into an injectable `ActiveViewPolicy` (default = today's `RandomKRegular`; new `FullFanout` = flood all peers). Thread it through `GossipSeam`. The server wraps `meshSeam(serverId, [conn-per-client])` in `GossipSeam(FullFanout)`; clients wrap their 2-peer seam in `GossipSeam` (default). `gameHost`/`gameJoin` run on top unchanged; the hub's `GossipSeam` re-floods each client's broadcast to the others (seam-level relay), and gap-healing is Quilter's existing FullState-on-reconnect.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (virtual-time tests), kotlinx-serialization (CBOR). Modules: `kuilt-gossip`, `kuilt-test`, `kuilt-game`, `kuilt-crdt`/`kuilt-quilter` (RGA chat in the test).

**Spec:** `docs/hosted-game-hub-replication-design.md`. Feeds ADR-005 (planning sub-issue #795), epic #794. Plan + spec land as **one PR closing #795**.

## Global Constraints

- **Backward-compatible defaults:** every new parameter defaults to today's behaviour (`ActiveViewPolicy.RandomKRegular`). No existing call-site changes.
- **Virtual-time test discipline:** `runTest(StandardTestDispatcher(), timeout = 5.seconds)`; drive with bounded `advanceTimeBy`/`runCurrent`; **never** `advanceUntilIdle` (gossip/heartbeat timers re-arm). Inject the dispatcher into seams via `currentCoroutineContext()[ContinuationInterceptor]!!`.
- **Seeded RNG:** all `Random` in production paths is injected; tests pass `Random(seed)`.
- **Test style (repo):** no `test` prefix on `@Test` names; multi-assert via `assertAll` where it shortens; `@ParameterizedTest` when it materially shortens.
- **Scope:** single-server hub only. No spoke↔spoke routing, no Quilter change, no multi-hop. Escalations (routed `sendTo`, Plumtree) are out of scope.

## File Structure

- **Create** `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicy.kt` — the policy interface + `RandomKRegular`/`FullFanout` instances. One responsibility: active-view sizing.
- **Modify** `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipView.kt` — accept an `ActiveViewPolicy`, use it in `reconcile`.
- **Modify** `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt` — accept + forward the policy to `GossipView`.
- **Create** `kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicyTest.kt`.
- **Create** `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/StarHarness.kt` — `inMemoryStarOf(n)` building a hub `GossipSeam(FullFanout)` + n client `GossipSeam`s over `connectionPair` links. Companion to `MeshHarness.kt`.
- **Create** `kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/fabric/StarHarnessTest.kt`.
- **Create** `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt` — the two gating integration tests (prompt forward flow; reconnect heals).

---

### Task 1: `ActiveViewPolicy` + `FullFanout`/`RandomKRegular`

**Files:**
- Create: `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicy.kt`
- Test: `kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicyTest.kt`

**Interfaces:**
- Consumes: `recommendedActiveViewSize(Int): Int` (existing, `PartialView.kt`).
- Produces: `fun interface ActiveViewPolicy { fun activeViewSize(rosterSize: Int): Int }` with `ActiveViewPolicy.RandomKRegular` and `ActiveViewPolicy.FullFanout` (both `ActiveViewPolicy`). Tasks 2–5 depend on these names.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.gossip

import kotlin.test.Test
import kotlin.test.assertEquals

class ActiveViewPolicyTest {

    @Test
    fun fullFanoutTargetsEveryOtherPeer() {
        assertEquals(4, ActiveViewPolicy.FullFanout.activeViewSize(5))
        assertEquals(1, ActiveViewPolicy.FullFanout.activeViewSize(2))
        assertEquals(0, ActiveViewPolicy.FullFanout.activeViewSize(1))
        assertEquals(0, ActiveViewPolicy.FullFanout.activeViewSize(0))
    }

    @Test
    fun randomKRegularMatchesRecommendedSize() {
        assertEquals(recommendedActiveViewSize(20), ActiveViewPolicy.RandomKRegular.activeViewSize(20))
        assertEquals(recommendedActiveViewSize(3), ActiveViewPolicy.RandomKRegular.activeViewSize(3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-gossip:jvmTest --tests "us.tractat.kuilt.gossip.ActiveViewPolicyTest"`
Expected: FAIL — `ActiveViewPolicy` unresolved.
(Prefix non-interactive shells with `source ~/.sdkman/bin/sdkman-init.sh &&`.)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.gossip

/**
 * Decides how many **active** (eager-flood + GC-target) neighbours a peer keeps for a
 * given roster size. The active view is a subset of full membership; see [GossipView]
 * and `docs/gossip-mesh-design.md`.
 */
public fun interface ActiveViewPolicy {
    /** Target active-view size for a roster of [rosterSize] peers (including self). */
    public fun activeViewSize(rosterSize: Int): Int

    public companion object {
        /** Today's emergent partial mesh: `k = recommendedActiveViewSize(N)`. The default. */
        public val RandomKRegular: ActiveViewPolicy =
            ActiveViewPolicy { rosterSize -> recommendedActiveViewSize(rosterSize) }

        /**
         * Flood **every** other peer: active = roster minus self. The server-hub policy — the
         * hub re-floods each client's broadcast to all the others. With this policy a node
         * keeps no spares (the active view already covers everyone).
         */
        public val FullFanout: ActiveViewPolicy =
            ActiveViewPolicy { rosterSize -> (rosterSize - 1).coerceAtLeast(0) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-gossip:jvmTest --tests "us.tractat.kuilt.gossip.ActiveViewPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicy.kt \
        kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/ActiveViewPolicyTest.kt
git commit -m "feat(kuilt-gossip): ActiveViewPolicy with RandomKRegular + FullFanout"
```

---

### Task 2: Thread `ActiveViewPolicy` through `GossipView` and `GossipSeam`

**Files:**
- Modify: `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipView.kt` (constructor; `reconcile`)
- Modify: `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt` (constructor; `GossipView(...)` call)
- Test: `kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/GossipViewTest.kt` (add one case, model on existing tests in this file)

**Interfaces:**
- Consumes: `ActiveViewPolicy` (Task 1).
- Produces: `GossipView(..., activeViewPolicy: ActiveViewPolicy = ActiveViewPolicy.RandomKRegular)` and `GossipSeam(..., activeViewPolicy: ActiveViewPolicy = ActiveViewPolicy.RandomKRegular)`. Tasks 3–5 pass `ActiveViewPolicy.FullFanout` to `GossipSeam` on the hub.

- [ ] **Step 1: Write the failing test** (append to `GossipViewTest.kt`; reuse that file's existing setup helpers for `roster`/`seam`/`rawIncoming`/`scope`/seeded `random`/`clock`)

```kotlin
    @Test
    fun fullFanoutSelectsEveryOtherPeerAsActive() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val self = PeerId("hub")
        val roster = MutableStateFlow(setOf(self, PeerId("a"), PeerId("b"), PeerId("c")))
        val view = GossipView(
            selfId = self,
            seam = FakeSeam(self),                         // existing test fake in this module
            roster = roster,
            rawIncoming = MutableSharedFlow<Swatch>().asSharedFlow(),
            random = Random(1L),
            clock = { Instant.fromEpochMilliseconds(0) },
            activeViewPolicy = ActiveViewPolicy.FullFanout,
        )
        view.start(backgroundScope)

        // Drive past the recompute jitter window (DEFAULT_JITTER max = 200ms).
        advanceTimeBy(250)
        runCurrent()

        assertEquals(setOf(PeerId("a"), PeerId("b"), PeerId("c")), view.active.value)
        assertEquals(emptyList(), view.spares.value)
    }
```

> Note: match the exact fake-seam / setup the other tests in `GossipViewTest.kt` already use (e.g. `FakeSeam`, `connectionPair`, or the file's own helper). If they pass a `dispatcher`, thread `currentCoroutineContext()[ContinuationInterceptor]!!`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-gossip:jvmTest --tests "us.tractat.kuilt.gossip.GossipViewTest"`
Expected: FAIL — `GossipView` has no `activeViewPolicy` parameter.

- [ ] **Step 3a: Add the parameter to `GossipView`**

In `GossipView.kt`, add to the primary constructor (after `jitter`, before the closing paren):

```kotlin
    private val activeViewPolicy: ActiveViewPolicy = ActiveViewPolicy.RandomKRegular,
```

In `reconcile(...)`, replace:

```kotlin
        val k = recommendedActiveViewSize(currentRoster.size)
```

with:

```kotlin
        val k = activeViewPolicy.activeViewSize(currentRoster.size)
```

- [ ] **Step 3b: Forward it from `GossipSeam`**

In `GossipSeam.kt`, add to the primary constructor (after `initialTtl`):

```kotlin
    activeViewPolicy: ActiveViewPolicy = ActiveViewPolicy.RandomKRegular,
```

and pass it into the internal `GossipView(...)` construction:

```kotlin
            activeViewPolicy = activeViewPolicy,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-gossip:jvmTest --tests "us.tractat.kuilt.gossip.GossipViewTest"`
Expected: PASS. Then run the whole module to prove backward-compatibility:
`./gradlew :kuilt-gossip:jvmTest` — Expected: all existing tests still PASS (default policy unchanged).

- [ ] **Step 5: Commit**

```bash
git add kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipView.kt \
        kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt \
        kuilt-gossip/src/commonTest/kotlin/us/tractat/kuilt/gossip/GossipViewTest.kt
git commit -m "feat(kuilt-gossip): inject ActiveViewPolicy into GossipView/GossipSeam"
```

---

### Task 3: `inMemoryStarOf(n)` test harness

**Files:**
- Create: `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/StarHarness.kt`
- Test: `kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/fabric/StarHarnessTest.kt`

**Interfaces:**
- Consumes: `connectionPair()` (`ConnectionPair.kt`), `meshSeam(...)` (`MeshSeam.kt`), `GossipSeam`, `ActiveViewPolicy.FullFanout`.
- Produces: `class Star(val hub: GossipSeam, val clients: List<GossipSeam>)` and
  `suspend fun CoroutineScope.inMemoryStarOf(n: Int, hubId: PeerId = PeerId("hub"), random: Random = Random(0L)): Star`.
  The returned seams are already `start`ed on the receiver scope. Tasks 4–5 consume this.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class StarHarnessTest {

    @Test
    fun clientBroadcastReachesEveryOtherClientViaHub() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val star = backgroundScope.inMemoryStarOf(n = 3)
        // Let FullFanout reconcile on the hub (past the jitter window).
        advanceTimeBy(300); runCurrent()

        val received = mutableListOf<String>()
        val collectJob = kotlinx.coroutines.launch {
            star.clients[1].incoming.take(1).toList().forEach { received += it.decodeToString() }
        }

        star.clients[0].broadcast("hello".encodeToByteArray())
        advanceTimeBy(300); runCurrent()
        collectJob.join()

        assertEquals(listOf("hello"), received)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-test:jvmTest --tests "us.tractat.kuilt.test.fabric.StarHarnessTest"`
Expected: FAIL — `inMemoryStarOf` unresolved.

- [ ] **Step 3: Write minimal implementation** (model the connection wiring on `MeshHarness.inMemoryMeshOfSize`)

```kotlin
package us.tractat.kuilt.test.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.fabric.meshSeam
import us.tractat.kuilt.gossip.ActiveViewPolicy
import us.tractat.kuilt.gossip.GossipSeam
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Instant

/** A started in-memory star: a [hub] GossipSeam (FullFanout) and n client GossipSeams. */
public class Star(public val hub: GossipSeam, public val clients: List<GossipSeam>)

/**
 * Build a star of [n] clients around one hub over [connectionPair] links, wrap each end in a
 * [GossipSeam] (hub = [ActiveViewPolicy.FullFanout], clients = default), and [GossipSeam.start]
 * them on the receiver scope. The hub holds one link per client; each client holds one link to
 * the hub. Per-peer seeded RNG: client i derives `Random(random.nextLong())`.
 */
public suspend fun CoroutineScope.inMemoryStarOf(
    n: Int,
    hubId: PeerId = PeerId("hub"),
    random: Random = Random(0L),
): Star {
    val dispatcher = currentCoroutineContext()[ContinuationInterceptor]!!
    val clock = { Instant.fromEpochMilliseconds(0) }

    val hubConns = ArrayList<us.tractat.kuilt.core.fabric.Connection>(n)
    val clientBases = ArrayList<Pair<PeerId, us.tractat.kuilt.core.fabric.Connection>>(n)
    for (i in 0 until n) {
        val (hubEnd, clientEnd) = connectionPair()
        hubConns += hubEnd
        clientBases += PeerId("client-$i") to clientEnd
    }

    // Hub base mesh holds all client links; each client base mesh holds its single hub link.
    val hubBase = meshSeam(selfId = hubId, connections = hubConns, dispatcher = dispatcher)
    val clientBaseSeams = clientBases.map { (id, conn) ->
        id to meshSeam(selfId = id, connections = listOf(conn), dispatcher = dispatcher)
    }

    val hub = GossipSeam(
        base = hubBase,
        random = Random(random.nextLong()),
        clock = clock,
        activeViewPolicy = ActiveViewPolicy.FullFanout,
    ).also { it.start(this) }

    val clients = clientBaseSeams.map { (_, base) ->
        GossipSeam(base = base, random = Random(random.nextLong()), clock = clock)
            .also { it.start(this) }
    }
    return Star(hub, clients)
}
```

> If `GossipSeam`'s constructor requires more non-defaulted args than shown, supply the same defaults `MeshHarness`/`GossipSeamTest` use; do not change `GossipSeam`'s signature here.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-test:jvmTest --tests "us.tractat.kuilt.test.fabric.StarHarnessTest"`
Expected: PASS — `client[0]`'s broadcast reaches the hub, which re-floods (FullFanout) to `client[1]`.

- [ ] **Step 5: Commit**

```bash
git add kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/fabric/StarHarness.kt \
        kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/fabric/StarHarnessTest.kt
git commit -m "test(kuilt-test): inMemoryStarOf harness (hub FullFanout + client GossipSeams)"
```

---

### Task 4: Gating test — prompt, in-order forward flow under `gameHost`

**Files:**
- Create: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt`

**Interfaces:**
- Consumes: `inMemoryStarOf` (Task 3); `gameHost`/`gameJoin` (existing); `fastRaftConfig`/`seats`/`awaitEitherLeader` (`GameBootstrapHarness.kt`, `HarnessSmokeTest.kt`); `GameSession.appChannel(name)`; `Quilter` over an RGA (`Rga.empty()`, `Rga.insertAt`, `Rga.toList`, `Rga.wireSerializer`); model the RGA↔Quilter wiring on `kuilt-quilter` `RgaGcCoordinator3PeerIntegrationTest`.
- Produces: nothing consumed downstream (terminal test).

**Behaviour under test:** host over the star hub + two joiners; a `Quilter<Rga<String>>` chat channel on `session.appChannel("chat")` per participant; client A appends three RGA entries; assert B and C converge to the same ordered list within a bounded virtual-time window (not the anti-entropy interval).

- [ ] **Step 1: Write the failing test**

```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.game

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.Rga
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.test.fabric.inMemoryStarOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HostedHubReplicationTest {

    @Test
    fun clientChatRgaReachesAllOtherClientsPromptly() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        val star = backgroundScope.inMemoryStarOf(n = 3)
        advanceTimeBy(300); runCurrent()

        // Host on the hub seam; join on each client seam. Launch concurrently (admit loop ↔ joiners).
        val hostD = async { backgroundScope.gameHost(star.hub, peerCount = 4, raftConfig = fastRaftConfig(seed = 1L)) }
        val joinDs = star.clients.mapIndexed { i, c ->
            async { backgroundScope.gameJoin(c, raftConfig = fastRaftConfig(seed = (i + 2).toLong())) }
        }
        advanceTimeBy(2000); runCurrent()
        val hostSession = hostD.await()
        val clientSessions = joinDs.map { it.await() }

        // One RGA chat replicator per participant over the shared appChannel("chat").
        fun chatQuilter(s: GameSession) = Quilter(
            s.appChannel("chat"), Rga.empty<String>(), Rga.wireSerializer(String.serializer()),
            backgroundScope,
        )
        val hostChat = chatQuilter(hostSession)
        val clientChats = clientSessions.map { chatQuilter(it) }
        advanceTimeBy(300); runCurrent()

        // Client 0 appends three messages.
        repeat(3) { k ->
            clientChats[0].apply { mutateAppend("m$k") }   // helper below — appends to the RGA tail
        }
        // Bounded window — must converge well under the anti-entropy interval (default 1 min).
        advanceTimeBy(2000); runCurrent()

        val expected = listOf("m0", "m1", "m2")
        assertEquals(expected, hostChat.state.value.toList())
        assertEquals(expected, clientChats[1].state.value.toList())
        assertEquals(expected, clientChats[2].state.value.toList())
    }
}
```

> `mutateAppend` stands for "apply an `Rga.insertAt(size, value)` op through the Quilter." Implement it exactly as `RgaGcCoordinator3PeerIntegrationTest` mutates an RGA through a `Quilter` (it shows the `insertAt → Patch → quilter.apply` idiom and the correct `Quilter` constructor arity). Copy that idiom verbatim rather than inventing one.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "us.tractat.kuilt.game.HostedHubReplicationTest"`
Expected: FAIL (compile error on `mutateAppend`, or assertion if the wiring is incomplete).

- [ ] **Step 3: Make it pass** — replace `mutateAppend("…")` with the exact RGA-through-Quilter mutation idiom from `RgaGcCoordinator3PeerIntegrationTest`, and adjust `chatQuilter`'s constructor arguments to match that test's `Quilter(...)` arity (serializer wrapping, `expectVirtualTime = true` if it sets it). Tune the `advanceTimeBy` budgets only if bootstrap needs longer — keep the convergence window far below 60 s to prove promptness.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "us.tractat.kuilt.game.HostedHubReplicationTest"`
Expected: PASS — all three RGAs equal `[m0, m1, m2]`, converged within the bounded window.

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt
git commit -m "test(kuilt-game): prompt in-order chat forward-flow over the hub star"
```

---

### Task 5: Gating test — reconnect heals via FullState

**Files:**
- Modify: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt` (add a second `@Test`)

**Interfaces:**
- Consumes: same as Task 4, plus the connection-drop mechanism `connectionPair`/`meshSeam` expose. To drop a client, close its base seam link and admit a fresh one — model on how `MeshSeamTest`/`GossipSeam` reconnect tests sever and re-`addLink` a connection (`Mesh.addLink`, `Seam.close`).
- Produces: nothing downstream.

**Behaviour under test:** with the star + host established, client 1 drops; the host appends chat while client 1 is away; client 1 reconnects (fresh link admitted to the hub mesh, new `gameJoin`); assert client 1 converges to the full chat via Quilter's FullState-on-(re)contact — without relying on the anti-entropy interval.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun reconnectingClientConvergesViaFullState() = runTest(StandardTestDispatcher(), timeout = 5.seconds) {
        // Arrange the star + host + two joiners + chat exactly as in
        // clientChatRgaReachesAllOtherClientsPromptly (extract a private `setupStarGame(n)`
        // helper in this file to avoid duplication — DRY).
        val game = setupStarGame(n = 3)

        // Client 1 has joined and seen an initial message.
        game.clientChats[0].mutateAppend("before")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("before"), game.clientChats[1].state.value.toList())

        // Drop client 1's link to the hub.
        game.dropClient(1)
        advanceTimeBy(1000); runCurrent()

        // Host-side activity while client 1 is away.
        game.clientChats[0].mutateAppend("during")
        advanceTimeBy(1000); runCurrent()

        // Client 1 reconnects: fresh link admitted to the hub mesh + fresh gameJoin + chat Quilter.
        val rejoined = game.reconnectClient(1)
        advanceTimeBy(2000); runCurrent()

        assertEquals(listOf("before", "during"), rejoined.chat.state.value.toList())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-game:jvmTest --tests "us.tractat.kuilt.game.HostedHubReplicationTest.reconnectingClientConvergesViaFullState"`
Expected: FAIL — `setupStarGame`/`dropClient`/`reconnectClient` unresolved.

- [ ] **Step 3: Implement the helpers in the test file** — `setupStarGame(n)` returns a small holder (`hub`, `clients`, `clientChats`, the raw `connectionPair` ends, the hub `Mesh`); `dropClient(i)` calls `close()` on client i's base seam link; `reconnectClient(i)` creates a fresh `connectionPair`, `hubMesh.addLink(newHubEnd)`, builds a new client `GossipSeam` + `gameJoin` + chat `Quilter`. Model the sever/re-admit calls on the existing `MeshSeam`/`GossipSeam` reconnect test in `kuilt-core`/`kuilt-gossip`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-game:jvmTest --tests "us.tractat.kuilt.game.HostedHubReplicationTest"`
Expected: PASS — the reconnecting client's RGA equals `[before, during]`, healed by FullState-on-reconnect (no anti-entropy wait).

- [ ] **Step 5: Commit**

```bash
git add kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/HostedHubReplicationTest.kt
git commit -m "test(kuilt-game): reconnecting client heals via FullState"
```

---

### Task 6: Land spec + plan as the ADR-005 PR closing #795

- [ ] **Step 1: Full module builds**

Run: `./gradlew :kuilt-gossip:jvmTest :kuilt-test:jvmTest :kuilt-game:jvmTest`
Expected: all PASS. (CI runs the full KMP matrix; JVM is the fast local gate.)

- [ ] **Step 2: Open the PR** (branch `overlay-faithful-seam-spec`)

```bash
gh pr create --repo tractat-us/kuilt --base main \
  --title "Hosted-game hub replication: FullFanout active-view policy + gameHost-over-star (ADR-005)" \
  --body "$(cat <<'EOF'
🤖 Generated with [Claude Code](https://claude.com/claude-code)

Spec + plan + implementation for the single-server hub baseline (epic #794).
FullFanout active-view policy; gameHost over a meshSeam hub; prompt in-order
forward flow + FullState-on-reconnect; no spoke↔spoke, no Quilter change.
Heterogeneous P2P (regimes 2–3) and escalations (routed sendTo, Plumtree)
are documented and deferred.

Closes #795. Part of #794.
EOF
)"
```

- [ ] **Step 3:** Confirm CI green and `closes #795` is recognised; verify #795 closes on merge, #794 stays open.

## Self-Review

- **Spec coverage:** Two-transport/no-classes → documented (no code; `kuilt-deal` owns secrecy). Prompt forward flow → Tasks 1–4. Reconnect→FullState → Task 5. Hub-centric/no-spoke↔spoke → enforced by *not* adding routing (default policies + FullFanout only). FullFanout dependency → Tasks 1–2. Regime-1 scope / regimes 2–3 deferred → spec only, no code. Escalations → spec only. **No gaps.**
- **Placeholder scan:** `mutateAppend`, `setupStarGame`, `dropClient`, `reconnectClient` are named, scoped, and each pinned to a concrete exemplar test to copy the idiom from — not "TODO". The only intentional deferrals to an exemplar are the RGA-through-Quilter mutation idiom and the mesh sever/re-admit idiom, because their exact arity lives in `kuilt-quilter`/`kuilt-core` tests the implementer must match rather than guess.
- **Type consistency:** `ActiveViewPolicy` / `RandomKRegular` / `FullFanout` / `activeViewPolicy` param / `inMemoryStarOf` / `Star(hub, clients)` are used identically across Tasks 1–5.
