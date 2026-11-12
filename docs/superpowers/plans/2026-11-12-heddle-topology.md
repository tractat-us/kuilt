# Heddle Topology, diff and a core-only local engine: Implementation Plan

> **For agentic workers:** execute task by task under `worktree-dispatch`, one fresh worker per task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give heddle a checked `Topology` value, a deterministic lowering, a re-entrant `Topology.diff` that reads ownership from the ledger, and a single-writer `LocalHeddle` engine that runs the fair-share tree in one process (#3173, #3176). The engine leases one slot per pick, with time as the cost. It applies reloads per child, and moves open leases across a reweight. It persists its state, and it gives an admin status, explain and preview reads.

**Architecture:** Everything lands in `:kuilt-heddle-core`, package `us.tractat.kuilt.heddle`. Its dependency floor is `:kuilt-crdt` + `kotlinx-serialization-core`, enforced by #3167's `verifyHeddleCoreDependencies`. One process is the only writer, so there is no `Seam`, no replication, no Raft, and no `heddleStatic`/`heddleGoverned`. `Topology.diff` is pure and returns `TopologyStep`s, and `LocalHeddle.apply` is the interpreter for them.

**Tech Stack:** Kotlin Multiplatform (JVM, Android, iOS, macOS, wasmJs), kotlinx.serialization (format-agnostic), `:kuilt-crdt`, `:kuilt-test` (`assertAll`).

**Spec:** tractat-us/kuilt#3173 (`gh issue view 3173`), #3176, and `docs/heddle-design.md` §4 (renewable quanta), §5.1–5.3 and §11.1. The rulings below override the spec where they differ.

## Rulings

1. `diff` lives in `:kuilt-heddle-core`. The generation counter is supplied by the caller and floored by the ledger. No id carries a timestamp (#1713).
2. Runtime-child names are opaque. No kuilt code, test or doc names a consumer. There is no script front end and no format library: `Topology` is `@Serializable` and the consumer brings its own format.
3. **The front door is the single-writer `LocalHeddle` in core.** `heddleGoverned` remains a documented later option, not a task. `heddleStatic` is not used, because it pulls `:kuilt-core`, quilter, liveness and coroutines.
4. **D2:** `diff` is re-entrant. **D3:** a change of root throws.
5. **D3m (confirmed): `Topology` carries no mint plan, and supply is not part of the admin's view.** The engine tops up supply inside `acquire`/`extend` against a watermark. Total capacity sizes that watermark.
6. **D6:** `LocalHeddle` is not thread-safe and uses no atomicfu. An immutable `snapshot()` lets status and persistence readers avoid the caller's scheduling lock.
7. **D7:** reconcile by lease, not by executor. `reconcileLeases(live)` reports mismatches in both directions. The consumer completes each orphan at its elapsed cost. An orphan never blocks a reload.
8. **Q6:** one lease per slot. The consumer owns concurrency, so the engine has no slot gate and no executor view. It keeps only total capacity, for supply sizing. Task C runs before L1's API is frozen, because Task C is the evidence for that API.
9. **R1: a reweight or reparent moves open leases with it (Option A).** In one all-or-nothing step, the engine:
   - releases each open lease's reservation up the old path;
   - retires the old edge, which is now at outstanding 0;
   - activates the new edge;
   - reconciles where needed;
   - re-delegates and recaptures each lease down the new path.

   Charging happens only at `complete`, so nothing is lost. **The group's fairness history resets:** it restarts at its parent's front. The KDoc and the cookbook both say this. The ledger-level alternative (#3176 option c, a mutable weight on a stable edge) is **deferred**. Reopen it if the history reset or ledger growth hurts in practice.
10. **R2: cost is time.** A lease reserves an estimate, or `defaultReservation` when the estimate is unknown. It is extended in renewable quanta on overrun (design §4). `complete` charges the actual cost and returns the unused remainder. **Decided here:** when the actual cost is above the reservation, `complete` extends inline for the shortfall, charges the actual cost, and reports the overrun. A charge is never lost or refused. `PolicyConfig` is hidden. Each pick runs with `quantum = that lease's reservation`, so the deadline is `ev + r/w`, which is EEVDF by request length. `perChildOutstandingCap = Long.MAX_VALUE`, because the consumer owns concurrency. `sleeperCredit` is exposed as `LocalPolicy.sleeperCredit`.
11. **Ownership comes from the ledger,** not from `old`. `diff(new, ledger, generation)` owns every ledger edge whose id parses under the topology grammar and whose child lies under `new.root`. This deviates from #3173's `diff(old, new, ledger)` signature. It fixes an edge orphaned when a second change arrives mid-drain, and the case where `old` is gone after a restart.
12. **A rename or a move is a delete plus an add, and it loses history.** The docs say so.
13. **Out of this plan:** #3177 and #3178, which are filed separately. Task A (#3172) is done: it merged as #3175. Task 3 (`draft`) is independent and off the consumer's path.

**Dependencies:** #3171 (#3167) is about to merge. It makes `FairShareAllocator` public, with `demand: (AttachmentId) -> Demand`, makes `LedgerCell` public, and adds a cookbook route. Every task here starts after #3171 merges.

## Amendments after Task C (2026-11-12, settled with the user)

These override the task sections below where they differ.

1. **Task 1b: problems are addressed by index path, and every subtree is walked.** This overrules Task 1's merged behaviour (#3182), which skipped the subtree under a child whose own name was empty or duplicated. A `TopologyProblem`'s address is the list of indices from the root to the node it concerns. Names are carried for display only, so two siblings both named `batch` cannot share an address.
2. **Task L0: a ledger edge index lands before L1, as its own PR.** Task C's scale row measured `acquire` at 5k active + 50k retired edges: 42 ms on JVM and 962 ms on macOS debug, with retired edges alone making it 5–7× slower. L0 adds a parent → child-edges index, a child → inbound-edges index, and a **folded per-parent retired-outflow aggregate**. That aggregate is *not* a skip of retired children: a retired child that ever served has `issued − returned = spent > 0`, and `holdings(parent)` must keep subtracting it. Conservation pins it. L0 also cuts each operation down to one `piece`. It is measured against Task C's `LeaseScaleProbe` (`-Pheddle.scale.probe=true`), before and after.
3. **L1 inherits Task C's findings.**
   - Add `FairShareAllocator.pickChild(waiting, eligible)`, which makes one pick with separate waiting and eligible views.
   - Carry the reservation through `maximumUsefulGrant`, under a long-lived allocator with an unbounded quantum.
   - Port both fairness arms of `LeaseCharacterizationTest` onto `LocalHeddle`, and require identical `fingerprint()` strings, **before** deleting `LeasePrototype.kt` and that test. L1 inherits the 0.03 overall-share bar. The 0.15 window bar is not validated.
   - Seating a large newborn cohort is quadratic in `seatPatch`. Fix it in L1 or file it.
   - L2 sizes the mint chunk, since mint records grow linearly with service.
4. **Task E: `explain(leaf)` moves out of S2 into its own task, right after L1.** S2 keeps `preview` only.
5. **Task O1: `:kuilt-heddle-otel`, after S1** (#3184). It lives in a sibling module over core + `:kuilt-otel`, so core's dependency floor stays where it is. It records metrics per group path, a span per lease, and the hold reason (`WaitExplanation`) as a structured attribute, **emitted when a leaf's reason changes**.

## Verified in source (2026-11-12)

- **A single writer is already serialized, so local `mint` is sound.** This holds only while one process writes. Two processes would each keep their own ledger, and persistence would become last-writer-wins on the stored bytes. Excluding a second writer is the consumer's job.
- **Supply runs out as work is charged.** `complete` → `spendCaptured` bumps `leafSpent` permanently. Only `mint` creates supply, and `pick` returns `null` at zero holdings. Production code never calls `release`.
- **The fair ordering key is committed service.** `virtualService = grossVirtualService(base issued) − returned / w`. Delegation advances it, `release` moves it back, and spend does not touch it.
- **#3176 has a local repair.** A probe on `main` used the internal reweight after a spend (`holdings(acme) = −7`, `PersistentNegativeHoldings`). `relocationPatch(new, {old → baseFinalsOn(old)})` returned `Moved`. After that, `validate()` was empty, `minted` was unchanged, and a second call returned `Nothing`. For a leaf, the same call was `Moved` and neutral. The probe used one replica, and its scratch test is deleted.
- **A closing edge drains only by release or spend.** `retire` needs `outstanding == 0`, and no node exposes `release`. The engine releases before it retires.
- **`diff` and the engine need nothing outside core** (`allEdges`, `record`, `lifecycle`, `relocationPatch`, `baseFinalsOn`, `FairShareAllocator`).
- **`PolicyConfig` = (`quantum`, `perChildOutstandingCap = Long.MAX_VALUE`, `sleeperCredit = 0`)**, and `FairShareAllocator` takes `demand: (AttachmentId) -> Demand`.

## Dependency footprint

- Every task stays on `:kuilt-crdt` + `kotlinx-serialization-core`. JSON appears only in `commonTest`. Nothing goes above the floor. D6 removed the one candidate, atomicfu.
- **A config-only module is not warranted.** `Topology` and `TopologyDocument` need only `GroupId`, `Weight`, `AttachmentRecord` and serialization. A separate module would save `:kuilt-crdt` and the ledger classes, but no third-party dependency. Both floors come down to `kotlinx-serialization-core`.
- **The tension.** Both node front doors live in `:kuilt-heddle`, which carries `api` `:kuilt-core` and `:kuilt-raft` (and so `:kuilt-store`), plus quilter, liveness, coroutines, cbor, atomicfu and kotlin-logging. The local engine keeps the floor for scheduling, not just for config.

## Global Constraints

- Code goes under `kuilt-heddle-core/src/{commonMain,commonTest,commonSamples}/kotlin/us/tractat/kuilt/heddle/`. If a task seems to need a dependency, the worker stops and reports it rather than adding it.
- `explicitApi()`, no `!!`, no `test` prefix on test names, and `us.tractat.kuilt.test.assertAll` for multi-assert tests.
- A public API change runs `./gradlew :kuilt-heddle-core:updateKotlinAbi` and commits `kuilt-heddle-core/api/`. The PR body names the change.
- **The skill and cookbook move in the same PR as the API.**
  - The `kuilt-primitives` body is at **8,180 of 8,192 bytes**, and the description at 1,535 of 1,536.
  - Never touch `description:`. Extend the existing heddle index row, and trim the same number of bytes elsewhere in the body.
  - Route only to symbols that are on `main`.
  - Cookbook: one subsection, `### A fair-share tree in one process`, in `docs/agent-cookbook/heddle.md`, plus its row in `docs/agent-cookbook.md`.
  - Snippets are `<!-- verbatim from … -->` of a compiled sample.
  - Run `python3 .github/scripts/llms-txt.py` when `--check` goes red.
- Every zero-argument `@sample` has a `@Test` in `HeddleSamplesRunTest.kt`.
- No consumer is named. Test names look like `"c-7f3a"`, `"a/b"`, `"é#%@"`, `"#3"`.
- All output is totally ordered and target-independent. Sort every `Set`/`Map` view, `allEdges()` included. Seeded generators walk only `List`s they built (`forbidHashOrderedSeededDraw`).
- No clock, timestamp or `Random` in production code. Elapsed cost is a number the caller passes in. A generation is a `Long ≥ 0`.
- Gradle runs as `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-tem`, under `timeout 600`, with `--max-workers=6`.
- **Pre-merge:** `./gradlew clean && timeout 600 ./gradlew :kuilt-heddle-core:build --rerun-tasks --max-workers=6`, and the tasks show EXECUTED. For a docs PR, also run `./gradlew verifyDocCitations verifySampleLinks verifySamplesAreRun verifySkillBodyBudget verifySkillDescriptionBudget`.
- Claim each task with a Draft PR whose body says `Part of #3173` or `Part of #3176`, and close issues by hand. Commit messages are plain sentences, never "chore", and end with the Co-Authored-By line.

## Review Focus

1. **A reweight while leases are open.** A user expects every lease to survive, to be charged once at `complete`, and to land on the new path, with `validate()` empty at every step. Pinned by `reweightRehomesOpenLeasesAtomically` and `rehomeIsAllOrNothing` (Task L3).
2. **One slow group in a save that also changes others.** A user expects the other children to apply. Pinned by `oneDrainingChildDoesNotBlockAnother` (Task L3) and `unrelatedGroupInTheSameSaveApplies` (Task 4).
3. **Restart or reset with leases in flight.** A user expects a lease to be either still valid or rejected by name, and never to charge the wrong group. Pinned by `staleLeaseAfterResetIsNamed` and `restoreKeepsLeases` (Task P).
4. **A routine reload or drain.** A user expects the status to stay healthy, so that fail-open does not fire. Pinned by `drainingIsInformationalNotDegraded` (Task S1).
5. **Names that collide once flattened,** including a runtime child named like a declared one, and a child literally named `#3`. A user expects distinct ids and an unambiguous problem path. Pinned by `flatteningIsInjectiveOverAwkwardNames` (Task 2), `runtimeNamespaceCannotCollide` (Task 5), and `emptyNameProblemDoesNotCollideWithHashNames` (Task 1).

---

### Task 1: `Topology`, `TopologyDocument` and collected, path-addressed problems [after #3171]

**Files:** create `Topology.kt`, `TopologyDocument.kt`, `TopologyTest.kt` and `TopologySamples.kt` (`sampleTopologyFromDocument`). Modify `HeddleSamplesRunTest.kt`, the cookbook (start the subsection), the index row, the skill row (`Topology`), and the API dump.

**Interfaces (produced):**
```kotlin
/** What an admin writes: permissive, never throws on decode. Weights are text ("3", "3/2"). */
@Serializable public data class TopologyDocument(public val root: String, public val children: List<Node>) {
    @Serializable public sealed interface Node { public val name: String; public val weight: String }
    @Serializable @SerialName("group") public data class Group(override val name: String, override val weight: String = "1",
        public val children: List<Node> = emptyList(), public val runtimeChildren: String? = null) : Node   // marker weight text
    @Serializable @SerialName("lane") public data class Lane(override val name: String, override val weight: String = "1") : Node
}
/** The checked value: constructible only valid. */
@Serializable public data class Topology(public val root: String, public val children: List<Node>) {
    @Serializable public sealed interface Node { public val name: String; public val weight: Weight }
    @Serializable @SerialName("group") public data class Group(override val name: String,
        @Serializable(with = TopologyWeightSerializer::class) override val weight: Weight,
        public val children: List<Node>, public val runtimeChildren: RuntimeChildren? = null) : Node
    @Serializable @SerialName("lane") public data class Lane(override val name: String,
        @Serializable(with = TopologyWeightSerializer::class) override val weight: Weight = Weight.ONE) : Node
    @Serializable public data class RuntimeChildren(@Serializable(with = TopologyWeightSerializer::class) public val weight: Weight)
    public companion object {
        public fun from(document: TopologyDocument): TopologyParse
        public fun problems(root: String, children: List<Node>): List<TopologyProblem>
    }
}
public sealed interface TopologyParse {
    public data class Valid(public val topology: Topology) : TopologyParse
    public data class Invalid(public val problems: List<TopologyProblem>) : TopologyParse
}
/** [parent] = the names from the root to the containing group; [childIndex] = the child's position, or null when the problem is the group at [parent] itself. */
public data class TopologyProblem(public val parent: List<String>, public val childIndex: Int?, public val kind: Kind, public val detail: String) {
    public enum class Kind { EMPTY_NAME, DUPLICATE_NAME, EMPTY_GROUP, INVALID_WEIGHT }
}
public class TopologyException(public val problems: List<TopologyProblem>) : IllegalArgumentException(/* every problem, one per line */)
public object TopologyWeightSerializer : KSerializer<Weight>   // a STRING: "3" or "3/2"
```
- `Topology.init` throws `TopologyException` whenever `problems` is non-empty.
- `from` collects **every** problem, weight text included, and never throws. A config page renders `Invalid.problems` keyed by `(parent, childIndex)`.
- The index is a separate field, so a child literally named `#3` cannot be confused with an index.
- There is no mint field (D3m).

- [ ] **Step 1: Write the failing tests.**
  - `fromCollectsEveryProblem`: a duplicate `batch` under `acme`, an empty name at index 1 under `hobby`, an empty group `idle`, and `weight: "0"` on `x`. `Invalid.problems` equals exactly those four, in walk order, each with its `parent` and `childIndex`.
  - `emptyNameProblemDoesNotCollideWithHashNames`: a sibling named `"#1"` beside an empty name at index 1. The problem is `childIndex = 1`, and `"#1"` raises no problem.
  - `weightTextAcceptsWholeAndRatio`: `"3/2"` → `Weight.of(3, 2)`. `"0"`, `"-1"`, `"x"` and `"1/0"` each give `INVALID_WEIGHT`.
  - `fromValidDocumentEqualsHandBuiltTopology`.
  - `constructingInvalidTopologyThrowsWithAllProblems`.
  - `topologyJsonRoundTrips`.
  - `decodingAnInvalidTopologyThrows` (the strict type, through the read path).
  - `acceptsAMarkedGroupWithNoDeclaredChildren`.
  - `sameNameAtDifferentDepthsIsAllowed`.
- [ ] **Step 2: Run them and watch them fail.** `… ./gradlew :kuilt-heddle-core:jvmTest --tests "*TopologyTest*"`.
- [ ] **Step 3: Implement.** The KDoc opens with "the tree you write in a config file", and says a rename or move is a delete plus an add.
- [ ] **Step 4: Expect PASS.** Then the sample, docs, API dump, docs gates and pre-merge verification. Commit "heddle-core: a Topology value and a permissive document form with collected problems (#3173)".

---

### Task 2: Lowering, ids, the runtime namespace and the generation floor [after Task 1]

**Files:** create `TopologyLowering.kt`, `TopologyLoweringTest.kt` and `TopologyTestSupport.kt`. Modify the cookbook (one sentence), the skill row (`Topology.lower`), and the API dump.

**Interfaces (produced):**
- `public fun Topology.groupId(vararg path: String): GroupId`, and `public fun Topology.runtimeChildId(group: List<String>, name: String): GroupId`.
- `public fun Topology.lower(generation: Long = 0L): List<AttachmentRecord>`, sorted by id.
- Internal:
  - `escapeSegment`: every UTF-8 byte outside `[A-Za-z0-9._~-]` becomes `%XX`.
  - A declared child is `"<parent>/<escaped>"`. **A runtime child is `"<parent>/!<escaped>"`.** `!` never survives escaping, so the two namespaces cannot collide.
  - `attachmentIdOf(child, weight, generation)` = `"${child.value}@${n}:${d}#$generation"`.
  - `generationOf(id): Long?` parses only the whole grammar.
  - `pathOf(group: GroupId): ParsedPath?` = (raw names, `runtime: Boolean`). It is the inverse of the encoding, which lets status name a group the topology no longer declares.
  - `EntitlementLedger.generationFloor()` takes the maximum over `allEdges()`, retired edges included, or `-1`.
  - `effectiveGeneration(supplied, ledger) = max(supplied, floor + 1)`.
- Test support: `bootFrom(t, generation)`, and `EntitlementLedger.activeTriples()`.

- [ ] **Step 1: Write the failing tests.**
  - `lowerIsSortedAndGolden`: the §11.1 tree at generation 0 gives exactly `["root/acme/batch@1:1#0", "root/acme/interactive@3:1#0", "root/acme@3:1#0", "root/hobby/default@1:1#0", "root/hobby@1:1#0"]`.
  - `flatteningIsInjectiveOverAwkwardNames`: `"a/b"`, `"a%2Fb"`, `"a"`, `"é"`, `"x@1:1#0"`, `"!x"`, a nested `a → b`, and runtime child `"x"` beside declared `"x"`. Every id is distinct.
  - `pathOfInvertsGroupId`, over the same names.
  - `generationOfParsesOnlyTheGrammar`.
  - `floorSeesRetiredEdges`.
  - `aWeightChangeChangesOnlyThatId`.
  - `bootedTopologyIsValid`.
- [ ] **Step 2: Run them and watch them fail.** Then implement, and expect PASS on `jvmTest` and on `timeout 600 ./gradlew :kuilt-heddle-core:allTests --max-workers=6`.
- [ ] **Step 3: Docs, API dump and pre-merge verification.** Commit "heddle-core: lower a Topology to deterministic, generation-carrying ids (#3173)".

---

### Task 3: `draft { }` [after Task 1; independent, off the consumer's path]

`draft(root) { group(name, weight) { lane(...); runtimeChildren(weight) } }` returns a `Topology`. Problems surface as `TopologyException`. A second `runtimeChildren` in one group throws. Add the `@sample` (`sampleTopologyDraft`) and a cookbook mention. Tests: `draftEqualsTheValueItDescribes` (§11.1) and `draftProblemsArePathAddressed`. Same verification and docs rules as above. Commit "heddle-core: the draft builder over Topology (#3173)".

---

### Task C: Capacity and scale evidence, before L1's API is frozen [after #3171; parallel with Tasks 1, 2]

**Files:** create `kuilt-heddle-core/src/commonTest/kotlin/us/tractat/kuilt/heddle/LeasePrototype.kt` (a test-only prototype of R2's acquire/extend/complete over `EntitlementLedger` + `FairShareAllocator`) and `LeaseCharacterizationTest.kt`.

- [ ] **Step 1: Build the prototype.**
  - Each acquire runs one pick per level, from the root, among eligible children. Waiting children feed the wake detection.
  - It delegates exactly the reservation down the chosen path.
  - `extend` delegates more down the same path.
  - `complete(actual)` extends any shortfall, spends `actual`, and releases the rest.
  - Top-up happens on a watermark.
- [ ] **Step 2: Pin the fairness evidence.** Run 10,000 seeded steps. Concurrency churns between 1 and 8 in-flight leases, leaf weights are `3:1:1`, reservations are 1..5, and actual costs are 0..8 (so they overrun). Waiting sets are intermittent, and eligible sets are filtered by a seeded label. Pin each leaf's share of actual service, the maximum deviation over any 500-step window, the number of mint records, and the minimum `unspent`. Assert the loop count. Run under `allTests`.
- [ ] **Step 3: Scale row.** Build 5,000 runtime-style children and 50,000 retired edges directly through ledger mutators. Assert the counts, and **record** (do not assert) the per-`acquire` time on JVM, `macosArm64` and `wasmJs` in the PR body. The ledger's scans are `O(edges)` per lineage walk, so this row decides whether L1 needs an index. A timing assertion would be load-dependent, so there is none.
- [ ] **Step 4: Post the numbers to #3173.** If fairness or scale fails, stop before L1 and re-plan. Commit "heddle-core: lease prototype and capacity/scale characterization (#3173)".

---

### Task 4: `Topology.diff`, with ownership read from the ledger [after Task 2]

**Files:** create `TopologyDiff.kt`, `TopologyDiffTest.kt` and `TopologyDiffPropertyTest.kt`. Modify `TopologyTestSupport.kt` (`applyAll`), `TopologySamples.kt` (`sampleTopologyDiff`), the cookbook, the skill row (`Topology.diff`), and the API dump.

**Interfaces (produced):**
```kotlin
public sealed interface TopologyStep {
    public val child: GroupId
    public data class Prepare(public val record: AttachmentRecord) : TopologyStep
    public data class Activate(public val edge: AttachmentId, override val child: GroupId) : TopologyStep
    public data class Close(public val edge: AttachmentId, override val child: GroupId) : TopologyStep
    public data class Retire(public val edge: AttachmentId, override val child: GroupId) : TopologyStep
    public data class Reconcile(override val child: GroupId) : TopologyStep
}
public fun Topology.Companion.diff(new: Topology, ledger: EntitlementLedger, generation: Long): List<TopologyStep>
internal fun EntitlementLedger.reconcileLocally(child: GroupId): Relocation   // relocationPatch(live, retired.associateWith { baseFinalsOn(it) })
```
- **Owned edges** are every ledger edge whose id parses under the grammar and whose child's `pathOf` starts at `new.root`. `require` that the ledger's minted root, if any, equals `new.root`'s `GroupId` (D3).
- **Desired:** each declared node, at its weight. Runtime-namespace children are left untouched here, and Task 5 adds them.
- **Per child, unchanged from the last plan:**
  - Adopt an ACTIVE edge, else the highest-generation PREPARED edge, else `Prepare` at `effectiveGeneration`.
  - `Close` the other ACTIVE edges, `Retire` the other CLOSING ones, and `Activate` the target.
  - A non-desired child gets `Close`/`Retire`.
  - A `Reconcile` goes to an internal group whose retired inbound strand is not `Nothing`, or whose retirement in this list has base issuance.
- **Order:** prepares top-down; fresh activates; closes; retires bottom-up; replacement activates top-down; reconciles top-down. Each step names its `child`, so an interpreter can stop per child (Task L3).
- **Test support `applyAll`:** applies per child. A refusal skips the rest of that child's steps, and the retires of its ancestors in the same removal. It returns `(ledger, refused children)`.

- [ ] **Step 1: Write the failing scenario tests.**
  - `addingALaneIsPrepareThenActivate`.
  - `removingAGroupClosesTheSubtreeAndRetiresBottomUp`.
  - `aLeafReweightIsPrepareCloseRetireActivate`.
  - `internalReweightEmitsReconcileAndEndsClean`: the probe scenario. After `applyAll`, `validate()` is empty.
  - `revertNeverReusesAnId`: 3→4→3, ending at `"root/acme@3:1#2"`.
  - `diffOfAReachedStateIsEmpty`.
  - `diffAfterPartialApplyReturnsOnlyTheRemainder`.
  - `aPrepareThatLandedOutOfBandIsAdopted`.
  - `differentRootsAreRejected`.
  - `diffFromEmptyLedgerReplaysTheTopology`.
  - `diffStepOrderIsGolden`.
  - **Fable 2 set:**
    - `secondChangeMidDrainLeavesNoOrphan`: remove acme while it has outstanding units, then re-add it at w5 before the drain completes. `diff` owns both old edges, and after the drain plus `applyAll` there is exactly one live edge.
    - `revertMidDrainConverges`.
    - `unrelatedGroupInTheSameSaveApplies`: acme is blocked, and hobby's change still applies.
    - `renameIsDeleteThenAdd`: `acme` → `acme2` closes `acme` and prepares `acme2`. The history stays with the old edges.
    - `diffWithoutPriorTopologyAfterRestart`: there is only a ledger and `new`, and the result matches what the version with an `old` would have given.
- [ ] **Step 2: Write the property tests.** Use 200 seeds, trees of depth ≤ 3 and fan-out ≤ 3, names from `["a","b","c","default"]`, and weights 1..4. Assert the loop count.
  - `applyingTheDiffMatchesBootingTheTarget`.
  - `generationsNeverCollideAcrossAChain`.
  - `withTrafficTheDiffStillEndsClean`: delegate and spend randomly, drain by hand, and `validate()` is empty.
- [ ] **Step 3: Run them and watch them fail.** Then implement, and expect PASS on JVM and `allTests`. Read the target rows together.
- [ ] **Step 4: Mutation checks, recorded in the PR.**
  - Swap the retire and replacement-activate phases.
  - Use `generation` without the floor.
  - Drop adoption.
  - Drop Reconcile.
  - Derive ownership from `new` alone, so `secondChangeMidDrainLeavesNoOrphan` must red.

  Undo each with a WIP commit.
- [ ] **Step 5: Sample, docs, API dump and pre-merge verification.** Commit "heddle-core: Topology.diff, re-entrant, ownership from the ledger (#3173, #3176)".

---

### Task 5: Runtime children [after Task 4]

`Topology.attachRuntimeChild(group, name, ledger, generation)` returns `[Prepare, Activate]`, `[Activate]` or `[]` at the marker's weight. `detachRuntimeChild(group, name, ledger)` returns `[Close, Retire]`, `[Retire]` or `[]`. Both live in the `!` namespace (Task 2), so **a name never collides with a declared child, and nothing on the build path throws for it**. They throw only when the group is unmarked or the name is empty.

In `diff`, owned runtime children under a group that is still marked are desired at `new`'s marker weight. A changed weight regenerates them, and a removed marker closes them.

Tests:
- `attachIsIdempotent`.
- `runtimeNamespaceCannotCollide` (declared `"x"` and runtime `"x"` coexist).
- `runtimeChildNamesAreOpaque`.
- `reattachAfterDetachMintsAFreshGeneration`.
- `diffLeavesRuntimeChildrenAlone`.
- `diffClosesRuntimeChildrenWhenTheMarkerGoes`.
- `diffRegeneratesRuntimeChildrenWhenTheMarkerWeightChanges`.
- Extend the generator.

Same verification and docs rules. Commit "heddle-core: runtime children in their own namespace (#3173)". After merge, tick #3173's "Done when" boxes.

---

### Task L1: `LocalHeddle`, leases with time as the cost [after Task C and L0; see Amendments 2–3]

This is one of the two riskiest tasks. Lift Task C's prototype, adjusted by its findings.

**Files:** create `LocalHeddle.kt` and `LocalHeddleTest.kt` (plus `LocalHeddlePropertyTest.kt`). Modify `FairShareAllocator.kt` (add `pickChild`), the cookbook, the skill row (`LocalHeddle`), and the API dump. Delete `LeasePrototype.kt`.

**Interfaces (produced):**
```kotlin
public data class LocalPolicy(public val defaultReservation: Long, public val sleeperCredit: Long = 0L)
public class LocalHeddle private constructor(/* … */) {
    public val root: GroupId
    public fun snapshot(): LocalHeddleState                    // immutable; safe to read without the caller's lock (D6)
    public fun acquire(waiting: Set<GroupId>, eligible: Set<GroupId>, reservation: Long? = null): AcquireOutcome
    public fun extend(lease: LeaseId, amount: Long): LeaseOutcome
    public fun complete(lease: LeaseId, actualCost: Long): LeaseOutcome
    public fun cancel(lease: LeaseId): LeaseOutcome
    public companion object { public fun fresh(root: GroupId, policy: LocalPolicy, epoch: Long): LocalHeddle }
}
public data class LeaseId(public val epoch: Long, public val seq: Long)
public data class Lease(public val id: LeaseId, public val leaf: GroupId, public val reserved: Long)
public data class LocalHeddleState(public val ledger: EntitlementLedger, public val leases: List<Lease>, public val epoch: Long)
public sealed interface AcquireOutcome {
    public data class Acquired(public val lease: Lease) : AcquireOutcome
    public data object NothingEligible : AcquireOutcome
    public data object SupplyExhausted : AcquireOutcome
}
public sealed interface LeaseOutcome {
    public data class Done(public val charged: Long, public val overrun: Long) : LeaseOutcome  // extend: charged = 0
    public data object Unknown : LeaseOutcome
    public data class Stale(public val currentEpoch: Long) : LeaseOutcome
}
// FairShareAllocator:
public fun pickChild(parent: GroupId, ledger: EntitlementLedger, waiting: (AttachmentId) -> Demand, eligible: (AttachmentId) -> Demand, reservation: Long): Pair<EntitlementLedger, Grant>?
```
- The writer's `ReplicaId` is fixed and internal (`"local"`). `MintId`s are `"local#<k>"`.
- **`acquire`**:
  - `require(eligible ⊆ waiting)`.
  - Descend from the root, and at each group call `pickChild`. `waiting` feeds the seat and wake step. `eligible` defines the candidates. `PolicyConfig(quantum = reservation, perChildOutstandingCap = Long.MAX_VALUE, sleeperCredit)` (R2).
  - Delegate `reservation` down the chosen path.
  - **Compute it all on a local ledger value, and swap it in once** (all-or-nothing). Only the allocator's wake memory may advance on a failed acquire, and the KDoc says so.
- **`extend`** delegates `amount` more down the lease's path. It is not a new pick.
- **`complete`** extends inline for any `actual > reserved`, spends `actual`, releases the remainder, and returns `Done(charged, overrun)`. **`cancel`** releases everything.
- A `LeaseId` from another epoch gets `Stale`. An unknown id gets `Unknown`. Neither throws.
- **Failure surface (KDoc):** `IllegalArgumentException` for bad arguments, `ArithmeticException` on overflow, and `IllegalStateException` for a policy bug (#1737). Nothing suspends or times out.
- Supply top-up arrives in L2. Until then, `fresh` + a test-only mint funds the tests.

- [ ] **Step 1: Write the failing tests.**
  - `weightedLeasesFollowTheWeights` (one level, `3:1`, 400 cycles, 300 ± 1 / 100 ± 1).
  - `twoLevelTreeWithConcurrentLeases`: `k ∈ {1,4,16}` in flight, shares within Task C's pinned tolerance.
  - `intermittentWaitingSetsDoNotGrantIdleCredit`.
  - `filteredEligibleSetsStillFeedWakeDetection`: a leaf that is waiting but ineligible is not treated as a fresh waker when it becomes eligible.
  - `differentialAgainstSchedule`: one level, equal reservations, everything waiting and eligible. `n` acquires choose the same leaf sequence as `FairShareAllocator.schedule`'s grants with `quantum = reservation` on the same ledger.
  - `overrunCompleteExtendsAndCharges`, and `unusedReservationIsReturned`.
  - `staleAndUnknownLeasesAreNamed`.
  - `acquireIsAllOrNothing`: when the descent fails (a leaf is eligible but no live lineage reaches it), `snapshot().ledger` is unchanged.
  - `newbornLeafIsSeatedNotFavoured`.
  - `exhaustedSupplyIsNamed`.
  - `snapshotIsImmutable`.
- [ ] **Step 2: Write `conservationHoldsUnderRandomLeaseSequences`.** 200 seeds of random acquire/extend/complete/cancel. Assert `Σ holdings + Σ open reserved + spent == minted` and `validate()` empty after every operation, and assert the loop count.
- [ ] **Step 3: Run them and watch them fail.** Then implement, and expect PASS on JVM and `allTests`. The existing `FairShareAllocator` tests stay green, and so does `timeout 600 ./gradlew :kuilt-heddle:build --max-workers=6` (`HeddleNode` uses the allocator).
- [ ] **Step 4: Mutation checks, recorded in the PR.**
  - Skip the seat step in `pickChild`: `newbornLeafIsSeatedNotFavoured` reds.
  - Feed `eligible` to wake detection: `filteredEligibleSetsStillFeedWakeDetection` reds.
  - Skip the remainder release: conservation and `unusedReservationIsReturned` red.
  - Write the ledger mid-descent: `acquireIsAllOrNothing` reds.
- [ ] **Step 5: Sample (`sampleLocalLease`), cookbook, API dump and pre-merge verification.** Commit "heddle-core: LocalHeddle, one lease per slot with time as the cost (#3173)".

---

### Task L2: Capacity, supply top-up, and lease reconcile [after L1]

**Interfaces (produced, on `LocalHeddle`):**
```kotlin
public data class SupplyPolicy(public val lowWatermarkSlots: Long, public val targetSlots: Long)   // in LocalPolicy
public fun reconcileCapacity(totalSlots: Long)                       // idempotent; sizes the watermark only
public fun reconcileLeases(live: Set<LeaseId>): LeaseReconciliation
public data class LeaseReconciliation(public val orphaned: List<Lease>, public val unknown: Set<LeaseId>)   // engine-only, consumer-only
```
- **Top-up runs inside `acquire` and `extend`.** Let `unspent = mintedTotal − leafSpentTotal`. When `unspent < totalSlots × defaultReservation × lowWatermarkSlots`, mint up to `targetSlots`. So supply cannot run out between periodic calls. `reconcileCapacity` only stores the total, and a repeated value changes nothing.
- `reconcileLeases` reports and changes nothing. The consumer completes each orphan at its elapsed cost.

Tests:
- `reconcileCapacityIsIdempotent`.
- `topUpInsideAcquireOnlyBelowTheWatermark`.
- `supplyNeverRunsOutBetweenReconciles` (10,000 leases, `reconcileCapacity` called once).
- `mintRecordsGrowPerRefillNotPerAcquire`.
- `reconcileLeasesReportsBothDirections`.
- `orphanCompletedAtElapsedCostCharges`.

Same rules. Commit "heddle-core: LocalHeddle sizes and tops up supply, and reconciles leases (#3173)".

---

### Task L3: `apply`, per child, moving open leases across a reweight [after Task 4 and L1]

This is the riskiest task.

**Interfaces (produced):**
```kotlin
public fun apply(steps: List<TopologyStep>): ApplyResult
public data class ApplyResult(public val applied: List<TopologyStep>, public val refusals: List<StepRefusal>)
public sealed interface StepRefusal {
    public val child: GroupId
    public data class Draining(override val child: GroupId, public val edge: AttachmentId, public val outstanding: Long, public val blockingLeases: List<Lease>) : StepRefusal
    public data class Refused(override val child: GroupId, public val step: TopologyStep, public val reason: String) : StepRefusal
}
public companion object { public fun fromTopology(topology: Topology, policy: LocalPolicy, epoch: Long): LocalHeddle }   // root = topology.groupId()
```
- **Per child:** a refusal stops that child, and the retires of its ancestors in the same removal. Every other child continues.
- **A replacement** is a child whose steps hold a `Retire(old)` followed by an `Activate(new)`. It runs **as one all-or-nothing transition on a local ledger value** (R1):
  1. Release each open lease's reservation up the old path.
  2. Release idle holdings bottom-up.
  3. `retire(old)`, which is now at outstanding 0.
  4. `activate(new)`.
  5. `Reconcile`, if listed.
  6. Re-delegate each lease's reservation down the new path and recapture its path.
  7. Swap the ledger in.

  Any failure leaves the state untouched and yields `Refused`.
- **A plain removal with open leases** yields `Draining` for that child only. It finishes once those leases complete. Orphans are completed through `reconcileLeases`, so an orphan never blocks the rest of the reload (D7).
- **KDoc and cookbook:** a reweighted or reparented group restarts at its parent's front, and its fairness history resets (R1).

- [ ] **Step 1: Write the failing tests.**
  - `reweightRehomesOpenLeasesAtomically`: three leases are open under acme, and acme is reweighted 3→4. All three leases are still open, their captured paths run through the new edge, `complete` charges each exactly once, `validate()` is empty, and `holdings(acme) ≥ 0` throughout.
  - `rehomeIsAllOrNothing`: force a failing step (a corrupt record via test support), and the snapshot is unchanged.
  - `internalReweightAfterSpendEndsClean`.
  - `oneDrainingChildDoesNotBlockAnother`.
  - `removalWithOpenLeaseIsDrainingThenCompletes`.
  - `orphanUnderRemovedGroupDoesNotBlockReload`.
  - `reweightRestartsAtTheParentsFront` (pins the R1 reset).
  - `reapplyingTheSameDiffIsANoOp`.
  - `fromTopologyMatchesBootingTheTarget`.
- [ ] **Step 2: Re-run Task 4's properties through the real interpreter (Fable 8).** `applyingTheDiffMatchesBootingTheTarget` and `withTrafficTheDiffStillEndsClean` run through `LocalHeddle.apply`, with 0–3 leases open across random leaves before each diff. Afterwards every open lease is still completable, and conservation holds.
- [ ] **Step 3: Run them and watch them fail.** Then implement, and expect PASS on JVM and `allTests`.
- [ ] **Step 4: Mutation checks.**
  - Re-delegate before the retire: `reweightRehomesOpenLeasesAtomically` reds, with `DualActiveInbound` or negative holdings.
  - Write mid-transition: `rehomeIsAllOrNothing` reds.
  - Stop globally on the first refusal: `oneDrainingChildDoesNotBlockAnother` reds.
- [ ] **Step 5: Sample (`sampleLocalReload`), cookbook (the R1 reset, and rename = delete + add), API dump and pre-merge verification.** Commit "heddle-core: LocalHeddle applies reloads per child and moves open leases across a reweight (#3176)". Comment on #3176 that the local engine repairs it, and that option (c) is deferred with the R1 reopen trigger.

---

### Task P: Persistence [after L2 and L3]

**Interfaces (produced):**
```kotlin
@Serializable public data class LocalHeddleSnapshot(
    public val ledger: EntitlementLedger, public val leases: List<PersistedLease>,
    public val epoch: Long, public val nextSeq: Long, public val totalSlots: Long, public val lastApplied: Topology?,
)
public fun LocalHeddle.persist(): LocalHeddleSnapshot          // from snapshot(); never holds the caller's lock
public fun LocalHeddle.Companion.restore(snapshot: LocalHeddleSnapshot, policy: LocalPolicy, epoch: Long): LocalHeddle
```
- `restore` keeps every lease with its own epoch, and its old `LeaseId`s stay valid.
- **New leases take the injected `epoch`, and the call does `require(epoch > snapshot.epoch)`.** The epoch is a persisted, monotonic boot counter, the same discipline `heddleGoverned`'s `epoch` uses. Restoring one snapshot twice therefore cannot reissue an id from the first run.
- `fromTopology` (reset) starts a new epoch, so every older `LeaseId` is answered `Stale`.

Tests:
- `snapshotRoundTripsThroughJson`.
- `restoreKeepsLeases` (complete after restore charges the same leaf).
- `staleLeaseAfterResetIsNamed`.
- `doubleRestoreCannotReissueAnId`.
- `restoreRejectsANonIncreasingEpoch`.
- `lastAppliedSurvivesRestore`.

Same rules. Commit "heddle-core: LocalHeddle persists ledger, leases, epoch and last topology (#3173)".

---

### Task S1: Status and health [after Tasks 5 and L3]

**Interfaces (produced):**
```kotlin
public fun LocalHeddle.status(topology: Topology): TopologyStatus      // reads snapshot()
public data class TopologyStatus(public val nodes: List<NodeStatus>, public val conflicts: List<LedgerConflict>,
    public val issues: List<StatusIssue>) { public val healthy: Boolean /* no DEGRADED issue */ }
public data class NodeStatus(public val path: List<String>, public val runtime: Boolean, public val declared: Boolean,  // false = removed but still draining
    public val group: GroupId, public val configuredWeight: Weight?, public val configuredShare: Rational?,
    public val observedShare: Rational?, public val inFlight: Int, public val edges: List<EdgeStatus>)
public data class EdgeStatus(public val edge: AttachmentId, public val weight: Weight, public val lifecycle: Lifecycle, public val outstanding: Long)
public sealed interface StatusIssue {
    public val severity: Severity
    public enum class Severity { DEGRADED, INFO }
    public data class Conflicts(public val conflicts: List<LedgerConflict>) : StatusIssue      // DEGRADED
    public data object SupplyExhausted : StatusIssue                                            // DEGRADED: acquire cannot answer
    public data class Draining(public val path: List<String>, public val edge: AttachmentId, public val outstanding: Long, public val blockingLeases: List<Lease>) : StatusIssue  // INFO
    public data class StrandPending(public val path: List<String>) : StatusIssue               // INFO
    public data class Unowned(public val edges: List<AttachmentId>) : StatusIssue              // INFO
}
```
Supply is not shown to the admin (D3m). It appears only as a `SupplyExhausted` issue. `nodes` includes removed groups that are still draining, named through `pathOf`. `inFlight` counts leases per leaf.

Tests:
- `statusListsGroupsWeightsAndShares` (exact `3/4` and `1/4`).
- `removedGroupStillDrainingIsListed`.
- `drainingCarriesPathOutstandingAndBlockingLeases`.
- `drainingIsInformationalNotDegraded`.
- `conflictsAreDegraded`.
- `inFlightCountsPerLeaf`.

Same rules. Commit "heddle-core: LocalHeddle status with severity-graded issues (#3173)".

---

### Task S2: `explain(leaf)` and the diff preview [after S1; `explain` moves to Task E, see Amendment 4]

**Interfaces (produced):**
```kotlin
public fun LocalHeddle.explain(leaf: GroupId, waiting: Set<GroupId>, eligible: Set<GroupId>): WaitExplanation
public sealed interface WaitExplanation {
    public data object NotWaiting : WaitExplanation
    public data object NotEligible : WaitExplanation
    public data class NoLiveLineage(public val path: List<String>) : WaitExplanation       // removed or mid-reshape
    public data class BehindAtLevel(public val parent: List<String>, public val nextChild: List<String>,
        public val leafSideVirtualService: Rational, public val front: Rational) : WaitExplanation  // the first level where another child is ahead
    public data object WouldBePickedNext : WaitExplanation
}
public fun LocalHeddle.preview(new: Topology): TopologyPreview          // pure; runs diff, applies nothing
public data class TopologyPreview(public val changes: List<PlannedChange>)
public data class PlannedChange(public val path: List<String>, public val kind: Kind, public val historyResets: Boolean,
    public val leasesToRehome: Int, public val wouldDrain: Boolean) { public enum class Kind { ADD, REMOVE, REWEIGHT, RUNTIME_CLOSE, RUNTIME_REWEIGHT } }
```
Tests:
- `explainNamesTheLevelThatIsAhead`.
- `explainAgreesWithAcquire`: when `WouldBePickedNext`, the next `acquire` picks it.
- `previewMatchesApply`: every change in the preview corresponds to what `apply` does.
- `previewMarksRenameAsRemovePlusAdd`.
- `previewAppliesNothing`.

Same rules. Commit "heddle-core: explain a waiting leaf and preview a reload (#3173)".

---

## Consumer-side, on record (no kuilt task)

- **YAML or another format.** Decode `TopologyDocument`, call `Topology.from`, and render `Invalid.problems` by `(parent, childIndex)`.
- **Rendering.** Render `status`, `explain` and `preview`. Fail open on `!status.healthy`, or on an exception from Task L1's failure surface.
- **Fallback.** The disable switch and the fallback scheduler.
- **Concurrency and capacity.** Call `acquire` once per free slot. Call `reconcileCapacity(total)` on each notification and on the periodic tick. Call `reconcileLeases(live)` and complete orphans at their elapsed cost.
- **Persistence.** Persist `persist()` bytes, keep a monotonic boot epoch, and enforce a single writer.
- **Reset.** Reset is `fromTopology` with a new epoch, after which the consumer tells executors that old leases are void.

## Later option: `heddleGoverned` (documented, not planned)

`diff`'s steps map onto `GovernedHeddleNode` verbs, and `Reconcile` maps to `reconcile(child)`.
- **Reconcile acks.** Reconcile opens quiesce barriers itself, and is usually refused until the acks land. `pendingAcks` decides when to retry. Every enrolled replica holds it hostage, so churning executors must never be enrolled replicas.
- **Timeouts and `Indeterminate`.** A timeout throws `TimeoutCancellationException`, and the act may still commit. `Indeterminate` means the same. Until `enroll(self)` applies, the node answers `GateClosed`.
- **No compaction.** The log is never compacted, so every periodic mint is a permanent log entry.
- **No release.** The node exposes no `release`, and R1's lease re-homing would need one.
- **Footprint.** The dependency floor is given up.

## Dispatch summary

| Task | Depends on | Parallel with | Notes |
|---|---|---|---|
| 1 | #3171 | C | |
| C | #3171 | 1, 2 | evidence; stop and re-plan if fairness or scale fails |
| 2 | 1 | C, L1 | |
| 3 | 1 | anything | independent, off the consumer's path |
| 4 | 2 | L1, L2 | |
| 1b | 1 | 2, 3, C | index-path problem addresses (Amendment 1) |
| L0 | C | 2, 3, 1b, 4 | ledger edge index + folded retired outflow (Amendment 2) |
| L1 | C, L0 | 2, 4 | riskiest (with L3) |
| E | L1 | L2, L3, 5 | `explain(leaf)`, split out of S2 (Amendment 4) |
| L2 | L1 | 4, 5 | |
| 5 | 4 | L2, L3 | |
| L3 | 4, L1 | 5, L2 | riskiest |
| P | L2, L3 | S1 | |
| S1 | 5, L3 | P | |
| S2 | S1 | P, O1 | `preview` only |
| O1 | S1 | S2, P | `:kuilt-heddle-otel` (#3184) |

Task A (#3172) was dropped from the plan: it merged as #3175.
