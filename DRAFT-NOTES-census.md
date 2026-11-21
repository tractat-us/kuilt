# Census for Tasks 1.8 and 2.4 (#3159)

Draft notes on branch `draft/3159-census-1.8-2.4`. **Not a slice; never merge this file.**

- Base: `origin/identity/3211-admission-proof` at **`65257d458`** ("real P-256 proof arms"), the
  Task 1.4 branch, 48 commits over `origin/main` `d951b4c1d`. The 1.4 branch is still moving.
- Tree state the census describes: **after 1.4, before 1.5, 1.6 and 1.7.** Many Part 1 sites will
  already have moved by the time 1.8 starts (1.5 reshapes `tryResume`, 1.7 rewrites the joiner's
  reconnect and the `ResumeHarness`). Each row says which task should already have touched it, so the
  1.8 worker can treat a missing hit as expected rather than as a census error.
- Plan: `origin/epic/3159:docs/superpowers/plans/2026-11-12-identity-key-bound-admission.md`
  (Amendment A § 1.8 / § 2.4 override the task text). Spec: same branch,
  `docs/superpowers/specs/2026-11-12-identity-key-bound-admission-design.md`.
- Method: `git grep` / `rg` over every tracked file (examples/, spike/, demo/, build-logic/ included),
  plus a receiver-classifying Python pass for `.host(` (941 lexical `.host(` hits; 744 are `Loom.host`).
  No Gradle was run, so every count is lexical. Where a count matters, the compiler is the final word.

---

## Part 1: Task 1.8 (retire `Resume`, `ResumeAck`, `ResumeSecret`)

### 1.0 Two families of "resume": 1.8 deletes one and keeps the other

| Family | Examples | 1.8 action |
|---|---|---|
| **The wire frame and its secret** | `AdmitMessage.Resume`, `AdmitMessage.ResumeAck`, `ResumeSecret`, `Welcome.resumeSecret`, `Resume.resumeSecret`, `ResumeToken.secret`, `handleResume`, `handleResumeAck`, `resumeSecrets`, `heldResumeSecretCount`, `RejectCode.ResumeTokenInvalid` / `ResumeWindowNotYetOpen` / `ResumeWindowExpired` (Decided 20) | **Delete** |
| **The return machinery** | `Room.resume`, `Room.resumeToken`, `ResumeToken` (minus `secret`), `RoomId`, `ResumeResult` and its `JoinerOutcome`s, `JoinerResumeMachine`, `JoinerReconnectController` / `tryResume`, `JoinerReconnectEvent`, `MembershipEvent.Resumed`, `ResumeHarness` | **Keep.** 1.5 changes `tryResume`, 1.7 changes `JoinerResumeMachine` and the harness; 1.8 deletes none of it |

Counts across the tree, `api/` excluded: `AdmitMessage.Resume`/`ResumeAck` 34 lines in 9 files;
`.resumeToken` 62 lines in 26 files; `.resume(` 68 lines in 29 files; `JoinerResumeMachine` 89 lines
in 24 files; `ResumeResult.` 274 lines in 40 files. The last four are the kept family.

### 1.1 Production sites (main source sets)

**`kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/`**

| File:line | What | Action |
|---|---|---|
| `partition/ResumeSecret.kt` (whole file, 14 refs) | the type | delete file |
| `admit/AdmitMessage.kt:14` | `import …ResumeSecret` | delete |
| `admit/AdmitMessage.kt:139-145` | `Welcome` KDoc: `[roomId]` "mint their ResumeToken", `[resumeSecret]` paragraph | rewrite / delete |
| `admit/AdmitMessage.kt:162` | `Welcome.resumeSecret` | delete |
| `admit/AdmitMessage.kt:166` | `Reject` KDoc "a joiner's [Hello] or [Resume]" | drop "or [Resume]" |
| `admit/AdmitMessage.kt:190-213` | `Resume` (`@SerialName("resume")`) | delete |
| `admit/AdmitMessage.kt:215-223` | `ResumeAck` (`@SerialName("resume-ack")`) | delete |
| `admit/RejectCode.kt:4` | KDoc "[Hello] or [AdmitMessage.Resume]" | edit |
| `admit/RejectCode.kt:47-57, 64-72, 89-96` | the three resume codes | delete |
| `admit/RejectCode.kt:117-119` | `fromId` branches | delete (the ids now decode as `Unknown`) |
| `admit/ProtocolVersion.kt:50-56` | `CURRENT`/`MIN`/`MAX` = 4 on this branch | 7 by 1.8 (5 at 1.5, 6 at 1.6) |
| `SeamRoom.kt:75` | `import …ResumeSecret` | delete |
| `SeamRoom.kt:763` | KDoc "`scope.launch { handleResume }`" | edit |
| `SeamRoom.kt:812-836` | `resumeSecrets` map and its invariant KDoc | delete |
| `SeamRoom.kt:1011` | KDoc "the host's `ResumeAck` is never delivered" | edit (1.7's Welcome(Restored)) |
| `SeamRoom.kt:1200` | comment naming `ResumeAck`/`handleResumeAck` | edit |
| `SeamRoom.kt:1406-1425` | the `ResumeAck` send on a resume (`ackBytes`) | delete (1.5 says which path sends it; 1.8 removes it) |
| `SeamRoom.kt:1494` | KDoc "the host's `ResumeAck` — buffered" | edit |
| `SeamRoom.kt:1657-1666` | `is AdmitMessage.Resume` / `is AdmitMessage.ResumeAck` arms in `handleAdmitFrame` | delete (the `when` is exhaustive, so the compiler finds them) |
| `SeamRoom.kt:1755-1768` | `admitPeer`: secret minted under the lock | delete; keep the `retire` + `addToRoster` |
| `SeamRoom.kt:1787` | `welcome.copy(resumeSecret = resumeSecret)` | send `welcome` |
| `SeamRoom.kt:1968-2030` | `handleResume` (the only producer of the three reject codes) | delete |
| `SeamRoom.kt:2055` | KDoc "[AdmitMessage.ResumeAck], not as Welcome" | edit |
| `SeamRoom.kt:2118` | `resumeMachine?.mintTokenIfAbsent(welcome.roomId, welcome.resumeSecret)` | `mintTokenIfAbsent(welcome.roomId)`, see Part 3 § P12 |
| `SeamRoom.kt:2151-2190` | `handleResumeAck` | delete (1.7 has moved its job to the Welcome(Restored) handler) |
| `SeamRoom.kt:3347` | KDoc list "… a `ResumeAck` — stay inline" | edit |
| `SeamRoom.kt:3882-3883` | `resumeSecrets.remove(peerId)` in member pruning | delete |
| `SeamRoom.kt:3922` | `internal fun heldResumeSecretCount()` | delete |
| `SeamRoom.kt:4211-4214` | `Room.resume` KDoc: sends `Resume`, `ResumeAck → Success` | rewrite (1.7 should have) |
| `SeamRoom.kt:4350-4351` | `resumeSecrets.clear()` on leave | delete |
| `partition/ResumeToken.kt:6-31` | KDoc: claim 2 (the secret), "bearer secret", "serializes as hex" | rewrite; the token is no longer a credential |
| `partition/ResumeToken.kt:39` | `val secret: ResumeSecret` | delete |
| `partition/JoinerResumeMachine.kt:312` | `fun mintTokenIfAbsent(roomId: String?, secret: ResumeSecret?)` | drop `secret` (Part 3 § P12) |
| `partition/JoinerResumeMachine.kt:838` | `resumeSecret = token.secret` in the `Resume` it builds (`:834`) | gone with 1.7's re-Hello |
| `partition/JoinerResumeMachine.kt:81-96, 130, 168, 222-223, 346-365, 457-473, 542-547, 632-651, 735, 777, 791-802, 926, 993` | KDoc/comments naming `ResumeAck`, `Resume`, `ResumeWindowNotYetOpen` (the #1637 dwell) | 1.7 removes the dwell and the frame; 1.8 sweeps any comment left |
| `partition/JoinerReconnectController.kt:23` | KDoc "[ResumeSecret] and this controller hold its window" | edit |
| `partition/JoinerReconnectController.kt:244, 262, 270, 301` | KDoc naming `ResumeAck`, `RejectCode.ResumeWindowNotYetOpen`, `RejectCode.ResumeTokenInvalid` | edit (Part 3 § P11) |
| `partition/ServerClusterReconnect.kt:16` | KDoc "another server holds neither the joiner's [ResumeSecret] nor its window" | edit: "holds no seat for its key" |
| `MembershipEvent.kt:130` | KDoc "a `ResumeAck` handled…" | edit |
| `Room.kt:358` | KDoc: host verdicts "do not travel as values" (they travel as reject codes) | edit (Part 3 § P11) |

**Other modules' production sources**

| File:line | What | Action |
|---|---|---|
| `kuilt-session/build.gradle.kts:63` | comment: CryptoRand is "the platform CSPRNG behind `ResumeSecret.generate()`" | keep the dependency (nonces use it, Amendment A § 1.3); reword the comment |
| `kuilt-liveness/src/commonMain/kotlin/us/tractat/kuilt/liveness/HeartbeatConfig.kt:35` | KDoc "`ResumeAck`/`Reject`" | edit. **A second module**; the plan does not list it |
| `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/ClusterClient.kt:77` | KDoc "`RejectCode.ResumeTokenInvalid`, since each server mints its own `RoomId`" | rewrite: another server holds no seat for the key, so the client is admitted fresh (Amendment A names this one) |
| `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt:59` | `import …ResumeSecret` | delete |
| `RoomConformanceSuite.kt:391-409` | `resumeSuccessIsAcknowledged`, `resumeNotYetOpenHasExactCode`, `resumeExpiredHasExactCode`, `resumeInvalidTokenHasExactCode` (the `assertResumeMapping` rows) | delete or reshape. 1.7 moved the harness reshape here; whatever survives may not name the three codes |
| `RoomConformanceSuite.kt:410-466` | `aResumeWithoutTheMembersSecretIsRefusedAndLeavesItsWindowOpen` (`token.copy(secret = ResumeSecret.generate())` at `:433`) | delete; its property moves to a key-forgery row (1.9's `differentKeyOnLiveIdIsRefused`) |
| `RoomConformanceSuite.kt:516-520` | `resumeAfterLeaveIsLocalWindowClosed` asserts `RejectCode.ResumeWindowNotYetOpen` | rewrite (1.7) |
| `RoomConformanceSuite.kt:1454-1494` + `aTokenMintedForAnotherRoomIsRefused` `:1506` | KDoc and row pinned to `ResumeTokenInvalid` | rewrite: the room id is now inside the signed Challenge/Proof (Part 3 § P2) |
| `spike/src/appleMain/kotlin/spike/suite/ConnectivitySuite.kt:1183, 1347, 1358, 1508-1569, 1587` | the #1637 probe's **whole verdict logic** is keyed on `RejectCode.ResumeWindowNotYetOpen` and on a "real `ResumeAck`" | fix or knowingly leave (Part 3 § P13). Compiles only with `-PincludeSpike` |
| `spike/src/appleMain/kotlin/spike/suite/ResumeLaneProbe.kt:109, 144` | KDoc "resolved on a real `ResumeAck`" | edit |

### 1.2 Test, sample and harness files: delete / rewrite / keep

`kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/` unless stated.

| File | Hits (line) | Verdict |
|---|---|---|
| `partition/ResumeSecretTest.kt` | whole file | **delete** (the type goes) |
| `ForgedResumeTest.kt` | whole file | **delete**, citing the map in § 1.3 below |
| `admit/AdmitMessageTest.kt` | `:5` import; `:100-104` `Resume` round-trip; `:106-127` the secret wire forms; `:129-132` "`ResumeAck` and Goodbye round-trip" | **rewrite**: delete the Resume and secret tests, keep a Goodbye-only round-trip, and **add** a test that a frame tagged `"resume"` or `"resume-ack"` decodes to `null` (dropped). That is the positive control that the serial names are really gone |
| `admit/RejectCodeWireCompatTest.kt` | `:43-48` uses `ResumeWindowExpired` as its sample code; `:66-70` retryable asserts on the three | **rewrite**: pick a surviving code (`BadProof`) for the wire sample; drop the three asserts; **add** `fromId("resume-token-invalid")` is `Unknown` and retryable |
| `admit/ProtocolVersionTest.kt` | version pins | **rewrite** (bump to 7; Amendment A § 1.3) |
| `admit/HelloVersionWireCompatTest.kt` | version gate | **rewrite** if it pins the number |
| `ResumeAckAfterHostLostTest.kt` | `:33` import; `:48-54, 81, 94, 128, 167, 226, 271, 278, 410` `AdmitMessage.ResumeAck` driven by hand; `:314` `resumeSecret =` on a Welcome | **rewrite** if 1.7 has not: the race is now a `Welcome(returnKind = Restored)` handled after `HostLost`; count `Hello`s instead of `Resume`s at `:410` |
| `RoomResumeTest.kt` | `:186-218` test 4a "wrong roomId is refused as ResumeTokenInvalid" (`.secret` at `:212`); `:234`; `:337-352` the code-mapping table; `:423-480` test 6 "ResumeAck is host-gated and Resumed is idempotent" (`:465`) | **rewrite** (1.7 owns most of it). 4a: **delete**, replaced by a proof-for-another-room case (§ 1.3, not covered today). Table: drop the three rows. Test 6: becomes "a Restored Welcome from a non-host is ignored, a duplicate fires one `Resumed`" |
| `RoomResumeTimeoutTest.kt` | `:65` comment "no ResumeAck/Reject can arrive" | **keep**, fix the comment |
| `ConcurrentResumeHangTest.kt` | `:57, 129, 133, 198, 214` comments on `ResumeAck` | **keep** (1.7 rewrites the frame it waits for), fix comments |
| `FastReconnectRaceTest.kt` | `:54, 57` reason strings; `:118`; `:125` `ResumeWindowNotYetOpen` (the dwell); `:175, 211` `ResumeWindowExpired` (terminal) | **rewrite**: the `:125` dwell arm goes with 1.7's dwell removal; the `:211` "terminal code surfaces HostLost at once" arm keeps its property with a surviving terminal code (`KeyConflict` or `RoomMismatch`) |
| `JoinerReconnectTest.kt` | `:233` comment; `:307` `FailureReason.Refused("resume-window-expired", RejectCode.ResumeWindowExpired)` | **rewrite**: after 1.7 a closed window ends as a fresh admission (`ResumeResult.WindowClosed` per 1.7), not a refusal. Assert what 1.7 decided |
| `MembershipEventDropContractTest.kt` | `:73` KDoc | **keep**, fix text |
| `RoomIdentityTest.kt` | `:134` KDoc "never sends `ResumeAck`" | **keep**, fix text |
| `TransportCloseWindowTest.kt` | `:372, 389` `heldResumeSecretCount()` | **rewrite**: drop the secret-pruning asserts, keep the window test |
| `WindowLevelTest.kt` | `:632-634` KDoc: "the clearing path is [SeamRoom.handleResumeAck] and nothing else" | **keep**, re-point the KDoc at 1.7's Welcome(Restored) handler (a dangling `[link]`, so Dokka warns) |
| `partition/JoinerReconnectControllerTest.kt` | `:36-37` `secretA/B`; 11× `ResumeToken(…, secret = …)` (`:116, 137, 157, 274, 330, 344, 359, 363, 375, 391, 421`) | **rewrite**. 1.5 turns these into `ProvenResume`s (`tryResume(ProvenResume, at)`); whatever is left drops `secret =` |
| `partition/ResumeFlightOwnershipTest.kt` | `:37` KDoc; `:146, 225` `mintTokenIfAbsent("room-1", ResumeSecret.generate())` | **rewrite**: one-argument `mintTokenIfAbsent` |
| `partition/ThrowawaySeamCloseCancellationTest.kt` | `:72` same | **rewrite** (mechanical) |
| `partition/ServerClusterReconnectTest.kt` | `:43` `secret = ResumeSecret.generate()` | **rewrite** (mechanical) |
| `partition/ResumeRefusalReportingTest.kt` | `:55, 129-131, 166, 259`: all about reporting a persistent `ResumeWindowNotYetOpen` (#1637's dwell) | **delete** if 1.7 has not already; nothing produces that refusal once the dwell is gone |
| `partition/SubTimeoutBlipResumeTest.kt` | `:74, 284` comments on `ResumeWindowNotYetOpen` | **rewrite**: the property (a blip the host never saw does not end in `HostLost`) survives as "re-Hello against a connected seat restores it" (1.5's connected-seat `Restore`) |
| `FakeKeyVirtualTimeTest.kt`, `AdmitKeysTest.kt`, `KeyAdmissionTest.kt` | none | keep |
| `kuilt-session/src/commonSamples/…/AgentCookbookSamples.kt` | `:131` `resumeAfterDropSample` (`room.resumeToken` / `room.resume`); `:158-162` `classifyRejectCodeSample` names all three codes | **rewrite** both (`classifyRejectCodeSample` is also the `@sample` on `RejectCode`, `RejectCode.kt:22`); re-copy the cookbook blocks (§ 1.4) |
| `kuilt-session-test/src/commonTest/…/FakeRoomTest.kt` | `:38` import; `:241, 488, 496, 611, 618` `ResumeToken(…, secret = ResumeSecret.generate())` | **rewrite** (mechanical: drop `secret =`) |
| `kuilt-cluster/src/commonTest/…/EndpointRotationTest.kt` | `:12` import; `:91, 134` `secret =`; `:116` scripted `ResumeResult.Refused("resume-token-invalid: session-mismatch", RejectCode.ResumeTokenInvalid)` | **rewrite**: the fake's scripted refusal becomes the post-1.7 cross-server outcome (fresh admission, `ResumeResult.WindowClosed`) |
| `kuilt-conformance/src/commonTest/…/ReferenceResumeHarness.kt` | `:60-63` `roomLevelVerdict(resume: AdmitMessage.Resume)`, `resume.resumeSecret == null`; `:81-92` counts `Resume` frames; `:142` `token.copy(secret = it.secret)` | **rewrite** in 1.7 (Amendment A § 1.7 moved the harness reshape there); 1.8 checks nothing is left |
| `kuilt-conformance/src/jvmTest/…/RoomResumeHarnessTest.kt`, `RoomConformanceGapDeclarationTest.kt` | harness shape | 1.7 |
| `examples/src/test/…/ResumeTokenFailoverTest.kt` | `:50` KDoc, `:220` `RejectCode.ResumeTokenInvalid` | **rewrite** (1.7 owns it: "one client device modelled as two stores", Amendment A § 1.4-1.6) |
| `spike/…/ConnectivitySuite.kt`, `ResumeLaneProbe.kt` | see § 1.1 | fix or knowingly leave |

### 1.3 `ForgedResumeTest.kt`: where each case now lives

Every case forges a `Resume` frame for another member. After 1.8 there is no frame to forge. The
attack becomes "claim another member's seat with a Proof", and the key → seat table decides it.

| `ForgedResumeTest` case (line) | What it pinned | Covered after 1.8 by | Status |
|---|---|---|---|
| `a member presenting another members token with a guessed secret is refused and the window survives` (`:103`) | a bystander cannot spend a partitioned victim's window by naming its id | **1.5** `bystanderClaimingALiveMembersIdIsRejectedKeyConflict` (connected victim). Its **partitioned** twin is not named in 1.5's list: by Decided 18 the same id with a different key against a *partitioned* seat **releases the seat and admits the newcomer**. That is the cleared-key-store path, by design | **Not covered as a "refused" property; by design it is no longer refused.** The 1.5 worker should add one test that pins the new answer: the victim's seat is released, the attacker gets a fresh seat with *its own* key (never the victim's identity), and the victim's later Proof under its own key is decided again (Decided 23/30), not locked out |
| `a resume frame carrying no secret is refused and the window survives` (`:161`) | the pre-#3165 frame shape | nothing: the frame is gone, and an older peer is refused by the version gate (`ProtocolVersionAdmissionTest`) | **delete, no replacement** |
| `a bystander sniffing every frame across two resume episodes cannot forge the victims resume` (`:224`) | no frame a bystander sees carries the victim's secret | `KeyAdmissionTest.proofReplayedUnderAnotherNonceIsRejected` (a captured proof is useless under a fresh host nonce) and `proofForAnotherHostIsRejected` | **covered** (replay of what was sniffed). The *passive* half (nothing secret is broadcast) has no analogue: keys are public |
| `a members secret is pruned when it leaves` (`:278`) | `heldResumeSecretCount` drops on leave | the held-seat lifetime is the key → seat table's; spec "the host forgets a departed member's key … once its last grant expires" is a 2.5/2.6 property | **delete**; the table-pruning test belongs to 2.5/2.6 |
| `a host that leaves drops every members secret` (`:299`) | no secret outlives the room | nothing secret is held any more | **delete** |
| `a wrong-room forgery is refused even by a hold policy that skips the room check` (`:325`) | a custom `JoinerReconnectController` never sees a wrong-room claim | the room id is signed into `Challenge`/`AdmissionProof` and checked in `KeyAdmission.checkProof` (`roomId = signedRoomId()`), and 1.5's `ProvenResume` carries the room's own id, so a custom controller cannot see another room's id | **Not covered by a named test.** `KeyAdmissionTest` has `proofForAnotherHostIsRejected` but no `proofForAnotherRoomIsRejected`. **Keep as a key-forgery case:** a Proof signing another room id is `Reject(BadProof)` and a counting controller sees zero `tryResume` |
| `a forgery for a departed member is refused and its window still expires` (`:403`) | after `Goodbye`, a forgery cannot resume onto the empty seat and cancel its expiry timer | `KeyAdmissionTest.seatIsReReadAtProofTimeNotChallengeTime` and `goodbyeDuringTheProofCheckLeavesNoGhost` cover the Goodbye races; the **expiry-timer** half (a claim against a departed id must not cancel `WindowExpired`, the `Farewell(expired = true)` a star bystander depends on) is not | **Not covered. Keep as a key-forgery case:** after the victim's `Goodbye`, a Proof from another key claiming the victim's id is admitted as a newcomer under its own id and key; `WindowExpired(victim)` still fires; no `Resumed` |

So `ForgedResumeTest.kt` becomes a short `KeyForgeryTest` with three cases: the partitioned-seat
claim (owned by 1.5), proof for another room, and a departed member's window still expiring. Or the
1.8 PR deletes the file and lands those three in `KeyAdmissionTest`.

### 1.4 Docs (Part 1)

| Where | What | Action |
|---|---|---|
| `docs/agent-cookbook/session.md:43-56` | "Rejoin & reconnect": `ResumeToken` presented to `Room.resume`; host keys the seat by `token.peerId` | 1.9 rewrites it; 1.8 must not leave the `secret` story |
| `session.md:58-80` | `<!-- verbatim from …#resumeAfterDropSample -->` | re-copy if the sample changes |
| `session.md:80-87` | "the host's own verdicts … arrive as the `RejectCode` on `Refused`" | false after 1.8 (Part 3 § P11) |
| `session.md:89-105` | `<!-- verbatim from …#classifyRejectCodeSample -->` quotes all three codes | **breaks `verifyDocCitations`** when the sample changes; re-copy |
| `session.md:241` | `tryResume validates the returning peer's ResumeToken (right room, window still open, token not already used)` | rewrite for `ProvenResume` (1.5) |
| `session.md:245` | "**You never see a forged resume.** … carries that member's `ResumeSecret` (#3165). Don't check the secret yourself." | **rewrite** (1.5 or 1.8; the plan names neither) |
| `session.md:318` | "A peer that returns through `Room.resume` emits…" | check against 1.7 |
| `.claude/skills/kuilt-primitives/SKILL.md:16` | row: "`Room.resumeToken` + `Room.resume`; `ResumeResult.JoinerOutcome` (`code.retryable`)" | 1.9 rewrites it ("return is automatic"); body budget 8,192 B |
| `docs/agent-cookbook.md:25` | index row "`Room.resumeToken` + `Room.resume`" | 1.9 |
| `build-logic/src/main/kotlin/kuilt.publish.gradle.kts:225-227` | `"kuilt-session"`: "…roster, reconnect tokens and partition detection." | "key-bound return" (plan) then `python3 .github/scripts/llms-txt.py` |
| `llms.txt:193` | generated from the above | regenerate, never hand-edit |
| `AGENTS.md:55` | `:kuilt-session` row: "reconnect tokens" | **edit** (not in the plan's list) |
| `README.md:137` | module row "reconnect tokens" | **edit** (not in the plan's list) |
| `Writerside/topics/modules.md:27` | "reconnect tokens" | **edit** (not in the plan's list) |
| `Writerside/topics/fabrics.md:166` | "…reconnect tokens, and partition detection" | **edit** (not in the plan's list) |
| `Writerside/topics/cluster-server-client.md:174-187` | "a `ResumeToken` from server-A is unknown to server-B" | true in effect; reword "no seat for its key" |
| `docs/architecture.md:709-711` | `RejectCode.ResumeTokenInvalid` and the client fresh-joins | **edit** (not in the plan's list) |
| `docs/usage.md:146-148, 625` | `room.resumeToken`, `room.resume(token)`, cross-server `ResumeToken` | check against 1.7 |
| `kuilt-session-test/README.md:29` | `room.resumeResult = ResumeResult.Refused("resume-window-expired", RejectCode.ResumeWindowExpired)` | **edit** (example uses a deleted code) |
| `kuilt-cluster/module.md:147` | dependency diagram "`ResumeToken`" | keep (type survives) |
| `examples/README.md:63` | `ResumeTokenFailoverTest.kt` row | follow 1.7's rewrite |
| `HARDWARE-RUNBOOK-2026-07-28.md:122, 213`, `spike/CONNECTIVITY-SUITE.md:350, 402, 743, 790` | field logs quoting `resume-window-not-yet-open` | leave: they are dated records |
| `docs/superpowers/**` (plans and specs, 50+ hits) | historical plans | leave |

### 1.5 Does `RejectCode.ResumeTokenInvalid` still have a producer after 1.7?

**No.** On this branch the only producer in any main source set is `SeamRoom.handleResume`
(`SeamRoom.kt:2024-2025`). It maps `ResumeResult.TokenInvalid` from three places: `:2001`
missing-secret, `:2002` session-mismatch, `:2004` secret-mismatch, and the controller's own
`DefaultJoinerReconnectController.kt:61` session-mismatch. `JoinerResumeMachine`,
`ServerClusterReconnect` and `ClusterClient` only mention it in KDoc. After 1.7 the joiner sends no
`Resume`, so `handleResume` is unreachable even before 1.8 deletes it. The Proof path (1.4/1.5)
answers a bad claim with `BadProof` or `KeyConflict`, or decides again, and never with
`ResumeTokenInvalid`. Decided 20 removes all three codes anyway. The same is true of
`ResumeWindowNotYetOpen` (`:2021`) and `ResumeWindowExpired` (`:2023`). Grep before deleting, as the
task says: tests produce them only through fakes (`EndpointRotationTest`, `FakeRoom.resumeResult`).

### 1.6 ABI removals the 1.8 PR body must record

`kuilt-session` (`api/jvm`, `api/android`, `kuilt-session.klib.api`; 37 + 37 + 65 matching lines
today):

- `us.tractat.kuilt.session.partition.ResumeSecret` (the class, `Companion.generate`, `SIZE_BYTES`,
  `matches`, `equals`/`hashCode`/`toString`, its serializer).
- `AdmitMessage.Resume`: class, constructors, `component1-4`, `copy`, `getTokenPeerId`,
  `getTokenRoomId`, `getIssuedAt`, `getResumeSecret`, `$serializer`, `Companion`.
- `AdmitMessage.ResumeAck` (the object and its serializer).
- `AdmitMessage.Welcome.resumeSecret`: `getResumeSecret`, `component7`, and the constructor and
  `copy` change arity (JVM `copy-aRiy5tk` mangling changes). **A source break for any consumer that
  builds a `Welcome` positionally.**
- `RejectCode.ResumeTokenInvalid`, `RejectCode.ResumeWindowNotYetOpen`,
  `RejectCode.ResumeWindowExpired`. A consumer's exhaustive-looking `when` on them stops compiling;
  the ids now decode as `RejectCode.Unknown(id)`, **retryable**.
- `ResumeToken.secret`: `getSecret`, `component4`, constructor, `copy-rI7dSL0`. **`ResumeToken` is
  `@Serializable` and apps persist it** (Amendment A § 1.8): a stored token decoded under a strict
  `Json` now fails on the unknown `"secret"` key. Say so, and say what an app should do (drop it, or
  decode with `ignoreUnknownKeys`).
- **`kuilt-conformance` too** (not in the plan's list): the public test methods
  `aResumeWithoutTheMembersSecretIsRefusedAndLeavesItsWindowOpen`, `resumeNotYetOpenHasExactCode`,
  `resumeExpiredHasExactCode`, `resumeInvalidTokenHasExactCode`, possibly
  `resumeSuccessIsAcknowledged` / `aTokenMintedForAnotherRoomIsRefused`, and `ResumeHarness` if 1.7
  did not already reshape it (`kuilt-conformance/api/jvm/kuilt-conformance.api:529-615`).
- `kuilt-session-test`: no direct hit (`FakeRoom` exposes `ResumeToken`/`ResumeResult`, which stay).
  Re-run `updateKotlinAbi` anyway, since `ResumeToken`'s shape is in its signatures.

---

## Part 2: Task 2.4 (host entry points require `HostAdmission`)

### 2.1 Call-site census

`ROOM` = a `RoomFactory.host(` call (receiver resolved to `SeamRoomFactory`/`FakeRoomFactory`/a
`RoomFactory`-typed name). **174 sites** (165 resolved + 7 resolved by hand + 2 in KDoc), against
744 `Loom.host` calls the pass set aside.

**`RoomFactory.host(` by module**

| Module / source set | Sites | Files |
|---|---|---|
| `kuilt-session` commonTest | 117 | 40 |
| `kuilt-session` jvmTest | 2 | 2: `RealKeyAdmitTest.kt:53`, `SeamRoomFactorySystemClockTest.kt:25` |
| `kuilt-session` commonMain | 1 | `RoomHost.kt:92` (`LoomRoomHost.start`) |
| `kuilt-session` commonSamples | 1 | `AgentCookbookSamples.kt:114` (`callerSuppliedRoomIdSample`, quoted verbatim) |
| `kuilt-conformance` commonMain | 39 | `RoomConformanceSuite.kt`: 38 rows through `h.hostFactory.host(` (`:427 … :1902`) + the `defaultHarness` forwarder `:2273` |
| `kuilt-conformance` commonTest | 2 | `ReferenceResumeHarness.kt:129, 136` (forwarders) |
| `kuilt-conformance` jvmTest | 1 | `RoomChannelStateAfterLeaveControlTest.kt:82-83` `StuckOpenRoomFactory` (a forwarder over `delegate`; **the script cannot see it**) |
| `kuilt-session-test` commonTest | 4 | `FakeRoomFactoryTest.kt:29, 57, 58, 65` |
| `kuilt-session-test` commonMain | KDoc | `FakeRoomFactory.kt:27-28` |
| `kuilt-websocket` jvmAndAndroidMain | 1 | `KtorRoomHost.kt:105` |
| `kuilt-websocket` jvmTest | 1 | `WebSocketDeadLinkConsequenceTest.kt:187` |
| `kuilt-nw` commonTest / appleTest | 2 + 1 | `NwMeshRoomPartitionTest.kt:99`, `NwRejoinTest.kt:127`, `NwFactoryJoinHostDropLoopbackTest.kt:74` (appleTest: macOS-only run) |
| `kuilt-cluster` commonTest | 1 | `ClusterClientCloseTest.kt:448` |
| `kuilt-game` commonSamples | 1 | `GameSamples.kt:464` (`sampleGameOverRoom`, run by `GameSamplesRunTest.gameOverRoomHolds`) |
| `examples` | 0 direct | all through `KtorRoomHost(` (below) |

`kuilt-session` commonTest, per file (line numbers of each `.host(`):

```
AdmitKeysTest 56,74,97,134 · FakeKeyVirtualTimeTest 61,87 · ForgedResumeTest 107,165,230,281,302,348,407
HostAuthoritativeLeaveTest 48,95 · JoinDeviceIdTest 34 · JoinWindowHostIdentityTest 185,201
JoinerAdmitFailureTest 55,107 · JoinerWelcomeGateTest 44 · KeyAdmissionTest 423 · LivenessRouteGateTest 169,233
LocalFabricTest 338,484 · MemberInboxCapacityTest 42,69,106,137 · MemberMetadataConvergenceTest 69
MembershipEventDropContractTest 125 · MeshRoomRecoveredWindowExpiryTest 97,364 · MeshRoomRecoveryTest 81,285
MultiRoomIsolationTest 55,56 (+29 KDoc) · MuxHubPrincipalTest 54 · PartitionRoleTest 68,80
PerMemberFramesSampleTest 37 · ProtocolVersionAdmissionTest 53,98,149,172,217 · RehostedRoomAdmissionTest 59,60
RoomAttestedPrincipalsTest 52,71 · RoomBoundAdmissionTest 48,76,118 · RoomChannelReplicatorTest 103,139,182
RoomChannelTest 60,90,110,121,182,238,271 · RoomFlushTest 318
RoomIdentityTest 61,62,100,101,112,142,143,179,195,197,218,254 · RoomLifecycleFlapTest 72,98,126,214
RoomReplicatorTest 41 · SeamRoomMemberNameTest 26
SeamRoomTest 52,61,72,81,100,118,141,163,190,214,240,262,298,311,322,339,373,392,394
StarRelayHarness 377 · StarTopologyPresenceFanoutTest 208,553 · ThreePeerLateJoinerConvergenceTest 73,122
TwoCrdtChannelIsolationTest 85,150 · TwoPeerEagerPushTest 80 · WindowEpisodeIdentityTest 171,327,500
WindowLevelTest 811,944 · partition/MeshTestHarness 88
```

**`adopt(` with its role: 66 sites in 24 files**

| Role passed | Sites | Becomes |
|---|---|---|
| `SessionRole.Host` | 22 (session tests 21, `RoomGameHarness.kt:72`) | `AdoptRole.Host(admission[, roomId = R])` |
| `SessionRole.Joiner` | 40 (session tests 38, `RoomGameHarness.kt:70`, `NwMeshRoomPartitionTest.kt:103`) | `AdoptRole.Joiner` |
| a variable | `SeamElectionLobby.kt:613` (`adoptRoom(role, …)`; callers `adoptAfterCommit(SessionRole.Host, …)` `:187` from `start`, `adoptAfterCommit(SessionRole.Joiner, …)` `:311` from `awaitRoom`) | `start` passes `AdoptRole.Host(admission)`, `awaitRoom` passes `AdoptRole.Joiner` |
| `role = if (amHost) …` | `spike/…/ConnectivitySuite.kt:706, 1250` | hand edit (spike) |

Host adopts that pass a `roomId`, which moves into `AdoptRole.Host`: `KeyAdmissionTest.kt:78`,
`RoomIdentityTest.kt:122`, `RealKeyAdmitTest.kt:81` (jvmTest).
The joiner adopt that passes a `roomId` is `RoomIdentityTest.kt:150-166`
(`` `adopt rejects a room id supplied for a joiner` ``). With `AdoptRole` it is a type error:
**delete the test**, and the `require` at `SeamRoom.kt:299-308` and its `@throws` KDoc at `:283-288`.

Full per-site list (host/joiner per line):

```
AdmitFanOutOrderingTest 602H 615J 664H 666J · AdoptTearTerminalTest 118H 119J 195H 198J 264H 266J
AdoptTest 41H 42J 76H 77J · HostAuthoritativeLeaveTest 98J · JoinDeviceIdTest 62H 63J
JoinWindowHostIdentityTest 191J 204J · KeyAdmissionTest 78H(roomId) 125H 126J 238H 240J 442J
LivenessRouteGateTest 171J 242J 244J · LocalFabricTest 344J 393H 398J 441H 445J 487J
MembershipEventDropContractTest 129J · MeshRoomRecoveredWindowExpiryTest 99J 366J · MeshRoomRecoveryTest 83J 287J
RoomIdentityTest 82H 83H 122H(roomId) 157J(roomId: delete) · StarRelayHarness 259H 269J 382J
StarTopologyPresenceFanoutTest 210J 555J 557J · WindowEpisodeIdentityTest 173J 329J 502J
WindowLevelTest 798H 813J 946J · partition/ResumeRefusalReportingTest 276H 277J · partition/SubTimeoutBlipResumeTest 362H 363J
RealKeyAdmitTest (jvmTest) 81H(roomId) · kuilt-game RoomGameHarness 70J 72H · kuilt-nw NwMeshRoomPartitionTest 103J
```

**Lobby host paths** (see Part 3 § P1 on where `admission` belongs)

- `SeamRoomFactory.electLobby(pattern)`: `SeamRoom.kt:342`; callers `ElectLobbyEntryTest.kt:25`,
  `spike/…/ConnectivitySuite.kt:356`, `Writerside/topics/picking-a-host.md:12`.
- `ElectionLobby.start(memberName, deviceId)` (interface `ElectionLobby.kt:144`, impl
  `SeamElectionLobby.kt:175`): `MeshRoomPartitionTest.kt:93`, `JoinDeviceIdTest.kt:99`,
  `election/LobbyHeartbeatTest.kt:76, 152, 194, 243, 298`,
  `election/SeamElectionLobbyTest.kt:107, 140, 222, 279, 458`, `election/LobbyPromotionTest.kt:277`,
  `AgentCookbookSamples.kt:313` (`handleEveryElectionOutcomeSample`, quoted verbatim at
  `session.md:461-472`), `spike/…/ConnectivitySuite.kt:363, 368`, `picking-a-host.md:14`.
- Direct `SeamElectionLobby(` constructions (internal, tests): `MeshRoomPartitionTest.kt:71`,
  `JoinDeviceIdTest.kt:82`, `LobbyHeartbeatTest.kt:48`, `SeamElectionLobbyTest.kt:50, 297, 338, 378, 407`,
  `LobbyPromotionTest.kt:54`. They change only if `admission` goes on the lobby's constructor.

**`RoomHost` implementers and constructions**

| Type | Declaration | Constructions |
|---|---|---|
| `LoomRoomHost` | `kuilt-session/…/RoomHost.kt:69-75` (`factory.host(pattern)` at `:92`) | `RoomEventsReplayTest.kt:34`, `MemberPrincipalTest.kt:40`, `LoomRoomHostTest.kt:35, 65, 101` |
| `NwRoomHost` | `kuilt-nw/…/NwRoomHost.kt:36` `: RoomHost by LoomRoomHost(loom, sessionConfig, deviceKeyStore)` | `NwRoomHostTest.kt:25, 41` |
| `MultipeerRoomHost` | `kuilt-multipeer/…/MultipeerRoomHost.kt:32` (same delegation) | `MultipeerRoomHostTest.kt:24, 40` (jvmTest) |
| `KtorRoomHost` | `kuilt-websocket/…/KtorRoomHost.kt:44` internal ctor + `:59` public ctor; `SeamRoomFactory.systemClock(…)` `:101`, `factory.host(pattern)` `:105` | `KtorRoomHostTest.kt:47, 104, 123, 150, 196`; examples: `ConcurrentAdmissionE2ETest.kt:85`, `ServerClusterM3E2ETest.kt:129`, `ServerClusterOverlayE2ETest.kt:64, 150`, `LearnerDisconnectE2ETest.kt:79`, `ClusterClientDurableRestartE2ETest.kt:105`, `RelayRoomTest.kt:94`, `ClusterClientFailoverE2ETest.kt:207, 208`, `ClusterClientMultiClientHardeningE2ETest.kt:109, 110`, `ResumeTokenFailoverTest.kt:89, 96`, `ClusterClientProductionPathE2ETest.kt:94`, `ServerClusterE2ETest.kt:101`; docs `kuilt-cluster/module.md:126`, `docs/usage.md:523` |
| `ControlledRoomHost` (`kuilt-cluster` jvmTest `ServerClusterSim.kt:205`), `OneRoomHost` (`ServerClusterAdmitLearnerTimeoutTest.kt:145`) | build `FakeRoom`s | **no change** |
| `ServerCluster` | takes a `RoomHost` (`ServerCluster.kt:103`) | **no change** (Amendment A drops it) |

**`RoomFactory` implementers (6, the plan's 1.1 note says 4)**: `SeamRoomFactory` (`SeamRoom.kt:155`),
`FakeRoomFactory` (`kuilt-session-test/…/FakeRoomFactory.kt:32`), two anonymous objects in
`ReferenceResumeHarness.kt:127, 134`, the anonymous `joinerFactory` in
`RoomConformanceSuite.defaultHarness` (`:2271`, added on the 1.4 branch), and
`StuckOpenRoomFactory` (`kuilt-conformance` jvmTest `RoomChannelStateAfterLeaveControlTest.kt:82`).
Each forwarder must **forward the `admission` it was given**, not mint an open one.

**Direct `SeamRoom(` constructions (internal; not in the plan's Files list)**

28 with `role = SessionRole.Host`, 31 `Joiner`, 4 with a role variable (plus the three in
`SeamRoomFactory`). What 2.4 does to them depends on how `SeamRoom`'s internal constructor carries
the admission (Part 3 § P3).

```
Host: RoomLifecycleFlapTest 157,262 · SeamRoomCloseResurrectionTest 86 · TransportCloseWindowTest 60,117,177,256,345
PartitionRoleTest 103,167,232,309 · JoinerHostTimeoutRecoveryTest 96 · DrainingGenerationReconnectTest 257
WindowLevelTest 896 · FastReconnectRaceTest 252 · LocalFabricTest 223,299 · SeamRoomDetectorTeardownTest 42
HostReconnectControllerInjectionTest 97 · JoinerReconnectTest 93,171,252,503,583 · ConcurrentResumeHangTest 153
GracefulLeaveTest 53 · AdmissionRosterConcurrencyTest (jvmTest) 70
Variable role: RoomResumeTimeoutTest 129 · RoomResumeTest 496 · LocalFabricTest 557 · ConcurrentResumeHangTest 247
```

### 2.2 Doc snippets that show a host entry point

| Where | Citation | Breaks the gate when the sample changes? |
|---|---|---|
| `docs/agent-cookbook/session.md:29-37` | `verbatim from …AgentCookbookSamples.kt#callerSuppliedRoomIdSample` (`factory.host(pattern, roomId = RoomId(lobbyCode))`) | **yes**: re-copy |
| `docs/agent-cookbook/session.md:461-472` | `verbatim from …#handleEveryElectionOutcomeSample` (`lobby.start(memberName = "Player 2")`) | **yes**, if `admission` goes on `start` |
| `docs/agent-cookbook/session.md:672-698` | `verbatim from …#hostAdmissionSample` (builds a `HostAdmission`, no host call) | only if 2.4 extends the sample to show the call; prose `:670` "No room entry point takes one yet; that is tracked by #3220" **must go** |
| `docs/agent-cookbook/session.md:703` | `verbatim from kuilt-session-test/…/AdmissionSamples.kt#admissionPolicyTestSample` | no host call |
| `docs/agent-cookbook/session.md:27` | prose `RoomFactory.host(pattern, roomId = …)` | prose; update |
| `docs/agent-cookbook/consensus.md:18` | `verbatim from …GameSamples.kt#sampleGameHostJoin` | check: `sampleGameHostJoin` has no room host; `sampleGameOverRoom` (`:464`) is uncited |
| `docs/usage.md:114` (`factory.host(Pattern(…))`), `:523` (`KtorRoomHost(`) | **uncited** | no gate; will silently rot, edit by hand |
| `Writerside/topics/fabrics.md:171` | **uncited** | edit by hand |
| `Writerside/topics/picking-a-host.md:12, 14` (`electLobby`, `lobby.start()`) | **uncited** | edit by hand |
| `kuilt-session-test/README.md:50`, `kuilt-test/README.md:54-55`, `kuilt-cluster/module.md:126` | **uncited** | edit by hand |
| `FakeRoomFactory.kt:27-28` KDoc example | n/a | edit |
| `docs/host-election-design.md:61, 69` | design-doc signatures of `adopt`/`electLobby` | historical; leave or note |
| `.claude/skills/kuilt-primitives/SKILL.md:17-18` | rows name `AdmissionPolicy` and `RoomFactory.host(…, roomId = …)` | the `…` already elides; no edit needed unless a route changes. Body budget is 8,192 B |
| `kuilt-session/…/admit/AdmissionPolicy.kt:36-37` | "No room asks one yet … tracked by #3220" | **must go** (Amendment A § 2.4); these and `session.md:670` are the only two hits for #3220 outside `docs/superpowers/` |

### 2.3 Mechanical rewrite recipe

Do the production signatures by hand first, so the compiler checks every edit the script makes:

1. `RoomFactory.host(pattern, memberName = null, roomId = null, admission: HostAdmission)`. Put
   `admission` **last**, required, so every call site must name it. Two reasons. Appending
   `admission = …` as a named argument is then correct at every call shape. And a forwarder that
   passes `(pattern, memberName, roomId)` positionally fails to compile until it forwards the
   admission too.
2. `SeamRoomFactory.host`, `adopt(seam, role: AdoptRole, …)` (drop `roomId`: it is in
   `AdoptRole.Host`), `ElectionLobby.start(…, admission)` (Part 3 § P1), `LoomRoomHost`,
   `NwRoomHost`, `MultipeerRoomHost`, `KtorRoomHost` (both constructors) gain `admission`.
3. `FakeRoomFactory.host` takes and ignores it; the forwarders listed in § 2.1 forward it.
4. Then run the script below on the test source sets, then fix its "HAND EDITS" list.

Dry run on this branch: **252 edits** (122 `host`, 40 `adopt` joiner, 22 `adopt` host, 28
`SeamRoom`, 20 `KtorRoomHost`, 13 lobby `start`, 5 `LoomRoomHost`, 2 `MultipeerRoomHost`,
2 `NwRoomHost`) across 216 session, 15 examples, 6 nw, 6 websocket, 4 session-test, 2 conformance,
2 game, 2 multipeer and 1 cluster sites. A trial `--write` on six copied files produced the expected
diffs (multi-line trailing-comma calls, `roomId` moved into `AdoptRole.Host`, imports added in
sorted position, same-package imports skipped). **Uncompiled.** The script is fenced here and was
never run with `--write` on the tree.

```python
#!/usr/bin/env python3
"""Task 2.4 mechanical rewrite: give every TEST host entry point an open HostAdmission.

    python3 -I rewrite_admission.py <repo-root>            # dry run: print planned edits + hand list
    python3 -I rewrite_admission.py <repo-root> --write    # apply
    add --no-seamroom if SeamRoom's internal constructor does NOT take `admission: HostAdmission?`

Run it AFTER the production signatures exist so the compiler checks every edit. Touches only test
source sets (src/*Test/, examples/src/test/); every *Main/ and *Samples/ site is printed for a hand edit.
OPEN is the literal `HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())` (Decided 17).
"""
import os, re, sys

ROOT = sys.argv[1]
WRITE = "--write" in sys.argv
SEAMROOM = "--no-seamroom" not in sys.argv
OPEN = "HostAdmission(AdmissionPolicy.open(), RevocationList.inMemory())"
IMPORTS = {
    "HostAdmission": "import us.tractat.kuilt.session.admit.HostAdmission",
    "AdmissionPolicy": "import us.tractat.kuilt.session.admit.AdmissionPolicy",
    "RevocationList": "import us.tractat.kuilt.identity.RevocationList",
    "AdoptRole": "import us.tractat.kuilt.session.AdoptRole",
}
TEST_SET = re.compile(r"/src/[A-Za-z]*Test/|^examples/src/test/")
LOOMISH = re.compile(r"(?i)loom|^TcpLoom$|^delegate$|^mem$|^client$|^alice$|^host$|^rooms$")


def files():
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in (".git", "build", ".gradle", "node_modules", ".claude")]
        for f in fn:
            if f.endswith(".kt"):
                yield os.path.relpath(os.path.join(dp, f), ROOT)


def close_paren(t, i):
    """t[i-1] == '('; return the index of its matching ')'."""
    d = 1
    while i < len(t):
        c = t[i]
        if c == '"':  # skip string literals (no templates with parens assumed)
            j = t.index('"', i + 1)
            while t[j - 1] == "\\": j = t.index('"', j + 1)
            i = j
        elif c == "(": d += 1
        elif c == ")":
            d -= 1
            if d == 0: return i
        i += 1
    raise ValueError("unbalanced")


def receiver(t, dot):
    i = dot - 1
    while i >= 0 and t[i] in " \t\n": i -= 1
    end = i + 1; d = 0
    while i >= 0:
        c = t[i]
        if c in ")]}": d += 1
        elif c in "([{":
            if d == 0: break
            d -= 1
        elif d == 0 and not (c.isalnum() or c in "_.?!"): break
        i -= 1
    return t[i + 1:end].strip()


def is_room_factory(t, rec):
    head = re.match(r"[A-Za-z_]\w*", rec)
    head = head.group(0) if head else ""
    bare = re.sub(r"\(.*\)$", "", rec, flags=re.S)
    name = re.search(r"(\w+)\W*$", bare)
    name = name.group(1) if name else bare
    if head in ("SeamRoomFactory", "FakeRoomFactory"): return True
    if LOOMISH.search(name) and "Factory" not in name: return False
    if "us.tractat.kuilt.session" not in t: return False
    if re.search(r"\b" + re.escape(name) + r"\s*:\s*[\w.]*RoomFactory\b", t): return True
    decl = re.search(r"\b(?:val|var|fun)\s+" + re.escape(name) + r"\b[^\n]*(?:\n[^\n]*){0,3}", t)
    return bool(decl and re.search(r"SeamRoomFactory|RoomFactory|FakeRoomFactory|factory\(", decl.group(0)))


def append_arg(t, open_i, close_i, arg):
    """Return the replacement for t[open_i:close_i] (the argument text) with `arg` appended."""
    inner = t[open_i:close_i]
    if re.search(r"\badmission\s*=", inner): return None
    if inner.strip() == "": return arg
    stripped = inner.rstrip()
    if stripped.endswith(","):  # multi-line with trailing comma: add a line in the same indent
        last_line = stripped.rsplit("\n", 1)[-1]
        indent = re.match(r"\s*", last_line).group(0)
        tail = inner[len(stripped):]
        return stripped + "\n" + indent + arg + "," + tail
    if "\n" in inner and re.search(r"\n\s*$", inner):
        last_line = stripped.rsplit("\n", 1)[-1]
        indent = re.match(r"\s*", last_line).group(0)
        return stripped + ",\n" + indent + arg + inner[len(stripped):]
    return stripped + ", " + arg + inner[len(stripped):]


def add_imports(t, names):
    lines = t.split("\n")
    idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not idx: return t
    for n in sorted(names):
        imp = IMPORTS[n]
        if imp in lines: continue
        same_pkg = re.search(r"^package (\S+)", t, re.M)
        if same_pkg and imp.rsplit(".", 1)[0] == "import " + same_pkg.group(1): continue
        pos = next((i for i in idx if lines[i] > imp), idx[-1] + 1)
        lines.insert(pos, imp)
        idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    return "\n".join(lines)


hand, edits_total = [], 0
for f in sorted(files()):
    path = os.path.join(ROOT, f)
    t = open(path, encoding="utf-8").read()
    test = bool(TEST_SET.search(f))
    uses_lobby = "ElectionLobby" in t or "electLobby" in t
    edits = []  # (start, end, replacement, why)
    for m in re.finditer(r"\.(host|adopt|start)\(|(?<![\w.])(LoomRoomHost|NwRoomHost|MultipeerRoomHost|KtorRoomHost|SeamRoom)\(", t):
        ls = t.rfind("\n", 0, m.start()) + 1
        pre = t[ls:m.start()]
        if pre.lstrip().startswith(("*", "//")) or re.search(r"\b(class|fun|object|interface)\b", pre):
            continue
        o = m.end(); c = close_paren(t, o)
        line = t.count("\n", 0, m.start()) + 1
        what = m.group(1) or m.group(2)
        args = t[o:c]
        if what == "host" and not is_room_factory(t, receiver(t, m.start())): continue
        if what == "start" and not (uses_lobby and re.search(r"(?i)lobby|^l\d?$|promoted", receiver(t, m.start()))): continue
        if what == "SeamRoom" and (not SEAMROOM or "SessionRole.Host" not in args): continue
        if not test:
            hand.append(f"{f}:{line}\t{what}\tnot a test source set"); continue
        if what == "host" and "override suspend fun host(" in t[max(0, ls - 300):m.start()].split("\n\n")[-1]:
            hand.append(f"{f}:{line}\thost\tforwarding RoomFactory override: forward its own `admission` parameter"); continue
        if what == "adopt":
            if "SessionRole.Host" in args:
                rid = re.search(r",\s*roomId\s*=\s*([^,\n)]+)", args)
                new = args
                host = f"AdoptRole.Host({OPEN}" + (f", roomId = {rid.group(1).strip()}" if rid else "") + ")"
                if rid: new = new.replace(rid.group(0), "", 1)
                new = new.replace("SessionRole.Host", host, 1)
                edits.append((o, c, new, "adopt-host"))
            elif "SessionRole.Joiner" in args:
                if re.search(r"\broomId\s*=", args):
                    hand.append(f"{f}:{line}\tadopt\tjoiner adopt with roomId: the require() test becomes a type error, delete it"); continue
                edits.append((o, c, args.replace("SessionRole.Joiner", "AdoptRole.Joiner", 1), "adopt-joiner"))
            else:
                hand.append(f"{f}:{line}\tadopt\trole is computed: `{' '.join(args.split())[:70]}`")
            continue
        new = append_arg(t, o, c, f"admission = {OPEN}")
        if new is None: continue
        edits.append((o, c, new, what))
    if not edits: continue
    edits_total += len(edits)
    for s, e, new, why in edits:
        print(f"EDIT {f}:{t.count(chr(10), 0, s) + 1}\t{why}")
    if WRITE:
        for s, e, new, _ in sorted(edits, reverse=True):
            t = t[:s] + new + t[e:]
        kinds = {w for *_, w in edits}
        need = set()
        if kinds - {"adopt-joiner"}: need |= {"HostAdmission", "AdmissionPolicy", "RevocationList"}
        if kinds & {"adopt-host", "adopt-joiner"}: need.add("AdoptRole")
        t = add_imports(t, need)
        open(path, "w", encoding="utf-8").write(t)

print(f"\n{edits_total} edits planned" + (" and written" if WRITE else " (dry run)"))
print("HAND EDITS:")
for h in hand: print("  " + h)
```

**What the script hands back for hand edits (dry run on this branch)**

- `kuilt-conformance/…/RoomConformanceSuite.kt` (commonMain): 38 `h.hostFactory.host(` rows plus the
  `defaultHarness` forwarder `:2273`. Suggest one `private fun openAdmission()` in the suite, or a
  `hostAdmission` field on `RoomHarness`. The second lets an implementation's harness run the TCK
  under its own policy, and it is a conformance ABI change.
- `ReferenceResumeHarness.kt:129, 136`: forwarders; forward the parameter.
- `StuckOpenRoomFactory` (`RoomChannelStateAfterLeaveControlTest.kt:82-83`): forwarder over a
  receiver named `delegate`, which the script reads as a loom and **skips silently**.
- `RoomIdentityTest.kt:157`: delete the joiner-with-`roomId` test.
- Production / samples: `RoomHost.kt:92`, `SeamRoom.kt:203` (the factory's own `SeamRoom(`),
  `SeamElectionLobby.kt:613`, `KtorRoomHost.kt:105`, `NwRoomHost.kt:36`, `MultipeerRoomHost.kt:32`,
  `AgentCookbookSamples.kt:114, 313`, `GameSamples.kt:464`.
- `spike/…/ConnectivitySuite.kt:363, 368, 706, 1250` (outside the default build).
- Every doc in § 2.2.

Known blind spots: a `RoomFactory` held under a name matching `LOOMISH` (`delegate`, `host`, `client`)
is skipped. A `.start(` on a lobby held under a name other than `*lobby*`, `l`, `l1`, `promoted` is
skipped. String templates containing `(` inside a call's arguments would confuse `close_paren`.
None of these occur today except `StuckOpenRoomFactory`. The compiler catches the rest: a missed
site is a missing required argument.

### 2.4 The `adopt(role: AdoptRole, …)` reshape: every caller

`AdoptRole.Host(admission, roomId = null)` / `AdoptRole.Joiner`. The `require` at
`SeamRoom.kt:306` goes, because the type now carries the rule. Callers and what they pass:

| Caller | Passes today | Passes after 2.4 |
|---|---|---|
| `SeamElectionLobby.adoptRoom` `:613` via `adoptAfterCommit` `:187` (`start`) | `SessionRole.Host` | `AdoptRole.Host(admission)`, admission from `start` (§ P1) |
| `SeamElectionLobby.adoptRoom` via `adoptAfterCommit` `:311` (`awaitRoom`) | `SessionRole.Joiner` | `AdoptRole.Joiner` |
| `KeyAdmissionTest.kt:78` (`hostRoom` helper), `RoomIdentityTest.kt:122`, `RealKeyAdmitTest.kt:81` | `SessionRole.Host, roomId = R` | `AdoptRole.Host(OPEN, roomId = R)` |
| `RoomIdentityTest.kt:157` | `SessionRole.Joiner, roomId = …` (asserts the `require`) | **delete the test** |
| the other 19 host and 39 joiner sites in § 2.1 | `SessionRole.Host` / `.Joiner` | `AdoptRole.Host(OPEN)` / `AdoptRole.Joiner` |
| `kuilt-game` `RoomGameHarness.kt:70, 72` (commonTest) | Joiner / Host | same mapping |
| `kuilt-nw` `NwMeshRoomPartitionTest.kt:103` | Joiner | `AdoptRole.Joiner` |
| `spike` `ConnectivitySuite.kt:706, 1250` | `role = if (amHost) Host else Joiner` | `role = if (amHost) AdoptRole.Host(admission) else AdoptRole.Joiner` |

`SessionRole` itself stays: `Room.role`, `FakeRoom(initialRole = …)`, `SeamRoom(role = …)`, and the
`_role.value == SessionRole.Host` gates in `handleAdmitFrame` all read it.

### 2.5 Draft Review-Focus tests (UNCOMPILED)

Written against `KeyAdmissionTest`'s harness on the 1.4 branch (`FakeSeam`-driven host, `say`,
`hello`, `proof`, `challengesTo`, `rejectsTo`, `sentTo`, `bobKey`/`eveKey`, `ADMIT_TIMEOUT`).
**Assumed API**, which 2.4 must create: `AdoptRole.Host(admission, roomId)`,
`RejectCode.AdmissionPolicyFailed` (retryable, with a `fromId` branch), and the room calling
`policy.decide` on the `Ordinary` arm. They need this helper beside `hostRoom`:

```kotlin
/** A host room whose ordinary admissions [policy] decides. */
private suspend fun TestScope.hostRoom(
    policy: AdmissionPolicy,
    store: DeviceKeyStore = fakeKeyStore(0),
    joiners: Set<PeerId> = setOf(bob, eve),
): Pair<FakeSeam, Room> {
    val seam = FakeSeam(selfId = hostId, initialPeers = setOf(hostId) + joiners)
    val admission = HostAdmission(policy, RevocationList.inMemory())
    val room = SeamRoomFactory(InMemoryLoom(), backgroundScope, clock(), store, admitTimeout = ADMIT_TIMEOUT)
        .adopt(seam, AdoptRole.Host(admission, roomId = ROOM), memberName = "Host")
    runCurrent()
    return seam to room
}

/** Every `Joined` the room emits from now on, in order. */
private fun TestScope.joinedOn(room: Room): List<MembershipEvent.Joined> {
    val joined = mutableListOf<MembershipEvent.Joined>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
        room.events.filterIsInstance<MembershipEvent.Joined>().collect { joined += it }
    }
    return joined
}

private fun admitAll(): AdmissionDecision = AdmissionDecision.Admit(GrantTerms(readmitFor = 1.hours))
```

**`throwingPolicyRejectsRetryablyAndRoomSurvives`**: the throw is caught on the admit coroutine.
On Kotlin/Native an escaped throw aborts the process; under `runTest` it fails the test as an
uncaught exception in `backgroundScope`, so the test catches an escape on every target.

```kotlin
@Test
fun throwingPolicyRejectsRetryablyAndRoomSurvives() = runTest {
    val asked = mutableListOf<String>()
    val policy = AdmissionPolicy { request ->
        asked += request.displayName
        when (request.displayName) {
            "Bob" -> throw IllegalStateException("the app's allow-list store is down")
            else -> admitAll()
        }
    }
    val (seam, room) = hostRoom(policy)
    say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
    say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
    val bobRejects = seam.rejectsTo(bob)

    // The same room, the same policy, after the throw: Eve is still admitted.
    say(seam, eve, hello("Eve", eveKey.id, nonce = 2))
    say(seam, eve, proof(seam.challengesTo(eve).single(), joiner = eveKey))

    assertAll(
        { assertEquals(listOf("Bob", "Eve"), asked, "rig: the policy was asked for Bob, threw, then was asked for Eve") },
        { assertEquals(listOf(RejectCode.AdmissionPolicyFailed), bobRejects.map { it.code }, "Bob hears the policy failed") },
        { assertTrue(bobRejects.single().code.retryable, "the host's own failure is retryable") },
        { assertTrue(room.roster.value.none { it.id == bob }, "a throwing policy admits nobody") },
        { assertEquals(setOf(eve), room.roster.value.map { it.id }.toSet(), "the room keeps admitting") },
    )
    room.leave()
}

/**
 * The #1834 shape: a policy that runs its own `withTimeout` throws a CancellationException the
 * room's coroutine did not ask for. `ensureActive()` lets it through as a failure; a type-based
 * `if (e is CancellationException) throw e` would cancel the admit coroutine silently and Bob would
 * hear nothing until his own deadline.
 */
@Test
fun policyMintedCancellationIsAFailureNotASilence() = runTest {
    var asked = 0
    val policy = AdmissionPolicy {
        asked++
        withTimeout(1.milliseconds) { awaitCancellation() }
    }
    val (seam, room) = hostRoom(policy)
    say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
    say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
    advanceTimeBy(2.milliseconds)
    runCurrent()

    assertAll(
        { assertEquals(1, asked, "rig: the policy ran and its own timeout fired") },
        { assertEquals(listOf(RejectCode.AdmissionPolicyFailed), seam.rejectsTo(bob).map { it.code }) },
        { assertTrue(room.roster.value.isEmpty()) },
    )
    room.leave()
}
```

**`slowPolicyTimesOutWithoutWedgingPending`**: Bob's first decision never returns; once its
challenge's deadline passes, Bob says Hello again and is admitted exactly once. Then the stale first
decision is released and must change nothing. Plus a real-joiner arm for the `AdmissionFailed(TimedOut)`
half of the plan's wording. The host-side behaviour on its own deadline (cancel the decision, or let
it run and discard it) is an open design point (Part 3 § P7). The first test asserts only what holds
either way; the last line pins the bounded variant, so drop it if the decision is "discard".

```kotlin
@Test
fun slowPolicyTimesOutWithoutWedgingPending() = runTest {
    val firstAsked = CompletableDeferred<Unit>()
    val firstRelease = CompletableDeferred<AdmissionDecision>()
    var firstCancelled = false
    var asked = 0
    val policy = AdmissionPolicy {
        if (++asked == 1) {
            firstAsked.complete(Unit)
            try {
                firstRelease.await()
            } catch (cancelled: CancellationException) {
                firstCancelled = true
                throw cancelled
            }
        } else {
            admitAll()
        }
    }
    val (seam, room) = hostRoom(policy)
    val joined = joinedOn(room)

    say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
    say(seam, bob, proof(seam.challengesTo(bob).single(), joiner = bobKey))
    assertTrue(firstAsked.isCompleted, "rig: the host is waiting on the policy")

    advanceTimeBy(ADMIT_TIMEOUT + 1.milliseconds)
    runCurrent()
    val welcomedWhileStalled = seam.sentTo(bob).count { it is AdmitMessage.Welcome && it.assignedPeerId == bob.value }

    // A fresh Hello from the same sender is decided afresh and admitted.
    say(seam, bob, hello("Bob", bobKey.id, nonce = 2))
    say(seam, bob, proof(seam.challengesTo(bob).last(), joiner = bobKey))

    // The stale first decision, released late, must change nothing.
    firstRelease.complete(admitAll())
    runCurrent()

    assertAll(
        { assertEquals(2, asked, "rig: the policy was asked twice, once per challenge") },
        { assertEquals(2, seam.challengesTo(bob).size, "rig: the host challenged Bob twice") },
        { assertEquals(0, welcomedWhileStalled, "a stalled policy admits nobody") },
        { assertEquals(setOf(bob), room.roster.value.map { it.id }.toSet(), "the fresh Hello is admitted") },
        { assertEquals(1, joined.count { it.member.id == bob }, "exactly one Joined for Bob") },
        {
            assertEquals(
                1,
                seam.sentTo(bob).count { it is AdmitMessage.Welcome && it.assignedPeerId == bob.value },
                "the stale decision sent no second Welcome",
            )
        },
        { assertTrue(seam.rejectsTo(bob).isEmpty(), "nothing refused Bob") },
        // Only if the host bounds the decision by the challenge deadline (Part 3 § P7):
        { assertTrue(firstCancelled, "the host stopped waiting on the stalled decision at its deadline") },
    )
    room.leave()
}

/** The joiner-side half: a host whose policy never answers leaves the joiner AdmissionFailed(TimedOut). */
@Test
fun slowPolicyLeavesTheJoinerTimedOut() = runTest {
    var asked = 0
    val stalled = AdmissionPolicy { asked++; awaitCancellation() }
    val loom = InMemoryLoom()
    val host = SeamRoomFactory(loom, backgroundScope, clock(), fakeKeyStore(0), admitTimeout = ADMIT_TIMEOUT)
        .host(Pattern("room"), memberName = "Host", admission = HostAdmission(stalled, RevocationList.inMemory()))
    val joiner = SeamRoomFactory(loom, backgroundScope, clock(), fakeKeyStore(1), admitTimeout = ADMIT_TIMEOUT)
        .join(InMemoryTag("room"), memberName = "Bob")

    val failed = joiner.events.filterIsInstance<MembershipEvent.AdmissionFailed>().first()

    assertAll(
        { assertEquals(1, asked, "rig: the host reached the policy, so the proof was verified") },
        { assertEquals(AdmissionFailure.TimedOut, failed.reason) },
        { assertTrue(host.roster.value.isEmpty(), "nobody was admitted") },
    )
    host.leave()
}
```

**`retriedHelloDuringDecisionAdmitsOnce`**: Review Focus 3. While Bob's first decision is still
running, his re-Hello opens a new challenge. The first decision completes against a retired
generation (`PendingChallenges.retire` returns false) and is discarded. Bob answers the second
challenge and is admitted exactly once. **Nothing is refused** (Part 3 § P6: Review Focus 3 says
"refused").

```kotlin
@Test
fun retriedHelloDuringDecisionAdmitsOnce() = runTest {
    val gates = listOf(CompletableDeferred<AdmissionDecision>(), CompletableDeferred<AdmissionDecision>())
    var asked = 0
    val policy = AdmissionPolicy { gates[asked++].await() }
    val (seam, room) = hostRoom(policy)
    val joined = joinedOn(room)

    say(seam, bob, hello("Bob", bobKey.id, nonce = 1))
    val first = seam.challengesTo(bob).single()
    say(seam, bob, proof(first, joiner = bobKey))
    assertEquals(1, asked, "rig: the first decision is in flight")

    // Bob's retry lands mid-decision: a new challenge, answered.
    say(seam, bob, hello("Bob", bobKey.id, nonce = 2))
    val second = seam.challengesTo(bob).last()
    say(seam, bob, proof(second, joiner = bobKey))

    // The overwritten decision completes first and is discarded; the current one admits.
    gates[0].complete(admitAll())
    runCurrent()
    val admittedByTheStaleDecision = room.roster.value.any { it.id == bob }
    gates[1].complete(admitAll())
    runCurrent()

    assertAll(
        { assertEquals(2, seam.challengesTo(bob).size, "rig: the retry opened a second challenge") },
        { assertFalse(first.hostNonce.contentEquals(second.hostNonce), "rig: the two challenges differ") },
        { assertEquals(2, asked, "rig: both proofs reached the policy") },
        { assertFalse(admittedByTheStaleDecision, "the overwritten challenge's decision admits nobody") },
        { assertEquals(1, joined.count { it.member.id == bob }, "exactly one Joined") },
        { assertEquals(1, seam.sentTo(bob).count { it is AdmitMessage.Welcome && it.assignedPeerId == bob.value }) },
        { assertTrue(seam.rejectsTo(bob).isEmpty(), "the stale decision is dropped, never refused") },
    )
    room.leave()
}
```

Imports these need beyond `KeyAdmissionTest`'s: `kotlinx.coroutines.CoroutineStart`,
`kotlinx.coroutines.awaitCancellation`, `kotlinx.coroutines.withTimeout`,
`kotlinx.coroutines.flow.filterIsInstance`, `kotlin.coroutines.cancellation.CancellationException`,
`kotlin.time.Duration.Companion.hours`, `kotlin.test.assertFalse`,
`us.tractat.kuilt.identity.RevocationList`, `us.tractat.kuilt.session.admit.{AdmissionDecision,
AdmissionPolicy, GrantTerms, HostAdmission}`. Each test asserts its rig fired: the policy call
count, the challenge count, and, where a gate is used, that the gate was reached before the
behaviour under test.

---

## Part 3: what the census shows the plan missed

**P1. The lobby's host entry point is `ElectionLobby.start`, not `electLobby`.** The plan's 2.4 Files
list names `electLobby` and `SeamElectionLobby.kt` but not the public interface `ElectionLobby.kt`.
Only `start` adopts as host (`SeamElectionLobby.kt:187`); `awaitRoom` adopts as joiner (`:311`), and a
`BecameHost` outcome sends the caller back to `start`. Putting `admission` on `electLobby(pattern, admission)`
makes every peer, joiners included, supply a policy it never uses, which cuts against "host entry
points only". Putting it on `start(memberName, deviceId, admission)` mirrors how Amendment B threads
`deviceId` (#3271) and makes the lobby's host path a type. **Recommend `start`.** Cost: the
`ElectionLobby` interface ABI, 13 test sites, `handleEveryElectionOutcomeSample` (verbatim in
`session.md`), `Writerside/topics/picking-a-host.md`, the spike. A ruling is needed before the 2.4 worker starts.

**P2. A Proof for another room has no named test.** `KeyAdmission.checkProof` checks
`roomId = signedRoomId()`, but `KeyAdmissionTest` pins host key, nonce, peer id and device id, not
room id. Today the room-id property is pinned only by the `Resume`-path tests 1.8 deletes:
`RoomResumeTest` 4a, `ForgedResumeTest` `:325`, `RoomConformanceSuite.aTokenMintedForAnotherRoomIsRefused`.
Deleting them without adding `proofForAnotherRoomIsRejected` leaves the check unpinned. A mutation
that drops the room-id comparison would stay green.

**P3. 28 direct host-role `SeamRoom(` constructions (16 test files) plus 4 with a role variable are
not in the Files list.** They are internal, so no ABI moves, but they compile against `SeamRoom`'s
constructor. The 2.4 worker must decide how the constructor carries the admission. Option (a):
`role: SessionRole` plus `admission: HostAdmission?` checked once at construction (a constructor-time
`require` is not on the Proof path, so it does not hit Amendment A § 1.2's no-throw rule). Option (b):
an internal role type carrying it. The script assumes (a); pass `--no-seamroom` for (b).

**P4. Six `RoomFactory` implementers, not four.** Amendment A § 1.1 lists four. The census adds the
`defaultHarness` joiner forwarder (`RoomConformanceSuite.kt:2271`, new on the 1.4 branch) and
`StuckOpenRoomFactory` (`kuilt-conformance` jvmTest). Every forwarder must forward `admission`; a
forwarder that mints `AdmissionPolicy.open()` silently opens a TCK subclass's room.

**P5. `RejectCode.AdmissionPolicyFailed` is not in 2.4's Files list.** The plan's Names section
defines it (`"admission-policy-failed"`, retryable), and 2.4's Behaviour sends it, but `admit/RejectCode.kt`
(object, `fromId` branch), `RejectCodeWireCompatTest` and the ABI line are not listed. Without the
`fromId` branch the joiner decodes it as `Unknown("admission-policy-failed")`, still retryable, so a
test checking only `retryable` passes either way. Assert `code == AdmissionPolicyFailed` on the joiner.

**P6. Review Focus 3 says the stale Proof is "refused"; the mechanism drops it.** `admitPeer`'s
`retire(…)` returning false logs `room.admit.stale` and returns (`SeamRoom.kt:1759-1762`), and 2.4's
Behaviour says "discarded". A `Reject` here is wrong twice. A joiner ends its admission on a `Reject`
(`BadProof` and `KeyConflict` are non-retryable; even the retryable `proof-check-failed`/`Unspecified`
surfaces as `AdmissionFailed`). And it would race the newer challenge's Welcome. The draft
test asserts no Reject; fix the Review Focus wording.

**P7. The plan does not say whether the host bounds `policy.decide`.** The spec says the policy "must
answer within the joiner's `admitTimeout`", but nothing enforces it host-side. Unbounded, a stalled
policy parks one coroutine per verified Proof for the room's lifetime. A bystander can mint those at
will (one Hello and one Proof each, `bystanderHelloFloodOverwritesOnlyItsOwnPending` notwithstanding,
since each fresh fabric id gets its own entry). Recommend
`withTimeoutOrNull(pending.deadline - clock())` around the call. On expiry the host sends nothing,
or a retryable `AdmissionPolicyFailed`: a design point, since host and joiner `admitTimeout`s are
configured separately and may differ. Apply the catch discipline either way: catch `Throwable`,
`ensureActive()`, then treat a callee-minted cancellation as a policy failure (draft test
`policyMintedCancellationIsAFailureNotASilence`).

**P8. Where the policy call sits relative to the lock.** Amendment A § 1.5/2.4 says re-run
`decideSeat` under the room lock after the suspension. The current `admitPeer` does
`retire` + `addToRoster` under `lock`, with the secret mint between. 2.4 inserts the suspending
`policy.decide` **before** that block and **outside** `lock` (an atomicfu `reentrantLock`; suspending
inside it is banned). The order is: verify → `decideSeat` → (`Ordinary`) `policy.decide` → `lock { retire(gen) && decideSeat again && addToRoster }`.

**P9. `AdmissionRequest`'s constructor is public under `@SessionInternalApi`, not `internal`**
(`AdmissionPolicy.kt:80`; Decided 26's tests-only factory lives in `kuilt-session-test`). So "calling
the policy on a restore is a type error" (Amendment A § 2.4) holds only if the ordinary-arm factory
is the sole caller inside the room. Say that in the factory's KDoc. Also say what Amendment A asks:
a null `deviceId` is signed as `""`.

**P10. Docs naming "reconnect tokens" the plan does not list:** `AGENTS.md:55`, `README.md:137`,
`Writerside/topics/modules.md:27`, `Writerside/topics/fabrics.md:166` (the plan lists only
`moduleDescription()` and `llms.txt`). `verifyModuleTable` checks only that the row exists, not what
it says, so nothing reds.

**P11. The fate of `ResumeResult.TokenInvalid` and `ResumeResult.WindowNotYetOpen` is undecided.**
They are public `HostVerdict`s, the vocabulary of the public `JoinerReconnectController` SPI. After
1.8 nothing maps them to the wire, and the 1.5 Proof path treats any non-`Success` verdict as
"decide again". `DefaultJoinerReconnectController.kt:61` (`TokenInvalid("session-mismatch")`) becomes
unreachable once `ProvenResume.roomId` is always the room's own. KDoc that becomes false:
`JoinerReconnectController.kt:213, 241, 264-295`, `Room.kt:358`, and cookbook `session.md:80-87`
("they arrive as the `RejectCode` on `Refused`"). Decide: keep them (SPI stability) with corrected
KDoc, or remove them in 1.8 and record the ABI.

**P12. `mintTokenIfAbsent` changes meaning, not just arity.** Today a joiner mints a `ResumeToken`
only if the Welcome carried a secret ("a joiner whose host sent no secret holds no token",
`ResumeToken.kt:20`). Without the secret, 1.8 must pick the new condition (a `roomId` on the
Welcome?). That changes when `Room.resumeToken` is non-null, which `resumeAfterDropSample` and
consumers branch on. The token also stops being a bearer secret, so its "store it as you would a
password" KDoc goes. Its persisted form loses a field (§ 1.6).

**P13. The spike's #1637 probe is built on the deleted code.** `ConnectivitySuite.kt:1508-1569`
decides PASS/FAIL by matching `RejectCode.ResumeWindowNotYetOpen` and "a real `ResumeAck`". That is a
verdict rewrite, not a rename. It breaks `-PincludeSpike` at 1.8, and again at 2.4 (`adopt(role = if …)`,
`lobby.start`). Amendment A § 1.1's "fix it or say it is knowingly left" should be repeated in both
briefs.

**P14. A second module's KDoc names `ResumeAck`:** `kuilt-liveness/…/HeartbeatConfig.kt:35`. The 1.8
grep in the task (`--include='*.kt'`) finds it, but the Files list scopes the task to session.

**P15. The 1.8 ABI list should include `kuilt-conformance`** (its public resume test methods and
`ResumeHarness`, § 1.6), unless 1.7's harness reshape has already removed them. It should also
mention `Welcome`'s positional constructor arity, a source break for anyone building a `Welcome`.

**P16. The cookbook's "You never see a forged resume" callout** (`session.md:245`) names
`ResumeSecret` and #3165. It becomes false at 1.5 (the Proof path) and certainly at 1.8. No task's
Files list names it; 1.9 names only the rejoin sections.

**P17. 1.7 is missing from the protocol-bump list.** Amendment A § 1.3 lists bumps at 1.3-1.6, 1.8,
2.5 and 2.6. By the plan's own rule ("what a peer must send to be admitted"), 1.7 changes what a
returning peer sends (Hello, not `Resume`). A 1.6 host still answers that Hello, so the omission may
be deliberate. If so, say why in 1.7's PR.

**P18. Uncited snippets will rot without a red** (`docs/usage.md:114, 523`,
`Writerside/topics/fabrics.md:171`, `picking-a-host.md:12-14`, `kuilt-session-test/README.md:50`,
`kuilt-test/README.md:54-55`, `kuilt-cluster/module.md:126`). `verifyDocCitations` checks only
cited blocks. Edit them by hand in 2.4, or convert one or two to `verbatim from` a compiled sample.

**P19. `kuilt-nw`'s ABI refresh is macOS-only** (a cinterop module). `NwRoomHost`'s constructor gains
`admission`, so the 2.4 PR needs a Mac `updateKotlinAbi` or
`gh workflow run apple-nightly.yml --ref <branch> -f update_abi=true`. `kuilt-multipeer`'s dumps
move too (`MultipeerRoomHost`).

---

## Next

- **State:** census complete on branch `draft/3159-census-1.8-2.4`, based on
  `origin/identity/3211-admission-proof` at `65257d458`. This file is the only change. No PR, by
  brief. No Gradle run. Counts are lexical.
- **Open threads needing a ruling before 2.4 dispatches:** P1 (admission on `ElectionLobby.start` vs
  `electLobby`), P7 (does the host bound `policy.decide`, and what it sends on expiry), P11 (keep or
  remove `ResumeResult.TokenInvalid` / `WindowNotYetOpen`), P12 (when a joiner mints a `ResumeToken`
  once there is no secret).
- **Before 1.8 dispatches:** re-run the § 1.1/1.2 greps on the then-current tree (after 1.5-1.7
  land), since many rows will already have moved. Fold P2 (`proofForAnotherRoomIsRejected`) and the
  two "keep as a key-forgery case" rows of § 1.3 into 1.5's or 1.8's brief.
- **One next action:** take P1, P7, P11 and P12 to Iain (or rule them as controller), then paste the
  answers into the 2.4 and 1.8 briefs alongside this file's path.
