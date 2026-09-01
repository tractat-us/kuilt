# morning report — kuilt overnight

> **Session started 2026-08-31 00:22 ET.** Running log; TL;DR is current as of the last update below.
> The report this overwrote is preserved as [`morning-report-2026-08-17-to-18.md`](morning-report-2026-08-17-to-18.md).

## TL;DR

Picked up `next-plan.md` under `/goodnight`. **Seven PRs merged** — #2584, #2596, #2597, #2586,
#2589, #2599, #2602 — with **`main` at `f6db2711`**. **Seven issues hand-closed and verified**; two more
(#2587, #2535) deliberately left open as partial, with the remaining scope written into their bodies.
One PR left, and it needs only a decision. **One design call waits on you: #2577 / #1738** (below).

Six more issues are briefed and ready to dispatch the moment the box is quiet — I stopped
dispatching when load hit 60 on 16 cores, because verification produced under that starvation is
worth less than the throughput is worth having.

## Shipped

| PR | Issues | Merge SHA | What |
|---|---|---|---|
| **#2584** | #2328, #2330 | `c31eb19a` | A throwing `onClose()` must still cancel the owned scope; remaining unguarded once-latches go atomic. |
| **#2596** | #2572 | `6e80d89b` | `kuilt-primitives` now routes at `pumpIn`, so an agent about to hand-roll a bare `launchIn` pump gets intercepted. |
| **#2597** | #2587 (part) | `9cbe1f4c` | `assembleVoterMesh` closes the dials abandoned mid-handshake at formation timeout. **#2587 deliberately left open** — see below. |
| **#2586** | #2237, #2185 | `64829b5c` | A broken store is reported once per outage, not once per retry. Crash-sweep test **~11 s → 0.0 s**; wasm XML 288,685 B → 20,278 B, back under the ceiling that was silently dropping results. |
| **#2589** | #2019 | `acf313c8` | The lattice-law generator reaches `compact()`, with vacuity accounting. Mutation matrix agrees on JVM and native. |
| **#2599** | #2535 (part) | `7d84c519` | Eight `catch` arms stop swallowing their own cancellation. **#2535 left open** — items 2–3 remain; prevention filed as #2598. |

**Six issues hand-closed and verified `state=CLOSED`** — #2328, #2330, #2572, #2237, #2185, #2019 — each with evidence (auto-close is off
org-wide, so a merged PR closes nothing on its own).

On #2596 I verified the two claims the route rests on rather than taking the report's word:
`pumpIn` and `PumpFailure` really are `public` in `us.tractat.kuilt.core` (a route naming an
`internal` symbol is worse than no route — the reference rejoin route named `internal SeamRoom`
for a year), and the cookbook entry really did already exist from #2560, so leaving it untouched
was correct rather than an omission. I also re-ran the YAML parse myself: that frontmatter is one
7506-char unquoted scalar, a stray `: ` silently unloads the whole skill, and the daily sync would
carry the breakage into consumer repos unreviewed.

## In flight

| Item | Kind | State |
|---|---|---|
| **#2577** heddle | reviewed -> fixing | Re-reviewed **BLOCKING**, seven findings, posted to the PR. Staying **draft**. See below — the arm itself is fine; one item is yours. |
| **#2586** otel | fixed, **armed** | All three findings applied, CI fully green. Its merge releases #2583 / #2530 / #2411 / #2527, all queued behind it on the root `build.gradle.kts`. |
| **#2589** crdt | reviewed, **armed** | Native mutation matrix run and it agrees with JVM on every cell. Worker caught one of its own "green" cells as a non-measurement and ran a control for it. Also adds `VersionVector.contiguous` as public API, with the required skill route. |
| **#2591** conformance | fix | **Highest-value backlog item.** `SeamConformanceSuite` checks `peers.size >= 2` on the host only — a fabric whose joiner never learns its peer passes the TCK. This gap hid a shipped `:kuilt-nearby` defect. |
| **#2572** skill | fix | No `kuilt-primitives` route for `pumpIn`, so nothing intercepts a hand-rolled bare `launchIn`. |
| **#2595** build | **diagnosed — see below** | Verdict (B): a real false green in `ci-required`. Issue body and title rewritten. |

## The #2586 worker pushed back on my brief, and was right

Worth knowing because it is the good failure mode. I gave it two options for the latch and let it
choose. It rejected the reviewer's option (b) — pass the report at all four failure sites — on the
grounds that it does not fix the reported symptom at all: a buffer failure would still *open* the
outage, and making the counted and reported populations coincide that way silences the store rather
than reporting it. Only the dedicated latch satisfies "the durable-write line is emitted". That is
correct, and it is the kind of thing a worker usually just implements as told.

It also declined the atomicfu `atomic()` I asked for, because this build does not apply the atomicfu
Gradle plugin, so an atomic *field* would go untransformed — and used `MutableStateFlow.compareAndSet`,
already this file's own primitive, instead. I checked: `atomic()` *is* used elsewhere in the repo
(`kuilt-core`, `kuilt-cluster`), so "not usable" overstates it — but the plugin really is absent and
the conclusion stands.

Its closing "what is this now unpinned on" answer is the honest one: **the latch rests on `commit`
being the only place a durable write happens.** A `store.write` added elsewhere that logs that line
recreates the same defect one level up, and nothing reds.

## The one call worth your attention

I **held #2586 rather than arming it**, against this repo's fix-forward bias, because the review
found the PR's own headline claim is false in a realistic sequence.

The PR is "report a broken store once per outage, not once per retry". But the dedup key counts
*every* export failure while only the commit path actually reports. So: a gossiped remote's CRDT
join throws, the counter goes to 1, the store then starts refusing writes — and the durable-write
line is never emitted **for the whole outage**, because nothing succeeds to reset the streak. Not
once per outage; zero. And since the pre-PR behaviour was noisy-but-present, that is *strictly
worse* for diagnosis than not fixing it at all.

Fix-forward is right for a nit. It is wrong when the thing that is broken is the fix's own subject,
and the failure mode is silence — nothing reds, and the next person to debug a write outage reads
a CRDT error and goes to the wrong subsystem. It is a ~20-line fix and a worker is on it.

The other two findings are cheap and went with it: a shared test fake left carrying dead knobs that
model a state the PR's own KDoc proves unreachable (an invitation to rewrite the vacuous arm this
PR just deleted), and `verifyTestResultParity` greening on `0 class(es) across 0 module(s)` if an
artifact ever arrives empty — a guard indistinguishable from a clean repo, one level above the
guard it exists to be. That last one does demonstrably fire today (824 classes / 37 modules /
4 targets), so it is future-proofing, not a live hole.

## #2595 — the investigation found a real false green, and the issue was pointing at the wrong tool

This is the one I would read first. The fork resolved to **(B)**, and the original filing's
attribution was wrong: the blind tool is **Android Lint**, not the Kotlin compile. ("Android
unit-test compiles" was `lintAnalyzeDebug**UnitTest**` misread.)

Lint 31.13.2 bundles a `2.2.20-Beta1-for-lint` frontend whose metadata ceiling is `[2,3,0]`, while
everything it reads — kotlin-stdlib 2.4.10, play-services-nearby, every sibling `kuilt` module — is
`[2,4,0]`. Metadata past the ceiling is **silence, not an error**. So `lint`, which sits in
`check` → `build` → `ci-required` with `abortOnError = true` across 43 modules × 3 analyze tasks,
silently enforces nothing that resolves a Kotlin *library* symbol. It prints "No issues found"
either way.

What makes this a verdict rather than a suspicion is that it was proved in both directions: the
Kotlin compile's four silent-if-blind arms all went **red** (so the compiler is fine), lint's
Java/Android arms **fire** while its kotlin-stdlib arms are **silent**, and forcing
`kotlin-stdlib:2.2.20` on one module — byte-identical source, same lint — makes the silent arms
fire. That last step is causation, not correlation. Dispatching this as evidence-only with no fix
instruction is what made the wrong attribution survivable.

Practical loss is narrow (thin Android surface) and there is **nothing to bump** — lint's frontend
is pinned by AGP, the same trade as detekt's in #2471. Recommended follow-up, queued behind #2586
on the root `build.gradle.kts`: document the partial gate, add a `forbidLintFrontendSkew` tripwire
mirroring the detekt one so the claim reds when AGP catches up, and decide deliberately whether a
known-partial lint stays in `check`.

## #2587 is open on purpose

#2597 merged, but I did **not** close #2587, and its body now says why at the top — otherwise the
next reader sees a merged PR against a closed-looking issue and assumes the class is done. The fix
covers the formation-failure path only; `superviseVoterReconnection`'s redial and `buildMesh`'s
construction-time handshakes have the identical shape and are untouched. One residual needs a change
to `dial`'s own contract rather than to `assembleVoterMesh`.

Worth recording that the worker volunteered all of that rather than reporting a clean close, and
that it *rejected* the fix the issue itself floated (closing inside `handshakeLink`) for a good
reason: `acceptPump` already closes on handshake failure, so that would double-close the inbound
path — the precise over-reach its own test forbids.

## #2589 — the worker caught its own false green

Worth reading its PR comment. Running the native mutation matrix, it noticed that one cell recorded
as *green* had never actually executed: `run()` does the post-merge phase first, that phase fails by
`check`, so the pre-merge phase for two of the three types never ran — and reading its absence as a
pass is precisely the vacuity this PR exists to remove, arriving in the PR body instead of in a test.
It ran a dedicated control to separate the two, and the prior commit's JVM table has the same
artifact. It also deleted `build/test-results/<target>` before every run, so a mutation that failed
to compile would yield *no* XML rather than the previous run's verdict — excluding the stale-report
trap structurally rather than by eyeballing a console line.

Before arming I simulated the merge rather than trusting GitHub's `MERGEABLE`: this branch forked
before #2585 and #2596, so a plain diff renders their routes as deletions. The merge is clean, the
skill frontmatter still parses, the line count is unchanged, and all ten routes survive. This repo
has twice landed a broken `main` from two PRs with no textual conflict, which is why that check ran.

## Notes

- The four workers killed by last session's quota exhaustion are **not resumable** — their session
  is gone, so `SendMessage` cannot reach them. Their commits were all pushed, so their branches are
  current and fresh workers are continuing from them rather than redoing anything.
- **Unblocked by #2586's merge, but NOT dispatched — ready to go the moment the box is quiet:**
  **#2583** (173 uncited doc blocks + a count ratchet), **#2530** (11 `!!` sites #2471 widened the
  guard onto), **#2411** (`verifyDocCitations` scans gitignored `.superpowers/`), **#2527**
  (`module.md` link targets unguarded — Dokka already detects all 29, nothing reads it), and
  **#2593** (the same report-once-per-outage shape in `WarpSpanExporter`/`WarpMetricExporter`, which
  should now copy what #2586 landed). Plus the #2595 follow-up: document lint's partial gate and add
  a `forbidLintFrontendSkew` tripwire.
  ⚠ **#2602 also touches the root `build.gradle.kts`**, so the four that collide on that file are
  blocked behind it now rather than behind #2586. Land #2602 first, then dispatch them.
  I held these because load was 50–60 on 16 cores with only two of the daemons mine — a worker
  dispatched into that starves, and worse, produces verification nobody should trust. Throughput
  tonight was not the binding constraint; a false green would have been.
- **Originally queued behind #2586** (root `build.gradle.kts` collision): the five above.
  **Queued behind #2591** (all collide on `SeamConformanceSuite`): #2568, #2561, #2554, #2538.
  **Queued behind #2589** (collides on the CRDT generator): #2592.
  **Queued behind #2586** by pattern rather than files: #2593, which is the same
  report-once-per-outage shape and should copy whatever #2586 lands.
- Not touched, not mine: #2545 / #2544 / #2543 belong to the `kuilt-worktrees/rejoin` session;
  #1736 is the Renovate Gradle 9 draft.
- No release cut, by design — `sync-release.yml` fast-forwards `release` to `main` at 06:00 UTC.

## #2577 — the arm is fine, and one item is yours

Good news first: the re-review **answers the load-bearing question in the PR's favour.** The new
`Relocation.Refused` arm refuses nothing `main` accepts and should accept. Enumerating the states
where `finals[s]` can lack a named donor leaves exactly one — a donor that received a carried row
while the slot was live, then left the roster before that slot's `Quiesce` committed. Both candidate
over-refusals are ruled out on a code path, and a departed donor that later rejoins **self-heals**.
So the worry that motivated holding this PR turned out not to be the problem.

Seven other things were. The two that mattered: the residual clause is **unreachable** and its KDoc
credits it for what ack monotonicity actually provides (a future reader would have preserved it for
a reason that was never true), and the control arm asserting `Nothing`-not-`Refused` is **green by
construction** — the fixture drives the ack set and the residual to zero together, so dropping
either filter alone stays green. It catches only the crudest mutation, the one its own KDoc names.
Plus two pieces of load-bearing prose that are simply wrong, a tautological test, and a reflection
guard that has never been shown to fire. A worker is on all of it.

**What I did not decide for you.** `SlotFinals` gains a wire field, and `applyEntry`'s own KDoc says
verbatim: *settle #1738 before any schema evolution of the wire types.* #1738 is open. `Cbor`
defaults `ignoreUnknownKeys = false`, so an old-binary peer replaying a log containing such a
`QuiesceAck` throws, takes the `envelope == null` branch, **advances the roster index anyway**, and
permanently loses that ack — a permanent divergence in the log-pure derivation `FenceState` exists
to keep identical across peers. Pre-1.0 upgrade-together posture probably makes that acceptable,
which is why I had it documented rather than blocked. But it is a written precondition in the code
being deliberately violated, and that is your call, not mine. I left #2577 **draft** so you make it.

## Decisions needed

- **#2577 / #1738** — see above. Evolve `SlotFinals`' wire schema now under upgrade-together, or
  land #1738's entry tagging first? I have the exposure documented either way; the PR is held draft.
- **#2594** (`needs-design`) — `witness()` does not copy `transferRelocIn`. Fixing it crosses the
  plane-ownership boundary #1691 rests on. Prior session's recommendation was option 1 (document
  the gap rather than fix it). Still wants you.

## Anomalies

- **Load peaked at 55 on 16 cores** (3.4×/core) around 01:37. Only **3 of 18** Gradle/Kotlin daemons
  were mine — the rest belong to sibling sessions, including a JDK 25 build that is not even this
  repo, plus one daemon that has been up since near boot. So this is a shared-machine effect, not my
  fan-out alone. I did **not** kill anything: a pattern kill would reap other agents' builds and land
  as an inexplicable failure in *their* change, which is the process-level twin of the shared-scratch
  trap. Earlier in the night it also hit 41 on 16 cores.
- **Original note: load hit 41 on 16 cores** (~2.6×/core) with five concurrent `--rerun-tasks` builders — past
  the ~38 that this box has hit before. I stopped dispatching and warned the running workers that a
  timeout, truncated log, killed daemon, or missing test XML is a contention artifact right now and
  must not be banked as a finding. Treat every absolute timing in tonight's PRs as unreliable;
  relative comparisons within one run survive.
- **The #2586 fix worker died mid-response** (API connection lost) and was **resumed, not
  re-dispatched** — re-dispatching would have duplicated an open PR and discarded banked work. Its
  remote was not authoritative: `HEAD` matched `origin` with zero commits, but the worktree held an
  uncommitted +7/−3 edit that was the actual fix in progress. I read that diff before resuming (a
  stalled worker's working tree sometimes holds mutation scaffolding rather than work — committing
  one reintroduces the bug it was probing for). This one was real work, and it had died at exactly
  the declaration of the new latch: the symbol was referenced three times and declared nowhere, so
  the file did not compile. Resumed with that state spelled out so it neither redid nor lost anything.
- One worker caught itself about to bank a **stale tool report** — an ERROR-level deprecation had
  broken a compile so the tool never re-ran, and the previous run's output was still sitting there
  looking current. Same shape as the mutation-harness trap. It is the reason I warned the two workers
  running mutation tables specifically.
