# Adopt-path resume (#1618 root cause) — Investigation & Fix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. **Rebase onto `origin/main` first** — `MeshRoomPartitionTest` + `FaultyLoom` (the harness this plan reuses) merged with #1619 and are on `origin/main` but not necessarily on your branch. Do NOT assume PR #1621's `NwMeshRoomPartitionTest` is available (still open).

**Goal:** Stop the #1618 re-election storm and make presence fire on a real Wi-Fi drop, by giving the `electLobby`/`adopt` Room **resume-after-tear** so a transient NW blip resumes the same Room within a window (emitting `WindowOpened`) instead of going straight to terminal `HostLost` and forcing a full re-election.

**Confirmed root cause (code-verified):** `SeamRoomFactory.adopt` wires **no `reweave`** (`SeamRoom.kt` adopt KDoc; `reweave` defaults null). On a joiner, a host-link tear funnels to `JoinerResumeMachine.attemptReconnect`, and with `reweave == null` it takes the immediate-terminal branch — `onReconnectFailed(FailureReason.Unrecoverable)` → `MembershipEvent.HostLost`, **no `WindowOpened`, no resume** (`kuilt-session/.../partition/JoinerResumeMachine.kt:312-316`). So every transient mesh blip permanently kills the adopted Room → the app re-elects → churn → no Room lives the ~15 s a `WindowOpened` needs. (Not the cause, all verified correct: the consumer wiring, the mesh emit path, and the lobby's tear timing — which already tears only on `PeerLost`, `SeamElectionLobby.kt:347-373`.)

**Architecture:** Reproduce the adopt-path terminal-on-tear locally (kuilt-session, `FaultyLoom`, virtual time). Then wire a `reweave` through `electLobby`/`adopt` so `JoinerResumeMachine` runs its real resume path — **but** guard for the symmetric-mesh case where the host can change across a reconnect (likely why `reweave` was left unwired): resume only to the same reachable host within the window; otherwise fall through to today's terminal → re-elect. Strictly additive: it inserts a resume attempt before the terminal branch.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines virtual time, kuilt `SeamElectionLobby`/`SeamRoomFactory.adopt`/`JoinerResumeMachine`/`HeartbeatPartitionDetector`, `FaultyLoom`.

## Global Constraints

- `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first.
- `explicitApi()`; coroutine tests use `StandardTestDispatcher`, bounded `advanceTimeBy`, injected clock, tight `timeout = 5.seconds`; never `advanceUntilIdle()`.
- Diagnostics log identities + state (roomId per incarnation, tear reason, host id), not sizes.
- Fence agent test runs: `timeout 120 ./gradlew :kuilt-session:jvmTest --tests "<oneTest>"`.
- Consensus/behavior change → run full `./gradlew :kuilt-session:build --rerun-tasks` (+ `:examples:test` if the lobby/room lifecycle changes).
- No `closes #1618` until validated on the 2-phone reproducer; commits use "part of #1618".

---

## Phase 0 — Instrument the Room's terminal + resume-branch decision

### Task 1: Log *why* the adopted Room goes terminal (which null gate fired) + roomId identity

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt` (the terminal branch, ~line 310-316)

**Interfaces:**
- Consumes: `reweave`, `resumeToken`, `hostPeerId`, the room's `RoomId`.
- Produces: a log line at the terminal branch — `resume.terminal reason=<no-reweave|no-token|no-host> host=<id> roomId=<id>` — and one at a successful resume — `resume.ok host=<id> roomId=<id>`.

- [ ] **Step 1:** Read `JoinerResumeMachine.attemptReconnect`/`runReconnect` (`:276-360`). Confirm the terminal branch (`reweaveFn == null || token == null || hostId == null`) and the success path. Note `#1620`'s `SeamRoom.emitEvent` already logs the resulting `HostLost` — the gap here is *which* null gate fired.
- [ ] **Step 2:** Add a `KotlinLogging` line in the terminal branch naming the specific null gate (distinguish `no-reweave` from `no-token`/`no-host`) with host id + roomId; add a `resume.ok` line on success. Behavior unchanged.
- [ ] **Step 3:** `timeout 300 ./gradlew :kuilt-session:jvmTest`. Expected: existing resume/reconnect tests green.
- [ ] **Step 4:** Commit — `diag(session): name the resume-terminal null gate + roomId identity (part of #1618)`

---

## Phase 1 — Reproduce: an adopted Room dies terminal (no WindowOpened) on a transient tear

### Task 2: Failing test — a transient host-link blip on an adopt-path joiner produces `HostLost`, never `WindowOpened`

**Files:**
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/AdoptTearTerminalTest.kt`
- Reference: `MeshRoomPartitionTest.kt` (on `origin/main`, #1619) for the `FaultyLoom` + advancing-clock harness; `AdoptTest.kt` for the `adopt`-over-pre-woven-seams pattern.

**Interfaces:**
- Consumes: `SeamRoomFactory.adopt(seam, role, …)` (no reweave), `FaultyLoom`/`FaultySeam.partition(Direction.Both)`, advancing clock in lock-step with `advanceTimeBy`.
- Produces: a reusable "adopt a 2-peer host+joiner over a `FaultyLoom`" helper.

- [ ] **Step 1: Write the test.** Weave a 2-peer `FaultyLoom` mesh; `adopt` a host Room and a joiner Room over the pre-woven seams (as `AdoptTest` does). Drop the **joiner's** link to the host (`partition(Direction.Both)`), advance past the heartbeat timeout. Assert the joiner emits **`HostLost`** and **never** `WindowOpened` — pinning today's fragile behavior.

```kotlin
@Test
fun `adopted joiner goes terminal HostLost on a transient tear, never WindowOpened`() =
    runTest(timeout = 5.seconds) {
        val h = adopt2PeerHostJoiner(fastHeartbeat, clock)          // helper: FaultyLoom + adopt
        val hostLost = async { h.joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first() }
        val windowSeen = async {
            withTimeoutOrNull(3_000) {                              // virtual ms
                h.joinerRoom.events.filterIsInstance<MembershipEvent.WindowOpened>().first()
            }
        }
        h.joinerLinkToHost.partition(Direction.Both)
        repeat(8) { tick() }                                        // past timeout+window
        assertIs<MembershipEvent.HostLost>(hostLost.await())
        assertNull(windowSeen.await(), "adopt-path joiner never opens a reconnect window")
    }
```

- [ ] **Step 2: Run — expect PASS** (it pins the *current* buggy behavior). `timeout 120 ./gradlew :kuilt-session:jvmTest --tests "*AdoptTearTerminalTest*"`. This is the reproduction; Phase 2 flips the assertions.
- [ ] **Step 3:** Add a second case: a **transient** blip that *heals within a window* (drop then heal after 2 virtual s). Under current code it still goes terminal (no window to recover into) — assert `HostLost`. Phase 2 will make this recover instead.
- [ ] **Step 4:** Commit — `test(session): pin adopt-path terminal-on-tear (no WindowOpened) — #1618 repro (part of #1618)`

---

## Phase 2 — Fix: wire resume-after-tear into the electLobby/adopt path

### Task 3 (design spike): decide the reweave mechanism for a lobby-adopted mesh Room

**Files:** none (design note appended to the design spec, or a short `docs/` note).

**Why a spike:** `JoinerResumeMachine`'s `reweave` contract requires a **same-instance heal** — the loom must return a stable, resumable handle whose `selfId` is frozen and whose channel is re-pointed onto a freshly-woven base (`JoinerResumeMachine.kt` reweave KDoc; the machine checks post-reweave whether the seam left `Torn`). Whether `NwLoom.weave(Existing)` satisfies this on a mesh, and whether the *same host* is still reachable after a re-weave, are the open questions that decide the implementation.

- [ ] **Step 1:** Read the `reweave` contract KDoc on `SeamRoom`'s constructor param and `JoinerResumeMachine` (the same-instance-heal requirement, the "check seam left Torn" resumable-vs-nonconforming test). Read how `SeamRoomFactory.join` supplies `reweave = { loom.join(tag) }` — the working reference.
- [ ] **Step 2:** Determine, from `NwLoom`/`NwSeam`, whether a lobby-woven mesh seam can be re-woven in place (same `selfId`, channel re-pointed) — i.e. does `NwLoom` mint a resumable handle, or a fresh seam per weave? (If fresh-per-weave, the reweave is invisible to the room and falls to `HostLost` — the machine already handles that; then the fix is at the loom layer, a bigger scope to flag.)
- [ ] **Step 3:** Decide the **symmetric-host guard**: after a re-weave, resume proceeds only if the *same* host peer is present/reachable; if the host is genuinely gone (or a different peer would be elected), fall through to today's terminal → re-elect. Write the decision (one short design note) and which module owns it (`electLobby` threading a reweave into `adopt`, vs a new `SeamRoomFactory` entry point).
- [ ] **Step 4:** Commit the design note — `docs(spec): adopt-path resume mechanism + symmetric-host guard (part of #1618)`

### Task 4: Thread a `reweave` through `electLobby`/`adopt` (implementation follows the spike)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`SeamRoomFactory.adopt` — add an optional `reweave`; `electLobby` — supply it)
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt` (pass the loom-backed reweave into `adopt`)
- Test: `AdoptTearTerminalTest.kt` (flip the transient-blip case), + a new "genuine host gone → still terminal" case

**Interfaces:**
- Consumes: the loom the lobby owns (to re-weave `Rendezvous.Existing`), the spike's same-host guard.
- Produces: `adopt(seam, role, memberName, roomKey, reweave)` (reweave optional, defaulted null so existing callers/tests are unaffected); `electLobby`-adopted joiner Rooms carry a working reweave.

- [ ] **Step 1: Flip the failing test** — the Task-2 *transient-blip-heals-within-window* case now expects `WindowOpened` then `Recovered`/`Resumed` (not `HostLost`). Add a companion: a *sustained* loss beyond the window still ends in `HostLost` (so genuine host-gone still re-elects).
- [ ] **Step 2: Run — expect FAIL** (no reweave yet → still terminal).
- [ ] **Step 3: Implement** per the spike: add the optional `reweave` param to `adopt`; in `electLobby`/`SeamElectionLobby` supply `reweave = { loom.weave(Rendezvous.Existing(tag)) }` (exact form from the spike) with the same-host guard. Do NOT change the terminal path's *existence* — only give it a resume attempt first.
- [ ] **Step 4: Run — expect PASS** (transient → window+resume; sustained → terminal). Full guard: `./gradlew :kuilt-session:build :examples:test --rerun-tasks`.
- [ ] **Step 5:** Guard the class: confirm `SeamRoomFactory.adopt` callers other than the lobby (if any) are unaffected by the new defaulted param; confirm the #1480 lobby detector interaction is unchanged.
- [ ] **Step 6:** Commit — `fix(session): adopt-path resume-after-tear — transient mesh blips resume instead of terminal HostLost (part of #1618)`

### Task 5 (if spike Step 2 finds NwLoom mints a fresh seam per weave): flag the loom-layer gap

- [ ] **Step 1:** If `NwLoom` cannot satisfy the same-instance-heal contract, the session-layer reweave alone won't resume on real hardware. Do NOT force a broken reweave. File a focused issue against the kuilt-nw fabric epic (#1403): "NwLoom must mint a resumable handle for adopt-path resume," with the spike evidence. Land the session-layer reweave anyway (it is correct and helps resumable looms + tests), and note in the PR that hardware resume is gated on the loom fix.

---

## Phase 3 — Validate on hardware

### Task 6: Snapshot + 2-phone capture, then close #1618 by hand

- [ ] **Step 1:** Land Phase-2 fix + PR #1620 diagnostics on a snapshot; point fireworks at it.
- [ ] **Step 2:** 2-iPhone Quick Play, airplane-mode drop. From #1620 + Phase-0 logs confirm: the session **stops re-electing** (adopted Room survives a transient blip via resume), a drop now yields `WindowOpened` → the seat pauses, and only a *sustained* loss re-elects.
- [ ] **Step 3:** Only after the reproducer is green on hardware, `Closes #1618` by hand.

## Self-review
- Phase 1 pins the confirmed bug (test *passes* on buggy code); Phase 2 flips it. No false-dichotomy fork.
- Phase 2's fix is spike-gated because the reweave mechanism (same-instance heal on a mesh + symmetric-host guard) is genuine open design, not fabricated code.
- Task 5 refuses to ship a broken reweave if the loom can't heal in place — routes that to #1403.
