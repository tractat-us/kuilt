---
paths:
  - "kuilt-raft/**"
  - "kuilt-raft-test/**"
  - "kuilt-game/**"
  - "kuilt-cluster/**"
---
# Consensus

When every device has to agree on one order of events — whose turn it is, which
move came first — one of them is chosen to lead and the others copy its history.
These modules are that machinery, and the two things built on top of it: a game's
turn order, and a cluster of servers that clients propose into.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/consensus.md`. This file holds only the
rules for changing the code here.

## Rules

(none yet — the family's conventions move here from the root `CLAUDE.md` in a later change)
