# Host-election lobby (1C) — design

> **Status:** design approved (adversarially reviewed). Implements #1439 (epic #1403, kuilt-nw).
> Consumer: symmetric-mesh + elected-leader Quick Play. Design: **"elect late, adopt once."**

## The problem in one sentence

kuilt-nw connects a symmetric peer-to-peer mesh, but the session/admit layer forces one peer to
be "host" and the rest "joiner" *before* the mesh exists — so two peers who both open the app
each take Host, neither sends `Hello`, and no session forms.

## What a person should experience

You open the app. If a friend is nearby with theirs open, your phones **find each other and agree**
— with no "who's hosting?" tap. If you are the only one around, you wait; when a friend shows up you
converge. When you're ready, someone taps **Start** and you're in a game together. That "agree with
no ceremony" is the whole feature.

## The idea that makes it simple

**The host is always the peer with the lowest id among those currently connected.** Every phone
computes the same function — `min(connected peers)` — over the same membership set, so they all reach
the same answer with **no negotiation, no voting, no protocol**. The lowest id is a stable attractor:
whoever it is never has to step down, and everyone else defers to it. When someone new appears (or two
groups merge), each phone just recomputes `min` and re-converges.

This is *not* consensus in the hard (Raft/FLP) sense. Consensus is only needed when peers must commit
to a value irrevocably while disagreeing is fatal. Here, during the lobby, nothing is irrevocable — a
disagreement costs only a moment of churn, and the answer is a pure function of an eventually-consistent
input (`Seam.peers`). The single irrevocable moment is **Start**, and that one moment gets a small
agreement handshake (below) that is allowed to *abort and retry* — the escape hatch a real
impossibility result would deny us.

## The shape: the lobby is not a Room

The key decision. During the lobby there is **no `Room`** — no admitted-member roster, no admit
handshake, no per-peer heartbeat. A lobby is just:

- the live set of connected transport peers (`Seam.peers` — join / leave / two-group-merge are all
  simply set changes), and
- a reactive `host = min(peers ∪ self)` that every peer computes identically.

A `Room` (with its admit handshake and role-specific machinery) is created **exactly once**, at
**Start**, by adopting the already-woven seam with a now-fixed role. Because the role is decided before
the Room exists, the Room never has to change role, never has to be rebuilt, and never has to share a
live seam with a successor — which is what makes this design ~⅓ the moving parts of a
"rebuild-the-room-on-every-leadership-change" alternative, and free of that alternative's three
blocking hazards (seam-close-kills-the-mesh, admit-rehandshake churn, double `incoming` collection).

## Public surface (kuilt-session)

```kotlin
// The missing primitive: adopt an ALREADY-WOVEN Seam into a Room with an explicit role.
// No re-weave. The Room owns the seam's lifetime from here (leave() closes it — correct, because
// the seam is handed over exactly once). Makes SeamRoom construction publicly reachable.
public suspend fun SeamRoomFactory.adopt(
    seam: Seam,
    role: SessionRole,
    memberName: String? = null,
    roomKey: String? = null,
): Room

// Symmetric entry both peers call identically: weave the mesh, return a lobby over it.
public suspend fun SeamRoomFactory.electLobby(
    pattern: Pattern,
): ElectionLobby

// The lobby: election over live transport peers; NOT a Room.
public interface ElectionLobby {
    public val selfId: PeerId
    /** Live connected peers (∪ self). Merge / join / leave are set changes. */
    public val peers: StateFlow<Set<PeerId>>
    /** The elected host — `min(peers ∪ self)` — reactive. Everyone computes the same value. */
    public val host: StateFlow<PeerId>

    /**
     * HOST-ONLY. Close the lobby and begin the session: run the freeze/ack round, then adopt.
     * Throws [NotElectedHostException] if this peer is not currently `host`.
     */
    public suspend fun start(memberName: String? = null): Room

    /**
     * Await the session. On a MEMBER: suspend until the host's freeze arrives, ack it, adopt, return
     * the Room. (The host obtains its Room from [start] instead.) Returns when the session freezes.
     */
    public suspend fun awaitRoom(memberName: String? = null): Room

    /** Leave the lobby, closing the underlying seam (only if no Room has adopted it). Idempotent. */
    public suspend fun leave()
}
```

Both peers call `electLobby(pattern)`; the app shows a Start button only to the peer whose `host ==
selfId`. Every peer calls `awaitRoom(...)` and gets its `Room` when the session begins; the host
additionally calls `start()` on the Start tap.

## The one hard part: freezing safely (2PC-lite, host-initiated, abort-on-change)

New lobby-only wire messages (`LobbyMessage`, CBOR, own prefix byte, sent over the raw seam
pre-adopt — parallel to `AdmitMessage`, distinct prefix so the two never collide):

| Message | Direction | Meaning |
|---------|-----------|---------|
| `Freeze(hostId, roster, epoch)` | host → all | "Closing the lobby. These are the members. Ack if I'm your host." |
| `FreezeAck(hostId, epoch)` | member → host | "Agreed you're host; I'm ready." |
| `Commit(hostId, epoch)` | host → all | "Everyone acked — adopt now." |
| `Reopen(epoch)` | host → all | "Aborted; back to the lobby." |

**Host (`start`)**
1. Reject early if `host.value != selfId` (`NotElectedHostException`).
2. Snapshot `members = peers.value − self`; `epoch++`; broadcast `Freeze(self, peers.value, epoch)`.
3. Await `FreezeAck(self, epoch)` from **every** member, within `freezeTimeout`. **Abort** on: any
   `peers` change, `host.value != self`, or timeout → broadcast `Reopen(epoch)`, return to step 1
   (retry the tap) — `start` surfaces the abort so the UI can re-enable Start.
4. On unanimous ack: broadcast `Commit(self, epoch)`; `adopt(seam, role = Host, roomKey)`; return Room.

**Member (`awaitRoom`)**
1. Await `Freeze(hostId, roster, epoch)` where `hostId == host.value` **and** `self ∈ roster`
   (host-authoritative gate — drop a `Freeze` from anyone who isn't this peer's elected host, the
   same gate `Farewell` already applies in `SeamRoom`).
2. Reply `FreezeAck(hostId, epoch)`; await `Commit(hostId, epoch)` within a timeout.
3. On `Commit`: `adopt(seam, role = Joiner, roomKey)`; return Room. On `Reopen(epoch)` or timeout:
   discard and go back to step 1 (await a fresh `Freeze`).

**Why the extra `Commit` phase:** a member must not adopt on `Freeze` alone — if the host aborts (didn't
get all acks), a member that already adopted would be a joiner to a host that reopened, healing only
after a 30 s admit timeout. Adopt-after-`Commit` makes success/abort clean. A host crash between ack
and commit leaves members awaiting `Commit`; they time out and re-enter the lobby (re-elect). Bounded
badness, never a silent permanent split — matching the adversarial review's requirement.

**Why this is safe against split-brain:** a committed freeze proves every listed member had the *same*
elected host at ack time (each acks only its own elected host). The residual races (a member acks, then
a lower peer appears just before `Commit`) degrade through the existing partition machinery: a peer
pinned to a host that no longer admits it gets no heartbeat pongs → `PeerLost` → `HostLost` → back to
lobby. A wrong freeze ends in a player-drop-and-retry, never an unhealable split.

## Adopt once, at Commit — the seam handover

At `Commit`, the lobby **stops its own coroutines** (the election observer and the `LobbyMessage`
collector on `seam.incoming`) *before* calling `adopt`, then hands the seam to the new `SeamRoom`,
which starts the single `seam.incoming` collector it is contractually allowed (ADR-034, single-collection).
Serialised so exactly one collector is ever live. The `SeamRoom` now owns the seam for the game's
lifetime — `leave()` → `seam.close()` is correct here (unlike the rebuild design, the seam is handed
over exactly once and never shared with a successor). The lobby's `leave()` closes the seam **only if
no Room has adopted it**.

## Loose ends the review flagged (design-level resolutions)

- **`NwLoom.weave` throws `NwUnreachableException` after 30 s alone** — the "open the lobby and wait for
  your friend" UX must not crash. `electLobby` weaves the mesh; for a lobby that waits, the `NwLoom`
  is configured with a generous/large `weaveTimeout` by the consumer (the knob already exists on
  `NwLoom`), or `electLobby` retries the weave until a peer appears or the caller cancels. A truly-alone
  peer (n=1) simply hasn't woven yet — the UI shows "waiting for players"; when the first peer arrives,
  weave returns and election runs. (kuilt-nw concern; documented on `electLobby`.)
- **`NwLoom.visiblePeers` is accumulate-only (never removes) — ghosts forever.** The lobby's live
  membership is `Seam.peers`, never `visiblePeers`. Documented on `ElectionLobby.peers`.
- **`Room.role` KDoc says "May change in 1C (host-election)."** No longer true under this design — role
  is fixed at adopt. Update the KDoc to point at `ElectionLobby` as where role is resolved.

## Testing

- **`adopt`** — over `InMemoryLoom` (one `Rendezvous.New` + N `Existing`, its documented single-mesh
  shape): one peer `adopt(Host)`, others `adopt(Joiner)`; assert the admit handshake completes and the
  roster forms — the same paths `host`/`join` exercise, now reached via `adopt`.
- **Election function** — pure unit test: `min` over assorted `PeerId` sets, ties, self-only.
- **Lobby election** — construct the lobby impl directly over pre-woven `InMemoryLoom` seams (bypassing
  `electLobby`'s single-`New` weave, which `InMemoryLoom` rejects on the second call). Assert all peers
  compute the same `host`, and `host` updates as peers join / leave.
- **Freeze round** — multi-peer: host `start()`, members `awaitRoom()`; assert all adopt the correct
  role and the Room forms. Abort case: mutate membership mid-freeze → `Reopen` → retry converges.
- **Discipline** — `StandardTestDispatcher`, tight timeouts (`timeout = 5.seconds`), seeded RNG where
  relevant, node coroutines on `backgroundScope`; never `advanceUntilIdle()`. (Repo coroutine-determinism
  rules; multi-peer tests fenced with an OS `timeout` when run from an agent.)

## Explicitly out of scope (follow-ups)

- **Continuous re-election of a *live game*** (promote/demote a running Room) — this design freezes the
  role at Start; post-Start churn is the existing `HostLost`/partition machinery's job.
- **Sticky leadership across a full teardown** — a fresh election per session is fine (per #1439).
- **`adopt(expectedHost)` pre-seeding** to drop foreign `Welcome`s — a cheap hardening that the freeze
  round makes largely unnecessary (freeze already agrees the host); additive later if a post-freeze
  late-joiner surface needs it.
