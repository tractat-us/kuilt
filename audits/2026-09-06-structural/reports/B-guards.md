# Audit B — the guard tasks in `build.gradle.kts`

Worktree `/Users/keddie/tractatus/kuilt-worktrees/bugs`, branch `docs/2600-depart-carries-finals`, read-only. No builds run.

## 0. Brief corrections (stated before the findings, per instruction)

| Brief says | Measured |
|---|---|
| "~30 guards … with grandfathered baselines and `// ALLOW-xxx` escape markers" | Exactly **30** guards, but only **7 declare a baseline** (2 of them empty: `verifySamplesAreRun` build.gradle.kts:2699, `forbidSuspendCallUnderLock` :5702) and only **8 distinct ALLOW tokens** exist. **17 of 30 have neither.** The "baseline + marker" shape is a minority pattern, not the norm. |
| "the list file may be incomplete" | It is complete. 30 `by tasks.registering` (no `tasks.register`, no other `check` wiring in the root build) = 30 names in `guards.txt` = 30 `dependsOn` at :8215–8244. |
| CLAUDE.md "admits most guards are backstops … defeated by one helper hop" | That sentence in CLAUDE.md is scoped to the **three cancellation guards** only. The generalisation is nevertheless *understated*: the helper-hop/typealias limit is stated in the headers of at least **7** guards (:3556, :4123, :5250, :6230, :7852, :8045, :3708). |
| — (not in brief) | `verifyTestResultParity` is `check`-wired but **stands down unless `CI` is set or it is named** (:7689–7695). A local `./gradlew build` runs it as a no-op. |

## 1. The thirty guards

Line ranges are header-comment start → end of task block. `Iss` = issues citing the guard **by name** (`gh issue list --search`, index lags). Baseline = grandfathered entries (files); ALLOW = live marker sites in `.kt` (files in parens).

| # | Guard | Lines (n) | Cites | Property (one sentence) | Baseline | ALLOW | Class | Own header's blind spot |
|---|---|---|---|---|---|---|---|---|
| 1 | `forbidUnboundedSwatchDelivery` | 856–897 (42) | #701/#741/#655 · Iss 4 | Inbound frames are delivered through the bounded `Spool`, never a `Channel<Swatch>(UNLIMITED)`. | — | — | **T** | (none stated) |
| 2 | `forbidPortProbeRebind` | 898–1056 (159) | #1590/#1586 · Iss 5 | No probe-a-free-port-then-rebind TOCTOU; bind 0 and read back. | — | — | **T** | ":924 an entry here is a file the guard cannot see" |
| 3 | `forbidSourcelessKmpTarget` | 1057–1083 (27) | #1014 · Iss 4 | No declared KMP target whose main compilation has no source (breaks post-merge publish). | — | — | **P** | (none stated — reads the Gradle model, not text) |
| 4 | `verifyDocCitations` | 1084–1913 (830) | #1792/#1825/#2256 · Iss 17 | A `verbatim from`/`condensed from` block still matches the source it names. | — | — | **L** | ":1260 a `$`-template whose … (none occurs today)" |
| 5 | `forbidUncitedDocCodeBlock` | 1914–2144 (231) | #2583 · Iss 1 | Every code block in a live reference doc carries a provenance marker. | **56 files / 201 sites** | — | **L** | (complement of #4; fence-tracking limits shared) |
| 6 | `verifyModuleDocLinks` | 2145–2285 (141) | #2527/#2507 · Iss 1 | No `module.md` carries a bare `[Identifier]` KDoc link Dokka cannot resolve. | — | — | **P** | ":2192 a `commonMain`-only include structurally cannot reach…" |
| 7 | `verifySampleLinks` | 2286–2699 (414) | #2259 · Iss 3 | Every `@sample` tag names a sample Dokka can actually resolve. | — | — | **P** | ":2303 a mid-line tag Dokka does not parse **at all** — invisible to Dokka *and* to this guard" |
| 8 | `verifySamplesAreRun` | 2700–2920 (221) | #2116 · Iss 2 | Every callable `@sample` is actually called by a test. | 0 (empty) | — | **L** | ":2653 a sample the detector does not recognise is exempted with no output" |
| 9 | `forbidRunCatchingCancellableUnderNonCancellable` | 2921–3028 (108) | #1803 · Iss 9 | No `runCatchingCancellable` inside a `NonCancellable` shield (it aborts the cleanup the shield guarantees). | — | — | **C** | ":2948 three known limits" — token-only; blind through a helper |
| 10 | `forbidCancellationRethrowAroundBound` | 3029–3111 (83) | #2292 · Iss 2 | No cancellation rethrow written directly around a `withTimeout` (kills the handler the bound exists for). | — | — | **C** | ":3055 the scanner sees ONE lexical block; a helper defeats it" |
| 11 | `forbidBareRunCatching` | 3112–3239 (128) | #1329 · Iss 4 | No stdlib `runCatching` anywhere — use `runCatchingCancellable`. | — | 17 (8) | **C** | (none; a pure token ban) |
| 12 | `forbidKotlinAssert` | 3240–3348 (109) | #2119/#2112 · Iss 1 | No `kotlin.assert` — whether it checks anything is a property of the launcher. | — | 0 | **C** | (none; a pure token ban) |
| 13 | `forbidProductionDispatcherInTests` | 3349–3601 (253) | #1934/#340 · Iss 8 | No `Dispatchers.{Default,IO,Main,Unconfined}`/`GlobalScope` in test sources. | — | **58 (55)** | **C** | ":3556 taint does not propagate through a helper, a field or a function return" |
| 14 | `forbidHashOrderedSeededDraw` | 3602–3757 (156) | #2592 · Iss 0 | A seeded generator must not draw from a hash-ordered collection (target-dependent trajectory). | — | 0 | **C** | ":3708 cannot see a gate read behind a helper; not greppable by container name" |
| 15 | `forbidRuntimeSelfSkippingProbe` | 3758–4067 (310) | #2621 · Iss 1 | A `-P`-gated probe must be gated at the **task** level, never decide at runtime (self-skip reports *passed*). | — | 0 | **T/P** | ":3703 what it does NOT check … cannot see a gate read behind a helper in another file" |
| 16 | `forbidCancellationSwallowingCatch` | 4068–4221 (154) | #2598/#2535 · Iss 0 | No unguarded `catch (…: IllegalStateException)` — `CancellationException` *is* one. | — | 7 (6) | **C** | ":4123 the 'cannot see through a helper' limit; `*.kts` unscanned" |
| 17 | `forbidTightRunTestTimeout` | 4222–5022 (801) | #1739/#2482/#2462 · **Iss 12** | No bare duration literal as a `runTest` timeout — use `TEST_WEDGE_BACKSTOP`. | 2 | 0 | **T+C** | ":4292 the ratchet does not auto-tighten" |
| 18 | `forbidCoroutineLaunchDuringConstruction` | 5023–5663 (641) | #2465/#2482/#2480 · Iss 0 | No coroutine started from a property initialiser or an `init` block above a construction-initialised field. | 7 + 4 | 0 | **C** | ":5228 a lambda constructed under the lock does not run under it" |
| 19 | `forbidSuspendCallUnderLock` | 5664–5878 (215) | #2480 · Iss 0 | No `suspend` call inside a thread-bound lock body. | 0 (empty) | 0 | **T/C** | ":5241 `.close(` is not a token — `Seam.close()` suspends, `Channel.close()` does not, lexically identical" |
| 20 | `forbidDemotedFieldTrail` | 5879–6302 (424) | #2420/#2425 · Iss 0 | A curated `:kuilt-nw` field-trail log line stays at INFO+ and does not silently disappear. | — | — | **T** | ":5911 does NOT check the CONTENT of a line" |
| 21 | `forbidNotNullAssertionInUnresolvedSource` | 6303–6540 (238) | #2039/#2471 · Iss 4 | No `!!` under a module's `src/`. | 3 | 0 | **C** | ":6230 cannot see through a typealias or a helper" |
| 22 | `forbidLintFrontendSkew` | 6541–6728 (188) | #2595/#2701 · Iss 3 | Red when AGP's bundled lint Kotlin frontend catches up with the project's Kotlin (the CLAUDE.md paragraph stops being true). | — | — | **P** | ":6693 cannot see a backend that changes IC layout without the version moving" |
| 23 | `forbidCleanGateMeasurementSkew` | 6729–6850 (122) | #2692/#1914 · Iss 2 | Red when either premise of CLAUDE.md's `clean`-leads-the-gate measurement moves. | — | — | **P** | (same class as #22) |
| 24 | `forbidBoltRejoiningTheLattice` | 6851–6909 (59) | #2212/#2210 · Iss 1 | `:kuilt-bolt` main sources never join the CRDT lattice (`piece`). | — | — | **T** | ":6866 main sources only; test sources legitimately build `Rga` fixtures" |
| 25 | `verifySkillDescriptionBudget` | 6963–7057 (~96)¹ | #2662/#2541/#2572 · Iss 0 | A `SKILL.md` `description:` stays under the 1,536-char listing cap and does not break its YAML plain scalar. | — | — | **L** | ":6981 lexical, not a YAML parse" |
| 26 | `verifyModuleTable` | 6911–6962 + 7058–7345 (~340)¹ | #2257 · Iss 6 | Every `:kuilt-*` module in `settings.gradle.kts` has a row in CLAUDE.md's module table, and vice versa. | 0 (deliberate) | — | **L** | ":6949 a multi-line HTML comment wrapping real rows would still satisfy this guard" |
| 27 | `verifySeamHarnessCoverage` | 7346–7655 (310) | #1871/#2185 · Iss 3 | Every production `Seam` impl is *enumerated* in `docs/seam-harness-coverage.md` (38 rows). | — | — | **L** | ":7354 It does NOT prove COVERAGE — only that the names exist" |
| 28 | `verifyTestResultParity` | 7656–7793 (138) | #2185/#2183 · Iss 0 | A `commonTest` class that produced results on one target did not silently produce none on another. | — | — | **P** | ":7701 a green that does not say what it compared is one absent artifact from vacuous" |
| 29 | `forbidBareSeamStateFlow` | 7794–7984 (191) | #2627/#1803 · Iss 3 | A production `MutableStateFlow<SeamState>` must be a `SeamStateGate` (check-flag-then-write *is* the race). | — | **10 (10)** | **T** | ":7852 cannot see a flow reached through a typealias" |
| 30 | `forbidBareLaunchIn` | 7985–8210 (226) | #1803/#1788 · Iss 0 | No bare `.launchIn(` in production — a long-lived pump goes through `pumpIn`. | **11 files / 20 sites** | 0 | **T** | ":8045 cannot see a pump spelled `scope.launch { flow.collect { … } }`" |

¹ The two headers are adjacent and out of order: `verifyModuleTable`'s header block sits at :6911–6962, *above* `verifySkillDescriptionBudget`'s task at :6985. Line attribution between the two is approximate; the pair totals 436.

### Two guards whose live population is 100% exempted

- **`forbidBareSeamStateFlow`**: 11 bare `MutableStateFlow<SeamState>` in production, **9 production files carry `ALLOW-bareSeamState`**. The guard fires on nothing today; its green says only "no *new* site". CLAUDE.md claims "nine flows are exempt" — that matches, but the claim reads as a small residue when it is in fact the whole population.
- **`forbidBareLaunchIn`**: 20 sites in 11 files, all baselined; **zero** `ALLOW-bareLaunchIn` in `.kt`. Same shape.

## 2. How much of the file is guard code

| Region | Lines | Comment | Code | Comment % |
|---|---|---|---|---|
| Ordinary build config (plugins, kover/dokka aggregation, version, test backstops) | 1–64 (**64**) | 29 | 31 | 45% |
| `TimeoutShapedFailureReporter` (diagnostics, #1931 — not a guard) | 65–229 (**165**) | 61 | 91 | 37% |
| Guard plumbing (stamps, `KotlinCodeScanner`, `KdocScanner`, 3 timeout scanners) | 230–855 (**626**) | 249 | 353 | 40% |
| 30 guard task blocks | 856–8210 (**7,355**) | 2,523 | 4,592 | 34% |
| `check` wiring | 8211–8246 (**36**) | 1 | 34 | 3% |
| **Whole file** | **8,246** | 2,863 | 5,101 | 35% |

**Guard code = 8,017 / 8,246 = 97.2%.** Ordinary build configuration is **64 lines (0.8%)**. Growth: 9 (06-01) → 165 → 1,679 → 6,535 (09-01) → **8,246 today** — +1,711 lines in the five days since 09-01 (~342/day), across **65 commits in 60 days**. For scale, `.github/workflows/ci.yml` is a further 1,338 lines.

## 3. Subsets, near-duplicates, and guards whose remedy type already exists

| Family | Guards | Lines | Collapse to |
|---|---|---|---|
| **Cancellation discipline** | #9, #10, #11, #16 (+#19 partly) | 473 | **One** type-resolving detekt rule. #9 and #10 are literally two halves of one rule (shielded / unshielded dual, stated at :3031); #16 is the same rule with a narrower catch type; #11 is `ForbiddenMethodCall`. All four say in their own headers that one helper hop defeats them. |
| **Dokka warnings re-implemented** | #6, #7 | 555 | `dokka { failOnWarning.set(true) }`. Both headers say Dokka *already emits* the warning and `failOnWarning` (unset repo-wide) drops it (:2151, :2291). Caveat: #7's limit 1 (a mid-line `@sample` Dokka never parses as a tag) is invisible to Dokka too, so ~20 lines of it survive. |
| **Doc-citation complements** | #4, #5 | 1,061 | One task, two passes — they already share the `moduleDocFiles()` provider (:2126) and duplicate fence tracking. |
| **Inventory completeness** | #26, #27, #25 | ~840 | One parameterised `verifyInventory(derivedSet, markdownSection, rowShape)` task. All three are "a hand-maintained markdown list must equal a set derived from the build". |
| **Meta-premise staleness** | #22, #23 | 310 | A nightly workflow, exactly like the existing `.github/workflows/citation-staleness.yml`. Neither is a property of the code; both watch a *prose* claim in CLAUDE.md. |
| **Count-ratchet boilerplate** | #17, #18, #19, #21, #30 | ~200 of the 2,121 | A shared `CountRatchet` helper. Three headers say verbatim "the shape `forbidTightRunTestTimeout` uses" (:5031, :5671, :6273); the dangling/stale/regression reporting is copy-pasted five times. |

**Remedy types that already exist and are public** — in every case the repo shipped the primitive as *available* and then wrote a lexical guard to make it *mandatory*, when an un-bypassable form was reachable:

| Guard | Existing type | The un-bypassable form not taken |
|---|---|---|
| #29 `forbidBareSeamStateFlow` | `SeamStateGate` — `kuilt-core/.../SeamStateGate.kt:94` | `SeamStateGate.state` returns `StateFlow<SeamState>` (:104). Return a `GatedStateFlow` subtype with a non-public constructor and declare `Seam.state: GatedStateFlow` (`Seam.kt:116`) — a bare `MutableStateFlow<SeamState>` then **cannot satisfy the interface**. Small diff; retires 191 lines and 9 markers. |
| #30 `forbidBareLaunchIn` | `pumpIn` — `kuilt-core/.../PumpIn.kt:133` | Have `Seam.incoming` return a wrapper whose only terminal operator is `pumpIn`. Weaker (a `Flow` obtained elsewhere escapes), but it covers the population the guard baselines. |
| #1 `forbidUnboundedSwatchDelivery` | `Spool` — `kuilt-core/.../Spool.kt:36` | Fabrics take a `Spool<Swatch>` from a factory rather than constructing their own delivery channel. |
| #11 `forbidBareRunCatching` | `runCatchingCancellable` — `RunCatchingCancellable.kt:26` | Nothing type-shaped; this is a forbidden-call rule (C). |
| #17 `forbidTightRunTestTimeout` | `TEST_WEDGE_BACKSTOP` — `:kuilt-test` | A `kuiltRunTest { }` wrapper in `:kuilt-test` with **no** `timeout` parameter. Then only "don't call kotlinx `runTest` directly" needs a linter, and pass 2 (wrapper defaults, 0 tolerance) disappears entirely. |
| #19 `forbidSuspendCallUnderLock` | `kotlinx.atomicfu.locks.withLock` | `withLock` is **`inline`**, which is precisely why a suspend call inside it compiles. A *non-inline* `fun <T> ReentrantLock.withLockNoSuspend(block: () -> T): T` makes a suspend call in the body a **compile error**. This is the single strongest T-fix in the set: it converts a 215-line scanner that admits it cannot tell `Seam.close()` from `Channel.close()` (:5241) into a type rule with no false negatives. |

## 4. What would remain after T→types and C→detekt 2.x

| Class | Guards | Lines in task blocks |
|---|---|---|
| **T** — enforceable by a type/API shape | 10 (#1, 2, 3ᵃ, 15, 17, 19, 20, 24, 29, 30) | 2,454 |
| **C** — needs a type-resolving linter / compiler plugin | 9 (#9, 10, 11, 12, 13, 14, 16, 18, 21) | 1,870 |
| **L** — genuinely lexical/documentary | 6 (#4, 5, 8, 25, 26, 27) | 2,028 |
| **P** — process/CI/tool-config | 5 (#3, 6, 7, 22, 23, 28)ᵃ | 1,003 |

ᵃ #3 `forbidSourcelessKmpTarget` is counted once under P; it reads the Gradle model, and the structural fix is for `kuilt.kmp-library` to **not declare** a target with no source.

**Answer.** Replacing T with types and C with detekt 2.x under `dev.detekt` retires **19 guards / 4,324 lines** of task blocks, plus ~304 lines of now-unused plumbing (`RunTestTimeoutScanner` :552–668, `CancellationRethrowAroundBoundScanner` :669–732, `RunTestWrapperTimeoutScanner` :733–855). Folding the P class into tool config and workflows (Dokka `failOnWarning` for #6/#7, a nightly for #22/#23, a CI step for #28 which already only runs under `CI`) retires **5 more / 1,003 lines**.

> **Remaining: 6 guards, ~2,030 lines of task blocks + ~322 lines of plumbing ≈ 2,350 lines** — the doc-citation pair, `verifySamplesAreRun`, and the three inventory checks. Applying the family collapses in §3 to those six takes it to **3 tasks, ~1,400 lines**. Against 8,246 today.

**Extraction into `build-logic/` is feasible, and the precedent is already there.** `build-logic/src/main/kotlin/` holds two typed tasks with full KDoc — `GenerateKarmaOrphanGuard.kt` and `GenerateKarmaTimeouts.kt` — wired from `kuilt.kmp-library.gradle.kts:157–178`. Nothing about the guards needs the root script; they use only `FileTree`, `Regex` and `error()`.

**Are any unit-tested today? No.** `build-logic/` has **`src/main` only — there is no `src/test`**, and no test task anywhere in the included build. The closest thing is four in-task positive controls that run on *every* `check`: `NotNullAssertionScanner.selfTestFailures()` (:6160, asserted :6347), `CancellationSwallowingCatchScanner.selfTestFailures()` (:4063, asserted :4144), `ConstructionLaunchControls.verify()` (:4764, asserted :5093), `LockBodyControls.verify()` (:5485, asserted :5704). These are already `(name, source, expected)` case tables — **directly portable to JUnit with no redesign**. The repo is therefore already paying for a test suite on every build that it cannot run, debug, or extend in isolation.

## 5. Recommendation (one, prioritised)

**Do not burn down the guards. Move them, then let two tool adoptions retire two-thirds of them — and change the rule that mints the next one.** In order, each step landable alone:

1. **Mechanical extraction, zero behaviour change (highest value, lowest risk).** Move all 30 guards + the plumbing into `build-logic/src/main/kotlin/guards/`, one file per family, and promote the four existing case tables into `build-logic/src/test/`. This is the precondition for everything else: today no guard can be changed with confidence because none can be executed without a full `check`, and the file is taking ~340 lines/day of append-only edits from parallel sessions. It also ends the merge-conflict surface — 65 commits in 60 days on one 8k-line file.
2. **Adopt detekt 2.x under `dev.detekt`.** CLAUDE.md already states it pins this repo's exact Kotlin and would restore type resolution in full; #2540's measurement was against detekt **1.23.8**, and the argument that retired detekt does not carry over. This retires 9 guards / 1,870 lines *and*, more importantly, retires the **class that generates the most new guards** — four cancellation guards in one quarter, each admitting in its own header that one helper hop defeats it.
3. **Ship the six type fixes in §3**, `withLockNoSuspend` and `GatedStateFlow` first: they are the two where the guard's own header names a defect the type makes impossible (`.close(` ambiguity at :5241; typealias blindness at :7852). Sweep the 9 `ALLOW-bareSeamState` files and the 20 baselined `.launchIn` sites in the same PR — otherwise the ratchets stay green over a population they never protected.
4. **Move the P class out of Gradle**: `failOnWarning` for the two Dokka guards, a nightly for the two meta-premise guards (the `citation-staleness.yml` pattern already exists), a CI step for `verifyTestResultParity` (which already refuses to run locally).
5. **Change the minting rule, in CLAUDE.md, in the same PR as step 1.** Before a new `check`-wired scan is written, classify the property T/C/L/P and require a written reason why the stronger class was rejected. The evidence that this is the actual generator: **17 of 30 guards protect a property for which a type or a linter rule was the stronger tool**, and in five cases the type *already existed in `:kuilt-core`* and was left optional. The lexical guard is the reflex; it is also the only class whose cost compounds — every new one adds a baseline that must be re-verified, an ALLOW vocabulary, a paragraph of prose, and a blind spot that will be re-discovered as a fresh incident.

One thing worth saying plainly about the maintainer's "burndown finds as many issues as it fixes": that is not a failure of the burndown. Six guards (#14, #16, #18, #19, #28, #30) are cited by **zero** issues and three more by one, while `verifyDocCitations` alone is cited by 17. The guards that generate issues are the documentary ones with large baselines; the lexical *code* guards mostly generate CLAUDE.md paragraphs, not issues. Steps 2–3 remove the paragraph-generating half.
