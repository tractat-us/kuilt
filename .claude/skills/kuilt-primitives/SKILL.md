---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, reconnect, or shared-state code in a repo that depends on kuilt. Fires when the task involves rejoin/reconnect/resume after a drop, a "grace window"/"reconnect grace window"/"hold the seat open"/"hold the slot open", a table/lobby/room/hub/session container, a seat/peer roster, presence/heartbeat/"is this peer alive"/"is that peer still there"/"paused"/"reconnecting…"/idle-reaper/"evict stale session"/lastSeen, closing or expiring a room/table/lobby that never filled ("nobody ever joined", "reap an abandoned table"), host election/"who hosts"/tiebreak/propose-commit turns, retry/back-off, dedup/seenIds/"skip-if-exists", shared state that must converge across peers (last-write-wins, grow-only set/counter, add/remove set), OR fair-share/weighted scheduling — "weight some tasks heavier"/"give this group 3× the share"/"who runs the next quantum"/an EEVDF or weighted round-robin, an entitlement/quota/budget ledger, "reserve a slot before running"/"hold a slot"/"charge once on complete", minting quota or re-parenting a group at runtime, weighted lanes over a warp workload, or location placement ("only run on GPU/in-region peers", "can this peer run this", an affinity/capability predicate). Routes to kuilt's existing primitive so you don't hand-roll one.
---

# kuilt primitives — check before you build

kuilt almost certainly already provides the networking / session / shared-state
thing you're about to write. **Before hand-rolling, read the cookbook** and use the
primitive it names.

## Where the cookbook is

Read the first path that exists, in order:

1. `docs/agent-cookbook.md` — if you are working inside the kuilt repo.
2. `../kuilt/docs/agent-cookbook.md` — if kuilt is checked out side-by-side
   (the `includeBuild("../kuilt")` layout).
3. Otherwise the source-of-truth blob:
   `https://github.com/tractat-us/kuilt/blob/main/docs/agent-cookbook.md`.

## The reflex

If you're about to write any of these, STOP and open the cookbook:

description: Use BEFORE writing any networking, session, reconnect, or shared-state code in a repo that depends on kuilt. Fires when the task involves rejoin/reconnect/resume after a drop, a "grace window"/"reconnect grace window"/"hold the seat open"/"hold the slot open", a table/lobby/room/hub/session container, a seat/peer roster, presence/heartbeat/"is this peer alive"/"is that peer still there"/"paused"/"reconnecting…"/a reconnect banner/a connection-state or failure-reason enum/idle-reaper/"evict stale session"/lastSeen, closing or expiring a room/table/lobby that never filled ("nobody ever joined", "reap an abandoned table"), host election/"who hosts"/tiebreak/propose-commit turns, retry/back-off, dedup/seenIds/"skip-if-exists", or shared state that must converge across peers (last-write-wins, grow-only set/counter, add/remove set). Routes to kuilt's existing primitive so you don't hand-roll one.
- a heartbeat / idle-reaper / "is this peer still alive" timer → `HeartbeatPartitionDetector`
- a `delay(timeout); if (peers.size < 2) close()` reaper for a room/table nobody joined → `SoloDeadlineDetector`
- a propose→authoritative/rejected turn or session facade, host election with a term → `GameSession` + `TurnSequencer`
- a last-write-wins register, grow-only set/counter, add/remove set, "merge two states" → the CRDT zoo
- replicating a CRDT over a connection by hand → `Quilter`
- a `seenIds` set → `GSet` / kuilt dedup
- a fixed/exponential retry back-off → `ExponentialBackoff`
- merging mDNS/Multipeer discovery feeds into one lobby roster → `discoveryRoster`
- a weighted / fair-share scheduler, "give this group 3× the share", "who runs the next quantum", an EEVDF/weighted round-robin → `HeddlePolicy.pick` + `HeddleNode`
- an entitlement/quota/budget ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers → `EntitlementLedger` + `HeddleNode.reserve`/`complete`
- minting quota or re-parenting a group at runtime with everyone agreeing on the order (no double-mint on a split) → `heddleGoverned` (`GovernedHeddleNode`)
- gating a `WarpNode`'s tasks by a weighted lane ("interactive gets 3× batch") → `HeddleAdmissionControl` + `TaskDescriptor.inLane`
- "only run on GPU/in-region peers", a placement predicate over peer capabilities, "can this peer run this" → `Affinity` + `TaskDescriptor.where` + `CapSet`

Then follow the cookbook's exact primitive and snippet.
