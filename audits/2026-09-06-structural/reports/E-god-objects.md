# E — God objects in kuilt

Read-only audit at `docs/2600-depart-carries-finals`, `origin/main` fetched 2026-09-06. No builds run.

## 0. Corrections to the brief (read first)

| Brief | Finding |
|---|---|
| `kuilt-core/.../composite/CompositeSeam.kt`, `.../fabric/MeshSeam.kt` | Paths are `us/tractat/kuilt/**core**/composite/…` and `…/core/fabric/…`. Files found. |
| "the largest production files" (raw lines) | Raw lines are ~56% comment repo-wide. By **code** lines CompositeSeam (411) and GameNode (413) are not in the repo's top 18. Ranking below uses both. |
| SeamRoom "26 class/object/interface declarations" | **8** top-level, 25 at all nesting depths; 12 of those are one-line arms of three private sealed verdict types. Three real co-tenants. |
| SeamRoom concerns incl. "reconnect tokens, token expiry, wire codec" | All three are **already extracted** — `partition/JoinerResumeMachine.kt` (769 L), `admit/AdmitMessage.kt`, `RelayEnvelope.kt`, `RoomFramePrefix.kt`. The brief's list omits the file's **largest** concern: relay routing + per-recipient lane fan-out, 775 L / 24% of the class. |
| CLAUDE.md: "the older `CompositeSeam`/`CompositeLoom` `limitedParallelism(1)` confinement is legacy being migrated" | **Stale — the migration is done.** `git grep limitedParallelism -- '*.kt'` finds **zero** uses in either file; the only hit is `CompositeSeam.kt:355`, a comment *denying* it. Both are on `reentrantLock` (`CompositeSeam.kt:218`, `MeshSeam.kt:726`), `SeamStateGate` (`:227`, `:798`), `Mutex` (`MeshSeam.kt:764`) and dedicated single-writer coroutines. The surviving production uses are `kuilt-tcp`/`kuilt-websocket` **dispatcher defaults**, which the policy permits. The CLAUDE.md sentence points a reader at a defect that no longer exists. |
| `docs/ply-roadmap.md` would carry a decomposition rationale | It does not. It is a feature roadmap (3 deferred capabilities); nothing about file size or structure. |
| RaftEngine "already decomposed once in #1121" | True, and it is the headline — see §1. |

## 1. Headline: both decompositions completed on 2026-07-07 and both files have since ~2.5×'d

| File | At decomposition closeout (2026-07-07) | Today | Growth |
|---|---|---|---|
| `RaftEngine.kt` | 1,945 raw / **1,158 code** (`79354c0e`, "#1121 closeout") | 4,330 / **1,685** | **2.2× raw, 1.45× code** |
| `SeamRoom.kt` | 1,330 raw / **624 code** (#1122 extraction landed) | 3,758 / **1,351** | **2.8× raw, 2.2× code** |

#1121 filed the engine as a god object at **1,868 lines / ~30 mutable fields**; #1122 filed SeamRoom at **1,230 lines**. Both are CLOSED/COMPLETED. Both files are now roughly triple the size that justified filing, and **neither has an open tracker**. The extractions were real (12 raft siblings, 2,023 L; `JoinerResumeMachine` + `JoinerReconnectController`, 1,072 L) — the files regrew past them.

The regrowth is *new concerns*, not re-accretion of the extracted ones: SeamRoom absorbed the relay plane + lane fan-out (#1994, #2048, #1557), which did not exist when #1122 was scoped. That is the pattern behind "the burndown finds as many issues as it fixes": a bug fix in a god object lands **inside** the god object, and the decomposition boundary is set once and never revisited.

## 2. Size, state and concurrency

`code` = non-comment non-blank. Non-private = public + internal + override, excluding private.

| File | raw | **code** | %cmt | non-priv | `var` | locks/atomics | `launch` sites | Mut*Flow | %module code |
|---|---|---|---|---|---|---|---|---|---|
| RaftEngine | 4330 | **1685** | 59 | 16 | 24 fields | **0** (Channel actor) | 10 | 11 | 55% |
| SeamRoom | 3758 | **1351** | 62 | 25 | 7 | 1 lock :652, 4 atomics | **16** | 8 | 49% |
| SeamConformanceSuite | 2979 | **1230** | 56 | 70 | **0** | **0** | 2 | 0 | — (TCK) |
| NwSeam | 2437 | **914** | 61 | 13 | 17 | lock :325, Mutex :532, 2 atomics | 9 | 5 | 26% |
| EntitlementLedger | 2209 | **819** | 57 | 54 | **0 fields** | **0** | **0** | 0 | 33% |
| WarpLogRecordExporter | 2190 | **608** | 68 | ~9 | 11 | lock :267, Mutex :347 | **0** | 2 | 35% |
| RealNwApi | 1785 | **870** | 48 | 39 (13 are `…ForTest`) | 17 | lock :701, 3 atomics, GCD queue | **0** | 9 | — (appleMain) |
| WarpNode | 1717 | **674** | 54 | 19 | 11 | lock :379 | **13** | 1 | 45% |
| CompositeSeam | 1468 | **411** | **71** | 11 | 4 | lock :218, atomic :213, gate :227 | 10 | 3 | 14% |
| MeshSeam | 1407 | **500** | 62 | 19 | 2 | lock :726, Mutex :764, atomic :836 | 8 | 2 | — |
| Quilter | 1269 | **496** | 58 | 21 | 5 | lock :217 | **14** | 4 | 51% |
| NwLoom | 1212 | **465** | 60 | 9 | 9 | 2 locks :160, :827 | 6 | 2 | — |
| GameNode | 1094 | **413** | 60 | 14 | 1 (loop local) | **0** | 2 | 0 | 34% |

**Comment ratio is not the outlier it looks like.** Repo median over 153 production files ≥200 L is **56%** (p25 47%, p75 63%); `Seam.kt` is 94%, `RaftNode.kt` 89%. Only CompositeSeam (71%) and WarpLogRecordExporter (68%) sit meaningfully above the house norm. What *is* an outlier is the **absolute** volume: RaftEngine carries 2,462 comment lines, including a 107-line KDoc on one guard (`RaftEngine.kt:981–1087`) and eight blocks over 70 lines. These are issue post-mortems written as invariant proofs plus anti-refactor fences (`CompositeSeam.kt:1060–1140`, 81 L, ends "*do not smuggle the predicate change in alongside it*"). They are load-bearing and must survive any move — which is the real cost driver, not the code.

**Three files are misclassified as god objects by raw lines:**
- `EntitlementLedger` — **zero mutable fields** (all 5 `var`s are function locals), zero locks, zero launches, `: Quilted<EntitlementLedger>` (`:190`), `piece()` a componentwise semilattice join (`:192–225`). An immutable delta-state CRDT; its size is **lattice surface area**. Appropriately large.
- `SeamConformanceSuite` — **zero fields**, zero locks. Stateless by construction (§5).
- `CompositeSeam`/`GameNode` — 411/413 code lines. GameNode contains no `GameNode` type at all: it is top-level extension functions on `CoroutineScope`, already shrunk twice (`97467786` #1155, `7b43272b` #1160).

The brief's parallel premise for `WarpLogRecordExporter` (that low code + high comment ⇒ value type) is **refuted**: 11 mutable fields, two distinct lock primitives (`:267`, `:347`), 5 mutable collections. Zero launches because it deliberately owns no scope (`:424`), not because it is pure.

## 3. Churn and issue density

`git log origin/main --since=2026-05-20` (= all time; repo's first commit is 2026-05-25). Issue refs counted from `closes/fixes/part of #N` in subject+body, so the squash `(#PR)` suffix is excluded.

| File | commits | `fix:` | distinct issues | `<Type> in:title` issues (all / open) |
|---|---|---|---|---|
| RaftEngine | 95 | **53** | **72** | 11 / 0 |
| SeamRoom | 63 | 28 | 39 | 8 / 0 |
| SeamConformanceSuite | 40 | 11 | 28 | 9 / **4** |
| WarpNode | 37 | 11 | 29 | 12 / 0 |
| Quilter | 36 | 9 | 25 | **21** / 2 |
| NwSeam | 29 | 17 | 23 | 15 / 2 |
| CompositeSeam | 27 | 17 | 17 | **16** / 2 |
| MeshSeam | 27 | 20 | 24 | 11 / 0 |
| RealNwApi | 25 | 15 | 24 | 4 / 0 |
| GameNode | 24 | 2 | 11 | 1 / 0 |
| NwLoom | 23 | 11 | 23 | 3 / 0 |
| EntitlementLedger | 18 | 5 | 10 | 2 / 1 |
| WarpLogRecordExporter | 17 | 4 | 18 | 4 / 1 |

**The recurring fix class is the same shape in each file, and it is a missing decision layer.**

| File | Dominant fix class | Share |
|---|---|---|
| RaftEngine | *An inbound or restored value was not validated* — "bound", "validate", "refuse", "reject", "gate", "clamp" | **~24 of 53** |
| NwSeam | Per-connection state reconciliation — zombie conns, stale snapshots, self-connections, tombstones, roster-collapse atomicity | ~10 of 17 |
| SeamRoom | Partition/reconnect-window lifecycle across two roles + per-peer fan-out wedging | ~15 of 28 |

## 4. Decomposition seams (concrete, with line ranges)

| File | Seam | Lines | Extracted shape | Confidence |
|---|---|---|---|---|
| **RaftEngine** | `RaftInboundValidators` — the 4 `RefusalGate?` validators | `:2282`, `:2362`, `:2650`, `:2665` | already-pure `(from, msg) → RefusalGate?`; make them `internal`, unit-testable | **very high** |
| | `RaftDiagnostics` — warn-once + wedge reporting | fields `:351–445`; funs `:612–697`, `:1487–1599`, `:2431–2497`, `:4085–4195` (~530 raw L) | **8 of 24 mutable fields** are log-throttling counters, zero consensus risk | high |
| **SeamRoom** | `PeerLaneSet<T>` — collapse the two near-identical per-recipient lanes | `:2649–2734`, `:2801–2820`, `:2896–2941`, `:3032–3091` (~550 → ~150 L) | KDoc admits the duplication: `:2899` "*Lazily, for the same reasons as [relayLaneFor]; see there*" | **very high** |
| | `RelayPlane` + pure `resolveRecipients` | `:2340–2820` (~480 L) | `Resolved` verdict ADT already exists `:2340–2352` | high |
| | `PeerEntry` — collapse 6 `PeerId`-keyed maps | `:655`, `:692`, `:780`, `:836`, `:2671`, `:2896` | one reap path instead of six. **Hazard:** `episodeDetectedAtMs` (`:684–691`) is deliberately *not* a superset — keep nullable | med |
| **NwSeam** | `DisplacementDrains` + ordering hold | `:342–396`, `:1143–1400` (~320 L) | owns its own `stageMutex` `:532`, distinct from `lock` `:325` — separate concurrency domain already | high |
| | `SeamWedgeWatchdog` | `:1732–2059` (~300 L) | read-only observer; 5 of `ConnState`'s 7 `var`s exist only for it | high |
| **MeshSeam** | `MeshHello.kt` + `MeshBuilder.kt` | `:112–225`, `:424–698` (~490 L) | free functions, **zero** instance coupling | **very high, zero risk** |
| | pure `decide(links, link, state): Admission` | `:962` | sealed `Admission` ADT already at `:941–954`; today it mutates `links`/`draining` inline | high |
| **Quilter** | `PeerRecord` + `SenderRecord` | 10 parallel maps at `:261,295,298,301,304,307,314,322,329,354` | **7 keyed `PeerId`, 3 keyed `ReplicaId`, nothing reconciles them.** `QuilterEvictionWiringAuditTest` (630 L, largest in module) exists to audit this by hand | **very high** |
| **WarpNode** | `ClaimArbiter` / `CoordinatedExecutionDriver` / `PeerLivenessTracker` | `:988–1185` / `:1411–1519` / `:885–986` | `TaskRing.kt` (172 L, `TaskRingTest` 228 L) is the proven precedent | high |
| **WarpLogRecordExporter** | `SegmentLedger` | `:427–490` + `:1679–2001` (~450 L) | `StoreAction` effects ADT `:581–676`, 10 pure planners, single I/O funnel `commit()` `:1563` — **~80% built** | **very high** |
| **RealNwApi** | `ReceiveRetryPolicy` + `ConnStateLattice` → commonMain | `:1519–1583`, `:438–468` (~100 L) | sibling `classifyReceiveError` already extracted (`ReceiveErrorClass.kt:106`); this is its **unfinished half** | high |
| **NwLoom** | promote `ListenSupervisor` / `RedialCoordinator` to own files | `:586–816`, `:817–1212` | already file-`private` classes; mechanical. 1212 → ~585 L | **very high, zero risk** |
| **EntitlementLedger** | `LedgerConflictDetector` (`validate`), `RelocationPlanner` | `:1457–1728`, `:813–1168` | both already pure; tests already partitioned this way | med (legibility only) |
| **GameNode** | `GameExceptions.kt`, `AdmissionFlows.kt` | `:172–241`, `:770–876` | cosmetic | low |

**Already-pure-and-separately-tested, by file:** RaftEngine — `RaftLogMath.kt`/`LogPosition` (`RaftLogMathTest`, `LogPositionTest`), `MembershipState`, `ReadIndexTracker`, `LeadershipTransferMachine`, `ProposalForwarder`, `CollisionDetector`, `LeaderDedupCache` (7 of 12 siblings have dedicated tests). NwSeam — `canonicalLinkNonce`, `NwPathState.toAvailability`, `NwWire`, `renderFormationDump`. CompositeSeam — `rollup` `:963`, `reachablePeersLocked` `:1166` (extracted by #1804), `PlyInboundGate.kt`. WarpNode — `TaskRing`. EntitlementLedger — the whole merge law (`EntitlementLedgerLawsTest:121–198`).

## 5. RaftEngine specifically

**#1121 extracted** 12 siblings totalling 2,023 raw / 1,168 code lines: `RaftState` (the mutable-field holder, #1215), `SnapshotSender` (#1216), `SnapshotReceiver` (#1217), `LeadershipTransferMachine` (#1227), `ReadIndexTracker` (#1234), `ProposalForwarder` (#1237), plus `RaftLogMath`, `MembershipState`, `RaftMessage`, `EngineCommand`, `CollisionDetector`, `LeaderDedupCache`. Closeout `79354c0e` (#1258) fixed two teardown leaks the decomposition exposed.

**What is left is I/O-entangled, not a pure state machine.** The concurrency model is clean — a single `Channel<EngineCommand>` actor (`startActor()` `:901–986`), **zero locks, zero atomics**. But decision and effect are interleaved inside ~40 handler functions: **75 `transport.*` and 24 `storage.*` call sites** inline. `state = RaftState(...)` (`:714`) holds consensus state; **24 `var`s remain on the engine**, of which **8 are diagnostic/log-throttling** (`:351, 361, 377, 388, 394, 405, 416, 422`), 4 are `Job` handles, 4 are election-in-flight, and 2 are the safety-critical leader pin.

**The pure decision seam already exists, is documented as deliberate, and is only half-applied.** `RefusalGate.kt`'s KDoc names the pattern and instructs: *"Every factored validator on the inbound path returns `RefusalGate?` … a refusing clause added to one of them **cannot compile** without naming a gate: `return false` has stopped being expressible there. Keep it that way."* Measured: `snapshotChunkRefusal`, `committedTermFloorRefusal`, `configPayloadRefusal`, `batchRefusal` are all **non-suspend with zero mutating lines**. But:

1. **All four are `private`**, so the 548 `@Test`s in `kuilt-raft` reach them only by standing up a simulated cluster. Making them `internal` is a one-line change that turns cluster tests into microsecond unit tests.
2. **`adoptLeaderForTerm` (`:583`) is grouped with them in that KDoc and is not one of them** — it is `suspend` and has **4 mutating lines** (`pinLeaderForTerm(from)`, `leaderPinRefusalRun = 0` ×2, `noteLeaderPinRefusal(...)`). The documented uniformity is false for one of the four named members.
3. Only 4 validators exist against **~24 validation-class fixes**. The pattern covers a fraction of the sites the recurring bug class lives in.

## 6. Ranking and recommendation

| Rank | File | fix × raw /1k | fix × code /1k | fix per 100 code L |
|---|---|---|---|---|
| 1 | **RaftEngine** | 229.5 | **89.3** | 3.15 |
| 2 | **SeamRoom** | 105.2 | **37.8** | 2.07 |
| 3 | NwSeam | 41.4 | 15.5 | 1.86 |
| 4 | SeamConformanceSuite | 32.8 | 13.5 | 0.89 |
| 5 | MeshSeam | 28.1 | 10.0 | **4.00** |
| 6 | RealNwApi | 26.8 | 13.1 | 1.72 |
| 7 | WarpNode | 18.9 | 7.4 | 1.63 |
| 8 | **CompositeSeam** | 25.0 | 7.0 | **4.14** |
| 9 | NwLoom | 13.3 | 5.1 | 2.37 |
| 10 | Quilter | 11.4 | 4.5 | 1.81 |
| 11 | EntitlementLedger | 11.0 | 4.1 | 0.61 |
| 12 | WarpLogRecordExporter | 8.8 | 2.4 | 0.66 |
| 13 | GameNode | 2.2 | 0.8 | 0.48 |

Note the divergence: **`CompositeSeam` and `MeshSeam` have the highest defect density per line of code (4.1 and 4.0 per 100 L) while being small.** They are dense, not big — the volume is proof-comment. Decomposing them buys less than the raw-line ranking implies.

### The three that pay back most

**1. `RaftEngine` → finish the `RefusalGate` validator layer (`RaftInboundValidators`) + `RaftDiagnostics`.**
Target shape: `internal object RaftInboundValidators` holding pure `(state, frame) → RefusalGate?` functions, plus `internal class RaftDiagnostics` owning the 8 warn-once/wedge counters. Payback is not line count — it is that **~45% of this file's 53 fixes are one bug class the repo has already named a structural remedy for and applied to 4 sites.** Every future "an inbound/restored value was unvalidated" fix then lands in a pure function with a directly-callable unit test instead of inside a 4,330-line actor behind a simulated cluster. Fix `adoptLeaderForTerm`'s misgrouping in `RefusalGate.kt`'s KDoc in the same PR, or split it into a pure `decide` + an `apply`.

**2. `SeamRoom` → `PeerLaneSet<T>` + `RelayPlane`, then `PeerEntry`.**
Target shape: `SeamRoom` keeps admit/roster/close; the relay plane becomes `RelayPlane(lock, RelayHost)` with a **pure** `resolveRecipients(envelope, roster, hostPeer, selfId): Resolved`. The in-tree template is exact and proven: `JoinerResumeHost` (`JoinerResumeMachine.kt:38`) + **shared `ReentrantLock` instance** (`:193`, rationale `:168–175` — *"Two independent locks would reopen [the atomicity invariants]"*). Reuse it; do not mint a second lock. Two near-identical lane implementations whose own KDoc cross-references the duplication is the cheapest 400 lines in the audit.

**3. `WarpLogRecordExporter` → `SegmentLedger`.** The best-prepared seam anywhere in the 13 — the effects ADT (`StoreAction`, `:581–676`), ten pure planners returning `List<StoreAction>`, and a single I/O funnel (`commit()`, `:1563`) are already written. It is low churn (4 fixes), so this is the *cheap demonstration* of the pattern rather than the urgent one. If the goal is one high-value target instead, substitute **`NwSeam` → `DisplacementDrains` + `SeamWedgeWatchdog`** (~620 L, rank 3 by churn, and the watchdog is a read-only observer so the risk is near zero).

**Caveat to budget for on 1 and 2:** CLAUDE.md records that centralising a guard **blinds** the lexical root guards — `forbidRunCatchingCancellableUnderNonCancellable` and `forbidCancellationRethrowAroundBound` are both defeated by one helper hop, and `CompositeSeam` is named three times in their comments (`build.gradle.kts:2941, 3018–3019`). `forbidBareLaunchIn` baselines will need re-deriving (`build.gradle.kts:8078` CompositeSeam=1, `:8083` Quilter=2, `:8086` WarpNode=5). Also: SeamRoom's two primary pumps (`:1294`, `:1908`) and all five of NwSeam's are spelled `scope.launch { flow.collect { … } }`, which that guard structurally cannot see — none goes through `pumpIn`, so none has the `PumpFailure.UPSTREAM` half.

## 7. Recorded rationale for deliberate largeness

**Only one of the 13 has one, and it is a genuinely good one.**

| File | Recorded rationale |
|---|---|
| **SeamConformanceSuite** | **Yes, extensive and load-bearing.** Wholesale-ness is the point (`:49–51`: *"a conforming implementation must pass all of them"*); there is **no skip API** in common `kotlin-test`, so an early-returning obligation reports PASS (`:80–86`); the one split already made is backed by an ADR (`:161–165` → `docs/adr-001-conformance-suite-two-loom.md`, N-peer obligations live in the sibling `MeshConformanceSuite`); gate placement is enforced by a meta-test (`SeamConformanceUngatedCoreTest`) that drives the **body helpers**, so a gate inlined into a `@Test` wrapper would blind it (`:1405–1407`). 31 `@Test` (a `grep -c` gives 33; two are in comments), 4 abstract + 11 open hooks, 28 subclasses. **A third cut is already filed and open: [#2439](https://github.com/tractat-us/kuilt/issues/2439)** — pair-required vs single-seam-portable obligations. That is the seam with backing; a lifecycle/send-receive/roster cut would cross its grain. **Do not decompose this file on topic.** |
| All other 12 | **None.** Grep for `deliberately (large\|kept)`, `not decomposed`, `one file on purpose`, `do not split`, `god class`, `monolith` across all 13 returns **zero** hits. Nothing in `docs/`, no ADR. The `deliberately` fences that do exist are all local invariant fences (`CompositeSeam.kt:304, 1336`, `MeshSeam.kt:601, 787`, `Quilter.kt:348`), never about file size. |

**And one recorded claim is false.** `docs/superpowers/specs/2026-07-02-cleanup-simplification-epic-design.md:19–20` (Non-goals) states:

> `- No decomposition of the structural giants (RaftEngine, SeamRoom, WarpNode) — each is filed as a design issue instead (see Deferred).`

Only two were filed: #1121 (RaftEngine) and #1122 (SeamRoom), both DEF2/DEF3 in `docs/superpowers/plans/2026-07-02-cleanup-simplification-epic.md:2472–2478`. **There is no DEF stub and no issue for WarpNode** — `gh issue list --search "WarpNode in:title" --state all` returns 12, all behavioural. WarpNode is the only one of the three named giants still undecomposed, and the only one whose tracker was never opened, while the doc records it as tracked. Nothing has surfaced it since 2026-07-02.

## 8. Actionable follow-ups (not requested, but they fall out)

1. Correct or drop the `limitedParallelism(1)` sentence in CLAUDE.md's coroutine-determinism section — it names `CompositeSeam`/`CompositeLoom`, which have been clean since the migration landed.
2. File the WarpNode decomposition issue the cleanup-epic spec claims exists, or fix `…epic-design.md:19–20`.
3. Reopen or re-file RaftEngine (#1121) and SeamRoom (#1122) decomposition trackers at today's sizes — both closed at ~⅓ the current line count, and the closure is what removed the pressure.
4. Fix `RefusalGate.kt`'s KDoc: `adoptLeaderForTerm` is `suspend` and mutating, and is listed alongside three pure validators as if uniform.
