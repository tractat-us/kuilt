# Bounded backpressured delivery — Phase 0 + Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the configurable `DeliveryPolicy` + bounded `Mailbox` delivery primitive and migrate `InMemoryLoom` onto it, proving backpressure makes the #655 OOM structurally impossible.

**Architecture:** A `Mailbox` wraps a *bounded* `Channel` whose overflow behaviour is chosen by a `DeliveryPolicy` (`SUSPEND`/`DROP_OLDEST`/`DROP_LATEST`/`FAIL`). `InMemoryLoom` delivers through one `Mailbox` per link; the suspending send happens **outside** the factory mutex (sequence numbers assigned under the lock, delivery performed after release).

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines `Channel`/`BufferOverflow`, kotlin-test, Gradle. JDK 21 via SDKMAN.

## Global Constraints

- `explicitApi()` is enforced — every public declaration needs an explicit visibility modifier.
- Locks (`reentrantLock`/`Mutex`) guard **synchronous** state only — never held across a suspension point.
- Test methods: no `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests inject a test dispatcher; never `advanceUntilIdle()` on timer-bearing systems — bounded `advanceTimeBy` only.
- `kuilt-core` depends on nothing but coroutines + serialization. `DeliveryPolicy`/`Overflow` are public; `Mailbox` is internal.
- Build: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem` first. Lint with `./gradlew :kuilt-core:detektAll` (never bare `detekt`).
- Commits: never the word "chore". End commit messages with the `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` trailer.

---

### Task 1: `DeliveryPolicy` + `Overflow` + `FrameOverflow`

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/DeliveryPolicy.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/DeliveryPolicyTest.kt`

**Interfaces:**
- Produces: `enum class Overflow { SUSPEND, DROP_OLDEST, DROP_LATEST, FAIL }`; `data class DeliveryPolicy(val capacity: Int, val overflow: Overflow)` with `companion` presets `Reliable`/`Lossy`/`Strict` and `const val DEFAULT_CAPACITY`; `class FrameOverflow(message: String) : RuntimeException(message)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryPolicyTest {
    @Test
    fun presetsSelectTheExpectedOverflow() {
        assertEquals(Overflow.SUSPEND, DeliveryPolicy.Reliable.overflow)
        assertEquals(Overflow.DROP_OLDEST, DeliveryPolicy.Lossy.overflow)
        assertEquals(Overflow.FAIL, DeliveryPolicy.Strict.overflow)
    }

    @Test
    fun defaultCapacityIsBoundedAndPositive() {
        assertEquals(true, DeliveryPolicy.Reliable.capacity in 1..1_000_000)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*DeliveryPolicyTest*"`
Expected: FAIL — `Unresolved reference 'DeliveryPolicy'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.core

/** Overflow behaviour when a bounded delivery buffer is full. Mirrors Reactor's `onBackpressure*`. */
public enum class Overflow { SUSPEND, DROP_OLDEST, DROP_LATEST, FAIL }

/**
 * How an in-process fabric buffers frames for one receiver: a bounded [capacity] and an
 * [overflow] strategy. There is deliberately no UNLIMITED option — unbounded delivery is the
 * defect this type exists to make unrepresentable (#701).
 */
public data class DeliveryPolicy(
    val capacity: Int = DEFAULT_CAPACITY,
    val overflow: Overflow = Overflow.SUSPEND,
) {
    init { require(capacity >= 1) { "capacity must be >= 1, was $capacity" } }

    public companion object {
        /** Generous default; revisit if a conformance test throttles on it. */
        public const val DEFAULT_CAPACITY: Int = 256

        /** Lossless, ordered, backpressured — the contract-faithful default. */
        public val Reliable: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.SUSPEND)

        /** Lossy: drops the oldest buffered frame instead of blocking. Models a radio/UDP fabric. */
        public val Lossy: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.DROP_OLDEST)

        /** Strict: a full buffer throws [FrameOverflow]. For tests asserting no overflow. */
        public val Strict: DeliveryPolicy = DeliveryPolicy(overflow = Overflow.FAIL)
    }
}

/** Thrown by a [Overflow.FAIL] delivery buffer when a frame arrives and the buffer is full. */
public class FrameOverflow(message: String) : RuntimeException(message)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*DeliveryPolicyTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/DeliveryPolicy.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/DeliveryPolicyTest.kt
git commit -m "Add configurable DeliveryPolicy + Overflow + FrameOverflow (#701)"
```

---

### Task 2: `Mailbox` primitive

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/Mailbox.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/internal/MailboxTest.kt`

**Interfaces:**
- Consumes: `DeliveryPolicy`, `Overflow`, `FrameOverflow`, `Swatch` (existing).
- Produces: `internal class Mailbox(policy: DeliveryPolicy)` with `suspend fun deliver(frame: Swatch)`, `val incoming: Flow<Swatch>`, `fun close()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Swatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailboxTest {
    private fun frame(b: Byte) = Swatch(payload = byteArrayOf(b), sender = null, sequence = b.toLong())

    @Test
    fun reliableDeliversInOrder() = runTest {
        val box = Mailbox(DeliveryPolicy.Reliable)
        box.deliver(frame(1)); box.deliver(frame(2)); box.deliver(frame(3))
        val got = box.incoming.take(3).toList().map { it.sequence }
        assertEquals(listOf(1L, 2L, 3L), got)
    }

    @Test
    fun suspendBlocksWhenFull() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND))
        box.deliver(frame(1))                       // fills the buffer
        var second = false
        val job = backgroundScope.launch { box.deliver(frame(2)); second = true }
        runCurrent()
        assertTrue(!second, "second deliver must suspend while the buffer is full")
        assertEquals(1L, box.incoming.first().sequence) // drain one
        runCurrent()
        assertTrue(second, "second deliver completes once space frees")
        job.cancel()
    }

    @Test
    fun dropOldestBoundsAndKeepsNewest() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.DROP_OLDEST))
        box.deliver(frame(1)); box.deliver(frame(2)) // 1 dropped, never suspends
        assertEquals(2L, box.incoming.first().sequence)
    }

    @Test
    fun failThrowsOnOverflow() = runTest {
        val box = Mailbox(DeliveryPolicy(capacity = 1, overflow = Overflow.FAIL))
        box.deliver(frame(1))
        assertFailsWith<FrameOverflow> { box.deliver(frame(2)) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kuilt-core:jvmTest --tests "*MailboxTest*"`
Expected: FAIL — `Unresolved reference 'Mailbox'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.core.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.DeliveryPolicy
import us.tractat.kuilt.core.FrameOverflow
import us.tractat.kuilt.core.Overflow
import us.tractat.kuilt.core.Swatch

/**
 * The one sanctioned per-receiver inbound buffer for in-process fabrics. Always bounded; its
 * overflow behaviour is the injected [DeliveryPolicy]. `SUSPEND`/`DROP_*` delegate to the
 * channel's native [BufferOverflow]; `FAIL` is enforced explicitly. Single-collection FIFO
 * (collect [incoming] once).
 */
internal class Mailbox(private val policy: DeliveryPolicy) {
    private val channel: Channel<Swatch> =
        if (policy.overflow == Overflow.FAIL) {
            Channel(capacity = policy.capacity, onBufferOverflow = BufferOverflow.SUSPEND)
        } else {
            Channel(capacity = policy.capacity, onBufferOverflow = policy.overflow.toBufferOverflow())
        }

    val incoming: Flow<Swatch> = channel.receiveAsFlow()

    suspend fun deliver(frame: Swatch) {
        when (policy.overflow) {
            Overflow.FAIL ->
                if (!channel.trySend(frame).isSuccess) {
                    throw FrameOverflow("delivery buffer full (capacity=${policy.capacity})")
                }
            // SUSPEND suspends; DROP_* never suspend (the channel handles overflow).
            else -> channel.send(frame)
        }
    }

    fun close() {
        channel.close()
    }
}

private fun Overflow.toBufferOverflow(): BufferOverflow = when (this) {
    Overflow.SUSPEND -> BufferOverflow.SUSPEND
    Overflow.DROP_OLDEST -> BufferOverflow.DROP_OLDEST
    Overflow.DROP_LATEST -> BufferOverflow.DROP_LATEST
    Overflow.FAIL -> BufferOverflow.SUSPEND // unreachable; FAIL handled in deliver()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kuilt-core:jvmTest --tests "*MailboxTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/Mailbox.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/internal/MailboxTest.kt
git commit -m "Add bounded Mailbox delivery primitive (#701)"
```

---

### Task 3: Migrate `InMemoryLoom` onto `Mailbox`

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomPolicyTest.kt` (new); existing `InMemoryLoomTest.kt` must stay green.

**Interfaces:**
- Consumes: `Mailbox`, `DeliveryPolicy` from Tasks 1–2.
- Produces: `InMemoryLoom(policy: DeliveryPolicy = DeliveryPolicy.Reliable)` — same public surface plus the optional policy; `InMemorySeam.deliver` becomes `suspend`.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryLoomPolicyTest {
    @Test
    fun reliableLoomAppliesBackpressureInsteadOfUnboundedBuffering() = runTest {
        // A loom with capacity 1: a sender that outruns the receiver must SUSPEND, not pile up.
        val loom = InMemoryLoom(DeliveryPolicy(capacity = 1, overflow = Overflow.SUSPEND))
        val a = loom.host(Pattern("a"))
        val b = loom.join(InMemoryTag("a"))
        b.peers.first { a.selfId in it }            // links established

        var sentBoth = false
        val job = backgroundScope.launch {
            a.broadcast(byteArrayOf(1))
            a.broadcast(byteArrayOf(2))             // must suspend: b's mailbox (cap 1) is full
            sentBoth = true
        }
        runCurrent()
        assertTrue(!sentBoth, "second broadcast must suspend under backpressure")

        assertEquals(byteArrayOf(1).toList(), b.incoming.first().payload.toList()) // drain one
        runCurrent()
        assertTrue(sentBoth, "second broadcast completes once the receiver drains")
        job.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*InMemoryLoomPolicyTest*"`
Expected: FAIL — `InMemoryLoom` has no `DeliveryPolicy` constructor (unresolved), or (after adding the param) the second broadcast does NOT suspend because the channel is still UNLIMITED.

- [ ] **Step 3: Implement — add the policy + Mailbox + deliver outside the lock**

In `InMemoryLoom.kt`:

1. Add the constructor parameter:

```kotlin
public class InMemoryLoom(
    private val policy: DeliveryPolicy = DeliveryPolicy.Reliable,
) : Loom {
```

2. Replace the `InMemorySeam` channel with a `Mailbox` and make `deliver` suspend:

```kotlin
private class InMemorySeam(
    override val selfId: PeerId,
    private val factory: InMemoryLoom,
    policy: DeliveryPolicy,
) : Seam {
    private val mailbox = Mailbox(policy)
    private var closed = false
    private var sequenceCounter = 0L

    override val incoming: Flow<Swatch> = mailbox.incoming

    internal fun nextSequence(): Long = ++sequenceCounter

    internal suspend fun deliver(frame: Swatch) {
        if (!closed) mailbox.deliver(frame)
    }

    override suspend fun close(reason: CloseReason) {
        if (closed) return
        closed = true
        _state.value = Torn(reason)
        factory.remove(selfId)
        mailbox.close()
    }
    // ... rest unchanged (broadcast/sendTo/checkNotClosed) ...
}
```

3. Pass the policy when minting a seam:

```kotlin
private fun newSeam(): InMemorySeam {
    val id = freshPeerId()
    val link = InMemorySeam(id, this, policy)
    links[id] = link
    _peers.update { it + id }
    return link
}
```

4. Restructure `dispatch` so the suspending deliver happens **outside** the mutex — assign
   sequences under the lock, deliver after release:

```kotlin
internal suspend fun dispatch(
    sender: PeerId,
    payload: ByteArray,
    recipient: PeerId?,
) {
    // Snapshot (target, sequenced-frame) pairs under the lock — sequence assignment stays
    // atomic and ordered — then deliver outside the lock so a SUSPEND-policy backpressure
    // suspension never holds the factory mutex.
    val deliveries: List<Pair<InMemorySeam, Swatch>> = mutex.withLock {
        val targetIds = if (recipient == null) {
            links.keys.filter { it != sender }
        } else {
            listOf(recipient)
        }
        targetIds.mapNotNull { targetId ->
            val target = links[targetId] ?: return@mapNotNull null
            target to Swatch(payload = payload, sender = sender, sequence = target.nextSequence())
        }
    }
    for ((target, frame) in deliveries) {
        target.deliver(frame)
    }
}
```

- [ ] **Step 4: Run the new test + the existing suite to verify green**

Run: `./gradlew :kuilt-core:jvmTest --tests "*InMemoryLoomPolicyTest*" --tests "*InMemoryLoomTest*"`
Expected: PASS — backpressure test passes AND existing `InMemoryLoomTest` (`sequence == 1,2,3`, etc.) stays green.

- [ ] **Step 5: Run conformance + detekt**

Run: `./gradlew :kuilt-core:jvmTest :kuilt-core:detektAll`
Expected: BUILD SUCCESSFUL — `InMemoryLoomConformanceTest` (SeamConformanceSuite) green, zero detekt issues.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/InMemoryLoomPolicyTest.kt
git commit -m "Migrate InMemoryLoom to bounded Mailbox delivery (#701)"
```

---

### Task 4: Phase-1 acceptance gate — does backpressure beat the #655 OOM without deadlock?

This task **decides** the SUSPEND implementation for the whole epic (generous bound vs per-link pump). It runs the #655 stress test against the now-bounded `InMemoryLoom` under a tiny heap.

**Files:**
- Temporary (experiment only, reverted): `kuilt-quilter/build.gradle.kts` test-fork `maxHeapSize`.

- [ ] **Step 1: Force a tiny fork heap (experiment)**

Temporarily set the `:kuilt-quilter` test fork heap to 16 MB (the value at which the test
OOM'd *before* bounding, per #655):

```kotlin
tasks.withType<Test>().configureEach { maxHeapSize = "16m" }
```

- [ ] **Step 2: Run the stress test against the bounded loom**

Run: `timeout 240 ./gradlew :kuilt-quilter:jvmTest --tests "*QuilterConcurrencyTest*" --rerun-tasks`

Interpret:
- **PASS (converges, no OOM, no hang)** → a generous bounded `SUSPEND` is sufficient. The epic
  proceeds with no pump. The `:kuilt-quilter` `maxHeapSize` pin from #655 can later be relaxed
  in its own PR (note it; do not relax here).
- **HANG (timeout, `jstack` shows coroutines parked in `deliver`/`send`)** → cyclic-mesh
  deadlock confirmed. Do NOT widen anything. Stop and re-plan: add the per-link outbound pump
  (spec §"Deadlock", option b) as a new Task before any further fabric migration.
- **OOM at 16m** → backpressure isn't bounding as expected; `jstack`/heap-dump and re-plan
  (capacity too large for 16m, or a buffer still unbounded somewhere).

- [ ] **Step 3: Revert the experiment**

```bash
git checkout kuilt-quilter/build.gradle.kts
```

- [ ] **Step 4: Record the verdict**

Comment the outcome (PASS / HANG / OOM + evidence) on the epic's Phase-1 sub-issue. This verdict
is the input to planning Phases 2…N. No commit (experiment reverted).

---

## Deferred to later phases (not this plan)

- **Detekt/scan guard** forbidding `Channel.UNLIMITED` for delivery — can only pass once **all**
  sites are migrated, so it lands in the epic's final phase, not here.
- **Phases 2…N:** one PR per fabric/double (`MeshSeam`, `LinkSeam`, `SingleCollectionConnection`,
  `CompositeSeam` + `limitedParallelism(1)` retirement; then `FakeSeam`, `FakeRoom`,
  `ControllableLoom`, `ConnectionPair`, `MultiNodeRaftNetwork`, `FakeRaftNode`). Each gets its own
  plan, shaped by Task 4's verdict.
- **Relax the #655 heap pin** once Task 4 confirms backpressure bounds memory — its own small PR.

## Self-review

- **Spec coverage:** `DeliveryPolicy`/`Overflow`/presets → Task 1; `Mailbox` + all four overflow
  behaviours → Task 2; `InMemoryLoom` migration + deliver-outside-lock → Task 3; deadlock
  empirical gate → Task 4; detekt guard + Phases 2…N + heap-pin relax → explicitly deferred.
- **Types consistent:** `DeliveryPolicy(capacity, overflow)`, `Overflow.{SUSPEND,DROP_OLDEST,DROP_LATEST,FAIL}`,
  `Mailbox(policy).deliver/incoming/close`, `FrameOverflow` — same names across Tasks 1–3.
- **Placeholder scan:** clean — every code step shows the concrete code; the only "deferred"
  items are explicitly out of this plan's scope (detekt guard, Phases 2…N, heap-pin relax) and
  named as such.
