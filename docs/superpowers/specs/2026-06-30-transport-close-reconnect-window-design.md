# Transport-close opens the reconnect window (host Room)

**Issue:** #993 — `SeamRoom`/`MuxServerLoom`: a transport-close drop of an admitted joiner
emits `MembershipEvent.Left(Normal)` immediately, so the reconnect window never opens and
`Room.resume(token)` always returns `WindowClosed`. The resume fast-path is structurally
unreachable over a real transport.

## Root cause (confirmed empirically)

A host `SeamRoom` has **two competing disconnect mechanisms** with opposite semantics:

1. `runPeersWatcher` — collects `seam.peers.drop(1)`; on any admitted peer leaving the set
   (and the seam not `Torn`), calls `removeFromRoster(peerId, LeaveReason.Normal)` **and**
   `stopDetector(peerId)`. Immediate `Left(Normal)`.
2. The per-peer `HeartbeatPartitionDetector` — the windowed path
   (`PeerUnresponsive → markPartitioned → WindowOpened`, eventually `PeerLost`).

Over a real fabric (`MuxServerLoom`/`RoomHubSeam`) a socket close removes the peer from the
hub seam's `peers` set immediately:

```
watchDrop (MuxServerLoom) → teardownConnection → RoomHubSeam.deregister (_peers - peer)
  → SeamRoom.runPeersWatcher sees the drop → Left(Normal) + stopDetector
```

The `Torn` guard does not fire — only one spoke dropped; the hub seam stays `Woven`. So
mechanism 1 wins the race, cancels the detector, and the window machinery (mechanism 2) is
never reached. Reproduced with a `MuxServerLoom` host + client joiner: after a transport
close, with virtual time advanced 1200 ms (past a 200 ms heartbeat timeout and a 500 ms
window), the host emitted only `[Joined, Left(Normal)]` — no `Partitioned`, no `WindowOpened`.

Two supporting facts:

- The detector **already documents** a `Reason.TransportClosed → PeerUnresponsive` fast-path
  (fires when `link.incoming` completes), but it is **dead** for `SeamRoom`: the per-peer
  `PerPeerSeam.incoming` is a `filter` over a `MutableSharedFlow` (`rawIncoming`) that never
  completes.
- Eviction-on-window-expiry is driven by the **detector's** `PeerLost`, not the controller's
  `WindowExpired` (`runReconnectEventLoop` is a no-op on `WindowExpired`). The detector's
  clock-driven loop fires `PeerLost` after the window even with no transport, so keeping the
  detector as the disconnect owner means eviction still works with no new wiring.

The existing `RoomResumeTest` never caught this because it only partitions via
`FaultySeam.DropAll`, which drops *frames* but keeps the peer in `seam.peers` — the
silent-partition path, the one case where the window machinery already works.

## Target behavior

- **Transport-close of an admitted joiner (host side):** open the reconnect window
  (`Partitioned` → `WindowOpened(now + reconnectWindow)`), hold the membership partitioned,
  let `Room.resume(token)` within the window succeed (`Success` → `Resumed`); only on window
  expiry fire `Left(PartitionExpired)`.
- **Clean application-level leave:** still `Left(Normal)` immediately — distinguished from a
  transport-close by an explicit `AdmitMessage.Goodbye`.

## Approach: the detector owns disconnect

`runPeersWatcher` is **deleted**; the per-peer detector becomes the single owner of the
disconnect lifecycle. The fast transport-close signal flows through the *existing* partition
pipeline.

### §1 Detector peers-watch (`:kuilt-liveness`)

`HeartbeatPartitionDetector` gains a second trigger for its existing `Reason.TransportClosed`
path: it watches `link.peers`, and when its target leaves the set, fires
`PeerUnresponsive(TransportClosed)` immediately. Integrated into the existing Healthy-state
wait so it auto-re-arms after a recovery:

```
Healthy tick:
  dropped = withTimeoutOrNull(checkInterval) { link.peers.first { target !in it } } != null
  if (dropped) → PeerUnresponsive(TransportClosed) → awaitRecoveryOrLoss()
  else         → recompute silence; if silence ≥ timeout → PeerUnresponsive(Timeout)
```

- Silent partition (`FaultySeam.DropAll`) keeps the peer in `peers` → the race times out →
  the existing `Timeout` path runs. **Existing behavior and tests preserved.**
- The detector must observe the target *present* before reacting to absence (it is started
  from `addToRoster`, after the peer is registered, so the first `peers` value already
  contains the target; guard against a spurious immediate fire if it does not).
- The pre-existing `link.incoming`-completion path to `TransportClosed` stays as-is — correct
  for seams whose `incoming` does complete; harmless (unreachable) for `PerPeerSeam`.
- No new enum (`Reason.TransportClosed` exists), no new dependency (`Seam.peers` is in
  `:kuilt-core`, which `:kuilt-liveness` already depends on).

### §2 Role + reason mapping (`:kuilt-session`)

`runPeersWatcher` is deleted (every admitted member has a detector, so it is fully subsumed).
It was also — incorrectly — handling the joiner-side host drop as `Left(Normal)`; the detector
now carries that case, mapped on **role + reason** (`reason` exists today but is ignored):

| Role | Event | Mapping |
|------|-------|---------|
| Host | `PeerUnresponsive` (any reason) | `markPartitioned` → `Partitioned` + `WindowOpened` |
| Host | `PeerLost` | `Left(PartitionExpired)` |
| Joiner | `PeerUnresponsive(TransportClosed)` of host | `markHostLost` immediately — transport gone, no host-resume path |
| Joiner | `PeerUnresponsive(Timeout)` of host | `Partitioned` (unchanged — silent partition may recover) |
| Joiner | `PeerUnresponsive` of non-host | `markPartitioned` (unchanged) |
| Both | `PeerRecovered` | `Recovered` (unchanged) |

The host rows fix #993. The joiner `TransportClosed → HostLost` row both prevents a regression
from deleting `runPeersWatcher` and fixes a latent bug (a host vanishing from a non-torn
channel seam currently surfaces as `Left(Normal)` for the host instead of `HostLost`).

`PeerLost` mapping is unchanged (host → `Left(PartitionExpired)`; joiner + host → `HostLost`).
`WindowOpened`, `tryResume`, eviction, and resume self-healing (`PeerRecovered`) flow through
the existing pipeline unchanged.

### §3 Graceful leave: `AdmitMessage.Goodbye` (`:kuilt-session`)

New wire variant distinguishing a deliberate leave from a socket drop:

- `SeamRoom.leave(reason)`: when `_role == Joiner && reason is LeaveReason.Normal`, broadcast
  `Goodbye` on the seam **before** flipping `closed` / closing the seam (the terminal guard
  would otherwise no-op the send). Best-effort (`runCatchingCancellable`).
- Host `handleAdmitFrame(Goodbye)`: `stopDetector(sender)` + `removeFromRoster(sender, Normal)`
  immediately. Because `Goodbye` precedes the close, the host cancels the detector before the
  transport-drop trips the new peers-watch — the drop becomes a no-op (peer already
  un-admitted). One clean `Left(Normal)`, no window.
- Degradation: a lost `Goodbye` falls back to the transport-close → window path (a clean leave
  that loses its announcement looks like a drop). Acceptable.
- Joiner-role rooms ignore inbound `Goodbye` for now — a host leaving is covered by
  `runTornWatcher → HostLost`.

## Testing

- **Acceptance (`:kuilt-session`):** `MuxServerLoom` host + client joiner over
  `InMemoryConnectionSource`. Admit, capture token, `clientMesh.close()`. Assert host emits
  `Partitioned` + `WindowOpened` (not `Left(Normal)`). Two continuations: (a) joiner reconnects
  + `resume(token)` within window → `Success` + `Resumed` on both; (b) advance past window →
  `Left(PartitionExpired)`.
- **Goodbye:** joiner `leave()` → host emits `Left(Normal)` immediately, no `WindowOpened`.
- **Joiner-side:** host transport-close → `HostLost` (guards the `runPeersWatcher` deletion).
- **Detector unit (`:kuilt-liveness`):** target leaves `link.peers` → immediate
  `PeerUnresponsive(TransportClosed)`; peer stays in `peers` → still `Timeout` path.
- **Regression:** existing `RoomResumeTest` (`FaultySeam.DropAll`) stays green.

All coroutine tests: `StandardTestDispatcher`, tight timeout (≤ 5 s), seeded RNG, bounded
`advanceTimeBy` (never `advanceUntilIdle`), node coroutines on `backgroundScope`.

## Scope & risks

- **Touches:** `:kuilt-liveness` (detector peers-watch) + `:kuilt-session` (delete
  `runPeersWatcher`, role+reason mapping, `Goodbye`). No `:kuilt-core` change.
- **`explicitApi`:** new public `AdmitMessage.Goodbye`; explicit visibility on any new detector
  member.
- **Minor double-event:** on resume the host may emit both `Recovered` (detector) and `Resumed`
  (controller) — both set `Connected`. Acceptable; dedup only if a consumer needs it.
- **Verify cache-disabled before merge:** `./gradlew :kuilt-session:build :kuilt-liveness:build
  detektAll --rerun-tasks` (Android/Native variants, not just `jvmTest`).
- **Downstream:** unblocks fireworks #2967 PR-5; describe as behavior in the PR body, no
  cross-repo reference in code/docs per the references policy.
