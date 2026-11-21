# Draft tests for #3159 Tasks 1.5, 1.6, 1.7 — notes

Branch `draft/3159-tests-1.5-1.7`, cut from `origin/identity/3211-admission-proof` at `65257d458`
(1.4 in progress). **Nothing here has been compiled or run** — no Gradle was run, by brief. This file
is for the draft branch only; do not merge it.

Files:

- `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/KeySeatRig.kt` — shared rig (1.5, 1.6)
- `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/ProvenRestoreTest.kt` — Task 1.5
- `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/SupersededMembershipTest.kt` — Task 1.6
- `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/partition/ReHelloReconnectTest.kt` — Task 1.7

## The rig (`KeySeatRig.kt`)

- **`RawHost`** is the `KeyAdmissionTest` shape: a host room over a `FakeSeam`, joiners written as
  frames signed with `fakeDeviceKey(n)` (whose key equals `fakeKeyStore(n)`'s). Nothing answers the
  host's pings, so a joiner the test does not `ping` is partitioned after `PARTITION_DETECTED`
  (timeout 200 ms + 2 ticks). `partition(peer, alive)` asserts three witnesses: the roster level
  is `Partitioned`, and a `Partitioned` and a `WindowOpened` event fired.
- **The partitioned-seat `tryResume` arm is reachable on purpose.** The heartbeat detector counts
  any inbound frame as contact but reports recovery only on its own poll ticks
  (`HeartbeatPartitionDetector.awaitRecoveryOrLoss`). So a Hello and Proof delivered with no virtual
  time passing are decided while the seat is still partitioned. If an implementation puts a delay
  between Hello and decision, the seat can flip to connected first. The outcome assertions still
  hold then, but the `hold.calls` counts do not.
- **`ObservedHold`** wraps a real `DefaultJoinerReconnectController` and records every
  `ProvenResume` and verdict. It can also answer in the real controller's place, which gives a hold
  policy that refuses while the real window and its timer keep running underneath.
- **`Switchboard`** is a test-local fabric of `FakeSeam`s that carry frames into each other, so real
  rooms keep peer ids the test chooses. `InMemoryLoom` numbers its peers, so "rebuilt with the same
  fabric id" can't be expressed on it. `InMemoryRoomFabric.clientLoom(peerId)` takes an id, but
  what `MuxServerLoom` does with a second connection under a live id is unmeasured. Every cast
  member stays in every seam's peers, so `unplug` and `mute` black-hole a link (silence, then a
  timeout partition) rather than closing it. `sent` records attempted sends before muting, so a
  frame to a dead process is visible. `stallFirst` is the `AdmitFanOutOrderingTest` stall, applied
  on one recipient's lane. A candidate to promote to `:kuilt-test` if it earns its keep.
- `processScope()` is a child scope of `backgroundScope`. Cancelling it, plus `unplug`, is a
  process crash with no Goodbye.

## 1. Per test

### Task 1.5 — `ProvenRestoreTest`

| Test | Pins | Rig counted by |
|---|---|---|
| `healedLinkSameIdRestoresWithResumed` | partitioned same-id/same-key → `tryResume` → exactly `[Resumed(bob)]` in the host's edges; roster, key, liveness `Connected`; direct Welcome `returnKind = Restored`; the burst re-sends Carol and the host intro; Carol hears only `Unpaused(bob)` | `partition()` witnesses; `admit()` asserts the second Hello drew a challenge; `hold.calls == [bob]` with `bobKey`, verdict `Success` |
| `processRebuiltWithSameIdInsideWindowRestores` | real rooms; crashed process, new room over the **same** store and fabric id → `[Resumed(bob)]` on the host, no Left/Joined on the bystander, `Restored` Welcome, the rebuilt room holds host and Carol | roster level `Partitioned` before rebuild; `hold.calls`/`verdicts` |
| `stillConnectedSeatRestoresAndSupersedesOldLink` | connected same-id re-Hello → `[Resumed(bob)]`, no `tryResume`, `Restored`, nothing at all to Carol | liveness `Connected` asserted before the re-Hello; `hold.calls` empty |
| `bystanderClaimingALiveMembersIdIsRejectedKeyConflict` | Eve injects Hello+valid Proof under fabric sender `bob` with her key → one `Reject(KeyConflict)` (non-retryable); host keeps Bob's key; no edges; Bob stays in Carol's roster **and Bob's own room survives the Reject sent to his id** | the injected Hello drew exactly one challenge (decoded) |
| `clearedKeyStoreOnADurableIdIsAdmittedAfresh` | Decided 18: partitioned same id, new key → no Reject, no `tryResume`; `Left(bob)` then `Joined(bob, newKey)`; Carol: `Farewell(bob)` before `Welcome(bob)`; **still a member after 2× the old window** (stale-timer hazard, §3 item 9) | `partition()`; `hold.calls` empty |
| `releasedSeatsClaimantIsDecidedAgainUnderDecided30` | Amendment B § 1.5: Bob's partitioned seat released; the claimant key is Dave's (connected) → additional seat under `bob`, no terminal conflict, Dave untouched, no `Superseded` | `partition()` with Carol and Dave alive |
| `windowExpiredDecidesAgainWithoutTheSeat` | refusing hold policy → decided again without the seat → Ordinary: no `Resumed`; `Left` then `Joined`; unmarked Welcome; still a member after 2× window | `hold.calls == [bob]`, `verdicts == [WindowClosed]` |
| `seatChecksRunUnderTheLockAtProofTime` | window expired **between** Challenge and Proof → no `tryResume`, one `Joined(bob)`, unmarked Welcome | level `Partitioned` at Challenge time; 2 challenges; `Left(bob, PartitionExpired)` seen before the Proof |
| `hostKeyJoinerSayingHelloAgainRestoresItsOwnSeat` | Amendment A § 1.5 host-key check: a tab on the host's key restores its **own** seat on re-Hello (`[Resumed(tab)]`, `Restored`) | `member(tab).key == hostKey` before |

### Task 1.6 — `SupersededMembershipTest`

| Test | Pins | Rig counted by |
|---|---|---|
| `processRebuiltWithFreshIdInsideWindowRestoresUnderNewId` | host and bystander each see exactly `[Left(bob-1, Superseded(bob-2)), Joined(bob-2)]`; bystander wire: one `Farewell(bob-1, supersededBy = bob-2)` before `Welcome(bob-2)`; host **sent** the Farewell to `bob-1` directly (Decided 19), into a dead link; no `ResumeAck` anywhere (Amendment A § 1.6); new seat has the key | level `Partitioned` before; `hold.calls == [bob-1]`, `Success` |
| `bystanderOrderingHoldsUnderConcurrentFarewell` | the supersession Farewell stalls 500 ms on Carol's lane; `Welcome(new)` still arrives after it; Carol's events `Left` before `Joined` | `stallFirst` counter == 1 |
| `supersededJoinerDoesNotReHello` | Amendment B rig: A muted toward the host only, so A is partitioned but hears the host; B on the **same store** joins; A gets one `Farewell(a, supersededBy = b)`, emits `Left(a, Superseded(b))`, roster empty; then the cut is lifted and A's link flapped (reweave over `FlakyLifecycleSeam`); **zero Hello or Resume** from A over 10× admit timeout; host has only B | Farewell delivered to A counted; level `Partitioned`; A still holds host before B joins |
| `secondConnectedTabGetsAnAdditionalSeat` | two connected tabs, one store → both members, same key twice, no `tryResume`, no `Left` anywhere, one Hello each over 10× admit timeout, each tab sees the other | first tab `Connected` before the second joins |
| `hostSecondTabIsAdmittedAsMember` | two tabs on the host's store → both admitted, host still the host to each, no `tryResume`, no `Left` | both tabs' keys == host key |
| `severalPartitionedSeatsRestoreTheMostRecentlyConnected` | key holds two partitioned seats. Tab 1 was admitted **later** but went quiet **earlier**. Tab 3 restores tab 2, the more recent contact; tab 1 stays held. This tells "most recently connected" apart from "most recently admitted" (§3 item 4) | both levels `Partitioned`, `since2 > since1` |

### Task 1.7 — `ReHelloReconnectTest`

Scripted-host harness: the joiner is a real room over `FlakyLifecycleSeam(FakeSeam)` with
`reweave = { link }`. The test plays the host, signs Challenges with `fakeDeviceKey(0)` and keeps
the link alive with host pings. `dropAndHeal()` asserts the joiner emitted `Partitioned(host)` and
that `resumeFlightsStarted()` grew.

| Test | Pins | Rig |
|---|---|---|
| `reconnectLoopSendsHelloNotResume` | 0 `Resume`; the re-Hello is **addressed** to the host (broadcast Hellos stay 1, Amendment A § 1.7); no `HostLost`; host liveness `Connected`; some event (`Resumed` or `Recovered` for the host) closes the arc | the joiner's proof for the re-Hello's challenge is counted |
| `freshAdmissionAfterSeatLossIsNotFollowedByASecondHello` | unmarked Welcome → Hello count frozen across 2× window; no `HostLost`/`AdmissionFailed`; still in session | same |
| `refusalIsSurfacedWithItsCode` | `Reject(KeyConflict)` → exactly one `HostLost(Refused(code = KeyConflict))`, no retry Hello, 0 `Resume` | same |
| `blipTheHostNeverSawRestoresByHelloAlone` | real host, own stores; #1637 sub-timeout blip → host tap: Hello == 2, Resume == 0; host edges `[Resumed]`; no `tryResume`; no `HostLost` | Hello == 2 is the reconnect witness; no host `Partitioned` |
| `outageTheHostSawRestoresByHelloAlone` | the host's timeout is shorter than the outage → partitioned restore through `tryResume`; Resume == 0; host edges `[Resumed]` | host `Partitioned` asserted mid-outage; `hold.calls` |

Plan item "existing `JoinerResumeMachine` teardown and generation tests still pass unmodified bar
the frame they wait for": that is the Amendment A § 1.7 list, so I did not draft it.

## 2. Assumed symbols

| Symbol | Source |
|---|---|
| `public class ProvenResume internal constructor(peerId: PeerId, roomId: RoomId, key: DidKey)` in `partition/ProvenResume.kt` (tests read `.peerId`, `.key`) | plan line 687, files line 683 |
| `JoinerReconnectController.tryResume(resume: ProvenResume, at: Long): ResumeResult.HostVerdict` (`ObservedHold` overrides it, delegating the rest with `by`) | plan line 688 |
| `AdmitMessage.Welcome.returnKind: ReturnKind? = null` and `enum class ReturnKind { Restored, Returning }` in `us.tractat.kuilt.session.admit` | plan lines 54, 684 |
| `LeaveReason.Superseded(by: PeerId)`, a data class, compared with `==` | plan lines 54, 710; Decided 19 |
| `AdmitMessage.Farewell.supersededBy: String? = null` (data class, compared with `==` and constructed as `Farewell(peerId, supersededBy = …)`) | plan lines 53, 710 |
| Until 1.8: `Welcome.resumeSecret`, `ResumeSecret.generate()` (the scripted Welcome in `ReHelloReconnectTest` sets one) | existing; 1.8 removes them (plan line 748) |
| Existing, relied on: `AdmitTap` (internal, `KeyAdmissionTest.kt`), `SeamRoom.resumeFlightsStarted()`, `Liveness.Partitioned.since`, `HeartbeatPartitionDetector.PING_PREFIX`, `FakeSeam.deliver/directed/broadcasts`, `FlakyLifecycleSeam.enterWeaving/recover`, `fakeDeviceKey(n).id == fakeKeyStore(n).deviceKey().id` | branch at `65257d458` |
| Not used, though the plan names them: `MembershipEvent.Joined(member, returning: ReturningMark? = null)`, `ReturningMark(previousId)` | plan line 54 |

## 3. Ambiguities and contradictions writing the tests exposed

Ordered roughly by how much they change an implementation.

1. **The host-key check can be read two ways.** Amendment B § 1.4 says the host-key check
   before `decideSeat` "routes it to additional-seat admission". Read literally, every Proof under
   the host's key gets a new seat, so a host's tab that says Hello again (same id) would never
   restore its own seat. It would be re-admitted silently, since `addToRoster` treats a known id as
   a re-admit. Decided 23's last sentence and Decided 30 point the other way: keep the host's seat
   out of `held` and decide normally. `hostKeyJoinerSayingHelloAgainRestoresItsOwnSeat` encodes
   that second reading. **Needs a ruling.**
2. **Amendment A § 1.6 bullet 1 contradicts Amendment B § 1.6 and Decided 30.** A says "a
   **connected** old seat under a new id is superseded directly, without `tryResume`
   (`supersededJoinerDoesNotReHello` has a connected seat)". B and Decided 30 say a connected seat
   is never superseded: it gets an additional seat, and that test's first room is rigged
   partitioned. I followed B (later, and normative).
3. **`decideSeat` as merged in 1.2 predates Decided 30, and 1.5/1.6 must change it.**
   - Rule 2 (id seat under another key ∧ key holds a seat → `KeyConflict`, whatever the liveness)
     must go for a *partitioned* id seat (Amendment B § 1.5). Rule 3 still covers the connected
     case.
   - `RestoreUnderNewId` must fire only for partitioned seats and break ties by most recently
     connected, not by lowest `PeerId`.
   - A new verdict is needed for an **additional seat**. Whether `AdmissionPolicy` vets an
     additional seat (2.4) is **unsaid**: it is "a new member" (spec table), but the policy "sees
     only ordinary admissions". This changes 2.4's policy call counts and the conformance friction
     bar.
   - `SeatDecisionTest` will need `newIdSameKeyRestoresUnderNewId` (connected fixture) and
     `idConflictBeatsKeyMatchElsewhere` rewritten.
4. **"Most recently connected" has no data behind it.** `Seat` carries no timestamp. The nearest
   value is `Liveness.Partitioned.since`, which is the *detection* time. A `TransportClosed` fires
   at once and a `Timeout` only after `timeout`, so `since` order can disagree with last-contact
   order. `severalPartitionedSeatsRestoreTheMostRecentlyConnected` reads it as last contact
   (approximated by `since`) and tells it apart from admission order. **Needs a ruling** on the
   reading, and probably a last-contact field.
5. **"A still-connected seat's old link is superseded" has nothing to observe.** On every fabric
   here the link is the peer id, and the session cannot close a fabric link. The 1.5 test asserts
   only `Resumed`, the burst and silence to bystanders. Does "supersede" mean anything more:
   restart the detector, drain the lanes, reset the inbox?
6. **The same id admitted ordinarily emits no `Joined` today.** `addToRoster` treats an id
   already held as a re-admit and emits no event. "Decided again → Ordinary" for the *same* id
   (Decided 18 release, refused restore) must therefore remove the old seat first. Which
   `LeaveReason` should it carry: `PartitionExpired`, `Normal`, or a new one? And does the
   bystander Farewell set `expired`? My tests assert only `Left` before `Joined` and `Farewell`
   before `Welcome`. **Needs a ruling.**
7. **The stale window evicts the new member.** A refused restore, or a `ReleaseAndAdmit`, leaves
   the controller's window for the old seat armed. When it fires, `handleWindowExpired` evicts the
   *new* same-id membership. Two tests pin "still a member after 2× the window". The controller
   has no "forget this window" call that is not an expiry (`onPeerRecovered` only disarms the
   timer and leaves it resumable). The fix may need a `JoinerReconnectController` API change,
   which is public ABI, consumers' hold policies included (#1614).
8. **The `WindowNotYetOpen` race makes "any other verdict treats the seat as gone" unsafe.**
   `markPartitioned` sets the level, then `onPeerUnresponsive` opens the window on a launched
   coroutine (`DefaultJoinerReconnectController.openWindow`). A Proof landing between the two
   sees a partitioned seat with no window, and `tryResume` answers `WindowNotYetOpen`. Task 1.5's
   rule then releases a healthy seat. A consumer hold policy can answer `WindowNotYetOpen` too.
   I wrote no test (the gap needs a gated controller). **Recommend** treating `WindowNotYetOpen`
   on the Proof path as a connected restore, not a release.
9. **Where does the `KeyConflict` Reject go?** It goes to the fabric sender, which is the
   *claimed* id, and on sender-routed fabrics that is the **victim**. Today a joiner ignores a
   Reject once admitted (`failAdmission` returns early), which the 1.5 test pins. After 1.7, a
   Reject that lands during the victim's own re-Hello flight resolves that flight as refused, so
   the victim gets `HostLost(Refused(KeyConflict))`: an impostor can kick a reconnecting member.
   Not tested; flagged for 1.7.
10. **What the 1.7 flight reports on a fresh admission.** "The room keeps it" and "the flight
    reports `ResumeResult.WindowClosed`" pull apart. Today the reconnect loop maps `WindowClosed`
    to a terminal `HostLost`. For an automatic reconnect, nothing public sees the flight result:
    only `Room.resume`'s return does, and that is an app call the friction bar forbids. The test
    asserts no `HostLost` and the room still in session.
11. **What closes the joiner's reconnect arc after 1.7?** Today the `ResumeAck` handler restores
    the host's level and emits `Resumed(host)`. After 1.7 the `Restored` Welcome has to, or the
    roster pins the host `Partitioned` (the #1637 shape). The plan doesn't say whether the joiner
    emits `Resumed`; the test accepts `Resumed` or `Recovered`. Amendment A § 1.5 also asks "which
    path sends `ResumeAck`". The 1.6 test asserts none is sent on supersession; I asserted nothing
    for 1.5.
12. **`ProvenResume.roomId` is always this room's.** The room builds it from its own id, so
    `DefaultJoinerReconnectController.tryResume`'s `roomId` guard becomes dead code on the Proof
    path. Three things lean on that guard: `RoomConformanceSuite` mutation row M3 (commonMain
    line 151), `aTokenMintedForAnotherRoomIsRefused` (~line 1488) and `RoomIdentityTest` (~244).
    They stay reachable through `handleResume` until 1.8, then go dead.
13. **The `Returning` arm of `windowExpiredDecidesAgainWithoutTheSeat` cannot pass in 1.5.** It
    needs the host to verify a presented grant (`GrantTrust` enters the room in 2.4, returning
    admission in 2.6). Suggest moving that arm to 2.6, with 1.5 calling `decideSeat(…,
    holdsValidGrant = false)`. Amendment A § 1.5's "check revocation per key before trusting any
    restore" is in the same position: `RevocationList` reaches the room in 2.4.
14. **"The new seat inherits the identity and the key" (1.6).** Is the identity the old seat's
    `MemberIdentity` or the new Hello's display name, session id and device id? My tests give
    both processes the same name, so they don't tell the two apart. Ruling needed. A related
    question is unasserted: should `Joined(new)` carry `ReturningMark(previousId = old)`? It fits
    exactly, but the plan ties the mark to 2.6.
15. **"Concurrent Farewell" has two readings.** I read `bystanderOrderingHoldsUnderConcurrentFarewell`
    as the `AdmitFanOutOrderingTest` stall on the bystander's lane. Another reading is a second
    member's Goodbye-driven Farewell raised at the same time.
16. **The `supersededJoinerDoesNotReHello` count is only meaningful if A would otherwise have
    come back.** A still hears its host and never tears, so a non-closing A would never reconnect.
    The test therefore flaps A's link (reweave) and lifts the cut, then counts Hello **or**
    `Resume`, so the test means something before 1.7 too. The positive control, that a
    non-superseded joiner does come back after such a flap, lives in 1.7's
    `reconnectLoopSendsHelloNotResume` rather than beside it.
17. `ObservedHold`'s window is `KEY_SEAT_HEARTBEAT.reconnectWindow` (2 s) whatever the room's
    config. In `ReHelloReconnectTest.realPair` the room says 4 s. This is harmless (the outage is
    0.6 s), but thread the config through if it ever matters.

## 4. What 1.5's `tryResume(ProvenResume, Long)` breaks

Compile breaks (`override suspend fun tryResume(token: ResumeToken, …)`):

- `kuilt-conformance/src/commonTest/.../ReferenceResumeHarness.kt:113` (published-suite rig;
  Amendment A § 1.7 reshapes it anyway)
- `kuilt-session/src/commonTest/.../AdmitFanOutOrderingTest.kt:501`
- `kuilt-session/src/commonTest/.../ForgedResumeTest.kt:75` and `:338` (`token.copy(roomId = …)`
  has no `ProvenResume` equivalent: `copy` doesn't exist on a non-data class with an internal
  constructor)
- `kuilt-session/src/commonTest/.../HostReconnectControllerInjectionTest.kt:69`
- `kuilt-session/src/commonTest/.../StarTopologyPresenceFanoutTest.kt:510`
- `kuilt-session/src/commonTest/.../WindowEpisodeIdentityTest.kt:145`

Direct callers that build a `ResumeToken` (same module, so they can build `ProvenResume` through
the internal constructor): `kuilt-session/src/commonTest/.../partition/JoinerReconnectControllerTest.kt`
lines 117, 138, 140, 158, 277, 331, 345, 360, 364, 376, 393, 399, 422.

Production: `SeamRoom.handleResume` (~2005; Amendment A: it also builds a `ProvenResume` after its
secret check, until 1.8) and `DefaultJoinerReconnectController.tryResume` (56).

KDoc and docs that describe `tryResume(token)` and go stale: `JoinerReconnectController.kt` KDoc
(validation order, "the secret is not this controller's concern"); `docs/agent-cookbook/session.md`
lines 241, 245, 247; `RoomConformanceSuite.kt` lines 151, 1488; `JoinerReconnectTest.kt:233` and
`RoomIdentityTest.kt:244` (comments). Consumers with their own hold policies (#1614) break too,
which the spec lists under Consequences.

Shared-store harnesses still on the branch that 1.5–1.7 will trip: `partition/ResumeRefusalReportingTest.kt:273`
and `partition/SubTimeoutBlipResumeTest.kt:359` build host **and** joiner from one factory with
`fakeKeyStore(0)`. So the joiner is the host's second tab. Both are on Amendment A § 1.7's change
list; split their stores there.

## 5. 1.4 branch drift checked at hand-back

`origin/identity/3211-admission-proof` moved `65257d458` → `4aae7a683` while I worked. That covers
the api dumps for `BadProof`/`KeyConflict`, the cookbook, `RejectCodeWireCompatTest`, and
`proofForAnotherHostIsRejected` split into two single-field proofs. A test helper,
`Seam.helloAndProve(hello, device)` in `AdmitTestFrames.kt`, landed as well; it could replace
`RawHost.admit` for some uses. None of the shapes I relied on changed: `AdmitTap`, `FakeSeam`,
`decideSeat`, `handleProof`, `KeyAdmission`, `Member`. I did not rebase.

## Next

- **State:** four drafted test files plus this note, all pushed to `draft/3159-tests-1.5-1.7`.
  Uncompiled, and no PR.
- **Open threads:** §3 items 1, 2, 4, 6, 7 and 14 need a ruling (Iain or the controller) before
  1.5/1.6 implement. Items 8–10 are hazards to brief into the 1.5 and 1.7 workers.
- **One next action:** the 1.5 worker cherry-picks `KeySeatRig.kt` and `ProvenRestoreTest.kt` onto
  its branch and compiles them first (`:kuilt-session:compileTestKotlinJvm`). Expect small API
  fixes: `TestCoroutineScheduler.advanceTimeBy(Duration)`, `Seam by fake` members, the `EventLog`
  subscription. Then it watches them red against 1.4 before implementing.
