# `gameHosted` — hosting a game from accepted connections

> Implementation-design doc for epic [#794](https://github.com/tractat-us/kuilt/issues/794)'s
> **"Hosted game bootstrap"** item. Planning sub-issue [#831](https://github.com/tractat-us/kuilt/issues/831).
> Decisions land here before code.

## In one sentence

A server should be able to say *"accept whoever connects, and run a game over them"* — and
get back a live game session — without re-implementing the wiring kuilt already proves in its
own test harness.

## The plain-language version

kuilt already knows how to run a hosted game. Its test suite stands up a **star**: one hub in
the middle, several clients around it, all sharing game state. The pieces are all public and
transport-agnostic:

- `meshSeam(selfId, connections)` bonds a set of point-to-point links into one group view.
- `GossipSeam(base, FullFanout)` makes the hub flood every message to all clients.
- `Mesh.addLink(connection)` lets new clients join an already-running hub.
- `gameHost(seam, peerCount)` runs the turn-based game on top.

The only thing missing is the **front door**. A real server accepts WebSocket connections, but
every public way to do that today (`KtorServerLoom.nextLink(): Seam`) hands back a *finished
2-peer seam*, already collapsed — not the raw `Connection` that the hub composer needs. The
adapter that would un-collapse it (`WebSocketConnection`) exists but is `internal`. So a
consumer is forced to either give up the star topology or clone kuilt's private code.

This design adds that front door: a small, transport-agnostic way to **accept connections**, and
a composer that turns a stream of accepted connections into a running hub.

## Why this matters now (the concrete driver)

A downstream consumer's server needs exactly this: accept WebSocket clients → compose a star hub
→ run `gameHost`, behind a flag parallel to the existing relay host. Today it cannot, without
duplicating kuilt's `internal` `WebSocketConnection` — the explicit anti-goal. This is a **hard
blocker** for that consumer's hub-composition work, and the capability is general kuilt, not
consumer-specific.

## Scope

**In:** the minimal public surface to accept connections and compose a single-host star.

**Out (later #794 phases — do not build here):** session-mux (many games over one connection
set), `TwoTier`/federated routing (multi-server failover). Building toward them now is YAGNI.

## The five pieces

Each sits at its natural altitude; the dependency arrow only ever points down.

### 1. `ConnectionSource` — the accept contract · `kuilt-core`

The one genuinely-missing abstraction. A transport-agnostic stream of inbound `Connection`s —
*not* collapsed into 2-peer `Seam`s. Lives beside `Connection`/`Mesh`.

```kotlin
public interface ConnectionSource {
    /**
     * Suspends until the next inbound peer connection is accepted, then returns it.
     * Mirrors `KtorServerLoom.nextLink()`, but yields the raw `Connection` (a hub spoke)
     * rather than a collapsed 2-peer `Seam`.
     */
    public suspend fun accept(): Connection
}
```

With it, the accept-pump is trivial: `while (true) hubMesh.addLink(source.accept())`.

### 2. `KtorConnectionSource` — the WebSocket implementation · `kuilt-websocket`

A public `ConnectionSource` that routes `webSocket(path)` and emits each accepted session as a
`Connection` over an unlimited `Channel<Connection>`. It **reuses the existing `internal
WebSocketConnection`** — which stays internal. Only `ConnectionSource` (a `kuilt-core` contract
type) crosses the public boundary, so no implementation type leaks.

This is the parallel of `KtorServerLoom`, but for the **Connection-aggregation (hub)** topology
rather than the 2-peer/relay one. The two coexist: a WS session is *either* a relay seam *or* a
hub spoke, decided by which accept object the server installs on the route.

```kotlin
public class KtorConnectionSource(
    application: Application,
    path: String,
    // selfPeerId, dispatcher, (principalExtractor — see Deferred)
) : ConnectionSource {
    override suspend fun accept(): Connection // = channel.receive()
}
```

### 3. `hostedOverlay` — the reusable composer · `kuilt-gossip`

Turns a `ConnectionSource` into a started hub `Seam`. This *is* the body of the existing
test-only `inMemoryStarOf`, graduated to a production primitive. Lives in `kuilt-gossip` because
it owns `GossipSeam` + `FullFanout` (and reaches `meshSeam` via its `kuilt-core` api dependency).

```kotlin
public suspend fun CoroutineScope.hostedOverlay(
    selfId: PeerId,
    source: ConnectionSource,
    // dispatcher, random, clock
): Seam
```

Behaviour: build an initially-empty `Mesh` (`meshSeam(selfId, emptyList(), dispatcher)`), wrap it
in `GossipSeam(base, activeViewPolicy = FullFanout)`, `start` it on the receiver scope, and launch
an accept-pump (`launch { while (isActive) hubMesh.addLink(source.accept()) }`) so clients join
the running hub as they connect. Returns the `GossipSeam`. The pump coroutine lives on the
receiver scope and is torn down with it.

### 4. `gameHosted` — the convenience entry point · `kuilt-game`

What a consumer calls. Thin sugar over (3) + `gameHost`. Adds one new `kuilt-game → kuilt-gossip`
main dependency (today it's only transitive via tests).

```kotlin
public suspend fun CoroutineScope.gameHosted(
    selfId: PeerId,
    source: ConnectionSource,
    peerCount: Int,
    // + gameHost's params: returnAt, raftConfig, livenessConfig, clock, identity, …
): GameSession = gameHost(hostedOverlay(selfId, source, …), peerCount, …)
```

Clients are unchanged: they run `gameJoin` over a `KtorClientLoom` seam exactly as today.
Advanced callers who need to interpose on the hub seam can drop to `hostedOverlay` directly — the
layering is deliberate (the composer is the primitive; `gameHosted` is the sugar).

### 5. In-memory source + the loopback leak test · `kuilt-test` + `kuilt-game` commonTest

- **`InMemoryConnectionSource`** (`kuilt-test`): an unlimited `Channel<Connection>` you push
  `connectionPair()` hub-ends into; `accept()` receives from it. Lets `gameHosted` be driven
  **end-to-end under virtual time** — the same path a real server uses, minus the WS wire.
  `inMemoryStarOf` is reimplemented on top of `hostedOverlay` + this source (one composition path,
  not two).

- **Leak-invariant test** (`kuilt-game` commonTest): proves per-seat disclosure never escapes.
  A hub sends a per-seat masked frame via `seam.sendTo(client, …)`; the test asserts no *other*
  client ever observes it. The invariant holds by construction — `FullFanout` floods `broadcast`
  only, and `GossipSeam.sendTo` passes through unwrapped — so this test pins it against
  regression. It runs under `runTest` + `StandardTestDispatcher`, bounded advance, on the
  in-memory source. The invariant lives kuilt-side, so the test belongs here (de-risks the
  consumer's disclosure-relay gate at the source).

## Deferred (call out explicitly, do not silently drop)

**Principal / attestation on the hub-accept path.** `KtorServerLoom` carries a
`principalExtractor: (ApplicationCall) -> Principal?` that rides the connection through to
`Member.principal`. The `Connection`-yielding accept path has no equivalent yet; the MVP relies on
the mesh's peer-id handshake (`MeshHello`) for identity, with no host-verified attestation. Adding
attestation to `KtorConnectionSource` (and threading it through `hostedOverlay`/`gameHosted`) is a
follow-up issue, filed when this lands. The core composition and the leak invariant do not depend
on it.

## Delivery — three stacked PRs, each independently green

| PR | Lands | Unblocks |
|----|-------|----------|
| **A** | `ConnectionSource` (core) + `InMemoryConnectionSource` (test) + `hostedOverlay` (gossip) + composition test; reimplement `inMemoryStarOf` on top | The structural unblock — proves the composer end-to-end in-memory |
| **B** | `KtorConnectionSource` (websocket) + WS round-trip test | The real WS front door |
| **C** | `gameHosted` (game, new game→gossip edge) + loopback leak test | The convenience API the consumer adopts; pins the disclosure invariant |

Each PR is `Part of #794` (non-closing for the epic). The closing keyword (`Closes #831`) goes on
the PR that lands this doc + the implementation plan, never on the epic. PR-A alone is the
structural unblock; B and C complete the WebSocket path.

## Done when

A server can call `gameHosted(selfId, KtorConnectionSource(app, path), peerCount)` and obtain a
live `GameSession`; clients `gameJoin` over `KtorClientLoom` and converge; per-seat `sendTo`
disclosure is provably never relayed; and the same composer runs identically in-memory under
virtual time and over real WebSockets — with `inMemoryStarOf` reduced to a thin wrapper over the
production composer rather than a separate hand-rolled path.
