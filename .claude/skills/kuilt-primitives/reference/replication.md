# kuilt-primitives — replication

Routes for state every peer converges on: the CRDTs and replicating them, a room too big for all-to-all, fair dealing, an unbiasable random, dedup, a total that must not wrap, an archive beside the replica, sealed boxes.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a last-write-wins register, a grow-only or add/remove set, a shared JSON document; trimming a log that only grows; wanting the *edits* not the value|`LWWRegister`/`LWWMap`, `GSet`/`GCounter`/`PNCounter`, `ORSet`/`ORMap`, `BoundedCounter`, `Causal`, `CausalMap`, `EphemeralMap`, `JsonCrdt` (`Patch`); `Rga.dropWindow` + `VersionVector.contiguous`; `OpLogCrdt` (`operations`/`classify`/`dotOf`)|`replication.md#replicated-data`|
|replicating a CRDT by hand; "claim it only if it's free"; a hop so two guests can see each other; averaging model updates privately; comparing two peers' state as one number; a write lost to close|`Quilter`; `Quilter.mutateOrSkip { … }`; `Room.channel(id)`; `FedAvg` + `TrainingUpdate`; `canonicalDigest`; `Quilter.drainAndClose`|`replication.md#replicated-data`|
|a session too big for all-to-all|`GossipSeam` + `deltaTargets = { gossip.activePeers.value }` (`recommendedActiveViewSize`, `FullFanout`, `hostedOverlay`)|`replication.md#scaling-to-many-peers`|
|dealing cards nobody can peek at|`DealSession` + `SraScheme()`|`replication.md#dealing-cards-nobody-can-peek-at`|
|a shared random nobody could steer — a seed, a die roll|`FairRandom.roll()`|`replication.md#nobody-chose-that-number`|
|a `seenIds` set|`GSet`/kuilt dedup|`replication.md#dedup`|
|a `Long` total that must not wrap; cross-multiplied fractions, `a * b / c`|`plusOrNull`/`timesOrNull`; `compareProducts`, `mulDiv`, `WideSum`, `Int128`|`replication.md#counting-past-what-a-long-holds`|
|a longer-lived copy of a replicated log; replaying it, a resume cursor|`BoltDecorator` + `AppliedOpSink` (`WarpLogRecordExporter(appliedOps = …)`); `Bolt.replay` + `ReplayScope` → `CleanTail`/`Truncated`; `Bolt.durability()`|`replication.md#archiving-what-the-live-replica-forgets`|
|encrypting to a public key, bytes only one recipient can open|`SealedBox.seal`/`open` + `SealPublicKey`/`SealPrivateKey`|`replication.md#sealed-boxes`|
