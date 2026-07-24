# #1618 self-detection & recovery — Plan (v2, post-Fable)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`. **Rebase onto `origin/main` first.** Hardware validation uses the tethered **iPhone XS** over USB (see `fireworks-compose/docs/one-phone-hardware-debugging.md`). NB: airplane-mode toggles need a human — code + instrumentation land unattended; the device toggle is a morning step.

**Goal:** fix the *actual* #1618 behavior proven on hardware. Revised after an adversarial (Fable) review that found the v1 "ready" track unsound.

## Hardware ground truth (both phones, airplane drop, kuilt logs)
Both peers: `membership.unresponsive reason=Timeout branch=markPartitioned` at ~15 s. Neither took `TransportClosed→resume` ⇒ **#1632 never fires.** The dropped phone produced no *logged* fast local signal (but the `NWPathMonitor` update handler logs nothing — the signal likely fired and was discarded). Surviving host `markPartitioned` never reached `PeerLost`/evict. App resolved nothing.

## Acceptance criterion (state it in EVENTS, per Fable)
- **kuilt-side (this plan):** on any drop, both peers emit the correct partition/liveness events **fast and correctly** — the dropped peer self-observes near-grace (not 15–75 s); the host progresses `Partitioned → WindowOpened → (Recovered | Left)` and actually evicts; reasons + roster liveness are honest.
- **user-visible (Track D, fireworks):** even with perfect kuilt events, a *sustained* symmetric drop correctly ends both sides terminal (`HostLost`/`Left`) — "neither side knows" persists until fireworks **renders** these events. **#1618's user symptom is not closed by kuilt alone.**

## Tracks (priority: **C1 ≈ A0 > B**, and D is required for the user symptom)
| Track | Layer | What | Readiness |
|---|---|---|---|
| **A** | kuilt-nw | Fast self-detection via device-path-unsatisfied → `setViability(false)` | A0 spike ready to dispatch |
| **C** | kuilt-session | Host reaches eviction; find why `PeerLost` didn't fire + add a backstop | C1 investigation ready to dispatch |
| **B** | kuilt-session | Route host-`Timeout` into resume — **REDESIGN required** (tear-aware `runReconnect`) | NOT ready; redesigned below |
| **D** | fireworks | Render `Partitioned`/`WindowOpened`/`HostLost`/`Left` on both sides | separate app plan (the user-visible fix) |

## Global Constraints
`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`; `explicitApi()`, no `!!`; coroutine-test discipline (StandardTestDispatcher, bounded advance, no `advanceUntilIdle`, canonical sim harness, tight timeouts, hang=STOP). Diagnostics log identities+state. Commits "part of #1618"; no `closes #1618` until hardware-validated. #1632 stays open as **TransportClosed-lane hardening only** — re-scope its PR body.

---

## Track C1 (DISPATCH NOW — investigation, cheapest safe land)
Instrument, don't fix. Fable near-falsified the v1 "stray-frame refresh" hypothesis (it would emit `PeerRecovered`/`membership.recovered`, absent from the capture). Discriminate these:
1. **`PeerLost` emitted but never collected** — the per-detector event collector (`SeamRoom.kt:1311-1313`) dead/cancelled; the `UNLIMITED` channel emit succeeds silently either way.
2. **iOS process/dispatch suspension** stretching the `delay` loop (capture-env, not kuilt).
3. **Capture window artifact** — host `PeerLost` due exactly +60 s from `markPartitioned`; confirm the run went past 21:51:08.

- [ ] **C1.1:** in `HeartbeatPartitionDetector.awaitRecoveryOrLoss` (`:164-185`) log each poll's `elapsed`, `silenceMs`, **and wall-clock delta** (to catch suspension); log at the `PeerLost` emit AND at `SeamRoom.handlePeerLost`; log detector event-collector start/cancel. Identities + state.
- [ ] **C1.2:** ship it (part of the XS instrumented build, below), re-run the drop, read which of 1–3 it is. **No fix before evidence.**

### Track C2 (fix — shape from C1) + structural backstop
Regardless of C1's root cause, host eviction is a **single point of failure**: `WindowExpired` deliberately does NOT evict (`SeamRoom.kt:728-733`) and last-remote loss re-forms to `Weaving` not `Torn`, so `runTornWatcher`'s host eviction (`:668-676`) never fires — the detector's `PeerLost` is the ONLY evictor.
- [ ] **C2.1 (TDD):** add a backstop — on `reconnectController` `WindowExpired`, if the member is still `Partitioned`, evict (`Left(PartitionExpired)`). Test over a `FaultyLoom` mesh: host `Timeout → WindowOpened → WindowExpired → Left` even if the detector's `PeerLost` is suppressed.

---

## Track A0 (DISPATCH NOW — spike; instrument the path monitor)
Fable: the `NWPathMonitor` is live during a session (`RealNwApi.kt:243-275`, collected `NwSeam.kt:287`) but its update handler logs nothing (`RealNwApi.kt:383-385`) and folds only into `_capability` (`NwSeam.kt:697-711`) — so "no signal" is unproven; it likely fired and was discarded.
- [ ] **A0.1:** log every `NWPathMonitor` update (`satisfied`/`unsatisfied` + interface types + timestamp) and every connection state transition in `RealNwApi`; ship on the XS build; observe normal operation + (morning) an airplane toggle to measure the path-unsatisfied **latency**.
- [ ] **A0.2:** confirm the signal exists + its latency, then A1.

### Track A1 (fix — the SAFE hook, per Fable)
Do **not** "mark all connections lost immediately" (evicts healthy sessions on every roam/handoff — `NWPathMonitor` fires on every path reshuffle). Instead:
- [ ] **A1.1 (TDD off-device via a fake `NwApi` path callback, then hardware):** on device-path-**unsatisfied**, drive `setViability(id, false)` for all live connections (`RealNwApi.kt:308-318`), reusing the existing #1478 `PathLost → wovenPathGrace(10 s) → reconcile → tear` pipeline (`NwSeam.kt:724-804`). A blip that recovers within grace does nothing (false-positive-safe by construction). Detection ≈ grace (tunable), not 1 s.
- **Doc the coverage limit:** this catches airplane / all-radios-off, **not** walking out of AWDL range (device path stays satisfied via cellular). A closes the demo'd reproducer, not the general peer-link-loss gap.

---

## Track B (REDESIGN before dispatch — NOT the one-line flip)
Fable found the v1 gate-flip unsound: `runReconnect` assumes it was entered with the seam **Torn**; a `Timeout` entry (seam still `Woven`) causes:
- **B-1 seam leak:** the throwaway-seam close only runs in the `Torn` branch (`JoinerResumeMachine.kt:349-360`); Woven-entry skips it, and `NwLoom.weave` always mints a NEW `NwSeam` (`NwLoom.kt:131-132`) → every retry leaks a live seam (real for factory-join, `SeamRoom.kt:168`).
- **B-2 regression:** a mid-window transport tear (t≈75 s) during a Timeout-entered reconnect hits the `became-Torn` branch as "non-conforming loom" → terminal `Unrecoverable`; with `reconnectWindow>60 s` this **pre-empts** the today-working Torn-watcher late-restore (`SeamRoom.kt:689-692`). Highest regression risk.
- **B-3 semantics:** `onReconnectStarted` hardcodes `ReconnectReason.TransportClosed` and never flips roster liveness to `Partitioned` (`SeamRoom.kt:618`) — unlike `markPartitioned` (`:1384-1398`). Under B the app (Track D) gets a wrong reason + a `Connected` roster.

**Redesign — make `runReconnect` tear-aware (4 steps, each TDD):**
- [ ] **B.1:** while the seam is `Woven`, do NOT reweave — attempt `resume(token)` directly and poll; only reweave after observing `Torn`.
- [ ] **B.2:** close a throwaway seam **unconditionally** when `reweaveFn()` returns an instance `!== seam` (not only in the Torn branch).
- [ ] **B.3:** a seam that *becomes* `Torn` mid-Timeout-episode means "the transport finally tore — start healing" (defer to the self-heal/redial path), NOT "non-conforming loom → Unrecoverable".
- [ ] **B.4:** thread the real `ReconnectReason` (Timeout vs TransportClosed) through `onReconnectStarted`, and flip host roster liveness to `Partitioned` on reconnect start (parity with `markPartitioned`).
- [ ] **B.5:** only then flip `handleUnresponsive`'s gate to admit joiner-host `Timeout`. TDD: transient-heal-in-window → `Resumed`; sustained → terminal with the honest reason; **no seam leak** (assert `framesDropped`/instance count); `reconnectWindow=90 s` does not regress the Torn-watcher path.

**Honesty:** with `reweave={seam}`, recovery hinges entirely on NwSeam's own redial self-heal reviving the link inside the window — **unproven for the airplane reproducer**; hardware validation is load-bearing, not confirmatory. Consensus/reconnect-adjacent → careful review + hardware before merge.

---

## Overnight vs morning
- **Overnight (unattended):** dispatch C1 + A0 (investigation + instrumentation), land the C1/A0 instrumentation + (if C1/A0 evidence is conclusive from static analysis) the C2/A1 fixes with local tests, **hold merge**; build the instrumented app and stage it on the XS. Do NOT rush Track B (redesign is consensus-adjacent — write it, don't implement it unattended).
- **Morning (human):** one airplane toggle on the XS with the instrumented build → read A0 path-latency + C host-eviction; then finish A1/C2 fixes and Track B against real evidence; hardware-validate before any `closes #1618`.
