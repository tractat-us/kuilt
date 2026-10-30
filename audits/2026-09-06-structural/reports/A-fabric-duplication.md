# A — `Seam`/`Loom` duplication matrix

**Radius:** production source only (`src/*Main/`, `src/main/`) in this worktree at
`docs/2600-depart-carries-finals`. Population taken from `docs/seam-harness-coverage.md:57-95`
(the registry `verifySeamHarnessCoverage` already enforces as complete), cross-checked against
`grep "override val incoming"` — 34 rows, no discrepancy. `Loom` impls counted separately (26
`override suspend fun weave`), not tabulated: they are thin and the duplication is not there.

## ⚠ The brief's premise is half wrong — the skeleton EXISTS, by composition

`docs/extending-fabrics.md:17-145` documents the shared skeleton: a fabric implements only
`Connection` (`kuilt-core/.../fabric/Connection.kt:24`, four members) and calls
`identified()` / `handshaking()` / `peerMesh()`, which return a `LinkSeam` or `MeshSeam` that
already owns gate, spool, pumps, roster, budget pre-check and self-send guard.
`:kuilt-websocket`, `:kuilt-tcp`, `:kuilt-mdns` take that path — `SeamStateGate.kt:60` says so
explicitly: *"`:kuilt-websocket` is not among them — it composes `LinkSeam`/`MeshSeam`, which is
why it never had to solve this at all, and is the shape to prefer."*

So the finding is not "no skeleton was built". It is **five radio fabrics bypassed it**
(`NwSeam`, `NearbySeam`, `MCSessionLink`, `BridgePeerLink`, `WebRTCPeerLink`) and re-derived
`MeshSeam` by hand, and **nothing lexically stops the sixth**.

## Matrix — production `Seam` implementations

Legend: **D** delegated to a kuilt-core primitive · **H** hand-rolled · **–** absent/N/A.
`code` = non-comment, non-blank lines in the class body (script + ranges retained in scratchpad).

| # | Seam / file | code | state pub | close-once | `incoming` | pump launch | roster | budget | handshake | thread-safety |
|---|---|---:|---|---|---|---|---|---|---|---|
| **Fabric seams — own a transport** |
| 1 | `LinkSeam` `kuilt-core/…/fabric/LinkSeam.kt:72` | 75 | **D** gate:84 | D (gate.tear) | **D** Spool:98 | H `scope.launch`×2 :113 | H `update{}`:195 | **D** enforced `conn.oversizeOrNull` | D `Hello` | own scope, no lock |
| 2 | `MeshSeam` `kuilt-core/…/fabric/MeshSeam.kt:699` | 257 | **D** gate:798 | D | **D** Spool:832 | H ×8 | H `.value=`:1332 | **D** enforced, reserves TYPE_BYTES | H `MeshHello`+nonce | `reentrantLock`×2 |
| 3 | `NwSeam` `kuilt-nw/…/NwSeam.kt:251` | **873** | **H** bare flow +`ALLOW`:475 | **H** `atomic(false)`:545 | **D** Spool:494 | **H** ×9 `launch(UNDISPATCHED)`:2346 | **H** ×14 under own lock | **H** own `oversizeOrNull`:2097 | **H** `NwHello`+nonce | `reentrantLock`×2 |
| 4 | `NearbySeam` `kuilt-nearby/…/NearbySeam.kt:84` | 156 | **H** bare flow +`ALLOW`:127 | **H** `atomic(false)`:150 | **D** Spool:146 | **H** ×4 in ctor:159 | **H** `peersLock`+`collapsed`:101 | – (publishes none) | – (loom-side) | `reentrantLock` + `Mutex` |
| 5 | `MCSessionLink` `kuilt-multipeer/appleMain/…:78` | 197 | **D** gate:153 | D (gate.tear) | **D** Spool:162 | **H** ×1 | **H** `peersLock`+`collapsed`:103 | – | – | atomicfu `reentrantLock` |
| 6 | `BridgePeerLink` `kuilt-multipeer/jvmMain/…:61` | 130 | **D** gate:137 | **H** `java…AtomicBoolean`:157 | **D** Spool:146 | **H** ×1 | **H** `peersLock`+`collapsed`:123 | – | – | **`java.util.concurrent`** lock/atomic |
| 7 | `WebRTCPeerLink` `kuilt-webrtc/…:52` | 90 | **H** bare flow +`ALLOW`:67 | **H** `var closed`:236 | **H** `channelFlow`:87 | **H** ×3 | **H** unguarded `.value=`:229 | – | – | **single-thread confinement** (wasmJs) |
| 8 | `InMemorySeam` `kuilt-core/InMemoryLoom.kt:193` | 40 | **H** bare +`ALLOW`:220 | **H** `MSF<Bool>.CAS`:247 | **D** Spool | – | **D** `latchingTo`:217 | – | – | `Mutex` on the loom |
| **Composition / decorator seams** |
| 9 | `CompositeSeam` `…/composite/CompositeSeam.kt:201` | 346 | **D** gate:227 | D | **D** Spool | **D** `pumpIn`×7 + 1 bare `launchIn` | H | H derived, **not enforced** | – | `reentrantLock` (+legacy confinement) |
| 10 | `TieredSeam` `kuilt-core/TieredSeam.kt:89` | 85 | **D** gate:135 | D | **D** Spool | **H** 4 bare `.launchIn` | H `.value=`:221 | H derived, not enforced | – | `reentrantLock` |
| 11 | `RoomHubSeam` `kuilt-core/RoomHubSeam.kt:90` | 85 | **D** gate:139 | D | **D** Spool | – | H `.value=`:323 | H, not enforced | – | `reentrantLock` |
| 12 | `ChannelView` `kuilt-core/MuxBase.kt:121` | 76 | **D** gate:139 | D | **D** Spool | **D** `pumpIn`×2 | H | H, not enforced | – | `reentrantLock` |
| 13 | `ResumableChannel` `kuilt-core/MuxClientLoom.kt:147` | 33 | – (delegates) | H CAS | **H** `flow{emitAll}`:202 | – | – | H delegate | – | – |
| 14 | `GossipSeam` `kuilt-gossip/GossipSeam.kt:105` | 152 | – (delegates) | H | **D** Spool | H ×1 | H | H delegate, not enforced | – | `reentrantLock` |
| 15 | `ManagedSeam` `kuilt-cluster/ManagedSeam.kt:71` | 67 | **H** constant flow +`ALLOW`:86 | H | H `_incoming` | H ×1 | **H** `.value=`:225 | – | – | `reentrantLock` |
| 16 | `TokenGatedSeam` `kuilt-otel-tap/…:58` | 132 | – (delegates) | – | **H** `asSharedFlow`:89 | H ×3 | H overrides | – | H (tap admit) | `reentrantLock` |
| 17 | `RoomChannelSeam` `kuilt-session/RoomChannel.kt:156` | 33 | – | – | H `sharedRaw`:193 | – | H | **H enforced** :225 | – | – |
| 18 | `PeerlessSeam` `kuilt-cluster/ServerCluster.kt:332` | 10 | **H** constant +`ALLOW`:334 | **– none** (`close` is a no-op) | H `MutableSharedFlow()` — never completes | – | H constant | H `= null` | – | – |
| 19–22 | per-peer filter views: `GamePerPeerSeam` `GameNode.kt:151`, `PerPeerSeam` `GossipView.kt:262`, `PerPeerLivenessSeam` `HeddleBootstrap.kt:98`, `PerPeerSeam` `WarpNode.kt:1662` | 13,~20,13,~15 | – | – | **H** `shared.filter{}` ×4 independently | – | H | – | – | – |
| 23 | `RawIncomingProxy` `WarpNode.kt:1635` | ~10 | – | – | H | – | H | – | – | – |
| 24 | `ObservedCapabilitySeam` `kuilt-websocket/WebSocketSeam.kt:73` | ~20 | – (`Seam by inner`) | – | – | – | – | – | – | – |
| 25 | `PrincipalSeam` `kuilt-session/Principal.kt:27` | 1 | – (`Seam by inner`) | – | – | – | – | – | – | – |
| **Test-support seams shipped in published modules** |
| 26–31 | `FakeSeam` 91 · `FaultySeam` 112 · `FlakyLifecycleSeam` 101 · `ControllableSeam` 37 · `DelayedWovenSeam` 38 · `FakeChannelSeam` ~20 | 399 | 1 gate, 4 bare (`ALLOW`×3) | 3 hand CAS | 5 Spool, 1 `flow{}` | 4 bare launches | 1 `latchingTo`, 5 hand | – | – | mixed |
| 32 | `MeteredSeam` `kuilt-scale/…:23` | 33 | – | – | – | – | – | – | – | – |

Totals across the tabulated bodies: **3,249 code lines / ~4,150 comment lines.** The
comment-heavier-than-code ratio in the core seams (`CompositeSeam` 867:346, `NwSeam` 1200:873) is
itself the tell: the duplication is currently being managed by prose.

## 1 — Lines a shared base would absorb

Estimates, per implementation, of code lines that are lifecycle plumbing a
`Connection`-shaped fabric already gets free from `LinkSeam`/`MeshSeam`:

| Implementation | code | absorbable | what |
|---|---:|---:|---|
| `NwSeam` | 873 | **~330** | double-dial dedup + drain/grace (`NwSeam.kt:640-1300`) duplicates `MeshSeam`'s; roster/state/close/budget fields ~120 |
| `MCSessionLink` | 197 | **~90** | roster mirror, `peersLock`+`collapsed`, tearDown, spool wiring |
| `NearbySeam` | 156 | **~75** | fields `:96-175` (23) + close/collapse `:396-461` (~35) + roster mirror pump (~15) |
| `BridgePeerLink` | 130 | **~70** | near line-for-line copy of `MCSessionLink`'s roster/teardown half |
| `WebRTCPeerLink` | 90 | **~45** | `tear`, `close`, `_peers`, `_state`, `channelFlow` inbox |
| decorators 9–17 (`Composite`…`TokenGated`) | 1,009 | **~300** | a `DelegatingSeam` base absorbing delegate-forwarding + derived budget + roster mirroring |
| per-peer views 19–23 | ~70 | **~55** | one `Seam.filteredBy(peer)` factory replaces five hand-written classes |
| test seams 26–31 | 399 | **~120** | shared close-once + roster collapse |
| **Total** | **3,249** | **≈ 1,085 (33%)** | |

Of that, **~610 lines sit in the five radio fabrics** and are the load-bearing third — the rest is
delegation boilerplate whose defect rate is low.

## 2 — Concerns hand-rolled *differently* (divergence, not just duplication)

| Concern | Divergence | Evidence |
|---|---|---|
| **Payload budget** | 18 seams `override val maxPayloadBytes`; only **5** production sites construct a `PayloadTooLarge`. Fabric seams pre-check and refuse; **decorators publish a derived budget and do not enforce it**, so a payload above the decorator's number but within the wrapped seam's is *delivered* | `Seam.kt:33-38` states it as a known divergence (#2642); enforcers: `Connection.kt:91`, `NwSeam.kt:2099`, `RoomChannel.kt:225`, `SeamRoom.kt:3547`, `RaftEngine.kt:3252` |
| **Roster collapse on tear** | **five** mechanisms for one invariant: `latchingTo` (2 sites), `peersLock`+`collapsed` flag (3), under the seam's own lock re-reading `closed` (1), idempotent `update{}` CAS (2), **unguarded `.value=`** (4) | `LatchingTo.kt:63`; `InMemoryLoom.kt:217`, `ControllableLoom.kt:214`; `NearbySeam.kt:101`, `MCSessionLink.kt:103`, `BridgePeerLink.kt:123`; `NwSeam.kt:2296`; `LinkSeam.kt:195`, `MeshSeam.kt:1332`; `WebRTCPeerLink.kt:229`, `TieredSeam.kt:221`, `RoomHubSeam.kt:323`, `ManagedSeam.kt:225` |
| **Double-dial dedup** | `canonicalLinkNonce` implemented **twice, byte-identical including its KDoc**, in two modules, with two independent wire hellos (`MeshHello` / `NwHello`) and two private `ByteArray.toHex` helpers | `MeshSeam.kt:616` vs `NwHello.kt:114`; `MeshSeam.kt:220` vs `NwHello.kt:120` (third copy `Creel.kt:111`) |
| **Close-once latch** | `SeamStateGate.tear` verdict (2) · atomicfu `atomic(false).compareAndSet` (2) · **`java.util.concurrent.atomic.AtomicBoolean`** (1) · `MutableStateFlow<Boolean>.compareAndSet` (1) · plain `var closed` (1) · **none at all** (`PeerlessSeam.close` is a literal no-op) | `MCSessionLink.kt:153`; `NwSeam.kt:545`, `NearbySeam.kt:150`; `BridgePeerLink.kt:157`; `InMemoryLoom.kt:247`; `WebRTCPeerLink.kt:236`; `seam-harness-coverage.md:70` |
| **`incoming` shape** | `Spool` single-collection FIFO (11) vs `channelFlow` (1) vs `asSharedFlow` (1) vs `flow{emitAll}` (1) vs `MutableSharedFlow()` **that never completes** (`PeerlessSeam`) vs `shared.filter{}` re-derived **four** times | `Spool.kt:36`; `WebRTCPeerLink.kt:87`; `TokenGatedSeam.kt:89`; `MuxClientLoom.kt:202`; `ServerCluster.kt:336`; `GameNode.kt:159`, `GossipView.kt:278`, `HeddleBootstrap.kt:106`, `WarpNode.kt:1671` |
| **Thread-safety primitive** | atomicfu `reentrantLock` (8) · `kotlinx.coroutines.sync.Mutex` (2, one seam holds **both**) · `java.util.concurrent.locks.ReentrantLock` (1) · single-thread confinement (1, target-justified) · legacy `limitedParallelism(1)` confinement in `CompositeSeam`/`CompositeLoom` | `NearbySeam.kt:101` + `:17`; `BridgePeerLink.kt:28`; `WebRTCPeerLink.kt:67`; CLAUDE.md records the composite legacy |
| **Self-send guard** | not divergent — **20 verbatim copies** of the same `require(peer != selfId) { "Cannot send to self — use broadcast if you intend to loop back" }` | 20 files, incl. 5 in `kuilt-core` itself |
| **Pump launch** | `pumpIn` in **11 places, none of them a fabric seam** (only `MuxBase`, `CompositeSeam`, `RoutedRaftTransport`); every radio fabric launches raw, `NwSeam` ×9 from its `init` | `PumpIn.kt`; `NwSeam.kt:2346-2362`, `NearbySeam.kt:159-170` |

## 3 — Guards that exist because of this duplication

Of the 30, **four exist only for it** and would be unnecessary if the concern lived in one place:

| Guard | Why it exists | Fate under a base |
|---|---|---|
| `forbidBareSeamStateFlow` (#2627) | every seam owns its own `MutableStateFlow<SeamState>`; 10 `ALLOW-bareSeamState` markers | **gone** — the base owns the gate |
| `forbidUnboundedSwatchDelivery` | inbound buffering re-derived per seam; `Spool` centralises it, the guard polices non-adoption | **gone** |
| `verifySeamHarnessCoverage` (#1871) | 34 separate implementations to keep bound to the TCK | shrinks with the population, does not disappear |
| `forbidCoroutineLaunchDuringConstruction` (#2465/#2482) | **9 of 11** baseline entries are seam/`Connection` pump-start-in-constructor sites (`NearbySeam` ×4, `MuxBase`, `SingleCollectionConnection`, `FaultySeam`, `FlakyLifecycleSeam`, `RoutedRaftTransport`) | mostly gone |

Dominated-but-not-caused (would shrink, not vanish): `forbidBareLaunchIn` (baseline 20 sites / 11
files; only 5 are seams — the rest are quilter/warp/deal coordinators), `forbidSuspendCallUnderLock`
(#2480, per-seam hand-rolled locks), and the three cancellation guards
(`forbidRunCatchingCancellableUnderNonCancellable`, `forbidCancellationRethrowAroundBound`,
`forbidCancellationSwallowingCatch`) which police per-seam close fan-outs. The remaining ~20
(`verifyDocCitations`, `forbidTightRunTestTimeout`, `forbidHashOrderedSeededDraw`, lint/build/skill
guards, …) are unrelated.

## 4 — Was a skeleton considered? Yes, and Option B was explicitly rejected

`docs/superpowers/specs/2026-07-10-seam-terminal-lifecycle-design.md:108-131,193-195` — there is no
`docs/adr/` directory; the design specs under `docs/superpowers/specs/` are the ADR-equivalent.

> **B — a shared close skeleton (quiesce → publish → release)** … *"the option we deliberately
> demote, for two reasons found in the code, not in taste:"*
> 1. **Self-deadlock on the pump-initiated path** — `LinkSeam`/`MeshSeam` `readLoop`'s `finally`
>    triggers teardown *from inside the scope the skeleton would join*; a universal
>    join-before-publish deadlocks or grows a "join everything except the calling job" carve-out.
> 2. **`Torn` latency coupled to pump backpressure** — a pump parked in `spool.deliver` under
>    SUSPEND delays every observer's terminal signal.
>
> Non-goals: *"**Not** … a universal lifecycle base class (Option B's skeleton) — the survey shows
> teardown steps genuinely differ per seam; only the terminal publication is common."*

Two things about that rejection that matter now:

- **Its scope is narrower than the brief's question.** It rejects an inherited **teardown
  ordering** protocol. It says nothing against sharing roster collapse, budget enforcement,
  the self-send guard, pump launch, dedup or the hello — and the same doc's *Residual seam*
  section parks the roster half explicitly: *"A future generalization (a gate that owns a bundle
  of terminal-collapsible flows) is possible but **not justified by five call sites**."* That
  count is now **twelve** (§2, roster row), so the stated ground for parking has expired.
- `SeamStateGate.kt:56-79` records the sequel: the gate was `internal` for a year, four
  out-of-module fabrics hand-rolled the latch, three wrote the banned check-then-set, and the
  KDoc's own "What being `public` does NOT buy" says reachability is not adoption and that a
  lexical guard was needed — which is `forbidBareSeamStateFlow`, landed later as #2627.

`gh issue list --search "AbstractSeam OR \"base seam\" OR skeleton in:title,body" --state all`
returns **no issue proposing one** (9 hits, all incidental mux/gossip work). This is not a
declined proposal; it is an unasked question outside the one Option-B slice.

## 5 — Recommendation, prioritised

**Extract first: `MeshSeam`'s link-admission core as the fabric SPI for callback-driven radios —
i.e. make the five radio fabrics `Connection` producers instead of `Seam` implementers.**

Why this and not an `AbstractSeam`:

- It is **the shape the repo already documents and already validates** (`docs/extending-fabrics.md`,
  `:kuilt-tcp` at ~11 lines of `Connection`), it is **composition not inheritance**, and it does not
  reopen Option B — `MeshSeam` keeps its own teardown, so neither the self-deadlock nor the
  `Torn`-latency objection applies.
- `Mesh.addLink` (`MeshSeam.kt:88`) already does dynamic join, admission, dedup and roster update —
  exactly the four things the radios re-derive. `NwSeam`'s ~330 duplicated lines are the single
  largest concentration of duplicated logic in the tree, and its `canonicalLinkNonce` is a
  byte-identical copy.
- Every radio API is already per-peer message-shaped (`NwConnectionId`, Nearby `endpointId`, MC
  `MCPeerID`, one RTC data channel per peer), so a `Connection` per remote is a faithful model, not
  a squeeze.

**Best template: `MeshSeam` + `kuilt-tcp`.** `MeshSeam` is the only in-tree seam that solves the
whole N-peer problem (gate, spool, roster under lock, budget pre-check with reservation, admission,
dedup, drain-order hold); `:kuilt-tcp` is the proof that a fabric reduces to a `Connection` plus a
`weave`. `LinkSeam` is the template only for the degenerate 2-peer case (`WebRTCPeerLink`).

**Order:** (1) `WebRTCPeerLink` → `Connection` + `identified()` — smallest, 2-peer, ~45 lines, and
its own KDoc at `:200-226` already points at `SeamStateGate` and names #1879 as the same shape being
a live bug elsewhere. (2) `BridgePeerLink` and `MCSessionLink` together — they are near-copies, and
converging them onto `MeshSeam` removes the `java.util.concurrent` outlier. (3) `NearbySeam`.
(4) `NwSeam` last and only if (1)–(3) hold, because its drain/grace window is genuinely richer than
`MeshSeam`'s and may need to move *up* into `MeshSeam` rather than down.

**Cheap, independent wins available now, none of which needs the extraction:**
`Seam.requireNotSelf(peer)` in `:kuilt-core` (deletes 20 verbatim copies, 5 of them inside
`kuilt-core`); hoist `canonicalLinkNonce`/`toHex` into `:kuilt-core` (deletes a byte-identical
duplicate of a security-relevant function); `Seam.filteredBy(peer)` (replaces the five per-peer
view classes); and close the `Seam.kt:33-38` decorator budget divergence, which is a live
behavioural bug, not tidiness.
