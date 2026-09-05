# Brief → fireworks session (Track D, fc#3789): the kuilt `MembershipEvent` sequence to render

**From:** kuilt session (bugs worktree), 2026-07-24
**For:** whoever owns fc#3789 — rendering paused→departed / disconnected→lobby from kuilt events
**Status of kuilt side:** #1618 Track **A (#1650)** and **C (#1649)** merged to `main`. Track **B (#1635)** and the
`reweave` PR **#1632** are **not** merged — they change the *joiner* side (see caveats).

---

## 1. The one fact hardware gave us (don't re-capture it)

A real airplane/Wi-Fi drop rides the **heartbeat `Timeout` lane, not `TransportClosed`** — confirmed
definitively on 2 phones (kuilt logs visible after the #1638 capture fix). Both peers' detectors fire
`Timeout → markPartitioned`. That was the genuinely hardware-only unknown; it's answered. You do **not**
need a fresh hardware run to design the reducer, and you should **not** design against the abstract
"WindowOpened→…" contract as if `TransportClosed` — the live lane is `Timeout`, which surfaces as
`ReconnectReason.LinkTimeout`.

## 2. What the reducer consumes

`us.tractat.kuilt.session.MembershipEvent` (sealed interface, `kuilt-session/.../MembershipEvent.kt`).
Every variant is **dual-role** — the *same* type appears on the host's and the joiner's event stream with
`peerId` meaning "the peer whose link dropped" (the joiner on the host's side; the host on the joiner's side).
The KDoc on each variant states the dual-role precisely — read it, it's the contract.

Variants: `Joined · Partitioned(peerId, at, reason) · WindowOpened(peerId, expiresAt) · Recovered(peerId, at) ·
Resumed(peerId) · Left(peerId, reason) · HostLost(at, reason) · AdmissionFailed(reason, at)`.

## 3. Design the reducer by EVENT TYPE, not by reason (this is the stable, settled part)

The type→UX-state mapping is settled and identical across host/joiner and across whatever B lands:

| MembershipEvent | UX state | Notes |
|---|---|---|
| `Partitioned` | **reconnecting…** (peer paused) | link down, window may open next |
| `WindowOpened` | **reconnecting… + countdown to `expiresAt`** | the 60 s grace bar the reporter wanted |
| `Recovered` / `Resumed` | **restored → back to Connected** | transient blip healed; clear the overlay |
| `Left` | that peer **departed** (roster shrinks) | terminal *for that peer* |
| `HostLost` | **host gone → lobby / abandon** | terminal, joiner-only; no auto-re-elect |
| `AdmissionFailed` | never joined → error/lobby | distinct from `HostLost` (pre-admit) |

**`reason` fields are flavor text for the "reconnecting…"/"lost" copy — do not branch terminal-vs-transient
on them.** The type already tells you terminal (`Left`/`HostLost`) vs transient (`Partitioned`/`WindowOpened`
→ `Recovered`/`Resumed`). This keeps you robust to §5's caveats.

## 4. The sustained airplane-drop sequence (the #1618 scenario)

**Host (surviving phone) — SETTLED by merged C (#1649):**
```
Partitioned(peerId=joiner, reason=LinkTimeout)
  → WindowOpened(peerId=joiner, expiresAt = now + reconnectWindow)   // ~60 s countdown
  → [ transient heal:  Recovered / Resumed  →  back to Connected ]
  → [ sustained:       Left(peerId=joiner, reason=PartitionExpired) ]  // C's WindowExpired backstop evicts
```
Pre-C the host stuck in `Partitioned` forever (never emitted `Left`); C is exactly the fix that guarantees
the terminal `Left`. This branch you can build against now with confidence.

**Joiner (dropped phone) — fast self-detection by merged A (#1650), but exact stream PROVISIONAL:**
```
[fast local-path-loss self-observe, ~10 s grace not ~75 s]  →  … terminal  HostLost(reason=<FailureReason>)
```
A makes the dropped phone self-observe in seconds (was: frozen, never detected — see morning-report
"attempt #2"). Whether it emits a `WindowOpened → HostLost` pair or an immediate `HostLost`, and the exact
`FailureReason` (`WindowExpired` vs `Unrecoverable`), is **not final until B/#1632 land** — this is the code
zone they rewrite. The **terminal type is stable: `HostLost`** for a sustained drop. Map any `HostLost` (any
reason) → lobby/abandon; don't hard-code the reason.

## 5. Caveats to code defensively around

- **B-3 (plan §Track B): the joiner resume path currently emits a *wrong* `reason`/roster** — `onReconnectStarted`
  hardcodes `ReconnectReason.TransportClosed` and doesn't flip roster liveness to `Partitioned`. So on the
  joiner side, `Partitioned.reason` and roster `Liveness` may be dishonest until B lands. Another reason to
  branch on **type, not reason/roster**, for now.
- **No auto-re-election** after `HostLost` — kuilt does not pick a new host. "Back to lobby" is the app's call.
- After `HostLost`/terminal, `Room.broadcast`/`sendTo` are silent no-ops — safe to call, they do nothing.

## 6. Ground-truth pin (kuilt side will provide)

The §4 sequence above is derived from the merged A+C code against the hardware-confirmed `Timeout` lane. To
hand you an **executable** pin (not a derivation), the kuilt session will add a `FaultySeam`/`MeshTestHarness`
mesh test (harnesses already exist in `kuilt-test` + `kuilt-session/.../partition/`) that injects the confirmed
`Timeout` lane against merged A+C and asserts the exact both-sides `MembershipEvent` stream. When that lands
we'll update §4's joiner branch from "provisional" to "pinned." **You are unblocked now on §3 + the host branch
of §4;** wait on the pin only for the joiner terminal `reason` polish.

## 7. Reconcile with

fc#3634 / #3647 / #3649 (existing fireworks presence/lobby work) — the plan flags Track D should reconcile with
these rather than add a parallel path. Also: once fireworks bumps to a kuilt snapshot containing **#1648**, drop
the app-side `installKuiltAwareLogCapture` shim (superseded).
