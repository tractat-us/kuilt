<!--
DRAFT — GitHub release body for v0.7.7. Paste everything below the closing `-->` into the
release, above GitHub's generated "What's Changed" list. Every `ASSUMES` comment names an
unwritten task of #3159 whose final shape the snippet guesses; re-check each against `main`
at the tag, then delete the comments before posting.
-->

## Highlights

**A device that comes back is recognised.** When a phone kills your app, or a laptop sleeps
through a dropped link, the room now lets that device back in by itself: into the same seat
if the host is still holding it, or straight past the host's vetting if it was away less than
an hour. Nobody presses anything and your app writes no reconnect code.

It works because each device now keeps a key of its own. While a device is in a room, the host
keeps handing it a signed pass: "this key may come back until this time." Coming back means
showing the pass and proving you hold the key.

**This release breaks the wire and the API, on purpose, in a patch.** Every peer in a room must
run 0.7.7. A peer on 0.7.6 or older is turned away at the door with
`RejectCode.ProtocolMismatch`, and a 0.7.7 peer cannot join an older host. Upgrade the hosts and
the joiners of one deployment together. Pre-1.0, a patch is how kuilt ships; this one needs the
care a major would.

**Coming back (`:kuilt-session`)**

There are three ways back in, and the app sees each one differently.

| Away for | What happens | What your app sees |
|---|---|---|
| Less than the seat is held (60 s by default) | Restored to the same membership, after a healed link **or a process restart** | `MembershipEvent.Resumed`. No `Left`, no `Joined` |
| Less than its pass lasts (1 h from last contact by default) | Let back in with no vetting | `Joined(member, returning = ReturningMark(previousId))` on the host and every bystander |
| Longer | Asked again, like a newcomer | A plain `Joined` |

<!-- ASSUMES Task 2.6: `MembershipEvent.Joined(member, returning: ReturningMark? = null)` and `ReturningMark(previousId: PeerId?)`. -->

- One handshake covers joining and every return: `Hello → Challenge → Proof → Welcome/Reject`.
  The host signs its challenge, so a joiner knows the host's key is real (#3159).
- A device that restarts with a new peer id gets its old seat back. The old membership leaves
  with `LeaveReason.Superseded(by)`, and the new one inherits it.
  <!-- ASSUMES Task 1.6: `LeaveReason.Superseded(by: PeerId)`. -->
- Two tabs of one browser share one key, and each gets its own seat. **`Member.key` is not unique
  in a roster**, so never assume one member per key.
- A different device claiming a connected member's peer id is refused with
  `RejectCode.KeyConflict`. A forged or replayed proof gets `RejectCode.BadProof`.
- Joiners can send the app's own device id with `join(…, deviceId =)`. The proof signs over it.

**Who may come back (`:kuilt-session`, `:kuilt-identity`)**

- Hosts now ask an `AdmissionPolicy` about newcomers, and only about newcomers. A held seat or a
  valid pass never reaches the policy. `AdmissionPolicy.open()` lets everyone in with a one-hour pass.
- The policy chooses each pass's terms. `GrantTerms(Duration.INFINITE, GrantReach.AnyRoomOfIssuer)`
  is a friend's pass: it never expires and works in any room the same host runs.
- `RevocationList.revoke(key, notBefore)` voids the passes a device already holds. It is **not a
  ban** and **does not remove anyone present**. Keep your own blocked set for that.
- A pass is good only with the host that issued it. After a lobby re-election, or on a different
  cluster server, a returning device is asked again.

**Keys on every platform (`:kuilt-identity`)**

`DeviceKeyStore.platform(…)` keeps the device key where it never leaves the device. It uses the
Keychain on iOS (this device only), the Android Keystore, a non-extractable WebCrypto key on
wasmJs, and a file on macOS and desktop JVM. All of these shipped in 0.7.5 and 0.7.6; 0.7.7 is
where rooms start using them.

## Breaking changes

- **Admit protocol.** Every admit message changed. Old and new peers cannot share a room.
  <!-- ASSUMES Task 2.6: the protocol line ends at 9, with MIN_SUPPORTED = MAX_SUPPORTED = CURRENT. -->
- **`DeviceKeyStore` is required** on `SeamRoomFactory`, `SeamRoomFactory.systemClock`,
  `LoomRoomHost`, `NwRoomHost`, `MultipeerRoomHost`, `KtorRoomHost` and `clusterClient`.
- **Hosts take a `HostAdmission(policy, revocations)`**: `RoomFactory.host`, `KtorRoomHost`,
  `LoomRoomHost` and friends, and the election lobby.
  <!-- ASSUMES Task 2.4: the parameter is named `admission` on every host entry point. -->
- **`adopt` takes an `AdoptRole`**, `AdoptRole.Host(admission, roomId)` or `AdoptRole.Joiner`, in
  place of `SessionRole` plus `roomId`. <!-- ASSUMES Task 2.4. -->
- **`Member` gains a required `key: DidKey`.** Code that builds a `Member`, such as a test fake, must
  pass one. `fakeKeyStore(index)` in `:kuilt-identity-test` gives you fixed test keys.
- **`ResumeSecret` and the `Resume` frame are gone.** `ResumeToken` loses `secret`. A token your
  app serialised under 0.7.6 no longer decodes. Delete it rather than migrate it.
  <!-- ASSUMES Task 1.8: `ResumeToken` keeps peerId, roomId and issuedAt; whether `Room.resume` and `Room.resumeToken` survive is decided there. -->
- **Reject codes.** `ResumeTokenInvalid`, `ResumeWindowNotYetOpen` and `ResumeWindowExpired` are
  removed. `BadProof`, `KeyConflict` and `AdmissionPolicyFailed` are new. A `Reject` now carries
  `retryable` on the wire, so your own code arrives as `RejectCode.Unknown(id, retryable)` with the
  flag you set. <!-- ASSUMES Task 2.5: Unknown gains `retryable`. -->
- **`RoomFactory.join` and `ElectionLobby.start`/`awaitRoom` gain `deviceId`.** Callers are
  source-compatible; an implementer of either interface must add the parameter.
- **`JoinerReconnectController.tryResume` takes a `ProvenResume`**, which only the room can build,
  after it has checked the key. A custom hold policy changes its signature.
  <!-- ASSUMES Task 1.5: `tryResume(resume: ProvenResume, at: Long)`. -->
- A held seat now survives a **process restart** too, which changes what a custom hold policy sees.

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
<!-- ASSUMES Task 2.4: `host(pattern, memberName, roomId, admission)` with a named `admission`. -->

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
<!-- ASSUMES Tasks 1.7–1.8: the joiner re-Hellos on its own; if `Room.resume` survives, it is no longer needed by apps. -->

**4. Recognise people by key, not by peer id.** A returning device may have a new peer id.

```kotlin
room.events.collect { event ->
    when (event) {
        is MembershipEvent.Resumed -> Unit // same seat, nothing to redraw
        is MembershipEvent.Joined ->
            if (event.returning != null) showBack(event.member.key) else showNew(event.member.key)
        is MembershipEvent.Left ->
            if (event.reason is LeaveReason.Superseded) Unit // the same device, back under a new id
            else showGone(event.peerId)
        else -> Unit
    }
}
```
<!-- ASSUMES Tasks 1.6 and 2.6: `Joined.returning`, `LeaveReason.Superseded`. -->

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
