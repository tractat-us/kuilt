# Per-Dial Credential Hook (`Weft<C>`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `KtorClientLoom` and `WebSocketSignalingChannel` a hook to supply a fresh,
suspend-computed value on every dial (including every reconnect) instead of only once at
construction, so a caller can mint a single-use auth ticket that survives kuilt's transparent
reconnect.

**Architecture:** A single-file typealias `Weft<C> = suspend () -> C` in `:kuilt-core` names the
idiom generically. `:kuilt-websocket`'s `KtorClientLoom` and `:kuilt-webrtc`'s
`WebSocketSignalingChannel` each accept one on their constructor, invoked fresh inside their own
`weave()`/`open()`. No changes anywhere else — the existing joiner-resume and cluster-failover
redial loops already hold a reference to the same long-lived `Loom` instance and call it fresh on
every retry, so the hook is automatically re-invoked with zero new plumbing.

**Tech Stack:** Kotlin Multiplatform, Ktor 3.4.3 client (`ktor-client-websockets`,
`ktor-client-core`), Kotlin/Wasm `@JsFun` externals for the browser `WebSocket` API.

**Spec:** `docs/superpowers/specs/2026-07-09-dial-credential-weft-design.md`

## Global Constraints

- `explicitApi()` is enforced — every new public declaration needs an explicit `public` modifier.
- No dependency changes: `Weft<C>` is a typealias only; `:kuilt-websocket` already depends on
  ktor-client-core/ktor-client-websockets; `:kuilt-webrtc` gets no new dependency (percent-encoding
  uses a `@JsFun` binding, matching `WebSocketSignalingChannel`'s existing external-binding style).
- Coroutine tests: `testApplication` (existing convention for `:kuilt-websocket` JVM tests, real
  Ktor test server, no virtual-time concerns here) and plain `kotlin.test.Test` for the pure
  `:kuilt-webrtc` URL-building unit test (no coroutines involved).
- Test methods: no `test` prefix, `@Test` suffices. Multi-assert tests use `assertAll()`
  (`us.tractat.kuilt.test.assertAll`, already used by `KtorClientLoomIdentityTest`).
- Verify with the full module build, not bare `jvmTest` (Android/Native variants can differ):
  `./gradlew :kuilt-core:build :kuilt-websocket:build :kuilt-webrtc:build detektAll --rerun-tasks`.
- Don't touch `KtorMeshClientLoom` (`kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/KtorMeshClientLoom.kt`) — it has its own near-identical `appendPeerQuery` but serves the hub/mesh topology, out of scope for #1330.

---

### Task 1: `:kuilt-core` — the `Weft<C>` idiom

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Weft.kt`
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Loom.kt`

**Interfaces:**
- Produces: `public typealias Weft<C> = suspend () -> C` — consumed by Task 2 (`KtorClientLoom`)
  and Task 3 (`WebSocketSignalingChannel`) as a constructor parameter type.

This is a typealias with no runtime behavior, so there's no failing-test step — the "test" is
that the module compiles and every other module can resolve `us.tractat.kuilt.core.Weft`. Task 2's
first step re-verifies this by actually importing and using it.

- [ ] **Step 1: Create the typealias**

```kotlin
// kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Weft.kt
package us.tractat.kuilt.core

/**
 * A **weft** is the thread woven in fresh on every pass of the shuttle across a [Loom] — here,
 * a per-dial value recomputed on every [Loom.weave] attempt, including every reconnect. Never
 * cached by kuilt: a fabric [Loom] implementation that needs fresh per-dial data invokes this
 * itself, inside its own `weave()`, so the caller's [C] is recomputed on the first dial and on
 * every subsequent redial.
 *
 * Deliberately generic, not shaped around any one use case: a credential that must be refreshed
 * on reconnect (the motivating case — see
 * [#1330](https://github.com/tractat-us/kuilt/issues/1330)) is the first consumer, but any
 * per-dial value a fabric implementation needs recomputed rather than fixed at construction
 * fits the same shape.
 */
public typealias Weft<C> = suspend () -> C
```

- [ ] **Step 2: Cross-reference it from `Loom`'s KDoc**

Edit `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Loom.kt` — add a paragraph after the
existing "single abstract method" paragraph:

```kotlin
 * The single abstract method is [weave]; [host] and [join] are default
 * wrappers. ADR-002.
 *
 * ## Per-dial data
 *
 * A fabric implementation that needs a value recomputed on every dial — most commonly a
 * credential that must be refreshed on reconnect — accepts a [Weft] on its own constructor and
 * invokes it inside [weave]. See [Weft]'s KDoc for the full idiom; see
 * `KtorClientLoom`/`WebSocketSignalingChannel` in `:kuilt-websocket`/`:kuilt-webrtc` for the
 * first concrete uses.
 *
 * ## Usage
```

(This inserts between the existing `ADR-002.` line and the existing `## Usage` heading — don't
duplicate the `## Usage` heading.)

- [ ] **Step 3: Build `:kuilt-core` to confirm it compiles**

Run: `./gradlew :kuilt-core:build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Weft.kt kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Loom.kt
git commit -m "feat(core): Weft<C> — the per-dial-fresh-value idiom"
```

---

### Task 2: `:kuilt-websocket` — `WebSocketDialContext` + `KtorClientLoom` wiring

**Files:**
- Create: `kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketDialContext.kt`
- Modify: `kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/KtorClientLoom.kt`
- Test: Create `kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorClientLoomWeftTest.kt`

**Interfaces:**
- Consumes: `Weft<C>` from Task 1 (`us.tractat.kuilt.core.Weft`).
- Produces: `WebSocketDialContext(queryParams: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap())`;
  `KtorClientLoom`'s new `weft: Weft<WebSocketDialContext>` constructor parameter (defaulted, so
  every existing call site — `KtorClientLoom(httpClient)`, `KtorClientLoom(httpClient, selfPeerId = ...)`
  — keeps compiling unchanged).

- [ ] **Step 1: Write the failing test**

This test exercises both the query-param and header path in one call, using a value containing
`&`/space to prove percent-encoding actually happens (a naive string-concat would corrupt the
query string on `&`). It reuses `KtorClientLoomIdentityTest`'s `testApplication` +
`KtorServerLoom` + `principalExtractor`-as-capture-hook pattern.

```kotlin
// kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorClientLoomWeftTest.kt
package us.tractat.kuilt.websocket

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies #1330's per-dial hook at the [KtorClientLoom] unit level: `weft` is invoked fresh on
 * every [KtorClientLoom.join] call, its [WebSocketDialContext.queryParams] and
 * [WebSocketDialContext.headers] reach the server, and two sequential joins each get their own
 * fresh value rather than a cached one.
 *
 * This does not stand up the `SeamRoom`/`JoinerResumeMachine` reconnect harness — that composition
 * is verified by reading the actual call sites instead (`SeamRoom.kt:134`'s
 * `reweave = { loom.join(tag) }`, invoked fresh by `JoinerResumeMachine.runReconnect` on every
 * retry; see the design doc's "why this needs no changes" section). Calling `join()` twice here
 * exercises the same `weave()` path those retries call — it's a deliberate unit-scope boundary,
 * not a claim that this test alone proves the full reconnect composition.
 */
class KtorClientLoomWeftTest {

    private val serverPath = "/ws/weft-test"

    @Test
    fun `weft query params and headers land on the dial, percent-encoded`() =
        testApplication {
            var capturedQuery: String? = null
            var capturedHeader: String? = null
            val serverLoom = KtorServerLoom(
                application,
                serverPath,
                principalExtractor = { call ->
                    capturedQuery = call.request.queryParameters["ticket"]
                    capturedHeader = call.request.headers["X-Auth"]
                    null
                },
            )
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                weft = {
                    WebSocketDialContext(
                        queryParams = mapOf("ticket" to "abc 123&x"),
                        headers = mapOf("X-Auth" to "bearer-xyz"),
                    )
                },
            )
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                sessionName = "client",
            )

            val (_, clientSeam) = connectPair(serverLoom, advertisement, clientLoom)
            clientSeam.close(CloseReason.Normal)

            assertAll(
                { assertEquals("abc 123&x", capturedQuery, "query param round-trips through percent-encoding") },
                { assertEquals("bearer-xyz", capturedHeader, "header lands on the upgrade request") },
            )
        }

    @Test
    fun `weft is invoked fresh on every join, not cached`() =
        testApplication {
            var callCount = 0
            val seenTickets = mutableListOf<String?>()
            val serverLoom = KtorServerLoom(
                application,
                serverPath,
                principalExtractor = { call ->
                    seenTickets += call.request.queryParameters["ticket"]
                    null
                },
            )
            val clientLoom = KtorClientLoom(
                httpClient = createClient { install(WebSockets) },
                weft = {
                    callCount++
                    WebSocketDialContext(queryParams = mapOf("ticket" to "ticket-$callCount"))
                },
            )
            val advertisement = WebSocketAdvertisement(
                url = "ws://localhost$serverPath",
                serverPeerId = serverLoom.selfPeerId,
                sessionName = "client",
            )

            val (_, firstSeam) = connectPair(serverLoom, advertisement, clientLoom)
            firstSeam.close(CloseReason.Normal)

            val (_, secondSeam) = connectPair(serverLoom, advertisement, clientLoom)
            secondSeam.close(CloseReason.Normal)

            assertAll(
                { assertEquals(2, callCount, "weft invoked once per join attempt, including the redial") },
                { assertEquals(listOf("ticket-1", "ticket-2"), seenTickets, "server saw a fresh ticket on each dial") },
            )
        }

    // ── Helper (mirrors KtorClientLoomIdentityTest's connectPair) ──────────────
    private suspend fun connectPair(
        serverLoom: KtorServerLoom,
        advertisement: WebSocketAdvertisement,
        clientLoom: KtorClientLoom,
        timeoutMs: Long = 5_000,
    ): Pair<Seam, Seam> = withTimeout(timeoutMs) {
        coroutineScope {
            val serverLinkDeferred = async { serverLoom.nextLink() }
            val clientLink = clientLoom.join(advertisement)
            serverLinkDeferred.await() to clientLink
        }
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :kuilt-websocket:jvmTest --tests "*KtorClientLoomWeftTest"`
Expected: FAIL — compile error, `WebSocketDialContext` is unresolved and `KtorClientLoom` has no
`weft` parameter.

- [ ] **Step 3: Create `WebSocketDialContext`**

```kotlin
// kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketDialContext.kt
package us.tractat.kuilt.websocket

/**
 * Per-dial request decoration for [KtorClientLoom], supplied fresh on every attempt by a
 * [us.tractat.kuilt.core.Weft]. Named for what it carries generically, not for the one use case
 * (auth) that motivated it — [queryParams]/[headers] are equally at home carrying a trace id or
 * a client-version header as a ticket.
 *
 * @property queryParams Appended to the dial URL alongside the existing `?peer=` query param,
 *   percent-encoded.
 * @property headers Set on the WebSocket-upgrade HTTP request.
 */
public data class WebSocketDialContext(
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)
```

- [ ] **Step 4: Wire `weft` into `KtorClientLoom`**

Replace the full contents of
`kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/KtorClientLoom.kt` with:

```kotlin
package us.tractat.kuilt.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import us.tractat.kuilt.core.Loom
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Weft
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Client-side [Loom] backed by Ktor WebSockets.
 *
 * [join] connects directly to a [WebSocketAdvertisement] URL and returns a
 * 2-peer [WebSocketSeam] — no intermediate contract [Session] adapter.
 *
 * **Pairing:** `KtorClientLoom` ↔ [KtorServerLoom]/[KtorRoomHost] — a 2-peer relay with **no**
 * in-band handshake. It is **not** the client for a [us.tractat.kuilt.core.MuxServerLoom] hub:
 * that hub handshakes every spoke with an in-band `MeshHello` preamble a [WebSocketSeam] never
 * sends, so pointing this loom at one silently never completes admit. Use [KtorMeshClientLoom]
 * (hub-spoke mesh) for a `MuxServerLoom` hub.
 *
 * **PeerId discovery:**
 *  - Client's own [PeerId] is fixed at construction as [selfPeerId] and
 *    appended as `?peer=<id>` on every join so the server can read it.
 *  - Server's [PeerId] comes from [WebSocketAdvertisement.serverPeerId].
 *
 * **Stable identity across reconnects:** supplying [selfPeerId] gives this loom
 * a fixed fabric identity reused on every call to [weave]/[join]. This is required
 * for cluster-client failover: the server derives a learner [NodeId] from the
 * admitted [PeerId]; if a reconnect mints a new random id the server admits a
 * different learner and Raft routing breaks. The default mints a fresh random
 * identity per loom instance (mirroring the old per-join behaviour for callers
 * that do not need stable identity). See [#544](https://github.com/tractat-us/kuilt/issues/544).
 *
 * **Per-dial credentials:** [weft] is invoked fresh inside every [weave] call — the first dial
 * and every subsequent redial — so a caller can mint a single-use credential (e.g. a short-lived
 * WS ticket) that survives kuilt's transparent reconnect instead of being baked once into a
 * static [WebSocketAdvertisement.url]. See
 * [#1330](https://github.com/tractat-us/kuilt/issues/1330).
 *
 * **HttpClient lifecycle:** the [httpClient] is not closed by this loom.
 * Callers are responsible for closing it when all connections are done.
 *
 * @param dispatcher Scheduler for the per-connection seam's read/write loops; the loom
 *   confines it to a single thread via `limitedParallelism(1)`. Production default is
 *   [Dispatchers.Default]; tests inject [kotlinx.coroutines.test.UnconfinedTestDispatcher].
 * @param selfPeerId The fabric identity this loom presents on every join. Defaults to a
 *   random UUID minted once at construction; supply a deterministic value for stable
 *   cluster-client identity across reconnects.
 * @param weft Supplies a [WebSocketDialContext] fresh on every dial. Defaults to an empty
 *   context (no extra query params/headers).
 */
@OptIn(ExperimentalUuidApi::class)
public class KtorClientLoom(
    private val httpClient: HttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val selfPeerId: PeerId = PeerId(Uuid.random().toString()),
    private val weft: Weft<WebSocketDialContext> = { WebSocketDialContext() },
) : Loom {
    /**
     * Establishes a [Seam]:
     * - [Rendezvous.New] — not meaningful for a client; throws [UnsupportedOperationException].
     * - [Rendezvous.Existing] — connects to the [WebSocketAdvertisement] URL and returns a 2-peer [Seam].
     *
     * @throws UnsupportedOperationException for [Rendezvous.New].
     * @throws IllegalArgumentException if the tag is not a [WebSocketAdvertisement].
     */
    override suspend fun weave(rendezvous: Rendezvous): Seam =
        when (rendezvous) {
            is Rendezvous.New ->
                throw UnsupportedOperationException(
                    "KtorClientLoom does not open sessions. " +
                        "Use join(WebSocketAdvertisement) to connect to an existing server.",
                )
            is Rendezvous.Existing -> {
                val advertisement = rendezvous.tag
                require(advertisement is WebSocketAdvertisement) {
                    "KtorClientLoom only joins WebSocketAdvertisement, got ${advertisement::class}"
                }
                val dialContext = weft()
                // PEER_QUERY_PARAM is set last so it always wins if dialContext.queryParams
                // happens to contain a "peer" key — the fabric identity contract (#544) is not
                // something a credential weft should be able to silently override.
                val queryParams = linkedMapOf<String, String>()
                queryParams.putAll(dialContext.queryParams)
                queryParams[PEER_QUERY_PARAM] = selfPeerId.value
                val urlWithPeer = appendQueryParams(advertisement.url, queryParams)
                val wsSession =
                    httpClient.webSocketSession(urlWithPeer) {
                        dialContext.headers.forEach { (key, value) -> header(key, value) }
                    }
                WebSocketSeam(
                    selfId = selfPeerId,
                    remoteId = advertisement.serverPeerId,
                    session = wsSession,
                    dispatcher = dispatcher.limitedParallelism(1),
                )
            }
        }

    private fun appendQueryParams(
        url: String,
        params: Map<String, String>,
    ): String {
        if (params.isEmpty()) return url
        val separator = if ('?' in url) "&" else "?"
        val encoded =
            params.entries.joinToString("&") { (key, value) ->
                "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
            }
        return "$url$separator$encoded"
    }
}
```

Note: `appendQueryParams` replaces the old `appendPeerQuery` — it's the same function generalized
to merge the `?peer=` param with `weft`'s query params in one pass, and it now percent-encodes
(the old version didn't need to — a UUID `peerId` has no special characters — but an arbitrary
credential value does).

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :kuilt-websocket:jvmTest --tests "*KtorClientLoomWeftTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: Run the full existing `:kuilt-websocket` test suite to confirm no regression**

Run: `./gradlew :kuilt-websocket:jvmTest`
Expected: PASS (all tests, including `KtorClientLoomIdentityTest`, `WebSocketConformanceTest`,
`KtorConnectionSourceAttestationTest`)

- [ ] **Step 7: Commit**

```bash
git add kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/WebSocketDialContext.kt kuilt-websocket/src/commonMain/kotlin/us/tractat/kuilt/websocket/KtorClientLoom.kt kuilt-websocket/src/jvmTest/kotlin/us/tractat/kuilt/websocket/KtorClientLoomWeftTest.kt
git commit -m "feat(websocket): per-dial WebSocketDialContext via Weft on KtorClientLoom"
```

---

### Task 3: `:kuilt-webrtc` — `WebSocketSignalingChannel` wiring

**Files:**
- Modify: `kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannel.kt`
- Test: Create `kuilt-webrtc/src/wasmJsTest/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannelUrlTest.kt`

**Interfaces:**
- Consumes: `Weft<C>` from Task 1.
- Produces: `WebSocketSignalingChannel`'s new `weft: Weft<Map<String, String>>` constructor
  parameter (defaulted — the existing `WebSocketSignalingChannel(baseUrl)` call sites keep
  compiling); `internal fun buildSignalingUrl(baseUrl, room, queryParams): String`, the pure,
  directly-testable piece of the dial-URL construction.

No headers/subprotocol here, by design: the browser `WebSocket` constructor (`new WebSocket(url)`)
cannot set custom request headers — only the URL is available to JS. `Map<String, String>` (query
params only) is the honest ceiling of what this channel can carry.

- [ ] **Step 1: Write the failing test**

This tests the pure URL-building function directly — no real WebSocket/browser needed — which is
where the actual correctness risk lives (merging into the existing `$baseUrl/signaling/$room`
shape, percent-encoding). `WebSocketSignalingChannel.open()` itself isn't independently retested
here: it becomes a one-line call to this already-proven function.

```kotlin
// kuilt-webrtc/src/wasmJsTest/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannelUrlTest.kt
package us.tractat.kuilt.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies #1330's per-dial hook for [WebSocketSignalingChannel]: [buildSignalingUrl] merges a
 * [us.tractat.kuilt.core.Weft]-supplied query-param map onto the signaling URL, percent-encoded.
 */
class WebSocketSignalingChannelUrlTest {
    @Test
    fun noQueryParamsLeavesTheUrlUnchanged() {
        assertEquals(
            "https://example.com/signaling/room-1",
            buildSignalingUrl("https://example.com", "room-1", emptyMap()),
        )
    }

    @Test
    fun queryParamsArePercentEncodedAndAppended() {
        assertEquals(
            "https://example.com/signaling/room-1?ticket=abc%20123%26x",
            buildSignalingUrl("https://example.com", "room-1", mapOf("ticket" to "abc 123&x")),
        )
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :kuilt-webrtc:wasmJsTest --tests "*WebSocketSignalingChannelUrlTest*"`
Expected: FAIL — compile error, `buildSignalingUrl` is unresolved.

- [ ] **Step 3: Extract `buildSignalingUrl` and wire `weft` into `open()`**

This is a full-file replacement below — before applying it, diff it mentally against the file's
*current* contents (`git show HEAD:kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannel.kt`)
rather than pasting it blindly. The listing below is only the four changes described in this
task (the `weft` param, the `buildSignalingUrl` extraction, the `jsEncodeURIComponent` binding,
and the `Weft` import) — everything else must match the current file verbatim, in particular the
`runCatchingCancellable` call in `wsSetOnMessage` (not bare `runCatching` — this repo's exception
discipline requires it).

Edit `kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannel.kt`:

```kotlin
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package us.tractat.kuilt.webrtc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import us.tractat.kuilt.core.Weft
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.JsFun
import kotlin.js.JsAny

private val log = KotlinLogging.logger("us.tractat.kuilt.webrtc.WebSocketSignalingChannel")

/**
 * [SignalingChannel] backed by a browser `WebSocket`.
 *
 * Each [open] call dials `${baseUrl}/signaling/${room}` (with `wss://` if
 * the page is `https`, else `ws://`). The first impl of the server-side
 * route is `WS /signaling/{room}` in `:server`.
 *
 * **Per-dial credentials:** [weft] is invoked fresh inside every [open] call, so a caller can
 * mint a single-use credential (e.g. a short-lived ticket) as a query param rather than baking
 * one into a static [baseUrl]. Query params only — the browser `WebSocket` constructor cannot
 * set custom request headers. See [#1330](https://github.com/tractat-us/kuilt/issues/1330).
 */
public class WebSocketSignalingChannel(
    private val baseUrl: String,
    private val weft: Weft<Map<String, String>> = { emptyMap() },
) : SignalingChannel {
    override suspend fun open(room: String): SignalingSession {
        val url = buildSignalingUrl(baseUrl, room, weft())
        log.debug { "signaling open → $url" }
        val ws = createWebSocket(url)
        val openDeferred = CompletableDeferred<Unit>()
        val incoming = Channel<SignalingMessage>(Channel.UNLIMITED)

        wsSetOnOpen(ws) {
            log.debug { "signaling ws.onopen room=$room" }
            openDeferred.complete(Unit)
        }
        wsSetOnError(ws) {
            log.debug { "signaling ws.onerror room=$room openCompleted=${openDeferred.isCompleted}" }
            if (!openDeferred.isCompleted) {
                openDeferred.completeExceptionally(
                    IllegalStateException("Signaling WebSocket open failed"),
                )
            }
            incoming.close()
        }
        wsSetOnClose(ws) {
            log.debug { "signaling ws.onclose room=$room — closing inboundChannel" }
            incoming.close()
        }
        wsSetOnMessage(ws) { text ->
            runCatchingCancellable { SignalingMessageCodec.decode(text) }
                .onSuccess { msg ->
                    log.debug { "signaling ws.onmessage room=$room type=${msg::class.simpleName}" }
                    incoming.trySend(msg)
                }
        }

        openDeferred.await()
        log.debug { "signaling open complete room=$room" }
        return BrowserWebSocketSession(ws, incoming)
    }

    /**
     * Opens a session and awaits the server-assigned [SignalingMessage.Role] frame.
     * Returns the role alongside the session. The role frame is consumed
     * from the underlying channel before returning; the session's [SignalingSession.incoming]
     * flow starts at the first offer/answer/ICE frame.
     *
     * Unlike [open], this method uses [Channel.receive] directly so the rest of
     * [SignalingSession.incoming] remains collectible. (Calling [kotlinx.coroutines.flow.first]
     * on a [receiveAsFlow] / [kotlinx.coroutines.flow.consumeAsFlow] would cancel the
     * underlying channel.)
     *
     * Use this when both tabs are symmetric peers and the relay breaks the host/joiner
     * tie. See [WebRTCPeerLinkFactory.openWithServerRole].
     */
    public suspend fun openWithRole(room: String): Pair<Boolean, SignalingSession> {
        val session = open(room) as BrowserWebSocketSession
        log.debug { "signaling openWithRole room=$room — awaiting Role frame via receive()" }
        val role =
            (session.inboundChannel.receive() as? SignalingMessage.Role)
                ?: error("Expected Role frame as first message from signaling relay")
        log.debug { "signaling openWithRole room=$room — Role received isHost=${role.host}" }
        return role.host to session
    }
}

/** Pure URL-building for [WebSocketSignalingChannel.open] — no browser API needed, directly testable. */
internal fun buildSignalingUrl(
    baseUrl: String,
    room: String,
    queryParams: Map<String, String>,
): String {
    val base = "$baseUrl/signaling/$room"
    if (queryParams.isEmpty()) return base
    val encoded =
        queryParams.entries.joinToString("&") { (key, value) ->
            "${jsEncodeURIComponent(key)}=${jsEncodeURIComponent(value)}"
        }
    return "$base?$encoded"
}

private class BrowserWebSocketSession(
    private val ws: JsAny,
    val inboundChannel: Channel<SignalingMessage>,
) : SignalingSession {
    override val incoming: Flow<SignalingMessage> = inboundChannel.receiveAsFlow()

    override suspend fun send(message: SignalingMessage) {
        wsSend(ws, SignalingMessageCodec.encode(message))
    }

    override suspend fun close() {
        log.debug { "signaling session.close() — wsClose + inboundChannel.close" }
        wsClose(ws)
        inboundChannel.close()
    }
}

// ── Browser WebSocket bindings ─────────────────────────────────────────────────
// org.w3c.dom.WebSocket is a JS-target type; in Kotlin/Wasm we declare externals
// ourselves and use @JsFun wrappers to avoid extension-receiver restrictions.

@JsFun("(url) => new WebSocket(url)")
private external fun createWebSocket(url: String): JsAny

@JsFun("(ws, handler) => { ws.onopen = () => handler(); }")
private external fun wsSetOnOpen(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onerror = () => handler(); }")
private external fun wsSetOnError(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onclose = () => handler(); }")
private external fun wsSetOnClose(
    ws: JsAny,
    handler: () -> Unit,
)

@JsFun("(ws, handler) => { ws.onmessage = (event) => handler(event.data); }")
private external fun wsSetOnMessage(
    ws: JsAny,
    handler: (String) -> Unit,
)

@JsFun("(ws, text) => ws.send(text)")
private external fun wsSend(
    ws: JsAny,
    text: String,
)

@JsFun("(ws) => ws.close()")
private external fun wsClose(ws: JsAny)

@JsFun("(s) => encodeURIComponent(s)")
private external fun jsEncodeURIComponent(s: String): String
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :kuilt-webrtc:wasmJsTest --tests "*WebSocketSignalingChannelUrlTest*"`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the full existing `:kuilt-webrtc` wasmJs test suite to confirm no regression**

Run: `./gradlew :kuilt-webrtc:wasmJsTest`
Expected: PASS (all tests, including `WebRTCPeerLinkFactoryTest`, `WebRTCConformanceTest`)

- [ ] **Step 6: Commit**

```bash
git add kuilt-webrtc/src/wasmJsMain/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannel.kt kuilt-webrtc/src/wasmJsTest/kotlin/us/tractat/kuilt/webrtc/WebSocketSignalingChannelUrlTest.kt
git commit -m "feat(webrtc): per-dial query params on WebSocketSignalingChannel via Weft"
```

---

### Task 4: Full-repo verification + follow-up issue for Multipeer/Nearby

**Files:** none (verification + a GitHub issue, no code)

- [ ] **Step 1: Full build, cache-disabled, for every touched module**

Run: `./gradlew :kuilt-core:build :kuilt-websocket:build :kuilt-webrtc:build detektAll --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tasks `EXECUTED` (not `FROM-CACHE`) — confirms the Android/Native
variants of `:kuilt-websocket` (commonMain touches all "all"-target platforms) and the wasmJs
target of `:kuilt-webrtc` actually compile, not just the JVM tests run above.

- [ ] **Step 2: File the Multipeer/Nearby follow-up issue**

The design's cross-fabric survey found Apple Multipeer's `invitePeer(withContext:)` and Android
Nearby's `requestConnection(endpointInfo:)` have the identical unused per-dial admission slot —
same shape of gap, no current consumer. File it now rather than let the finding evaporate:

```bash
gh issue create \
  --title "kuilt-multipeer/kuilt-nearby: unused per-dial admission slots (withContext/endpointInfo) — same gap as #1330" \
  --label needs-design \
  --body "$(cat <<'EOF'
> 🤖 This issue was generated by Claude on behalf of @keddie.

## Gap

While scoping #1330 (per-dial credential hook on `KtorClientLoom`/`WebSocketSignalingChannel`),
a cross-fabric survey found the identical latent gap in two other fabrics — no current consumer
asking for it, filed here so the finding isn't lost:

- **Apple Multipeer** (`kuilt-multipeer`): `MultipeerPeerLinkFactory`'s join path calls
  `activeBrowser.invitePeer(peerID, toSession, withContext = null, timeout = ...)`. Multipeer's
  own `invitePeer` API has a `withContext` payload slot for exactly this purpose — kuilt just
  hardcodes it to `null`.
- **Android Nearby** (`kuilt-nearby`): `NearbyLoom.joinSession` calls
  `api.requestConnection(advertisement.sessionName, hostEndpointId)`. GMS Nearby's own
  `requestConnection` supports an app-supplied `endpointInfo` byte blob analogous to Multipeer's
  `withContext` — unused here too.

Both are real pre-connect admission moments, structurally identical to the WS upgrade #1330
fixes, just via a different platform primitive.

## Ask

When a consumer needs per-dial credentials on Multipeer or Nearby, follow #1330's
`Weft<C>`-on-constructor pattern (see `docs/superpowers/specs/2026-07-09-dial-credential-weft-design.md`):
a `weft: Weft<C>` constructor parameter on `MultipeerPeerLinkFactory`/`NearbyLoom`, with a
fabric-specific payload type analogous to `WebSocketDialContext`, invoked fresh inside each
fabric's join path.

## Done when

A consumer can supply a per-dial `Weft`-computed value to `MultipeerPeerLinkFactory` and/or
`NearbyLoom`, invoked fresh on first dial and every reconnect — built when an actual consumer
needs it, not speculatively.
EOF
)"
```

Expected: a new issue is created; note its number for the PR body.

---

## Execution notes for the implementer

- Tasks 2 and 3 are independent of each other (different modules, no shared files) and can run in
  parallel once Task 1 is committed.
- Task 4 depends on Tasks 2 and 3 both being committed (it builds every touched module together).
- Open one PR for the whole change (small, cross-module, all-or-nothing per the design) rather
  than three separate PRs — matches kuilt's "aggressive, low-ceremony merging" bias for pre-1.0.
