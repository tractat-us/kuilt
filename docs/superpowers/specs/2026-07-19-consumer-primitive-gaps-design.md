# Consumer primitive gaps — build/decline decisions (#1555–#1558)

Four "primitive gap" issues were filed from a survey of a real kuilt consumer. Each asks the same
question: does kuilt build this, or decline and document it? This spec records the verdict and the
design for each.

The four are not the same shape. One is already ~90% built and needs a small hole closed; one is
unsound to build at kuilt's layer; two are genuine, small, absent primitives.

| Issue | Gap | Verdict |
|-------|-----|---------|
| [#1555](https://github.com/tractat-us/kuilt/issues/1555) | Pre-`Seam` bootstrap election over discovery advertisements | **Decline** + pattern doc |
| [#1556](https://github.com/tractat-us/kuilt/issues/1556) | Reconnect state/reason taxonomy for UI | **Build**, threaded onto existing events |
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

> **Revised 2026-07-19** after harvesting an abandoned parallel implementation (branch
> `reconnect-taxonomy`, never PR'd). That work is **not** being merged — it is re-implemented from
> this spec — but its analysis found a load-bearing constraint the original version of this section
> got **wrong**, plus two refinements worth keeping. Recorded below.

### The reframe: two of the four requested buckets are not classification problems

The issue asks for transient / auth-expired / protocol-mismatch / unrecoverable.

- **auth-expired** — kuilt issues exactly one credential: `ResumeToken`, whose expiry *is* the
  reconnect window. Any other auth-expiry lives behind `RoomAuthorizer`, which is
  **consumer-supplied policy** (`RoomAuthorizer.kt`). kuilt classifying it would claim knowledge it
  structurally cannot have — not merely knowledge it lacks today.
- **protocol-mismatch** — `AdmitMessage.Hello` carries no version field; kuilt has no version
  negotiation at all. A missing **feature**, not a missing label; a bucket for it would have no
  producer. Tracked in #1569.

### The constraint that corrects this spec: a resume `Reject` is NOT always terminal

The original version of this section mapped `AdmissionFailure.Rejected` → a terminal
`Disconnected(Rejected(...))`. **That is wrong, and shipping it would have regressed reconnection.**

`DefaultJoinerReconnectController.tryResume` returns `ResumeResult.WindowClosed` when the window has
**not opened yet** (`state == null`) — the *fast-reconnect race*, where a silently-dropped joiner
re-weaves and sends `Resume` before the host's own detector has fired. Today's retry loop is exactly
what recovers that case: a later retry lands after the host opens the window and succeeds. Treating
the first `Reject` as terminal would break fast-reconnect recovery and mislabel a Wi-Fi blip as a
refusal.

Compounding it, `SeamRoom`'s host collapses every reject cause into one constant
`Reject("resume-rejected")` (`SeamRoom.kt:975`), so the joiner cannot distinguish a transient
never-opened reject from a terminal one even in principle.

**Therefore: record-and-relabel, never short-circuit.** The resume-reject path *records* the host's
message; `runReconnect` keeps retrying exactly as today. When the window ultimately expires, the
terminal event is `HostLost(Refused(message))` if a reject was seen during the window, else
`HostLost(WindowExpired)`. If a later retry succeeds, the recorded message is discarded and no
`HostLost` fires. **No retry or timing behavior changes** — this is a labelling change only.

### Design

Home: `:kuilt-session`, top-level `us.tractat.kuilt.session` package alongside `MembershipEvent`. It
is the only layer that sees both the partition signal and the terminal session outcomes;
`:kuilt-liveness` sits below and cannot reference session types.

```kotlin
/** Why a peer's link is currently down and a reconnect / grace window is in progress. */
public sealed interface ReconnectReason {
    public data object LinkTimeout : ReconnectReason      // no heartbeat within HeartbeatConfig.timeout
    public data object Backpressure : ReconnectReason     // per-peer outbound buffer over ceiling
    public data object TransportClosed : ReconnectReason  // the underlying Seam closed or tore
}

/** Why a joiner's session terminally failed. */
public sealed interface FailureReason {
    public data object WindowExpired : FailureReason          // window elapsed, no successful resume
    public data class Refused(val message: String) : FailureReason  // host rejected; raw message
    public data object Unrecoverable : FailureReason          // no resume path exists at all
}
```

`ReconnectReason` deliberately mirrors `PartitionEvent.Reason` rather than reusing it: the
joiner-side `Partitioned` (host-tear) does **not** originate from a `PartitionEvent`, and the public
session vocabulary should not leak the lower-level liveness enum. The lift is a one-liner.

**Threaded onto the two events that lack a "why"** — a breaking change to two data classes,
acceptable under the pre-1.0 posture:

- `MembershipEvent.Partitioned(peerId, at, reason: ReconnectReason)`
- `MembershipEvent.HostLost(at, reason: FailureReason)`

`WindowOpened` already carries `expiresAt`, `AdmissionFailed` already carries `AdmissionFailure`, and
`Recovered` needs nothing. Only these two change.

Producer wiring, all at existing emission sites:

| Site | Reason |
|------|--------|
| `markPartitioned` ← `PartitionEvent.PeerUnresponsive` | `Timeout`→`LinkTimeout`, `Backpressure`→`Backpressure`, `TransportClosed`→`TransportClosed` |
| `onReconnectStarted` (joiner host-tear) | `TransportClosed` |
| `onReconnectFailed`, immediate-terminal branch | `Unrecoverable` |
| `onReconnectFailed` window-timeout; host `PeerLost` → `markHostLost` | `WindowExpired` |
| resume-reject path, at window expiry | `Refused(message)` |

The first four rows are pure data-flow with **no behavior change** — every branch already knows which
case it is in; only a value the code already holds is threaded through.

### Host-side message honesty (in scope)

`handleResume` currently discards what `tryResume` distinguished. Thread the cause into the reject
string so `Refused` carries something real:

- `ResumeResult.WindowClosed` → `Reject("resume-window-closed")`
- `ResumeResult.TokenInvalid(reason)` → `Reject("resume-token-invalid: <reason>")`

Pure host-side message refinement — no `ResumeResult` or controller change, no retry-behavior change.

**One honest limit stays, and the KDoc must say so:** `tryResume` folds *never-opened* (transient)
and *expired/consumed* (terminal) both into `WindowClosed`, so `"resume-window-closed"` still cannot
be split transient-vs-terminal without a controller change. The type must not promise a distinction
the wire does not carry.

### Dropped from the original version of this spec

- **`RejectCode` as an open interface, landing now.** Deferred to the typed-reject-codes follow-up.
  The `DiscoveryKind`-precedent argument still holds, and typed codes are in fact *more* valuable
  than first assessed — they are what would finally split never-opened from expired — but they are a
  **wire-protocol change** that overlaps #1557's `AdmitMessage` work, and the free-text `Refused`
  carries the information in the meantime.
- **`Room.connection: StateFlow<ConnectionState>`.** Over-scoped. Threading data onto existing events
  is a contained data-type addition; a reducer that folds the event stream into a connection state
  machine is a separate, larger surface a consumer can write itself. Deferred to a follow-up.

### Testing

Through the existing `SeamRoom` / reconnect harness — **no hand-rolled cluster**.

- One parameterized test over the `PartitionEvent.Reason` → `ReconnectReason` map, all three variants.
- Each `HostLost` branch → its `FailureReason`: window-timeout → `WindowExpired`; no-reweave /
  non-conforming loom → `Unrecoverable`; host `PeerLost` → `WindowExpired`.
- **`Refused`, failing-test-first:** a host `Reject(msg)` mid-resume ultimately yields
  `HostLost(Refused(msg))`. Critically, also assert the **retry loop still runs** — a regression test
  that a reject does *not* short-circuit, guarding the fast-reconnect race above.
- A fast-reconnect test: `Resume` arriving before the host's window opens is rejected, retried, and
  **succeeds** — no `HostLost` at all.
- `RoomConformanceSuite` TCK: assert the new fields are populated on the relevant transitions.
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

Per `CLAUDE.md`, **every new public primitive** (#1556's `ReconnectReason`/`FailureReason`, #1557's
`Paused`/`Unpaused`, #1558's `SoloDeadlineDetector`) requires:

1. A symptom→primitive entry in `docs/agent-cookbook.md`, quoting a compiled snippet verbatim with a
   `<!-- verbatim from … -->` citation.
2. A check that `.claude/skills/kuilt-primitives/SKILL.md` still routes to it and its `description`
   matches how a developer would phrase the need.
3. KDoc + a `@sample` in `src/commonSamples/kotlin/` (compiled as part of `commonTest`).

A new primitive with no cookbook entry is treated as a broken build.

## Sequencing

#1555 (docs), #1557 and #1558 are mutually independent. **#1556 must land after #1557**: both edit
`SeamRoom`'s reconnect region, and #1557 additionally adds `AdmitMessage` variants. #1556 is the
smaller, more surgical of the two, so it rebases onto #1557 rather than the reverse.

The typed-reject-codes follow-up is deliberately sequenced **after both** — it is a wire change that
would collide with #1557's `AdmitMessage` work if run concurrently.

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
