# Identity, first slice: key-bound admission and return. Implementation Plan

> **For agentic workers:** execute task by task under `worktree-dispatch`, one fresh worker per task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A device that drops and comes back is restored to its held seat, or re-admitted without vetting inside a host-issued grant, and the app writes no reconnect code (#3159, #1593's mechanism).

**Architecture:** A new module, `:kuilt-identity`, holds one P-256 key per device (`DeviceKey`, named by a `DidKey`), the signed statement layouts (Challenge, Admission proof, Grant), grant trust and revocation, and the per-platform key stores. `:kuilt-session` replaces `Hello → Welcome` and the `Resume` frame with one exchange, `Hello → Challenge → Proof → Welcome/Reject`. On every verified Proof the host consults a key-to-seat table, and an `AdmissionPolicy` sees only ordinary admissions. `:kuilt-core` does not change.

**Tech Stack:** Kotlin Multiplatform (JVM, Android, iOS, macOS, wasmJs). cryptography-kotlin 0.6.0 (JDK, CryptoKit and WebCrypto providers, as in `:kuilt-seal`). kotlinx-serialization (CBOR on the admit wire). `:kuilt-store` for durable revocations and grants. `:kuilt-test` (`assertAll`).

**Spec:** `docs/superpowers/specs/2026-11-12-identity-key-bound-admission-design.md`, approved by Iain on 2026-11-12. Decided 1–23 there are binding. The calls Iain approved alongside the spec are listed under *Approved with the spec* below.

## Approved with the spec (binding, from the dispatch arguments)

1. `DeviceKeyStore` is a **required** parameter, with a one-line platform default, `DeviceKeyStore.platform(appId)`. A caller opts out by passing `InMemoryDeviceKeyStore()` explicitly.
2. The re-admission hour counts **from last contact**. The host re-issues a member's grant every `readmitFor / 2`.
3. One Hello exchange covers every return. `ResumeSecret` and the `Resume` frame are retired.
4. The host proves its own key in the signed Challenge. The keys of other members are **host-asserted**.
5. The threat model excludes an active relay on an unauthenticated fabric. The channel-binding field is reserved and stays empty.
6. Revocation is by subject key plus a `notBefore` cutoff.
7. A room-scoped grant survives a host restart only when the app supplies a stable `roomId`. An `AnyRoomOfIssuer` grant survives a restart.

## Rulings (Iain, 2026-11-12 — recorded as the spec's Decided 13–15)

- **R1 → (a), amended.** `DidKey.init` checks syntax only (`did:key:zDn` prefix, base58btc, multicodec `0x80 0x24`, 33 bytes, leading `0x02`/`0x03`). `suspend fun DidKey.parse(value)` also decodes the point. **Amended after #3201 (PR #3228):** cryptography-kotlin 0.6.0's JDK `decodePoint` computes `y = rhs^((p+1)/4)` and never checks `y² = rhs` or `x < p`, so an off-curve x decodes on JVM/host-Android. Iain ruled: `parse` and `verify` run an explicit on-curve check. Task 0.3 is written for this.
- **R2 → both rules.** (i) The host stops re-issuing grants to a member whose subject was revoked after that member's current admission. (ii) For a revoked subject, a held-seat restore (tier 1) falls through to ordinary admission.
- **R3 → `DeviceKeyStore.platform(appId, context)`** in `androidMain`, grants under `context.noBackupFilesDir`. Other targets keep `platform(appId)`; JVM takes a directory.

## Plan decisions

- **`GrantTerms.readmitFor` ≥ 1 ms** (not the earlier `> 0`; #3270): a grant is dated in whole
  milliseconds and `Grant` requires `notAfter > issuedAt`, so a sub-millisecond term could not be issued.
  A part millisecond is dropped, never rounded up. 2.5/2.6 rebuilding terms from `notAfter − issuedAt` see ≥ 1.

The spec leaves these to the builder. Each one is fixed by a golden vector or a test once it lands. Reviewers may challenge them. Workers do not change them.

- **The statement layout.**
  - Header: a `u16` big-endian length, then the ASCII tag, then one version byte (`0x01`). Each field is a `u16` big-endian length followed by its bytes.
  - A `DidKey` in a layout is its 33-byte compressed point. Times are 8-byte big-endian epoch milliseconds. Strings are UTF-8.
  - Fixed widths: both nonces are **16 bytes**, and so is the grant id. Each statement type's `init` enforces them.
  - Scope is one tag byte: `0x01` followed by the room id's UTF-8 for `Room`, or `0x02` alone for `AnyRoomOfIssuer`.
  - The reserved fields must be empty to decode: channel binding, and the parent grant id.
  - Fields appear in the order of the spec's table.
- **`DeviceKey` is an interface (`id` + `suspend fun sign`), not a cryptography-kotlin key pair.** Decided 9 forces this. A non-extractable WebCrypto key, an Android Keystore key and a later Secure Enclave key all sign without exposing their bytes. The software key behind `InMemoryDeviceKeyStore` and the file/Keychain stores is one internal implementation. No cryptography-kotlin type appears in the public surface, as in `:kuilt-seal`.
- **`Grant` lands in slice 0.** `DeviceKeyStore`'s interface names it. `GrantTrust`, `RevocationList` and everything that issues grants stay in slice 2.
- **Statements that only the session protocol needs** (`Challenge`, `AdmissionProof`) are `public` under `@IdentityInternalApi` (`@RequiresOptIn(level = ERROR)`). `:kuilt-session` opts in through `compilerOptions.optIn`. `Grant`, `DidKey`, `DeviceKey`, the stores, `GrantTrust` and `RevocationList` are public.
- **Every PR that changes `AdmitMessage`, or what a peer must send to be admitted, bumps the protocol.** `ProtocolVersion.CURRENT`, `MIN_SUPPORTED` and `MAX_SUPPORTED` all move up by one (3, 4, …). Snapshot consumers iterate on `-dev` builds, and Decided 10 rules out mixed versions.
- **A returning member's re-issued grant keeps the terms it presented:** the same scope, and the same lifetime (`notAfter − issuedAt`, or no expiry). The spec forces this. The policy sees only ordinary admissions, and Decided 2 gives a friend no expiry, which the room default would wipe out.
- **`revoke` is monotone.** A later call keeps `max(old, new)` as the cutoff, so a clock that moves backwards cannot un-revoke anyone.
- **`GrantTrust.verify` checks in a fixed order:** `UntrustedIssuer` → `BadSignature` → `WrongSubject` → `WrongScope` → `Revoked` (`issuedAt < notBefore`) → `Expired` (`now >= notAfter`) → `Valid`.
- **Names.**
  - Frames: `AdmitMessage.Challenge`, `AdmitMessage.Proof`, `AdmitMessage.GrantRenewal`.
  - Welcome marks: `ReturnKind { Restored, Returning }` on the Welcome. `Farewell.supersededBy`.
  - Session types: `LeaveReason.Superseded(by: PeerId)`, `MembershipEvent.Joined(member, returning: ReturningMark? = null)`, `ReturningMark(previousId: PeerId?)`.
  - Reject codes: `RejectCode.BadProof` (`"bad-proof"`), `RejectCode.KeyConflict` (`"key-conflict"`), `RejectCode.AdmissionPolicyFailed` (`"admission-policy-failed"`, retryable).
  - Policy types: `HostAdmission(policy, revocations)` (Decided 17), `GrantTerms(readmitFor, reach)`, `GrantReach { ThisRoom, AnyRoomOfIssuer }`.
  - `AdoptRole.Host(admission, roomId)` / `AdoptRole.Joiner` replaces `adopt(role: SessionRole, roomId)`. This makes "host entry points only" a type rather than a `require`.

## Amendment A — the slice 1–2 audit (2026-11-12, before any slice-1 task started)

A read-only pass checked Tasks 1.1–2.7 against `origin/main` at `492cdab7d`. Each task below carries a
pointer here; **the bullets override the task text where they differ.** Full findings, with file:line:
`slice12-audit-2026-11-12.md` in the epic worktree (uncommitted notes). Rulings: spec Decided 17–20.

**Shared files.** 1.2, 2.1, 2.3 and every 1.x task share `kuilt-session/api/*`, `SKILL.md` (and its
8,192-byte body budget), `docs/agent-cookbook/session.md` and `llms.txt`. They rebase on each other;
they are not independent. 1.2 adds `implementation(project(":kuilt-identity"))` to `:kuilt-session`;
1.1 promotes it to `api`.

**Graph.** 2.3 is after **2.1** (and after 0.6 and the session → identity edge), not after 0.7 alone.
Split option: policy types after 0.6; `HostAdmission` after 2.1.

**Lands early, additively, on main:** `admit/PendingChallenges.kt` (its own file, not
`KeyAdmission.kt`) with `PendingChallengesTest` — needs only today's `Hello`, `PeerId` and a clock;
give each entry a **generation** (`take` returns `Pending(gen)`, plus `isCurrent(sender, gen)`) for
2.4's stale-decision discard; expire lazily against `clock` and sweep on `open`, no timer job.

- **1.1**
  - Also thread: `LoomRoomHost` (`RoomHost.kt`), `NwRoomHost`, `MultipeerRoomHost` (+ their tests,
    `:kuilt-multipeer` dumps and an `api(project(":kuilt-identity"))` edge there), both `KtorRoomHost`
    constructors, `examples/src/test/**`, all four `RoomFactory` implementers (`SeamRoomFactory`,
    `FakeRoomFactory`, two anonymous objects in `ReferenceResumeHarness`).
  - **Drop `ServerCluster.kt`** and the `(PeerId) -> DeviceKeyStore` idea: it takes a `RoomHost`.
    Multi-server tests pass distinct `InMemoryDeviceKeyStore()`s per `KtorRoomHost`.
  - Count, measured by the 1.1 worker (#3258): **240 sites in 89 files**, including 62 direct internal
    `SeamRoom(` constructions. `:kuilt-nw` also needs the `api(project(":kuilt-identity"))` edge.
  - Docs with constructor snippets: `docs/usage.md`, `Writerside/topics/fabrics.md`,
    `Writerside/topics/cluster-server-client.md`, `kuilt-cluster/module.md`, `kuilt-test/README.md`.
  - `spike/` (`ConnectivitySuite.kt`): fix it, or say in the PR it is knowingly left.
  - Run `updateKotlinAbi` and record whatever moves (`:kuilt-game` likely does not; multipeer does).
- **1.2** (from #3251's review; fixed in 1.2 itself, before merge)
  - `Seat` carries liveness (connected / partitioned). `KeyConflict` only against a connected seat
    (Decided 18); a partitioned same-id/different-key seat gives a release-and-admit verdict that
    carries whether the key then holds a valid grant (Decided 23).
  - The id-holds-a-seat-under-another-key ∧ key-holds-another-seat overlap is `KeyConflict` whatever
    the liveness (Decided 23).
  - **No throwing `require`s.** Two seats per key is reachable (two tabs both decided `Ordinary`
    before either is added), and a throw on the Proof path aborts a Kotlin/Native host. Make id
    uniqueness structural (`Map<PeerId, Seat>`); key uniqueness is the admission site's job (1.5/2.4).
  - KDoc: `held` is joiner seats only, never the host's; revocation is the caller's; "fall through"
    means calling `decideSeat` again without the released seat, not a hardcoded `Ordinary`.
  - AGENTS.md: drop the "(today only the internal key → seat decision reads them)" parenthetical.
- **1.4–1.6: the split must name two shared-device harnesses in published/common code (from #3258's
  review).** `RoomConformanceSuite.defaultHarness` (`kuilt-conformance` **commonMain**, ~line 2179:
  `hostFactory = factory, joinerFactory = factory`, one store) and `ReferenceResumeHarness`
  (`kuilt-conformance` commonTest). From 1.5's host-key check, every default-harness TCK row reds at
  once. Build two factories, and KDoc `RoomHarness.hostFactory/joinerFactory` as distinct devices.
  The opposite case, `examples` `ResumeTokenFailoverTest` (one client device modelled as two stores),
  is rewritten when 1.7–1.8 retire `Resume`.
- **1.4–1.6: split shared factories in tests (from #3258).** About 24 test files build one
  `SeamRoomFactory` and use it for the host and joiners, or for several joiners, so those rooms
  share one `InMemoryDeviceKeyStore` and are **one device**. Harmless until the key → seat table
  reads keys; from 1.5 they will restore or supersede each other. Each of 1.4–1.6 gives every
  logical device in its tests its own store (or factory), and its brief says so.
- **1.5 / 2.4 obligation (from #3251's review):** re-run `decideSeat` under the room lock immediately
  before `addToRoster`, *after* any suspension (the policy in 2.4). If the key gained a seat in the
  meantime, supersede rather than add a second seat. A failed `tryResume` decides again without the
  seat (Decided 23) rather than falling through to `Ordinary`.
- **1.3**
  - **`Hello.key` and `Hello.joinerNonce` are nullable on the wire** (`= null`). Non-null fields make a
    v2 Hello fail CBOR decode, which `AdmitMessage.decode` turns into a silent drop, so the version
    gate never fires (`HelloVersionWireCompatTest` pins that hazard). A v3+ Hello missing either is
    refused (`ProtocolMismatch` or `BadProof`).
  - Bootstrap Welcomes need each member's key, which the host does not store until `Member.key`:
    **move `Member.key` into 1.3** (or fill only direct/host-intro keys here and bootstrap keys in 1.4).
  - Every admit DTO with a `ByteArray` gets content-based `equals`/`hashCode` (the `RoomFrame`
    precedent); round-trip tests compare with `assertEquals`.
  - `handleAdmitFrame`'s `when` is exhaustive: `Challenge`/`Proof` need arms in `SeamRoom.kt`.
  - The test file is `admit/AdmitMessageTest.kt`. Also update `HelloVersionWireCompatTest`, the golden
    Hello vectors in `AdmitMessageTest`, and the 10 test files with raw `Hello(`/`Welcome(`.
  - **Every version bump edits `admit/ProtocolVersionTest.kt`** (1.3, 1.4, 1.5, 1.6, 1.8, 2.5, 2.6).
  - Nonces from `org.kotlincrypto.random.CryptoRand`, which session already uses; keep that
    dependency when 1.8 deletes `ResumeSecret`.
- **1.4**
  - `PendingChallenges` has already landed (above); 1.4 wires it.
  - From #3264's review: `take` returns null for never-opened, expired **and already-taken**. A
    duplicate Proof for a challenge whose decision is in flight must be **dropped silently**, not
    answered with `Reject(BadProof)` (terminal), or a joiner the host is about to admit is rejected.
  - Expiry is lazy against the room clock: the timeout test must tie that clock to
    `testScheduler.currentTime`. A frozen `Instant.fromEpochMilliseconds(0)` makes `advanceTimeBy`
    expire nothing, and the test passes without exercising expiry.
  - A pending sender is not an admitted member, so `handlePeerLost` never fires for it. Drop pending
    entries for senders that leave `seam.peers`, or rely on the `admitTimeout` sweep.
  - **A `Goodbye` from the sender drops its pending entry** (needed by
    `seatIsReReadAtProofTimeNotChallengeTime`), or that test moves to 1.5.
  - Joiner: gate the `hostPeerId` capture on the first Welcome **from a sender whose Challenge it
    verified** — today the first Welcome of any shape from anyone sets it.
  - `Member(` sites: `FakeRoomFactory.kt` (`seedRoster`), `FakeRoomTest.kt`'s helper,
    `kuilt-session-test/README.md`, `kuilt-cluster` jvmTest `ServerClusterSim.kt`. Not `FakeRoom.kt`.
    Non-suspend helpers become suspend (keys come from `DidKey.parse` / `DeviceKey.generate()`).
  - Session opts in to `@IdentityInternalApi` via `compilerOptions.optIn`.
- **1.5**
  - From #3251's re-review: the host-key check (compare `provenKey` to the room's own store key, under
    the lock, before `decideSeat`) must land **with** the 1.4–1.6 factory split — ~24 test files share
    one `SeamRoomFactory` (one key store) between host and joiners, so every such joiner presents the
    host's key; the check alone reds them, an incomplete split leaves them silently testing #3257's
    case as `Ordinary`. Pin it with a room-level test. Check revocation per key before trusting any
    restore verdict (a re-decide can otherwise restore a revoked key's *other* seat).
  - From #3251 (1.2 as merged): `held` never contains the host's own seat, so a joiner presenting the
    **host's key** is decided `Returning`/`Ordinary`. 1.5 must catch that case before `decideSeat`
    (answer depends on #3257, Iain's), or that joiner is admitted as a stranger. `decideSeat` never
    throws: a key holding two seats yields `Restore` for the claimed one, else `RestoreUnderNewId` on
    the lowest `PeerId.value`; keeping one seat per key is the admission site's job.
  - `handleResume` also constructs `ProvenResume` (after its secret check) until 1.8 retires it.
  - A successful `tryResume` emits `Resumed`, fans out `Unpaused` and sends `ResumeAck`. On the Proof
    path: **no Welcome/Farewell fan-out, but the existing `Unpaused` still goes**; say which path sends
    `ResumeAck` (1.8 deletes it).
  - A connected-seat Restore skips `tryResume` (it would return `WindowNotYetOpen`).
  - `healedLinkSameIdRestoresWithResumed` would pass through the old `Resume` path until 1.7: drive
    the re-Hello with raw frames here, or move the test to 1.7.
  - Same id + different key: `KeyConflict` only against a **connected** seat (Decided 18).
  - `tryResume` users to update: see the audit list. `RoomId` lives in `partition/ResumeToken.kt`.
- **1.6**
  - A **connected** old seat under a new id is superseded directly, without `tryResume`
    (`supersededJoinerDoesNotReHello` has a connected seat).
  - Send `Farewell(previous, supersededBy)` **directly to `previous`** before removing it, plus the
    bystander fan-out (Decided 19).
  - The partitioned-seat path must not emit a stray `Resumed(previous)`/`ResumeAck` (it races the
    `Left`): use a different controller call, or skip already-removed peers.
  - New joiner arm for a self-`Farewell`: emit `Left(selfId, Superseded(by))` and close (Decided 19).
  - `SeamRoom.leave`'s `when` over `LeaveReason` needs a `Superseded` arm.
- **1.7**
  - **Move the `ResumeHarness` reshape (`RoomConformanceSuite`, `ReferenceResumeHarness`,
    `RoomResumeHarnessTest`, `RoomConformanceGapDeclarationTest`) here from 1.9**: after 1.7 the joiner
    sends no `Resume`, so the suite's `resumeRequests()` counts go red. Public conformance ABI change.
  - Re-Hello with `sendTo(hostPeer)`, not broadcast (a broadcast is admitted by foreign hosts on a
    flat loom).
  - Tests to change: `ResumeTokenFailoverTest` (examples), `RoomResumeTest`, `RoomResumeTimeoutTest`,
    `ConcurrentResumeHangTest`, `DrainingGenerationReconnectTest`, and in `partition/`
    `ResumeFlightOwnershipTest`, `ThrowawaySeamCloseCancellationTest`, `SubTimeoutBlipResumeTest`,
    `ResumeRefusalReportingTest`. There is no `JoinerResumeMachine*` test file.
  - Remove the now-unreachable `WindowNotYetOpen` dwell/retry in `runReconnect`.
- **1.8**
  - Remove `ResumeTokenInvalid`, `ResumeWindowNotYetOpen`, `ResumeWindowExpired` (Decided 20) and
    their readers: `AgentCookbookSamples.classifyRejectCodeSample` (quoted verbatim in
    `session.md`), `ClusterClient` KDoc, `EndpointRotationTest`, `ResumeTokenFailoverTest`.
  - `ResumeToken.secret` users: `EndpointRotationTest`, `FakeRoomTest`, `ServerClusterReconnectTest`,
    `TransportCloseWindowTest`, `ResumeSecretTest`, `JoinerResumeMachine.mintTokenIfAbsent`, and
    `spike/.../ResumeLaneProbe.kt` (the "expect no hits" grep will hit spike).
  - `ResumeToken` is `@Serializable` and apps persist it: say so in the PR body.
- **1.9** — the harness reshape is already done in 1.7; the rest stands.
- **2.3** — after 2.1 (graph above). `AdmissionRequest.key: DidKey` needs the `api` edge.
- **2.4**
  - From #3270's review: build `AdmissionRequest` only from the `SeatDecision` ordinary arm (an internal
    factory taking the verdict and the verified proof), so calling the policy on a restore is a type
    error rather than a counting test. `AdmissionProof` signs a null `deviceId` as `""`: say so where
    the request is built (a joiner sending `""` and one sending nothing sign the same bytes).
  - Remove 2.3's "No room … yet; tracked by #3220" lines (KDoc and `session.md`) and the present-tense
    `HostAdmission` KDoc claims that only become true here.
  - Retire a decided challenge with `PendingChallenges.retire(sender, generation)` (compare-and-remove,
    added in #3264), never `isCurrent` + `drop`: the decision completes on the `admitPeer` coroutine,
    so a re-Hello's `open` can land between the two and the `drop` deletes the newer challenge.
    `drop(sender)` stays for Goodbye and peer loss.
  - `HostAdmission(policy, revocations)`; the room builds `GrantTrust` from its own store key
    (Decided 17). Tests no longer need a suspend `store.deviceKey().id` to build the trust.
  - Same missed sites as 1.1 (`LoomRoomHost`, `NwRoomHost`, `MultipeerRoomHost`, `:examples`); drop
    `ServerCluster`. `adopt(…, SessionRole…)` callers: `SeamElectionLobby.adoptAfterCommit`, ~20
    session test files, `kuilt-game`'s `RoomGameHarness`, `kuilt-nw`'s `NwMeshRoomPartitionTest`, spike.
  - The stale-decision discard uses `PendingChallenges`' generation.
- **2.5** — `Welcome.grant` and `GrantRenewal` carry `ByteArray`: content-based `equals`/`hashCode`.
  - **Refresh period**: compute `(notAfter − issuedAt) / 2` as a `Duration` or clamp it to ≥ 1 ms.
    `GrantTerms` admits a 1 ms term (2.3's floor), so `Long` `1 / 2 = 0` would spin a `delay(0)` refresh
    loop signing a grant per member — and freeze virtual time under `runTest`.
  - **Custom reject codes** (from #3270's review): the wire `Reject` carries only `code.id`, so an app's
    own `RejectCode` arrives as `Unknown(id)`, retryable. If 2.5's protocol bump carries `retryable`, see
    the issue filed for it; otherwise 1.7's re-Hello retries a refused returning device to its deadline.

## Global Constraints

- **Each task is one PR and one sub-issue of #3159.**
  - Dispatch it to an opus worker with `isolation: "worktree"` and the `kuilt-worker-contract` skill loaded.
  - The worker claims the task with a Draft PR whose body says `Part of #<sub-issue>`. Issues are closed by hand, since auto-close is off.
  - An opus `code-reviewer` reviews every PR.
  - **In slices 1 and 2, every PR also gets a spec-conformance review** against the spec sections named in its task. Each slice closes with a whole-slice spec-conformance pass.
- `explicitApi()` applies. No `!!`. No bare `runCatching` in `suspend` code: use `runCatchingCancellable`, or `catch` + `currentCoroutineContext().ensureActive()`. Test names carry no `test` prefix. Multi-assert tests use `us.tractat.kuilt.test.assertAll`.
- A scope or dispatcher is a **required** parameter, never defaulted to a real dispatcher. Shared mutable state is guarded by atomicfu locks or atomics, never by `limitedParallelism(1)`. Long-lived collectors use `Flow.pumpIn`. Time comes from the injected `clock`, never a wall clock. Tests use virtual time.
- **A public API change runs `./gradlew updateKotlinAbi`.** Commit the `api/` diff, and have the PR body name each change it records. `:kuilt-nw` is a cinterop module, so its dumps refresh only on macOS.
- **The new module needs, in Task 0.1:**
  - `include(":kuilt-identity")` in `settings.gradle.kts`.
  - A `:kuilt-identity` row in the `AGENTS.md` module table.
  - A `"kuilt-identity" ->` branch in `moduleDescription()` in `build-logic/src/main/kotlin/kuilt.publish.gradle.kts`.
  - `kuilt-identity/module.md`.
  - `kuilt-identity/**` added to `paths:` in `.claude/rules/session.md`.
  - Then run `python3 .github/scripts/llms-txt.py` and commit what it writes.
- **The skill and cookbook move in the same PR as each public primitive.**
  - `.claude/skills/kuilt-primitives/SKILL.md`'s body is at **8,178 of 8,192 bytes**. The `description:` is already full at the 1,536-character cap.
  - **Do not touch `description:`.** "rejoin/reconnect/resume after a drop" already routes this family.
  - Add **one** identity row and extend it. Trim the body by the bytes you add.
  - The cookbook gets one section, `## Device identity & return`, in `docs/agent-cookbook/session.md`, plus its row in `docs/agent-cookbook.md`. Snippets are `<!-- verbatim from … -->` quotes of compiled samples.
  - Run `./gradlew verifySkillBodyBudget verifySkillDescriptionBudget verifyDocCitations verifySampleLinks verifySamplesAreRun`, and `python3 .github/scripts/llms-txt.py --check`.
- **Golden and signature vectors run under `:kuilt-identity:allTests` and `:kuilt-identity:testDebugUnitTest`**, which cover JVM, Android, iOS sim, macOS and wasmJs. A vector arm asserts that its negative control fails: a flipped bit, a wrong key.
- Gradle runs as `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-tem`, under `timeout 600`, with `--max-workers` per the worker contract.
- **Before merging a module-local PR:** `./gradlew clean && timeout 600 ./gradlew :<module>:build --rerun-tasks`, and confirm the tasks show EXECUTED.
- **Before merging a PR that changes session behaviour** (Tasks 1.4–1.8 and 2.4–2.6): `./gradlew clean && ./gradlew build --rerun-tasks`, which includes `:examples:test` and the `:kuilt-cluster` E2E tests. A module-scoped green is not enough.
- Commit messages are plain sentences, never "chore", and end with the Co-Authored-By line. No release tag until heddle lands. Snapshots are fine.

## Review Focus

1. **Two processes of one device in the same room**, such as two browser tabs sharing one IndexedDB key, or the app opened twice. Under the table, each Hello supersedes the other's seat, and the reconnect loops can ping-pong forever. A user expects one membership and no loop. **Pinned by `supersededJoinerDoesNotReHello` (Task 1.6):** a joiner that receives its own `Farewell(supersededBy = …)` ends the room with `LeaveReason.Superseded` and sends no further Hello.
2. **A policy that throws or stalls.** A user expects the joiner to get an answer and the room to keep running. On Kotlin/Native an escaped throw kills the process. **Pinned by `throwingPolicyRejectsRetryablyAndRoomSurvives` and `slowPolicyTimesOutWithoutWedgingPending` (Task 2.4).**
3. **A Hello retried while the first one is still being decided.** A user expects exactly one admission. **Pinned by `retriedHelloDuringDecisionAdmitsOnce` (Task 2.4):** the Proof for the overwritten challenge is refused, and one `Joined` fires.
4. **A key that leaves the device**, through iCloud Keychain sync or a backup restore. That breaks Decided 4, since many devices would hold one key. A user expects the key to stay on its device. **Pinned by `keychainItemIsThisDeviceOnlyAndUnsynced` (Task 3.2), `keystoreSpecIsSignOnlyAndDeviceBound` (Task 3.3) and `webCryptoKeyIsNonExtractable` (Task 3.4).**
5. **A cleared or corrupt key store**: a truncated key file, a deleted Keychain item, cleared site data. A user expects the app to keep working as a new device, with no crash and no prompt. **Pinned by `corruptKeyIsReplacedNotFatal` in `DeviceKeyStoreConformanceSuite` (Task 3.1),** which every platform store inherits.

## Dependency graph

```
0.1 ─gate G0─┬─ 0.3 ─┬─ 0.5 ─┐
             ├─ 0.4 ─┤       ├─ 0.7 ─ 1.1 ─ 1.3 ─ 1.4 ─ 1.5 ─ 1.6 ─ 1.7 ─ 1.8 ─ 1.9 ─[slice-1 review]
0.1 ─ 0.2 ───┘       └─ 0.6 ─┘          0.3 ─ 1.2 ──────────┘
0.6 ─ 2.1 ─ 2.2      0.7 ─ 2.3 ──┐
           1.9 + 2.1 + 2.3 ─ 2.4 ─ 2.5 ─ 2.6 ─ 2.7 ─[slice-2 review]   (2.2 before 2.7)
0.7 ─ 3.1 ─┬─ 3.2   ├─ 3.3   └─ 3.4                    (slice 3 runs beside slices 1–2)
```

Tasks 1.1–1.8 all edit `SeamRoom.kt`, so they run one at a time. Several slices may run at once: 1.2, 2.1–2.3 and slice 3 touch no file a slice-1 task touches.

---

## Slice 0: `:kuilt-identity` foundation

All code goes under `kuilt-identity/src/<set>/kotlin/us/tractat/kuilt/identity/`, in package `us.tractat.kuilt.identity`.

### Task 0.1: Module skeleton and the WebCrypto compressed-point probe [gate G0]

**Files:**
- Create: `kuilt-identity/build.gradle.kts` and `kuilt-identity/module.md`.
- Create in `commonMain`: `PlatformProvider.kt` (internal `expect fun platformProvider()`, `algorithms()` self-test, `providerCall`) and `IdentityInternalApi.kt`.
- Create the actuals: `PlatformProvider.{jvm,android,apple,wasmJs}.kt`.
- Test: `commonTest/CompressedPointProbeTest.kt`.
- Modify: `settings.gradle.kts`, `AGENTS.md`, `kuilt.publish.gradle.kts`, `.claude/rules/session.md`, `llms.txt`, `site/llms.txt` (if the script writes it), and `kuilt-identity/api/`.

**Interfaces (produced):**
- `@RequiresOptIn(level = RequiresOptIn.Level.ERROR) public annotation class IdentityInternalApi`.
- `internal suspend fun algorithms(): Algorithms`, where `Algorithms(val ecdsa: ECDSA)` is self-tested once, as in `kuilt-seal/src/commonMain/kotlin/us/tractat/kuilt/seal/PlatformProvider.kt`. Copy that file's shape. **Do not depend on `:kuilt-seal`**: its provider is `internal`, and Decided 7 keeps the modules apart.

- [ ] **Step 1: The build file.** Copy `kuilt-seal/build.gradle.kts`: the manual `appleMain` wiring, one provider per platform, and the kotlinx-io 0.9.1 constraint. Add `alias(libs.plugins.kotlinSerialization)`, and `api(libs.kotlinx.serialization.core)`, because `DidKey` will be `@Serializable` in the public surface. The module has no kuilt dependency yet.
- [ ] **Step 2: Write the probe tests** in `CompressedPointProbeTest` (ECDSA, P-256):
  - `compressedExportIsThirtyThreeBytes`: generate a key pair, export the public key with `EC.PublicKey.Format.RAW.Compressed`, and assert `size == 33` and `bytes[0] in {0x02, 0x03}`.
  - `compressedImportRoundTripsToTheSamePoint`: decode those 33 bytes with `RAW.Compressed`, re-export with `RAW` (uncompressed), and assert it equals the original's `RAW` export.
  - `importedCompressedKeyVerifies`: sign with `SignatureFormat.RAW` under SHA-256 using the original private key, and assert the decoded key verifies. Then flip a bit of the signature and assert it fails.
  - `knownCompressedPointDecodesOnEveryTarget`: take a fixed 33-byte compressed point and its 65-byte uncompressed form, both generated once on the JDK and pasted as hex literals. Assert that decoding the compressed form re-exports the uncompressed bytes.
  - `blockingDecodeIsUnavailableOnWebCrypto`: on wasmJs only, assert that `decodeFromByteArrayBlocking` throws, as evidence for R1. Use a `wasmJsTest` source set file.
- [ ] **Step 3: Run the probe on every target.** Run `timeout 600 ./gradlew :kuilt-identity:allTests :kuilt-identity:testDebugUnitTest`. **Record the browser that Karma ran wasmJs in** (by default the Kotlin Gradle plugin's ChromeHeadless), and put it in the PR body.
- [ ] **Step 4: Gate G0.** The probe passes only if every arm is green on every target.
  - **If any arm fails on any target, stop.** Leave the PR as a draft, and report the failing arm and target to the dispatcher. Do not hand-roll point decompression. Do not switch the device key to uncompressed points. Both are Iain's call.
  - **If wasmJs passes only in ChromeHeadless,** say so in the PR body. Safari and Firefox import of compressed raw EC points stays unproven, and Iain decides whether that blocks.
- [ ] **Step 5: Module paperwork.**
  - `AGENTS.md` row: `| \`:kuilt-identity\` | all | One P-256 key per device, named by a \`did:key\`; the signed statements a room's admission exchange and grants are made of; per-platform key stores. Primitives are cryptography-kotlin's; this module owns no math. Depends on \`:kuilt-store\` (from slice 2); see \`kuilt-identity/module.md\`. |`
  - `moduleDescription`: `"Device identity for Kotlin Multiplatform: one key per device, did:key names, and signed grants that let a device come back to a room."`
  - Run `python3 .github/scripts/llms-txt.py`.
- [ ] **Step 6: Verify and commit.** Run `./gradlew clean && timeout 600 ./gradlew :kuilt-identity:build --rerun-tasks`, then `./gradlew verifyModuleTable`. Commit: "identity: add the :kuilt-identity module and prove compressed P-256 points on every provider (#3159)".

### Task 0.2: The statement layout codec [after 0.1 merges; not gated on G0's result]

**Files:**
- Create: `commonMain/Layout.kt`.
- Test: `commonTest/LayoutTest.kt`.

**Interfaces (produced):**
```kotlin
internal object Layout {
    const val SIGNATURE_SIZE: Int = 64
    fun encode(tag: String, version: Int, fields: List<ByteArray>): ByteArray   // require: tag ASCII, every field ≤ 65 535 bytes
    fun decode(bytes: ByteArray, tag: String, version: Int, fieldCount: Int): List<ByteArray>?  // null on ANY mismatch
    fun split(wire: ByteArray): Pair<ByteArray, ByteArray>?   // (layout, 64-byte signature), null when shorter than 64
}
```
- [ ] **Step 1: Write the failing tests.**
  - `encodesTheDocumentedBytes`: `encode("kuilt/t", 1, listOf(byteArrayOf(1, 2), byteArrayOf()))` equals the hex literal `0007 6b75696c742f74 01 0002 0102 0000`. Write the expected bytes by hand, field by field, in the test. **Never derive them from the encoder.**
  - `decodeInvertsEncode`.
  - `decodeRejects`: each case below returns `null`.
    - another tag;
    - another version;
    - a field count one short;
    - a field count one long;
    - a truncated length prefix;
    - a length running past the end;
    - one trailing byte.
  - `encodeRefusesAnOversizeField`: a 65 536-byte field throws `IllegalArgumentException`.
- [ ] **Step 2: Watch them fail.** Run `timeout 600 ./gradlew :kuilt-identity:jvmTest --tests "*LayoutTest*"` and expect FAIL.
- [ ] **Step 3: Implement `Layout`**, then run `:kuilt-identity:allTests` and expect PASS.
- [ ] **Step 4: Pre-merge verification, then commit.** Commit: "identity: the fixed signed-statement layout (#3159)".

### Task 0.3: `DidKey` [after G0 passes]

**Files:**
- Create: `commonMain/DidKey.kt`, `commonMain/Base58.kt` (internal base58btc) and `commonMain/P256Curve.kt` (internal on-curve check).
- Modify: `kuilt-identity/build.gradle.kts` (`implementation(libs.ionspin.bignum)`, already in the catalog for `:kuilt-deal`).
- Test: `commonTest/DidKeyTest.kt`.
- Modify: the skill (add the identity row), the cookbook (start `## Device identity & return`), `docs/agent-cookbook.md`, and `api/`.

**Interfaces (produced), written for R1 (a):**
```kotlin
@Serializable(with = DidKey.Serializer::class)   // a STRING on the wire; deserialize = syntax checks only
@JvmInline public value class DidKey internal constructor(public val value: String) {
    // init: starts "did:key:z", base58btc decodes, multicodec 0x80 0x24, 33 bytes, leading 0x02/0x03
    public fun compressedPoint(): ByteArray                          // 33-byte copy
    public suspend fun verify(message: ByteArray, signature: ByteArray): Boolean  // runs isOnCurve first; SHA-256, raw r‖s; false (never throws) on off-curve or bad length
    public companion object {
        public suspend fun parse(value: String): DidKey               // init checks + isOnCurve + decodes the point; IllegalArgumentException otherwise
        internal fun ofCompressedPoint(point: ByteArray): DidKey
    }
}
```
- **The on-curve check** (`internal fun isOnCurve(compressed: ByteArray): Boolean` in `P256Curve.kt`): with the P-256 constants `p` and `b` as hex literals, require `x < p`, compute `rhs = x³ − 3x + b mod p`, and require `rhs^((p−1)/2) ≡ 1` or `rhs ≡ 0` (Euler's criterion: `rhs` is a square, so a point with this x exists; the lead byte then picks y). This is validation only. Decompression stays the provider's. It runs in common code, so every provider gets the same answer.
- [ ] **Step 1: Write the failing tests.**
  - `isOnCurveMatchesKnownPoints`: both G0 fixed points (`02` and `03`) and the generator's x are on the curve. `x = p` and `x = p + 1` (as 32 bytes where they fit, otherwise all-`0xff`) are rejected by the `x < p` arm. Each arm asserts its own precondition.
  - `w3cVectorsRoundTrip`: take at least two P-256 entries from the W3C CCG did:key spec's test vectors, citing the document in a comment. For each, assert that `parse(did)` succeeds, that `value` starts `did:key:zDn`, and that decompressing through the provider gives the vector's JWK `x‖y`.
  - `syntaxRejects`: each of these throws `IllegalArgumentException`.
    - a wrong prefix;
    - a non-base58 character;
    - an Ed25519 multicodec (`0xed 0x01`);
    - 32 point bytes;
    - 34 point bytes;
    - a leading `0x04`.
  - `offCurvePointIsRejectedByParseAndVerifiesNothing`: use `x = 1`, which #3201 measured has no point on P-256, wrapped as `0x02‖x`. As the rig's control, assert that the JDK provider's raw decode **accepts** it on JVM (the defect this check exists for; skip that control on other targets, and record per target whether the provider accepted it). Assert that `parse` throws on every target. Assert that a `DidKey` decoded through the serializer `verify`s to `false` and does not throw. Mutation: make `isOnCurve` return `true`, and confirm this arm reds on JVM.
  - `serializerRoundTripsAsAString`, over CBOR.
- [ ] **Step 2: Watch them fail**, then implement. The point decode uses `ecdsa.publicKeyDecoder(EC.Curve.P256).decodeFromByteArray(RAW.Compressed, …)` inside `providerCall`.
- [ ] **Step 3: Docs.**
  - Skill row: `|a per-device key, a did:key, recognising a device across sessions|\`DidKey\`|\`session.md#device-identity--return\`|`. Trim the body to stay ≤ 8,192 bytes.
  - Cookbook: a stub section with one compiled-sample snippet (`commonSamples/IdentitySamples.kt` → `sampleParseDidKey`), plus `IdentitySamplesRunTest`.
- [ ] **Step 4: Run `allTests`, the doc checks and the pre-merge build, then commit.** Commit: "identity: DidKey, the did:key name of a P-256 device key (#3159)".

### Task 0.8: Decompress in common code and import uncompressed on every target [after 0.3; before 0.5, 0.6, 0.7 and 3.4]

Spec Decided 16. #3232's probe: Safari/WebKit refuse a 33-byte compressed raw import.

**Files:** modify `commonMain/P256Curve.kt` (an internal `decompress(compressed: ByteArray): ByteArray?` returning `0x04‖x‖y`, or null off the curve; `isOnCurve` becomes `decompress(...) != null` or shares its `rhs`) and `commonMain/DidKey.kt` (`decodeOnCurve` decodes `RAW` uncompressed from `decompress`'s output, never `RAW.Compressed`). Test: `commonTest/P256CurveTest.kt`, plus arms in `DidKeyTest`.

- [ ] **Step 1: Failing tests.**
  - `decompressMatchesKnownPoints`: both G0 fixed points (`02` and `03`) decompress to the 65-byte forms in `CompressedPointProbeTest`, and G (`03 6b17…`) decompresses to `04‖Gx‖Gy` (hex literals from SEC 2).
  - `decompressAgreesWithTheProviderOnGeneratedKeys`: 32 generated keys. `decompress(RAW.Compressed export)` equals the `RAW` export, byte for byte, on every target.
  - `decompressRefusesOffCurveAndOutOfRange`: x = 1, x = p, x = p+5 and a `0x04` lead byte all give null.
  - `decodeOnCurveNeverCallsCompressedImport`: assert by construction (a review point, not a test) that no `RAW.Compressed` decode remains in `commonMain`. Add a lexical check in the test that reads nothing, or state it in the PR body and let review hold it.
- [ ] **Step 2: Mutations.** Flip the parity choice, which should red the `03` arm and half the generated keys. Drop the `x < p` check, which should red the out-of-range arm.
- [ ] **Step 3: Docs.** Update `module.md` § Underneath ("the module decompresses the point itself …, because Safari refuses compressed import") and the AGENTS.md row's "beyond one on-curve validation" wording.
- [ ] **Step 4:** `allTests` + `testDebugUnitTest`, a per-target table, and the pre-merge module build. Commit: "identity: decompress P-256 points in common code so every browser imports them (#3159)".

### Task 0.4: `DeviceKey` and signature vectors [after G0; parallel with 0.3 once 0.3's `DidKey` signature is fixed; merges after 0.3]

**Files:**
- Create: `commonMain/DeviceKey.kt` (the public interface plus internal `SoftwareDeviceKey`).
- Test: `commonTest/DeviceKeySignatureTest.kt`.
- Modify: the skill row (add `DeviceKey`), the cookbook, the samples, and `api/`.

**Interfaces (produced):**
```kotlin
public interface DeviceKey {
    public val id: DidKey
    /** ECDSA P-256 over SHA-256 of [message]; 64-byte raw r‖s. */
    public suspend fun sign(message: ByteArray): ByteArray
    public companion object { public suspend fun generate(): DeviceKey }   // software key, platform CSPRNG
}
internal class SoftwareDeviceKey : DeviceKey {   // wraps a cryptography-kotlin ECDSA key pair
    // As built in #3243: no PKCS#8 pair. On the JDK provider, PKCS#8 drops the public half and
    // getPublicKey() throws. The stores' key format is JWK plus a self-test (Task 3.1).
}
```
- [ ] **Step 1: Write the failing tests.**
  - `wycheproofSignaturesVerifyOnEveryTarget` (as built in #3243, stronger than the original JDK-made vector): use Project Wycheproof `ecdsa_secp256r1_sha256_p1363_test.json` at C2SP/wycheproof `878e5366`, with 6 valid and 4 invalid cases pasted unchanged. Assert `DidKey.parse(did).verify(msg, sig)`. Then assert it is `false` with one flipped signature bit, and `false` with one flipped message bit.
  - `signThenVerifyHoldsPerTarget`: 20 keys × 3 messages (empty, 1 byte, 4 KiB). Each signature is 64 bytes and verifies under `id`, and not under another key's `id`.
- [ ] **Step 2: Watch them fail, implement, then run `:kuilt-identity:allTests` and `testDebugUnitTest`.**
- [ ] **Step 3: Docs, `api/` and the pre-merge build, then commit.** Commit: "identity: DeviceKey and cross-target signature vectors (#3159)".

### Task 0.5: Challenge and Admission-proof statements [after 0.2, 0.3, 0.4, 0.8]

**Files:**
- Create: `commonMain/Challenge.kt` and `commonMain/AdmissionProof.kt`.
- Test: `commonTest/ChallengeTest.kt` and `commonTest/AdmissionProofTest.kt`.
- Modify: `api/`. No skill change: these are internal API.

**Interfaces (produced):** both are `@IdentityInternalApi`. A statement always carries its wire form, which is the layout followed by the signature.
```kotlin
@IdentityInternalApi public class Challenge internal constructor(
    public val hostKey: DidKey, public val roomId: String, public val hostPeerId: String,
    public val joinerPeerId: String, public val joinerNonce: ByteArray, public val hostNonce: ByteArray,
    public val wire: ByteArray,
) {   // init: both nonces exactly NONCE_SIZE
    public suspend fun isSignedByHost(): Boolean          // verifies under hostKey
    public companion object {
        public const val NONCE_SIZE: Int = 16
        public suspend fun sign(host: DeviceKey, roomId: String, hostPeerId: String, joinerPeerId: String,
                                joinerNonce: ByteArray, hostNonce: ByteArray): Challenge
        public fun decode(wire: ByteArray): Challenge?     // tag "kuilt/challenge" v1; layout only
    }
}
@IdentityInternalApi public class AdmissionProof internal constructor(
    public val roomId: String, public val hostKey: DidKey, public val hostPeerId: String,
    public val joinerPeerId: String, public val deviceId: String, public val hostNonce: ByteArray,
    public val wire: ByteArray,
) {   // channel binding: always empty this slice; decode refuses a non-empty one
    public suspend fun isSignedBy(joiner: DidKey): Boolean
    public companion object {
        public suspend fun sign(joiner: DeviceKey, roomId: String, hostKey: DidKey, hostPeerId: String,
                                joinerPeerId: String, deviceId: String?, hostNonce: ByteArray): AdmissionProof  // null deviceId → ""
        public fun decode(wire: ByteArray): AdmissionProof?  // tag "kuilt/admit-proof" v1
    }
}
```
- [ ] **Step 1: Write the failing tests**, the same set for each statement.
  - `layoutIsTheDocumentedBytes`: fixed inputs. Assert that `Layout.split(wire).first` equals a hand-annotated hex literal, field by field.
  - `signedDecodesAndVerifies`.
  - `tamperedFieldFailsVerification`: flip one byte in each field in turn, and assert either that `decode` is null or that verification is false.
  - `wrongSignerFails`.
  - `decodeRejectsTheOtherStatementsTag`: a Challenge's wire bytes are not a proof, and the reverse.
  - `nonEmptyChannelBindingDoesNotDecode`: proof only.
  - `nonceOfFifteenBytesIsRefusedAtConstruction`.
- [ ] **Step 2: Watch them fail, implement, then run `allTests`.**
- [ ] **Step 3: `api/` and the pre-merge build, then commit.** Commit: "identity: the Challenge and Admission-proof statements (#3159)".

### Task 0.6: The `Grant` statement [after 0.2, 0.3, 0.4, 0.8; parallel with 0.5]

**Files:**
- Create: `commonMain/Grant.kt`.
- Test: `commonTest/GrantTest.kt`.
- Modify: the skill row (add `Grant`), the cookbook, the samples, and `api/`.

**Interfaces (produced):**
```kotlin
public sealed interface GrantScope {
    public data class Room(public val roomId: String) : GrantScope
    public data object AnyRoomOfIssuer : GrantScope
}
public class Grant internal constructor(
    public val issuer: DidKey, public val subject: DidKey, public val scope: GrantScope,
    public val issuedAt: Long, public val notAfter: Long,          // epoch ms; NO_EXPIRY = Long.MAX_VALUE
    public val id: ByteArray,                                      // 16 bytes, enforced in init
    public val wire: ByteArray,
) {   // equals/hashCode by wire bytes; parent grant id: always empty this slice
    public suspend fun isSignedByIssuer(): Boolean
    public companion object {
        public const val NO_EXPIRY: Long = Long.MAX_VALUE
        public suspend fun issue(issuer: DeviceKey, subject: DidKey, scope: GrantScope, issuedAt: Long, notAfter: Long): Grant
        public fun decode(wire: ByteArray): Grant?                 // tag "kuilt/grant" v1; unknown scope tag → null
        internal suspend fun issue(issuer: DeviceKey, subject: DidKey, scope: GrantScope, issuedAt: Long, notAfter: Long, id: ByteArray): Grant
    }
}
```
- [ ] **Step 1: Write the failing tests.**
  - `layoutIsTheDocumentedBytes`, for both scopes, using a fixed id.
  - `unknownScopeTagDoesNotDecode`, with tag `0x03`.
  - `nonEmptyParentDoesNotDecode`.
  - `issueRequiresNotAfterAfterIssuedAt`: `IllegalArgumentException` when `notAfter <= issuedAt`.
  - `tamperedSubjectFailsIssuerSignature`.
  - `noExpiryRoundTrips`.
  - `issuedIdsAreDistinct`: 100 issues give 100 distinct ids.
- [ ] **Step 2: Watch them fail, implement, then run `allTests`.**
- [ ] **Step 3: Docs, `api/` and the pre-merge build, then commit.** Commit: "identity: the Grant statement (#3159)".

### Task 0.7: `DeviceKeyStore` and `InMemoryDeviceKeyStore` [after 0.4, 0.6]

**Files:**
- Create: `commonMain/DeviceKeyStore.kt` (the interface, plus internal `List<Grant>.keeping(grant)`) and `commonMain/InMemoryDeviceKeyStore.kt`.
- Test: `commonTest/InMemoryDeviceKeyStoreTest.kt`.
- Modify: the skill row (lead with `DeviceKeyStore`), the cookbook (the section's main snippet), `module.md`, the samples, and `api/`.

**Interfaces (produced):** the spec's interface verbatim.
```kotlin
public interface DeviceKeyStore {
    public suspend fun deviceKey(): DeviceKey      // created on first call, the same on every call after
    public suspend fun grants(): List<Grant>       // the newest per issuer and scope (Decided 21)
    public suspend fun keepGrant(grant: Grant)     // replaces an OLDER (issuedAt) grant from the same issuer; ignores an older one
}
public class InMemoryDeviceKeyStore : DeviceKeyStore    // lock-guarded; KDoc: forgets everything with the process
```
- [ ] **Step 1: Write the failing tests.**
  - `deviceKeyIsStable`: 50 concurrent first calls on `StandardTestDispatcher` produce one id.
  - `keepsNewestPerIssuer`: an older grant from the same issuer is ignored, and a newer one replaces it.
  - `twoIssuersBothKept`.
  - `distinctStoresHaveDistinctKeys`.
- [ ] **Step 2: Watch them fail, implement, then run `allTests`.**
- [ ] **Step 3: Docs, `api/` and the pre-merge build, then commit.** In `module.md`, describe the threat model in the spec's own words: what it protects against and what it does not. Commit: "identity: DeviceKeyStore and its in-memory store (#3159)".

---

## Slice 1: the exchange and the key → seat table

Session code lives in `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/`. Spec sections for this slice's conformance review: *One exchange for admission and return*, *The key → seat table*, *Membership and the wire*, *What this protects against*, and Decided 1, 4, 8, 10. Use `ForgedResumeTest` and `AdmitFanOutOrderingTest` as the shape for raw-frame session tests.

### Task 1.1: Thread `DeviceKeyStore` and `deviceId` through every factory [after 0.7; mechanical]

> **Amended — read Amendment A § 1.1 first; it overrides this task where they differ.**

**Files:**
- Modify: `kuilt-session/build.gradle.kts` (add `api(project(":kuilt-identity"))`) and `SeamRoom.kt` (`SeamRoomFactory`, `SeamRoomFactory.systemClock`, `join`, `adopt`, and the `SeamRoom` constructor).
- Modify: `Room.kt` (`RoomFactory.join`), `kuilt-websocket/.../KtorRoomHost.kt`, `kuilt-cluster/.../ClusterClient.kt` and `ServerCluster.kt`, the `kuilt-nw` call sites, `kuilt-game/src/commonSamples/.../GameSamples.kt`, `kuilt-conformance`, and `kuilt-session-test/.../FakeRoomFactory.kt`.
- Modify: every test that builds a `SeamRoomFactory`, about 50 files, and the `AGENTS.md` `:kuilt-session` row (its dependencies).

**Interfaces (produced):**
- `SeamRoomFactory(loom, scope, clock, deviceKeyStore: DeviceKeyStore, …)`. It is required, and comes before the defaulted parameters.
- `systemClock(loom, scope, deviceKeyStore, …)`.
- `RoomFactory.join(tag, memberName = null, deviceId: String? = null)`, and `SeamRoomFactory.adopt(…, deviceId: String? = null)`. The Hello carries `deviceId`.
- `KtorRoomHost(…, deviceKeyStore)`. Where one process builds several cluster servers, each server gets its own store through a `(PeerId) -> DeviceKeyStore`, because each server has its own key.

- [ ] **Step 1: Write the failing test.** `helloCarriesTheSuppliedDeviceId`: join with `deviceId = "d-1"`, and assert the host's `Member.identity.deviceId == "d-1"`. A join without one still gives `null`.
- [ ] **Step 2: Add the parameter and fix every call site.** Tests pass `InMemoryDeviceKeyStore()`. The room stores the store and does not read it yet.
- [ ] **Step 3: Update the ABI dumps** for session, websocket, cluster, nw (on macOS), game and session-test. The PR body records "required `deviceKeyStore`; `deviceId` on join/adopt".
- [ ] **Step 4: Full verification, then commit.** Run `./gradlew clean && ./gradlew build --rerun-tasks`. Commit: "session: rooms take a DeviceKeyStore and send a deviceId (#3159)".

### Task 1.2: The key → seat decision, as a pure function [after 0.3; parallel with slice 0's tail]

> **Amended — read Amendment A § Shared files first; it overrides this task where they differ.**

**Files:**
- Create: `admit/SeatDecision.kt`.
- Test: `kuilt-session/src/commonTest/.../admit/SeatDecisionTest.kt`.

**Interfaces (produced):**
```kotlin
internal data class Seat(val peerId: PeerId, val key: DidKey)           // a HELD seat: admitted, connected or partitioned
internal sealed interface SeatDecision {
    data class Restore(val peerId: PeerId) : SeatDecision                 // same id, same key
    data class RestoreUnderNewId(val previous: PeerId) : SeatDecision     // new id, same key
    data object Returning : SeatDecision                                  // no held seat, grant valid (wired in 2.6)
    data object KeyConflict : SeatDecision                                // claimed id held under a different key
    data object Ordinary : SeatDecision
}
internal fun decideSeat(claimedId: PeerId, provenKey: DidKey, held: Collection<Seat>, holdsValidGrant: Boolean): SeatDecision
```
The precedence is: id held → `Restore` or `KeyConflict`; else key held → `RestoreUnderNewId`; else `holdsValidGrant` → `Returning`; else `Ordinary`.

- [ ] **Step 1: Write the failing tests.**
  - One test per table row: `sameIdSameKeyRestores`, `newIdSameKeyRestoresUnderNewId`, `noSeatValidGrantReturns`, `sameIdDifferentKeyConflicts`, `noSeatNoGrantIsOrdinary`.
  - `idConflictBeatsKeyMatchElsewhere`: the claimed id is held by key B, and the proven key A holds another seat. Expect `KeyConflict`.
  - `validGrantNeverOverridesAHeldSeat`.
- [ ] **Step 2: Watch them fail, implement, then run `:kuilt-session:allTests --tests "*SeatDecision*"`.** Pre-merge build, then commit. Commit: "session: the key-to-seat decision table (#3159)".

### Task 1.3: Admit wire v3: Challenge, Proof, and keys on Hello and Welcome [after 1.1, 0.5]

> **Amended — read Amendment A § 1.3 first; it overrides this task where they differ.**

**Files:**
- Modify: `admit/AdmitMessage.kt`, `admit/ProtocolVersion.kt` (`CURRENT` = `MIN_SUPPORTED` = `MAX_SUPPORTED` = 3), and `SeamRoom.kt` (only `sendHello` and the three Welcome constructions fill the new fields).
- Test: `admit/AdmitMessageCodecTest.kt` (extend it or create it).

**Interfaces (produced):**
- `Hello(…, key: DidKey, @ByteString joinerNonce: ByteArray)`. The joiner draws a fresh 16-byte nonce on every Hello from the platform CSPRNG.
- `Welcome(…, key: DidKey)`, filled on all three shapes:
  - direct: the joiner's key from its Hello;
  - bootstrap: each existing member's key;
  - host intro: the host's own key.
- `Challenge(@ByteString statement: ByteArray)` and `Proof(@ByteString statement: ByteArray, grants: List<@ByteString ByteArray> = emptyList())`, `@SerialName` `"challenge"` and `"proof"`. Nothing sends them yet.

- [ ] **Step 1: Write the failing tests.**
  - `everyNewShapeRoundTrips`.
  - `helloWithAMalformedKeyDecodesToNull`.
  - `v2HelloIsRefusedWithProtocolMismatch`, via the existing gate.
- [ ] **Step 2: Implement.** Existing admission is unchanged. Only the frames carry more.
- [ ] **Step 3: `api/`, full build, then commit.** Commit: "session: admit protocol v3 carries device keys and the challenge frames (#3159)".

### Task 1.4: Admission requires a verified proof of the key [after 1.3] — riskiest task

> **Amended — read Amendment A § 1.4 first; it overrides this task where they differ.**

**Files:**
- Create: `admit/KeyAdmission.kt`, the internal pending-challenge table plus the host and joiner checks, so that `SeamRoom.kt` grows only by its call sites.
- Modify: `SeamRoom.kt` (`handleAdmitFrame`, `admitPeer`, `handleWelcome`, `handlePeerLost`), `Member.kt` (`key`), `admit/RejectCode.kt`, `kuilt-session-test/.../FakeRoom.kt`, the `Member(` sites in `kuilt-cluster`, and `ProtocolVersion` (to 4).
- Test: `KeyAdmissionTest.kt`, `admit/PendingChallengesTest.kt`, and the existing admission tests.

**Interfaces (produced):**
- `Member(id, identity, key: DidKey, liveness, principal)`. The KDoc says the host's own key on a joiner's roster is verified by its signed Challenge, and every other member's key is host-asserted. It also says the key binds the handshake, not later frames, so a `HostedOverlay` deployment should use a binding `LinkAdmission` until #2387 lands. Copy that warning into the cookbook section too.
- `RejectCode.BadProof` (`"bad-proof"`, `retryable = false`) and `RejectCode.KeyConflict` (`"key-conflict"`, `retryable = false`), each with a `fromId` branch.
- `internal class PendingChallenges(clock)` with `open(sender: PeerId, hello: Hello, hostNonce: ByteArray, deadline: Instant)`, `take(sender): Pending?` and `drop(sender)`. Entries are keyed by fabric sender only. A newer Hello from the same sender overwrites the older entry. An entry is dropped when its peer is lost, or after `admitTimeout`.

**Behaviour:**
- **Host.** On Hello, it opens a pending entry and sends `Challenge.sign(hostKey, roomId, selfId, sender, joinerNonce, hostNonce)` to the sender. On a Proof from `sender`, it takes the pending entry and checks the decoded proof:
  - room id = this room's id;
  - host key = this host's key;
  - host peer id = `selfId`;
  - joiner peer id = `sender`;
  - host nonce = the pending entry's nonce;
  - `deviceId` = `Hello.deviceId ?: ""`;
  - the signature verifies under `Hello.key`.

  Any failure sends `Reject(BadProof)`. Success runs today's `admitPeer` with `Member.key = Hello.key`.
- **Joiner.** It answers **every** Challenge independently. It verifies it under the key the Challenge names, checks `joinerNonce` = its latest Hello's nonce and `joinerPeerId` = `selfId`, and then signs the proof with **the fabric sender of that Challenge** as the host peer id. It records `sender → verified host key`. It accepts the host-intro Welcome only from a sender it holds a verified key for, and only when that key equals `Welcome.key`.

- [ ] **Step 1: Write the failing tests.** Each test also asserts that its rig fired, by counting the injected frame at the receiver.
  - `honestJoinerIsAdmittedWithItsKey`: `Member.key` on both sides, with the host entry on the joiner equal to the Challenge's key.
  - `forgedProofIsRejectedBadProof`: signed by another key.
  - `proofReplayedUnderAnotherNonceIsRejected`: capture attempt 1's proof, and present it in attempt 2.
  - `proofForAnotherHostIsRejected`.
  - `coJoinersChallengeDoesNotStarveTheRealOne`, on a flat `InMemoryLoom` with three peers. The co-joiner's Challenge is answered, and the joiner is still admitted by the real host.
  - `bystanderHelloFloodOverwritesOnlyItsOwnPending`: 100 Hellos from the bystander, and the victim's pending Proof still succeeds.
  - `pendingIsDroppedOnPeerLostAndAfterAdmitTimeout`.
  - `seatIsReReadAtProofTimeNotChallengeTime`: a `Goodbye` landing between Challenge and Proof leaves no admitted ghost.
- [ ] **Step 2: Watch them fail, implement, then run `:kuilt-session:allTests`.**
- [ ] **Step 3: `api/` (session, session-test, cluster), `./gradlew clean && ./gradlew build --rerun-tasks`, then commit.** Commit: "session: admission requires a signed proof of the device key (#3159)".

### Task 1.5: Same id, same key restores the held seat, and a different key is refused [after 1.2, 1.4]

> **Amended — read Amendment A § 1.5 first; it overrides this task where they differ.**

**Files:**
- Create: `partition/ProvenResume.kt`.
- Modify: `partition/JoinerReconnectController.kt`, `partition/DefaultJoinerReconnectController.kt`, `SeamRoom.kt` (the Proof handler consults `decideSeat` under `lock` at Proof time), `admit/AdmitMessage.kt` (`Welcome.returnKind: ReturnKind? = null`, `ReturnKind`), `ProtocolVersion` (to 5), `kuilt-conformance/.../ReferenceResumeHarness.kt`, and every `tryResume` test.

**Interfaces (produced):**
- `public class ProvenResume internal constructor(public val peerId: PeerId, public val roomId: RoomId, public val key: DidKey)`. **Only the Proof handler constructs it, after the key check.** A test outside `:kuilt-session` reaches it through a real room. No public factory is added.
- `JoinerReconnectController.tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict`.

**Behaviour:**
- `Restore` on a partitioned seat calls `tryResume`. `Success` restores the same membership: the host emits `MembershipEvent.Resumed`, re-sends the Welcome burst with `returnKind = Restored`, and fans out nothing to bystanders. Any other verdict treats the seat as gone, and the key is **decided again without that seat** (Decided 23): `Returning` with a valid grant, else `Ordinary` — never a hardcoded `Ordinary`.
- `Restore` on a connected seat supersedes the old link, emits `Resumed`, and fans out nothing.
- `KeyConflict` sends `Reject(KeyConflict)`, which is terminal.
- The old `Resume` frame still works.

- [ ] **Step 1: Write the failing tests.**
  - `healedLinkSameIdRestoresWithResumed`: no `Left` or `Joined` anywhere.
  - `processRebuiltWithSameIdInsideWindowRestores`: a new `SeamRoom` over the same `DeviceKeyStore` and the same fabric id.
  - `stillConnectedSeatRestoresAndSupersedesOldLink`.
  - `bystanderClaimingALiveMembersIdIsRejectedKeyConflict`: the victim stays in every roster.
  - `windowExpiredDecidesAgainWithoutTheSeat` (`Ordinary` without a grant, `Returning` with one).
  - `seatChecksRunUnderTheLockAtProofTime`: the window expires between Challenge and Proof, and the outcome is `Ordinary`.
- [ ] **Step 2: Watch them fail, implement, update `api/`, run the full build, then commit.** Commit: "session: a proven key restores its held seat (#3159)".

### Task 1.6: A known key under a new id restores, and the old membership is superseded [after 1.5]

> **Amended — read Amendment A § 1.6 first; it overrides this task where they differ.**

**Files:**
- Modify: `LeaveReason.kt` (`Superseded(by: PeerId)`), `admit/AdmitMessage.kt` (`Farewell.supersededBy: String? = null`), `ProtocolVersion` (to 6), and `SeamRoom.kt` (the `RestoreUnderNewId` arm, the bystander handling of `Farewell.supersededBy`, and the joiner's handling of its own supersession).

**Behaviour:**
- **On the host,** for a partitioned old seat it calls `tryResume(ProvenResume(previous, …))`. On `Success` the old seat leaves with `Left(previous, Superseded(by = new))`, and `Joined(new)` follows. The new seat inherits the identity and the key. Bystanders get `Farewell(previous, supersededBy = new)` then `Welcome(new)` through `fanOutToOtherMembers`, in that order. That ordering is what #1781 relies on.
- **On a joiner** that receives `Farewell` naming **itself** with `supersededBy`, the room ends with `LeaveReason.Superseded` and never re-Hellos. This covers Review Focus 1.

- [ ] **Step 1: Write the failing tests.**
  - `processRebuiltWithFreshIdInsideWindowRestoresUnderNewId`: the host and a bystander each see exactly one `Left(Superseded)` + `Joined` pair, in that order.
  - `bystanderOrderingHoldsUnderConcurrentFarewell`: as in `AdmitFanOutOrderingTest`.
  - `supersededJoinerDoesNotReHello`: two rooms over one `DeviceKeyStore`. The second admission supersedes the first. Then count the Hellos from the first over 10× `admitTimeout` of virtual time, and expect zero.
- [ ] **Step 2: Watch them fail, implement, update `api/`, run the full build, then commit.** Commit: "session: a known key under a new peer id supersedes its old membership (#3159)".

### Task 1.7: The joiner's reconnect re-Hellos instead of sending `Resume` [after 1.6]

> **Amended — read Amendment A § 1.7 first; it overrides this task where they differ.**

**Files:**
- Modify: `partition/JoinerResumeMachine.kt` (`resume` and `runReconnect` send a fresh Hello and answer the Challenge, and the flight completes on the self Welcome), `SeamRoom.kt` (`Room.resume` and the self-Welcome handler), and the tests in `partition/` and `JoinerResumeMachine*`.

**Behaviour:**
- The flight completes as `ResumeResult.Success` on `Welcome(returnKind = Restored)`, and as the matching refusal on `Reject`.
- A self Welcome **without** `Restored` is a completed fresh admission. The room keeps it and sends no second Hello, and the flight reports `ResumeResult.WindowClosed`.
- The app makes zero calls, and the existing reconnect loop and back-off are unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `reconnectLoopSendsHelloNotResume`: zero `Resume` frames on the wire across a partition-and-heal.
  - `freshAdmissionAfterSeatLossIsNotFollowedByASecondHello`.
  - `refusalIsSurfacedWithItsCode`.
  - The existing `JoinerResumeMachine` teardown and generation tests still pass unmodified, apart from the frame they wait for.
- [ ] **Step 2: Watch them fail, implement, run the full build, then commit.** Commit: "session: reconnecting says Hello again (#3159)".

### Task 1.8: Retire `Resume`, `ResumeAck` and `ResumeSecret` [after 1.7]

> **Amended — read Amendment A § 1.8 first; it overrides this task where they differ.**

**Files:**
- Delete: `partition/ResumeSecret.kt` and its tests.
- Modify:
  - `AdmitMessage.kt`: remove `Resume`, `ResumeAck` and `Welcome.resumeSecret`.
  - `ResumeToken.kt`: remove `secret`.
  - `SeamRoom.kt`: remove `handleResume`, `handleResumeAck`, `resumeSecrets` and `heldResumeSecretCount`.
  - `RejectCode.kt`: remove `ResumeTokenInvalid` only if nothing produces it any more, and grep for it first.
  - `ProtocolVersion` (to 7).
  - `ForgedResumeTest.kt` is rewritten as the key-forgery cases still not covered by 1.4/1.5, or deleted with its cases cited as now living in `KeyAdmissionTest`.
  - The `moduleDescription()` text for `kuilt-session` ("reconnect tokens" becomes "key-bound return"), `llms.txt`, and `api/`.
- [ ] **Step 1: Delete, then make it compile.** Run `grep -rn 'ResumeSecret\|AdmitMessage.Resume\b\|ResumeAck' --include='*.kt' .` and expect no hits outside history.
- [ ] **Step 2: Run the full build.** The PR body records each ABI removal. Commit: "session: retire ResumeSecret and the Resume frame (#3159)".

### Task 1.9: Slice-1 conformance rows and the rejoin route [after 1.8]

> **Amended — read Amendment A § 1.9 first; it overrides this task where they differ.**

**Files:**
- Modify: `kuilt-conformance/src/commonMain/.../RoomConformanceSuite.kt` (new cases, and `ResumeHarness` reshaped around keys), `InMemoryRoomConformanceTest.kt`, `RoomResumeHarnessTest.kt`, the skill's rejoin row, and `docs/agent-cookbook/session.md` (`## Rejoin & reconnect` and `## Device identity & return`).

**Tests (the friction bar):** each case asserts **zero** room or factory calls by the app between drop and return.
- `returnRestoresHeldSeatSameId`
- `returnRestoresHeldSeatNewId`
- `differentKeyOnLiveIdIsRefused`
- `processRebuiltInsideWindowRestores`
- `processRebuiltWithFreshIdRestores`

**Docs:** the skill's rejoin row stops routing to `Room.resumeToken` + `Room.resume` as something the app drives, and says "return is automatic — `DeviceKeyStore` on the factory". The cookbook's rejoin section is rewritten from a compiled sample.

- [ ] **Step 1: Write the cases, and watch them run red against a deliberately broken room.** As a positive control, temporarily skip `decideSeat`. Record the red, then revert.
- [ ] **Step 2: Run them green, then the doc checks and the full build, then commit.** Commit: "conformance: the friction bar for a device that comes back (#3159)".

**Slice-1 gate:** one spec-conformance review of the merged slice against the sections listed under the slice heading, before any slice-2 task dispatches.

---

## Slice 2: grants

Spec sections for this slice's conformance review: *Grants, trust and revocation*, *Grant refresh*, *The host's decision point*, *Configuration and call sites*, *Testing*, and Decided 1, 2, 11, 12.

### Task 2.1: `GrantTrust`, `GrantVerdict`, and the in-memory `RevocationList` [after 0.6; may run during slice 1]

**Files:**
- Create: `kuilt-identity/.../GrantTrust.kt` and `RevocationList.kt`.
- Test: `GrantTrustTest.kt` and `RevocationListTest.kt`.
- Modify: the skill row (add `GrantTrust` + `RevocationList`), the cookbook, the samples, and `api/`.

**Interfaces (produced):**
```kotlin
public sealed interface GrantVerdict { Valid, Expired, WrongScope, WrongSubject, UntrustedIssuer, BadSignature, Revoked }  // data objects
public class GrantTrust(public val hostKey: DidKey, public val revocations: RevocationList) {
    public suspend fun verify(grant: Grant, subject: DidKey, roomId: String, now: Long): GrantVerdict   // order per Plan decisions
}
public class RevocationList {   // lock-guarded
    public fun notBefore(subject: DidKey): Long?
    public suspend fun revoke(subject: DidKey, notBefore: Long)     // monotone max
    public companion object { public fun inMemory(): RevocationList }
}
```
- [ ] **Step 1: Write the failing tests.**
  - One test per verdict, each with the single defect that produces it.
  - `checkOrderIsFixed`: a grant that is both expired and from an untrusted issuer gives `UntrustedIssuer`.
  - `anyRoomOfIssuerIsValidInEveryRoomOfThatHost`.
  - `revokedOnlyBeforeCutoff`: a grant issued at `notBefore − 1` is `Revoked`, and one issued at `notBefore` is `Valid`.
  - `revokeIsMonotone`.
  - `noExpiryNeverExpires`, at `now = Long.MAX_VALUE − 1`.
- [ ] **Step 2: Watch them fail, implement, run `allTests`, update docs and `api/`, run the pre-merge build, then commit.** Commit: "identity: GrantTrust and RevocationList (#3159)".

### Task 2.2: Revocations persist through a `DurableStore` [after 2.1]

**Files:**
- Modify: `kuilt-identity/build.gradle.kts` (add `implementation(project(":kuilt-store"))`, or `api` if `DurableStore` appears in a public signature, which it does), `RevocationList.kt`, `module.md` (the two failure modes, in the spec's words), and the `AGENTS.md` row.
- Test: `RevocationListPersistenceTest.kt`.

**Interfaces (produced):** `public suspend fun RevocationList.Companion.open(store: DurableStore, key: StoreKey = …): RevocationList`. `revoke` returns only after the write commits.

- [ ] **Step 1: Write the failing tests.**
  - `revocationSurvivesReopen`.
  - `corruptStoredListFailsLoudly`: an `IllegalStateException` naming the store key. Losing revocations silently is the dangerous direction.
  - `revokeReturnsAfterTheWriteCommits`: a store whose `write` suspends until released.
- [ ] **Step 2: Implement, run `allTests`, update `api/`, run the pre-merge build, then commit.** Commit: "identity: revocations survive a host restart (#3159)".

### Task 2.3: `AdmissionPolicy` and its types [after 0.7; may run during slice 1]

> **Amended — read Amendment A § 2.3 first; it overrides this task where they differ.**

**Files:**
- Create: `kuilt-session/.../admit/AdmissionPolicy.kt`.
- Test: `admit/AdmissionPolicyTest.kt`.
- Modify: the skill row (add `AdmissionPolicy`), the cookbook, the samples, and `api/`.

**Interfaces (produced):**
```kotlin
public fun interface AdmissionPolicy {
    public suspend fun decide(request: AdmissionRequest): AdmissionDecision
    public companion object { public fun open(readmitFor: Duration = 1.hours): AdmissionPolicy }  // Admit(GrantTerms(readmitFor, ThisRoom))
}
public class AdmissionRequest internal constructor(public val key: DidKey, public val displayName: String, public val deviceId: String?)
public sealed interface AdmissionDecision {
    public data class Admit(public val grant: GrantTerms) : AdmissionDecision
    public data class Reject(public val code: RejectCode) : AdmissionDecision
}
public enum class GrantReach { ThisRoom, AnyRoomOfIssuer }
public data class GrantTerms(public val readmitFor: Duration, public val reach: GrantReach = GrantReach.ThisRoom) {  // init: readmitFor > 0; INFINITE = no expiry
    public fun notAfter(issuedAt: Long): Long   // issuedAt + readmitFor, or Grant.NO_EXPIRY when INFINITE (saturating)
}
public class HostAdmission(public val policy: AdmissionPolicy, public val revocations: RevocationList)  // Decided 17: the room builds GrantTrust from its own store key
```
- [ ] **Step 1: Write the failing tests.**
  - `openAdmitsEveryoneWithTheRoomDefault`.
  - `termsRefuseZeroAndNegative`.
  - `infiniteMeansNoExpiry`: `GrantTerms(Duration.INFINITE).notAfter(0)` returns `Grant.NO_EXPIRY`, and a finite term near `Long.MAX_VALUE` saturates instead of overflowing.
- [ ] **Step 2: Implement, update docs and `api/`, run the pre-merge build, then commit.** Commit: "session: the host's AdmissionPolicy (#3159)".

### Task 2.4: Host entry points require `HostAdmission`, and the policy decides ordinary admissions [after 1.9, 2.1, 2.3]

> **Amended — read Amendment A § 2.4 first; it overrides this task where they differ.**

**Files:**
- Modify: `SeamRoom.kt` (`SeamRoomFactory.host`, `adopt(role: AdoptRole, …)`, `electLobby`, and the `Ordinary` arm), `Room.kt` (`RoomFactory.host(…, admission: HostAdmission)`), `SessionRole.kt` or a new `AdoptRole.kt`, `election/SeamElectionLobby.kt`, `KtorRoomHost`, `ServerCluster`, the `kuilt-nw` sites, `kuilt-game` samples, `kuilt-conformance`, `FakeRoomFactory`, and every host-side test (pass `HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())` (Decided 17)). Also `api/` everywhere.

**Interfaces (produced):** `public sealed interface AdoptRole { public data class Host(public val admission: HostAdmission, public val roomId: RoomId? = null) : AdoptRole; public data object Joiner : AdoptRole }`. The old `require(roomId == null || role == Host)` goes, because the type now carries it.

**Behaviour:**
- On `SeatDecision.Ordinary`, the room calls `policy.decide`.
- `Reject(code)` sends `Reject(code)`.
- A throw that is not this coroutine's cancellation (`ensureActive`) sends `Reject(AdmissionPolicyFailed)`, logs with the throwable, and leaves the room running.
- A decision that comes back after the pending entry was overwritten or dropped is discarded.
- The `Admit` terms are recorded per member, and not yet issued (2.5).

- [ ] **Step 1: Write the failing tests.**
  - `policyRejectReachesJoinerWithItsCode`.
  - `restoresNeverCallThePolicy`: a counting policy reads 0 across the 1.9 rows.
  - `throwingPolicyRejectsRetryablyAndRoomSurvives`.
  - `slowPolicyTimesOutWithoutWedgingPending`: the joiner sees `AdmissionFailed(TimedOut)`, and a fresh Hello from it is admitted afterwards.
  - `retriedHelloDuringDecisionAdmitsOnce`.
- [ ] **Step 2: Watch them fail, implement, thread the call sites, update `api/` (the PR body names `AdoptRole` and the host parameter), run the full build, then commit.** Commit: "session: hosts decide ordinary admissions through an AdmissionPolicy (#3159)".

### Task 2.5: Grants are issued on Welcome and refreshed while connected [after 2.4]

> **Amended — read Amendment A § 2.5 first; it overrides this task where they differ.**

**Files:**
- Create: `admit/GrantIssuer.kt` (internal: per-member terms, issue, the refresh schedule, the R2(i) check).
- Modify: `AdmitMessage.kt` (`Welcome.grant: ByteArray? = null` with `@ByteString`, and `GrantRenewal(grant)`), `ProtocolVersion` (+1), and `SeamRoom.kt` (issue on the direct Welcome, the joiner calls `deviceKeyStore.keepGrant`, the refresh job on `scope`).

**Behaviour:**
- **Issue.** Every self Welcome carries a fresh grant. The subject is the member's key, and the scope comes from `reach`: `Room(roomId)` or `AnyRoomOfIssuer`. `notAfter = now + readmitFor`, or `NO_EXPIRY`.
- **Refresh.** Re-issue every `(notAfter − issuedAt) / 2` on the member's direct lane, **only while the member is `Connected`**. A partitioned member gets nothing, so the hour runs from last contact. A no-expiry grant is never refreshed.
- **R2(i).** No refresh goes to a member whose subject has `notBefore` later than that member's admission instant.
- **Joiner.** It keeps a grant only if it decodes, names this room's host as issuer, and is signed by that issuer. Anything else is dropped and logged.

- [ ] **Step 1: Write the failing tests**, all on virtual time with the clock injected.
  - `welcomeCarriesAGrantForTheMembersKey`.
  - `refreshArrivesAtHalfLifeWhileConnected`.
  - `noRefreshWhilePartitioned`: after 1 h of partition the stored grant is expired.
  - `noExpiryGrantIsNeverRefreshed`.
  - `revokedPresentMemberStopsReceivingRefreshes`.
  - `joinerIgnoresAGrantFromAnotherIssuer`.
- [ ] **Step 2: Watch them fail, implement, update `api/`, run the full build, then commit.** Commit: "session: hosts issue and refresh grants (#3159)".

### Task 2.6: Returning admission inside a grant [after 2.5]

**Files:**
- Modify: `SeamRoom.kt` (the joiner puts `grants().filter { it.issuer == challenge.hostKey }` in the Proof, the host verifies each with `GrantTrust`, and `holdsValidGrant` feeds `decideSeat`), `admit/GrantIssuer.kt` (departed records `key → (previousId, latest notAfter)`, pruned at expiry, and the carry-forward of terms), `AdmitMessage.kt` (`Welcome.previousPeerId: String? = null`, and `returnKind = Returning` on the direct and bystander Welcomes), `MembershipEvent.kt` (`Joined(member, returning: ReturningMark? = null)`, `ReturningMark(previousId: PeerId?)`), `ProtocolVersion` (+1), and the R2(ii) check in the `Restore` arms.

**Behaviour:**
- `Returning` admits without calling the policy. Host and bystanders emit `Joined(member, ReturningMark(previousId))`. `previousId` is null when the host no longer has a departed record, for example after a restart.
- The re-issued grant keeps the presented terms.
- **R2(ii).** A revoked subject's held-seat restore is decided again without the seat; revocation is checked once per key **before** trusting any restore verdict, so a second seat under the same key cannot come back as `RestoreUnderNewId`.

- [ ] **Step 1: Write the failing tests.**
  - `returnInsideGrantSkipsPolicyAndIsMarked`: policy calls = 0, and the bystander sees the mark.
  - `friendsNoExpiryAnyRoomGrantIsCarriedForward`.
  - `departedRecordIsForgottenAtGrantExpiry`: an internal count probe.
  - `anyRoomGrantSurvivesHostRestartRoomGrantNeedsStableRoomId`: restart the host over the same `DeviceKeyStore`, once with `host(roomId = stable)` and once without.
  - `newHostAfterLobbyReElectionTreatsGrantAsUntrusted`: Decided 11.
  - `grantFromAnotherServersKeyIsUntrusted`: two hosts with distinct stores, as two cluster servers would have.
  - `revokedSubjectsHeldSeatGoesToThePolicy`.
- [ ] **Step 2: Watch them fail, implement, update `api/`, run the full build, then commit.** Commit: "session: a device inside its grant is re-admitted without vetting (#3159)".

### Task 2.7: Slice-2 conformance, refusal cases and docs [after 2.6, 2.2]

**Files:**
- Modify: `RoomConformanceSuite.kt`, `InMemoryRoomConformanceTest.kt`, the cookbook (the section's grants and revocation half, from compiled samples), the skill row (final symbol list, ≤ 8,192 bytes), `module.md` for both modules, and `llms.txt`.

**Tests.** Each case asserts its rig fired, and asserts the policy's call count.
- `returnInsideGrantNoVetting`: 0 policy calls.
- `grantExpiredRunsVetting`: 1 policy call.
- `grantForAnotherRoomIsIgnored`
- `grantForAnotherSubjectIsIgnored`
- `untrustedIssuerIsIgnored`
- `revokedSubjectIsVetted`
- `expiredGrantIsVetted`

Each of the last five goes to ordinary admission with exactly one policy call.

- [ ] **Step 1: Write the cases, and run the positive control** (`holdsValidGrant = true` hard-wired) to see the refusal cases red. Then revert.
- [ ] **Step 2: Run them green, then the doc checks and the full build, then commit.** Commit: "conformance: grants, expiry and revocation on return (#3159)".

**Slice-2 gate:** one spec-conformance review of the merged slice.

---

## Slice 3: platform key stores

Spec sections: *Key storage* and Decided 4, 8, 9. Each store keeps its key with the platform and its grants in a `DurableStore`. Each subclasses the TCK from Task 3.1 and adds its own `DeviceKeyStore.platform(...)`. The function is declared in that platform's source set, with no `expect`.

### Task 3.1: The store TCK and the desktop-JVM file store [after 0.7; add `:kuilt-store` if 2.2 has not]

**Key file format (Iain approved 2026-11-12): JWK, carrying x, y and d.** On decode, run a sign-then-verify self-test, so a mismatched pair is refused and treated as a corrupt key, which is replaced, not fatal. PKCS#8 does not work: on the JDK provider (cryptography-kotlin 0.6.0) it drops the public half, and `getPublicKey()` then throws (measured in #3243). BouncyCastle is rejected because two registered JCA providers stop `PlatformProvider` from starting.

**Files:**
- Create: `commonTest/DeviceKeyStoreConformanceSuite.kt` (abstract) and `commonTest/InMemoryDeviceKeyStoreConformanceTest.kt`.
- Create: `jvmMain/FileDeviceKeyStore.kt` and `jvmTest/FileDeviceKeyStoreConformanceTest.kt`.
- Modify: the cookbook, the samples, and `api/`.

**Interfaces (produced):**
- In the suite, the fixture is a **two-armed sealed fixture**, `sealed interface Fixture { class Volatile(val fresh: suspend () -> DeviceKeyStore); class Durable(val open: suspend () -> DeviceKeyStore, val corruptKey: suspend () -> Unit, val clearKey: suspend () -> Unit) }`. The KDoc states what the `Volatile` arm cannot detect.
- `public class FileDeviceKeyStore(directory: Path)`. The key is a JWK file (x, y, d) with mode `0600`, self-tested on decode. Grants go through `FileChannelDurableStore`.
- `public fun DeviceKeyStore.Companion.platform(appId: String, directory: Path): DeviceKeyStore`.

- [ ] **Step 1: Write the suite's cases.**
  - `keyIsStableAcrossCalls`.
  - `keyIsStableAcrossReopen`: Durable only.
  - `grantsSurviveReopen`: Durable only.
  - `keepsNewestPerIssuer`.
  - `corruptKeyIsReplacedNotFatal`: Durable only. Expect a new id, a warning log and no throw.
  - `clearedKeyStartsAsANewDevice`.
  - `concurrentFirstCallsAgree`.
- [ ] **Step 2: Implement the file store.** Run `:kuilt-identity:jvmTest` and expect PASS. Also run `fileIsOwnerOnly` (POSIX permissions `rw-------`).
- [ ] **Step 3: Docs, `api/` and the pre-merge build, then commit.** Commit: "identity: a device-key store conformance suite and the desktop file store (#3159)".

### Task 3.2: Apple Keychain store [after 3.1]

**Files:**
- Create: `appleMain/KeychainDeviceKeyStore.kt` and `appleTest/KeychainDeviceKeyStoreConformanceTest.kt`.
- Modify: `api/`, on macOS.

**Interfaces (produced):** `public fun DeviceKeyStore.Companion.platform(appId: String): DeviceKeyStore`.
- The key is a JWK (x, y, d), self-tested on decode, in a `kSecClassGenericPassword` item. Service `"us.tractat.kuilt.identity.<appId>"`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, `kSecAttrSynchronizable = false`.
- Grants go in `NSFileManagerDurableStore` under Application Support/`<appId>`.

- [ ] **Step 1: Probe first.** Run `SecItemAdd` + `SecItemCopyMatching` from `macosArm64Test` and `iosSimulatorArm64Test`. **If either returns `errSecMissingEntitlement` (-34018) or prompts, stop and report.** Test binaries are not app bundles, and the answer decides the test strategy.
- [ ] **Step 2: Write `keychainItemIsThisDeviceOnlyAndUnsynced`.** Read the item's attributes back and assert both. Then implement, and run the suite on both Apple targets.
- [ ] **Step 3: `api/` and the pre-merge build on macOS, then commit.** Commit: "identity: the Apple Keychain device-key store (#3159)".

### Task 3.3: Android Keystore store [after 3.1]

**Files:**
- Create: `androidMain/AndroidKeystoreDeviceKeyStore.kt` and `androidUnitTest/AndroidKeystoreDeviceKeyStoreTest.kt`.
- Modify: `api/`.

**Interfaces (produced):** `public fun DeviceKeyStore.Companion.platform(appId: String, context: Context): DeviceKeyStore`.
- An EC P-256 `KeyGenParameterSpec` under alias `"kuilt-identity-<appId>"`, with `PURPOSE_SIGN` and `DIGEST_SHA256`, signing through `Signature("SHA256withECDSA")`.
- The DER signature is converted to raw `r‖s` with `cryptography-serialization-asn1` (an `ECDSA-Sig-Value` decode, left-padded to 32 bytes each). That is encoding work, not curve math.
- Grants go under `context.noBackupFilesDir/<appId>`.

- [ ] **Step 1: Write the tests.** Robolectric has no AndroidKeyStore provider, so the store runs over an internal `KeystoreBackend` seam, and the suite runs against a JCA-backed fake of it. Add `derToRawIsLeftPaddedAndFixedWidth`, with vectors whose `r` and `s` have leading zero bytes. Add `keystoreSpecIsSignOnlyAndDeviceBound`: the `KeyGenParameterSpec` the store builds names the `AndroidKeyStore` provider and `PURPOSE_SIGN` alone. `KeystoreBackend` has no member that returns private-key bytes, so the store cannot export the key.
- [ ] **Step 2: Implement, run `testDebugUnitTest`, update `api/`, run the pre-merge build, then commit.** The PR body states that the real-Keystore path has **no automated runtime test** in this repo and needs a device check by Iain. Commit: "identity: the Android Keystore device-key store (#3159)".

### Task 3.4: wasmJs WebCrypto + IndexedDB store [after 3.1, 0.8, #3232]

**Files:**
- Create: `wasmJsMain/WebCryptoDeviceKeyStore.kt` and `wasmJsTest/WebCryptoDeviceKeyStoreConformanceTest.kt`.
- Modify: `api/`.

**Interfaces (produced):** `public fun DeviceKeyStore.Companion.platform(appId: String): DeviceKeyStore`.
- `crypto.subtle.generateKey({name:"ECDSA", namedCurve:"P-256"}, extractable = false, ["sign"])`.
- The `CryptoKeyPair` is stored by structured clone in an IndexedDB object store, database `"kuilt-identity-<appId>"`. The id comes from exporting the **public** key raw and compressing it through cryptography-kotlin.
- Signing calls `subtle.sign` with `{name:"ECDSA", hash:"SHA-256"}`, whose output is already raw `r‖s`.
- Grants go in `IndexedDbDurableStore`.

- [ ] **Step 1: Write `webCryptoKeyIsNonExtractable`** (`privateKey.extractable == false`, and `exportKey` on it rejects), then implement, and run the suite under `wasmJsTest`.
- [ ] **Step 2: `api/` and the pre-merge build, then commit.** The PR body names the browser it ran in. Commit: "identity: the WebCrypto device-key store (#3159)".
