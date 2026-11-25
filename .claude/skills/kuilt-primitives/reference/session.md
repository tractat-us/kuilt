# kuilt-primitives — session

Routes for rooms and seats: rejoin and why a drop happened, a device's own key and its pass back in, liveness and presence, per-member frames, a last frame before leaving, host election.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a rejoin/reconnect loop, a resume token, a grace window, a `(room, device) → seat` record; rejoining after an app kill; who came back; why a rejoin was refused; a peer id kept across processes|nothing to call: the room says Hello again, its `DeviceKeyStore` proves the seat (`RoomConformanceSuite` counts the app's calls); after a kill, the launch's plain `join` over the same store; `Resumed`, `LeaveReason.Superseded(by)`; `HostLost.reason` (`FailureReason.Refused` → `code.retryable`); a new peer id per process — a reused superseded one is told `Farewell(supersededBy)`|`session.md#rejoin--reconnect`|
|a device key/did:key kept across launches, a returning device, a member's device; signing app bytes with that key; a "remember this device" pass, revoked across restarts; who joins, testing it|`DeviceKeyStore.platform` (`DeviceKeyStoreConformanceSuite`), `Member.key` (not unique) → `DeviceKey.signApp` + `DidKey.verifyApp` (never the raw `sign`); `Grant` + `GrantTrust`/`RevocationList.open`; `AdmissionPolicy`/`HostAdmission`/`admissionRequest`|`session.md#device-identity--return`|
|a "why did we drop" classifier; a retry/back-off loop; a per-session id every peer agrees on|`MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason`/`FailureReason`); `ExponentialBackoff`; `Room.roomId`/`RoomFactory.host(…, roomId = …)`|`session.md#rejoin--reconnect`|
|a heartbeat, an idle reaper, "is this peer alive"; reaping an unjoined room; a `pendingSeats` map|`HeartbeatPartitionDetector`; `SoloDeadlineDetector`; `JoinerReconnectController`|`session.md#liveness--presence`|
|a "reconnecting…" flag, a `lastSeen` map, a seat-release countdown; that surface for a **game**; "you are offline" vs "theirs"|`Room.roster` + `Member.liveness` + `Liveness.Partitioned` (not `Room.events`); `RoomGameSession.presence`/`.roster` via `gameOverRoom`; `Room.localFabric` + `MembershipEvent.LocalFabricLost`/`LocalFabricRestored`|`session.md#liveness--presence`|
|`room.incoming.filter { it.sender == peer }`, a member's first frame lost before subscribing|`Room.incomingFrom` (`FramesLost`)|`session.md#per-member-frames`|
|a `delay` between a last send and `leave`/`close`|`Room`/`Seam.flush` → `FlushOutcome`|`session.md#a-last-frame-before-leaving`|
|a `host == selfId` check, re-electing a host that left mid-lobby|`SeamRoomFactory.electLobby` → `awaitRoom` → `ElectionOutcome.BecameHost` → `start()`|`session.md#host-election--the-lobby`|
