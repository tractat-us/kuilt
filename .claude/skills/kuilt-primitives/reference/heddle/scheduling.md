# kuilt-primitives — heddle: running the scheduler

Routes for fair-share scheduling, in one process or across peers: leases, shares, ledgers and limits.
Editing the group tree is in `tree-editing.md` beside this file.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|fair-share scheduler, "3× the share", a lease per slot, awaiting a slot; config trees, reloads, attached children, N at once, why it waits, orphans, restart; quota ledger across peers; minting quota at runtime; gating a `WarpNode` by lane; the bare pick|`LocalHeddle`, `FairSharePool.withSlot`; `Topology.from`, `apply`/`attach`, `maxConcurrent`, `explain`, `reconcileLeases`, `persist`/`restore`; `HeddleNode.reserve`/`complete`; `heddleGoverned`; `HeddleAdmissionControl`; `HeddlePolicy.pick`, `FairShareAllocator`|`heddle.md#which-engine-one-process-or-many-peers`|
|groups found while running that have children of their own — repository → author both discovered, re-applying the tree per new group|`Topology.ChildTemplate.template`, `GroupPath.Nested`, `LocalHeddle.attach(path, generation)`/`detach(path)`|`heddle.md#children-your-app-attaches-while-running`|
|a slot reserved at the longest job, holding a short job's slack until `complete`|`LocalHeddle.shrink(lease, amount)`|`heddle.md#handing-back-a-reservation-early`|
|each group's share of the pool on a table, a team's share summed from its lanes' entitlements|`LocalHeddle.shareLine(waiting)` / `LocalHeddleView.shareLine` → `ShareLine.share(group)`|`heddle.md#each-groups-share-on-the-page`|
|how far each group is from its share, owed or ahead, by subtracting `explain`'s front and service|`ShareLine.lag(group)`|`heddle.md#how-far-each-group-stands-from-its-share`|
|a "had" column beside share and lag, summing lease costs per group; what idle history a waker or newborn was let off|`ShareLine.had(group)`, `ShareLine.forgiven(group)`|`heddle.md#what-each-group-has-had-and-what-it-was-forgiven`|
|a negative group after a static reweight of a group with children, a hand-rolled patch moving spend off the retired edge|`HeddleNode.reconcileOwn(child)` → `ReconcileOwnOutcome` (`Repaired`, `CrossParent`, `TwoLiveEdges`), every peer for itself|`heddle.md#repairing-a-reweight-on-a-static-cluster`|
|vetting a weight before proposing it; re-deriving whether a weight set leaves `Long` or is too fine for one lease|`weightFitsTheBudget`; `EntitlementLedger.activationFitsTheBudget`|`heddle.md#refusals-and-limits`|
