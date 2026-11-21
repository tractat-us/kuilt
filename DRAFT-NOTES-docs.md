# Draft notes: docs for #3159 Tasks 1.9 and 2.7

Branch `draft/3159-docs-1.9-2.7`, cut from `origin/identity/3211-admission-proof` (Task 1.4 WIP).
Nothing here is compiled; no Gradle was run. **Delete this file before any of it lands** — it is a
hand-off note, not documentation.

The docs are written in their **final, post-2.7 state**. The table below says which slice each piece
belongs to, so a worker can take its own part and leave the rest.

## Files touched, and which task owns each piece

| File | Piece | Owner |
|---|---|---|
| `docs/agent-cookbook/session.md` | `## Rejoin & reconnect`: new first entry (three-ways-back table, `returnIsAutomaticSample`, the loom bullets, `whoCameBackSample`) | 1.9 — but the table's second row and the `ReturningMark` mention are 2.6/2.7; 1.9 ships the table with rows 1 and 3 only, or with row 2 marked "arriving" |
| same | `Room.roomId` entry gains "to name a device, use `Member.key`" | 1.9 |
| same | `callerSuppliedRoomIdSample` entry: the "#1593 / host restart not resumable" sentence replaced by the room-pass sentence | 2.7 (true only once 2.6 lands). 1.9 should instead say "a restarted host holds no seats, so devices come back through ordinary admission" |
| same | `classifyRejectCodeSample` entry rewritten (BadProof, KeyConflict; no Resume* codes) | 1.8 (it removes the codes the old sample names). The "carrying the `retryable` the app gave it" sentence is 2.5 (Decided 25) |
| same | the old `resumeAfterDropSample` quote and its "four arms" paragraph removed | 1.9 |
| same | `JoinerReconnectController` entry: `tryResume` takes a `ProvenResume`; "You never see a forged return" | 1.5 (it changed the type). Already stale on this branch |
| same | `## Device identity & return`, the policy paragraph: "#3220" sentence gone; "the joiner sees your code with its `retryable`" | "#3220" removal is 2.4 (Amendment A § 2.4 says so); the `retryable` sentence is 2.5 |
| same | `hostAdmissionSample` comment (in the sample and its quote) | 2.5 |
| same | `**On the room.**` + `**Which member is this device:** Member.key` + `seatsOfDeviceSample` + the 1.4 KDoc warning copied in | 1.9 (1.4 said to copy the warning into the cookbook; it was not yet there) |
| same | `**Hosting with passes**`, `**A pass in use**`, `**Stopping a device coming back**`, `**What a room does not protect against**` and their samples | 2.7 |
| same | the closing "So far this is…" paragraph, deleted | 1.9 deletes the "rejoins through Rejoin & reconnect" half; 2.7 the rest |
| `kuilt-session/src/commonSamples/.../AgentCookbookSamples.kt` | seven drafted samples (below), `classifyRejectCodeSample` body, two KDoc edits, imports, two `// DRAFT` marker comments | per sample, below. **Delete both `// DRAFT` comments** when compiling |
| `.claude/skills/kuilt-primitives/SKILL.md` | rejoin row and identity row | rejoin row 1.9; `HostAdmission` in the identity row is already there from 2.3, `Member.key` is 1.9 |
| `docs/agent-cookbook.md` | index rows for rejoin and identity | rejoin row 1.9; identity row's `RoomFactory.host`/`LoomRoomHost`/`KtorRoomHost` and `Joined.returning` are 2.7 |
| `kuilt-session/module.md` | **new file** — the module had none | 2.7 (the plan names it). Its "Who decides" section is 2.x; the rest is true at 1.9 |
| `kuilt-identity/module.md` | "What is here so far" → "What is here"; the statements paragraph; new `## How a room uses them` with `sampleGrantTrust` inlined; the "not built yet" line dropped | 2.7 |
| `docs/usage.md` | host snippet takes `admission = HostAdmission(…)`; key-store paragraph names `platform`; "Reconnect/resume" paragraph rewritten | snippet 2.4; the rest 1.9 |
| `Writerside/topics/cluster-server-client.md` | failover paragraph and limitations row: keys and rooms, not tokens | 1.8/1.9 (the old text cites `ResumeToken` semantics 1.8 removes). Vale: 0/0/0 |
| `Writerside/topics/modules.md` | `kuilt-session` row; **new `kuilt-identity` row** (it was missing) | 1.9; the identity row could land any time. Vale: 0/0/0 |
| `docs/architecture.md` | cross-server limitation (it cited `RejectCode.ResumeTokenInvalid`, which 1.8 removes); module bullet | 1.8 |
| `kuilt-cluster/module.md` | failover paragraph | 1.8. The dependency diagram's `ResumeToken` is left: `ResumeToken` survives 1.8 (only `secret` goes), and I could not tell whether `ClusterClient` still uses it |

Vale ran as 3.26.0 (the agent-scheme copy); the repo pins 3.22.0. Re-run with the pinned one.

## Samples drafted (all in `kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt`)

Every one needs a test that **calls** it, or `verifySamplesAreRun` reds (the existing pattern:
`AdmissionPolicyTest` calls `hostAdmissionSample`). Suggested callers in brackets.

| Sample | API it assumes | Where the API comes from | Task |
|---|---|---|---|
| `returnIsAutomaticSample(loom, scope, clock, keyStore, tag): Room` | `SeamRoomFactory(loom =, scope =, clock =, deviceKeyStore =)`, `RoomFactory.join(tag, memberName)` | exists on this branch (1.1) | 1.9 [a conformance or `InMemoryLoom` test that kills a joiner room and calls the sample again over the same store, asserting `Resumed`] |
| `whoCameBackSample(room)` | `MembershipEvent.Resumed`, `MembershipEvent.Left(peerId, reason)`, `LeaveReason.Superseded(by: PeerId)` | `Superseded` is plan "Names" + Task 1.6 | 1.9 [the 1.6 supersession test, collecting through the sample] |
| `seatsOfDeviceSample(room, device): List<PeerId>` | `Member.key: DidKey` | exists (1.4, this branch) | 1.9 [Amendment B's `secondConnectedTabGetsAnAdditionalSeat`: two seats, one key] |
| `classifyRejectCodeSample` (rewritten body) | `RejectCode.BadProof`, `RejectCode.KeyConflict` (exist), `RoomMismatch`, `ProtocolMismatch` | 1.4; Decided 20 removes the three Resume codes | 1.8 [the sample's existing caller, if any — I found none outside KDoc `@sample`; check the baseline] |
| `hostARoomTheyCanReturnToSample(factory, pattern, admission, lobbyCode): Room` | `RoomFactory.host(pattern, roomId =, admission =)` | Task 2.4 "`RoomFactory.host(…, admission: HostAdmission)`" — **parameter name assumed** | 2.7 [2.6's `anyRoomGrantSurvivesHostRestartRoomGrantNeedsStableRoomId`] |
| `returningInsideAPassSample(room)` | `MembershipEvent.Joined.returning: ReturningMark?`, `ReturningMark.previousId: PeerId?` | plan "Names"; Task 2.6 | 2.7 [2.6's `returnInsideGrantSkipsPolicyAndIsMarked`] |
| `stopADeviceComingBackSample(blocked, revocations, device, nowEpochMs)` | `RevocationList.revoke(subject, notBefore)` (exists), `MutableStateFlow.update` | 2.1 | 2.7 [`revokedSubjectIsVetted`, with `hostAdmissionSample`'s policy over the same `blocked`] |

KDoc edits in the same file: `callerSuppliedRoomIdSample` (now links `hostARoomTheyCanReturnToSample` — a
KDoc link to a sample that does not exist until 2.7, so 1.9 must not take this edit) and
`holdTheSeatOpenSample` ("the single-use token check" → "the answer to a proven return", true from 1.5).

`resumeAfterDropSample` is **kept** but no longer quoted by the cookbook: `Room.resume`'s KDoc still
`@sample`s it. Whoever settles question 4 below deletes or rewrites it.

## Skill budget

Measured after the edit, with the guard's own split (body = everything after the closing `---` line):

- `description:` **1,532** of 1,536 characters — **unchanged**. The plan's Global Constraints say
  "Do not touch `description:`", and "rejoin/reconnect/resume after a drop" already routes this
  family, so I added no trigger and displaced none. The brief allowed a swap; the plan forbids it,
  and I followed the plan. If a trigger is wanted ("an app killed and reopened", "a device that comes
  back"), the cheapest one to drop is "reaping a room nobody joined", which "a room/lobby/table
  container" largely covers — but that is a judgement call I did not make.
- Body: **8,191** of 8,192 bytes (was 8,184). The rows had to be trimmed three times to fit; there is
  **1 byte** left. 2.7 cannot add `Joined.returning` or `HostAdmission on host` to the row without
  moving bytes out of another row. The cookbook index row carries both instead.
- YAML: `ruby -ryaml` parses it; keys `name`, `description`.

Final rows:

```
|a rejoin/reconnect loop, a resume token, a grace window; why a rejoin was refused|return is automatic — `DeviceKeyStore` on the factory; `RejectCode.retryable`|`session.md#rejoin--reconnect`|
|a device key/did:key kept across launches, a returning device; a "remember this device" pass, revoked across restarts; who joins, testing it|`DeviceKeyStore.platform` (`DeviceKeyStoreConformanceSuite`), `DeviceKey` + `DidKey`, `Member.key`; `Grant` + `GrantTrust`/`RevocationList.open`; `HostAdmission`/`AdmissionPolicy`/`admissionRequest`|`session.md#device-identity--return`|
```

## What turned out ambiguous when explained to a newcomer

These are the questions I could not answer from the spec and plan, ordered by how much they matter.

1. **A quick comeback under a new peer id gets a second seat, not its old one (Decided 30).**
   `RestoreUnderNewId` fires only for a *partitioned* seat; a key whose seats are all *connected*
   gets an additional seat. A phone killed and reopened within the host's heartbeat timeout, on a
   fabric that hands it a fresh peer id, finds its old seat still connected on the host. So it is
   admitted *beside* itself, bystanders see `Joined(new)`, and only later `Left(old, PartitionExpired)`
   — the opposite of the spec's story ("the other person sees 'Iain is back'"). I documented this as
   the honest seam (the "quicker than the host's detector" bullet), but it is inferred from Decided 30,
   not observed. Check which state `processRebuiltWithFreshIdRestores` rigs: if it partitions the seat
   first, the fast case is untested. A ruling may want a supersede-on-new-id for a connected seat
   whose link the host can see is gone, which Decided 30 forbids because a live tab looks the same.

2. **"The app writes no reconnect code" holds only on a loom that heals the same seam.** Decided 8
   says "kuilt re-dials, proves the key and restores the member". Task 1.7 keeps
   `JoinerResumeMachine`'s loop, which needs the loom to heal the **same seam instance** (as today).
   On a loom that hands back a new seam (plain `KtorClientLoom`, a fresh `NwLoom` session after an
   app kill), the room still ends in `HostLost`, and the app must call `join` again. I wrote that the
   launch-time `join` is "all the rejoin code there is" — true but a stretch of "nothing". Either the
   spec's sentence narrows, or the reconnect loop learns to re-weave.

3. **After an app kill, the app must remember where it was.** Return needs the same key *and* a
   `join` to the same room. kuilt keeps the key; nothing keeps the `Tag` (or URL, or lobby code). The
   spec's "nobody presses anything" assumes the app re-joins on launch by itself. Docs should say who
   keeps "which room was I in" — I did not, beyond calling the launch `join` the comeback.

4. **What are `Room.resumeToken` and `Room.resume` for after 1.8?** The plan keeps both: 1.7 makes
   `resume` re-Hello, 1.8 strips `ResumeToken.secret` but keeps the type. A token without a secret
   proves nothing, and the reconnect loop no longer needs the app. I wrote "Don't save
   `Room.resumeToken` or drive `Room.resume` yourself", which is a doc telling people not to use a
   public API. Needs a ruling: deprecate, remove, or document a remaining use. Their KDoc (and
   `Room.roomId`'s, and `RoomFactory.host`'s) still cite #1593 and say a host restart is not
   resumable; 1.9/2.7 must rewrite those KDocs too, which the plan's file lists do not name.

5. **When does a joiner ever see its own `Superseded`?** Decided 19 has the host send
   `Farewell(self, supersededBy)` straight to the superseded peer. Decided 30 supersedes only a
   *partitioned* seat — one whose link is down, so it may never get that frame. If its link heals
   later, its reconnect loop re-Hellos under the old id with the same key; the seat is gone, so it is
   `Returning` (it holds a pass) and gets *another* seat. Review Focus 1's "never re-Hellos" depends
   on the Farewell arriving. I wrote "a room told it was superseded is over", which is right when it is
   told; what happens when it is not is unspecified.

6. **`callerSuppliedRoomIdSample` stops compiling at 2.4.** It calls `factory.host(pattern, roomId = …)`
   with no `admission`, and 2.4 makes `admission` required on `RoomFactory.host`. 2.4 must update the
   sample and its cookbook quote (or fold it into `hostARoomTheyCanReturnToSample`). The plan's 2.4
   file list says "`kuilt-game` samples" but not this one. `docs/usage.md`'s host snippet is the same
   case; I drafted the fix there.

7. **"An hour by default" is the policy's default, not the room's.** The spec says "default 1 hour
   since last contact, per room". In the API, the hour is `AdmissionPolicy.open(readmitFor = 1.hours)`
   and nothing else. An app with its own policy has no default. I wrote "by default" in the table; a
   reader may look for a room setting that does not exist.

8. **Does the returning device itself learn which way back it got?** Bystanders and the host get
   `Joined(…, returning)`, or `Resumed`. The plan does not say what the *joiner* sees on its own
   `events` for a `Returning` admission (a fresh `Joined` for itself? `ResumeResult.WindowClosed` from
   1.7's flight?). An app that wants to say "welcome back" on the phone has no documented signal.

9. **Revocation and a connected-seat restore.** Decided 14 sends a revoked device's *held-seat*
   restore to ordinary admission, and 1.5 says to check revocation before trusting any restore
   verdict. Is a same-id, same-key restore of a still-*connected* seat (a superseded link) also
   refused for a revoked key? The docs say "revoking voids … the seat held for it while it is away",
   deliberately silent on the connected case.

10. **The reject `retryable` crossing the wire (Decided 25) is assumed.** The cookbook now says an
    app's own code "arrives as `RejectCode.Unknown`, carrying the `retryable` the app gave it". True
    only once 2.5 ships it; if 2.5 does not, restore the old "treated as retryable, whatever its
    `retryable` says" wording in three places (`session.md` twice, `hostAdmissionSample`).

11. **"Removing someone present is the app's separate call" (Decided 12) names a call that does not
    exist.** `Room` has no eviction. I wrote "`Room` has no method that makes it", which is accurate
    and unhelpful. A reader will ask how.

12. **Parameter names I guessed:** `admission` on `RoomFactory.host`, `LoomRoomHost` and
    `KtorRoomHost` (the plan names the type, not the parameter); `LeaveReason.Superseded.by`;
    `ReturningMark.previousId`; `MembershipEvent.Joined.returning`. The last three are in the plan's
    "Names"; the first is not.

13. **The threat-model paragraph names `kuilt-nw`'s pre-shared-key session as closing the relay
    gap.** The spec says channel binding closes it, citing that session. Whether `kuilt-nw` closes it
    *today* — with the proof's binding field empty — I could not confirm; the spec's own wording is
    quoted verbatim in both module.md files, and the cookbook paraphrase should be checked.

## Docs that still teach app-driven resume (not edited)

- KDoc: `Room.resumeToken`, `Room.resume`, `Room.roomId` ("does not extend across a host restart …
  #1593"), `RoomFactory.host` ("It does not make a host restart resumable … #1593").
- `kuilt-session-test/README.md`: `RejectCode.ResumeWindowExpired` (line 29) and `setResumeToken` —
  1.8 removes the code.
- `examples/README.md` line 63: `ResumeTokenFailoverTest` — 1.7/1.8 rewrite that test.
- `moduleDescription()` for `kuilt-session` ("reconnect tokens") and `llms.txt` — 1.8 owns both.

## Next

State: branch pushed, every edit committed; nothing compiled, no PR (by design).

Open threads: questions 1, 2, 4 and 5 above need rulings before 1.9's docs can be final — 1 and 5 may
be real behaviour gaps rather than doc wording. Question 6 is a compile break 2.4 will hit.

One next action: put questions 1, 2, 4 and 5 to Iain (or the slice-1 spec-conformance review) before
dispatching 1.9, then give the 1.9 worker this file and the "Owner" column.
