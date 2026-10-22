# D — Issue clusters, weeks 31–36 (tractat-us/kuilt)

Corpus: 487 issues created 2026-07-27 → 2026-09-07 (#1723–#2747). Bodies (first 1,800 chars),
labels, state and last comment fetched for all 540 issues back to #1607 via GraphQL.
Classification is keyword-over-title+body, hand-audited class by class.

## Headline: the hypothesis is half right, and the plateau has a different arithmetic

**The plateau is not a steady state.** It was *built* in a four-week filing surge and has been
draining for three weeks:

| weeks | opened | closed | net | cumulative open at end |
|---|---|---|---|---|
| W22–29 | 653 | 614 | +39 | 39 |
| **W30–33** | **405** | **265** | **+140** | **179** |
| W34–37 | 146 | 168 | **−22** | 157 |

Per week since: W34 +6, W35 −6, W36 −21, W37 −1. The "155–185 for six weeks" is the tail of the
surge, and the last three weeks are a 15% decline, not a plateau. **Median time-to-close since W31
is 28.1 hours** (n=348). A backlog held up by unaddressed root causes does not close its issues in
a day. So "steady burndown against a fixed level" mis-describes the arithmetic: filing and closing
are now roughly matched and slightly negative, and the level is residue.

The surge is fully attributable to three audits: the W31 Raft adversarial-input sweep (~25 issues,
#1812–#1972), the #1816 peers-collapse TCK obligation fan-out, and W32–33's #2247 conformance audit
(20 children) + #2210 bolt epic (12) + the crdt canonical sweep.

**50% of everything filed since W31 is about the verification apparatus, not the library**
— 244 of 487 in classes (a)(b)(c)(h)(i)(k)+skill vs 243 in the runtime classes. That is the real
generator: an audit program whose output *is* issues.

## Class table (since W31)

| cls | root-cause class | opened | still open | dup-flagged | examples |
|---|---|---|---|---|---|
| a | test vacuity / conformance gap | **125** | 29 | 62 | [#2247](https://github.com/tractat-us/kuilt/issues/2247) [#2601](https://github.com/tractat-us/kuilt/issues/2601) [#2722](https://github.com/tractat-us/kuilt/issues/2722) |
| e | seam lifecycle / roster / concurrency race | 60 | 22 | 30 | [#1816](https://github.com/tractat-us/kuilt/issues/1816) [#2648](https://github.com/tractat-us/kuilt/issues/2648) [#2655](https://github.com/tractat-us/kuilt/issues/2655) |
| j | algorithm/spec defect (Raft/CRDT/heddle) | 43 | 13 | 18 | [#2087](https://github.com/tractat-us/kuilt/issues/2087) [#2086](https://github.com/tractat-us/kuilt/issues/2086) [#2594](https://github.com/tractat-us/kuilt/issues/2594) |
| k | build/CI/process mechanics | 42 | 13 | 21 | [#1913](https://github.com/tractat-us/kuilt/issues/1913) [#2540](https://github.com/tractat-us/kuilt/issues/2540) [#2595](https://github.com/tractat-us/kuilt/issues/2595) |
| f | unvalidated peer input / wire invariant | 39 | 10 | 17 | [#1822](https://github.com/tractat-us/kuilt/issues/1822) [#1832](https://github.com/tractat-us/kuilt/issues/1832) [#2650](https://github.com/tractat-us/kuilt/issues/2650) |
| h | stale doc / claim / citation | 31 | 7 | 9 | [#1792](https://github.com/tractat-us/kuilt/issues/1792) [#2583](https://github.com/tractat-us/kuilt/issues/2583) [#2647](https://github.com/tractat-us/kuilt/issues/2647) |
| n | other | 31 | 9 | 13 | [#2047](https://github.com/tractat-us/kuilt/issues/2047) [#2416](https://github.com/tractat-us/kuilt/issues/2416) [#2707](https://github.com/tractat-us/kuilt/issues/2707) |
| **o** | **silent failure / observability gap** *(added)* | 24 | 9 | 11 | [#1892](https://github.com/tractat-us/kuilt/issues/1892) [#2420](https://github.com/tractat-us/kuilt/issues/2420) [#2524](https://github.com/tractat-us/kuilt/issues/2524) |
| c | hang / flake / load-sensitivity | 19 | 6 | 5 | [#2226](https://github.com/tractat-us/kuilt/issues/2226) [#2466](https://github.com/tractat-us/kuilt/issues/2466) [#2118](https://github.com/tractat-us/kuilt/issues/2118) |
| **p** | **performance & wire cost** *(added)* | 15 | 8 | 3 | [#2044](https://github.com/tractat-us/kuilt/issues/2044) [#2111](https://github.com/tractat-us/kuilt/issues/2111) [#2193](https://github.com/tractat-us/kuilt/issues/2193) |
| b | cross-target determinism & canonical encoding | 14 | 3 | 11 | [#1957](https://github.com/tractat-us/kuilt/issues/1957) [#2592](https://github.com/tractat-us/kuilt/issues/2592) [#2715](https://github.com/tractat-us/kuilt/issues/2715) |
| d | cancellation-exception discipline | 11 | 1 | 4 | [#1834](https://github.com/tractat-us/kuilt/issues/1834) [#2292](https://github.com/tractat-us/kuilt/issues/2292) [#2535](https://github.com/tractat-us/kuilt/issues/2535) |
| **q** | **durability / storage correctness** *(added)* | 9 | 4 | 3 | [#2120](https://github.com/tractat-us/kuilt/issues/2120) [#2506](https://github.com/tractat-us/kuilt/issues/2506) [#2141](https://github.com/tractat-us/kuilt/issues/2141) |
| i | lexical-guard maintenance | 8 | 0 | 4 | [#2256](https://github.com/tractat-us/kuilt/issues/2256) [#2480](https://github.com/tractat-us/kuilt/issues/2480) [#2627](https://github.com/tractat-us/kuilt/issues/2627) |
| l | feature / enhancement | 7 | 2 | 3 | [#2210](https://github.com/tractat-us/kuilt/issues/2210) [#2213](https://github.com/tractat-us/kuilt/issues/2213) |
| **s** | **agent-skill routing surface** *(added)* | 5 | 1 | 1 | [#2541](https://github.com/tractat-us/kuilt/issues/2541) [#2572](https://github.com/tractat-us/kuilt/issues/2572) [#2662](https://github.com/tractat-us/kuilt/issues/2662) |
| m | API design smell | 4 | 2 | 1 | [#2323](https://github.com/tractat-us/kuilt/issues/2323) [#2687](https://github.com/tractat-us/kuilt/issues/2687) |

(g) *duplication* is not a class here — it cuts across all of them, so it is a **flag**: 216/487 =
**44%** carry an explicit re-derivation marker; 94 name a second/third site outright.

Classes added beyond the brief's list: **(o) silent failure / observability gap** (a refusal or
failure path that emits nothing — 24 issues, its own recurring root), **(p) performance & wire
cost**, **(q) durability / storage correctness**, **(s) agent-skill routing surface**.

## Spawn: 50.3% of issues since W31 were filed as a consequence of another fix

245 / 487 carry an explicit provenance phrase ("found while", "follow-up to", "the fix for #N",
"surfaced by", "same class as #N", "#N landed…"). That is a **lower bound** — it only counts
phrase-matched provenance.

Top parents by distinct children (deduped):

| parent | kind | children | what it is |
|---|---|---|---|
| [#2247](https://github.com/tractat-us/kuilt/issues/2247) | issue | **20** | Audit every conformance suite for properties the in-memory reference satisfies for free |
| [#2210](https://github.com/tractat-us/kuilt/issues/2210) | epic | **12** | `:kuilt-bolt` epic |
| [#1712](https://github.com/tractat-us/kuilt/issues/1712) | issue | 4 | Liveness vocabulary has no self-attribution |
| [#1693](https://github.com/tractat-us/kuilt/issues/1693) | issue | 3 | heddle quiesce/ack fence |
| [#1739](https://github.com/tractat-us/kuilt/issues/1739) | issue | 3 | 293 hand-rolled 5 s runTest budgets |
| [#1824](https://github.com/tractat-us/kuilt/pull/1824) | **PR** | 3 | forbid `runCatchingCancellable` inside a `NonCancellable` shield |
| [#1816](https://github.com/tractat-us/kuilt/issues/1816) | issue | 3 | Seam contract must collapse peers before latching Torn |
| [#1859](https://github.com/tractat-us/kuilt/pull/1859) | **PR** | 3 | state peers-collapse + close-cancellation obligations in the TCK |
| [#2044](https://github.com/tractat-us/kuilt/issues/2044) | issue | 3 | ORSet/ORMap have no delta mutator |
| [#2305](https://github.com/tractat-us/kuilt/issues/2305) | issue | 3 | `ScopedCloseable._closed` is an unguarded var |
| [#2366](https://github.com/tractat-us/kuilt/issues/2366) | issue | 3 | heddle generation move abandons transfer rows |
| [#1917](https://github.com/tractat-us/kuilt/issues/1917) | issue | 3 | `MDNSServiceDiscoverer.departures()` never emits |
| [#2511](https://github.com/tractat-us/kuilt/pull/2511) | **PR** | 3 | `StoreKey` stored losslessly |

Two of the top three parents are **audits**, not fixes. Their children are the audit's *output*.

## Timelines — the direct test of "roots not addressed"

Three distinct generators, and they need different verdicts.

### G1 — audit frontier (the structural fix working as designed)
A TCK obligation or guard lands and *enumerates* its own violations, each filed separately.
- **(f) wire invariant — CLASS ENDED.** First W24 ([#228](https://github.com/tractat-us/kuilt/issues/228)); the W31 burst of 15 (#1812 #1817 #1818 #1820 #1829 #1831 #1832 #1868 #1876 #1880 #1881 #1887 #1911 #1912 #1972) is the *finding*, and [#1822](https://github.com/tractat-us/kuilt/issues/1822) explicitly chose "a codec TCK, not a lint rule" → `WireCodecConformanceSuite`. **After the TCK: exactly one recurrence, [#2650](https://github.com/tractat-us/kuilt/issues/2650)** (W36), and it is "enforced in the handler, not the wire type" — a known rule not applied, not a new derivation. 1 open of 39. This is the model.
- **(h) stale doc/citation.** First W25 ([#494](https://github.com/tractat-us/kuilt/issues/494)); `verifyDocCitations` [#1792](https://github.com/tractat-us/kuilt/issues/1792) (W31). 21 issues after — but each *widens the guard's reach* (module.md #2256, root .md #2267, test KDoc #1997, `@sample` #2259, line-ranges #2281, uncited blocks #2583), not the same drift recurring. Frontier expansion, and it converges: 3 open of 32.
- **(b) canonical/cross-target.** First W26 ([#752](https://github.com/tractat-us/kuilt/issues/752)); `CanonicalGoldenVectorTest` from [#1957](https://github.com/tractat-us/kuilt/issues/1957) (W31). Its W31–32 children are the audit output. **After: 4 — and the residual is scope, not recurrence:** [#2715](https://github.com/tractat-us/kuilt/issues/2715) "three modules outside the zoo" and [#2557](https://github.com/tractat-us/kuilt/issues/2557) "no golden vectors outside `:kuilt-crdt`". The digest binds one module.

### G2 — genuine re-derivation in a second/third module (the hypothesis, ~19% of new issues)
94 issues name a second site outright. Sharpest receipts:
[#2240](https://github.com/tractat-us/kuilt/issues/2240) — both mmap backends shipped the *same* cross-segment defect independently, because `InMemoryBolt` cannot reach it; [#1821](https://github.com/tractat-us/kuilt/issues/1821) — duplicate-id eviction fixed in 1 of 3 peer-bytes paths; [#2286](https://github.com/tractat-us/kuilt/issues/2286) — three more `#1834`-shaped teardowns; [#2654](https://github.com/tractat-us/kuilt/issues/2654) — "five fabrics hand-roll one today"; [#2747](https://github.com/tractat-us/kuilt/issues/2747) — three relay envelopes double their payload outside `:kuilt-raft`.

### G3 — the remedy is itself blind (47 issues; the real recurring root)
This is where the maintainer's hypothesis is *understated*. The pattern is not "fix an instance,
miss the class" — it is **"ship a lexical or positional remedy for a type-level property, and the
remedy has a blind spot that is itself filed as the next issue."**
- **(d) cancellation.** Primitive `runCatchingCancellable` W27 ([#1080](https://github.com/tractat-us/kuilt/issues/1080)/[#1086](https://github.com/tractat-us/kuilt/issues/1086)) → detekt ban that **never fired** ([#1194](https://github.com/tractat-us/kuilt/issues/1194)/[#1329](https://github.com/tractat-us/kuilt/issues/1329), proved dead by [#1934](https://github.com/tractat-us/kuilt/issues/1934)) → CLAUDE.md paragraph, which [#1835](https://github.com/tractat-us/kuilt/issues/1835) shows **"followed literally, reintroduces the #1803 bug in a form the guard cannot see"** → [#1847](https://github.com/tractat-us/kuilt/issues/1847) "the carve-out covers only the SHIELDED case" → `forbidCancellationRethrowAroundBound` ([#2292](https://github.com/tractat-us/kuilt/issues/2292), W33) → `forbidCancellationSwallowingCatch` ([#2598](https://github.com/tractat-us/kuilt/issues/2598), W36, prompted by [#2535](https://github.com/tractat-us/kuilt/issues/2535) — `catch (IllegalStateException)` swallows cancellation, five probes destroying their own wedge reports). **12 occurrences after the primitive, 6 after the first guard.** Every guard is defeated by one helper hop, and CLAUDE.md says so.
- **(e) seam lifecycle / roster.** [#1816](https://github.com/tractat-us/kuilt/issues/1816) states the peers-collapse obligation (W31) → 5 fabrics immediately fail it (#1849 #1850 #1851 #1853 #1854) → **15 more after** (#2372 #2432 #2436 #2443 #2456 #2536 #2538 #2546 #2618 #2626 #2643 #2648 #2654 #2655 #2716). The killer is [#2648](https://github.com/tractat-us/kuilt/issues/2648): *"NwSeam.close latches Torn BEFORE collapsing peers — **the exact ordering violation `Seam.peers`' KDoc predicts the TCK cannot see**."* The obligation was written, the blind spot was documented next to it, and the violation shipped anyway. [#2546](https://github.com/tractat-us/kuilt/issues/2546): "the guard is a snapshot, not an invariant."
- **(i) guard maintenance is a tax, not a class.** 16 issues, 0 open — self-limiting, but every new guard costs ≈0.6 follow-up issues: #2020 (a fourth ceiling *invisible to* `forbidTightRunTestTimeout`), #2072/#2374 (`forbidSourcelessKmpTarget` ordering), #2399 (#2039's shape through the new guard's back door), #2411 (scans gitignored scratch), #2482 (the #2462 guards miss a non-terminal `init { launch }`), #2630 (main red: guard vs block, zero file overlap), #2701/#2702 (the skew guard **confirms itself** on an unanchored alias match).

## What would end each class

| cls | structural change | already proposed? |
|---|---|---|
| a | Make the **coverage registry** total: a `Seam`/`Room`/`Bolt` impl that is not bound to a suite fails the build, and a gap hook must be a two-armed sealed value (`Gap`/`InapplicableByDesign`), never nullable. Every obligation asserted on **both roles**, not the host. | partly — [#2437](https://github.com/tractat-us/kuilt/issues/2437)/[#2441](https://github.com/tractat-us/kuilt/issues/2441) closed, [#2625](https://github.com/tractat-us/kuilt/issues/2625) + [#2628](https://github.com/tractat-us/kuilt/issues/2628) open; [#2568](https://github.com/tractat-us/kuilt/issues/2568) did the sealed-arm half |
| b | One `CanonicalCodecConformanceSuite` in `:kuilt-conformance` that every `@Serializable` wire type subclasses, with golden vectors generated on **native + wasm**, not JVM. | yes — [#2557](https://github.com/tractat-us/kuilt/issues/2557) (open) names exactly this |
| d | Delete the choice: make `Seam.close`/`sendTo`/`weave` **return** a result rather than throw, so a callee-minted cancellation is not expressible; or ship one `closeBestEffort { }` inline function and ban a bare `try` around a close. Guards cannot decide this lexically. | partial — [#1826](https://github.com/tractat-us/kuilt/issues/1826) (closed, obligation only), [#2518](https://github.com/tractat-us/kuilt/issues/2518) open |
| e | `Seam.peers` should not be a writable field on 12 impls. Give `:kuilt-core` a `PeerRoster` type that **owns** the tear ordering (collapse-then-latch, atomically, with `selfId` non-removable) the way `SeamStateGate` owns the state write. Five fabrics currently hand-roll it. | yes in substance — [#2654](https://github.com/tractat-us/kuilt/issues/2654) (open, FOLD-vs-LATCH), [#2456](https://github.com/tractat-us/kuilt/issues/2456) (open) |
| f | **Done** — [#1822](https://github.com/tractat-us/kuilt/issues/1822)'s `init { require(...) }` + `WireCodecConformanceSuite`. Keep it as the template for every other class. | yes, landed |
| g | No umbrella issue exists. The one lever that worked was the reference-impl rule ([#2247](https://github.com/tractat-us/kuilt/issues/2247)): **a conformance suite must carry a non-nullable fixture hook for every failure its reference cannot reach.** Extend it to a standing rule that any fix touching ≥2 modules must name the guard or TCK property that makes a third site impossible. | no |
| h | Landed and working: `verifyDocCitations` + `forbidUncitedDocCodeBlock` + `citation-staleness.yml` ([#2647](https://github.com/tractat-us/kuilt/issues/2647)). Remaining gap is the half the script cannot see — a claim falsified while its issue is still open. | partly |
| i | Every new guard ships with a **positive control committed as a test** (a planted violation the guard must red on, run in CI), which is what #2701/#2702 and #2399 lacked. | no |

## Modules named in the most issues since W31

`kuilt-raft` 72 · `kuilt-nw` 57 · `kuilt-core` 57 · `kuilt-bolt` 26 · `kuilt-conformance` 25 ·
`kuilt-test` 24 · `kuilt-otel` 20 · `kuilt-nearby` 20 · `kuilt-multipeer` 18 · `kuilt-session` 16 ·
`kuilt-crdt` 16 · `kuilt-websocket` 15 · `kuilt-cluster` 13 · `kuilt-heddle` 12 · `kuilt-mdns` 10.

Files/symbols: `SeamConformanceSuite` 37 · `WarpLogRecordExporter` 24 · `InMemoryLoom` 17 ·
`CompositeSeam` 15 · `NearbySeam` 14 · `RoomHubSeam` 13 · `TieredSeam` 13 · `MCSessionLink` 10 ·
`BridgePeerLink` 10 · `SeamStateGate` 9.

## Already-done open issues

Spot-checked the 20 oldest open `ready` issues against `origin/main` (`d5a632ba`) by grepping the
symbol or file each names. **3–4 of 20 (~15–20%) are already satisfied or premise-dead**:
- [#1708](https://github.com/tractat-us/kuilt/issues/1708) "Move to JDK 25 once detekt can run on it" — **detekt was dropped in [#2540](https://github.com/tractat-us/kuilt/issues/2540)**; the stated blocker no longer exists. Body needs rewriting or closing.
- [#1768](https://github.com/tractat-us/kuilt/issues/1768) "concurrency-probes cannot fail a merge" — its own option 2 **landed**: `ci.yml:681` now has `capability-probes` in `ci-required`'s needs, with the split documented at `ci.yml:824`. The remaining non-blocking job is deliberate.
- [#2186](https://github.com/tractat-us/kuilt/issues/2186) "phantom segment" — behaviour is documented at `WarpLogRecordExporter.kt:632` **and** has a test at `WarpLogRecordExporterRetirementTest.kt:757`.
- [#1803](https://github.com/tractat-us/kuilt/issues/1803) — both remedies now exist as guards (`forbidBareSeamStateFlow`, `forbidBareLaunchIn`), but [#2654](https://github.com/tractat-us/kuilt/issues/2654) says remedy 2 is *wrong*, so it is half-done rather than done.
The other 16 (#1349 #1738 #1837 #1865 #1870 #1874 #1892 #2068 #2118 #2141 #2155 #2160 #2181 #2183
#2191 #2206 #2288 #2321 #2326 #2332) are genuinely open — verified against source, e.g.
`PlyInboundGate.kt:114` still comments "nothing prunes — #1874", `NSFileManagerDurableStore.kt:45`
still documents the unflushed rename, `libs.versions.toml:33` still says "locks API only".
Extrapolating, ~15% of the 157 open issues (≈24) are closable on inspection.

## Where the brief is wrong

1. **"The open count has plateaued despite steady burndown"** — it has not plateaued for six weeks;
   it rose +140 over W30–33 and has fallen 22 over W34–37. Filing is spiky (W34 47, W35 21, W36 76),
   not "~80/week steady". Nothing needs explaining about a steady state that isn't there.
2. **"Fixes address instances, not root causes"** — the dominant generator is the opposite. Half of
   all new issues are the *output of audits*, and where a genuine type-level fix landed the class
   stopped: (f) went from 15 instances in one week to one recurrence in five weeks after
   `WireCodecConformanceSuite`. The hypothesis is right for (d) and (e) and for the 94 explicit
   re-derivations, and wrong for (a)(b)(f)(h).
3. **The sharper root is one level up.** 47 issues are *the remedy's own blind spot* — a lexical
   guard, a CLAUDE.md paragraph or a TCK obligation shipped for a property that only a type can
   carry, with the gap documented beside it. [#1835](https://github.com/tractat-us/kuilt/issues/1835)
   (the written rule reintroduces the bug), [#2648](https://github.com/tractat-us/kuilt/issues/2648)
   (the KDoc predicts the TCK cannot see the violation) and
   [#2702](https://github.com/tractat-us/kuilt/issues/2702) (the skew guard confirms itself) are the
   three receipts. **The lever is not "fix roots harder" — it is "stop paying for a lexical guard
   where a type would do", and give every new guard a committed positive control.**
