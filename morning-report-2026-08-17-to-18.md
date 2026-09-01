# Morning report — 2026-08-17 → 18

_`/donext` → `/goodnight`. Worktree `kuilt-worktrees/bugs`. `main` `6e36b56d` → **`fb8aa088`** (so far)._

## ⚠ READ THIS FIRST — a regression I merged tonight, and the fix is in flight

**[#2462](https://github.com/tractat-us/kuilt/issues/2462) — PR #2451 (`8c4bae38`, merged 23:26) landed a
real null-deref in SHIPPED code.** Found ~1 h later by **two workers independently, 2 minutes apart**,
from different directions; I verified it on `origin/main` myself before acting. (#2463 was the duplicate,
now closed into #2462.)

**It manifests as two different symptoms, which is why it read as unrelated flake:** a null deref is a
**SIGSEGV on Kotlin/Native** (`NwLoopbackConformanceTest`, symbolicated to
`NwSeam.inboundSilenceLoop` → `Flow#collect`) and an **NPE on the JVM** (`NwBridgeLoopbackConformanceTest`).

```
 395:  private val silenceJob: Job = scope.launch { inboundSilenceLoop() }   ← launched DURING construction
1235:  private val watchdogWake = MutableStateFlow(0L)                       ← initialized AFTER line 395
1290:      watchdogWake.first { … }                                          ← read by that coroutine
```

Kotlin initializes properties in declaration order, so a body that runs before construction finishes
reads an uninitialized field. **Every test is green because `runTest`'s `StandardTestDispatcher` defers
the body past construction** — only a real dispatcher can lose the race, and
`NwBridgeLoopbackConformanceTest` (the sole real-threads suite) reds **intermittently, naming a different
victim test each run**.

**It is not test-only:** `NwLoom.weave` constructs on a production dispatcher and the NPE escapes a
`launch { }` — thrown into the coroutine's failure path, not to any caller. So the inbound-silence
watchdog may be **dead on real devices**, silently gutting the diagnostics #2451 just shipped.

**The base rate is the receipt:** unmodified `origin/main` fails **2/6** at matching box load; moving the
one declaration gives **6/6 green**. So a single green run proves nothing here — this must be measured as
a rate, and it is why "it passed" was never going to catch it.

**✅ FIXED AND MERGED — `53b8189d` ([PR #2464](https://github.com/tractat-us/kuilt/pull/2464)), 00:49 ET.
`main` is healthy; #2462 closed. The window where `main` carried this was ~1 h 23 m.**
Two files. All **seven** launches move out of property initialisers into **one `init { }` block last in
the class body**; the job `val`s were write-only and are gone entirely.

**Why not just hoist the one field:** that fixes the instance and leaves the hazard — a *pairwise*
constraint (every launch below every field it transitively reads), re-checked on every future edit with
nothing to catch a violation. That is exactly how this arrived. One terminal `init` collapses it to a
single, commented, obviously-terminal constraint that holds for a field added **anywhere** in the class.

**The test is the clever part.** `NwSeamConstructionOrderTest` builds the seam on an **eager**
dispatcher (`UnconfinedTestDispatcher`, `isDispatchNeeded == false`), so a launched body runs *inline at
the launch site* — the same window a real dispatcher opens on another thread, made **deterministic**. No
real threads, no banned production dispatcher, no `ALLOW-realDispatcher` escape hatch. It covers every
coroutine the constructor starts, not just the watchdog, and its **first assertion is a rig check** (the
launch must run inline) so a future dispatcher swap reddens instead of going vacuous.

### The measurement is the part worth reading

| suite | load | before | after |
|---|---|---|---|
| `NwBridgeLoopbackConformanceTest` (JVM) | **5.3–5.7** | 0/6 failed | 0/6 failed |
| `NwBridgeLoopbackConformanceTest` (JVM) | 16.6–28.4 | **6/6 failed** | 0/6 failed |
| `NwLoopbackConformanceTest` (K/N) | 26.3–29.4 | **3/6 failed** | 0/6 failed |

**The top row is the finding.** At load ~5 *neither* arm reds — so these suites are a **load-dependent
coin-flip detector**, and a single green run of either proves nothing. That is why this survived review,
and it is worth remembering the next time a `:kuilt-nw` conformance red gets called a flake.
(Load was controlled with 20 `yes` spinners, reaped via an `EXIT` trap with `pgrep` confirming zero
survivors, and sampled immediately before each run.) Neither suite was weakened or skipped.

**Sibling audit — 8 more launch-from-initialiser sites, all currently safe, most only *positionally*.**
`NearbySeam.kt:141,142,147,152` is the closest twin; `RoutedRaftTransport.kt:219` has
`override val incoming` declared *after* it and is safe only because the body doesn't read it. The
sharpest finding is adjacent: **`NwSeam:341` and `NearbySeam:125` read `unobservedCapability`, declared
~700 / ~135 lines later — safe *only* because it is a computed `get()`. Converting either to a stored
`val` is an instant constructor NPE.** Fakes are clean, and no test pins the defect as correct.
Guard follow-up filed as **[#2465](https://github.com/tractat-us/kuilt/issues/2465)** (`ready`).

**The judgement call, recorded:** I chose **not** to revert #2451 and to fix forward instead — the fix
was small and well-understood, and a revert would have lost the diagnostics. That call is now settled by
the outcome (fixed and merged 1 h 23 m after it landed), but it was a live risk at the time and you
should know I took it rather than waking you.

## TL;DR

**Six PRs merged, `main` `6e36b56d` → `7de4830b`. Pipeline empty, worktrees swept, nothing blocked.
One decision waits on you: [#2457](https://github.com/tractat-us/kuilt/issues/2457).**

**The #2425 wedge is SOLVED** — root cause established from data already on disk, no re-repro needed. It
is a **publish-then-swap ordering window**, not either hypothesis the plan carried. The plan's leading
hypothesis was refuted, and so was my replacement for it; the answer came from Apple's unified logs,
which turned out to be the *richer* channel for Network.framework internals — the opposite of what the
archive README claimed.

**Two things I'd read before anything else:**
1. **I merged a regression and it is fixed** — see the box above. It was on `main` for 1 h 23 m.
2. **The `:kuilt-nw` conformance suites are a load-dependent coin-flip detector** — measured 0/6 reds at
   box load ~5 and **6/6 at load ~17–28 for the same defect**. A single green run of those suites proves
   nothing. This is why the regression survived review, and it is worth knowing the next time one of them
   reds and someone reaches for "flake".

**Net new capability on `main` tonight:** #2425 is now reproducible **in CI** rather than only on two
phones, its loss is **loud** rather than silent, and the next field occurrence carries a trail that names
the window's width and the frames stranded in it — at a level a release build keeps.

---

## The finding

`NwSeam` publishes a peer the instant the **first** connection resolves, then may silently **move**
that peer's binding ~10 ms later and destroy the first link. Anything the consumer wrote in that
window lands on a socket the far end has already destroyed.

```
36.903  seam publishes the peer on nw-2 (resolved.first)
        consumer writes 182 bytes onto nw-2
36.913  nw-1 resolves; dedup rebinds the peer to nw-1
36.914  nw-2 closed  →  those 182 bytes were already gone, never retried
```

The surviving link then behaved **perfectly for 8 s** — 742 bytes each way, 7 heartbeat pings and 7
pongs, all TCP-acked — which is why both peers saw each other as present while the one frame that
mattered was already lost. Derived from FIN sequence numbers on both devices, which agree exactly.

**Why it was invisible:** `RealNwApi.send` cannot fail (filed as #2455). A send to a closed connection
returns silently at `log.debug`, so `Seam.sendTo` reported success for a frame that went nowhere, and
`NwSeam.sendTo`'s `onFailure → removeByConn` recovery is **dead code on this fabric**.

### Three claims I made during the night that were wrong, and are corrected

- "The two dedup cancels were 31 ms apart" — the devices' clocks differ by **≈26 ms**; they were
  **simultaneous**. Any cross-device timing read off these logs must correct for that skew first.
- "A 31-byte gap suggests unconsumed app data" — it is the TLS `close_notify`, present on all four
  connections in both directions.
- "The host's registry binding went stale" (my hypothesis (c)) — refuted by the byte ledger; the
  binding was correct and live throughout.

---

## Shipped

| SHA | PR | What |
|---|---|---|
| `c8d2e239` | [#2459](https://github.com/tractat-us/kuilt/pull/2459) | `RealNwApi.send` reports an immediately-known failure instead of discarding the frame. Closed #2455. |
| `53b8189d` | [#2464](https://github.com/tractat-us/kuilt/pull/2464) | `NwSeam` starts its coroutines after construction, not from a property initialiser. Closed #2462. |
| `8c4bae38` | [#2451](https://github.com/tractat-us/kuilt/pull/2451) | Three wedge diagnostics + a build guard keeping the field trail at INFO. `part of #2420`. |
| `fb8aa088` | [#2453](https://github.com/tractat-us/kuilt/pull/2453) | A Torn seam's `sendTo` must report the TEAR, not blame the addressee. Closed #2448. |
| `911f885d` | [#2452](https://github.com/tractat-us/kuilt/pull/2452) | Decode, surface and re-create a failed Bonjour advertiser. `part of #2449`. |

**`main` `6e36b56d` → `c8d2e239` — five PRs.** The whole planned burndown (tasks 1–4 and 6) is landed,
plus one regression found and fixed in-session; task 5 is #2457, below.

**What #2451 actually buys you** — this was your steer, so it is worth being concrete. On the next
occurrence the field trail now carries, at a level a release build **keeps**:
`nw.seam.publish-swap` (**warn**) naming the window's width *and the frames written into it* — i.e. the
exact 182-byte loss, measured rather than inferred; `nw.seam.registry.orphan` (**error**);
`nw.seam.inbound-silent` (**warn**); and both dedup verdicts promoted DEBUG→**INFO**. A root-build guard
(`forbidDemotedFieldTrail`, 25 curated events) now fails the build if any of them is ever demoted —
its message reads *"THE FIX IS THE LEVEL, NOT THE LIST"*.

**#2449 deliberately stayed OPEN** — #2452 is `part of`, not `closes`, because no CI test can prove the
real transport's *emission*. **Close it by hand once the consumer confirms against a bumped pin.**

Also landed, no code: **#2425 retitled and its body rewritten**; the diagnostics archive README
corrected; the consumer's instrumentation premise corrected on fireworks-compose#4312.

---

## In flight at wake-up

**Nothing.** All six PRs merged, pipeline empty, six agent worktrees retired and six branches deleted
(other sessions' 45 worktrees untouched). The only open PR in the repo is **#1736** (Renovate AGP 9 /
Gradle 9, pre-existing and blocked on IntelliJ support — not mine, not touched).

`kuilt-worktrees/bugs` is fast-forwarded to `7de4830b`.

---

## What each merged PR actually bought

- **[#2459](https://github.com/tractat-us/kuilt/pull/2459)** — `RealNwApi.send` can now fail.
  **This is the safety net that makes #2425's window LOUD even before it is closed**, and it presumes no
  answer to #2457. `closes #2455`.
  Two swallow points routed differently: **synchronous** (unknown/closed connId) now throws
  `NwSendFailedException` — `NwApi.send`'s KDoc moves from "MAY throw" to **MUST**; **asynchronous**
  (`nw_connection_send`'s completion error) is decoded and escalated into the *existing* teardown path,
  and deliberately **not awaited**, so `send` still means *handed off*, never *delivered*.
  **Both fakes were permissive, and one test pinned the bug as correct:** `FakeNwRadio.send` was
  `links[id] ?: return` — **the reference carried the identical defect** — and `NwNativeLibTest`
  *asserted* `nw_send` to an unknown conn returns `0`. Both fixed; the native assertion now expects `-1`,
  measured through the real dylib, which is the receipt that the fix crosses the ABI.
  It also corrected a `SeamConformanceSuite` comment #2453 landed yesterday claiming `RealNwApi.send`
  "CANNOT throw" — now false.
  **`closes` confirmed** (worker asked): #2455's subject is the missing failure path itself, provable in
  CI — not a hardware-reproduced transport bug, so the "validate against the reproducer first" rule
  doesn't apply.
  It verified the #2464 interaction *for the right reason* rather than settling for green: the four
  loops its test depends on are still `UNDISPATCHED` in the new terminal `init { }` so they still
  subscribe before the constructor returns, and it **re-ran the revert-check on the rebased tree** and
  got the byte-identical red message. The cause did not move.
- **[#2458](https://github.com/tractat-us/kuilt/pull/2458)** — `FakeNwRadio` double-dial harness
  (`part of #2425`).
  **What it buys you:** the two facts #2425 turns on were previously inseparable — arrival order was
  inherited from dial order (so making a link resolve first meant dialling it first, confounding the
  variables by construction), and bytes were delivered in the same virtual instant they were sent, so a
  frame could never be *in flight* when its link died. One new primitive — **bytes can be in flight**
  (`holdSends`/`releaseSends` + a `sentFrames` ledger with fates `Delivered`/`InFlight`/
  `DiscardedOnClose`/`DroppedLinkGone`/`Refused`) — supplies both. The field sequence now reproduces
  **including the discriminating detail**: the inbound link resolves first, the dedup keeps the
  outbound, and the window write dies with the link while both seams stay `Woven` and `peers` still
  names the peer. **9/9 mutations red, no survivors** — and mutation #9 (`releaseSends` throws instead of
  dropping) reds **only** its new test, which is what earns that test its place.

---

## Anomalies

Three *distinct* load-sensitive failure modes surfaced tonight. Each was correctly separated from the
others rather than collapsed into "flaky" — worth recording, because two of them look identical at a
glance and one of them was a real bug:

1. **#2462's coin flip** — the real regression. 0/6 reds at load ~5, 6/6 at load ~17–28. A defect, not a flake.
2. **A K/N SIGSEGV under contention** — genuinely load, not code. A worker first attributed it to its own
   change, then ran the control arm, reproduced it with its changes reverted at load ~23, and **amended
   the commit message** rather than leave the false claim standing.
3. **[#2386](https://github.com/tractat-us/kuilt/issues/2386)** — `closeDrivesStateTornNormal[iosSimulatorArm64]`
   is a **real 30 s wall-clock Bonjour deadline** that a loaded build trips on its own. Not a crash.

The transferable bit: on this repo a `:kuilt-nw` result without a **load sample** is uninterpretable. Two
memories were banked tonight — `test-dispatcher-hides-init-order-race` and
`identity-by-message-folds-implementations`.

**⚠ Filed after the fact as [#2466](https://github.com/tractat-us/kuilt/issues/2466) — I under-called this
overnight.** I recorded the load-dependence as an *anomaly to know about* rather than as a **defect in the
gate**, and Iain was right to push. There are two directions and only one was tracked:

| Direction | Consequence | Tracked? |
|---|---|---|
| **False positive** — #2386's real 30 s Bonjour deadline | fails loudly; annoying, ships nothing | ✅ #2386 |
| **False negative** — races detected *only* under contention | **CI goes GREEN with the defect present** | ❌ nothing, until #2466 |

The second is how #2462 passed review *and* a full green CI run. The framing that matters: these suites
drive real transports and **cannot be made deterministic without ceasing to be what they are** — so the
gap is not "flaky tests", it is that **we relied on a probabilistic detector as the only safety net for a
class with no deterministic one**. The in-tree model for the fix already exists:
`NwSeamConstructionOrderTest` (`53b8189d`) catches that class deterministically on an eager dispatcher,
and would have caught #2462 on the PR that introduced it.

**Also verified, since it was the other reading of the question: no conformance property was weakened
tonight.** I checked the diffs rather than the workers' claims. `fb8aa088`'s two deleted `assertFailsWith`
calls were *replaced* by `failureOf` + `assertAll` **plus** a new identity clause, and the comment it
deleted was the one saying the property *cannot* distinguish `PeerNotConnected` — deleted because the PR
made it distinguishable. `c8d2e239`'s conformance change is **comment-only**. Both net strictly stronger.

## Cleanup

- 6 agent worktrees retired via `worktree-sweep` (all PR-merged, clean, nothing unpushed).
- 6 session branches deleted. ⚠ Note for the routine: they were **squash**-merged, so
  `git merge-base --is-ancestor` says *not merged* and would have kept all six. The PR-state check
  (`gh pr list --head $b --state all`) is the correct one, as `~/.claude/CLAUDE.md` says.
- Other sessions' 45 worktrees and their 6 `: gone]` branches deliberately untouched — every one is
  checked out in a live worktree belonging to another session.
- No release cut (kuilt ships on deliberate `v<x.y.z>` tags; nothing asked for one).
- ⚠ **`bugs/main` is 1 commit ahead of `origin/main`** — `9af4a472`, this report plus last night's
  rotated copy. **Not pushed**, because `bugs/main` tracks `origin/main`, so a push would go **straight
  to `main`**, and the standing rule is PR-by-default. `next-plan.md` is gitignored, so it is not in the
  commit and stays local. Push it, PR it, or drop it — your call; nothing depends on it.

---

## Decisions needed

**One — [#2457](https://github.com/tractat-us/kuilt/issues/2457) (`needs-design`): what shape should #2425's fix take?**
Full argument in the issue; the short version:

The stakes are higher than they look because **the double-dial is the norm, not an edge case** —
`:kuilt-nw` is a full mesh, so every pair makes two connections and the dedup runs on *every*
formation. The successful 11 ms formation in the same session had the identical race; it just resolved
outbound-first, so the link that lost was one nobody had published on.

The current tiebreak (smaller canonical link nonce) is **comparative**, so a link cannot know it is the
winner until the *other* link resolves — which is exactly why the seam publishes early.

**⚠ Updated after you asked for the fable review — my recommendation (C) was WRONG, and the issue body is
rewritten.** Recording it because the correction is the useful part:

| | Option | Verdict |
|---|---|---|
| **D** | **graceful loser drain** — rebind *writes* to the winner, **half-close** the loser, keep **reading** it until FIN/EOF or a bound | ✅ **now recommended** |
| C | identity-keyed tiebreak | ❌ as sketched — see below |
| B | replay the window's writes | ❌ on **cost**, not impossibility (my "unsolvable at this layer" was wrong) |
| A | bounded settle window | ❌ never the fix; survives only as a mitigation if D's probe fails |

**Where I was wrong about C.** I argued it was immune to the race that killed the old direction rule
because it keys on stable identity. It isn't: **`PeerId` cannot discriminate the two links** — both carry
the identical `(selfId, remoteId)` pair. The discriminating bit **is direction**, and at resolve time
that is `cs.endpoint != null` (`NwSeam.kt:396`), written by one collector and read by another, with the
bytes loop explicitly allowed to see a conn before its opened event. So an outbound conn whose hello
beats its opened event misclassifies, both ends pick opposite survivors, and the pair **wedges to zero**
— exactly the failure at `NwSeam.kt:108-110`. Two further problems nobody had named: it is a **silent
version break** (the tiebreak is a two-sided agreement and `NwHello` has no version field), and its
fallback **re-opens the window** it claimed to remove.

**Why D is better, in one line:** the 182 bytes did not die because they went to the "wrong" link — they
died because the far end **full-closed** it and RST'd the bytes in flight. Fix the teardown, not the
choice. TCP's FIFO + FIN is precisely the receipt B lacked, so D gets zero loss *and* zero duplicates
with **no tiebreak change** — hence no version break, no publish latency, no `MeshSeam` fork, and it is
unilaterally deployable (a mixed pair is half-protected, never worse).

**Blocking next action:** probe whether Network.framework supports **half-close** on this binding. That
single answer decides D vs C-with-hello-carried-direction. Needs a device, not CI.

Also worth knowing: **#2459 does not make this self-healing.** The window write targets a still-locally-
live conn, so the new synchronous throw cannot fire; the async error arrives after `sendTo` returned and
no-ops on an already-tombstoned conn.

**Nothing is blocked on this decision** — see "In flight", both items are approach-independent.

---

## Follow-ups filed

- **[#2454](https://github.com/tractat-us/kuilt/issues/2454)** (`needs-design`) — `sendTo` on a seam that
  re-formed to `Weaving` after `removeByConn` throws `PeerNotConnected`, blaming the addressee for the
  link's death. `MeshSeam` shares the shape.
- **[#2455](https://github.com/tractat-us/kuilt/issues/2455)** (`ready`) — `RealNwApi.send` cannot fail;
  `NwSeam`'s send-failure eviction is dead code on this fabric. **This is why the wedge was silent.**
- **[#2456](https://github.com/tractat-us/kuilt/issues/2456)** (`needs-design`) — six impls whose `sendTo`
  reads a shared registry rather than their own collapsing `peers`, so their Torn guard is not
  load-bearing.

---

## Running log

_(appended as the night goes)_

- **23:0x** — #2453 merged (`fb8aa088`), #2448 auto-closed. #2452 merged earlier (`911f885d`).
- **23:1x** — `/goodnight`. Dispatched the two approach-independent slices (#2455 fix, `FakeNwRadio`
  harness) and filed #2457 for the one call I would not guess at. Three workers in flight — held there
  rather than fanning wider, since >~6 concurrent full-KMP builds oversubscribe this box and the
  burndown finishing cleanly beats it finishing broadly.
- Deliberately **not** swept agent worktrees yet: #2451's is still OPEN, and sweeping mid-night risks
  stranding a resumable worker. Cleanup runs once the last PR lands.
- Deliberately **not** cutting a release — kuilt ships on deliberate `v<x.y.z>` tags, and nothing tonight
  asks for one.
- **#2451 armed.** Its worker fixed all three review findings and **corrected a bug in my own suggested
  fix**: my snippet hoisted the size check above the roster lookup, which would have silently swapped
  which refusal a caller sees when a peer is both absent *and* over-budget (`PeerNotConnected` wins
  today). It kept precedence and got atomicity another way. It also verified the tightened
  `sendOnTornSeamThrows` empirically for every `:kuilt-nw` subclass across **seven targets** (30 cases
  each, 0 failures) rather than assuming the rebase was safe — which is what the cross-PR hazard below
  actually required.
- **23:26** — #2451 merged (`8c4bae38`). Planned burndown complete. **#2420 stays OPEN**: items 1
  (`dumpFormationState`), 2 (own Bonjour rename), 3 (redial-campaign health) and 5 (tap guidance) are
  untouched. Not dispatched tonight **on purpose** — they touch `NwSeam`/`NwLoom`, which the in-flight
  #2455 worker also touches, and sequencing beats parallelism when two workers collide on one file.
  Next session should pick them up once #2455 lands.
- **00:2x** — #2458 (harness) returned **done**, and with it the #2463 discovery. Verified the NPE on
  `origin/main` myself before acting, then dispatched the fix as the top item on the board. Banked the
  lesson to memory (`test-dispatcher-hides-init-order-race`): *a green suite is not evidence for this
  bug class — only a real-dispatcher suite is*, and an intermittent red naming a **different test each
  run** is an init-order/shared-state suspect rather than contention.
- The harness worker also **self-corrected a false claim**: it had committed a ledger bound saying it
  fixed a K/N segfault, then ran the control arm, got the identical segfault with its changes reverted
  (box load ~23 — contention, not its code), and **amended the commit message** rather than leave the
  claim standing. The bound was kept on its own merits (an append-only ledger retaining 16 MiB
  conformance payloads is a real leak), with a test asserting truncation *and* that delivery still works.
