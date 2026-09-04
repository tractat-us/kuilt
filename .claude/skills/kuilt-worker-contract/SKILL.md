---
name: kuilt-worker-contract
description: The contract for a background worker dispatched into an isolated git worktree in this repo — the five rules CLAUDE.md addresses to the dispatcher rather than to the worker, plus the verification traps that produce false greens here. Load this when dispatching a worker (to avoid re-typing it) or when you ARE a dispatched worker starting work in a .claude/worktrees/ checkout.
---

# kuilt worker contract

**A dispatched worker already has the CLAUDE.md hierarchy.** Measured 2026-09-03: a
`isolation: "worktree"` subagent's starting context contains `~/.claude/CLAUDE.md`,
`~/tractatus/CLAUDE.md` and the worktree's own `CLAUDE.md`, verbatim. A probe agent asked to answer
*without reading any file* quoted back the `detektAll` rule, the `pkill` ban, JDK 21 and the SDKMAN
rule. So **do not restate them in a brief** — a forty-line preamble of rules the worker already holds
buries the handful that are genuinely new.

Re-probe after a Claude Code upgrade, or for an agent type with a restricted `tools:` list. It costs
one haiku agent and about thirty seconds.

## The five rules that DO need restating

CLAUDE.md phrases each of these as an instruction to the *dispatcher* ("brief the worker to…"), so a
worker reads them as advice addressed to someone else.

1. **Prove your isolation before touching anything.** First commands: `git branch --show-current`
   and `pwd`. **If `pwd` is not under a path containing `.claude/worktrees/`, ABORT and report
   back.** Do not run `git checkout`, `git branch`, `git worktree` or `git reset` — you would be
   corrupting a live interactive checkout that a human is working in. Otherwise:
   `git fetch origin main && git checkout -b <branch> origin/main`.

   The defensive half matters more than the happy path. A brief that tells a worker to *self-correct*
   ("if you're on a session branch, branch from `origin/main`") makes it thrash branches in the
   dispatcher's checkout while trying to fix itself. Abort and report; never repair.

2. **Seed the build environment.** Before any Gradle call:
   ```bash
   export JAVA_HOME=/Users/keddie/.sdkman/candidates/java/21.0.5-tem
   export PATH="$JAVA_HOME/bin:$PATH"
   echo "sdk.dir=/Users/keddie/Library/Android/sdk" > local.properties
   ```
   `local.properties` is gitignored and absent in a fresh worktree. Set `JAVA_HOME` inline rather
   than sourcing SDKMAN — the `source` form has been refused in worktrees.

3. **Push after each finished piece**, not once at the end. Workers die two ways — a no-output
   watchdog and a plain API disconnect — and both leave the remote *not authoritative* about what
   you finished. Unpushed work is invisible to the dispatcher, who will read `gh pr view` and
   conclude the work was never done.

4. **Do not enable auto-merge.** Open the PR and return to the dispatcher for review. The review is
   the gate, and it is not yours to skip.

5. **If scope balloons materially, stop.** Land at the last green checkpoint, push, open a draft, and
   return a re-plan — done / remaining / suggested split. Do not grind to a truncated marathon. A
   PR with three solid pieces and an honest re-plan is worth far more than six half-done ones, and
   at night a worker that grinds is a lost cycle.

## Verification traps that produce a false green here

Every one of these has actually fired in this repo. A "green" that came through one of them is not
evidence.

- **A piped Gradle run masks its exit code.** `timeout 600 ./gradlew build | tail -30` returns
  *`tail`'s* status, so a timeout kill or a compile failure reports **exit 0 with no
  `BUILD SUCCESSFUL` line**. Assert on the output, never the status:
  ```bash
  ./gradlew build --max-workers=6 2>&1 | tee /tmp/build.log | tail -30
  grep -q "BUILD SUCCESSFUL" /tmp/build.log && echo GREEN || echo "NOT GREEN"
  ```
  or `set -o pipefail`, or read `${PIPESTATUS[0]}`. This is the general shape *a success code from a
  wrapper is a claim to verify, not a fact* — the same trap `gh-pr-wait`'s exit 0 sets, one layer down.

- **A module-scoped build is a false green** for anything touching a widely-implemented interface, a
  cross-module DTO, a wire boundary, consensus *behaviour*, or a `*.gradle.kts`. It compiles neither
  the Android variants nor Kotlin/Native nor wasm — and several subclasses of the conformance suites
  live in `appleTest` and `wasmJsTest`, so a new abstract member's compile break **cannot** appear in
  `jvmTest`. Run the full `./gradlew build`.

- **Bare `detekt` is `NO-SOURCE`** and reports success without analysing anything. Use `detektAll`.
  Know its reach: it covers `commonMain` + `jvmMain` + `androidMain` production + `jvmTest`, and
  reaches **nothing** in `appleMain`/`appleTest`, the native/wasm source sets, `commonTest`, or
  `spike/`. A green `detektAll` on a PR touching only those proves nothing — say so rather than
  citing it as a gate.

- **The build cache can serve a stale `FROM-CACHE` success** for a test-compile task whose source is
  broken. If any test-compile task shows `FROM-CACHE`, re-run with `--rerun-tasks` (add
  `--no-build-cache` if it persists) and confirm tasks are `EXECUTED`.

- **A `--tests` filter matching nothing passes silently.** Confirm your new tests actually *ran* by
  counting them in `build/test-results/`.

## Sharing the machine

Four or five workers build concurrently on one 16-core box.

- **Never pattern-kill.** No `pkill -f`, no `killall`, no pattern of any kind. Isolation is a
  *filesystem* boundary, not a process one — every worker's Gradle daemons and test JVMs share one
  machine, so a pattern kill reaps a **sibling's** build. The damage lands on a different agent and
  arrives disguised as a failure in *its* change: a crash, a truncated results XML, a one-off that
  will not reproduce. The honest reading of that evidence is "my change broke something" or "this
  test is flaky", and both are wrong.
- **`./gradlew --stop` is NOT the safe alternative — it has the same blast radius.** Gradle daemons
  are pooled per Gradle version and JVM args, **not** per project, so every worktree of this repo
  shares one pool and `--stop` reaps a sibling's daemon exactly as `pkill` would. Measured
  2026-09-03: a worker's full build died at 5279 tasks with *"Gradle build daemon has been stopped:
  stop command received"*, from a sibling's `--stop` — **and the wrapper reported exit 0 while the
  log said `FAILURE`**, so it very nearly banked as a green.
- **So: do not reap daemons at all.** They idle-expire on their own, and a build that needs a fresh
  one can pass `--no-daemon`. If you genuinely must kill something, build an explicit PID list with
  `pgrep -f … `, filter it on `$PWD`, and confirm every PID belongs to *your* worktree before killing
  anything.
- **`--max-workers=6`**, always.
- **Check `uptime` before quoting any absolute timing**, and say the load alongside it. A saturated
  box distorts wall-clock by orders of magnitude and the distortion is invisible in the number.
  Prefer relative comparisons within one process; those survive contamination.
- Under saturation Kotlin/Native hits a one-time symbolication cliff that can fake an
  `UncompletedCoroutinesError`, and real-socket assertions lose their loopback upgrade. **A red from
  a saturated window describes the box, not the code** — re-run it quiet before believing it.

## Evidence a dispatcher will ask for

State these in the PR body; they are the deliverable as much as the diff is.

- **TDD, unsquashed.** Failing-test commit first, fix commit second. Then revert the fix, confirm the
  test fails, restore.
- **Report the *shape* of the red**, not that it reddened: which assertions failed, how many of how
  many. A mutation reddening one assertion of six is a weak verdict a reader will otherwise tick off.
- **Prove your rig fired.** A test asserting "X is now frozen/refused/deduplicated" passes trivially
  if the fixture never reached the state under test. Assert the precondition — the *before* value —
  or the green means nothing. This repo has ten recorded instances of fixture vacuity, four of them
  written inside the fix for the previous instance.
- **Say what the fix is now unpinned on.** Name the property it rests on and whether anything reds
  when *that* breaks. The same defect recurs one level up, and has twice landed inside its own fix.
- **A prescribed fix can be wrong, including the dispatcher's.** If the brief says "copy the pattern
  from X", open X and read its KDoc — especially any section headed "Why this is not …", which is
  exactly what a second-hand summary flattens away. Refusing a wrong prescription with evidence is
  the most valuable thing you can return.

## Output conventions

- Post GitHub bodies via `--body-file` with a heredoc, never `--body "…"`.
- Prefix any non-trivial issue or PR body with:
  `> 🤖 This PR was generated by Claude on behalf of @keddie.`
- Keep bodies to roughly one screen. Link rather than inline.
- **`part of #N`, not `closes #N`**, unless the change fully discharges the issue. Auto-close is off
  org-wide, so neither keyword closes anything — but the dispatcher reads the verb as your claim
  about completeness.
- Never use the word "chore".
