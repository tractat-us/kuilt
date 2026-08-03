# Root-Hash-Gated Anti-Entropy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `Quilter`'s anti-entropy tick ship a small constant-size frame carrying a hash of the CRDT state instead of the whole state, sending state only when the hashes disagree. (Phase 0 sized that frame at ~31 b; the shipped frame measured 54–57 b — see Task 5.)

**Architecture:** Two new `QuiltMessage` variants (`RootDigest`, `FullStateRequest`) turn the tick into a digest exchange; `FullState` is untouched and stays the always-correct fallback. The digest is 64-bit FNV-1a over the `binaryFormat`-encoded state, computed in `:kuilt-quilter` under the existing lock. `RootDigest` **must** carry the sender's `upThrough` high-water, because today's anti-entropy `FullState` doubles as the carrier that resyncs the receiver's delta cursor (#1266).

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization (CBOR wire format), kotlinx-coroutines-test, JUnit5 on JVM. Gradle 9.4.1, JDK 21.

**Spec:** [`docs/superpowers/specs/2026-08-03-root-hash-anti-entropy-design.md`](../specs/2026-08-03-root-hash-anti-entropy-design.md) · **Issue:** [#1955](https://github.com/tractat-us/kuilt/issues/1955)

## Global Constraints

- **JDK/toolchain:** source SDKMAN first in every non-interactive shell — `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit `public`. Internal helpers get `internal`.
- **`:kuilt-crdt` must gain no new production dependency.** All work in this plan lands in `:kuilt-quilter` (plus one test in `:kuilt-scale`).
- **Test naming:** no `test` prefix — `@Test` suffices. Multi-assert tests use `assertAll()` from `us.tractat.kuilt.test`.
- **No production dispatchers in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`). Use `UnconfinedTestDispatcher(testScheduler)` / `StandardTestDispatcher(testScheduler)`.
- **Bounded virtual time only.** Drive anti-entropy with bounded `advanceTimeBy` + `runCurrent()`; **never `advanceUntilIdle()`** on a re-arming timer. `runTest(timeout = …)` is a generous wedge backstop, never a tight assertion — 30 s is the established value; do not tighten it.
- **Exception discipline:** best-effort sends use `runCatchingCancellable { … }` (from `:kuilt-core`), never bare `runCatching`.
- **Never use the word "chore"** in a commit message or PR title.
- **Commit messages end with:** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`
- **Backticks do NOT disarm closing keywords in commit messages.** A squash turns the PR body into a commit message. Use "part of #1955" on every commit and PR in this plan; only the final PR may close it.
- **FNV-1a 64 constants** (verified against the published vector for `"a"` = `0xaf63dc4c8601ec8c`): offset basis `-3750763034362895579L`, prime `1099511628211L`.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Fnv1a64.kt` | **Create.** The hash, alone, so it is independently testable and cannot quietly accrete callers. |
| `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/Fnv1a64GoldenVectorTest.kt` | **Create.** Cross-target absolute pins for the hash. |
| `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/QuiltMessage.kt` | **Modify.** Add `RootDigest`, `FullStateRequest`. |
| `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt` | **Modify.** Narrow `resyncReceiveCursor`; add `onRootDigest`, `onFullStateRequest`; flip `reconcileWithRandomPeer`. |
| `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterRootDigestTest.kt` | **Create.** Receive-side handler behaviour. |
| `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterAntiEntropyDigestResyncTest.kt` | **Create.** The #1266-for-anti-entropy trap. |
| `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterStateRootGoldenVectorTest.kt` | **Create.** Cross-target pins for the root of concrete `GSet`/`LWWMap` states. |
| `kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/MerkleDigestCostModelTest.kt` | **Modify.** Part (B) currently asserts exact `FullState × rounds` bytes and will break by design; becomes the measured acceptance test. |
| `docs/gossip-mesh-design.md` | **Modify.** Prose still describes full-state anti-entropy. |
| `Writerside/topics/partial-mesh.md` | **Modify.** Guide page states the tick reconciles full state. |
| `kuilt-quilter/module.md` | **Modify.** Enumerates the `QuiltMessage` variants; becomes incomplete. |

**Task order is dependency order.** Tasks 1–2 are independent of each other; 3 needs 1 and 2; 4 needs 3; 5 and 6 need 4.

---

### Task 1: The FNV-1a 64 hash, pinned cross-target

**Files:**
- Create: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Fnv1a64.kt`
- Test: `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/Fnv1a64GoldenVectorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal fun fnv1a64(bytes: ByteArray): Long` in package `us.tractat.kuilt.quilter`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/Fnv1a64GoldenVectorTest.kt`:

```kotlin
package us.tractat.kuilt.quilter

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Absolute cross-target pins for [fnv1a64].
 *
 * `commonTest` compiles and runs on JVM, Android, iOS, macOS and wasmJs, so these constants hold
 * every target to the same arithmetic — the property a peer-to-peer digest depends on and that a
 * JVM-only run cannot establish. The `"a"` vector is the published FNV-1a 64 test vector
 * (`0xaf63dc4c8601ec8c`), so a wrong basis or prime fails here rather than silently shipping a
 * hash that only agrees with itself.
 *
 * Note `Long` overflow is the *intended* arithmetic: FNV-1a is defined mod 2^64, and Kotlin's
 * wrapping `Long` multiply is exactly that.
 */
class Fnv1a64GoldenVectorTest {

    @Test
    fun pinnedVectors() = assertAll(
        { assertEquals(-3750763034362895579L, fnv1a64(ByteArray(0)), "empty input must be the offset basis") },
        { assertEquals(-5808556873153909620L, fnv1a64("a".encodeToByteArray()), "published FNV-1a 64 vector for \"a\"") },
        { assertEquals(-6382011383256120612L, fnv1a64("kuilt".encodeToByteArray()), "\"kuilt\"") },
        { assertEquals(4932904490461320209L, fnv1a64(byteArrayOf(0, 1, 2, 3)), "raw bytes incl. a zero byte") },
    )

    @Test
    fun highBitBytesAreFoldedUnsigned() {
        // The classic FNV port error is folding `byte.toLong()` without `and 0xFF`, so a byte >=
        // 0x80 sign-extends. It is invisible for ASCII, which is why every vector above would pass
        // with the bug present — only an ABSOLUTE pin on a high-bit byte catches it.
        //
        // Do NOT weaken these to relational assertions. `byteArrayOf(-1)` and
        // `byteArrayOf(0xFF.toByte())` are the same value, so comparing them is a tautology, and
        // 0x7F vs 0xFF differ under the bug too — both forms pass while broken.
        //
        // Reference values (correct / buggy) for 0xFF: -5808391946409677970 / 5808589858502755950.
        assertAll(
            { assertEquals(-5808391946409677970L, fnv1a64(byteArrayOf(0xFF.toByte())), "0xFF must fold as 255, not -1") },
            { assertEquals(-5808450220525973153L, fnv1a64(byteArrayOf(0x80.toByte())), "0x80 must fold as 128") },
        )
    }

    @Test
    fun orderMatters() =
        kotlin.test.assertNotEquals(
            fnv1a64(byteArrayOf(1, 2)),
            fnv1a64(byteArrayOf(2, 1)),
            "FNV-1a is order-sensitive; a commutative fold would break divergence detection",
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-quilter:jvmTest --tests "*Fnv1a64GoldenVectorTest"
```

Expected: **compile failure** — `Unresolved reference: fnv1a64`. That is the correct red for step 2; a compile error is only unacceptable as a *mutation* result (Task 4), not here.

- [ ] **Step 3: Write minimal implementation**

Create `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Fnv1a64.kt`:

```kotlin
package us.tractat.kuilt.quilter

/** FNV-1a 64 offset basis (`0xcbf29ce484222325`). */
private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L

/** FNV-1a 64 prime (`0x100000001b3`). */
private const val FNV_PRIME: Long = 1099511628211L

/**
 * 64-bit FNV-1a over [bytes].
 *
 * Used to summarise a CRDT's canonical encoding so two peers can decide whether they have
 * converged without shipping the state (#1955). Matches the constants used by
 * `:kuilt-conformance`'s `canonicalDigest`, so the two agree by construction — though nothing
 * requires them to: that one is a test/harness divergence alarm, this one is peer-to-peer.
 *
 * Pinned cross-target by `Fnv1a64GoldenVectorTest`. `Long` arithmetic wraps, which is exactly
 * FNV-1a's mod-2^64 definition.
 *
 * **Not cryptographic.** Fine against accidental divergence; no defence against a peer that
 * forges a matching digest. Correctness never rests on it — a mismatch only triggers the
 * `FullState` path that was previously unconditional.
 */
internal fun fnv1a64(bytes: ByteArray): Long {
    var hash = FNV_OFFSET_BASIS
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :kuilt-quilter:jvmTest --tests "*Fnv1a64GoldenVectorTest"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Prove the vectors are genuinely cross-target**

`ci-required` runs on Linux, so wasmJs is the only non-JVM target in the per-PR gate. Run it:

```bash
./gradlew :kuilt-quilter:wasmJsBrowserTest
```

Expected: PASS. (`wasmJsTest` is a lifecycle task that rejects `--tests` — use `wasmJsBrowserTest`.) Also run `./gradlew :kuilt-quilter:macosArm64Test` locally; Apple targets are nightly-only in CI, so this is the one chance to see them before merge.

- [ ] **Step 6: Commit**

```bash
git add kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Fnv1a64.kt \
        kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/Fnv1a64GoldenVectorTest.kt
git commit -m "feat(quilter): add FNV-1a 64 with cross-target golden vectors (part of #1955)

Pins the published vector for \"a\" (0xaf63dc4c8601ec8c) plus an unsigned-byte-folding
case, the classic FNV port error that is invisible for ASCII input.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Narrow `resyncReceiveCursor` to its actual inputs

A pure refactor with **no behaviour change**, landed separately so a reviewer can gate it as such. It exists because `onRootDigest` (Task 3) must enter the same #1266 cursor-resync path as `onFullState`, and duplicating that logic is how the two drift.

**Files:**
- Modify: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt:816-832` (`resyncReceiveCursor`) and its one call site at `:801`.

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `private fun resyncReceiveCursor(sender: PeerId, senderReplica: ReplicaId, upThrough: Long)` — the signature Task 3's `onRootDigest` calls.

- [ ] **Step 1: Confirm the current tests are green before touching anything**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-quilter:jvmTest --tests "*QuilterFullStateResyncTest"
```

Expected: PASS. This is the #1266 test and it is the behaviour-preservation oracle for this task.

- [ ] **Step 2: Change the signature**

In `Quilter.kt`, replace the declaration line of `resyncReceiveCursor`:

```kotlin
    private fun resyncReceiveCursor(sender: PeerId, msg: QuiltMessage.FullState<S>) {
        if (msg.upThrough <= 0L) return
        val senderReplica = msg.sender
```

with:

```kotlin
    private fun resyncReceiveCursor(sender: PeerId, senderReplica: ReplicaId, upThrough: Long) {
        if (upThrough <= 0L) return
```

Then inside the body, replace every remaining `msg.upThrough` with `upThrough`. There are four: the two in the `if (msg.upThrough >= expected)` block (the comparison and `expectedReceiveSeq[senderReplica] = msg.upThrough + 1`), the `buffer.keys.removeAll { it <= msg.upThrough }` predicate, and the `seq = msg.upThrough` argument to `sendAck`.

- [ ] **Step 3: Update the sole call site**

In `onFullState` (`Quilter.kt:801`), replace:

```kotlin
        resyncReceiveCursor(sender, msg)
```

with:

```kotlin
        resyncReceiveCursor(sender, msg.sender, msg.upThrough)
```

- [ ] **Step 4: Update the KDoc reference**

`resyncReceiveCursor`'s KDoc opens "Fast-forwards the per-sender receive cursor past the history a just-absorbed [QuiltMessage.FullState] already covers (#1266)." Change that first sentence to:

```kotlin
     * Fast-forwards the per-sender receive cursor past the history [upThrough] already covers
     * (#1266). Called from both [onFullState] and [onRootDigest] — an anti-entropy round must
     * resync the cursor whether or not it ships state.
```

Leave the rest of the KDoc as-is. (`[onRootDigest]` does not exist until Task 3; Dokka only warns on unresolved links and the required doc check runs on modules, so this will not fail the build — Task 3 creates it.)

- [ ] **Step 5: Run tests to verify no behaviour changed**

```bash
./gradlew :kuilt-quilter:jvmTest
```

Expected: PASS, no test edits needed. If any test required a change, the refactor was not behaviour-preserving — stop and re-read.

- [ ] **Step 6: Commit**

```bash
git add kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt
git commit -m "refactor(quilter): narrow resyncReceiveCursor to (senderReplica, upThrough) (part of #1955)

It only ever used those two fields of FullState. Taking them directly lets the incoming
root-digest handler enter the same #1266 cursor-resync path instead of duplicating it.
No behaviour change.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The wire variants and the receive side

Adds both message types and both handlers. The send side still ships `FullState`, so **no existing test changes behaviour** in this task — which is what makes it independently reviewable.

Note `Quilter.dispatch`'s `when (msg)` is exhaustive over the sealed class, so adding variants without handling them is a compile error. That is why the variants and handlers land together.

**Files:**
- Modify: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/QuiltMessage.kt`
- Modify: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt`
- Test: `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterRootDigestTest.kt`

**Interfaces:**
- Consumes: `fnv1a64(ByteArray): Long` (Task 1); `resyncReceiveCursor(PeerId, ReplicaId, Long)` (Task 2).
- Produces:
  - `QuiltMessage.RootDigest<S>(sender: ReplicaId, root: Long, upThrough: Long)`, `@SerialName("rootDigest")`
  - `QuiltMessage.FullStateRequest<S>(requester: ReplicaId, sender: ReplicaId)`, `@SerialName("fullStateRequest")`
  - `private fun stateRoot(): Long` on `Quilter` — must be called under `lock`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterRootDigestTest.kt`:

```kotlin
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Receive-side behaviour of the #1955 digest exchange, driven by injecting frames into a
 * [FakeSeam] and reading back what the [Quilter] sent via [FakeSeam.directed].
 *
 * The send side still ships `FullState` at this point in the plan, so nothing here depends on
 * the anti-entropy tick.
 */
class QuilterRootDigestTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")

    private fun encode(msg: QuiltMessage<GSet<String>>): ByteArray =
        Cbor.encodeToByteArray(msgSer, msg)

    private fun decoded(bytes: ByteArray): QuiltMessage<GSet<String>> =
        Cbor.decodeFromByteArray(msgSer, bytes)

    /**
     * Must mirror `Quilter.stateRoot()` exactly: the root is FNV-1a over the state encoded inside a
     * synthetic `FullState` with [ReplicaId.Bottom] and `upThrough = 0L`, because the class holds no
     * `KSerializer<S>`. Hashing the bare state here instead would silently take the mismatch branch.
     */
    private fun expectedRoot(state: GSet<String>): Long = fnv1a64(
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)),
    )

    private fun quilterOn(seam: FakeSeam, scope: kotlinx.coroutines.CoroutineScope, initial: GSet<String>) =
        Quilter(
            replica = ReplicaId(seam.selfId.value),
            seam = seam,
            initial = initial,
            messageSerializer = msgSer,
            scope = scope,
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
        )

    @Test
    fun matchingRootShipsNoState() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, encode(QuiltMessage.RootDigest(peerReplica, expectedRoot(GSet.of("x")), upThrough = 0L)))
        testScheduler.runCurrent()

        val sentAfter = seam.directed.drop(before).map { decoded(it.second) }
        assertAll(
            { assertTrue(sentAfter.none { it is QuiltMessage.FullState }, "a matched root must not ship state") },
            { assertTrue(sentAfter.none { it is QuiltMessage.FullStateRequest }, "a matched root must not request state") },
            { assertEquals(setOf("x"), quilter.state.value.elements, "state untouched") },
        )
    }

    @Test
    fun mismatchedRootRequestsFullState() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        quilterOn(seam, backgroundScope, GSet.of("x"))
        testScheduler.runCurrent()

        val before = seam.directed.size
        seam.deliver(peer, encode(QuiltMessage.RootDigest(peerReplica, root = 0xDEADBEEFL, upThrough = 0L)))
        testScheduler.runCurrent()

        val requests = seam.directed.drop(before)
            .map { it.first to decoded(it.second) }
            .filter { it.second is QuiltMessage.FullStateRequest }
        assertEquals(1, requests.size, "a mismatched root must request exactly one full state")
        assertEquals(peer, requests.single().first, "the request goes to the digest's sender")
    }

    @Test
    fun solicitedRequestShipsStateAndUnsolicitedDoesNot() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
            val quilter = quilterOn(seam, backgroundScope, GSet.of("x"))
            testScheduler.runCurrent()

            // Unsolicited: we have sent this peer no RootDigest, so the request is a no-op.
            var before = seam.directed.size
            seam.deliver(peer, encode(QuiltMessage.FullStateRequest<GSet<String>>(peerReplica, ReplicaId(self.value))))
            testScheduler.runCurrent()
            val unsolicited = seam.directed.drop(before).map { decoded(it.second) }

            // Solicited: arm the flag the way the anti-entropy tick does, then request.
            before = seam.directed.size
            quilter.sendRootDigestForTest(peer)
            testScheduler.runCurrent()
            before = seam.directed.size
            seam.deliver(peer, encode(QuiltMessage.FullStateRequest<GSet<String>>(peerReplica, ReplicaId(self.value))))
            testScheduler.runCurrent()
            val solicited = seam.directed.drop(before).map { decoded(it.second) }

            assertAll(
                {
                    assertTrue(
                        unsolicited.none { it is QuiltMessage.FullState },
                        "an unsolicited FullStateRequest must not pull state — that is a 3.5 MB amplification lever",
                    )
                },
                { assertTrue(solicited.any { it is QuiltMessage.FullState }, "a solicited request must ship state") },
            )
        }
}
```

**Note on the test hook:** Task 3 has not flipped the send side, so the anti-entropy tick cannot arm the flag yet. Add an `internal` test hook to `Quilter` in this task:

```kotlin
    /** Sends one [QuiltMessage.RootDigest] to [peer], arming the solicited-request flag. Test-only. */
    internal fun sendRootDigestForTest(peer: PeerId): Unit = lock.withLock { sendRootDigestTo(peer) }
```

Also drop the unused `Patch` import from the test file — `detektAll` flags it.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :kuilt-quilter:jvmTest --tests "*QuilterRootDigestTest"
```

Expected: compile failure — `RootDigest`, `FullStateRequest`, `sendRootDigestForTest` unresolved.

- [ ] **Step 3: Add the wire variants**

In `QuiltMessage.kt`, after the `FullState` class and before `Resend`, add:

```kotlin
    /**
     * A hash of [sender]'s whole state, sent on the anti-entropy tick in place of the state
     * itself (#1955). The recipient compares it with its own root and replies with a
     * [FullStateRequest] only if they differ, so a converged round costs one small frame
     * instead of the entire CRDT.
     *
     * [upThrough] carries the same own-delta high-water as [FullState.upThrough], and for the
     * same reason: an anti-entropy round must resync the recipient's receive cursor whether or
     * not it ships state. Omitting it here would reintroduce the #1266 livelock for a peer
     * whose state matches while its delta cursor lags.
     *
     * [upThrough] deliberately has **no default**: omitting it must be a compile error, not a
     * silent `0L` that disables the resync. ([FullState.upThrough] carries a default only because
     * it predates its callers.)
     *
     * [root] is advisory — a collision costs a missed heal, cleared by the next state mutation on
     * either side or routed around by a third peer, never divergence that survives a [FullState].
     */
    @Serializable
    @SerialName("rootDigest")
    public class RootDigest<S>(
        public val sender: ReplicaId,
        public val root: Long,
        public val upThrough: Long,
    ) : QuiltMessage<S>()

    /**
     * Sent by [requester] when a [RootDigest] from [sender] disagreed with its own root: please
     * ship the state. The recipient answers with a [FullState].
     *
     * Honored only when the recipient has sent [requester] a [RootDigest] since the last request
     * it honored, which caps delivery at one full state per peer per anti-entropy interval —
     * exactly the pre-#1955 ceiling — and makes an unsolicited request a no-op.
     */
    @Serializable
    @SerialName("fullStateRequest")
    public class FullStateRequest<S>(
        public val requester: ReplicaId,
        public val sender: ReplicaId,
    ) : QuiltMessage<S>()
```

- [ ] **Step 4: Add the root computation, the solicited-request flag, and both handlers**

In `Quilter.kt`, alongside the other per-peer maps (near `pendingFullStateJobs`), add:

```kotlin
    /**
     * Peers we have sent a [QuiltMessage.RootDigest] to and not yet answered a
     * [QuiltMessage.FullStateRequest] for. Gates the amplification lever: an unsolicited request
     * finds no entry and is dropped.
     */
    private val digestOutstanding: MutableSet<PeerId> = mutableSetOf()
```

Add the root helper and the digest send (both require `lock`):

```kotlin
    /**
     * FNV-1a 64 over the state as it would appear on the wire. Must be called under [lock].
     *
     * There is no `KSerializer<S>` on this class — the primary constructor takes only
     * [messageSerializer], and the top-level factory's `valueSerializer` is not retained — so the
     * state is encoded inside a fixed synthetic [QuiltMessage.FullState]. [ReplicaId.Bottom] and
     * `upThrough = 0L` are constants, so equal states still yield equal roots on every peer.
     */
    private fun stateRoot(): Long = fnv1a64(
        binaryFormat.encodeToByteArray(
            messageSerializer,
            QuiltMessage.FullState(sender = ReplicaId.Bottom, state = _state.value, upThrough = 0L),
        ),
    )

    /** Ships a [QuiltMessage.RootDigest] to [peer] and arms the solicited-request flag. Under [lock]. */
    private fun sendRootDigestTo(peer: PeerId) {
        val bytes = encode(QuiltMessage.RootDigest(sender = replica, root = stateRoot(), upThrough = nextSeq))
        digestOutstanding.add(peer)
        scope.launch {
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "rootDigest to $peer failed: ${it.message}" } }
        }
    }
```

Add the two handlers next to `onFullState`:

```kotlin
    /**
     * An inbound anti-entropy digest.
     *
     * On a **match** the roots agree, so the states agree, so resyncing the receive cursor and
     * acking [QuiltMessage.RootDigest.upThrough] is honest — and it is the #1266 obligation this
     * frame exists to carry, since no state ships.
     *
     * On a **mismatch** it deliberately does **not** resync. `resyncReceiveCursor` acks, and today
     * that ack is only ever issued after the state was merged ([onFullState] merges, then resyncs).
     * Acking here would claim absorption of history we have not received and drop buffered deltas
     * covering it; if the request or its reply were then lost we would be stale *and* cut off from
     * that history via the delta path. The requested [QuiltMessage.FullState] carries its own
     * `upThrough` and resyncs exactly as it does today.
     */
    private fun onRootDigest(sender: PeerId, msg: QuiltMessage.RootDigest<S>) {
        if (msg.root == stateRoot()) {
            resyncReceiveCursor(sender, msg.sender, msg.upThrough)
            return
        }
        val bytes = encode(QuiltMessage.FullStateRequest<S>(requester = replica, sender = msg.sender))
        scope.launch {
            runCatchingCancellable { seam.sendTo(sender, bytes) }
                .onFailure { logger.debug { "fullStateRequest to $sender failed: ${it.message}" } }
        }
    }

    /**
     * A peer's reply to our digest, asking for the state. Ships it directly rather than via
     * [sendFullStateTo]: that helper arms [scheduleFullStateRetry] for the first-contact path,
     * and anti-entropy already retries every interval, so reusing it would run two independent
     * retry machines over one peer.
     */
    private fun onFullStateRequest(sender: PeerId, msg: QuiltMessage.FullStateRequest<S>) {
        if (msg.sender != replica) return // mirrors onResend's guard — not our state being asked for
        if (!digestOutstanding.remove(sender)) {
            logger.debug { "unsolicited fullStateRequest from $sender — ignored" }
            return
        }
        val bytes = encode(QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq))
        scope.launch {
            runCatchingCancellable { seam.sendTo(sender, bytes) }
                .onFailure { logger.debug { "fullState reply to $sender failed: ${it.message}" } }
        }
    }
```

Add the test hook from Step 1's note, next to the other `*ForTest` members:

```kotlin
    /** Sends one [QuiltMessage.RootDigest] to [peer], arming the solicited-request flag. Test-only. */
    internal fun sendRootDigestForTest(peer: PeerId): Unit = lock.withLock { sendRootDigestTo(peer) }
```

Wire both into `dispatch`'s `when` (`Quilter.kt:614-618`):

```kotlin
            is QuiltMessage.RootDigest -> onRootDigest(sender, msg)
            is QuiltMessage.FullStateRequest -> onFullStateRequest(sender, msg)
```

Finally, in `evictStalePeers`'s per-peer cleanup loop (beside `cancelFullStateRetry(peer)`), add:

```kotlin
            digestOutstanding.remove(peer)
```

so an evicted peer leaves no stale flag behind.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :kuilt-quilter:jvmTest --tests "*QuilterRootDigestTest"
./gradlew :kuilt-quilter:jvmTest
```

Expected: both PASS. The full-module run must be green **without editing any existing test** — the send side has not changed yet. If an existing test fails here, something in `dispatch`/`evictStalePeers` was altered beyond the additions above.

`FakeSeam.deliver(from: PeerId, payload: ByteArray)` (`FakeSeam.kt:137`) is the two-argument form used above; the `from` argument is load-bearing, because it stamps `Swatch.sender` and `Quilter`'s collector silently drops sender-less swatches (`Quilter.kt:341`).

- [ ] **Step 6: Commit**

```bash
git add kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/QuiltMessage.kt \
        kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt \
        kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterRootDigestTest.kt
git commit -m "feat(quilter): add RootDigest/FullStateRequest and their handlers (part of #1955)

Receive side only — the anti-entropy tick still ships FullState, so no existing behaviour
moves. onRootDigest compares roots FIRST and resyncs the receive cursor only on the match
branch (#1266) — resyncing on a mismatch would ack history not yet received and drop the
buffered deltas covering it; the requested FullState carries its own upThrough. And
onFullStateRequest honors a request only from a peer we have an outstanding digest with,
so an unsolicited request cannot pull a full state on demand.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Flip the anti-entropy tick, and audit what that un-pins

The behaviour change, plus the vacuity audit — which is the highest-risk item in the plan, not a formality.

**Files:**
- Modify: `kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt:508-521` (`reconcileWithRandomPeer` and its KDoc)
- Test: `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterAntiEntropyDigestResyncTest.kt`
- Test: `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterStateRootGoldenVectorTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–3.
- Produces: no new API. `reconcileWithRandomPeer` now sends `RootDigest`.

- [ ] **Step 1: Write the failing test — the #1266-for-anti-entropy trap**

The existing `QuilterFullStateResyncTest` covers the **first-contact** `FullState` path, which this plan does not change. It therefore gives the *anti-entropy* path **zero** cursor-resync coverage. This test is that coverage.

Create `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterAntiEntropyDigestResyncTest.kt`:

```kotlin
@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package us.tractat.kuilt.quilter

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.FakeSeam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A **matched** anti-entropy round must still resync the receiver's delta cursor (#1266, #1955).
 *
 * Before #1955 the tick shipped a `FullState`, which did two jobs: it moved the state *and* it
 * carried `upThrough`, which `resyncReceiveCursor` uses to fast-forward the receive cursor, drop
 * covered inbound buffer entries, and ack — the thing that keeps the sender's `pendingDeltas`
 * from being pinned forever by a lagging receiver.
 *
 * A digest that shipped nothing on a match would silently drop the second job for exactly the
 * peers where state agrees but the cursor lags — reachable, because state can arrive via another
 * peer or a gossip flood while this sender's cursor stays stale. Hence `RootDigest.upThrough`.
 *
 * **Two tests, deliberately.** [matchedRootStillAcksSoSenderCanGc] fabricates the inbound digest, so
 * it pins the *handler* but never executes `sendRootDigestTo` — hardcoding `upThrough = 0L` on the
 * send side would leave it green. [emittedDigestCarriesOwnDeltaHighWater] is the one that goes red
 * under that mutation. The field also has no default, so omitting it fails to compile.
 */
class QuilterAntiEntropyDigestResyncTest {

    private val valueSer = GSet.serializer(String.serializer())
    private val msgSer = QuiltMessage.serializer(valueSer)
    private val self = PeerId("self")
    private val peer = PeerId("peer-1")
    private val peerReplica = ReplicaId("peer-1")

    /** Mirrors `Quilter.stateRoot()` — see the same helper in `QuilterRootDigestTest`. */
    private fun expectedRoot(state: GSet<String>): Long = fnv1a64(
        Cbor.encodeToByteArray(msgSer, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)),
    )

    @Test
    fun matchedRootStillAcksSoSenderCanGc() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = Quilter(
            replica = ReplicaId(self.value),
            seam = seam,
            initial = GSet.of("shared"),
            messageSerializer = msgSer,
            scope = backgroundScope,
            config = QuilterConfig(expectVirtualTime = true, fullStateRetryLimit = 0),
        )
        testScheduler.runCurrent()

        // The peer's state matches ours exactly, so the roots agree...
        val matchingRoot = expectedRoot(GSet.of("shared"))
        // ...but it claims deltas 1..5, which our receive cursor has never seen.
        val before = seam.directed.size
        seam.deliver(
            peer,
            Cbor.encodeToByteArray(msgSer, QuiltMessage.RootDigest(peerReplica, matchingRoot, upThrough = 5L)),
        )
        testScheduler.runCurrent()

        val replies = seam.directed.drop(before).map { Cbor.decodeFromByteArray(msgSer, it.second) }
        val acks = replies.filterIsInstance<QuiltMessage.Ack<GSet<String>>>()
        assertAll(
            {
                assertEquals(
                    1,
                    acks.size,
                    "a matched round must still ack upThrough — otherwise the sender's pendingDeltas " +
                        "are pinned forever by a receiver whose cursor lags (#1266)",
                )
            },
            { assertEquals(5L, acks.single().seq, "the ack must carry the digest's high-water") },
            { assertTrue(replies.none { it is QuiltMessage.FullState }, "matched roots ship no state") },
            { assertEquals(setOf("shared"), quilter.state.value.elements, "state unchanged") },
        )
    }

    /**
     * The digest we *emit* must carry our own-delta high-water. This is the test that fails if
     * `sendRootDigestTo` passes `upThrough = 0L`; the receive-side test above cannot, because it
     * fabricates its input.
     */
    @Test
    fun emittedDigestCarriesOwnDeltaHighWater() = runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
        val interval = 50.milliseconds
        val seam = FakeSeam(selfId = self, initialPeers = setOf(self, peer))
        val quilter = Quilter(
            replica = ReplicaId(self.value),
            seam = seam,
            initial = GSet.empty<String>(),
            messageSerializer = msgSer,
            scope = backgroundScope,
            config = QuilterConfig(
                expectVirtualTime = true,
                antiEntropyInterval = interval,
                fullStateRetryLimit = 0,
            ),
            random = Random(7),
        )
        testScheduler.runCurrent()

        // Mint three deltas so nextSeq == 3.
        repeat(3) { i -> quilter.apply(Patch(GSet.of("e$i"))) }
        testScheduler.runCurrent()

        val before = seam.directed.size
        testScheduler.advanceTimeBy(interval.inWholeMilliseconds + 1)
        testScheduler.runCurrent()

        val digests = seam.directed.drop(before)
            .map { Cbor.decodeFromByteArray(msgSer, it.second) }
            .filterIsInstance<QuiltMessage.RootDigest<GSet<String>>>()
        assertTrue(digests.isNotEmpty(), "the anti-entropy tick must emit a RootDigest")
        assertEquals(
            3L,
            digests.first().upThrough,
            "the emitted digest must carry our own-delta high-water, or a matched round can never " +
                "unpin the receiver's cursor (#1266)",
        )
    }
}
```

Additional imports for this file: `us.tractat.kuilt.crdt.Patch`, `kotlin.random.Random`, and `kotlin.time.Duration.Companion.milliseconds`.

- [ ] **Step 2: Run it and confirm it fails for the right reason**

```bash
./gradlew :kuilt-quilter:jvmTest --tests "*QuilterAntiEntropyDigestResyncTest"
```

Expected: `matchedRootStillAcksSoSenderCanGc` PASSES already (Task 3 wired the handler);
`emittedDigestCarriesOwnDeltaHighWater` **FAILS**, because the tick still sends `FullState` and emits
no `RootDigest` at all. That is the correct red for this step, and it is the assertion that makes the
`upThrough` carry provable.

After Step 3 flips the tick, run the mutation: set `upThrough = 0L` in `sendRootDigestTo`, re-run
both tests, and confirm `emittedDigestCarriesOwnDeltaHighWater` goes red while the receive-side test
stays green — that asymmetry is the whole reason both tests exist. Restore, and paste the red output
into the commit message.

- [ ] **Step 3: Flip the tick**

In `Quilter.kt`, replace `reconcileWithRandomPeer`'s body:

```kotlin
    private fun reconcileWithRandomPeer() {
        if (knownPeers.isEmpty()) return
        val peer = knownPeers.elementAt(random.nextInt(knownPeers.size))
        val bytes = encode(QuiltMessage.FullState(sender = replica, state = _state.value, upThrough = nextSeq))
        scope.launch {
            runCatchingCancellable { seam.sendTo(peer, bytes) }
                .onFailure { logger.debug { "antiEntropy reconcile to $peer failed: ${it.message}" } }
        }
    }
```

with:

```kotlin
    private fun reconcileWithRandomPeer() {
        if (knownPeers.isEmpty()) return
        sendRootDigestTo(knownPeers.elementAt(random.nextInt(knownPeers.size)))
    }
```

- [ ] **Step 4: Rewrite the KDoc, which now states the opposite of the truth**

The existing KDoc ends "Full-state-first is always correct; a version-vector or digest diff is a later optimization for large CRDTs." That sentence is now false. Replace the KDoc's last two paragraphs with:

> **Refined after this plan ran.** The final review found the acceptance harness never metered the
> `Ack` a matched round sends back, so the figures below were the sender's half only. The block is
> shown with the corrected numbers (94–103 b per round, ~1.7 B/s, ~34,000×) and the corrected
> collision bound; see Task 5 and Task 8 for what changed and why.

```kotlin
     * Sends a [QuiltMessage.RootDigest] — a hash of the state, not the state (#1955). The peer
     * replies with a [QuiltMessage.FullStateRequest] only if its own root differs, so a converged
     * round costs two small frames instead of the whole CRDT: the digest out (~54–57 b) and the
     * matched peer's [QuiltMessage.Ack] of `upThrough` back (~40–46 b). Measured: a converged
     * 100k-entry `GSet` node drops from ~58 KB/s of steady-state egress to roughly 1.7 B/s — a
     * ~34,000× reduction. Both frames are flat in state size, which is the claim that matters;
     * the constant is not exact, because CBOR encodes `root`, `seq` and `upThrough` at minimal
     * width, so a few bytes move with the values and with the replica id's length.
     *
     * [QuiltMessage.FullState] remains the always-correct fallback and every convergence
     * guarantee still traces to it — a root collision or a digest a peer cannot parse costs a
     * missed heal, never divergence that survives a [QuiltMessage.FullState]. That missed heal is
     * not bounded by the next round: between two quiescent peers the same roots collide again
     * every round in both directions, so recovery waits on a change. The digest also carries
     * `upThrough`, because on a *matched* round no state ships and nothing else would resync the
     * recipient's receive cursor (#1266). On a mismatch the requested [QuiltMessage.FullState]
     * carries its own, so the digest handler deliberately leaves the cursor alone there rather
     * than acking history it has not yet received.
     *
     * The send is fire-and-forget — the next anti-entropy round is the natural retry. Must
     * be called under [lock]; the actual `seam.sendTo` is launched on [scope] outside it.
```

- [ ] **Step 5: Pin the state root cross-target**

Create `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterStateRootGoldenVectorTest.kt`. The expected constants cannot be written from theory — they depend on CBOR's canonical encoding — so **capture them, then prove them on a second target**:

```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.quilter

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.LWWMap
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Absolute cross-target pins for the anti-entropy root of concrete states (#1955).
 *
 * [Fnv1a64GoldenVectorTest] pins the hash; this pins the *encoding underneath it*. Two replicas
 * on different targets must compute the same root for the same state, or every round reports
 * false divergence and ships full state forever — self-consistent per target, broken across
 * them, and silent. `commonTest` runs on JVM, Android, iOS, macOS and wasmJs, so these constants
 * hold all of them to one answer.
 *
 * Sibling: `CanonicalGoldenVectorTest` in `:kuilt-crdt`, which pins the encodings themselves.
 */
class QuilterStateRootGoldenVectorTest {

    private val gsetMsgSer = QuiltMessage.serializer(GSet.serializer(String.serializer()))
    private val lwwMsgSer =
        QuiltMessage.serializer(LWWMap.serializer(String.serializer(), String.serializer()))
    private val replica = ReplicaId("r0")

    /** Mirrors `Quilter.stateRoot()`: the state encoded inside a fixed synthetic `FullState`. */
    private fun <S> rootOf(ser: kotlinx.serialization.KSerializer<QuiltMessage<S>>, state: S): Long =
        fnv1a64(Cbor.encodeToByteArray(ser, QuiltMessage.FullState(ReplicaId.Bottom, state, upThrough = 0L)))

    @Test
    fun pinnedStateRoots() {
        val gset = GSet.of("alpha", "beta", "gamma")
        val lww = LWWMap.empty<String, String>()
            .set(replica, 1L, "k1", "v1")
            .set(replica, 2L, "k2", "v2")
        assertAll(
            { assertEquals(REPLACE_WITH_CAPTURED_GSET_ROOT, rootOf(gsetMsgSer, gset)) },
            { assertEquals(REPLACE_WITH_CAPTURED_LWW_ROOT, rootOf(lwwMsgSer, lww)) },
        )
    }

    @Test
    fun insertionOrderDoesNotChangeTheRoot() {
        // The point of the canonical serializers: a set built in a different order must encode
        // identically, so two converged replicas agree regardless of how they got there.
        assertEquals(
            rootOf(gsetMsgSer, GSet.of("alpha", "beta", "gamma")),
            rootOf(gsetMsgSer, GSet.of("gamma", "alpha", "beta")),
            "root must not depend on insertion order",
        )
    }
}
```

Capture procedure — do not guess, and do not accept a JVM-only value:

1. Temporarily replace each `REPLACE_WITH_CAPTURED_*` with `0L` and run `./gradlew :kuilt-quilter:jvmTest --tests "*QuilterStateRootGoldenVectorTest"`. The assertion message prints the actual value.
2. Substitute the two real `Long`s (suffix `L`).
3. **Prove them on a second target:** `./gradlew :kuilt-quilter:wasmJsBrowserTest` must pass with the same constants. Then `./gradlew :kuilt-quilter:macosArm64Test`.
4. If wasmJs disagrees, **stop** — that is a real cross-target canonicality bug in the encoding, not a bad constant. It is the exact failure class #1957 exists to catch. Report it rather than pinning the JVM value.

- [ ] **Step 6: The vacuity audit — blocking**

This change inserts a new gate **ahead of** an existing one, the pattern that silently zeroed a guard's coverage in #1872: `reconcileWithRandomPeer` no longer sends `FullState`, so every test that drove the anti-entropy tick now exercises the digest path while its assertions may no longer constrain anything.

These 19 files reference `antiEntropyInterval`:

```
kuilt-quilter/src/commonTest: QuilterAntiEntropyReconcileTest, QuilterBoundedCounterHealTest,
  QuilterChaosTest, QuilterDeliveredGossipTest, QuilterDeltaTargetChurnTest,
  QuilterDeltaTargetGcTest, QuilterEvictionTest, QuilterEvictionWiringAuditTest,
  QuilterResettableCounterTest, QuilterSparseTargetConvergenceTest, QuilterStableCutTest,
  QuilterTest, QuilterUniversalAckFlowTest, GossipScalingBenchmarkTest
kuilt-scale/src/test:  GossipAntiEntropyMeasurementTest, GossipQuilterConvergenceTest,
  GossipQuilterScalingTest, MerkleDigestCostModelTest
examples/:  FederatedLearningExampleTest
```

For each, answer in the commit message: *does its assertion still fail if the behaviour it names is broken?* Concretely:

1. Run the file. Green is not sufficient evidence.
2. Mutate the **production** behaviour it targets — e.g. make `onRootDigest` never send a `FullStateRequest`, or make `onFullStateRequest` ship an empty state — and confirm the test goes red.
3. **One mutation at a time, never batched.** A mutation that fails to *compile* is not a red test: check the build exit code before reading any results XML, or a stale XML will report the previous mutation's verdict and invert your conclusion.
4. Any test that stays green under a relevant mutation has gone vacuous — fix it in this task, or state in the commit message why it was already vacuous before this change.

`MerkleDigestCostModelTest` is a **known, expected** break, not a vacuity finding: its part (B) asserts the wire carries exactly `rounds × encoded FullState` bytes, which is precisely what this task falsifies. Leave it failing; Task 5 owns it. Note it in the commit message so the red is not mistaken for a regression.

- [ ] **Step 7: Run the full build — module scope is a false green here**

A `:kuilt-quilter`-scoped build skips the downstream `:examples` and `:kuilt-cluster` E2E tests, which exercise the whole runtime stack. This is a change to replication *behaviour*, exactly the case where that matters:

```bash
./gradlew build --rerun-tasks
```

Expected: everything green except `MerkleDigestCostModelTest` (see Step 6). Confirm tasks show `EXECUTED`, not `FROM-CACHE`; add `--no-build-cache` if any test-compile task is served from cache. Then:

```bash
./gradlew detektAll
./gradlew :kuilt-quilter:detektJvmMain --rerun-tasks
```

`detektAll` is the real gate — bare `detekt` is `NO-SOURCE` in this KMP setup and reports success without analyzing. The `detektJvmMain` run is type-resolution-aware and is the only thing that catches an `UnsafeCallOnNullableType` that `detektAll` can false-green.

- [ ] **Step 8: Commit**

```bash
git add kuilt-quilter/src/commonMain/kotlin/us/tractat/kuilt/quilter/Quilter.kt \
        kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterAntiEntropyDigestResyncTest.kt \
        kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/QuilterStateRootGoldenVectorTest.kt
git commit -m "feat(quilter): gate anti-entropy on a root hash instead of shipping full state (part of #1955)

The tick now sends RootDigest; the peer asks for state only when its root differs.
Measured basis: a converged 100k-entry GSet node drops from ~58 KB/s of steady-state
egress to roughly 1.7 B/s.

RootDigest carries upThrough because the old FullState did two jobs — moving state and
resyncing the receiver's delta cursor. Mutation-verified: upThrough = 0L turns
QuilterAntiEntropyDigestResyncTest red (paste the red output here).

Vacuity audit over the 19 antiEntropyInterval-dependent tests: <verdict per file>.
MerkleDigestCostModelTest part (B) fails by design — it asserts exact FullState bytes per
round, which is what this commit changes; Task 5 rewrites it as the measured acceptance test.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Turn the Phase-0 model into a measured acceptance test

Phase 0 predicted 58.1 KB/s → 0.52 B/s. Measure the real thing and assert it, so the prediction is verified rather than left standing. (**Outcome, as finally measured:** 58.1 KB/s → **~1.7 B/s**, a ~34,000× saving. The *before* is confirmed exactly; the *after* is ~3× worse than modelled, for three reasons — the Phase-0 model omitted `RootDigest.upThrough`, priced `root` as the placeholder `-1L` (which CBOR stores in one byte where a real FNV-1a 64 root costs nine), and treated the round as the digest alone with nothing coming back. **The third was found only in the final whole-branch review**, and this note read `~1 B/s` until then: the acceptance harness built every `Quilter` with `initial = state` and never called `apply`, so every node sat at `nextSeq == 0`, shipped `upThrough = 0`, and `resyncReceiveCursor` returned at its `upThrough <= 0` guard before acking. No `Ack` was ever emitted in the metered window. The harness now makes each node write once and re-agree before the meter opens.)

**Files:**
- Modify: `kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/MerkleDigestCostModelTest.kt`

**Interfaces:**
- Consumes: the flipped tick from Task 4.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Read what part (B) currently asserts and why it now fails**

```bash
./gradlew :kuilt-scale:test --tests "*MerkleDigestCostModelTest"
```

Expected: `meteredReconcileMatchesEncodedFullStateSize` FAILS. It asserts `totalOnWire == rounds × encoded FullState` — true before Task 4, false after, because a converged round now carries a digest. The measured number dropping is the whole point of the change.

- [ ] **Step 2: Rewrite part (B) as the converged-round acceptance test**

Replace `meteredReconcileMatchesEncodedFullStateSize` with:

```kotlin
    @Test
    fun convergedRoundShipsADigestNotTheState() = runTest(UnconfinedTestDispatcher()) {
        val n = 4
        val rounds = 20
        val stateSize = 200
        val antiEntropy = 50.milliseconds

        val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }

        val mesh = buildInMemoryMesh(n)
        val gossips = mesh.seams.mapIndexed { i, base ->
            GossipSeam(
                base = base,
                random = Random(1 + i),
                clock = clock,
                config = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours),
                jitter = ZERO..ZERO,
            )
        }
        gossips.forEach { it.start(backgroundScope) }
        flush()

        // Every replica starts from the SAME state, so every anti-entropy round is a converged
        // round — the case the #1955 gate exists for.
        val shared = gsetOf(stateSize)
        gossips.forEach { g ->
            Quilter(
                seam = g,
                initial = shared,
                valueSerializer = GSet.serializer(String.serializer()),
                scope = backgroundScope,
                config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropy),
                random = Random(100),
            )
        }
        flush()

        val before = mesh.clusterMetrics().totalBytesOut
        repeat(rounds) { testScheduler.advanceTimeBy(antiEntropy.inWholeMilliseconds); testScheduler.runCurrent() }
        val measured = mesh.clusterMetrics().totalBytesOut - before
        mesh.close()

        val fullState = fullStateBytes(shared, ReplicaId("peer-0"))
        val perNodeRound = measured.toDouble() / (rounds * n)
        println("\n=== #1955 acceptance: converged anti-entropy round, GSet($stateSize), $n nodes ===")
        println("  bytes/node/round now      : ${"%.1f".format(perNodeRound)}")
        println("  full state would have been : $fullState")
        println("  reduction                  : ${"%.1f".format(fullState / perNodeRound)}x")

        // The Phase-0 prediction was that a converged round becomes a small constant frame,
        // independent of state size. 200 entries is ~6.5 KB of state; a digest round is tens of
        // bytes. Assert the order of magnitude, not an exact frame size, so incidental framing
        // changes do not red-light this.
        assertTrue(
            perNodeRound < fullState / 20.0,
            "a converged round must cost far less than the state ($perNodeRound b vs $fullState b)",
        )
    }
```

- [ ] **Step 3: Verify the state-size independence that is the actual claim**

Add, in the same class:

```kotlin
    @Test
    fun convergedRoundCostIsFlatInStateSize() = runTest(UnconfinedTestDispatcher()) {
        // The #1955 claim is not "cheaper" but "constant": the converged round must cost the same
        // at 200 entries as at 20,000, while full state grows 100x. Measured, not modelled.
        val perRound = listOf(200, 20_000).map { size ->
            val antiEntropy = 50.milliseconds
            val clock = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
            fun flush() = repeat(32) { testScheduler.advanceTimeBy(1); testScheduler.runCurrent() }
            val mesh = buildInMemoryMesh(2)
            val gossips = mesh.seams.mapIndexed { i, base ->
                GossipSeam(
                    base = base,
                    random = Random(1 + i),
                    clock = clock,
                    config = HeartbeatConfig(interval = 1.hours, timeout = 1.hours, reconnectWindow = 1.hours),
                    jitter = ZERO..ZERO,
                )
            }
            gossips.forEach { it.start(backgroundScope) }
            flush()
            val shared = gsetOf(size)
            gossips.forEach { g ->
                Quilter(
                    seam = g,
                    initial = shared,
                    valueSerializer = GSet.serializer(String.serializer()),
                    scope = backgroundScope,
                    config = QuilterConfig(expectVirtualTime = true, antiEntropyInterval = antiEntropy),
                    random = Random(100),
                )
            }
            flush()
            val before = mesh.clusterMetrics().totalBytesOut
            repeat(10) { testScheduler.advanceTimeBy(antiEntropy.inWholeMilliseconds); testScheduler.runCurrent() }
            val bytes = mesh.clusterMetrics().totalBytesOut - before
            mesh.close()
            size to bytes
        }
        println("\n=== #1955 acceptance: converged-round cost vs state size ===")
        perRound.forEach { (size, bytes) -> println("  GSet(%6d) -> %8d bytes over 10 rounds".format(size, bytes)) }

        val (small, large) = perRound.map { it.second }
        assertTrue(
            large < small * 2,
            "converged-round cost must be ~flat in state size (200 entries: $small b, 20k entries: $large b)",
        )
    }
```

- [ ] **Step 4: Update the class KDoc**

Its "What is modelled" paragraph says the digest protocol "does not exist yet" and is priced via `RootDigestProbe`/`ShardDigestsProbe`/`ShardPushProbe`. `RootDigest` now exists. Add after that paragraph:

```kotlin
 * **Superseded in part.** `QuiltMessage.RootDigest` shipped in #1955, so the converged-round cost
 * is no longer modelled — [convergedRoundShipsADigestNotTheState] and
 * [convergedRoundCostIsFlatInStateSize] measure it on a `MeteredSeam`. The probe frames remain
 * only to price the *sharded* variant, which was measured and deliberately not built: its
 * advantage collapses as divergence grows (see the (D) table).
```

- [ ] **Step 5: Run and confirm the numbers land near the Phase-0 prediction**

```bash
./gradlew :kuilt-scale:test --tests "*MerkleDigestCostModelTest" --rerun-tasks
```

Expected: PASS. Read the printed reduction factor. Phase 0 predicted the converged round becomes a ~31-byte frame; if the measured per-node round is wildly larger (say >200 b), something else is riding the tick — investigate before accepting, and record the number either way. (**Recorded:** 54 b metered, 54 b independently encoded from the shipped `RootDigest` — agreeing to the byte, so nothing else rides the tick. The >200 b tripwire was never approached; the gap to Phase 0's ~31 b is the two model pricing bugs, not an extra frame. **Refined in the final review:** that 54 b was right as far as it went — it is the digest *out*, and the model-vs-wire agreement did rule out an extra frame — but the harness had every node at `nextSeq == 0`, so the matched peer's `Ack` back was never emitted and never counted. With each node writing once first, the round meters **94 b**, again agreeing to the byte with an independently encoded digest + `Ack`. The conclusion survives intact: nothing else rides the tick; there is simply one more frame *in* the round than the note first recorded.)

- [ ] **Step 6: Commit**

```bash
git add kuilt-scale/src/test/kotlin/us/tractat/kuilt/scale/MerkleDigestCostModelTest.kt
git commit -m "test(scale): measure the converged anti-entropy round instead of modelling it (part of #1955)

Part (B) asserted the wire carried exactly rounds x encoded FullState, which #1955 falsifies
by design. It becomes the acceptance test for the change: a converged round must cost far
less than the state, and must be flat in state size (200 vs 20,000 entries).

Measured reduction: <paste the printed factor>.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Update the prose that now describes the old behaviour

**Files:**
- Modify: `docs/gossip-mesh-design.md`
- Modify: `Writerside/topics/partial-mesh.md` — line ~94 says "Periodically each peer reconciles **full state** with one random peer", now false.
- Modify: `kuilt-quilter/module.md` — lines ~13-14 enumerate the wire hierarchy as "`Delta`, `Ack`, `FullState`, `Resend`, `Delivered`", now incomplete.

**Interfaces:** none.

- [ ] **Step 1: Find every stale claim**

```bash
grep -n "full state\|full-state\|anti-entropy" docs/gossip-mesh-design.md Writerside/topics/partial-mesh.md kuilt-quilter/module.md
```

`Writerside/topics/partial-mesh.md` is a **guide** page, so it is bound by the accessible-first rule: keep it plain-language ("each peer periodically checks a fingerprint of its state against one random peer, and only sends the data if they differ"), and do not introduce `RootDigest` or "FNV" there.

Any sentence saying the anti-entropy backstop ships full state, or that a digest diff is future work, is now wrong. Read each hit in context — some will legitimately still describe `FullState` as the *fallback*, which remains true.

- [ ] **Step 2: Rewrite the stale sentences**

Keep the existing accessible-first structure: plain language up top, mechanism deeper down. State that the tick sends a hash and ships state only on disagreement, that `FullState` is still the fallback every guarantee rests on, and give the measured figures (58.1 KB/s → **roughly 1.7 B/s** at 100k entries, a ~34,000× reduction; a converged round is the digest out *plus* the matched peer's `Ack` back, 94–103 b). Publish a rounded figure, not a precise one, and round in the direction least flattering to kuilt: the two frames are flat in state size but CBOR encodes `root`, `seq` and `upThrough` at minimal width, so ~1.7 B/s is the top of the measured range and ~34,000× its floor. Do not introduce "Merkle" — no tree was built, and saying otherwise would mislead the next reader.

- [ ] **Step 3: Verify citations still resolve**

```bash
./gradlew verifyDocCitations
```

Expected: PASS. Citations into `Quilter` point at `commonSamples`, not the KDoc Task 4 rewrote, so this should be unaffected — but it takes about a second and a drifted citation fails CI's `doc-citations` job.

- [ ] **Step 4: Re-read top-to-bottom for the accessible→technical flow**

Required by the repo's docs rule: confirm the page still opens in plain language and that jargon appears only after a plain-language framing.

- [ ] **Step 5: Commit**

```bash
git add docs/gossip-mesh-design.md Writerside/topics/partial-mesh.md kuilt-quilter/module.md
git commit -m "docs(gossip): anti-entropy ships a root hash, not full state (part of #1955)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Wrap-up (after Task 6)

- [ ] Rebase onto current `origin/main`: `git fetch origin main && git rebase origin/main`
- [ ] Full cache-disabled gate: `./gradlew build detektAll --rerun-tasks` — confirm `EXECUTED`, not `FROM-CACHE`
- [ ] Open the PR **ready, not draft** (a drafted-then-readied PR leaves a stale `ci-required` FAILURE). Body: the measured before/after, the #1266 `upThrough` obligation, the vacuity-audit verdict, and the accepted mixed-version limitation.
- [ ] The PR may `closes #1955` **only if** Iain agrees the root-hash-only scope closes it; otherwise "part of #1955" and update the issue body. Remember backticks do not disarm the keyword in a squashed commit message.
- [ ] `~/.claude/bin/gh-pr-wait <PR> --arm-auto`
- [ ] File a follow-up issue for the reply-always mixed-version fallback described in the spec's "Mixed-version rooms" section, so the rejected alternative is recorded rather than lost.

## Self-Review

**Spec coverage.** Wire protocol → Task 3. `upThrough` obligation → Tasks 2, 3, 4 (test). Digest location/hash choice → Task 1. `resyncReceiveCursor` narrowing → Task 2. `onFullStateRequest` not reusing `sendFullStateTo` → Task 3. Amplification guard → Task 3 (impl + test). First-contact untouched → asserted by Task 3's "no existing test changes". Uniform scope / graceful degradation → covered by Task 4's full build across the zoo-typed suites; the spec's dedicated non-canonical-serializer test is **not** separately written, because `Rga`/`Fugue` already supply that case in the existing suites once the tick flips — if Task 4's audit shows no such coverage, add it there. Golden vectors → Tasks 1 and 4. Measured acceptance → Task 5. Docs → Task 6. Mixed-version limitation → recorded in the spec, follow-up issue in Wrap-up.

**Placeholder scan.** `REPLACE_WITH_CAPTURED_*` in Task 4 Step 5 is deliberate and carries an exact four-step capture procedure plus a stop condition; it cannot be derived from theory. `<verdict per file>` / `<paste the printed factor>` in commit messages are outputs of steps that precede them. Note Task 5's `:kuilt-scale` snippets pass `valueSerializer` deliberately — those call the top-level **factory** (`Quilter.kt:929`), which does take it; only the class's own `stateRoot()` had to avoid it.

**Type consistency.** `fnv1a64(ByteArray): Long` — Task 1 defines, Tasks 3/4/5 use. `resyncReceiveCursor(PeerId, ReplicaId, Long)` — Task 2 defines, Task 3 calls. `stateRoot()`, `sendRootDigestTo(PeerId)`, `digestOutstanding` — Task 3 defines, Task 4 calls. `RootDigest(sender, root, upThrough)` and `FullStateRequest(requester, sender)` — consistent across Tasks 3, 4, 5. `fullStateBytes(state, sender)` in Task 5 matches the two-arg overload already on `MerkleDigestCostModelTest`.

**One correction found in my own review:** an earlier draft had Task 4 add the `upThrough` field, which would have left Task 3's handler reading a field that did not exist. `RootDigest` carries `upThrough` from its introduction in Task 3.

## Applied from the adversarial review (2026-08-03)

An independent reviewer read both documents against the real code. Every claim below was
re-verified against the source before being applied. Two were blocking:

1. **`stateRoot()` did not compile.** It called `binaryFormat.encodeToByteArray(valueSerializer, …)`, but `Quilter`'s primary constructor holds only `messageSerializer` (`Quilter.kt:157`); `valueSerializer` belongs to the top-level factory (`:929`) and is not retained. Fixed by hashing a fixed synthetic `FullState` frame through the serializer the class already has, using the existing `ReplicaId.Bottom` sentinel (`ReplicaId.kt:31`) — zero API change, no call-site churn. Every test-side expected-root formula was updated to match, or `matchingRootShipsNoState` would silently have taken the mismatch branch.
2. **The headline `upThrough` test could not fail.** `QuilterAntiEntropyDigestResyncTest` fabricated its inbound digest, so `sendRootDigestTo` never ran and the mutation the plan itself nominated (`upThrough = 0L`) left it green — the #1266 livelock could have shipped with the build green. Added `emittedDigestCarriesOwnDeltaHighWater` (drives the real tick, decodes the emitted frame) and **removed the `= 0L` default** from `RootDigest.upThrough`, so omission is now a compile error rather than a silent disable.

Design change accepted: **resync only on the match branch.** Resyncing before comparing roots would ack history not yet received and drop buffered deltas covering it, because `resyncReceiveCursor` acks (`:829`) whereas today's `onFullState` merges first (`:791-794`) and only then resyncs (`:801`). On a mismatch the requested `FullState` carries its own `upThrough`, so nothing is lost and no false ack is issued.

Also fixed: `FakeSeam.deliver` is the two-arg `(from, payload)` form (`FakeSeam.kt:137`) and the `from` stamps `Swatch.sender`, which `Quilter`'s collector requires (`:341`); the FNV high-bit test was vacuous — its two assertions were a tautology over identical arrays and a comparison that holds under the signed-fold bug too, so both were replaced with absolute pins for `0xFF`/`0x80` (verified against the reference algorithm); `onFullStateRequest` gained `onResend`'s `msg.sender != replica` guard; Task 6 gained `Writerside/topics/partial-mesh.md` and `kuilt-quilter/module.md`; the collision claim ("a third peer still repairs it") was corrected — a 2-peer session has none, so it heals on the next state mutation instead; and the amplification ceiling was restated as one full state *per digest sent* rather than per interval, since a matched round leaves the grant armed.

The reviewer independently confirmed the central enumeration is complete: it traced `lastSeenAt`/eviction, `cancelFullStateRetry`, the push-back heal, sender-side GC via the ack, and `recomputeDeliveredLocal`/causal-matrix/`cutFrontier`/RGA-Fugue-GC/BoundedCounter, and found no other consequence of the unconditional 60-second push that silently disappears.
