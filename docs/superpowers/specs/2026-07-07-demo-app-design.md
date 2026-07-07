# Patchwork — the kuilt demo app (design)

> Design + build plan for issue #1196 ("an all-singing all-dancing demo app").
> This document proposes *what* to build and *in what order*; nothing here is built yet.

## The one idea

A group of people, each on their own device, stitch patches onto **one shared
quilt**. Everyone sees everyone else's patches appear live. Someone walks into a
tunnel and keeps stitching offline; when they come back, their patches merge in —
nothing lost, nothing doubled. And at any moment, a laptop can reach into any
running peer and **pull out its logs and metrics** to see exactly what that
device did.

That's the demo: **Patchwork**, a collaborative quilt board. It is deliberately
on-brand — the app's patches ride kuilt's `Swatch`es over `Seam`s woven by
`Loom`s, so the demo's vocabulary *is* the library's vocabulary. A non-engineer
watches a quilt fill in; an engineer watches every layer of kuilt work at once.

## Why a quilt board (and not tic-tac-toe)

Issue #1196 sketched tic-tac-toe. A quilt board is a better showcase for the
same effort:

- **Convergence is literally visible.** A grid of coloured cells filling in from
  several devices *is* CRDT convergence — no explanation needed. A partition
  demo ("go offline, keep stitching, reconnect") lands visually in seconds.
- **No consensus required.** Tic-tac-toe needs turn order, which drags in
  `:kuilt-raft`/`:kuilt-game` — already well covered by the `examples/` E2E
  tests. The quilt is pure CRDT: concurrent edits are the *point*, not a problem.
- **It composes several CRDTs naturally** instead of showcasing one.

## What it demonstrates, mapped to modules

| Demo feature | What the user sees | kuilt module(s) |
|---|---|---|
| The shared quilt canvas | patches appear from every peer, live | `LWWMap<Cell, Patch>` in `:kuilt-crdt`, replicated by `:kuilt-quilter` |
| Chat sidebar | ordered message log | `Rga` in `:kuilt-crdt` |
| Presence cursors | "alice is stitching at (3,7)", fades on leave | `EphemeralMap` in `:kuilt-crdt` |
| Stitch leaderboard | per-peer patch tally | `GCounter` in `:kuilt-crdt` |
| One session, many streams | quilt + chat + presence + tap on one connection | `NamedMux`/`MuxSeam` in `:kuilt-core` |
| Pick your wire | same app over relay WebSocket, LAN TCP, browser WebRTC, in-memory (tests) | `:kuilt-websocket`, `:kuilt-tcp`, `:kuilt-webrtc`, `InMemoryLoom` |
| Find peers on the LAN | CLI peers discover the relay without typing an address | `:kuilt-mdns` |
| Membership + reconnect | roster panel; drop and rejoin with a resume token | `:kuilt-session`, `:kuilt-liveness` |
| Offline → reconnect merge | tunnel mode: stitch offline, merge on return | offline-first `Quilter` anti-entropy |
| Scaling toggle | with N browser tabs, each peer talks to ~ln N neighbours; frames-sent metric visibly drops | `:kuilt-gossip` |
| Every peer records telemetry | logs + counters + histograms buffered durably on-device | `:kuilt-otel`, `:kuilt-otel-logging` |
| **Reach into a device** | a laptop harness pulls a running peer's logs/metrics live | `:kuilt-otel-tap` (`LogTapClient`, `MetricTapClient`) |
| Forward to real dashboards | drain the buffered telemetry to an OTLP collector | `:kuilt-otel-otlp` |

A deliberately nice loop: the **gossip scaling claim is proven by the demo's own
metrics** — toggle gossip on, watch the frames-sent counter (a kuilt-otel metric,
pulled over a kuilt tap) drop from O(N) to O(k). The observability story
instruments the networking story.

**Non-goals:** `:kuilt-raft` / `:kuilt-game` / `:kuilt-deal` / `:kuilt-cluster`
(consensus, fair dealing, server clusters). They have strong E2E coverage in
`examples/` already, and adding turn order would muddy the "concurrent edits
just merge" narrative. If appetite appears later, a "vote on the quilt's border
pattern" mini-feature could exercise Raft — parked.

## Platform: browser-headlined (RESOLVED)

The **wasmJs browser page is the headline platform** — open a URL, get a quilt,
N tabs = N peers. Iain confirmed this as the target. Why it's the best
showcase-per-ceremony:

- Instantly legible to a non-engineer — a link, not an install.
- Exercises the least-demoed fabric (`:kuilt-webrtc` is wasmJs-only) plus the
  browser `KtorClientLoom`, and proves the wasm WAL/offline-first story.
- No new toolchain: every kuilt module already builds wasmJs; the page is
  Kotlin/JS DOM, no Compose needed.
- The browser can't host a server, but the tap already handles that:
  `installLogTapJoining` lets the page *join* a session the laptop harness hosts
  — pulling logs out of a browser tab is a genuinely novel demo.

The **JVM CLI stays as an intermediate**, not a headline: a terminal peer plus a
small relay (`KtorRoomHost`) is the least-ceremony way to stand up a real
WebSocket fabric and prove convergence before the browser page exists. The CLI
and browser peers interoperate on one quilt.

**Mobile is parked as the separate-showcase endgame.** A Compose Multiplatform
app (Android headline for portability; iOS `:kuilt-multipeer`, Android
`:kuilt-nearby`) is the "pull logs off a real phone" story from #1196's genesis —
but it carries heavy toolchain that doesn't belong in a library repo. If built,
it lives in a **separate showcase repo** consuming the *published*
`us.tractat.kuilt:*` artifacts via the BOM (which doubles as a live test of the
consumer story and the Maven Central pipeline). Not part of this MVP.

## Where the code lives

New **unpublished** Gradle modules under a `demo/` directory (the existing
`examples/` module stays what it is — compiled JVM API-litmus tests):

| Module | Type | Contents |
|---|---|---|
| `:demo-shared` | KMP (jvm + wasmJs) | `PatchworkSession`: the CRDT composition + `Quilter`/mux wiring behind one small API (`stitch`, `say`, `presence`, `quilt: StateFlow`). Unit-tested over `InMemoryLoom`. |
| `:demo-relay` | JVM `application` | `KtorRoomHost` relay + mDNS advertise + log capture installed. |
| `:demo-cli` | JVM `application` | Terminal peer: ANSI quilt render, stitch/chat commands, `--fabric=ws|tcp|mdns` flag. |
| `:demo-tap` | JVM `application` | The reach-in harness: live log tail + metric snapshot from any running peer. |
| `:demo-web` | wasmJs browser | The DOM page: quilt grid, chat, presence, **offline/tunnel toggle** (the headline), telemetry panel. |

These apply plain KMP/application plugins, **not** `kuilt.kmp-library` (no
`explicitApi`, no publishing, no full target set). CI cost is bounded: demo
modules compile in the normal `build` graph but add no publications.

## Slices (RESOLVED — MVP only, each a small PR)

**The headline is convergence-under-partition** (the visual CRDT story); the
observability *pull* (the tap) is the **second act / follow-up**, not the opener.
The demo script and screenshots lead with a quilt filling in from several tabs,
then a tab going offline in a "tunnel," stitching, and its patches merging back
on reconnect. Only after that does the doc pull logs off a peer.

**MVP slices (build these):**

1. **This design doc** (the current PR).
2. **`:demo-shared`** — `PatchworkSession` over `InMemoryLoom`, fully unit-tested.
   Pure library composition; readable as documentation in its own right.
3. **`:demo-relay` + `:demo-cli`** — relay + two terminal peers converge over
   real WebSockets; `installLogCapture` on from the start. (CLI is the
   intermediate that stands up a real fabric; not the headline surface.)
4. **`:demo-web` — THE HEADLINE.** Browser page joining the relay session: quilt
   + chat + presence in the DOM, CLI and browser peers on one quilt, and the
   **offline/tunnel toggle** that makes convergence-under-partition visible (go
   offline, stitch, reconnect, watch patches merge). This is the demo.
5. **`:demo-tap` (second act)** — pull logs + metrics off a running peer, live
   tail, plus a small telemetry panel fed by the peer's own `WarpTelemetry`.
   *The #1196 genesis story, as the follow-up to the convergence headline.*

**MVP = slices 2–5.** That is the smallest build that delivers the
browser-headlined convergence demo (through 4) plus the observability-pull
follow-up (5). Stop here and reassess before doing more.

## Parked / future (NOT part of the MVP)

- **Gossip toggle** (`GossipSeam` O(N)→O(k)) — **parked.** Not worth the 10+-tab
  setup needed to make the scaling drop convincing.
- **OTLP-collector drain** (`docker-compose` + `:kuilt-otel-otlp` to real
  dashboards) — **future.** The on-device buffer and the tap pull tell the
  observability story without standing up a collector.
- **Compose Multiplatform mobile app** (Multipeer/Nearby, phone-tap-over-WiFi) —
  **future endgame**, in a separate showcase repo consuming published artifacts
  (see Platform, above). Optional, chiefly for the portability story.
