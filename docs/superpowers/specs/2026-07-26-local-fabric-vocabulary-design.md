# Local-fabric vocabulary: self-attributed liveness as a level

**Date:** 2026-07-26
**Issues:** #1712 (primary), #1618 Q2, plus two defects found during design (D3, D4 below)
**Track:** A of three — see [Scope boundary](#scope-boundary) for B and C

## The problem in one paragraph

A peer can learn that *some other peer* went quiet. It can never learn that **it** went quiet.
Every member of the liveness vocabulary names a `peerId` belonging to somebody else, so a device
that loses its own network attributes the outage to its peers. In a two-peer session the two cases
are indistinguishable from peer-side observations alone, and a consumer rendering presence from the
vocabulary can only ever say "the other player is disconnected" — never "you are disconnected."

The fix is not a new detector. It is to publish, as **readable state**, a fact the transport layer
already computes, and to stop making consumers reconstruct liveness by replaying edges.

## What is already true (corrections to #1712's framing)

#1712 states the self-reachability fact "sits in the **transport**, not in the presence vocabulary"
and "exists only for the `nw` lane." Both are inaccurate, and the difference matters:

- `Seam.capability: StateFlow<TransportCapability>` is in **commonMain `:kuilt-core`**
  (`Seam.kt:75`), carrying `FabricAvailability.{Available, Unavailable(reason), Unknown(reason)}`.
  The transport-agnostic slot exists.
- `NwSeam` already drives it live from `NWPathMonitor` (#1541, `NwSeam.kt:697`), and `CompositeSeam`
  already rolls plies up as *any Available ⇒ Available; else any Unknown ⇒ Unknown; else Unavailable*
  over currently-Woven plies (`CompositeSeam.kt:258`).

So the real gaps are narrower and different: `Room` never exposes the fact, and the interface
**default lies**.

## The four defects

| | Defect | Source |
|---|---|---|
| **D1** | `Room` publishes no self-attributed fact. A consumer must reach past `Room` into a transport-specific API. | #1712 |
| **D2** | The **host's** `WindowOpened` reaches `Room.events` through a deferred `launch` onto a **replay-0** `SharedFlow`; the other two emission sites emit inline. | #1618 Drop B |
| **D3** | A **joiner** never sets its host's `Member.liveness`. While partitioned from its host, `events` has said `Partitioned(hostId)` but `roster` still reports that host `Connected`. | found in design |
| **D4** | On a **joiner**, `reconnectController` is `null`, so the `markPartitioned` lane emits **no `WindowOpened` at all** — there is nothing to count down from. | found in review |

### D2 root cause — not lossy delivery

`DefaultJoinerReconnectController.onPeerUnresponsive` does `scope.launch { openWindow(…) }`, and
`openWindow` ends with `_events.emit(...)` on a `MutableSharedFlow(extraBufferCapacity = 64)` with
**`replay = 0`**. A `SharedFlow` emit with no subscriber and no replay **discards the value**.
`runReconnectEventLoop` is host-only and subscribes during startup; the deferred `launch` widens the
interval in which nobody is listening. #1618's body frames this as "unreliable delivery"; it is a
structurally different code path with a subscription race, and its body should be updated.

### D4 evidence

`reconnectController` is constructed only `if (role == SessionRole.Host && roomId != null)`
(`SeamRoom.kt:593`), and `runReconnectEventLoop` launches only when it is non-null (`683`). On a
joiner whose host goes silent by `Timeout` (not `TransportClosed`), `handlePeerUnresponsive` routes
to `markPartitioned`, which emits `Partitioned`, then calls `reconnectController?.onPeerUnresponsive(…)`
— a no-op against `null`. `propagatePaused` is host-gated. No window, ever.

**`markPartitioned` is role-agnostic, so one fix closes D2 and D4 together.**

## Design principles

1. **The level is authoritative; events are idempotent notifications.** This is already kuilt's
   `roster`-vs-`events` convention; the liveness vocabulary simply never finished adopting it.
2. **Session-scoped, never device-scoped.** A `Room` rides exactly one `Seam`, so anything `Room`
   publishes means *"my end of **this room's** fabric."* A peer in two rooms over two fabrics has two
   independent values and neither speaks for the other. Multipath *within* a room is already handled
   by `CompositeSeam`'s any-Available rollup: a Bluetooth+Wi-Fi bonded room reports `Unavailable`
   only when every woven ply is down. kuilt does **not** get a device-level registry.
3. **`Unknown` is a first-class answer.** A lane with no live path observer must say so, not guess.
4. **"Identically wired" means both roles run the same code against their own observations — not
   that both roles see the same sequence.** The observations legitimately differ: #1637 is
   joiner-only by construction, so a sub-timeout blip gives the joiner `Partitioned → Resumed` and
   the host *nothing at all*. What is symmetric is the **level** (`roster` + `localFabric` read
   identically on both roles), never the event stream.

## The public surface

### `:kuilt-core` — the honesty floor

```kotlin
// internal/StaticCapability.kt — replaces StaticAvailableCapability
internal val StaticUnknownCapability: StateFlow<TransportCapability> =
    MutableStateFlow(
        TransportCapability(emptySet(), FabricAvailability.Unknown("no live path observer on this fabric")),
    ).asStateFlow()
```

`Seam.capability`'s interface default becomes this. Today it is a hardcoded `Available`
(`internal/StaticCapability.kt:18`), overridden only by `NwSeam` and `CompositeSeam` — so surfacing
it from `Room` unchanged would make every other lane assert "you are online" forever, an
authoritative false negative strictly worse than the current silence. `FabricAvailability.Unknown`
already exists for exactly this and is currently unused as a floor; #1530's stated intent was
"model UNKNOWN explicitly rather than guessing."

### `:kuilt-session` — the self-attributed level

```kotlin
public interface Room {
    /**
     * Whether **this peer's own end of the fabric carrying this room** can carry frames now.
     *
     * Session-scoped, never device-scoped: a peer in two rooms over two fabrics has two
     * independent values and neither speaks for the other. A room over a bonded
     * [CompositeSeam] reports [FabricAvailability.Unavailable] only when every woven ply is down.
     *
     * [FabricAvailability.Unknown] means the fabric has no live path observer. Treat it as
     * "kuilt cannot tell", never as either answer.
     */
    public val localFabric: StateFlow<FabricAvailability>
}
```

### `MembershipEvent` — edges and the precedence tag

```kotlin
public data class LocalFabricLost(val at: Instant, val reason: String) : MembershipEvent
public data class LocalFabricRestored(val at: Instant) : MembershipEvent

public data class Partitioned(
    val peerId: PeerId, val at: Instant, val reason: ReconnectReason,
    val localFabric: FabricAvailability,   // new
) : MembershipEvent

public data class HostLost(
    val at: Instant, val reason: FailureReason,
    val localFabric: FabricAvailability,   // new
) : MembershipEvent
```

Edges fire only on transitions **into** `Unavailable` and **into** `Available`. A move into `Unknown`
emits nothing: "we stopped being able to tell" is not a loss, and claiming either edge there would be
the same over-claim being removed. `Unknown` is visible on the level only.

The tag goes on the two events that carry bad news about someone else, satisfying #1712's requirement
that precedence be readable "from the stream rather than by racing timestamps." `HostLost` matters
most: today a joiner whose own radio died gets a bare `HostLost` and renders "the host is gone."
`Recovered` and `Resumed` stay untagged — good news needs no excuse.

Because the tag is scoped to the emitting room by construction, the strongest claim it can make is
"the fabric carrying this room was down when I observed this peer go quiet." It is structurally
incapable of becoming "the player is offline." That is why tagging was chosen over a combined
`Room.presence` aggregate, which would be equally room-scoped in fact but whose name invites the
device-level misreading, and which a UI would bind straight to a "player offline" badge.

### `Liveness` — the window as a level

```kotlin
public sealed interface Liveness {
    public data object Connected : Liveness
    public data class Partitioned(val since: Instant, val windowExpiresAt: Instant) : Liveness
}
```

`windowExpiresAt` is **non-null** and set at the same site and instant that sets the state, so a
partitioned member with no known window becomes unrepresentable rather than a state to handle.
`markPartitioned` already computes `at + heartbeatConfig.reconnectWindow` one line below for the
`Paused` fan-out (`SeamRoom.kt:1512`); `handlePaused` already has `paused.expiresAt`.

Rejected alternatives: nullable `Member.partitionedSince` / `windowExpiresAt` fields on `Member`
(permits `Connected` carrying a deadline — the illegal state stays representable), and a separate
`Room.windows: StateFlow<Map<PeerId, Instant>>` (a fourth surface to keep in step with `roster`,
`events` and `localFabric`).

## Mechanism

### One collector writes both the level and the edges

They cannot diverge, because there is one writer.

```kotlin
private val _localFabric = MutableStateFlow(seam.capability.value.availability)
override val localFabric: StateFlow<FabricAvailability> = _localFabric.asStateFlow()

// scope.launch — capability is a StateFlow, not `incoming`, so no ADR-034 single-collection conflict.
private suspend fun localFabricLoop() {
    var lastDecided: FabricAvailability? = null      // last Available or Unavailable seen
    seam.capability.collect { cap ->
        val next = cap.availability
        if (next == _localFabric.value) return@collect
        _localFabric.value = next                    // level FIRST — always authoritative
        when (next) {
            is FabricAvailability.Unavailable ->
                if (lastDecided !is FabricAvailability.Unavailable) {
                    emitEvent(MembershipEvent.LocalFabricLost(clock(), next.reason))
                    lastDecided = next
                }
            is FabricAvailability.Available -> {
                if (lastDecided is FabricAvailability.Unavailable) {
                    emitEvent(MembershipEvent.LocalFabricRestored(clock()))
                }
                lastDecided = next
            }
            is FabricAvailability.Unknown -> Unit    // level only; lastDecided deliberately unchanged
        }
    }
}
```

`lastDecided` is tracked separately from the previous value so `Unavailable → Unknown → Available`
still emits `LocalFabricRestored` — otherwise a lane that briefly loses observability while
recovering would silently never restore.

**Ordering guarantee:** the level is written before any edge is emitted, so a consumer that receives
`LocalFabricLost` and then reads `localFabric` can never catch a torn read.

The precedence tag reads `_localFabric.value` at **every site emitting those two event types** — four
in total: `Partitioned` from `markPartitioned` (1507), from `handlePaused` (1587) and from
`onReconnectStarted` (651), and `HostLost` from `markHostLost` (1660). It is a plain
`MutableStateFlow` read, taken **outside** the room's `lock` (which guards `admittedById` only); every
one of those sites already emits outside the lock.

### Seam lifetime

`SeamRoom.seam` is a `val`, and `reweave` is a documented **same-instance heal**
(`JoinerResumeMachine.kt:109`; a loom that mints a different seam is treated as non-conforming and
its throwaway is closed, `362-374`). So `seam.capability` is a stable handle for the room's lifetime
and survives reconnects — `localFabric` inherits the same guarantee `seamState` already relies on.

### D2 + D4 — one fix, both roles

`markPartitioned` computes `expiresAtMs` **once** (it already does) and feeds three things from that
single value:

1. `updateMemberLiveness(peerId, Liveness.Partitioned(since = at, windowExpiresAt = …))`
2. the existing `propagatePaused(...)` (host only)
3. a new **inline** `emitEvent(MembershipEvent.WindowOpened(peerId, expiresAt))`

`runReconnectEventLoop` drops its `WindowOpened` mapping and keeps `Resumed` / `WindowExpired`.

The numbers provably agree: the controller is constructed with
`reconnectWindowMs = heartbeatConfig.reconnectWindow.inWholeMilliseconds` (`SeamRoom.kt:603`) and
receives the same `at` that line 1512 uses. Same inputs, same formula. Inline emission has no
`launch`, no intermediate `SharedFlow`, and no subscription race — and `Room.events` itself has a
bounded replay cache, so a late `Room.events` collector still sees it.

**Authority hazard, and its fix.** A joiner watching *another joiner* time out would compute a
deadline the **host** is authoritative for, and `handlePaused` currently `return`s early when the
member is already `Partitioned`, so the host's real `expiresAt` could never correct the local guess.
`handlePaused` keeps its early return for *event* emission (idempotent) but is allowed to **refine
the deadline** on the level. A joiner watching its *host* has no such problem: there the joiner's own
`reconnectWindow` budget genuinely is the authority. (Alternative considered and rejected: an
`authoritative: Boolean` on `Liveness.Partitioned` — more honest, more surface, and no consumer has
asked to distinguish them.)

### D3 — the joiner's host liveness

`onReconnectStarted(hostId, at, windowDeadline)` currently emits two events and mutates no state. It
gains `updateMemberLiveness(hostId, Liveness.Partitioned(since = at, windowExpiresAt = windowDeadline))`;
`Resumed` clears it back to `Connected`. (`hostId` is in `admittedById` on a joiner — `restoreHostDetector`
already looks it up there.) `roster` and `events` stop contradicting each other.

### Why the level settles the `Recovered`-vs-`Resumed` ambiguity

#1618's Correction 2 advises "always `Recovered`, both sides." #1637 makes the joiner's no-op resume
emit `Resumed` instead, so an edge-keyed consumer must clear on `Recovered` **or** `Resumed` — either
alone hangs a real case. A consumer reading `Member.liveness` keys on neither.

This creates a hard cross-track constraint: because **D3** makes the joiner *set* its host's liveness,
**#1637's no-op-resume path is obligated to clear it**, whichever terminal event it emits. That
belongs in #1637's plan (`docs/superpowers/plans/2026-07-26-1637-sub-timeout-blip.md`, branch
`plan/1637-sub-timeout-blip`).

## What deliberately does not change

- **`:kuilt-game` — zero edits.** `RoomGameSession.presence` *is* `room.events` and `roster` *is*
  `room.roster`, so the whole vocabulary arrives for free. That property is exactly why #1712 asked
  for this in the vocabulary rather than as a transport special-case.
- **`:kuilt-liveness` untouched.** `PartitionEvent` stays peer-attributed — it *is* a peer detector,
  and the cookbook's "every `PartitionEvent` names a `peerId`" stays true.
- **No wire-format change.** `LocalFabricLost` / `LocalFabricRestored` are strictly local
  observations and are never propagated to peers. A peer's own fabric state is unreachable-by-
  definition at the moment it matters, so anything that arrived would be stale — and the receiving
  peer already holds the honest version of that fact as its own `Partitioned` event.

## Testing

### Conformance — the enforcement point

The obvious replacement invariant, *"`Woven` ⇒ not `Unavailable`"*, is **wrong**: on the nw lane the
path goes `Unsatisfied` while the seam deliberately stays `Woven` through the #1478 grace period.
`Woven` + `Unavailable` is precisely the self-loss window this design exists to surface, so the TCK
must permit it.

`SeamConformanceSuite` instead gets a declaration each fabric makes about itself, replacing the
`Available` assertion at line 448:

```kotlin
/**
 * Whether this fabric drives [Seam.capability] from a live OS observer. Default `false`:
 * the fabric inherits the [FabricAvailability.Unknown] floor and must not claim otherwise.
 * A fabric flips this to `true` only alongside a test proving its observer moves the value.
 */
protected open val reportsLiveCapability: Boolean = false
```

When `false`, the suite asserts `availability is Unknown` — proving the fabric did not fake
`Available`. When `true`, the fabric owns a reactivity test. The boolean becomes an enforced ledger
of which lanes are real, and each Track B slice is "flip one boolean, add one test." The assertion is
on `availability` only; a fabric may still report real static `roles`.

### Session tests

`:kuilt-session`, `runTest(StandardTestDispatcher(), timeout = 5.seconds)`, injected clock, bounded
`advanceTimeBy`, never `advanceUntilIdle()`. The existing fake already overrides `capability`
(cf. `FastReconnectRaceTest.kt`).

| Test | Asserts |
|---|---|
| level/edge coherence | `Available→Unavailable→Available` drives both; reading `localFabric` *inside* the `LocalFabricLost` collector already sees `Unavailable` |
| the `Unknown` rule | a move into `Unknown` emits no edge; `Unavailable→Unknown→Available` still emits `LocalFabricRestored` |
| precedence tag | with `localFabric == Unavailable`, a peer partition and a host loss carry `Unavailable` on `Partitioned` / `HostLost` |
| **D2 regression** | host detects a joiner unresponsive → `WindowOpened` on the host's *own* `events`; and the structural claim: a collector subscribing only *after* the partition still reads the deadline off `roster` |
| **D3 regression** | joiner's host tears → `roster` shows the host `Partitioned(windowExpiresAt = …)`, not `Connected` |
| **D4 regression** | joiner's host goes silent by `Timeout` → a window with a deadline is readable, on both `events` and `roster` |
| authority refinement | a joiner's locally-estimated peer deadline is corrected by a later host `Paused`, without emitting a duplicate `Partitioned` |

D2/D3/D4 are bug fixes and follow the repo rule: failing test as the first commit, fix as the next,
then revert the fix and confirm the test goes red again.

### Verification gate

A session-behaviour change, so `:kuilt-session:jvmTest` is a false green. Full
`./gradlew build detektAll --rerun-tasks`, including the `:examples` E2E cluster tests. No `!!`
anywhere — CI's `detektJvmMain` type-resolution pass fails on it where a local `detektAll` can
false-green (#1537).

### Hardware validation

The nw lane is the one lane where `localFabric` reports a real value on day one, and it is
validatable in the same airplane-mode session already owed for other work: drop the joiner device
short (~8 s, the #1637 repro) and then long (~90 s). Do **not** `closes #1712` until the surviving
device has been observed rendering self-attribution rather than peer misattribution.

## Breaking changes

All four are real consumer breaks. Pre-1.0 makes them acceptable, not invisible.

1. `Liveness` enum → sealed interface: every `== Liveness.Partitioned` becomes `is`, including
   `agent-cookbook.md:323` and its `Member.liveness` rows (21, 318).
2. `Partitioned` / `HostLost` gain a field — positional construction in tests breaks.
3. `Seam.capability` floor flips to `Unknown` — `SeamConformanceSuite:448` and any consumer reading
   it on a non-nw fabric.
4. **`kuiltVersionLine` 0.7 → 0.8.** Per the repo's own rule a minor bump is *"reserved for a
   deliberate breaking-API release and is a human call, not a default."* Flagged here; **not** to be
   put in a PR without that decision.

## Scope boundary

Track A is the observation vocabulary. It explicitly does **not**:

- **Make any lane but nw report a real value.** After this lands, `Room.localFabric` reads `Unknown`
  forever on WebSocket, TCP, InMemory, Multipeer, Nearby, WebRTC and Mux. That is the intended
  shippable state: it fixes the reported nw case and is honest, not silent, everywhere else.
  **Track B** flips them one at a time — #1542 multipeer, #1543 nearby, #1544 webrtc, #1545
  composite, #1546 mux, plus a websocket-lane issue that does not yet exist.
- **Touch recovery behaviour.** In particular it does **not** wire `localFabric` into
  `JoinerResumeMachine`. That is the #1637 discriminator — "my own fabric dropped and healed, so the
  host never saw a tear, so no window is coming" versus the plan's dwell timer — and it should be
  decided in **Track C**'s spec (#1636, #1637, #1655) with this signal on the table, not smuggled in
  here.
- **Address #1618 Q1's other detection branches, or Q3** (the premature `Resumed`).
- **Add a device-level rollup across rooms.**

Track A does not depend on Track B; `Seam.capability` already exists. Track C's #1636 and #1655 are
**not** helped by self-attribution — #1636's problem is on the *survivor*, whose path stays satisfied
(which is why #1650 did not fix it), and #1655's black-hole has a healthy local path by construction.
Only #1637 is coupled.

## Delivery

One defect per PR, TDD each:

1. `:kuilt-core` floor flip + `SeamConformanceSuite.reportsLiveCapability`
2. `Room.localFabric` + `LocalFabricLost` / `LocalFabricRestored`
3. precedence tags on `Partitioned` / `HostLost`
4. `Liveness` enum → sealed interface carrying the deadline
5. D2 + D4 — inline `WindowOpened` in `markPartitioned`, drop the controller mapping, `handlePaused`
   deadline refinement
6. D3 — joiner sets its host's liveness
7. cookbook + KDoc + `commonSamples`

Seven small PRs suits the repo's aggressive-merge posture. PR 5 changes consensus-adjacent session
behaviour, so it takes the full-build gate above rather than a module-scoped build.

## Issue hygiene

To be done alongside the work, not at the end:

- **File D3** and **D4** — both found during this design, currently unfiled.
- **File the websocket-lane reactive-capability issue** that #1712 calls out and Track B lacks.
- **Comment on #1618** with the D2 root cause (replay-0 `SharedFlow` + deferred `launch`, not lossy
  delivery), and post the Correction 2 supersession: #1637 makes the joiner emit `Resumed` where
  Correction 2 says `Recovered`, so anyone reading that thread today gets advice #1637 invalidates.
  Update the body, which still frames D2 as unreliable delivery.
- **Correct #1712's body** — `Seam.capability` is commonMain `:kuilt-core`, not nw-only; the real gap
  is the `Available` floor plus no `Room` surface. Attach the hardware evidence, which currently
  argues the case from first principles.
