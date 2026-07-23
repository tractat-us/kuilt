# Uniform presence: surface a Room's presence through GameSession

**Status:** design · **Date:** 2026-07-22 · **Issue:** part of #1618

## One-paragraph summary

Today a game and a room speak two different presence languages. A `Room`
(`SeamRoom`) already publishes the canonical presence signal — `events:
Flow<MembershipEvent>` (`Partitioned`/`WindowOpened`/`Recovered`/`Resumed`/`Left`)
plus per-member `Member.liveness` — and the cookbook blesses that as *the* way to
show a "paused / reconnecting…" seat. But the game facade (`GameSession`) exposes
**no** presence at all; the app rides a `SeamRoom` for transport and then
hand-wires its own mapping from `room.events` (and, worse, from Raft roster churn)
into a presence overlay. That hand-wired adapter is where a "premature Resumed"
symptom lives, and the split means "how do I know a player dropped?" has a
different answer for a Game than for a Room. This design makes the game surface
**the same presence vocabulary the Room already emits** — no new types, no second
source of truth — by giving the game a `Room`-backed session variant whose
`presence` *is* `room.events`.

This is **Track 1**. It is an ergonomics + correctness layer. It is **not** the
fix for the #1618 hardware symptom itself (0 `WindowOpened` on a real Wi-Fi
drop) — that is **Track 2**, a separate investigation (consumer-observation /
app-config / host-re-election lifecycle) tracked on #1618 and gated on the f-c
audit + a hardware capture with the #1620 diagnostics. Track 1 stands on its own
merits regardless of where Track 2 lands.

## Goals / non-goals

**Goals**
- One presence vocabulary across Room and Game: `MembershipEvent` +
  `Member.liveness`. No new presence type.
- A game bootstrapped over a `Room` exposes `presence`/`roster` that **are** the
  room's — so consumers stop hand-wiring the mapping.
- Retire the app's `EmbeddedRoomGameHost` adapter (moves the room↔game
  composition into kuilt), eliminating adapter-synthesized `Resumed`.
- Fix a real kuilt defect found en route: `handleResumeAck` is ungated/unlatched
  and can emit spurious/duplicate `Resumed` (DIAG-4).

**Non-goals**
- Fixing the #1618 hardware symptom (Track 2).
- Human-presence semantics ("seated / away / thinking"). Presence here is
  **link liveness**; the app layers human state on top (see §7).
- Adding presence to the raw, no-Room `gameNode`/`gameHost` bootstraps. Those
  keep no presence surface (see §3, DES-1) — deliberately, via the type system.

## §1 — Shared vocabulary (reuse, don't invent)

`MembershipEvent` (`Joined`/`Partitioned`/`WindowOpened`/`Recovered`/`Resumed`/
`Left`/`HostLost`/`AdmissionFailed`) and `Member.liveness`
(`Connected`/`Partitioned`) are the presence vocabulary. Both Room and Game speak
exactly this. No `PresenceEvent`/`PeerStatus` type is introduced. Rationale: the
cookbook already documents `Room.events` + `Member.liveness` as the pause/resume
primitive; a parallel vocabulary would be a second source of truth to keep in
sync.

## §2 — Module dependency

`kuilt-game` gains `api(project(":kuilt-session"))`.

This is an **ABI promotion, not a new edge**: `kuilt-game` already depends on
`kuilt-session` transitively (`kuilt-game → implementation(:kuilt-cluster) →
api(:kuilt-session)`), so session types are already on the compile classpath. The
promotion to `api` is required because the new public API returns `Room` /
`MembershipEvent` / `Member`. Verified acyclic: `kuilt-session` does not depend on
`kuilt-game`. Layering is consistent with the module table in `CLAUDE.md` — the
game facade sits above the session library.

## §3 — `RoomGameSession`: a subtype, not a nullable field

A game bootstrapped over a `Room` returns a **`RoomGameSession`** — a subtype of
`GameSession` — with non-nullable presence:

```kotlin
public class RoomGameSession internal constructor(
    node: RaftNode,
    private val room: Room,          // held so presence/roster delegate + close() can leave it
    appMux: NamedMux,
    lobby: GamePresence?,
) : GameSession(node, room.channel(RAFT_CHANNEL_TAG), appMux, lobby) {
    /** Live membership/presence events — identical to the backing room's. */
    public val presence: Flow<MembershipEvent> get() = room.events
    /** Live roster with per-member Liveness — identical to the backing room's. */
    public val roster: StateFlow<Set<Member>> get() = room.roster
    // close(): close the Raft node, then room.leave(reason) — see §4.
}
```

(`RaftNode`/`NamedMux`/`GamePresence` are the same objects `gameOverRoom` already
builds; the base `GameSession`'s seam is the room's Raft channel. Exact param
order is a plan detail — the load-bearing contract is: it `is-a GameSession`, and
`presence`/`roster` **are** the backing room's.)

The **base `GameSession` gets no `presence` member.** The raw `gameNode`/
`gameHost` bootstraps (no Room) return a plain `GameSession`, and the type system
says "this session has no presence surface" — rather than handing back a
silently-empty flow.

**Why a subtype, not `presence: Flow = emptyFlow()` on the base (DES-1).**
`emptyFlow()` *completes immediately*, so a consumer's `presence.collect{}`
**terminates** rather than idling — which downstream UI logic misreads as "the
presence stream ended / peer gone." A silently-empty presence surface is
*precisely* the #1618 failure class. The subtype makes "has presence" a
compile-time fact, matching the repo's optional≠tuning / fail-fast convention.

## §4 — Bootstrap + lifecycle & ownership

New bootstrap (mirrors the app's current `EmbeddedRoomGameHost` composition):

```kotlin
public suspend fun CoroutineScope.gameOverRoom(
    room: Room,                       // already adopted (e.g. from electLobby → adopt)
    peerCount: Int,
    livenessConfig: HeartbeatConfig? = null,
    clock: () -> Instant,
    /* … the gameNode/gameHost params … */
): RoomGameSession
```

It runs the Raft game over `room.channel(RAFT_CHANNEL_TAG)` (the game's existing
mux tags nest *inside* the RoomChannel frame — no collision; see §9/DES-6), and
returns a `RoomGameSession` that **holds the `Room`**.

**Ownership (DES-2) — the spec's trickiest correctness point.** `GameSession.close()`
today closes the `seam` it holds; if that seam is a `room.channel(…)` view,
`close()` is a **silent no-op** (the Room owns the channel's lifecycle) — leaking
the room, its detectors, and the underlying fabric. Therefore:

- `RoomGameSession.close()` closes the Raft **node**, then calls
  `room.leave(reason)` (which closes the seam per `adopt`'s handover contract).
  Double-close is safe: `SeamRoom.leave` latches on `closed`, `NwSeam.close`
  latches via CAS.
- **Single-ownership contract:** `gameOverRoom` *takes ownership* of the passed
  `Room`. The caller must **not** call `room.leave()` directly afterwards — doing
  so silently no-ops `Room.broadcast`/`sendTo`, leaving the Raft node spinning
  over a dead transport with no error. Documented on `gameOverRoom` and enforced
  by convention (the lobby hands the adopted room straight into `gameOverRoom`).
- `gameOverRoom` watches the room's terminal signals (`HostLost` / room `closed`)
  and closes the session when the room dies, so a torn room doesn't leave a live
  node.

## §5 — Backpressure decoupling (DES-3)

Once the game rides `room.channel(…)`, the game becomes a subscriber to the
room's `rawIncoming` (`MutableSharedFlow`, capacity 256, **SUSPEND** overflow) —
the same fan-out the per-peer heartbeat detectors subscribe to. A slow game
consumer that falls 256 frames behind would **suspend the room's single main-loop
collector**, stalling admit processing *and heartbeat delivery for every peer*.

The composition must decouple this: channel-frame delivery to `RoomChannelSeam`
subscribers uses per-subscriber staging (a bounded drain, à la `NwSeam`'s
`deliveryStage`, #1415) or `DROP_OLDEST` for channel frames — **with heartbeat
frames exempt** (they must never be dropped or blocked by app backpressure).
Detector fan-out stays on the un-throttled path. This hardens a coupling the
app's hand-wired adapter already has today; institutionalizing the composition in
kuilt is the moment to fix it.

## §6 — Fix ungated `ResumeAck` (DIAG-4)

Independent of the surface work, `SeamRoom.handleResumeAck` is **ungated and
unlatched**: unlike `Farewell`/`Paused`/`Unpaused`, any admitted peer — or a
duplicated/stray `ResumeAck` — triggers `MembershipEvent.Resumed(selfId)` and
marks the sender `Connected`. Fix:

- Gate on host-authoritative sender (only the identified `hostPeerId`'s
  `ResumeAck` is honored), mirroring the `Farewell` gate.
- Latch so a duplicate `ResumeAck` for an already-resumed flight is idempotent.

This removes one of the three "premature/duplicate Resumed" sources (see §7).
Ships with a focused regression test; it is a real defect on its own.

## §7 — Presence semantics: link liveness, not human presence (DES-5)

The "premature Resumed ~3 s after drop" symptom has **three** distinct sources;
be explicit about which this design addresses:

1. **Adapter-synthesized Resumed** (the app inferring resume from Raft churn) —
   **retired** by moving the composition into kuilt (§4). Fixed.
2. **kuilt's ungated `ResumeAck`** — **fixed** in §6.
3. **Semantic:** kuilt `Resumed`/`Recovered` mean *link-level* resume
   (`markRecovered` fires on any frame within the timeout; a host `Resumed` on
   token validation). A brief transport heal (app-background, not airplane) can
   *legitimately* resume in ~3 s while the player is still "away" from the app's
   perspective. `room.events` verbatim will surface that early — **correctly**.

**Decision:** presence at this layer **is link liveness**. Human-presence
("seated / away / reconnecting…") is an app-layer concept the consumer composes
on top (e.g. debounce, or gate on app-foreground). Documented on `presence` so no
consumer mistakes a legitimate link-resume for a bug.

## §8 — Consumer migration (f-c follow-up, separate repo)

The app's `EmbeddedRoomGameHost` + its `PeerFilteredChannel`/`LiveTransport.presence`
mapping is replaced by reading `RoomGameSession.presence` directly. This is a
fireworks-side change consuming the new kuilt API; the kuilt PR ships the API and
a migration note. It is what actually deletes the adapter-synthesized `Resumed`
in production.

## §9 — Testing

- **Surface identity:** a `gameOverRoom`-backed session's `presence` emits the
  same events as the backing `room.events` (drive a `FaultyLoom` mesh drop, assert
  `RoomGameSession.presence` sees `Partitioned`/`WindowOpened`, and that it is the
  room's flow). Reuses the `MeshRoomPartitionTest` harness (PR #1619).
- **Lifecycle:** `RoomGameSession.close()` tears down node **and** room (assert
  `room` reaches terminal, detectors cancelled, seam closed); double-close safe;
  caller-`leave()` documented.
- **Backpressure:** a deliberately-slow game-channel consumer does **not** stall
  heartbeat delivery / admit for other peers (assert a co-peer's `Partitioned`
  still fires while the game channel is backed up).
- **`ResumeAck` gate/latch (§6):** a non-host `ResumeAck` is ignored; a duplicate
  `ResumeAck` yields exactly one `Resumed`.
- **No-Room bootstraps:** `gameNode`/`gameHost` still compile and return a plain
  `GameSession` with **no** `presence` member (compile-time guarantee, not a
  runtime empty).

DES-6 doc note: on a mesh, joiner↔joiner Raft traffic over `room.channel` is
dropped by `RoomChannelSeam` until the host's bootstrap `Welcome` fan-out admits
each member; Raft retries make this eventual — worth a one-line latency note in
the bootstrap KDoc.

## Scope boundary: Track 1 vs Track 2

| | Track 1 (this spec) | Track 2 (#1618 core) |
|---|---|---|
| What | Uniform presence surface + adapter retirement + `ResumeAck` fix | 0 `WindowOpened` on a real Wi-Fi drop |
| Nature | Ergonomics + correctness of the surface | Consumer-observation / app-config / re-election lifecycle |
| Status | Ready to plan | Investigating (f-c audit + #1620 hardware capture) |
| Gate | Independent | Not closed until validated against the hardware reproducer |

Related, out of scope here: `handlePeerLost` has a small stop-detector /
`removeFromRoster` lock gap (DIAG-5) — transient, not #1618; file separately if
it proves to bite.
