# Sub-Timeout Blip Resume Implementation Plan (kuilt #1637)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A joiner whose link blips for less than the host's liveness timeout resumes its room instead of burning its 60 s budget and dying with `HostLost(Refused)`.

**Architecture:** Entirely **joiner-side**, in `JoinerResumeMachine.runReconnect`'s retry loop. The joiner already receives an unambiguous signal — a `ResumeWindowNotYetOpen` reject means *no window has ever been opened* (an open window returns `Success`, an expired one `WindowClosed`). Today that is retried until the window budget elapses. This plan makes the joiner conclude, after dwelling on that reject for longer than the host's own detector timeout, that **the host never partitioned it**, and complete the episode as a local no-op resume: restore the host detector, close the arc with `Recovered(hostId)` (see the Amendment below — the original plan said `Resumed`, which the success branch does not in fact emit), stay live. No wire change, no new config knob; one small `SeamRoom` callback, added by the Amendment.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (`StandardTestDispatcher`, virtual time), kotlin-test.

## Global Constraints

- **JDK 21 — select it explicitly.** Pin it per command:
  `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew …`
  **Do NOT use `sdk env`.** There is no `.sdkmanrc` in this repo (verified 2026-07-27 — untracked and
  absent in every worktree), so `sdk env` no-ops and the build proceeds on the system default, JDK 25.
  That does not fail at the top: it fails ~3 minutes in, at `:kuilt-warp-ksp:detekt`, with
  `Invalid value (25) passed to --jvm-target` (detekt/detekt#8714, see #1708) — which reads like a code
  defect in your own task and is not one. Confirm with `java -version` before trusting a build result.
- `explicitApi()` is enforced; every new public declaration needs an explicit modifier. **No `!!` in production code** — CI's `:module:detektJvmMain` fails on `UnsafeCallOnNullableType` and a local `detektAll` can false-green it (#1537).
- Coroutine-test discipline: `runTest(StandardTestDispatcher(), timeout = 5.seconds)`, bounded `advanceTimeBy`, **never `advanceUntilIdle()`** (election/heartbeat timers re-arm forever). Seed every `Random`. No production dispatchers in test sources.
- `runCatchingCancellable`, never bare `runCatching`, in any suspend context.
- Test methods take no `test` prefix; multi-assert tests use `assertAll()`.
- Commits say `part of #1637`. **Do not** write `closes #1637` — this is consensus-adjacent and wants the hardware check in Task 4 first.
- Verify with the **full** `./gradlew build detektAll --rerun-tasks`. A `:kuilt-session:jvmTest` run is a false green: it does not compile the Android or Kotlin/Native variants, and does not run the `:examples` / `:kuilt-cluster` E2E tests that a session-behaviour change can break.

---

## Background: the exact failure loop

Verified against `origin/main` on 2026-07-26:

1. The joiner's device path drops. `NwSeam`'s `wovenPathGrace` (10 s) expires, it evicts the host locally and re-forms `Woven`. Its detector reports `TransportClosed` → `attemptReconnect`.
2. **The host's link to the joiner never closed** — only the joiner's side tore. So the host's `collectIncoming` never completes and never fires the immediate `TransportClosed`.
3. The joiner sends `AdmitMessage.Resume`. In `HeartbeatPartitionDetector.collectIncoming`, **any** inbound frame calls `observedPeer(peerId)`, refreshing `lastSeenEpochMs` — *"Any inbound frame is proof of liveness."*
4. So the host's silence never reaches `HeartbeatConfig.timeout`, `onPeerUnresponsive` is never called, `windows[peerId]` stays `null`, and `DefaultJoinerReconnectController.tryResume` returns `ResumeResult.WindowNotYetOpen`.
5. `SeamRoom.handleResume` maps that to `Reject("resume-window-not-yet-open", RejectCode.ResumeWindowNotYetOpen)`, which is `retryable = true`.
6. The joiner sleeps `heartbeatConfig.interval` and retries — refreshing the host's `lastSeen` again. Goto 3, until the 60 s `reconnectWindow` elapses → `FailureReason.Refused` → `HostLost`.

**Why the fix cannot go host-side.** At the instant a `Resume` arrives, the host cannot distinguish this from the #1572 fast-reconnect race: in both, the roster still shows the peer `Connected` because the host has not yet processed the link close. A host-side "still connected → ack" would ack a genuine tear, then open a window for an already-resumed peer and expire it, evicting the joiner. The discriminator is only available **over time, on the joiner**: in the #1572 race the host's window opens promptly (its link closed, which fires `TransportClosed` immediately rather than by timeout), so a `WindowNotYetOpen` that persists beyond the host's timeout proves no window is coming.

---

## AMENDMENT 2026-07-26 — the episode must close itself (read before Task 2)

Verified against `origin/main` @ `8717f823` while designing #1712 Track A. **The plan as originally
written does not emit anything on the no-op path, contradicting its own Task 2 Step 4 expectation.**

`JoinerResumeMachine`'s success branch does **not** emit `Resumed`. Its own KDoc says so
(`JoinerResumeMachine.kt:284`): *"On `ResumeResult.Success` the room stays live (its ResumeAck
handler already emitted `Resumed`)."* The `if (ok)` block (`405-416`) only clears the guard and calls
`restoreHostDetector`. Both the `Resumed` emission **and** `updateMemberLiveness(hostId, Connected)`
live in `SeamRoom.handleResumeAck` (`1224-1234`).

The no-op path sets `ok = true` **precisely when no `ResumeAck` will ever arrive**, so
`handleResumeAck` never runs and the episode closes silently — leaving the `Partitioned(hostId)` +
`WindowOpened(hostId)` arc this machine already emitted permanently open.

### What the no-op branch must do

Add a `JoinerResumeHost` callback — `onNoOpResume(hostId: PeerId, at: Instant)` — invoked from the
`if (ok)` block when the episode completed via the dwell (not via a real `ResumeAck`). `SeamRoom`
implements it as the two things `handleResumeAck` does:

1. `updateMemberLiveness(hostId, Liveness.Connected)`
2. emit the closing edge

### Emit `Recovered(hostId)`, not `Resumed(selfId)` — changed from the original plan

`handleResumeAck` emits `MembershipEvent.Resumed(**selfId**)` — naming self, which is **not in
`roster`** (`Room.roster` excludes this peer). An edge-keying consumer therefore cannot match it to
the `Partitioned(hostId)` that opened the arc. Three reasons the no-op path should close with
`Recovered(hostId)` instead:

- **It names the right peer.** The arc opened on `hostId`; the closing edge should too.
- **It is semantically true.** `Recovered` is documented as *"a partitioned peer's link recovered
  before the window expired"* — exactly what happened. `Resumed` means "resumed via `Room.resume`",
  and in the no-op case nothing resumed; that is the whole point.
- **It removes a consumer problem instead of adding one.** #1618's Correction 2 advises *"always
  `Recovered`, both sides"*. Emitting `Resumed` here would make that guidance stale on the joiner
  lane and force every consumer to clear on `Recovered` **or** `Resumed`, either alone hanging a real
  case. Emitting `Recovered` keeps Correction 2 true.

**Update Task 1's test and Task 2 Step 4 accordingly:** expect `Recovered(hostId)`, not `Resumed`.

### Cross-track constraint with #1712 Track A

Track A (`docs/superpowers/specs/2026-07-26-local-fabric-vocabulary-design.md`, branch
`design/1712-local-fabric`) fixes **D3**: a joiner currently never sets its host's `Member.liveness`,
so `roster` reports the host `Connected` while `events` said `Partitioned`. After D3,
`onReconnectStarted` sets `Liveness.Partitioned(since, windowExpiresAt)` on the host — which makes
step 1 above **mandatory**, not merely tidy: without it the host stays pinned `Partitioned` in the
joiner's roster forever after a sub-timeout blip.

Whichever track lands first, the other must not regress this. The two touch different lines, so they
do not conflict textually — only semantically.

### "Identically wired" ≠ "both sides see the same sequence"

This fix is explicitly **joiner-side only — no wire change, no host change**. A sub-timeout blip
therefore gives the joiner `Partitioned → Recovered` and the host **nothing at all**, because the
host genuinely never observed a drop. Any consumer instruction to wire both roles identically must
mean *both roles run the same code against their own stream*, never *both roles observe the same
events*. The symmetric thing is the **level** (`roster` + `Member.liveness`), not the edge stream.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt` | **Modify.** The retry loop inside `runReconnect` gains a dwell timer on `ResumeWindowNotYetOpen` and a no-op-resume completion path; the `if (ok)` block gains the `onNoOpResume` call (see Amendment). |
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` | **Modify** (added by Amendment). Implement the new `JoinerResumeHost.onNoOpResume` callback: clear the host's liveness and emit `Recovered(hostId)`. |
| `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt` | **Create.** The #1637 repro plus the two guards that keep it honest. |
| `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/FastReconnectRaceTest.kt` | **Read only** — the #1572 regression guard. Must keep passing untouched. |

---

### Task 1: Pin the failing behaviour (RED)

**Files:**
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt`

**Interfaces:**
- Consumes: `SeamRoomFactory(loom, scope, clock, heartbeatConfig)`, `factory.adopt(seam, SessionRole, memberName, roomKey, reweave)`, `FlakyLifecycleLoom(InMemoryLoom(), scope)`, `MembershipEvent.{Partitioned,WindowOpened,Resumed,HostLost}`, `HeartbeatConfig(interval, timeout, reconnectWindow)`.
- Produces: nothing consumed by later tasks — this is the executable specification the implementation in Task 2 must satisfy.

The harness mirrors `AdoptTearTerminalTest` (same module), which is the canonical shape for this lane: `FlakyLifecycleLoom` reproduces a transport tear (`Woven → Weaving → Woven`) rather than `FaultySeam.partition`, which only drops frames and would fire `Timeout`, never reaching this code path.

**The fixed clock is load-bearing.** The detector measures silence as `clock() - lastSeen`, so freezing it makes a `Timeout` impossible — which is exactly the real-world condition being modelled: the host's silence timer never fires because the joiner's own retries keep refreshing it.

- [ ] **Step 1: Write the failing test**

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session.partition

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.session.MembershipEvent
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import us.tractat.kuilt.test.FlakyLifecycleLoom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * #1637 — a blip shorter than the host's liveness timeout must resume, not go terminal.
 *
 * The host never partitions the joiner (its own link never closed), so it answers every
 * `Resume` with `ResumeWindowNotYetOpen`. Each retry refreshes the host detector's
 * `lastSeen`, so no window will EVER open. Before the fix the joiner burned its whole
 * 60 s budget and died `HostLost(Refused)`.
 */
class SubTimeoutBlipResumeTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 200.milliseconds,
        reconnectWindow = 2000.milliseconds,
    )

    @Test
    fun `blip shorter than the host timeout resumes instead of going terminal`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock = { Instant.fromEpochMilliseconds(0L) }
            fun tick() {
                advanceTimeBy(100L)
                runCurrent()
            }

            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val factory = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
            )
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            val hostRoom = factory.adopt(hostSeam, SessionRole.Host, memberName = "Host")
            val joinerRoom = factory.adopt(
                joinerSeam,
                SessionRole.Joiner,
                memberName = "Joiner",
                reweave = { joinerSeam },
            )

            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val resumed = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first()
            }
            val hostLost = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }

            repeat(3) { tick() }

            // A tear the HOST never observes: the joiner's side re-forms, the host's link
            // stays up, so the host opens no window and keeps answering WindowNotYetOpen.
            loom.enterWeaving()
            repeat(2) { tick() }
            loom.recover()

            // Dwell past the host's timeout, then let the no-op resume complete. Bounded —
            // never advanceUntilIdle(); the heartbeat timers re-arm forever.
            repeat(20) { tick() }

            assertTrue(resumed.isCompleted, "joiner must resume after a sub-timeout blip")
            assertFalse(hostLost.isCompleted, "joiner must NOT go terminal on a sub-timeout blip")
        }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-session:jvmTest --tests "*SubTimeoutBlipResumeTest*"`
Expected: **FAIL** — `resumed.isCompleted` is false; the joiner is still retrying, or has already emitted `HostLost`. This is the #1637 repro.

- [ ] **Step 3: Commit the failing test**

```bash
git add kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt
git commit -m "test(session): sub-timeout blip loops to terminal instead of resuming (failing, part of #1637)"
```

---

### Task 2: Complete the episode as a no-op resume (GREEN)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt` — the `withTimeoutOrNull` retry loop inside `runReconnect`, at the `else` branch that currently only sleeps and retries.

**Interfaces:**
- Consumes: `refusal` (the recorded reject, already read in this loop as `lock.withLock { refusal }?.code?.retryable`), `RejectCode.ResumeWindowNotYetOpen`, `heartbeatConfig.timeout`, `clock()`.
- Produces: no new public API. The existing `resumed == true` branch is reused verbatim, so `restoreHostDetector` / guard-clearing / `MembershipEvent.Resumed` all fire exactly as on a real resume.

**Why this is safe.** `WindowNotYetOpen` is returned *only* when `windows[peerId] == null` — no window has ever been opened. An open window returns `Success`; an expired one returns `WindowClosed`. So a `WindowNotYetOpen` that persists past the host's own detector timeout is proof that no window is coming, not merely that one is late. The dwell is `heartbeatConfig.timeout` because that is the longest the host can take to open a window it intends to open.

- [ ] **Step 1: Add the dwell tracker to the retry loop**

Inside `runReconnect`, alongside `var failureReason`, add:

```kotlin
        // #1637: first instant we saw ResumeWindowNotYetOpen in THIS episode. A local on the
        // reconnect coroutine (the withTimeoutOrNull block runs inline on it), so no lock.
        var windowNotYetOpenSince: Instant? = null
```

- [ ] **Step 2: Replace the retry `else` branch**

Replace this existing block:

```kotlin
                } else {
                    // Fail fast ONLY on a code the host declared terminal (#1572). Everything
                    // else — a not-yet-open window, an unrecognised code, a host too old to send
                    // one — keeps retrying to the deadline, which is what recovers the
                    // fast-reconnect race.
                    if (lock.withLock { refusal }?.code?.retryable == false) return@withTimeoutOrNull false
                    delay(heartbeatConfig.interval)
                }
```

with:

```kotlin
                } else {
                    // Fail fast ONLY on a code the host declared terminal (#1572). Everything
                    // else — a not-yet-open window, an unrecognised code, a host too old to send
                    // one — keeps retrying to the deadline, which is what recovers the
                    // fast-reconnect race.
                    val code = lock.withLock { refusal }?.code
                    if (code?.retryable == false) return@withTimeoutOrNull false

                    // #1637: the host says no window has EVER opened for us. Only two things
                    // produce that answer — the #1572 fast-reconnect race (a window is coming,
                    // because the host's link closed and that fires TransportClosed at once), or
                    // a blip the host never observed at all. The second is self-sustaining: our
                    // own Resume frames refresh the host detector's lastSeen (any inbound frame
                    // calls observedPeer), so its silence never reaches HeartbeatConfig.timeout
                    // and no window can ever open. Retrying to the deadline then guarantees
                    // HostLost(Refused) on a link that is perfectly healthy.
                    //
                    // Dwelling past the host's own timeout discriminates them: a window the host
                    // intends to open is open by then. Past that, treat the episode as a no-op
                    // resume — we were never partitioned, so there is nothing to resume onto.
                    // The success branch below restores the host detector AND calls the new
                    // host.onNoOpResume(hostId) — see the Amendment: no ResumeAck arrives here, so
                    // handleResumeAck never runs, and without that call the Partitioned/WindowOpened
                    // arc this machine already emitted would stay open forever.
                    //
                    // WindowNotYetOpen is unambiguous: an OPEN window returns Success and an
                    // EXPIRED one returns WindowClosed, so this can never mask a real loss.
                    if (code == RejectCode.ResumeWindowNotYetOpen) {
                        val since = windowNotYetOpenSince ?: clock().also { windowNotYetOpenSince = it }
                        if (clock() - since >= heartbeatConfig.timeout) {
                            logger.info {
                                "resume.no-op host=$hostId roomId=${token.roomId.value} " +
                                    "reason=host-never-partitioned dwellMs=${heartbeatConfig.timeout.inWholeMilliseconds}"
                            }
                            ok = true
                            continue
                        }
                    } else {
                        windowNotYetOpenSince = null
                    }
                    delay(heartbeatConfig.interval)
                }
```

- [ ] **Step 3: Add the import**

At the top of `JoinerResumeMachine.kt`, add if not already present:

```kotlin
import us.tractat.kuilt.session.admit.RejectCode
```

- [ ] **Step 4: Run the Task 1 test and verify it passes**

Run: `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-session:jvmTest --tests "*SubTimeoutBlipResumeTest*"`
Expected: **PASS** — `Recovered(hostId)` emitted (see Amendment; **not** `Resumed`), no `HostLost`.

- [ ] **Step 5: Run the #1572 regression guard**

Run: `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-session:jvmTest --tests "*FastReconnectRaceTest*"`
Expected: **PASS**, unchanged. If this fails, the dwell is shorter than the host's window-open latency — do not lengthen the dwell blindly; read the failure and confirm which branch fired.

- [ ] **Step 6: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt
git commit -m "fix(session): a blip the host never observed completes as a no-op resume (part of #1637)"
```

---

### Task 3: Guard the two ways this could over-reach

**Files:**
- Modify: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt`

**Interfaces:**
- Consumes: everything from Task 1's harness.
- Produces: nothing.

The fix must not rescue a room that genuinely lost its host, and must not pre-empt a real resume.

- [ ] **Step 1: Write both guard tests**

```kotlin
    /**
     * Guard: the no-op path must NOT rescue a genuine host loss. A sustained tear never
     * re-forms, so `reweaveFn()` keeps yielding a seam that is not `Woven`, no reject is
     * ever recorded, the dwell never starts, and the window expires terminal.
     */
    @Test
    fun `sustained tear still ends terminal`() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val clock = { Instant.fromEpochMilliseconds(0L) }
            fun tick() {
                advanceTimeBy(100L)
                runCurrent()
            }

            val loom = FlakyLifecycleLoom(InMemoryLoom(), backgroundScope)
            val factory = SeamRoomFactory(
                loom = loom,
                scope = backgroundScope,
                clock = clock,
                heartbeatConfig = fastConfig,
            )
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))
            val hostRoom = factory.adopt(hostSeam, SessionRole.Host, memberName = "Host")
            val joinerRoom = factory.adopt(
                joinerSeam,
                SessionRole.Joiner,
                memberName = "Joiner",
                reweave = { joinerSeam },
            )
            hostRoom.roster.first { it.size == 1 }
            joinerRoom.roster.first { it.isNotEmpty() }

            val hostLost = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.HostLost>().first()
            }
            val resumed = backgroundScope.async {
                joinerRoom.events.filterIsInstance<MembershipEvent.Resumed>().first()
            }

            repeat(3) { tick() }
            loom.enterWeaving()          // and never recover()
            repeat(40) { tick() }

            assertAll(
                { assertTrue(hostLost.isCompleted, "a sustained tear must still go terminal") },
                { assertFalse(resumed.isCompleted, "the no-op path must not rescue a real loss") },
            )
        }
```

- [ ] **Step 2: Run both tests**

Run: `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-session:jvmTest --tests "*SubTimeoutBlipResumeTest*"`
Expected: **PASS** (both).

- [ ] **Step 3: Add the `assertAll` import**

```kotlin
import kotlin.test.assertAll
```

(If `kotlin.test.assertAll` is unavailable in this Kotlin version, use the same helper `AdoptTearTerminalTest` imports — copy its import line verbatim rather than inventing one.)

- [ ] **Step 4: Commit**

```bash
git add kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/SubTimeoutBlipResumeTest.kt
git commit -m "test(session): the no-op resume must not rescue a genuine host loss (part of #1637)"
```

---

### Task 4: Full verification and PR

**Files:** none modified.

- [ ] **Step 1: Full build, cache disabled**

Run: `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew build detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL, tasks `EXECUTED` not `FROM-CACHE`. This is the only run that proves the Android and Kotlin/Native variants compile and that the `:examples` / `:kuilt-cluster` E2E cluster invariants still hold.

Note: `:kuilt-warp-heddle:allTests` has an unrelated `iosSimulatorArm64` flake under machine load (`UncompletedCoroutinesError`) — check `uptime` before believing a timing failure, and re-run on an idle box. If it reproduces idle, it is a separate bug; file it, do not fold it into this PR.

- [ ] **Step 2: Open the PR**

```bash
git push -u origin fix/1637-sub-timeout-blip
gh pr create --title "fix(session): a blip the host never observed resumes instead of dying (part of #1637)" --body "..."
```

Body must state: the mechanism (host `lastSeen` refreshed by the joiner's own Resume frames), why the fix is joiner-side rather than host-side (the host cannot discriminate #1637 from #1572 at the instant a Resume arrives), and that the dwell is `HeartbeatConfig.timeout`.

- [ ] **Step 3: Hardware check before closing the issue**

Do **not** `closes #1637`. Use `part of #1637`. Two phones, per `docs/one-phone-hardware-debugging.md`: airplane-mode the **joiner** (identify it from `lobby.freeze-matched` vs `lobby.freeze-round` in the logs) for **~8 seconds** — under the 15 s host timeout, over the 10 s `wovenPathGrace`. Expect `resume.no-op` in the joiner's log and a `Recovered(hostId)` membership event (see Amendment), where the current build produces `HostLost(Refused)` at ~60 s. Close by hand once that reproduces.

---

## Self-Review Notes

- **Spec coverage:** #1637's body names one defect (sub-timeout blip → `HostLost(Refused)`); Task 2 fixes it, Task 3 guards both over-reach directions, Task 4 validates against the reproducer that found it.
- **The issue says "joiner-side handling"** and this plan agrees — but for a reason the issue does not state: a host-side fix is *unsound*, because at the instant a `Resume` arrives the host cannot distinguish this from #1572. That reasoning is captured in the code comment so it is not re-litigated.
- **Type consistency:** `RejectCode.ResumeWindowNotYetOpen`, `heartbeatConfig.timeout`, `clock()`, `refusal`, `ok`, `hostId`, `token` all already exist in `runReconnect`'s scope; no new types are introduced.
- **Open risk:** the dwell adds `HeartbeatConfig.timeout` (15 s default) before a sub-timeout blip recovers, so recovery lands at roughly 25 s rather than the 60 s failure. Acceptable, and it fits inside the 60 s `reconnectWindow` budget, but if a shorter recovery is wanted later the discriminator has to come from the host rather than from waiting — that would need a wire change and is out of scope here.
