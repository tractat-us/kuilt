---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, reconnect, or shared-state code in a repo that depends on kuilt. Fires when the task involves rejoin/reconnect/resume after a drop, a "grace window"/"hold the slot open", a table/lobby/room/hub/session container, a seat/peer roster, presence/heartbeat/"is this peer alive"/idle-reaper/"evict stale session"/lastSeen, host election/"who hosts"/tiebreak/propose-commit turns, retry/back-off, dedup/seenIds/"skip-if-exists", or shared state that must converge across peers (last-write-wins, grow-only set/counter, add/remove set). Routes to kuilt's existing primitive so you don't hand-roll one.
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

- a rejoin / reconnect / resume-token loop, or a "hold the seat open" grace window → `ResumeToken` + `SeamRoom`
- a reconnect banner / "why did we drop" classifier — transient vs. unrecoverable buckets → `MembershipEvent.Partitioned.reason` (`ReconnectReason`) + `HostLost.reason` (`FailureReason`)
- a heartbeat / idle-reaper / "is this peer still alive" timer → `HeartbeatPartitionDetector`
- a propose→authoritative/rejected turn or session facade, host election with a term → `GameSession` + `TurnSequencer`
- a last-write-wins register, grow-only set/counter, add/remove set, "merge two states" → the CRDT zoo
- replicating a CRDT over a connection by hand → `Quilter`
- a `seenIds` set → `GSet` / kuilt dedup
- a fixed/exponential retry back-off → `ExponentialBackoff`

Then follow the cookbook's exact primitive and snippet.
