# Fabrics

A fabric is the part of kuilt that actually moves bytes between two devices — over the
internet, over a local network, over Bluetooth or a direct cable — so everything built on
top of it (rooms, shared data, turns) works the same no matter which connection two devices
happen to share. This is where to look for finding other devices in the first place, and for
building a fabric of your own when kuilt doesn't already ship the one you need.

## Discovery

**Intent:** merge several `PeerDiscoverySource` feeds (mDNS, Multipeer, …) into one live roster for a lobby UI — "who can I currently see?"
**Primitive:** `discoveryRoster(sources, scope)` (`us.tractat.kuilt.core.discovery`). Folds `discoveries()` minus `departures()`, keyed on `Tag.peerKey`, into one `StateFlow<Set<Tag>>`. Don't hand-roll the merge.

It returns only **this peer's current best view** — not an agreement. It is **not** an election input: pick a host from `Seam.peers` once connected, never from this roster. And note the ghost caveat — a source whose `departures()` returns `emptyFlow()` is add-only, so departed peers linger forever. There is no interface default for `departures()`: a source with no leave signal has to write the `emptyFlow()` out, so you can tell which of your sources the caveat applies to by reading them.

**One transport dying costs you that transport, not the roster.** Each source's `discoveries()` and `departures()` is isolated separately, so a feed that throws mid-session just stops reporting while every other source carries on — don't wrap the sources in your own try/catch or `SupervisorJob`. `:kuilt-core` is logger-free, so pass `onSourceFailure` if you want to know which transport died; that callback is the only signal there is, and the throwable's trace names the feed. The dead feed's peers **linger** — nothing observed them leave, so it is the ghost caveat by a second route, not a claim anyone is still reachable.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/discovery/DiscoverySamples.kt#sampleDiscoveryRoster -->
```kotlin
// One StateFlow the lobby UI renders directly — no hand-rolled merge.
val roster = discoveryRoster(listOf(mdns, multipeer), backgroundScope)
runCurrent()

mdnsPeers.emit(InMemoryTag("alice"))
mdnsPeers.emit(InMemoryTag("bob"))
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("alice", "bob"))

// A departure removes the peer, keyed on Tag.peerKey.
mdnsGone.emit("alice")
runCurrent()
check(roster.value.map { it.peerKey }.toSet() == setOf("bob"))
```

### A peer id off the wire, straight into a `Set<PeerId>`

**Intent:** you are writing a fabric, and a remote has just told you who it is — a display name, a handshake payload, a native callback's string. You are about to write `PeerId(bytes.decodeToString())` and `peers.update { it + peer }`, and decide the session is over when `peers == setOf(selfId)`.

**Primitive:** `PeerIdentityRegistry` (`:kuilt-core`). Those bytes are the least trustworthy input a fabric handles, and every path that judged them for itself judged them differently — three times, over #1432, #1466, #1494 and #1821. Key membership by the underlying **device identity** and ask the registry:

<!-- verbatim from kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/PeerIdentityRegistry.kt#bind -->
```kotlin
public fun bind(
    id: PeerId,
    token: T,
): BindResult =
    lock.withLock {
        when {
            id.value.isBlank() -> BindResult.REFUSED_BLANK
            id == selfId -> BindResult.REFUSED_SELF
            else ->
                when (bound[id]) {
                    null -> {
                        bound[id] = token
                        BindResult.BOUND
                    }
                    token -> BindResult.ALREADY_BOUND
                    else -> BindResult.COLLISION
                }
        }
    }
```

Derive the roster from `registry.peers + selfId`, evict with the identity-scoped `unbind(id, token)` so a drop can only ever remove the device that actually holds the id, and `clear()` at teardown so a post-tear callback cannot recompute the roster from stale bindings.

Three things a bare set gets wrong, each of which has shipped:

- **Two devices on one id merge, then one drop evicts both.** A set has one entry; whoever leaves first takes the other with them. `COLLISION` refuses the newcomer instead, and the incumbent keeps the id.
- **A peer registers *itself*.** A symmetric advertise+browse fabric is handed its own advertisement, dials it, and the eventual drop of that self-link evicts the peer from its own roster — the #1466 signature. `REFUSED_SELF`.
- **A blank id is unaddressable and wedges teardown.** `PeerId("")` in the roster makes a `remaining == setOf(selfId)` end-of-session test unsatisfiable forever, so the seam never tears, `incoming` never completes, and the session slot is never freed. `REFUSED_BLANK` — and ask `registry.peers.isEmpty()` rather than comparing a set that peer-supplied strings can pollute.

**Decoding is still yours.** The registry judges an id, not bytes: `ByteArray.decodeToString()` defaults to *lossy*, mapping every malformed sequence to U+FFFD, so two entirely different announcements arrive as the same id and there is nothing left for a collision check to see. Decode with `throwOnInvalidSequence = true` and refuse what does not decode.

**The one thing it cannot do** is un-merge what the layer beneath already merged. If your transport hands up only the id string with no device handle to key by, pass the id as its own token: you still get the refusals and the identity-scoped eviction, but `COLLISION` is unreachable and closing it means changing the layer below. Say so where a reader will hit it, rather than leaving the arm looking covered.

### Peers pile up and are never removed

**Intent:** the list of nearby games only ever grows. Someone closes the app, walks out of the building, or turns their Wi-Fi off, and their name stays on the screen; tapping it connects to nothing. You are about to write a sweeper that quietly drops anyone you have not heard from in thirty seconds.
**Primitive:** don't — the removal already exists. `discoveryRoster` drops a peer the instant one of its sources emits that peer's `Tag.peerKey` from `departures()`, so a roster that only grows means some source never emits. Read each source's `departures()` body: there is **no interface default**, so a source with no leave signal has the `emptyFlow()` written out where you can see it, and those sources — only those — are the ones the ghost caveat covers.

If you own the source, `departures()` is the thing to fix and `DiscoverySourceConformanceSuite` (`:kuilt-conformance`) is how you find out whether it works — binding an existing backend to it is also how you learn what it does *not* yet do, since each backend's own conformance test is where its standing is recorded. Subclass, say how a peer arrives, and declare the leave signal: `DepartureFixture.Emits { … }` when there is one, `DepartureFixture.NoLeaveSignal` when there honestly isn't. The second arm is a claim the suite then checks, not an exemption — a source that declares it and emits anything at all fails.

<!-- verbatim from kuilt-conformance/src/commonTest/kotlin/us/tractat/kuilt/conformance/ReferenceDiscoverySourceConformanceTest.kt#ReferenceDiscoverySourceConformanceTest -->
```kotlin
class ReferenceDiscoverySourceConformanceTest : DiscoverySourceConformanceSuite() {
    override fun newSource(): PeerDiscoverySource = ReferenceDiscoverySource()

    override suspend fun causeArrival(source: PeerDiscoverySource) {
        (source as ReferenceDiscoverySource).advertise()
    }

    override fun departureFixture(source: PeerDiscoverySource): DepartureFixture =
        DepartureFixture.Emits { (source as ReferenceDiscoverySource).withdraw() }
}
```

Two traps that shipped here, and that the suite exists to catch. **Emitting *something* is not enough** — the roster removes by exact key, so a departure carrying a display name, a socket address, or another transport's handle leaves precisely the same ghost while looking correct in a log. And **a leave signal that only works while somebody is watching arrivals is not a leave signal** — `discoveryRoster` merges the two feeds, and `merge` subscribes to inner flows in separately launched coroutines, so a departure feed fed off the `discoveries()` session can lose the event to its own sibling.

The reason this is more than a missing `override`: on every mDNS platform the goodbye carries only the service **name**, never the TXT map the peer id lives in. Read the id off the removal event and you get null, on all three backends. The peer id has to be remembered at resolution time and looked up when the name goes away — which also means a service that never resolved emits nothing, exactly as it emitted nothing on arrival.

## Your own transport

You already have a way for two machines to talk — a TCP socket, an in-house RPC, a serial
link, something a customer insists on — and you want a kuilt room on top of it: a roster,
reconnect, shared state. You are about to write a length prefix, a read loop that
reassembles it, and a small "who are you?" exchange so each end learns the other's name.

**Intent:** turn a byte stream you already have into a `Seam`.
**Primitive:** `framed(source, sink)` (`:kuilt-stream`), then `handshaking(connection, selfId, dispatcher)`
(`:kuilt-core`). Between them that is the whole bridge; the only transport-specific code left is your
own connect/accept. `framed` wraps a kotlinx-io `Source`/`Sink` as a `Connection` with a 4-byte
big-endian length prefix per frame, and `handshaking` negotiates identity in-band and hands back a
2-peer `Seam` — after which `Room`, `Quilter` and `GameSession` all work unchanged, because they only
ever knew about `Seam`.

<!-- verbatim from kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/ProprietaryRpcExampleTest.kt#ProprietaryRpcExampleTest -->
```kotlin
private fun rpcConnection(socket: Socket): Connection =
    framed(
        source = socket.getInputStream().asSource().buffered(),
        sink = socket.getOutputStream().asSink().buffered(),
    )

/** Wrap a connected socket as a 2-peer kuilt [Seam], identity negotiated in-band. */
private suspend fun weaveSeam(socket: Socket, selfId: PeerId): Seam =
    handshaking(rpcConnection(socket), selfId, Dispatchers.IO)
```

**The size ceiling is symmetric, and publishing it matters as much as enforcing it.**
`maxFrameSize` (default `DEFAULT_MAX_FRAME_SIZE`, 16 MiB) is checked in both directions: an oversize
`send` throws `FrameTooLargeException` before writing a byte, and a hostile length prefix on the wire
throws *before anything is allocated for it*. The same number then travels upward as
`Connection.maxFrameBytes` and surfaces as `Seam.maxPayloadBytes` — the budget
[Payload limits](#payload-limits) tells callers to chunk to. A fabric that leaves it unset leaves
that whole mechanism inert, which is why TCP is the in-tree fabric held to the number rather than
declaring the gap away.

<!-- verbatim from kuilt-stream/src/commonTest/kotlin/us/tractat/kuilt/stream/FramedTest.kt#rejectsOversizePrefixWithoutAllocating -->
```kotlin
val wire = Buffer()
wire.writeInt(Int.MAX_VALUE)        // hostile length prefix — validates before allocating
val conn = framed(source = wire, sink = wire, maxFrameSize = 16)
assertFailsWith<FrameTooLargeException> { conn.incoming.toList() }
```

Two assumptions to hold on to. Reading uses `Source.readByteArray`, which **blocks the collecting
coroutine** until the bytes arrive — so collect on a real IO dispatcher, never under a virtual-time
test scheduler, where a blocking read never advances and the test hangs rather than fails. And a
clean EOF *at a frame boundary* completes `incoming` normally, while an EOF *mid-frame* propagates as
an `EOFException`: a truncated frame is an error, not a tidy end of stream.

### Plain TCP is already assembled

**Intent:** the transport really is just TCP and you are about to do the plumbing above by hand.
**Primitive:** `TcpLoom.host(serverSocket, selfId, selector)` and `TcpLoom.join(selfId, selector)`
(`:kuilt-tcp`, JVM/Android), joining a `TcpAddress`. It is `framed()` + `handshaking()` already
wired, and it is held to the same `SeamConformanceSuite` as every other fabric.

<!-- verbatim from kuilt-tcp/src/jvmTest/kotlin/us/tractat/kuilt/tcp/TcpConformanceTest.kt#TcpConformanceTest -->
```kotlin
override fun newLoomPair(): Pair<Loom, Loom> {
    // …
    val hostLoom = TcpLoom.host(serverSocket, PeerId("tcp-host"), selector)
    val joinerLoom = TcpLoom.join(PeerId("tcp-joiner"), selector)
    return hostLoom to joinerLoom
}

override fun joinTag(): Tag = TcpAddress(host = "127.0.0.1", port = port)
```

Bind the `ServerSocket` yourself before calling `host` — `aSocket(selector).tcp().bind(host, 0)`, then
read the port back off the socket you are holding. Probing for a free port and re-binding the number
is a TOCTOU another process can win in the window between the probe closing and the real bind. And
`weave` refuses outright to build under a `TestDispatcher`: this is real socket IO, and under virtual
time it would deadlock silently instead of failing. Test the layers above it over an in-memory
`Connection` pair instead.

## Sending to yourself

**Intent:** loop a frame back to your own peer — replay your own move locally, feed your own replicator, treat "everyone" uniformly by iterating `peers` and sending to each.
**Primitive:** `Seam.broadcast` / `Room.broadcast`. **Not** `sendTo(selfId, …)`, which throws `IllegalArgumentException` on every fabric.

`broadcast` is the loop-back surface; `sendTo` names *another* peer. The refusal is deliberately not `PeerNotConnected` — `selfId` **is** in `peers`, always, so reporting the peer as absent would state something false and push a caller into reconnecting over what is really a bug in its own addressing.

The trap is the uniform loop. `peers` includes you, so `peers.value.forEach { seam.sendTo(it, frame) }` sends to yourself on the first or last iteration, and before #2428 what happened next depended on which fabric you were on — a 2-peer link delivered the frame to the *other* peer and reported success. Filter, or broadcast:

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/AgentCookbookSamples.kt#sendToEveryoneElseSample -->
```kotlin
// Wrong — `peers` includes selfId, so this self-sends:
//     seam.peers.value.forEach { seam.sendTo(it, frame) }
// Either filter…
seam.peers.value.filter { it != seam.selfId }.forEach { seam.sendTo(it, frame) }
// …or just broadcast, which is what "everyone else" means at this layer.
seam.broadcast(frame)
```

Order matters if you are catching: the `Torn` check runs first, so a self-send on a closed seam is an `IllegalStateException`, not this. Pinned for every fabric by `SeamConformanceSuite.sendToSelfIsRefused`.

## Payload limits

**Intent:** pick a chunk size for a big payload — or explain a `FrameTooLargeException` that appears only after somebody drops out.
**Primitive:** `Room.maxPayloadBytes` (`:kuilt-session`), and `Seam.maxPayloadBytes` (`:kuilt-core`) one layer down. Size to that, not to the fabric's frame limit.

`null` means **unknown, not unbounded** — the honest answer from a fabric that names no ceiling. A non-null value is a promise: a payload that size or smaller will not be refused for being too big, *whatever route the frame takes*.

That last clause is the whole point. On a star, a spoke's frame reaches only the host, so once the roster diverges from what the transport can address the payload is wrapped in a relay envelope and forwarded — and the wrapper costs bytes. The budget holds them back **unconditionally**, even while no relay is in use, because routing flips the instant a member enters its reconnect window: a limit that moved with the route would be a trap for a caller that checked it and then sent. A `room.channel(id)` view reports a further-reduced budget, since its own framing costs bytes too.

Every layer that wraps a seam answers the same way, so read the budget off the seam you actually hold rather than the fabric at the bottom. A `MuxSeam`/`NamedMux` channel view subtracts its channel header; a `CompositeSeam` reports the tightest of its **live** plies less its own envelope, so the number moves as plies attach and detach; a tiered union reports the tighter of its two tiers; a gossip overlay subtracts its relay header even on the `sendTo` route that does not pay it. Where several routes are folded, a member that names no ceiling is skipped rather than collapsing the answer — a bond of one bounded and one unknown transport is still bounded by what it does know — and `0` is a real answer, not a bug: it means the framing above you has eaten the whole ceiling underneath.

The number is a **promise, not the refusal threshold** — refusal is measured on the frame that actually goes on the wire, so a payload above the budget that still fits (a direct send, where no envelope is applied) is delivered rather than rejected. Size to the budget anyway: it is the only number that holds whichever route the frame takes.

It is also **a reading, not a lease.** Route-independent is not the same as time-independent: a mesh reports the minimum across its live links, so a peer attaching over a tighter transport lowers the number under you. Re-read it per send rather than once per batch.

When a frame genuinely will not fit, each call keeps its own contract: `sendTo` raises `PayloadTooLarge` (addressed sends report), `broadcast` drops it with a debug log (it is lossy-without-error by contract). Neither lets the fabric's own oversize error out.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#chunkToTheRoomsBudgetSample -->
```kotlin
var start = 0
while (start < blob.size) {
    // Re-read per chunk, not once for the loop: the budget is a reading, not a lease. On a mesh
    // it is the minimum across live links, so a peer attaching over a tighter transport lowers
    // it under you mid-blob. null means "this fabric names no ceiling" — unknown, not
    // unbounded; floored at 1 because the budget is legitimately 0 on a fabric whose ceiling is
    // under the relay reservation.
    val budget = (room.maxPayloadBytes ?: DEFAULT_CHUNK_BYTES).coerceAtLeast(1)
    val end = minOf(start + budget, blob.size)
    // Index arithmetic, not `asSequence().chunked()` — the latter boxes every byte and builds
    // an ArrayList<Byte> per chunk. On a blob big enough to need chunking that is the point.
    // Past the budget, sendTo reports PayloadTooLarge (addressed sends do) while broadcast
    // drops with a log (lossy by contract) — neither surfaces the fabric's own oversize error.
    room.sendTo(peer, blob.copyOfRange(start, end))
    start = end
}
```

## Long-lived pumps

**Intent:** collect a flow for the life of a session — a peer's state, a roster, an inbound frame stream — without one throw ending the *collector* rather than the item. Don't write `flow.onEach { try { … } catch (…) { … } }.launchIn(scope)`.
**Primitive:** `Flow.pumpIn(scope, onFailure, name) { … }` (`:kuilt-core`).

There are **two** ways such a collector dies and a hand-written `try` only covers one. `onEach { … }.launchIn(scope)` desugars to `scope.launch { flow.onEach { … }.collect() }`, so your `try` sits *inside* the collector: it sees what the body throws and never sees a throw raised by the **flow itself**, which ends the flow and escapes the `launch` entirely. On Kotlin/Native that escape is not a dead coroutine, it is a dead **process** — an unhandled coroutine exception reaches the runtime's default handler and aborts, and a `SupervisorJob` is the mechanism rather than the protection. `pumpIn` is one call owning both halves for exactly that reason, and `PumpFailure.ITEM` / `PumpFailure.UPSTREAM` tells your handler whether the pump survived.

It also settles the cancellation question you would otherwise get wrong: `runCatchingCancellable` discriminates on *type*, which cannot tell your own cancellation from a `CancellationException` a callee minted (a consumer's `withTimeout` inside `sendTo`). Rethrown from a pump, that one **cancels it silently** — no report, no stack trace. `pumpIn` uses `currentCoroutineContext().ensureActive()`, which decides it at runtime; cancelling your scope still cancels the pump.

`name` is **required**, and it is not decoration. `launchIn` keeps the `onEach` lambda out of the suspended continuation chain, so every pump of this shape parks at the same frame with no library frame in its stack at all — a coroutine dump renders your whole pump set as one indistinguishable blob, which looks the same whether they are healthy or one of them is wedged. `pumpIn` attaches the name as a `CoroutineName` on the launch, so anything reading `CoroutineInfo.context` can attribute a parked pump to the pump it belongs to. Make it distinct **per pump instance**, not per call site: where several pumps of one kind run side by side, qualify it (`"room-peers[$roomId]"`) so a census can group by kind and still name the instance.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/PumpInSamples.kt#samplePumpIn -->
```kotlin
// A consumer-authored flow that hands over one item the body cannot apply, and then fails outright.
val updates = flow {
    emit("apply-me")
    emit("i-will-not-apply")
    error("…and then the flow itself gave up")
}

val pump = updates.pumpIn(
    scope = backgroundScope,
    // ITEM: that update was lost, the pump lives. UPSTREAM: the pump is over — say so, loudly.
    onFailure = { half, _ -> reported += half },
    // What a coroutine census calls this pump when it is the one that wedged.
    name = "sample-updates",
) { update ->
    if (update == "i-will-not-apply") error("this update could not be applied")
    applied += update
}
pump.join()
```

## A seam's terminal state

**Intent:** publish your fabric's `SeamState` when two different threads write it — a transport callback promoting `Weaving → Woven`, and `close()` latching the terminal `Torn`.
**Primitive:** `SeamStateGate` (`:kuilt-core`).

A `Seam` has **two** state writers and they are genuinely concurrent: the promotion runs on whatever thread your transport calls back on (a JNA trampoline, MC's private delegate queue, a socket reader), while `close()` runs on the consumer's. So this, which is what everyone writes, is a **check-then-set**:

```text
if (_state.value is SeamState.Weaving) _state.value = SeamState.Woven   // ← read and write are not atomic
```

A tear landing between that read and that write is stamped over with `Woven`. It is **permanent**, not transient: the spool is closed, `incoming` has completed, `peers` has collapsed, and both writers have retired, so no later emission can correct it. Every consumer on `state.first { it is Torn }` hangs forever, and a factory that frees its seam slot on `Torn` never frees — so no later `weave()` succeeds either.

**A `closed` flag does not fix it.** The flag read and the flow write are still two steps, so a callback can read `closed == false`, be preempted by a complete `close()`, and resume into the same clobber. Check-a-flag-then-write *is* the race. This is not hypothetical: four fabrics hand-rolled a latch while this type was `internal`, and three wrote precisely that shape (#1803).

`update` no-ops once `tear` has latched, and `tear` returns `true` for exactly one caller — so it **replaces** your single-shot atomic rather than sitting beside it. Publish `Torn` through `tear`, never `update`: `update` refuses it, because a `Torn` published without latching is the same bug through the front door.

<!-- verbatim from kuilt-core/src/commonSamples/kotlin/us/tractat/kuilt/core/SeamStateGateSamples.kt#sampleSeamStateGate -->
```kotlin
val gate = SeamStateGate(SeamState.Weaving)

// The transport callback's promotion. Unconditional: `Woven` over `Woven` conflates, and once
// the gate has latched it cannot land at all — so no `if (state.value is Weaving)` guard, which
// was never a promotion rule but the read half of a race.
gate.update(SeamState.Woven)
assertIs<SeamState.Woven>(gate.state.value)

// The close decision. Single-shot: `true` for the one winning caller, so this IS the seam's
// terminal latch and it needs no separate `closed` atomic beside it.
assertTrue(gate.tear(CloseReason.Normal))
assertEquals(false, gate.tear(CloseReason.RemoteRequested), "a second tear loses; the first reason stands")

// The whole point: a promotion still in flight when the tear landed. Before the gate this write
// stamped `Woven` over the terminal `Torn` — permanently, because both writers then retire and
// every `state.first { it is Torn }` waiter hangs forever.
gate.update(SeamState.Woven)
assertIs<SeamState.Torn>(gate.state.value)
```

**Two shapes genuinely do not need it**, and churning them onto the gate buys nothing: one shared mutual-exclusion primitive covering *every* write to the flow (`NwSeam` takes all three under its own lock), and a single-threaded target, where nothing can run between the read and the write (`WebRTCPeerLink` on wasmJs, which documents exactly that).

## Durable storage

**Intent:** keep a blob of bytes under a name so it is still there after a restart or a crash — "save this to disk", "persist it across launches", "write it somewhere it won't be lost". Don't hand-roll a write-temp-then-`fsync`-then-atomic-rename dance, an `expect`/`actual` file helper per platform, or an IndexedDB wrapper.
**Primitive:** `DurableStore` (`:kuilt-store`) — `read` / `write` / `delete` under a `StoreKey`. `InMemoryDurableStore` in tests; `FileChannelDurableStore` (JVM/Android), `NSFileManagerDurableStore` (iOS/macOS) and `IndexedDbDurableStore` (wasmJs) in production.

The contract is about *timing*, not just storage: **`write` returns only once the bytes are committed**, so the caller can report its own success at that moment rather than holding everything open until something downstream confirms. That is what makes an offline-first exporter possible — and each implementation's KDoc names the exact instant it treats as the commit, plus where its guarantee stops (the Apple store does not force before its rename, so power loss can differ from process death — #2141).

It is deliberately small: no iteration, no query, no transaction across two keys, no opinion about what the bytes mean. If you want history you can replay and forget, that is `Bolt` (`:kuilt-bolt`); if you want state that merges across peers, that is the CRDT zoo (`:kuilt-crdt`).

<!-- verbatim from kuilt-store/src/commonSamples/kotlin/us/tractat/kuilt/store/Samples.kt#sampleDurableStore -->
```kotlin
// Every platform has its own crash-safe implementation; a test uses the in-memory one.
val store: DurableStore = InMemoryDurableStore()
val key = StoreKey("draft")

// `write` returns only once the bytes are committed — that is the whole contract.
store.write(key, byteArrayOf(1, 2, 3))

// A later session (a fresh store over the same backing directory or database)
// reads back exactly what was committed; an unwritten key reads back null.
val recovered: ByteArray? = store.read(key)
check(recovered.contentEquals(byteArrayOf(1, 2, 3)))
check(store.read(StoreKey("never-written")) == null)

store.delete(key)
check(store.read(key) == null)
```

