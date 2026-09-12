---
paths:
  - "kuilt-core/**"
  - "kuilt-stream/**"
  - "kuilt-tcp/**"
  - "kuilt-websocket/**"
  - "kuilt-nw/**"
  - "kuilt-multipeer/**"
  - "kuilt-nearby/**"
  - "kuilt-webrtc/**"
  - "kuilt-mdns/**"
  - "kuilt-conformance/**"
  - "kuilt-test/**"
  - "kuilt-store/**"
---
# Fabrics

Two devices that want to talk to each other need something to carry bytes between
them — over the internet, a local network, or a direct cable — and a way to find
each other before they can start. These modules are the several ways kuilt moves
those bytes, the shared contract every one of them honors, the tests that keep a
new one honest, and a place to keep bytes safely on disk between runs.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/fabrics.md`. This file holds only the
rules for changing the code here.

## Rules

(none yet — the family's conventions move here from the root `CLAUDE.md` in a later change)
