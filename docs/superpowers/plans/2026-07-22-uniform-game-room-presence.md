# Uniform Game/Room Presence — Implementation Plan (Track 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. **Rebase onto `origin/main` first** — `MeshRoomPartitionTest` + `FaultyLoom` merged with #1619 and are on `origin/main`; do NOT assume PR #1621's `NwMeshRoomPartitionTest` is available (still open).

**Goal:** Surface a `Room`'s presence (`MembershipEvent` + `Member.liveness`) through a `RoomGameSession` subtype + `gameOverRoom` bootstrap so a game speaks the same presence vocabulary as a room; fix an ungated `ResumeAck` that can duplicate `Resumed`; and isolate channel backpressure so app traffic can't starve heartbeat delivery.

**Architecture:** Reuse the existing `SeamRoom` presence surface — no new presence type. `kuilt-game` promotes its already-transitive `kuilt-session` dep to `api`. `gameOverRoom(room, …)` runs the **roster-given** Raft bootstrap (`gameNode(voterIds = room roster)`) over a single named Room channel view, and returns a `RoomGameSession : GameSession` exposing `presence == room.events` / `roster == room.roster`, owning the room's lifecycle.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kuilt `SeamRoom`/`RoomChannelSeam`/`GameSession`/`GameNode`/`HeartbeatPartitionDetector`.

**Spec:** `docs/superpowers/specs/2026-07-22-uniform-game-room-presence-design.md` (PR #1622). **This is NOT the #1618 fix** (that is Track 2); commits use "part of #1618".

## Global Constraints

- `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first.
- `explicitApi()`; run the **full module build** before done (`./gradlew :<module>:build detektAll --rerun-tasks`) — `jvmTest` hides Android/native breaks. Behavior changes also run `:examples:test`.
- Coroutine tests: `StandardTestDispatcher`, bounded `advanceTimeBy`, injected clock; no production dispatchers. No `!!` in prod (detekt type-resolution fails).
- Drive admit frames through a **second peer's seam** (as `RoomResumeTest` does) — there is no `injectAdmitFrame` helper.
- `FaultySeam.partition(Direction.Both)` exists (shorthand for `setFaultProfile(DropAll(Both))`); reach links via `faulty.links.first { it.selfId == id }`.

---

## Task 1: Gate + latch `ResumeAck` (independent, ship first)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`handleResumeAck` ~1156-1163; mirror the `Farewell` gate at ~1207-1213)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomResumeTest.kt`

**Interfaces:**
- Consumes: `hostPeerId`, `resumeMachine.takePendingFlight()` (exists, `JoinerResumeMachine.kt:211`), `lock`.
- Produces: `handleResumeAck(sender)` honors the ack **only** from the identified `hostPeerId` (drops when host unidentified, like `Farewell`), puts `updateMemberLiveness` behind the gate, and emits `Resumed` **only when a pending flight actually resolved** (duplicate ack → no event).

- [ ] **Step 1: Write the failing test** — a non-host `ResumeAck` is ignored; a duplicate host ack yields exactly one `Resumed`. Drive frames via a second peer's seam (copy the setup in `RoomResumeTest.kt:60-100`); collect `room.events.filterIsInstance<MembershipEvent.Resumed>()` into a list; assert size 1 after {non-host ack, host ack, duplicate host ack}.
- [ ] **Step 2: Run — expect FAIL** (`timeout 120 ./gradlew :kuilt-session:jvmTest --tests "*RoomResumeTest*"`); currently 2–3 Resumed.
- [ ] **Step 3: Implement.** Mirror `handleFarewell`'s gate: under `lock`, `val host = hostPeerId; if (host == null || sender != host) return`. Then `val flight = resumeMachine?.takePendingFlight()`; only if `flight != null` call `updateMemberLiveness(sender, Connected)` and `_events.tryEmit(Resumed(selfId))`; `flight.complete(Success)`.
- [ ] **Step 4: Run — expect PASS.** `./gradlew :kuilt-session:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(session): gate ResumeAck on host + dedup Resumed (part of #1618)`

---

## Task 2: Rename the base `presence` field + promote the `kuilt-session` dep + open the class

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameSession.kt` (rename the existing `internal val presence: GamePresence?` → `internal val lobbyPresence: GamePresence?` at all usages; mark the class `open`; mark `close()` `open`)
- Modify: `kuilt-game/build.gradle.kts` (promote to `api(project(":kuilt-session"))`)

**Interfaces:**
- Consumes: existing transitive `kuilt-cluster → api(:kuilt-session)` (session types already compile in kuilt-game; verified).
- Produces: `GameSession` is `open`, its lobby field is `lobbyPresence` (freeing the name `presence` for the subtype), and `Room`/`MembershipEvent`/`Member` are on kuilt-game's public ABI.

- [ ] **Step 1:** Rename `presence` → `lobbyPresence` in `GameSession.kt` and every internal reference (grep `\.presence` within `kuilt-game`). It is `internal`, so no ABI break.
- [ ] **Step 2:** Mark `class GameSession` `open` and `fun close(...)` `open`.
- [ ] **Step 3:** Add `api(project(":kuilt-session"))` to `kuilt-game/build.gradle.kts` commonMain. Verify acyclic: `grep kuilt-game kuilt-session/build.gradle.kts` → empty.
- [ ] **Step 4:** `./gradlew :kuilt-game:build --rerun-tasks` — green across variants.
- [ ] **Step 5: Commit** — `refactor(game): free the presence name + open GameSession + api(:kuilt-session) (part of #1618)`

---

## Task 3: `RoomGameSession` subtype + `gameOverRoom` (roster-given bootstrap over one Room channel)

**Files:**
- Create: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/RoomGameSession.kt`
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt` (extract the shared bootstrap so both `gameNode` and `gameOverRoom` reuse it; add `internal const val GAME_ROOM_CHANNEL = "kuilt.game"`)
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RoomGamePresenceTest.kt`

**Interfaces:**
- Consumes: `Room` (kuilt-session), `Room.channel(String): Seam` (hashed 2-byte sub-id; the game's internal MuxSeam byte-tags nest *inside* this one channel view — no collision, ADR-034-safe because `RoomChannelSeam.incoming` is a SharedFlow view, not a second `seam.incoming` collector), `room.roster` → voter ids, the existing `gameNode` internals.
- Produces:
  - `public class RoomGameSession : GameSession { val presence: Flow<MembershipEvent>; val roster: StateFlow<Set<Member>> }`
  - `public suspend fun CoroutineScope.gameOverRoom(room: Room, clock: () -> Instant, /* raftConfig, identity, placement as gameNode */): RoomGameSession`
  - Note: **no `livenessConfig` param** — the adopted room's detectors were fixed at `SeamRoomFactory` construction and cannot be re-tuned here (Optional≠tuning). The room already runs them.

- [ ] **Step 1: Write the failing test — a `gameOverRoom` session's `presence` IS the room's events.** Reuse the `MeshRoomPartitionTest` harness (`FaultyLoom` 3-peer mesh + adopt on `origin/main`): adopt a host Room, `gameOverRoom(hostRoom, clock)`, drop a member via `faulty.links.first{it.selfId==victim}.partition(Both)`, assert `session.presence.filterIsInstance<Partitioned>().first().peerId == victim`.

```kotlin
@Test
fun `gameOverRoom presence surfaces the backing room's partition events`() = runTest(timeout = 5.seconds) {
    val h = adopt3PeerMeshRoom(fastHeartbeat, clock, ::tick)     // host Room + FaultyLoom
    val session = gameOverRoom(h.hostRoom, clock = clock)
    val partitioned = async { session.presence.filterIsInstance<MembershipEvent.Partitioned>().first() }
    h.faulty.links.first { it.selfId == h.victimId }.partition(Direction.Both)
    repeat(5) { tick() }
    assertEquals(h.victimId, partitioned.await().peerId)
    session.close()
}
```

- [ ] **Step 2: Run — expect FAIL** (`gameOverRoom`/`RoomGameSession` don't exist).
- [ ] **Step 3: Implement.**
  - In `GameNode.kt`, extract the post-election wiring (`node`, `appMux`, lobby-presence build) into an `internal` helper both `gameNode` and `gameOverRoom` call, so `gameNode` still returns `GameSession` and `gameOverRoom` returns `RoomGameSession`.
  - `gameOverRoom` derives `voterIds` from `room.roster.value` (roster-given — no admission handshake, no `gameHost` quorum-block that would hang `runTest`), runs the bootstrap over `seam = room.channel(GAME_ROOM_CHANNEL)`, and constructs `RoomGameSession(node, room, appMux, lobbyPresence)`.
  - `RoomGameSession(node, room, appMux, lobby) : GameSession(node, room.channel(GAME_ROOM_CHANNEL), appMux, lobby)` with `presence get() = room.events`, `roster get() = room.roster`. (Lifecycle in Task 4.)
- [ ] **Step 4: Run — expect PASS.** `./gradlew :kuilt-game:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `feat(game): gameOverRoom + RoomGameSession surface the room's presence (part of #1618)`

---

## Task 4: Ownership & lifecycle — `close()` tears down node then room

**Files:**
- Modify: `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/RoomGameSession.kt`
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/RoomGameLifecycleTest.kt`

**Interfaces:**
- Consumes: `GameSession.close()` (now `open`, Task 2), `Room.leave(LeaveReason)` (idempotent via `closed` latch, `SeamRoom.kt:1647-1651`), `MembershipEvent.HostLost`.
- Produces: `RoomGameSession.close(reason)` overrides to `super.close(reason)` then `room.leave(reason.toLeave())`; `gameOverRoom` launches a `HostLost` watcher that closes the session when the room dies. `private fun CloseReason.toLeave(): LeaveReason` = `Normal → LeaveReason.Normal`, `Error(t) → LeaveReason.Error(t.message ?: "closed")`.

- [ ] **Step 1: Write the failing test — `close()` leaves the room (a channel-view close is a no-op).** After `session.close()`, assert the backing room is terminal via a **broadcast no-op** (`room.broadcast(bytes)` then confirm nothing delivered) or `room`'s seam state — **not** "roster empties" (`leave` does not clear the roster). Assert `close()` twice does not throw.
- [ ] **Step 2: Run — expect FAIL** (base `close()` closes only the channel view → room still live: `room.broadcast` still delivers).
- [ ] **Step 3: Implement.** Override `close(reason)`: `super.close(reason)`; then `room.leave(reason.toLeave())`. In `gameOverRoom`: `scope.launch { room.events.filterIsInstance<MembershipEvent.HostLost>().first(); close(CloseReason.Normal) }` (double-close safe via the leave latch). KDoc the **single-ownership** contract: the caller hands the room to `gameOverRoom` and must not `room.leave()` it directly.
- [ ] **Step 4: Run — expect PASS.** `./gradlew :kuilt-game:build --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(game): RoomGameSession owns the room lifecycle (close tears both) (part of #1618)`

---

## Task 5: Split the channel bus from the heartbeat bus (backpressure isolation)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`rawIncoming` fan-out) + `RoomChannel.kt` (`RoomChannelSeam.incoming`)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ChannelBackpressureTest.kt`

**The real defect (corrected):** `rawIncoming` is a SUSPEND `MutableSharedFlow(cap 256)` that **both** the per-peer detectors **and** every `RoomChannelSeam` subscribe to (`SeamRoom.kt:495, 825-828`; `RoomChannel.kt:143-149`). A slow channel consumer suspends the main loop's `rawIncoming.emit`, which does **not** delay the silence-driven `Partitioned` (separate coroutine, `Channel.UNLIMITED`) but **does**: (a) stop pings reaching detectors → pong replies stop → **remote** peers falsely evict this node; (b) block a healthy peer C's pongs from reaching C's local detector → **false local `Partitioned(C)`**; (c) stall admit/`ResumeAck`/`Farewell`.

**Interfaces:**
- Produces: a **bus split** — heartbeat/admit frames stay on the un-throttled detector path; channel frames go to a separate per-`RoomChannelSeam` bounded stage. Channel delivery becomes lossy-under-local-backpressure (`DROP_OLDEST` on the *per-channel* stage only — **never** the shared `rawIncoming`), documented on `Room.channel`.

- [ ] **Step 1: Write the failing test — a stalled game-channel consumer must not falsely `Partitioned` a healthy peer C.** 3-peer mesh; subscribe to `channel("game")` with a blocked collector; keep peer C fully healthy (pongs flowing); assert the host does **not** emit `Partitioned(C)` and C's detector stays `Connected`, despite the channel backlog.
- [ ] **Step 2: Run — expect FAIL** (blocked channel consumer backpressures `rawIncoming` → C's pongs don't reach C's detector → false `Partitioned(C)`).
- [ ] **Step 3: Implement.** Route channel frames off `rawIncoming` into a per-`RoomChannelSeam` bounded staging flow (`DROP_OLDEST`); keep heartbeat/admit frames on the existing detector fan-out (un-throttled). Preserve ADR-034 single-collection (main loop stays the sole `seam.incoming` collector). Document on `Room.channel` KDoc: channel delivery is **best-effort under local backpressure** — Raft/Quilter are gap-tolerant; an at-least-once consumer (e.g. `DealSession`/`FairRandom` commit-reveal) must not assume lossless channel delivery.
- [ ] **Step 4: Run — expect PASS**, then full `:kuilt-session` suite + `:examples:test` for channel-ordering/consensus regressions. `./gradlew :kuilt-session:build :examples:test --rerun-tasks`.
- [ ] **Step 5: Commit** — `fix(session): split channel bus from heartbeat bus — app backpressure can't false-partition a peer (part of #1618)`

---

## Task 6: Semantics doc, sample, and the no-Room compile guarantee

**Files:**
- Modify: KDoc on `RoomGameSession.presence` (link-liveness semantics)
- Create: `kuilt-game/src/commonSamples/kotlin/us/tractat/kuilt/game/RoomGamePresenceSamples.kt` (`@sample`; samples compile as `commonTest` — a broken one breaks the build)
- Modify: `docs/agent-cookbook.md` (a "presence from a game" row → `RoomGameSession.presence`)
- Test: `kuilt-game/src/commonTest/kotlin/us/tractat/kuilt/game/NoRoomBootstrapTest.kt`

**Interfaces:**
- Produces: KDoc stating presence = **link liveness** (a ~3 s `Resumed` can be a legitimate link heal; human "seated/away" is app-layer); a compiled sample; a test that `gameNode(...)` returns a plain `GameSession` with **no** `presence` member (compile-time: `(session as? RoomGameSession)?.presence` is the only path).

- [ ] **Step 1:** Write the KDoc + `@sample`.
- [ ] **Step 2:** `NoRoomBootstrapTest`: `val s: GameSession = gameNode(...); assertNull((s as? RoomGameSession))` — pins that the raw bootstrap has no presence surface (documented, type-enforced).
- [ ] **Step 3:** Add the cookbook row; `./gradlew :kuilt-game:build --rerun-tasks` (samples compile).
- [ ] **Step 4: Commit** — `docs(game): presence=link-liveness semantics + sample + cookbook row (part of #1618)`

---

## Task 7: Consumer migration note (fireworks — separate repo)

- [ ] **Step 1:** Short `docs/` note: build the game with `gameOverRoom(adoptedRoom, clock)`; read `session.presence` instead of a hand-wired `room.events` adapter; `Resumed` now fires only on a real host `ResumeAck`. Retires `EmbeddedRoomGameHost` app-side (out of kuilt scope).
- [ ] **Step 2: Commit** — `docs: RoomGameSession consumer migration note (part of #1618)`

---

## Self-review
- **Spec coverage:** §1 (Tasks 3/6), §2 (Task 2), §3 subtype + name-collision fix (Tasks 2/3), §4 lifecycle (Task 4), §5 backpressure bus-split (Task 5), §6 ResumeAck (Task 1), §7 semantics (Task 6), §8 migration (Task 7).
- **Fable fixes folded in:** base `presence` renamed (compile-breaker); `RAFT_CHANNEL_TAG` replaced with one `GAME_ROOM_CHANNEL` string channel; roster-given bootstrap (no `gameHost` hang); `livenessConfig` no-op dropped; Task-5 test re-targeted at false-`Partitioned(C)`; `close()`/class marked `open`; `toLeave()` mapping defined; "roster empties" assertion replaced; `injectAdmitFrame`/`linkFor` fictions removed.
- **Ordering:** Task 1 independent/first; Task 2 precedes 3–6; Task 5 (riskiest, lossy semantics) isolated for review.
