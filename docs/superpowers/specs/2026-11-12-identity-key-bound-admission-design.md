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
   - *The seat is still held* (today's 60 s resume window): the member is restored as the same
     membership, whether the link healed or the process died and restarted. Nobody's screen changes.
   - *Away less than the re-admission period* (default **1 hour since last contact**, per room):
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
    and a revoked device's held-seat restore falls through to ordinary admission.
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
    only through `fanOutToOtherMembers`, which excludes its subject.
20. **The resume-only reject codes go with the `Resume` frame (Iain, 2026-11-12, slice 1–2 audit).**
    `ResumeTokenInvalid`, `ResumeWindowNotYetOpen` and `ResumeWindowExpired` are removed when
    `Resume` is retired, together with their readers.
21. **The store keeps the newest grant per issuer and scope, not per issuer (controller ruling,
    2026-11-13, #3253's review; for Iain to confirm).** One host key serves many rooms, so
    `Room(A)` and `Room(B)` from one issuer are both kept; keyed by issuer alone, B's refresh evicts
    A's and the device loses its pass back into A — Decided 1's middle tier. A tie on `issuedAt` keeps
    the held grant, so a replayed or reordered copy cannot displace it.
22. **Statement text is strict UTF-8 on encode as well as decode (controller ruling, 2026-11-13,
    #3252's review).** An unpaired surrogate is refused with `IllegalArgumentException` rather than
    replaced (`?` on the JVM, U+FFFD elsewhere), so a statement never signs a value other than the one
    it was given, and the signed bytes are the same on every target.
23. **Where two rows meet, the claimed id decides, and a released seat is decided again (controller
    ruling, 2026-11-13, #3251's review; confirmed by Iain 2026-11-13).** When the claimed id holds a seat under
    a *different* key while the proven key holds *another* seat, the verdict is the terminal key
    conflict whatever either seat's liveness: releasing the id's seat would leave one key holding two
    seats. When Decided 18 releases a partitioned seat, the joiner's key is then decided as if that
    seat were never held: `Returning` with a valid grant, else ordinary. The same "decide again
    without the seat" applies to a revoked restore (Decided 14) and to a restore whose window refuses
    it. A host's own seat is never in the table; a joiner presenting the host's key is #3257.
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
29. **The Android store tells a failing Keystore from a damaged key by the cause; below API 33 it counts
    (Iain, 2026-11-13, #3306's review).** The Keystore's JCA surface answers a passing failure as
    "absent" (`containsAlias` false, `getCertificate` null), so the store reads with `getKey` first and
    treats a null certificate beside a key as a fault, never as "half-made", on API 31+. On API 33+ a
    load or sign failure is judged by its `android.security.KeyStoreException` cause: transient
    (`isTransientFailure()`) throws and keeps the key; a permanent code (e.g. `INVALID_KEY_BLOB`), or an
    `UnrecoverableKeyException` with no `KeyStoreException` cause, replaces it. Below API 33, where no
    cause separates the two, a failure count kept beside the grants replaces the key after 3
    consecutive failed launches. A replaced key is a new device returning through ordinary admission.

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
  cannot resume as, return as, or lock out another member. A captured proof cannot be replayed.
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
    /** The grants this device holds, by issuer. Not secret — useless without the key. */
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
                                       ◀─  Welcome(…, grant)   or   Reject(code)
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
  the proof binds the room and the joiner picks grants by issuer.

### The key → seat table

The host keeps, for each member, its key. On a verified Proof:

| Peer id | Key | Seat | Outcome |
|---|---|---|---|
| same | same | held (partitioned or still connected) | **Restored**: the same membership, `MembershipEvent.Resumed`; a still-connected seat's old link is superseded |
| new | same | held | **Restored under the new id**: the old member leaves with a dedicated `LeaveReason` (superseded), the new one inherits the membership; one event pair, ordered through `fanOutToOtherMembers` |
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

A grant is issued in the Welcome and **re-issued every `readmitFor / 2`** on the member's direct
lane while it stays connected, so "one hour" means one hour since last contact. The joiner keeps the
newest grant per issuer **and scope** in its `DeviceKeyStore` (Decided 21).

### The host's decision point

kuilt has no admission predicate today. This slice adds one, required on **host** entry points only
(joiners never decide):

```kotlin
public fun interface AdmissionPolicy {
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision
}
```

`AdmissionRequest`: the verified `DidKey` (an `internal` constructor, like `ProvenResume`), display
name, `deviceId`. `AdmissionDecision`: `Admit(grant: GrantTerms)` or `Reject(code)`. The room
consults the key → seat table first; the policy sees only ordinary admissions.

Provided: `AdmissionPolicy.open(readmitFor = 1.hours)` — admits everyone with a room-scoped grant.

The policy must answer within the joiner's `admitTimeout` (30 s). A slow human-in-the-loop vet
needs a `Pending` frame that extends the joiner's deadline — not in this slice.

### Membership and the wire

- `Member.key: DidKey`, non-null. Carried on all three Welcome shapes (direct, bootstrap, host intro).
- The returning mark rides the bystander Welcome through `fanOutToOtherMembers`, keeping #1781's
  ordering against `Farewell`.
- New refusal codes (bad proof, key conflict) override `retryable = false` and get `fromId` branches.
  No "keyless Hello" code: the `ProtocolVersion` gate already refuses every older peer.
- `join`/`adopt` gain a `deviceId` parameter; today nothing sends one.
- The host forgets a departed member's key and previous peer id once its last grant expires.

### Configuration and call sites

- `SeamRoomFactory` gains `DeviceKeyStore` (all roles), and `HostAdmission` (policy + revocations;
  Decided 17) on host entry points.
- Thread them through `LoomRoomHost` (and so `NwRoomHost` and `MultipeerRoomHost`), `KtorRoomHost`
  and `ClusterClient`. `ServerCluster` takes a `RoomHost` and builds no factory, so each server's key
  comes from the `KtorRoomHost` it is given. Each cluster server has its own key, so a grant from one
  server is `UntrustedIssuer` at another; `ClusterClient` joins fresh after rotating anyway.

## Consequences

- Breaking: `Member` gains a required field (consumers, `FakeRoom`, `InMemoryRoomFabric`);
  `JoinerReconnectController.tryResume` changes type (consumer hold policies, #1614); `ResumeToken`
  loses `secret`; factories gain required parameters. Refresh ABI dumps (`updateKotlinAbi`) and say
  which change each records.
- A held seat now also recovers across a process restart, which changes what a hold policy sees.

## Testing

- **The friction bar, in `RoomConformanceSuite`:** each row of the key → seat table, plus "grant
  expired → vetting runs", with the room/factory call count asserted zero between drop and return,
  and the policy's call count asserted (zero for returns, one for the expired case). Include
  "process rebuilt inside the window" and "rebuilt with a fresh peer id".
- **Refusals, each asserting its rig fired:** forged proof; proof replayed under another nonce; proof
  for another host; Challenge from a co-joiner; grant for another room, another subject, an
  untrusted issuer; expired grant; revoked subject; a bystander's Hello claiming a live member's id;
  a bystander's Hello flood overwriting nothing but its own pending challenge.
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
