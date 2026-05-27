# `:kuilt-session` (Stage 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan slice-by-slice. Steps use checkbox (`- [ ]`) syntax. This plan is dispatched one slice per worker; later slices are re-planned with the foundation in place.

**Goal:** Build `:kuilt-session` — the membership/room layer over `Seam` — as a standalone, conformance-tested kuilt library, with **zero fireworks-compose changes**.

**Architecture:** A new KMP module depending only on `:kuilt-core`. It wraps a `Loom`/`Seam` and adds an *admitted-member* model on top of raw connectivity: identity handshake, roster, transport role/host-election, partition/reconnect/resume, and a defined host-loss terminal state. The `partition/` package moves up from `:kuilt-core` (its types — `ResumeToken`/`RoomId`/reconnect — are session concepts). Built and tested entirely against `InMemoryLoom`; a `RoomConformanceSuite` TCK encodes the contract.

**Tech Stack:** Kotlin Multiplatform, `kuilt.kmp-library` convention plugin, kotlinx-coroutines, kotlinx-serialization (CBOR for admit-protocol framing), kotlin-test + coroutines-test (`runTest`/virtual time).

**Decision record:** [ADR-036](https://github.com/tractat-us/fireworks-compose/blob/main/docs/adr-036-kuilt-session-membership-layer.md) (in fireworks-compose). **Spec:** `2026-05-27-kuilt-session-membership-and-merged-roadmap-design.md` (same repo).

---

## Slice overview (dispatch order)

| Slice | Title | Depends on | Green-build deliverable |
|---|---|---|---|
| **1A** | Scaffold module + move `partition/` up | — | `:kuilt-session` builds; `partition/` + tests pass in new home; `:kuilt-core` no longer ships `partition/` |
| **1B** | `Room`/`RoomFactory` types + admit/identify + roster | 1A | `RoomFactory.host`/`join` over `InMemoryLoom`; admitted-member roster derived from handshake; broadcast/sendTo/incoming route by `Member` |
| **1C** | Role / host-election + `HostLost` terminal state | 1B | `SessionRole` assignment + deterministic tiebreak; partition of host → `HostLost`; no auto-election (D-010) |
| **1D** | reconnect / resume wired into `Room` | 1B, 1C | `Room.resume(ResumeToken)` using `JoinerReconnectController`; window-open/resume/expire events surface as `MembershipEvent` |
| **1E** | `RoomConformanceSuite` (TCK) + lifecycle state-machine tests | 1B–1D | abstract suite in `commonMain`; `InMemoryLoom`-backed subclass green — the **#1519 exit gate** |
| **1F** | Publish + catalog bump | 1E | `:kuilt-session` published; fireworks `libs.versions.toml` alias `kuilt-session` added (in a fireworks PR, not here) |

Slices 1B–1D design the admit wire-protocol and election algorithm TDD-style against `InMemoryLoom`, grounded in the fireworks `session.hello`→`welcome` handshake being ported and the existing `JoinerReconnectController`. **This plan fully details 1A** (mechanical, next); 1B–1F carry interface contracts + acceptance criteria and are re-planned by the dispatcher when reached.

---

## File Structure (target, end of Stage 1)

```
kuilt-session/
  build.gradle.kts
  src/commonMain/kotlin/us/tractat/kuilt/session/
    Room.kt                 # Room, RoomFactory interfaces
    Member.kt               # Member, MemberIdentity, Liveness
    SessionRole.kt          # SessionRole (Host/Joiner)
    MembershipEvent.kt      # sealed MembershipEvent; RoomFrame
    LeaveReason.kt
    SeamRoom.kt             # the Loom/Seam-backed Room implementation
    admit/                  # admit/identify handshake (CBOR framing over Swatch.payload)
    election/               # role assignment + host-loss
    partition/              # MOVED from :kuilt-core (PartitionDetector, Heartbeat*, JoinerReconnect*, ResumeToken, PartitionEvent)
  src/commonTest/kotlin/us/tractat/kuilt/session/
    ...                     # per-slice tests
kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/
    RoomConformanceSuite.kt # NEW TCK (1E)
```

---

## Task 1A: Scaffold `:kuilt-session` + move `partition/` up from `:kuilt-core`

**Files:**
- Create: `kuilt-session/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include(":kuilt-session")`)
- Move: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/partition/` → `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/` (6 files: `PartitionDetector.kt`, `HeartbeatPartitionDetector.kt`, `HeartbeatConfig.kt`, `JoinerReconnectController.kt`, `DefaultJoinerReconnectController.kt`, `ResumeToken.kt`, `PartitionEvent.kt`)
- Move: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/partition/` → `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/` (`JoinerReconnectControllerTest.kt`, `HeartbeatPartitionDetectorTest.kt`, `PartialConnectivityScenarioTest.kt`, `FilteredSeam.kt`, `MeshTestHarness.kt`)

> **Package rename on move:** `package us.tractat.kuilt.core.partition` → `package us.tractat.kuilt.session.partition` in every moved file, and update imports in the moved tests. These types reference `PeerId`, `Seam`, etc. from `:kuilt-core` — those imports stay (`us.tractat.kuilt.core.*`), satisfied by the `api(project(":kuilt-core"))` dep.

- [ ] **Step 1: Seed `local.properties` (Android SDK) in the worktree**

```bash
echo "sdk.dir=/Users/keddie/Library/Android/sdk" > local.properties
```

- [ ] **Step 2: Create the module build file**

`kuilt-session/build.gradle.kts`:
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
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

- [ ] **Step 3: Register the module**

In `settings.gradle.kts`, add after `include(":kuilt-conformance")`:
```kotlin
include(":kuilt-session")
```

- [ ] **Step 4: Move the `partition/` source + test packages**

Use `git mv` to preserve history, then rewrite the `package` line and any in-package imports:
```bash
mkdir -p kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition
mkdir -p kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition
git mv kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/partition/* \
       kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/
git mv kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/partition/* \
       kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/
```
Then in every moved file change `package us.tractat.kuilt.core.partition` → `package us.tractat.kuilt.session.partition`. If any moved file imports a sibling via `us.tractat.kuilt.core.partition.X`, change it to `us.tractat.kuilt.session.partition.X`. Leave `us.tractat.kuilt.core.{PeerId,Seam,...}` imports unchanged.

- [ ] **Step 5: Confirm nothing else referenced the old package**

Run: `git grep -n "kuilt.core.partition" -- '*.kt'`
Expected: **no matches** (verified during design — only the package itself used these types).

- [ ] **Step 6: Build + test the new module (FOREGROUND, timeout 600000)**

Run: `./gradlew :kuilt-session:build 2>&1 | tail -40`
Expected: `BUILD SUCCESSFUL`. Confirm the moved tests actually ran — check `kuilt-session/build/test-results/*/` XML has the `JoinerReconnectControllerTest` / `HeartbeatPartitionDetectorTest` / `PartialConnectivityScenarioTest` counts (a `--tests` filter matching nothing still reports success; here we run the whole module so the counts must be > 0).

- [ ] **Step 7: Build `:kuilt-core` to confirm it no longer ships `partition/` and still compiles**

Run: `./gradlew :kuilt-core:build 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`; `kuilt-core/.../partition/` directory gone.

- [ ] **Step 8: ktlint**

Run: `./gradlew ktlintFormat 2>&1 | tail -10` then `./gradlew ktlintCheck 2>&1 | tail -10`
Expected: clean.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(kuilt-session): scaffold module + move partition/ up from kuilt-core (Stage 1A)

New :kuilt-session library depending on :kuilt-core. Relocate the partition/
package (PartitionDetector, Heartbeat*, JoinerReconnect*, ResumeToken/RoomId,
PartitionEvent) + its tests — these are session concepts (reconnect needs a room).
:kuilt-core is now the pure transport contract. No new behavior.

Refs ADR-036, refs tractat-us/fireworks-compose#1519.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 10: Push + open Draft PR (claim) referencing the kuilt epic issue**

```bash
git push -u origin kuilt-session-stage1
gh pr create --draft --base main --head kuilt-session-stage1 --title "feat(kuilt-session): Stage 1A — scaffold + move partition/ up" --body-file - <<'BODY'
> 🤖 This PR was generated by Claude on behalf of @keddie.

Stage 1A of `:kuilt-session` (ADR-036). New module depending on `:kuilt-core`; moves `partition/` (reconnect/resume/`RoomId`) up — these are session concepts. `:kuilt-core` becomes the pure transport contract. No behavior change.

Part of <kuilt epic issue>. Refs tractat-us/fireworks-compose#1519.
BODY
```

---

## Task 1B: `Room`/`RoomFactory` + admit/identify + roster

**Files (create):** `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/{Room.kt, Member.kt, MembershipEvent.kt, SessionRole.kt, LeaveReason.kt, SeamRoom.kt, admit/*}`; tests under `commonTest/.../session/`.

**Contract to deliver (the public API, exactly as ADR-036 §Decision):**
```kotlin
interface RoomFactory {                 // wraps a Loom
    suspend fun host(pattern: Pattern): Room
    suspend fun join(tag: Tag): Room
}
interface Room {
    val selfId: PeerId
    val role: StateFlow<SessionRole>
    val roster: StateFlow<Set<Member>>
    val events: Flow<MembershipEvent>
    val incoming: Flow<RoomFrame>
    suspend fun broadcast(bytes: ByteArray)
    suspend fun sendTo(peer: PeerId, bytes: ByteArray)
    suspend fun resume(token: ResumeToken): ResumeResult   // wired in 1D
    suspend fun leave(reason: LeaveReason)
}
data class Member(val id: PeerId, val identity: MemberIdentity, val liveness: Liveness)
data class RoomFrame(val sender: PeerId, val payload: ByteArray)
enum class SessionRole { Host, Joiner }
sealed interface MembershipEvent  // Joined / Left / Partitioned / Recovered / Resumed / HostLost
```

**Acceptance criteria (TDD against `InMemoryLoom`):**
1. `host(Pattern)` returns a `Room` with `role == Host`; `join(Tag)` returns one with `role == Joiner`.
2. **Roster ≠ `Seam.peers`.** A joiner appears in `roster` only *after* completing the admit/identify handshake; a connected-but-unidentified peer is in `Seam.peers` but not `roster`. Test by intercepting the handshake.
3. `MembershipEvent.Joined(member)` emits when admit completes; `Left` on `leave()`/disconnect.
4. `broadcast`/`sendTo` delegate to the underlying `Seam`; `incoming` surfaces `RoomFrame` tagged with the admitted-member sender (drop frames from unadmitted peers).
5. Admit framing rides inside `Swatch.payload` (CBOR); `:kuilt-core` stays oblivious. Port the shape of fireworks' `session.hello`→`welcome` (`LiveLeaderImpl`).
6. `MemberIdentity` subsumes the ADR-030 `(deviceId | sessionId)` stable identity used for dedup.

**Dispatcher note:** re-plan 1B into bite-sized TDD steps when dispatching — the admit wire-format is designed here against `InMemoryLoom`.

---

## Task 1C: Role / host-election + `HostLost` terminal state

**Files (create):** `kuilt-session/.../election/*`; tests.

**Acceptance criteria:**
1. Deterministic host-election/tiebreak among peers (e.g. lowest `PeerId` or opener-wins) — documented and tested for stability across reconnects.
2. Feeding the host's link a `PartitionEvent.PeerLost` (via the moved `HeartbeatPartitionDetector`/`PartitionDetector`) drives the `Room` to a `HostLost` **terminal** state and emits `MembershipEvent.HostLost`.
3. **No auto-election** on host loss (preserves D-010 / today's `HostDisconnectController`): `HostLost` is terminal, not a re-election trigger.
4. `PeerUnresponsive`/`PeerRecovered` surface as `Partitioned`/`Recovered` events without changing role.

---

## Task 1D: reconnect / resume wired into `Room`

**Files (create):** glue in `SeamRoom.kt` + `commonTest`.

**Acceptance criteria:**
1. `Room.resume(token: ResumeToken): ResumeResult` drives the moved `JoinerReconnectController` (`onPeerUnresponsive` → `WindowOpened`; successful rejoin → `Resumed`; timeout → `WindowExpired`).
2. The controller's `JoinerReconnectEvent`s map to `MembershipEvent` (`Resumed`, and window-open/expire surfaced appropriately).
3. Virtual-time tests only (`runTest` + `advanceTimeBy`) — no wall-clock; inject the clock.

---

## Task 1E: `RoomConformanceSuite` (TCK) + lifecycle state-machine tests

**Files:**
- Create: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt` (abstract; mirror `SeamConformanceSuite` — `abstract fun newRoomFactory(): RoomFactory`)
- Modify: `kuilt-conformance/build.gradle.kts` → add `api(project(":kuilt-session"))`
- Create: an `InMemoryLoom`-backed subclass in `:kuilt-session` `commonTest` (or `:kuilt-conformance` `commonTest`) proving the suite green.

**Acceptance criteria (the #1519 exit gate — lifecycle state-machine tests green):**
1. join → active → leave → rejoin round-trips cleanly.
2. partition → recover (no role change).
3. partition → resume(token) (Resumed).
4. host-loss → HostLost terminal.
5. roster reflects admitted members only; admit handshake required before a peer counts.
6. The suite lives in `commonMain` (like `SeamConformanceSuite`) so any future `RoomFactory` fabric can subclass it.

---

## Task 1F: Publish + catalog bump

**Acceptance criteria:**
1. `:kuilt-session` publishes to the kuilt GitHub Packages registry alongside the other `kuilt-*` artifacts (follow `:kuilt-core`'s publish config; bump the kuilt version).
2. In a **separate fireworks-compose PR** (not in the kuilt repo): add `kuilt-session = { module = "...", version.ref = "kuilt" }` to `gradle/libs.versions.toml`. This is the only fireworks change in Stage 1 and gates Stages 3–4.

---

## Self-review

- **Spec coverage:** §Stage-1.1 (move partition) → 1A. §1.2 (Room API) → 1B. §1.3 (admit) → 1B. §role/host-loss → 1C. §reconnect/resume → 1D. §1.4 (conformance + lifecycle) → 1E. §1.5 (publish) → 1F. All covered.
- **Type consistency:** `RoomFactory.host/join`, `Room.{selfId,role,roster,events,incoming,broadcast,sendTo,resume,leave}`, `Member`, `SessionRole`, `MembershipEvent`, `RoomFrame`, `ResumeToken`/`RoomId` (moved), `PartitionEvent` (moved) — used consistently across 1A–1F.
- **Placeholders:** 1A is fully concrete. 1B–1F are intentionally contract-level (protocol designed against `InMemoryLoom` per slice) and flagged for per-slice re-planning — consistent with the EPIC dispatch convention, not stray TODOs.
