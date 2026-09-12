---
paths:
  - "kuilt-crdt/**"
  - "kuilt-quilter/**"
  - "kuilt-gossip/**"
  - "kuilt-gossip-test/**"
  - "kuilt-bolt/**"
  - "kuilt-deal/**"
  - "kuilt-deal-test/**"
  - "kuilt-scale/**"
---
# Replication

Every device in a group might change the same shared piece of information at
once — a score, a list, a shared note — with nobody around to referee whose
change wins. These modules are what lets everyone's changes merge back into one
answer on their own, keep that answer moving between many devices without every
pair having to talk directly, fairly deal out a shuffled hand of cards that
nobody — not even the dealer — can peek at, and keep a written history beside the
live copy for later.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/replication.md`. This file holds only the
rules for changing the code here.

## Rules

(none yet — the family's conventions move here from the root `CLAUDE.md` in a later change)
