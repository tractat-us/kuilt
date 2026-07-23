# Election-Lobby Churn (#1618 root cause) — Investigation & Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This is an investigation-gated plan: Phase 1 decides the fix in Phase 2. Do NOT skip to a fix.**

**Goal:** Find and fix why a mesh game session over `NwLoom` re-elects its host ~47× and never settles into a stable adopted game Room long enough (15 s) for the partition detector to emit `WindowOpened` — the true cause behind #1618's "0 presence events on a Wi-Fi drop."

**Architecture:** Evidence-first. The consumer (fireworks) and the presence-emit path are both proven correct (f-c audit + PRs #1619/#1621). The symptom is that no game Room lives 15 s because the session churns in the **election-lobby** layer (`SeamElectionLobby`, 3 s `LOBBY_HEARTBEAT`, `LobbyTornException` → `electWithTornRetry`). Phase 0 instruments the lobby tear→re-elect loop; Phase 1 reproduces the churn under a *flappy* fake-NW mesh and resolves the fork (over-aggressive lobby-tear vs. genuine NW-fabric flap); Phase 2 fixes the confirmed cause; Phase 3 validates on hardware.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (virtual-time `runTest`), `kotlin-logging`, kuilt `SeamElectionLobby`/`HeartbeatPartitionDetector`/`NwSeam`, the fake-NW radio harness from `NwMeshConformanceTest`.

## Global Constraints

- Source SDKMAN + JDK 21 first: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.
- `explicitApi()` enforced — every new public decl gets an explicit modifier.
- Coroutine tests: `StandardTestDispatcher`, tight `timeout = 5.seconds`, bounded `advanceTimeBy` — **never** `advanceUntilIdle()` (re-arming timers). Multi-node lobby tests use the canonical sim harness pattern; a hang = STOP and re-plan.
- Diagnostics log **identities + state, not sizes** (peer ids, tear cause, roster ids).
- Instrumentation-before-fix: the first shipped change on the churn is evidence capture, not a fix.
- Fence agent test runs: `timeout 90 ./gradlew :<module>:jvmTest --tests "<oneTest>"`.
- No `closes #1618` until validated against the hardware reproducer; use "part of #1618".

---

## Phase 0 — Instrument the lobby tear → re-elect loop

### Task 1: Identity-carrying logs on every `SeamElectionLobby` tear + election transition

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt`
- (build already has `kotlin-logging`; if not, add `implementation(libs.kotlin.logging)` to `kuilt-session/build.gradle.kts`)

**Interfaces:**
- Consumes: existing `SeamElectionLobby` internals (the `LobbyTornException` path, the freeze-round 2PC, `host` StateFlow, `peers`).
- Produces: log lines `lobby.tear cause=<Unreachable|LobbyTorn|PeerNotConnected> peer=<id> phase=<pre-freeze|mid-2PC|post-commit> roster=<ids>`; `lobby.elect host=<id> peers=<ids>`; `lobby.awaitRoom.timeout waited=<ms> peers=<ids>`.

- [ ] **Step 1:** Read `SeamElectionLobby.kt` end-to-end; locate every site that throws/propagates `LobbyTornException` and the freeze-round (2PC) phases. Note the `LOBBY_HEARTBEAT` config (`interval=1s, timeout=3s, reconnectWindow=3s`, ~line 429-433) and the per-peer lobby detector wiring (#1480).
- [ ] **Step 2:** Add a `private val logger = KotlinLogging.logger(...)` if absent. At each tear origin, log `lobby.tear` with the **cause enum, the peer id that triggered it, the 2PC phase, and the current roster ids** (not `.size`). At election settle, log `lobby.elect host peers`. In `awaitRoom`'s timeout branch, log `lobby.awaitRoom.timeout waited peers`.
- [ ] **Step 3:** Behavior unchanged — logging only. Verify: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && timeout 300 ./gradlew :kuilt-session:jvmTest`. Expected: existing lobby tests still green.
- [ ] **Step 4:** Commit: `git commit -m "diag(session): identity-carrying logs on SeamElectionLobby tear + election transitions (part of #1618)"`

---

## Phase 1 — Reproduce the churn under a flappy fake-NW mesh (THE FORK-RESOLVER)

### Task 2: A flappy fake-NW harness that injects transient blips

**Files:**
- Create: `kuilt-nw/src/commonTest/kotlin/us/tractat/kuilt/nw/FlappyNwMeshTest.kt`
- Reference: the fake-NW radio + `newMeshOfSize(n)` used by `kuilt-nw/src/commonTest/.../NwMeshConformanceTest.kt` and the coverage agent's `NwMeshRoomPartitionTest` (PR #1621).

**Interfaces:**
- Consumes: fake-NW mesh factory (`newMeshOfSize`), `SeamRoomFactory.electLobby`, `FaultySeam.partition/heal` (from `:kuilt-test`) or the fake radio's own blip injection.
- Produces: a reusable helper `blip(link, downFor: Duration)` that drops then heals one peer's frames after `downFor`, so a test can inject a transient loss *shorter* than a real drop.

- [ ] **Step 1: Write the failing/observing test — "a transient blip shorter than a real drop must NOT tear the lobby into a re-elect."** Stand a 3-peer fake-NW mesh, run `electLobby` on all three to a settled host + adopted Rooms. Then inject a **2 s** blip on one non-host peer (shorter than the game Room's 15 s but longer than the lobby's 3 s `LOBBY_HEARTBEAT` timeout). Assert: the elected host is **unchanged** and no new adopted Room was created.

```kotlin
@Test
fun `a transient blip does not churn the election`() = runTest(timeout = 5.seconds) {
    val mesh = newMeshOfSize(3)                       // fake-NW seams
    val lobbies = mesh.map { electLobbyOver(it) }     // SeamElectionLobby via SeamRoomFactory
    val host0 = settle(lobbies)                        // elected host + adopted rooms
    val victim = lobbies.first { it.selfId != host0 }

    blip(victim, downFor = 2.seconds)                  // transient loss, heals itself
    advanceTimeBy(4_000); runCurrent()

    assertEquals(host0, lobbies[0].host.value, "a 2s blip must not re-elect")
    // and: no LobbyTornException surfaced, room identity stable
}
```

- [ ] **Step 2: Run it.** `timeout 90 ./gradlew :kuilt-nw:jvmTest --tests "*FlappyNwMeshTest*"`. **Record the outcome — this is the fork-resolver:**
  - **FAILS** (blip → `LobbyTornException` → re-elect): the `LOBBY_HEARTBEAT` (3 s) tears on transient blips the game layer would ride out → **cause A (over-aggressive lobby-tear).** Proceed to Task 3A.
  - **PASSES** (lobby rides the blip): the lobby is *not* over-tearing on transient loss → the 47 re-elects come from **genuine, sustained NW flap or a protocol wedge** → **cause B.** Proceed to Task 3B.
- [ ] **Step 3:** Add a second variant: a **repeated** blip train (blip every ~4 s) and assert whether each blip forces a re-elect (quantifies churn amplification). Log via Phase-0 lines.
- [ ] **Step 4:** Commit the repro + the recorded verdict in the commit body: `git commit -m "test(nw): flappy-mesh repro for #1618 election churn — verdict: cause <A|B> (part of #1618)"`

---

## Phase 2 — Fix the confirmed cause

### Task 3A (if cause A): give the election lobby a partition→reconnect grace instead of tearing on the first 3 s silence

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt` (+ the `kuilt-nw` repro from Task 2)

**Interfaces:**
- Consumes: `LOBBY_HEARTBEAT` config; the per-peer lobby `HeartbeatPartitionDetector` (#1480) — which already emits `PeerUnresponsive` (recoverable) before `PeerLost` (terminal).
- Produces: lobby tears to `LobbyTornException` **only on `PeerLost`** (window expired), not on the first `PeerUnresponsive`; a `LOBBY_HEARTBEAT.reconnectWindow` wide enough (candidate: align to the game Room's window, or a deliberate lobby-specific value) to ride a transient blip.

- [ ] **Step 1: Write the failing test** — the Task-2 "2 s blip does not re-elect" test, now expected to PASS after the fix (it currently FAILS under cause A).
- [ ] **Step 2:** In `SeamElectionLobby`, change the tear trigger so a lobby peer going `PeerUnresponsive` opens the reconnect window (and surfaces a *recoverable* signal), and only `PeerLost` (window expired) raises `LobbyTornException`. Widen `LOBBY_HEARTBEAT.reconnectWindow` from 3 s to a value that rides a realistic transient AWDL blip (spec the exact value from Phase-1 blip data; candidate 10–15 s) — **but keep the lobby responsive enough that a genuinely-gone host still re-elects promptly.** This is a real trade-off; pick the value from the Phase-1 blip-duration distribution and document it.
- [ ] **Step 3:** Run the repro + `:kuilt-session` lobby suite. Confirm: transient blip → no re-elect; a *sustained* loss (> window) → still tears + re-elects. `timeout 300 ./gradlew :kuilt-session:jvmTest :kuilt-nw:jvmTest --tests "*FlappyNwMeshTest*"`.
- [ ] **Step 4:** Guard the class: search for other 2PC/freeze-round sites that tear on first-silence rather than window-expiry; fix consistently.
- [ ] **Step 5:** Commit: `git commit -m "fix(session): election lobby rides transient blips — tear only on reconnect-window expiry, not first-silence (part of #1618)"`

### Task 3B (if cause B): confirm the NW-fabric flap and route it to the kuilt-nw stability track

**Files:**
- Create: `kuilt-nw/src/commonTest/kotlin/us/tractat/kuilt/nw/NwFlapCharacterizationTest.kt`
- Modify (diag): `kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt` (already richly logged — add only what's missing: connection `ready↔waiting` flap identities)

**Interfaces:**
- Consumes: the fake radio's connection-state injection; `NwSeam.peers`/`state` transitions.
- Produces: a characterization of how often/why `NwSeam.peers` churns under the Phase-1 flap profile; a filed follow-up issue for the kuilt-nw fabric stability epic (#1403).

- [ ] **Step 1:** Instrument `NwSeam` connection `ready↔waiting`/`PathLost` transitions with peer identities (if not already). Re-run the Task-2 flap train; capture how the peer set churns.
- [ ] **Step 2:** If the churn is a real sustained fabric flap (not a kuilt logic bug), the fix belongs to the **kuilt-nw fabric stability epic (#1403)** — file a focused issue with the characterization, and land only the diagnostics here. Do NOT force a session-layer fix over a genuine fabric problem.
- [ ] **Step 3:** Commit: `git commit -m "diag(nw): characterize NW-mesh flap under #1618 profile; file fabric-stability follow-up (part of #1618)"`

---

## Phase 3 — Validate

### Task 4: Snapshot, re-capture on 2 phones with #1620 + this fix, then close #1618 by hand

**Files:** none (validation).

- [ ] **Step 1:** Land Phase-2 fix + PR #1620 diagnostics on a snapshot; point fireworks' `includeBuild`/version at it.
- [ ] **Step 2:** Run the 2-iPhone Quick Play airplane-mode drop. Pull off-device otel logs. Confirm from the #1620 + Phase-0 lines: the session **settles into a stable adopted game Room** (no re-elect storm), the per-peer detector matures, and a drop now produces `WindowOpened`/`Partitioned` → the seat pauses.
- [ ] **Step 3:** Only after the reproducer is green on hardware, `Closes #1618` by hand (per repo rule: never close a hardware-repro'd bug until validated against the reproducer).

## Self-review notes
- Phase 1 is the gate; Tasks 3A/3B are mutually exclusive, chosen by the Task-2 verdict.
- The 3A window value is data-driven (Phase-1 blip distribution), not guessed — the plan flags it as a documented trade-off, not a placeholder.
- Cause B explicitly refuses to force a session fix over a fabric problem (routes to #1403).
