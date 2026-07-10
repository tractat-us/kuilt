# Per-dial credential hook on client Looms (`Weft<C>`)

**Issue:** [#1330](https://github.com/tractat-us/kuilt/issues/1330) — `KtorClientLoom` and
`WebSocketSignalingChannel` dial with a URL fixed at construction/`Tag` time. There is no hook
to attach a credential to the WS upgrade, and critically no hook to **refresh it per dial** —
kuilt's transparent reconnect (#1045) re-dials the same static `Tag`, so a single-use ticket
baked into `WebSocketAdvertisement.url` is already consumed on the first redial attempt.

## Cross-fabric scope check

Before designing WS-specific plumbing: is this gap unique to WebSocket? No. Every client-join
path across every fabric replays a static `Tag` with zero per-attempt input:

- `TcpLoom.weave` dials the bare address — no upgrade moment at all (auth is pushed in-band,
  post-connect, via `handshaking()`).
- `KtorClientLoom.weave` / `WebSocketSignalingChannel.open` — the HTTP-upgrade moment #1330
  names.
- Apple Multipeer's `invitePeer(peerID, toSession, withContext:)` and Android Nearby's
  `requestConnection(endpointInfo:)` both have a real pre-connect admission payload slot,
  currently hardcoded to `null`/unused — the identical shape of gap, via a different platform
  primitive.
- mDNS discovery converts straight to a `WebSocketAdvertisement` and delegates to
  `KtorClientLoom` — it inherits whatever fix lands there for free.

So the hook's *shape* belongs in `:kuilt-core` as a named idiom, even though the credential
*payload* is necessarily fabric-specific (a WS query map isn't an Apple `Data` blob isn't an
Android byte array) and `:kuilt-core` must stay free of fabric-specific imports.

## Design

### §1 `:kuilt-core` — `Weft<C>`

A **weft** is the thread woven in fresh on every pass of the shuttle across the loom. Here:
a per-dial value recomputed on every [`Loom.weave`] attempt, including every reconnect —
never cached by kuilt.

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Weft.kt
public typealias Weft<C> = suspend () -> C
```

One file, one typealias, no fabric knowledge. Deliberately not named or shaped around
credentials specifically: `C` is whatever a fabric `Loom` implementation needs fresh on every
attempt. Credentials are the motivating case for #1330, not the only one — a consumer could
equally use a `Weft` to attach a per-dial trace/correlation id, a client-version header, or any
other value that must be recomputed rather than fixed at construction. `Loom`'s KDoc gains a
short cross-reference to this idiom.

### §2 `:kuilt-websocket` — `KtorClientLoom`

```kotlin
// new file: WebSocketDialContext.kt
public data class WebSocketDialContext(
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)
```

Named for what it carries generically (per-dial request decoration), not for the one use case
(auth) that motivated it — `queryParams`/`headers` are equally at home carrying a trace id or a
client-version header as a ticket.

`KtorClientLoom` gains a constructor parameter:

```kotlin
public class KtorClientLoom(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val selfPeerId: PeerId = PeerId(Uuid.random().toString()),
    private val weft: Weft<WebSocketDialContext> = { WebSocketDialContext() },
) : Loom
```

`weave()` calls `weft()` first, merges `queryParams` into the dial URL (percent-encoded via
ktor's URL-encoding helpers — the existing `appendPeerQuery` doesn't encode today because a UUID
`peerId` has no special characters, but an arbitrary ticket value needs real encoding), and
passes `headers` into the `httpClient.webSocketSession(url) { header(k, v) }` request-builder
block ktor's `webSocketSession` overload already accepts.

### §3 `:kuilt-webrtc` — `WebSocketSignalingChannel`

```kotlin
public class WebSocketSignalingChannel(
    private val baseUrl: String,
    private val weft: Weft<Map<String, String>> = { emptyMap() },
) : SignalingChannel
```

`open()` calls `weft()`, percent-encodes the result (a small `encodeURIComponent` `@JsFun`
binding, matching the file's existing external-binding style — there's no ktor-http on this
target) and appends it as a query string before `createWebSocket(url)`.

**No headers here, by design, not oversight.** The browser `WebSocket` constructor
(`new WebSocket(url)`) cannot set custom request headers — only the URL and a `protocols` array
are available to JS. `Map<String, String>` (query params only) is the honest ceiling of what
this channel can carry; there is no `WebSocketDialContext`-shaped symmetry to chase here, and
introducing one would just be an unusable `headers` field. This is also why the fireworks
device-auth design's *preferred* scheme is ticket-in-query in the first place — it already
avoids the header/subprotocol path everywhere, not just here.

### Why this needs no changes to `:kuilt-session` or `:kuilt-cluster`

The joiner auto-resume path (`JoinerResumeMachine`, from #1045) already builds
`reweave = { loom.join(tag) }` and calls it fresh on every retry within the reconnect window;
`ClusterClient`'s failover loop already calls `factory.join(reconnect.currentEndpoint())` fresh
on every tear. Both hold a reference to the *same, long-lived* `Loom` instance across every
retry. Because `weft` lives on that `Loom`'s constructor — not on the `Tag`, not threaded through
`join(tag)` — it is already in scope for every `weave()` call those existing loops make. No
public API in `:kuilt-session`/`:kuilt-cluster` changes; a consumer solves the whole problem by
constructing `KtorClientLoom(client, weft = { mintTicket() })` once.

### Approaches considered and rejected

- **Provider on `Tag`/`WebSocketAdvertisement`** (closer to the issue's literal wording): rejected
  — `WebSocketAdvertisement` is a public `data class`; a function-typed field breaks
  `equals`/`hashCode`/`toString` (two adverts with different closures for the same URL wouldn't
  be `==`), and mDNS's advertisement conversion would need to re-thread it.
- **Payload slot on `Rendezvous.Existing`**: rejected — would need an untyped `Any` payload
  (fabrics' credential shapes genuinely differ) and, because `Rendezvous.Existing(tag)` is
  rebuilt fresh from the caller's `tag` argument on every `join(tag)` call, would additionally
  require extending `RoomFactory`/`ClusterClient`'s public `join(tag)` signatures to thread a
  provider through — touching two more modules for no benefit over the constructor-level design.

## Testing

- `KtorClientLoom`: a mocked-engine test asserting `weft`'s `WebSocketDialContext.queryParams`/
  `headers` land on the outgoing request.
- A redial test (the issue's explicit "done when" bar): reusing the existing joiner-resume test
  harness, assert `weft` is invoked more than once across a tear + reconnect cycle.
- `WebSocketSignalingChannel`: equivalent query-string assertion on the wasmJs test target.
  Redial coverage here depends on whether an existing reconnect harness already exercises the
  WebRTC signaling path — confirm during planning rather than assume.

## Scope & non-goals

- **In scope:** `KtorClientLoom` (`:kuilt-websocket`) and `WebSocketSignalingChannel`
  (`:kuilt-webrtc`), matching #1330's stated "done when" bar. mDNS-discovered joins get the fix
  for free (they delegate to `KtorClientLoom`).
- **Out of scope, follow-up issue:** Multipeer (`invitePeer(withContext:)`) and Nearby
  (`requestConnection(endpointInfo:)`) have the identical latent gap but no current consumer
  asking for it — file a follow-up issue documenting the two unused platform slots rather than
  building speculative hooks now.
- **Out of scope, deliberately:** WS subprotocol-based credentials. The referenced device-auth
  design explicitly avoids the `Sec-WebSocket-Protocol` echo requirement by using ticket-in-query
  instead; adding subprotocol support would be unused complexity.
- **No `:kuilt-core` behavior change** — `Weft<C>` is a typealias, zero runtime surface.
  `explicitApi()`: new public typealias in `:kuilt-core`, new public `WebSocketDialContext` data
  class and constructor parameter in `:kuilt-websocket`, new constructor parameter in
  `:kuilt-webrtc`. All backward-compatible (defaulted, non-breaking).
- **Verify cache-disabled before merge:** `./gradlew :kuilt-core:build :kuilt-websocket:build
  :kuilt-webrtc:build detektAll --rerun-tasks`.
