# Uniform Game/Room Presence — Implementation Plan (Track 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface a `Room`'s presence (`MembershipEvent` + `Member.liveness`) through a `RoomGameSession` subtype so a game speaks the same presence vocabulary as a room, move the room↔game composition into kuilt (retiring the app's hand-wired adapter), and fix an ungated `ResumeAck` that can duplicate `Resumed`.

**Architecture:** Reuse the existing `SeamRoom` presence surface — no new presence type. `kuilt-game` gains `api(:kuilt-session)` (already a transitive dep via `:kuilt-cluster`; this promotes it to ABI). A new `gameOverRoom(room, …): RoomGameSession` runs the Raft game over `room.channel(…)` and returns a subtype whose `presence == room.events` / `roster == room.roster`, owning the room's lifecycle. Backing correctness fixes: ungated `ResumeAck`, and backpressure isolation so game traffic can't stall heartbeat fan-out.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kuilt `SeamRoom`/`RoomChannelSeam`/`GameSession`/`GameNode`, `HeartbeatPartitionDetector`.

**Spec:** `docs/superpowers/specs/2026-07-22-uniform-game-room-presence-design.md` (PR #1622).

## Global Constraints

- Source SDKMAN + JDK 21: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.
- `explicitApi()` enforced — every public decl explicit; new public types `public`.
- Run the **full module build** before declaring done (`./gradlew :<module>:build detektAll --rerun-tasks`) — `jvmTest` hides Android/native variant breaks. Consensus-*behavior* changes also run `:examples:test`.
- Coroutine tests: `StandardTestDispatcher`, bounded `advanceTimeBy`, injected clock; no production dispatchers in tests.
- No `!!` in production (detekt type-resolution job fails on it).
- **This is NOT the #1618 fix** — commits use "part of #1618"; the #1618 close is Track 2.

---

## Task 1: Gate + latch `ResumeAck` (DIAG-4) — independent, ship first

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`handleResumeAck`, ~line 1156-1163 region; `handleAdmitFrame`'s `ResumeAck` branch)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomResumeTest.kt`

**Interfaces:**
- Consumes: `hostPeerId` (identified host), the `resumeMachine` pending-flight slot, `lock`.
- Produces: `handleResumeAck(sender)` honors the ack **only** from `hostPeerId`, and emits `Resumed` **at most once** per resume flight.

- [ ] **Step 1: Write the failing test — a non-host `ResumeAck` is ignored; a duplicate yields one `Resumed`.**

```kotlin
@Test
fun `ResumeAck from a non-host is ignored and duplicates are idempotent`() = runTest {
    // Build a joiner SeamRoom whose host is identified as hostId; a stray ResumeAck from
    // another admitted peer must NOT emit Resumed, and two acks from the host emit once.
    val events = mutableListOf<MembershipEvent>()
    val job = launch { room.events.filterIsInstance<MembershipEvent.Resumed>().collect { events += it } }
    room.injectAdmitFrame(sender = otherPeer, AdmitMessage.ResumeAck)   // non-host
    room.injectAdmitFrame(sender = hostId, AdmitMessage.ResumeAck)      // host
    room.injectAdmitFrame(sender = hostId, AdmitMessage.ResumeAck)      // duplicate
    advanceUntilIdleBounded()
    assertEquals(1, events.size, "exactly one Resumed, only from the host, dedup on duplicate")
    job.cancel()
}
```

- [ ] **Step 2: Run — expect FAIL** (`timeout 120 ./gradlew :kuilt-session:jvmTest --tests "*RoomResumeTest*"`). Expected: 2 or 3 Resumed emitted (ungated/unlatched).
- [ ] **Step 3: Implement.** In `handleResumeAck`, mirror the `Farewell` host-authoritative gate: `if (sender != hostPeerId) return` under `lock`; take the pending flight and only emit `Resumed(selfId)` when a flight actually resolved (the latch — a second ack finds no pending flight → no event).
- [ ] **Step 4: Run — expect PASS.** Full guard: `./gradlew :kuilt-session:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(session): gate ResumeAck on host + dedup Resumed (part of #1618)`

---

## Task 2: Promote `kuilt-game → api(:kuilt-session)` + channel-tag constants

**Files:**
- Modify: `kuilt-game/build.gradle.kts` (add `api(project(":kuilt-session"))` in commonMain deps)
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt` (surface the existing Raft/app channel tag constants as `internal const` if not already, for reuse by `gameOverRoom`)

**Interfaces:**
- Consumes: existing `:kuilt-cluster → api(:kuilt-session)` transitive edge (session types already on classpath).
- Produces: `Room`/`MembershipEvent`/`Member` available on `kuilt-game`'s public ABI; `internal const val RAFT_CHANNEL_TAG` / `APP_CHANNEL_TAG` reusable in the room-backed bootstrap.

- [ ] **Step 1:** Add `api(project(":kuilt-session"))` to `kuilt-game/build.gradle.kts` commonMain. Confirm no cycle: `grep kuilt-game kuilt-session/build.gradle.kts` → empty.
- [ ] **Step 2:** Verify it compiles across variants: `./gradlew :kuilt-game:build --rerun-tasks`. Expected: EXECUTED, green (session types resolve on JVM/Android/native/wasm).
- [ ] **Step 3: Commit** — `build(game): promote kuilt-session to api for room-backed presence (part of #1618)`

---

## Task 3: `RoomGameSession` subtype + `gameOverRoom` bootstrap

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/RoomGameSession.kt`
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameSession.kt` (make it `open` so a subtype can extend it; no `presence` member added to the base)
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RoomGamePresenceTest.kt`

**Interfaces:**
- Consumes: `Room` (kuilt-session), `RaftNode`, `NamedMux`, `GamePresence`, `RAFT_CHANNEL_TAG` (Task 2), the existing `gameNode`/`gameHost` internals for wiring Raft over a seam.
- Produces:
  - `public class RoomGameSession : GameSession { val presence: Flow<MembershipEvent>; val roster: StateFlow<Set<Member>> }`
  - `public suspend fun CoroutineScope.gameOverRoom(room: Room, peerCount: Int, livenessConfig: HeartbeatConfig? = null, clock: () -> Instant, …): RoomGameSession`

- [ ] **Step 1: Write the failing test — a `gameOverRoom` session's `presence` IS the room's events.** Use an `InMemoryLoom` mesh + `FaultyLoom` (reuse `MeshRoomPartitionTest` harness on main), adopt a Room, run `gameOverRoom`, drop a member, assert `RoomGameSession.presence` emits `Partitioned`/`WindowOpened` for that member and that `session.presence` and `room.events` deliver the same event.

```kotlin
@Test
fun `gameOverRoom presence surfaces the backing room's partition events`() = runTest {
    val (hostRoom, faulty, victimId) = adopt3PeerMeshRoom(fastHeartbeat, clock, ::tick)
    val session = gameOverRoom(hostRoom, peerCount = 3, livenessConfig = fastHeartbeat, clock = clock)
    val partitioned = async { session.presence.filterIsInstance<MembershipEvent.Partitioned>().first() }
    faulty.linkFor(victimId).partition(Direction.Both)
    repeat(5) { tick() }
    assertEquals(victimId, partitioned.await().peerId)
    session.close()
}
```

- [ ] **Step 2: Run — expect FAIL** (`gameOverRoom`/`RoomGameSession` don't exist).
- [ ] **Step 3: Implement.** `GameSession` → `open`. `RoomGameSession(node, room, appMux, lobby) : GameSession(node, room.channel(RAFT_CHANNEL_TAG), appMux, lobby)` with `presence get() = room.events`, `roster get() = room.roster`. `gameOverRoom` runs the same Raft bootstrap `gameNode`/`gameHost` uses but sources the seam from `room.channel(RAFT_CHANNEL_TAG)`, then constructs `RoomGameSession`. (Lifecycle/backpressure land in Tasks 4/5.)
- [ ] **Step 4: Run — expect PASS.** `./gradlew :kuilt-game:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `feat(game): gameOverRoom + RoomGameSession surface the room's presence (part of #1618)`

---

## Task 4: Ownership & lifecycle — `close()` tears down node then room; watch terminal

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/RoomGameSession.kt`
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RoomGameLifecycleTest.kt`

**Interfaces:**
- Consumes: `Room.leave(LeaveReason)`, `GameSession.close()`, `room.events` terminal (`HostLost`).
- Produces: `RoomGameSession.close()` closes the Raft node then `room.leave(Normal)` (idempotent/double-close-safe); `gameOverRoom` launches a watcher that closes the session when the room goes terminal.

- [ ] **Step 1: Write the failing test — `close()` leaves the room (channel-view close is a no-op).** Assert after `session.close()` the backing `room` is terminal (`room.roster` empties / a subsequent `room.broadcast` no-ops / seam closed), and that calling `close()` twice does not throw.
- [ ] **Step 2: Run — expect FAIL** (base `close()` closes only the channel view → room still live).
- [ ] **Step 3: Implement.** Override `close(reason)`: `super.close(reason)` (node) then `room.leave(reason.toLeaveReason())`. In `gameOverRoom`, `scope.launch { room.events.filterIsInstance<HostLost>().first(); this@RoomGameSession.close() }` (guard against double-close via the leave latch). KDoc the **single-ownership contract**: caller must not `room.leave()` directly.
- [ ] **Step 4: Run — expect PASS.** `./gradlew :kuilt-game:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(game): RoomGameSession owns the room lifecycle (close tears both) (part of #1618)`

---

## Task 5: Backpressure isolation — game traffic must not stall heartbeat fan-out

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (the `rawIncoming` fan-out / `RoomChannelSeam` delivery path) and/or `RoomChannel.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ChannelBackpressureTest.kt`

**Interfaces:**
- Consumes: `rawIncoming` (`MutableSharedFlow`, cap 256, SUSPEND), the per-peer detector subscription, `RoomChannelSeam.incoming`.
- Produces: channel-frame delivery to `RoomChannelSeam` subscribers is decoupled from the detector/heartbeat fan-out — a slow channel consumer cannot suspend the room main loop or delay heartbeat frames.

- [ ] **Step 1: Write the failing test — a stalled game-channel consumer does not delay a co-peer's `Partitioned`.** 3-peer mesh; subscribe to a room `channel("game")` with a deliberately-blocked collector; drop peer B; assert the host still emits `Partitioned(B)` within the heartbeat window despite the channel backlog.
- [ ] **Step 2: Run — expect FAIL** (a blocked channel consumer backpressures `rawIncoming` → detector fan-out stalls → `Partitioned` delayed/absent).
- [ ] **Step 3: Implement.** Give channel-frame delivery its own bounded staging (per-`RoomChannelSeam` drain, or `DROP_OLDEST` for channel frames) so heartbeat/admit frames on the detector path are never blocked by an app consumer. Heartbeat frames stay on the un-throttled path. Preserve ADR-034 single-collection (main loop remains the sole `seam.incoming` collector).
- [ ] **Step 4: Run — expect PASS**, and re-run the full `:kuilt-session` suite to ensure no channel-ordering regressions. `./gradlew :kuilt-session:build :examples:test --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(session): isolate channel-frame backpressure from heartbeat fan-out (part of #1618)`

---

## Task 6: Semantics doc, samples, and the no-Room compile guarantee

**Files:**
- Modify: KDoc on `RoomGameSession.presence` (link-liveness semantics, §7)
- Create: `kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/RoomGamePresenceSamples.kt` (a `@sample` for `presence`)
- Modify: `docs/agent-cookbook.md` (add a "presence from a game" row pointing at `RoomGameSession.presence`)
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/NoRoomBootstrapTest.kt`

**Interfaces:**
- Consumes: `gameNode`/`gameHost` (no-Room), `RoomGameSession`.
- Produces: KDoc stating presence = link liveness (a ~3 s resume can be a legitimate link heal; human "seated/away" is app-layer); a compiled sample; a test asserting the raw bootstraps return a base `GameSession` with **no** `presence` member.

- [ ] **Step 1:** Write the KDoc + `@sample` (samples compile as `commonTest`, so a broken sample breaks the build — load-bearing).
- [ ] **Step 2:** Write `NoRoomBootstrapTest`: assert `gameNode(...)` returns `GameSession` and that `RoomGameSession` is the only type carrying `presence` (compile-time — reference `(session as? RoomGameSession)?.presence`, document that the base has none).
- [ ] **Step 3:** Add the cookbook row. Run `./gradlew :kuilt-game:build --rerun-tasks` (samples compile).
- [ ] **Step 4: Commit** — `docs(game): presence=link-liveness semantics + sample + cookbook row (part of #1618)`

---

## Task 7: Consumer migration note (fireworks — separate repo, doc only here)

**Files:**
- Modify: the PR description / a `docs/` migration note in kuilt describing how a consumer replaces a hand-wired `room.events` adapter with `RoomGameSession.presence`.

- [ ] **Step 1:** Write a short migration note: "Build the game with `gameOverRoom(adoptedRoom, …)`; read `session.presence` instead of collecting `room.events` in an adapter; `Resumed` now fires only on a real `ResumeAck`." Note it retires `EmbeddedRoomGameHost` on the app side (out of kuilt scope).
- [ ] **Step 2: Commit** — `docs: RoomGameSession consumer migration note (part of #1618)`

---

## Self-review

- **Spec coverage:** §1 vocabulary (Tasks 3/6), §2 dep (Task 2), §3 subtype (Task 3), §4 lifecycle (Task 4), §5 backpressure (Task 5), §6 ResumeAck (Task 1), §7 semantics (Task 6), §8 consumer migration (Task 7). All sections mapped.
- **Ordering:** Task 1 (ResumeAck) is independent and ships first (smallest, standalone value). Task 2 (dep) precedes Tasks 3-6. Task 5 (backpressure) is the riskiest — isolate its review.
- **Type consistency:** `RoomGameSession`, `gameOverRoom`, `presence`, `roster`, `RAFT_CHANNEL_TAG` used consistently across Tasks 2-6.
- **Not #1618's fix:** every commit "part of #1618"; the close is Track 2's Phase 3.
