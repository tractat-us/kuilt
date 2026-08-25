# Which connections we actually test

kuilt moves data between devices over a handful of different connection types — a
Wi-Fi link between two phones in the same room, a WebSocket to a server, a browser
data channel. Internally each of those is a `Seam`, and each one promises to behave
the same way: deliver what you send, tell you who else is here, and say so cleanly
when it goes away.

We check those promises with a shared test suite. Every connection type is supposed
to be plugged into it. The trouble is that nothing used to tell us when one *wasn't* —
so a connection could quietly sit outside the suite for months, and the test report
would look perfectly healthy the whole time. That happened: two connection types
broke a brand-new promise while the coverage table read a clean 6/6.

This page is the fix. It is a list of **every** connection implementation in the
codebase, each one either pointed at the test harness that exercises it, or written
down as untested with a reason. A build check keeps the list complete: add a new
connection type without adding a line here and the build fails.

## What this page proves, and what it does not

It proves the list is **complete**. Nothing is missing from it, and nothing on it
points at a test or a file that has since been deleted or renamed.

It does **not** prove the mapping is right. Nothing verifies that the harness named
beside a seam really exercises that seam — a row could name the wrong harness and the
build would stay green. That mapping is a human claim, reviewed like any other prose,
which is why every row has to say *how* the harness reaches the seam: the written path
is the thing a reviewer can go and falsify.

The limit is not laziness. "Is seam X covered by some harness?" is not decidable from
the source. `SeamConformanceSuite` drives a seam **through a `Loom`** — a harness hands
the suite a `Loom`, the suite calls `host()`/`join()`, and which `Seam` implementation
comes out is a runtime fact of `weave`. Nothing in the text of
`InMemoryLoomConformanceTest` says the words `InMemorySeam`. A check that *claimed* to
detect real coverage would be exactly the kind of falsely reassuring artifact that
caused the original miss.

## Adding a row

One row per production `Seam` implementation, keyed by the file it is declared in and
its type name. The harness column is either one or more backticked
`SeamConformanceSuite` subclass names, or the bare word `none`. The last column is
required either way, and must be a real sentence:

```markdown
| `MySeam` | `kuilt-thing/src/commonMain/kotlin/us/tractat/kuilt/thing/MySeam.kt` | `MyLoomConformanceTest` | `MyLoom.weave` returns it; the harness returns `loom to loom`. |
| `MySeam` | `kuilt-thing/src/commonMain/kotlin/us/tractat/kuilt/thing/MySeam.kt` | none | A per-peer view minted inside `MyNode`; no `Loom` produces it. |
```

Prefer binding a harness to writing an opt-out. Not every entry below marked `none` is
*untestable* — `FakeSeam` comes out of a real `Loom` and would take a subclass. It is simply
unbound, which is the finding this page exists to publish. `ControllableSeam` was in the same
position until #2441 bound it, and the binding immediately turned up a real defect (#2443),
which is the argument for doing this rather than writing a reason.

## Registry

| Seam | Declared in | Harness | Why |
|------|-------------|---------|-----|
| `ChannelView` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxBase.kt` | `MuxServerLoomConformanceTest` | The joiner side: `RoomHubLoomPair.clientLoom.weave` returns `NamedMux(base, scope).channel(name)`, and `MuxBase.channel` mints this view. |
| `CompositeSeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/composite/CompositeSeam.kt` | `CompositeConformanceTest` | `CompositeLoom.weave` returns it; the harness returns one `CompositeLoom` as both ends of the pair. |
| `InMemorySeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/InMemoryLoom.kt` | `InMemoryLoomConformanceTest` | `InMemoryLoom.newSeam` mints it and the harness returns `loom to loom`; also the joiner tier of `TieredSeamConformanceTest` and the base of `GossipSeamConformanceTest`. |
| `LinkSeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/LinkSeam.kt` | `IdentifiedConformanceTest`, `HandshakingConformanceTest`, `TcpConformanceTest` | `identified(...)` returns it directly and `handshaking(...)` ends in `identified(...)`, so all three harnesses land on this one class; it is also the seam `ObservedCapabilitySeam` wraps. |
| `Mesh` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt` | `PeerMeshConformanceTest` | The interface `peerMesh(...)` returns and `MeshSeam` implements; `PeerMeshLoom.weave` calls `peerMesh(...)`. |
| `MeshSeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/fabric/MeshSeam.kt` | `PeerMeshConformanceTest` | `buildMesh` mints it behind `peerMesh(...)`, which the harness's `PeerMeshLoom.weave` calls; it is also the client base under `MuxServerLoomConformanceTest`'s channel view. |
| `ResumableChannel` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/MuxClientLoom.kt` | none | Minted only by `MuxClientLoom.weave`. A harness was written for #2441 — two `MuxClientLoom`s over one shared `InMemoryLoom` base — and it **reds on ungated core**, so it is blocked rather than merely unwritten: `close()` delegates to a `NamedMux` channel view, whose `state` keeps reporting the live base's `Woven`, failing `closeDrivesStateTornNormal` (24 of 30 pass; the other 5 reds are that same value read from 5 places). No capability flag may excuse an ungated obligation, so binding this waits on #2372 — whose acceptance criteria already ask for exactly this harness. |
| `RoomHubSeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomHubSeam.kt` | `MuxServerLoomConformanceTest` | `MuxServerLoom.weave(Rendezvous.New)` resolves through `roomFor` to this hub, and the harness's `serverLoom` delegates there. Bound in #1937 — it is one of the two seams #1871 was filed about. |
| `TieredSeam` | `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/TieredSeam.kt` | `TieredSeamConformanceTest` | `tieredSeam(...)` returns it and `TieredLoomPair.hostLoom.weave` calls that over two `InMemoryLoom` tiers. Bound in #1937 — the other seam #1871 was filed about. |
| `ManagedSeam` | `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/ManagedSeam.kt` | none | A swap-on-failover wrapper `clusterClient` builds around already-woven seams, so no `Loom` returns it. Its `state` is a constant `Woven` by design (it outlives its backing seams), which makes it non-conforming to the suite's ungated close-drives-`Torn` obligation — unobserved precisely because no harness drives it. |
| `PeerlessSeam` | `kuilt-cluster/src/jvmAndAndroidMain/kotlin/us/tractat/kuilt/cluster/ServerCluster.kt` | none | A file-private no-peer placeholder `localOverlay` builds for the single-server overlay. `close()` is a literal no-op, `state` a constant `Woven`, and `incoming` never completes — three suite obligations it would fail, none of them reachable because nothing weaves it. |
| `DelayedWovenSeam` | `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/DelayedWovenLoom.kt` | none | The one entry where `none` understates things: `SeamConformanceSuite.sendWhileWeavingDoesNotThrow` builds a `DelayedWovenLoom` inline, so every harness drives this seam through that one obligation. No harness returns it from `newLoomPair`, so the rest of the suite never touches it. |
| `GamePerPeerSeam` | `kuilt-game/src/commonMain/kotlin/us/tractat/kuilt/game/GameNode.kt` | none | A sender-filtered view `VoterLivenessMonitor` mints per monitored voter over a shared heartbeat seam. A liveness-detector adapter, not something a `Loom` weaves. |
| `GossipSeam` | `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipSeam.kt` | `GossipSeamConformanceTest` | The harness's `GossipLoom.weave` wraps each base seam as `GossipSeam(base = base.weave(rendezvous), ...)` and returns that loom as both ends. |
| `PerPeerSeam` | `kuilt-gossip/src/commonMain/kotlin/us/tractat/kuilt/gossip/GossipView.kt` | none | Minted inside `GossipView.startDetector` to feed one `HeartbeatPartitionDetector`. It is instantiated incidentally during a gossip conformance run, but it is never handed to the suite and is not in the returned seam's delegation chain, so no obligation is asserted against it. |
| `PerPeerLivenessSeam` | `kuilt-heddle/src/commonMain/kotlin/us/tractat/kuilt/heddle/HeddleBootstrap.kt` | none | A filtered per-peer detector link `HeddleNode` builds over its own seam. No `Loom` produces it. |
| `MCSessionLink` | `kuilt-multipeer/src/appleMain/kotlin/us/tractat/kuilt/multipeer/internal/MCSessionLink.kt` | `MultipeerAppleConformanceTest` | An `appleTest` harness whose two Looms each build one link over a `FakeMCSessionBus` — a Kotlin/Native subclass of `MCSession` intercepting `connectedPeers`/`sendData`/`disconnect`, the only injection point `appleMain` has. Bound in #2441; it runs on `macosArm64` and `iosSimulatorArm64`, and found #2444 (no torn-send guard, fixed) and #2445 (no self-connection guard, fixed — the harness now injects a self-dial and declares no `selfDialGap`). |
| `BridgePeerLink` | `kuilt-multipeer/src/jvmMain/kotlin/us/tractat/kuilt/multipeer/internal/BridgePeerLink.kt` | `MultipeerConformanceTest` | `MultipeerPeerLinkFactory.weave` reaches it through `openSession`/`joinSession` to `startSession`; the harness returns two such factories. |
| `NearbySeam` | `kuilt-nearby/src/commonMain/kotlin/us/tractat/kuilt/nearby/NearbySeam.kt` | `NearbyConformanceTest` | `NearbyLoom` builds it on both the host and the join path, and the harness returns one `NearbyLoom` over a fake API as both ends. |
| `NwSeam` | `kuilt-nw/src/commonMain/kotlin/us/tractat/kuilt/nw/NwSeam.kt` | `NwConformanceTest`, `NwLoopbackConformanceTest`, `NwBridgeLoopbackConformanceTest` | `NwLoom.weave` constructs it; all three harnesses return `NwLoom` pairs — over the in-memory fake, over a real Apple loopback link, and over the macOS JVM bridge respectively. |
| `TokenGatedSeam` | `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeam.kt` | none | A decorator `Seam.tokenGated` applies to an already-woven tap seam, so no `Loom` returns it. It overrides `peers` (in the verifier role) and `incoming` while delegating `close` and `state` to the wrapped seam — a split the suite would probe and only `TokenGatedSeamTest` currently does. |
| `MeteredSeam` | `kuilt-scale/src/main/kotlin/us/tractat/kuilt/scale/MeteredSeam.kt` | none | A counting decorator the scale-benchmark mesh builders apply. `:kuilt-scale` is an unpublished JVM bench harness with no conformance subclass of its own. |
| `PrincipalSeam` | `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Principal.kt` | none | Reached only through `Seam.withPrincipal`, whose one production caller is `KtorServerLoom` — and only when the consumer supplies a `principalExtractor`. The conformance harness leaves the default `{ null }`, at which point `withPrincipal` returns the receiver unwrapped, so this class is on a live production path with no suite and no direct unit test. |
| `RoomChannelSeam` | `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt` | none | A sub-channel view `SeamRoom.channel(id)` mints — one abstraction level above `Loom`/`Seam`, and no `newLoomPair` reaches a `SeamRoom`. |
| `PerPeerSeam` | `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` | none | An internal per-peer view `SeamRoom` mints for one admitted member's heartbeat detector. Not produced by any `Loom`. |
| `FakeChannelSeam` | `kuilt-session-test/src/commonMain/kotlin/us/tractat/kuilt/session/test/FakeRoom.kt` | none | The channel view of the `FakeRoom` test double. A `Room`-level fake, driven by room-level tests, never by a `Loom`. |
| `ControllableSeam` | `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/ControllableLoom.kt` | `ControllableLoomConformanceTest` | `ControllableLoom.weave` mints it and the harness returns `loom to loom` (one shared in-process mesh, as with `InMemoryLoom`). Bound in #2441; the binding found #2443 — a torn seam still names every remaining remote and has dropped its own `selfId` — declared as a `collapsesPeersOnTear` gap. |
| `FakeSeam` | `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeSeam.kt` | none | The general-purpose test double, produced by `FakeLoom` and constructed directly in dozens of unit tests. No harness returns a `FakeLoom`, so the fake every other test leans on is itself unverified against the contract it imitates — it was fixed for a real violation of that contract in #1854. |
| `FaultySeam` | `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FaultySeam.kt` | none | Produced by `FaultyLoom`, which *is* wired into a conformance suite — but `RoomConformanceSuite`, a different, room-level one. No `SeamConformanceSuite` subclass builds a `FaultyLoom`. |
| `FlakyLifecycleSeam` | `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FlakyLifecycleSeam.kt` | none | A lifecycle-fault decorator constructed directly by `:kuilt-core` unit tests; no `Loom` produces it. The other half of the #1854 pair, and the other fixture seam that broke the contract it exists to stress. |
| `PerPeerSeam` | `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt` | none | A sender-filtered adapter `WarpNode` mints per peer for its heartbeat detector, over the node's own seam. Not a `Loom` product. |
| `RawIncomingProxy` | `kuilt-warp/src/commonMain/kotlin/us/tractat/kuilt/warp/WarpNode.kt` | none | An internal proxy `WarpNode` wraps its seam in before muxing, so the raw frame flow stays observable. Constructed once during node setup; no `Loom` returns it. |
| `ObservedCapabilitySeam` | `kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketSeam.kt` | `WebSocketConformanceTest`, `MDNSConformanceTest` | `WebSocketSeam(...)` returns this wrapper around `identified(...)`, built by both `KtorServerLoom` and `KtorClientLoom`; the mDNS harness reaches the same class because its peer-link factory delegates to those two looms. |
| `WebRTCPeerLink` | `kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/internal/WebRTCPeerLink.kt` | `WebRTCConformanceTest` | `WebRTCPeerLinkFactory.buildLink` returns it; the harness returns two such factories over paired fakes. |

## What the build check enforces

`verifySeamHarnessCoverage` in the root `build.gradle.kts`, wired into `check` and run
in the `doc-citations` CI job so that editing this file alone is still covered. It:

- scans production source (`src/*Main/`, `src/main/`, plus `spike/src`) for every type
  whose supertype list reaches `Seam`, directly or through an intermediate;
- fails when one of them has no row here, naming the file, the line and the type;
- fails when a row names a seam that is no longer in the tree, or a harness that is no
  longer a `SeamConformanceSuite` subclass;
- fails when a `SeamConformanceSuite` subclass is cited by no row at all — every named
  subclass weaves *something*, so one attributed nowhere means the mapping has drifted;
- fails when a row's last column is too short to be a reason;
- fails on an anonymous `object : Seam` in production source, which could carry no row.

Two things it cannot see, stated so nobody mistakes a green for more than it is. It does
not check that a named harness really weaves the seam beside it — see the section at the
top. And it matches type names *lexically*, with no import or `typealias` resolution, so
a seam that reached `Seam` through an `import ... as` alias would be missed; nothing in
the tree does that today, and closing it properly needs a compiler front end rather than
a source scan.
