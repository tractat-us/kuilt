# kuilt-primitives — heddle

Routes for fair-share scheduling, in one process or across peers.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|fair-share scheduler, "3× the share", a lease per slot, awaiting a slot; config trees, reloads, attached children, N at once, why it waits, orphans, restart; quota ledger across peers; minting quota at runtime; gating a `WarpNode` by lane; the bare pick|`LocalHeddle`, `FairSharePool.withSlot`; `Topology.from`, `apply`/`attach`, `maxConcurrent`, `explain`, `reconcileLeases`, `persist`/`restore`; `HeddleNode.reserve`/`complete`; `heddleGoverned`; `HeddleAdmissionControl`; `HeddlePolicy.pick`, `FairShareAllocator`|`heddle.md#which-engine-one-process-or-many-peers`|
