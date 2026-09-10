# C — Test infrastructure audit (kuilt, `docs/2600-depart-carries-finals` worktree)

Measured 2026-09-06. Read-only; no builds run. LOC: **227,195 test** / **126,734 production** / 4,140 samples
(brief said ~250k/130k — close enough). **38,091** of the test surface is the *published* support modules
(`:kuilt-conformance` + the seven `:kuilt-*-test` modules), i.e. it is API, not scaffolding.

**Headline correction to the brief.** The premise "each incident produced a paragraph and sometimes a lexical
guard" understates what shipped. Five *different* structural anti-vacuity mechanisms exist in-tree, each
invented independently for one suite family. The recurrence root is not that nobody built the mechanism — it
is that **the mechanism was rebuilt five times under five names and is mandatory in none of them**.

---

## 1. Conformance suites / TCKs

Subclass counts are `^…(class|object)…: Suite` excluding KDoc. "Fixture" = a hook injecting a failure the
reference implementation cannot reach.

| Suite (file) | LOC | subcl. | ref impl | failure-injection fixture | shape |
|---|--:|--:|---|---|---|
| `SeamConformanceSuite` | 2979 | **38** / 28 files | `InMemoryLoom` | 3 hooks **defaulting `false`** (`:297`,`:329`,`:380`) + `ObligationDeclaration` cross-check (`:310`,`:340`,`:397`) + `departCounterpart` (`:425`) | hardened, **default-off** |
| `BoltConformanceSuite` | 1907 | 8 / 3 | `InMemoryBolt` | 6 **non-nullable abstract** fixtures (`:66`,`:84`,`:139`,`:175`,`:231`) + sealed `DurabilityFixture` (`:1866`); paired `TinySegment*`/`Asynchronous*` configs | **exemplar** |
| `RoomConformanceSuite` | 1396 | 2 (**1 real**) | in-memory via `FaultyLoom` | sealed `FaultInjection.Supported(FaultyLoom)` vs declared gap (`:281`) | hardened, **1 backend** |
| `RaftStorageConformanceSuite` | 1390 | **1** | `InMemoryRaftStorage` | `reopen()` (`:163`) — *not* sealed, *not* two-armed | **reference-only** ⚠ |
| `DurableStoreConformanceSuite` | 854 | 4 | `InMemoryDurableStore` | sealed `RestartFixture{Durable,KeepsNothing}` (`:811`) | hardened |
| `CommutativeSchemeConformanceSuite` | 786 | 5 / 4 | Xor scheme | `proofStrength()` abstract, **both arms fail** (`:113`) | hardened |
| `RoomFanoutIsolationConformanceSuite` | 761 | **1** | in-memory | non-nullable `absenceBudget` + positive control before every absence (`:157`) | hardened, 1 backend |
| `WasmRuntimeConformanceSuite` | 711 | 3 | — | adversarial `WasmKernelFixtures` corpus (TRAP / IMPORTS / OVERSIZE_*) | hardened |
| `DurableStoreFilenameConformanceSuite` | 619 | 2 | file-backed | `plantRawFile()` non-nullable (`:234`) | hardened |
| `WireCodecConformanceSuite` | 577 | 7 / 6 | — | abstract `exactWidthFields`/`rejectionMode` + `ObligationDeclaration` + rig test | **exemplar** |
| `DiscoverySourceConformanceSuite` | 512 | **18** / 8 | fixed-roster fake | sealed `DepartureFixture{Emits,NoLeaveSignal}` (`:41`) + non-nullable quiescence hook | hardened |
| `PrincipalAttestationConformanceSuite` | 467 | 2 | — | `newHarness(...)` only (`:221`) | thin |
| `QuiltedConformanceSuite` | 426 | **24** / 22 | — | `retirementIsMeaningful = false` (`:266`) + `retirementReAssertion(): …? = null` (`:291`) | **nullable opt-out** ⚠ |
| `MeshConformanceSuite` | 398 | 4 | in-memory mesh | **none** — `newMeshOfSize(n)` only (`:61`) | **pre-#2240** ⚠ |
| `MeshDisplacementDrainConformanceSuite` | 327 | 1 | — | `newAbruptClosingConnectionPair()` non-nullable (`:101`) | hardened, 1 backend |
| `CloseableLifecycleConformanceSuite` | 290 | 4 | — | **none** — `create` + `backgroundJobsOf` (`:72`,`:108`) | **pre-#2240** ⚠ |
| `LatticeLawSuite` / `CompactableLatticeLawSuite` (+`LatticeLawHarness` 1453) | 1453 | 19 + 3 | — | `VacuityFloors` declared per binding (`LatticeOp.kt:331`), asserted at `LatticeLawSuite.kt:158`, proved by `VacuityFloorSelfTest` | **strongest idiom** |

**Still pre-#2240 (reference-only, no failure-injection hook):** `MeshConformanceSuite`,
`CloseableLifecycleConformanceSuite`. **Effectively reference-only:** `RaftStorageConformanceSuite` — one
subclass, and its own KDoc says it outright (`:154`): *"over `InMemoryRaftStorage` alone, these five properties
add no discriminating power at all."* **Nullable opt-out** (the shape CLAUDE.md bans): `QuiltedConformanceSuite`,
across 24 subclasses.

**Five idioms for one property.** sealed two-armed fixture (Bolt/DurableStore/Discovery/Room) · declaration
cross-checked against a hook (Seam/WireCodec, `ObligationDeclaration.kt`) · declared numeric floor + self-test
(Lattice) · declared strength enum with both arms failing (CommutativeScheme) · adversarial fixture corpus (Wasm).
Nothing in `:kuilt-conformance` names the concept, so a new suite author picks one of five exemplars by luck.

---

## 2. Fake / fault-injection matrix

`✓` = injectable; `–` = not supported; `∘` = reachable but hand-rolled per test.

| Fake (module, visibility) | drop | delay | reorder | dup | partition | torn peer | crash/restart | oversize | malformed | seeded | used by |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|--:|
| **`FaultySeam`/`FaultyLoom`** `:kuilt-test` **public** (`FaultProfile.kt:16`) | ✓ all/prob/**by-index** | ✓ | ✓ | – | ✓ (`DropAll`) | ✓ (`CloseAt`, `TeardownFault`) | – | ✓ (`BufferCeiling`) | – | ✓ | 32 files, 7 modules |
| `ChaosSeam` `:kuilt-quilter` **internal** (`ChaosSeam.kt:67`) | ✓ | ✓ | ✓ | **✓** | ✓ | – | – | – | – | ✓ | 7 files, 1 module |
| `FlakyLifecycleSeam` `:kuilt-test` public | – | – | – | – | – | ✓ flap/blip/tear + `FlapSchedule` | – | – | – | ✓ | 11 files |
| `FakeSeam`/`FakeLoom` `:kuilt-test` public | – | – | – | – | ∘ (`removePeer`) | ✓ (`tear`) | – | – | ∘ (`deliver` raw) | – | 54 files, 11 modules |
| `ControllableLoom` `:kuilt-test` public | – | ✓ hold/release/step | ∘ | – | ✓ per-peer hold | – | – | – | – | – | 9 files |
| `InMemoryRaftNetwork` `:kuilt-raft` **commonTest** | ✓ link | ✓ latency | – | – | ✓ | – | – | ✓ (`overBudget`) | – | – | 25 files |
| `RaftSimulation` / `MultiNodeRaftSim` | ✓ | ✓ | – | – | ✓ | – | **✓ `crash`/`restart`** | ✓ | ∘ (`deliver*` by hand) | ✓ per-node | 48 + 28 files |
| `MultiNodeWarpSim` `:kuilt-warp-test` public | – | – | – | ✓ (dup-execution) | ✓ `disconnect` | – | – | – | – | ✓ | 4 |
| `GossipSimulation` / `VoterMeshSim` | – | – | – | – | ✓ `disconnect` | – | – | – | – | ✓ | 2 / 3 |
| `FakeNwRadio`, `FakeNearbyRadio`, `FakeMCSessionBus` | ∘ | – | – | – | ✓ `dropAllLinks` | ✓ | – | – | ∘ | – | per-fabric |
| `FakeRoom`/`FakeRoomFactory` `:kuilt-session-test` | – | – | – | – | – | – | – | – | – | – | 5 |

**Answer to "is there ONE fault-injecting fabric?" — yes, and the brief's implied remedy is the wrong one.**
`FaultySeam`/`FaultyLoom` in `:kuilt-test` is public, multiplatform, seeded, and strictly *stronger* than
`ChaosSeam` on every axis but one (`duplicate`). Building a new `ChaosSeam`/`ChaosLoom` in `:kuilt-test` would
re-derive what exists. Two real gaps:

- **Mandate, not construction.** Of 17 suites, exactly **one** (`RoomConformanceSuite`) makes the injector a
  required fixture. `SeamConformanceSuite` — 38 subclasses, the widest surface in the tree — never imports it,
  so no fabric in kuilt is conformance-tested under drop / delay / reorder.
- **`ChaosSeam` is a 201-line internal re-derivation** carrying the one arm `FaultProfile` lacks, and its first
  line is `// TODO: If a test starts flaking, try seeds 1..100 to find a stable reproducer.` — documented
  seed-shopping, in the module with the heaviest chaos usage.

---

## 3. Duplicated test helpers

| Helper | sites | verdict |
|---|--:|---|
| `schedulerClock(scheduler)` | 10, 4 modules (`:kuilt-warp`, `-otel`, `-heddle`, `-planning`) | **byte-identical** `{ Instant.fromEpochMilliseconds(scheduler.currentTime) }`. Hoist to `:kuilt-test`. |
| `tick()` | 16+, `:kuilt-session` + `:kuilt-game` | Near-identical `advanceTimeBy(100); runCurrent()`. Half also do `nowMs += 100L` — a **second clock hand-synced to virtual time**; drift is silent. Hoist. |
| `mergeIsCommutative` / `pieceIsAssociative` / `pieceIsIdempotent` | 11–13 each, 24 files (mostly `:kuilt-crdt`) | Genuinely different *values*, structurally identical *and strictly weaker*: single hand-picked pair vs `LatticeLawSuite`'s 32-seed × permutation sweep, which already covers 19 of these types. Redundant coverage that reads as coverage. |
| `roundTripsThroughJson` | 24, `:kuilt-crdt` + `:kuilt-heddle` | Same 3-line shape per type. Candidate for a `SerializationRoundTripSuite` hook on the existing lattice suites. |
| `exporterFor` | 11, all `:kuilt-otel` | Same module, differing constructor args — legitimately different. |
| `record(i)` | 12, all `:kuilt-otel` | Same, legitimately different fixtures. |
| `factory(loom, scope)` | 12, `:kuilt-session` + `:kuilt-game` | `SeamRoomFactory(...)` with varying args — mostly copies; a `:kuilt-session-test` helper would take 10 of them. |
| `connectPair` | 7, all `:kuilt-websocket` jvmTest | One shared `KtorClientLoomTestSupport.kt:12` **already exists** and 6 files still hand-roll it. |
| `awaitLeader` | 8 | Correctly centralised in `RaftSimulation`/`MultiNodeRaftSim`/`VoterMesh`; not duplication. |
| `tearDown` / `setUp` | 33 / 11 | Per-fixture; not duplication. |

---

## 4. Harness discipline

Better than the brief assumes.

| Metric | Count | Note |
|---|--:|---|
| Test files constructing a real `RaftNode` outside a sim | **1** (`HeddleControlPlaneTest.kt`) | everything else goes through `FakeRaftNode` / `RaftSimulation` / `MultiNodeRaftSim` |
| Files with a `while` + `delay` poll within 8 lines | **14** | all real-IO/concurrency probes (`Nw*Concurrency`, `MeshSeamConcurrency`, `RouteLiveness`) — legitimate |
| **Real `advanceUntilIdle()` calls** (non-comment) | **376** | **242 in `:kuilt-quilter`**, 74 `:kuilt-cluster`, 21 `:kuilt-session` |
| …in files that also stand up a multi-peer topology | 11 files | plus quilter's replicator pairs, which the grep pattern misses |
| Every `advanceUntilIdle` mention inside the canonical sims | 4 | all KDoc/comments **banning** it (`RaftSimulation.kt:33,354,357`; `MultiNodeRaftSim.kt:16,244`) |
| `ALLOW-realDispatcher` markers | **58** (brief said 65) | |
| `ConcurrencyStressHarness` (`:kuilt-test/jvmMain`, 414 loc) users | **1** file | |

Two findings:

1. **`:kuilt-quilter` is the discipline hole.** 242 `advanceUntilIdle()` calls, under
   `runTest(UnconfinedTestDispatcher())`, against a replicator with a re-arming
   `delay(config.antiEntropyInterval)` loop (`Quilter.kt:601`) and multiple replicas — the exact shape
   CLAUDE.md bans twice over. There is **no lexical guard** for `advanceUntilIdle` among the 30 root-build
   `check` tasks, unlike `forbidTightRunTestTimeout`, `forbidProductionDispatcherInTests`, etc.
2. **`ALLOW-realDispatcher` reasons cluster into three families**, and one is already hoistable:
   *real-network/loopback socket needs a real IO dispatcher* (~16, irreducible) · *lost-terminal-latch race
   needs two OS threads* (~6, near-identical prose) · ***"real OS-thread concurrency stress harness"* (~9,
   across 7 files in `:kuilt-core`, `-nw`, `-mdns`, `-quilter`, `-cluster`)** — and the shared
   `ConcurrencyStressHarness` those markers describe has exactly **one** user. It is `jvmMain`-only, which is
   why the Apple-target probes could not adopt it.

---

## 5. Fixture knobs — is the property asserted at both sides of the threshold?

| Suite | Knob | Both sides? |
|---|---|---|
| `WireCodec` | declared field width | **Yes, four-sided** — at width, −1, +1, 0 (`:260,278,283,295`), plus `…RigVariesTheWidthAndNothingElse` (`:309`) proving the rig itself. Header: −1 / at / +1 (`:331,357`). **Best in tree.** |
| `Bolt` | segment byte budget | **Yes** — default 1 MiB *and* `TinySegment*` subclasses per backend (one frame/segment), plus `Asynchronous*`. Also `ONE_LOST_SEGMENT=1` vs `SEVERAL_LOST_SEGMENTS=2` (`:1674,1684`), each KDoc'd with what the other setting switches off. |
| `Seam` | `maxPayloadBytes` | **Yes** — at-budget carried (`:2712`) and over-budget refused (`:2817`), both directions, with a non-uniform fill so truncate-and-pad can't pass the size check. Weakness: `PAYLOAD_FILL_MODULUS`/`REVERSE_ORDER_BASE` are hardcoded constants, not subclass-threaded. |
| `DiscoverySource` | `awaitBudget: Duration? = 5.s`, quiescence window | **Yes, and the vacuous setting is closed by construction**: the KDoc (`:215–240`) records that `awaitBudget = null` — the setting the suite *prescribes* for real-IO harnesses — used to silently delete the whole `NoLeaveSignal` obligation; the negative wait is now its own non-nullable hook. |
| `RoomFanoutIsolation` | `awaitBudget = 2.s` (nullable), `absenceBudget = 1.s` (**non-nullable, deliberately**) | **Yes** — every absence assertion is preceded by a positive control on the same path (`:157` KDoc: *"Raising this budget is a latency accommodation, never a strengthening"*). |
| `CommutativeScheme` | `validPlaintexts()` — "the suite's one free knob" | **Yes** — every property asserts the list is non-empty first (`:118`). |
| `Lattice` | `POOL_LIMIT=14`, `GOSSIP_ONE_IN=4`, `EXHAUSTIVE_WORD_LENGTH=4`, per-binding `VacuityFloors` | **Yes, and machine-checked** — `checkVacuityFloors` asserts the generator's *own* no-op/retirement rates; `VacuityFloorSelfTest` runs a deliberately crippled arm and requires it to red. |
| `RaftStorage` | none | n/a — no knob, but also no second backend. |
| `Quilted` | `retirementIsMeaningful = false` | **No.** The `false` default is the setting at which `samplesReAssertAfterRetirement` asserts nothing; its own KDoc calls it *"an opt-out, not evidence"* (`:262`). 24 subclasses. |
| `Mesh` | `newMeshOfSize(n)` — n chosen per test | **No knob discipline**; no fixture. |

Not one knob in the sampled suites is *silently* vacuous. Every recent suite documents what its setting
switches off. The two failures are **absence of a fixture** (`Mesh`, `CloseableLifecycle`, `RaftStorage`) and
**a nullable/boolean opt-out** (`Quilted`), not a mis-tuned number.

---

## 6. Structural changes, ranked by recurrence removed

### A. Promote the anti-vacuity declaration from prose to one type in `:kuilt-conformance`, and guard it
`ObligationDeclaration` (already its own file, already reused by `WireCodecConformanceSuite`) is 90% of it:
four arms, each cross-checked against the injection hook's *return value*, so an arm cannot be self-certified.
Generalise it out of `SeamConformanceSuite`, make sealed-two-armed fixtures its documented alternative, and add
a root-build guard in the family of the existing 30: *a `*ConformanceSuite` in a published `commonMain` with
abstract hooks and no `ObligationDeclaration` / sealed fixture / `VacuityFloors` reference fails `check`.*

Would have prevented: **#2240** (two workers shipping the identical cross-segment defect because
`InMemoryBolt` cannot lose a segment) · **#2247** (the sweep, which is still open) ·
`MeshConformanceSuite` and `CloseableLifecycleConformanceSuite` being reference-only today ·
`QuiltedConformanceSuite`'s nullable opt-out across 24 subclasses ·
`RaftStorageConformanceSuite` shipping with its own admission of zero discriminating power.
Would **not** have prevented: #2592 (hash-ordered JVM-only walk) or the fixture-configuration cases.

### B. Mandate the fault injector that already exists on `SeamConformanceSuite`
Fold `ChaosSeam`'s `duplicateProbability` into `FaultProfile` as a `DuplicateProbabilistic` arm, delete
`ChaosSeam` (`kuilt-quilter/…/ChaosSeam.kt`, 201 loc, internal, 7 users) and its seed-shopping TODO, then give
`SeamConformanceSuite` a `FaultInjection` fixture in **`RoomConformanceSuite`'s exact shape** (`:281`) — sealed,
`Supported(FaultyLoom)` vs a checked declaration. 38 harnesses gain a drop/delay/reorder/duplicate pass they
do not have today.

Would have prevented: **#1819** (16 bytes leaving a `NearbySeam` permanently deaf with no `Torn` — a
`DropSpecific` + malformed-frame arm reaches it, and no Seam harness has one) · the `MuxServerLoom`
green-by-absence class (#2665) generally, since a fabric that cannot be faulted must now *declare* it.
**Do not build a new `ChaosSeam`/`ChaosLoom`** — that is the brief's one wrong prescription: the injector
exists, is public, and is strictly stronger than the internal one. The gap is mandate, not construction.

### C. Make the *executable* rig test the rule, not the mutation-receipt prose
Two suites ship a receipt today and they are not equivalent. `RaftStorageConformanceSuite`'s is a **prose
table in KDoc** (`:140–160`) — unchecked, and it already carries the admission that the properties discriminate
nothing. `VacuityFloorSelfTest` is **executable**: it runs a deliberately crippled arm and asserts the suite
reds. Twelve `*RigTest`/`*SelfTest` files already do the executable thing for 7 suites. So the rule that pays
is *every published conformance suite ships a rig test that runs it against a broken fixture and asserts a
red* — mechanically guardable (a `*ConformanceSuite` with no sibling `*RigTest`/`*SelfTest` fails), unlike
"ships a mutation receipt", which the RaftStorage case shows degrades into unverified prose.

### D. (cheap, low recurrence value) Hoist the three genuine duplicates
`schedulerClock` → `:kuilt-test` (10 byte-identical copies). `tick` → `:kuilt-test`, dropping the hand-synced
`nowMs` mirror (16 sites, a live drift hazard). Make `ConcurrencyStressHarness` `commonMain`-or-`appleMain`
rather than `jvmMain` so the ~9 hand-rolled "real OS-thread concurrency stress harness" probes can adopt it.
Retire the 24 hand-written per-type CRDT law functions in `:kuilt-crdt` in favour of the `LatticeLawSuite` /
`QuiltedConformanceSuite` bindings that already cover those types far more strongly. This removes ~1,000 lines
and one class of silent weakness (a single-pair law reading as coverage), but prevents no recorded incident.

### E. Add the missing lexical guard: `forbidAdvanceUntilIdleInMultiPeerTest`
376 real calls, 242 in the one module whose subject is timer-driven multi-replica replication. The rule is
written in CLAUDE.md and in both sims' KDoc and enforced nowhere. Given `:kuilt-quilter`'s population, this
should land as a **per-file count ratchet with a baseline** (the `forbidBareLaunchIn` / `forbidNotNullAssertion`
shape), not a hard ban.
