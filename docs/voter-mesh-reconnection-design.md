# Voter-mesh reconnection

When a handful of servers run a cluster together, they stay in touch over live
network connections. If one of those connections briefly drops — a WiFi hiccup,
a laptop going to sleep, a switch rebooting — you'd expect it to come back on its
own. Today it doesn't: once kuilt's server cluster has formed, a dropped link
between two servers stays broken until someone restarts the process. This design
lets the cluster heal itself — each server quietly re-dials whatever it lost, and
keeps trying, so a blip repairs itself with no restart and no lost work.

> Status: **design accepted** (issue #1450), hardened by an adversarial review.
> Tracking the implementation plan at [`plans/2026-07-15-voter-mesh-reconnection.md`](plans/2026-07-15-voter-mesh-reconnection.md).

## The one idea

Keep the connection fabric **forgiving** — never treat "all my links dropped" as
death — and add a small supervisor that notices a missing peer and re-dials it,
forever, with a patience that grows the longer that peer stays away.

## Why the fabric stays forgiving

kuilt has two flavours of multi-peer fabric. One (`peerMesh`) ends the whole
session the moment its last peer leaves — the right behaviour for a two-party
conversation that is *over* when the other side hangs up. The other (`hubMesh`)
never ends itself; it simply sits with whatever peers it currently has and waits.

A cluster of voting servers wants the forgiving one. The consensus layer riding
on top (Raft) is *built* to tolerate a peer being temporarily unreachable — it
treats an absent voter as an ordinary network partition and retries on the next
round. If the fabric instead declared the session dead on a single dropped link,
a two-voter cluster would die on one transient blip, and it would die *loudly* —
as an unrecoverable crash rather than a quiet gap. So each voter's mesh stays on
`hubMesh`: an isolated voter just idles (it can't win an election with nobody to
ask, so it quietly waits) until its links come back. The only thing missing was
the coming-back. (This is why issue #1438's proposal to move the voter mesh to
`peerMesh` was declined — see that issue.)

## How reconnection works

Three small pieces, each usable on its own.

**One redial loop per peer.** For every higher-ranked voter a server is
responsible for dialing, one coroutine watches a single fact — "is this peer in
my roster?" — and does nothing while the answer is yes. When a peer drops, the
loop re-dials under a growing backoff; the instant the peer returns, the loop is
cancelled automatically (`collectLatest` over the roster flow). There is no
shared state between loops, so each is correct on its own and testable on its own.

**Who dials.** Reconnection reuses the rule the cluster already forms with — the
lower-id server dials the higher, and only the lower side re-dials after a drop.
Exactly one dialer per pair means a reconnect never produces a duplicate link for
the fabric to arbitrate.

**Growing patience.** Retries use full-jitter exponential backoff: the delay
after each failed attempt grows (so a genuinely-dead server is retried sparsely,
not hammered), and it's randomised (so a shared-switch blip that flaps many links
at once doesn't produce a synchronized reconnect storm). Backoff is a small,
stateless value with an injected random source — deterministic in tests.

**Accepting the other side.** The re-dial has to land somewhere, so the accept
side becomes a persistent, concurrent, handshake-timed pump (one that can't be
wedged forever by a single connection that opens but never speaks). This is a
reusable primitive; it also replaces the same latent weakness in two existing
hosts (`hostedOverlay`, `MuxServerLoom`).

**Forming is unchanged.** Cold-start still happens synchronously — a cluster
finishes wiring itself up before its consensus nodes start — so nothing about
today's startup guarantees changes. The supervisor only handles the steady-state
drops it's designed for.

## The three flows that have to be right

**A clean heal.** An edge drops, both ends notice, the lower side re-dials, the
higher side's pump admits it, the peer reappears in both rosters, and Raft
resumes on its next round. No node restart, no lost term or log.

**A redial racing a stale send.** A leader's heartbeat can be mid-flight to the
old connection just as the fresh link is installed. If the failed old send were
allowed to evict "the peer" indiscriminately, it would tear down the *new* link
and permanently recreate the very edge just healed. This is closed by
[#1452](https://github.com/tractat-us/kuilt/issues/1452) (merged): the
send-failure path now names the connection it failed on, so it can only remove
that one.

**A silently dead connection.** A half-open TCP link is the hard case — the
read-only side never notices, and holds a stale peer for minutes while every
redial into it loses a coin-flip against the corpse. The fix is WebSocket
ping/pong on both ends: a missed pong tears the session down promptly and
symmetrically, so the redial then lands cleanly. (kuilt configures no ping today;
adding it is a prerequisite.)

## Landing order

Foundation first — each step is independently landable and testable.

1. **[#1452](https://github.com/tractat-us/kuilt/issues/1452)** — conn-guard the
   send-failure eviction. *(merged)*
2. **WebSocket ping/pong** — symmetric, bounded half-open detection.
3. **`acceptPump`** — the shared concurrent, handshake-timed accept primitive;
   migrate `hostedOverlay` and `MuxServerLoom` onto it.
4. **`ExponentialBackoff`** + the **`VoterReconnectionSupervisor`** — the per-peer
   redial loops.
5. **Wire it into `voterMeshOverWebSockets`** — persistent pump from t0, synchronous
   formation preserved, supervisors and pumps cancelled together with the cluster.

## Testing

The supervisor's hard parts — backoff schedule, detect-departure→redial,
lose-to-corpse retry, forever-retry, no reconnect storm — all run under virtual
time with a fake dialer and an in-memory mesh, so they're deterministic and fast.
A real-WebSocket integration test then covers one end-to-end heal, a
command committing across the heal, and the two-voter case (a single blip that
`peerMesh` would have killed), plus that closing the cluster stops all reconnection.
