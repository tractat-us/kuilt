# Module kuilt-raft

Keeps a handful of devices agreeing on one shared list of decisions, in one order, even
while some of them are asleep, slow, or gone for good. The group picks one peer to put
decisions in order and the rest follow it, and a decision only counts once more than
half the group has written it down — so the group can lose a peer and carry on without
losing anything it had already agreed.

Raft consensus over a `Seam`: leader election + PreVote, log replication, log
compaction with chunked `InstallSnapshot`, dynamic membership, linearizable reads
(`readIndex()`), and graceful leadership transfer (`transferLeadership()`).

## Proposing from any peer

`RaftNode.propose` may be called on **any** role. The leader appends directly; a
follower, candidate, or learner forwards the command to the current leader and
suspends until it commits (Raft §8). If no leader is known yet the call waits,
cancellably, until one is elected.

## Exactly-once forwarded proposals (§8)

A forwarded `propose` retried after a lost ack or a leader change must not be
appended or applied twice — without forbidding the retry. kuilt stamps every
application proposal with a stable `DedupKey(clientId, requestId)` and gives you
three rungs of guarantee:

1. **Auto (default).** Pass no `clientId`; the node mints
   `ClientId.auto(thisNodeId, raftConfig.random)` — `"$nodeId-$randomHex"`. The
   `NodeId` prefix keeps two nodes distinct even under the same seeded test RNG
   (a bare random GUID would alias) and is readable in logs. Auto serials are
   monotonic; this gives at-least-once forwarding with best-effort dedup, but no
   guarantee across a process restart (the suffix changes).
2. **Durable.** Pass a **stable** `ClientId` the caller persists itself and replay
   the *same* `requestId` (the `propose(command, requestId)` overload) on a
   post-crash retry. The key survives the crash, so the consumer skips the entry
   it already applied — exactly-once end-to-end.
3. **Ignore.** Treat the key as absent (internal no-op/config entries carry a
   `null` `dedupKey`); the entry always applies.

**Who enforces what.** The proposer stamps the key once; it rides the forward hop
**unchanged** (the leader never re-stamps). A best-effort, non-durable leader-side
cache coalesces the common lost-ack retry on a still-leading node. The
**authoritative** table is the consumer's: fold `ClientSessionTable.shouldApply`
into your apply loop and serialize it **into your own snapshot**
(`toBytes`/`fromBytes`). Because that table rides the consumer's snapshot, raft's
`InstallSnapshot` carries it with **zero raft-side change**.

**Collision detection.** A committed entry under this node's own `clientId`
bearing a serial it never issued proves another live writer shares the identity. A
**durable** id fails loud with `ClientIdCollisionException` (two processes were
handed one id — an operational error; do not retry under it). An **auto** id
silently re-mints a fresh suffix and logs a warning.

**Bounding (v2 — supersession prune).** The table is self-bounding without any
clock, horizon, or heuristic. A `NodeId` is cluster-unique, so two incarnations
of one node are never live at once — the arrival of a new `auto:$nodeId-…` entry
proves every prior sibling is dead. `shouldApply` therefore evicts every same-family
auto sibling in the same apply step, so the table holds at most one entry per live
auto family plus the durable ids. Durable/stable ids are never pruned and keep
their cross-crash exactly-once. Long-lived clients should reuse a stable `ClientId`
so their entry updates in place.

**Explicit close.** `closeSession(clientId)` drops a client's high-water-mark
from the table. Drive it from the apply loop when a committed close op signals that
a logical client is finished. A subsequent request from that client re-opens at
mark 0 — the same at-least-once floor, never a silent drop.

## Trust between peers

Every peer you let vote in a cluster is a peer you are trusting. This module is built
to survive peers that **stop** — a phone that walks into a tunnel, a laptop whose lid
closes, a server that crashes and comes back — and it survives them well: the group
keeps agreeing as long as more than half of its voting members are reachable. It is
**not** built to survive a peer that keeps talking but lies. Someone who takes over a
voting peer can corrupt what the group agrees on.

So the security decision is *admission*, and admission happens before this layer.
Whoever you let into the cluster, you are trusting with the shared state; deciding who
may connect at all belongs to the fabric and to you (a fabric can carry a verified
identity for a peer and a host can refuse the connection — see
[`docs/superpowers/specs/2026-07-07-hub-accept-attestation.md`](../docs/superpowers/specs/2026-07-07-hub-accept-attestation.md)).
In the usual vocabulary: kuilt-raft assumes **crash faults** among admitted voters, not
Byzantine ones, as Ongaro's dissertation does throughout.

That is the whole assumption — but it is not a licence to ignore a hostile frame,
because plenty of hostile frames *are* catchable. The line this module draws:

> **Defend where the recipient can check the claim against state it already holds.
> Record as accepted where no local check exists.**

### Where it defends

- **Leader authority (§5.2 / §8).** `AppendEntries`, `InstallSnapshot` and `TimeoutNow`
  are leader→peer RPCs and only a voter can be leader, so a frame of any of those types
  from a sender outside the voter set the recipient has currently adopted is a forgery
  and is dropped (`RaftEngine.onMessage`, #1383, #1889). The witness is local:
  `membershipState.voters` is right there.
- **Well-formedness of wire fields.** A term or a snapshot position outside
  `0..2^60`, or a batch whose entry terms exceed the sender's own stated term, is proof
  of a malformed or foreign frame — checkable against the frame plus the recipient's own
  term, with no trust required (#1833, #1868, #1872, #1886).
- **A quantity is clamped**, because one conservative in-range reading exists, and
  because a throw inside the engine's actor loop would make a malformed frame permanent
  node death. A follower's snapshot `nextOffset` and a §5.3 `conflictIndex` are
  quantities (#1818, #1829).
- **A nonce is discarded, not clamped.** A read-index round echo has no conservative
  reading, so clamping it into the current round would launder a foreign or forged value
  into the *most favourable valid one* — the mistake #1817 records. An out-of-range echo
  is dropped outright, and a stale-term rejection attests to nothing rather than
  echoing a round it cannot vouch for (#1817, #1831).
- A **missing** local check is a bug in this class, not an accepted exposure, and four are
  open: the uncapped snapshot reassembly buffer (#1881); five redundant sender-identity
  fields (`leaderId` / `candidateId`) carried on the wire and then read as authority when
  `from` already says who sent the frame, so the fix is to delete them (#1912);
  `ForwardResponse`, which has no provenance check at all, so a forged receipt makes
  `propose()` report a commit that never happened and the write is silently lost (#1911);
  and `_leader` accepting a same-term reassignment, which lets a voter name itself leader
  (#1906).

### What it accepts, unauthenticated

The lane decides who can lie. On the **snapshot** lane (1–3) the first two are told *to*
a follower, so the liar must be an admitted voter — the §5.2 gate drops leader→peer
frames from anyone else — except against a joiner that has not yet learned any voters,
where the gate is deliberately skipped so the join cannot deadlock, and there exposure 2
is open to any admitted peer; the third runs the other way, told *to* the leader by
whichever peer it is currently catching up (voter or learner), since a peer→leader ack is
outside the gate. The **vote** lane (4–5) and the **forwarding** lane (6) are peer→peer
and sit outside the gate entirely, so any admitted peer can lie on them. The
`AppendEntries` lane has no equivalent *field-range* residual — an entry's index is
pinned to `prevLogIndex + 1 + i` and Log Matching pins `prevLogIndex` against the local
log.

1. **Snapshot position** (#1876). A recipient cannot distinguish a forged
   `lastIncludedTerm`/`lastIncludedIndex` from the genuine position of a far-ahead
   leader — a snapshot exists precisely to jump a follower past its own log (§7), so
   "far ahead" is not evidence of anything. The plausibility ceiling (#1872) rules out
   the implausible range and nothing more. In range, a malicious voter can advance a
   follower's `snapshotIndex`, `commitIndex` and compaction floor to a position it never
   reached, wipe its log, and thereby dominate honest candidates at the same term
   (§5.4.1). Uncheckable by construction.
2. **Snapshot config** (#1880). `InstallSnapshot.config` is adopted verbatim by
   `finalizeInstalledSnapshot`, because the config entries that produced it were
   compacted away on the leader and the snapshot is the only carrier (§4, §7). A forged
   config re-parents the victim into a cluster where it is the entire voter set; it then
   elects itself while holding no committed entry, and Leader Completeness (§5.4 /
   Figure 3.2) is gone. **Well-formedness here is locally checkable** — a recipient can
   compute its own committed config — **but authorization is not, and authorization is
   the only property that separates a forged config from an honest one.** No predicate
   over local state and the frame can do it: legitimate use *requires* accepting an
   arbitrarily distant config, since a node absent across unboundedly many membership
   changes must still be catchable-up; and the attack config `{victim}` is the endpoint
   of a legal sequence of §4 single-server removals, i.e. is itself an arbitrarily
   distant legitimate config. The predicate would have to accept and reject the same
   value.
3. **Snapshot-transfer acks.** A follower that stored nothing but acks
   `nextOffset == state.size` is credited a full `matchIndex`
   (`SnapshotSender.onAck`). The value is in range, so it is indistinguishable from an
   honest completion, and unprovable without an end-to-end digest of the transferred
   bytes. When the liar is a voter this is a safety matter and not just bookkeeping: a
   credited `matchIndex` counts toward the commit quorum (`tryAdvanceLeaderCommit`), so
   the leader can commit entries no majority actually holds.
4. **A candidate's claimed log position** — `RequestVote`/`PreVote`
   `lastLogTerm`/`lastLogIndex`, compared by `isLogUpToDate` and never stored.
   Unauthenticated *and* unbounded, unlike the sibling lanes (#1832, #1868/#1872), and
   deliberately so: this is exposure 1's uncheckability again rather than a second
   argument. `(term - 1, MAX_PLAUSIBLE_INDEX - 1)` is a value any plausibility ceiling
   must accept and it already dominates every honest log, so a bound would be decoration.
   A forged position wins votes from honest voters — §5.4.1 election safety held by
   consent rather than by construction.
5. **`RequestVote.leadershipTransfer`** — an unvalidated wire flag that bypasses §4.2.3's
   leader-stickiness deny, so any peer can make a healthy leader's voters process a vote
   request they should have denied. Only the old leader holds a witness that a transfer
   was authorized; the other voters have none.
6. **`Forward.dedupKey`** — attacker-chosen, and the leader appends it unchanged (it never
   re-stamps), so a forged key under another client's identity can advance that client's
   high-water mark, after which the client's own next request is skipped as a duplicate,
   or trips `ClientIdCollisionException` on a durable id. Partly checkable, and so partly
   a *missing* check rather than an accepted one: an **auto** id embeds the proposer's
   `NodeId` (`auto:$nodeId-…`), which can be compared to `from`; a **durable** id is
   caller-minted with no node binding, and only that half is genuinely accepted.

### One gate, two failure directions

The leader-authority gate is the "defend" exemplar above, and its predicate has failed
in both directions — worth stating because these are the cost of the rule, not
exceptions to it. The local witness is local, and it is also possibly out of date.

- **Too narrow, since fixed.** `TimeoutNow` is a leader→peer RPC too, and sat outside
  the gate's type test, so any peer — learner or spoke included — could send one at all.
  That was one of two halves, and the sharper half was elsewhere: `onTimeoutNow`'s own
  "sender must be the leader" check was scoped to `m.term == state.currentTerm`, because
  `_leader` is meaningless at a higher term, so a frame one term ahead bypassed it
  entirely and forced an immediate, pre-vote-less election. Both halves closed in #1889 —
  `TimeoutNow` joined the type test, and a `TimeoutNow` ahead of our term is now refused
  rather than adopted.
- **Too strict.** The gate keys on the *recipient's* currently-adopted voter set, which
  may be stale. A node absent across a full rotation of that set may be unable to accept
  the current leader's frames at all — and those frames are the only thing that could
  teach it the new set. Reported from a code reading and not yet reproduced; the
  reachability step is the part still to verify (#1898).

### Out of scope

Authenticating the snapshot channel — signed configs, an authenticated transport, a
quorum witness travelling with a config, an end-to-end digest for the ack half — is not
a Raft-layer fix and is not attempted here. Nothing in these frames proves that a config
was agreed by a quorum, and no amount of frame validation can supply that proof. A check
that constrained the *consequence* instead of the value (what a node may do having
learned its entire electorate from a snapshot) would be a change to §4 membership
handling rather than a frame check, and is likewise not attempted. Both directions are
tracked in #1907, so the exposures above have an open home even once #1876 and #1880
close against this text.
