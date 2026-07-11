# Morning report — overnight 2026-07-10 → 07-11

## TL;DR
Night spine = **hardening the #1373 bug class** (a `Loom` factory holding a single active
session/link cleared only on explicit `close()`, so a self-terminating transport leaks the slot
forever). The fable audit found **the same bug on the jvm/macOS multipeer side** (#1373 fixed only
apple) + a latent **use-after-free** + a demo reconnect wedge → all fixed in **Draft PR #1374**
(left Draft for your review + hardware retest — native bridge change). Five other fabrics cleared
SAFE. The structural API fix is **filed as #1376** (needs a `Seam`-contract decision, correctly not
guessed). **5 PRs merged + a 6th (#1381) auto-merging:** Track B principal follow-ups all in
(#1375/#1377/#1378), the docs completeness follow-up (#1379), Track A #1366 MuxServerLoom teardown
(#1380, full-build+E2E green), and the #1368 resurrection sweep (#1381 — found+fixed one real
`SeamRoom` race). **All planned overnight work is landed or landing.** No release (human-gated
Maven Central). **Two things need your eye in the morning: PR #1374** (device retest) **and #1376**
(contract decision) — see Decisions needed.

## In flight at wake-up (needs you)
| Work | Issue/PR | State | Notes |
|------|----------|-------|-------|
| #1373 bug-class fix (multipeer-jvm + demo) | **PR #1374** | **Draft — reviewed ✅, finding fixed, GREEN** | Fable done + review-complete. Fixes jvm/macOS slot leak + use-after-free + demo wedge. Opus review: **no blocking defect**, close-exactly-once + apple-matching `onTerminated` confirmed. The one Important finding (JVM native-handle leak if a `Torn` seam is dropped without `close()`) is **fixed** — must-`close()` contract now in both KDocs + pinned by `terminalDropFreesTheSlotButLeavesNativeHandleForConsumerClose`. Full multipeer build green, all variants. **Only gate left: a real-device multipeer reconnect-after-drop retest — then ready + `--auto --squash`.** (Held at commit `e99f51e2`; worktree kept since the PR is open.) |

_All dispatched worker PRs have landed. The only in-flight item is #1374, held by design for your hardware retest._
| Post-close resurrection sweep | **PR #1381** (#1368) | ready, **auto-merge armed**, CI running | Sweep found ONE real gap — a `SeamRoom` resurrection race (detached `admitPeer` launch re-registering a peer after `leave()`); fixed by an atomic `closed` gate in `addToRoster`'s lock. TDD-reproduced; full `./gradlew build` green all platforms + E2E. Rest of the audit clean. Lands on green. |

## Still queued (morning dispatch)
- **#1376** (NEW, filed by the fable audit, `ready`) — the structural `kuilt-core` `ActiveSeamSlot` fix for the whole slot-leak class. Needs a `Seam`-lifecycle contract decision (every `Seam` must latch `SeamState.Torn` on self-termination; multipeer links don't today) → `SeamConformanceSuite` change. Design-first, not an overnight auto-merge.

## Not touched (other live sessions)
- **#1361** slice6-pr2a-raft-relay (Draft, BLOCKED) + its `pr2a-rebase` / locked `agent-a2365…` worktrees.
- Epic/tool worktrees: `bugs`, `c-bobbin`, `crdt`, `d-forge`, `docs`, `f-weave`, `logs`.
- `stash@{0}` (guide-branch wip — not mine).
- Parked by design: **#1367** (`needs-design`), **#1359** (`needs-design`).

## Cleanup done (from next-plan.md handoff)
- IDE checkout (`/Users/keddie/tractatus/kuilt`) returned to `main` (was on merged `weft-1330-dial-credential`), fast-forwarded +28 commits to `b359416d`.
- Deleted stale gone-upstream branches `weft-1330-dial-credential`, `writerside-quilt-branding`. `worktree prune`d.
- Left the two remaining `: gone]` branches (`crdt-fix-779`, `warp/c5b-wasm-runtime`) — held by live sibling worktrees.

## Shipped
- **#1381** (closes #1368) — post-close resurrection sweep. Found + fixed ONE real race: `SeamRoom.addToRoster` could re-register a peer via a detached `admitPeer` launch running after `leave()`; now gated by an atomic `closed` check under the roster lock. TDD-reproduced; full `./gradlew build` green all platforms + E2E. Audit table clean otherwise; shared "terminal-collapsible primitive" judged not-yet-justified (~3 sites) — no speculative issue filed. Squash-merged 22:11 ET. Worktree cleaned up.
- **#1380** (closes #1366) — `MuxServerLoom` now extends `ScopedCloseable`: pump `SupervisorJob` parented to the injected scope (parent-cancel now propagates), idempotent `close()` stops the accept loop + per-conn pumps + deregisters connections. Deliberate scope boundary: does NOT tear consumer-owned hosted seams. TDD + revert-verified (4/5 fail with the bug reintroduced); **full `./gradlew build` — 5009 tasks executed incl. E2E** — green. Squash-merged. Worktree cleaned up.
- **#1379** (follow-up to #1375/#1357) — architecture.md roster-first read rule now cites `Room.attestedPrincipals` (the gap #1375 deliberately left). Docs-only, squash-merged 21:18 ET. Worktree cleaned up.
- **#1378** (closes #1356) — `PrincipalAttestationConformanceSuite` TCK in `:kuilt-conformance` (the backstop for the #1352 hole). 6 invariants, mechanism-agnostic harness, grounded on BOTH `Mesh` and `RoomHubSeam`. TDD red 6/6 on a broken stub, green 12/12; full cache-disabled build green. Squash-merged. Worktree cleaned up.
- **#1377** (closes #1357) — `Room.attestedPrincipals` roster accessor in `:kuilt-session`, thin delegation mirroring `GameSession`. Abstract interface member; swept all `Room` impls (`SeamRoom`+`FakeRoom`). TDD red→green, full build green. Squash-merged 21:15 ET. Worktree cleaned up. **→ completes Track B (principal attestation).**
- **#1375** (closes #1358) — `docs/architecture.md` mux-hub attestation subsection + roster-first read rule. Docs-only fast-path green, squash-merged 20:57 ET. Worker correctly withheld documenting `Room.attestedPrincipals` (doesn't exist until #1357 lands). Worktree cleaned up.

## Release
- None. kuilt Maven Central is a deliberate human-gated minor-bump + `v<x.y.z>` tag; snapshots auto-publish per main push. No tag cut overnight.

## Decisions needed (morning)
1. **PR #1374 — ready + merge?** Fixes a real "reconnect wedges forever" bug on jvm/macOS multipeer (the side #1373 missed) + a use-after-free (double `mc_session_close`). TDD, cache-disabled full `:kuilt-multipeer:build`/`:demo-shared:build` green incl. native compile/link/test on the macOS host. **Opus review done: no blocking defect; close-exactly-once + apple-matching `onTerminated` both confirmed.** One Important finding (JVM-only native-handle leak if a `Torn` seam is dropped without `close()`) was fed back and is being fixed (contract doc + a `mc_session_close==1` test). Left Draft because it's a **native bridge handle-lifecycle** change — the review still recommends a **real-device reconnect-after-drop retest** (as #1373 got). My recommendation: skim the review thread on the PR, do the device retest, then ready+`--auto --squash`. The one open sub-item to confirm the finding-fix landed is in "In flight."
2. **#1376 — approve the `Seam`-contract direction?** The clean structural fix (a core `ActiveSeamSlot` that can't wedge) only works if every `Seam` latches `SeamState.Torn` on self-driven termination. Today multipeer links stay `Woven` on peer-drop; only `close()` sets `Torn`. Adopting the slot means changing the documented `Seam` lifecycle + adding a `SeamConformanceSuite` assertion across all fabrics. Recommendation: worth doing (it's the real fix; #1374 is per-fabric whack-a-mole), but it's a design-first track — start it deliberately, not overnight.

## Follow-ups filed
- **#1376** — structural `kuilt-core` fix for the single-active-session slot-leak class (`ready`). Filed by the fable audit instead of guessing the contract change. See Decisions needed #2.
- Tiny docs touch-up **dispatched** (a worker is adding `Room.attestedPrincipals` to the architecture.md roster-first read rule now that #1357 landed — deliberately omitted in #1375 when the API didn't exist). Auto-merges on green.

## Anomalies
- **Fable agent stalled once mid-finalize** — it backgrounded its cache-disabled verification build and stopped (subagents aren't woken by background jobs; known pattern). Recovered via SendMessage → it re-ran the build in foreground (green, 1785 tasks EXECUTED) and finalized cleanly. No impact on the result.

---
_Running log — updated through the night. Last update: dispatch time._
