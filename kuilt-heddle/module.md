# Module kuilt-heddle

Shares a pool of work fairly across a group of peers — even when some of them
can't talk to each other for a while.

Imagine several teams drawing from one shared budget of computing time. You want
each team to get the slice it was promised (say, three parts to one), you want a
team that isn't using its share right now to lend it to a busier team, and you
want all of this to keep working while the network is flaky — no central referee
handing out permission slips. That is what heddle does. It keeps a running,
tamper-evident tally of who was granted what, who passed their grant down to a
sub-team, and who actually spent it, and every peer can merge its own copy of
that tally with anyone else's and always agree on the result.

The name is a weaver's word: a *heddle* is the part of a loom that lifts one set
of threads so another can cross. Here it lifts one group's claim so another's can
pass through — the same fair-share idea, mechanical instead of computational.

## The ledger and the policy

The tally itself — the ledger, its merge rule and lifecycle, the scheduling policy, and the
`FairShareAllocator` loop that drives it — lives in **`:kuilt-heddle-core`**, which this module
re-exports, so a consumer of `:kuilt-heddle` needs no second dependency. Read that module's
documentation for what the ledger keeps, how it merges, and how the next child is picked. This
module adds what it takes to run all of that between peers.

## Types at a glance

- `FairShareExecution` — the spend surface both nodes share (`reserve`/`complete`/`cancel`/
  `earmarked`), so code that only spends, such as `:kuilt-warp-heddle`'s admission gate, takes
  either node.
- `ReserveOutcome` / `ScheduleOutcome` / `GateClosed` — what a spend call did. `reserve` answers
  `Reserved` or `NoHoldings`; a governed node's `schedule` answers `Delegated` or
  `NothingToDelegate` (a static node's returns a plain grant count). Only a governed node adds
  `GateClosed`, with a `Reason`, when this peer may not author entitlement at all.
- `HeddleNode` / `heddleStatic` — the node that runs the tally, demand board, reservations,
  and liveness over a `Seam`, and its fixed-roster bootstrap (see below).
- `GovernedHeddleNode` / `heddleGoverned` — the same node with runtime supply creation and
  reshapes serialized through `kuilt-raft`; its control calls return a `ControlOutcome`
  (`Applied` / `Conflict` / `Indeterminate`) so an overlapping reshape's loser surfaces, never
  silently drops.
- `HeddleConfig` — the node's policy caps, the §8.2 bound cap, the demand-staleness window,
  and the injected replication/liveness/RNG knobs.
- `DemandBoard` — one peer's advertised appetite across the edges it serves; the value
  carried in that peer's slot of the presence-style demand board.
- `ReservationId` — the handle a leaf earmark is completed against.
- `BoundMetrics` — the three consistent pieces of the temporary fairness-error bound at a
  parent (§8.2): the configured worst case, the current state-dependent bound, and the
  fairness gap actually observed.

## Running it over a network

`HeddleNode` is the piece that puts the tally, the policy, and the schedule onto a live
connection between peers. Start one with `heddleStatic` — you hand it the connection, this
peer's identity, the root group, the starting supply (who begins with how much), and the
starting shape of the tree; every peer that starts with the same inputs begins from an
identical tally, and from then on their copies stay in step over the wire on their own.

What a node does for you:

- **Keeps every peer's tally in step.** Grants, returns, and charges are exchanged as they
  happen and reconciled in the background, so a peer that was briefly cut off catches up on
  reconnect — no referee, no lost history.
- **Collects "who wants work right now."** Each peer advertises appetite per child with
  `advertise`; a peer that stops refreshing (say, it crashed) has its appetite quietly
  expire, so stale wants can't keep pulling work its way. Appetite is only advice — it can
  never authorize spending.
- **Hands out and charges for work.** `schedule(parent)` runs allocation rounds, delegating
  this peer's share down toward the children that want it. Leaf work `reserve`s a slice,
  runs, then `complete`s it — and completing the same reservation twice charges exactly once.
- **Notices when a peer goes quiet.** It distinguishes a temporary split from a real crash
  and reports it (`partitionEvents`). It deliberately does **not** claw back a crashed peer's
  share: a wrong reclaim would let two peers spend the same units, so a crashed peer's share
  simply sits unused until an operator recovers it.
- **Publishes how far off fair it could be.** `boundMetrics(parent)` reports the worst-case,
  current, and actually-observed fairness gap, so a consumer never claims tighter fairness
  than it can prove while peers are still reconciling.

## Creating supply and reshaping at runtime

`heddleStatic` is the fixed-roster front door: the supply is decided once, at start-up, and
every peer is handed the same starting tree. Its sibling **`heddleGoverned`** is for when supply
and shape have to change *while the system is running* — a new tenant arrives, a lane is added,
entitlement is topped up. Those few decisions are the only ones that genuinely need everyone to
agree on an order, so `heddleGoverned` runs them through a small agreement step (`kuilt-raft`)
and nothing else does:

- **Creating supply can't be double-counted.** Minting new entitlement (`mint`) goes through the
  agreement step, so if the network splits, the smaller half simply can't create supply — two
  halves of a split can never both mint against the same pool.
- **Two conflicting reshapes don't corrupt the tree.** If two peers try to re-attach the same
  child to different parents at once, the agreement step picks an order: the first wins, and the
  second comes back as a **structured conflict** (`ControlConflict.DualInbound`) for the operator
  to resolve — never a silent overwrite, never a coin-flip on a timestamp. Reshapes that don't
  touch the same child don't wait on each other at all.
- **The everyday path is untouched.** Handing out and charging for work — `schedule`, `reserve`,
  `complete` — never goes through the agreement step, at any rate. That is the whole point:
  agreement appears only at the two rare moments that need it.

- **Who is taking part is written down, not guessed.** A peer says `enroll` when it joins and
  `depart` when it leaves cleanly, and both go through the agreement step — so every peer agrees on
  exactly who the participants are, and *when* each of them joined or left. That matters for any
  operation that has to wait for *everyone* to answer: without an agreed list, "everyone" has no
  meaning. Only a peer itself may say it is leaving; a peer that has crashed stays on the list (and
  keeps its share) until it comes back — see `revocation` below.
- **A peer that has not said `enroll` cannot spend, and it says so.** Until its own `enroll`
  lands, `reserve` and `schedule` answer `GateClosed` — never the same "nothing here" answer an
  idle peer gives — so forgetting to enroll shows up as a refusal with a reason rather than as a
  scheduler that quietly does nothing.
- **A peer that cannot read the agreed record stops, and it says so.** The agreed record is the
  truth about who holds what. A peer that meets an entry it cannot read, say one written by a newer
  version, no longer knows that truth, so it refuses to spend rather than guess. `reserve` and
  `schedule` answer `GateClosed` naming the entry, and this one does not clear by itself: it takes a
  version that can read the entry, or starting the group on a fresh record. The stop reaches the
  whole group, because everyone reads the same record: the same entry on the same version stops
  every peer, and one peer on a newer version stops the older ones until they upgrade. That cost is
  accepted, because there is no way to skip an entry. So upgrade a group together.
- **The agreed record has to be heddle's alone.** An entry heddle did not write stops the group the
  same way, because a peer cannot tell it from an act it has lost the means to read. So give the
  agreement step a Raft node of its own: one proposal from anything else on that node stops everyone.
  Leave that node on its default `ClientIdentity.Auto`: heddle replays its record from the start on
  every boot and restarts its request serials, which a `ClientIdentity.Durable` cannot describe.
- **A saved record from v0.7.3 does not carry over.** Its `Mint` and `Prepare` entries no longer
  read, so a peer stops at the first one. Start a fresh record. There is no migrator: this is the
  accepted price of a reader that never guesses, not a gap waiting to be filled.
- **A call that gave up may still land.** Each control call waits for its act to be agreed, and a
  `timeout` or a cancellation stops only the wait. The act can still be agreed afterwards, and it
  takes effect when it is — an `enroll` or `depart` included, which opens or closes the spend gate
  then. So read before you resubmit (a second `mint` is a second mint), and never re-issue `enroll`
  or `depart` just to repair the gate.
- **Budget stranded by a race can be brought home.** Retiring an edge checks locally that it has
  drained, and a peer's grant still in flight can slip past that check, leaving budget stranded on
  the retired edge. `reconcile(child)` moves it, with the service already charged through it and any
  hand-offs made on it, onto the child's live edge. First every enrolled peer promises never to write
  the old edge again (`quiesce`), and the amount is worked out from those recorded promises, so a
  peer with a stale view cannot move a wrong one. Expect to call it twice; `pendingAcks(edge)` shows
  who it is still waiting for, and a peer that is down holds it up, on purpose.

`heddleGoverned` returns a `GovernedHeddleNode`: the same data-plane calls as `HeddleNode` plus
`mint`/`prepare`/`activate`/`close`/`retire`, `quiesce`/`reconcile` and `enroll`/`depart`, each of
which returns a
`ControlOutcome` — `Applied` when it took effect, `Conflict` carrying the structured reason when it
was refused, or `Indeterminate` when the peer stopped at an unreadable entry with the call still in
flight, so the act may yet land and must not be retried blindly (`enrolledReplicas()` reads back the
agreed list). Reclaiming a
crashed peer's stranded share is a fenced control-plane act whose seam is **defined but not yet
shipped** (`revocation`): a wrong reclaim would let two peers spend the same units, so v1 leaves the
share safely stranded.

See `docs/heddle-design.md` and `docs/heddle-ledger-design.md` for the full model.
