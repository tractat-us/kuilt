# Host-Election Lobby (1C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let two-or-more peers on a symmetric kuilt mesh **agree on one host with no ceremony**, then start a session — by adopting the already-woven seam into a `Room` exactly once, with the role decided by a pure lowest-`PeerId` election.

**Architecture:** "Elect late, adopt once." During the lobby there is **no `Room`** — just live `Seam.peers` and a reactive `host = min(peers)`. A `Room` is created once, at Start, via a new `SeamRoomFactory.adopt(seam, role)` primitive, gated by a host-initiated freeze/ack round (2PC-lite, abort-on-membership-change). See `docs/host-election-design.md`.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), kotlinx-coroutines, kotlinx-serialization-cbor. All new code in `:kuilt-session`.

## Global Constraints

- **`explicitApi()` is enforced** — every public declaration needs an explicit `public`/`internal` modifier.
- **Test methods:** no `test` prefix; backtick names in this module (match `SeamRoomTest`); multi-assert tests use `assertAll()` (from `us.tractat.kuilt.test`).
- **Coroutine determinism:** tests use `runTest` + `backgroundScope`; construct `SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })`. No wall-clock, no unseeded randomness, never `advanceUntilIdle()`.
- **Single-collection `incoming` (ADR-034):** `Seam.incoming` is collected by exactly one collector at a time. The lobby's collector MUST be cancelled-and-joined before the adopted `Room` starts its own.
- **`Seam.peers` includes `selfId`** (documented invariant) — election needs no `∪ {self}`.
- **Exception discipline:** in coroutine/suspend contexts use `runCatchingCancellable` (from `:kuilt-core`), never bare `runCatching`.
- **Build/verify:** JVM inner loop `./gradlew :kuilt-session:jvmTest`; before declaring done run `./gradlew :kuilt-session:build detektAll --rerun-tasks` (the Android/native variants and lint are not covered by `jvmTest`).

## File structure

- Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt` — the freeze wire protocol.
- Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/ElectionLobby.kt` — public `ElectionLobby` interface, `NotElectedHostException`, and the pure `electHost` function.
- Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt` — the `internal` impl.
- Modify `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` — add `SeamRoomFactory.adopt(...)` and `SeamRoomFactory.electLobby(...)`.
- Modify `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt` — retire the `Room.role` "May change in 1C" KDoc.
- Create tests: `AdoptTest.kt`, `election/ElectHostTest.kt`, `election/LobbyMessageTest.kt`, `election/SeamElectionLobbyTest.kt` under `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/`.

---

### Task 1: `SeamRoomFactory.adopt()` — adopt an already-woven Seam into a Room

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (add method to the `SeamRoomFactory` class, near `host`/`join`)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/AdoptTest.kt`

**Interfaces:**
- Produces: `public suspend fun SeamRoomFactory.adopt(seam: Seam, role: SessionRole, memberName: String? = null, roomKey: String? = null): Room`

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/AdoptTest.kt`:

```kotlin
package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SeamRoomFactory.adopt] wraps an ALREADY-WOVEN [us.tractat.kuilt.core.Seam] into a [Room]
 * with an explicit role — no re-weave. This is the primitive the election lobby adopts with.
 */
class AdoptTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    @Test
    fun `adopt forms roster over pre-woven seams`() =
        runTest {
            val loom = InMemoryLoom()
            // Weave the mesh ourselves (what the lobby will do), then adopt with explicit roles.
            val hostSeam = loom.weave(Rendezvous.New(Pattern("s")))
            val joinerSeam = loom.weave(Rendezvous.Existing(InMemoryTag("s")))

            val f = factory(loom, backgroundScope)
            val hostRoom = f.adopt(hostSeam, SessionRole.Host, memberName = "Alice")
            val joinerRoom = f.adopt(joinerSeam, SessionRole.Joiner, memberName = "Bob")

            val hostRoster = hostRoom.roster.first { it.size == 1 }
            val joinerRoster = joinerRoom.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(SessionRole.Host, hostRoom.role.value) },
                { assertEquals(SessionRole.Joiner, joinerRoom.role.value) },
                { assertEquals("Bob", hostRoster.first().identity.displayName) },
                { assertEquals("Alice", joinerRoster.first().identity.displayName) },
            )

            joinerRoom.leave()
            hostRoom.leave()
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*AdoptTest*"`
Expected: FAIL — `adopt` is unresolved.

- [ ] **Step 3: Implement `adopt`**

In `SeamRoom.kt`, add to the `SeamRoomFactory` class body (after `join`, before the `companion object`). Add the `Seam` import at the top if not present (it is imported via other refs; verify):

```kotlin
    /**
     * Adopt an **already-woven** [seam] into a [Room] with an explicit [role] — no re-weave.
     *
     * Unlike [host]/[join] (which each weave a fresh seam), [adopt] takes ownership of a seam the
     * caller wove, so the calling layer can weave the mesh once and decide role afterward (the
     * host-election lobby, #1439). The returned [Room] owns the seam's lifetime from here:
     * [Room.leave] closes it — correct, because the seam is handed over exactly once.
     *
     * [role] is fixed for the room's lifetime. [roomKey] is the admit-gate key
     * ([us.tractat.kuilt.core.Pattern.roomKey]); [memberName] is this peer's own roster label
     * (null → peer-id-derived). Resume-after-tear is not wired (no `reweave`): a joiner whose host
     * link tears goes terminal ([MembershipEvent.HostLost]).
     */
    public suspend fun adopt(
        seam: Seam,
        role: SessionRole,
        memberName: String? = null,
        roomKey: String? = null,
    ): Room {
        val roomId = if (role == SessionRole.Host) RoomId(seam.selfId.value + "-room") else null
        return SeamRoom(
            seam = seam,
            role = role,
            memberName = memberName,
            scope = scope,
            clock = clock,
            heartbeatConfig = heartbeatConfig,
            admitTimeout = admitTimeout,
            roomId = roomId,
            roomKey = roomKey,
        ).also { room -> room.start() }
    }
```

Confirm these imports exist at the top of `SeamRoom.kt` (add any missing): `us.tractat.kuilt.core.Seam` and `us.tractat.kuilt.session.partition.RoomId` (both are already used in the file).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*AdoptTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/AdoptTest.kt
git commit --no-gpg-sign -m "kuilt-session: SeamRoomFactory.adopt() — adopt a woven Seam into a Room (#1439)"
```

---

### Task 2: `electHost` — the pure election function

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/ElectionLobby.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectHostTest.kt`

**Interfaces:**
- Produces: `public fun electHost(peers: Set<PeerId>): PeerId` (throws on empty set), plus the `ElectionLobby` interface and `NotElectedHostException` (used by later tasks).

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectHostTest.kt`:

```kotlin
package us.tractat.kuilt.session.election

import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElectHostTest {
    @Test
    fun `elects the lowest PeerId by value`() {
        val peers = setOf(PeerId("ccc"), PeerId("aaa"), PeerId("bbb"))
        assertEquals(PeerId("aaa"), electHost(peers))
    }

    @Test
    fun `single peer elects itself`() {
        assertEquals(PeerId("solo"), electHost(setOf(PeerId("solo"))))
    }

    @Test
    fun `empty set fails fast`() {
        assertFailsWith<IllegalArgumentException> { electHost(emptySet()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*ElectHostTest*"`
Expected: FAIL — `electHost` and package unresolved.

- [ ] **Step 3: Implement `electHost` + the `ElectionLobby` interface**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/ElectionLobby.kt`:

```kotlin
package us.tractat.kuilt.session.election

import kotlinx.coroutines.flow.StateFlow
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.Room

/**
 * The elected host is the peer with the lowest [PeerId] value among those currently connected.
 *
 * Pure and deterministic: every peer computes the SAME function of the SAME membership set, so
 * they agree with no negotiation. [peers] must be non-empty ([us.tractat.kuilt.core.Seam.peers]
 * always includes this peer, so this holds for any live seam).
 */
public fun electHost(peers: Set<PeerId>): PeerId =
    requireNotNull(peers.minByOrNull { it.value }) { "electHost requires a non-empty peer set" }

/** Thrown by [ElectionLobby.start] when this peer is not the currently-elected host. */
public class NotElectedHostException(message: String) : Exception(message)

/**
 * A pre-session lobby over a symmetric mesh. **Not a [Room]** — during the lobby there is no admit
 * handshake, no admitted roster, no heartbeat: just the live connected peers and a reactive elected
 * host. A [Room] is created exactly once, at [start]/[awaitRoom], by adopting the woven seam with a
 * now-fixed role. See `docs/host-election-design.md`.
 */
public interface ElectionLobby {
    /** This peer's own identifier. */
    public val selfId: PeerId

    /**
     * Live set of connected peers (includes [selfId]). Join / leave / two-group-merge are all just
     * set changes. Sourced from [us.tractat.kuilt.core.Seam.peers] — never a discovery roster such
     * as `NwLoom.visiblePeers`, which accumulates and never removes.
     */
    public val peers: StateFlow<Set<PeerId>>

    /** The elected host — `electHost(peers)` — reactive. Every peer computes the same value. */
    public val host: StateFlow<PeerId>

    /**
     * **HOST-ONLY.** Close the lobby and begin the session: run the freeze/ack round, then adopt the
     * seam as [us.tractat.kuilt.session.SessionRole.Host]. Retries internally while membership churns;
     * returns the admitted [Room] once every member has acknowledged.
     *
     * @throws NotElectedHostException if this peer is not currently [host].
     */
    public suspend fun start(memberName: String? = null): Room

    /**
     * Await the session as a member: suspend until the elected host freezes the lobby, acknowledge it,
     * and adopt the seam as [us.tractat.kuilt.session.SessionRole.Joiner]. (The host obtains its [Room]
     * from [start] instead.) Returns once the session is committed.
     */
    public suspend fun awaitRoom(memberName: String? = null): Room

    /** Leave the lobby, closing the underlying seam — unless a [Room] has already adopted it. Idempotent. */
    public suspend fun leave()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*ElectHostTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/ElectionLobby.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectHostTest.kt
git commit --no-gpg-sign -m "kuilt-session: electHost() + ElectionLobby interface (#1439)"
```

---

### Task 3: `LobbyMessage` — the freeze wire protocol

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/LobbyMessageTest.kt`

**Interfaces:**
- Produces: `sealed interface LobbyMessage` with `Freeze(hostId: String, roster: Set<String>, epoch: Long)`, `FreezeAck(hostId: String, epoch: Long)`, `Commit(hostId: String, epoch: Long)`, `Reopen(epoch: Long)`; companion `encode(LobbyMessage): ByteArray`, `decode(ByteArray): LobbyMessage?`, `isLobbyFrame(ByteArray): Boolean`, `PREFIX_BYTE: Byte`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/LobbyMessageTest.kt`:

```kotlin
package us.tractat.kuilt.session.election

import us.tractat.kuilt.session.admit.AdmitMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyMessageTest {
    @Test
    fun `round-trips every variant`() {
        val messages = listOf(
            LobbyMessage.Freeze(hostId = "aaa", roster = setOf("aaa", "bbb"), epoch = 7L),
            LobbyMessage.FreezeAck(hostId = "aaa", epoch = 7L),
            LobbyMessage.Commit(hostId = "aaa", epoch = 7L),
            LobbyMessage.Reopen(epoch = 7L),
        )
        for (m in messages) {
            assertEquals(m, LobbyMessage.decode(LobbyMessage.encode(m)))
        }
    }

    @Test
    fun `decode rejects a non-lobby frame`() {
        assertNull(LobbyMessage.decode(byteArrayOf(0x00, 0x01)))
        // An admit frame must not decode as a lobby frame (distinct prefixes).
        assertNull(LobbyMessage.decode(AdmitMessage.encode(AdmitMessage.Goodbye)))
    }

    @Test
    fun `lobby and admit prefixes differ`() {
        assertTrue(LobbyMessage.PREFIX_BYTE != AdmitMessage.PREFIX_BYTE)
        assertTrue(LobbyMessage.isLobbyFrame(LobbyMessage.encode(LobbyMessage.Reopen(1L))))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*LobbyMessageTest*"`
Expected: FAIL — `LobbyMessage` unresolved.

- [ ] **Step 3: Implement `LobbyMessage`**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt` (mirrors `AdmitMessage`'s framing exactly, distinct prefix `0x65`):

```kotlin
package us.tractat.kuilt.session.election

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * Wire messages for the host-election freeze round (#1439). Sent over the raw lobby [seam] BEFORE any
 * [us.tractat.kuilt.session.Room] adopts it, so the framing is parallel to — and prefix-disjoint from —
 * [us.tractat.kuilt.session.admit.AdmitMessage].
 *
 * Freeze round: `Freeze` (host→all) → `FreezeAck` (each member→host) → `Commit` (host→all, adopt now)
 * or `Reopen` (host→all, abort). See `docs/host-election-design.md`.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public sealed interface LobbyMessage {
    /** Host→all: "closing the lobby; [roster] are the members; ack if I'm your elected host." */
    @Serializable
    @SerialName("freeze")
    public data class Freeze(val hostId: String, val roster: Set<String>, val epoch: Long) : LobbyMessage

    /** Member→host: "agreed you're host, I'm ready." */
    @Serializable
    @SerialName("freeze-ack")
    public data class FreezeAck(val hostId: String, val epoch: Long) : LobbyMessage

    /** Host→all: "every member acked — adopt now." */
    @Serializable
    @SerialName("commit")
    public data class Commit(val hostId: String, val epoch: Long) : LobbyMessage

    /** Host→all: "freeze aborted — back to the lobby." */
    @Serializable
    @SerialName("reopen")
    public data class Reopen(val epoch: Long) : LobbyMessage

    public companion object {
        /**
         * First byte of every encoded lobby payload: `0x65` ('e' for "election"). Distinct from
         * [us.tractat.kuilt.session.admit.AdmitMessage.PREFIX_BYTE] (`0x61`) and the channel prefix
         * (`0x63`) so the three frame kinds never alias on one seam.
         */
        public const val PREFIX_BYTE: Byte = 0x65

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode a [LobbyMessage] to bytes with the [PREFIX_BYTE] framing prefix. */
        @OptIn(ExperimentalSerializationApi::class)
        public fun encode(message: LobbyMessage): ByteArray {
            val encoded = cbor.encodeToByteArray(message)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = PREFIX_BYTE
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /** Decode bytes as a [LobbyMessage]; null if the prefix mismatches or decoding fails. */
        @OptIn(ExperimentalSerializationApi::class)
        public fun decode(bytes: ByteArray): LobbyMessage? {
            if (bytes.isEmpty() || bytes[0] != PREFIX_BYTE) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<LobbyMessage>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** True if [bytes] looks like a lobby frame (starts with [PREFIX_BYTE]). */
        public fun isLobbyFrame(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*LobbyMessageTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/LobbyMessageTest.kt
git commit --no-gpg-sign -m "kuilt-session: LobbyMessage freeze wire protocol (#1439)"
```

---

### Task 4: `SeamElectionLobby` — election display (peers + host), no freeze yet

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt`

**Interfaces:**
- Consumes: `electHost` (Task 2), `LobbyMessage` (Task 3), `SeamRoomFactory.adopt` (Task 1).
- Produces: `internal class SeamElectionLobby(seam, factory, scope, clock, roomKey, freezeTimeout, commitTimeout) : ElectionLobby`. This task implements `selfId`, `peers`, `host`, `leave`, and the internal `seam.incoming` → `LobbyMessage` collector. `start`/`awaitRoom` are added in Task 5 (stub them to `TODO()` here so the class compiles, but do NOT test them yet).

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt`:

```kotlin
package us.tractat.kuilt.session.election

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.Rendezvous
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SeamElectionLobbyTest {
    private fun factory(loom: InMemoryLoom, scope: CoroutineScope) =
        SeamRoomFactory(loom, scope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })

    private fun lobby(seam: Seam, loom: InMemoryLoom, scope: CoroutineScope) =
        SeamElectionLobby(seam = seam, factory = factory(loom, scope), scope = scope,
            clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) }, roomKey = null)

    @Test
    fun `all peers elect the same lowest-id host`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            // Both see 2 peers; both elect min(peers). Peer ids are "peer-1","peer-2" (InMemoryLoom).
            val h1 = l1.host.first()
            val h2 = l2.host.first { l2.peers.value.size == 2 }
            assertAll(
                { assertEquals(h1, h2) },
                { assertEquals(electHost(l1.peers.value), h1) },
                { assertEquals(2, l1.peers.value.size) },
            )
            l1.leave(); l2.leave()
        }

    @Test
    fun `host updates when a lower-id peer joins`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            assertEquals(s1.selfId, l1.host.first()) // alone → self is host

            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l2 = lobby(s2, loom, backgroundScope)
            // host is now min of both; assert both agree once the second peer is visible.
            val settled = l1.host.first { l1.peers.value.size == 2 }
            assertEquals(electHost(setOf(s1.selfId, s2.selfId)), settled)
            l1.leave(); l2.leave()
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamElectionLobbyTest*"`
Expected: FAIL — `SeamElectionLobby` unresolved.

- [ ] **Step 3: Implement the lobby skeleton**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt`:

```kotlin
package us.tractat.kuilt.session.election

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.tractat.kuilt.core.CloseReason
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.session.Room
import us.tractat.kuilt.session.SeamRoomFactory
import us.tractat.kuilt.session.SessionRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [Seam]-backed [ElectionLobby]. Owns the woven mesh [seam] until a [Room] adopts it.
 *
 * A single collector drains [Seam.incoming], decoding [LobbyMessage]s and republishing them on
 * [lobbyMessages] for [start]/[awaitRoom] to consume. At adopt time that collector is cancelled and
 * joined BEFORE [SeamRoomFactory.adopt] starts the room's own collector — preserving the
 * single-collection `incoming` contract (ADR-034).
 */
internal class SeamElectionLobby(
    private val seam: Seam,
    private val factory: SeamRoomFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Instant,
    private val roomKey: String?,
    private val freezeTimeout: Duration = 10.seconds,
    private val commitTimeout: Duration = 10.seconds,
) : ElectionLobby {

    override val selfId: PeerId = seam.selfId

    // Seam.peers already includes selfId (documented invariant) — no ∪ {self} needed.
    override val peers: StateFlow<Set<PeerId>> = seam.peers

    override val host: StateFlow<PeerId> =
        seam.peers
            .map { electHost(it) }
            .stateIn(scope, SharingStarted.Eagerly, electHost(seam.peers.value))

    private val _lobbyMessages = MutableSharedFlow<Pair<PeerId, LobbyMessage>>(extraBufferCapacity = 64)
    protected val lobbyMessages: SharedFlow<Pair<PeerId, LobbyMessage>> = _lobbyMessages.asSharedFlow()

    private val adoptMutex = Mutex()
    private var adopted = false
    protected var epoch: Long = 0L

    private val collectorJob: Job = scope.launch {
        seam.incoming.collect { swatch ->
            val sender = swatch.sender ?: return@collect
            val msg = LobbyMessage.decode(swatch.toByteArray()) ?: return@collect
            _lobbyMessages.emit(sender to msg)
        }
    }

    override suspend fun start(memberName: String?): Room = TODO("Task 5")

    override suspend fun awaitRoom(memberName: String?): Room = TODO("Task 5")

    override suspend fun leave() {
        adoptMutex.withLock {
            if (adopted) return // seam ownership transferred to the Room; do not close it.
            collectorJob.cancel()
            seam.close(CloseReason.Normal)
        }
    }

    /**
     * Stop the lobby's `incoming` collector, then adopt the seam as [role]. Cancel-and-join guarantees
     * the lobby collector has fully stopped before the room starts its own collector (single-collection).
     * Callable at most once.
     */
    protected suspend fun adoptRoom(role: SessionRole, memberName: String?): Room =
        adoptMutex.withLock {
            check(!adopted) { "lobby already adopted a room" }
            adopted = true
            collectorJob.cancelAndJoin()
            factory.adopt(seam, role, memberName = memberName, roomKey = roomKey)
        }
}
```

Note: `protected` members are used by the freeze logic added in Task 5 (same class). If Task 5 keeps everything in this one class (it does), change `protected` to `private` in Task 5's final version — but leave as-is now so the skeleton compiles cleanly. Add the `cancelAndJoin` import: `import kotlinx.coroutines.cancelAndJoin`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamElectionLobbyTest*"`
Expected: PASS (the two display tests; `start`/`awaitRoom` are not exercised yet).

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt
git commit --no-gpg-sign -m "kuilt-session: SeamElectionLobby election display (peers + host) (#1439)"
```

---

### Task 5: The freeze round — `start()` (host) + `awaitRoom()` (member)

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt` (replace the two `TODO()` stubs; promote the `protected` helpers to `private`)
- Modify: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt` (add freeze tests)

**Interfaces:**
- Consumes: `lobbyMessages`, `adoptRoom`, `epoch`, `host`, `peers` (Task 4); `LobbyMessage` variants (Task 3).
- Produces: working `start`/`awaitRoom` per the design.

- [ ] **Step 1: Write the failing tests**

Append to `SeamElectionLobbyTest.kt` (and add imports: `kotlinx.coroutines.async`, `kotlinx.coroutines.awaitAll`, `us.tractat.kuilt.session.Room`, `us.tractat.kuilt.session.SessionRole`, `kotlin.test.assertFailsWith`):

```kotlin
    @Test
    fun `host start and member awaitRoom form a session with correct roles`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            // Wait until both see the full 2-peer roster and agree on the host.
            val electedHost = l1.host.first { l1.peers.value.size == 2 }
            l2.host.first { l2.peers.value.size == 2 }

            // The elected host calls start(); the other calls awaitRoom(). Determine which is which.
            val hostLobby = if (l1.selfId == electedHost) l1 else l2
            val memberLobby = if (l1.selfId == electedHost) l2 else l1

            val memberRoomDeferred = async { memberLobby.awaitRoom(memberName = "Member") }
            val hostRoom = hostLobby.start(memberName = "Host")
            val memberRoom = memberRoomDeferred.await()

            // Both rooms complete their admit handshake: one member each.
            hostRoom.roster.first { it.size == 1 }
            memberRoom.roster.first { it.size == 1 }

            assertAll(
                { assertEquals(SessionRole.Host, hostRoom.role.value) },
                { assertEquals(SessionRole.Joiner, memberRoom.role.value) },
            )
            memberRoom.leave(); hostRoom.leave()
        }

    @Test
    fun `start from a non-host peer throws`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val s2 = loom.weave(Rendezvous.Existing(InMemoryTag("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val l2 = lobby(s2, loom, backgroundScope)

            val electedHost = l1.host.first { l1.peers.value.size == 2 }
            l2.host.first { l2.peers.value.size == 2 }
            val nonHost = if (l1.selfId == electedHost) l2 else l1
            assertFailsWith<NotElectedHostException> { nonHost.start() }
            l1.leave(); l2.leave()
        }

    @Test
    fun `lone host starts immediately with an empty roster`() =
        runTest {
            val loom = InMemoryLoom()
            val s1 = loom.weave(Rendezvous.New(Pattern("g")))
            val l1 = lobby(s1, loom, backgroundScope)
            val room = l1.start(memberName = "Solo") // no members → immediate commit
            assertEquals(SessionRole.Host, room.role.value)
            room.leave()
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamElectionLobbyTest*"`
Expected: FAIL — the new tests hit `TODO("Task 5")`.

- [ ] **Step 3: Implement `start` and `awaitRoom`**

In `SeamElectionLobby.kt`, replace the two `TODO()` stubs with the following, and add imports: `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.withTimeoutOrNull`, `kotlinx.coroutines.coroutineScope`, `kotlinx.coroutines.CompletableDeferred`, `kotlinx.coroutines.selects.select`, `us.tractat.kuilt.core.runCatchingCancellable`. Change the `protected` modifiers on `lobbyMessages`, `epoch`, and `adoptRoom` to `private`.

```kotlin
    override suspend fun start(memberName: String?): Room {
        if (host.value != selfId) {
            throw NotElectedHostException("not the elected host: host=${host.value}, self=$selfId")
        }
        while (true) {
            // Re-check role each attempt: a lower-id peer may have appeared between retries.
            if (host.value != selfId) {
                throw NotElectedHostException("lost host election mid-start: host=${host.value}, self=$selfId")
            }
            val roster = peers.value
            val members = roster - selfId
            val myEpoch = ++epoch
            runCatchingCancellable {
                seam.broadcast(
                    LobbyMessage.encode(
                        LobbyMessage.Freeze(selfId.value, roster.map { it.value }.toSet(), myEpoch),
                    ),
                )
            }

            val committed = withTimeoutOrNull(freezeTimeout) { awaitUnanimousAck(members, myEpoch) } ?: false
            if (committed) {
                runCatchingCancellable {
                    seam.broadcast(LobbyMessage.encode(LobbyMessage.Commit(selfId.value, myEpoch)))
                }
                return adoptRoom(SessionRole.Host, memberName)
            }
            // Aborted (membership changed, lost host, or timed out): reopen and retry.
            runCatchingCancellable { seam.broadcast(LobbyMessage.encode(LobbyMessage.Reopen(myEpoch))) }
        }
    }

    /**
     * Await a [LobbyMessage.FreezeAck] from every peer in [members] for [ackEpoch]. Returns true on
     * unanimous ack; false (abort) if the peer set changes or this peer stops being the elected host.
     */
    private suspend fun awaitUnanimousAck(members: Set<PeerId>, ackEpoch: Long): Boolean {
        if (members.isEmpty()) return true
        val snapshot = peers.value
        return coroutineScope {
            val outcome = CompletableDeferred<Boolean>()
            val needed = members.toMutableSet()
            val ackJob = launch {
                lobbyMessages.collect { (sender, msg) ->
                    if (msg is LobbyMessage.FreezeAck && msg.hostId == selfId.value && msg.epoch == ackEpoch) {
                        needed.remove(sender)
                        if (needed.isEmpty()) outcome.complete(true)
                    }
                }
            }
            val membershipJob = launch {
                peers.collect { if (it != snapshot) outcome.complete(false) }
            }
            val hostJob = launch {
                host.collect { if (it != selfId) outcome.complete(false) }
            }
            val result = outcome.await()
            ackJob.cancel(); membershipJob.cancel(); hostJob.cancel()
            result
        }
    }

    override suspend fun awaitRoom(memberName: String?): Room {
        while (true) {
            // Await a Freeze from THIS peer's currently-elected host that names us in the roster.
            // Ignore a Freeze whose host is ourselves (a member never joins itself) or a foreign host.
            val freeze = lobbyMessages
                .first { (sender, msg) ->
                    msg is LobbyMessage.Freeze &&
                        msg.hostId != selfId.value &&
                        sender.value == msg.hostId &&
                        PeerId(msg.hostId) == host.value &&
                        selfId.value in msg.roster
                }
                .second as LobbyMessage.Freeze

            runCatchingCancellable {
                seam.broadcast(LobbyMessage.encode(LobbyMessage.FreezeAck(freeze.hostId, freeze.epoch)))
            }

            val resolution = withTimeoutOrNull(commitTimeout) {
                lobbyMessages.first { (_, msg) ->
                    (msg is LobbyMessage.Commit && msg.hostId == freeze.hostId && msg.epoch == freeze.epoch) ||
                        (msg is LobbyMessage.Reopen && msg.epoch == freeze.epoch)
                }.second
            }
            if (resolution is LobbyMessage.Commit) {
                return adoptRoom(SessionRole.Joiner, memberName)
            }
            // Reopen or timeout: discard and await a fresh Freeze.
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamElectionLobbyTest*"`
Expected: PASS (all display + freeze tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/SeamElectionLobby.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/SeamElectionLobbyTest.kt
git commit --no-gpg-sign -m "kuilt-session: freeze/ack round — ElectionLobby start()/awaitRoom() (#1439)"
```

---

### Task 6: `SeamRoomFactory.electLobby()` — the weave-and-wrap entry

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` (add `electLobby` to `SeamRoomFactory`)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectLobbyEntryTest.kt`

**Interfaces:**
- Consumes: `SeamElectionLobby` (Task 4/5).
- Produces: `public suspend fun SeamRoomFactory.electLobby(pattern: Pattern): ElectionLobby`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectLobbyEntryTest.kt`:

```kotlin
package us.tractat.kuilt.session.election

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.session.SeamRoomFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ElectLobbyEntryTest {
    @Test
    fun `electLobby weaves a mesh and returns a lobby that elects self when alone`() =
        runTest {
            val loom = InMemoryLoom()
            val factory = SeamRoomFactory(loom, backgroundScope, clock = { kotlin.time.Instant.fromEpochMilliseconds(0L) })
            val lobby = factory.electLobby(Pattern("game"))
            // Alone on the mesh → this peer is the elected host.
            assertEquals(lobby.selfId, lobby.host.first())
            lobby.leave()
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-session:jvmTest --tests "*ElectLobbyEntryTest*"`
Expected: FAIL — `electLobby` unresolved.

- [ ] **Step 3: Implement `electLobby`**

In `SeamRoom.kt`, add to the `SeamRoomFactory` class body (after `adopt`). Add imports `us.tractat.kuilt.core.Rendezvous`, `us.tractat.kuilt.session.election.ElectionLobby`, `us.tractat.kuilt.session.election.SeamElectionLobby` at the top:

```kotlin
    /**
     * Symmetric lobby entry both peers call identically: weave the mesh via
     * [us.tractat.kuilt.core.Rendezvous.New] (a constant session name), then return an
     * [ElectionLobby] over the woven seam. Every peer elects the same host (`min(peers)`);
     * on Start the elected host runs the freeze round and each peer adopts a [Room] once (#1439).
     *
     * The seam's lifetime belongs to the lobby until a [Room] adopts it (or [ElectionLobby.leave]).
     *
     * **Weave timeout:** this delegates to the [Loom]'s own `weave`. On a real radio fabric (e.g.
     * `NwLoom`) `weave` blocks until the first peer resolves and may time out if no peer appears —
     * configure the fabric's weave timeout generously for a "wait for players" lobby. The lobby's
     * live membership always reads from [ElectionLobby.peers] (the woven seam), never a discovery roster.
     */
    public suspend fun electLobby(pattern: Pattern): ElectionLobby {
        val seam = loom.weave(Rendezvous.New(pattern))
        return SeamElectionLobby(
            seam = seam,
            factory = this,
            scope = scope,
            clock = clock,
            roomKey = pattern.roomKey,
        )
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kuilt-session:jvmTest --tests "*ElectLobbyEntryTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/election/ElectLobbyEntryTest.kt
git commit --no-gpg-sign -m "kuilt-session: SeamRoomFactory.electLobby() entry (#1439)"
```

---

### Task 7: Retire the `Room.role` "May change in 1C" KDoc

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt:28-34`

**Interfaces:** none (doc-only).

- [ ] **Step 1: Update the KDoc**

In `Room.kt`, replace the `role` KDoc block:

```kotlin
    /**
     * The role this peer plays in the room.
     *
     * Fixed for the room's lifetime. Set to [SessionRole.Host] or [SessionRole.Joiner] by the
     * [RoomFactory] method that created the room ([RoomFactory.host] / [RoomFactory.join]), or by the
     * role the room was adopted with. When the role must be *resolved from the connected roster* rather
     * than chosen up front, use the host-election lobby
     * ([us.tractat.kuilt.session.election.ElectionLobby]) — it elects the host before any room exists,
     * then adopts with a now-fixed role.
     */
    public val role: StateFlow<SessionRole>
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :kuilt-session:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt
git commit --no-gpg-sign -m "kuilt-session: retire Room.role \"May change in 1C\" KDoc; point at ElectionLobby (#1439)"
```

---

### Task 8: Full-module verification + push + PR

**Files:** none (verification gate).

- [ ] **Step 1: Run the full module build with cache disabled**

Run: `./gradlew :kuilt-session:build detektAll --rerun-tasks`
Expected: BUILD SUCCESSFUL, all tasks `EXECUTED` (not `FROM-CACHE`). This covers the Android + Kotlin/Native variants and lint that `jvmTest` skips (a `commonTest` source can compile on JVM yet fail the Android/native compile).

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin nw-1439-host-election
gh pr create --title "kuilt-session: host-election lobby (1C) — elect late, adopt once (closes #1439)" \
  --body "$(cat <<'PRBODY'
> 🤖 This PR was generated by Claude on behalf of @keddie.

Implements the "elect late, adopt once" host-election lobby (#1439, epic #1403). Design: `docs/host-election-design.md`; adversarially reviewed.

**Shape:** the lobby is NOT a `Room` — just live `Seam.peers` + a reactive `host = min(peers)`. A `Room` is adopted exactly once at Start via the new `SeamRoomFactory.adopt(seam, role)` primitive, gated by a host-initiated freeze/ack round (`LobbyMessage`: Freeze→Ack→Commit/Reopen, abort-on-membership-change).

**API (kuilt-session):** `SeamRoomFactory.adopt(...)`, `SeamRoomFactory.electLobby(pattern)`, `ElectionLobby` (`peers`/`host`/`start`/`awaitRoom`/`leave`), `electHost(peers)`.

**Out of scope (follow-ups):** promote/demote of a *live* game (role frozen at Start; post-Start churn = existing `HostLost` machinery); sticky leadership across teardown.

Closes #1439.
PRBODY
)"
gh pr view --web
```

- [ ] **Step 3: Enable auto-merge once CI is green**

```bash
gh pr merge --auto --squash
```

---

## Self-review

**Spec coverage:**
- `adopt(seam, role)` primitive → Task 1. ✓
- `electLobby` symmetric entry → Task 6. ✓
- `ElectionLobby` (peers/host/start/awaitRoom/leave) → Tasks 2 (interface), 4 (display), 5 (freeze). ✓
- Election = `min(peers)` pure function → Task 2. ✓
- Freeze = 2PC-lite host-initiated, abort-on-change → Task 5 (`start`/`awaitUnanimousAck`/`awaitRoom`). ✓
- Adopt-once seam handover, single-collection preserved (cancel-and-join before adopt) → Task 4 (`adoptRoom`). ✓
- Host-authoritative Freeze gate (member acks only its elected host) → Task 5 (`awaitRoom` filter). ✓
- `Room.role` KDoc retired → Task 7. ✓
- Cross-cutting notes (weave timeout, `visiblePeers` ghosts) → documented on `electLobby`/`ElectionLobby.peers` (Tasks 6, 2). ✓

**Placeholder scan:** the only `TODO()` is the deliberate Task-4 stub replaced in Task 5; no other placeholders.

**Type consistency:** `electHost(Set<PeerId>): PeerId`, `LobbyMessage.{Freeze(hostId,roster,epoch), FreezeAck(hostId,epoch), Commit(hostId,epoch), Reopen(epoch)}`, `SeamElectionLobby(seam,factory,scope,clock,roomKey,freezeTimeout,commitTimeout)`, `adopt(seam,role,memberName,roomKey)` — names/signatures consistent across tasks.

**Known follow-up risks (not blocking this plan; noted for the reviewer):**
- `start()`'s retry loop can spin if membership churns continuously — bounded by real transport-event rate; a small backoff is a cheap future hardening.
- `awaitUnanimousAck` aborts on *any* `peers` change including a benign member-leave; it retries with the new snapshot (correct, converges). A member who leaves after acking but before Commit is handled by the joiner's own admit-timeout / the host's next round.
