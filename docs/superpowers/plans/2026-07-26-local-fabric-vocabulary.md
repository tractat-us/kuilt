# Local-Fabric Vocabulary Implementation Plan (kuilt #1712 Track A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the self-reachability fact the transport already computes as readable `Room` state, and finish the level-vs-edge convention the liveness vocabulary never adopted — so a peer can learn that **it** is the one offline, and a late subscriber can never miss a reconnect window.

**Architecture:** `Seam.capability`'s interface default flips from a lying `Available` to an honest `Unknown`; `SeamRoom` forwards its seam's availability as `Room.localFabric` and emits edges from the same single collector; `Liveness` becomes a sealed interface carrying a non-null window deadline set at the same instant as the state; and the host's async window hop is replaced by an inline emission that also gives the joiner a window it never had.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines (`StandardTestDispatcher`, virtual time), kotlin-test, detekt.

**Spec:** `docs/superpowers/specs/2026-07-26-local-fabric-vocabulary-design.md`
**Issues:** #1712 (primary), #1618 Q2, #1723 (D3), #1724 (D4)

## Global Constraints

- `source ~/.sdkman/bin/sdkman-init.sh && sdk env` — kuilt pins **JDK 21** via `.sdkmanrc`. Not 25 (detekt/detekt#8714, see #1708).
- `explicitApi()` is enforced; every new public declaration needs an explicit visibility modifier.
- **No `!!` in production code.** CI's `:module:detektJvmMain` type-resolution pass fails on `UnsafeCallOnNullableType`, and a local `detektAll` can false-green it (#1537).
- Use `detektAll`, never bare `detekt` — bare `detekt` is `NO-SOURCE` here and reports success without analysing anything.
- `runCatchingCancellable`, never bare `runCatching`, in any suspend context.
- Coroutine test discipline: `runTest(StandardTestDispatcher(), timeout = 5.seconds)`, bounded `advanceTimeBy`, **never `advanceUntilIdle()`**. No production dispatchers (`Dispatchers.*`, `GlobalScope`) in test sources. Seed every `Random`.
- Thread safety via explicit atomicfu locks/atomics or genuinely thread-safe types. **Never** `limitedParallelism(1)` confinement as a mutex substitute.
- Test methods take **no** `test` prefix; multi-assert tests use `assertAll()`.
- **`kuiltVersionLine` stays `0.7`.** Do not touch it. This ships on the patch cadence even though it is source-incompatible.
- Commits say `part of #1712` (and `part of #1723` / `part of #1724` where applicable). **Never `closes`** — hardware validation is owed first (Task 8).
- Final gate is the **full** `./gradlew build detektAll --rerun-tasks`. A module-scoped `jvmTest` is a false green: it does not compile the Android or Kotlin/Native variants, and does not run the `:examples` / `:kuilt-cluster` E2E tests.

---

## Two corrections to the spec (verified 2026-07-26 against `origin/main` @ `8717f823`)

Both were found while mapping the blast radius. Follow the plan, not the spec, where they differ.

**1. The conformance declaration uses the existing mechanism, not a new `protected open val`.**
The spec proposes `protected open val reportsLiveCapability: Boolean = false` on `SeamConformanceSuite`. The repo already has a mature declaration system: `SeamCapabilities` (a data class of 8 boolean flags with a `FLAGS` single-source-of-truth list), `CapabilityGaps` (stable gap URLs), a rendered `CapabilityMatrix`, and `SeamCapabilitiesReflectionTest` which fails loudly if a declared boolean property is missing from `FLAGS`. The flag becomes the **9th `SeamCapabilities` dimension** so it inherits matrix rendering and gap-declaration enforcement for free.

**2. `markPartitioned` does not currently compute a deadline on a joiner.**
The spec says the deadline "is already computed one line below." It is — but at `SeamRoom.kt:1512`, **inside** the `if (!wasPartitioned && _role.value == SessionRole.Host)` branch. On a joiner nothing is computed. Task 6 hoists the expression above the role gate.

**3. `FakeRoom.partition(peerId)` is shipped commonMain API with no deadline concept.**
`kuilt-session-test/src/commonMain/.../FakeRoom.kt:186` calls `updateLiveness(peerId, Liveness.Partitioned)`. Task 5 gives it a defaulted `expiresAt` parameter rather than a required one, so existing consumer call sites keep compiling.

---

## File Structure

| File | Responsibility |
|---|---|
| `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/StaticCapability.kt` | **Modify.** Replace the `Available` floor with an `Unknown` one. |
| `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt` | **Modify.** Point `capability`'s default at the new floor; update its KDoc. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamCapabilities.kt` | **Modify.** Add the `reportsLiveCapability` flag + its `FLAGS` entry. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/CapabilityGaps.kt` | **Modify.** Add the `LIVE_CAPABILITY` gap URL. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt` | **Modify.** Replace the "must report Available" assertion with a flag-driven one. |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt` | **Modify.** `Liveness.Partitioned` equality → `assertIs`. |
| every fabric's `*ConformanceTest.kt` | **Modify.** Declare `reportsLiveCapability` + gap URL. Enumerated in Task 2. |
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt` | **Modify.** Add `localFabric`. |
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/MembershipEvent.kt` | **Modify.** Add `LocalFabricLost` / `LocalFabricRestored`; add the tag to `Partitioned` / `HostLost`. |
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Member.kt` | **Modify.** `Liveness` enum → sealed interface. |
| `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` | **Modify.** The single fabric collector, the tag reads, the inline window, the joiner's host liveness. |
| `kuilt-session-test/src/commonMain/kotlin/us/tractat/kuilt/session/test/FakeRoom.kt` | **Modify.** `partition()` gains a defaulted deadline. |
| `kuilt-session/src/commonTest/.../LocalFabricTest.kt` | **Create.** Level/edge coherence, the `Unknown` rule, the precedence tag. |
| `kuilt-session/src/commonTest/.../WindowLevelTest.kt` | **Create.** D2/D4/D3 regressions and the authority-refinement guard. |
| `kuilt-session/src/commonSamples/.../AgentCookbookSamples.kt` | **Modify.** `== Liveness.Partitioned` → `is`. |
| `docs/agent-cookbook.md`, `docs/architecture.md` | **Modify.** Cookbook rows 21/318/323; the `liveCapability` gap anchor. |

---

## Task 1: The honesty floor (`:kuilt-core`)

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/StaticCapability.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt:70-76`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/SeamCapabilityFloorTest.kt` (create)

**Interfaces:**
- Consumes: `TransportCapability(roles: Set<TransportRole>, availability: FabricAvailability)`, `FabricAvailability.Unknown(reason: String)`.
- Produces: `internal val StaticUnknownCapability: StateFlow<TransportCapability>`, and the new default value of `Seam.capability`. Task 2 depends on this default being `Unknown`.

This task alone will make `SeamConformanceSuite.wovenSeamReportsAvailableCapability` fail for ~10 fabrics. **That is expected** — Task 2 fixes it, and the two tasks land in one PR. Do not skip ahead; the red state is the evidence the floor moved.

- [ ] **Step 1: Write the failing test**

Create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/SeamCapabilityFloorTest.kt`:

```kotlin
package us.tractat.kuilt.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A [Seam] that overrides nothing beyond the abstract members inherits the capability floor.
 *
 * The floor must be [FabricAvailability.Unknown], not [FabricAvailability.Available]: a fabric
 * with no live path observer cannot know whether its path is up, and a confident `Available`
 * there is an authoritative false negative once `Room.localFabric` surfaces it (#1712).
 */
class SeamCapabilityFloorTest {
    private class BareSeam : Seam {
        override val selfId: PeerId = PeerId("bare")
        override val peers: StateFlow<Set<PeerId>> = MutableStateFlow(emptySet())
        override val state: StateFlow<SeamState> = MutableStateFlow(SeamState.Woven)
        override val incoming: Flow<Swatch> = emptyFlow()
        override suspend fun broadcast(payload: ByteArray) = Unit
        override suspend fun sendTo(peer: PeerId, payload: ByteArray) = Unit
        override suspend fun close(reason: CloseReason) = Unit
    }

    @Test
    fun floorIsUnknownNotAvailable() {
        assertIs<FabricAvailability.Unknown>(BareSeam().capability.value.availability)
    }
}
```

Check `Seam.kt`'s `sendTo` signature before writing — if it takes named parameters in a different order, match it exactly.

- [ ] **Step 2: Run the test and verify it fails**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk env
./gradlew :kuilt-core:jvmTest --tests "*SeamCapabilityFloorTest*"
```

Expected: **FAIL** — `Available` is not `Unknown`.

- [ ] **Step 3: Flip the floor**

In `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/StaticCapability.kt`, replace the whole declaration:

```kotlin
/**
 * The floor [us.tractat.kuilt.core.Seam.capability] value: roleless and
 * [FabricAvailability.Unknown].
 *
 * A woven [us.tractat.kuilt.core.Seam] proves the fabric was *attemptable*; it does not prove the
 * device's path is up **right now**, and those are different questions once `Room.localFabric`
 * surfaces this to consumers (#1712). A fabric with no live path observer must say "I cannot tell"
 * rather than assert `Available` — an authoritative false negative is strictly worse than silence.
 * Fabrics with a real observer override [us.tractat.kuilt.core.Seam.capability] (see `NwSeam`, #1541).
 *
 * Exposed via [asStateFlow] — not cosmetic: without it a consumer could downcast the interface
 * default to [MutableStateFlow] and mutate the one global value shared by *every* [Seam].
 */
internal val StaticUnknownCapability: StateFlow<TransportCapability> =
    MutableStateFlow(
        TransportCapability(emptySet(), FabricAvailability.Unknown("no live path observer on this fabric")),
    ).asStateFlow()
```

In `Seam.kt`, change the default and its KDoc:

```kotlin
    /**
     * Live capability of the fabric carrying this session — its role(s) and
     * whether it is usable right now. Updates as radios, permissions, and network
     * paths change.
     *
     * Default: a roleless [FabricAvailability.Unknown] floor — a fabric with no live path
     * observer reports "cannot tell", never a confident `Available`. Fabrics with real OS
     * observers override this to make it reactive; a fabric that does so declares
     * `reportsLiveCapability = true` in its `SeamCapabilities`.
     */
    public val capability: StateFlow<TransportCapability>
        get() = us.tractat.kuilt.core.internal.StaticUnknownCapability
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
./gradlew :kuilt-core:jvmTest --tests "*SeamCapabilityFloorTest*"
```

Expected: **PASS**.

- [ ] **Step 5: Do NOT commit yet**

The conformance suite is now red across the repo. Task 2 is the other half of this commit.

---

## Task 2: Declare the gap through the existing capability mechanism

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamCapabilities.kt`
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/CapabilityGaps.kt`
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt` (the `runWovenSeamReportsAvailableCapability` block, ~line 444)
- Modify: `docs/architecture.md`
- Modify: each fabric's conformance subclass (enumerated in Step 4)

**Interfaces:**
- Consumes: `StaticUnknownCapability` (Task 1), `SeamCapabilities.FULL`, `SeamCapabilities.falseFlags()`, each subclass's `capabilityGaps()` map.
- Produces: `SeamCapabilities.reportsLiveCapability: Boolean` and `CapabilityGaps.LIVE_CAPABILITY: String`. Nothing later in this plan consumes them; Track B's per-lane slices flip the flag to `true` one fabric at a time.

**Why the obvious invariant is wrong.** Do not replace the assertion with *"`Woven` ⇒ not `Unavailable`"*. On the nw lane the device path goes `Unsatisfied` while the seam deliberately stays `Woven` through the #1478 grace period. `Woven` + `Unavailable` is precisely the self-loss window this whole change exists to surface.

- [ ] **Step 1: Add the flag**

In `SeamCapabilities.kt`, add a ninth property after `meshDelivery`:

```kotlin
    /**
     * [us.tractat.kuilt.core.Seam.capability] is driven by a **live** OS path observer, so its
     * [us.tractat.kuilt.core.FabricAvailability] tracks the device's real reachability.
     *
     * `false` means the fabric inherits the roleless [us.tractat.kuilt.core.FabricAvailability.Unknown]
     * floor and must not claim otherwise — `Room.localFabric` will read `Unknown` on it (#1712). Flip
     * to `true` only alongside a fabric-owned test proving the observer actually moves the value.
     */
    val reportsLiveCapability: Boolean,
```

and the matching `FLAGS` entry, last in the list (the order is the capability matrix's column order):

```kotlin
            "reportsLiveCapability" to SeamCapabilities::reportsLiveCapability,
```

`SeamCapabilitiesReflectionTest` asserts `FLAGS` equals the declared boolean properties, so omitting the entry fails loudly. That is the intended safety net — do not suppress it.

- [ ] **Step 2: Add the gap URL and its docs anchor**

In `CapabilityGaps.kt`:

```kotlin
    /**
     * A fabric with no live OS path observer, so [us.tractat.kuilt.core.Seam.capability] is a static
     * [us.tractat.kuilt.core.FabricAvailability.Unknown] floor rather than a reactive value. A *fabric*
     * gap, not a harness gap: the platform observer simply has not been wired yet. Track B
     * (#1542 multipeer, #1543 nearby, #1544 webrtc, #1545 composite, #1546 mux, #1725 websocket)
     * closes these one lane at a time.
     */
    public const val LIVE_CAPABILITY: String =
        "https://github.com/tractat-us/kuilt/blob/main/docs/architecture.md#livecapability--fabrics-without-a-path-observer"
```

Add the anchor to `docs/architecture.md` beside the existing `#securestransport…` and `#meshdelivery…` sections. Match their heading depth and house style — read those two first. Content: what the floor means, why `Unknown` beats `Available`, and that Track B flips lanes individually.

- [ ] **Step 3: Replace the suite assertion**

In `SeamConformanceSuite.kt`, replace the `runWovenSeamReportsAvailableCapability` body and rename it. Read the surrounding gate helpers first — the suite has an established idiom for skipping on a `false` flag; use it rather than a bare `if`.

```kotlin
    // ── (6b) live capability is honest about whether it is observed ─────────

    internal suspend fun runWovenSeamCapabilityIsHonest(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            val availability = host.capability.value.availability
            if (capabilities.reportsLiveCapability) {
                // A fabric claiming a live observer must not be sitting on the Unknown floor.
                // Whether it reads Available or Unavailable is the fabric's own business: a woven
                // seam whose device path has dropped is legitimately Unavailable (the #1478 grace
                // window), so this must NOT assert Available.
                assertTrue(
                    availability !is FabricAvailability.Unknown,
                    "a fabric declaring reportsLiveCapability=true must not report the Unknown floor, got $availability",
                )
            } else {
                assertTrue(
                    availability is FabricAvailability.Unknown,
                    "a fabric with no live path observer must report Unknown, not a fabricated verdict, got $availability",
                )
            }
        }

    @Test
    public fun wovenSeamCapabilityIsHonest(): TestResult =
        runTest { runWovenSeamCapabilityIsHonest(this) }
```

Update `SeamConformanceUngatedCoreTest` if it references the old method name.

- [ ] **Step 4: Declare the flag in every fabric**

`SeamCapabilities` is a data class with required positional parameters, so **every** construction site must supply the new flag. Sites, from `grep -rn "SeamCapabilities(" --include="*.kt" . | grep -v /build/`:

- `SeamCapabilities.kt` — the `FULL` constant. Set `reportsLiveCapability = true`: `FULL` means "meets every obligation", and fabrics flip individual flags down from it.
- `SeamCapabilitiesReflectionTest.kt:37` — the `allFalse` value. Add `reportsLiveCapability = false`.
- `SeamConformanceUngatedCoreTest.kt:87` — the `ALL_FALSE` value. Same.

Then each fabric's conformance subclass that uses `FULL` (or a `FULL.copy(...)`) and has **no** live observer must flip it down and declare the gap. Find them with:

```bash
grep -rln "SeamConformanceSuite\|MeshConformanceSuite" --include="*.kt" . | grep -v /build/ | grep -i "conformancetest"
```

Expected set: `TcpConformanceTest`, `MDNSConformanceTest`, `WebSocketConformanceTest`, `MultipeerConformanceTest`, `WebRTCConformanceTest`, `NearbyConformanceTest`, `GossipSeamConformanceTest`, `InMemoryLoomConformanceTest`, `PeerMeshConformanceTest`, `CompositeConformanceTest`, `HandshakingConformanceTest`, `IdentifiedConformanceTest`, and the nw suites (`NwConformanceTest`, `NwLoopbackConformanceTest`, `NwBridgeLoopbackConformanceTest`). Treat the grep output as authoritative over this list.

For each fabric **without** a live observer:

```kotlin
    override val capabilities: SeamCapabilities =
        SeamCapabilities.FULL.copy(reportsLiveCapability = false)

    override fun capabilityGaps(): Map<String, String> =
        super.capabilityGaps() + ("reportsLiveCapability" to CapabilityGaps.LIVE_CAPABILITY)
```

Match each file's existing idiom for both overrides — some already override one or both, in which case merge rather than replace.

**The nw suites are the exception**: `NwSeam` overrides `capability` and drives it from `NwApi.pathState`, so they keep `reportsLiveCapability = true` and declare no gap. Verify per-suite: a suite whose harness uses a `FakeNwApi` that never publishes a `pathState` will sit on the floor and fail the `true` branch. If so, seed the fake's `pathState` in that harness rather than declaring a gap — the fabric genuinely has the observer.

`CompositeConformanceTest` also needs judgement: `CompositeSeam` overrides `capability`, but it aggregates its constituent looms, so it reports `Unknown` when they all do. Declare `false` unless its harness gives it an observing constituent.

- [ ] **Step 5: Run the conformance suites**

```bash
./gradlew :kuilt-conformance:jvmTest :kuilt-core:jvmTest --rerun-tasks
```

Expected: **PASS**. Then the fabrics:

```bash
./gradlew :kuilt-websocket:jvmTest :kuilt-tcp:jvmTest :kuilt-gossip:jvmTest :kuilt-nw:jvmTest --rerun-tasks
```

Expected: **PASS**. A failure naming `reportsLiveCapability` in a gap-declaration test means Step 4 missed a fabric — add it, do not weaken the check.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core kuilt-conformance docs/architecture.md kuilt-websocket kuilt-tcp kuilt-multipeer kuilt-nearby kuilt-webrtc kuilt-mdns kuilt-gossip kuilt-nw
git commit -m "feat(core): Seam.capability floors at Unknown, declared per fabric (part of #1712)"
```

---

## Task 3: `Room.localFabric` and its edges

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/MembershipEvent.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/LocalFabricTest.kt` (create)

**Interfaces:**
- Consumes: `Seam.capability` (Task 1), `SeamRoom.emitEvent`, `SeamRoom.clock`, `SeamRoom.scope`.
- Produces: `Room.localFabric: StateFlow<FabricAvailability>`, `MembershipEvent.LocalFabricLost(at: Instant, reason: String)`, `MembershipEvent.LocalFabricRestored(at: Instant)`. Task 4 reads the same backing field for its tag.

Additive only — no existing signature changes, so nothing else in the repo breaks.

**Before writing the test**, read `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/FastReconnectRaceTest.kt` for the in-module fake-seam idiom; it already overrides `capability`. Reuse that fake if it is shared, otherwise mirror its shape. Do not build a new harness style.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/LocalFabricTest.kt`. Adapt the room construction to whatever `FastReconnectRaceTest` does; the assertions are the contract:

```kotlin
package us.tractat.kuilt.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocalFabricTest {

    @Test
    fun availableToUnavailableToAvailableDrivesLevelAndEdges() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)   // helper below
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Available)
            runCurrent()

            assertAll(
                { assertIs<FabricAvailability.Available>(room.localFabric.value) },
                { assertEquals(1, seen.filterIsInstance<MembershipEvent.LocalFabricLost>().size) },
                { assertEquals("radio off", seen.filterIsInstance<MembershipEvent.LocalFabricLost>().single().reason) },
                { assertEquals(1, seen.filterIsInstance<MembershipEvent.LocalFabricRestored>().size) },
            )
        }

    /** The level is written BEFORE the edge, so a consumer reacting to the edge cannot read a stale level. */
    @Test
    fun levelIsVisibleFromInsideTheEdgeCollector() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            var levelAtEdge: FabricAvailability? = null
            backgroundScope.launch {
                room.events.filterIsInstance<MembershipEvent.LocalFabricLost>()
                    .collect { levelAtEdge = room.localFabric.value }
            }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()

            assertIs<FabricAvailability.Unavailable>(levelAtEdge)
        }

    /** Unknown is level-only: entering it claims neither loss nor restoration. */
    @Test
    fun unknownEmitsNoEdge() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unknown("observer gone"))
            runCurrent()

            assertAll(
                { assertIs<FabricAvailability.Unknown>(room.localFabric.value) },
                { assertTrue(seen.none { it is MembershipEvent.LocalFabricLost }) },
                { assertTrue(seen.none { it is MembershipEvent.LocalFabricRestored }) },
            )
        }

    /**
     * Recovery THROUGH Unknown still restores. Tracking only the previous value would swallow
     * this: at the Available step the previous value is Unknown, not Unavailable.
     */
    @Test
    fun unavailableThroughUnknownToAvailableStillRestores() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Unknown("observer gone"))
            runCurrent()
            capability.value = TransportCapability(emptySet(), FabricAvailability.Available)
            runCurrent()

            assertEquals(1, seen.filterIsInstance<MembershipEvent.LocalFabricRestored>().size)
        }
}
```

Write `roomOverCapability(capability)` as a private helper in this file, building a `SeamRoom` over a fake seam whose `capability` is the passed `MutableStateFlow`, on `backgroundScope` with a fixed injected clock. Model it on `FastReconnectRaceTest`'s setup.

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :kuilt-session:jvmTest --tests "*LocalFabricTest*"
```

Expected: **FAIL to compile** — `localFabric`, `LocalFabricLost` and `LocalFabricRestored` do not exist.

- [ ] **Step 3: Add the events**

In `MembershipEvent.kt`, inside the sealed interface:

```kotlin
    /**
     * **This peer's own end** of the fabric carrying this room can no longer carry frames.
     *
     * Self-attributed and **session-scoped** — it says nothing about the device as a whole. A peer
     * in two rooms over two fabrics gets this independently per room, and neither speaks for the
     * other; a room over a bonded `CompositeSeam` emits it only when every woven ply is down.
     *
     * Emitted only on a transition **into** [us.tractat.kuilt.core.FabricAvailability.Unavailable].
     * A move into [us.tractat.kuilt.core.FabricAvailability.Unknown] emits nothing — "we stopped
     * being able to tell" is not a loss. Read [Room.localFabric] for the authoritative level; this
     * is the notification. [reason] is the transport's own words.
     */
    public data class LocalFabricLost(val at: Instant, val reason: String) : MembershipEvent

    /**
     * This peer's own end of the room's fabric can carry frames again.
     *
     * Emitted on a transition into [us.tractat.kuilt.core.FabricAvailability.Available] when the
     * last decided state was [us.tractat.kuilt.core.FabricAvailability.Unavailable] — including
     * when the path passed through [us.tractat.kuilt.core.FabricAvailability.Unknown] on the way
     * back. Never emitted for a first-ever `Available`: nothing was lost.
     */
    public data class LocalFabricRestored(val at: Instant) : MembershipEvent
```

- [ ] **Step 4: Add the `Room` member**

In `Room.kt`, after `attestedPrincipals`, add the declaration and KDoc exactly as given in the spec's "The public surface" section. Add the `FabricAvailability` import.

- [ ] **Step 5: Implement the single collector**

In `SeamRoom.kt`, beside the other backing fields (near `_events`, ~486):

```kotlin
    private val _localFabric = MutableStateFlow(seam.capability.value.availability)
    override val localFabric: StateFlow<FabricAvailability> = _localFabric.asStateFlow()
```

Add the loop as a private suspend fun near the other loops:

```kotlin
    /**
     * Fold [Seam.capability] into [localFabric] and its edges. ONE collector writes both, so the
     * level and the events cannot diverge — and the level is written **first**, so a consumer
     * reacting to an edge always reads the matching level (#1712).
     *
     * [Seam.capability] is a StateFlow, not `incoming`, so collecting it here does not contend
     * with the ADR-034 single-collection contract.
     */
    private suspend fun localFabricLoop() {
        var lastDecided: FabricAvailability? = null
        seam.capability.collect { cap ->
            val next = cap.availability
            if (next == _localFabric.value) return@collect
            _localFabric.value = next
            when (next) {
                is FabricAvailability.Unavailable ->
                    if (lastDecided !is FabricAvailability.Unavailable) {
                        emitEvent(MembershipEvent.LocalFabricLost(clock(), next.reason))
                        lastDecided = next
                    }
                is FabricAvailability.Available -> {
                    if (lastDecided is FabricAvailability.Unavailable) {
                        emitEvent(MembershipEvent.LocalFabricRestored(clock()))
                    }
                    lastDecided = next
                }
                // Level only. lastDecided deliberately unchanged, so a recovery THROUGH Unknown
                // still restores.
                is FabricAvailability.Unknown -> Unit
            }
        }
    }
```

Launch it alongside the room's other jobs — find where `jobs += scope.launch { runReconnectEventLoop(...) }` lives (~683) and add `jobs += scope.launch { localFabricLoop() }` in the same block, **outside** any host-only gate. It must run for both roles.

- [ ] **Step 6: Run the test and verify it passes**

```bash
./gradlew :kuilt-session:jvmTest --tests "*LocalFabricTest*"
```

Expected: **PASS**, all four.

- [ ] **Step 7: Commit**

```bash
git add kuilt-session
git commit -m "feat(session): Room.localFabric — self-attributed reachability as a level (part of #1712)"
```

---

## Task 4: The precedence tag

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/MembershipEvent.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (lines 651, 1507, 1587, 1660)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/LocalFabricTest.kt` (extend)

**Interfaces:**
- Consumes: `_localFabric` (Task 3).
- Produces: `MembershipEvent.Partitioned.localFabric: FabricAvailability`, `MembershipEvent.HostLost.localFabric: FabricAvailability`.

Adding a field to these two data classes breaks **positional** construction. Named-argument construction keeps compiling. Expect breaks in test sources that build events positionally; fix by adding the argument, not by reordering.

- [ ] **Step 1: Write the failing test**

Append to `LocalFabricTest.kt`:

```kotlin
    /**
     * #1712's core ask: precedence readable "from the stream rather than by racing timestamps".
     * While our own fabric is down, a peer going quiet is NOT evidence about that peer.
     */
    @Test
    fun peerPartitionCarriesTheLocalFabricStateAtEmission() =
        runTest(StandardTestDispatcher(), timeout = 5.seconds) {
            val capability = MutableStateFlow(
                TransportCapability(emptySet(), FabricAvailability.Available),
            )
            val room = roomOverCapability(capability)
            val seen = mutableListOf<MembershipEvent>()
            backgroundScope.launch { room.events.collect { seen += it } }
            runCurrent()

            capability.value = TransportCapability(emptySet(), FabricAvailability.Unavailable("radio off"))
            runCurrent()
            partitionAPeer(room)     // helper: drive a peer unresponsive; see WindowLevelTest
            runCurrent()

            val partitioned = seen.filterIsInstance<MembershipEvent.Partitioned>().single()
            assertIs<FabricAvailability.Unavailable>(partitioned.localFabric)
        }
```

Write `partitionAPeer` against whatever mechanism the module's existing partition tests use — `PartitionRoleTest` and `JoinerHostTimeoutRecoveryTest` are the references. Do not invent a new injection path.

- [ ] **Step 2: Run and verify it fails**

```bash
./gradlew :kuilt-session:jvmTest --tests "*LocalFabricTest*"
```

Expected: **FAIL to compile** — `Partitioned` has no `localFabric`.

- [ ] **Step 3: Add the fields**

In `MembershipEvent.kt`, add to `Partitioned` and `HostLost` as the **last** parameter of each:

```kotlin
    /**
     * This peer's own [Room.localFabric] at the instant this event was emitted.
     *
     * **Precedence.** When this is [us.tractat.kuilt.core.FabricAvailability.Unavailable], this
     * event is **not evidence about [peerId]** — our own end of the fabric was down, so their
     * silence says nothing about them. Read it off the event rather than correlating two streams
     * by timestamp (#1712). [us.tractat.kuilt.core.FabricAvailability.Unknown] means the fabric
     * has no path observer, so precedence cannot be determined — treat it as "no information",
     * not as "we were fine".
     */
    val localFabric: FabricAvailability,
```

Use the same KDoc on `HostLost.localFabric`, substituting "about the host" for "about [peerId]". `HostLost` is the highest-value site: a joiner whose own radio died currently renders "the host is gone."

- [ ] **Step 4: Populate it at all four emission sites**

`SeamRoom.kt` lines 651 (`onReconnectStarted`), 1507 (`markPartitioned`), 1587 (`handlePaused`), 1660 (`markHostLost`). Each becomes `localFabric = _localFabric.value`.

Read it **outside** the room's `lock` — the lock guards `admittedById`, and every one of these sites already emits outside it. `_localFabric` is a `MutableStateFlow`, so the read is thread-safe on its own.

- [ ] **Step 5: Fix the fallout and run**

```bash
./gradlew :kuilt-session:jvmTest --rerun-tasks
```

Expected: **PASS**. Compile errors in other tests mean positional construction — add the named argument.

- [ ] **Step 6: Commit**

```bash
git add kuilt-session
git commit -m "feat(session): tag Partitioned/HostLost with the local-fabric state at emission (part of #1712)"
```

---

## Task 5: `Liveness` becomes a sealed interface carrying the deadline

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Member.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (1504, 1505, 1524, 1582, 1583, 1604, 1643)
- Modify: `kuilt-session-test/src/commonMain/kotlin/us/tractat/kuilt/session/test/FakeRoom.kt:182-196`
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt:284`
- Modify: `kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt:82`
- Modify: test sources enumerated in Step 3

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Liveness.Connected` (data object), `Liveness.Partitioned(since: Instant, windowExpiresAt: Instant)`. Tasks 6 and 7 construct `Liveness.Partitioned` at new sites.

**This task is atomic.** It cannot be split across commits — the type change and every call site must land together or the module does not compile.

**Compile-order check, already done.** Every existing site that constructs `Liveness.Partitioned` can supply a non-null deadline the moment this lands:

| site | deadline source |
|---|---|
| `SeamRoom.kt:1505` `markPartitioned` | `at + heartbeatConfig.reconnectWindow` — but see Task 6: the expression currently lives inside the host-only branch and must be hoisted **in this task** so the call site compiles |
| `SeamRoom.kt:1583` `handlePaused` | `Instant.fromEpochMilliseconds(paused.expiresAt)` — host-authoritative, already in hand |
| `FakeRoom.kt:186` `partition()` | synthesized; see Step 2 |

Sites setting `Liveness.Connected` (785, 1229, 1525, 1605, 1044, 1203) need no change — `Connected` stays a data object, so `assertEquals(Liveness.Connected, …)` also keeps compiling. Only `Partitioned` comparisons break.

- [ ] **Step 1: Change the type**

In `Member.kt`, replace the enum:

```kotlin
/**
 * The liveness state of an admitted member.
 *
 * A **level**, and the authoritative one: [Room.roster] is a StateFlow, so a late subscriber reads
 * the current value and can never miss a partition the way an events collector can (#1618 Q2).
 * Prefer this over replaying [MembershipEvent]s — in particular, do not key a UI on
 * [MembershipEvent.Recovered] vs [MembershipEvent.Resumed], which differ by role and by recovery
 * path; the level clears on either.
 */
public sealed interface Liveness {
    /** The member's transport link is active. */
    public data object Connected : Liveness

    /**
     * The member's transport link has dropped and its seat is held open until [windowExpiresAt].
     *
     * [windowExpiresAt] is **non-null by construction** — it is written at the same site and
     * instant that sets this state, so a partitioned member whose window is unknown is not a state
     * this type can represent. That is deliberate: it was previously reachable only by replaying a
     * [MembershipEvent.WindowOpened] that some paths never emitted (#1723, #1724).
     *
     * On a member watching *another* member, this deadline may start as a local estimate and be
     * refined by the host's authoritative `Paused`. On a joiner watching its *host*, the joiner's
     * own reconnect budget is the authority and no refinement occurs.
     */
    public data class Partitioned(
        val since: Instant,
        val windowExpiresAt: Instant,
    ) : Liveness
}
```

Add the `kotlin.time.Instant` import.

- [ ] **Step 2: Update `SeamRoom` and `FakeRoom`**

In `SeamRoom.kt`, the comparisons at 1504, 1524, 1582, 1604 and 1643 become `is` / `!is`:

```kotlin
            val wasPartitioned = current.liveness is Liveness.Partitioned
```

At 1505, hoist the deadline above the role gate and use it (Task 6 will reuse the same hoisted value):

```kotlin
    private fun markPartitioned(peerId: PeerId, at: Instant, reason: ReconnectReason) {
        // Hoisted above the role gate: previously computed only in the host-only propagatePaused
        // branch, so a joiner had no deadline at all (#1724). The level needs it on both roles.
        val expiresAt = at + heartbeatConfig.reconnectWindow
        val (wasPartitioned, updated) = lock.withLock {
            val current = admittedById[peerId] ?: return
            val wasPartitioned = current.liveness is Liveness.Partitioned
            wasPartitioned to (
                updateMemberLiveness(peerId, Liveness.Partitioned(since = at, windowExpiresAt = expiresAt))
                    ?: return
                )
        }
        // …unchanged below; propagatePaused now takes expiresAt.inWholeMilliseconds…
    }
```

Confirm `heartbeatConfig.reconnectWindow` is a `Duration` so `at + reconnectWindow` yields an `Instant`; if the existing code went via epoch-millis, keep that arithmetic and convert once.

At 1583, `handlePaused` uses the host's value:

```kotlin
            updateMemberLiveness(
                subject,
                Liveness.Partitioned(
                    since = clock(),
                    windowExpiresAt = Instant.fromEpochMilliseconds(paused.expiresAt),
                ),
            ) ?: return
```

In `FakeRoom.kt`, `partition()` gains a **defaulted** parameter so existing consumer call sites keep compiling:

```kotlin
    /**
     * Flip the named member's [Liveness] to [Liveness.Partitioned] and emit the matching events.
     *
     * [windowExpiresAt] defaults to one minute past this fake's clock — an arbitrary but non-null
     * stand-in, since [Liveness.Partitioned] carries a real deadline in production. Pass an explicit
     * value when a test asserts on the countdown.
     */
    public fun partition(
        peerId: PeerId,
        windowExpiresAt: Instant = clock() + 1.minutes,
    ) {
        updateLiveness(peerId, Liveness.Partitioned(since = clock(), windowExpiresAt = windowExpiresAt))
        // …unchanged…
    }
```

Read `FakeRoom`'s existing clock/time handling first — if it has no clock, add the parameter as required-with-no-default only if there are no external call sites, otherwise thread a clock in.

- [ ] **Step 3: Update every broken assertion**

`Liveness.Connected` comparisons are unaffected. Only these `Partitioned` sites break:

- `kuilt-conformance/.../RoomConformanceSuite.kt:284` — **commonMain, a shipped TCK.** `assertEquals(Liveness.Partitioned, …)` → `assertIs<Liveness.Partitioned>(hostRoom.roster.value.first().liveness)`.
- `kuilt-session/src/commonTest/.../StarTopologyPresenceFanoutTest.kt:168`
- `kuilt-session/src/commonTest/.../PartitionRoleTest.kt:262, 337`
- `kuilt-nw/src/commonTest/.../NwMeshRoomPartitionTest.kt:142`
- `kuilt-session-test/src/commonTest/.../FakeRoomTest.kt:138, 145`
- `kuilt-session/src/commonSamples/.../AgentCookbookSamples.kt:82` — **compiled as part of commonTest.** The comment and the filter both change:

```kotlin
    // room.roster.value.filter { it.liveness is Liveness.Partitioned } is the same fact, pull-style —
    // and each Partitioned carries windowExpiresAt, so the countdown needs no event replay.
```

Re-run the grep to catch anything this list missed:

```bash
grep -rn "Liveness.Partitioned" --include="*.kt" . | grep -v /build/
```

- [ ] **Step 4: Full build**

```bash
./gradlew build --rerun-tasks
```

Expected: **PASS**. A module-scoped run is a false green here — the change crosses `:kuilt-session`, `:kuilt-session-test`, `:kuilt-conformance` and `:kuilt-nw`, and `commonSamples` compiles only under `commonTest`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(session): Liveness carries its reconnect deadline as a level (part of #1712, part of #1723)"
```

---

## Task 6: The window is emitted inline, for both roles (#1724 + #1618 Drop B)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`markPartitioned`, `runReconnectEventLoop` ~748, `handlePaused` ~1578)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/WindowLevelTest.kt` (create)

**Interfaces:**
- Consumes: `Liveness.Partitioned(since, windowExpiresAt)` (Task 5), the `expiresAt` hoisted in Task 5.
- Produces: no new API. Changes *which* code path emits `MembershipEvent.WindowOpened`.

This is a bug fix — strict TDD, and Step 6 reverts to confirm the test bites.

**Why one change fixes two defects.** `markPartitioned` is role-agnostic. The host currently gets its window through `reconnectController.onPeerUnresponsive` → `scope.launch { openWindow(…) }` → `_events.emit(…)` on a **`replay = 0`** `MutableSharedFlow` collected only by the host-only `runReconnectEventLoop` — and a `SharedFlow` emit with no subscriber and no replay **discards the value** (#1618 Drop B). The joiner gets nothing at all, because `reconnectController` is `null` on a joiner (`593`) so the call is a no-op against null (#1724). Emitting inline in `markPartitioned` serves both.

**Numbers agree by construction:** the controller is built with `reconnectWindowMs = heartbeatConfig.reconnectWindow.inWholeMilliseconds` (`603`) and receives the same `at`. Same inputs, same formula — so removing the controller's emission cannot change the deadline anyone sees.

- [ ] **Step 1: Write the failing tests**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/WindowLevelTest.kt`. Build the rooms the way `PartitionRoleTest` does.

```kotlin
    /** #1618 Drop B: the host's own events must carry the window, with no async hop to lose it. */
    @Test
    fun hostEmitsWindowOpenedForAnUnresponsiveJoiner() { /* … assert WindowOpened on the HOST's events … */ }

    /**
     * #1618 Q2, the structural claim: a collector that subscribes only AFTER the partition
     * still reads the deadline. This is what makes the window unlosable rather than
     * merely more-reliably-delivered.
     */
    @Test
    fun aLateSubscriberStillReadsTheDeadlineOffRoster() { /* … partition, THEN read roster … */ }

    /** #1724: a joiner whose host goes silent by Timeout gets a window, not a bare Partitioned. */
    @Test
    fun joinerHostTimeoutOpensAWindowWithADeadline() { /* … drive Timeout, not TransportClosed … */ }

    /**
     * A joiner's local estimate for ANOTHER joiner must be corrected by the host's
     * authoritative Paused — without emitting a duplicate Partitioned.
     */
    @Test
    fun hostPausedRefinesALocallyEstimatedDeadline() { /* … */ }
```

Fill each body against the module's existing idiom. For `joinerHostTimeoutOpensAWindowWithADeadline`, drive `PartitionEvent.Reason.Timeout` — **not** `TransportClosed`, which routes to `attemptReconnect` instead of `markPartitioned` and would not exercise the defect. `JoinerHostTimeoutRecoveryTest` is the reference for that injection.

- [ ] **Step 2: Run and verify they fail**

```bash
./gradlew :kuilt-session:jvmTest --tests "*WindowLevelTest*"
```

Expected: **FAIL** — no `WindowOpened` on the joiner lane; the late-subscriber and refinement cases red too.

- [ ] **Step 3: Emit inline**

In `markPartitioned`, after the existing `Partitioned` emission:

```kotlin
        if (!wasPartitioned) {
            emitEvent(MembershipEvent.Partitioned(updated.id, at, reason, _localFabric.value))
            // Inline, from the SAME expiresAt that sets the level and feeds propagatePaused.
            // Previously the host's window crossed the controller's replay-0 SharedFlow (lost when
            // runReconnectEventLoop had not yet subscribed, #1618 Drop B) and the joiner got none at
            // all (reconnectController is null on a joiner, #1724). markPartitioned is role-agnostic,
            // so one emission serves both.
            emitEvent(MembershipEvent.WindowOpened(updated.id, expiresAt))
        }
```

- [ ] **Step 4: Drop the controller's mapping**

In `runReconnectEventLoop`, delete the `is JoinerReconnectEvent.WindowOpened ->` branch and its `MembershipEvent.WindowOpened` construction. Keep `Resumed` and `WindowExpired` untouched. Update the KDoc bullet list above the function to say the window is now emitted inline by `markPartitioned` and why.

If the `when` becomes non-exhaustive, add `is JoinerReconnectEvent.WindowOpened -> Unit` with a comment rather than deleting the branch — the controller still opens real windows; only its *event* is now redundant.

- [ ] **Step 5: Let `handlePaused` refine the deadline**

`handlePaused` currently returns early when the member is already `Partitioned`, so a host's authoritative `expiresAt` can never correct a local estimate. Split idempotence-of-events from freshness-of-level:

```kotlin
    private fun handlePaused(sender: PeerId, paused: AdmitMessage.Paused) {
        val subject = PeerId(paused.peerId)
        val hostDeadline = Instant.fromEpochMilliseconds(paused.expiresAt)
        val alreadyPartitioned: Boolean
        val updated = lock.withLock {
            val host = hostPeerId
            if (host == null || sender != host || subject == host || subject == selfId) return
            val current = admittedById[subject] ?: return
            alreadyPartitioned = current.liveness is Liveness.Partitioned
            // Refine the deadline even when already partitioned: our local estimate was a guess and
            // the host is authoritative. Returning early here would pin the guess forever (#1724).
            val since = (current.liveness as? Liveness.Partitioned)?.since ?: clock()
            updateMemberLiveness(
                subject,
                Liveness.Partitioned(since = since, windowExpiresAt = hostDeadline),
            ) ?: return
        }
        // Events stay idempotent: a peer that already detected the drop locally must not emit twice.
        if (alreadyPartitioned) return
        emitEvent(MembershipEvent.Partitioned(updated.id, clock(), ReconnectReason.TransportClosed, _localFabric.value))
        emitEvent(MembershipEvent.WindowOpened(updated.id, hostDeadline))
    }
```

Kotlin requires `alreadyPartitioned` to be definitely assigned — restructure to return the pair from the `withLock` block if the compiler objects, matching `markPartitioned`'s shape.

- [ ] **Step 6: Run, then revert-to-confirm**

```bash
./gradlew :kuilt-session:jvmTest --tests "*WindowLevelTest*" --tests "*PartitionRoleTest*" --tests "*StarTopologyPresenceFanoutTest*" --tests "*JoinerHostTimeoutRecoveryTest*"
```

Expected: **PASS**. Then `git stash` the `SeamRoom.kt` change, re-run, confirm **FAIL**, and `git stash pop`. A test that passes both ways is not testing the fix.

- [ ] **Step 7: Commit**

```bash
git add kuilt-session
git commit -m "fix(session): emit WindowOpened inline from markPartitioned, so both roles get a deadline (part of #1724, part of #1618)"
```

---

## Task 7: A joiner sets its host's liveness (#1723)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (`onReconnectStarted`, ~650)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/WindowLevelTest.kt` (extend)

**Interfaces:**
- Consumes: `Liveness.Partitioned(since, windowExpiresAt)` (Task 5).
- Produces: no new API.

Bug fix — strict TDD, revert-to-confirm.

`onReconnectStarted(hostId, at, windowDeadline)` emits two events and mutates no roster state, so while a joiner is partitioned from its host, `events` has said `Partitioned(hostId)` while `roster` still reports that host `Connected`.

- [ ] **Step 1: Write the failing test**

Append to `WindowLevelTest.kt`:

```kotlin
    /**
     * #1723: roster and events must not contradict each other. A joiner partitioned from its host
     * must show that host Partitioned in the roster, with the same deadline the event carried.
     */
    @Test
    fun joinerShowsItsHostPartitionedInTheRoster() { /* tear the host link; assert roster, not events */ }
```

Assert on `roster`, and assert the `windowExpiresAt` equals the `WindowOpened.expiresAt` the joiner emitted — that equality is the point.

- [ ] **Step 2: Run and verify it fails**

```bash
./gradlew :kuilt-session:jvmTest --tests "*WindowLevelTest.joinerShowsItsHostPartitionedInTheRoster*"
```

Expected: **FAIL** — the roster reports `Connected`.

- [ ] **Step 3: Set the level**

In the `onReconnectStarted` override (~650):

```kotlin
                    override fun onReconnectStarted(hostId: PeerId, at: Instant, windowDeadline: Instant) {
                        // The level, not just the edge (#1723): without this the joiner's roster
                        // reports its host Connected while these events say Partitioned, and a late
                        // subscriber has no way to recover the state at all.
                        lock.withLock {
                            updateMemberLiveness(
                                hostId,
                                Liveness.Partitioned(since = at, windowExpiresAt = windowDeadline),
                            )
                        }
                        emitEvent(MembershipEvent.Partitioned(hostId, at, ReconnectReason.TransportClosed, _localFabric.value))
                        emitEvent(MembershipEvent.WindowOpened(hostId, windowDeadline))
                    }
```

`hostId` is in `admittedById` on a joiner — `restoreHostDetector` already looks it up there. Check whether this callback is invoked from inside an existing critical section; the room's lock is reentrant, but confirm rather than assume.

The clearing side already works: `handleResumeAck` calls `updateMemberLiveness(sender, Liveness.Connected)` at `1229`. **But see the cross-track note below.**

- [ ] **Step 4: Run and revert-to-confirm**

```bash
./gradlew :kuilt-session:jvmTest --rerun-tasks
```

Expected: **PASS**. Then stash the change, confirm the new test fails, restore.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session
git commit -m "fix(session): a joiner marks its host Partitioned in the roster, not only in events (part of #1723)"
```

> **Cross-track constraint — do not lose this.** #1637's sub-timeout-blip fix completes an episode as a *no-op resume* in which **no `ResumeAck` ever arrives**, so `handleResumeAck` never runs and this liveness is never cleared: the host would stay pinned `Partitioned` in the joiner's roster forever. #1637's plan has been amended to add a `JoinerResumeHost.onNoOpResume` callback that clears it. If #1637 lands after this task, verify that amendment is still in its plan (`docs/superpowers/plans/2026-07-26-1637-sub-timeout-blip.md`, branch `plan/1637-sub-timeout-blip`).

---

## Task 8: Documentation and final verification

**Files:**
- Modify: `docs/agent-cookbook.md` (rows 21, 318, 323)
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt` (KDoc cross-references)
- Modify: `Writerside/` topics only if one cites a changed symbol

**Interfaces:** none — documentation only.

- [ ] **Step 1: Update the cookbook**

Row 21's "Primitive" column becomes `Room.roster` + `Member.liveness` (level-first), with `Room.events` as the notification. Line 318's paragraph gains: the roster entry reads `Liveness.Partitioned(since, windowExpiresAt)` for as long as the seat is held, so the countdown needs no event replay; and do not key a UI on `Recovered` vs `Resumed`, which differ by role and recovery path — the level clears on either.

Add a row for the new self-attributed surface:

| a "you are offline" / "your connection dropped" indicator, distinguishing *your* outage from *their* outage | `Room.localFabric` + `MembershipEvent.LocalFabricLost` | [Liveness & presence](#liveness--presence) |

In the section body, state plainly: session-scoped not device-scoped; `Unknown` means kuilt cannot tell on this fabric and is the current answer on every lane but nw; and `Partitioned` / `HostLost` carry `localFabric` so precedence needs no timestamp correlation.

Re-read the whole section top-to-bottom afterwards and confirm it still flows accessible → technical. That property is easy to lose in an edit and is a documented requirement.

- [ ] **Step 2: Check the samples still compile**

```bash
./gradlew :kuilt-session:compileTestKotlinJvm --rerun-tasks
```

`commonSamples` is wired into `commonTest`, so a stale `@sample` breaks the build.

- [ ] **Step 3: Full verification, cache disabled**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk env
./gradlew build detektAll --rerun-tasks
```

Expected: **BUILD SUCCESSFUL**. Confirm the test-compile tasks report `EXECUTED`, not `FROM-CACHE`; add `--no-build-cache` if any show cached. A `:kuilt-session:jvmTest` run is **not** acceptable evidence — it skips the Android and Kotlin/Native variants and the `:examples` / `:kuilt-cluster` E2E tests that a session-behaviour change can break.

Then the type-resolution detekt pass that a repo-wide `detektAll` can false-green (#1537):

```bash
./gradlew :kuilt-session:detektJvmMain :kuilt-core:detektJvmMain --rerun-tasks
```

- [ ] **Step 4: Commit and open the PRs**

Each task above is its own PR. Bodies lead with the defect and its evidence; use `part of #1712` / `part of #1723` / `part of #1724`. **Never `closes`** — see Step 5.

- [ ] **Step 5: Hardware validation before closing anything**

`nw` is the only lane reporting a real `localFabric` value on day one. Validate on two iPhones in one session: airplane-mode the joiner **~8 s** (short) and then **~90 s** (long). Expect the surviving device to distinguish "you are offline" from "they are offline", and the dropped device to render its own outage rather than blaming its peer.

Close #1712, #1723 and #1724 **by hand** after that reproduces. A fake-injected `capability` test proves the consumer's *reaction*, never the real transport's *emission*.

---

## Self-Review

**Spec coverage.** D1 → Tasks 3 and 4. D2 → Task 6. D3 → Task 7. D4 → Task 6. Honesty floor → Task 1. Conformance enforcement → Task 2. `Liveness` level → Task 5. Docs → Task 8. Hardware gate → Task 8 Step 5. Version line → Global Constraints (unchanged, per decision). Scope boundary (Track B/C) → not implemented here, by design.

**Deviations from the spec, deliberate and flagged inline:** the conformance declaration uses `SeamCapabilities` rather than a new `protected open val`; the deadline expression is hoisted out of the host-only branch in Task 5; `FakeRoom.partition()` gains a defaulted deadline; and the spec's seven PRs became eight tasks because Task 1 and Task 2 must ship as one commit while `Liveness` needs a task of its own.

**Known softness.** Tasks 6 and 7 give test *names*, contracts and the exact injection mechanism to use, but not full bodies — the harness idiom must be copied from `PartitionRoleTest` / `JoinerHostTimeoutRecoveryTest`, and inventing it from scratch here would likely diverge from the module's conventions. Task 3's `roomOverCapability` helper is likewise specified by contract. Implementers: read those files first.
