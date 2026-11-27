# kuilt-primitives — heddle: editing the tree

Routes for an admin editing, previewing and saving the group tree of a running `LocalHeddle`.
Running the scheduler itself is in `scheduling.md` beside this file.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a whole admin editor for the running tree, end to end — load, edit, validate, warn, preview, save, reload on a clash|`LocalHeddle.topology` → `toDocument` (an app using `appSettings` loads its stored document, `startedFrom = Topology.from(stored)`), `TopologyEdit`, `Topology.from`, `changesTo`, `previewApply`/`apply(new, generation, startedFrom)` → `ApplyOutcome.TreeChanged`|`heddle.md#a-minimal-editor-end-to-end`|
|set a weight, add, remove, rename, move or reorder a node in a config tree by hand, rebuilding every group on the way|`TopologyDocument.setWeight`/`addChild`/`remove`/`rename`/`move` (with an `index` to reorder) → `TopologyEdit`|`heddle.md#editing-the-tree-as-a-document`|
|turning a group's attached children on or off, or changing their weight, ceiling or nesting, by rebuilding the document by hand or removing and re-adding the group|`TopologyDocument.setTemplate(path, template)` (`null` removes it) → `TopologyEdit`|`heddle.md#editing-the-tree-as-a-document`|
|your own per-node keys (match rules, split keys) in a side map keyed by child-index paths that shift after a remove or move; diffing them by hand|`TopologyDocument.Node`/`ChildTemplate.appSettings`, `setAppSettings`; `TopologyDocument.appSettingsChangesTo` → `AppSettingsChange`|`heddle.md#an-apps-own-settings-on-each-node`|
|an admin page editing the running tree; a "what this edit changes" dialog, a hand-rolled tree diff; two admins editing at once, a save that must not undo another's, a hand-rolled "has it changed" check|`LocalHeddle.topology`, `Topology.toDocument`; `Topology.changesTo` → `TopologyChange`; `LocalHeddle.apply(new, generation, startedFrom)` → `ApplyOutcome.TreeChanged`|`heddle.md#showing-an-admin-what-an-edit-changes`|
|warning before a save that costs a group its history; sorting `TopologyChange`s by hand into "loses history", "keeps it" and "refuse"|`TopologyChange.saveEffect` → `TopologyChange.SaveEffect` (`LOST`, `KEPT`, `NEW_TREE`)|`heddle.md#showing-an-admin-what-an-edit-changes`|
|previewing a config edit before applying it, "what would this reload do", shares under a candidate tree|`LocalHeddle.previewApply`, `LocalHeddleSnapshot.previewApply` → `TopologyPreview`; `LocalHeddle.fromTopology` + `entitlement`|`heddle.md#previewing-a-change-to-the-tree`|
