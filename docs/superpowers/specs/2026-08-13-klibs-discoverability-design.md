# klibs.io discoverability — design

**2026-08-13.** Approved.

## How the card is built

From [JetBrains/klibs-io](https://github.com/JetBrains/klibs-io) source:

- **Description** — GPT, 20–30 words, from the README.
- **Tags** — GPT picks ≤10 from a closed list of 180 (<https://api.klibs.io/tags/allowed>).
- **Packages tab** — each artifact's POM `<description>`.

Four things follow:

1. GitHub topics are not an input. kuilt's `crdt`/`raft`/`p2p` topics can't reach klibs.
2. The tag list has no `crdt`, `raft`, `consensus`, `p2p`, `webrtc`, `opentelemetry`, `metrics`, `offline-first`, or `sync`. No README wording produces them.
3. Description and tags are generated **once** and never refreshed (`WHERE description IS NULL`, `NOT EXISTS project_tags`). Editing the README won't change the card. The supported path is their [Suggest an edit](https://github.com/JetBrains/klibs-io/issues/new?template=suggest_an_edit.yml) issue.
4. The tagger reads the whole README, so the bad tags are a vocabulary mismatch, not truncation.

So: **tags carry browsing, the description carries search.** Anything that can't be a tag goes in the description.

## Part 1 — klibs edit request

Description (28 words, their house style — no Kotlin/platform/license mentions). The card truncates near 12 words, so the un-taggable terms go first:

> Peer-to-peer sessions with CRDT state replication, Raft consensus, and offline-first telemetry, over interchangeable transports — WebSocket, LAN discovery, WebRTC, Apple Network.framework, Nearby — plus turn-based multiplayer and fair card-dealing primitives.

Tags, in relevance order. Counts are projects sharing the tag, out of 4,195 — six of ten are under 120, four rarer than the 100th tag:

| Tag | Count | Why |
|---|---|---|
| `network` | 104 | the `Loom`/`Seam` contract |
| `state-management` | 165 | CRDTs as replicated state |
| `analytics` (*telemetry*) | 40 | `kuilt-otel*` |
| `gamedev` | rare | `kuilt-game`, `kuilt-deal` |
| `concurrency` | 38 | `MuxSeam`, `CompositeLoom` |
| `messaging` | rare | frame transport, `kuilt-gossip` |
| `storage` | 116 | `kuilt-bolt` |
| `cryptography` | 101 | SRA, commit-reveal, TLS-PSK |
| `scheduling` | rare | `kuilt-warp`, `kuilt-heddle` |
| `background-synchronization` | rare | anti-entropy, reconnect-and-converge |

Replaces the current slate, which is nine-tenths generic: `sdk` 495, `wasm` 413, `kotlin-native` 411, `kotlin-coroutines` 394, `kotlin-flow` 182, `ktor` 181, `ktor-client` 150, `test` 120, `network` 104, `dependency-management` 46.

Dropping `wasm`/`kotlin-native` is safe — checked, not assumed. Their API returns `platforms` separately from `tags`:

```
"platforms":["androidJvm","common","jvm","native","wasm"],
"tags":["wasm","test","sdk",...]
```

## Part 2 — POM descriptions (build-logic PR)

`moduleDescription()` covers 15 of 43 published modules. The rest get
`kuilt — peer-symmetric, multiplatform networking. Module kuilt-otel.` — shown on Maven Central and the klibs Packages tab.

1. Write a line for every published module, from the CLAUDE.md module table. Fix `kuilt-crdt`'s, which still names `SeamReplicator` (now `Quilter`) and `RoutingSeam`.
2. Replace the `else ->` fallback with `error(...)`. `moduleDescription()` already runs at configuration time for exactly the modules applying `kuilt.publish`, so this is exact by construction — no allowlist, nothing lexical to evade. Same shape as `kuilt-bom`'s existing completeness check.

## Part 3 — README + topics (docs-only PR)

Doesn't move the klibs card (see 3 and 4 above). Worth doing because the first screen is wrong in two places:

- `SeamReplicator` → `Quilter` (line 30).
- Lead with Apple Network.framework, not Multipeer (line 15) — MC regressed on iOS 26 and is deprecated in Xcode 27; `kuilt-nw` replaces it.
- Add a short section on offline-first telemetry, currently missing.
- Keep the accessible-first rule — plain language up top, depth lower down.
- Topics: add `consensus`, `distributed-systems`, `offline-first`, `opentelemetry`, `webrtc`, `multiplayer`.

## Delivery

Two PRs off `origin/main`, no overlap, no stacking: build-logic (Part 2), docs-only (Part 3 + this spec). Part 1 is an external issue.

## Out of scope

`docs/architecture.md` has nine stale Multipeer mentions. Separate concern.
