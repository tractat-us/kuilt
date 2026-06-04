# Dynamic Ply Attach/Detach Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a composite session's plies join and leave while it is live, instead of being frozen at `weave()`, behind the same unchanged `Seam` surface.

**Architecture:** Refactor `CompositeSeam` from a frozen-`List` constructor into a reconcile engine driven by a `StateFlow` of the *desired* ply set: it diffs each emission against the live set, weaving newly-desired plies and closing removed ones. The existing static-list `CompositeLoom` constructor delegates to a never-changing flow, so the static path is the degenerate dynamic path. No `Seam` contract change.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines (StateFlow/Channel/Job), kotlin.test. All work is in `:kuilt-core` (engine) and `:kuilt-conformance` (tests). JDK 21 via SDKMAN.

**Spec:** `docs/superpowers/specs/2026-06-04-dynamic-ply-attach-detach-design.md`. **Roadmap:** item 1 of `docs/ply-roadmap.md`.

**Build prelude (every shell):**
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

**Semantic decision baked in (Resolution A):** `rollup` maps the **empty** ply map to `Weaving`; a **non-empty** all-`Torn` map stays `Torn(reason)` (terminal, unchanged from the MVP). Recoverable `Weaving` is therefore reached when the desired set empties (detach removes torn plies), not when plies tear while still desired. This preserves the shipped `CompositeResilienceTest.aggregateGoesToTornOnlyWhenLastPlyTears` test. (The alternative — any all-`Torn` ⇒ `Weaving` — would make relay-death recoverable too, but requires editing that shipped test; not taken here.)

---

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt` | Rewrite (Task 1) | The reconcile engine: per-ply handles, `PlyId`-keyed `idMap`, `_state` derived from `_plies`, dispatcher-confined send. |
| `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt` | Modify (Task 1 internal wrap, Task 2 public flow ctor) | Weave the initial set; pass the desired-set flow to `CompositeSeam`. |
| `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeDynamicPlyTest.kt` | Create (Task 3) | Dynamic attach/detach/recover/re-attach + static-as-degenerate conformance. |

No other files change. `PlyFrame.kt`, `PlyInboundGate.kt`, `PlyId.kt`, and `Seam.kt` are untouched.

---

## Task 1: Refactor `CompositeSeam` to a reconcile engine (behaviour-preserving)

Rewrite `CompositeSeam` so it is *driven by* a desired-set `StateFlow` but, in this task, only ever receives a single, never-changing emission (the static list `CompositeLoom` already passes). Every existing composite test must stay green — this is a pure refactor with no externally observable change.

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt` (full rewrite)
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt:31-34` (wrap the list in a `MutableStateFlow` and pass it + rendezvous)
- Guarded by (no new test in this task): `kuilt-conformance/.../CompositeConformanceTest.kt`, `CompositeMultiPlyTest.kt`, `CompositeResilienceTest.kt`

- [ ] **Step 1: Confirm the baseline is green**

Run:
```bash
./gradlew :kuilt-core:jvmTest :kuilt-conformance:jvmTest
```
Expected: PASS. This is the regression net for the refactor.

- [ ] **Step 2: Rewrite `CompositeSeam.kt`**

Replace the entire file with:

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.PeerNotConnected
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.Swatch
import kotlin.coroutines.CoroutineContext

/**
 * The composite `Seam` woven by [CompositeLoom]. Presents a single peer set,
 * `incoming` flow, and send surface over a set of constituent plies that may
 * change while the session is live.
 *
 * **Dynamic plies:** [initial] is the set woven by [CompositeLoom] before
 * `weave()` returns. Thereafter the composite collects [desired] and reconciles:
 * a [PlyId] that appears is woven and attached; one that disappears is closed and
 * detached. The static (fixed-list) case is the degenerate one where [desired]
 * never changes after its first value.
 *
 * **Identity:** Each peer mints a composite [selfId] once from [initial] and never
 * recomputes it, so it is stable across attach/detach. On each ply reaching
 * [SeamState.Woven] the peer broadcasts a [PlyFrame.Announce] so the far side can
 * map `(plyId, transportId) → compositeId`.
 *
 * **Send:** [broadcast] wraps the payload in a [PlyFrame.Data] envelope and sends
 * over every live, non-torn ply. [sendTo] resolves the composite id to a
 * `(ply, transportId)` in send-preference order. Both run on [dispatcher] so they
 * never race reconcile's mutation of the live set.
 *
 * **Receive:** Inbound [PlyFrame.Data] frames are de-duplicated and reordered per
 * origin by a [PlyInboundGate]; application payloads emerge as [Swatch] values.
 *
 * @param dispatcher Confines all internal state access (reconcile, rollup,
 *   announce, inbound pumps, send) to a single thread. Production uses the confined
 *   default ([Dispatchers.Default.limitedParallelism(1)]); tests inject
 *   [UnconfinedTestDispatcher] to drive reconciliation eagerly.
 */
internal class CompositeSeam(
    initial: List<Pair<PlyId, Seam>>,
    private val rendezvous: Rendezvous,
    private val desired: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) : Seam {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = PlyInboundGate()
    private var outSeq = 0L

    // Minted once from the initial set; never recomputed, so it survives ply churn.
    override val selfId: PeerId = mintCompositeId(initial)

    private val _state = MutableStateFlow<SeamState>(SeamState.Weaving)
    override val state: StateFlow<SeamState> = _state.asStateFlow()

    private val _plies = MutableStateFlow<Map<PlyId, SeamState>>(emptyMap())
    override val plies: StateFlow<Map<PlyId, SeamState>> = _plies.asStateFlow()

    // (plyId, transport id) -> composite id; built as Announce frames arrive.
    private val idMap = mutableMapOf<Pair<PlyId, PeerId>, PeerId>()

    private val _peers = MutableStateFlow(setOf(selfId))
    override val peers: StateFlow<Set<PeerId>> = _peers.asStateFlow()

    private val incomingChannel = Channel<Swatch>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<Swatch> = incomingChannel.receiveAsFlow()

    // PlyId -> live ply, in send-preference (insertion) order. A LinkedHashMap so
    // broadcast/sendTo iterate most-preferred-first. Mutated only on [dispatcher].
    private val live = LinkedHashMap<PlyId, PlyHandle>()

    private class PlyHandle(val seam: Seam, val job: Job)

    init {
        // Aggregate state is derived from the per-ply map. Empty => Weaving.
        _plies
            .onEach { _state.value = rollup(it.values.toList()) }
            .launchIn(scope)

        // Seed the initial plies (already woven by CompositeLoom).
        initial.forEach { (id, seam) -> attachPly(id, seam) }

        // Reconcile on every desired-set change. The first emission equals the
        // initial set, so it produces no attach/detach.
        desired
            .onEach { reconcile(it) }
            .launchIn(scope)
    }

    private suspend fun reconcile(desiredSet: List<Pair<PlyId, Loom>>) {
        val desiredIds = desiredSet.map { it.first }.toSet()
        // Detach: live plies no longer desired.
        live.keys.toList().forEach { id -> if (id !in desiredIds) detachPly(id) }
        // Attach: desired plies not yet live — weave their loom now.
        for ((id, loom) in desiredSet) {
            if (id !in live) attachPly(id, loom.weave(rendezvous))
        }
    }

    private fun attachPly(id: PlyId, seam: Seam) {
        // Per-ply pumps run under a child Job so detach cancels exactly this ply.
        val job = SupervisorJob(scope.coroutineContext[Job])
        val plyScope = CoroutineScope(scope.coroutineContext + job)

        seam.state
            .onEach { s -> _plies.value = _plies.value + (id to s) }
            .launchIn(plyScope)

        // Re-announce on every Woven transition (cold start + recovery).
        seam.state
            .onEach { if (it is SeamState.Woven) seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId))) }
            .launchIn(plyScope)

        seam.incoming
            .onEach { swatch -> onPlyFrame(id, swatch) }
            .launchIn(plyScope)

        // Recompute peers on transport membership changes; re-announce to newcomers.
        seam.peers
            .onEach { newPeers ->
                recomputePeers()
                if (newPeers.size > 1 && seam.state.value is SeamState.Woven) {
                    seam.broadcast(PlyFrame.encode(PlyFrame.Announce(selfId)))
                }
            }
            .launchIn(plyScope)

        live[id] = PlyHandle(seam, job)
    }

    private suspend fun detachPly(id: PlyId) {
        val handle = live.remove(id) ?: return
        // Remove from the per-ply map BEFORE closing so the aggregate never
        // transiently latches Torn (terminal) on the last ply.
        _plies.value = _plies.value - id
        // Purge this ply's learned mappings so a re-attach starts clean.
        idMap.keys.removeAll { it.first == id }
        handle.job.cancel()
        handle.seam.close(CloseReason.Normal)
        recomputePeers()
    }

    private fun rollup(states: List<SeamState>): SeamState =
        when {
            states.isEmpty() -> SeamState.Weaving
            states.any { it is SeamState.Woven } -> SeamState.Woven
            states.all { it is SeamState.Torn } -> states.filterIsInstance<SeamState.Torn>().first()
            else -> SeamState.Weaving
        }

    private fun onPlyFrame(plyId: PlyId, swatch: Swatch) {
        when (val frame = PlyFrame.decode(swatch.payload)) {
            is PlyFrame.Announce -> {
                // Announce keys idMap by (plyId, transport sender) → composite id.
                val sender = swatch.sender ?: return
                idMap[plyId to sender] = frame.compositeId
                recomputePeers()
            }
            is PlyFrame.Data -> {
                // Data uses the in-frame originId — the transport sender may be a gateway.
                gate.accept(frame).forEach { payload ->
                    incomingChannel.trySend(Swatch(payload = payload, sender = frame.originId))
                }
            }
        }
    }

    private fun recomputePeers() {
        val reachable = buildSet {
            add(selfId)
            idMap.forEach { (key, compositeId) ->
                val (plyId, transportId) = key
                val seam = live[plyId]?.seam
                if (seam != null && transportId in seam.peers.value) add(compositeId)
            }
        }
        _peers.value = reachable
    }

    override suspend fun broadcast(payload: ByteArray) = withContext(dispatcher) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        live.values
            .filter { it.seam.state.value !is SeamState.Torn }
            .forEach { it.seam.broadcast(bytes) }
    }

    override suspend fun sendTo(peer: PeerId, payload: ByteArray) = withContext(dispatcher) {
        check(state.value !is SeamState.Torn) { "seam is Torn" }
        val bytes = PlyFrame.encode(PlyFrame.Data(selfId, outSeq++, payload))
        for ((plyId, handle) in live) {
            if (handle.seam.state.value is SeamState.Torn) continue
            val transportId = idMap.entries
                .firstOrNull { (k, v) -> k.first == plyId && v == peer }
                ?.key?.second
            if (transportId != null && transportId in handle.seam.peers.value) {
                handle.seam.sendTo(transportId, bytes)
                return@withContext
            }
        }
        throw PeerNotConnected(peer)
    }

    override suspend fun close(reason: CloseReason) {
        live.values.forEach { it.seam.close(reason) }
        live.clear()
        _state.value = SeamState.Torn(reason)
        incomingChannel.close()
        scope.cancel()
    }

    private companion object {
        fun mintCompositeId(initial: List<Pair<PlyId, Seam>>): PeerId =
            PeerId("composite-" + initial.joinToString("-") { it.second.selfId.value })
    }
}
```

- [ ] **Step 3: Update `CompositeLoom.weave` to pass the flow + rendezvous**

In `CompositeLoom.kt`, replace the `weave` body (currently lines 31-34) so it weaves the initial set and hands `CompositeSeam` the rendezvous and a (single-value, for now) desired-set flow:

```kotlin
    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val initial = plies.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(initial, rendezvous, MutableStateFlow(plies), dispatcher)
    }
```

Add the import at the top of `CompositeLoom.kt`:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```

(Leave the class's `plies: List<Pair<PlyId, Loom>>` constructor and the `init { require(...) }` block exactly as they are in this task.)

- [ ] **Step 4: Run the full composite + core suite — expect no behaviour change**

Run:
```bash
./gradlew :kuilt-core:jvmTest :kuilt-conformance:jvmTest
```
Expected: PASS — identical green set to Step 1. If `CompositeResilienceTest` or `CompositeMultiPlyTest` fail, the refactor changed behaviour; fix before continuing.

- [ ] **Step 5: Run the build to catch explicitApi / other-target breakage**

Run:
```bash
./gradlew :kuilt-core:build :kuilt-conformance:build
```
Expected: PASS (no `explicitApi` violations — `CompositeSeam` stays `internal`, `PlyHandle` is a private nested class).

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt
git commit -m "refactor(kuilt-core): drive CompositeSeam from a desired-set reconcile engine

PlyId-keyed idMap, per-ply cancellable handles, _state derived from _plies
(empty => Weaving), and dispatcher-confined broadcast/sendTo. No behaviour
change for the static fabric — all existing composite tests stay green.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Add the declarative `StateFlow` constructor to `CompositeLoom`

Promote `CompositeLoom` to a flow-driven primary constructor; the existing list constructor delegates to it via a never-changing `MutableStateFlow`. This exposes dynamic plies to consumers with no `Seam` surface change.

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt` (full rewrite)
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeLoomFlowCtorTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeLoomFlowCtorTest.kt`:

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeLoomFlowCtorTest {

    @Test
    fun flowConstructorWeavesTheInitialDesiredSet() = runTest {
        val desired = MutableStateFlow(listOf(PlyId("mem") to InMemoryLoom() as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        val seam = loom.host(Pattern("host"))

        assertIs<SeamState.Woven>(seam.state.value, "single in-memory ply is woven immediately")
        assertEquals(setOf(PlyId("mem")), seam.plies.value.keys)
    }

    @Test
    fun availabilityReflectsCurrentDesiredSet() {
        val desired = MutableStateFlow(listOf(PlyId("mem") to InMemoryLoom() as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        assertEquals(FabricAvailability.Available, loom.availability())
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile**

Run:
```bash
./gradlew :kuilt-core:compileTestKotlinJvm
```
Expected: FAIL — `CompositeLoom(MutableStateFlow, dispatcher)` constructor does not exist.

- [ ] **Step 3: Rewrite `CompositeLoom.kt` with the flow primary constructor**

Replace the entire file with:

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import kotlin.coroutines.CoroutineContext

/**
 * A [Loom] that weaves one logical session from several constituent [Loom]s
 * ("plies"). The union of plies covers the session's peer set; the list order is a
 * send-preference hint (most-preferred first).
 *
 * The ply set may change while the session is live: construct with a
 * [StateFlow] of the **desired** set and push a new list to attach or detach
 * plies. The list constructor is the degenerate case of a never-changing flow.
 * See `docs/superpowers/specs/2026-06-04-dynamic-ply-attach-detach-design.md`.
 *
 * @param plies The desired ply set; emit a new value to reconcile (attach/detach).
 * @param dispatcher Forwarded to each [CompositeSeam] as its internal dispatcher.
 *   Production default ([Dispatchers.Default.limitedParallelism(1)]) confines all
 *   mutable state access to one thread; tests inject [UnconfinedTestDispatcher].
 */
public class CompositeLoom(
    private val plies: StateFlow<List<Pair<PlyId, Loom>>>,
    private val dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) : Loom {

    /** Static convenience: a fixed ply set that never changes after `weave()`. */
    public constructor(
        plies: List<Pair<PlyId, Loom>>,
        dispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    ) : this(MutableStateFlow(plies), dispatcher)

    override suspend fun weave(rendezvous: Rendezvous): Seam {
        val current = plies.value
        require(current.isNotEmpty()) { "CompositeLoom desired set must be non-empty at weave()" }
        require(current.map { it.first }.toSet().size == current.size) { "duplicate PlyId" }
        val initial = current.map { (id, loom) -> id to loom.weave(rendezvous) }
        return CompositeSeam(initial, rendezvous, plies, dispatcher)
    }

    override fun availability(): FabricAvailability =
        if (plies.value.any { it.second.availability() == FabricAvailability.Available }) {
            FabricAvailability.Available
        } else {
            FabricAvailability.Unavailable("no ply available")
        }
}
```

- [ ] **Step 4: Run the new test + the full composite suite**

Run:
```bash
./gradlew :kuilt-core:jvmTest :kuilt-conformance:jvmTest
```
Expected: PASS — the two new flow-ctor tests pass and every existing composite test (static list ctor) still passes via delegation.

- [ ] **Step 5: Run the build (explicitApi check on the new public constructor)**

Run:
```bash
./gradlew :kuilt-core:build
```
Expected: PASS — both constructors carry explicit `public`.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeLoomFlowCtorTest.kt
git commit -m "feat(kuilt-core): declarative desired-set constructor on CompositeLoom

Construct CompositeLoom from a StateFlow<List<Pair<PlyId, Loom>>>; the list
constructor delegates via a never-changing MutableStateFlow. weave() validates
a non-empty, duplicate-free initial set.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Dynamic conformance — attach, detach, recover, re-attach

Add a conformance test that drives a changing desired set and asserts the dynamic semantics. Use `DelayedWovenLoom` plies (already in `:kuilt-conformance` commonMain) so per-ply `Woven` is controlled explicitly, and `UnconfinedTestDispatcher` so reconciliation settles synchronously.

**Files:**
- Create: `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeDynamicPlyTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeDynamicPlyTest.kt`:

```kotlin
package us.tractat.kuilt.conformance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PlyId
import us.tractat.kuilt.core.SeamState
import us.tractat.kuilt.core.composite.CompositeLoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Dynamic ply attach/detach behaviour: a changing desired-set [MutableStateFlow]
 * drives plies in and out of a live composite session.
 *
 * Shared in-memory plies (one [InMemoryLoom] per [PlyId]) are referenced by both
 * host and joiner desired sets, so attaching a ply on both sides bonds them.
 * [UnconfinedTestDispatcher] makes reconciliation and the Announce round-trip
 * settle synchronously for `.value` assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeDynamicPlyTest {

    private fun assertAll(vararg assertions: () -> Unit) = assertions.forEach { it() }

    @Test
    fun attachingAPlyMidSessionAddsItToPliesAndDedupsAcrossBoth() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))
        val joinDesired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))

        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Attach the overlay ply on both sides.
        hostDesired.update { it + (PlyId("overlay") to overlay as Loom) }
        joinDesired.update { it + (PlyId("overlay") to overlay as Loom) }
        host.peers.first { it.size == 2 } // still 2 — same peer over two plies, no double-count

        assertAll(
            { assertEquals(setOf(PlyId("relay"), PlyId("overlay")), host.plies.value.keys) },
            { assertEquals(2, host.peers.value.size, "multi-homed peer counted once") },
        )

        // A broadcast now rides both plies but must be delivered exactly once.
        host.broadcast(byteArrayOf(7))
        val received = joiner.incoming.first()
        assertEquals(7, received.payload.single())
    }

    @Test
    fun detachingAnOverlayKeepsAPeerReachableOnTheRelay() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val joinDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Detach the overlay on the host side only.
        hostDesired.update { it.filterNot { (id, _) -> id == PlyId("overlay") } }

        assertAll(
            { assertEquals(setOf(PlyId("relay")), host.plies.value.keys, "overlay gone from plies") },
            { assertIs<SeamState.Woven>(host.state.value, "aggregate stays Woven via relay") },
            { assertEquals(2, host.peers.value.size, "joiner still reachable on relay — no flap") },
        )
    }

    @Test
    fun detachingEveryPlyGoesWeavingAndReattachRecoversToWoven() = runTest {
        val relay = InMemoryLoom()
        val desired = MutableStateFlow(listOf(PlyId("relay") to relay as Loom))
        val seam = CompositeLoom(desired, UnconfinedTestDispatcher()).host(Pattern("s"))
        assertIs<SeamState.Woven>(seam.state.value)

        // Detach the only ply: aggregate must go Weaving (recoverable), not Torn.
        desired.update { emptyList() }
        assertAll(
            { assertTrue(seam.plies.value.isEmpty(), "no live plies") },
            { assertIs<SeamState.Weaving>(seam.state.value, "zero plies => Weaving, not Torn") },
        )

        // Re-attach a ply: aggregate recovers to Woven.
        desired.update { listOf(PlyId("relay2") to InMemoryLoom() as Loom) }
        assertAll(
            { assertEquals(setOf(PlyId("relay2")), seam.plies.value.keys) },
            { assertIs<SeamState.Woven>(seam.state.value, "re-attach recovers to Woven") },
        )
    }

    @Test
    fun reAttachingTheSamePlyIdStartsCleanAndStillDelivers() = runTest {
        val relay = InMemoryLoom()
        val overlay = InMemoryLoom()
        val hostDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val joinDesired = MutableStateFlow(
            listOf(PlyId("relay") to relay as Loom, PlyId("overlay") to overlay as Loom),
        )
        val host = CompositeLoom(hostDesired, UnconfinedTestDispatcher()).host(Pattern("s"))
        val joiner = CompositeLoom(joinDesired, UnconfinedTestDispatcher()).join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        // Detach then re-attach the same PlyId on the host side.
        hostDesired.update { it.filterNot { (id, _) -> id == PlyId("overlay") } }
        assertEquals(setOf(PlyId("relay")), host.plies.value.keys)
        hostDesired.update { it + (PlyId("overlay") to overlay as Loom) }

        // Mapping rebuilt via a fresh Announce; delivery still works exactly once.
        host.peers.first { it.size == 2 }
        host.broadcast(byteArrayOf(9))
        val received = joiner.incoming.first()
        assertEquals(9, received.payload.single())
        assertEquals(setOf(PlyId("relay"), PlyId("overlay")), host.plies.value.keys)
    }

    @Test
    fun singleElementFlowIsTheDegenerateStaticCase() = runTest {
        // A never-changing single-element flow behaves exactly like the static ctor.
        val mem = InMemoryLoom()
        val desired = MutableStateFlow(listOf(PlyId("mem") to mem as Loom))
        val loom = CompositeLoom(desired, UnconfinedTestDispatcher())

        val host = loom.host(Pattern("s"))
        val joiner = loom.join(InMemoryTag("s"))
        host.peers.first { it.size == 2 }

        host.broadcast(byteArrayOf(3))
        assertEquals(3, joiner.incoming.first().payload.single())
        assertEquals(setOf(PlyId("mem")), host.plies.value.keys)
    }
}
```

- [ ] **Step 2: Run the new test — confirm it passes against the Task 1/2 engine**

Run:
```bash
./gradlew :kuilt-conformance:jvmTest --tests "*CompositeDynamicPlyTest"
```
Expected: PASS. If `detachingEveryPlyGoesWeavingAndReattachRecoversToWoven` fails with a `Torn` state, the detach-ordering in `detachPly` (remove from `_plies` before close) regressed — fix `CompositeSeam.detachPly`. If `reAttachingTheSamePlyId...` fails, `idMap` purge-on-detach (`idMap.keys.removeAll { it.first == id }`) is missing or wrong.

- [ ] **Step 3: Run the full multiplatform test suite**

Run:
```bash
./gradlew allTests
```
Expected: PASS on every target (JVM, wasmJs, native sims as available on the machine). This guards against a Kotlin/Native coroutine-determinism flake — the dispatcher injection follows `docs/testing-coroutine-determinism.md`.

- [ ] **Step 4: Commit**

```bash
git add kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/CompositeDynamicPlyTest.kt
git commit -m "test(kuilt-conformance): dynamic ply attach/detach conformance

Attach mid-session (dedup across new ply), detach with survivor (no flap),
detach-to-empty => Weaving then re-attach => Woven, same-PlyId re-attach
(clean mapping), and single-element-flow as the degenerate static case.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Full build + all tests green**

Run:
```bash
./gradlew build
```
Expected: PASS — `explicitApi` clean, all module suites green.

- [ ] **Update the roadmap status line**

In `docs/ply-roadmap.md`, under "### 1. Dynamic ply attach/detach", the **Designed** line already points at the spec; after merge, change roadmap row 1's note or strike it as shipped (small follow-up commit, not blocking).

---

## Notes for the implementer

- **Keep `CompositeSeam` `internal`** — only `CompositeLoom` is public. `explicitApi()` is enforced; a stray `public` on an internal helper fails the build.
- **Dispatcher discipline:** never read/write `live`, `idMap`, `_plies`, or `outSeq` off `dispatcher`. `broadcast`/`sendTo` already hop via `withContext(dispatcher)`; any new mutating helper must too.
- **Detach order matters:** in `detachPly`, mutate `_plies` (and thus recompute `_state`) *before* closing the seam, or the last-ply detach transiently latches terminal `Torn`.
- **Don't widen scope:** gateway forwarding, primary-ply-per-peer send, and the discovery source that produces the desired-set flow are explicitly out of scope (roadmap items 2/3 and a consumer concern).
```
