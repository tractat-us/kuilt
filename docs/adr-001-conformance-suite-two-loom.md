# ADR-001 — Conformance suite: two-Loom contract

**Status:** Proposed
**Date:** 2026-05-27
**Context:** first ADR in kuilt's own series. The `Loom`/`Seam`/`Swatch` contract surface itself is fixed elsewhere; this ADR changes only the *test harness* shape.

## Context

Phase 3 extracted `SeamConformanceSuite` into `:kuilt-conformance` (`commonMain`) so any fabric's test source set can subclass it. Issue #1617 asks to adopt it across the four real fabrics (websocket, mdns, webrtc, multipeer), claiming "each is mechanical: implement `newLoom()`."

**That claim is wrong.** Four of the six invariants (`broadcastFromHostDeliversToJoinedPeer`, `incomingPreservesSendOrderToSingleCollector`, `peersReportsSelfIdAndAtLeastTwoAfterJoin`, and implicitly the join in `closeIsIdempotent`'s peers) call **both `open()` AND `join()` on a SINGLE `newLoom()` instance**, expecting one Loom to play host and joiner with in-process loopback. That holds only for the two in-process *radio* fabrics, which were designed for it:

- `InMemoryLoom` — `open()`/`join()` both register into one shared in-memory mesh (`kuilt-core/.../InMemoryLoom.kt`).
- `NearbyLoom` — `FakeNearbyRadio` is explicitly "a single `FakeNearbyApi` that handles BOTH roles for the same `NearbyLoom`" (`kuilt-nearby/.../FakeNearbyRadio.kt`).

It breaks for all four real network fabrics, each of which is **architecturally role-split** — one instance is host *or* joiner, never both:

| Fabric | Why one Loom can't do both | Evidence |
|---|---|---|
| **websocket** | `KtorClientLoom.host()` throws `UnsupportedOperationException` ("clients join, they do not create"); `KtorServerLoom.join()` throws ("server accepts, it does not join"). | `kuilt-websocket/.../KtorClientLoom.kt:37`, `KtorServerLoom.kt:86` |
| **multipeer** | `host()` and `join()` both `check(activeSession == null)` → calling both on one instance throws. Single-session by design (one `MCSession`/device). | `kuilt-multipeer/.../MultipeerPeerLinkFactory.jvm.kt:103,116` |
| **mdns** | `host()` → `KtorServerLoom` + JmDNS register; `join()` → `KtorClientLoom`. Same server/client split, plus real multicast. | `kuilt-mdns/.../MDNSPeerLinkFactory.kt:69,79` |
| **webrtc** | `host()`/`join()` each `signaling.open(room)` + a host/joiner handshake; a signaling room caps at 2 peers. One factory can't loopback to itself. | `kuilt-webrtc/.../WebRTCPeerLinkFactory.kt:41,50` |

So "implement `newLoom()`" is impossible for these four. The suite needs a contract that lets a fabric supply a **host instance and a joiner instance**, while the radio fabrics keep supplying one instance for both roles. The Nearby findings doc already flagged this and deferred the adoption decision to #1515 (`docs/kuilt-nearby-conformance-findings.md` § "Structural finding").

## Decision

Reshape the suite's binding point from a single `newLoom()` to a **two-Loom pair**:

```kotlin
public abstract class SeamConformanceSuite {
    /**
     * Provide a fresh host/joiner Loom pair per test.
     *  - `.first`  hosts via weave(Rendezvous.New(pattern))  (or the host(pattern) wrapper).
     *  - `.second` joins via weave(Rendezvous.Existing(joinTag())) (or join(joinTag())).
     * In-process radio fabrics return the SAME instance twice: (loom, loom).
     * Role-split fabrics return DISTINCT host/joiner Looms wired to reach each other.
     */
    public abstract fun newLoomPair(): Pair<Loom, Loom>

    /** The advertisement the joiner uses. Defaults to the in-memory tag. */
    public open fun joinTag(): Tag = InMemoryTag("joiner")
}
```

Tests then read `val (hostLoom, joinerLoom) = newLoomPair()`, `hostLoom.weave(Rendezvous.New(...))`, `joinerLoom.weave(Rendezvous.Existing(joinTag()))` (equivalently the `host`/`join` wrappers). The single-Loom invariants (`weaveNewYields…`, `availability…`) use only `.first`.

Radio fabrics stay trivial:

```kotlin
class InMemoryLoomConformanceTest : SeamConformanceSuite() {
    private val loom = InMemoryLoom()
    override fun newLoomPair() = loom to loom        // same instance, shared mesh
}
class NearbyConformanceTest : SeamConformanceSuite() {
    override fun newLoomPair() = NearbyLoom(FakeNearbyApi(FakeNearbyRadio())).let { it to it }
}
```

The contract must explicitly accommodate **both** "same backing" (radio: identical instance, one shared mesh/signaling channel) and "independent-but-reachable" (role-split: two instances wired to each other). `Pair<Loom, Loom>` does, because returning `(loom, loom)` is the same-backing case for free.

### Concurrency note (load-bearing for the reshape)

`KtorServerLoom.host()` suspends until a client connects (`nextLink()` blocks on `connectionChannel.receive()`), and the WebRTC handshake needs both ends running. So the delivery invariants must run `host()` and `join()` **concurrently**, not sequentially. The reshaped suite wraps them in `coroutineScope { val h = async { host.host(...) }; val j = async { joiner.join(...) }; … }` — exactly the pattern `WebRTCPeerLinkFactoryTest.openAndJoinExchangeFrames` already uses (`kuilt-webrtc/.../WebRTCPeerLinkFactoryTest.kt`). This is a behavioural change to the suite even for the radio fabrics (harmless there — their `host`/`join` don't block on each other), so the two existing subclasses must stay green after the reshape.

### Real-loopback-first (which backing each pair connects over)

**Default: the pair connects over the fabric's real transport. A fake is the exception, used only where no in-process real transport exists.** This keeps conformance exercising the actual `Loom`/`Seam` code over a genuine connection rather than a fabricated radio:

- **websocket, mdns** connect over **real localhost** — real Ktor server + client, real sockets and frames. (mdns skips only *multicast discovery*, by handing the joiner a directly-constructed advertisement; the byte path is fully real. Real multicast stays `-P`-gated.)
- **webrtc** connects over a real `RTCPeerConnection` loopback **if the wasmJs test runner provides WebRTC**; otherwise the existing paired fake facade stands in.
- **multipeer** has no in-process real transport (Apple radio, macOS-only, no loopback mode), so the CI path routes the pair through a connecting fake at the JNA boundary; a real-radio two-peer test is manual/macOS-gated (mirrors the Nearby real-radio smoke).

Net: we build **one** connecting fake (multipeer), possibly two (webrtc) — not four. The listen/offer-vs-connect asymmetry `host`/`join` encode is genuine, so even the real-transport pairs are two distinct instances, never one playing both roles.

## Per-fabric harness implications

Ordered real-transport-first (the fakes are last):

| Fabric | Backing | Pair (source set) | Verdict |
|---|---|---|---|
| **websocket** | ✅ **real localhost, no fake** | `(KtorServerLoom(app, path), KtorClientLoom(httpClient))` over `testApplication`/localhost (jvmTest) | **Real connection.** Two real looms, real sockets/frames. Must run host()/join() concurrently (server's `host()` blocks until the client connects). `WebSocketSeamRoundTripTest` shows the wiring. |
| **mdns** | ✅ **real localhost byte path** (discovery skipped) | host real WS server; joiner via a directly-constructed `MDNSAdvertisement` at `localhost:port` (jvmTest) | **Real byte path, no transport fake** — see the bypass below. Real multicast stays `-P`-gated. |
| **webrtc** | ⚠️ **real `RTCPeerConnection` if env supports, else paired fake** | two `WebRTCPeerLinkFactory` over `PairedFacadeFactory.pair()` + `PairedSignalingChannels.pair()` (wasmJsTest) | The fake harness already exists and already drives open/join concurrently; real loopback is possible only if the wasmJs runner provides WebRTC. Lightest either way. |
| **multipeer** | ❌ **no in-process real transport → connecting fake** | two factories sharing a delivering `FakeMultipeerNativeLib` at the JNA boundary (jvmTest) | **The one unavoidable fake.** Current `FakeMultipeerNativeLib` is a no-delivery STUB — `mc_session_broadcast` returns `len`, `mc_session_set_data_callback` is a no-op. Needs a fake that routes one session's broadcast into the other's data callback. Real-radio two-peer = manual/macOS. |

### mdns multicast bypass (verified, recommended)

`MDNSPeerLinkFactory.host()` registers JmDNS *then* delegates byte transport to an internal `KtorServerLoom`; `join()` only reads an `MDNSAdvertisement`, converts it to a `WebSocketAdvertisement` (`wsUrl` + `serverPeerId`), and connects via `KtorClientLoom` — **it does no multicast discovery**. So the two-Loom shape lets conformance **skip JmDNS entirely**: the host opens a server on a known port, the test constructs the `MDNSAdvertisement` directly —

```kotlin
MDNSAdvertisement(host = "localhost", port = knownPort,
    serverPeerId = hostFactory.selfPeerId, displayName = "host")   // selfPeerId is already public
```

— and the joiner joins it. This makes mdns conformance deterministic and CI-runnable. The real-multicast path stays `-P`-gated (`mdns.multicast.tests=true`, already in `MDNSMulticastIntegrationTest`), mirroring the Nearby real-radio smoke precedent. If a future structural change couples discovery into `join()`, fall back to `-P`-gated/manual.

## Consequences

- `:kuilt-conformance` public API changes: `newLoom(): Loom` → `newLoomPair(): Pair<Loom, Loom>` (+ overridable `joinTag()`). Both existing subclasses (`InMemoryLoomConformanceTest`, `NearbyConformanceTest`) change in the same PR and stay green.
- Docs to update: `CLAUDE.md` line "subclass `SeamConformanceSuite` and implement `newLoom()`", `docs/usage.md` "Writing your own fabric" snippet, `docs/architecture.md` ("implementing `newLoom()`"), and the `docs/kuilt-nearby-conformance-findings.md` recommendation #1 (adoption is now unblocked).
- Four fabrics gain a `*ConformanceTest` and the epic's "one suite, all fabrics pass" invariant becomes real.
- The suite now asserts *concurrent* open/join, which more faithfully models real fabrics — a small fidelity gain even for the radio fabrics.
- **Downstream overlap:** the planned `Seam`/`Swatch` consumer-side rewrite consumes the same contract. A two-Loom suite that drives genuine host↔joiner topologies (not a self-loopback) is the better regression net for that refactor's coherence; landing this first de-risks it.

## Decided: unifying the rendezvous role (`weave(Rendezvous)`) — see ADR-002

An earlier draft listed `weave(Rendezvous)` as out of scope. **That is now decided in [ADR-002](adr-002-weave-rendezvous.md), and `weave` lands first (foundation-first).** `open(Pattern)` / `join(Tag)` collapse into a single `weave(rendezvous: Rendezvous)` where `Rendezvous = New(Pattern) | Existing(Tag)`, with thin `host`/`join` wrappers retained.

This does **not** change ADR-001's decision. `weave` does not remove the listen/connect asymmetry — it relocates it into data (a `KtorServerLoom` still supports only `New`, a wasm WebRTC client only `Existing`; the unsupported variant still throws). So a fabric still cannot play both roles on one instance, and the **two-Loom `newLoomPair()`** shape is still required. The two ADRs are coherent: **ADR-002 = the contract foundation; ADR-001 = the test harness on top of it.** ADR-001's suite reshape (below) lands *after* `weave`, so it is rebuilt to drive `weave(New(...))` / `weave(Existing(...))` (or the `host`/`join` wrappers) from day one rather than migrating twice.

## Alternatives considered

1. **Separate `newHostLoom()` / `newJoinerLoom()` with defaults delegating to `newLoom()`** — *rejected.* The two fabric classes pull the default in opposite directions: radio fabrics need both methods to return the **same** instance (shared mesh), role-split fabrics need **different** instances. No single default satisfies both — a default of `newLoom()` returning a fresh instance each call breaks radio (two disconnected meshes); returning a cached instance breaks role-split (one instance can't be both). `newLoomPair()` collapses the choice into one method the fabric author controls, making the same-instance vs. distinct-instance decision explicit and local.
2. **Keep the suite unchanged; build a per-fabric *composite* test-only `Loom`** that wraps two factories and routes `open`↔`join` in-process — *rejected.* For ws/mdns this means fusing an unrelated server Loom and client Loom behind one fake "Loom" interface that no production code ever uses — an artificial type that tests a fiction, not the real role-split contract. And it's **impossible** for the mdns real-multicast path (one process can't be two LAN hosts). The composite hides exactly the host/joiner asymmetry the suite should be exercising.
3. **`newLoomPair(): Pair<Loom, Loom>` (chosen)** — one method, author decides same-vs-distinct, accommodates both backings, and the WebRTC test already demonstrates the concurrent open/join idiom it requires.

## Sequencing

Foundation-first: ADR-002's `weave` contract lands **before** this harness reshape, so the suite is built once against the final contract instead of migrating twice. See [ADR-002 §Migration sequence](adr-002-weave-rendezvous.md) for the full cross-repo stack; this ADR's steps are its tail (steps 5–6 there).

1. **This ADR + ADR-002** (docs-only, PR #10) → land.
2. **ADR-002 contract PRs first** — `:kuilt-core` adds `Rendezvous` + `weave` (+ `host`/`join` wrappers), `InMemoryLoom` migrates; then each fabric's `weave`.
3. **`:kuilt-conformance` reshape PR** — suite `newLoom()`→`newLoomPair()`, driving `weave`/`host`/`join`, + concurrent host/join, migrate `InMemoryLoomConformanceTest` + `NearbyConformanceTest`, update the four doc sites. Kept green; no new fabric tests yet.
4. **Four fabric PRs, ordered by tractability:** webrtc → multipeer → websocket → mdns. (webrtc reuses an existing harness; multipeer needs the real loopback fake; websocket needs concurrent `testApplication` wiring; mdns last because it builds on the websocket pattern plus the direct-advertisement bypass.) Stack or parallelize per the epic's dispatch notes.
