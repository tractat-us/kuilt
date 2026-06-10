# FairRandom Commit-Reveal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `FairRandom`, a two-phase commit-reveal protocol in `:kuilt-deal` that lets N peers derive an identical 32-byte random seed over a `Seam` with no trusted dealer.

**Architecture:** Each peer commits `SHA-256(domain‖round‖peerId‖secret)`, then reveals its `secret`; once all reveals verify against their commitments, every peer derives the same seed from the `PeerId`-sorted secrets. Frames are CBOR over `seam.broadcast`; the instance collects `seam.incoming` once (mirroring `DealSession`). Cheats throw `FairRandomAborted(culprits)`; liveness is the caller's `withTimeout`.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines, kotlinx-serialization-cbor, KotlinCrypto `sha2` (new dep), the existing `internal secureRandomBytes` CSPRNG.

**Spec:** `docs/superpowers/specs/2026-06-09-fair-random-commit-reveal-design.md`

---

## File structure

| File | Responsibility |
|------|----------------|
| `gradle/libs.versions.toml` | Pin `kotlincrypto-sha2` (modify) |
| `kuilt-deal/build.gradle.kts` | Add `sha2` to `commonMain` (modify) |
| `kuilt-test/.../FakeLoom.kt` | Add `fakeSeamMesh` N-peer helper (modify) |
| `kuilt-deal/.../deal/Seed.kt` | The 32-byte seed result type (create) |
| `kuilt-deal/.../deal/FairRandomFrame.kt` | CBOR wire frames + `FairRandomAborted` (create) |
| `kuilt-deal/.../deal/FairRandom.kt` | The protocol driver (create) |
| `kuilt-test/.../test/FakeSeamMeshTest.kt` | Mesh helper test (create) |
| `kuilt-deal/.../deal/SeedTest.kt` | Seed accessors + SHA-256 known vector (create) |
| `kuilt-deal/.../deal/FairRandomFrameTest.kt` | Frame CBOR round-trip (create) |
| `kuilt-deal/.../deal/FairRandomTest.kt` | Protocol behaviour (create) |

Source-set root for `:kuilt-deal` common code is
`kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/`, tests
`kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/`. For `:kuilt-test`,
`kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/` and the matching
`commonTest`.

**Environment for every test run** (non-interactive shell):
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
```

---

## Task 1: Add KotlinCrypto sha2 dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `kuilt-deal/build.gradle.kts`
- Test: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/SeedTest.kt` (SHA-256 vector portion)

- [ ] **Step 1: Write a failing SHA-256 known-vector test**

Create `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/SeedTest.kt`:

```kotlin
package us.tractat.kuilt.deal

import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedTest {

    @Test
    fun sha256MatchesFips180KnownVector() {
        // FIPS 180-4 example: SHA-256("abc")
        val digest = SHA256().digest("abc".encodeToByteArray())
        val hex = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile (dependency missing)**

Run: `./gradlew :kuilt-deal:compileTestKotlinJvm`
Expected: FAIL — unresolved reference `org.kotlincrypto.hash.sha2.SHA256`.

- [ ] **Step 3: Add the version + library to the catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:
```toml
kotlincrypto-sha2 = "0.8.0"  # synchronous multiplatform SHA-256 (incl. wasmJs) for kuilt-deal commit-reveal
```
Under `[libraries]` add (next to the `ionspin-bignum` library line):
```toml
kotlincrypto-sha2 = { module = "org.kotlincrypto.hash:sha2", version.ref = "kotlincrypto-sha2" }
```

- [ ] **Step 4: Wire the dependency into kuilt-deal**

In `kuilt-deal/build.gradle.kts`, inside `commonMain.dependencies { … }`, add after the `ionspin.bignum` line:
```kotlin
            implementation(libs.kotlincrypto.sha2)
```

- [ ] **Step 5: Run the test on every platform to confirm the dep resolves**

Run: `./gradlew :kuilt-deal:allTests --tests "*SeedTest"`
Expected: PASS on jvm, wasmJs, iosSimulatorArm64, macosArm64 (proves KotlinCrypto resolves on all targets). If wasmJs fails to resolve, the version is wrong — recheck the latest at `org.kotlincrypto.hash:sha2`.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml kuilt-deal/build.gradle.kts kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/SeedTest.kt
git commit -m "feat(kuilt-deal): add KotlinCrypto sha2 for commit-reveal hashing (#311)"
```

---

## Task 2: N-peer fake seam mesh helper

**Files:**
- Modify: `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeLoom.kt`
- Test: `kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/FakeSeamMeshTest.kt`

Context: `FakeSeam` exposes `internal var onBroadcast`, `internal fun nextSequence()`, and `suspend fun deliver(Swatch)`. `fakeSeamPair` already cross-wires two seams via `onBroadcast`. The mesh fans each broadcast out to every *other* peer.

- [ ] **Step 1: Write the failing test**

Create `kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/FakeSeamMeshTest.kt`:

```kotlin
package us.tractat.kuilt.test

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeSeamMeshTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")
    private val carol = PeerId("carol")

    @Test
    fun broadcastReachesEveryOtherPeerButNotSelf() = runTest {
        val (a, b, c) = fakeSeamMesh(alice, bob, carol)
        a.broadcast(byteArrayOf(7))

        val toBob = b.incoming.first()
        val toCarol = c.incoming.first()
        assertAll(
            { assertEquals(alice, toBob.sender) },
            { assertEquals(7, toBob.payload.single()) },
            { assertEquals(alice, toCarol.sender) },
            { assertEquals(7, toCarol.payload.single()) },
            { assertEquals(setOf(alice, bob, carol), a.peers.value) },
        )
    }
}
```

Note: `fakeSeamMesh(alice, bob, carol)` returns `List<FakeSeam>`; Kotlin destructures a `List` of size ≥3 via `component1/2/3`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :kuilt-test:jvmTest --tests "*FakeSeamMeshTest"`
Expected: FAIL — unresolved reference `fakeSeamMesh`.

- [ ] **Step 3: Implement `fakeSeamMesh`**

In `kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeLoom.kt`, add after the `wireDelivery` private function:

```kotlin
/**
 * Cross-wire N [FakeSeam]s into a fully-connected mesh: a [FakeSeam.broadcast] on
 * any one is delivered to every *other* seam's [Seam.incoming], stamped with the
 * sender's id and a receiver-local sequence. Each seam's peers set is all [ids].
 *
 * ```kotlin
 * val (a, b, c) = fakeSeamMesh(PeerId("a"), PeerId("b"), PeerId("c"))
 * a.broadcast(byteArrayOf(1))   // arrives at b.incoming and c.incoming, not a
 * ```
 */
public fun fakeSeamMesh(vararg ids: PeerId): List<FakeSeam> {
    require(ids.toSet().size == ids.size) { "fakeSeamMesh peer ids must be distinct: ${ids.toList()}" }
    val all = ids.toSet()
    val seams = ids.map { FakeSeam(selfId = it, initialPeers = all) }
    for (sender in seams) {
        val receivers = seams.filter { it !== sender }
        sender.onBroadcast = { payload ->
            for (receiver in receivers) {
                receiver.deliver(
                    us.tractat.kuilt.core.Swatch(
                        payload = payload,
                        sender = sender.selfId,
                        sequence = receiver.nextSequence(),
                    ),
                )
            }
        }
    }
    return seams
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kuilt-test:jvmTest --tests "*FakeSeamMeshTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-test/src/commonMain/kotlin/us/tractat/kuilt/test/FakeLoom.kt kuilt-test/src/commonTest/kotlin/us/tractat/kuilt/test/FakeSeamMeshTest.kt
git commit -m "feat(kuilt-test): add fakeSeamMesh N-peer wiring helper (#311)"
```

---

## Task 3: The `Seed` result type

**Files:**
- Create: `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/Seed.kt`
- Test: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/SeedTest.kt` (extend)

- [ ] **Step 1: Add failing tests for the Seed accessors**

Append to the existing `SeedTest` class body in `SeedTest.kt`:

```kotlin
    @Test
    fun toLongReadsFirstEightBytesBigEndian() {
        val bytes = ByteArray(32)
        bytes[0] = 0x01
        bytes[7] = 0x02
        val seed = Seed(bytes)
        assertEquals(0x0100_0000_0000_0002L, seed.toLong())
    }

    @Test
    fun asRandomIsDeterministicForEqualSeeds() {
        val bytes = ByteArray(32) { it.toByte() }
        val a = Seed(bytes.copyOf()).asRandom().nextInt()
        val b = Seed(bytes.copyOf()).asRandom().nextInt()
        assertEquals(a, b)
    }

    @Test
    fun constructorRejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { Seed(ByteArray(31)) }
    }
```

Add imports at the top of `SeedTest.kt`:
```kotlin
import kotlin.test.assertFailsWith
```
And add the `assertAll` import if Task 2's pattern is reused — not needed here.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*SeedTest"`
Expected: FAIL — unresolved reference `Seed`.

- [ ] **Step 3: Implement `Seed`**

Create `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/Seed.kt`:

```kotlin
package us.tractat.kuilt.deal

import kotlin.random.Random

/**
 * A 32-byte seed agreed by all peers in a [FairRandom] round.
 *
 * [bytes] is the full SHA-256 output; [toLong] and [asRandom] are convenience
 * views over its first 8 bytes for the common dice/shuffle cases. Consumers that
 * need all 256 bits read [bytes] directly.
 */
public class Seed internal constructor(public val bytes: ByteArray) {

    init {
        require(bytes.size == 32) { "Seed must be 32 bytes, was ${bytes.size}" }
    }

    /** The first 8 bytes interpreted big-endian. Handy for seeding [Random]. */
    public fun toLong(): Long {
        var acc = 0L
        for (i in 0 until 8) acc = (acc shl 8) or (bytes[i].toLong() and 0xFF)
        return acc
    }

    /** A [Random] seeded from [toLong]. Identical seeds yield identical streams. */
    public fun asRandom(): Random = Random(toLong())

    override fun equals(other: Any?): Boolean = other is Seed && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "Seed(${bytes.size} bytes)"
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*SeedTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/Seed.kt kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/SeedTest.kt
git commit -m "feat(kuilt-deal): add Seed result type for fair random (#311)"
```

---

## Task 4: Wire frames + abort exception

**Files:**
- Create: `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandomFrame.kt`
- Test: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomFrameTest.kt`

- [ ] **Step 1: Write the failing CBOR round-trip test**

Create `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomFrameTest.kt`:

```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FairRandomFrameTest {

    @Test
    fun commitRoundTripsThroughCbor() {
        val frame: FairRandomFrame = FairRandomFrame.Commit(3, PeerId("alice"), byteArrayOf(1, 2, 3))
        val decoded = Cbor.decodeFromByteArray<FairRandomFrame>(Cbor.encodeToByteArray(frame))
        assertTrue(decoded is FairRandomFrame.Commit)
        assertAll(
            { assertEquals(3, decoded.round) },
            { assertEquals(PeerId("alice"), decoded.peer) },
            { assertEquals(listOf<Byte>(1, 2, 3), decoded.commit.toList()) },
        )
    }

    @Test
    fun revealRoundTripsThroughCbor() {
        val frame: FairRandomFrame = FairRandomFrame.Reveal(0, PeerId("bob"), byteArrayOf(9))
        val decoded = Cbor.decodeFromByteArray<FairRandomFrame>(Cbor.encodeToByteArray(frame))
        assertTrue(decoded is FairRandomFrame.Reveal)
        assertEquals(PeerId("bob"), decoded.peer)
    }
}
```

This file uses `assertAll`; the project already provides it for `:kuilt-deal` tests (see `CardStateTest`). If the import is needed, it resolves from the same test source set — match `CardStateTest`'s usage (no explicit import there).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomFrameTest"`
Expected: FAIL — unresolved reference `FairRandomFrame`.

- [ ] **Step 3: Implement the frames and the exception**

Create `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandomFrame.kt`:

```kotlin
package us.tractat.kuilt.deal

import kotlinx.serialization.Serializable
import us.tractat.kuilt.core.PeerId

/** Wire frames for the [FairRandom] commit-reveal protocol. CBOR-encoded over a Seam. */
@Serializable
internal sealed interface FairRandomFrame {

    /** Phase 1: the sender's commitment hash for [round]. */
    @Serializable
    data class Commit(val round: Int, val peer: PeerId, val commit: ByteArray) : FairRandomFrame

    /** Phase 2: the sender's revealed secret for [round]. */
    @Serializable
    data class Reveal(val round: Int, val peer: PeerId, val secret: ByteArray) : FairRandomFrame
}

/**
 * Thrown by [FairRandom.roll] when one or more peers reveal a secret that does not
 * match the commitment they broadcast. [culprits] names exactly those peers so the
 * application can enforce its forfeit policy.
 */
public class FairRandomAborted(
    public val culprits: Set<PeerId>,
) : Exception("FairRandom round aborted; commitment mismatch from: ${culprits.joinToString { it.value }}")
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomFrameTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandomFrame.kt kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomFrameTest.kt
git commit -m "feat(kuilt-deal): add FairRandom wire frames + abort exception (#311)"
```

---

## Task 5: The `FairRandom` driver — construction, commit/derive helpers

This task builds the class skeleton, constructor validation, and the pure hashing
helpers, tested in isolation. `roll()` arrives in Task 6.

**Files:**
- Create: `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandom.kt`
- Test: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt`

- [ ] **Step 1: Write failing construction + determinism tests**

Create `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt`:

```kotlin
package us.tractat.kuilt.deal

import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.FakeSeam
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FairRandomTest {

    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test
    fun constructorRejectsSelfNotInPeers() = runTest {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice))
        assertFailsWith<IllegalArgumentException> {
            FairRandom(seam, peers = setOf(bob), scope = backgroundScope)
        }
    }

    @Test
    fun constructorRejectsEmptyPeers() = runTest {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice))
        assertFailsWith<IllegalArgumentException> {
            FairRandom(seam, peers = emptySet(), scope = backgroundScope)
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: FAIL — unresolved reference `FairRandom`.

- [ ] **Step 3: Implement the class skeleton + helpers**

Create `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandom.kt`:

```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.deal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.kotlincrypto.hash.sha2.SHA256
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam

/**
 * Derives a shared random [Seed] across [peers] via a two-phase commit-reveal
 * protocol over a [Seam], with no trusted dealer.
 *
 * Each [roll] runs one round: every peer commits `SHA-256(domain‖round‖peerId‖secret)`,
 * then reveals its `secret`; once all reveals verify, every peer derives an
 * identical [Seed] from the secrets sorted by [PeerId].
 *
 * **Single collector.** This collects [Seam.incoming] directly, so there must be
 * exactly one collector per [Seam] (the seam contract). Do not run a second
 * consumer (e.g. a `DealSession`) on the same seam.
 *
 * **Lock-step.** [roll] is a barrier — a peer cannot finish round *r* until every
 * peer has committed for *r* — so all participants must call [roll] the same number
 * of times. Rolls on one instance are serialized.
 *
 * **Liveness vs. cheating.** A reveal that does not match its commitment makes
 * [roll] throw [FairRandomAborted] naming the culprits. A peer that never reveals
 * makes [roll] suspend; wrap the call in `withTimeout` to bound it.
 */
public class FairRandom(
    private val seam: Seam,
    private val peers: Set<PeerId>,
    scope: CoroutineScope,
) {
    init {
        require(peers.isNotEmpty()) { "FairRandom needs at least one peer" }
        require(seam.selfId in peers) { "peers must contain seam.selfId (${seam.selfId.value})" }
    }

    private val selfId: PeerId = seam.selfId

    /** Serializes whole rolls so round numbers stay in lock-step. Not held across map mutation. */
    private val rollMutex = Mutex()

    /** Guards [rounds] + each round's maps. Only ever held briefly — never across a network await. */
    private val stateMutex = Mutex()

    private var nextRound = 0
    private val rounds = mutableMapOf<Int, Round>()

    private class Round(val expected: Set<PeerId>) {
        val commits = mutableMapOf<PeerId, ByteArray>()
        val reveals = mutableMapOf<PeerId, ByteArray>()
        val allCommitted = CompletableDeferred<Unit>()
        val allRevealed = CompletableDeferred<Unit>()

        fun putCommit(peer: PeerId, commit: ByteArray) {
            if (peer !in expected || peer in commits) return
            commits[peer] = commit
            if (commits.keys == expected) allCommitted.complete(Unit)
        }

        fun putReveal(peer: PeerId, secret: ByteArray) {
            if (peer !in expected || peer in reveals) return
            reveals[peer] = secret
            if (reveals.keys == expected) allRevealed.complete(Unit)
        }
    }

    init {
        seam.incoming
            .onEach { swatch ->
                val frame = try {
                    Cbor.decodeFromByteArray<FairRandomFrame>(swatch.payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@onEach   // drop malformed/foreign frames; never cancel the collector
                }
                stateMutex.withLock {
                    when (frame) {
                        is FairRandomFrame.Commit ->
                            roundFor(frame.round).putCommit(frame.peer, frame.commit)
                        is FairRandomFrame.Reveal ->
                            roundFor(frame.round).putReveal(frame.peer, frame.secret)
                    }
                }
            }
            .launchIn(scope)
    }

    /** Get-or-create the round buffer for [round]. Caller holds [stateMutex]. */
    private fun roundFor(round: Int): Round = rounds.getOrPut(round) { Round(peers) }

    private companion object {
        val DOMAIN = "kuilt/fair-random/v1".encodeToByteArray()
        val DOMAIN_DERIVE = "kuilt/fair-random/v1/derive".encodeToByteArray()
    }

    internal fun commitOf(round: Int, peer: PeerId, secret: ByteArray): ByteArray {
        val peerBytes = peer.value.encodeToByteArray()
        return SHA256().digest(DOMAIN + intBytes(round) + intBytes(peerBytes.size) + peerBytes + secret)
    }

    internal fun deriveSeed(round: Int, secretsByPeer: Map<PeerId, ByteArray>): Seed {
        var acc = DOMAIN_DERIVE + intBytes(round)
        for (peer in secretsByPeer.keys.sortedBy { it.value }) {
            acc += secretsByPeer.getValue(peer)
        }
        return Seed(SHA256().digest(acc))
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: PASS (both construction-rejection tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandom.kt kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt
git commit -m "feat(kuilt-deal): FairRandom skeleton + commit/derive helpers (#311)"
```

---

## Task 6: `roll()` happy path

**Files:**
- Modify: `kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandom.kt`
- Test: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt` (extend)

- [ ] **Step 1: Add the failing happy-path tests**

Add these imports to the top of `FairRandomTest.kt`:
```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import us.tractat.kuilt.test.fakeSeamMesh
import kotlin.test.assertEquals
import kotlin.test.assertTrue
```
And the `carol` field next to `alice`/`bob`:
```kotlin
    private val carol = PeerId("carol")
```
Append to the `FairRandomTest` class:

```kotlin
    @Test
    fun threePeersDeriveTheSameSeed() = runTest {
        val (sa, sb, sc) = fakeSeamMesh(alice, bob, carol)
        val fa = FairRandom(sa, setOf(alice, bob, carol), backgroundScope)
        val fb = FairRandom(sb, setOf(alice, bob, carol), backgroundScope)
        val fc = FairRandom(sc, setOf(alice, bob, carol), backgroundScope)

        val seeds = listOf(
            async { fa.roll() },
            async { fb.roll() },
            async { fc.roll() },
        ).awaitAll()

        assertAll(
            { assertEquals(seeds[0], seeds[1]) },
            { assertEquals(seeds[1], seeds[2]) },
            { assertEquals(32, seeds[0].bytes.size) },
        )
    }

    @Test
    fun consecutiveRoundsProduceDifferentSeeds() = runTest {
        val (sa, sb) = fakeSeamMesh(alice, bob)
        val fa = FairRandom(sa, setOf(alice, bob), backgroundScope)
        val fb = FairRandom(sb, setOf(alice, bob), backgroundScope)

        val r0 = listOf(async { fa.roll() }, async { fb.roll() }).awaitAll()
        val r1 = listOf(async { fa.roll() }, async { fb.roll() }).awaitAll()

        assertAll(
            { assertEquals(r0[0], r0[1]) },
            { assertEquals(r1[0], r1[1]) },
            { assertTrue(r0[0] != r1[0]) },  // fresh secrets each round
        )
    }

    @Test
    fun singlePeerDerivesASeed() = runTest {
        val seam = FakeSeam(selfId = alice, initialPeers = setOf(alice))
        val fa = FairRandom(seam, setOf(alice), backgroundScope)
        assertEquals(32, fa.roll().bytes.size)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: FAIL — unresolved reference `roll`.

- [ ] **Step 3: Implement `roll()`**

Add the `secureRandomBytes` call (already `internal` in this module) and append this method to the `FairRandom` class, after `deriveSeed`'s companion/helpers (place it as a public member before the closing brace):

```kotlin
    /**
     * Run one commit-reveal round and return the agreed [Seed]. Suspends through the
     * commit and reveal phases. Throws [FairRandomAborted] if any peer's reveal does
     * not match its commitment. Suspends indefinitely if a peer never reveals — wrap
     * in `withTimeout` to bound liveness.
     */
    public suspend fun roll(): Seed = rollMutex.withLock {
        val round = nextRound++
        val secret = secureRandomBytes(32)
        val myCommit = commitOf(round, selfId, secret)

        val state = stateMutex.withLock {
            roundFor(round).also { it.putCommit(selfId, myCommit) }
        }
        seam.broadcast(Cbor.encodeToByteArray<FairRandomFrame>(FairRandomFrame.Commit(round, selfId, myCommit)))
        state.allCommitted.await()

        stateMutex.withLock { state.putReveal(selfId, secret) }
        seam.broadcast(Cbor.encodeToByteArray<FairRandomFrame>(FairRandomFrame.Reveal(round, selfId, secret)))
        state.allRevealed.await()

        val (commits, reveals) = stateMutex.withLock {
            rounds.remove(round)
            state.commits.toMap() to state.reveals.toMap()
        }

        val culprits = peers.filterTo(mutableSetOf()) { peer ->
            !commitOf(round, peer, reveals.getValue(peer)).contentEquals(commits.getValue(peer))
        }
        if (culprits.isNotEmpty()) throw FairRandomAborted(culprits)

        deriveSeed(round, reveals)
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: PASS (all five tests).

- [ ] **Step 5: Commit**

```bash
git add kuilt-deal/src/commonMain/kotlin/us/tractat/kuilt/deal/FairRandom.kt kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt
git commit -m "feat(kuilt-deal): FairRandom.roll commit-reveal happy path (#311)"
```

---

## Task 7: Cheater detection

**Files:**
- Test only: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt` (extend)

The cheating peer broadcasts a `Reveal` whose secret doesn't match its `Commit`.
We simulate this with a hand-driven `FakeSeam` mesh: the honest peer runs a real
`FairRandom`; the cheater's frames are injected directly.

- [ ] **Step 1: Add the failing test**

Add imports to `FairRandomTest.kt`:
```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
```
(place the `@file:OptIn` as the very first line of the file, above `package`), plus:
```kotlin
import kotlinx.coroutines.launch
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlin.test.assertEquals
```
Append to the class:

```kotlin
    @Test
    fun mismatchedRevealAbortsNamingTheCheater() = runTest {
        val (sa, sb) = fakeSeamMesh(alice, bob)
        val fa = FairRandom(sa, setOf(alice, bob), backgroundScope)

        // Bob is the cheater: commit to one secret, reveal a different one.
        val bobSecret = ByteArray(32) { 1 }
        val bobWrongReveal = ByteArray(32) { 2 }
        val fb = FairRandom(sb, setOf(alice, bob), backgroundScope)
        // Drive bob's commit honestly through his own FairRandom helper, then inject a bad reveal.
        val bobCommit = fb.commitOf(0, bob, bobSecret)

        val rolled = async { fa.roll() }

        // Bob commits (honest hash) then reveals the WRONG secret.
        sb.broadcast(Cbor.encodeToByteArray<FairRandomFrame>(FairRandomFrame.Commit(0, bob, bobCommit)))
        sb.broadcast(Cbor.encodeToByteArray<FairRandomFrame>(FairRandomFrame.Reveal(0, bob, bobWrongReveal)))

        val error = assertFailsWith<FairRandomAborted> { rolled.await() }
        assertEquals(setOf(bob), error.culprits)
    }
```

Note: `commitOf` is `internal`, so it is visible from the same-module test source set.

- [ ] **Step 2: Run to verify it fails or passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: PASS — the cheater path is already implemented in Task 6, so this test confirms it. If it fails, the culprit-set construction in `roll()` is wrong; fix `roll()` until green.

- [ ] **Step 3: Commit**

```bash
git add kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt
git commit -m "test(kuilt-deal): FairRandom aborts naming a mismatched-reveal cheater (#311)"
```

---

## Task 8: Liveness — silent peer is bounded by withTimeout

**Files:**
- Test only: `kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt` (extend)

- [ ] **Step 1: Add the failing test**

Add imports:
```kotlin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
```
Append to the class:

```kotlin
    @Test
    fun rollSuspendsWhenAPeerNeverReveals_boundedByWithTimeout() = runTest {
        val (sa, _) = fakeSeamMesh(alice, bob)
        val fa = FairRandom(sa, setOf(alice, bob), backgroundScope)
        // Bob never sends anything; alice's roll can never complete its commit phase.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.seconds) { fa.roll() }
        }
    }
```

Note: under `runTest`, `withTimeout` uses the test's virtual clock, so this
completes instantly (no real 1-second wait) when `roll()` is genuinely stuck.

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew :kuilt-deal:jvmTest --tests "*FairRandomTest"`
Expected: PASS — `roll()` suspends on `allCommitted.await()`; the timeout cancels it.

- [ ] **Step 3: Commit**

```bash
git add kuilt-deal/src/commonTest/kotlin/us/tractat/kuilt/deal/FairRandomTest.kt
git commit -m "test(kuilt-deal): FairRandom liveness bounded by withTimeout (#311)"
```

---

## Task 9: Cross-platform verification + full build

**Files:** none (verification only).

- [ ] **Step 1: Run the deal module on all platforms**

Run: `./gradlew :kuilt-deal:allTests :kuilt-test:allTests`
Expected: PASS on jvm, wasmJs, iosSimulatorArm64, macosArm64. The wasmJs run exercises the `crypto.getRandomValues` CSPRNG and KotlinCrypto's wasm SHA-256 together — the riskiest target.

If wasmJs times out on a single crypto-heavy test (a known hazard in this module — see `kuilt-deal/build.gradle.kts` and `karma.config.d/`), raise the Mocha browser timeout there, do **not** weaken the test.

- [ ] **Step 2: Full build (catches Android-variant, explicitApi, detekt)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `explicitApi()` is enforced — every new public declaration (`FairRandom`, `Seed`, `FairRandomAborted`, `roll`, `fakeSeamMesh`) must carry an explicit `public`. Fix any visibility or detekt finding until green.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(kuilt-deal): cross-platform + explicitApi cleanup for FairRandom (#311)"
```
(Skip if the working tree is clean after the build.)

---

## Self-review notes (for the executor)

- **Spec coverage:** Task 1 → hash dep; Task 2 → N-peer mesh; Task 3 → `Seed`
  (toLong/bytes/asRandom); Task 4 → frames + `FairRandomAborted`; Task 5 →
  construction validation + commit/derive (domain tag, round, peer-length prefix,
  PeerId-sorted derivation); Task 6 → `roll()` two-phase + lock-step + 1-peer;
  Task 7 → cheater; Task 8 → liveness; Task 9 → all-platform build. Nonce
  deliberately absent (256-bit secret is its own blinding factor, per spec).
- **No-collision note:** `roll()` never holds `stateMutex` across a network
  `await`, and the `incoming` collector only ever takes `stateMutex` briefly — so
  the collector can complete `allCommitted`/`allRevealed` while a `roll()` awaits
  them. Do not "simplify" by wrapping the whole `roll()` body in `stateMutex`; that
  deadlocks.
- **Type consistency:** `commitOf(round, peer, secret)`, `deriveSeed(round, map)`,
  `FairRandomFrame.Commit/Reveal`, `Seed(bytes)` used identically across tasks.
```
