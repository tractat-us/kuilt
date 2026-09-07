# Depart Carries Finals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A peer that departs cleanly declares the finals it authored, so a later fence can settle without it and `relocationPatch` stops freezing an entire edge on its uncancelled carried row.

**Architecture:** `ControlCommand.Depart` gains a `finals` payload the departing peer reads off its own store. `HeddleControlPlane` records it in `FenceState.departedFinals`. When a later `Quiesce(e)` commits, any departed peer's declaration for `e` is folded into `acks[e]` through the existing `FenceState.acked` path, so `fence.acksOn(e)` — and therefore `relocationPatch`'s `perReplica` — contains it. §6.1 is satisfied rather than traded: the promiser declares, about itself, while present.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization, `:kuilt-heddle`, `:kuilt-raft` (log), JDK 21.

**Spec:** [`docs/heddle-depart-finals-design.md`](../heddle-depart-finals-design.md) — read it first; this plan argues from it.

---

## ⛔ DO NOT START — this plan is SUPERSEDED, not merely gated

**An adversarial review on 2026-09-06 found the design unsound and this plan defective.** Do not
execute any task below until both are revised. Findings against *this plan* specifically:

- **C5 — Tasks 4/5 do not compile as sketched.** `HeddleControlPlane` is constructed **positionally
  with 8 arguments** across 8 test sites, so inserting a parameter "beside `barrier`" is a compile
  break — the plan's claim that "no existing construction site changes" is **false**. Worse, the
  control plane never *reads* `departure`: the only consumer is `GovernedHeddleNode.depart()`, which
  already holds `node`. Delete the seam. A defaulted functional dependency also contradicts
  `barrier`'s own KDoc ("Required, never defaulted") and the optional≠tuning rule.
- **C4 — Task 2's wire test targets the wrong codec.** The log is **CBOR**, not JSON, and `ReplicaId`
  is a value class encoding as a bare string — the literal cannot decode under either. Pin
  pre-change CBOR bytes as a hex literal captured on `main`. And name the other direction: a
  **non-empty** declaration hits the `envelope == null` skip on an old binary (the #1738 exposure).
- **I1 — `FenceState`'s 4th parameter must NOT be defaulted.** With a default, omitting it in
  `acked` or `relocated` compiles and silently resets `departedFinals`. **No test in this plan can
  see it**: the fold iterates a snapshot, so a dropping `acked` stays invisible until a *second*
  edge is quiesced.
- **I3 — Task 5's fixture pointer is to the wrong kind of fixture.** `handOffChainWithBystander` is
  a **pure-ledger** builder whose move 1 never goes through the control plane, so on the production
  receiver there is no carried row and the refusal cannot fire. A real E2E needs two full fence
  cycles committed via `Reconcile`, a plane per acker, the departing peer's plane stopped, and the
  `MultiNodeRaftSim` harness — *"the hardest task in the plan, and it is two lines."*
- **I4 — the `allEdges()` question is resolvable from source.** Enumerate
  `transfers.keys + transferRelocIn.keys` and map back via `AttachmentId(key.value)` (a bijection),
  filtering `PathKey.ROOT`. Also **sort before `associateWith`** — `filterTo(HashSet())` makes the
  committed `Depart` bytes hash-ordered on the proposer.

This plan's own "Known weaknesses" section named the right three *small* problems and **none** of
the five above. That is the lesson worth keeping: a self-review catches thin writing, not wrong
design.

## The original gate

The spec rests on two open questions, **both unresolved as of 2026-09-06**:

1. **Is the ordering premise true in practice?** The mechanism is confirmed from source, but the empirical (a)/(b) split in real departures is unmeasured and no instrument exists. If departures are overwhelmingly crashes (ordering (a)), this prevents a case that rarely happens.
2. **Does the recovery path this replaces even work?** #2610 recorded it as unverified. An investigation is in flight (#2600).

**Do not begin Task 1 until a human has confirmed both.** If the investigation shows ordering (a) dominates, the correct action is to close this plan unimplemented, not to build it anyway.

## Global Constraints

- **`explicitApi()` is enforced.** Every public declaration needs an explicit visibility modifier. Everything this plan adds is `internal` — nothing here belongs on the public surface.
- **Test style:** no `test` prefix on test methods; multi-assert tests use `assertAll()`; `@ParameterizedTest` only when it makes the test materially shorter.
- **No `!!` anywhere.** Enforced by `forbidNotNullAssertionInUnresolvedSource` on a per-file ratchet.
- **No production dispatchers in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) — enforced by `forbidProductionDispatcherInTests`.
- **Multi-node tests use the canonical harness.** Never hand-roll a cluster or a `while (true) { delay(1) }` leader-wait. Use `TEST_WEDGE_BACKSTOP` for `runTest` timeouts, never a tight literal.
- **Build:** `JAVA_HOME=/Users/keddie/.sdkman/candidates/java/21.0.5-tem ./gradlew …`. There is **no linter** — `detekt`/`detektAll` are not tasks. Static analysis is the `check`-wired source scans in the root `build.gradle.kts`.
- **A module-scoped build is a false green** for anything touching `ControlPlane`/`FenceState`. Run the full `./gradlew clean && ./gradlew build`.
- **Commit style:** never the word "chore". Conventional prefixes (`feat:`, `fix:`, `test:`, `docs:`) are used in this repo.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/FenceState.kt` | log-pure fence state | **Modify** — add `departedFinals` map, `departed()`, `departedFinalsOn()` |
| `.../ControlPlane.kt` | act vocabulary + apply loop | **Modify** — `Depart.finals` field (:165), its apply arm (:795), the `Quiesce` apply arm (:732), new `ControlDepartureSink` seam |
| `.../EntitlementLedger.kt` | the lattice | **Modify** — add `internal fun transferRowEdgesFor(r): Set<AttachmentId>` beside `allEdges()` (:1737) |
| `.../HeddleNode.kt` | the node | **Modify** — add `asDepartureSink()` beside `asBarrierSink()` (:304) |
| `.../HeddleGoverned.kt` | governed facade | **Modify** — `depart()` (:477) builds and carries the declaration |
| `kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt` | this feature's tests | **Create** |

---

### Task 1: `FenceState` holds a departed peer's declaration

**Files:**
- Modify: `kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/FenceState.kt:138-181`
- Test: `kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt` (create)

**Interfaces:**
- Consumes: `SlotFinals` (`FenceState.kt:86`), its `join` and `ZERO`.
- Produces: `FenceState.departed(replica: ReplicaId, finals: Map<AttachmentId, SlotFinals>): FenceState` and `FenceState.departedFinalsOn(edge: AttachmentId): Map<ReplicaId, SlotFinals>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.heddle

import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class HeddleDepartFinalsTest {

    private val alice = ReplicaId("alice")
    private val e4 = AttachmentId("e4")
    private val e9 = AttachmentId("e9")

    @Test
    fun aDepartedPeersDeclarationIsRetrievablePerEdgeAndJoinsByMax() {
        val low = SlotFinals(issued = 10L, returned = 0L, leafSpent = 2L, rollupSpent = 0L)
        val high = SlotFinals(issued = 10L, returned = 3L, leafSpent = 5L, rollupSpent = 0L)
        val state = FenceState.EMPTY
            .departed(alice, mapOf(e4 to low, e9 to SlotFinals.ZERO))
            .departed(alice, mapOf(e4 to high))

        assertAll(
            { assertEquals(mapOf(alice to high), state.departedFinalsOn(e4), "the later declaration's higher slots win") },
            { assertEquals(mapOf(alice to SlotFinals.ZERO), state.departedFinalsOn(e9), "an edge only the first declaration named survives") },
            { assertEquals(emptyMap(), state.departedFinalsOn(AttachmentId("unknown")), "an unnamed edge has no declaration") },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/Users/keddie/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: FAIL — `Unresolved reference: departed`.

- [ ] **Step 3: Write minimal implementation**

In `FenceState.kt`, add a fourth constructor property and two members. Thread it through **every** existing `FenceState(...)` construction in `quiesced`, `acked`, `relocated` and `EMPTY` — there are four, and missing one silently drops declarations.

```kotlin
internal class FenceState private constructor(
    private val quiescedAt: Map<AttachmentId, Long>,
    private val acks: Map<AttachmentId, Map<ReplicaId, SlotFinals>>,
    val relocations: EntitlementLedger,
    private val departedFinals: Map<ReplicaId, Map<AttachmentId, SlotFinals>> = emptyMap(),
) {
    /**
     * [replica]'s own declaration of what it authored, carried on its self-service
     * [ControlCommand.Depart] (§6.1: the promiser declares). Joined by per-slot max against any
     * prior declaration, exactly as [acked] does — so a re-declaration may only ever RAISE a final.
     */
    fun departed(replica: ReplicaId, finals: Map<AttachmentId, SlotFinals>): FenceState {
        val prior = departedFinals[replica] ?: emptyMap()
        val merged = prior.toMutableMap()
        for ((edge, declared) in finals) {
            merged[edge] = prior[edge]?.join(declared) ?: declared
        }
        return FenceState(quiescedAt, acks, relocations, departedFinals + (replica to merged))
    }

    /** Every departed peer's declaration for [edge] — the set a committing barrier folds into its acks. */
    fun departedFinalsOn(edge: AttachmentId): Map<ReplicaId, SlotFinals> =
        departedFinals.mapNotNull { (replica, byEdge) -> byEdge[edge]?.let { replica to it } }.toMap()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/Users/keddie/.sdkman/candidates/java/21.0.5-tem ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: PASS. Confirm it actually ran — a `--tests` filter matching nothing passes silently: `grep -c testcase kuilt-heddle/build/test-results/jvmTest/TEST-us.tractat.kuilt.heddle.HeddleDepartFinalsTest.xml`

- [ ] **Step 5: Commit**

```bash
git add kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/FenceState.kt \
        kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt
git commit -m "feat(heddle): FenceState holds a departed peer's own finals declaration (part of #2600)"
```

---

### Task 2: `Depart` carries the declaration, and the apply arm records it

**Files:**
- Modify: `.../ControlPlane.kt:165` (the `Depart` data class), `:795-813` (its apply arm)
- Test: `.../HeddleDepartFinalsTest.kt`

**Interfaces:**
- Consumes: `FenceState.departed` from Task 1.
- Produces: `ControlCommand.Depart(replica: ReplicaId, finals: Map<AttachmentId, SlotFinals> = emptyMap())`.

- [ ] **Step 1: Write the failing test**

Append to `HeddleDepartFinalsTest`. This is a **wire** test — the default is what keeps an old-format entry decodable, and a schema break here would vanish topology acts on replay (the shape #1717 is about).

```kotlin
    @Test
    fun aDepartWithNoDeclarationStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val legacy = """{"type":"us.tractat.kuilt.heddle.ControlCommand.Depart","replica":{"value":"alice"}}"""
        val decoded = json.decodeFromString(ControlCommand.serializer(), legacy)
        assertAll(
            { assertEquals(ControlCommand.Depart(alice), decoded, "an entry written before this field must still decode") },
            { assertEquals(emptyMap(), (decoded as ControlCommand.Depart).finals, "…as an empty declaration, not a failure") },
        )
    }
```

⚠ **Verify the discriminator string against the module's actual `Json` configuration before trusting this literal.** Find how `ControlEnvelope` is serialised in `ControlPlane.kt` and match it; if the module uses a different class-discriminator, fix the literal rather than the test's intent.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=… ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: FAIL — `Depart` has no `finals` parameter.

- [ ] **Step 3: Write minimal implementation**

`ControlPlane.kt:165` — extend the class, keeping the default so the field is wire-optional:

```kotlin
    @Serializable
    data class Depart(
        val replica: ReplicaId,
        /**
         * [replica]'s own finals on every edge where it holds a transfer row — its declaration of
         * what it authored, made while it is still present and holding its own store (§6.1: only
         * the promiser may make a promise about itself). Defaulted so an entry written before this
         * field still decodes; an empty declaration reproduces the pre-#2600 behaviour exactly.
         *
         * ⚠ Narrow the SET of edges, never the CONTENTS of one declaration. `relocationPatch`
         * drains each acked replica from its FULL finals, so a zero-filled [SlotFinals] would
         * under-declare this peer's counters — the amnesiac failure mode, reintroduced.
         *
         * A buggy or malicious peer can declare wrong finals here. That is the same exposure
         * [QuiesceAck] already carries, under the same self-asserted crash-fault (not Byzantine)
         * trust model the Raft log itself assumes — a sibling of the amnesiac rejoiner documented
         * on `SlotFinals.transfers`, not a new class. Named rather than left implicit.
         */
        val finals: Map<AttachmentId, SlotFinals> = emptyMap(),
    ) : ControlCommand
```

Then the apply arm at `:811`, inside the existing `else` branch (the self-service gate is unchanged and still applies — a third party may not declare on someone's behalf):

```kotlin
                } else {
                    roster.depart(command.replica)?.let { roster = it }
                    // The declaration is recorded, never applied: Depart still moves no entitlement
                    // (§8.1). It becomes an ack only when a later Quiesce over one of these edges
                    // commits — see the Quiesce arm.
                    fence = fence.departed(command.replica, command.finals)
                    ControlOutcome.Applied(index)
                }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=… ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/ControlPlane.kt \
        kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt
git commit -m "feat(heddle): Depart carries the departing peer's finals declaration (part of #2600)"
```

---

### Task 3: A committing `Quiesce` folds departed declarations into its acks

**Files:**
- Modify: `.../ControlPlane.kt:732` (the `Quiesce` apply arm, immediately after `fence = fence.quiesced(...)`)
- Test: `.../HeddleDepartFinalsTest.kt`

**Interfaces:**
- Consumes: `FenceState.departedFinalsOn` (Task 1), `Depart.finals` (Task 2).
- Produces: nothing new — the observable effect is that `fence.acksOn(edge)` contains departed peers.

- [ ] **Step 1: Write the failing test**

This is the task that makes the feature work, so its test is the mechanism's pin. Build it on the existing `HeddleControlPlaneTest` harness rather than a hand-rolled cluster — copy its `ControlBarrierSink { edge -> sink.snapshot().baseFinalsOn(edge, replica) }` wiring (`HeddleControlPlaneTest.kt:664`).

```kotlin
    @Test
    fun aBarrierCommittingAfterADepartAdoptsTheDepartedPeersDeclarationAsAnAck() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        val declared = SlotFinals(issued = 20L, returned = 0L, leafSpent = 5L, rollupSpent = 0L)
        val state = FenceState.EMPTY
            .departed(alice, mapOf(e4 to declared))
            .quiesced(e4, index = 7L)

        // The production fold, exercised through the same call the apply arm makes.
        val folded = state.departedFinalsOn(e4)
            .entries.fold(state) { acc, (replica, finals) -> acc.acked(e4, replica, finals) }

        assertAll(
            { assertEquals(mapOf(alice to declared), folded.acksOn(e4), "the declaration reads as an ack") },
            { assertEquals(emptySet(), folded.pendingAcks(e4, setOf(alice)), "…so the barrier is not waiting on alice") },
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=… ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: PASS at the `FenceState` level (Task 1 already provides both members) — **this test pins the composition, not new code.** If it fails, Task 1 is wrong. The *production* wiring is asserted end-to-end in Task 5; this step exists so the fold's semantics are pinned independently of the control plane.

- [ ] **Step 3: Write the production wiring**

`ControlPlane.kt`, in the `Quiesce` apply arm, immediately after `fence = fence.quiesced(command.edge, index)`:

```kotlin
                    fence = fence.quiesced(command.edge, index)
                    // A peer that departed BEFORE this barrier is absent from `enrolledAt(index)`,
                    // so the fence never waits for it — but `relocationPatch` still refuses on an
                    // uncancelled carried row it left behind (`unackedCarriedDonors`). Its own
                    // departure declaration is the missing ack, and folding it here is what makes
                    // the move derivable. Log-pure: both the declaration and this index come off
                    // the committed prefix, so every peer folds identically.
                    for ((replica, declared) in fence.departedFinalsOn(command.edge)) {
                        fence = fence.acked(command.edge, replica, declared)
                    }
```

- [ ] **Step 4: Run the full heddle module and confirm nothing regressed**

Run: `JAVA_HOME=… timeout 1800 ./gradlew clean && JAVA_HOME=… timeout 1800 ./gradlew :kuilt-heddle:build --max-workers=5 --console=plain 2>&1 | tee /tmp/t3.log; grep -q "BUILD SUCCESSFUL" /tmp/t3.log && echo GREEN || echo "NOT GREEN"`
Expected: GREEN. Assert on the output, not the exit code — a piped Gradle run returns `tail`'s status.

- [ ] **Step 5: Commit**

```bash
git add kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/ControlPlane.kt \
        kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt
git commit -m "feat(heddle): a committing barrier adopts a departed peer's declaration as its ack (part of #2600)"
```

---

### Task 4: The declaration's source — enumerate the edges, and a seam to read them

**Files:**
- Modify: `.../EntitlementLedger.kt:1737` (beside `allEdges()`)
- Modify: `.../ControlPlane.kt:290` (beside `ControlBarrierSink`)
- Modify: `.../HeddleNode.kt:304` (beside `asBarrierSink()`)
- Test: `.../HeddleDepartFinalsTest.kt`

**Interfaces:**
- Produces: `EntitlementLedger.transferRowEdgesFor(r: ReplicaId): Set<AttachmentId>`, and `internal fun interface ControlDepartureSink { fun finalsOnDeparture(): Map<AttachmentId, SlotFinals> }`.

⚠ **Do NOT add a method to `ControlBarrierSink`.** It is a `fun interface` with 9+ SAM-lambda construction sites across the test suite (`HeddleRosterTest.kt:358`, `HeddleFenceTest.kt:435/612/699`, `HeddleControlPlaneTest.kt:664/1057`, …). A second abstract method breaks every one of them. Add a **separate** `fun interface` with a defaulted constructor parameter instead.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun theDeclarationNamesOnlyEdgesWhereThePeerHoldsATransferRow() {
        // carol → alice at e2's path key; alice holds no row at e9 but does hold counters there.
        val l = EntitlementLedger.ZERO
            .piece(EntitlementLedger.bootstrap(GroupId("root"), mapOf(carol to 100L), nonce = "g"))
            // …build e2 with a transfer carol → alice, and e9 with an alice counter but no row.
        assertAll(
            { assertTrue(e2 in l.transferRowEdgesFor(carol), "carol holds an outflow row at e2") },
            { assertTrue(e9 !in l.transferRowEdgesFor(carol), "…and no row at e9, counters notwithstanding") },
            { assertEquals(emptySet(), l.transferRowEdgesFor(ReplicaId("nobody")), "a peer with no rows declares nothing") },
        )
    }
```

⚠ The fixture body is deliberately left as a comment because the exact mutator sequence depends on the topology you build. Copy the ladder from `EntitlementLedgerReconcileTest`'s `spendThroughStrand()` (`:169-183`) and adapt — **do not invent one**; a fixture that never creates a transfer row makes this test vacuously green.

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — `Unresolved reference: transferRowEdgesFor`.

- [ ] **Step 3: Write minimal implementation**

`EntitlementLedger.kt`, beside `allEdges()`:

```kotlin
    /**
     * The edges where [r] holds a transfer row — base or carried — and therefore the exact set a
     * departure declaration must cover (`docs/heddle-depart-finals-design.md` §5.1). Those are the
     * only edges whose move `unackedCarriedDonors` can refuse on [r]'s account.
     *
     * Edges where [r] has counters but no row are deliberately excluded: it is not in `finals`
     * there today either, so omitting them changes nothing.
     */
    internal fun transferRowEdgesFor(r: ReplicaId): Set<AttachmentId> =
        allEdges().filterTo(HashSet()) { edge ->
            val key = PathKey.of(edge)
            transfers[key]?.containsKey(r) == true ||
                transferRelocIn[key]?.containsKey(r) == true ||
                transferRelocOut[key]?.containsKey(r) == true
        }
```

⚠ **Verify `allEdges()` actually reaches every edge with a transfer row.** It unions `records.keys`, `lifecycle.keys` and the edge-counter matrices — but `transfers` is keyed by `PathKey`, not `AttachmentId`, so an edge known *only* through a row would be missed. Prove it either way with a test; if it can be missed, widen `allEdges()` or enumerate the path keys directly.

`ControlPlane.kt`, beside `ControlBarrierSink`:

```kotlin
/**
 * The seam a peer uses to build its own departure declaration — §6.1's "the promiser declares",
 * read off its own store while it is still present. Separate from [ControlBarrierSink] rather than
 * a second method on it: that is a `fun interface` with SAM-lambda construction sites throughout
 * the suite, and a second abstract method would break every one.
 */
internal fun interface ControlDepartureSink {
    /** This peer's own finals on every edge where it holds a transfer row. */
    fun finalsOnDeparture(): Map<AttachmentId, SlotFinals>
}
```

Add it as a **defaulted** constructor parameter on `HeddleControlPlane` (beside `barrier`, `:350`) so no existing construction site changes:

```kotlin
    private val departure: ControlDepartureSink = ControlDepartureSink { emptyMap() },
```

`HeddleNode.kt`, beside `asBarrierSink()`:

```kotlin
    internal fun asDepartureSink(): ControlDepartureSink = ControlDepartureSink {
        val snapshot = ledger.value
        snapshot.transferRowEdgesFor(self).associateWith { snapshot.baseFinalsOn(it, self) }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=… ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/EntitlementLedger.kt \
        kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/ControlPlane.kt \
        kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/HeddleNode.kt \
        kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt
git commit -m "feat(heddle): a peer can enumerate and read its own departure declaration (part of #2600)"
```

---

### Task 5: `depart()` carries it, end-to-end, with the negative arm

**Files:**
- Modify: `.../HeddleGoverned.kt:477-487`
- Modify: `.../ControlPlane.kt` — wire `departure` where `HeddleControlPlane` is constructed in `heddleGoverned()` (`HeddleGoverned.kt:90`)
- Test: `.../HeddleDepartFinalsTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4.

- [ ] **Step 1: Write the failing test — BOTH arms**

The negative arm is what makes the positive one a claim. Reuse the #2610 freeze fixture from `EntitlementLedgerReconcileTest.kt:1137-1436` — do not build a new one.

```kotlin
    @Test
    fun aCleanDepartWithADeclarationUnfreezesTheGroupAndWithoutOneDoesNot() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // Arm 1 — the pre-#2600 behaviour, reproduced exactly: depart carrying nothing.
        val frozen = departThenQuiesceThenReconcile(declaration = emptyMap())
        // Arm 2 — the same sequence, the peer declaring what it authored.
        val settled = departThenQuiesceThenReconcile(declaration = aliceDeclaration)

        assertAll(
            { assertIs<ControlConflict.Refused>(frozen.conflict, "without a declaration the move is still refused") },
            { assertTrue("alice" in frozen.reason, "…naming the donor whose row blocks it") },
            { assertIs<ControlOutcome.Applied>(settled.outcome, "with a declaration the move is derivable") },
            // The rig fired: prove the carried row was really there and uncancelled at the moment
            // of the move, or a green here means the fixture never reached the frozen state.
            { assertTrue(settled.carriedResidualBeforeMove > 0L, "the carried row must be live or this proves nothing") },
            { assertEquals(30L, settled.holdingsAfter(dave), "the bystander's units are spendable again") },
            { assertEquals(40L, settled.holdingsAfter(bob), "…and the funded recipient's, since alice's row moved with them") },
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — arm 2 still refuses, because `depart()` does not yet carry the declaration.

- [ ] **Step 3: Write minimal implementation**

`HeddleGoverned.kt:477`:

```kotlin
    public suspend fun depart(timeout: Duration? = null): ControlOutcome =
        control.propose(ControlCommand.Depart(self, departure.finalsOnDeparture()), timeout)
```

…and pass `node.asDepartureSink()` where `HeddleControlPlane` is constructed in `heddleGoverned()`.

⚠ Read the existing `depart()` body before replacing it — it has a `ControlOutcome` shape and possibly a writability guard this sketch omits. Preserve them.

- [ ] **Step 4: Run test to verify it passes, then the full build**

```bash
JAVA_HOME=… ./gradlew :kuilt-heddle:jvmTest --tests "*HeddleDepartFinalsTest*" --console=plain
JAVA_HOME=… ./gradlew clean && JAVA_HOME=… timeout 2400 ./gradlew build --max-workers=5 --console=plain 2>&1 | tee /tmp/t5.log
grep -q "BUILD SUCCESSFUL" /tmp/t5.log && echo GREEN || echo "NOT GREEN"
```

Expected: GREEN. A module-scoped build is a **false green** here — `ControlPlane` changes are consensus-behaviour changes, and the `:examples`/`:kuilt-cluster` E2E tests are downstream.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(heddle): a clean depart declares its finals, unfreezing the group (part of #2600)"
```

---

### Task 6: The stale-declaration containment, pinned rather than inherited

**Files:**
- Test: `.../HeddleDepartFinalsTest.kt`

The spec's §5.3 names three mechanisms that stop a stale declaration under-declaring. The third — the boot gate — *"was written to justify excluding a late enroller, not to justify trusting a stale declaration"*, so it must be pinned against this new use rather than inherited.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun aRejoinedPeersStaleDeclarationCannotBeTheLastWordOnALaterBarrier() = runTest(timeout = TEST_WEDGE_BACKSTOP) {
        // alice departs declaring leafSpent = 5, re-enrolls, then a NEW edge is quiesced.
        val outcome = departDeclaringThenReEnrollThenQuiesce(declared = 5L)
        assertAll(
            { assertIs<ControlConflict.Refused>(outcome.conflict, "the later barrier waits for a real ack") },
            { assertTrue("alice" in outcome.reason, "…naming alice as pending, not adopting her stale declaration") },
        )
    }

    @Test
    fun aReDeclarationMayOnlyRaiseAFinalNeverLowerIt() {
        val state = FenceState.EMPTY
            .departed(alice, mapOf(e4 to SlotFinals(issued = 20L, returned = 0L, leafSpent = 9L, rollupSpent = 0L)))
            .departed(alice, mapOf(e4 to SlotFinals(issued = 20L, returned = 0L, leafSpent = 1L, rollupSpent = 0L)))
        assertEquals(9L, state.departedFinalsOn(e4).getValue(alice).leafSpent, "the max is kept, so a lower re-declaration cannot erase a charge")
    }
```

- [ ] **Step 2: Run to verify the SHAPE of the failure**

Expected: the second test passes from Task 1's `join`; the first may pass or fail depending on the boot gate. **Either result is a finding.** If it passes, the containment is real and now pinned. If it fails, the stale declaration *is* adoptable and the spec's §5.3 is wrong — **stop and report** rather than adding a guard, because that changes the design.

- [ ] **Step 3: Mutation — prove the fold is what is doing the work**

Break the fold in Task 3 (`for ((replica, declared) in emptyMap<ReplicaId, SlotFinals>())`) and re-run the whole class. Expected: Task 5's arm 2 reds, Task 6's tests do not. Record `tests=N skipped=0 failures=M` per row against the **final** suite.

- [ ] **Step 4: Commit**

```bash
git add kuilt-heddle/src/commonTest/kotlin/us/tractat/kuilt/heddle/HeddleDepartFinalsTest.kt
git commit -m "test(heddle): pin what stops a stale departure declaration under-declaring (part of #2600)"
```

---

## Known weaknesses in this plan — read before executing

Stated rather than hidden, because a plan that looks uniformly complete invites an executor to
trust the thin parts as much as the thick ones.

1. **Two fixture bodies are pointers, not code.** Task 4 Step 1 and Task 5 Step 1 name the ladder to
   copy (`EntitlementLedgerReconcileTest.spendThroughStrand()` at `:169-183`; the #2610 freeze
   fixture at `:1137-1436`) instead of inlining it. That is a real gap by this repo's own standard —
   *"a fixture that never creates a transfer row makes this test vacuously green"* — and the
   executor must read those helpers rather than improvise. If either turns out not to produce the
   state the task needs, **stop and report**; do not adapt the assertion to whatever the fixture
   happens to reach.
2. **Task 3 Step 2 expects a PASS, not a FAIL.** It pins a composition of Task 1's members rather
   than driving new code, so it breaks the plan's own red-then-green rhythm. It is kept because the
   fold's semantics deserve a pin independent of the control plane — but the *production* wiring is
   only proven end-to-end in Task 5, and an executor who stops after Task 3 has shipped an unwired
   feature that no test would catch.
3. **The `allEdges()` reachability question in Task 4 is unresolved** and is a correctness
   precondition for the whole declaration, not a detail. If an edge known only through a transfer
   row is invisible to `allEdges()`, the declaration silently omits exactly the edges that matter.
   Settle it with a test before building on it.

## Follow-ups to file, not to build

- **`departedFinals` grows without bound** and has no retirement rule, exactly as `FenceState.acks` does. File it; do not solve it here.
- **An over-large declaration** — §5.1's narrowing may still exceed the Raft batch bound (#2720/#2721). The spec's Open Question 3 does not settle whether `Depart` should refuse or ship incomplete. File it as `needs-design`.
- **`kuilt-primitives` skill:** this adds no public primitive (everything is `internal`), so no route is owed. Confirm before closing.
