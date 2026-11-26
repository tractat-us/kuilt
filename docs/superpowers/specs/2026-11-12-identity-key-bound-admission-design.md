# Identity, first slice: a device that comes back is recognised

Status: **draft, revised after review**, 2026-11-12. Epic #3159. Design record: [session 1](https://github.com/tractat-us/kuilt/issues/3159#issuecomment-6756180564),
[Iain's rulings](https://github.com/tractat-us/kuilt/issues/3159#issuecomment-6836437411), and the session 2
conversation summarised in § Decided. Reviewed by a builder check, an opus design-architect pass and a
Fable protocol pass; Iain answered the two questions it raised (Decided 11, 12).

## The story

Two people are whiteboarding. One of them is on a phone, and iOS kills the app. Ten minutes later
they open it again. Today the room has forgotten them: the 60-second resume window is long gone, so
the phone joins as a stranger and the other person sees someone new arrive.

After this work, the phone simply comes back. Nobody presses anything, nobody is asked anything, and
the other person sees "Iain is back". If they had been away for most of a day the room would ask to
let them in again — unless the host had marked them a friend, in which case it never forgets them.

The way it works is small. Every device quietly makes itself a key the first time it runs. While
someone is in the room, the host keeps handing them a fresh signed note: *"this key may come back
until this time."* Coming back means showing the note and proving you hold the key. Nothing else
about joining changes.

## Decided

Iain's statements are marked **(Iain)**. *My call* items were proposed in the conversation and
accepted by him, or are review corrections he has not yet seen (marked *revised*).

1. **Three tiers of return (Iain).**
   - *The seat is still held* (the host's reconnect window, counted from when it noticed the drop):
     under the same peer id the member is restored as the same membership, whether the link healed or
     the process restarted, and nobody's screen changes. A restarted process under a new peer id takes
     the seat over: members present see `Left(old, Superseded(by = new))` then `Joined(new)`, with
     identity and key kept (the slice-1 gate).
   - *Away less than the re-admission period* (default **1 hour**, per room; a device away up to this long always gets back in on its pass, per Amendment O and #3709):
     re-admitted with no vetting, announced to everyone as the same device returning.
   - *Longer*: ordinary admission, as if new.
2. **The grant's lifetime is the host's choice, per member (Iain).** A stranger gets the room's
   default; a friend can get no expiry and "any room I host" as scope. kuilt checks grants; it never
   decides who deserves one.
3. **"Friend" means different things to different apps (Iain).** bee: the devices and people you
   regularly work with. fireworks-compose: a community built over many games and networks. kuilt
   ships the grant and its check; the community graph is the app's and out of scope.
4. **A credential many hold never resumes; one a single device holds does (Iain, Q1).**
5. **One P-256 key per device (Iain, Q3).** Personas are later. `kuilt-nw`'s room key and #3157's
   TLS identity stay separate from the device key.
6. **fireworks-compose's `deviceId` stays its own id (Iain).** The device key signs over it in the
   admission proof — that signature is the binding (tractat-us/fireworks-compose#3862).
   *Note (2026-11-20, 1.4 review):* the binding is joiner-only. A host's own `deviceId` is never carried, since neither the Challenge layout nor the host intro has the field, so a host's `MemberIdentity.deviceId` is null on every joiner's roster. `ElectionLobby.start(deviceId)` takes the parameter for symmetry with `awaitRoom`, and its KDoc says it has no effect on the elected host.
7. **New module `:kuilt-identity` (Iain).** `:kuilt-seal` stays sealed boxes only.
8. **Friction is the anti-goal (Iain).** So:
   - *Joining asks the person nothing new* — no prompt, passphrase or account (my call).
   - *Reconnecting asks nothing at all* — kuilt re-dials, proves the key and restores the member;
     the app writes no reconnect code (my call).
   - *The safe path is one line* (*revised*): the device key store is a **required** parameter, and
     kuilt ships a one-line platform default (`DeviceKeyStore.platform(appId)`). Opting out is
     passing the in-memory store explicitly. The session promised "an app that does nothing gets
     it"; reviewers showed a silent default either writes the Keychain under tests or quietly
     disables recognition, so the repo's "optional ≠ tuning" rule wins.
   - *The bar is a test* — conformance asserts zero app calls and zero user actions on return.
9. **Key storage behind a small interface** with platform defaults (Keychain, Android Keystore,
   non-extractable WebCrypto key in IndexedDB, a file on desktop JVM). Secure Enclave later without
   a design change (my call).
10. **No mixed versions (my call).** `ProtocolVersion` bumps; an old peer is refused by the existing
    gate.
11. **A grant is good only with the host that issued it (Iain).** After a host change — lobby
    re-election, a cluster client on another server — returning people go through ordinary
    admission. Host-to-host handover of trust is not built.
12. **Revoking blocks the next return; it does not evict (Iain).** Removing someone present is the
    app's separate call.
13. **`DidKey` checks syntax in `init`; the point is decoded in `suspend DidKey.parse` (Iain,
    planning R1).** A constructor cannot suspend and WebCrypto's decoders are async, so `init`
    checks prefix, multicodec, length and the `0x02`/`0x03` lead byte only. `parse` and `verify`
    both run an **explicit on-curve check** (`x < p`, then Euler's criterion that `x³ − 3x + b` is a
    square mod p, which for p ≡ 3 mod 4 is exactly when the provider's root is correct) (Iain, 2026-11-12): cryptography-kotlin 0.6.0's JDK decoder takes a square root without
    checking it, so an off-curve x decodes there (found by #3201's probe). The check is validation,
    not decompression; decompression stays the library's.
14. **A revoked present member gets no refresh and no held-seat restore (Iain, planning R2).** The
    host stops re-issuing grants to a member whose subject was revoked after its current admission,
    and a revoked device's held-seat restore falls through to ordinary admission. That covers a
    connected seat as well as a partitioned one. The seat is decided again at the device's own Hello
    (Decided 12's "next return", not an eviction): the policy is asked first, and the seat is
    released with `Left(old, PartitionExpired)` only once it admits (Amendment K). The admission
    instant is inherited across a takeover, so a takeover cannot launder a revocation.
16. **kuilt decompresses the point itself and imports it uncompressed on every target (Iain,
    2026-11-12).** Real Safari 27.0.1 and WebKit refuse a 33-byte compressed `importKey("raw")`
    (`DataError`); Chromium and Firefox accept it, and the 65-byte form works in all four (#3232's
    probe). Every iOS browser is WebKit. So after the on-curve check, take `y = rhs^((p+1)/4)`, pick
    its parity by the lead byte, and import `0x04‖x‖y` on JVM, Android, Apple and wasmJs alike. The
    wire stays compressed did:key; export stays the library's. This supersedes Decided 13's
    "decompression stays the library's" and takes the JDK's unchecked decoder out of the path.
15. **Android's platform default takes a `Context` (Iain, planning R3):**
    `DeviceKeyStore.platform(appId, context)`, grants under `context.noBackupFilesDir`.
17. **One source for the host's key (Iain, 2026-11-12, slice 1–2 audit).** Host entry points take
    `HostAdmission(policy, revocations)`; the room builds its `GrantTrust` from its own
    `DeviceKeyStore` key. A caller-supplied trust key could differ from the store's and silently turn
    every grant into `UntrustedIssuer`.
18. **A key conflict needs a connected seat (Iain, 2026-11-12, slice 1–2 audit).** "Live" in the
    key → seat table means **connected**. The same peer id with a different key against a
    *partitioned* held seat releases that seat and is admitted as a new member — this is how a device
    whose key store was cleared, and whose peer id is durable, keeps working with no prompt.
19. **A superseded joiner sees `Left(self, LeaveReason.Superseded(by))`, then the room closes (Iain,
    2026-11-12, slice 1–2 audit).** The host sends that `Farewell` directly to the superseded peer, not
    only through `fanOutToOtherMembers`, which excludes its subject. A new process that Hellos under
    the superseded id and key is told by a named `Reject(Superseded)` instead (Decided 32).
20. **The resume-only reject codes go with the `Resume` frame (Iain, 2026-11-12, slice 1–2 audit).**
    `ResumeTokenInvalid`, `ResumeWindowNotYetOpen` and `ResumeWindowExpired` are removed when
    `Resume` is retired, together with their readers.
21. **The store keeps the newest grant per issuer and scope, not per issuer (controller ruling,
    2026-11-13, #3253's review; confirmed by Iain, 2026-11-16).** One host key serves many rooms, so
    `Room(A)` and `Room(B)` from one issuer are both kept; keyed by issuer alone, B's refresh evicts
    A's and the device loses its pass back into A — Decided 1's middle tier. A tie on `issuedAt` keeps
    the held grant, so a replayed or reordered copy cannot displace it. One exception (Iain,
    2026-11-25, Amendment L): a grant delivered on the direct `Welcome` answering this device's
    latest Hello, which C1 makes unreplayable, replaces a held grant dated beyond the clock-skew
    allowance (Decided 24) relative to it. That is how a device holding a post-dated pass recovers.
    A `GrantRenewal` carries no nonce, so it never takes the exception.
22. **Statement text is strict UTF-8 on encode as well as decode (controller ruling, 2026-11-13,
    #3252's review).** An unpaired surrogate is refused with `IllegalArgumentException` rather than
    replaced (`?` on the JVM, U+FFFD elsewhere), so a statement never signs a value other than the one
    it was given, and the signed bytes are the same on every target.
23. **Superseded in part by Decided 30:** a claimed id holding a **connected** seat under another key
    is the terminal key conflict whatever the proven key holds; a **partitioned** one is released
    (Decided 18) and the key decided again. **Where two rows meet, the claimed id decides, and a released seat is decided again (controller
    ruling, 2026-11-13, #3251's review; confirmed by Iain 2026-11-13).** When the claimed id holds a seat under
    a *different* key while the proven key holds *another* seat, the verdict is the terminal key
    conflict whatever either seat's liveness: releasing the id's seat would leave one key holding two
    seats. When Decided 18 releases a partitioned seat, the joiner's key is then decided as if that
    seat were never held: `Returning` with a valid grant, else ordinary. The same "decide again
    without the seat" applies to a revoked restore (Decided 14) and to a restore whose window refuses
    it. A host's own seat is never in the table; a joiner presenting the host's key is decided by Decided 30.
24. **A grant dated beyond a clock-skew allowance is refused (Iain, 2026-11-13, #3268's review).** A
    grant whose `issuedAt` lies more than a fixed skew allowance (a few minutes) past the verifier's
    `now` gets a new `GrantVerdict` (e.g. `NotYetValid`), failing closed: the device goes through
    ordinary admission. Otherwise a pass issued while the host's clock ran ahead survives a later
    `revoke(subject, now + 1)` for the whole skew. Adding a variant to the sealed `GrantVerdict` breaks
    exhaustive `when`s, so it lands in the breaking stack. The allowance and its place in the check
    order are settled by that task (#3300).
25. **`Reject` carries `retryable` on the wire (Iain, 2026-11-13, #3276).** A nullable field, decoded
    into `RejectCode.Unknown(id, retryable)`, in 2.5's protocol bump, so an app's own refusal code
    keeps its retry meaning and 1.7's re-Hello stops retrying a refused device.
26. **Apps may build an `AdmissionRequest` in tests (Iain, 2026-11-13, #3277).** A tests-only factory
    in `:kuilt-session-test` behind an opt-in marker; the room keeps the only production path, which
    2.4 narrows to the ordinary-admission verdict.
27. **Revocation voids passes; it is not a ban (Iain, 2026-11-13, #3270's sample).** `RevocationList`
    is monotone and cannot be undone, so a policy that refuses every revoked key bans for good. Samples
    and docs keep the two apart: revoking voids the passes already handed out, and an app-owned blocked
    set (which can be undone) is what keeps a device out of ordinary admission.
28. **macOS keeps the device key in a file; the Keychain store is iOS's (Iain, 2026-11-13, #3225's
    probe).** On macOS `ThisDeviceOnly` exists only in the data-protection keychain, which needs a
    team-signed app with a provisioning profile, so no test binary can reach it (`-34018`); the
    file-based login keychain ignores the accessibility class and travels with Time Machine and
    Migration Assistant. So macOS uses a file store in a non-roaming local directory, with that
    backup trade stated in its KDoc, and the Keychain store serves iOS. The iOS store runs the TCK
    over a fake Keychain backend. **Correction (#3312's review):** that runs locally and in the
    non-blocking `apple-nightly` job, not in PR CI, whose native job runs on Linux and cannot execute
    Apple tests; the Android store's fake-Keystore TCK, by contrast, runs per PR. A nightly step boots
    a simulator and runs the real Keychain once (#3310), since the default `iosSimulatorArm64Test`
    spawn has no keychain (`-25291`).
29. **The Android store tells a failing Keystore from a damaged key by the cause, and waits an hour
    when it cannot tell (Iain, 2026-11-13, #3306's reviews).** The Keystore's JCA surface answers a passing failure as
    "absent" (`containsAlias` false, `getCertificate` null), so the store reads with `getKey` first and
    treats a null certificate beside a key as a fault, never as "half-made", on API 31+. On API 33+ a
    load or sign failure is judged by its `android.security.KeyStoreException` cause. **Amended (Iain,
    2026-11-13, #3306):** AOSP flags `isTransientFailure()` on only nine codes and not on
    `SYSTEM_ERROR`, so "transient keeps, all else replaces" would replace on an outage. The rule is:
    transient, `isSystemError()` or `requiresUserAuthentication()` throws and keeps the key; a
    key-specific code (`KEY_NOT_FOUND`/`KEY_PERMANENTLY_INVALIDATED`, `VALUE_CORRUPTED`) or an
    `UnrecoverableKeyException` with no `KeyStoreException` cause replaces it, as does a
    `KeyPermanentlyInvalidatedException` on every version; any other failure, unflagged code 10
    included (AOSP's KeyMint bucket mixes `INVALID_KEY_BLOB` with passing TEE faults), is a failure
    of unknown cause. Below API 33, where no cause can be read, every failure except a `KeyPermanentlyInvalidatedException` is of unknown cause. **Amended again
    (Iain, 2026-11-13, #3306's re-review: "simplicity, not perverse corner cases"):** a failure of
    unknown cause replaces the key once the Keystore has been failing for at least an hour with no
    successful read in between. The store keeps one timestamp beside the grants, the start of the
    current run of failures; any successful read clears it. No launch is counted, so processes and
    process churn don't matter. On API 30 and earlier `getKey` answers null on an outage, so a "key
    was made" marker beside the grants turns null-with-marker into a failure of unknown cause rather
    than a first launch. The Android store requires API 26 (`@RequiresApi(26)` on `platform`; its
    grants store uses `java.nio.file`). A replaced key is a new device returning through ordinary
    admission.
30. **One key may hold many seats; a connected seat is never superseded (Iain, 2026-11-16, #3257).**
    Every tab of one browser shares one IndexedDB key, and many admitted tabs are the point ("if it's
    browser tabs I don't mind having many many admitted tabs"). So a Proof under a new peer id with a
    key that already holds seats **restores that key's partitioned seat** if it has one (the most
    recently connected first), and otherwise is **admitted to an additional seat**. A connected seat
    is never superseded by another peer id: live tabs coexist, and a restarted process still reclaims
    its dead seat. **The host's own key** gets an ordinary additional seat, so the host's second tab
    joins like any other. Consequences:
    - `Member.key` is not unique in a roster. Nothing in kuilt or a consumer may assume one member
      per key.
    - Review Focus 1's loop cannot form, because only a partitioned seat is ever superseded; a
      superseded joiner still ends its room and never re-Hellos.
    - Decided 23's terminal key conflict loses its premise ("one key holding two seats"): when
      Decided 18 releases the id's partitioned seat, the proven key is decided again under this rule.
    - Decided 18's same-id, different-key, **connected** refusal stands: that is another device
      claiming a live member's peer id, not a second tab.
31. **`Room.resumeToken` and `Room.resume` are removed, not kept (Iain, 2026-11-20, #3159 census).**
    Once the secret is gone, a token proves nothing, and return is the joiner's own re-Hello (Task
    1.7). Task 1.8 therefore deletes `Room.resumeToken`, `Room.resume`, `RoomFactory`'s token
    plumbing, `ResumeToken` itself and `mintTokenIfAbsent`. "If we find we are wrong we can add it
    back." Consequences:
    - Nothing is minted any more, so census P12 (when a joiner mints a token) is moot.
    - `ResumeResult.TokenInvalid` goes with the token. `ResumeResult.WindowNotYetOpen` and
      `tryResume`'s other verdicts stay only if the host-side `ProvenResume` path (Task 1.5) still
      produces them. 1.8 greps for a producer and deletes any code that has none (census P11).
    - Every KDoc, cookbook entry and Writerside topic that teaches app-driven resume is rewritten in
      1.8 or 1.9. That includes the #1593 citations on `Room.roomId` and `RoomFactory.host`, and the
      spike's #1637 probe (census P13).

32. **The protection is restated, a superseded id's Hello gets a named `Reject`, and
    `MemberIdentity.dedupKey` goes (Iain, 2026-11-25, #3662).**
    - Decided 18 stands. A bystander can take a *partitioned* member's id on a fabric with
      self-chosen peer ids, and *What this protects against* now says so. Seat-decision rule 3 is
      unchanged. Closing the gap needs channel binding, which is tracked separately.
      *Proposed correction (Decided 33):* channel binding does not close this gap; only a change to
      rule 3, or ids a peer cannot choose, does.
    - Gate finding I1: the Hello gate (and the Proof-time check, for a Hello challenged before the takeover) answers a Hello under a superseded id and its superseded key
      with a `Reject` naming it (`answers` = its nonce) under a new terminal code, instead of
      `Farewell(supersededBy)`. The joiner already honours a named `Reject` before admission. It
      ends `Left(self, LeaveReason.Superseded(by))`, as Decided 19 promises. This changes the v7
      wire, so it lands before v0.7.7.
    - The public `MemberIdentity.dedupKey` is deleted. Nothing in kuilt or its known consumers
      reads it, and naming a device is `Member.key`'s job.

33. **Proposed, awaiting Iain (#3696): channel binding does not stop a partitioned id being taken.
    The partitioned-id gap is a property of Decided 18, and the binding is deferred.**
    - **Why a binding cannot close it.** A channel binding ties a proof to the link it travelled
      over, so a proof made on one link cannot be replayed on another. The bystander in Decided 32 does
      not replay anything. It opens its own link and signs its own proof under its own key, so it
      binds its own link honestly. What tells it apart from the victim is the key alone. Decided 18
      deliberately lets a new key take a partitioned id, so that a device whose key store was cleared
      keeps its durable peer id. From the host's side, that device and a bystander look the same. A
      binding leaves rule 3 exactly as it is.
    - **What would close it, all three Iain's call:**
      - **A. Keep Decided 18 and the stated gap (recommended for closing the epic).** The gap is
        pinned by a test that reds when anyone closes it (slice 33.1). An app names a device by
        `Member.key`, which the cookbook already teaches.
      - **B. Refuse a different key, retryably, while the id's seat is held** (#3662 option 2).
        That was declined on 2026-11-25 because a cleared-store device then waits out the hold. That
        reason still holds.
      - **C. The id's previous key wins it back.** When Decided 18 releases a seat, the new seat
        records the key it took the id from. A later Proof under that key, against the connected
        taker, supersedes the taker instead of being refused `KeyConflict`. The victim always gets
        back in, and a bystander can squat only while the victim is partitioned. #3662 set this
        aside for "its own race against a legitimate cleared-store device". That race needs the
        cleared key to come back, which a this-device-only store cannot do, short of restoring a
        JVM key file from backup. So the reason is weaker than it looked. The cost is telling the
        taker why it was ended, which is a wire change: a v10 step, and a breaking one after v0.7.7.
    - **Which fabrics could supply a binding.** It would close only the *active relay* row of the
      threat section, and only where the joiner can read a value the host computes too. Read from
      the API surface, not probed:

      | Fabric | Binding | Cost |
      |---|---|---|
      | WebSocket over TLS, when the room host terminates TLS | `tls-server-end-point` (SHA-256 of the host's certificate, RFC 5929) from the joiner's trust manager. A TLS exporter is out of reach: no Ktor client engine kuilt uses hands one to the caller, and no browser does | JVM, Android and Darwin clients only, after #3157 / PR #3162. A browser client and a host relayed through someone else's server get none |
      | `kuilt-nw` | A TLS-1.2 PSK exporter (`sec_protocol_metadata_create_secret`) exists, and is **worthless** here: every member holds the room key the PSK comes from, and the suite has no ephemeral exchange. A member acting as relay can pass both handshakes through, so both legs export the same secret | none worth building |
      | Nearby | `ConnectionInfo`'s raw authentication token, the same on both ends of the UKEY2 exchange | cheap, Android only |
      | WebRTC (wasmJs) | SHA-256 of both DTLS fingerprints, sorted, read from the local and remote SDP | cheap |
      | Multipeer, TCP, in-memory | none: no API, plaintext, no channel | none |

    - **A fabric with no binding stays unbound, and the threat section says so per fabric.** A
      refusal would lock out every Multipeer, TCP and browser-WebSocket member. If a binding is
      built, the host must learn *whether* a link can be bound from the fabric, never from the proof.
      A proof that may carry an empty field is a downgrade any relay can choose.
    - **If built, field 6 of `kuilt/admit-proof` carries a one-byte kind followed by the value,**
      which each side derives on its own from its own end of the link. Today's `decode` refuses a
      non-empty field, so this is a `ProtocolVersion` step whenever it lands. Nothing done before
      v0.7.7 makes it cheaper: a host that checks bindings must refuse an empty field on a fabric
      that can supply one, so every older joiner is refused anyway.
    - Channel binding moves to § Deferred, with a reopen trigger. `kuilt-identity/module.md` on `main`
      still quotes the pre-Decided-32 text ("cannot … lock out another member", and `kuilt-nw`'s PSK
      as a remedy). Slice 33.2 corrects it.

34. **Proposed, awaiting Iain (#3727): the host lets a device back in on the last pass it issued,
    delivered or not. No ack, no new field, and `ProtocolVersion` stays 9.**
    - **The loss is narrower than #3727 says.** `Seam` delivers in send order. On a link that loses
      no frame and then delivers a later one, a renewal is lost only when nothing sent after it
      arrives. So the device's last contact comes before that renewal was sent, and the pass before
      it has more than `readmitFor` left at that contact. The pass issued at `t`, with the next
      renewal due at `t + readmitFor / 2`, ends at `t + 1.5 × readmitFor`, so more than `readmitFor`
      is left at any contact before `t + readmitFor / 2`. The floor dips only where a frame can be
      lost while later ones still arrive, so the device stays in touch on an older pass. The `Seam`
      contract does not rule that out, and a bonded seam that loses a ply with frames in flight is
      the in-tree candidate (not measured). In that case the floor can fall to `readmitFor / 2`.
    - **An ack cannot fix the case that matters.** It tells the host a renewal was lost, but over a
      link that has died the host can send nothing until the device comes back. By then the remedy
      is the same as with no ack: honour what the host knows it issued. The same objection applies to
      inferring delivery from a later frame, and there is no periodic joiner-to-host admit frame to
      carry an issued-at. Putting one in heartbeats would tie `:kuilt-liveness` to the session.
    - **The rule.** The departed-member record (*Membership and the wire*) keeps the last pass the host
      *signed* for that membership, the `Grant` itself, not only its `notAfter`. With several tabs it
      keeps whichever pass runs out later. A Proof whose key holds no seat counts as holding a valid
      grant when the key presents one, *or* when the record's pass for that proven key passes
      `GrantTrust.verify(recorded, provenKey, roomId, now)`: the same checks in the same order,
      revocation and expiry included. When both are valid, the more generous one wins, as today.
      The device must still prove its key, so the record is no use to anyone else. It never
      lengthens a pass: it honours a pass the host had already signed.
    - **The residue, stated.** The record lives in the host's memory. After a host restart, or once
      256 later departures have pushed it out, the device's own pass is all there is. So a renewal
      lost while later frames still arrive can lower the floor only across a host restart. #3717's
      retries stay: they cover a renewal that fails to sign, and a device that will next meet a
      restarted host.
    - **Timing: after v0.7.7 (recommended).** Nothing changes on the wire, so an old host and a new
      joiner, or the reverse, interoperate, and the change can ship in any patch. With three days to
      the tag, a seat-decision change that needs a review is not worth the hurry. An ack would be a
      v10 step, which after v0.7.7 refuses every v0.7.7 peer. That is the other reason it is
      rejected.

## Goals

- A device that drops and comes back — link heal or process restart — is restored to its held seat,
  or re-admitted without vetting inside its grant, with no app code. This is #1593's mechanism.
- Every member carries a device key, so an app can recognise a person across memberships and peer
  ids.
- A host decides who may come back and for how long, per member, and can take it back.

## Non-goals

- Personas, the equivalence log, QR linking, delegation beyond one link, agent keys.
- Decisions by vote or Raft. The host decides.
- Mesh link dedup using the key (#2387), `RoomAuthorizer` seeing the key. See § Deferred.
- f-c's community graph, bee's invite budgets (tractat-us/bee#218), OAuth, SPIFFE.

## Anti-goals

- **Any new user-visible step** on join or return.
- **A bearer secret on disk.** A grant is useless without the device's private key.
- **kuilt deciding who is trusted.**

## What this protects against, and what it does not

- **Protected:** a bystander in the room — who knows every peer id, the room id and every key —
  cannot resume as or return as another member. A bystander presenting a **connected** member's id
  under its own key is refused `KeyConflict`, and that member keeps its seat. A captured proof
  cannot be replayed.
- **Not protected (Decided 32):** on a fabric with self-chosen peer ids, a bystander presenting a
  **partitioned** member's id under its own key releases that seat and is admitted under the id
  (Decided 18). When the member's link heals, its return meets a connected seat under another key
  and is refused `KeyConflict`. Every member knows who is partitioned, so this needs no guessing.
  An app must name a device by `Member.key`, never by its peer id: bystanders see
  `Left(id, PartitionExpired)` then `Joined(id)` under a different key. Closing it needs
  authenticated peer ids, the channel binding below.
- **Not protected:** an active relay sitting on the victim's own link over an unauthenticated
  fabric. Peer ids are self-chosen on WebSocket and star fabrics, so such a relay can forward a
  challenge and its answer. Closing that needs channel binding (a TLS exporter from #3157, or
  `kuilt-nw`'s PSK session); the proof layout reserves a field for it, empty in this slice.
- **The key binds the handshake, not later frames.** On a `Mesh`, a link that later takes a
  member's peer id (#2387) sends frames under that member's `Member.key`. Deployments on
  `HostedOverlay` should use a binding `LinkAdmission` until #2387 lands.

---

The rest of this document is for the builder.

## Shape of the work

```
:kuilt-identity   (new; cryptography-kotlin, kotlinx-serialization, :kuilt-store)
   DidKey, DeviceKey, DeviceKeyStore (+ platform stores), signed statement layouts
   Grant, GrantTrust (own key + revocations), golden vectors

:kuilt-session    (+ :kuilt-identity)
   one admission exchange for all three tiers: Hello → Challenge → Proof → Welcome/Reject
   the host's key → seat table; AdmissionPolicy; Member.key; grant refresh
   ResumeSecret and the Resume frame retired
```

`:kuilt-core` does not change.

## `:kuilt-identity`

### Keys and names

- `DidKey` — `did:key` for P-256: multicodec `p256-pub` (`0x1200`, varint `0x80 0x24`), compressed
  33-byte SEC1 point, base58btc with `z`, so every value starts `did:key:zDn`. A value class whose
  `init` checks the prefix, multicodec, length and lead byte; `suspend DidKey.parse` also decodes
  the point (Decided 13).
- `DeviceKey` — an interface (`id: DidKey`, `suspend fun sign`) over a P-256 key that never has to expose its bytes. The software key wraps cryptography-kotlin's.
- cryptography-kotlin 0.6.0 exposes `EC.PublicKey.Format.RAW.Compressed` and
  `ECDSA.SignatureFormat.RAW`. *First task of slice 0:* prove compressed import/export at runtime on
  the WebCrypto provider, which has no native compressed path. If it fails, stop and report; do not
  hand-roll point decompression.

### Key storage

```kotlin
public interface DeviceKeyStore {
    /** This device's key: created on first call, the same on every call after. */
    public suspend fun deviceKey(): DeviceKey
    /** The grants this device holds, by issuer and scope. Not secret — useless without the key. */
    public suspend fun grants(): List<Grant>
    public suspend fun keepGrant(grant: Grant)
}
```

`InMemoryDeviceKeyStore` everywhere. Platform stores, one slice each: JVM file (takes a directory),
iOS Keychain, a macOS file store (Decided 28), Android Keystore, wasmJs WebCrypto + IndexedDB. `DeviceKeyStore.platform(appId)` is
the one-line default where the platform can find its own storage; on JVM it takes a directory, on
Android a `Context` (Decided 15).

### Signed statements

ECDSA P-256 / SHA-256, signatures in raw 64-byte `r‖s`. Each statement's **wire form is its layout
bytes followed by the signature** — never a CBOR object re-encoded for verification. Layout: a
length-prefixed ASCII domain tag, a version byte, then fields in fixed order, each with a `u16`
big-endian length prefix. Signatures are never identifiers, and every proof is single-use through
its nonce, so ECDSA malleability buys an attacker nothing.

| Statement | Tag | Fields |
|---|---|---|
| Challenge | `kuilt/challenge` v1 | host `DidKey`, room id, host peer id, joiner peer id (the Hello's fabric sender), joiner nonce, host nonce |
| Admission proof | `kuilt/admit-proof` v1 | room id, host `DidKey`, host peer id, joiner peer id, `deviceId` (empty when absent), host nonce, channel binding (empty this slice) |
| Grant | `kuilt/grant` v1 | issuer `DidKey`, subject `DidKey`, scope, `issuedAt`, `notAfter` (`Long.MAX_VALUE` = no expiry), 16-byte grant id, parent grant id (empty — the capability-chain slot) |

Scope is `Room(roomId)` or `AnyRoomOfIssuer` (a bare tag: valid wherever the verifying host's own
key is the issuer). Unknown tags fail to decode.

### Grants, trust and revocation

`GrantTrust(hostKey, revocations)` verifies: `verify(grant, subject, roomId, now): GrantVerdict` —
`Valid`, `Expired`, `WrongScope`, `WrongSubject`, `UntrustedIssuer`, `BadSignature`, `Revoked`.
`verify` is pure given its inputs. This slice trusts only the verifying host's own key.

**Revocation is by subject, with a cutoff.** `RevocationList` maps subject `DidKey` → `notBefore`;
a grant with `issuedAt < notBefore` is `Revoked`. One entry per subject, no grant storage on the
host, and a fresh grant after a revocation still works. It persists through a `DurableStore` the
host app supplies. Failure modes to document: losing the store forgets every revocation (revoked
friends return); losing the host key is the safe direction (every grant becomes `UntrustedIssuer`).

**What survives a host restart:** the host's key comes back from its platform store, so
`AnyRoomOfIssuer` grants keep working. A `Room(roomId)` grant survives only if the app passes a
stable id to `host(roomId =)` — a host-minted id is fresh per process.

### Golden vectors

- `did:key` against the W3C did:key P-256 test vectors.
- Each layout: fixed inputs → exact bytes.
- Verify-only signature vectors (Project Wycheproof's P-256/SHA-256 P1363 cases) checked on JVM,
  Android, iOS, macOS and wasmJs; a per-target sign-then-verify property for signing.

## `:kuilt-session`

### One exchange for admission and return

```
joiner                                        host
  Hello(displayName, deviceId, key, joinerNonce, …) ─▶
                                       ◀─  Challenge(signed: host key, room id, both peer ids,
                                                         joinerNonce, hostNonce)
  verify Challenge under the host key it names
  Proof(signed admit-proof, grants whose issuer == host key) ─▶
                                       check signature against Hello.key, grant.subject == Hello.key
                                       consult the key → seat table (below)
                                       ◀─  Welcome(answers = joinerNonce, membership, …, grant)
                                           or   Reject(answers = joinerNonce, code)
```

- **The joiner answers every Challenge independently**, signing the Challenge's *fabric sender* as
  the host peer id — never an id carried inside the Challenge. On a flat loom a co-joiner's stray
  Challenge cannot starve the real one.
- **The host verifies itself to the joiner.** The signed Challenge makes the host's own entry in a
  joiner's roster a verified key. Every other member's key on a joiner's roster is *host-asserted*,
  carried on the Welcome; say so in `Member.key`'s KDoc.
- **Pending challenges are bounded:** one per fabric sender, overwritten by a newer Hello from that
  sender, dropped when the peer is lost or after `admitTimeout`. Keying by sender, never by a claimed
  peer id, is what stops a bystander overwriting a member's pending challenge to lock it out.
- **The room id is now known to the joiner before the Proof**, because the Challenge carries it, so
  the proof binds the room and the joiner presents the grants from the challenger's key whose scope is
  this room or any room of the host — two at most (Amendment M). The host rejects on issuer, subject,
  scope and expiry before checking any signature, and caps the signature checks.

### The key → seat table

The host keeps, for each member, its key. On a verified Proof:

| Peer id | Key | Seat | Outcome |
|---|---|---|---|
| superseded (in `SupersededIds` under this key) | same | successor seated | **Refused**, terminal: `Reject(Superseded, supersededBy, answers)`, at the Hello gate or at Proof time. Checked before every row below (Decided 32) |
| same | same | held (partitioned or still connected) | **Restored**: the same membership (the `Welcome` carries its `membership` id). `MembershipEvent.Resumed` only if the seat was `Partitioned`; a still-connected seat's old link is superseded silently (plan Amendment E) |
| new | same | held, **partitioned** | **Restored under the new id**: the old member leaves with a dedicated `LeaveReason` (superseded), the new one inherits the seat's identity and key under a fresh membership id (plan Amendment E); one event pair, ordered through `fanOutToOtherMembers`, for every member present for it. A joiner that was away hears the successor in its admission burst, and its roster rebuild drops the old id later (Amendment G). With several partitioned seats, the most recently connected (Decided 30) |
| new | same | held, all **connected** | **Additional seat**: admitted as a new member under the same key; no seat is superseded (Decided 30). A joiner presenting the host's own key is decided against the joiner seats that key holds, never the host's: with none it falls to the rows below, and a later tab gets an additional seat. The TCK row is #3656 |
| any | same | gone, grant `Valid` | **Returning**: admitted without calling vetting; the roster Welcome carries a `returning` mark |
| same | **different** | connected | **Rejected**, terminal — a different device claiming a connected member's id (Decided 18) |
| same | **different** | partitioned | old seat released; the key is decided again without it: **Returning** with a valid grant, else **ordinary admission** as a new member (Decided 18, 23) |
| any | — | none, no valid grant | ordinary admission through `AdmissionPolicy` |

This retires `ResumeSecret`, the `Resume` frame and the token-presentation problem in #1593: a
healed link and a cold-started process both just say Hello, and the key decides. `JoinerResumeMachine`
keeps its reconnect loop but re-Hellos instead of sending `Resume`. The window controller's
`tryResume` takes a `ProvenResume` whose constructor is `internal` and called only after the key
check — the #3165 property as a type, not a comment.

The check must re-read the seat under the room lock **at Proof time**, not at Challenge time: a
`Goodbye` or window expiry can land between the two.

### Grant refresh

A grant lives `1.5 × readmitFor`. It is issued in the Welcome and **re-issued every
`readmitFor / 2`**, a third of its life, on the member's direct lane while it stays connected, so a
pass held at last contact has between `readmitFor` and `1.5 × readmitFor` left, provided the last
renewal reached the device (Amendment O, #3709). A renewal that fails to sign or send is retried,
with a backoff that tightens near the end of the device's last pass (#3711). The joiner keeps the
newest grant per issuer **and scope** in its `DeviceKeyStore` (Decided 21).

### The host's decision point

kuilt has no admission predicate today. This slice adds one, required on **host** entry points only
(joiners never decide):

```kotlin
public fun interface AdmissionPolicy {
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision
}
```

`AdmissionRequest`: the verified `DidKey`, display name (an unnamed joiner's is its peer id), and
`deviceId` (absent and `""` both reach the policy as `null`); its constructor is
`@SessionInternalApi` (Decided 26). `AdmissionDecision`: `Admit(grant: GrantTerms)` or
`Reject(code)`, never the `superseded` code. The room consults the key → seat table first; the
policy sees newcomers: ordinary admissions and additional seats (Amendment C), including a
revoked device's seat. A seat the newcomer's admission would release is released only after the
policy admits (Amendment K).

Provided: `AdmissionPolicy.open(readmitFor = 1.hours)` — admits everyone with a room-scoped grant.

The host bounds `decide` by the challenge's deadline (its `admitTimeout`). At expiry it sends
nothing and the joiner times out (census P7); a throw gets a retryable
`Reject(AdmissionPolicyFailed)`; an answer to a challenge no longer current is dropped (P6). A slow human-in-the-loop vet
needs a `Pending` frame that extends the joiner's deadline — not in this slice.

### Membership and the wire

- `Member.key: DidKey`, non-null. Carried on all four Welcome shapes (direct, its roster copy, bootstrap, host intro).
- The direct `Welcome`, every `Reject`, and the rest of the admission burst (bootstrap Welcomes and
  the host intro) carry `answers`, the nonce of the Hello they answer; a roster copy carries none. A
  joiner acts on a direct Welcome or a Reject only if it names its latest Hello (plan Amendments E
  and F, including the one pre-admission `ProtocolMismatch` exception), and drops a burst frame
  naming an older one (Amendment G). The direct `Welcome` also carries the seat's opaque `membership`
  id, minted at every admission and at a takeover under a new id, and kept through a same-id restore:
  a matching id is a restore, any other a fresh admission. A superseded id's Hello is answered with
  `Reject(code = Superseded, supersededBy = <successor>, answers = <its nonce>)`, so a new process
  of a superseded device ends `Left(self, Superseded(by))` instead of timing out (Decided 32,
  #3695). `RejectCode.Superseded` is terminal; `Reject.init` requires the code and `supersededBy`
  together, and a superseded `Reject` always names its Hello. The same answer replaces the
  Proof-time self-`Farewell` for a Hello challenged before the takeover; the takeover's
  `Farewell(old, supersededBy)` to the admitted old member is unchanged.
- The returning mark rides the bystander Welcome through `fanOutToOtherMembers`, keeping #1781's
  ordering against `Farewell`.
- New refusal codes (bad proof, key conflict) override `retryable = false` and get `fromId` branches.
  `HostFailed` (retryable) covers the host's own signing or checking failure, and
  `AdmissionPolicyFailed` (retryable) a policy that throws.
  No "keyless Hello" code: the `ProtocolVersion` gate already refuses every older peer.
- `join`/`adopt` gain a `deviceId` parameter; today nothing sends one.
- The host forgets a departed member's key and previous peer id once the last pass it *issued* that
  membership expires (from slice 2), keeping at most 256 such records, oldest out first. A takeover
  writes none (Amendment M). In slice 1 a departed member is forgotten at once, except a superseded id, which
  `SupersededIds` keeps with its key (at most 64) while its successor is seated.

### Configuration and call sites

- `SeamRoomFactory` gains `DeviceKeyStore` (all roles), and `HostAdmission` (policy + revocations;
  Decided 17) on host entry points.
- Thread them through `LoomRoomHost` (and so `NwRoomHost` and `MultipeerRoomHost`), `KtorRoomHost`
  and `ClusterClient`. `ServerCluster` takes a `RoomHost` and builds no factory, so each server's key
  comes from the `KtorRoomHost` it is given. Each cluster server has its own key, so a grant from one
  server is `UntrustedIssuer` at another; `ClusterClient` joins fresh after rotating anyway.

## Consequences

- Breaking: `Member` gains a required field (consumers, `FakeRoom`, `InMemoryRoomFabric`);
  `JoinerReconnectController.tryResume` changes type (consumer hold policies, #1614); `ResumeToken`,
  `Room.resume` and `Room.resumeToken` are removed (Decided 31); factories gain required parameters. Refresh ABI dumps (`updateKotlinAbi`) and say
  which change each records.
- A held seat now also recovers across a process restart, which changes what a hold policy sees.

## Testing

- **The friction bar, in `RoomConformanceSuite`:** each row of the key → seat table, plus "grant
  expired → vetting runs", with the room/factory call count asserted zero between drop and return,
  and the policy's call count asserted (zero for returns, one for the expired case). Include
  "process rebuilt inside the window" and "rebuilt with a fresh peer id".
- **Refusals, each asserting its rig fired:** forged proof; proof
  for another host; Challenge from a co-joiner; grant for another room, another subject, an
  untrusted issuer; expired grant; revoked subject; a bystander's Hello claiming a live member's id;
  a bystander's Hello flood overwriting nothing but its own pending challenge. A proof replayed under
  another nonce is **dropped silently, never refused** (plan Amendment A § 1.4). A relaunch's own
  `join` is the one app call a return row allows (Amendment H).
- Cross-target vectors and properties under `allTests`; virtual time; the grant clock is injected.

## Slices

0. `:kuilt-identity` foundation: `DidKey` (with the WebCrypto compressed-key check first),
   `DeviceKey`, statement layouts, sign/verify, `InMemoryDeviceKeyStore`, golden vectors. Module
   row, `moduleDescription()`, `llms.txt`, primitives-skill route.
1. The exchange and the key → seat table: Challenge/Proof, `Member.key`, `ProvenResume`, the
   `Resume` frame and `ResumeSecret` retired, protocol bump.
2. Grants: `Grant`, `GrantTrust`, `RevocationList`, refresh, returning admission, `AdmissionPolicy`,
   the conformance cases, call-site threading.
3. Platform key stores, one slice each.

Slices 1 and 2 get a spec-conformance review against this document as well as a code review.

## Deferred

- **#2387** — mesh dedup sits below the session Hello; its fix is a proof in `MeshHello` behind a
  verifier interface `:kuilt-core` defines and `:kuilt-identity` implements. Its own spec.
- **`RoomAuthorizer` seeing the key** — same layering, same follow-on.
- A `Pending` frame for slow vetting; channel binding; personas and delegation.

## Left to the builder

- Names: events, verdict and decision types, the superseded `LeaveReason`.
- Field order inside each layout, fixed by its golden vector once chosen.
