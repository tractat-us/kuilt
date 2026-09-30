# Agent workspace — M0 harness and M1 causality spike: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one headless scenario harness for the dinner story, run a server-owned baseline through it (M0), then add a kuilt backend and prove or refute the input-history promise on the same scenarios (M1, causality half).

**Architecture:** A plain-KMP, JVM-only `:demo-workspace` module under `demo/workspace/`, following the `demo/shared` precedent: no `kuilt.kmp-library`, no publishing, listed in `kuilt-bom`'s `deliberatelyUnpublished`. Scenarios are backend-neutral scripts of steps. Each step that creates an input has a stable scenario-level key, so an oracle can score any backend. There are two backends: `BaselineBackend`, a server-owned model with per-key version checks and an offline edit queue, and `KuiltBackend`, three `Quilter<Rga<WorkspaceEntry>>` replicas over `FaultyLoom(InMemoryLoom())`. An input's identity is the `Dot` of its `Rga` insert, as ruled for epic #2869: **dot-carrying types only**.

**Tech Stack:** Kotlin Multiplatform (JVM target only), kotlinx-coroutines-test (virtual time), kotlinx-serialization (CBOR via Quilter), `:kuilt-quilter`, `:kuilt-crdt`, `:kuilt-test` (`FaultyLoom`/`FaultySeam`/`FaultProfile`, `assertAll`).

**Spec:** Epic tractat-us/kuilt#2869 (its M0 and M1 sections) and discussion tractat-us/kuilt#2868. Rulings recorded on #2869: identities from dot-carrying types only; prototype in `demo/`, Compose decided at M1 exit.

## Global Constraints

- Module: `:demo-workspace` at `demo/workspace/`, package `us.tractat.kuilt.demo.workspace`. Plain `kotlinMultiplatform` plugin + `kotlinSerialization`, `jvm()` target only.
- Add `":demo-workspace"` to `deliberatelyUnpublished` in `kuilt-bom/build.gradle.kts` in the same PR that adds the module to `settings.gradle.kts`.
- **Every input an agent can receive is an element of an `Rga`, and its identity is `RgaId.dot`.** No `LWWRegister`, `ORMap`, `MVRegister` or `JsonNode.Leaf` may carry an input: they contribute no dots (`Quilted.causalDots()` defaults to empty). A preference is an appended `PreferenceSet` entry, not a mutable scalar.
- Delivery of a dot on a replica is `d in state.causalDots() || state.causalFloor().contains(d)`. Nothing else counts, including a delivery acknowledgement.
- No `Dispatchers.*` or `GlobalScope` in tests (`forbidProductionDispatcherInTests`). Use `runTest(UnconfinedTestDispatcher())` and `backgroundScope`, and `QuilterConfig(expectVirtualTime = true)`.
- No bare `runCatching` in suspend code, no `!!`, no bare `.launchIn(` in `*Main` (root guards scan `demo/` too).
- Test methods carry no `test` prefix. A test with several asserts uses `assertAll()` from `:kuilt-test`.
- No external links (a2ui.org, developer.android.com) in any committed file (References policy in `AGENTS.md`). Committed docs follow `docs/voice.md` and lead accessible-first.
- Headless and JVM-only. **Make no cross-target claim from these results.** M6 owns cross-target evidence.
- Verify before auto-merge: `./gradlew clean && ./gradlew :demo-workspace:build :kuilt-bom:build --rerun-tasks` (JDK 25.0.2-tem), and confirm tasks `EXECUTED`.
- Metric values are **counts over scripted actors**, never user-study results. Every committed results table says so.

## Review Focus

1. **The proposal whose basis names a dot this replica has not received.** Expect `Unknown`, never `Applicable`. This covers a remote agent that saw more than the phone judging it has.
2. **A report removed and garbage-collected after a proposal captured it.** The proposal's basis must still read as the dots it captured. Assessment must not throw on a dot that has no live entry.
3. **A duplicated or reordered proposal delivery** (`FaultProfile.ReorderWindow`, a duplicate apply). The proposal appears once, and its basis is unchanged.
4. **A reply for an unknown or already-completed request id.** It is rejected and never turned into a trusted proposal.
5. **An input on the host that the agent's selection excluded.** It is absent from the basis, and it counts as *missing* in assessment even though the host had it at capture time.

Each is pinned by a named test in Task 7 or Task 8.

---

### Task 1: Hypotheses, metrics and scenario script (the pre-registration)

> Iain's rulings (2026-09-29) amended this task; `demo/workspace/HYPOTHESES.md` as merged is authoritative over this task's text.

This is the M0 gate. Pass/stop criteria are fixed **before** any measurement. The PR is **held for Iain's approval**. Do not arm auto-merge. When it is open, set the peer session to `blocked` with the PR link, and go on to Tasks 2–4, which do not depend on its wording. Task 5 must not start until this PR merges.

**Files:**
- Create: `demo/workspace/HYPOTHESES.md`

- [ ] **Step 1: Write the doc.** Required sections, in this order, accessible-first:
  1. *The story in one paragraph*: two friends, a closure, a late agent reply.
  2. *Scenarios* S1–S7, each in 2–4 plain sentences with its step list:
     - S1 independent edits: A adds a note on venue V2, B reports V3 full; no agent.
     - S2 budget changed during inference: remote agent captures, A lowers budget to 30, agent returns V1 (price 40).
     - S3 closure report: remote agent captures, B (on the network) reports V1 closed, agent returns V1.
     - S4 two conflicting human choices: A and B, partitioned, each accept a different proposal.
     - S5 corrected report: B reports V1 closed (`closeV1`), agent captures, B corrects (`reopenV1`), agent returns V2, the stale answer since V1 is nearer.
     - S6 S2 without the outage: S2's steps with A connected throughout.
     - S7 harmless note: remote agent captures, B adds a note on V1, agent returns V1, still right.
  3. *Metrics*, each with an exact definition:
     - `editsMade` and `editsPreserved`: the human edit steps, and those whose effect is visible in every actor's final view.
     - `agentRuns` and `unnecessaryReruns`: a rerun is unnecessary when it returns the same recommendation as the run it replaced.
     - `staleTreatedAsCurrent` (**ground truth**, not policy): a proposal shown to an actor as applicable when rerunning the scripted agent on *basis ∪ inputs that actor knew* returns a different recommendation. The agent is deterministic, so the oracle can compute this exactly.
     - `falseInvalidations`: a proposal flagged for review where that rerun returns the *same* recommendation, which is wasted human attention.
     - `missedInvalidations`: the backend's own relevance policy called a proposal applicable and ground truth disagreed. This is identical to `staleTreatedAsCurrent` for kuilt, and it is reported separately so the policy's error is visible.
     - `humanPrompts`: the times a person had to choose.
     - `outageActions` and `outageActionsServed`: local actions attempted while partitioned, and those that took visible effect locally without the network.
     - Tracked separately, not scored: wire bytes, from `FaultySeam` delivered-frame counts.
  4. *Relevance policy* (what a backend may use cheaply, without rerunning the agent): a missing input is relevant if (a) it is a `Report` about the recommended venue, (b) it is a `Report.Reopened` about any venue (a nearer place may be back), (c) it is a `PreferenceSet` below the recommended venue's price, or (d) it is a `PreferenceSet` that makes a nearer venue affordable. Notes are never relevant. This is **policy**; staleness is scored against ground truth (the metric above), so the policy's misses and over-flags are measured rather than assumed. S5 exists to test (b): its missing input concerns a venue *other* than the recommended one.
  5. *Hypotheses table*: columns `id | claim | metric | pass if | stop if`. Required rows:
     - H1: the kuilt backend has `staleTreatedAsCurrent == 0` on S2, S3 and S5, where the baseline has ≥ 1 or needs a rerun to avoid it.
     - H1b: kuilt's `falseInvalidations` is ≤ 1 across S1–S7. More means the policy asks people too often.
     - H2: `unnecessaryReruns` is lower for kuilt on S1 and S5.
     - H3: `outageActionsServed / outageActions` is 1.0 for kuilt, and below 1.0 for the baseline on S2–S4.
     - H4: `humanPrompts` is equal or lower for kuilt.
     - H0 (stop): if the baseline's scoped version checks match kuilt on H1–H3, record that the simpler design suffices and recommend narrowing.
  6. *Trust boundary*: the negative claim and its limits, copied in substance from #2869 ("did not receive through tracked inputs", not "did not know"; missing evidence ⇒ unknown).
- [ ] **Step 2: Lint the prose.** Run `vale --no-global demo/workspace/HYPOTHESES.md`. Expected: no errors. If Vale's config does not cover `demo/`, note that in the PR body instead.
- [ ] **Step 3: Commit, open the PR, and hold it.**

```bash
git add demo/workspace/HYPOTHESES.md
git commit -m "docs(demo-workspace): pre-register M0 hypotheses and scenarios (#2869)"
gh pr create --title "Pre-register agent-workspace hypotheses (#2869 M0)" --body "…one screen: what, why fixed before measuring, ask Iain to approve pass/stop criteria…"
```

---

### Task 2: Module skeleton and domain types

**Files:**
- Create: `demo/workspace/build.gradle.kts`
- Modify: `settings.gradle.kts` (after the `:demo-tap` lines, around line 138)
- Modify: `kuilt-bom/build.gradle.kts:53-60` (`deliberatelyUnpublished`)
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/Domain.kt`
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/DomainTest.kt`

**Interfaces:**
- Produces: `ActorId`, `VenueId`, `Venue`, `Fixtures.venues`, the sealed `WorkspaceEntry` (`Report.Closed`, `Report.Full`, `Report.Reopened`, `PreferenceSet`, `Note`, `AgentProposal`), `Recommendation`, and `ScriptedAgent.recommend(inputs: List<WorkspaceEntry>): Recommendation`.

- [ ] **Step 1: Build file and wiring.**

```kotlin
// demo/workspace/build.gradle.kts
// Agent-workspace spike (epic #2869, M0/M1). Deliberately a PLAIN KMP module, as
// demo/shared is: no explicitApi, no publishing, JVM only. Headless: results here are
// JVM-only evidence and make no cross-target claim. Listed in kuilt-bom's
// `deliberatelyUnpublished` set.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kuilt-quilter")) // api-exposes :kuilt-core + :kuilt-crdt
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":kuilt-test"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies { runtimeOnly(libs.logback) }
    }
}
```

```kotlin
// settings.gradle.kts, after the :demo-tap block
// Agent-workspace spike (epic #2869) — unpublished, JVM only.
include(":demo-workspace")
project(":demo-workspace").projectDir = file("demo/workspace")
```

Add `":demo-workspace", // agent-workspace spike, epic #2869 (plain KMP jvm, never published)` to `deliberatelyUnpublished`.

- [ ] **Step 2: Write the failing test.**

```kotlin
package us.tractat.kuilt.demo.workspace

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainTest {
    private val a = ActorId("alex")

    @Test
    fun agentPicksNearestOpenVenueWithinBudget() {
        val inputs = listOf(
            WorkspaceEntry.PreferenceSet(a, budget = 35),
            WorkspaceEntry.Report.Closed(a, VenueId("v2")),
        )
        assertEquals(Recommendation(VenueId("v3")), ScriptedAgent.recommend(inputs))
    }

    @Test
    fun reopenedReportCancelsEarlierClosure() {
        val inputs = listOf(
            WorkspaceEntry.Report.Closed(a, VenueId("v1")),
            WorkspaceEntry.Report.Reopened(a, VenueId("v1")),
        )
        assertEquals(Recommendation(VenueId("v1")), ScriptedAgent.recommend(inputs))
    }

    @Test
    fun noVenueFitsYieldsNone() = assertAll(
        { assertEquals(Recommendation(null), ScriptedAgent.recommend(listOf(WorkspaceEntry.PreferenceSet(a, budget = 5)))) },
        { assertEquals(Recommendation(VenueId("v1")), ScriptedAgent.recommend(emptyList())) },
    )
}
```

- [ ] **Step 3: Run it and watch it fail.** Run `./gradlew :demo-workspace:jvmTest --tests "*DomainTest*"`. Expected: a compilation failure (unresolved references).
- [ ] **Step 4: Implement.**

```kotlin
package us.tractat.kuilt.demo.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable public data class ActorId(val value: String)
@Serializable public data class VenueId(val value: String)
@Serializable public data class Venue(val id: VenueId, val name: String, val pricePerHead: Int, val walkMinutes: Int)

public object Fixtures {
    // Ordered by walkMinutes so "nearest" is unambiguous.
    public val venues: List<Venue> = listOf(
        Venue(VenueId("v1"), "Trattoria Uno", pricePerHead = 40, walkMinutes = 2),
        Venue(VenueId("v2"), "Due Fratelli", pricePerHead = 25, walkMinutes = 5),
        Venue(VenueId("v3"), "Tre Sorelle", pricePerHead = 30, walkMinutes = 8),
        Venue(VenueId("v4"), "Quattro", pricePerHead = 20, walkMinutes = 15),
    )
    public fun venue(id: VenueId): Venue = venues.first { it.id == id }
}

@Serializable public data class Recommendation(val venue: VenueId?)

/** Every input an agent can receive. Each lives as one Rga element; its identity is that insert's Dot. */
@Serializable
public sealed interface WorkspaceEntry {
    public val by: ActorId

    @Serializable
    public sealed interface Report : WorkspaceEntry {
        public val venue: VenueId
        @Serializable @SerialName("closed") public data class Closed(override val by: ActorId, override val venue: VenueId) : Report
        @Serializable @SerialName("full") public data class Full(override val by: ActorId, override val venue: VenueId) : Report
        @Serializable @SerialName("reopened") public data class Reopened(override val by: ActorId, override val venue: VenueId) : Report
    }

    @Serializable @SerialName("budget") public data class PreferenceSet(override val by: ActorId, val budget: Int) : WorkspaceEntry
    @Serializable @SerialName("note") public data class Note(override val by: ActorId, val venue: VenueId, val text: String) : WorkspaceEntry
    // AgentProposal is added in Task 6, once Proposal exists.
}

public object ScriptedAgent {
    /** Deterministic "agent": the nearest venue that is open (last report wins, in input order) and within the last budget set. */
    public fun recommend(inputs: List<WorkspaceEntry>): Recommendation {
        val budget = inputs.filterIsInstance<WorkspaceEntry.PreferenceSet>().lastOrNull()?.budget ?: Int.MAX_VALUE
        val unavailable = inputs.filterIsInstance<WorkspaceEntry.Report>()
            .groupBy { it.venue }
            .filterValues { reports -> reports.last() !is WorkspaceEntry.Report.Reopened }
            .keys
        return Recommendation(
            Fixtures.venues.filter { it.id !in unavailable && it.pricePerHead <= budget }.minByOrNull { it.walkMinutes }?.id,
        )
    }
}
```

- [ ] **Step 5: Run the tests and watch them pass.** Same command. Expected: PASS, 3 tests (check `demo/workspace/build/test-results/jvmTest/*.xml`).
- [ ] **Step 6: Commit and open a Draft PR** that claims the module: "demo-workspace: module skeleton and dinner domain (#2869)".

---

### Task 3: Backend-neutral scenarios, backend contract and the oracle

**Files:**
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/Scenario.kt`
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/Oracle.kt`
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/OracleTest.kt`

**Interfaces:**
- Consumes: Task 2's domain.
- Produces:
  - `InputKey(val value: String)`, the step id that created an input.
  - `sealed interface Step` with `Edit(key, actor, entry)`, `StartAgent(request: String, host: ActorId, selectExcluding: Set<InputKey> = emptySet())`, `ToolResult(request, key, entry)`, `ReleaseAgent(request)`, `Accept(actor, request)`, `Partition(actor)`, `Reconnect(actor)`.
  - `Scenario(val name: String, val actors: List<ActorId>, val steps: List<Step>)` and `Scenarios.all: List<Scenario>` (S1–S7 from Task 1).
  - `Presentation(actor: ActorId, request: String, recommendation: Recommendation, basis: Set<InputKey>, known: Set<InputKey>, shownAsApplicable: Boolean)`.
  - `RunResult(presentations, finalViews: Map<ActorId, List<WorkspaceEntry>>, agentRuns: Int, reruns: List<Pair<Recommendation, Recommendation>>, humanPrompts: Int, outageActions: Int, outageActionsServed: Int)`.
  - `interface WorkspaceBackend { suspend fun run(scenario: Scenario): RunResult }`.
  - `Metrics` (the fields named in Task 1, including `falseInvalidations`).
  - `Oracle.score(scenario, result): Metrics`.
  - `Relevance.isRelevant(missing: WorkspaceEntry, rec: Recommendation): Boolean`.

- [ ] **Step 1: Write the failing test.** The oracle is scored against hand-built `RunResult`s:

```kotlin
class OracleTest {
    private val a = ActorId("alex")
    private val closeV1 = InputKey("closeV1")
    private val s3 = Scenarios.all.first { it.name == "S3" }

    private fun result(p: Presentation) = RunResult(
        presentations = listOf(p), finalViews = emptyMap(), agentRuns = 1, reruns = emptyList(),
        humanPrompts = 0, outageActions = 0, outageActionsServed = 0,
    )

    @Test
    fun shownApplicableWhileKnowingRelevantMissingReportIsStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s3, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggedForReviewIsNotStale() {
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(closeV1), shownAsApplicable = false)
        assertEquals(0, Oracle.score(s3, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun reopeningAnotherVenueMakesAnApplicableProposalStale() {
        // S5: basis saw v1 closed and chose v2; the presenter knows v1 reopened. Truth reruns to v1.
        val s5 = Scenarios.all.first { it.name == "S5" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v2")), basis = setOf(closeV1), known = setOf(closeV1, InputKey("reopenV1")), shownAsApplicable = true)
        assertEquals(1, Oracle.score(s5, result(p)).staleTreatedAsCurrent)
    }

    @Test
    fun flaggingAProposalTruthKeepsIsAFalseInvalidation() {
        val s1 = Scenarios.all.first { it.name == "S1" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(InputKey("noteV2")), shownAsApplicable = false)
        assertEquals(1, Oracle.score(s1, result(p)).falseInvalidations)
    }

    @Test
    fun irrelevantMissingInputIsNotStale() {
        val note = InputKey("noteV2")
        val s1 = Scenarios.all.first { it.name == "S1" }
        val p = Presentation(a, "r1", Recommendation(VenueId("v1")), basis = emptySet(), known = setOf(note), shownAsApplicable = true)
        assertEquals(0, Oracle.score(s1, result(p)).staleTreatedAsCurrent)
    }
}
```

The oracle resolves an `InputKey` to its `WorkspaceEntry` through the scenario's `Edit`/`ToolResult` steps. Scenario key names used above (`closeV1`, `noteV2`) are fixed in `Scenarios`.

- [ ] **Step 2: Run it and watch it fail.** Run `./gradlew :demo-workspace:jvmTest --tests "*OracleTest*"`. Expected: a compilation failure.
- [ ] **Step 3: Implement `Scenario.kt`.** Use the types above. Define S1–S7 exactly as `HYPOTHESES.md` lists them, with actors `alex`, `sam` and `remote`; the remote agent is hosted on `remote`. Example (S3):

```kotlin
Scenario("S3", listOf(alex, sam, remote), listOf(
    Step.StartAgent("r1", host = remote),
    Step.Edit(InputKey("closeV1"), sam, WorkspaceEntry.Report.Closed(sam, VenueId("v1"))),
    Step.ReleaseAgent("r1"),
))
```

- [ ] **Step 4: Implement `Oracle.kt`.**

```kotlin
/** The cheap relevance POLICY (HYPOTHESES.md §4). The Oracle never uses it; it scores ground truth. */
public object Relevance {
    public fun isRelevant(missing: WorkspaceEntry, rec: Recommendation): Boolean {
        val chosen = rec.venue?.let(Fixtures::venue)
        return when (missing) {
            is WorkspaceEntry.Report.Reopened -> true
            is WorkspaceEntry.Report -> missing.venue == chosen?.id
            is WorkspaceEntry.PreferenceSet ->
                chosen == null ||
                    missing.budget < chosen.pricePerHead ||
                    Fixtures.venues.any { it.walkMinutes < chosen.walkMinutes && it.pricePerHead <= missing.budget }
            else -> false
        }
    }
}

public object Oracle {
    public fun score(scenario: Scenario, result: RunResult): Metrics {
        val entries: Map<InputKey, WorkspaceEntry> = scenario.steps.mapNotNull {
            when (it) {
                is Step.Edit -> it.key to it.entry
                is Step.ToolResult -> it.key to it.entry
                else -> null
            }
        }.toMap()
        // Ground truth: rerun the deterministic agent on basis ∪ what the presenter knew, in scenario order.
        val order: List<InputKey> = entries.keys.toList()
        fun truth(p: Presentation): Recommendation =
            ScriptedAgent.recommend(order.filter { it in p.basis || it in p.known }.mapNotNull { entries[it] })
        val stale = result.presentations.count { p -> p.shownAsApplicable && truth(p) != p.recommendation }
        val falseInvalidations = result.presentations.count { p -> !p.shownAsApplicable && truth(p) == p.recommendation }
        val edits = scenario.steps.filterIsInstance<Step.Edit>()
        val preserved = edits.count { e -> result.finalViews.values.all { view -> e.entry in view } }
        return Metrics(
            editsMade = edits.size, editsPreserved = preserved,
            agentRuns = result.agentRuns,
            unnecessaryReruns = result.reruns.count { (before, after) -> before == after },
            staleTreatedAsCurrent = stale, falseInvalidations = falseInvalidations, humanPrompts = result.humanPrompts,
            outageActions = result.outageActions, outageActionsServed = result.outageActionsServed,
        )
    }
}
```

Note for review: `editsPreserved` compares entries by value. Two humans adding an identical `Note` would be indistinguishable. No scenario does this; say so in a KDoc line.

- [ ] **Step 5: Run the tests and watch them pass.** Expected: 5/5. Add a `RelevanceTest` pinning policy clauses (a)–(d), one test each.
- [ ] **Step 6: Commit** ("demo-workspace: scenarios, backend contract, oracle").

---

### Task 4: Baseline backend — server-owned model with scoped version checks

This is an honest baseline. Per-key versions are the strongest simple design, and wherever they solve a problem the baseline gets credit for it.

**Files:**
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/BaselineBackend.kt`
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/BaselineBackendTest.kt`

**Interfaces:**
- Consumes: `WorkspaceBackend`, `Scenario`, `Step`, `RunResult`, `Presentation`, `ScriptedAgent`, `Relevance`.
- Produces: `class BaselineBackend : WorkspaceBackend`.

Semantics (write these into the class KDoc):
- The server holds `entries: List<Pair<InputKey, WorkspaceEntry>>` and a version per *key scope*. The scopes are `venue:<id>` for reports and notes, and `budget`.
- A connected client's `Edit` applies at once and bumps its scope's version.
- A partitioned client's `Edit` enters its offline queue with the scope version it last saw. It counts as one `outageAction`. It is **not served**: nothing is visible even locally until reconnect, because the server owns the model. (Optimistic local display is a variant; add it as a constructor flag `optimisticLocal: Boolean = false`, and run both in Task 5.)
- On `Reconnect`, queued edits replay. An edit whose scope version is unchanged applies. Otherwise it is a conflict: `humanPrompts += 1`, and it applies after the prompt, since the scripted human accepts.
- `StartAgent` records `basedOn = versions of scopes {budget} ∪ {venue:v for all v}` and the input keys it saw (minus `selectExcluding`).
- On `ReleaseAgent`, the result depends on scopes `{budget, venue:<recommended>}`. If any of them changed since capture, the server rejects it and reruns the agent on current inputs (`agentRuns += 1`, a `reruns` entry). The rerun result is then presented as applicable. Otherwise the result is presented as applicable.
- `Accept` in S4: the second accept on a changed version is a conflict, so `humanPrompts += 1`.
- Presentation `known` is what the presenting actor has seen. For the baseline, that is the server's set if the actor is connected, and their last sync otherwise.

- [ ] **Step 1: Write the failing tests**, one per scenario, asserting the RunResult facts the semantics above determine:

```kotlin
class BaselineBackendTest {
    private suspend fun run(name: String) = BaselineBackend().run(Scenarios.all.first { it.name == name })

    @Test
    fun closureDuringInferenceForcesRerunAndIsNotStale() = runTest {
        val r = run("S3")
        assertAll(
            { assertEquals(2, r.agentRuns) },
            { assertEquals(listOf(Recommendation(VenueId("v1")) to Recommendation(VenueId("v2"))), r.reruns) },
            { assertEquals(0, Oracle.score(Scenarios.all.first { it.name == "S3" }, r).staleTreatedAsCurrent) },
        )
    }

    @Test
    fun budgetChangedOfflineIsNotVisibleUntilReconnect() = runTest {
        val r = run("S2")
        assertAll(
            { assertEquals(1, r.outageActions) },
            { assertEquals(0, r.outageActionsServed) },
        )
    }

    @Test
    fun conflictingAcceptsPromptAHuman() = runTest {
        assertEquals(1, run("S4").humanPrompts)
    }

    @Test
    fun independentEditsBothSurvive() = runTest {
        val s1 = Scenarios.all.first { it.name == "S1" }
        val m = Oracle.score(s1, BaselineBackend().run(s1))
        assertEquals(m.editsMade, m.editsPreserved)
    }
}
```

If a scenario's step list makes one of these expectations wrong, the scenario definition decides. Fix the test's expected value, and state in the PR which expectation moved and why. Do not bend the backend to match.

- [ ] **Step 2: Run it and watch it fail.** Run `./gradlew :demo-workspace:jvmTest --tests "*BaselineBackendTest*"`.
- [ ] **Step 3: Implement `BaselineBackend`.** It is a plain sequential interpreter over `scenario.steps`, with no coroutines inside (`run` is `suspend` only for interface parity). Keep the server, per-actor connectivity, offline queues, and pending agent captures as local state in `run`.
- [ ] **Step 4: Run the tests and watch them pass.**
- [ ] **Step 5: Commit** ("demo-workspace: server-owned baseline backend").

---

### Task 5: Baseline measurements (M0 exit evidence)

**Blocked until Task 1's PR is merged.** Criteria first, numbers second.

**Files:**
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/BaselineMeasurementTest.kt`
- Create: `demo/workspace/RESULTS.md`

- [ ] **Step 1: Write a golden test.** For each of S1–S7 × `optimisticLocal ∈ {false, true}`, assert the full `Metrics` value. Run it once to learn the values, **then review every number by hand against the scenario**. A value you cannot explain from the semantics is a bug in the backend or the oracle, not a number to record.

```kotlin
@Test
fun baselineMetrics() = runTest {
    val expected: Map<Pair<String, Boolean>, Metrics> = mapOf(
        ("S1" to false) to Metrics(/* filled from the explained run */),
        // … all 14 rows
    )
    val actual = expected.keys.associateWith { (name, opt) ->
        val s = Scenarios.all.first { it.name == name }
        Oracle.score(s, BaselineBackend(optimisticLocal = opt).run(s))
    }
    assertEquals(expected, actual)
}
```

- [ ] **Step 2: Write `RESULTS.md`.** It holds the baseline table (14 rows), a one-line explanation per non-zero cell, and the "counts over scripted actors, not a user study" caveat. Leave a `## kuilt backend` section for Task 9 whose only content is "Pending M1."
- [ ] **Step 3: Commit.** Take the Draft PR out of draft once Tasks 2–5 are in, and arm auto-merge. **This ends M0.** Post a comment on #2869 with the baseline table and the link.

---

### Task 6: Input capture contract — type-shaped, with a positive control

This is the heart of the promise. The shape makes a stamped-late basis impossible to construct, and the positive control proves that the property test would notice if it were constructed anyway.

**Files:**
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/Capture.kt`
- Modify: `Domain.kt` (add `WorkspaceEntry.AgentProposal`)
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/CaptureTest.kt`

**Interfaces:**
- Consumes: `Rga<WorkspaceEntry>`, `Dot`, `RgaId.dot`, `ScriptedAgent`.
- Produces:
  - `RequestId(val value: String)`.
  - `StepBasis(val index: Int, val dots: Set<Dot>)`, where dots are added at this step only.
  - `InputRecord(val request: RequestId, val steps: List<StepBasis>)` with `val allDots: Set<Dot>` and `fun basisThrough(step: Int): Set<Dot>`.
  - `Proposal` (private constructor) with `request`, `basis: InputRecord`, `recommendation`, `intermediate: List<Pair<Int, Recommendation>>`. Nested inside it, `Proposal.Pending` with `withToolResult(log: Rga<WorkspaceEntry>, newIds: Set<RgaId>): Pending`, `intermediate(rec): Pending` and `complete(rec): Proposal`.
  - `InputCapture.capture(request: RequestId, snapshot: Rga<WorkspaceEntry>, exclude: (WorkspaceEntry) -> Boolean = { false }): Proposal.Pending`.
  - `@Serializable WorkspaceEntry.AgentProposal(by, request, basis: List<List<Dot>>, recommendation)`, the wire form.

- [ ] **Step 1: Write the failing tests.**

```kotlin
class CaptureTest {
    private val r = ReplicaId("remote")
    private val alex = ActorId("alex")

    private fun Rga<WorkspaceEntry>.append(e: WorkspaceEntry): Pair<Rga<WorkspaceEntry>, RgaId> {
        val (next, op) = insertAt(r, size, e)
        return next to op.id
    }

    @Test
    fun laterHostUpdatesNeverEnterTheBasis() {
        val (s0, budget) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val pending = InputCapture.capture(RequestId("r1"), s0)
        val (_, closure) = s0.append(WorkspaceEntry.Report.Closed(alex, VenueId("v1")))
        val proposal = pending.complete(ScriptedAgent.recommend(listOf(WorkspaceEntry.PreferenceSet(alex, 50))))
        assertAll(
            { assertEquals(setOf(budget.dot), proposal.basis.allDots) },
            { assertTrue(closure.dot !in proposal.basis.allDots) },
        )
    }

    @Test
    fun intermediateOutputKeepsItsEarlierBasis() {
        val (s0, budget) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p0 = InputCapture.capture(RequestId("r1"), s0).intermediate(Recommendation(VenueId("v1")))
        val (s1, tool) = s0.append(WorkspaceEntry.Report.Full(alex, VenueId("v1")))
        val proposal = p0.withToolResult(s1, setOf(tool)).complete(Recommendation(VenueId("v2")))
        assertAll(
            { assertEquals(setOf(budget.dot), proposal.basis.basisThrough(0)) },
            { assertEquals(setOf(budget.dot, tool.dot), proposal.basis.allDots) },
            { assertEquals(listOf(0 to Recommendation(VenueId("v1"))), proposal.intermediate) },
        )
    }

    @Test
    fun excludedInputIsAbsentFromBasis() {
        val (s0, _) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.Note(alex, VenueId("v2"), "quiet"))
        val (s1, budget) = s0.append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p = InputCapture.capture(RequestId("r1"), s1, exclude = { it is WorkspaceEntry.Note }).complete(Recommendation(null))
        assertEquals(setOf(budget.dot), p.basis.allDots)
    }

    @Test
    fun wireRoundTripPreservesBasisExactly() {
        val (s0, _) = Rga.empty<WorkspaceEntry>().append(WorkspaceEntry.PreferenceSet(alex, 50))
        val p = InputCapture.capture(RequestId("r1"), s0).complete(Recommendation(VenueId("v2")))
        assertEquals(p.basis, p.toEntry(alex).toProposal().basis)
    }
}
```

`toEntry`/`toProposal` convert between `Proposal` and `WorkspaceEntry.AgentProposal`. `toProposal` is the only other constructor path, and it is what M5's external adapter will have to go through.

- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement.** `capture` reads `snapshot.entries()` **once** from the immutable `Rga` value it was handed. That single read is the atomicity argument: snapshot and record derive from one value. It filters with `exclude` and keeps the selected `(id.dot, entry)` pairs as step 0. `withToolResult` appends a `StepBasis` holding only the new ids' dots, and it `require`s that each new id is present in the given log. `complete` builds the `Proposal` through the private constructor, which a nested class can reach.
- [ ] **Step 4: Add the positive control.** It lives in the test source set, so it can never ship. A `LeakyStamp.stamp(proposal, liveLog)` returns an `AgentProposal` whose basis is `liveLog.entries().map { it.first.dot }`, the bug the promise forbids. Add the property that Task 7 reuses, `fun basisUnchangedByLaterUpdates(stamp: (Proposal, Rga<WorkspaceEntry>) -> WorkspaceEntry.AgentProposal): Boolean`, and two tests:

```kotlin
@Test fun honestPathKeepsTheBasis() = assertTrue(basisUnchangedByLaterUpdates { p, _ -> p.toEntry(alex) })
@Test fun propertyDetectsTheLeakyStamp() = assertFalse(basisUnchangedByLaterUpdates(LeakyStamp::stamp))
```

`propertyDetectsTheLeakyStamp` is the evidence that the property can fail. **Revert-check:** temporarily make `toEntry` read the live log, confirm `honestPathKeepsTheBasis` goes red, and restore it. Note the result in the PR body.

- [ ] **Step 5: Run the tests and watch them pass.** Six tests. Count them in the XML.
- [ ] **Step 6: Commit and open a Draft PR** ("demo-workspace: type-shaped input capture (#2869 M1)").

---

### Task 7: Kuilt backend over Quilter, and assessment

**Files:**
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/Assessment.kt`
- Create: `demo/workspace/src/commonMain/kotlin/us/tractat/kuilt/demo/workspace/KuiltBackend.kt`
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/AssessmentTest.kt`
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/KuiltBackendTest.kt`

**Interfaces:**
- Consumes: Task 6's `Proposal`, `InputCapture` and `AgentProposal`, plus `Relevance`.
- Produces:
  - `sealed interface Verdict { data object Applicable; data class NeedsReview(val relevantMissing: Set<Dot>); data class Unknown(val undelivered: Set<Dot>) }`.
  - `Assessment.assess(p: Proposal, known: Rga<WorkspaceEntry>): Verdict`.
  - `class KuiltBackend(scope: CoroutineScope, faults: (ActorId) -> FaultProfile = { FaultProfile.Healthy }) : WorkspaceBackend`.

`assess` rules, in order:
1. `undelivered = p.basis.allDots.filterNot { known.causalDots().contains(it) || known.causalFloor().contains(it) }`. If it is non-empty, return `Unknown(undelivered)`: this replica cannot judge a basis it has not received.
2. `missing = known.entries().filter { (id, e) -> id.dot !in p.basis.allDots && e !is WorkspaceEntry.AgentProposal }`.
3. `relevant = missing.filter { Relevance.isRelevant(it.second, p.recommendation) }`. If it is non-empty, return `NeedsReview(relevant dots)`. Otherwise return `Applicable`.

Backend wiring: one Quilter per actor (`alex`, `sam`, `remote`) over `FaultyLoom(InMemoryLoom(), backgroundScope)`, created with `Rga.wireSerializer(WorkspaceEntry.serializer())` and `QuilterConfig(expectVirtualTime = true)`. Copy the wiring from `kuilt-quilter/src/commonTest/kotlin/us/tractat/kuilt/quilter/RgaGcCoordinator3PeerIntegrationTest.kt#wireRgaWithGc`, including `RgaGcCoordinator`, since Task 8 needs compaction. Mutate with `rep.apply(Patch(Rga.empty<WorkspaceEntry>().apply(op)))`, as that test does. `Partition(actor)` calls `partition()` on that actor's `FaultySeam`, and `Reconnect` calls `heal()`. Each step is followed by `testScheduler.advanceUntilIdle()`. Every local `Edit` is served (it counts in `outageActionsServed` when partitioned). A released proposal is appended to the host's log as an `AgentProposal`. It is presented on each actor when that actor's replica holds it, and at that point it is assessed against that actor's state: `shownAsApplicable = verdict == Applicable`, and a `NeedsReview` counts as one `humanPrompt`.

Because `KuiltBackend` needs a `TestScope` for its scheduler, `run` takes its scope from the constructor. Build the backend inside `runTest` with `backgroundScope`, and use an injected `advance: () -> Unit` (wired to `testScheduler::advanceUntilIdle`) passed as a constructor parameter, so production code never touches a test type.

- [ ] **Step 1: Write the failing assessment tests.**
  - `unreceivedBasisIsUnknownNotApplicable` (Review Focus 1): a proposal captured on a log with an element this `known` lacks.
  - `relevantMissingClosureNeedsReview`.
  - `irrelevantMissingNoteIsApplicable`.
  - `excludedHostInputCountsAsMissing` (Review Focus 5).
- [ ] **Step 2: Write the failing backend tests.**
  - `s3ClosureDuringInferenceIsFlaggedNotStale`: `staleTreatedAsCurrent == 0` and `humanPrompts >= 1`.
  - `s2OfflineBudgetEditIsServedLocally`: `outageActionsServed == outageActions`.
  - `s1IndependentEditsMergeWithoutPrompt`.
  - `unknownRequestReplyIsRejected` (Review Focus 4): an `AgentProposal` whose `request` has no open `Pending` on the host is not appended, and `run` records it as rejected. Add a `rejectedReplies: Int` to `RunResult` with a default of `0`, so the baseline stays source-compatible.
- [ ] **Step 3: Run them and watch them fail.** Then implement `Assessment`, then `KuiltBackend`.
- [ ] **Step 4: Run them and watch them pass.** Then run `--tests "*KuiltBackendTest*"` **alone** (the suite-ordering trap) and count the tests in the XML.
- [ ] **Step 5: Commit.**

---

### Task 8: Adversarial traces — duplicates, reorder, compaction, corrections

**Files:**
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/AdversarialTraceTest.kt`

Each test **asserts that its rig fired**. For example, `seam.framesDelayed > 0` for reorder, a non-zero `compactOpCount` or a raised `causalFloor()` for compaction, and a duplicate apply observed. An unreached rig is green by absence.

- [ ] **Step 1: Write the tests.**
  - `reorderedAndDuplicatedProposalKeepsBasis` (Review Focus 3): `FaultProfile.ReorderWindow` on `remote`, plus a second `apply` of the same `AgentProposal` patch. Expect exactly one `AgentProposal` visible, with basis equal to the originally captured `InputRecord`. Assert via `toProposal().basis == captured.basis`.
  - `compactedReportLeavesBasisReadable` (Review Focus 2): capture over a closure, remove the closure, and drive `RgaGcCoordinator` until the tombstone is purged (assert `tombstones` is empty). Then check that `assess` returns without throwing, that `basis.allDots` still contains the closure's dot, and that the verdict is **not** `Unknown`, because the dot is at or below `causalFloor()`.
  - `correctedReportIsANewIdentity`: `Closed(v1)` then `Reopened(v1)` have distinct dots. A proposal captured between them `NeedsReview` for the `Reopened` dot.
  - `sameFactTwoIdentities`: `alex` and `sam` both report `Closed(v1)`. A proposal whose basis holds only alex's dot is `NeedsReview` for sam's. The claim is about receipt of a report, not the fact.
  - `leakyStampFailsOverTheWire`: rerun Task 6's property through `KuiltBackend` with `LeakyStamp`, and assert it fails. This is the positive control at the replication layer.
- [ ] **Step 2: Run them.** If one fails for a reason other than a missing rig, stop. Treat it as a finding: record it in the PR body and ask the controller before changing semantics.
- [ ] **Step 3: Commit.**

---

### Task 9: Comparison, decision table, smallest contracts (M1-causality exit)

**Files:**
- Test: `demo/workspace/src/commonTest/kotlin/us/tractat/kuilt/demo/workspace/KuiltMeasurementTest.kt` (golden, same shape as Task 5)
- Modify: `demo/workspace/RESULTS.md`
- Create: `demo/workspace/CONTRACTS.md`

- [ ] **Step 1: Write the golden kuilt metrics** for S1–S7. Explain every cell before recording it.
- [ ] **Step 2: Fill `RESULTS.md`'s kuilt section.** Put the side-by-side table in, then score H0–H4 **against the criteria as merged in Task 1, unedited**. For each hypothesis write pass, stop, or no difference, with one sentence each.
- [ ] **Step 3: Write `CONTRACTS.md`** in three columns:
  - *existing primitive used*: `Rga.entries`, `RgaId.dot`, `causalDots`/`causalFloor`, `Quilter`, `RgaGcCoordinator`, `FaultyLoom`.
  - *missing canonical API*: anything hand-rolled here that a kuilt module should own. Candidates to confirm or reject include a public `Quilted.delivered(dot)` helper, since the spike spells the union by hand.
  - *application policy*: relevance, authority and presentation.

  Then state the trust boundary the traces actually establish, and list each unsupported shape with its explicit failure mode (for example, a scalar input has no identity by construction).
- [ ] **Step 4: Commit, ready the PR, and arm auto-merge.** Post the table and the H verdicts on #2869 as the M1-causality exit comment. Include the **Compose/host decision prompt** for M2, which is Iain's call.
