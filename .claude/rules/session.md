---
paths:
  - "kuilt-session/**"
  - "kuilt-liveness/**"
  - "kuilt-session-test/**"
---
# Session

Keeping a group of people together in one shared room is harder than it sounds
once anyone's connection can drop — the room needs to know who is actually still
there, let someone back in after a hiccup without losing their seat, and notice
quickly when someone has really gone rather than just gone quiet for a moment.
These modules are that membership layer: who is in the room, whether they can
still be reached, and how a new arrival is let in.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/session.md`. This file holds only the
rules for changing the code here.

## Rules

This family has no conventions scoped to it alone today; the cross-cutting rules in the root `CLAUDE.md` apply.
