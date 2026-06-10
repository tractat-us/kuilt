# EphemeralMap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `EphemeralMap<V>` presence/awareness CRDT to `:kuilt-crdt` plus an `EphemeralMapCoordinator` that drives heartbeats, the live-presence view, departure tombstones, and local-grace compaction.

**Architecture:** A per-owner LWW slot CRDT (`ReplicaId → Slot(clock, value?)`) with a pure max-merge whose tie-break is `(clock desc, present > null)`. TTL/liveness lives entirely *outside* `piece`, as a read-time projection computed by the coordinator from local receive wall-clock. Departure is a `null`-slot tombstone (self- or detector-authored); memory is reclaimed by a local, non-broadcasting state rewrite. Full rationale: `docs/superpowers/specs/2026-06-09-ephemeral-map-design.md`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization (`@Serializable`, CBOR via the replicator), kotlinx.coroutines (`StateFlow`, `UnconfinedTestDispatcher`). `explicitApi()` is enforced — every public declaration needs an explicit modifier.

---

## File structure

| File | Responsibility |
|---|---|
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt` | The CRDT: `Slot<V>`, `EphemeralMap<V>`, `set`/`piece`/`present`/`get`/`forget`. Pure lattice, no time, no coroutines. |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt` | *Modify:* add `compactLocal(transform)` (local, non-broadcasting state rewrite) and make `SystemMonotonicMillis` `internal` for reuse. |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinator.kt` | `EphemeralMapConfig` + `EphemeralMapCoordinator<V>`: heartbeat ticker, `presence` flow, receive-time stamping, silence→tombstone sweep, local-grace compaction. |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt` | CRDT unit + lattice-law + tie-break + serialization tests. |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinatorTest.kt` | Coordinator behavior under `UnconfinedTestDispatcher` + virtual-time clock. |

Run JVM tests fast with `./gradlew :kuilt-crdt:jvmTest --tests "<pattern>"` after sourcing JDK 21:
`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`

---

## Task 1: EphemeralMap core — slot, set, piece, present, get

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EphemeralMapTest {

    private val a = ReplicaId("A")
    private val b = ReplicaId("B")

    @Test
    fun emptyHasNoPresence() {
        assertEquals(emptyMap<ReplicaId, String>(), EphemeralMap.empty<String>().present)
    }

    @Test
    fun setOwnSlotShowsValue() {
        val m = EphemeralMap.empty<String>().set(a, 1L, "thinking")
        assertEquals("thinking", m[a])
        assertEquals(mapOf(a to "thinking"), m.present)
    }

    @Test
    fun higherClockWins() {
        val m1 = EphemeralMap.empty<String>().set(a, 1L, "idle")
        val m2 = EphemeralMap.empty<String>().set(a, 2L, "active")
        assertEquals("active", m1.piece(m2)[a])
        assertEquals("active", m2.piece(m1)[a]) // commutative
    }

    @Test
    fun distinctOwnersComposeIndependently() {
        val m1 = EphemeralMap.empty<String>().set(a, 5L, "a-val")
        val m2 = EphemeralMap.empty<String>().set(b, 3L, "b-val")
        val merged = m1.piece(m2)
        assertEquals("a-val", merged[a])
        assertEquals("b-val", merged[b])
    }

    @Test
    fun unknownOwnerIsNull() {
        assertNull(EphemeralMap.empty<String>()[a])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapTest"`
Expected: FAIL — `EphemeralMap` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package us.tractat.kuilt.crdt

import kotlinx.serialization.Serializable

/**
 * One peer's ephemeral slot: a presence [value] tagged with a per-owner logical
 * [clock]. `value == null` is a **tombstone** — the owner has departed (gracefully
 * self-authored, or detector-authored on TTL silence; see [EphemeralMap]).
 */
@Serializable
public data class Slot<V>(public val clock: Long, public val value: V?)

/**
 * A presence/awareness CRDT: each peer owns exactly one slot keyed by its
 * [ReplicaId]. Convergence is a pure last-writer-wins max over the slot tag
 * `(clock, present-bit)` — higher [Slot.clock] wins; at equal clock a present
 * value beats a `null` tombstone (so a live peer deterministically overrides a
 * false tombstone). This `piece` is a join-semilattice and never expires
 * anything; TTL **liveness** is a read-time projection owned by the coordinator
 * (see `EphemeralMapCoordinator`), never part of the merge.
 *
 * Design: `docs/superpowers/specs/2026-06-09-ephemeral-map-design.md`.
 */
@Serializable
public class EphemeralMap<V> private constructor(
    public val slots: Map<ReplicaId, Slot<V>>,
) : Quilted<EphemeralMap<V>> {

    /** Currently non-tombstoned owners and their values (ignores TTL — that is the coordinator's view). */
    public val present: Map<ReplicaId, V>
        get() = slots.mapNotNull { (owner, slot) -> slot.value?.let { owner to it } }.toMap()

    /** The current value for [owner], or `null` if unset or tombstoned. */
    public operator fun get(owner: ReplicaId): V? = slots[owner]?.value

    /**
     * Write [owner]'s slot at logical [clock] with [value] (`null` = tombstone).
     *
     * **Precondition.** Application code writes only its **own** slot. The sole
     * exception is a system-level (coordinator) tombstone for a departed peer.
     * [clock] MUST be monotonic per owner and never reused for two different
     * values; reuse breaks deterministic convergence (same precondition as
     * [LWWRegister]). Not enforced at runtime.
     */
    public fun set(owner: ReplicaId, clock: Long, value: V?): EphemeralMap<V> {
        val incoming = Slot(clock, value)
        val current = slots[owner]
        val merged = if (current == null) incoming else maxSlot(current, incoming)
        return EphemeralMap(slots + (owner to merged))
    }

    /** The join: per-owner max by `(clock, present-bit)`. */
    override fun piece(other: EphemeralMap<V>): EphemeralMap<V> {
        val merged = HashMap(slots)
        for ((owner, theirs) in other.slots) {
            val mine = merged[owner]
            merged[owner] = if (mine == null) theirs else maxSlot(mine, theirs)
        }
        return EphemeralMap(merged)
    }

    override fun equals(other: Any?): Boolean = other is EphemeralMap<*> && slots == other.slots
    override fun hashCode(): Int = slots.hashCode()
    override fun toString(): String = "EphemeralMap($slots)"

    public companion object {
        /** The empty map. */
        public fun <V> empty(): EphemeralMap<V> = EphemeralMap(emptyMap())
    }
}

/**
 * Per-owner slot join: higher [Slot.clock] wins; at equal clock a present value
 * beats a `null` tombstone. A valid total order, so the max is idempotent,
 * commutative, and associative.
 */
private fun <V> maxSlot(a: Slot<V>, b: Slot<V>): Slot<V> = when {
    b.clock > a.clock -> b
    b.clock < a.clock -> a
    b.value != null && a.value == null -> b // equal clock: present beats tombstone
    else -> a
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
git commit -m "$(cat <<'EOF'
feat(kuilt-crdt): EphemeralMap CRDT core — per-owner LWW slots (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Lattice laws, present-over-null tie-break, forget(), serialization

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt` (add `forget`)
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests** (append inside `EphemeralMapTest`)

```kotlin
    @Test
    fun presentBeatsTombstoneAtEqualClock() {
        val live = EphemeralMap.empty<String>().set(a, 7L, "active")   // real heartbeat
        val tomb = EphemeralMap.empty<String>().set(a, 7L, null)       // false detector tombstone
        assertEquals("active", live.piece(tomb)[a])
        assertEquals("active", tomb.piece(live)[a]) // commutative — live peer always survives
    }

    @Test
    fun laterTombstoneRemovesValue() {
        val live = EphemeralMap.empty<String>().set(a, 7L, "active")
        val tomb = EphemeralMap.empty<String>().set(a, 8L, null)       // genuine departure
        assertNull(live.piece(tomb)[a])
        assertEquals(emptyMap<ReplicaId, String>(), live.piece(tomb).present)
    }

    @Test
    fun pieceIsIdempotentCommutativeAssociative() {
        val x = EphemeralMap.empty<String>().set(a, 3L, "x")
        val y = EphemeralMap.empty<String>().set(a, 5L, "y").set(b, 2L, "yb")
        val z = EphemeralMap.empty<String>().set(b, 9L, null)
        assertEquals(x, x.piece(x))                                   // idempotent
        assertEquals(x.piece(y), y.piece(x))                         // commutative
        assertEquals(x.piece(y).piece(z), x.piece(y.piece(z)))      // associative
    }

    @Test
    fun forgetDropsNamedSlots() {
        val m = EphemeralMap.empty<String>().set(a, 8L, null).set(b, 2L, "b")
        val after = m.forget(setOf(a))
        assertNull(after[a])
        assertEquals("b", after[b])
        assertEquals(setOf(b), after.slots.keys)                    // a's bytes are gone
    }

    @Test
    fun roundTripsThroughJson() {
        val m = EphemeralMap.empty<String>().set(a, 8L, "active").set(b, 3L, null)
        val ser = EphemeralMap.serializer(String.serializer())
        assertEquals(m, Json.decodeFromString(ser, Json.encodeToString(ser, m)))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapTest"`
Expected: FAIL — `forget` unresolved reference (other new asserts compile but `forget` does not exist).

- [ ] **Step 3: Add `forget` to `EphemeralMap`** (insert after the `piece` method)

```kotlin
    /**
     * Local memory reclamation: drop the slots owned by [owners]. Intended for
     * coordinator-driven compaction of tombstones that have outlived their grace
     * window — applied **locally and without broadcast** (see
     * `SeamReplicator.compactLocal`). Safe despite being non-monotonic: a
     * redelivered delta may re-add a dropped slot, but the coordinator's
     * read-time liveness view hides it (stale receive-time / `null` value), so
     * resurrection is never observable in the presence view.
     */
    public fun forget(owners: Set<ReplicaId>): EphemeralMap<V> =
        if (owners.isEmpty()) this else EphemeralMap(slots - owners)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapTest"`
Expected: PASS (10 tests total).

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/EphemeralMap.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/EphemeralMapTest.kt
git commit -m "$(cat <<'EOF'
test(kuilt-crdt): EphemeralMap lattice laws + present-over-null + forget (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: SeamReplicator hooks — compactLocal + reusable clock

The coordinator needs (a) a way to shrink local state without broadcasting, and (b) the default monotonic clock. Both are tiny additions to `SeamReplicator.kt`.

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorTest.kt` (add one test)

- [ ] **Step 1: Write the failing test** (append a new `@Test` to `SeamReplicatorTest`; reuse that file's existing in-memory `Seam`/scope setup — search it for an existing test that builds a `SeamReplicator<…>` and copy that construction)

```kotlin
    @Test
    fun compactLocalRewritesStateWithoutBroadcasting() = runTest(UnconfinedTestDispatcher()) {
        // Build a single replicator over an in-memory seam exactly as the other
        // tests in this file do (same `seam`, `messageSerializer`, `backgroundScope`).
        val replicator = newReplicatorUnderTest(initial = GCounter.empty())   // adapt to this file's helper
        replicator.apply(Patch(GCounter.empty().increment(replicator.replica, 5L)))
        val broadcastsBefore = sentFrameCount()                               // adapt to this file's seam spy

        replicator.compactLocal { GCounter.empty() }                          // local rewrite to empty

        assertEquals(GCounter.empty(), replicator.state.value)
        assertEquals(broadcastsBefore, sentFrameCount(), "compactLocal must not broadcast")
    }
```

> Adapt the three `// adapt` lines to whatever fixtures `SeamReplicatorTest` already
> provides (an in-memory seam, a frame-counting spy, and a replicator factory). If the
> file lacks a frame spy, assert only the state change and drop the broadcast assertion;
> the no-broadcast guarantee is also evident from the one-line implementation.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*SeamReplicatorTest"`
Expected: FAIL — `compactLocal` unresolved reference.

- [ ] **Step 3: Add `compactLocal` and widen the clock visibility**

In `SeamReplicator.kt`, add this method to the `SeamReplicator` class, immediately **after** the `apply` method (do not reorder existing members):

```kotlin
    /**
     * Local-only state rewrite for coordinator-driven memory reclamation. Applies
     * [transform] to [state] **without** broadcasting a delta, minting a seq, or
     * touching the delta/ack bookkeeping. Intended for shrinking operations a
     * coordinator performs identically on every replica (e.g.
     * `EphemeralMapCoordinator` dropping tombstones past their grace window).
     *
     * Unlike [apply], this is *not* a lattice patch and is *not* propagated: each
     * replica reclaims its own memory. A subsequent inbound delta or `FullState`
     * may re-grow what was dropped; callers that rely on this (presence) must keep
     * a read-time view that tolerates transient resurrection.
     */
    public fun compactLocal(transform: (S) -> S) {
        _state.update(transform)
    }
```

Then change the `SystemMonotonicMillis` visibility so the coordinator can reuse it as a default. Find:

```kotlin
private object SystemMonotonicMillis : MonotonicMillis {
```

and change `private` to `internal`:

```kotlin
internal object SystemMonotonicMillis : MonotonicMillis {
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*SeamReplicatorTest"`
Expected: PASS (existing tests + the new one).

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicator.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/SeamReplicatorTest.kt
git commit -m "$(cat <<'EOF'
feat(kuilt-crdt): SeamReplicator.compactLocal for local memory reclamation (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: EphemeralMapCoordinator — heartbeats, presence view, receive-time stamping

**Files:**
- Create: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinator.kt`
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinatorTest.kt`

**Test clock convention:** the coordinator reads `clock.now()` for TTL math and uses `delay(...)` for loop cadence. In tests, drive the clock off the virtual scheduler so both advance together: `MonotonicMillis { testScheduler.currentTime }`. Advancing virtual time then advances TTL time identically.

- [ ] **Step 1: Write the failing test**

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Fake replicator surface: a state flow plus apply/compactLocal that mutate it,
 * so the coordinator can be tested without a real Seam.
 */
private class FakeReplica<V>(initial: EphemeralMap<V>) {
    val state = MutableStateFlow(initial)
    fun apply(patch: Patch<EphemeralMap<V>>) { state.value = state.value.piece(patch.delta) }
    fun compactLocal(transform: (EphemeralMap<V>) -> EphemeralMap<V>) { state.value = transform(state.value) }
    /** Simulate an inbound delta from a remote peer. */
    fun receive(delta: EphemeralMap<V>) { state.value = state.value.piece(delta) }
}

class EphemeralMapCoordinatorTest {

    private val self = ReplicaId("self")
    private val peer = ReplicaId("peer")
    private val cfg = EphemeralMapConfig(
        ttl = 30.seconds,
        heartbeatInterval = 10.seconds,
        silenceSweepInterval = 10.seconds,
        compactionGrace = 60.seconds,
    )

    @Test
    fun publishMakesSelfPresent() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self = self,
            state = fake.state,
            apply = fake::apply,
            compactLocal = fake::compactLocal,
            scope = backgroundScope,
            config = cfg,
            clock = MonotonicMillis { testScheduler.currentTime },
        )
        coord.publish("active")
        assertEquals(mapOf(self to "active"), coord.presence.value)
    }

    @Test
    fun freshRemoteHeartbeatIsPresent() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        fake.receive(EphemeralMap.empty<String>().set(peer, 1L, "thinking"))
        assertEquals("thinking", coord.presence.value[peer])
    }

    @Test
    fun silentRemoteDropsFromPresenceAfterTtl() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        fake.receive(EphemeralMap.empty<String>().set(peer, 1L, "thinking"))
        assertEquals("thinking", coord.presence.value[peer])

        testScheduler.advanceTimeBy(31.seconds)   // past TTL, no refresh
        testScheduler.runCurrent()
        assertEquals(emptyMap<ReplicaId, String>(), coord.presence.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapCoordinatorTest"`
Expected: FAIL — `EphemeralMapCoordinator` / `EphemeralMapConfig` unresolved.

- [ ] **Step 3: Write the coordinator** (this step delivers heartbeat + presence + stamping; silence/compaction loops are added in Task 5 but their no-op-safe structure is included here)

```kotlin
package us.tractat.kuilt.crdt.replicator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import us.tractat.kuilt.crdt.EphemeralMap
import us.tractat.kuilt.crdt.Patch
import us.tractat.kuilt.crdt.ReplicaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for [EphemeralMapCoordinator].
 *
 * @param ttl a remote slot is shown present only if its owner was heard from within this window.
 * @param heartbeatInterval cadence for re-publishing this peer's own slot to refresh remote TTL.
 *   Keep comfortably below [ttl] so one dropped beat does not expire a live peer.
 * @param silenceSweepInterval cadence of the silence-detection + compaction sweep.
 * @param compactionGrace how long a `null` slot lingers locally before its bytes are dropped.
 */
public data class EphemeralMapConfig(
    val ttl: Duration = 30.seconds,
    val heartbeatInterval: Duration = 10.seconds,
    val silenceSweepInterval: Duration = 15.seconds,
    val compactionGrace: Duration = 60.seconds,
)

/**
 * Drives presence/awareness for an [EphemeralMap] replicated via [SeamReplicator]
 * (analogous to [RgaGcCoordinator]). Owns the four things that must live *outside*
 * the convergent lattice:
 *
 * 1. **Heartbeat** — periodically re-publishes this peer's own slot at `clock+1`.
 * 2. **Presence view** — [presence] filters the raw state to non-tombstoned slots
 *    heard from within [EphemeralMapConfig.ttl], by **local receive wall-clock**.
 * 3. **Silence → tombstone** — mints a `null` slot for any remote peer that has
 *    gone TTL-silent (detector-on-behalf departure).
 * 4. **Compaction** — drops `null` slots past [EphemeralMapConfig.compactionGrace]
 *    via [SeamReplicator.compactLocal] (local, non-broadcasting).
 *
 * Per the repo coroutine-determinism rule, [clock] and [scope]'s dispatcher are
 * injectable; tests pass `MonotonicMillis { testScheduler.currentTime }` and an
 * `UnconfinedTestDispatcher`-backed scope.
 *
 * Design: `docs/superpowers/specs/2026-06-09-ephemeral-map-design.md`.
 *
 * @param self this peer's [ReplicaId]; the only slot [publish]/[leave] write.
 * @param state the replicated [EphemeralMap] state (from [SeamReplicator.state]).
 * @param apply broadcasts a patch (wire to [SeamReplicator.apply]).
 * @param compactLocal local non-broadcasting rewrite (wire to [SeamReplicator.compactLocal]).
 * @param scope background scope for the heartbeat and sweep loops.
 * @param config tuning.
 * @param clock monotonic millis source; override in tests.
 */
public class EphemeralMapCoordinator<V>(
    private val self: ReplicaId,
    private val state: StateFlow<EphemeralMap<V>>,
    private val apply: (Patch<EphemeralMap<V>>) -> Unit,
    private val compactLocal: ((EphemeralMap<V>) -> EphemeralMap<V>) -> Unit,
    private val scope: CoroutineScope,
    private val config: EphemeralMapConfig = EphemeralMapConfig(),
    private val clock: MonotonicMillis = SystemMonotonicMillis,
) {
    private val _presence = MutableStateFlow<Map<ReplicaId, V>>(emptyMap())

    /** Owners currently present from this peer's vantage: non-tombstoned and heard-from within TTL. */
    public val presence: StateFlow<Map<ReplicaId, V>> = _presence.asStateFlow()

    private var joined = false
    private var localClock = 0L
    private var localValue: V? = null

    /** Local receive wall-clock per owner; the basis of the TTL view (never replicated). */
    private val receivedAt = mutableMapOf<ReplicaId, Long>()

    /** Highest slot clock seen per owner, to detect *advancing* (fresh) heartbeats. */
    private val lastClockSeen = mutableMapOf<ReplicaId, Long>()

    /** When a slot first became a local tombstone, for grace-based compaction. */
    private val tombstonedAt = mutableMapOf<ReplicaId, Long>()

    init {
        state.onEach { onState(it) }.launchIn(scope)
        scope.launch { heartbeatLoop() }
        scope.launch { sweepLoop() }
    }

    /** Publish or update this peer's presence value, and start heartbeating. */
    public fun publish(value: V) {
        joined = true
        localValue = value
        emitHeartbeat()
    }

    /** Graceful departure: self-author a tombstone and stop heartbeating. */
    public fun leave() {
        localValue = null
        emitHeartbeat()
        joined = false
    }

    // ---- private ----

    private fun emitHeartbeat() {
        localClock += 1
        apply(Patch(EphemeralMap.empty<V>().set(self, localClock, localValue)))
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(config.heartbeatInterval)
            if (joined) emitHeartbeat()
        }
    }

    private fun onState(snapshot: EphemeralMap<V>) {
        val now = clock.now()
        for ((owner, slot) in snapshot.slots) {
            if (owner == self) continue
            val prev = lastClockSeen[owner]
            if (prev == null || slot.clock > prev) {
                lastClockSeen[owner] = slot.clock
                if (slot.value != null) receivedAt[owner] = now
            }
            if (slot.value == null) {
                tombstonedAt.putIfAbsent(owner, now)
            } else {
                tombstonedAt.remove(owner)
            }
        }
        recomputePresence(now)
    }

    private fun recomputePresence(now: Long) {
        _presence.value = state.value.slots.mapNotNull { (owner, slot) ->
            val value = slot.value ?: return@mapNotNull null
            if (owner == self || fresh(owner, now)) owner to value else null
        }.toMap()
    }

    private fun fresh(owner: ReplicaId, now: Long): Boolean {
        val seen = receivedAt[owner] ?: return false
        return (now - seen) < config.ttl.inWholeMilliseconds
    }

    private suspend fun sweepLoop() {
        while (true) {
            delay(config.silenceSweepInterval)
            val now = clock.now()
            sweepSilence(now)
            compact(now)
            recomputePresence(now)
        }
    }

    /** Task 5 fills these in. */
    private fun sweepSilence(now: Long) {}
    private fun compact(now: Long) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapCoordinatorTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinator.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinatorTest.kt
git commit -m "$(cat <<'EOF'
feat(kuilt-crdt): EphemeralMapCoordinator heartbeat + read-time presence (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Silence→tombstone sweep, grace compaction, false-tombstone override

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinator.kt` (fill `sweepSilence` + `compact`)
- Test: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinatorTest.kt` (add tests)

- [ ] **Step 1: Write the failing tests** (append inside `EphemeralMapCoordinatorTest`)

```kotlin
    @Test
    fun gracefulLeaveRemovesImmediately() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        coord.publish("active")
        assertEquals(mapOf(self to "active"), coord.presence.value)
        coord.leave()
        assertEquals(emptyMap<ReplicaId, String>(), coord.presence.value)
    }

    @Test
    fun silentRemoteIsTombstonedAndBroadcast() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        fake.receive(EphemeralMap.empty<String>().set(peer, 4L, "thinking"))

        testScheduler.advanceTimeBy(31.seconds)   // past TTL → sweep mints a tombstone
        testScheduler.runCurrent()

        // The detector wrote null at seenClock+1 into the shared state.
        assertEquals(null, fake.state.value[peer])
        assertEquals(emptyMap<ReplicaId, String>(), coord.presence.value)
    }

    @Test
    fun falseTombstoneIsOverriddenByLiveHeartbeat() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        fake.receive(EphemeralMap.empty<String>().set(peer, 4L, "thinking"))
        testScheduler.advanceTimeBy(31.seconds)   // sweep tombstones peer at clock 5 (null)
        testScheduler.runCurrent()
        assertEquals(null, fake.state.value[peer])

        // peer was only partitioned: its real next heartbeat arrives at clock 6.
        fake.receive(EphemeralMap.empty<String>().set(peer, 6L, "back"))
        testScheduler.runCurrent()
        assertEquals("back", coord.presence.value[peer])
    }

    @Test
    fun tombstoneBytesAreCompactedAfterGrace() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeReplica(EphemeralMap.empty<String>())
        val coord = EphemeralMapCoordinator(
            self, fake.state, fake::apply, fake::compactLocal,
            backgroundScope, cfg, MonotonicMillis { testScheduler.currentTime },
        )
        fake.receive(EphemeralMap.empty<String>().set(peer, 9L, null)) // arrives already-departed
        testScheduler.runCurrent()
        // present excludes it immediately; bytes still held until grace elapses.
        assert(fake.state.value.slots.containsKey(peer))

        testScheduler.advanceTimeBy(61.seconds)   // past compactionGrace
        testScheduler.runCurrent()
        assertEquals(false, fake.state.value.slots.containsKey(peer))  // bytes dropped
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapCoordinatorTest"`
Expected: FAIL — `silentRemoteIsTombstonedAndBroadcast` and `tombstoneBytesAreCompactedAfterGrace` fail (sweep/compact are no-ops). `gracefulLeaveRemovesImmediately` and `falseTombstoneIsOverriddenByLiveHeartbeat` should already pass from Task 4.

- [ ] **Step 3: Implement `sweepSilence` and `compact`** (replace the two no-op stubs)

```kotlin
    /**
     * Detector-on-behalf departure: for every remote slot still showing a value
     * but unheard-from for [EphemeralMapConfig.ttl], broadcast a tombstone at
     * `slot.clock + 1`. The present-over-null tie-break guarantees a merely-
     * partitioned peer's next real heartbeat (a higher or equal clock with a
     * value) overrides this, so a false tombstone self-heals.
     */
    private fun sweepSilence(now: Long) {
        val ttlMs = config.ttl.inWholeMilliseconds
        state.value.slots.forEach { (owner, slot) ->
            if (owner == self || slot.value == null) return@forEach
            val seen = receivedAt[owner]
            if (seen == null || (now - seen) >= ttlMs) {
                apply(Patch(EphemeralMap.empty<V>().set(owner, slot.clock + 1, null)))
            }
        }
    }

    /**
     * Local memory reclamation: drop `null` slots that have been tombstoned for
     * longer than [EphemeralMapConfig.compactionGrace], via the non-broadcasting
     * [SeamReplicator.compactLocal]. Safe because a resurrected slot is hidden by
     * the read-time presence view.
     */
    private fun compact(now: Long) {
        val graceMs = config.compactionGrace.inWholeMilliseconds
        val expired = tombstonedAt.filter { (_, at) -> (now - at) >= graceMs }.keys.toSet()
        if (expired.isEmpty()) return
        compactLocal { it.forget(expired) }
        expired.forEach { owner ->
            tombstonedAt.remove(owner)
            receivedAt.remove(owner)
            lastClockSeen.remove(owner)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kuilt-crdt:jvmTest --tests "*EphemeralMapCoordinatorTest"`
Expected: PASS (7 tests total).

- [ ] **Step 5: Commit**

```bash
git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinator.kt \
        kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/replicator/EphemeralMapCoordinatorTest.kt
git commit -m "$(cat <<'EOF'
feat(kuilt-crdt): EphemeralMap silence tombstones + grace compaction (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Full multiplatform build + module-doc update

**Files:**
- Modify: `CLAUDE.md` (the `:kuilt-crdt` row lists the CRDT zoo — add `EphemeralMap`)

- [ ] **Step 1: Add `EphemeralMap` to the zoo list**

In `CLAUDE.md`, find the `:kuilt-crdt` table row beginning "The delta-state CRDT zoo (`GCounter`/…" and add `EphemeralMap` to the parenthesised list (after `Rga`), and append to the same cell: "plus `EphemeralMapCoordinator` (presence/awareness over a `Seam`)."

- [ ] **Step 2: Run the full multiplatform build** (catches Android-variant, wasmJs, and K/N issues `jvmTest` hides — see the repo's "run full build locally" rule)

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-crdt:build`
Expected: `BUILD SUCCESSFUL`. If `explicitApi()` flags a missing modifier, add `public`/`private` as needed.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(kuilt-crdt): list EphemeralMap in the CRDT zoo (#309)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Push and open PR** (squash auto-merge once `ci-required` is green, per the repo's aggressive pre-1.0 posture)

```bash
git push -u origin <branch>
gh pr create --base main --title "feat(kuilt-crdt): EphemeralMap presence/awareness CRDT (#309)" \
  --body "Implements the EphemeralMap design spec. Closes #309."
gh pr merge --auto --squash
```

---

## Self-review notes (for the implementer)

- **Spec coverage:** §1 lattice+tag → Tasks 1–2; §2 read-time liveness → Task 4 (`recomputePresence`/`fresh`); §3 heartbeat/graceful/detector departure → Tasks 4–5; §4 coordinator+config → Tasks 4–5; §5 local-grace compaction + the reclaimed-ReplicaId constraint (documented on `set`/`forget` KDoc) → Tasks 2–3, 5; §6 placement+tests → all tasks + Task 6 full build.
- **The reclaimed-ReplicaId constraint** (spec §5) is a *documentation* requirement, satisfied by the KDoc on `EphemeralMap.set` (clock monotonicity) and the spec link; no runtime enforcement, matching `LWWRegister`/`LWWMap`.
- **Determinism:** every coordinator test uses `UnconfinedTestDispatcher` + `MonotonicMillis { testScheduler.currentTime }` so virtual-time `delay`s and TTL math advance together — do not introduce a real wall-clock in tests.
- **Do not reorder** existing `SeamReplicator` members; `compactLocal` goes right after `apply`.
```
