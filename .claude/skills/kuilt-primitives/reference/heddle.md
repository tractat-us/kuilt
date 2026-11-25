# kuilt-primitives — heddle

Routes for fair-share scheduling, in one process or across peers.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|fair-share scheduler, "3× the share", a lease per slot, awaiting a slot; config trees, reloads, attached children, N at once, why it waits, orphans, restart; quota ledger across peers; minting quota at runtime; gating a `WarpNode` by lane; the bare pick|`LocalHeddle`, `FairSharePool.withSlot`; `Topology.from`, `apply`/`attach`, `maxConcurrent`, `explain`, `reconcileLeases`, `persist`/`restore`; `HeddleNode.reserve`/`complete`; `heddleGoverned`; `HeddleAdmissionControl`; `HeddlePolicy.pick`, `FairShareAllocator`|`heddle.md#which-engine-one-process-or-many-peers`|
|a whole admin editor for the running tree, end to end — load, edit, validate, warn, preview, save, reload on a clash|`LocalHeddle.topology` → `toDocument`, `TopologyEdit`, `Topology.from`, `changesTo`, `previewApply`/`apply(new, generation, startedFrom)` → `ApplyOutcome.TreeChanged`|`heddle.md#a-minimal-editor-end-to-end`|
|set a weight, add, remove, rename or move a node in a config tree by hand, rebuilding every group on the way|`TopologyDocument.setWeight`/`addChild`/`remove`/`rename`/`move` → `TopologyEdit`|`heddle.md#editing-the-tree-as-a-document`|
|an admin page editing the running tree; a "what this edit changes" dialog, a hand-rolled tree diff; two admins editing at once, a save that must not undo another's, a hand-rolled "has it changed" check|`LocalHeddle.topology`, `Topology.toDocument`; `Topology.changesTo` → `TopologyChange`; `LocalHeddle.apply(new, generation, startedFrom)` → `ApplyOutcome.TreeChanged`|`heddle.md#showing-an-admin-what-an-edit-changes`|
|warning before a save that costs a group its history; sorting `TopologyChange`s by hand into "loses history", "keeps it" and "refuse"|`TopologyChange.history` → `TopologyChange.History` (`LOST`, `KEPT`, `NEW_TREE`)|`heddle.md#showing-an-admin-what-an-edit-changes`|
|previewing a config edit before applying it, "what would this reload do", shares under a candidate tree|`LocalHeddle.previewApply`, `LocalHeddleSnapshot.previewApply` → `TopologyPreview`; `LocalHeddle.fromTopology` + `entitlement`|`heddle.md#previewing-a-change-to-the-tree`|
