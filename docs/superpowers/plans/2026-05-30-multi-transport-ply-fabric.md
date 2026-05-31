# Many-transport ("Ply") Composite Fabric Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one logical `Seam` be woven from several transports at once (a composite fabric), presenting a single peer set / `incoming` / `broadcast` while exposing per-ply health.

**Architecture:** A `CompositeLoom` (in `kuilt-core`, depends only on the `Loom`/`Seam` contract) wraps an ordered list of constituent `Loom`s. Its `CompositeSeam` mints one composite `PeerId`, reconciles per-ply transport ids to it via `Announce` control frames, broadcasts application `Data` frames over every ply, and collapses duplicates + restores per-origin order in one inbound gate. `Seam.plies` is added additively over axis-1's `state` rollup.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (`StateFlow`/`Flow`), `kotlin.test`. JDK 21 (`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`).

**Design spec:** `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`. Read it first.

**Prerequisite (met):** axis-1 `SeamState` (#50) is merged — `Seam.state`, `SeamState`, `PeerNotConnected`, `CloseReason.Unreachable`, and `DelayedWovenLoom` all exist.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `kuilt-core/.../core/PlyId.kt` (create) | Value class identifying one constituent link. |
| `kuilt-core/.../core/Seam.kt` (modify) | Add `plies` with a default single-ply view. |
| `kuilt-core/.../core/internal/MappedStateFlow.kt` (create) | Scope-free 1:1 `StateFlow` transform backing the `plies` default. |
| `kuilt-core/.../core/composite/PlyFrame.kt` (create) | Wire frame types (`Announce`/`Data`) + byte codec. |
| `kuilt-core/.../core/composite/PlyInboundGate.kt` (create) | Per-origin dedup + bounded reorder. |
| `kuilt-core/.../core/composite/CompositeLoom.kt` (create) | The composing `Loom`. |
| `kuilt-core/.../core/composite/CompositeSeam.kt` (create) | The composite `Seam`: identity reconcile, union peers, rollup, send/receive. |
| `kuilt-core/.../core/*Test.kt` (create) | Unit tests for the pure units above. |
| `kuilt-conformance/.../conformance/CompositeConformanceTest.kt` (create) | `CompositeLoom` over in-memory plies passes `SeamConformanceSuite`. |
| `kuilt-conformance/.../conformance/CompositeMultiPlyTest.kt` (create) | Two `DelayedWovenLoom` plies: rollup, exactly-once dedup, no-flap membership. |

Path prefix `kuilt-core/.../core/` = `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/`; tests under `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/`. Conformance prefix = `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/`.

**`explicitApi()` is enforced** — every public declaration needs an explicit `public`; helpers that are not contract surface are `internal`.

---

### Task 1: `PlyId` value class

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/PlyId.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/PlyIdTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlyIdTest {
    @Test
    fun valueIsPreservedAndEquality() {
        assertEquals(PlyId("relay"), PlyId("relay"))
        assertNotEquals(PlyId("relay"), PlyId("lan"))
        assertEquals("relay", PlyId("relay").value)
    }

    @Test
    fun soleIsAStableConstant() {
        assertEquals(PlyId.Sole, PlyId.Sole)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-core:jvmTest --tests "*PlyIdTest"`
Expected: FAIL — `PlyId` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.core

import kotlin.jvm.JvmInline

/** Stable identity of one constituent link ("ply") within a composite fabric. */
@JvmInline
public value class PlyId(public val value: String) {
    public companion object {
        /** The single ply of a non-composite (single-transport) fabric. */
        public val Sole: PlyId = PlyId("sole")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyIdTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/PlyId.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/PlyIdTest.kt
git commit --no-gpg-sign -m "feat(core): add PlyId"
```

---

### Task 2: `Seam.plies` with a default single-ply view

Adds `plies` additively. Every existing fabric inherits a default: a one-entry map `{ Sole -> state }`, satisfying the rollup invariant with **zero** changes to existing `Seam` impls. The default is backed by a scope-free 1:1 `StateFlow` transform.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/MappedStateFlow.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/SeamPliesDefaultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SeamPliesDefaultTest {
    @Test
    fun singlePlyFabricReportsOneEntryMapMatchingState() = runTest {
        val loom = InMemoryLoom()
        val seam = loom.host(Pattern("host"))
        val plies = seam.plies.value
        assertEquals(1, plies.size, "single-ply fabric reports exactly one ply")
        assertEquals(seam.state.value, plies[PlyId.Sole], "the sole ply's state equals the aggregate")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*SeamPliesDefaultTest"`
Expected: FAIL — `Seam.plies` is unresolved.

- [ ] **Step 3: Write `MappedStateFlow`**

```kotlin
package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A scope-free 1:1 view of [source] through [transform]. Valid as a [StateFlow]
 * only when [transform] is injective on [source]'s distinct values (so conflation
 * and distinct-until-changed are preserved). Used to derive [us.tractat.kuilt.core.Seam.plies]
 * from [us.tractat.kuilt.core.Seam.state] without owning a coroutine scope.
 */
internal class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.collect { collector.emit(transform(it)) }
    }
}
```

- [ ] **Step 4: Add `plies` to `Seam` with a default getter**

In `Seam.kt`, add the import and the member (place after the existing `state` declaration; do not reorder existing members):

```kotlin
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.internal.MappedStateFlow

// ... inside interface Seam, after `val state` ...

    /**
     * Per-ply lifecycle breakdown. Single-ply fabrics report a one-entry map
     * keyed by [PlyId.Sole]. Invariant: `state.value` equals the rollup of
     * `plies.value.values` under "any ply Woven ⇒ Woven".
     */
    public val plies: StateFlow<Map<PlyId, SeamState>>
        get() = MappedStateFlow(state) { mapOf(PlyId.Sole to it) }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*SeamPliesDefaultTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/MappedStateFlow.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/SeamPliesDefaultTest.kt
git commit --no-gpg-sign -m "feat(core): add Seam.plies with single-ply default"
```

---

### Task 3: `PlyFrame` wire types + codec

The composite wire carries two frame types behind a one-byte tag: `Announce(compositeId)` and `Data(originId, originSeq, payload)`. Encoding is manual and self-describing; plies treat the whole byte array as an opaque `Swatch` payload. Both frame types are `internal` — wire detail, not contract surface.

Layout (big-endian): `[tag:1]` then, for `Announce`, `[idLen:4][idUtf8]`; for `Data`, `[idLen:4][idUtf8][seq:8][payload:rest]`.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyFrame.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyFrameTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlyFrameTest {
    @Test
    fun announceRoundTrips() {
        val bytes = PlyFrame.encode(PlyFrame.Announce(PeerId("composite-7")))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Announce>(decoded)
        assertEquals(PeerId("composite-7"), decoded.compositeId)
    }

    @Test
    fun dataRoundTripsPreservingPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = PlyFrame.encode(PlyFrame.Data(PeerId("c"), originSeq = 42L, payload = payload))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Data>(decoded)
        assertEquals(PeerId("c"), decoded.originId)
        assertEquals(42L, decoded.originSeq)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val bytes = PlyFrame.encode(PlyFrame.Data(PeerId("c"), 0L, ByteArray(0)))
        val decoded = PlyFrame.decode(bytes)
        assertIs<PlyFrame.Data>(decoded)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun unknownTagThrows() {
        assertFailsWith<IllegalArgumentException> { PlyFrame.decode(byteArrayOf(99)) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyFrameTest"`
Expected: FAIL — `PlyFrame` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId

/** A frame on the composite wire. Opaque-payload bytes from each ply's perspective. */
internal sealed interface PlyFrame {
    /** Control: the sender's composite id, used to reconcile per-ply transport ids. */
    data class Announce(val compositeId: PeerId) : PlyFrame

    /** Application: origin-stamped payload for dedup + per-origin ordering. */
    data class Data(val originId: PeerId, val originSeq: Long, val payload: ByteArray) : PlyFrame

    companion object {
        private const val TAG_ANNOUNCE: Byte = 1
        private const val TAG_DATA: Byte = 2

        fun encode(frame: PlyFrame): ByteArray =
            when (frame) {
                is Announce -> {
                    val id = frame.compositeId.value.encodeToByteArray()
                    val out = ByteArray(1 + 4 + id.size)
                    out[0] = TAG_ANNOUNCE
                    writeInt(out, 1, id.size)
                    id.copyInto(out, 5)
                    out
                }
                is Data -> {
                    val id = frame.originId.value.encodeToByteArray()
                    val out = ByteArray(1 + 4 + id.size + 8 + frame.payload.size)
                    out[0] = TAG_DATA
                    writeInt(out, 1, id.size)
                    id.copyInto(out, 5)
                    writeLong(out, 5 + id.size, frame.originSeq)
                    frame.payload.copyInto(out, 5 + id.size + 8)
                    out
                }
            }

        fun decode(bytes: ByteArray): PlyFrame {
            require(bytes.isNotEmpty()) { "empty ply frame" }
            return when (bytes[0]) {
                TAG_ANNOUNCE -> {
                    val len = readInt(bytes, 1)
                    val id = bytes.decodeToString(5, 5 + len)
                    Announce(PeerId(id))
                }
                TAG_DATA -> {
                    val len = readInt(bytes, 1)
                    val id = bytes.decodeToString(5, 5 + len)
                    val seq = readLong(bytes, 5 + len)
                    val payload = bytes.copyOfRange(5 + len + 8, bytes.size)
                    Data(PeerId(id), seq, payload)
                }
                else -> throw IllegalArgumentException("unknown ply frame tag: ${bytes[0]}")
            }
        }

        private fun writeInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
        }
        private fun readInt(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
        private fun writeLong(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
        }
        private fun readLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyFrameTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyFrame.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyFrameTest.kt
git commit --no-gpg-sign -m "feat(core): add PlyFrame wire codec for composite fabric"
```

---

### Task 4: `PlyInboundGate` — dedup

A per-origin gate that collapses duplicate `Data` frames (same `(originId, originSeq)` arriving over multiple plies). Ordering is added in Task 5. The gate is pure and synchronous: `accept(frame)` returns the payloads to deliver now (empty if duplicate).

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyInboundGate.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyInboundGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlyInboundGateTest {
    private fun data(seq: Long, origin: String = "o", payload: Byte = seq.toByte()) =
        PlyFrame.Data(PeerId(origin), seq, byteArrayOf(payload))

    private fun seqs(out: List<ByteArray>) = out.map { it[0].toLong() }

    @Test
    fun firstFrameFromAnOriginIsDelivered() {
        val gate = PlyInboundGate(maxBuffered = 8)
        assertEquals(listOf(0L), seqs(gate.accept(data(0))))
    }

    @Test
    fun duplicateSecondCopyIsDropped() {
        val gate = PlyInboundGate(maxBuffered = 8)
        gate.accept(data(0))
        assertTrue(gate.accept(data(0)).isEmpty(), "the relay/overlay duplicate is dropped")
    }

    @Test
    fun distinctOriginsAreIndependent() {
        val gate = PlyInboundGate(maxBuffered = 8)
        assertEquals(listOf(0L), seqs(gate.accept(data(0, origin = "a"))))
        assertEquals(listOf(0L), seqs(gate.accept(data(0, origin = "b"))))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyInboundGateTest"`
Expected: FAIL — `PlyInboundGate` is unresolved.

- [ ] **Step 3: Write the implementation (dedup only)**

```kotlin
package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.PeerId

/**
 * Per-origin inbound gate for a composite fabric. Collapses duplicate [PlyFrame.Data]
 * (same `(originId, originSeq)` arriving over multiple plies). Reorder buffering is
 * added in a later step. Not thread-safe — the composite calls it from a single
 * inbound coroutine.
 */
internal class PlyInboundGate(private val maxBuffered: Int = 16) {
    // Per origin: the next sequence we expect to deliver.
    private val nextExpected = mutableMapOf<PeerId, Long>()
    private var seenAny = mutableSetOf<PeerId>()

    /** Returns the payloads to deliver now, in order. Empty for a duplicate. */
    fun accept(frame: PlyFrame.Data): List<ByteArray> {
        val origin = frame.originId
        if (origin !in seenAny) {
            // First sight of this origin: adopt its sequence as the baseline.
            seenAny.add(origin)
            nextExpected[origin] = frame.originSeq + 1
            return listOf(frame.payload)
        }
        val expected = nextExpected.getValue(origin)
        if (frame.originSeq < expected) return emptyList() // already delivered → duplicate/late
        if (frame.originSeq == expected) {
            nextExpected[origin] = expected + 1
            return listOf(frame.payload)
        }
        // frame.originSeq > expected: a gap. Reorder handling arrives in Task 5;
        // for now deliver immediately and advance (no buffering yet).
        nextExpected[origin] = frame.originSeq + 1
        return listOf(frame.payload)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyInboundGateTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyInboundGate.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyInboundGateTest.kt
git commit --no-gpg-sign -m "feat(core): add PlyInboundGate dedup"
```

---

### Task 5: `PlyInboundGate` — bounded per-origin reorder

Hold out-of-order frames per origin and release in sequence; cap the buffer at `maxBuffered` and, on overflow, skip the missing gap (advance to the lowest buffered sequence) to preserve liveness. Count-bounded so it is deterministic and clock-free; a time-based flush is a documented follow-up (see spec).

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyInboundGate.kt`
- Modify: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyInboundGateTest.kt`

- [ ] **Step 1: Add failing reorder tests**

Append to `PlyInboundGateTest`:

```kotlin
    @Test
    fun outOfOrderFramesAreReleasedInSequence() {
        val gate = PlyInboundGate(maxBuffered = 8)
        gate.accept(data(0))                          // baseline
        assertTrue(gate.accept(data(2)).isEmpty(), "seq 2 buffered, waiting for 1")
        assertEquals(listOf(1L, 2L), seqs(gate.accept(data(1))), "1 then buffered 2 drain")
    }

    @Test
    fun bufferOverflowSkipsTheGapForLiveness() {
        val gate = PlyInboundGate(maxBuffered = 2)
        gate.accept(data(0))                          // baseline, expect 1
        assertTrue(gate.accept(data(2)).isEmpty())    // buffer {2}
        // seq 3 arrives, buffer would exceed 2 held → skip the missing 1, release contiguous from lowest
        assertEquals(listOf(2L, 3L), seqs(gate.accept(data(3))))
    }

    @Test
    fun lateFrameAfterSkipIsDropped() {
        val gate = PlyInboundGate(maxBuffered = 2)
        gate.accept(data(0))
        gate.accept(data(2))
        gate.accept(data(3))                          // skipped past 1
        assertTrue(gate.accept(data(1)).isEmpty(), "the late, skipped-over frame is dropped")
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyInboundGateTest"`
Expected: FAIL — `outOfOrderFramesAreReleasedInSequence` (seq 2 is delivered immediately, not buffered).

- [ ] **Step 3: Replace the gap branch with bounded buffering**

Replace the body of `accept` after the baseline block with:

```kotlin
        val expected = nextExpected.getValue(origin)
        if (frame.originSeq < expected) return emptyList() // duplicate / already delivered / skipped

        val buffer = buffers.getOrPut(origin) { sortedMapByKey() }
        if (frame.originSeq == expected) {
            buffer[expected] = frame.payload
        } else {
            buffer[frame.originSeq] = frame.payload
            // Overflow: too many held out-of-order frames → skip the gap to the lowest buffered.
            if (buffer.size > maxBuffered) {
                nextExpected[origin] = buffer.firstKey()
            }
        }
        return drain(origin, buffer)
```

Add the supporting members and helpers to the class:

```kotlin
    private val buffers = mutableMapOf<PeerId, MutableMap<Long, ByteArray>>()

    private fun drain(origin: PeerId, buffer: MutableMap<Long, ByteArray>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var expect = nextExpected.getValue(origin)
        while (true) {
            val payload = buffer.remove(expect) ?: break
            out.add(payload)
            expect += 1
        }
        nextExpected[origin] = expect
        return out
    }
```

For a multiplatform-portable sorted map keyed by `Long`, add:

```kotlin
    // commonMain has no TreeMap; keep a small sorted-by-key map.
    private fun sortedMapByKey(): MutableMap<Long, ByteArray> = LinkedHashMap()
    private fun MutableMap<Long, ByteArray>.firstKey(): Long = keys.min()
```

> Note: `firstKey()` uses `keys.min()` so insertion order does not matter; the
> drain loop pulls strictly by expected sequence. `LinkedHashMap` is available in
> Kotlin common.

- [ ] **Step 4: Run test to verify all pass**

Run: `./gradlew :kuilt-core:jvmTest --tests "*PlyInboundGateTest"`
Expected: PASS (all dedup + reorder tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/PlyInboundGate.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/PlyInboundGateTest.kt
git commit --no-gpg-sign -m "feat(core): add bounded per-origin reorder to PlyInboundGate"
```

---

### Task 6: `CompositeLoom` + `CompositeSeam` skeleton — fan-out, state rollup, close

Build the composing `Loom` and a `CompositeSeam` that weaves every constituent ply, rolls their `state` up to the aggregate, exposes `plies`, and closes them all. Identity reconciliation and send/receive arrive in Tasks 7–8.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt`
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeRollupTest.kt`

- [ ] **Step 1: Write the failing test (uses two `InMemoryLoom` plies)**

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompositeRollupTest {
    @Test
    fun aggregateIsWovenWhenAnyPlyIsWovenAndPliesListsBoth() = runTest {
        val loom = CompositeLoom(
            listOf(PlyId("a") to InMemoryLoom(), PlyId("b") to InMemoryLoom()),
        )
        val seam = loom.host(Pattern("host"))
        assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        assertEquals(setOf(PlyId("a"), PlyId("b")), seam.plies.value.keys)
    }

    @Test
    fun closeDrivesAggregateTorn() = runTest {
        val loom = CompositeLoom(listOf(PlyId("a") to InMemoryLoom()))
        val seam = loom.host(Pattern("host"))
        seam.close()
        assertIs<SeamState.Torn>(seam.state.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositeRollupTest"`
Expected: FAIL — `CompositeLoom` is unresolved.

- [ ] **Step 3: Write `CompositeLoom`**

```kotlin
package us.tractat.kuilt.core.composite

import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies") at once. The union of plies must cover the session's peer set; the
 * list order is a send-preference hint (most-preferred first). See
 * `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`.
 */
public class CompositeLoom(
    private val plies: List<Pair<PlyId, Loom>>,
) : Loom {
    init {
        require(plies.isNotEmpty()) { "CompositeLoom needs at least one ply" }
        require(plies.map { it.first }.toSet().size == plies.size) { "duplicate PlyId" }
    }

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val woven = plies.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(woven)
    }

    override fun availability(): FabricAvailability =
        if (plies.any { it.second.availability() == FabricAvailability.Available }) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("no ply available")
        }
}
```

- [ ] **Step 4: Write `CompositeSeam` skeleton**

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch

internal class CompositeSeam(
    private val plies: List<Pair<PlyId, Seam>>,
) : Seam {
    private val scope = CoroutineScope(SupervisorJob())

    // A fresh composite identity for this peer, distinct from any per-ply transport id.
    override val selfId: PeerId = PeerId("composite-" + plies.joinToString("-") { it.second.selfId.value })

    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val _plies = MutableStateFlow(plies.associate { (id, seam) -> id to seam.state.value })
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // Filled in Task 7 (identity reconciliation).
    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    // Filled in Task 8 (send/receive). Empty for now.
    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    init {
        // Roll each ply's state up: any Woven ⇒ Woven; all Torn ⇒ Torn(first reason); else Weaving.
        combine(plies.map { it.second.state }) { states -> rollup(states.toList()) }
            .onEach { _state.value = it }
            .launchIn(scope)
        // Track the per-ply breakdown.
        plies.forEach { (id, seam) ->
            seam.state
                .onEach { s -> _plies.value = _plies.value.toMutableMap().apply { put(id, s) } }
                .launchIn(scope)
        }
    }

    private fun rollup(states: List<SeamState>): SeamState =
        when {
            states.any { it is SeamState.Woven } -> SeamState.Woven
            states.all { it is SeamState.Torn } ->
                states.filterIsInstance<SeamState.Torn>().first()
            else -> SeamState.Weaving
        }

    override suspend fun broadcast(payload: ByteArray) { TODO("Task 8") }
    override suspend fun sendTo(peer: PeerId, payload: ByteArray) { TODO("Task 8") }

    override suspend fun close(reason: CloseReason) {
        plies.forEach { (_, seam) -> seam.close(reason) }
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        scope.cancel()
    }
}
```

> The `TODO("Task 8")` bodies are placeholders **for this task only**; they are
> replaced with real implementations in Task 8 and must not remain at the end of
> the plan. `selfId` derivation here is a temporary deterministic stand-in;
> Task 7 keeps it but it is never sent on the wire except via `Announce`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositeRollupTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeRollupTest.kt
git commit --no-gpg-sign -m "feat(core): add CompositeLoom + CompositeSeam state rollup"
```

---

### Task 7: Identity reconciliation — `Announce` + union peers

On each ply reaching `Woven`, send `Announce(selfId)`. On receiving `Announce(C)` from transport id `T` on ply `P`, record `map[(P,T)] = C` and recompute `peers` as `{selfId} ∪ { C : its transport id is still in that ply's peers }`.

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositePeersTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositePeersTest {
    @Test
    fun twoCompositePeersSeeEachOtherOnceAcrossSharedPlies() = runTest {
        // One shared InMemoryLoom mesh used as a single ply by both composite peers.
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(us.tractat.kuilt.core.InMemoryTag("join"))

        // Each peer's `peers` eventually contains itself + the other composite id (size 2, no dup).
        val hostPeers = host.peers.first { it.size == 2 }
        assertEquals(2, hostPeers.size)
        assertEquals(true, host.selfId in hostPeers)
        assertEquals(true, joiner.selfId in hostPeers)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositePeersTest"`
Expected: FAIL — `peers` only ever contains `selfId` (reconciliation not wired).

- [ ] **Step 3: Implement reconciliation in `CompositeSeam`**

Add fields and wiring. In `init`, after the rollup blocks, add an inbound pump per ply that decodes frames, plus an announcer that fires when a ply becomes `Woven`:

```kotlin
    // (ply index, transport id) -> composite id
    private val idMap = mutableMapOf<Pair<Int, PeerId>, PeerId>()

    init {
        plies.forEachIndexed { index, (_, seam) ->
            // Announce our composite id once this ply is live, and again is harmless (idempotent map).
            seam.state
                .onEach { if (it is SeamState.Woven) seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
                .launchIn(scope)
            // Inbound pump: learn announcements, recompute peers; (Data handled in Task 8).
            seam.incoming
                .onEach { swatch -> onPlyFrame(index, swatch) }
                .launchIn(scope)
            // Recompute peers when a ply's membership changes (a transport id leaving removes its composite id).
            seam.peers
                .onEach { recomputePeers() }
                .launchIn(scope)
        }
    }

    private fun onPlyFrame(plyIndex: Int, swatch: Swatch) {
        val sender = swatch.sender ?: return
        when (val frame = PlyFrame.decode(swatch.payload)) {
            is PlyFrame.Announce -> {
                idMap[plyIndex to sender] = frame.compositeId
                recomputePeers()
            }
            is PlyFrame.Data -> { /* Task 8 */ }
        }
    }

    private fun recomputePeers() {
        val reachable = buildSet {
            add(selfId)
            idMap.forEach { (key, compositeId) ->
                val (plyIndex, transportId) = key
                if (transportId in plies[plyIndex].second.peers.value) add(compositeId)
            }
        }
        _peers.value = reachable
    }
```

> The existing `combine`/`_plies` blocks from Task 6 stay; this `init` block is
> additive (Kotlin allows multiple `init` blocks, executed in order). Keep the
> Task 6 `init` first.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositePeersTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositePeersTest.kt
git commit --no-gpg-sign -m "feat(core): composite identity reconciliation via Announce + union peers"
```

---

### Task 8: Send / receive — broadcast-all, `sendTo` resolve, gated `incoming`

`broadcast` wraps the payload in a `Data` frame and sends over every ply. `sendTo(C)` resolves `C` to a `(ply, transportId)` via `idMap` in send-preference order and sends there, else throws `PeerNotConnected`. Inbound `Data` frames pass through `PlyInboundGate` and are emitted as `Swatch(payload, sender = originId)`.

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeSendReceiveTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeSendReceiveTest {
    @Test
    fun broadcastDeliversBarePayloadToTheOtherPeer() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(InMemoryTag("join"))
        host.peers.first { it.size == 2 } // reconciled

        host.broadcast(byteArrayOf(7, 8, 9))
        val got = joiner.incoming.first()
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(got.payload))
    }

    @Test
    fun sendToUnknownPeerThrows() = runTest {
        val mem = InMemoryLoom()
        val loom = CompositeLoom(listOf(PlyId("mem") to mem))
        val host = loom.host(Pattern("host"))
        assertFailsWith<PeerNotConnected> { host.sendTo(PeerId("nobody"), byteArrayOf(1)) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositeSendReceiveTest"`
Expected: FAIL — `broadcast`/`sendTo` are `TODO()`.

- [ ] **Step 3: Implement send/receive**

Add the gate + outbound sequence and replace the `TODO` bodies and the `Data` branch:

```kotlin
    private val gate = PlyInboundGate()
    private var outSeq = 0L

    // In onPlyFrame's `is PlyFrame.Data` branch, replace `{ /* Task 8 */ }` with:
    //     is PlyFrame.Data -> gate.accept(frame).forEach { payload ->
    //         incomingChannel.trySend(Swatch(payload = payload, sender = frame.originId))
    //     }

    override suspend fun broadcast(payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        plies.forEach { (_, seam) -> seam.broadcast(bytes) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        // Resolve composite id -> (ply, transport id) in send-preference (list) order.
        for (index in plies.indices) {
            val transportId = idMap.entries.firstOrNull { (k, v) -> k.first == index && v == peer }?.key?.second
            if (transportId != null && transportId in plies[index].second.peers.value) {
                val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
                plies[index].second.sendTo(transportId, bytes)
                return
            }
        }
        throw PeerNotConnected(peer)
    }
```

Apply the `is PlyFrame.Data ->` change inside `onPlyFrame` (replace the `/* Task 8 */` comment with the body shown in the comment above).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-core:jvmTest --tests "*CompositeSendReceiveTest"`
Expected: PASS.

- [ ] **Step 5: Verify no `TODO(` remains in the composite**

Run: `grep -rn "TODO(" kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeSendReceiveTest.kt
git commit --no-gpg-sign -m "feat(core): composite send/receive via broadcast-all + inbound gate"
```

---

### Task 9: Composite passes `SeamConformanceSuite`

Prove a `CompositeLoom` over in-memory plies is itself a conformant `Seam`.

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeConformanceTest.kt`

- [ ] **Step 1: Write the conformance subclass**

```kotlin
package us.tractat.kuilt.conformance

import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.composite.CompositeLoom

/**
 * A single-ply [CompositeLoom] over a shared in-memory mesh must satisfy every
 * seam-contract invariant — composing does not weaken the contract.
 */
class CompositeConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair(): Pair<Loom, Loom> {
        // In-process fabric: host and joiner share one composite over one mesh.
        val composite = CompositeLoom(listOf(PlyId("mem") to InMemoryLoom()))
        return composite to composite
    }
}
```

- [ ] **Step 2: Run it (expect possible failures to fix)**

Run: `./gradlew :kuilt-conformance:jvmTest --tests "*CompositeConformanceTest"`
Expected: All suite tests PASS. If `broadcastFromHostDeliversToJoinedPeer` hangs or fails, the joiner likely sends before reconciliation — the suite awaits `Woven` (which is satisfied), and broadcast is fire-and-forget over the ply, so delivery should arrive; investigate the inbound pump if not.

- [ ] **Step 3: Commit**

```bash
git add kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeConformanceTest.kt
git commit --no-gpg-sign -m "test(conformance): CompositeLoom passes SeamConformanceSuite"
```

---

### Task 10: Multi-ply behaviours — rollup, exactly-once dedup, no-flap membership

Two `DelayedWovenLoom` plies bonded under one `CompositeLoom`, driving `markWoven` explicitly.

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package us.tractat.kuilt.conformance

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompositeMultiPlyTest {
    @Test
    fun aggregateWovenWhenOnlyOnePlyWoven() = runTest {
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = CompositeLoom(listOf(PlyId("a") to plyA, PlyId("b") to plyB))
        val seam = loom.host(Pattern("host"))
        assertIs<SeamState.Weaving>(seam.state.value)
        // Mark only ply A woven.
        (seam.pliesSeamFor(plyA)).markWoven()
        assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        assertEquals(SeamState.Woven, seam.plies.value[PlyId("a")])
    }
}
```

> Helper note: `DelayedWovenLoom.weave` returns a `DelayedWovenSeam`. To reach the
> right per-ply seam from the composite, capture the seams at weave time instead of
> a helper — rewrite using the looms directly (next step) since `CompositeSeam`
> does not expose its constituents.

- [ ] **Step 2: Rewrite the test to capture per-ply seams via the looms**

Replace the test body with a version that drives `markWoven` through the looms. Because `DelayedWovenLoom` tracks its seams internally, expose the most-recent seam for tests by adding to `DelayedWovenLoom` (in `kuilt-conformance` commonMain):

```kotlin
    /** Test hook: the seams woven by this loom, in weave order. */
    public val wovenSeams: List<DelayedWovenSeam> get() = links.values.toList()
```

Then the test:

```kotlin
    @Test
    fun aggregateWovenWhenOnlyOnePlyWoven() = runTest {
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = CompositeLoom(listOf(PlyId("a") to plyA, PlyId("b") to plyB))
        val seam = loom.host(Pattern("host"))
        assertIs<SeamState.Weaving>(seam.state.value)
        plyA.wovenSeams.single().markWoven()
        assertIs<SeamState.Woven>(seam.state.first { it is SeamState.Woven })
        assertEquals(SeamState.Woven, seam.plies.value[PlyId("a")])
        assertEquals(SeamState.Weaving, seam.plies.value[PlyId("b")])
    }

    @Test
    fun frameOverTwoSharedPliesIsDeliveredExactlyOnce() = runTest {
        // Both plies shared by host + joiner; a broadcast goes over both → dedup to one.
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = CompositeLoom(listOf(PlyId("a") to plyA, PlyId("b") to plyB))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(us.tractat.kuilt.core.InMemoryTag("join"))
        plyA.wovenSeams.forEach { it.markWoven() }
        plyB.wovenSeams.forEach { it.markWoven() }
        host.peers.first { it.size == 2 }

        host.broadcast(byteArrayOf(5))
        val received = joiner.incoming.take(1).toList()
        assertEquals(1, received.size, "exactly one delivery despite two plies carrying it")
        assertEquals(5, received.single().payload.single())
    }

    @Test
    fun onePlyTearingDoesNotRemoveAPeerStillOnAnother() = runTest {
        val plyA = DelayedWovenLoom()
        val plyB = DelayedWovenLoom()
        val loom = CompositeLoom(listOf(PlyId("a") to plyA, PlyId("b") to plyB))
        val host = loom.host(Pattern("host"))
        val joiner = loom.join(us.tractat.kuilt.core.InMemoryTag("join"))
        plyA.wovenSeams.forEach { it.markWoven() }
        plyB.wovenSeams.forEach { it.markWoven() }
        val peers = host.peers.first { it.size == 2 }
        assertEquals(2, peers.size)

        // Tear ply B's joiner link; the joiner is still reachable on ply A.
        plyB.wovenSeams.first { it.selfId != host.selfId }
            .close(us.tractat.kuilt.core.CloseReason.RemoteRequested)

        // Membership stays at 2 (no flap) and aggregate stays Woven.
        assertEquals(2, host.peers.value.size)
        assertIs<SeamState.Woven>(host.state.value)
    }
}
```

> If `wovenSeams` returns seams for both host and joiner (they share a loom mesh),
> `forEach { markWoven() }` drives them all — intended.

- [ ] **Step 3: Run to verify failures, then they pass once the hook is added**

Run: `./gradlew :kuilt-conformance:jvmTest --tests "*CompositeMultiPlyTest"`
Expected: PASS after adding `wovenSeams`. If `frameOverTwoSharedPliesIsDeliveredExactlyOnce` sees 2 frames, the dedup key is wrong (confirm both copies carry the same `selfId`/`outSeq`).

- [ ] **Step 4: Commit**

```bash
git add kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeMultiPlyTest.kt kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/DelayedWovenLoom.kt
git commit --no-gpg-sign -m "test(conformance): multi-ply rollup, dedup, no-flap membership"
```

---

### Task 11: Full build + spec self-check

- [ ] **Step 1: Full multiplatform build**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build`
Expected: BUILD SUCCESSFUL (all targets, `explicitApi` satisfied).

- [ ] **Step 2: Confirm no placeholders shipped**

Run: `grep -rn "TODO(" kuilt-core/src/commonMain kuilt-conformance/src`
Expected: no output.

- [ ] **Step 3: Commit any build-fix follow-ups, then open the PR out of draft.**

The PR closes the implementation portion of epic #49 (the epic stays open for deferred items: latency-optimized primary-ply send, application-layer gateway forwarding, dynamic ply attach).

---

## Self-Review

**Spec coverage:**
- Contract additions (`PlyId`, `Seam.plies`) → Tasks 1, 2.
- `CompositeLoom` fan-out + availability + close → Task 6.
- Lifecycle rollup (any Woven ⇒ Woven) → Task 6.
- Identity reconciliation (`Announce`, mapping, union peers, no-flap) → Tasks 7, 10.
- Dedup `(originId, originSeq)` + envelope → Tasks 3, 4, 8.
- Per-origin bounded reorder → Task 5.
- Broadcast-all send + `sendTo` resolve + `PeerNotConnected` → Task 8.
- Conformance (suite + two delayed-Woven plies) → Tasks 9, 10.
- Out-of-scope items (forwarding, federation, dynamic plies, primary-ply send) → not implemented, recorded in Task 11 as epic-open.

**Deferred / not covered by design (intentional):** time-based reorder flush (count-bounded only, per spec note); cross-ply global ordering (spec promises per-origin only).

**Type consistency:** `PlyFrame.Data(originId, originSeq, payload)` and `PlyFrame.Announce(compositeId)` are used identically in Tasks 3, 7, 8. `PlyInboundGate.accept(PlyFrame.Data): List<ByteArray>` consistent across Tasks 4, 5, 8. `CompositeLoom(List<Pair<PlyId, Loom>>)` consistent across Tasks 6, 9, 10. `wovenSeams` hook added in Task 10 and used only there.
