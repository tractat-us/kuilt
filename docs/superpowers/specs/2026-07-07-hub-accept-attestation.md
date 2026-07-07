# Principal / attestation on the hub-accept path — design

**Status:** design (issue #839, `needs-design`). Implementation tracked by #1261.

## What this is about, in plain language

When a game server hosts a session, players connect to it over the network. Today, on the
hosted-hub path, the server takes each player's word for who they are: a connecting client
announces its own name, and the server believes it. That is fine on a trusted LAN or in a
private test, but on the open internet it means anyone who can reach the server's address can
walk in, sit down at the table, and even claim to be another player.

The server usually *does* know who is connecting — a login token, a session cookie, a client
certificate — but that knowledge is currently thrown away at the front door. This design gives
the hosted path a way to carry that verified identity (a **principal**) from the front door all
the way to the seat, and to slam the door on connections whose claimed name doesn't match who
they proved to be.

The relay topology (`KtorServerLoom` → `SeamRoom`) already has this: a `principalExtractor`
whose result rides the connection and lands on the admitted `Member.principal`. The hosted-hub
topology (`KtorConnectionSource` → `hostedOverlay` → `gameHosted`) has no equivalent — and, as
the scoping on #839 found, no place for one to land, because this path deliberately has no
`SeamRoom`, no admit handshake, and no `Member` objects. This document designs both the
threading *and* the missing landing spot.

## Threat model

### What the hosted path establishes today

Identity on the hub path is exactly one thing: the `MeshHello` preamble — a **self-asserted**
`PeerId` plus a dedup nonce
(`kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt:65`). Nothing on the
path verifies it:

- `KtorConnectionSource` accepts every WebSocket session and emits it as a raw `Connection`
  (`kuilt-websocket/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/websocket/KtorConnectionSource.kt:37-38`).
  The `ApplicationCall` — the only object carrying auth results — is dropped on the floor.
- `hostedOverlay`'s accept-pump feeds every accepted connection straight into `Mesh.addLink`
  (`kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/HostedOverlay.kt:48-54`).
- `gameHost`'s voter-admission loop promotes **any** peer that appears in `seam.peers` to a Raft
  learner and then voter, until `peerCount` seats fill
  (`kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt:769-807`).

### What an unauthenticated or forged joiner can do

1. **Take a seat uninvited.** Any client that can reach the WS endpoint and speak a `MeshHello`
   is admitted learner→voter. It then holds a quorum vote (it can stall or destabilise
   consensus — Raft assumes non-Byzantine members) and receives the full replicated log, i.e.
   the whole game state. With spectators enabled, even a non-voter gets the log.
2. **Steal a seat / read another player's private frames.** The `MeshHello` `PeerId` is
   self-asserted. A connection claiming a victim's `PeerId` enters the mesh's duplicate-link
   dedup (`MeshSeam.kt:259-267`), where the canonical-nonce tiebreak — designed for benign
   simultaneous dials — becomes a coin-flip lottery the attacker can replay until it *displaces
   the victim's live link*. From then on, every `sendTo(victim, …)` — including per-seat masked
   frames, since `GossipSeam.sendTo` routes point-to-point by `PeerId`
   (`kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt:222-225`) — is
   delivered to the attacker. The per-seat disclosure invariant pinned by #838
   (`GameHostedLeakTest`) protects the *flow routing* (unicast never floods); it cannot protect
   the *endpoint identity* that routing keys on.
3. **Exhaust seats.** Filling `peerCount - 1` seats with throwaway connections locks legitimate
   players out permanently — the admission door never closes on a timer.

### What attestation must prevent

- **(P1) Unattested entry, when the host demands attestation:** a connection with no verified
  principal must be droppable *before* it joins the mesh (before it can occupy a seat, appear
  in `seam.peers`, or contend in link dedup).
- **(P2) Principal↔PeerId mismatch (spoofing):** a connection whose verified principal does not
  match the `PeerId` it claims in `MeshHello` must be rejected before the link is published —
  closing threat 2 entirely, because a forged link never reaches the dedup lottery.
- **(P3) Loss of the attested identity:** whatever principal was verified at accept time must be
  observable downstream, keyed by the peer it was verified against, with no out-of-band
  `peer → principal` map that can desync (same rule the relay path's `PrincipalAttested`
  enforces — `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Principal.kt:19-30`).

### Non-goals

- **Byzantine-fault-tolerant consensus.** An *attested, authorized* peer that then misbehaves
  inside Raft is out of scope; attestation controls who gets in, not what they do after.
- **Transport confidentiality/integrity.** TLS (`wss://`) is the transport's job.
- **Authorization semantics.** *Which* principals may join *which* session is the consumer's
  policy; kuilt supplies the enforcement point and hands the policy the verified inputs.

## The seam: where the check belongs

There are exactly two moments on the accept path where new identity information appears, and the
design places one responsibility at each.

### Extraction — `KtorConnectionSource`'s accept handler

`kuilt-websocket/.../KtorConnectionSource.kt:37-38` (the `webSocket(path) { … }` block) is the
**only** point on the hosted path with access to the `ApplicationCall` — the object that carries
Ktor auth results, headers, cookies, and the TLS session. Earlier is impossible (there is no
call yet); later is impossible (the call is gone once the session is wrapped in
`WebSocketConnection` and emitted). So principal *extraction* must happen here, exactly as it
does on the relay path (`KtorServerLoom.kt:53`, applied at `:72`).

What extraction alone cannot do: bind the principal to a `PeerId`. At accept time the
`MeshHello` has not happened — the claimed peer id is unknown.

### Verification and landing — inside `Mesh.addLink`, between handshake and publication

`kuilt-core/.../MeshSeam.kt:244-252` (`MeshSeam.addLink`) is the seam. Specifically, the gap
between `handshakeLink(...)` returning (`:246` — the first moment the self-asserted `PeerId` is
known) and `admitOrReject(link)` (`:249` — the moment the link becomes live and can receive
frames). This is the **first and last** point where both facts coexist on the same object
*before* the peer is reachable:

- **Not earlier** (at accept, or in `hostedOverlay`'s pump before `addLink`): the `PeerId` is
  unknown, so the binding check (P2) is impossible. A pre-`addLink` check could only enforce
  P1, and would leave the spoofing hole open.
- **Not later** (after `admitOrReject`, e.g. `addLink` returning the admitted `PeerId` for the
  caller to post-check): the link is already published — it has contended in dedup (possibly
  displacing the victim's link) and can already receive `sendTo` frames. That is a real
  disclosure window, not a theoretical one.

The check consumes the principal riding the *original* `Connection` (read before
`handshakeLink` wraps it in `singleCollection`) plus the handshake's `remoteId`, and on rejection
closes the connection without ever calling `admitOrReject`. On acceptance the mesh records
`PeerId → Principal` in its link roster **atomically with admission** — this is the missing
landing spot the #839 scoping identified: the hosted path's analogue of
`SeamRoom.admitPeer`'s `principal = (seam as? PrincipalAttested)?.principal`
(`kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:904`).

`hostedOverlay`'s pump already treats a failed `addLink` as a dropped spoke and keeps accepting
(`HostedOverlay.kt:51-52`), so rejection needs no new failure plumbing: a rejected link surfaces
as one debug-logged drop and the hub keeps serving.

## API shape

Minimal surface, mirroring the relay path's precedent. Everything defaults to today's behaviour.

### 1. `Principal` moves to `kuilt-core` (with compatibility aliases)

`Principal` and `PrincipalAttested` currently live in `kuilt-session` — but `kuilt-gossip` and
`kuilt-game` do not (and should not) depend on `kuilt-session`, and `Connection` lives in
`kuilt-core`. Move both to `us.tractat.kuilt.core`, leaving `typealias`es in `kuilt-session`
(which already has an `api` dependency on `kuilt-core`) so existing source keeps compiling.

Side benefit: `KtorServerLoom`'s public `principalExtractor` parameter currently leaks a
`kuilt-session` type through an `implementation` dependency
(`kuilt-websocket/build.gradle.kts:10`); after the move it references an `api`-visible core type.

### 2. A principal can ride a `Connection`

```kotlin
// kuilt-core — mirrors the existing Seam.withPrincipal (Principal.kt:37-38)
public fun Connection.withPrincipal(principal: Principal?): Connection
// null → receiver returned unchanged (unattested connections are never wrapped)

private class PrincipalConnection(inner: Connection, override val principal: Principal?) :
    Connection by inner, PrincipalAttested
```

The principal rides the connection object itself — no out-of-band map (P3).

### 3. `KtorConnectionSource` gains the extractor (the #839 acceptance criterion, verbatim)

```kotlin
public class KtorConnectionSource(
    application: Application,
    path: String,
    private val principalExtractor: (ApplicationCall) -> Principal? = { null },
) : ConnectionSource
// in the handler: connections.send(WebSocketConnection(this).withPrincipal(principalExtractor(call)))
```

Identical shape and default to `KtorServerLoom.kt:53`. Runs in the accept handler, after Ktor
auth plugins have run.

### 4. `LinkAdmission` — the verification policy, enforced inside `addLink`

```kotlin
// kuilt-core
public fun interface LinkAdmission {
    /** Decide whether a handshaked link may join. [principal] is the host-verified identity
     *  riding the connection (null = unattested); [remoteId] is the peer id the joiner
     *  claimed in its MeshHello. */
    public suspend fun admit(principal: Principal?, remoteId: PeerId): Boolean

    public companion object {
        /** Today's behaviour: every link joins. */
        public val AcceptAll: LinkAdmission
        /** Closed mode: only attested links join (P1). */
        public val RequireAttested: LinkAdmission
    }
}
```

`meshSeam(...)` gains `admission: LinkAdmission = LinkAdmission.AcceptAll`, applied in
`addLink` at the seam identified above (and, for symmetry, to construction-time connections
after their parallel handshakes). Rejection closes the connection and throws
`LinkRejectedException` out of `addLink` — which `hostedOverlay`'s existing
`runCatchingCancellable` drop-and-continue already absorbs.

The P2 binding check is one line of consumer policy, because the policy sees both values:

```kotlin
LinkAdmission { principal, remoteId -> principal?.value == remoteId.value }
// or: token subject ↔ peer id via whatever mapping the consumer's auth scheme defines
```

kuilt deliberately does **not** hardcode `principal == peerId` — the relationship between an
auth subject and a mesh peer id is consumer-defined (one user may run several devices).

### 5. The landing spot — an observable per-peer roster

```kotlin
// kuilt-core
public interface PrincipalRoster {
    /** Host-verified principals of currently-linked peers, keyed by the PeerId each was
     *  verified against at admission. Peers with no attestation are absent. */
    public val attestedPrincipals: StateFlow<Map<PeerId, Principal>>
}
```

- `Mesh` implements it: the map is maintained under the existing link lock, updated in
  `admitOrReject`/`removePeer`/`tearDown` so it can never desync from `links` (P3).
- `GossipSeam` implements it by delegating to its base when the base is a `PrincipalRoster`
  (empty map otherwise), so `hostedOverlay`'s returned seam exposes it.
- `GameSession` adds a convenience `principalFor(peer: PeerId): Principal?` /
  `attestedPrincipals` view over `(seam as? PrincipalRoster)` — the hosted-path analogue of
  `Member.principal`, and the sugar-parity surface `gameHosted` consumers use.

This resolves the acceptance-criterion mismatch the #839 scoping flagged: the hosted path has no
`Member`, so "observable on the admitted member" becomes "observable on the session's principal
roster, keyed by admitted peer" — same guarantee, path-appropriate carrier.

### 6. Threading through `hostedOverlay` and `gameHosted`

```kotlin
public suspend fun CoroutineScope.hostedOverlay(
    selfId: PeerId,
    source: ConnectionSource,
    dispatcher: CoroutineContext,
    random: Random = Random.Default,
    clock: () -> Instant = { Clock.System.now() },
    admission: LinkAdmission = LinkAdmission.AcceptAll,   // → meshSeam
): Seam

public suspend fun CoroutineScope.gameHosted(
    …existing params…,
    admission: LinkAdmission = LinkAdmission.AcceptAll,   // → hostedOverlay
): GameSession
```

The `principalExtractor` is *not* a `gameHosted` parameter — it is a WebSocket-fabric concern
configured on the `KtorConnectionSource` the caller constructs (an `InMemoryConnectionSource`
in tests attaches principals directly via `withPrincipal`). `gameHosted` only threads the
transport-agnostic policy.

End-to-end flow:

```
principalExtractor(call)                KtorConnectionSource accept handler
      │  Connection.withPrincipal
      ▼
Connection (PrincipalAttested)          rides the object — no side map
      │  hostedOverlay pump → Mesh.addLink
      ▼
handshakeLink → remoteId                MeshSeam.addLink:246
      │  admission.admit(principal, remoteId)   ← THE CHECK (P1 + P2)
      ▼ reject: close conn, LinkRejectedException (pump drops & continues)
admitOrReject + roster update           atomic with link publication (P3)
      │
      ▼
PrincipalRoster (Mesh → GossipSeam → GameSession)
```

### Open by default — and why

With no extractor and `AcceptAll`, behaviour is byte-identical to today. Open-by-default is
deliberate:

- It matches the relay path's precedent (`KtorServerLoom` defaults to no attestation) — the two
  topologies should not differ in security posture by default.
- `Principal` is defined as opt-in, caller-verified attestation (`Principal.kt:6-14`); fabrics
  with no auth concept (in-memory tests, LAN, Multipeer) must keep working unchanged.
- A closed default would silently break every existing consumer and test harness.

The important asymmetry: **once a consumer supplies a `LinkAdmission`, that policy is
authoritative for every link** — unattested connections are not waved through; they reach the
policy with `principal = null` and the policy decides. "Open" is the *default*, never an
*override*. Consumers who want closed mode set `LinkAdmission.RequireAttested` (or their own
binding check) and a real extractor; the design doc for the consumer's deployment should treat
extractor-without-admission as a smell (attestation collected but not enforced) — worth a KDoc
warning on `principalExtractor`.

## Compatibility

- **Admit/identify handshake (`SeamRoom`, relay path): untouched.** `Seam.withPrincipal`,
  `SeamRoom.admitPeer`, and `Member.principal` keep working as-is; the only change they see is
  `Principal`/`PrincipalAttested` becoming typealiases to core types (source-compatible;
  pre-1.0, so the ABI move is acceptable and lands in one slice).
- **`MeshHello` wire format: unchanged.** No new preamble fields; attestation is carried by the
  transport accept, not the wire. WASM/iOS/Android clients (`KtorMeshClientLoom`) need no
  changes — the server-side call object is where auth lives.
- **`explicitApi()` surface additions:** `Connection.withPrincipal`, `LinkAdmission`,
  `LinkRejectedException`, `PrincipalRoster`, `Mesh : PrincipalRoster`, new optional parameters
  on `meshSeam` / `hostedOverlay` / `gameHosted` / `KtorConnectionSource`, and the
  `GameSession` accessor. All defaulted, all additive.
- **`ConnectionSource` contract: unchanged** — attestation composes via the `Connection`
  wrapper, so `InMemoryConnectionSource` and future fabric sources participate for free.

## Implementation slices (ordered, each independently green)

Tracked by #1261.

1. **Core types move** — relocate `Principal` + `PrincipalAttested` to `kuilt-core`, typealiases
   in `kuilt-session`; add `Connection.withPrincipal`. Pure refactor + one addition; existing
   tests prove compatibility.
2. **Mesh admission + roster** — `LinkAdmission` (+ `AcceptAll`/`RequireAttested`),
   `meshSeam(admission = …)`, the check in `addLink` between handshake and `admitOrReject`,
   `LinkRejectedException`, and `Mesh : PrincipalRoster` maintained under the link lock. Tests:
   unattested-rejected in closed mode, binding-mismatch rejected **before** the dedup lottery
   (spoof cannot displace a live link), roster observability and cleanup on peer removal.
3. **WebSocket front door** — `KtorConnectionSource(principalExtractor = …)` wrapping each
   accepted session via `withPrincipal`. WS round-trip test: auth header → extractor →
   principal observed on the hub's roster.
4. **Overlay + game threading** — `hostedOverlay(admission)`, `GossipSeam` roster delegation,
   `gameHosted(admission)`, `GameSession` principal accessor. End-to-end virtual-time test on
   `InMemoryConnectionSource` (the #839 acceptance test, restated for the roster landing spot):
   a principal attached at accept is observable for the admitted peer; a spoofed `MeshHello`
   under a binding policy never joins.
5. **Docs** — `docs/architecture.md` hosted-path attestation paragraph; KDoc on
   `principalExtractor` (including the extractor-without-admission warning); update
   `docs/gamehosted-bootstrap-design.md`'s Deferred pointer.

Slices 1–2 are the substance; 3–5 are thin. 2 must precede 3–4; 1 must precede all.
