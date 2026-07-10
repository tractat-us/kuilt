# The mux-hub principal gap — design (#1352)

**Status:** design. Follows the hub-accept attestation work (2026-07-07 spec, impl #1261).

## What this is about, in plain language

When a server hosts many game rooms over one shared socket, it usually knows *who* is
connecting — a login token, a client certificate, a signed header. kuilt already has a way to
carry that verified identity (a **principal**) from the front door to the seat, and consumers
use it to slam the door on a client that logs in as one person but sits down claiming to be
another.

That machinery works on two of kuilt's three hub shapes. On the third — the one that puts many
rooms on one connection (`MuxServerLoom` → `RoomHubSeam` → `SeamRoom`) — the verified principal
is silently dropped: every admitted `Member.principal` is `null`, and the leader-side check that
would reject a spoofed device id has nothing to check against. This document explains why the
hole exists, corrects the modeling error that hid it, and recommends the fix.

## The gap, precisely

Three hub topologies carry an attested principal, and they do it three different ways:

| # | Topology | Seam type the consumer reads | Carrier interface | Status |
|---|----------|------------------------------|-------------------|--------|
| 1 | `KtorServerLoom` 2-peer relay → `SeamRoom` | the relay link seam | `PrincipalAttested` (single) | **works** |
| 2 | `hostedOverlay`/`starOverlay(mesh)` → `GameSession` | one shared `Mesh` | `PrincipalRoster` (map) | **works** |
| 3 | `MuxServerLoom` → `RoomHubSeam` → `SeamRoom` / `GameRoom` | `RoomHubSeam` | **neither** | **gap (#1352)** |

`RoomHubSeam` (`kuilt-core/.../RoomHubSeam.kt:66`) is declared `: Seam` only. It is neither
`PrincipalAttested` nor `PrincipalRoster`. So both consumer read-sites come up empty:

- `SeamRoom.admitPeer` (`kuilt-session/.../SeamRoom.kt:812`) sets
  `principal = (seam as? PrincipalAttested)?.principal`. Over a `RoomHubSeam` the cast fails →
  `null` → every `Member.principal` on the mux hub is `null`.
- `GameSession.attestedPrincipals` (`kuilt-game/.../GameSession.kt:66`) reads
  `(seam as? PrincipalRoster)?.attestedPrincipals ?: EMPTY_ROSTER`. `GameRoom` wraps the room in
  `starOverlay(RoomHubSeam)` (`GameRoom.kt:92`), and `GossipSeam` delegates the roster to
  `(base as? PrincipalRoster)` (`GossipSeam.kt:187`). Base is the `RoomHubSeam` → not a roster →
  a constant empty map. So the game-per-room path is holed too, for the same root cause.

### The modeling error that hid it

The #1261 spec states the relay path "already has this" and leaves `SeamRoom` untouched
(2026-07-07 spec, lines ~19, ~291). That is the blind spot. **`SeamRoom` is loom-agnostic** — it
rides whatever `Seam` its `Loom.host` returns. "The relay path works" is a statement about the
*relay seam* (which happens to be `PrincipalAttested`), not about `SeamRoom`. Over `MuxServerLoom`
the very same `SeamRoom` code rides a `RoomHubSeam`, which carries no attestation — so the
principal read that "works on the relay path" silently yields `null`. The spec reasoned about
looms and seams but drew its conclusion about a *room*, and the room is downstream of a seam the
spec never enumerated.

### Why the principal is physically stranded

The two working topologies share a structural property the mux hub breaks: **one seam is both the
fabric and the roster.** `hostedOverlay` builds a *single* `Mesh` and feeds every spoke into it
with `hubMesh.addLink(conn)`; that one mesh reads each connection's `PrincipalAttested` at
handshake and publishes all of them in its `attestedPrincipals` (`MeshSeam.kt:230, :448`). The
consumer reads that mesh directly.

`MuxServerLoom` splits those two roles apart:

- **Fabric** is *N per-connection meshes* — `admit()` builds `meshSeam(connections = listOf(conn))`
  per accepted connection (`MuxServerLoom.kt:145`), stored as `ConnRecord.rawSeam`. Each such mesh
  *does* implement `PrincipalRoster` and *does* hold `{connPeerId → principal}` — but for one peer,
  and nobody reads it.
- **Membership** is *M `RoomHubSeam`s*, one per room. The read loop forwards only
  `(connPeerId, strippedBytes, sender)` into `room.deliver` (`MuxServerLoom.kt:169`). The
  principal never crosses from the per-connection mesh into the room.

So the identity is verified, lands on the per-connection mesh's roster, and then dies there. The
room — the thing the consumer reads — never learns it.

## Threat model (unchanged from #1261, restated for this path)

kuilt supplies the *enforcement point*; the consumer supplies the policy. The concrete consumer
need driving #1352: **leader-side per-peer verified-id enforcement** — a joiner self-asserts a
`deviceId` in its `Hello` (`MemberIdentity.deviceId`), and the leader must reject it if it does
not match the host-verified `Principal` for that same peer. `Member` already carries both fields
side by side (`Member.identity.deviceId` self-asserted, `Member.principal` verified —
`Member.kt:17-21`); the check is one comparison **once `principal` is non-null**. Today it is
always null on the mux hub, so the check is unarmed. Non-goals are identical to #1261: not
Byzantine consensus, not transport confidentiality, not the authorization policy itself.

## Options

### A. Bridge in `SeamRoom` only — do not touch `RoomHubSeam`

Have `SeamRoom.admitPeer` reach past the room seam to the underlying per-connection mesh's roster.
Rejected: `SeamRoom` has no handle on the per-connection meshes — `MuxServerLoom` owns them
privately on `ConnRecord`. There is no object to reach through. This option cannot be built
without exposing loom internals to the session layer, inverting the dependency.

### B. `RoomHubSeam` becomes a `PrincipalRoster`; `MuxServerLoom` feeds it; consumers read the roster

`RoomHubSeam` implements `PrincipalRoster`. `MuxServerLoom` reads the principal off each connection
(the marker rides the original `Connection` before `meshSeam` wraps it — the same read
`MeshSeam.handshakeLink` does at `:230`) and threads it into the room's registration. The room
maintains a `{PeerId → Principal}` map in the *same* lock critical sections that already maintain
`registered` and `_peers`. Consumers read it uniformly:

- `SeamRoom` reads it **roster-first, `PrincipalAttested`-fallback** (below), populating
  `Member.principal`.
- `GameRoom`'s `starOverlay(RoomHubSeam)` now finds a `PrincipalRoster` base → `GossipSeam`'s
  existing delegation lights up → `GameSession.attestedPrincipals` works with **zero** game-layer
  change.

This is the recommended option. It closes both holes at the one structural cause and reuses the
delegation the #1261 work already built.

### C. Unify the whole model onto `PrincipalRoster` and delete `PrincipalAttested`

Make every principal-bearing seam a `PrincipalRoster`, including the 2-peer relay seam (as a
1-entry roster), and drop the single-valued `PrincipalAttested` interface. Rejected as the
*immediate* step: `PrincipalAttested` is the natural **connection-level** marker — a `Connection`
carries exactly one principal, and `Connection.withPrincipal` (`Principal.kt:43`) is the primitive
every fabric uses to attach it. Deleting it churns the relay seam and every `withPrincipal` site
for no correctness gain over B. It is a plausible *later* simplification (see "the seam we leave
open"), not this change.

## Recommendation

**Adopt Option B.** Concretely:

1. **`RoomHubSeam : Seam, PrincipalRoster`.** Add a third map, `attested: MutableMap<PeerId,
   Principal>`, guarded by the *existing* `lock`, mutated in the *same* critical sections that
   already touch `registered`/`_peers`:
   - **register** (first frame, post-authorize): if a principal was supplied for this peer, put it;
     publish `attestedPrincipals` alongside `_peers.update`.
   - **deregister**: remove the peer's entry **iff** the departing `sender` is still the registered
     one (identity guard — same predicate that already gates `registered.remove`), so a stale
     reconnect teardown cannot evict a live re-registration.
   - **close/tearDown**: clear it with `registered` and `_peers`.
   `deliver` gains the principal as a parameter (nullable): `deliver(connPeerId, frame, sender,
   principal)`.

2. **`MuxServerLoom` feeds the principal.** Capture it once at `admit()` — read
   `(conn as? PrincipalAttested)?.principal` **before** the `conn` is handed to `meshSeam`
   (the `singleCollection` wrapper hides the marker, exactly the ordering `MeshSeam.handshakeLink`
   relies on) — and store it on `ConnRecord`. `readLoop` passes `record.principal` into
   `room.deliver`. The principal is immutable for the life of a connection, so a single captured
   snapshot is correct and, crucially, requires **no second flow collection** — the
   single-collection `incoming` contract (ADR-034) is untouched.

3. **`SeamRoom` bridges roster-first.** Replace the single read with:
   ```kotlin
   principal = (seam as? PrincipalRoster)?.attestedPrincipals?.value?.get(joinerPeerId)
       ?: (seam as? PrincipalAttested)?.principal
   ```
   On the mux hub the roster branch fires (per-peer lookup — the correct shape, since `SeamRoom`
   admits *one* named `joinerPeerId`). On the 2-peer relay the roster branch is absent and the
   `PrincipalAttested` fallback fires — and because a 2-peer seam has exactly one remote, the single
   principal *is* that joiner's principal, so both branches agree by construction. No relay-path
   behavior changes.

4. **`Room` gains a roster accessor.** Add `Room.attestedPrincipals: StateFlow<Map<PeerId,
   Principal>>` (delegating to `(seam as? PrincipalRoster)`), mirroring `GameSession`. `Member.principal`
   stays the *primary* surface (it co-locates the self-asserted `deviceId` and the verified principal
   on one object — the mismatch check is a single field comparison while walking `roster`). The
   Room-level roster is the cheap snapshot analogue and the uniform cross-facade surface. Both, not
   one: the per-member field is where the enforcement check naturally lives; the roster is the
   generalization that the next facade reads without re-deriving it from members.

### The modeling stance this locks in

`PrincipalRoster` (map) is the **canonical seam-level carrier**; `PrincipalAttested` (single) is
the **connection-level marker and the degenerate 2-peer view**. Every consumer of a
*multi-peer* seam reads the roster; `PrincipalAttested` survives only as the connection primitive
and the relay-path fallback. This is the sentence #1261 was missing.

## Making the next loom safe (the structural fix)

The hole was invisible because the failure is a **silent fallback**: `RoomHubSeam` satisfied
`: Seam` without carrying attestation, and every consumer does `as? PrincipalRoster ?: EMPTY`.
A new hub seam that forgets the principal produces an empty roster, not a compile error or a test
failure. Per the repo's "survey the category, then make it impossible" rule, add a **conformance
obligation**, not just a fix:

- Extend the seam TCK (a new `PrincipalAttestationConformanceSuite`, or a case in
  `SeamConformanceSuite`): *given a seam constructed/admitting a `Connection.withPrincipal(p)`,
  `(seam as? PrincipalRoster)?.attestedPrincipals` must report `p` for that peer.* `RoomHubSeam`,
  `Mesh`, and any future hub seam subclass it — the next loom that drops the principal **fails the
  suite** instead of shipping a silent empty roster. This matches the existing "subclass
  `SeamConformanceSuite` to add a fabric" discipline; the conformance module has **no** principal
  test today (verified — `grep Principal kuilt-conformance` is empty), which is precisely why the
  gap shipped.

That conformance test is the part that generalizes; the three-file code change is the instance.

## Thread-safety

Every roster mutation lives inside `RoomHubSeam`'s existing `reentrantLock` critical sections,
paired with the `registered`/`_peers` mutations it already performs there — no new lock, no
suspend call under the lock (authorizer and spool delivery stay outside, unchanged). The
identity-guarded deregister predicate is reused verbatim for roster cleanup, so reconnect
(same `PeerId`, fresh sender, possibly a re-authenticated principal) replaces the entry and a
stale teardown cannot evict the live one — the exact invariant `registered` already upholds. This
is real-primitive mutual exclusion, not dispatcher confinement: correct under a multi-threaded
dispatcher, satisfying the repo's no-confinement-as-mutex policy. `Mesh` already maintains its
roster under its link lock the same way (`MeshSeam.kt:350` `publishRosters()`), so `RoomHubSeam`
is copying a proven pattern, not inventing one.

## Compatibility

- **Additive, all defaulted.** New `explicitApi()` surface: `RoomHubSeam : PrincipalRoster`, a
  nullable `principal` parameter on the internal `RoomHubSeam.deliver`, `Room.attestedPrincipals`.
  No signature is removed; `Member.principal` already exists.
- **Relay path untouched.** The `SeamRoom` bridge keeps `PrincipalAttested` as fallback; 2-peer
  relay behavior is byte-identical.
- **Game path lights up for free.** No `kuilt-game`/`kuilt-gossip` change — the `GossipSeam`
  roster delegation already present begins returning a populated map once its base
  (`RoomHubSeam`) is a `PrincipalRoster`.
- **Wire format unchanged.** Attestation rides the transport accept, never the `MeshHello`/`Named`
  frame; no client (WASM/iOS/Android) changes.
- **Open by default.** With no `principalExtractor`, principals are `null` and every roster is
  empty — identical to today.

## Non-goals

- Enforcing the deviceId↔principal binding *inside* kuilt. kuilt surfaces both values on `Member`;
  *whether* a mismatch is fatal is consumer policy (the same stance as `LinkAdmission`).
- Byzantine tolerance, transport TLS, and the authorization policy — all as in #1261.
- Deleting `PrincipalAttested` / collapsing the relay seam to a 1-entry roster (Option C) — a
  possible later simplification, out of scope here.

## The seam we leave open (honest limitation)

After this change two interfaces still coexist: `PrincipalAttested` (connection + 2-peer) and
`PrincipalRoster` (multi-peer seam), bridged by two lines in `SeamRoom`. That is deliberate, not
unfinished: `PrincipalAttested` is load-bearing at the *connection* layer where "one connection,
one principal" is exactly true, and forcing the relay seam to also expose a 1-entry roster buys
only the deletion of a two-line, provably-correct fallback at the cost of churning the relay seam
type and every `withPrincipal` call site. If a future change makes the relay seam a
`PrincipalRoster` for independent reasons, the `SeamRoom` fallback can then be deleted and the
model fully unifies — a one-line follow-up, filed but not forced.

## Is a design pass even warranted?

Honest verdict: **the code change is small (three files), but the pass earned its keep** — because
the fix is downstream of a *modeling* decision the #1261 spec got wrong, and shipping the small
patch without settling "roster is canonical, `PrincipalAttested` is the degenerate view, consumers
bridge roster-first" would have re-encoded the same ambiguity that let the hole through the first
time. The design deliverable is that sentence plus the conformance obligation; the LOC is the easy
part.

## Slices (ordered, each independently green)

1. **`RoomHubSeam : PrincipalRoster` + `MuxServerLoom` feed.** Roster map under the existing lock;
   principal captured at `admit()`, threaded through `deliver`. Test: a principal attached via
   `withPrincipal` on an in-memory connection is observable on the room's `attestedPrincipals` for
   the admitted peer, updates on reconnect, clears on drop/close.
2. **`SeamRoom` bridge + `Member.principal`.** Roster-first, `PrincipalAttested`-fallback. Test:
   `Member.principal` populated over `MuxServerLoom`; relay path unchanged.
3. **`Room.attestedPrincipals` accessor.** Thin delegation. Test: matches the members' principals.
4. **Conformance obligation.** `PrincipalAttestationConformanceSuite`; `RoomHubSeam` + `Mesh`
   subclass it. This is the slice that stops the next loom.
5. **Docs.** `docs/architecture.md` mux-hub attestation paragraph; note the two-interface model and
   the roster-first read rule.

Slice 1 is the substance; 2–4 are thin and depend on it; 4 is the one that generalizes.
