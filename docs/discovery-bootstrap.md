# Picking a host before a Seam exists — and why kuilt ships no primitive for it

Consumer-facing version: [Picking a host](https://tractat-us.github.io/kuilt/guide/picking-a-host.html).
This is the rationale behind the decline in [#1555](https://github.com/tractat-us/kuilt/issues/1555).

## The ask

Given N peers discovered over mDNS/Multipeer but not yet connected, deterministically
and symmetrically agree on who hosts — before anyone dials anyone.

## The verdict: declined

`electHost(peers: Set<PeerId>)` is already pure and public
(`ElectionLobby.kt`). Nothing stops a consumer calling it on discovery-derived
ids today. What kuilt will not do is *bless* that as a primitive, because at
kuilt's layer there is no correct version to ship.

## Three independent reasons a discovery snapshot cannot be elected over

1. **Visibility asymmetry.** The global minimum always appears in its own
   snapshot, so it elects itself immediately, while a peer that hasn't discovered
   it yet elects someone else. Seconds of Bonjour latency is normal, not exotic.
2. **Add-only rosters.** `NwLoom.visiblePeers` never removes — the design doc
   already calls this "ghosts forever" (`docs/host-election-design.md`).
   `PeerDiscoverySource.departures()` defaults to `emptyFlow()`, so kuilt cannot
   promise removal *at the interface level* even where a given source implements it.
   Electing a ghost means dialling a dead advertisement and wedging until a
   consumer-owned timeout.
3. **Transport-scoped identity.** `Tag.peerKey` is the `PeerId` value for mDNS and
   the `MCPeerID` handle for Multipeer. #1555 asks for election over a *merged*
   roster, where `min` is therefore ill-defined: the same physical peer carries
   different keys per transport, so **two peers with perfect visibility still
   compute different minima.** No API typed over `Tag` can fix this — only a
   canonical `PeerId` embedded in the advertisement payload, which is consumer-layer.

Divergence does not self-heal by any kuilt machinery. On an asymmetric fabric two
hosts means two disjoint sessions with no merge path, unlike the symmetric mesh
where a two-group merge is just a set change.

**`RaceCollapse` is not the mutual-dial resolver.** It aborts a suspended operation
when an *existing* seam tears or drains; it has nothing to say pre-`Seam`.
Simultaneous dials are resolved by the fabric's own dedup (in kuilt-nw, a nonce
tiebreak inside `NwSeam`). This is worth stating explicitly because it is an easy
and load-bearing thing to get wrong.

## Why "elect late" is the real answer

`docs/host-election-design.md` already establishes the principle: election over an
eventually-consistent input is fine **while nothing is irrevocable**, and the one
irrevocable moment — Start — gets an abortable freeze/ack round.

The pre-`Seam` case is the same principle with the input degraded (a discovery
snapshot, not `Seam.peers`) and the act no longer free (dialling costs a timeout,
and a wrong choice may not be mergeable). Hence: never one-shot. `electLobby` is
the recommended path wherever the fabric can form a symmetric mesh, and kuilt-nw
exists partly so that it can.

## Why not ship a "settle-window" helper

A debounce over `PeerDiscoverySource` yielding a stabilised set and an advisory
host was considered and rejected:

- A settle window converges *one peer's view*, not the roster. Windows end at
  different times with different contents — it buys latency, not agreement.
- Over an add-only source it "stabilises" to a superset including ghosts.
- Any field named `electedHost` gets read as authoritative regardless of KDoc.
  kuilt would then simultaneously document "never elect over a discovery roster"
  (`ElectionLobby.peers`) and ship the primitive that does exactly that.

The genuinely honest extraction is not an election at all — an election-free
roster fold, tracked in [#1570](https://github.com/tractat-us/kuilt/issues/1570).

## The documented pattern for asymmetric fabrics

Optimistic-host-then-defer, with continuous reconciliation: host immediately; fold
discoveries and departures into a roster; recompute `electHost(roster ∪ self)` as
**advisory** on every change; on seeing a lower id, tear down and dial it, capturing
its advertisement at decision time; a join timeout returns to hosting. Embed a
canonical `PeerId` in the advertisement payload when merging transports.

The election function is one line. The other ~95% is the convergence loop and its
app-specific effects — which is precisely what a kuilt primitive would replace the
one line of, while implying the 95% was unnecessary.
