# RGA Positional Reroot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `RgaOp.Compact` to carry each GC'd element's predecessor position so that `computeSequence` can reattach surviving successors to their nearest surviving ancestor instead of rerooting to `HEAD`, eliminating the eviction-orphan reorder (`[J, M]` → `[M, J]`).

**Architecture:** `Compact.ids: Set<RgaId>` becomes `Compact.positions: Map<RgaId, RgaId>` (id → `Insert.after` at GC time). A new `compactPositions: Map<RgaId, RgaId>` lazy property unions all `Compact` ops in the log. `computeSequence` chain-walks that map to find the nearest surviving ancestor for any orphaned element. Merge stays map union (values are immutable once an `Insert` is created). The coordinator calls a new internal `positionsFor()` helper for window-dropped live elements.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization (CBOR/JSON via custom `RgaOpSerializer`), JVM/Android/iOS/macOS/wasmJs.

**Spec:** `docs/superpowers/specs/2026-06-09-rga-positional-reroot-design.md`

**Non-collision note:** `#288` (SeamReplicator lock — `next-plan.md` in `../kuilt-crdt/`) touches `SeamReplicator.kt` exclusively. This plan touches `Rga.kt`, `RgaOpSerializer.kt`, `RgaGcCoordinator.kt`, and `RgaRerootTest.kt`. Branch from `origin/main`; no shared files.

---

## File Map

| File | Change |
|------|--------|
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt` | `Compact` field rename; update `compactedIds`, `causalDots`, `apply`, `piece`, `compact()`; add `positionsFor` helper; add `compactPositions` lazy prop; update `computeSequence` |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/RgaOpSerializer.kt` | Replace `idsSerializer: SetSerializer` with `positionsSerializer: MapSerializer`; update descriptor field `"ids"` → `"pos"`; update serialize/deserialize |
| `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/RgaGcCoordinator.kt` | `compactWithWindow`: use `positions` map, call `positionsFor`; fix log line `ids.size` → `positions.size` |
| `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt` | Update 4 direct `Compact(setOf(...))` calls to `Compact(mapOf(...))` in Task 1; flip pin test assertion in Task 2; add chain test in Task 3 |
| `gradle.properties` | Bump `kuiltVersionLine` from `0.3` to `0.4` (breaking wire change) |

---

## Task 1: Data model — `Compact.ids: Set` → `Compact.positions: Map` (no behavior change)

This task changes the type and updates all callsites mechanically. `computeSequence` is **not** changed yet — it still does HEAD-reroot. All existing tests pass at end of this task.

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt`
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/RgaGcCoordinator.kt`
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt`

- [ ] **Step 1: Change `RgaOp.Compact` in `Rga.kt`**

  Find the `Compact` data class (around line 91). Replace the entire block:

  ```kotlin
      @Serializable
      public data class Compact(
          public val ids: Set<RgaId>,
      ) : RgaOp<Nothing>
  ```

  with:

  ```kotlin
      /**
       * Records that the [positions] entries have been garbage-collected from the op-log.
       *
       * The map carries each compacted id's predecessor at GC time (`id → Insert.after`).
       * [computeSequence] uses this to reattach surviving successors to the nearest surviving
       * ancestor (positional reroot, #293) rather than to [RgaId.HEAD].
       *
       * The ids that are purged are [positions].keys. Merging two [Compact] ops via [Rga.piece]
       * unions their [positions] maps — sound because a given id's `after` is fixed when its
       * [Insert] was created, so two replicas always agree on the value.
       *
       * Applying a [Compact] removes every [Insert] and [Remove] op whose id is in [positions].keys.
       * Receiving the same [Compact] twice is idempotent.
       */
      @Serializable
      public data class Compact(
          public val positions: Map<RgaId, RgaId>,
      ) : RgaOp<Nothing>
  ```

- [ ] **Step 2: Update `compactedIds` lazy property in `Rga.kt`**

  Find `compactedIds` (around line 134). Change `it.ids` to `it.positions.keys`:

  ```kotlin
      private val compactedIds: Set<RgaId> by lazy {
          ops.filterIsInstance<RgaOp.Compact>()
              .flatMapTo(mutableSetOf()) { it.positions.keys }
      }
  ```

- [ ] **Step 3: Update `causalDots()` in `Rga.kt`**

  Find the `is RgaOp.Compact` branch inside `causalDots()`. Change `op.ids.asSequence()` to `op.positions.keys.asSequence()`:

  ```kotlin
                      is RgaOp.Compact -> op.positions.keys.asSequence().map { it.dot }
  ```

- [ ] **Step 4: Update `apply()` in `Rga.kt`**

  Find `is RgaOp.Compact -> Rga(purgeAndRecord(ops, op.ids, op), lamport)` and change `op.ids` to `op.positions.keys`:

  ```kotlin
          is RgaOp.Compact -> Rga(purgeAndRecord(ops, op.positions.keys, op), lamport)
  ```

- [ ] **Step 5: Update `piece()` in `Rga.kt`**

  Find `flatMapTo(mutableSetOf()) { it.ids }` inside `piece()`. Change to `.positions.keys`:

  ```kotlin
          val allCompactedIds = rawUnion.filterIsInstance<RgaOp.Compact>()
              .flatMapTo(mutableSetOf()) { it.positions.keys }
  ```

- [ ] **Step 6: Update `compact()` to build positions map in `Rga.kt`**

  Find `compact(stableCut, frontierMax, delivered)` (around line 283). Replace the two lines that build and use `compactOp`:

  ```kotlin
          // before the change, this was:
          //   val compactOp = RgaOp.Compact(gcIds)
          val positions = gcIds.associateWith { id -> insertsByid.getValue(id).after }
          val compactOp = RgaOp.Compact(positions)
          val newOps = purgeAndRecord(ops, gcIds, compactOp)
          return Rga(newOps, lamport) to compactOp
  ```

  The full updated `compact()` body:

  ```kotlin
      public fun compact(
          stableCut: VersionVector,
          frontierMax: VersionVector,
          delivered: VersionVector,
      ): Pair<Rga<V>, RgaOp.Compact>? {
          if (!delivered.dominates(frontierMax)) return null
          val predecessors = insertsByid.values.mapTo(mutableSetOf()) { it.after }
          val gcIds = tombstones
              .filter { id -> stableCut.contains(id.dot) && id !in predecessors }
              .toSet()
          if (gcIds.isEmpty()) return null
          val positions = gcIds.associateWith { id -> insertsByid.getValue(id).after }
          val compactOp = RgaOp.Compact(positions)
          val newOps = purgeAndRecord(ops, gcIds, compactOp)
          return Rga(newOps, lamport) to compactOp
      }
  ```

- [ ] **Step 7: Add `positionsFor` internal helper to `Rga.kt`**

  Add this method immediately after `compact()` (before `apply()`):

  ```kotlin
      /**
       * Returns a positions map for [ids]: each id mapped to its [RgaOp.Insert.after].
       * All ids must be present in [insertsByid] (live, non-compacted elements).
       * Used by [us.tractat.kuilt.crdt.replicator.RgaGcCoordinator] to build positions
       * for window-dropped live elements when constructing a combined [RgaOp.Compact].
       */
      internal fun positionsFor(ids: Set<RgaId>): Map<RgaId, RgaId> =
          ids.associateWith { id -> insertsByid.getValue(id).after }
  ```

- [ ] **Step 8: Update `RgaGcCoordinator.compactWithWindow`**

  Open `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/RgaGcCoordinator.kt`.
  Find `compactWithWindow` (around line 162). Replace the entire method body:

  ```kotlin
      private fun compactWithWindow(
          rga: Rga<V>,
          cut: CutFrontier,
          delivered: VersionVector,
          windowIds: Set<RgaId>,
      ): Pair<Rga<V>, RgaOp.Compact>? {
          val gcPositions = rga.compact(
              stableCut = cut.stableCut,
              frontierMax = cut.frontierMax,
              delivered = delivered,
          )?.second?.positions.orEmpty()
          val liveDropIds = windowIds.intersect(rga.sequence.toSet())
          val livePositions = rga.positionsFor(liveDropIds)
          val allPositions = gcPositions + livePositions
          if (allPositions.isEmpty()) return null
          val compactOp = RgaOp.Compact(allPositions)
          return rga.apply(compactOp) to compactOp
      }
  ```

- [ ] **Step 9: Fix the log line in `compactUntilStable`**

  Find `compactOp.ids.size` in `compactUntilStable` (around line 139) and change to `compactOp.positions.size`:

  ```kotlin
              logger.debug {
                  "[rga-gc] compacting ${compactOp.positions.size} id(s) at " +
                      "stableCut=${cut.stableCut} frontierMax=${cut.frontierMax}"
              }
  ```

- [ ] **Step 10: Update the 4 direct `RgaOp.Compact(setOf(...))` calls in `RgaRerootTest.kt`**

  **`droppingLeadingPrefixRerootsRetainedWindowAtHead`** — `chainOfSix()` builds a tail-appended chain (`ops[0]@HEAD`, `ops[1]@ops[0]`, `ops[2]@ops[1]`, …):

  ```kotlin
          val dropped = mapOf(
              ops[0].id to RgaId.HEAD,
              ops[1].id to ops[0].id,
              ops[2].id to ops[1].id,
          )
          val windowed = rga.apply(RgaOp.Compact(dropped))
  ```

  **`divergentWindowsConvergeMostAggressiveWins`** — same chain:

  ```kotlin
          val x = rga.apply(RgaOp.Compact(mapOf(
              ops[0].id to RgaId.HEAD,
              ops[1].id to ops[0].id,
              ops[2].id to ops[1].id,
          )))
          val y = rga.apply(RgaOp.Compact(mapOf(
              ops[0].id to RgaId.HEAD,
              ops[1].id to ops[0].id,
          )))
  ```

  **`rerootedSiblingsOrderDescendingById`** — both `a` and `b` inserted directly after `HEAD`:

  ```kotlin
          val windowed = r3.apply(RgaOp.Compact(mapOf(a.id to RgaId.HEAD, b.id to RgaId.HEAD)))
  ```

  **`evictionOrphanRerootsAbovePrecedingContent`** — `opI` was inserted after `opM`; leave the assertion at `[J, M]` for now (still accurate because `computeSequence` unchanged):

  ```kotlin
          val windowed = tombstoned.apply(RgaOp.Compact(mapOf(opI.id to opM.id)))
  ```

- [ ] **Step 11: Compile and run jvmTest to confirm all tests pass**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && \
    ./gradlew :kuilt-crdt:jvmTest
  ```

  Expected: BUILD SUCCESSFUL. `evictionOrphanRerootsAbovePrecedingContent` still asserts `[J, M]` and passes — `computeSequence` unchanged.

- [ ] **Step 12: Commit**

  ```bash
  git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt \
          kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/replicator/RgaGcCoordinator.kt \
          kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt
  git commit -m "refactor(kuilt-crdt): Compact carries positions Map<RgaId,RgaId> instead of ids Set (#293)"
  ```

---

## Task 2: TDD — positional reroot in `computeSequence`

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt`
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt`

- [ ] **Step 1: Flip pin test assertion to the desired behavior**

  In `RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent`, update the assertion and comment:

  ```kotlin
          assertEquals(
              listOf("M", "J"),
              windowed.toList(),
              "positional reroot: Compact records I.after=M, so J chain-follows I→M and stays below M",
          )
  ```

  Also remove the old "accepted quirk / #293 would give" sentence from the KDoc above the test — replace the full comment with:

  ```kotlin
      /**
       * Positional reroot (#293): an eviction-orphan reattaches to the nearest surviving ancestor,
       * not HEAD. Build `[M, I, J]` (I removed, then GC'd). Before this fix J rerooted to HEAD
       * and sorted above M (`[J, M]`). With positional reroot, `Compact({I→M})` records I's
       * position; J chain-follows I→M and lands below M → `[M, J]`.
       */
  ```

- [ ] **Step 2: Run test to confirm it fails**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && \
    ./gradlew :kuilt-crdt:jvmTest --tests "*RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent"
  ```

  Expected: FAIL — `expected: <[M, J]> but was: <[J, M]>`

- [ ] **Step 3: Add `compactPositions` lazy property to `Rga.kt`**

  Add this property in the `Rga` class body, immediately before `computeSequence()`:

  ```kotlin
      /**
       * Union of all [RgaOp.Compact] ops' [RgaOp.Compact.positions] maps in this log.
       * Maps each compacted id to its [RgaOp.Insert.after] at GC time.
       * Used by [computeSequence] to resolve orphaned elements to their nearest surviving ancestor.
       */
      private val compactPositions: Map<RgaId, RgaId> by lazy {
          ops.filterIsInstance<RgaOp.Compact>()
              .flatMap { it.positions.entries }
              .associate { (k, v) -> k to v }
      }
  ```

- [ ] **Step 4: Update `computeSequence()` in `Rga.kt`**

  Replace the entire `computeSequence()` method:

  ```kotlin
      private fun computeSequence(): List<RgaId> {
          // Group each insert op by its effective predecessor: HEAD if `after` is HEAD or present,
          // else chain-walk compactPositions to the nearest surviving ancestor (positional
          // reroot, #293) — preserves relative order when GC removes an intermediate element.
          val present = insertsByid.keys
          val positions = compactPositions
          fun nearestAncestor(start: RgaId): RgaId {
              var cur = start
              while (cur != RgaId.HEAD && cur !in present) cur = positions[cur] ?: RgaId.HEAD
              return cur
          }
          val childrenOf = insertsByid.values
              .groupBy(
                  keySelector = { ins ->
                      val a = ins.after
                      if (a == RgaId.HEAD || a in present) a else nearestAncestor(a)
                  },
                  valueTransform = { it.id },
              )
              .mapValues { (_, ids) -> ids.sortedDescending() }
          val result = mutableListOf<RgaId>()
          appendChildren(RgaId.HEAD, childrenOf, result)
          return result
      }
  ```

- [ ] **Step 5: Run pin test to confirm it passes**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && \
    ./gradlew :kuilt-crdt:jvmTest --tests "*RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent"
  ```

  Expected: PASS

- [ ] **Step 6: TDD revert — confirm test fails without the fix**

  In `computeSequence`, temporarily restore HEAD-reroot (revert to the old `groupBy`):

  ```kotlin
          val childrenOf: Map<RgaId, List<RgaId>> = insertsByid.values
              .groupBy(
                  keySelector = { if (it.after in present) it.after else RgaId.HEAD },
                  valueTransform = { it.id },
              )
              .mapValues { (_, ids) -> ids.sortedDescending() }
  ```

  Run:

  ```bash
  ./gradlew :kuilt-crdt:jvmTest --tests "*RgaRerootTest.evictionOrphanRerootsAbovePrecedingContent"
  ```

  Expected: FAIL. Confirms the test guards the fix.

  Restore `computeSequence` to the chain-following version from Step 4.

- [ ] **Step 7: Run full jvmTest — no regressions**

  ```bash
  ./gradlew :kuilt-crdt:jvmTest
  ```

  Expected: all tests pass.

- [ ] **Step 8: Commit**

  ```bash
  git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/Rga.kt \
          kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt
  git commit -m "fix(kuilt-crdt): positional reroot — Compact positions preserve eviction-orphan order (#293)"
  ```

---

## Task 3: Add two-hop chain test

**Files:**
- Modify: `kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt`

- [ ] **Step 1: Add chain test at the end of `RgaRerootTest`**

  Append before the closing `}` of the class:

  ```kotlin
      @Test
      fun chainedGcPassesResolveToNearestSurvivingAncestor() {
          // Build M→I→K→J (each element after the previous), then tombstone I and K.
          val (r0, opM) = Rga.empty<String>().insertAfter(p, RgaId.HEAD, "M")
          val (r1, opI) = r0.insertAfter(p, opM.id, "I")
          val (r2, opK) = r1.insertAfter(p, opI.id, "K")
          val (r3, opJ) = r2.insertAfter(p, opK.id, "J")
          assertEquals(listOf("M", "I", "K", "J"), r3.toList(), "baseline")

          val t1 = r3.removeAt(1)!!.first  // tombstone I → visible [M, K, J]
          val t2 = t1.removeAt(1)!!.first  // tombstone K → visible [M, J]
          assertEquals(listOf("M", "J"), t2.toList(), "I and K tombstoned")

          // GC K first (K.after=I, I still present): J.after=K → compactPositions[K]=I (present).
          val gcK = t2.apply(RgaOp.Compact(mapOf(opK.id to opI.id)))
          assertEquals(listOf("M", "J"), gcK.toList(), "K GC'd; J reroots to I (nearest present ancestor)")

          // GC I second (I.after=M): two-hop chain J→K(compacted,pos=I)→I(compacted,pos=M)→M(present).
          val gcKI = gcK.apply(RgaOp.Compact(mapOf(opI.id to opM.id)))
          assertEquals(listOf("M", "J"), gcKI.toList(), "two-hop chain: J resolves K→I→M; no reorder")
      }
  ```

- [ ] **Step 2: Run the new test**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && \
    ./gradlew :kuilt-crdt:jvmTest --tests "*RgaRerootTest.chainedGcPassesResolveToNearestSurvivingAncestor"
  ```

  Expected: PASS

- [ ] **Step 3: Commit**

  ```bash
  git add kuilt-crdt/src/commonTest/kotlin/us/tractat/kuilt/crdt/RgaRerootTest.kt
  git commit -m "test(kuilt-crdt): two-hop chain GC resolves to nearest surviving ancestor (#293)"
  ```

---

## Task 4: Update `RgaOpSerializer` wire format

**Files:**
- Modify: `kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/RgaOpSerializer.kt`

- [ ] **Step 1: Replace `SetSerializer` import with `MapSerializer`**

  Change:

  ```kotlin
  import kotlinx.serialization.builtins.SetSerializer
  ```

  to:

  ```kotlin
  import kotlinx.serialization.builtins.MapSerializer
  ```

- [ ] **Step 2: Replace `idsSerializer` field with `positionsSerializer`**

  Change:

  ```kotlin
      private val idsSerializer: KSerializer<Set<RgaId>> = SetSerializer(rgaIdSerializer)
  ```

  to:

  ```kotlin
      private val positionsSerializer: KSerializer<Map<RgaId, RgaId>> = MapSerializer(rgaIdSerializer, rgaIdSerializer)
  ```

- [ ] **Step 3: Update the class KDoc wire format comment**

  Change the Compact line in the comment:

  ```
   * - Compact: `{ "t": 2, "pos": Map<RgaId, RgaId> }`
  ```

- [ ] **Step 4: Update the descriptor**

  Find `element("ids", idsSerializer.descriptor, isOptional = true)` and replace:

  ```kotlin
          element("pos", positionsSerializer.descriptor, isOptional = true)    // Compact only
  ```

- [ ] **Step 5: Update `serialize()`**

  Find the `is RgaOp.Compact` branch and replace:

  ```kotlin
              is RgaOp.Compact -> {
                  encodeIntElement(descriptor, 0, TYPE_COMPACT)
                  encodeSerializableElement(descriptor, 4, positionsSerializer, value.positions)
              }
  ```

- [ ] **Step 6: Update `deserialize()`**

  Change `var ids: Set<RgaId>? = null` to `var positions: Map<RgaId, RgaId>? = null`.

  Change the `4 ->` branch:

  ```kotlin
                  4 -> positions = decodeSerializableElement(descriptor, 4, positionsSerializer)
  ```

  Change the `TYPE_COMPACT` case:

  ```kotlin
              TYPE_COMPACT -> RgaOp.Compact(positions = positions ?: missingField("Compact", "positions"))
  ```

- [ ] **Step 7: Run jvmTest**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-crdt:jvmTest
  ```

  Expected: all tests pass.

- [ ] **Step 8: Commit**

  ```bash
  git add kuilt-crdt/src/commonMain/kotlin/us/tractat/kuilt/crdt/RgaOpSerializer.kt
  git commit -m "feat(kuilt-crdt): update Compact wire format — pos Map replaces ids Set (#293)"
  ```

---

## Task 5: Full build, version bump, and PR

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump `kuiltVersionLine` in `gradle.properties`**

  Open `gradle.properties`. Change:

  ```properties
  kuiltVersionLine=0.3
  ```

  to:

  ```properties
  kuiltVersionLine=0.4
  ```

- [ ] **Step 2: Run the full build**

  ```bash
  source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build
  ```

  Expected: BUILD SUCCESSFUL across all platforms (JVM, Android, iOS sim, macOS, wasmJs).

- [ ] **Step 3: Commit version bump**

  ```bash
  git add gradle.properties
  git commit -m "chore: bump kuiltVersionLine to 0.4 — Compact wire format is breaking (#293)"
  ```

  (Note: "chore" is normally avoided per project conventions — use "release" or just describe it.)

  Actually: replace with:

  ```bash
  git commit -m "release: bump kuiltVersionLine 0.3→0.4 — Compact pos-Map wire format (#293)"
  ```

- [ ] **Step 4: Open PR closing #293**

  ```bash
  gh pr create \
    --title "fix(kuilt-crdt): positional reroot — preserve eviction-orphan order (#293)" \
    --body "$(cat <<'EOF'
  🤖 This comment was generated by Claude on behalf of @keddie.

  ## Summary
  - `RgaOp.Compact.ids: Set<RgaId>` → `positions: Map<RgaId, RgaId>` (id → its `Insert.after` at GC time).
  - `computeSequence` chain-walks `compactPositions` to the nearest surviving ancestor for orphaned elements, eliminating the `[J, M]` reorder that appeared when the eviction-orphan safety-net GC'd a middle element.
  - Wire format change (`"ids"` → `"pos"` in CBOR/JSON). Version line bumped `0.3` → `0.4`.

  Closes #293

  ## Test plan
  - `evictionOrphanRerootsAbovePrecedingContent` flipped from `[J, M]` to `[M, J]`.
  - New `chainedGcPassesResolveToNearestSurvivingAncestor` covers two-hop chain resolution.
  - All reroot, GC barrier, adversarial, coordinator, and window tests remain green.
  EOF
  )"
  ```

- [ ] **Step 5: Enable auto-merge and open in browser**

  ```bash
  PR=$(gh pr list --head "$(git branch --show-current)" --json number --jq '.[0].number')
  gh pr merge "$PR" --auto --squash
  gh pr view "$PR" --web
  ```
