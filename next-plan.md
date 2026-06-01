# kuilt — do next

_Updated 2026-06-01. `main` @ `80ce5bb` (#91 merged). No PRs in flight, no stale branches/worktrees/stashes._

## Just landed (this session)

**#64 kuilt-test fixture — MERGED ✅.** PR [#88](https://github.com/tractat-us/kuilt/pull/88) (`FakeSeam`/`FakeLoom`/`fakeSeamPair`). #64 CLOSED.

**Polish PR [#90](https://github.com/tractat-us/kuilt/pull/90) — MERGED ✅.** `FakeSeam` `initialPeers` default `setOf(PeerId("self"))` → `setOf(selfId)` + regression test.

**#89 FakeRoom → PR [#91](https://github.com/tractat-us/kuilt/pull/91) — MERGED ✅ @ `80ce5bb`.** New **`:kuilt-session-test`** module (`api(:kuilt-session)`, keeps kuilt-test core-only — user-confirmed placement) shipping `FakeRoom` + `FakeRoomFactory` + `fakeRoomPair` + README + 40 tests. Architect review = APPROVE-WITH-NITS; nits applied (KDoc on the hot-vs-buffered stream divergence vs real `Room`, self-roster guard, private `incomingChannel`, +2 tests). #89 CLOSED, worktree/branches pruned, `main` fast-forwarded. NOTE: still did NOT do the `assertAll()` convention pass (the local `assertAll` helper runs assertions sequentially, not collect-all) — left for a future tidy-up.

Other threads from this plan still untouched: **self-merge guard** (process), **native CI gating** (infra), **deferred Ply roadmap** (feature).

## What just landed (this session)

Epic #49 (many-transport "Ply" composite fabric) + resilience testing, all merged & closed:
- Contract/impl: #59 `PlyId`+`Seam.plies`, #60 `PlyFrame`, #61 `PlyInboundGate`, #62 `CompositeLoom`/`CompositeSeam`.
- Tests: #63 conformance + multi-ply, #67 composite resilience, #77 codec edge cases.
- Resilience primitive + foundation tests: #65 `FlakyLifecycleSeam`/`Loom`+`FlapSchedule`, #66 contract/session/soak.
- Session: #73 `SeamRoom` observes `SeamState.Torn` → immediate `HostLost`.
- Hardening: #82 (Native flakiness), #71 (peers dual-write narrowed + tested).
- Foundation doc: `docs/testing-coroutine-determinism.md` + `CLAUDE.md` convention bullet.

## Open decisions / threads (pick one)

1. **Self-merge guard (process, ~30 min).** Two workers ran `gh pr merge` themselves this session, bypassing review. To hard-prevent: tighten branch protection (require a review, or restrict who/what can merge) or scope dispatched-worker GH tokens to no-merge. `main` protection today requires only `ci-required` (no reviews). Decide whether to add a gate.
2. **Native CI gating (infra, deferred this session).** CI `build` runs on `ubuntu-latest` and **cannot run Apple-native tests** (`macosArm64Test`/`iosSimulatorArm64Test`) — they only run locally on a Mac. A real native bug (caught as #82) is invisible to the merge gate. Options: add a `macos-latest` CI job to `ci-required` (per-PR, ~10× cost) or a nightly stress job. Chosen "convention only" for now; revisit if native regressions recur. See `docs/testing-coroutine-determinism.md`.
3. **Deferred Ply roadmap (feature, build when a consumer needs it).** From `docs/superpowers/specs/2026-05-30-multi-transport-ply-fabric-design.md`, explicitly out-of-scope future work:
   - Latency-optimized **primary-ply-per-peer** send (replace broadcast-all + dedup; the inbound gate makes it a safe swap).
   - Application-layer **single-hop gateway forwarding** (the "piggyback" scenario — wire is already reserved via the explicit `(originId, originSeq)` envelope).
   - **Dynamic ply attach/detach** mid-session (plies are fixed at `weave()` today).
   - Server-to-server **federation** (below-kuilt; a federated relay is one ply to the `Seam`).

## Carry-forward / cleanup

If cleanup wasn't already run this session: prune merged local branches and `worktree-agent-*` branches (all correspond to merged/closed PRs from this session):
```
git fetch --prune origin
git branch | grep -vE '^\*|(^| )main$' | xargs git branch -D
```
`git worktree list` should show only the main checkout.

## Other open issues (unrelated to this session)

- **#64** — ship a `kuilt-test` fixture (`FakeSeam`/`FakeLoom`) so consumers stop re-implementing `Seam`.
- **#44** — publish to Maven Central (Sonatype OSSRH).
- **#11** — epic: `:kuilt-session` membership/room layer over `Seam`.
