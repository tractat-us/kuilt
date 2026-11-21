# kuilt-primitives — consensus

Routes for agreement: propose/commit turns, a server holding the log, consensus state that survives a restart.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a propose→commit turn/session facade, host election with a term|`GameSession` + `TurnSequencer`|`consensus.md#consensus--turns`|
|a server holding the log clients propose into, a client outliving its server|`ClusterClient` (`clusterClient`/`clusterClientWithNode`) + `ServerCluster` (`serverCluster`)|`consensus.md#when-one-of-the-peers-is-a-server`|
|a Raft term/vote/log surviving a restart, an `expect`/`actual` `RaftStorage`|`DurableStoreRaftStorage.open(store)` + `RaftStorageConformanceSuite`|`consensus.md#durable-consensus-state`|
