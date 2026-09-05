# Hardware runbook — morning of 2026-07-28

_Written overnight, 2026-07-27 ~21:45 ET. Two iPhones needed: **iPhone XS / iOS 18.7.9** and
**iPhone 17 Pro / iOS 26.5.2**. Hardware window is closing, so the runs are ordered by what can
**only** be done on-device._

> `morning-report.md` in this worktree is the **stale 2026-07-23 #1618 report**, untouched and still
> uncommitted. Ignore it for today; this file supersedes it for the hardware work.

---

## Before you touch the phones

Three PRs were dispatched overnight. Check them first — the runs below depend on the first two:

```bash
gh pr list --repo tractat-us/kuilt --state open --json number,title,headRefName,mergeStateStatus \
  --jq '.[] | select(.headRefName | test("1637|1837")) | "\(.number)  \(.headRefName)  \(.mergeStateStatus)  \(.title)"'
```

| Branch | What it is | State | Needed for |
|---|---|---|---|
| `feat/1637-spike-scenario7` | **Scenario 7** — the repro rig | ✅ **MERGED** (#1857, `main` @ `ac798a37`) | **Run 1 (required)** |
| `fix/1637-sub-timeout-blip` | the #1637 fix itself | **PR #1848 — ready, CI green, deliberately NOT merged** | Run 1's GREEN half |
| `feat/1837-spike-durable-logs` | durable capture + `spike/collect-logs.sh` | ✅ **MERGED** (#1852, `main` @ `2c0dd2ee`) | log collection (all runs) |

So a plain `main` build already has S7 in it — you can start Run 1's RED half immediately.

### ⚠ Sequencing — do NOT merge #1848 before Run 1

Run 1 is worth doing **twice**, in this order, because S7's PASS criterion is the post-fix behaviour:

1. **Build from `main`** (S7 is already there; the fix is NOT) → S7 should **FAIL** with
   `HostLost(Refused)`. **That failure is the RED capture** — attach it to #1637.
2. **Then merge the fix**, rebuild, re-run → S7 should **PASS** with `Recovered(hostId)`.

```bash
gh pr merge 1848 --repo tractat-us/kuilt --squash    # verified CLEAN/MERGEABLE, CI fully green
```

Then close #1637 by hand once the GREEN run reproduces (its commits say `part of`, never `closes`).

That pair is the whole proof. Merging #1848 first throws away half of it. #1848 is held unmerged
overnight for exactly this reason — its commits say `part of #1637`, never `closes`.

Then build and install the spike on both phones:

```bash
open ~/tractatus/kuilt/spike/app/SpikeNw.xcodeproj
```

Build to each device in turn. See `spike/CONNECTIVITY-SUITE.md` → **Building**.

---

## Run 1 — Scenario 7: the #1637 repro  ← the one that matters

**This is the capture that cannot be recreated once the phones are gone.**

### Why Scenario 6 could not do this

S6 runs with `detect=5s`, but `NwSeam.DEFAULT_WOVEN_PATH_GRACE` is a fixed **10 s**. #1637 needs
`grace < outage < hostTimeout`, i.e. `10 s < outage < 5 s` — an **empty interval**. The host always
opens its window before the joiner's grace expires, so a resume always finds an open window and
returns `Success`. Proven by last night's run: a real **23.7 s** outage produced `WindowOpened` at
9.3 s and a clean `Recovered` at 30.4 s — the resume machine was never entered.

S7 raises the scenario's `timeout` to **30 s**, making the repro interval **10–30 s** and hand-hittable.

### Build note — build from `hw/`, NOT `~/tractatus/kuilt`

⚠ **`~/tractatus/kuilt` (the main checkout) is on `fix/1660-nw-peerid-txt`, not `main`.** Building the
RED run from there would silently invalidate it. A dedicated worktree
**`~/tractatus/kuilt-worktrees/hw`** (branch `hw/main`, tracking `origin/main`) now exists for exactly
this — it's the kuilt equivalent of the `fireworks-xcode` surface. Keep it pinned to whatever commit
the run is meant to prove.

Also: S7 declares a new Bonjour type `_ksuite7._tcp`, so **`xcodegen generate` is mandatory** —
without it iOS silently blocks discovery and the run just never weaves.

```bash
cd ~/tractatus/kuilt-worktrees/hw
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem ./gradlew -PincludeSpike :spike:linkDebugFrameworkIosArm64
cd spike/app && xcodegen generate
xcodebuild -project SpikeNw.xcodeproj -scheme SpikeNw -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath /tmp/spike-dd build
xcrun devicectl device install app --device <id> /tmp/spike-dd/Build/Products/Debug-iphoneos/SpikeNw.app
```

### Steps

1. On the phone you will **keep online**: tap **`Host · S7 stay up`**, then don't touch it. It sits
   doing nothing for ~3 minutes and finishes on its own — expected, not a hang. It's a witness, and
   it reports PASS either way.
2. On the phone that will **go offline**: tap **`Join · S7 go offline`**.
   (The dropped phone must be the joiner — #1637 is joiner-side. S7 wires it that way; don't swap.)
3. Follow the orange banner. When it says Airplane Mode **ON**, turn it on and **hold ~15 s**
   (that typically measures ~20 s, comfortably inside the band).
4. When it says **OFF — that's the only toggle**, turn it off reasonably promptly. That's the only
   manual step. Then leave it alone up to another minute while it reaches a verdict.
5. Collect from **both** phones (see below). The Host report should say
   `never partitioned … — my link to it never closed`; the pair is what makes the run adjudicable.

### A SKIP is a retry, not a result

The band is **exclusive**: `10 s < outage < 30 s`, measured off `localFabric`'s own edges. Outside it,
the run says nothing about #1637 and S7 refuses to judge it — always SKIP, never FAIL. The message
names which side it fell on:

| Message | Do |
|---|---|
| fell **SHORT** of the 10 s path grace | hold longer |
| fell **PAST** the host's 30 s detect | turn it off faster |
| "the host ACKed a real resume" (`Resumed`) | turn it off faster — the outage overran detect |

### How to read the result

**A FAIL is the expected, wanted result if `fix/1637-sub-timeout-blip` has NOT landed.** S7's PASS
criterion is deliberately the post-fix behaviour. Pre-fix, expect on the **Join** phone:

```
HostLost … reason=Refused(code=resume-window-not-yet-open,retryable=true)
```

at roughly **70 s** after the radio died (10 s grace + the 60 s reconnect budget). That trace **is**
the RED capture — attach it to #1637. The Host phone reports PASS either way; it's the witness.

If the fix HAS landed, expect the room to survive: `Recovered(hostId)`, host back to `Connected` in
the roster, no `HostLost`. That closes the loop — then close #1637 by hand (its commits deliberately
say `part of`, never `closes`).

Ideal outcome is **both**: run once on a build without the fix, once with it.

---

## Run 2 — #1655 black-hole discovery (opportunistic)

No special rig, no code. You are hunting a **sustained `Woven`-but-silent** condition that does
**not** self-heal — the host present in `seam.peers`, reachable by state, but no frames arriving.

- The condition itself is confirmed real (2026-07-24, both phones, during formation churn — see
  #1655's comment). What has never been seen is a version that doesn't recover on its own; last time
  it cleared via an 8 s bounded re-election.
- Induced by formation churn (#1660). Keep the election flight recorder on and do repeated
  form/tear cycles while you're doing anything else.
- **#1655 stays `blocked` indefinitely without this.** It's the only item where hardware is
  load-bearing for the *design*, not just confirmatory — everything else can be unit-tested.

---

## Collecting the logs

Per your note that this is cumbersome — **merged (#1852), on `main`.** After a run:

```bash
cd ~/tractatus/kuilt && ./spike/collect-logs.sh
```

Merged file lands at **`./spike-logs/<UTC-stamp>/merged-timeline.log`** (path printed at the end; raw
per-device pulls sit beside it). Both phones' lines, one timestamp column, each line labelled with
which phone said it — S6/S7 verdicts are defined by the *asymmetry between the two phones*, so the
interleaved file is the artifact you paste into an issue, not two texts compared by eye. On-device
files are `Documents/suite-<UTC-stamp>-<hw-model>-<role>.log`; scenario 1's `nw.log` is pulled too.
`spike-logs/` is gitignored.

**This was verified against your two actual phones** — the XS over cable and the 17 Pro over
localNetwork (only `available (paired)`) both pulled, the Watch was correctly excluded, and it
produced a real interleaved timeline whose causality reads correctly. What could **not** be verified
without a build on the phones is the on-device *write* path itself, so tomorrow's first run is also
that path's first proof. If `suite-*.log` doesn't appear, fall back to Share-the-report — that's the
status quo, not a regression.

Scope note: this is **step 1 of #1837 only**. The Mac-side `LogTapHost` drain (step 2) was
deliberately deferred — #1837 itself flags two unsettled questions (the `LogTapJoinToken` TTL is a
documented security control and a poor fit for a 3-minute human-paced run, and #1820's unvalidated
nonce width disarms that control outright). Those want your decision, not an unattended guess.

---

## What changed overnight

**Landed:**
- **#1841** — the #1637 implementation plan is on `main` (was sitting unmerged on a branch since 07-26).
- **#1637 body rewritten** — leads with the real failure loop; carries an explicit *"NOT superseded by
  #1655"* table so the fold-and-close mistake can't recur.
- **#1655 body corrected** — its "Blocked on" no longer asks whether the black-hole occurs (settled);
  it now names the real gap, the non-self-healing variant.
- **#1724 CLOSED** — your S6 run proved it. #1777 fixed it but said `part of`, so it never
  auto-closed. The dropped phone (`role=join`) emitted `WindowOpened(host, expires=…)` on the
  `LinkTimeout`/`markPartitioned` lane at t=9.3 s — the exact negation of the issue's claim.

**The #1637 fix — PR #1848, ready, held unmerged.** Full `./gradlew build detektAll --rerun-tasks`
green with all 5640 tasks *executed* (zero `FROM-CACHE`); the repro test runs on jvm, Android
debug+release, macosArm64, iosSimulatorArm64 and wasmJsBrowser; revert-the-fix confirmed it goes red
again. `JoinerResumeMachine.runReconnect` dwells one `HeartbeatConfig.timeout` on a persistent
not-yet-open reject, then completes as a local no-op resume via a new `onNoOpResume` →
`markRecovered(hostId)`. Net behaviour: a sub-timeout blip recovers at ~25 s instead of dying at ~60 s.

Four things the worker found wrong in the plan beyond the duration error — worth knowing before you
read the diff:
- The plan's test sketch **froze the clock**, so `clock() - since` is always zero and the dwell could
  never elapse — the test could not have passed even with a correct fix.
- `loom.enterWeaving()` doesn't compile (`enterWeaving`/`recover` are on `FlakyLifecycleSeam`).
- `FlakyLifecycleSeam` alone collapses the grace to zero, so the plan's own
  `grace < outage < hostTimeout` ordering wasn't representable; the landed harness layers
  `FlakyLifecycleSeam(FaultySeam(base))`.
- It **tightened the dwell gate beyond the plan**: the plan keyed only on `refusal.code`, which
  persists across attempts, so a host that went *silent* (`TimedOut`, #1587) would keep a stale dwell
  running and declare a no-op resume on a dead host. I verified this one against the source — it's
  correct and it's real hardening.

**New issue filed: #1858** — found reviewing #1848. `docs/agent-cookbook.md`'s resume sample branches
on `ResumeResult.WindowNotYetOpen` after `room.resume(token)`, but that case is **unreachable** on the
joiner path (any reject resolves as `WindowClosed`, with the code in `refusal`). The reachable branch
says "re-join fresh" — the opposite of correct, and it resets the slot. A consumer following the
published cookbook does the wrong thing in exactly the #1637 scenario. Compiles fine, so the
sample-compilation guard can't catch it.

**Corrected:**
- The plan's Task 4 Step 3 said *"~8 s — under the 15 s host timeout, over the 10 s `wovenPathGrace`"*.
  8 s is not over 10 s. Real window is **10–15 s** at production defaults. An 8 s hold never trips the
  grace timer, so the bug cannot appear — which is why S6's 8 s short leg has been passing all along
  on a build that still has it.

**Verified still broken** (all six steps of the loop intact on `main` as of `85a3ba34`):
`NwSeam.kt:997` grace 10 s · `HeartbeatPartitionDetector.kt:110` any frame refreshes `lastSeen` ·
`DefaultJoinerReconnectController.kt:70` → `WindowNotYetOpen` · `SeamRoom.kt:1287` → retryable reject ·
`JoinerResumeMachine.kt:388` retry-to-deadline → `Refused`. #1777 gave both roles a *deadline* once a
window opens; it does not make a window open, which is the actual failure.

Sharpest detail, worth keeping in mind while reading traces: **the joiner's own retries sustain the
bug.** Each `resume(token)` frame refreshes the host's `lastSeen`, which is what stops the host's
timeout from ever firing. The recovery attempt is what prevents recovery.

---

## Also open, not hardware-gated

- **#1636** — dead peer lingers in `seam.peers` ~213 s, `needs-design`.
- **#1618** Q1 (other detection branches) and Q3 (premature `Resumed`) — both explicitly outside
  #1712's scope.
- **#1796** — CI never compiles `:spike`, so the hardware-validation vehicle can break silently.
  Worth landing: both spike PRs above are invisible to CI today.
