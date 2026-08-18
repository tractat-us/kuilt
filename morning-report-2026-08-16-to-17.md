# Morning report — 2026-08-16 → 17

_`/burndown` → `/goodnight`. Worktree `kuilt-worktrees/bugs`. `main` `1835957b` → **`6e36b56d`**._

## TL;DR

**12 PRs merged, 12 issues closed, 11 filed (6 still open), 5 wrong-or-stale issue bodies corrected.**
The **#1816 "collapse `peers` on tear" family is CLOSED**, and it ended with a standing build guard
rather than five point fixes. Pipeline empty, branches deleted, worktrees reclaimed, nothing blocked,
**no decisions waiting on you.** Next steps are in `next-plan.md`.

**Two things I'd read first:** `sendTo` was silently misdelivering (below), and four separate issue
bodies prescribed a fix that was wrong.

## Merged

| PR | Issue | |
|---|---|---|
| #2426 | #1853 | WebRTC `close()` didn't collapse `peers` |
| #2429 | #1748 | `-P` test flags never reached the test JVM |
| #2430 | #1851 | Multipeer `tearDown` never touched `_peers` |
| #2431 | #1854 | the two `:kuilt-test` fakes |
| #2434 | #1849 | `InMemorySeam` + new `LatchingStateFlow` |
| #2435 | #2428 | **`sendTo` refuses a self-send** |
| #2437 | #1871 | **the seam-harness coverage guard** |
| #2438 | #2427 | WebRTC tear is single-shot; first `CloseReason` wins |
| #2440 | #2436 | `ManagedSeam` publishes its own `selfId` |
| #2442 | #2433 | WS route liveness — **refutes its own issue's hypothesis** |
| #2446 | #2441 | bind `ControllableSeam` + the **Apple** `MCSessionLink` to the TCK |
| #2447 | #2444 | the Apple link rejects sends on a `Torn` seam |

## 🔴 `sendTo` was silently misdelivering

Verified against `main` before believing it. `LinkSeam.sendTo` guards with `peer !in _peers.value` —
and `selfId` **is** in `peers`, so it passes — then `enqueue(payload)` writes to the single
connection. On a 2-peer link that is **the remote peer**. `LinkSeam` backs **WebSocket, TCP and
mDNS**; `WebRTCPeerLink` is the same shape; `BridgePeerLink` handed its own id to
`mc_session_send_to` as a native addressee.

So `sendTo(selfId, …)` delivered your payload to somebody else and reported success. Your one-line
call on #2428 fixed a routing bug, not a contract nicety. It stayed invisible for exactly the #1871
reason: **no TCK property ever asked.**

## #1871 answered with a number: 20 of 34 seams had no harness

`docs/seam-harness-coverage.md` now enumerates every production `Seam` against a harness or a
**written opt-out reason**, enforced by `verifySeamHarnessCoverage` in the root build. It proves
**enumeration completeness, not coverage** (whether a harness truly exercises a seam is not
statically decidable) and says so in the guard comment, the doc, and the failure message.

Then #2446 bound two of them and **immediately found three more bugs** — #2443, #2444, #2445. #2444
is already fixed. The list is now **18 of 34**, and it *is* the backlog.

Standout row: **`PrincipalSeam`** — live on any server with a `principalExtractor`, no harness, no
unit test. I tried to refute that (two `withPrincipal` overloads, both called in `:kuilt-websocket`)
and couldn't.

## Four prescribed fixes, all wrong

Each was plausible, written with the code in hand, and would have merged green:

1. **#1849's `MappedStateFlow`** — violates that primitive's documented **injectivity** precondition.
   Caught pre-dispatch; the mutation matrix then proved it empirically (passes three properties while
   emitting the roster 4× to a pre-tear collector).
2. **#2428's "delete the failing assertion"** — it was the **only detector** of the misdelivery above.
3. **#2436's `+ selfId`** — would have satisfied `Seam.peers` while **breaking Raft relay routing**:
   `RoutedRaftTransport.playerServerHop` requires *exactly one* non-self peer and drops every relayed
   send on more.
4. **#2433's "poll until it stops returning 404"** — a healthy kuilt WS route answers a plain GET with
   **400**. The poll would exit on the first attempt proving nothing. *(I endorsed this one in the
   brief; the worker caught it.)*

What caught them every time was checking the **consumer**, or the **primitive's own stated contract**
— never re-reading the issue.

## The same defect kept recurring inside its own fix

On #2434, three times: the prescribed fix was wrong → the replacement had the same defect at its
**boundary** (its three tests used a connected *pair*, so the rig structurally couldn't reach the
case — green by construction) → the natural fix for *that* introduced a **hang** for a post-tear
subscriber. Each was caught by asking what the fix now rests on.

## Filed — 11 total, 6 still open

Closed the same night by fixes that landed: **#2427**, **#2428**, **#2433**, **#2436**, **#2441**,
**#2444**. Still open:

- **#2432** `FakeSeam`'s ctor can build a `Torn` seam with an un-collapsed roster (gates binding it)
- **#2439** (`needs-design`) bind `ManagedSeam` + the unwoven decorators
- **#2443** torn `ControllableSeam` roster — needs `LatchingStateFlow` promoted out of `:kuilt-core`
- **#2445** Apple self-connection guard — **the obvious fix was tried and reverted**: the Apple send
  paths read `session.connectedPeers` directly, so the loopback survives and the guard becomes
  undetectable. Two coupled edits, neither provable alone.
- **#2448** **`sendOnTornSeamThrows` is satisfied by `PeerNotConnected`** (which extends
  `IllegalStateException`) — so a seam that blames the addressee for its own death passes the TCK.
  Proven by a per-guard revert: reverting only the `sendTo` guard leaves the suite **green**. Affects
  every fabric; tightening it needs a measured blast radius.

## Verification notes

- **`main` CI watched to green, not assumed** — including for the commit that added the structural
  guard. `publish` is **not** in `ci-required`, so a break there lands silently. **Final commit
  `6e36b56d`: `ci`, `publish` and `docs` all `success`.** Nothing outstanding.
- `MultipeerAppleConformanceTest` genuinely **runs** 30 tests on `macosArm64` and
  `iosSimulatorArm64` — the first real conformance coverage the Apple link has ever had.
- `detektAll` gated **none** of tonight's changed production files (`wasmJsMain`/`appleMain` are
  parse-only, #2039; #2429 was `.gradle.kts`-only). Said plainly in each PR rather than cited.

## Anomalies

- Box peaked at load 37/16 with two full builds; throttled dispatches rather than pushing past it.
- `merge-base --is-ancestor` reports **squash-merged branches as UNMERGED** — used PR state instead.
  The *opposite* trap from the `: gone]` recipe. Both are live in this repo.
- I twice backgrounded a merge poll with `&` inside a foreground call, orphaning it and suppressing
  its notification. Recovered both times; root cause (chaining) saved to memory.
