# Seam lifecycle (`SeamState`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fabric-lifecycle contract (`SeamState`: `Weaving → Woven → Torn(reason)`) to `Seam` so consumers can tell "the fabric is live" from "I'm alone" from "it failed," ending the silent-drop-into-hang class of bug.

**Architecture:** New `state: StateFlow<SeamState>` on the `Seam` interface (no default — every fabric must drive it honestly). Send semantics pinned down (`broadcast`-to-no-one = observable no-op; `sendTo`-to-absent-peer throws). The conformance suite gains the timing invariants plus a delayed-`Woven` harness so radio fabrics can't regress. `SeamRoom` then awaits `Woven` instead of poking `peers.size > 1`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (`StateFlow`), kotlin.test. JDK 21 (`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`).

**Design ref:** `docs/superpowers/specs/2026-05-30-seam-lifecycle-design.md`

---

## Context: the quick unblock is already handled

The interim hotfix (`SeamRoom` waits for `peers.size > 1`) is in PR #46 with auto-merge enabled. When it merges, `publish.yml` ships a new `0.3.<run>` to Tigris automatically, which unblocks the consumer on a published artifact. **This plan is the fuller hardening; it is not on the unblock critical path.** PR 2 below replaces the hotfix's `peers` poke with the `Woven` await once the contract lands.

## File structure

| File | Responsibility | PR |
|------|----------------|----|
| `kuilt-core/.../core/SeamState.kt` (create) | The lifecycle sealed interface | 1 |
| `kuilt-core/.../core/Seam.kt` (modify) | Add `val state` | 1 |
| `kuilt-core/.../core/CloseReason.kt` (modify) | Add `Unreachable` | 1 |
| `kuilt-core/.../core/SeamErrors.kt` (create) | `PeerNotConnected` | 1 |
| `kuilt-core/.../core/InMemoryLoom.kt` (modify) | Reference impl drives `state`; `sendTo` throws on absent peer | 1 |
| `kuilt-conformance/.../conformance/SeamConformanceSuite.kt` (modify) | Lifecycle invariants every fabric must pass | 1 |
| `kuilt-conformance/.../conformance/DelayedWovenLoom.kt` (create, test src) | Fake fabric staying `Weaving` after `weave()` | 1 |
| `kuilt-conformance/.../conformance/DelayedWovenSeamTest.kt` (create) | Asserts no frame lost across the `Weaving` window | 1 |
| each fabric module's `Seam` impl | Drive `state` honestly (compile-gating) | 1 |
| `kuilt-session/.../session/SeamRoom.kt` (modify) | Await `Woven` instead of `peers.size > 1` | 2 |
| `kuilt-session/.../session/SeamRoomJoinerHelloRaceTest.kt` (modify) | Drive the fake via `state`, not `peers` | 2 |

---

## PR 1 — Core contract + reference impl + conformance

Atomic across modules: the interface change won't compile until every `Seam` impl provides `state`. Land core + InMemory + conformance + all adapters in one PR.

### Task 1: `SeamState` sealed interface

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/SeamState.kt`

- [ ] **Step 1: Write the type**

```kotlin
package us.tractat.kuilt.core

/**
 * One peer's view of whether the fabric can carry frames.
 *
 * Orthogonal to [Seam.peers]: [Woven] with `peers == {selfId}` is a fully
 * legitimate state — the fabric is live and this peer is simply alone in the
 * session. "Can I inject frames" (this) and "who can I reach" ([Seam.peers])
 * are different questions.
 *
 * Defined as an aggregate over the fabric's constituent links: [Weaving] when no
 * link is live, [Woven] when at least one is, [Torn] when all are gone. Today every
 * fabric has exactly one link, so the aggregate equals that link's state.
 */
public sealed interface SeamState {
    /** The fabric is forming. Sends may reach no one and must not be relied on. */
    public data object Weaving : SeamState

    /** The fabric is live. Frames broadcast now are carried to the current peers. */
    public data object Woven : SeamState

    /** Terminal. The fabric is gone; [reason] says why. */
    public data class Torn(val reason: CloseReason) : SeamState
}
```

- [ ] **Step 2: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/SeamState.kt
git commit -m "feat(core): add SeamState fabric lifecycle type"
```

### Task 2: `CloseReason.Unreachable` + `PeerNotConnected`

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/CloseReason.kt`
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/SeamErrors.kt`

- [ ] **Step 1: Add the `Unreachable` variant** to `CloseReason` (after `RemoteRequested`):

```kotlin
    /** The fabric never wove — e.g. the join target was absent or the handshake timed out. */
    public data object Unreachable : CloseReason
```

- [ ] **Step 2: Create the exception**

```kotlin
package us.tractat.kuilt.core

/** Thrown by [Seam.sendTo] when the addressed [peer] is not in the connected peer set. */
public class PeerNotConnected(
    public val peer: PeerId,
) : IllegalStateException("peer not connected: ${peer.value}")
```

- [ ] **Step 3: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/CloseReason.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/SeamErrors.kt
git commit -m "feat(core): add CloseReason.Unreachable and PeerNotConnected"
```

### Task 3: Add `state` to the `Seam` interface

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt`

- [ ] **Step 1: Add the member** after `peers` (no default — every impl must provide it):

```kotlin
    /**
     * This peer's view of the fabric lifecycle: [SeamState.Weaving] →
     * [SeamState.Woven] → [SeamState.Torn]. Distinct from [peers]: a host alone
     * in a live session is `Woven` with `peers == {selfId}`. Broadcasts issued
     * before `Woven` may reach no one.
     */
    public val state: StateFlow<SeamState>
```

- [ ] **Step 2: Build core only — expect every other module to break later, but core's own impl (InMemory) will fail to compile now**

Run: `./gradlew :kuilt-core:compileKotlinJvm`
Expected: FAIL — `InMemorySeam` does not implement `state`. (Fixed in Task 4.)

### Task 4: `InMemorySeam` drives `state`; `sendTo` throws on absent peer (TDD)

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt`

- [ ] **Step 1: Write failing tests** (append to `InMemoryLoomTest`):

```kotlin
@Test
fun inMemorySeamIsWovenAtConstruction() = runTest {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("h"))
    assertEquals(SeamState.Woven, seam.state.value)
}

@Test
fun inMemorySeamIsTornNormalAfterClose() = runTest {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("h"))
    seam.close()
    assertEquals(SeamState.Torn(CloseReason.Normal), seam.state.value)
}

@Test
fun sendToUnknownPeerThrowsPeerNotConnected() = runTest {
    val loom = InMemoryLoom()
    val seam = loom.host(Pattern("h"))
    assertFailsWith<PeerNotConnected> { seam.sendTo(PeerId("ghost"), byteArrayOf(1)) }
}
```

- [ ] **Step 2: Run, expect compile failure / FAIL**

Run: `./gradlew :kuilt-core:jvmTest --tests "*InMemoryLoomTest"`
Expected: FAIL (state unimplemented; sendTo does not throw).

- [ ] **Step 3: Implement in `InMemorySeam`** — add the backing flow and drive it:

```kotlin
    private val _state = MutableStateFlow<SeamState>(SeamState.Woven)
    override val state: StateFlow<SeamState> = _state.asStateFlow()
```

In `close()`, after `closed = true`:

```kotlin
        _state.value = SeamState.Torn(reason)
```

In `sendTo`, replace the self-check block so an absent peer throws (keep the existing self `require`):

```kotlin
    override suspend fun sendTo(
        peer: PeerId,
        payload: ByteArray,
    ) {
        checkNotClosed()
        require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }
        if (peer !in factory.peers.value) throw PeerNotConnected(peer)
        factory.dispatch(sender = selfId, payload = payload, recipient = peer)
    }
```

(Imports `MutableStateFlow` is already present; `asStateFlow` is already imported.)

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :kuilt-core:jvmTest --tests "*InMemoryLoomTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomTest.kt
git commit -m "feat(core): InMemorySeam drives SeamState; sendTo throws on absent peer"
```

### Task 5: Conformance lifecycle invariants (TDD across fabrics)

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt`

- [ ] **Step 1: Add tests** to the suite (after the existing tests). These run against every fabric subclass:

```kotlin
    // ── (7) a connected seam reaches Woven, even alone ──────────────────────
    @Test
    public fun seamReachesWovenAfterConnect(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val hostDeferred = async { hostLoom.host(Pattern("host")) }
                val joinerDeferred = async { joinerLoom.join(joinTag()) }
                val host = hostDeferred.await()
                joinerDeferred.await()
                host.state.first { it is SeamState.Woven }   // must reach Woven
            }
        }

    // ── (8) sendTo an unknown peer throws PeerNotConnected ──────────────────
    @Test
    public fun sendToUnknownPeerThrows(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val host = async { hostLoom.host(Pattern("host")) }.await()
                async { joinerLoom.join(joinTag()) }.await()
                assertFailsWith<PeerNotConnected> {
                    host.sendTo(PeerId("definitely-not-a-peer"), byteArrayOf(1))
                }
            }
        }

    // ── (9) close drives Torn(Normal) ───────────────────────────────────────
    @Test
    public fun closeDrivesTornNormal(): TestResult =
        runTest {
            val (hostLoom, joinerLoom) = newLoomPair()
            coroutineScope {
                val host = async { hostLoom.host(Pattern("host")) }.await()
                async { joinerLoom.join(joinTag()) }.await()
                host.close()
                assertEquals(SeamState.Torn(CloseReason.Normal), host.state.value)
            }
        }
```

Add imports: `kotlinx.coroutines.flow.first`, `kotlin.test.assertFailsWith`, `us.tractat.kuilt.core.CloseReason`, `us.tractat.kuilt.core.PeerId`, `us.tractat.kuilt.core.PeerNotConnected`, `us.tractat.kuilt.core.SeamState`.

- [ ] **Step 2: Run against InMemory, expect PASS**

Run: `./gradlew :kuilt-conformance:jvmTest --tests "*InMemoryLoomConformanceTest"`
Expected: PASS (InMemory already drives state from Task 4).

- [ ] **Step 3: Commit**

```bash
git add kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt
git commit -m "test(conformance): assert SeamState lifecycle + sendTo-throws + close-Torn"
```

### Task 6: Delayed-`Woven` harness + frame-loss regression

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DelayedWovenSeamTest.kt`

This reproduces the bug class at the library level — relay/in-memory fabrics weave instantly and can't exercise the window alone.

- [ ] **Step 1: Write the fake + test**

```kotlin
package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fabric that stays [SeamState.Weaving] until [weave] is called, mimicking radio
 * fabrics (Multipeer/WebRTC) that hand back a Seam before the link is connected.
 * Guards against the silent-drop-into-hang class of bug at the contract level.
 */
private class DelayedWovenSeam(override val selfId: PeerId) : Seam {
    private val _peers = MutableStateFlow(setOf(selfId))
    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    val sent = mutableListOf<ByteArray>()

    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()
    override val state: StateFlow<SeamState> = _state.asStateFlow()
    override val incoming: Flow<Swatch> = MutableSharedFlow()

    fun weave(remote: PeerId) {
        _peers.value = setOf(selfId, remote)
        _state.value = SeamState.Woven
    }

    override suspend fun broadcast(payload: ByteArray) {
        // contract: broadcasting while Weaving reaches no one (no-op); once Woven it's recorded.
        if (_state.value is SeamState.Woven) sent += payload
    }
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
    override suspend fun close(reason: CloseReason) { _state.value = SeamState.Torn(reason) }
}

class DelayedWovenSeamTest {
    @Test
    fun awaitingWovenBeforeBroadcastLosesNoFrame() = runTest {
        val seam = DelayedWovenSeam(PeerId("self"))
        // A consumer that awaits Woven before sending:
        val consumer = kotlinx.coroutines.async {
            seam.state.first { it is SeamState.Woven }
            seam.broadcast(byteArrayOf(42))
        }
        // Fabric not yet woven — nothing sent.
        assertTrue(seam.sent.isEmpty(), "no frame before Woven")
        seam.weave(PeerId("remote"))
        consumer.await()
        assertEquals(1, seam.sent.size, "frame delivered exactly once after Woven")
        assertEquals(42, seam.sent.single().single())
    }
}
```

- [ ] **Step 2: Run, expect PASS**

Run: `./gradlew :kuilt-conformance:jvmTest --tests "*DelayedWovenSeamTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/DelayedWovenSeamTest.kt
git commit -m "test(conformance): delayed-Woven harness proves await-Woven loses no frame"
```

### Task 7: Make every fabric adapter drive `state` (compile-gating)

The interface member is non-default, so each module's `Seam` impl must implement it before the full build compiles. **One sub-task per module.** For each: read the module's `Seam` implementation, add the backing flow, drive `Woven`/`Torn` at the right transport event, then run that module's build.

The shape to add to each `Seam` impl:

```kotlin
private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
override val state: StateFlow<SeamState> = _state.asStateFlow()
// ... set _state.value = SeamState.Woven when the link is live;
// ... set _state.value = SeamState.Torn(reason) on close/disconnect.
```

Drive points per module (confirm the exact symbol when editing):

- [ ] **`kuilt-websocket`** — `Woven` once the Ktor WS session is open (the point `weave()` currently returns the seam); `Torn(reason)` on close/exception. Run: `./gradlew :kuilt-websocket:build`
- [ ] **`kuilt-multipeer`** — **the bug's epicenter.** `Woven` from the `MCSession` `didChangeState` callback when state becomes `.connected` (the same callback that already updates `_peers`); `Torn(CloseReason.Unreachable)` on establish-timeout; `Torn(reason)` on disconnect. Run: `./gradlew :kuilt-multipeer:build` (Apple targets only build on macOS).
- [ ] **`kuilt-mdns`** — discovery feeds a websocket seam; delegate `state` to the underlying websocket seam's `state`. Run: `./gradlew :kuilt-mdns:build`
- [ ] **`kuilt-webrtc`** — `Woven` when the peer connection reaches connected; `Torn(Unreachable)` on ICE failure/timeout. Run: `./gradlew :kuilt-webrtc:build`
- [ ] **`kuilt-nearby`** — `Woven` on the Nearby connection established callback; `Torn(reason)` on disconnect. Run: `./gradlew :kuilt-nearby:build`

For each module also add (or confirm) its `SeamConformanceSuite` subclass passes the new tests where a loopback/in-memory harness exists; real-radio paths stay `-P`-gated as today.

- [ ] **Commit per module**, e.g.:

```bash
git add kuilt-multipeer/
git commit -m "feat(multipeer): drive SeamState.Woven from MCSession connected callback"
```

### Task 8: Full build green + open PR

- [ ] **Step 1: Full build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build`
Expected: BUILD SUCCESSFUL (all targets, all modules, conformance green). On non-macOS, Apple targets are skipped — note this and ensure CI (which runs on macOS for Apple targets) is green before merge.

- [ ] **Step 2: Push + open PR**

```bash
git push -u origin feat/seam-state-lifecycle
gh pr create --title "feat(core): SeamState fabric lifecycle (Weaving/Woven/Torn)" \
  --body "Implements docs/superpowers/specs/2026-05-30-seam-lifecycle-design.md. Closes the contract gap behind the silent-drop hang. Every fabric now drives state; conformance suite holds them to the timing invariants."
gh pr merge --auto --squash
```

---

## PR 2 — `SeamRoom` awaits `Woven` (supersedes the hotfix poke)

Open only after PR 1 has merged and published, so the `SeamState` API is resolvable.

### Task 9: Switch `SeamRoom` to `state == Woven`

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:297-304`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/SeamRoomJoinerHelloRaceTest.kt`

- [ ] **Step 1: Update the regression test** to drive the fake via `state` rather than `peers` — the fake `Seam` gains a `MutableStateFlow<SeamState>` starting `Weaving`, flipping to `Woven` instead of (or alongside) the peer-set change; assert Hello is not broadcast until `Woven`. (Mirror `DelayedWovenSeam` from Task 6.)

- [ ] **Step 2: Run, expect FAIL** (SeamRoom still gates on `peers.size > 1`)

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamRoomJoinerHelloRaceTest"`
Expected: FAIL.

- [ ] **Step 3: Replace the gate** in `runMainLoop`:

```kotlin
    private suspend fun runMainLoop() {
        if (_role.value == SessionRole.Joiner) {
            seam.state.first { it is SeamState.Woven }
            sendHello()
        }
        seam.incoming.collect { swatch ->
            rawIncoming.emit(swatch)
            dispatchIncoming(swatch)
        }
    }
```

Replace the `import kotlinx.coroutines.flow.filter` usage if no longer needed; add `import us.tractat.kuilt.core.SeamState` and keep `import kotlinx.coroutines.flow.first`.

- [ ] **Step 4: Run, expect PASS**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamRoomJoinerHelloRaceTest"`
Expected: PASS.

- [ ] **Step 5: Full build, commit, PR**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build
git add kuilt-session/
git commit -m "refactor(session): SeamRoom awaits SeamState.Woven, dropping the peers.size poke"
git push -u origin refactor/seamroom-await-woven
gh pr create --title "refactor(session): SeamRoom awaits Woven" --body "Supersedes the interim peers.size>1 hotfix now that SeamState has landed."
gh pr merge --auto --squash
```

---

## Self-review notes

- **Spec coverage:** `SeamState` (Task 1), `CloseReason.Unreachable` + `PeerNotConnected` (Task 2), `Seam.state` (Task 3), reference impl + send semantics (Task 4), conformance invariants (Task 5), delayed-`Woven` harness (Task 6), all-fabric rollout (Task 7), `SeamRoom` fix (Task 9). Rollup-over-plies framing is documented in the spec; axis-3 `plies` is explicitly deferred to the Ply brief.
- **`broadcast`-to-empty observable no-op:** real adapters should `log.warn`/`debug` on empty-target sends (multipeer already does after the hotfix); the in-memory reference stays a silent no-op (no logging dependency in core) — the contract is "no *post-Woven* frame lost," which Task 6 verifies.
- **Type consistency:** `SeamState.Weaving/Woven/Torn`, `CloseReason.Unreachable`, `PeerNotConnected(peer)` used identically across all tasks.
