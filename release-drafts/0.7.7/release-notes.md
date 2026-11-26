<!--
DRAFT — GitHub release body for v0.7.7. Paste everything below the closing `-->` into the
release, above GitHub's generated "What's Changed" list. Refreshed 2026-11-26 against
`origin/main` at `31ea84acd4`. No `ASSUMES` comments remain. At the tag, check
`git log --oneline 31ea84acd4..<tag>` for anything that merged after.
-->

## Highlights

**A device that comes back is recognised.** When a phone kills your app, or a laptop sleeps
through a dropped link, the room now lets that device back in by itself: into the same seat
if the host is still holding it, or straight past the host's vetting if it was away up to an
hour. Nobody presses anything and your app writes no reconnect code.

It works because each device now keeps a key of its own. While a device is in a room, the host
keeps handing it a signed pass: "this key may come back until this time." Coming back means
showing the pass and proving you hold the key.

**This release breaks the wire and the API, on purpose, in a patch. Upgrade every peer of a room
together.** 0.7.6 speaks admit protocol 2; 0.7.7 speaks 9 and accepts nothing else. A 0.7.6 peer is
turned away at the door with `RejectCode.ProtocolMismatch`, and a 0.7.7 peer cannot join a 0.7.6
host. So the hosts, servers and joiners of one deployment move to 0.7.7 in one step. Pre-1.0, a
patch is how kuilt ships; this one needs the care a major would.

**Coming back (`:kuilt-session`)**

There are three ways back in, and the app sees each one differently.

| Away for | What happens | What your app sees |
|---|---|---|
| Less than the seat is held (60 s by default) | Restored to the same membership, after a healed link **or a process restart** | `MembershipEvent.Resumed`; bystanders hear only `Unpaused`. No `Left`, no `Joined` |
| Up to `readmitFor` after last contact (60 min under `AdmissionPolicy.open()`, and up to 90 depending on when it dropped) | Let back in with no vetting | `Joined(member, returning = ReturningMark(previousId))` on the host and every bystander |
| Longer | Asked again, like a newcomer | A plain `Joined` |

In the last two rows the old membership is gone: the host and bystanders saw it leave with
`PartitionExpired` when its hold ran out. A device whose room kept running through the outage
hears `MembershipEvent.Readmitted`. Whatever your app tied to the old membership starts over.

- One handshake covers joining and every return: `Hello → Challenge → Proof → Welcome/Reject`.
  The host signs its challenge, so a joiner knows the host's key is real (#3159).
- A device that restarts under a new peer id while its seat is held gets that seat back. The old
  membership leaves with `LeaveReason.Superseded(by)`, and the new one inherits it. A process still
  running under the superseded id is told so with `RejectCode.Superseded` and ends with
  `Left(self, Superseded(by))` instead of timing out (#3697).
- A second live process on one device, such as a second browser tab, shares the device's key and
  gets a seat of its own. **`Member.key` is not unique in a roster**, so never assume one member
  per key.
- A different device claiming a connected member's peer id is refused with
  `RejectCode.KeyConflict`. A forged or replayed proof gets `RejectCode.BadProof`.
- A returning device that missed a departure while it was away now drops that member, with the
  new `LeaveReason.Unobserved` (#3641).
- Joiners can send the app's own device id with `join(…, deviceId =)`. The proof signs over it.

**Who may come back (`:kuilt-session`, `:kuilt-identity`)**

- Hosts now ask an `AdmissionPolicy` about newcomers, and only about newcomers (#3699). A held
  seat or a valid pass never reaches the policy. `AdmissionPolicy.open()` lets everyone in.
- The policy chooses each pass's terms. `GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)`
  is a friend's pass: it never expires and works in any room the same host runs.
- The host renews a pass while its device stays connected (#3701). A pass lives one and a half
  times `GrantTerms.readmitFor` and is renewed every half of `readmitFor`, so `readmitFor` is the
  least time a device away after its last contact can still return on it (#3712). A renewal that
  fails to sign or send is tried again, closing in on the pass's end (#3717).
- `RevocationList.revoke(key, notBefore)` voids the passes a device already holds. It is **not a
  ban** and **does not remove anyone present**. Keep your own blocked set for that.
- A pass dated more than five minutes in the host's future is refused as
  `GrantVerdict.NotYetValid` (#3643).
- A pass is good only with the host that issued it. After a lobby re-election, or on a different
  cluster server, a returning device is asked again.

**Keys on every platform (`:kuilt-identity`)**

`DeviceKeyStore.platform(…)` keeps the device key where it never leaves the device. It uses the
Keychain on iOS (this device only), the Android Keystore, a non-extractable WebCrypto key on
wasmJs, and a file on macOS and desktop JVM. All of these shipped in 0.7.5 and 0.7.6; 0.7.7 is
where rooms start using them.

- A store that checks its key at launch no longer replaces the key when the crypto provider
  fails for a moment. Only a check that ran and refused counts as a damaged key (#3719).
- `DeviceKey.sign` is now `@IdentityInternalApi`. To sign your app's own bytes, use
  `DeviceKey.signApp(context, message)` and check with `DidKey.verifyApp(context, message,
  signature)`. Those signatures can never be passed off as one of kuilt's own statements (#3613).

## Also in this release

**Fair-share scheduling (`:kuilt-heddle-core`, `:kuilt-heddle`).** An admin screen can now edit a
scheduler's groups without writing any tree code. Read the live tree as a `TopologyDocument`,
edit it by path (`addChild`, `remove`, `move`, `rename`, `setWeight`, `setMaxConcurrent`,
`setTemplate`), list what changed with `changesTo` (each `TopologyChange` says, through
`saveEffect`, whether a group keeps its history), preview with `previewApply`, and `apply` only
if nobody changed the tree since you loaded it. `ShareLine` gives every group's share of the pool
and how far it stands from it (`lag`). Templates can nest attached children. The heddle cookbook
has a minimal editor, end to end. Smaller fixes: a snapshot whose books do not balance decodes as
`Corrupt`; a waker clamped after a restart keeps what its open leases reserve; `retire` takes an
unused prepared edge; and `HeddleNode.reconcileOwn` lets a static peer repair its own row after a
reweight. A governed node restarted under a reused incarnation no longer has an act silently
deduplicated against an entry its earlier run left in the log: every act but a mint carries a
per-run nonce, and a mint whose key is already taken is refused and logged. Six read-only queries
leave `@HeddleInternalApi` and become stable: `EntitlementLedger.liveInboundEdges`, `mintedRoots`,
`hasForgotten` and `activationFitsTheBudget`, `weightFitsTheBudget`, and
`FairShareAllocator.peakPositionFraction`. The docs now say `cancel` is for work that never ran;
work that held its slot settles with `complete(lease, elapsed)`. A slot acquired at the longest job no longer holds a short job's slack until it completes: `LocalHeddle.shrink(lease, amount)` hands part of a lease's reservation back (#3729), and `FairSharePool` and `previewPicks` now shrink a lease to the winner's head at the grant when the head is below the slot's reservation (#3733). A competing edge's demand target now sits above what the edge already holds, so an edge holding a lot no longer reads as idle (#3728). `LocalHeddle`'s KDoc now states what a wrong cost estimate costs a group, with sizing
guidance on `LocalPolicy.defaultReservation` (#3652).

**Replication (`:kuilt-quilter`).** Anti-entropy ships much less when peers are idle or slightly
behind. At most one whole state is in flight per replica and per peer link, a stale digest is told
apart from a behind one (the new `QuiltMessage.VectorDigest`), and a behind state draws a digest
or a `QuiltMessage.StateReply` instead of another whole state. An older peer drops the two new
messages and still converges.

**Liveness and membership.** Heartbeat frames are told apart by a byte prefix instead of a full
decode. Across `:kuilt-session`, `:kuilt-game`, `:kuilt-gossip`, `:kuilt-warp` and
`:kuilt-heddle`, an event from a liveness detector counts only while that detector still watches
the peer, so a stale detector can no longer evict, release or reroute anyone. Several roster
races in `:kuilt-session` are closed by sending each fan-out from the section that decided it.

**Games and warp.** An eviction keeps spectators closed, admission after a replacement closes
only once the roster is full, and a queued vacate counts only while the voter is still vacating
(`:kuilt-game`). An older ring rebuild never overwrites a newer one (`:kuilt-warp`).

## Breaking changes

- **Admit protocol.** 2 in 0.7.6, 9 in 0.7.7, with `MIN_SUPPORTED = MAX_SUPPORTED = CURRENT = 9`.
  Old and new peers cannot share a room.
- **`DeviceKeyStore` is required** on `SeamRoomFactory`, `SeamRoomFactory.systemClock`,
  `LoomRoomHost`, `NwRoomHost`, `MultipeerRoomHost`, `KtorRoomHost` and `clusterClient`.
- **Hosts take a required `admission: HostAdmission(policy, revocations)`**:
  `RoomFactory.host`, `ElectionLobby.start`, and the `LoomRoomHost`, `NwRoomHost`,
  `MultipeerRoomHost` and `KtorRoomHost` constructors.
- **`adopt` takes an `AdoptRole`**, `AdoptRole.Host(admission, roomId)` or `AdoptRole.Joiner`, in
  place of `SessionRole` plus `roomId`.
- **`Member` gains a required `key: DidKey`.** Code that builds a `Member`, such as a test fake, must
  pass one. `fakeKeyStore(index)` in `:kuilt-identity-test` gives you fixed test keys.
  `MemberIdentity.dedupKey` is gone; key on `Member.key`.
- **Resume is retired.** `Room.resume`, `Room.resumeToken`, `ResumeToken`, `ResumeSecret`, the
  `Resume`/`ResumeAck` frames and `ServerClusterReconnect`'s token calls are removed. A token your
  app stored is dead data: delete it. A `Room` implementation, such as a test fake, drops those
  overrides; `:kuilt-session-test`'s `FakeRoom` already has.
- **Events.** `MembershipEvent.Joined` gains `returning`, and `MembershipEvent.Readmitted`,
  `LeaveReason.Superseded(by)` and `LeaveReason.Unobserved` are new. An exhaustive `when` over
  either sealed type stops compiling until it handles them, which is the point.
- **Reject codes.** `ResumeTokenInvalid`, `ResumeWindowNotYetOpen` and `ResumeWindowExpired` are
  removed. `BadProof`, `KeyConflict` and `Superseded` (terminal) and `HostFailed` and
  `AdmissionPolicyFailed` (retryable) are new. A `Reject` now carries `retryable` on the wire, so
  your own code arrives as `RejectCode.Unknown(id, retryable)` with the flag you set.
- **`RoomFactory.join` and `ElectionLobby.start`/`awaitRoom` gain `deviceId`.** Callers are
  source-compatible; an implementer of either interface must add the parameter.
- **`JoinerReconnectController.tryResume` takes a `ProvenResume`**, which only the room can build,
  after it has checked the key. A custom hold policy changes its signature. `ResumeResult` keeps
  only the verdicts a hold policy returns; the joiner-side outcomes are internal.
- A held seat now survives a **process restart** too, which changes what a custom hold policy sees.
- **`:kuilt-identity`.** `DeviceKey.sign` needs `@OptIn(IdentityInternalApi::class)`, and a
  `DeviceKey` written outside kuilt marks its `sign` override `@IdentityInternalApi`.
  `GrantVerdict.NotYetValid` is new.
- **`:kuilt-conformance`.** `RoomConformanceSuite.newResumeHarness` is replaced by
  `newReturnHarness(scope): ReturnFixture`, either `Supported(ReturnHarness)` or
  `Unsupported(trackingUrl)`. A `ReturnDevice` must run every `launch` and `launchBeside` on the
  device's **one** key store, as an app's launches share its platform store. A harness that gives
  each launch its own store reds `returnInsideGrantNoVetting`, `expiredGrantIsVetted` and
  `revokedSubjectIsVetted`, on the policy being asked (#3708). `DeviceKeyStoreConformanceSuite`
  gains two rows on how a store keeps passes.
- **`:kuilt-heddle-core`.** `FairShareAllocator.rebaseEnabled` and `rebases`, deprecated no-ops,
  are removed, and `FairShareAllocator.peakPositionFraction` drops its unused `demand` parameter
  (#3723).
- **`:kuilt-raft-test`.** `MultiNodeRaftSim`'s `nodeFactory` takes one `NodeStart` and returns
  the `RaftNode`. Seed a custom node from `start.random`, so a restarted node draws a fresh seed.

## Migrating from 0.7.6

**1. Give every room factory the device's key store.** Use the platform store in production. An
in-memory store forgets the key with the process, so a restarted app comes back as a stranger.

```kotlin
// 0.7.6
val factory = SeamRoomFactory.systemClock(loom, scope)

// 0.7.7
val factory = SeamRoomFactory.systemClock(loom, scope, DeviceKeyStore.platform("com.example.board"))
```

| Target | Call |
|---|---|
| iOS, macOS, wasmJs | `DeviceKeyStore.platform(appId)` |
| Android | `DeviceKeyStore.platform(appId, context)` |
| Desktop JVM | `DeviceKeyStore.platform(appId, directory)`, a local, non-roaming directory |
| Tests | `InMemoryDeviceKeyStore()` or `fakeKeyStore(index)`, **one per simulated device** |

Build one store per device, not per room. In a test, a host and a joiner that share a store are
one device, and from 0.7.7 they will restore each other's seats.

**2. Tell each host who may come in.** Keep the revocations in a durable store: a friend's pass never
expires, so an in-memory list forgets on restart and the voided passes work again.

```kotlin
// 0.7.6
val room = factory.host(Pattern("board"), memberName = "Iain")

// 0.7.7
val admission = HostAdmission(
    policy = AdmissionPolicy.open(readmitFor = 1.hours),
    revocations = RevocationList.open(durableStore),
)
val room = factory.host(Pattern("board"), memberName = "Iain", admission = admission)
```

To vet newcomers, write the policy yourself. Decide on `request.key`, which the joiner proved, and
never on `request.displayName`, which is its say-so:

```kotlin
val policy = AdmissionPolicy { request ->
    when {
        request.key in blocked.value -> AdmissionDecision.Reject(notWelcome)
        request.key in friends -> AdmissionDecision.Admit(GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer))
        else -> AdmissionDecision.Admit(GrantTerms(readmitFor = 1.hours))
    }
}
```

A host's passes survive its own restart when the host uses a platform key store. A
`GrantReach.ThisRoom` pass also needs the same room id after the restart, so pass a stable
`host(roomId =)`. A minted id is new each time.

**3. Delete your reconnect code.** Joiners re-dial and prove their key by themselves.

```kotlin
// 0.7.6: save the token, present it after a drop
val token = room.resumeToken ?: return
when (room.resume(token)) { /* … */ }

// 0.7.7: nothing to write. Watch the roster instead.
```

**4. Recognise people by key, not by peer id.** A returning device may have a new peer id.

```kotlin
room.events.collect { event ->
    when (event) {
        is MembershipEvent.Resumed -> Unit // same seat, nothing to redraw
        is MembershipEvent.Readmitted -> resetMyOwnSeat() // this device is back, as a new membership
        is MembershipEvent.Joined ->
            if (event.returning != null) showBack(event.member.key) else showNew(event.member.key)
        is MembershipEvent.Left ->
            if (event.reason is LeaveReason.Superseded) Unit // the same device, back under a new id
            else showGone(event.peerId)
        else -> Unit
    }
}
```

On a joiner's roster, only the host's key is verified by the joiner itself. Every other member's
key is the host's word.

**5. Revoke to void a pass.** This stops the next return, not the current visit.

```kotlin
admission.revocations.revoke(member.key, notBefore = Clock.System.now().toEpochMilliseconds())
```

## What this does not protect against

- A relay sitting on the victim's own link, on a fabric with no channel security of its own, can
  forward a challenge and its answer. Channel binding is reserved in the proof and empty in this
  release.
- The key binds the handshake, not later frames. On a `HostedOverlay` mesh, use a binding
  `LinkAdmission` (#2387).
- Lose the store that holds your revocations and every revoked device can come back. Lose the host's
  key and every pass stops working, which is the safe direction.
