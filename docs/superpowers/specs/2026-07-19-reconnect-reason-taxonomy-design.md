# Reconnect state/reason taxonomy for UI — design (kuilt #1556)

_2026-07-19. Branch `reconnect-taxonomy` off `origin/main`. Closes kuilt #1556._

## Problem

kuilt owns the reconnect *mechanics* (`ResumeToken`, the resume/grace-window flow,
`HeartbeatPartitionDetector`) and already emits a rich event vocabulary
(`MembershipEvent`, `PartitionEvent.Reason`, `ResumeResult`, `AdmissionFailure`).
But the two events a reconnect **banner** actually keys off carry no *why*:

- `MembershipEvent.Partitioned(peerId, at)` — "the link dropped" with no cause.
- `MembershipEvent.HostLost(at)` — terminal, with no failure classification.

So a consumer driving a "reconnecting…" banner or a "give up and show an error"
decision has to re-derive the classification app-side. A real consumer
(`live-runtime`'s `ConnectionState`/`ReconnectReason`/`FailureReason`) does exactly
this — a parallel taxonomy re-implemented on top of kuilt's own signals, sorting
failures into transient / auth-expired / protocol-mismatch / unrecoverable buckets
that kuilt is the layer positioned to observe.

**Ask (from the issue):** ship a public reconnect-reason / connection-state
taxonomy, or decline. **Decision: build**, threaded onto the existing events.

## What kuilt can honestly observe (the altitude constraint)

The taxonomy must model only what kuilt actually sees. In particular
**auth-expired** and **protocol-mismatch** are *not* distinguishable by kuilt today:
the admit `Reject` frame carries only a free-form message string, not a typed code.
Those arrive as a `Refused(message)` catch-all; the consumer parses the semantics
from the message. Typing the reject code is a wire-protocol change (cross-Seam,
overlaps #1557) and is explicitly **out of scope** here — tracked as a follow-up.

## Design

### Home

`:kuilt-session`, top-level `us.tractat.kuilt.session` package (alongside
`MembershipEvent`). It is the only layer that sees both the partition signal and
the terminal session outcomes; `:kuilt-liveness` sits below and cannot reference
session types.

### Two new public sealed types

```kotlin
/** Why a peer's link is currently down and a reconnect/window is in progress. */
public sealed interface ReconnectReason {
    /** No heartbeat within HeartbeatConfig.timeout — a silent drop. */
    public data object LinkTimeout : ReconnectReason
    /** The per-peer outbound buffer exceeded its ceiling. */
    public data object Backpressure : ReconnectReason
    /** The underlying Seam was closed or torn. */
    public data object TransportClosed : ReconnectReason
}

/** Why a joiner's session terminally failed. Attached to HostLost. */
public sealed interface FailureReason {
    /** The reconnect window elapsed without a successful resume. */
    public data object WindowExpired : FailureReason
    /** The host actively Rejected the resume — carries its raw reason string.
     *  Where auth-expired / protocol-mismatch surface until typed reject codes exist. */
    public data class Refused(val message: String) : FailureReason
    /** No resume path exists: no reweave support, a non-conforming loom, or no known host. */
    public data object Unrecoverable : FailureReason
}
```

`ReconnectReason` mirrors `PartitionEvent.Reason` but stays a distinct
session-level type: the joiner-side `Partitioned` (host-tear) does **not** originate
from a `PartitionEvent`, and the public session vocabulary should not leak the
lower-level liveness enum. The map from `PartitionEvent.Reason` is a one-liner.

### Thread onto the two events that lack a "why"

Breaking change to two data classes — acceptable under the pre-1.0 aggressive-merge
posture; the requesting consumer absorbs it.

- `MembershipEvent.Partitioned(peerId, at, reason: ReconnectReason)`
- `MembershipEvent.HostLost(at, reason: FailureReason)`

`WindowOpened` already carries `expiresAt`; `AdmissionFailed` already carries
`AdmissionFailure`; `Recovered` needs nothing. Only these two change.

### Producer wiring (all at existing `SeamRoom`/`JoinerResumeMachine` sites)

| Emission site | Reason produced |
|---|---|
| `markPartitioned` ← `PartitionEvent.PeerUnresponsive` (`handleUnresponsive` already holds `event.reason`) | `Timeout→LinkTimeout`, `Backpressure→Backpressure`, `TransportClosed→TransportClosed` |
| `onReconnectStarted` (joiner host-tear) | `TransportClosed` |
| `onReconnectFailed`, immediate-terminal branch (no reweave/token/host, non-conforming loom) | `Unrecoverable` |
| `onReconnectFailed`, window-timeout branch; host-`PeerLost` → `markHostLost` | `WindowExpired` |
| resume-reject path (see below) | `Refused(message)` |

The first four rows are **pure data-flow, no behavior change** — every branch
already knows which case it is in; only a value the code already has is threaded
through. `handleUnresponsive`, `markPartitioned`, `onReconnectStarted`,
`onReconnectFailed`, `markHostLost` gain a `reason` parameter; `JoinerResumeMachine`
determines `Unrecoverable` vs `WindowExpired` at the two `onReconnectFailed` call
sites it already distinguishes.

### The `Refused(message)` behavior refinement (in scope)

Today a host `Reject` of a resume resolves the flight as `ResumeResult.WindowClosed`
and `runReconnect` keeps retrying the refused token until the window elapses →
`WindowExpired`. That is both futile (a refused token will never be accepted) and
lossy (the host's message is discarded).

Change: the resume-reject path (`rejectFlight` / the admit-frame `Reject` handler)
carries the host's message and short-circuits the retry loop, so the terminal event
is `HostLost(Refused(message))` instead of a delayed `HostLost(WindowExpired)`.

This **touches reconnect behavior**, so it is gated separately:
- its own failing-test-first TDD step: `Reject(msg) during resume → HostLost(Refused(msg))`, no retry-until-window;
- the **full `./gradlew build`** (plus `:examples:test`) before auto-merge — a module-scoped build is a false green for reconnect-behavior changes.

The `AdmissionFailure.Rejected(message)` variant (admit-phase, pre-admission) already
carries a message and is left as-is; `FailureReason.Refused` is the post-admission
analogue. KDoc cross-references the two so the parallel is legible.

## Testing

Through the existing `SeamRoom` / reconnect harness — **no hand-rolled cluster**.

- `PartitionEvent.Reason → ReconnectReason` map: one parameterized test, all three variants → the right `Partitioned.reason`.
- Each `HostLost` branch → `FailureReason`: window-timeout → `WindowExpired`; no-reweave / non-conforming loom → `Unrecoverable`; host `PeerLost` → `WindowExpired`.
- `Refused`: failing-test-first — a host `Reject(msg)` mid-resume yields `HostLost(Refused(msg))` and does **not** spin the retry loop to the window deadline.
- `RoomConformanceSuite` TCK: assert the new fields are populated on the relevant transitions (any conforming room impl carries the reason).

## Docs sync (required)

New public primitive ⇒ the CLAUDE.md rule the capability-discovery track added applies:

- **`docs/agent-cookbook.md`** — add an intent row under **Rejoin & reconnect**:
  "drive a reconnect banner / classify why the session dropped →
  `MembershipEvent.Partitioned.reason` + `HostLost.reason` (`ReconnectReason` /
  `FailureReason`)", with a compile-checked `@sample` snippet
  (`AgentCookbookSamples.kt#reconnectBannerSample`).
- **`.claude/skills/kuilt-primitives/SKILL.md`** — sync the same primitive.
- **KDoc** on both new types and the two changed events; a `commonSamples` sample
  wired into `commonTest`.

## Out of scope (follow-ups)

- **Typed reject codes** (auth-expired / protocol-mismatch as first-class kuilt
  variants) — a wire-protocol change; file a follow-up if the consumer needs
  kuilt-native classification instead of parsing the `Refused` message.
- **A folded `Room.connectionState: StateFlow<ConnectionState>`** — a reducer over
  the event stream. The event-threading here is the "contained data-type addition"
  the plan scoped; a state-machine fold is a separate, larger surface.

## Consumer impact (fc #3585)

Unblocks the UI half: `live-runtime` can drive its reconnect banner and terminal
error copy off `Partitioned.reason` / `HostLost.reason` instead of re-deriving,
and delete the parallel classification. The app-semantic reasons kuilt cannot see
(server-restart, catching-up, game-over) stay app-side, layered above kuilt's.
