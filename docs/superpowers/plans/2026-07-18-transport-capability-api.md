# Transport-Capability API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every `Loom`/`Seam` a unified capability report — what transport role(s) it plays and whether its fabric is usable right now — so a consuming app can turn a failed connect into specific guidance.

**Architecture:** Extend the existing `FabricAvailability` lattice with a third `Unknown` case, wrap it with a `Set<TransportRole>` into a new `TransportCapability`. Make `Loom.capability()` the single primary method (existing `availability()` becomes a derived, no-longer-overridden default). Add a live `StateFlow<TransportCapability>` to `Seam` whose default is a static floor — real OS observers are deferred follow-ups. `CompositeLoom`/`CompositeSeam` aggregate by unioning roles across plies.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines `StateFlow`, kotlin.test. Build via `./gradlew`.

**Spec:** `docs/superpowers/specs/2026-07-18-transport-capability-api-design.md`

## Global Constraints

- **`explicitApi()` is enforced** — every new public declaration needs an explicit `public` modifier.
- **JDK 21**: source SDKMAN first — `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`.
- **`:kuilt-core` depends on nothing but coroutines + serialization** — the new types must not import any fabric-specific code.
- **Naming collision:** `:kuilt-conformance` already uses "capability" for `SeamCapabilities` (test-harness feature flags) — a *different* concept. Keep the new type named `TransportCapability`; never overload the conformance suite's `capabilityGaps()`/`SeamCapabilities` vocabulary.
- **Full build is the only real gate** for behavior/API changes: `./gradlew build detektAll` (not scoped `jvmTest`, which skips Android/Native variants). Verify with `--rerun-tasks` if any test-compile task shows `FROM-CACHE`.
- **Distinguish two `availability()` methods:** migrate only overrides of **`Loom.availability()`**. The SPI interfaces `NwApi.availability()` / `NearbyApi.availability()` are *separate* methods — leave them; the Loom's new `capability()` wraps their result.

---

### Task 1: Core capability types

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FabricAvailability.kt`
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TransportRole.kt`
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TransportCapability.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FabricAvailabilityTest.kt`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/TransportCapabilityTest.kt` (create)

**Interfaces:**
- Produces: `FabricAvailability.Unknown(reason: String)`; `sealed interface TransportRole` with objects `Discovery, Data, WifiLan, WifiDirect, Bluetooth, WebRtc, ServerRelay`; `data class TransportCapability(roles: Set<TransportRole>, availability: FabricAvailability)`.

- [ ] **Step 1: Write the failing test** — append to `FabricAvailabilityTest.kt`:

```kotlin
@Test
fun unknownCarriesReason() {
    val u: FabricAvailability = FabricAvailability.Unknown("local-network permission not yet probed")
    assertEquals("local-network permission not yet probed", (u as FabricAvailability.Unknown).reason)
}
```

And create `TransportCapabilityTest.kt`:

```kotlin
package us.tractat.kuilt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportCapabilityTest {
    @Test
    fun holdsRolesAndAvailability() {
        val cap = TransportCapability(
            roles = setOf(TransportRole.Discovery, TransportRole.Data),
            availability = FabricAvailability.Available,
        )
        assertEquals(setOf(TransportRole.Discovery, TransportRole.Data), cap.roles)
        assertTrue(cap.availability is FabricAvailability.Available)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-core:compileTestKotlinJvm`
Expected: FAIL — `Unknown` and `TransportCapability`/`TransportRole` unresolved.

- [ ] **Step 3: Add `Unknown` to `FabricAvailability.kt`** — inside the sealed interface, after `Unavailable`:

```kotlin
    /**
     * The fabric may or may not be usable — the platform cannot report ground
     * truth right now (e.g. iOS gives no Wi-Fi SSID; a Local-Network permission
     * has not yet been probed). Distinct from a target-scoped-out fabric, which
     * is simply absent. Best-effort consumers should surface [reason] rather than
     * assume [Available] or [Unavailable].
     */
    public data class Unknown(public val reason: String) : FabricAvailability
```

- [ ] **Step 4: Create `TransportRole.kt`:**

```kotlin
package us.tractat.kuilt.core

/**
 * What role a transport plays. A single fabric may hold several roles at once
 * (Apple Multipeer is [WifiDirect] + [Bluetooth]; a Network.framework fabric is
 * [Discovery] + [Data]). Sealed so a novel fabric can add a case without editing
 * a closed enum.
 */
public sealed interface TransportRole {
    /** Finds peers/sessions (mDNS/Bonjour advertising & browsing). */
    public data object Discovery : TransportRole

    /** Carries application frames once a link is established. */
    public data object Data : TransportRole

    /** Wi-Fi via a shared access point — the "same Wi-Fi network" case. */
    public data object WifiLan : TransportRole

    /** Peer-to-peer Wi-Fi with no access point (AWDL / Wi-Fi Aware / Wi-Fi Direct). */
    public data object WifiDirect : TransportRole

    /** Bluetooth radio link. */
    public data object Bluetooth : TransportRole

    /** WebRTC data channel. */
    public data object WebRtc : TransportRole

    /** Reaches peers by relaying through a server. */
    public data object ServerRelay : TransportRole
}
```

- [ ] **Step 5: Create `TransportCapability.kt`:**

```kotlin
package us.tractat.kuilt.core

/**
 * A transport's self-report: the [roles] it plays and whether its fabric is
 * usable now ([availability]). Produced pre-connect by [Loom.capability] and
 * live per-session by [Seam.capability].
 */
public data class TransportCapability(
    public val roles: Set<TransportRole>,
    public val availability: FabricAvailability,
)
```

- [ ] **Step 6: Fix the one exhaustive `when` in `:kuilt-core` test source.** `LoomSamples.kt` is wired into **commonTest** by `build-logic/src/main/kotlin/kuilt.kmp-library.gradle.kts` (`src/commonSamples/kotlin` → commonTest roots), so adding `Unknown` breaks `compileTestKotlinJvm` here and now — not at runtime. Replace `LoomSamples.kt:63-67` body:

```kotlin
    when (val avail = loom.availability()) {
        is FabricAvailability.Available -> { /* ready to weave */ }
        is FabricAvailability.Unavailable -> error("Fabric not usable: ${avail.reason}")
        is FabricAvailability.Unknown -> { /* best-effort: attempt anyway, surface avail.reason */ }
    }
```

- [ ] **Step 7: Run to verify pass**

Run: `./gradlew :kuilt-core:compileTestKotlinJvm`
Expected: PASS (compiles — the new types resolve and no exhaustive `when` in `:kuilt-core` is broken). Downstream modules (`:kuilt-nw`) are not compiled by this task; their `when` break sites are handled in Task 5.

- [ ] **Step 8: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/FabricAvailability.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TransportRole.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TransportCapability.kt \
        kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/FabricAvailabilityTest.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/TransportCapabilityTest.kt
git commit -m "feat(core): add TransportRole, TransportCapability, FabricAvailability.Unknown (#1530)"
```

---

### Task 2: `Loom.capability()` primary; `availability()` derived

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Loom.kt:38-43`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt:55-58`
- Modify: `kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/LoomSamples.kt:61-67`
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/` (add a CompositeLoom role-union test — see Step 4)

**Interfaces:**
- Consumes: `TransportCapability`, `TransportRole`, `FabricAvailability` (Task 1).
- Produces: `Loom.capability(): TransportCapability` (default `TransportCapability(emptySet(), Available)`); `Loom.availability()` now `= capability().availability` and **must not be overridden anywhere**.

- [ ] **Step 1: Write the failing test** — create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeLoomCapabilityTest.kt`:

```kotlin
package us.tractat.kuilt.core.composite

import kotlinx.coroutines.flow.MutableStateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.TransportCapability
import us.tractat.kuilt.core.TransportRole
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeLoomCapabilityTest {
    private fun loomWith(vararg roles: TransportRole) = object : Loom {
        override suspend fun weave(rendezvous: Rendezvous): Seam = error("not woven in this test")
        override fun capability() = TransportCapability(roles.toSet(), FabricAvailability.Available)
    }

    @Test
    fun compositeUnionsPlyRoles() {
        val composite = CompositeLoom(
            listOf(
                PlyId("a") to loomWith(TransportRole.Discovery),
                PlyId("b") to loomWith(TransportRole.Data, TransportRole.Bluetooth),
            ),
        )
        assertEquals(
            setOf(TransportRole.Discovery, TransportRole.Data, TransportRole.Bluetooth),
            composite.capability().roles,
        )
    }
}
```

> Note: `PlyId` is a value class — construct with `PlyId("a")`, not a factory (`PlyId.kt:7`; cf. `CompositeSendReceiveTest.kt:25`). The `CompositeLoom(listOf(...))` list-ctor is correct (`CompositeLoom.kt:41-45`); constructing without weaving starts no coroutines, so the default dispatcher is fine.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-core:compileTestKotlinJvm`
Expected: FAIL — `CompositeLoom.capability()` returns default `emptySet()`, assertion mismatch (or unresolved if `capability()` not yet on the class).

- [ ] **Step 3: Rewrite `Loom.kt` availability block** — replace lines 38-42 (`availability()` KDoc + method) with:

```kotlin
    /**
     * This fabric's role(s) and whether it can be attempted now. The single
     * capability primitive — override this, not [availability]. Default: a
     * roleless [FabricAvailability.Available].
     */
    public fun capability(): TransportCapability =
        TransportCapability(roles = emptySet(), availability = FabricAvailability.Available)

    /**
     * Whether this fabric can be attempted now — the availability half of
     * [capability]. Derived; do not override.
     */
    public fun availability(): FabricAvailability = capability().availability
```

Add `import`s are unnecessary (same package).

- [ ] **Step 4: Migrate `CompositeLoom.kt`** — replace the `override fun availability()` block (lines ~55-58) with role-union + the existing availability rollup:

```kotlin
    override fun capability(): TransportCapability {
        val caps = plies.value.map { it.second.capability() }
        val roles = caps.flatMap { it.roles }.toSet()
        val availability =
            if (caps.any { it.availability == FabricAvailability.Available }) {
                FabricAvailability.Available
            } else {
                FabricAvailability.Unavailable("no ply available")
            }
        return TransportCapability(roles, availability)
    }
```

Add `import us.tractat.kuilt.core.TransportCapability` if not already imported.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :kuilt-core:jvmTest`
Expected: PASS — the new test passes and the module compiles. (The `LoomSamples.kt` exhaustive-`when` was already fixed in Task 1 Step 6.)

- [ ] **Step 6: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Loom.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeLoom.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeLoomCapabilityTest.kt
git commit -m "feat(core): capability() as the primary Loom method, availability() derived (#1530)"
```

---

### Task 3: `Seam.capability` live StateFlow + CompositeSeam aggregation

**Files:**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt` (add `capability` property + default)
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/StaticCapability.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt` (add `capability` rollup)
- Test: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeSeamCapabilityTest.kt` (create)

**Interfaces:**
- Consumes: `TransportCapability`, `TransportRole`, `MappedStateFlow` (existing internal).
- Produces: `Seam.capability: StateFlow<TransportCapability>` (default = static `Available`, `emptySet()` floor).

- [ ] **Step 1: Write the failing test** — `CompositeSeamCapabilityTest.kt` asserting a composite Seam's `capability.value.roles` unions its live plies' roles. Model it on the existing `CompositeAttachAnnounceOrderingTest.kt` harness (reuse its fake plies/looms; give two fake Seams distinct roles via a `capability` override) and assert:

```kotlin
assertEquals(
    setOf(TransportRole.Discovery, TransportRole.Data),
    composite.capability.value.roles,
)
```

> Use the existing composite test fakes as the template — copy their `Loom`/`Seam` fake wiring so the ply set actually reaches `Woven`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-core:compileTestKotlinJvm`
Expected: FAIL — `Seam.capability` / `CompositeSeam.capability` unresolved.

- [ ] **Step 3: Create the floor constant** — `internal/StaticCapability.kt`:

```kotlin
package us.tractat.kuilt.core.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.FabricAvailability
import us.tractat.kuilt.core.TransportCapability

/**
 * The floor [Seam.capability] value: a live-but-roleless [FabricAvailability.Available].
 * A woven [Seam] exists, so its fabric is at least attemptable. Fabrics that know
 * their roles override [Seam.capability] with their own static/live StateFlow.
 */
internal val StaticAvailableCapability: StateFlow<TransportCapability> =
    MutableStateFlow(TransportCapability(emptySet(), FabricAvailability.Available)).asStateFlow()
```

> `.asStateFlow()` is required, not cosmetic: without it a consumer could downcast the interface default to `MutableStateFlow` and mutate the one global value shared by *every* `Seam`. Add `import kotlinx.coroutines.flow.asStateFlow`.

- [ ] **Step 4: Add the property to `Seam.kt`** — after the `plies` property, add:

```kotlin
    /**
     * Live capability of the fabric carrying this session — its role(s) and
     * whether it is usable right now. Updates as radios, permissions, and network
     * paths change. Default: a static roleless [FabricAvailability.Available] floor;
     * fabrics with real OS observers override to make it reactive.
     */
    public val capability: StateFlow<TransportCapability>
        get() = us.tractat.kuilt.core.internal.StaticAvailableCapability
```

- [ ] **Step 5: Add the rollup to `CompositeSeam.kt`.** Roles live on the **`Loom`** (static), so union them from the constituent Looms — held in `desired: StateFlow<List<Pair<PlyId, Loom>>>` (~line 87) — for the plies that are currently `Woven`. The constituent seams are in `live: LinkedHashMap<PlyId, PlyHandle>` (~line 126, `PlyHandle.seam`), and **every read of `live`/`desired`/`idMap` must be under `lock`** (the file's lock discipline, KDoc ~lines 97-100 / #411). Reading `.state.value`, `.capability.value`, and `loom.capability()` are all non-suspending, so computing under the lock is legal (no suspend call inside the locked section).

```kotlin
    private val _capability = MutableStateFlow(
        TransportCapability(emptySet(), FabricAvailability.Available),
    )
    override val capability: StateFlow<TransportCapability> = _capability.asStateFlow()

    /** Recompute from the constituent Looms of currently-Woven plies. Caller holds NO lock. */
    private fun recomputeCapability() {
        val snapshot = lock.withLock {
            val wovenIds = live.entries
                .filter { it.value.seam.state.value is SeamState.Woven }
                .map { it.key }.toSet()
            val roles = desired.value
                .filter { (id, _) -> id in wovenIds }
                .flatMap { (_, loom) -> loom.capability().roles }.toSet()
            roles to wovenIds.isNotEmpty()
        }
        _capability.value = TransportCapability(
            roles = snapshot.first,
            availability = if (snapshot.second) FabricAvailability.Available
            else FabricAvailability.Unavailable("no ply woven"),
        )
    }
```

Call `recomputeCapability()` from the same sites that mutate `_plies` — the per-ply `seam.state.onEach { … }` pump (~line 175), `attachPly` (after a ply goes live), and `detachPly` (~line 214) — **after** releasing any lock those sites hold (the method re-takes `lock` itself; do not call it from inside an already-locked block, to avoid the non-reentrant-lock deadlock the file guards against). Before writing, confirm the field names `live` / `PlyHandle.seam` / `desired` / `lock` against the current file — they were verified against `CompositeSeam.kt` at review time but line numbers drift.

> **Scope note (from review):** this makes *composite* role-union correct because roles are read from the Looms. A **direct** (non-composite) fabric `Seam` (e.g. an NW or WebSocket seam) still reports the roleless floor until its per-fabric live-observer follow-up seeds it — acceptable because the pre-connect role answer already comes from `Loom.capability()` (Task 5). Do not attempt to seed every fabric Seam in this PR.

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew :kuilt-core:jvmTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/internal/StaticCapability.kt \
        kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/composite/CompositeSeamCapabilityTest.kt
git commit -m "feat(core): live Seam.capability StateFlow + CompositeSeam role rollup (#1530)"
```

---

### Task 4: Conformance suite — accept `Unknown`, assert roles

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt` (obligation (6) at ~424-439; the `assertTrue` to edit is ~430-433; `connectedPair` helper ~line 275)

**Interfaces:**
- Consumes: `FabricAvailability.Unknown`, `TransportCapability`, `Loom.capability()`, `Seam.capability`.
- Produces: widened obligation (6); new obligation asserting `capability.value.availability == Available` while `Woven`.

- [ ] **Step 1: Read the current obligation (6)** at lines ~424-439 (`availabilityReturnsAKnownVariant`) to get the exact `assertTrue` expression (~430-433) and surrounding harness (`hostLoom`).

- [ ] **Step 2: Widen the availability assertion** — replace the `Available || Unavailable` check with one that also accepts `Unknown`:

```kotlin
        val availability = hostLoom.availability()
        assertTrue(
            availability is FabricAvailability.Available ||
                availability is FabricAvailability.Unavailable ||
                availability is FabricAvailability.Unknown,
            "availability() must return Available, Unavailable, or Unknown, got $availability",
        )
```

- [ ] **Step 3: Add a live-capability obligation** — a new ungated `@Test` that, inside `connectedPair { host, _ -> }`, asserts the woven host reports an `Available` capability:

```kotlin
    internal suspend fun runWovenSeamReportsAvailableCapability(scope: TestScope): Unit =
        scope.connectedPair { host, _ ->
            assertTrue(
                host.capability.value.availability is FabricAvailability.Available,
                "a Woven Seam must report Available capability, got ${host.capability.value}",
            )
        }

    @Test
    public fun wovenSeamReportsAvailableCapability(): TestResult =
        runTest { runWovenSeamReportsAvailableCapability(this) }
```

> Do **not** assert roles are non-empty here — in-memory/loopback conformance looms legitimately have `emptySet()`. Role coverage is asserted per-fabric in that fabric's own test (Task 5), not the shared suite.

- [ ] **Step 4: Run the conformance-derived tests**

Run: `./gradlew :kuilt-core:jvmTest :kuilt-conformance:jvmTest`
Expected: PASS (InMemoryLoom conformance still green with the new obligation).

- [ ] **Step 5: Commit**

```bash
git add kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/SeamConformanceSuite.kt
git commit -m "test(conformance): accept Unknown availability + assert Woven capability (#1530)"
```

---

### Task 5: Migrate fabric Looms + declare roles + fix test looms

**Files (migrate every `override fun availability()` that overrides `Loom` — NOT `NwApi`/`NearbyApi`):**
- Modify: `kuilt-websocket/.../KtorClientLoom.kt`, `KtorServerLoom.kt`, `KtorMeshClientLoom.kt` → add `capability()`
- Modify: `kuilt-nw/src/commonMain/.../NwLoom.kt` (override at ~line 120)
- Modify: `kuilt-nearby/src/commonMain/.../NearbyLoom.kt:69`
- Modify: `kuilt-multipeer/src/jvmMain/.../MultipeerPeerLinkFactory.jvm.kt:115` **AND** the Apple actual `kuilt-multipeer/src/appleMain/.../MultipeerPeerLinkFactory.apple.kt` (~line 60) — see Step 4a; the JVM file is a macOS-only stub, the Apple actual is the *real* fabric and must also declare roles.
- Modify: `kuilt-mdns/...` loom (find the `Loom` impl), `kuilt-tcp/.../TcpLoom.kt`, `kuilt-webrtc/src/wasmJsMain/.../WebRTCPeerLinkFactory.kt` (wasmJs-only)
- Modify: `kuilt-nw/src/jvmMain/.../NwCrossProcessProbe.kt` (~line 151) — a second exhaustive `when` over `FabricAvailability`, in **main** source; see Step 4b.
- Modify (test looms → `capability()`): `kuilt-conformance/.../DelayedWovenLoom.kt:54`, `kuilt-test/.../ControllableLoom.kt:67`, `kuilt-gossip/.../GossipSeamConformanceTest.kt:71`, the composite test fakes, `kuilt-nw/.../NwBridgeLoopbackConformanceTest.kt:121`, `NwConnectionDrainTest.kt:143`, `NwLoopbackConformanceTest.kt:102`
- **Leave untouched (SPI, not Loom):** `NwApi.availability()` (`RealNwApi.kt:245`, `FakeNwApi.kt:66`, `BridgeNwApi.kt:~203`, `NwNativeLib.jvmAvailability`), `NearbyApi.availability()` (`GmsNearbyApi.kt:102`, `FakeNearbyRadio.kt:152`), `ConnectStateMachineTest`/`NearbySeamTearDownTest` api fakes.

**Interfaces:**
- Consumes: `TransportCapability`, `TransportRole`, each fabric's existing availability logic.
- Produces: per-fabric `capability()` returning the roles from the design table.

Role table (from the spec):

| Loom | roles |
|---|---|
| `KtorClientLoom` / `KtorServerLoom` / `KtorMeshClientLoom` | `{ServerRelay, Data}` |
| `NwLoom` | `{Discovery, Data}` |
| `MultipeerPeerLinkFactory` | `{Discovery, Data, WifiDirect, Bluetooth}` |
| `NearbyLoom` | `{Bluetooth, WifiDirect, Data}` |
| mDNS loom | `{Discovery, WifiLan}` |
| `TcpLoom` | `{Data}` |
| WebRTC loom | `{WebRtc, Data}` |

- [ ] **Step 1: Enumerate the real migration set** — run to separate Loom overrides from SPI overrides:

```bash
grep -rn "override fun availability" --include="*.kt" . | grep -v "/build/"
```

For each hit, open the file and check what interface it overrides. Migrate only `Loom` overrides.

- [ ] **Step 2: For each fabric Loom, add a failing role test first** (one per module). Example for NW — `kuilt-nw/src/commonTest/.../NwLoomCapabilityTest.kt`:

```kotlin
@Test
fun declaresDiscoveryAndDataRoles() {
    // NwLoom requires a serviceType; FakeNwApi needs (radio, deviceId, serviceName) —
    // match the real ctors (NwLoom.kt:101-109; FakeNwApi.kt:33-37). Reuse whatever
    // helper the existing NW tests use to build a FakeNwApi.
    val loom = NwLoom(api = FakeNwApi(FakeNwRadio(), deviceId = "d", serviceName = "s"), serviceType = "_kuilt._udp")
    assertEquals(setOf(TransportRole.Discovery, TransportRole.Data), loom.capability().roles)
}
```

Repeat per module with that module's roles + a **constructible** fake. Note the awkward constructors, verified at review time:
- **`TcpLoom`** has a *private* constructor (`TcpLoom.kt:38`); instances come only from `TcpLoom.host(serverSocket, selfId, selector, …)`. Its role test needs a real bound loopback `ServerSocket` + `SelectorManager` (construction does no IO, so it's cheap) — model it on the existing TCP tests, not a bare `TcpLoom(...)`.
- **`WebRTCPeerLinkFactory`** is `wasmJsMain`-only; its role test lives in `wasmJsTest` and runs via `:kuilt-webrtc:wasmJsTest` (see Step 5).

- [ ] **Step 3: Run to verify they fail** — `./gradlew :kuilt-nw:compileTestKotlinJvm` (etc.): FAIL, roles empty.

- [ ] **Step 4: Migrate each Loom.** Pattern — replace `override fun availability(): FabricAvailability = <expr>` with:

```kotlin
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = setOf(TransportRole.Discovery, TransportRole.Data),   // this fabric's roles
            availability = <the same expr that availability() returned>,   // e.g. api.availability()
        )
```

For test looms with a plain `= FabricAvailability.Available`, use `TransportCapability(emptySet(), FabricAvailability.Available)` (roles don't matter for a test double) unless a test asserts on them.

- [ ] **Step 4a: Migrate the Multipeer Apple actual (the real fabric).** `MultipeerPeerLinkFactory` is an `expect` class; only the JVM actual overrides availability today. Add `override fun capability()` to the **Apple** actual (`MultipeerPeerLinkFactory.apple.kt`, ~line 60) returning the real roles, so the spec's mapping lands where the fabric actually runs:

```kotlin
    override fun capability(): TransportCapability =
        TransportCapability(
            roles = setOf(TransportRole.Discovery, TransportRole.Data, TransportRole.WifiDirect, TransportRole.Bluetooth),
            availability = FabricAvailability.Available,
        )
```

The JVM actual keeps its native-lib gate but now via `capability()` with the *same* role set (availability = the existing `nativeLib != null` branch). The android/wasmJs stubs inherit the default (`Available`, `emptySet()`) while `weave` throws — leave them, or optionally return `Unavailable("Multipeer is Apple-only")` for honesty (one line; not required for #1530).

- [ ] **Step 4b: Fix the second exhaustive `when` — `NwCrossProcessProbe.kt`.** `unavailableReason(): String?` (~line 151) is an expression `when` over `FabricAvailability` with `Unavailable -> a.reason` / `Available -> null` and no `else`; adding `Unknown` breaks `:kuilt-nw` **main** compilation. `Unknown` means "attempt anyway, best-effort" — so it is *not* a definitive unavailable reason; return `null`:

```kotlin
    is FabricAvailability.Unknown -> null   // not definitively unavailable — let the probe proceed
```

- [ ] **Step 5: Run each module's tests** — `./gradlew :kuilt-nw:jvmTest :kuilt-nearby:jvmTest :kuilt-multipeer:jvmTest :kuilt-websocket:jvmTest :kuilt-mdns:jvmTest :kuilt-tcp:jvmTest :kuilt-gossip:jvmTest :kuilt-webrtc:wasmJsTest`: PASS. (Multipeer's Apple actual + WebRTC's wasmJs code are compile-checked here for JVM/wasmJs respectively; full Apple/Native compile is Task 6.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(fabrics): declare TransportRole per fabric; migrate to capability() (#1530)"
```

---

### Task 6: Full build, docs, follow-up issues

**Files:**
- Modify: `kuilt-core/module.md` and/or the `FabricAvailability`/`Loom`/`Seam` KDoc if a `@sample` reference needs updating.
- Modify (Writerside guide — currently teaches the now-migrated pattern): `Writerside/topics/fabrics.md:100-102` (documents `override fun availability()` as the how-to-write-a-fabric pattern → change to `override fun capability()`), `Writerside/topics/contract.md:22` (documents `FabricAvailability` as two-valued → add `Unknown` + mention roles) and `contract.md:50` (samples `availability()`).

- [ ] **Step 1: Full cache-disabled build (the real gate)**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL — confirms Android + Apple/Kotlin-Native variants compile the new exhaustive `when`s, the Multipeer Apple actual, and every `capability` override.

- [ ] **Step 2: Grep for any remaining `Loom` `availability()` override** (must be zero):

```bash
grep -rn "override fun availability" --include="*.kt" . | grep -v "/build/"
```

Expected: only `NwApi`/`NearbyApi` SPI overrides remain.

- [ ] **Step 3: Update the Writerside guide** so it doesn't teach the migrated-away pattern:
  - `Writerside/topics/fabrics.md:100-102` — replace the `override fun availability()` how-to-write-a-fabric snippet with `override fun capability()` (roles + availability), keeping the `<!-- verbatim from … -->` citation accurate if the snippet is test-cited.
  - `Writerside/topics/contract.md:22` — `FabricAvailability` is now three-valued (`Available`/`Unavailable`/`Unknown`) and transports report roles; update the prose. `contract.md:50` — refresh the `availability()` sample if the change alters it.
  - Keep the accessible-first flow (project docs rule): plain-language first, type names deeper.

- [ ] **Step 4: File follow-up issues** (per the spec's out-of-scope list) — one per fabric for **live OS observers** (`NWPathMonitor`, `CBCentralManager`, GMS listeners, WebRTC ICE) making `Seam.capability` reactive; plus one for **`CompositeSeam` transport-selection** consuming the aggregated capability. Reference #1530.

```bash
gh issue create --title "kuilt-nw: live Seam.capability via NWPathMonitor/permission observer" \
  --body "🤖 Filed by Claude on behalf of @keddie. Part of #1530. Make NwLoom's Seam.capability reactive to path/permission changes."
# repeat for multipeer (CBCentralManager), nearby (GMS listener), webrtc (ICE state), + CompositeSeam selection.
```

- [ ] **Step 5: Open the PR**

```bash
git push -u origin feat/1530-transport-capability-api
gh pr create --title "feat: unified transport-capability API — roles + live availability (closes #1530)" \
  --body "$(cat <<'EOF'
🤖 This PR was generated by Claude on behalf of @keddie.

Unified capability report per the spec (`docs/superpowers/specs/2026-07-18-transport-capability-api-design.md`).

- `capability()` is now the single primary `Loom` method; `availability()` is derived.
- `FabricAvailability.Unknown(reason)` added for platforms without ground truth.
- `TransportRole` sealed hierarchy (`WifiLan` vs `WifiDirect` distinguished).
- Live `Seam.capability: StateFlow`; `CompositeSeam`/`CompositeLoom` union roles across plies.

Static reporting only; per-fabric **live OS observers** are follow-ups (filed, part of #1530).

Closes #1530.
EOF
)"
gh pr view --web
```

- [ ] **Step 6: Enable auto-merge once green**

```bash
gh pr merge --auto --squash
```

## Self-Review

- **Spec coverage:** core types ✓ (T1) · `Unknown` ✓ (T1) · `Loom.capability` primary + derived `availability` ✓ (T2) · `Seam.capability` live StateFlow ✓ (T3) · Composite role-union + rollup ✓ (T2/T3) · conformance widening ✓ (T4) · per-fabric roles + corrected Nearby/Multipeer mapping ✓ (T5) · naming-collision note ✓ (Global Constraints) · follow-up issues ✓ (T6). No gaps.
- **Placeholders:** none — every code step shows code. Task 5's per-file loop is mechanical with an explicit pattern + role table; Task 3/5 flag "match the real ctor/field name" rather than guess a signature I haven't verified.
- **Type consistency:** `TransportCapability(roles, availability)`, `capability()`, `Seam.capability`, `TransportRole.*` used identically across tasks. `StaticAvailableCapability` defined in T3, referenced only in the `Seam` default in the same task.

## Fable review (claude-fable-5) — incorporated

A read-only Fable pass against the real source found 9 actionable items; all folded in:
- **B1** — second exhaustive `when` in `NwCrossProcessProbe.kt` (main source) → Task 5 Step 4b.
- **B2** — `LoomSamples.kt` is `commonTest`, so its `when` break lands in Task 1's own compile gate → moved the fix to Task 1 Step 6.
- **S1** — `CompositeSeam` rollup used a wrong field (`idMap`) and ignored the lock → Task 3 Step 5 rewritten to snapshot `live`/`desired` under `lock`.
- **S2** — Multipeer roles must also land on the **Apple actual**, not just the JVM stub → Task 5 Step 4a.
- **S3** — no concrete Seam is seeded, so composite role-union would be empty → union from constituent **Looms** (Task 3 Step 5); direct-Seam roles explicitly de-scoped to follow-ups (spec + Task 3 scope note).
- **S4** — `PlyId("a")` (no `.of`), real `NwLoom`/`FakeNwApi`/`TcpLoom` ctors → Tasks 2 & 5 corrected.
- **S5** — Writerside `fabrics.md`/`contract.md` teach the migrated-away pattern → Task 6 Step 3.
- Verified-correct: the Loom-vs-SPI override split (all 22 grep hits classified) needs no change.
