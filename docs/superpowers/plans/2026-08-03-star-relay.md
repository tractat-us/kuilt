# Star Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a room's roster genuinely routable on a star fabric, so a `Quilter` on two spokes converges — closing [#1994](https://github.com/tractat-us/kuilt/issues/1994).

**Architecture:** A joiner whose target is absent from its transport's `peers` wraps the frame in a `RelayEnvelope(origin, dest, payload)` and sends it to the host; the host validates the first hop, resolves a recipient set, and delivers to each — locally if that recipient is itself, otherwise re-wrapped with `origin` preserved. The envelope, the first-hop rule and the recipient resolver are lifted into `:kuilt-core` as a shared primitive, because `:kuilt-cluster` already ships this dialect privately (`RaftRelay`/`RaftRelayHub`/`RoutedUnicastRouter`).

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization CBOR, kotlinx-coroutines, atomicfu locks, kotlin-test.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-03-star-relay-design.md`. Read it before Task 1.
- **`explicitApi()` is enforced** — every public declaration needs an explicit visibility modifier.
- **JDK 21:** `export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem` (do **not** `source` sdkman-init.sh; it is refused in an isolated worktree).
- **`detektAll`, never bare `detekt`** — bare `detekt` is `NO-SOURCE` here and reports a false green. No `!!` in production code (CI's `:module:detektJvmMain` type-resolution pass fails on `UnsafeCallOnNullableType`, which a local `detektAll` can false-green).
- **`runCatchingCancellable`, never bare `runCatching`** in suspend/coroutine contexts.
- **No `Dispatchers.{Unconfined,Default,IO,Main}` or `GlobalScope` in test sources.**
- **`runTest` timeout is a GENEROUS 30 s backstop, never a tight assertion** — it is wall-clock over a virtual-time trajectory, so tightening it measures the host. Bounded `advanceTimeBy`/`runCurrent` only; **never `advanceUntilIdle()`** (relay and heartbeat timers re-arm forever).
- **Fence every Gradle call at the OS level:** `timeout 600 ./gradlew …`, one test class at a time. Tight fence outside, generous backstop inside.
- **Run builds in the FOREGROUND.** A backgrounded long build stalls a dispatched worker.
- Test methods take no `test` prefix; multi-assert tests use `assertAll()`.
- Commit after every task. Never use the word "chore".

---

### Task 1: Frame-prefix registry (slice 0, closes #2007)

Five room-frame discriminators live as loose `public const val`s in four packages, and one of them
(heartbeat) is a `String` whose first byte merely happens to be `0x6b`. The relay needs a sixth. Claim
the byte space in one registry first, so the relay declares a member instead of adding a constant.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomFramePrefix.kt`
- Create: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RoomFramePrefixTest.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt:236`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt:49`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt:48`
- Modify: `kuilt-liveness/src/commonMain/kotlin/us/tractat/kuilt/liveness/HeartbeatPartitionDetector.kt:273`

**Interfaces:**
- Consumes: nothing.
- Produces: `RoomFramePrefix` enum with `public val byte: Byte`, `public fun matches(bytes: ByteArray): Boolean`, entries `Admit`/`Channel`/`Lobby`/`Heartbeat`/`Relay`, and `RoomFramePrefix.RESERVED_CBOR: IntRange`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RoomFramePrefixTest.kt`:

```kotlin
package us.tractat.kuilt.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room-frame byte space is claimed in exactly one place (#2007).
 *
 * These assertions are the enforcement mechanism. A uniqueness check in a companion `init` block
 * was deliberately rejected: an enum's companion initialises as part of the enum's static init, so
 * a bad claim would throw during class loading and poison every unrelated test that merely touches
 * the enum. A test names the offender instead.
 */
class RoomFramePrefixTest {

    @Test
    fun everyFamilyClaimsADistinctByte() {
        val claimed = RoomFramePrefix.entries.map { it.byte }
        assertEquals(
            claimed.size,
            claimed.toSet().size,
            "two frame families claim the same discriminator byte: $claimed",
        )
    }

    @Test
    fun noFamilyClaimsAByteTheSerializerMayEmit() {
        val offenders = RoomFramePrefix.entries.filter { (it.byte.toInt() and 0xFF) in RoomFramePrefix.RESERVED_CBOR }
        assertEquals(
            emptyList(),
            offenders,
            "a discriminator inside CBOR major-type-7 can be produced by a bare serialized payload",
        )
    }

    @Test
    fun matchesReadsOnlyTheFirstByte() = assertAll(
        { assertTrue(RoomFramePrefix.Relay.matches(byteArrayOf(0x72, 0x01)), "leading byte claims the frame") },
        { assertFalse(RoomFramePrefix.Relay.matches(byteArrayOf(0x01, 0x72)), "a later occurrence must not match") },
        { assertFalse(RoomFramePrefix.Relay.matches(byteArrayOf()), "an empty payload claims nothing") },
    )

    @Test
    fun theRegistryIsTheSourceOfTruthForEveryShippedFamily() = assertAll(
        { assertEquals(0x61.toByte(), RoomFramePrefix.Admit.byte, "admit") },
        { assertEquals(0x63.toByte(), RoomFramePrefix.Channel.byte, "channel") },
        { assertEquals(0x65.toByte(), RoomFramePrefix.Lobby.byte, "lobby") },
        { assertEquals(0x6b.toByte(), RoomFramePrefix.Heartbeat.byte, "heartbeat") },
        { assertEquals(0x72.toByte(), RoomFramePrefix.Relay.byte, "relay") },
    )
}
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem
timeout 600 ./gradlew :kuilt-core:jvmTest --tests "*RoomFramePrefixTest*"
```

Expected: FAIL — compilation error, `Unresolved reference: RoomFramePrefix`.

- [ ] **Step 3: Create the registry**

Create `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RoomFramePrefix.kt`:

```kotlin
package us.tractat.kuilt.core

/**
 * Every kind of frame that can travel on one room's seam, and the single byte that identifies it.
 *
 * A room multiplexes several unrelated conversations down one fabric: the admit handshake, an
 * application channel, the host-election lobby, liveness pings, and relayed frames. A receiver tells
 * them apart by the payload's **first byte**. This enum is the one place those bytes are claimed, so
 * a new frame family cannot be added without claiming one, and two families cannot silently agree on
 * the same value (pinned by `RoomFramePrefixTest`).
 *
 * ## For application authors
 *
 * An application payload sent on [Seam.broadcast]/[Seam.sendTo] beneath a room **must not begin with
 * any byte claimed here** — it would be misclassified as a protocol frame and routed or dropped.
 *
 * ## Reserved, and never claimable
 *
 * [RESERVED_CBOR] is the CBOR major-type-7 range, which a serializer can emit as the first byte of a
 * bare encoded payload. A discriminator in that range could not be distinguished from data.
 *
 * @property byte the claimed first byte.
 */
public enum class RoomFramePrefix(public val byte: Byte) {
    /** The admit handshake — Hello/Welcome/Resume/Farewell/Paused/… (`0x61`, 'a'). */
    Admit(0x61),

    /** An application channel view over a room (`0x63`, 'c'). */
    Channel(0x63),

    /** The host-election lobby (`0x65`, 'e'). */
    Lobby(0x65),

    /**
     * Liveness ping/pong (`0x6b`, 'k' — the first byte of `"kuilt.heartbeat."`).
     *
     * This family's decoder matches a **String** prefix rather than this byte; the byte is claimed
     * here so the space is legible in one place, and pinned to the string in
     * `HeartbeatPrefixRegistryTest`.
     */
    Heartbeat(0x6b),

    /** A relayed frame carrying its true origin across a hub (`0x72`, 'r'). See [RelayEnvelope]. */
    Relay(0x72),
    ;

    /** True when [bytes] is a frame of this family — i.e. its first byte is [byte]. */
    public fun matches(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == byte

    public companion object {
        /**
         * CBOR major-type-7 (`0xe0`–`0xff`), which a serializer may emit as the leading byte of a
         * bare encoded payload. No family may claim a byte in this range.
         */
        public val RESERVED_CBOR: IntRange = 0xe0..0xff
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
timeout 600 ./gradlew :kuilt-core:jvmTest --tests "*RoomFramePrefixTest*"
```

Expected: PASS (4 tests).

- [ ] **Step 5: Point the four existing declarations at the registry**

In `kuilt-session/.../admit/AdmitMessage.kt`, replace the `PREFIX_BYTE` declaration (keep its KDoc,
append the registry sentence):

```kotlin
        /**
         * First byte of every encoded admit payload. Application frames must not
         * begin with this byte so the receiver can distinguish protocol from app frames.
         *
         * Sourced from [us.tractat.kuilt.core.RoomFramePrefix.Admit] — the registry claims the byte
         * space for every room-frame family (#2007). No longer `const`: the value now has one
         * definition site rather than five.
         */
        public val PREFIX_BYTE: Byte = RoomFramePrefix.Admit.byte
```

Add `import us.tractat.kuilt.core.RoomFramePrefix`.

Apply the same shape in `RoomChannel.kt` (`CHANNEL_PREFIX` → `RoomFramePrefix.Channel.byte`) and
`election/LobbyMessage.kt` (`PREFIX_BYTE` → `RoomFramePrefix.Lobby.byte`).

Note: dropping `const` is safe here — every use site is `==`/`!=`/assignment, never a constant
position such as a `when` branch label. It is a binary-compatibility break for a consumer that
inlined the constant, which the repo's pre-1.0 posture accepts.

- [ ] **Step 6: Pin the heartbeat string to its claimed byte**

Append to `kuilt-liveness/src/commonTest/kotlin/us/tractat/kuilt/liveness/` a new file
`HeartbeatPrefixRegistryTest.kt`:

```kotlin
package us.tractat.kuilt.liveness

import us.tractat.kuilt.core.RoomFramePrefix
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The heartbeat family is the odd one out: it discriminates on a **String** prefix, so its claim on
 * `0x6b` in [RoomFramePrefix] is only true by coincidence of encoding. This pins the coincidence, so
 * renaming the string to something starting with a different letter fails here rather than silently
 * vacating a byte the registry still shows as taken.
 */
class HeartbeatPrefixRegistryTest {

    @Test
    fun bothHeartbeatStringsStartWithTheClaimedByte() = assertAll(
        {
            assertEquals(
                RoomFramePrefix.Heartbeat.byte,
                HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray().first(),
                "ping prefix must start with the byte the registry claims for heartbeat",
            )
        },
        {
            assertEquals(
                RoomFramePrefix.Heartbeat.byte,
                HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray().first(),
                "pong prefix must start with the byte the registry claims for heartbeat",
            )
        },
    )
}
```

- [ ] **Step 7: Full module builds + detekt**

```bash
timeout 600 ./gradlew :kuilt-core:build :kuilt-liveness:build :kuilt-session:build detektAll --rerun-tasks
```

Expected: BUILD SUCCESSFUL, tasks `EXECUTED` (not `FROM-CACHE`). A scoped `jvmTest` would not compile
the Android or Kotlin/Native variants — this must.

- [ ] **Step 8: Commit**

```bash
git add kuilt-core kuilt-session kuilt-liveness
git commit -m "feat(core): claim the room-frame byte space in one registry (closes #2007)"
```

---

### Task 2: The lifted relay primitive

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RelayEnvelope.kt`
- Create: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RelayEnvelopeTest.kt`

**Interfaces:**
- Consumes: `RoomFramePrefix.Relay` (Task 1).
- Produces:
  - `public class RelayEnvelope(origin: PeerId, dest: PeerId?, payload: ByteArray)` with
    `RelayEnvelope.encode(RelayEnvelope): ByteArray`, `RelayEnvelope.decode(ByteArray): RelayEnvelope?`,
    `RelayEnvelope.isRelayFrame(ByteArray): Boolean`.
  - `public fun <T> validFirstHop(sender: T, origin: T, trusted: Set<T>): Boolean`
  - `public fun resolveRecipients(dest: PeerId?, members: Set<PeerId>, origin: PeerId): Set<PeerId>`

- [ ] **Step 1: Write the failing test**

Create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RelayEnvelopeTest.kt`:

```kotlin
package us.tractat.kuilt.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayEnvelopeTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val host = PeerId("host")

    @Test
    fun anEnvelopeSurvivesTheWireWithItsOriginIntact() {
        val decoded = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin = alice, dest = bob, payload = byteArrayOf(1, 2, 3))),
        )
        assertAll(
            { assertEquals(alice, decoded?.origin, "origin must survive verbatim — the whole point") },
            { assertEquals(bob, decoded?.dest, "dest must survive") },
            { assertContentEquals(byteArrayOf(1, 2, 3), decoded?.payload, "payload is opaque") },
        )
    }

    @Test
    fun aBroadcastEnvelopeCarriesANullDest() {
        val decoded = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin = alice, dest = null, payload = byteArrayOf(9))),
        )
        assertNull(decoded?.dest, "a null dest is how 'every other member' is expressed on the wire")
    }

    @Test
    fun aFrameOfAnotherFamilyIsNotARelayFrame() = assertAll(
        { assertFalse(RelayEnvelope.isRelayFrame(byteArrayOf(RoomFramePrefix.Channel.byte, 0, 0)), "channel") },
        { assertNull(RelayEnvelope.decode(byteArrayOf(RoomFramePrefix.Admit.byte, 1)), "admit decodes to null") },
        { assertNull(RelayEnvelope.decode(byteArrayOf()), "empty decodes to null") },
    )

    @Test
    fun aGarbledRelayPayloadDecodesToNullRatherThanThrowing() =
        assertAll(
            { assertTrue(RelayEnvelope.isRelayFrame(byteArrayOf(RoomFramePrefix.Relay.byte, 0x7f, 0x7f)), "claims the family") },
            { assertNull(RelayEnvelope.decode(byteArrayOf(RoomFramePrefix.Relay.byte, 0x7f, 0x7f)), "but is not decodable") },
        )

    // ── First-hop validation ──────────────────────────────────────────────────

    @Test
    fun anUntrustedSenderMaySpeakOnlyForItself() = assertAll(
        {
            assertTrue(
                validFirstHop(sender = alice, origin = alice, trusted = emptySet()),
                "a spoke speaking for itself is accepted",
            )
        },
        {
            assertFalse(
                validFirstHop(sender = alice, origin = bob, trusted = emptySet()),
                "a spoke must never forge a co-peer's origin",
            )
        },
    )

    @Test
    fun aTrustedSenderMayCarryAForeignOrigin() =
        assertTrue(
            validFirstHop(sender = host, origin = alice, trusted = setOf(host)),
            "a trusted relay has already validated the origin upstream and preserves it",
        )

    // ── Recipient resolution ──────────────────────────────────────────────────

    private val members = setOf(alice, bob, host)

    @Test
    fun aNullDestResolvesToEveryMemberExceptTheOrigin() =
        assertEquals(
            setOf(bob, host),
            resolveRecipients(dest = null, members = members, origin = alice),
            "broadcast reaches every other member, never the sender",
        )

    @Test
    fun anAddressedDestResolvesToExactlyOneRecipient() =
        assertEquals(
            setOf(bob),
            resolveRecipients(dest = bob, members = members, origin = alice),
            "a unicast cannot fan: the resolved set has one element by construction",
        )

    @Test
    fun anUnknownDestResolvesToNobody() =
        assertEquals(
            emptySet(),
            resolveRecipients(dest = PeerId("ghost"), members = members, origin = alice),
            "an unroutable dest is dropped here — never re-forwarded, never fanned",
        )

    @Test
    fun aDestNamingTheOriginResolvesToNobody() =
        assertEquals(
            emptySet(),
            resolveRecipients(dest = alice, members = members, origin = alice),
            "a frame is never reflected to its own sender",
        )
}
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
timeout 600 ./gradlew :kuilt-core:jvmTest --tests "*RelayEnvelopeTest*"
```

Expected: FAIL — `Unresolved reference: RelayEnvelope`.

- [ ] **Step 3: Implement the primitive**

Create `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RelayEnvelope.kt`:

```kotlin
package us.tractat.kuilt.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * One frame travelling through a middleman with **its true sender kept intact**.
 *
 * Some ways of connecting wire everybody to everybody, so any two members can talk directly. Others
 * wire everybody only to one host, like spokes on a wheel — and then two guests have no way at all
 * to reach each other. The fix is for the host to pass the message along. But an ordinary frame
 * carries no sender of its own: the fabric stamps *who handed me this*, which after a hand-off is the
 * host rather than the member who actually wrote it. This envelope carries the real writer inside the
 * frame so the far end can recover it.
 *
 * [origin] is that real writer and is **never re-stamped** at any hop. [dest] names the one member the
 * frame is for, or `null` for *every other member*. [payload] is opaque — this envelope never inspects
 * it.
 *
 * Encoded with a leading [RoomFramePrefix.Relay] byte, then CBOR. `ignoreUnknownKeys` makes decode
 * forward-compatible with a newer sender.
 *
 * @see validFirstHop for the rule that stops a member forging another's [origin].
 * @see resolveRecipients for turning [dest] into the set to deliver to.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public class RelayEnvelope(
    public val origin: PeerId,
    public val dest: PeerId?,
    public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayEnvelope) return false
        return origin == other.origin && dest == other.dest && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + (dest?.hashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "RelayEnvelope(origin=$origin, dest=$dest, payload=${payload.size}B)"

    public companion object {
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode [envelope] with the [RoomFramePrefix.Relay] framing byte. */
        public fun encode(envelope: RelayEnvelope): ByteArray {
            val encoded = cbor.encodeToByteArray(envelope)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = RoomFramePrefix.Relay.byte
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /** Decode [bytes]; null when the prefix mismatches (another family) or the body is malformed. */
        public fun decode(bytes: ByteArray): RelayEnvelope? {
            if (!isRelayFrame(bytes)) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<RelayEnvelope>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** True if [bytes] claims the relay family. Does not attempt a decode. */
        public fun isRelayFrame(bytes: ByteArray): Boolean = RoomFramePrefix.Relay.matches(bytes)
    }
}

/**
 * The first-hop origin-spoofing rule, applied wherever a relay frame is accepted off a fabric.
 *
 * [RelayEnvelope.origin] rides inside a forgeable frame, so before it is trusted it is checked
 * against the frame's *fabric* sender:
 *
 * - A frame from an **untrusted** sender (not in [trusted]) is accepted only if its [origin] equals
 *   that sender — a member may speak for itself and never for anybody else.
 * - A frame from a **trusted** sender is taken to carry an already-validated [origin]; a trusted
 *   relay preserves identity rather than minting it.
 *
 * Generic in the id type so both a `PeerId`-keyed session relay and a `NodeId`-keyed consensus relay
 * use one rule. A terminal receiver with no upstream passes `trusted = emptySet()`.
 *
 * @return `true` if the frame passes first-hop validation.
 */
public fun <T> validFirstHop(sender: T, origin: T, trusted: Set<T>): Boolean =
    sender in trusted || origin == sender

/**
 * Turn a [RelayEnvelope.dest] into the exact set of members to deliver to.
 *
 * `null` means every member; a named [dest] means that one member, and only if it is actually a
 * [members] entry. The [origin] is always removed — a frame is never reflected to its sender.
 *
 * This function is why a relaying host needs no unicast-versus-broadcast branch: an addressed frame
 * resolves to a set of **one**, so "a unicast never fans out" holds by construction rather than by a
 * guard a later reader could delete. An unknown [dest] resolves to the empty set, so it is dropped
 * where it arrives and never re-forwarded.
 */
public fun resolveRecipients(dest: PeerId?, members: Set<PeerId>, origin: PeerId): Set<PeerId> =
    when (dest) {
        null -> members
        else -> if (dest in members) setOf(dest) else emptySet()
    } - origin
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
timeout 600 ./gradlew :kuilt-core:jvmTest --tests "*RelayEnvelopeTest*"
```

Expected: PASS (10 tests).

- [ ] **Step 5: Full module build + detekt**

```bash
timeout 600 ./gradlew :kuilt-core:build detektAll --rerun-tasks
```

Expected: BUILD SUCCESSFUL, `EXECUTED`.

- [ ] **Step 6: Commit**

```bash
git add kuilt-core
git commit -m "feat(core): lift the relay envelope, first-hop rule and recipient resolver (part of #1994)"
```

---

### Task 3: PROTOTYPE — does the primitive actually subsume the cluster dialect?

**This task ships no production change and its test file is deleted at the end of it.** Its only job
is to answer a question that gates every later task: is `:kuilt-cluster`'s private relay dialect the
same primitive as Task 2's, or merely a similar shape? If it is not, the "lift" is a coincidence and
Tasks 4–5 must be re-planned to build a session-local relay instead — which is far cheaper to learn
now than after `SeamRoom` depends on it.

**Files:**
- Create (then delete in Step 5): `kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RelayPrimitiveSubsumptionPrototypeTest.kt`

**Interfaces:**
- Consumes: `validFirstHop`, `resolveRecipients` (Task 2); `RaftRelay`, `validFirstHop` as declared internally in `:kuilt-cluster`.
- Produces: a written finding recorded in the commit message. No code.

- [ ] **Step 1: Write the prototype**

The hypothesis to falsify: **`RaftRelayHub`'s routing decision is exactly
`validFirstHop` + `resolveRecipients`, with `trusted = voters`, `members = voters`, and `dest` never
null.** Create the file:

```kotlin
package us.tractat.kuilt.cluster

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.resolveRecipients
import us.tractat.kuilt.raft.NodeId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import us.tractat.kuilt.core.validFirstHop as coreValidFirstHop

/**
 * PROTOTYPE — delete after recording the finding (#1994 Task 3).
 *
 * Asks one question: is the core relay primitive the same rule `:kuilt-cluster` already runs, or only
 * a similar shape? Compares verdicts across the full truth table rather than refactoring anything.
 */
class RelayPrimitiveSubsumptionPrototypeTest {

    private val voterA = NodeId("voter-a")
    private val voterB = NodeId("voter-b")
    private val learner = NodeId("learner-1")
    private val voters = setOf(voterA, voterB)

    /** Every (sender, origin) pair that can arrive, against both implementations of the rule. */
    @Test
    fun theFirstHopRuleAgreesOnEveryCase() {
        val cases = listOf(
            Triple(learner, learner, "a learner speaking for itself"),
            Triple(learner, voterA, "a learner forging a voter"),
            Triple(learner, NodeId("other-learner"), "a learner forging a co-learner"),
            Triple(voterA, voterA, "a voter speaking for itself"),
            Triple(voterA, learner, "a core node carrying a validated foreign origin"),
            Triple(voterA, voterB, "a core node carrying another voter's origin"),
        )
        assertAll(
            cases.map { (sender, origin, why) ->
                {
                    assertEquals(
                        validFirstHop(sender = sender, origin = origin, core = voters),
                        coreValidFirstHop(sender = sender, origin = origin, trusted = voters),
                        "the two rules must agree — $why",
                    )
                }
            },
        )
    }

    /**
     * `RaftRelayHub` routes by `dest` to a voter and drops anything else, never re-forwarding. Under
     * the resolver that is the `dest != null` case with `members = voters`.
     */
    @Test
    fun theResolverReproducesTheHubsDestRouting() {
        val peers = voters.map { PeerId(it.value) }.toSet()
        val originPeer = PeerId(learner.value)
        assertAll(
            {
                assertEquals(
                    setOf(PeerId(voterA.value)),
                    resolveRecipients(dest = PeerId(voterA.value), members = peers, origin = originPeer),
                    "a named voter resolves to exactly that voter",
                )
            },
            {
                assertEquals(
                    emptySet(),
                    resolveRecipients(dest = PeerId("not-a-voter"), members = peers, origin = originPeer),
                    "a dest that is not a voter is dropped and never re-forwarded — the hub's rule",
                )
            },
        )
    }
}
```

- [ ] **Step 2: Run it**

```bash
timeout 600 ./gradlew :kuilt-cluster:jvmTest --tests "*RelayPrimitiveSubsumptionPrototypeTest*"
```

Expected: PASS. If `theFirstHopRuleAgreesOnEveryCase` fails, the two rules are **not** the same and
Step 4's gate closes.

- [ ] **Step 3: Record the finding**

Write, in your report to the reviewer, answers to exactly these three questions:

1. Did the first-hop rule agree on all six cases? (yes/no, with the failing case if no)
2. Does `RaftRelay` ever carry a null `dest`? (Search: `grep -rn "RaftRelay(" kuilt-cluster/src`. Expect
   no — every construction names a concrete voter or learner.)
3. What is left over — i.e. what does `:kuilt-cluster` do that the primitive does *not* cover? Expect:
   the `NodeId`↔`PeerId` mapping, constructing `RaftEnvelope(from = origin)`, reading live voters per
   frame, and `RELAY_HEADER_BUDGET` reservation. These are consumer-specific and are *expected* to
   stay in `:kuilt-cluster`.

- [ ] **Step 4: DECISION GATE — stop if the answer is no**

If (1) is **no**, or (2) reveals a null `dest`, or (3) shows the primitive missing something
structural rather than consumer-specific: **STOP. Do not start Task 4.** Report to the reviewer that
the lift does not hold and that Tasks 4–5 need re-planning against a session-local relay. This is a
success of the prototype, not a failure of the plan.

If all three are as expected, proceed.

- [ ] **Step 5: Delete the prototype and commit the finding**

The prototype has answered its question; keeping it would leave a test that asserts two
implementations agree while inviting neither to change.

```bash
rm kuilt-cluster/src/commonTest/kotlin/us/tractat/kuilt/cluster/RelayPrimitiveSubsumptionPrototypeTest.kt
git add -A kuilt-cluster
git commit --allow-empty -m "$(cat <<'EOF'
test(cluster): prototype-verify the lifted relay primitive subsumes the cluster dialect (part of #1994)

Throwaway prototype, deleted in this same commit. Recorded finding: the core
first-hop rule agrees with :kuilt-cluster's on all six (sender, origin) cases, and
the resolver reproduces RaftRelayHub's dest routing including drop-never-re-forward.
RaftRelay never carries a null dest, so the cluster dialect is the addressed case of
the primitive. What stays consumer-specific: NodeId<->PeerId mapping, RaftEnvelope
construction, live-voter reads, and the RELAY_HEADER_BUDGET reservation.

So the lift holds and #1994 may build on it. Landing the migration remains a
follow-up, deliberately not bundled with a feature.
EOF
)"
```

---

### Task 4: Host-side forwarding

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` — `restartIncomingCollect` (~:820-832), plus new private methods beside it
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayForwardingTest.kt`

**Interfaces:**
- Consumes: `RelayEnvelope`, `validFirstHop`, `resolveRecipients` (Task 2).
- Produces: `SeamRoom` forwards relay frames. No new public API.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayForwardingTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.RelayEnvelope
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A host relays a joiner's frames to a co-joiner the joiner cannot address (#1994).
 *
 * On a star fabric a spoke's seam holds one link — to the host — so `Room.sendTo(coJoiner)` had no
 * route and `Room.broadcast` reached only the host (pinned in `:kuilt-core` by
 * `StarTopologyPeerRoutingTest`). These tests pin the relay that closes it, and the two properties
 * that must not be traded away for it: a unicast never fans, and a forged origin never lands.
 */
class StarRelayForwardingTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    @Test
    fun aJoinerReachesACoJoinerItCannotAddress() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        val bSeen = mutableListOf<RoomFrame>()
        backgroundScope.launch { star.joinerB.incoming.collect { bSeen += it } }
        testScheduler.runCurrent()

        star.joinerA.sendTo(star.joinerBId, "hello-b".encodeToByteArray())
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()

        assertAll(
            {
                assertEquals(
                    listOf("hello-b"),
                    bSeen.map { it.payload.decodeToString() },
                    "the co-joiner must receive the frame the sender could not address directly",
                )
            },
            {
                assertEquals(
                    listOf(star.joinerAId),
                    bSeen.map { it.sender },
                    "and must see the ORIGINAL sender, not the relaying host",
                )
            },
        )
    }

    @Test
    fun aUnicastForOneJoinerReachesNobodyElse() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        val hostSeen = mutableListOf<RoomFrame>()
        val cSeen = mutableListOf<RoomFrame>()
        backgroundScope.launch { star.host.incoming.collect { hostSeen += it } }
        backgroundScope.launch { star.joinerC.incoming.collect { cSeen += it } }
        testScheduler.runCurrent()

        star.joinerA.sendTo(star.joinerBId, "for-b-only".encodeToByteArray())
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    hostSeen.none { it.payload.decodeToString() == "for-b-only" },
                    "the relaying host must not deliver a frame addressed to someone else into its own incoming",
                )
            },
            {
                assertTrue(
                    cSeen.none { it.payload.decodeToString() == "for-b-only" },
                    "a third joiner must never observe a unicast — a relayed unicast must not fan",
                )
            },
        )
    }

    @Test
    fun aJoinerBroadcastReachesEveryOtherMember() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        val hostSeen = mutableListOf<RoomFrame>()
        val bSeen = mutableListOf<RoomFrame>()
        val cSeen = mutableListOf<RoomFrame>()
        backgroundScope.launch { star.host.incoming.collect { hostSeen += it } }
        backgroundScope.launch { star.joinerB.incoming.collect { bSeen += it } }
        backgroundScope.launch { star.joinerC.incoming.collect { cSeen += it } }
        testScheduler.runCurrent()

        star.joinerA.broadcast("to-all".encodeToByteArray())
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()

        assertAll(
            { assertTrue(hostSeen.any { it.payload.decodeToString() == "to-all" }, "the host receives it") },
            { assertTrue(bSeen.any { it.payload.decodeToString() == "to-all" }, "co-joiner B receives it") },
            { assertTrue(cSeen.any { it.payload.decodeToString() == "to-all" }, "co-joiner C receives it") },
            {
                assertEquals(
                    listOf(star.joinerAId),
                    bSeen.filter { it.payload.decodeToString() == "to-all" }.map { it.sender },
                    "with the original sender preserved",
                )
            },
        )
    }

    @Test
    fun aJoinerCannotForgeACoJoinersOrigin() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        val bSeen = mutableListOf<RoomFrame>()
        backgroundScope.launch { star.joinerB.incoming.collect { bSeen += it } }
        testScheduler.runCurrent()

        // A hand-built envelope claiming to originate from C, sent by A straight at the host.
        star.joinerASeam.sendTo(
            star.hostId,
            RelayEnvelope.encode(
                RelayEnvelope(origin = star.joinerCId, dest = star.joinerBId, payload = "forged".encodeToByteArray()),
            ),
        )
        testScheduler.advanceTimeBy(1.seconds)
        testScheduler.runCurrent()

        assertTrue(
            bSeen.none { it.payload.decodeToString() == "forged" },
            "the host must refuse a frame whose origin is not its fabric sender — a member speaks only for itself",
        )
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private class Star(
        val host: Room,
        val hostId: PeerId,
        val joinerA: Room,
        val joinerAId: PeerId,
        val joinerASeam: us.tractat.kuilt.core.Seam,
        val joinerB: Room,
        val joinerBId: PeerId,
        val joinerC: Room,
        val joinerCId: PeerId,
    )

    /** A four-member star over the real hub fabric: one host, three mutually-unroutable joiners. */
    private suspend fun TestScope.star(): Star {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))

        val hostRoom = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock, fastConfig).host(Pattern(ROOM))

        val aLoom = fabric.clientLoom(PeerId("joiner-a"), Random(1L))
        val bLoom = fabric.clientLoom(PeerId("joiner-b"), Random(2L))
        val cLoom = fabric.clientLoom(PeerId("joiner-c"), Random(3L))
        val aSeam = aLoom.join(InMemoryTag(ROOM))
        val aRoom = SeamRoomFactory(aLoom, backgroundScope, clock, fastConfig).adopt(aSeam, SessionRole.Joiner)
        val bRoom = SeamRoomFactory(bLoom, backgroundScope, clock, fastConfig).join(InMemoryTag(ROOM))
        val cRoom = SeamRoomFactory(cLoom, backgroundScope, clock, fastConfig).join(InMemoryTag(ROOM))

        hostRoom.roster.first { it.size == 3 }
        aRoom.roster.first { it.size == 3 }
        bRoom.roster.first { it.size == 3 }
        cRoom.roster.first { it.size == 3 }

        return Star(
            host = hostRoom,
            hostId = hostRoom.selfId,
            joinerA = aRoom,
            joinerAId = aRoom.selfId,
            joinerASeam = aSeam,
            joinerB = bRoom,
            joinerBId = bRoom.selfId,
            joinerC = cRoom,
            joinerCId = cRoom.selfId,
        )
    }

    private companion object {
        const val ROOM = "table"

        /**
         * Generous wedge backstop, never a tight assertion: `runTest`'s timeout is wall-clock over a
         * virtual-time trajectory, so tightening it measures the host rather than the code. Fast
         * failure comes from the bounded `advanceTimeBy` above.
         */
        val BACKSTOP = 30.seconds
    }
}
```

Add `import kotlinx.coroutines.flow.first` for the `roster.first` calls.

- [ ] **Step 2: Run it to make sure it fails**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayForwardingTest*"
```

Expected: FAIL — `aJoinerReachesACoJoinerItCannotAddress` fails because `sendTo` throws
`PeerNotConnected` (Task 5 adds the send side; this task adds the receive side, so expect the
forgery test to be the first to pass).

- [ ] **Step 3: Add the host-side forwarding**

In `SeamRoom.kt`, replace the body of `restartIncomingCollect`'s collect lambda and add three private
methods. Change:

```kotlin
                seam.incoming.collect { swatch ->
                    rawIncoming.emit(swatch)
                    dispatchIncoming(swatch)
                }
```

to:

```kotlin
                seam.incoming.collect { swatch -> onInbound(swatch) }
```

Then add, immediately after `restartIncomingCollect`:

```kotlin
    /**
     * One inbound frame off the seam.
     *
     * A [RelayEnvelope] is handled first and never reaches [dispatchIncoming] wrapped — the host
     * forwards it, a joiner unwraps it. Every other frame takes the pre-existing path untouched.
     */
    private suspend fun onInbound(swatch: Swatch) {
        if (RelayEnvelope.isRelayFrame(swatch.toByteArray())) {
            handleRelayFrame(swatch)
            return
        }
        rawIncoming.emit(swatch)
        dispatchIncoming(swatch)
    }

    /**
     * A relayed frame. On the **host** this is the forwarding hop; on a **joiner** it is the arrival
     * of a frame some co-member could not address directly.
     *
     * Host: the trust boundary. It has no upstream relay, so it trusts nobody to carry a foreign
     * origin — `trusted = emptySet()` means [validFirstHop] demands `origin == sender`, and a member
     * therefore speaks only for itself.
     *
     * Joiner: only the identified host may hand over a relayed frame. A joiner-to-joiner relay frame
     * on a flat fabric is a forgery attempt and is dropped, mirroring [handleFarewell]'s and
     * [handlePaused]'s host-authoritative gates.
     */
    private suspend fun handleRelayFrame(swatch: Swatch) {
        val sender = swatch.sender ?: return
        val relay = RelayEnvelope.decode(swatch.toByteArray()) ?: return
        if (_role.value == SessionRole.Host) {
            if (!validFirstHop(sender = sender, origin = relay.origin, trusted = emptySet())) {
                logger.debug {
                    "relay.refused origin=${relay.origin.value} sender=${sender.value} reason=origin-not-sender"
                }
                return
            }
            forwardRelay(relay)
            return
        }
        val host = lock.withLock { hostPeerId }
        if (host == null || sender != host) {
            logger.debug { "relay.refused sender=${sender.value} reason=not-the-identified-host" }
            return
        }
        deliverRelayed(relay)
    }

    /**
     * Host-side: deliver [relay] to every member [resolveRecipients] names — itself included, when the
     * envelope was a broadcast.
     *
     * There is no unicast-versus-broadcast branch here on purpose: an addressed envelope resolves to a
     * set of exactly one, so "a relayed unicast never fans" is a property of the resolver rather than
     * of a guard here. An unknown `dest` resolves to the empty set and is dropped — never re-forwarded.
     *
     * Each onward hop is re-wrapped with the **concrete** recipient as `dest` and the origin
     * preserved verbatim. Best-effort per recipient: one torn spoke must not abort the rest.
     */
    private suspend fun forwardRelay(relay: RelayEnvelope) {
        val members = lock.withLock { admittedById.keys.toSet() } + selfId
        for (recipient in resolveRecipients(dest = relay.dest, members = members, origin = relay.origin)) {
            if (recipient == selfId) {
                deliverRelayed(relay)
            } else {
                val onward = RelayEnvelope.encode(
                    RelayEnvelope(origin = relay.origin, dest = recipient, payload = relay.payload),
                )
                runCatchingCancellable { seam.sendTo(recipient, onward) }
                    .onFailure { logger.debug { "relay.forward.failed to=${recipient.value}" } }
            }
        }
    }

    /**
     * Hand the relayed inner frame to this peer's own pipeline as though the fabric had delivered it
     * from [RelayEnvelope.origin] — which is exactly what the envelope exists to reconstruct.
     *
     * Emitting the **unwrapped** swatch into [rawIncoming] is what keeps
     * [RoomChannelSeam]'s `room.isAdmitted(swatch.sender)` filter meaningful; the wrapped frame is
     * never emitted.
     */
    private suspend fun deliverRelayed(relay: RelayEnvelope) {
        val inner = Swatch(payload = relay.payload, sender = relay.origin)
        rawIncoming.emit(inner)
        dispatchIncoming(inner)
    }
```

Add imports: `us.tractat.kuilt.core.RelayEnvelope`, `us.tractat.kuilt.core.resolveRecipients`,
`us.tractat.kuilt.core.validFirstHop`.

- [ ] **Step 4: Run the tests**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayForwardingTest*"
```

Expected: `aJoinerCannotForgeACoJoinersOrigin` PASSES. The other three still FAIL — the send side is
Task 5. Confirm the failure is `PeerNotConnected`, not something else.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session
git commit -m "feat(session): forward relayed frames on the host, origin preserved (part of #1994)"
```

---

### Task 5: Joiner-side send routing

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` — `broadcast` (~:1604) and `sendTo` (~:1615)

**Interfaces:**
- Consumes: Task 4's forwarding; `RelayEnvelope` (Task 2).
- Produces: `Room.broadcast`/`Room.sendTo` reach every admitted member on a star. No new public API.

- [ ] **Step 1: Confirm the three remaining tests still fail**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayForwardingTest*"
```

Expected: 1 passing, 3 failing.

- [ ] **Step 2: Route unroutable targets through the host**

Replace `SeamRoom.broadcast` and `SeamRoom.sendTo` with:

```kotlin
    /**
     * Broadcast [bytes] to all admitted members.
     *
     * On a fabric where every member is directly connected this is a plain fabric broadcast. Where the
     * roster contains a member this peer's transport cannot address — a spoke of a star — the frame is
     * relayed through the host instead, which fans it to the rest (#1994). The divergence between the
     * roster and the transport's `peers` is itself the trigger, so a mesh keeps the direct path at zero
     * cost, as does a two-peer room whose roster is `{self, host}`.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     */
    override suspend fun broadcast(bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        val reachable = seam.peers.value
        if (_rosterPeers.value.all { it in reachable }) {
            seam.broadcast(bytes)
            return
        }
        // No identified host yet ⇒ nothing to relay through; degrade to the direct path unchanged.
        val host = lock.withLock { hostPeerId }
        if (host == null) {
            seam.broadcast(bytes)
            return
        }
        seam.sendTo(host, RelayEnvelope.encode(RelayEnvelope(origin = selfId, dest = null, payload = bytes)))
    }

    /**
     * Send [bytes] to one specific admitted member.
     *
     * Directly when the transport can address [peer]; otherwise relayed through the host, which
     * forwards it to exactly that one member (#1994). Absent an identified host there is no relay
     * path, so the original [us.tractat.kuilt.core.PeerNotConnected] surfaces from the fabric rather
     * than the frame vanishing.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        if (peer in seam.peers.value) {
            seam.sendTo(peer, bytes)
            return
        }
        val host = lock.withLock { hostPeerId }
        if (host == null) {
            seam.sendTo(peer, bytes)
            return
        }
        seam.sendTo(host, RelayEnvelope.encode(RelayEnvelope(origin = selfId, dest = peer, payload = bytes)))
    }
```

- [ ] **Step 3: Run the tests to verify all four pass**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayForwardingTest*"
```

Expected: PASS (4 tests).

- [ ] **Step 4: Run the whole session suite — nothing may regress**

```bash
timeout 600 ./gradlew :kuilt-session:build detektAll --rerun-tasks
```

Expected: BUILD SUCCESSFUL, `EXECUTED`. Pay particular attention to `LivenessRouteGateTest` and every
admit/reconnect test — `broadcast`/`sendTo` are on the admit path.

- [ ] **Step 5: Commit**

```bash
git add kuilt-session
git commit -m "feat(session): relay a joiner's unroutable sends through the host (part of #1994)"
```

---

### Task 6: Protocol version bump

Relay changes what a room means: a joiner that relays to a host which does not forward black-holes
silently for as long as it runs, which is exactly how #1994 was found. Refuse the pairing at admit.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/ProtocolVersion.kt`
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RelayProtocolVersionTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ProtocolVersion.CURRENT == 2`, `MIN_SUPPORTED == 2`, `MAX_SUPPORTED == 2`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RelayProtocolVersionTest.kt`:

```kotlin
package us.tractat.kuilt.session

import us.tractat.kuilt.session.admit.ProtocolVersion
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A relay-capable room cannot interoperate with one that does not forward (#1994).
 *
 * A pre-relay host admits a relaying joiner happily and then silently drops every relayed frame — a
 * black hole discovered only by noticing state never converges. The version gate turns that into an
 * immediate, named refusal at admit time.
 */
class RelayProtocolVersionTest {

    @Test
    fun relayMovedTheSupportedVersionFloor() = assertAll(
        { assertEquals(2, ProtocolVersion.CURRENT, "relay is version 2") },
        { assertEquals(2, ProtocolVersion.MIN_SUPPORTED, "a pre-relay peer must be refused, not tolerated") },
    )

    @Test
    fun aPreRelayPeerIsRefused() = assertAll(
        { assertFalse(ProtocolVersion.isSupported(1), "version 1 cannot relay, so it cannot join") },
        { assertTrue(ProtocolVersion.isSupported(2), "version 2 relays") },
    )
}
```

- [ ] **Step 2: Run it to make sure it fails**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*RelayProtocolVersionTest*"
```

Expected: FAIL — `expected:<2> but was:<1>`.

- [ ] **Step 3: Move the floor**

In `ProtocolVersion.kt`, set `CURRENT`, `MIN_SUPPORTED` and `MAX_SUPPORTED` to `2`, and add to the
object's KDoc:

```
 * ## Version 2 — host relay (#1994)
 *
 * A version-2 room relays a joiner's peer-addressed and broadcast frames through the host, so the
 * roster is genuinely routable on a star fabric. A version-1 peer does not forward: pairing the two
 * would admit the joiner and then silently discard every relayed frame. Hence MIN_SUPPORTED moves
 * with CURRENT rather than tolerating 1 — a loud refusal at admit beats a black hole found by
 * noticing state never converges.
```

Note: `isSupported(null)` stays permissive by design (a version-less `Hello` predates the field). That
is deliberate and is **not** changed here — a peer old enough to omit the field cannot relay, but the
existing legacy carve-out is not this task's to revisit.

- [ ] **Step 4: Run the test to verify it passes**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*RelayProtocolVersionTest*"
```

Expected: PASS (2 tests).

- [ ] **Step 5: Full build — existing admit tests may pin version 1**

```bash
timeout 600 ./gradlew :kuilt-session:build detektAll --rerun-tasks
```

If a test asserts `protocolVersion = 1` or `isSupported(1)`, update it to the new floor and say so in
the commit body.

- [ ] **Step 6: Commit**

```bash
git add kuilt-session
git commit -m "feat(session): move the protocol floor to 2 for host relay (part of #1994)"
```

---

### Task 7: The done criteria — a Quilter converges between spokes

**Files:**
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayQuilterConvergenceTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 4–6.
- Produces: the test that closes #1994.

- [ ] **Step 1: Write the failing test**

`:kuilt-session` already test-depends on `:kuilt-quilter`, so no build-file change is needed. Use a
`GSet<String>` — the smallest CRDT that makes convergence observable. The `Quilter` construction below
is copied from the verified shape in `MemberMetadataConvergenceTest:54-61`: it takes `replica`, `seam`,
`initial`, `messageSerializer`, `scope`, `config`; it **auto-starts** (no `start()` call); mutation is
`apply(patch)`; and `QuilterConfig(expectVirtualTime = true)` is required under virtual time.
`GSet.add(element)` already returns a `Patch<GSet<E>>`, so no `Patch(...)` wrapper is needed.

```kotlin
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.liveness.HeartbeatConfig
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import us.tractat.kuilt.test.fabric.InMemoryRoomFabric
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The #1994 done criterion: a [Quilter] on two spokes of a star **converges**.
 *
 * `RoomChannelSeam` publishes the roster as its `peers` but routed `sendTo` through the transport, so
 * on a star every peer-addressed Quilter path — full-state, anti-entropy, resend, ack — threw
 * `PeerNotConnected`, and `broadcast` reached only the host. Two spokes therefore exchanged no state
 * at all. With the host relaying, the roster is genuinely routable and the Quilter needs no change.
 */
class StarRelayQuilterConvergenceTest {

    private val fastConfig = HeartbeatConfig(
        interval = 100.milliseconds,
        timeout = 300.milliseconds,
        reconnectWindow = 1.seconds,
    )

    @Test
    fun oneSpokesMutationBecomesVisibleOnTheOther() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        star.quilterA.apply(star.quilterA.state.value.add("from-a"))
        // Bounded advance; never advanceUntilIdle() — the room's heartbeat timers re-arm forever, so
        // the idle state is never reached and the test would hang rather than fail.
        testScheduler.advanceTimeBy(5.seconds)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    "from-a" in star.quilterB.state.value.elements,
                    "spoke B must observe spoke A's mutation — this is the #1994 done criterion; " +
                        "B saw ${star.quilterB.state.value.elements}",
                )
            },
            {
                assertTrue(
                    "from-a" in star.quilterA.state.value.elements,
                    "sanity: the originating spoke still holds its own mutation",
                )
            },
        )
    }

    @Test
    fun bothSpokesConvergeOnConcurrentMutations() = runTest(StandardTestDispatcher(), timeout = BACKSTOP) {
        val star = star()

        star.quilterA.apply(star.quilterA.state.value.add("a"))
        star.quilterB.apply(star.quilterB.state.value.add("b"))
        testScheduler.advanceTimeBy(5.seconds)
        testScheduler.runCurrent()

        assertAll(
            {
                assertTrue(
                    setOf("a", "b").all { it in star.quilterA.state.value.elements },
                    "A must hold both; saw ${star.quilterA.state.value.elements}",
                )
            },
            {
                assertTrue(
                    setOf("a", "b").all { it in star.quilterB.state.value.elements },
                    "B must hold both; saw ${star.quilterB.state.value.elements}",
                )
            },
        )
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private class Star(
        val quilterA: Quilter<GSet<String>>,
        val quilterB: Quilter<GSet<String>>,
    )

    private suspend fun TestScope.star(): Star {
        val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
            "no dispatcher (ContinuationInterceptor) in coroutine context"
        }
        val clock: () -> Instant = { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
        val fabric = InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))

        val hostRoom = SeamRoomFactory(fabric.serverLoom, backgroundScope, clock, fastConfig).host(Pattern(ROOM))
        val aLoom = fabric.clientLoom(PeerId("joiner-a"), Random(1L))
        val bLoom = fabric.clientLoom(PeerId("joiner-b"), Random(2L))
        val aRoom = SeamRoomFactory(aLoom, backgroundScope, clock, fastConfig).join(InMemoryTag(ROOM))
        val bRoom = SeamRoomFactory(bLoom, backgroundScope, clock, fastConfig).join(InMemoryTag(ROOM))

        hostRoom.roster.first { it.size == 2 }
        aRoom.roster.first { it.size == 2 }
        bRoom.roster.first { it.size == 2 }

        return Star(
            quilterA = quilter(aRoom),
            quilterB = quilter(bRoom),
        )
    }

    /**
     * One [Quilter] per room over the same channel id. Exactly one instance per
     * `(replica, CRDT type)` per process — two sharing a [ReplicaId] both mint deltas from `seq = 1`
     * and diverge permanently.
     */
    private fun TestScope.quilter(room: Room): Quilter<GSet<String>> = Quilter(
        replica = ReplicaId(room.selfId.value),
        seam = room.channel(CHANNEL),
        initial = GSet.empty(),
        messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
        scope = backgroundScope,
        config = QuilterConfig(expectVirtualTime = true),
    )

    private companion object {
        const val ROOM = "table"
        const val CHANNEL = "state"

        /** Generous wedge backstop, never a tight assertion — see `StarRelayForwardingTest.BACKSTOP`. */
        val BACKSTOP = 30.seconds
    }
}
```

- [ ] **Step 2: Run it**

```bash
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayQuilterConvergenceTest*"
```

Expected: PASS. If it fails on a timeout rather than an assertion, the cause is convergence, not the
ceiling — **do not raise `BACKSTOP`.** Read `kuilt-session/build/test-results/jvmTest/*.xml` for the
real stack and the `time=` attribute, and diagnose which Quilter path is still not delivering.

- [ ] **Step 3: Prove the test actually pins the relay (revert-check)**

```bash
git stash push kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt
timeout 600 ./gradlew :kuilt-session:jvmTest --tests "*StarRelayQuilterConvergenceTest*"
git stash pop
```

Expected: FAIL while stashed, PASS after popping. A test that passes without the fix pins nothing.

**Do not use `git stash` if any other agent may be working in this repo** — `refs/stash` is shared by
every linked worktree. Instead copy the file aside with `cp`, revert, test, and restore from the copy.

- [ ] **Step 4: Mutation-check the guard the relay branch now precedes**

`onInbound`'s relay branch runs **before** `dispatchIncoming`'s `isAdmittedPeer(sender) ->
routeApplicationFrame` arm. Adding a check ahead of an existing one can silently drop the older
guard's coverage to zero while the suite stays green — this repo has hit that four times. Verify, do
not assume:

1. Invert the **old** guard: change `isAdmittedPeer(sender) ->` to `!isAdmittedPeer(sender) ->`.
2. `timeout 600 ./gradlew :kuilt-session:jvmTest` — **confirm a non-zero exit before reading any
   XML.** A mutation that fails to compile makes an XML-parsing check report the *previous* run's
   verdict, inverting the answer toward a plausible-looking finding.
3. Record which tests go red. If **none** do, the old guard is now unpinned — say so in your report;
   it is a finding, not a blocker for this task.
4. Restore the guard.
5. Repeat for the **pair**: invert the old guard *and* remove the relay branch's host-role check
   together. A test can pin `G_new ∨ G_old` and neither conjunct, which a one-at-a-time survey cannot
   see.

- [ ] **Step 5: Full build**

```bash
timeout 600 ./gradlew build detektAll --rerun-tasks
```

The **whole** build, not a module-scoped one: relay changes room-level runtime behaviour, and a
module-scoped build skips the `:examples`/`:kuilt-cluster` E2E tests that exercise the full stack —
`:kuilt-cluster` in particular rides `Room.channel("raft")`.

- [ ] **Step 6: Commit**

```bash
git add kuilt-session
git commit -m "test(session): pin that a Quilter converges between two spokes of a star (part of #1994)"
```

---

### Task 8: Documentation

**Files:**
- Modify: `docs/fabric-peer-routing.md` — the matrix and the "What follows" section
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt:44-56` — `peers` KDoc
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt:88-92` — `broadcast`/`sendTo` KDoc
- Modify: `docs/agent-cookbook.md` — a `RelayEnvelope` entry
- Check: `.claude/skills/kuilt-primitives/SKILL.md` still routes to the relay

**Interfaces:**
- Consumes: the shipped behaviour from Tasks 4–7.
- Produces: docs only.

- [ ] **Step 1: Update the routing matrix**

In `docs/fabric-peer-routing.md`, move these four rows from "Star without relay / **No**" to "Star
with relay / **Yes, via the host**", each citing #1994: `RoomHubSeam`/`MuxServerLoom`, `MeshSeam` via
`hubMesh` (spoke side), `:kuilt-multipeer`, `:kuilt-nearby`.

Add a paragraph after the matrix:

```markdown
## Star with relay — how the middle column got filled

Until #1994 no shipped fabric was in the middle category: every star was a star *without* relay. A
room's host now forwards a joiner's frames to a co-joiner the joiner cannot address, carrying the
original sender inside the frame so the far end credits the right member. The four rows above
therefore changed answer without any of those fabrics changing: the relay lives in the session layer
(`SeamRoom`), above all of them.

Note what did **not** change: liveness detectors still run only on **direct** edges. Relaying every
member's "are you still there?" ping through the host would rebuild the O(N²) traffic #1576 removed,
so presence for a member with no direct edge still comes from the host's authoritative fan-out
(#1557). Routability and liveness are deliberately different questions.
```

Also update the leading paragraph's claim that the check is switched off "exactly where there is
nobody listening" — that is still true, but add that data frames now do travel there.

- [ ] **Step 2: Update `Seam.peers` KDoc**

Append to the `peers` KDoc in `Seam.kt`:

```
     * **Membership versus routability.** On most fabrics these coincide: a peer is here exactly when
     * a direct connection to it exists. A room over a star fabric is the exception — its view
     * publishes every admitted member, and the host relays frames to those a spoke cannot address
     * directly (#1994). So a peer in this set is always *reachable*, but not necessarily reachable in
     * one hop.
```

- [ ] **Step 3: Update `Room.broadcast`/`sendTo` KDoc**

```kotlin
    /**
     * Broadcast [bytes] to all other admitted members.
     *
     * Reaches every admitted member on every fabric. Where this peer's transport cannot address a
     * member directly — a spoke of a star — the frame is relayed through the host (#1994).
     */
    public suspend fun broadcast(bytes: ByteArray)

    /**
     * Send [bytes] to one specific admitted member.
     *
     * Reaches that member on every fabric, directly where the transport can address it and relayed
     * through the host where it cannot (#1994). A relayed send reaches **only** its addressee — it is
     * never fanned out.
     */
    public suspend fun sendTo(peer: PeerId, bytes: ByteArray)
```

- [ ] **Step 4: Add the cookbook entry and its compiled sample**

First append the sample function to
`kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt`. These are
compiled as part of `commonTest`, so a typo here breaks the build — that is deliberate.

```kotlin
/**
 * Two guests in one room see each other's shared state, whatever the fabric.
 *
 * On a hub/star fabric a guest holds one connection — to the host — so nothing it sent used to reach
 * another guest at all (#1994). The host now passes those frames along, carrying the original sender
 * inside the frame, so the roster is genuinely routable and this code is the same on every fabric.
 * There is nothing to wire: do **not** hand-roll a server-side rebroadcast, and do not narrow the
 * replicator's targets to the host.
 */
public fun roomStateConvergesBetweenGuestsSample(room: Room): Quilter<GSet<String>> = Quilter(
    replica = ReplicaId(room.selfId.value),
    // The channel view's peers is the admitted roster, and every member of it is reachable — directly
    // where the transport can address it, relayed via the host where it cannot.
    seam = room.channel("state"),
    initial = GSet.empty(),
    messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
    scope = room.scopeForSamples,
)
```

**Implementer note:** the samples file has no `scope` in hand. Match how a neighbouring sample in that
file obtains one — if none does, take `scope: CoroutineScope` as a parameter of the sample function
instead of the `room.scopeForSamples` placeholder above, and drop that property. Verify with
`timeout 600 ./gradlew :kuilt-session:compileTestKotlinJvm`.

Then add the entry to `docs/agent-cookbook.md`, in the **Replicated data** section, after the
`sampleQuilterSetup` entry, matching the surrounding two-line **Intent:**/**Primitive:** shape:

```markdown
**Intent:** two guests in the same room can't see each other's changes — "my shared state syncs with the server but not between clients", "the other player's moves never arrive", "it converges on the host only".
**Primitive:** nothing new — `Room.channel(id)` + `Quilter`, unchanged. On a hub/star fabric the host relays a guest's frames to a guest it cannot address directly, preserving the original sender (#1994), so the roster is routable and a replicator needs no per-fabric special case. Don't write a server-side rebroadcast, and don't narrow `deltaTargets` to the host — both re-create by hand what the room already does.

<!-- verbatim from kuilt-session/src/commonSamples/kotlin/us/tractat/kuilt/session/AgentCookbookSamples.kt#roomStateConvergesBetweenGuestsSample -->
```

followed by the sample function's body copied verbatim into a `kotlin` fence.

- [ ] **Step 4b: Record in the CODE why the detector gate stays narrow**

Once the roster is routable, `startDetector`'s route gate reads as over-conservative and invites a
"fix" that would rebuild the O(N²) heartbeat traffic #1576 removed. Append to the "Route gate (#1576)"
block of `startDetector`'s KDoc (`SeamRoom.kt`, around :1279-1300):

```
     * ## Why this gate stays narrow after #1994
     *
     * #1994 made the roster genuinely routable: the host relays a member's frames to a member it
     * cannot address directly. That does **not** widen this gate, and must not. A detector's traffic
     * is per-peer, continuous and O(N²) in the roster; relaying all of it through the host is exactly
     * the load #1576 removed. Data is relayed; liveness is not. Presence for a member with no direct
     * edge still comes from the host's authoritative fan-out ([AdmitMessage.Paused] /
     * [AdmitMessage.Unpaused] / [AdmitMessage.Farewell], #1557).
     *
     * So `member.id !in seam.peers.value` here means "no *direct* edge", which is the right question
     * for liveness even though it is no longer the right question for routability.
```

- [ ] **Step 5: Check the skill still routes**

```bash
grep -n "roster\|relay\|converge" .claude/skills/kuilt-primitives/SKILL.md
```

Confirm its `description` would fire for "clients can't see each other's state". If not, add the
phrasing. Report what you changed.

- [ ] **Step 6: Verify docs-only and commit**

```bash
timeout 600 ./gradlew :kuilt-core:build :kuilt-session:build detektAll --rerun-tasks
git add docs kuilt-core kuilt-session .claude
git commit -m "docs: record that four star fabrics now relay, and what deliberately did not change (closes #1994)"
```

---

## Follow-ups to file after the plan lands

Per the repo's proactive-follow-up rule, file these as issues when Task 8 commits — do not leave them
implicit:

1. **`TieredSeam.sendTo` silently drops** a peer owned by neither tier (`TieredSeam.kt:180`) — the
   worst #1576 variant, and the one an exception-keyed check cannot detect. Should throw
   `PeerNotConnected`.
2. **Migrate `:kuilt-cluster` onto the lifted primitive.** Task 3 proves it fits; landing it was
   deliberately kept out of a feature track. Reference Task 3's recorded finding.
