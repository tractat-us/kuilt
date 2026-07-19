# Consumer primitive gaps — build/decline decisions (#1555–#1558)

Four "primitive gap" issues were filed from a survey of a real kuilt consumer. Each asks the same
question: does kuilt build this, or decline and document it? This spec records the verdict and the
design for each.

The four are not the same shape. One is already ~90% built and needs a small hole closed; one is
unsound to build at kuilt's layer; two are genuine, small, absent primitives.

| Issue | Gap | Verdict |
|-------|-----|---------|
| [#1555](https://github.com/tractat-us/kuilt/issues/1555) | Pre-`Seam` bootstrap election over discovery advertisements | **Decline** + pattern doc |
| [#1556](https://github.com/tractat-us/kuilt/issues/1556) | Reconnect state/reason taxonomy for UI | **Build**, honestly scoped + extensible |
| [#1557](https://github.com/tractat-us/kuilt/issues/1557) | Server seat-hold + Paused presence | **Mostly built** — close the fan-out hole |
| [#1558](https://github.com/tractat-us/kuilt/issues/1558) | Never-paired room reaping | **Build**, standalone in `:kuilt-liveness` |

---

## #1555 — Pre-`Seam` bootstrap election: DECLINE

### Why

Deterministic symmetric election requires an *agreed* input set. A discovery snapshot is not one,
and three independent mechanisms break it:

1. **Visibility asymmetry.** Min-id election diverges exactly when peer `P` has not yet discovered
   the global minimum `M`. `M`'s own snapshot always contains itself, so `M` elects itself while `P`
   elects someone else — two hosts. Bonjour/mDNS discovery latency of seconds between peers is
   normal, not exotic.
2. **Ghost accumulation.** `NwLoom.visiblePeers` is add-only (`NwLoom.kt:111-118,143`) — the design
   doc already calls this "ghosts forever" (`docs/host-election-design.md:155-157`). A peer electing
   a departed ghost dials a dead advertisement and wedges until a *consumer-owned* timeout; kuilt has
   no machinery that unwedges it. `PeerDiscoverySource.departures()` defaults to `emptyFlow()`
   (`PeerDiscoverySource.kt:29-36`), so kuilt cannot promise ghost removal at the interface level.
3. **Identity-ordering incoherence.** `Tag.peerKey` is transport-scoped — for mDNS it is the
   `PeerId` value, for Multipeer the `MCPeerID` handle (`Tag.kt:20-27`). #1555 asks for election over
   a *merged* mDNS+Multipeer roster, but `min` over a merged roster is ill-defined: the same physical
   peer carries different keys per transport, so two peers with **perfect** visibility still compute
   different minima. No kuilt-layer API typed over `Tag` can fix this — only a canonical `PeerId`
   embedded in the advertisement payload can.

Divergence does **not** self-heal by any kuilt machinery. `RaceCollapse` is not the resolver — it
aborts a suspended operation when an *existing* seam tears or drains (`RaceCollapse.kt:29-62`) and
has nothing to say pre-`Seam`. Simultaneous mutual dials are resolved by the kuilt-nw dedup-nonce
tiebreak *inside* the fabric (`NwLoom.kt:91-92`, `NwLoom.kt:203-208`). On asymmetric fabrics two
hosts means two disjoint sessions with no merge path, unlike the symmetric mesh where a two-group
merge is just a set change (`ElectionLobby.kt:47-48`).

Shipping the primitive would also make kuilt's own surface self-contradictory: `ElectionLobby.kt:48-49`
already documents that election input must come from `Seam.peers` and "never a discovery roster."

### What already exists

- `electHost(peers: Set<PeerId>)` is already a pure, public function over a bare peer set
  (`ElectionLobby.kt:15-16`). Nothing prevents calling it on discovery-derived ids today.
- The sound path is fully shipped: `SeamRoomFactory.electLobby(pattern)` (`SeamRoom.kt:190`) →
  `ElectionLobby` with reactive `host = electHost(Seam.peers)` (`SeamElectionLobby.kt:70-73`) → a 2PC
  freeze/ack/commit round for the one irrevocable moment (`SeamElectionLobby.kt:118-197`).
  "Elect late" is kuilt's designed answer to this problem class: election over an eventually-consistent
  input is fine *while nothing is irrevocable*, and the irrevocable moment gets an abortable handshake
  (`docs/host-election-design.md:26-32`).

The surveyed consumer's hand-roll is evidence **for** declining: it is not "elect then dial" but
*optimistic-host-then-defer with continuous reconciliation*. The election function is one line; the
remaining ~95% is an app-specific convergence state machine (teardown, runtime open, start-latching)
that kuilt cannot own without becoming a framework. A primitive would replace the one line and imply
the 95% is unnecessary.

### Deliverable

1. **`docs/discovery-bootstrap.md`** — a pattern doc with two branches:
   - **(A) Symmetric fabric → use `electLobby`.** The shipped, sound path; prefer migrating to it.
   - **(B) Asymmetric fabric → optimistic-host-then-defer.** Advertise and host immediately; fold
     `discoveries()`/`departures()` into a roster; continuously recompute `electHost(roster ∪ self)`
     as **advisory**; on seeing a lower id, tear down and dial it, capturing its advertisement at
     decision time; a join timeout returns to hosting. Embed a canonical `PeerId` in the
     advertisement payload when merging transports.

   Explicit warnings: never one-shot; never from an accumulate-only roster; mutual-dial dedup is the
   fabric's job, not `RaceCollapse`'s.

   Per the repo references policy, the pattern is described standalone — no consumer-repo citation.
2. **KDoc pointers** on `electHost` (acknowledge the advisory pre-`Seam` use, link the pattern doc)
   and on `PeerDiscoverySource` (same pointer).
3. **Follow-up issue (not part of this work):** an election-free `discoveryRoster(sources, scope):
   StateFlow<Set<Tag>>` fold in `:kuilt-core` discovery — the "merge these flows" job the interface
   KDoc already assigns to consumers (`PeerDiscoverySource.kt:12-13`). Honest, tiny, carries no host
   field. It does *not* answer #1555 and must not be presented as if it does.

Docs-only, so `ci-required` skips the build.

---

## #1556 — Reconnect taxonomy: BUILD, honestly scoped

### The reframe

The issue asks for four buckets: transient / auth-expired / protocol-mismatch / unrecoverable. **Two
of them are not classification problems.**

- **auth-expired** — kuilt issues exactly one credential: `ResumeToken`, whose expiry *is* the
  reconnect window, already surfaced as `ResumeResult.WindowClosed`. Any other auth-expiry (a JWT, a
  session cookie) lives behind `RoomAuthorizer`, which is **consumer-supplied policy**
  (`RoomAuthorizer.kt`). kuilt classifying it would be claiming knowledge it structurally cannot have.
- **protocol-mismatch** — `AdmitMessage.Hello` carries no version field; kuilt has no version
  negotiation at all. This is a **missing feature**, not a missing classification. A bucket for it
  would be a label with no producer.

So: classify what kuilt actually observes, and file version negotiation separately.

### Why the raw material is there but unusable

Five partial taxonomies across three modules, with no UI-facing rollup:

| Signal | Module |
|--------|--------|
| `CloseReason` (`Normal`/`Error`/`RemoteRequested`/`Unreachable`) | `:kuilt-core` |
| `PartitionEvent.Reason` (`Timeout`/`Backpressure`/`TransportClosed`) | `:kuilt-liveness` |
| `AdmissionFailure` (`Rejected(String)`/`TimedOut`) | `:kuilt-session` |
| `LeaveReason` (`Normal`/`Error`/`PartitionExpired`) | `:kuilt-session` |
| `ResumeResult` (`Success`/`WindowClosed`/`TokenInvalid`) | `:kuilt-session` |

Nothing tells a consumer the one thing a reconnect banner needs: *am I retrying, or do I give up?*

### Design

A read-only rollup in `:kuilt-session`, derived from signals kuilt already emits. No new state
machine — a projection.

```kotlin
/** What a reconnect UI needs to know: are we live, retrying, or done. */
public sealed interface ConnectionState {
    public data object Connected : ConnectionState
    /** Link dropped, a resume window is open. [expiresAt] bounds the retry. */
    public data class Retrying(val since: Instant, val expiresAt: Instant) : ConnectionState
    /** Terminal. [reason] says whether retrying could ever help. */
    public data class Disconnected(val reason: FailureReason) : ConnectionState
}

/** Why a connection failed, at the granularity kuilt can honestly observe. */
public sealed interface FailureReason {
    /** A later retry may succeed — timeout, backpressure, unreachable host. */
    public data class Transient(val cause: PartitionEvent.Reason?) : FailureReason
    /** The host actively refused. [code] is structured; [message] is its free text. */
    public data class Rejected(val code: RejectCode, val message: String) : FailureReason
    /** The resume window closed, or the session ended cleanly. Retrying is futile. */
    public data object Unrecoverable : FailureReason
}
```

`RejectCode` is an **open interface, not an enum** — matching the `DiscoveryKind` precedent
(`DiscoveryKind.kt`), which is deliberately an interface "so transport modules in other Gradle
modules can supply their own kinds without amending `:kuilt-core`." kuilt defines `RoomMismatch`,
`ResumeWindowClosed`, and `Unknown(id)`; consumers add their own.

Mapping (all existing producers):

| Source | → |
|--------|---|
| `MembershipEvent.WindowOpened` / `Partitioned` | `Retrying(since, expiresAt)` |
| `MembershipEvent.Resumed` / `Joined(self)` | `Connected` |
| `MembershipEvent.HostLost`, `LeaveReason.PartitionExpired` | `Disconnected(Unrecoverable)` |
| `AdmissionFailure.Rejected(msg)` | `Disconnected(Rejected(code, msg))` |
| `AdmissionFailure.TimedOut`, `CloseReason.Unreachable` | `Disconnected(Transient(null))` |
| `PartitionEvent.Reason.*` | `Transient(reason)` |

Surface: `Room.connection: StateFlow<ConnectionState>`, folded from the existing `MembershipEvent`
stream. Additive — no existing member changes.

### Wire change (additive, not breaking)

`AdmitMessage.Reject(reason: String)` gains an optional structured code alongside the existing free
text. Today it has exactly two producers — `"room-mismatch: …"` (`SeamRoom.kt:820`) and
`"resume-rejected"` (`SeamRoom.kt:975`) — which become `RoomMismatch` and `ResumeWindowClosed`. A
peer that does not send a code decodes as `Unknown`, so old ↔ new interop degrades rather than breaks.

`RoomAuthorizer.authorize` keeps returning `Boolean`. Widening it to a reason type is explicitly
**out of scope**: authorization policy is the consumer's, and kuilt should not invite it to claim an
auth-expiry semantic kuilt cannot verify.

### Follow-up issue

Protocol-version negotiation in the admit handshake (`AdmitMessage.Hello` carries no version). Filed
separately; a `ProtocolMismatch` reject code lands with that feature, not before.

---

## #1557 — Server seat-hold + Paused presence: mostly built, close the fan-out hole

### What already exists

`JoinerReconnectController` **is** the server-side seat-hold: `onPeerUnresponsive(peerId, at)` opens
a timed window, `tryResume(token, at)` validates room + window + single-use, and it emits
`WindowOpened` / `Resumed` / `WindowExpired`. Expiry evicts with `LeaveReason.PartitionExpired`. The
Paused presence exists too: `MembershipEvent.Partitioned` / `WindowOpened` / `Recovered`, with the
member's `Liveness` transitioning to `Liveness.Partitioned`.

### The actual hole

Those events are emitted **locally**, from each peer's own heartbeat detector
(`SeamRoom.kt:1192-1200` — `markPartitioned` / `markRecovered` both `_events.tryEmit(...)` with no
send). On a symmetric mesh every peer runs a detector against every other peer, so each learns
independently and the local emission is sufficient. On a **star/host-relayed topology a joiner has no
direct link to another joiner** and therefore never learns that peer is paused.

kuilt already has the authoritative-fan-out pattern for the *clean* leave case: a departing peer's
`Goodbye` reaches the host, which propagates `AdmitMessage.Farewell` to every remaining member so all
evict promptly with `LeaveReason.Normal` (#1292). **There is no equivalent for the partition case.**

### Design

Add the missing half of that pair:

```kotlin
/** Host → members: [peerId]'s link dropped; its seat is held until [expiresAt]. */
public data class Paused(val peerId: String, val expiresAt: Long) : AdmitMessage
/** Host → members: [peerId] resumed within its window. */
public data class Unpaused(val peerId: String) : AdmitMessage
```

Host side: `markPartitioned` broadcasts `Paused` alongside its local emit; `markRecovered` broadcasts
`Unpaused`.

**Window expiry needs fixing too.** `propagateFarewell` is called from exactly one site — the
`Goodbye` handler (`SeamRoom.kt:848`). Expiry therefore propagates nothing; the KDoc's fallback, "a
lost Farewell degrades to that member's heartbeat-window eviction" (`SeamRoom.kt:1064`), only works
on a mesh where every member heartbeats every other. In a star topology a joiner has no heartbeat
against another joiner, so an expired seat is **never** evicted from its roster — the peer is stuck
`Partitioned` forever. Rather than add a third message, reuse the existing authoritative one:
broadcast `Farewell(peerId)` on `JoinerReconnectEvent.WindowExpired` as well. `handleFarewell`'s
host-authoritative gate (`SeamRoom.kt:1083-1090`) already covers it, and members are already
idempotent about evicting an absent peer.

This is a latent star-topology bug in its own right, independent of the Paused fan-out.

Member side: on `Paused`, apply `Liveness.Partitioned` and emit the same `Partitioned` +
`WindowOpened` pair a locally-detecting peer would; on `Unpaused`, emit `Recovered`.

The result is that `MembershipEvent` means the same thing on every member regardless of topology —
which is the invariant a consumer assumes today and does not get.

Additive on the wire (unknown variants already tolerated). Mesh behaviour is unchanged: a peer that
detects locally *and* receives `Paused` must be idempotent, so the member-side handler is a no-op
when the member is already `Partitioned`.

Also: a `docs/agent-cookbook.md` entry, because the survey shows this was hand-rolled purely for lack
of discoverability.

---

## #1558 — Never-paired reaping: BUILD, standalone in `:kuilt-liveness`

### Why it is genuinely absent

`HeartbeatPartitionDetector` detects a peer that *was* present and went silent. Every `PartitionEvent`
carries a non-null `peerId` — the contract is inherently per-peer. "A room that never filled" has no
peer to lose a heartbeat from; there is only a slot that was reserved and never paired. Different
shape, and nothing covers it.

### Why it maps onto an existing documented state

`SeamState`'s KDoc already blesses the condition explicitly: "`Woven` with `peers == {selfId}` is a
fully legitimate, well-defined state — the fabric is live and this peer is simply alone in the
session." The primitive is therefore *"`Woven` but below the minimum membership past deadline `T`"* —
a projection of a documented state, not a new concept.

### Design

A standalone type in `:kuilt-liveness`. It does **not** extend `PartitionDetector` — overloading a
per-peer contract with a peerless condition would break `PartitionEvent.peerId`'s non-nullability.

```kotlin
public class SoloDeadlineDetector(
    private val minimumMembers: Int,        // required; 2 = "never paired"
    private val deadline: Duration,         // required
    private val clock: Clock,               // injected — never Clock.System
    scope: CoroutineScope,                  // required; no real-dispatcher default
) {
    public val events: SharedFlow<SoloDeadlineEvent>
    public fun observeMembership(members: Set<PeerId>)
}

public sealed interface SoloDeadlineEvent {
    /** Membership stayed below the minimum for the full deadline. */
    public data class NeverPaired(val observed: Int, val required: Int, val at: Instant) : SoloDeadlineEvent
    /** Membership reached the minimum before the deadline; the detector disarms. */
    public data class Paired(val at: Instant) : SoloDeadlineEvent
}
```

Semantics: arm on construction; each `observeMembership` at or above `minimumMembers` emits `Paired`
and disarms permanently (this detects *never* paired, not *currently* solo — a room that paired and
later emptied is the partition detector's job). Otherwise the deadline elapses and `NeverPaired`
fires once.

It **emits**, it does not close. Reaping policy — close the room, close the seam, log — stays the
consumer's.

Per the repo's constructor rules: `clock` and `scope` are required and non-nullable; there is no
default real dispatcher. Tests use `StandardTestDispatcher(testScheduler)` with bounded
`advanceTimeBy` — never `advanceUntilIdle()`.

Also: a `docs/agent-cookbook.md` entry under "Liveness & presence", quoting a compiled snippet.

---

## Cross-cutting requirements

Per `CLAUDE.md`, **every new public primitive** (#1556's `ConnectionState`/`FailureReason`, #1557's
`Paused`/`Unpaused`, #1558's `SoloDeadlineDetector`) requires:

1. A symptom→primitive entry in `docs/agent-cookbook.md`, quoting a compiled snippet verbatim with a
   `<!-- verbatim from … -->` citation.
2. A check that `.claude/skills/kuilt-primitives/SKILL.md` still routes to it and its `description`
   matches how a developer would phrase the need.
3. KDoc + a `@sample` in `src/commonSamples/kotlin/` (compiled as part of `commonTest`).

A new primitive with no cookbook entry is treated as a broken build.

## Sequencing

#1555 (docs), #1556, #1557, #1558 are mutually independent — no shared files. #1556 and #1557 both
touch `AdmitMessage` and `SeamRoom`, so they are the one pair that must not be dispatched
concurrently onto the same region; sequence #1557 after #1556, or brief both on the collision.

## Testing

- **#1556** — a fold test per mapping-table row; a round-trip test that an unknown reject code decodes
  as `Unknown` (old↔new interop).
- **#1557** — a star-topology test: host + two joiners, joiner A's link drops, assert joiner B
  observes `Partitioned` + `WindowOpened` (fails today); a second asserting joiner B observes
  `Left(PartitionExpired)` once A's window expires (also fails today — the latent eviction bug); an
  idempotency test that a mesh peer detecting locally *and* receiving `Paused` emits once.
- **#1558** — deadline elapses with one member → `NeverPaired`; membership reaches the minimum first →
  `Paired` and no later `NeverPaired`; paired-then-emptied → silence (partition detector's job).

All under `runTest(StandardTestDispatcher(), timeout = 5.seconds)` with bounded time advancement.
