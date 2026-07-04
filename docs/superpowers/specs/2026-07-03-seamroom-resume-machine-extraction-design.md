# SeamRoom resume-machine extraction — design

Tracks [#1122](https://github.com/tractat-us/kuilt/issues/1122). Deferred from the
cleanup epic (#1079, DEF3): `kuilt-session/.../SeamRoom.kt` is 1230 lines; the
reconnect/resume machine is cohesive-but-separable.

## Problem

`SeamRoom` mixes four responsibilities under one shared lock: the admit
protocol, roster/membership, per-peer partition detection, and joiner-side
reconnect/resume. The host side of reconnect is already extracted —
`DefaultJoinerReconnectController` (partition package) owns window
open/expire/token-validation. The joiner side is not: `resumeToken`,
`pendingResume`, the `reconnecting`/`reconnectJob` guard, and the
`runHostReconnect` retry loop (reweave → await `Woven` → restart the incoming
collector → resume) all live directly on `SeamRoom`, entangled with roster and
detector state.

## Scope

**In scope:** the joiner-side reconnect/resume state machine only — token
issuance, the pending-resume deferred, the reconnect guard, and the
reweave/resume retry loop.

**Out of scope:** the host-side glue already in `SeamRoom`
(`handleResume`, `runReconnectEventLoop`, `handleReconnectResumed`). It's
~40 lines and already mostly delegates to `DefaultJoinerReconnectController`;
folding it into the new type would double the diff for no proven benefit.

## Design

### New type

`internal class JoinerResumeMachine` in
`kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/partition/JoinerResumeMachine.kt`,
alongside `JoinerReconnectController` / `DefaultJoinerReconnectController` /
`ResumeToken`. Naming mirrors the existing host/joiner split: the host owns a
`JoinerReconnectController`; the joiner owns a `JoinerResumeMachine`.

`SeamRoom` constructs one only for joiners, exactly mirroring how
`reconnectController` is constructed only for hosts:

```kotlin
private val resumeMachine: JoinerResumeMachine? =
    if (role == SessionRole.Joiner) JoinerResumeMachine(...) else null
```

### State ownership

**Moves into `JoinerResumeMachine`:**
- `resumeToken` + `mintResumeTokenIfAbsent` logic
- `pendingResume` (`CompletableDeferred<ResumeResult>`)
- `reconnecting` guard flag
- `reconnectJob`
- `runHostReconnect` (the retry loop) and `attemptHostReconnect` (the entry point)
- The joiner-side `resume(token)` logic (install deferred, send
  `AdmitMessage.Resume`, await the reply)

**Stays on `SeamRoom`** (read by the machine via callbacks):
- `hostPeerId` — used beyond resume, e.g. by `handleUnresponsive` /
  `handlePeerLost` to decide whether a partition event concerns the host at
  all, independent of any reconnect attempt.
- `closed`, `hostLost`, `detectorJobs`, `incomingCollectJob` — shared with
  roster/detector/main-loop logic well beyond resume.

### Lock model

The machine takes **the same `reentrantLock` instance** `SeamRoom` already
uses, passed in at construction — it does not own an independent lock.

Rationale: the current code has explicit, commented invariants around atomic
guard-flip-and-store (e.g. "Flip the guard and record the Job atomically under
the lock, so `leave()` can cancel it... with no window where the guard is set
but the Job is not yet stored"). Splitting into two independently-locked
objects would reopen exactly that kind of check-then-act window between
`SeamRoom`-owned state (`closed`, `hostLost`) and machine-owned state
(`reconnecting`, `reconnectJob`). Sharing one lock instance preserves every
existing atomicity guarantee with no new lock-ordering risk, at the cost of
the two types sharing a lock object — an explicit, acceptable coupling given
they're two halves of what was one class.

### Callback contract

The machine needs a handful of operations only `SeamRoom` can perform.
Bundled as one small internal interface, implemented by `SeamRoom`:

```kotlin
internal interface JoinerResumeHost {
    fun hostPeerId(): PeerId?
    fun isTerminal(): Boolean              // closed || hostLost
    fun silenceHostDetector(hostId: PeerId)
    fun restoreHostDetector(hostId: PeerId)
    fun restartIncomingCollect()
    suspend fun onReconnectFailed(at: Instant)   // == markHostLost
    fun emit(event: MembershipEvent)
}
```

Every callback is invoked while the machine already holds the shared lock —
the same convention the current code documents on `startDetector`/
`stopDetector` ("callers must hold lock"), just extended across the new
boundary.

`leave()` needs one more accessor to fold the reconnect job into its existing
cancel-snapshot: `resumeMachine?.reconnectJobSnapshot()`, included alongside
`incomingCollectJob` in the jobs list it already cancels.

Other constructor deps the machine needs and `SeamRoom` already has: `selfId`,
`seam`, `clock`, `heartbeatConfig`, `reweave`, `scope`.

### `SeamRoom` call-site changes

Every current direct field access becomes a delegate call; no behavioral
change:

| Current | Becomes |
|---|---|
| `override var resumeToken` | `override val resumeToken get() = resumeMachine?.resumeToken` |
| `handleWelcome`'s `mintResumeTokenIfAbsent(...)` | `resumeMachine?.mintTokenIfAbsent(welcome.roomId)` |
| `override suspend fun resume(token)` | `resumeMachine?.resume(token) ?: ResumeResult.WindowClosed` |
| `handleResumeAck` / `Reject` branch's `pendingResume?.complete(...)` | `resumeMachine?.completeResume(result)` |
| `runJoinerTornWatcher()` / `handleUnresponsive`'s host-transport-close branch: `attemptHostReconnect(at)` | `resumeMachine?.attemptReconnect(at)` |
| `leave()`'s job-cancel snapshot | includes `resumeMachine?.reconnectJobSnapshot()` |

`SeamRoom` drops entirely: `runHostReconnect`, `attemptHostReconnect`, the
`resumeToken`/`pendingResume`/`reconnecting`/`reconnectJob` fields, and
`mintResumeTokenIfAbsent`.

## Testing — hard constraint

**Existing tests are inviolable during this refactor.** `RoomConformanceSuite`,
`JoinerReconnectTest`, `RoomResumeTest`, and `TransportCloseWindowTest` (1454
lines total) already exercise every path being moved, entirely through the
public `Room` / `resumeToken` / `hasDetector` surface, which does not change.

- **Zero edits, zero deletions, zero renamed assertions** in any existing test
  file. If the extraction seems to require touching one, that is a signal the
  extraction changed observable behavior — stop and reconsider the extraction,
  don't adjust the test.
- New tests are welcome and encouraged where they'd pin the now-isolated
  machine's behavior more precisely than the black-box suites already do
  (e.g. a focused double-`attemptReconnect` guard test) — **additive only**.
- Acceptance bar: the full existing suite green and byte-for-byte unmodified,
  plus whatever new tests get added.

This constraint applies to the implementation plan and whoever executes it,
not just this design.

## Non-goals

- Extracting the host-side reconnect glue (see Scope).
- Any change to `Room`'s public API surface — `resumeToken` stays a `val`,
  `resume(token)` keeps its signature.
- Any behavioral change. This is a pure structural extraction.

## Risks

- The machine's internals must never suspend while holding the shared lock —
  the same discipline `SeamRoom` already follows and documents at the class
  level. Restate it on the new class's KDoc.
- Sharing a lock instance across two classes is an explicit, narrow coupling
  (see Lock model) — acceptable here because both classes exist to
  jointly manage what was one lock's worth of invariants; not a pattern to
  reach for generally.

## Openness to revision

This design should not be treated as gospel during implementation or review.
If a reviewer or implementer finds a way to make the result **safer,
simpler, or more maintainable** — a different state boundary, a smaller
callback surface, a clearer name — that's welcome, with two exceptions: the
scope boundary (joiner-side only) and the existing-tests-are-inviolable
constraint above are not up for revision without coming back to discuss them.
