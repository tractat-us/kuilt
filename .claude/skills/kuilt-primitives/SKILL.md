---
name: kuilt-primitives
description: Use BEFORE writing any networking, session, reconnect, or shared-state code in a repo that depends on kuilt. Fires when the task involves rejoin/reconnect/resume after a drop, a "grace window"/"reconnect grace window"/"hold the seat open"/"hold the slot open", a table/lobby/room/hub/session container, a seat/peer roster, presence/heartbeat/"is this peer alive"/"is that peer still there"/"paused"/"reconnecting…"/idle-reaper/"evict stale session"/lastSeen, a countdown on a reconnecting peer ("how long until their seat is released", a timer on a greyed-out player), self-attributed reachability — "am I the one offline?"/"is it my connection or theirs?"/a "you're offline" or "your connection dropped" banner/telling your own outage apart from a peer's/"did my radio die or did they leave?", closing or expiring a room/table/lobby that never filled ("nobody ever joined", "reap an abandoned table"), host election/"who hosts"/tiebreak/propose-commit turns, retry/back-off, dedup/seenIds/"skip-if-exists", how big a message/frame/payload may be — chunking a large blob, picking a chunk size, "max payload size", a `FrameTooLargeException` that only appears once a peer drops, shared state that must converge across peers (last-write-wins, grow-only set/counter, add/remove set), peers in one room that cannot see each other — "B never gets A's updates"/"two joiners never converge"/"in the roster but every send fails"/messages that reach the host and stop/wanting the host to forward between guests, OR federated learning/analytics — "train on each device and only share the update", averaging model weights across devices without collecting their data, a `(Σweights, Σcount)` accumulator or a training-round barrier, OR fair-share/weighted scheduling — "weight some tasks heavier"/"give this group 3× the share"/"who runs the next quantum"/an EEVDF or weighted round-robin, an entitlement/quota/budget ledger, "reserve a slot before running"/"hold a slot"/"charge once on complete", minting quota or re-parenting a group at runtime, weighted lanes over a warp workload, or location placement ("only run on GPU/in-region peers", "can this peer run this", an affinity/capability predicate), OR code mobility — shipping code rather than a job name to another peer, a plugin/kernel loader, "run untrusted code", a sandbox or timeout-and-kill wrapper around someone else's code, a blob cache keyed by a content hash, "who has these bytes"/a manifest of what each peer holds. Routes to kuilt's existing primitive so you don't hand-roll one.
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

- a heartbeat / idle-reaper / "is this peer still alive" timer → `HeartbeatPartitionDetector`
- a `delay(timeout); if (peers.size < 2) close()` reaper for a room/table nobody joined → `SoloDeadlineDetector`
- a "paused / reconnecting…" flag or a `lastSeen` map for greying out a player → `Room.roster` + `Member.liveness` (the level; `Room.events` is only the notification)
- a countdown on a reconnecting peer — "how long until their seat is released?" → `Liveness.Partitioned(since, windowExpiresAt)` read off `Room.roster`, never an event replay
- a "you're offline" / "your connection dropped" banner, telling *your* outage apart from *theirs*, "am I the one offline?" → `Room.localFabric` + `MembershipEvent.LocalFabricLost`/`LocalFabricRestored`, plus the `localFabric` tag on `Partitioned`/`HostLost`
- a propose→authoritative/rejected turn or session facade, host election with a term → `GameSession` + `TurnSequencer`
- a last-write-wins register, grow-only set/counter, add/remove set, "merge two states" → the CRDT zoo
- replicating a CRDT over a connection by hand → `Quilter`
- a forwarding hop through the host so two guests can reach each other — "peer B never sees peer A's updates", "the Quilter between two joiners never converges", "this peer is in the roster but every send to it fails", messages that reach the host and stop → `Room.channel(id)`; the room relays already (run replicators over the room's channel view, never over the raw fabric `Seam`)
- averaging model updates from many devices without collecting their data — federated learning/analytics, a `(Σweights, Σcount)` accumulator, a training-round barrier → `FedAvg` + `TrainingUpdate`
- hashing a replicated state by hand so two peers can compare it as one number — "are we in sync?" across a process/socket boundary, a divergence alarm → `canonicalDigest` (and in-process, just `assertEquals` the states)
- splitting a big blob into frames, hard-coding a chunk size, or chasing a `FrameTooLargeException` that shows up only once a peer drops out → `Room.maxPayloadBytes` / `Seam.maxPayloadBytes` (`null` means unknown, not unbounded)
- a `seenIds` set → `GSet` / kuilt dedup
- a fixed/exponential retry back-off → `ExponentialBackoff`
- merging mDNS/Multipeer discovery feeds into one lobby roster → `discoveryRoster`
- a weighted / fair-share scheduler, "give this group 3× the share", "who runs the next quantum", an EEVDF/weighted round-robin → `HeddlePolicy.pick` + `HeddleNode`
- an entitlement/quota/budget ledger, "reserve a slot before running then charge once", a coordination-free budget that converges across peers → `EntitlementLedger` + `HeddleNode.reserve`/`complete`
- minting quota or re-parenting a group at runtime with everyone agreeing on the order (no double-mint on a split) → `heddleGoverned` (`GovernedHeddleNode`)
- gating a `WarpNode`'s tasks by a weighted lane ("interactive gets 3× batch") → `HeddleAdmissionControl` + `TaskDescriptor.inLane`
- "only run on GPU/in-region peers", a placement predicate over peer capabilities, "can this peer run this" → `Affinity` + `TaskDescriptor.where` + `CapSet`
- a blob cache keyed by a content hash, a "have you got these bytes?" request/response, a manifest of what each peer holds → `Creel` + `BobbinExchange`
- running code that arrived from another peer — a plugin loader, an `eval`, a bespoke sandbox or timeout-and-kill wrapper → `WasmRuntime` + `WasmSandboxConfig` + `WarpLazyFetch`

Then follow the cookbook's exact primitive and snippet.
