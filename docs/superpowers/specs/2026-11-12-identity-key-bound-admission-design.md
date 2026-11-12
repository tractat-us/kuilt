# Identity, first slice: a device that comes back is recognised

Status: **draft for review**, 2026-11-12. Epic #3159. Design record: [session 1](https://github.com/tractat-us/kuilt/issues/3159#issuecomment-6756180564),
[Iain's rulings](https://github.com/tractat-us/kuilt/issues/3159#issuecomment-6836437411), and the session 2
conversation summarised in § Decided.

## The story

Two people are whiteboarding. One of them is on a phone, and iOS kills the app. Ten minutes later
they open it again. Today the room has forgotten them: the 60-second resume window is long gone, so
the phone joins as a stranger and the other person sees someone new arrive.

After this work, the phone simply comes back. Nobody presses anything, nobody is asked anything, and
the other person sees "Iain is back". If they had been away for a day the room would ask to let them
in again — unless the host had marked them a friend, in which case the room never forgets them.

The way it works is small. Every device quietly makes itself a key the first time it runs. When the
host lets someone in, it hands them a signed note: *"this key may come back, to this room, until this
time."* Coming back means showing the note and proving you hold the key. Nothing else changes about
joining.

## Decided

Iain's statements are marked **(Iain)**. Everything else marked *my call* was proposed in the
conversation and accepted by him; anything a reviewer may still change is marked *open*.

1. **Three tiers of return (Iain).**
   - *Under the resume window* (today 60 s): a seamless resume. Same membership; nobody's screen
     changes.
   - *Under the re-admission grant* (default **1 hour**, per room): re-admitted with no vetting, and
     every member is told it is the same device returning, not a new arrival.
   - *After that*: ordinary admission, as if new.
2. **The grant's lifetime is the host's choice, per member (Iain).** A stranger gets the room's
   default; a friend can get no expiry and a wider scope. kuilt checks a grant; it never decides who
   deserves one.
3. **"Friend" means different things to different apps (Iain).** In bee it is the devices and people
   you regularly work with. In fireworks-compose it is a community built over many games and
   networks. kuilt ships the grant and its check; the community graph is the app's and is out of
   scope.
4. **A credential many hold never resumes; one a single device holds does (Iain, Q1).** An invite
   link or room secret can admit; only a device key brings you back.
5. **One P-256 key per device (Iain, Q3).** A persona is an equivalence class of device keys, one
   persona by default — out of scope here. `kuilt-nw`'s room key and #3157's TLS identity stay
   separate from the device key.
6. **fireworks-compose's `deviceId` stays its own id (Iain).** The device key signs over it in the
   Hello proof, which is the binding (tractat-us/fireworks-compose#3862).
7. **New module `:kuilt-identity` (Iain).** `:kuilt-seal` stays sealed boxes only.
8. **Friction is the anti-goal (Iain).** "If joining is awkward and reconnections are a pain" is the
   failure even when everything technically works. So:
   - *Joining asks nothing new* — the key is made silently on first run; no prompt, passphrase or
     account (my call).
   - *Reconnecting asks nothing at all* — kuilt re-dials, proves the key and restores the member.
     The app writes no reconnect code (my call).
   - *The default is the safe path* — an app that does nothing gets key-bound join and return;
     opting out takes code (my call).
   - *The bar is a test* — the conformance suite asserts zero app calls and zero user actions for a
     resume and a re-admission (my call).
9. **Key storage behind a small interface** with platform defaults: Keychain on Apple, Keystore on
   Android, a non-extractable WebCrypto key in IndexedDB on wasmJs, a file on desktop JVM. Secure
   Enclave later without a design change (my call).
10. **No mixed versions (my call).** Pre-1.0: a new joiner and an old host refuse each other through
    the existing `ProtocolVersion` gate, whose reject already reads as "upgrade".

## Goals

- A device that drops and comes back inside its grant is admitted without vetting and announced as
  returning, after any length of process death, with no app code.
- The in-window resume is proved by the device key instead of a host-minted secret, so a resume no
  longer needs a stored bearer secret (closes the cold-start half of #1593).
- Each `Member` carries the verified device key, so an app can recognise a person across
  memberships and peer ids.
- A host can say who may come back and for how long, per member, and can take it back.

## Non-goals

- Personas, the equivalence log, linking a phone to a laptop by QR. Later slices of #3159.
- Delegation beyond one link (capability chains, agents' short-lived capabilities). The grant format
  leaves room for it (§ Grant) but nothing here builds it.
- Decisions by vote, weights, or Raft-committed admission. The host decides.
- Mesh link dedup taking the key into account (#2387) and `RoomAuthorizer` seeing the key. See
  § Deferred.
- f-c's community graph, bee's invite budgets (tractat-us/bee#218), OAuth, SPIFFE.

## Anti-goals

- **Any new user-visible step** on join or on return. A design that needs a "trust this device?"
  prompt, or a reconnect button, has failed regardless of its security properties.
- **A bearer secret on disk** that lets whoever copies the bytes be you. The key stays in the
  platform store; a grant is useless without it.
- **kuilt deciding who is trusted.** It verifies statements; the host's policy decides.

---

The rest of this document is for the builder.

## Shape of the work

```
:kuilt-identity   (new; depends on cryptography-kotlin, kotlinx-serialization, :kuilt-store)
   DeviceKey, DidKey, DeviceKeyStore + platform stores
   Grant (signed), RevocationList
   sign / verify, fixed-layout statement encoding, golden vectors

:kuilt-session    (depends on :kuilt-identity)
   Hello gains the device key; a Challenge/Proof exchange before admission and before resume
   AdmissionPolicy — the host's decision point (new; none exists today)
   Member.key; MembershipEvent for a returning member
   ResumeSecret retired: resume is proved by the key
```

`:kuilt-core` does not change. Its dependency direction is untouched.

## `:kuilt-identity`

### Keys and names

- `DeviceKey` — a P-256 ECDSA key pair, held as a cryptography-kotlin key; its private half never
  leaves the store's control where the platform allows it.
- `DidKey` — the public half named as a `did:key`: multicodec `p256-pub` (`0x1200`, varint
  `0x80 0x24`), the **compressed** 33-byte SEC1 point, base58btc with the `z` multibase prefix, so
  every value starts `did:key:zDn`. A `@JvmInline value class` whose `init` validates the prefix,
  the multicodec and the point length (the fixed-width rule in `.claude/rules/fabrics.md`).
  Parse and format round-trip.
- *Builder check first:* confirm cryptography-kotlin 0.6.0 exports and imports a compressed EC
  public key on all three providers (JDK, CryptoKit, WebCrypto). If one cannot, stop and report —
  do not hand-roll point decompression.

### Key storage

```kotlin
public interface DeviceKeyStore {
    /** This device's key, created on first call and the same on every call after. */
    public suspend fun deviceKey(): DeviceKey
}
```

Defaults per platform, each its own slice: Keychain (Apple), Android Keystore, WebCrypto
non-extractable key persisted in IndexedDB (wasmJs), a `0600` file under the app's data dir (JVM).
`InMemoryDeviceKeyStore` everywhere — a fresh key per process. **Opting out of identity is passing
the in-memory store**: the device still resumes within one process's life (an in-process drop),
and cannot be recognised after a restart. There is no keyless mode.

### Signing and the statement encoding

ECDSA P-256 over SHA-256, signatures in the 64-byte raw `r‖s` form. Signatures are never used as
identifiers, so ECDSA malleability is out of play; say so in KDoc.

Everything signed is encoded in a **fixed byte layout written by hand**, not CBOR — kotlinx CBOR is
not canonical, and a signature over a non-canonical encoding verifies on one target and not another.
Each layout starts with an ASCII domain tag and a version byte, then length-prefixed fields in a
fixed order. Two statements this slice signs:

- **Hello proof**: tag `kuilt/hello-proof`, v1. Fields: room key (or room id), the host's peer id,
  the joiner's peer id, the joiner's `deviceId` (empty when absent), the host's challenge nonce.
- **Grant**: tag `kuilt/grant`, v1. Fields: issuer `DidKey`, subject `DidKey`, scope, `notAfter`
  (epoch ms; `Long.MAX_VALUE` = no expiry), a 16-byte grant id, and a parent grant id (always empty
  this slice — the slot the capability chain will use).

Scope is a sealed type: `Room(roomId)` or `AnyRoomOf(issuer)`. Unknown scope tags fail to decode.

### Grants and revocation

`Grant.issue(issuerKey, subject, scope, notAfter)` and
`Grant.verify(trustedIssuers, subject, room, now): GrantVerdict` — a sealed verdict
(`Valid`, `Expired`, `WrongScope`, `UntrustedIssuer`, `BadSignature`, `Revoked`) so a refusal names
its reason. `verify` is pure given its inputs: every peer holding the same statements gets the same
answer.

A grant is not a secret. It is useless without the subject's private key, so the joiner stores it in
the clear beside its key (`DeviceKeyStore` grows a small grant shelf, or a sibling `GrantStore` — the
builder's call). **The host stores no grants.** A grant comes back with the device that holds it, and
the host checks its own signature. What the host must keep is its own key (already durable, from the
platform store) and a `RevocationList` — grant ids it has taken back — persisted through a
`DurableStore` the host app supplies. A permanent friend therefore survives a host restart for free.

### Golden vectors

The same discipline as the SRA work: committed vectors, checked on every target.

- `did:key` encode/decode against the published P-256 vectors in the W3C did:key test suite.
- Each statement layout: fixed inputs → exact bytes.
- Verify-only signature vectors: a fixed key, message and signature produced once on the JDK, and
  verified on JVM, Android, iOS, macOS and wasmJs. ECDSA signing is randomised, so signing has no
  golden bytes; a per-target sign-then-verify property covers it.

## `:kuilt-session`

### The admission exchange

Today: joiner `Hello` → host `Welcome` (or `Reject`). After:

```
joiner                                host
  Hello(displayName, deviceId, key=DidKey, …)  ─▶
                                      ◀─  Challenge(nonce: 32 random bytes)
  Proof(signature over hello-proof, grant?)    ─▶
                                      verify signature against Hello.key
                                      verify grant, if presented
                                      AdmissionPolicy.decide(…)
                                      ◀─  Welcome(…, grant = new or renewed)   or   Reject(code)
```

The nonce makes a captured proof useless on any other link. It is single-use and expires with the
admit deadline. The proof covers the host's and joiner's peer ids, so it cannot be replayed to a
different host or by a different sender. A Hello without a key is refused with a new terminal
`RejectCode` — every joiner has a key (§ Key storage).

`ProtocolVersion` bumps. An old peer is refused by the existing gate.

### The host's decision point

kuilt has no admission predicate today; every Hello that passes the version and room gates is
admitted. This slice adds one:

```kotlin
public fun interface AdmissionPolicy {
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision
}
```

`AdmissionRequest` carries the verified `DidKey`, the display name and `deviceId`, and the presented
grant's verdict (`Valid`, a refusal reason, or none presented). `AdmissionDecision` is
`Admit(grant: GrantTerms?)` — the terms of the grant to hand back, or none — or `Reject(code)`.

Provided policies:

- `AdmissionPolicy.open(readmitFor = 1.hours)` — admits everyone; issues a room-scoped grant for
  `readmitFor`. Today's behaviour plus recognition.
- `AdmissionPolicy.returningOr(vet)` — a valid grant admits without calling `vet`; otherwise `vet`
  decides. This is the shape bee and f-c plug their vetting into.

The parameter is **required** on `SeamRoomFactory` (the repo's "optional ≠ tuning" rule): a default
would silently decide who gets in. Breaking, accepted pre-1.0.

The policy runs on the host only, outside the room lock, and may suspend.

### Recognition, membership and events

- `Member` gains `key: DidKey` — the verified device key. The existing `principal` stays what it is
  (a transport-attested identity) and is not overloaded.
- A grant-backed admission emits a **distinct membership event** for a returning member, carrying
  the key and the previous peer id if the host still remembers it. Every member's roster sees it, so
  the other person's app can show "Iain is back". The builder names it; semantics are fixed here.
- *Peer id continuity is the fabric's, not kuilt's.* A joiner's peer id comes from its fabric, and
  for most consumers it is already stable per device (`selfId` from the app's `DeviceIdStore`). When
  it is, the returning member has its old peer id back; when it is not, the key is what links the
  two. Apps key a person on `Member.key`, never on peer id. *Open* — this corrects the session 2
  playback, which promised the old peer id back unconditionally.

### Resume

The in-window resume keeps #3165's control flow, with the secret check replaced by the key check:

```
joiner  Resume(tokenPeerId, roomId)  ─▶
host    ◀─  Challenge(nonce)
joiner  Proof(signature)             ─▶   checked against the key the member was admitted with,
                                          BEFORE the window controller is asked anything
host    ◀─  ResumeAck   or   Reject(code)
```

A bystander who knows a member's peer id cannot prove its key, so the #3165 hole stays shut, now
without a secret. `ResumeSecret`, its field on `ResumeToken`, `Welcome.resumeSecret` and
`Resume.resumeSecret` are removed. A persisted `ResumeToken` is no longer a bearer secret; update its
KDoc.

### Configuration

- Resume window: unchanged (`HeartbeatConfig.reconnectWindow`).
- Re-admission lifetime: per grant, chosen by `AdmissionPolicy`; `open()`'s default is one hour.
- The host's trusted issuers: its own key by default. Wider trust (a server issuer, a friend's
  `AnyRoomOf` grant) is a policy input, not a room setting.

## Testing

- **The friction bar is a conformance case**, in `RoomConformanceSuite`, so every room
  implementation inherits it:
  - transport drop and heal inside the window → the member resumes; the test makes zero calls on
    the room or factory between drop and resume;
  - the joiner's room is discarded and a new one built from the same `DeviceKeyStore` after the
    window but inside the grant → admitted, the returning event fires on every member, the
    `AdmissionPolicy`'s vetting arm is **not** called (count it), and the app made no call beyond
    the ordinary `join`;
  - the same after the grant expires → the vetting arm **is** called.
- **Refusals, each its own test and each asserting its rig fired:** a forged proof; a proof replayed
  under a second nonce; a proof made for another host; a grant for another room; a grant signed by
  an untrusted key; an expired grant; a revoked grant; a bystander's resume naming another member.
- **Cross-target:** the golden vectors and the sign/verify property run under `allTests`. A green
  JVM run is not evidence for the other targets (AGENTS.md).
- Virtual time throughout; the grant clock is the injected clock.

## Slices

0. `:kuilt-identity` foundation: `DidKey`, `DeviceKey`, sign/verify, statement layouts,
   `InMemoryDeviceKeyStore`, golden vectors. Module-table row, `moduleDescription()` branch,
   `llms.txt` regen, primitives-skill route.
1. Key-bound admission: Challenge/Proof, `AdmissionPolicy`, `Member.key`, protocol bump.
2. Grants and return: `Grant`, `RevocationList`, the returning event, key-proved resume,
   `ResumeSecret` removed, the conformance cases.
3. Platform key stores, one slice each (JVM file, Apple Keychain, Android Keystore, wasmJs).

Slices 1 and 2 are spec-critical: each gets a spec-conformance review against this document as well
as a code review.

## Deferred

- **#2387 — a mesh link taking a live peer's id.** That dedup happens in `:kuilt-core`'s `Mesh`,
  below the session Hello, so a session-level proof cannot reach it. The fix is a proof carried in
  `MeshHello` behind a verifier interface `:kuilt-core` defines and `:kuilt-identity` implements,
  keeping the dependency arrow out of core. Its own spec.
- **`RoomAuthorizer` seeing the key.** It runs per channel on the server mux, before the session
  layer; same layering question as #2387, same follow-on.
- Personas, the equivalence log, QR linking, capability delegation, agent keys.

## Left to the builder

- Names: the returning event, the grant shelf vs a `GrantStore`, the verdict and decision types.
- Where the challenge nonce lives between Challenge and Proof, and how it times out (bounded by the
  admit deadline).
- The field order inside each layout, fixed by its golden vector once chosen.
