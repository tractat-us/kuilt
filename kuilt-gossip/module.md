# Module kuilt-gossip

Spreads shared data across a group by having each device keep in touch with only
a **handful** of others, instead of everyone talking to everyone. In a small group
that makes no difference; in a large one it's the difference between practical and
not — the work each device does grows with the size of its handful, not with the
size of the whole group.

Each device picks a few neighbours to exchange updates with, and keeps a short
standby list so it can replace a neighbour that drops out. A background safety net
(the anti-entropy reconcile in `kuilt-quilter`) guarantees everyone still ends up
agreeing even if a neighbour link is briefly missing — so the network of
neighbours only has to be *usually* connected, not perfectly so.

Under the hood this is a roster-derived **k-regular partial view**: each peer
draws a seeded random *k-out* sample of the room roster (`k ≈ ln N`, so the union
of everyone's choices is connected with high probability), heals it on membership
change with per-peer jitter, and promotes a spare when a neighbour fails. It wraps
the result as a `GossipSeam : Seam` exposing two views — the active neighbours
(deltas + GC) and full membership (anti-entropy sampling). See
`docs/gossip-mesh-design.md`.
