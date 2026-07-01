# Real-phone tap reach — mDNS+WS fabric + join-token admission — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the shipped log tap (`:kuilt-otel-tap`) *reach* — drive its replication `Seam` over mDNS-discovered LAN WebSocket so a laptop pulls logs off a real iOS device with no manual addressing — behind an opt-in, short-lived **join-token** admission gate. The default (loopback-bound, `Open`, off) stays byte-identical.

**Architecture:** A fabric-agnostic **token-gated `Seam` decorator** (`TokenGatedSeam`, mirroring `:kuilt-session`'s `PrincipalSeam`/`withPrincipal`) runs a challenge-response on first contact: the **offering** side (holds the code) challenges with a nonce; the **pulling** side answers `HMAC-SHA256(code, nonce)`; the offering side verifies constant-time within a TTL and only then surfaces the peer to the `Quilter`. Because the tap's `Quilter` replication is symmetric, the *offer role* is decoupled from the *rendezvous role*: for iOS (which cannot host a WS server nor advertise mDNS) the **laptop hosts** and the **iOS device joins** — a new `installLogTapJoining` entry point — and the iOS buffer still replicates to the laptop.

**Scope boundary (locked, see spec):** mDNS+WS only. Apple Multipeer is follow-up **#1042**; `wss://`/TLS is follow-up **#1043**. `ws://` is plaintext-on-wire by design — the token gates *who may pull*, not *confidentiality on the wire* (the documented honest seam).

**Tech Stack:** Kotlin Multiplatform; kotlinx-coroutines (`MutableSharedFlow`/`MutableStateFlow`/`Channel`); kotlinx-serialization CBOR (`LogTapCbor`); kotlinx.atomicfu (`reentrantLock`); KotlinCrypto `hmac-sha2` (`HmacSHA256`); `:kuilt-quilter` (`Quilter`); `:kuilt-mdns` + `:kuilt-websocket` (`MDNSPeerLinkFactory`, `KtorServerLoom`/`KtorClientLoom`, `MDNSServiceDiscoverer`); JUnit + `kotlinx-coroutines-test`.

**Spec:** `docs/superpowers/specs/2026-07-01-realphone-tap-reach-design.md`.

## Global Constraints

- `explicitApi()` is enforced — every new public declaration gets explicit `public`/`internal`. No `!!`.
- Test method names carry no `test` prefix; `@Test` suffices. Multi-assert tests use `assertAll()`.
- Coroutine tests use `StandardTestDispatcher(testScheduler)`, a **seeded** `Random`, and a virtual `kotlin.time.Clock`. **No production dispatchers** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) in test sources.
- Scope-owning types take a **required** injected `CoroutineScope` — never a real-dispatcher default. `TokenGatedSeam` owns a relay coroutine; its scope is required.
- **Thread-safety by real primitives, never confinement.** Guard the gate's pending-nonce map / verified-peer set with an atomicfu `reentrantLock` (suspend `seam.sendTo` calls kept **outside** the locked section), exactly as `SeamRoom` does. The gate must be correct under a multi-threaded dispatcher.
- In any coroutine/suspend context use `runCatchingCancellable` (from `us.tractat.kuilt.core`), never bare `runCatching`. Best-effort challenge/reject sends: `runCatchingCancellable { seam.sendTo(peer, bytes) }.onFailure { logger.debug { … } }`.
- **Security:** the token/code must never appear in `toString()` or logs beyond the deliberate one-time issuance print. The tag comparison is **constant-time** (no `contentEquals` early-return). The code never crosses the wire — only `HMAC(code, nonce)` does.
- References policy: abstract use case only; no third-party citations; no other `tractat-us/*` repos.
- Verify before declaring done: `./gradlew :kuilt-otel-tap:build detektAll --rerun-tasks` (add `--no-build-cache` if any test-compile shows `FROM-CACHE`); `detektAll`, never bare `detekt`. Full `./gradlew build` before the PR (the gate is `commonMain` → Android + Native + wasmJs must compile).

**PR split (per issue #1028, "likely split"):**
- **PR A — admission gate** (Tasks 1–5, 7): the reusable, fully unit-tested token gate + the offer/rendezvous-role split, verified over loopback WS. Safe to land alone (loopback still the default; the gate is additive).
- **PR B — mDNS+WS reach** (Tasks 6, 8), stacked on PR A: the mDNS wiring sample + gated multicast integration test + manual real-device validation notes.

Reason for the order: the gate must exist before any non-loopback reach ships, so reach is never exposed un-gated.

---

### Task 1: HMAC-SHA256 primitive + constant-time compare (RFC 4231 vectors)

**Files:**
- Modify: `gradle/libs.versions.toml` (add the MACs library)
- Modify: `kuilt-otel-tap/build.gradle.kts` (commonMain dep + atomicfu)
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TapMac.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TapMacTest.kt`

**Interfaces:**
- Produces: `internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray`; `internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean`.

- [ ] **Step 1: Add catalog entries**

In `gradle/libs.versions.toml`, under `[versions]` (next to the existing `kotlincrypto-hash = "0.7.0"`) add:
```toml
kotlincrypto-macs = "0.7.0"
```
Under `[libraries]` (next to `kotlincrypto-hash-sha2`) add:
```toml
# KotlinCrypto HMAC-SHA256 — multiplatform MAC for the log-tap join-token admission gate:
kotlincrypto-macs-hmac-sha2 = { module = "org.kotlincrypto.macs:hmac-sha2", version.ref = "kotlincrypto-macs" }
```
> **Version note:** KotlinCrypto MACs is released in lockstep with the hash module already vendored (`0.7.0`). If `0.7.0` fails to resolve for `org.kotlincrypto.macs:hmac-sha2`, bump `kotlincrypto-macs` to the MACs release aligned with the resolved `kotlincrypto-hash` version. KotlinCrypto supports every kuilt target (its SHA-256 sibling already compiles on JVM/Android/iOS/macOS/**wasmJs** in `:kuilt-deal`).

- [ ] **Step 2: Write the failing test**

`TapMacTest.kt` — pins RFC 4231 Test Case 2 (a known HMAC-SHA256 vector) so the primitive is verified, not merely present:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapMacTest {
    // RFC 4231 §4.3 Test Case 2: key = "Jefe", data = "what do ya want for nothing?"
    @Test
    fun hmacSha256MatchesRfc4231TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expectedHex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        assertEquals(expectedHex, hmacSha256(key, data).toHex())
    }

    @Test
    fun constantTimeEqualsMatchesContent() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew :kuilt-otel-tap:jvmTest --tests "*TapMacTest"`
Expected: FAIL — unresolved `hmacSha256`, `constantTimeEquals` (add the deps first so it compiles to the failure).

- [ ] **Step 4: Wire deps + write minimal implementation**

In `kuilt-otel-tap/build.gradle.kts`, add to `commonMain.dependencies`:
```kotlin
            implementation(libs.kotlincrypto.macs.hmac.sha2)
            implementation(libs.kotlinx.atomicfu)
```
`TapMac.kt`:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/** HMAC-SHA256 of [message] under [key]. KMP-uniform (KotlinCrypto). */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    HmacSHA256(key).doFinal(message)

/**
 * Constant-time byte-array equality — no early return on first mismatch, so timing
 * does not leak how many leading bytes matched. Used to compare admission tags.
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
```
> **API note:** confirm the KotlinCrypto MACs surface for the resolved version — `HmacSHA256(key).doFinal(message)` is the stable one-shot form; if the resolved version exposes `HmacSHA256(key).apply { update(message) }.doFinal()` instead, adjust. The class is `org.kotlincrypto.macs.hmac.sha2.HmacSHA256`.

- [ ] **Step 5: Run test to verify it passes + commit**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*TapMacTest"` → PASS.
```bash
git add gradle/libs.versions.toml kuilt-otel-tap/build.gradle.kts \
        kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TapMac.kt \
        kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TapMacTest.kt
git commit -m "feat(otel-tap): HMAC-SHA256 + constant-time compare for tap admission (RFC 4231 pinned)"
```

---

### Task 2: `LogTapJoinToken` — the short-lived join code

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/LogTapJoinToken.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/LogTapJoinTokenTest.kt`

**Interfaces:**
- Produces: `class LogTapJoinToken(code, issuedAt: Instant, ttl: Duration)` with `fun isValid(now: Instant): Boolean`; `companion object.issue(random: Random, clock: Clock, ttl: Duration = DEFAULT_TTL): LogTapJoinToken`. `code` is 8-char Crockford base32 (locked default). `toString()` **redacts** the code.

- [ ] **Step 1: Write the failing test**

`LogTapJoinTokenTest.kt`:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LogTapJoinTokenTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private fun clockAt(i: Instant) = object : Clock { override fun now() = i }

    @Test
    fun issuesADeterministicCodeFromSeededRandom() {
        val a = LogTapJoinToken.issue(Random(42), clockAt(t0), ttl = 5.minutes)
        val b = LogTapJoinToken.issue(Random(42), clockAt(t0), ttl = 5.minutes)
        assertEquals(a.code, b.code)                 // seeded Random ⇒ reproducible
        assertEquals(8, a.code.length)               // 8-char code
        assertTrue(a.code.all { it in CROCKFORD_ALPHABET })
    }

    @Test
    fun validWithinTtlAndExpiredAfter() {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        assertTrue(token.isValid(t0))
        assertTrue(token.isValid(t0 + 4.minutes))
        assertFalse(token.isValid(t0 + 5.minutes + 1.seconds))
    }

    @Test
    fun toStringDoesNotLeakTheCode() {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        assertFalse(token.toString().contains(token.code))
    }

    private companion object { const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }
}
```
(Add the needed `kotlin.time.Duration.Companion.seconds` / `minutes` imports.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*LogTapJoinTokenTest"` → FAIL (unresolved `LogTapJoinToken`).

- [ ] **Step 3: Write minimal implementation**

`LogTapJoinToken.kt`:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A short-lived join code that authorizes a debugger peer to pull the log tap.
 *
 * The device **shows** this code (printed to the platform log / an app debug UI);
 * the operator types it into the puller. The code itself never crosses the wire —
 * the puller proves knowledge of it via `HMAC(code, nonce)` (see the admission
 * gate). Valid only within [ttl] of [issuedAt]; reusable for repeated pulls /
 * reconnects inside that window.
 *
 * The code is secret material: [toString] redacts it, and it must never be logged
 * except at the deliberate one-time issuance print.
 */
public class LogTapJoinToken(
    public val code: String,
    public val issuedAt: Instant,
    public val ttl: Duration,
) {
    /** True while [now] is within [issuedAt] `..` [issuedAt] + [ttl]. */
    public fun isValid(now: Instant): Boolean = now >= issuedAt && now <= issuedAt + ttl

    /** Redacted — never exposes [code]. */
    override fun toString(): String = "LogTapJoinToken(code=****, issuedAt=$issuedAt, ttl=$ttl)"

    public companion object {
        /** Default pairing window: long enough to read + type the code, short enough to bound guessing. */
        public val DEFAULT_TTL: Duration = 5.minutes

        // Crockford base32 (no I/L/O/U — unambiguous when read aloud/typed).
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LEN = 8

        /**
         * Mint a fresh token: an 8-char code from [random], stamped with [clock] `now`.
         * [random] and [clock] are **required** (determinism) — seed [random] in tests.
         */
        public fun issue(random: Random, clock: Clock, ttl: Duration = DEFAULT_TTL): LogTapJoinToken {
            val code = buildString(CODE_LEN) { repeat(CODE_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return LogTapJoinToken(code, clock.now(), ttl)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes + commit**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*LogTapJoinTokenTest"` → PASS.
```bash
git add kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/LogTapJoinToken.kt \
        kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/LogTapJoinTokenTest.kt
git commit -m "feat(otel-tap): LogTapJoinToken — short-lived, redacted, seeded-issue join code"
```

---

### Task 3: `TapAdmitMessage` — the challenge-response wire

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TapAdmitMessage.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TapAdmitMessageTest.kt`

**Interfaces:**
- Produces: `sealed interface TapAdmitMessage { Challenge(nonce: ByteArray); Proof(tag: ByteArray); Reject(reason: String) }` with `encode`/`decode`/`isAdmitFrame` companion, using a discriminator **prefix byte** so admit frames are distinguishable from the `Quilter`'s replication frames (mirrors `us.tractat.kuilt.session.admit.AdmitMessage`).

**Design note:** the tap's app frames are the `Quilter`'s CBOR-encoded replication messages. Reuse the same trick `AdmitMessage` uses — a leading `PREFIX_BYTE` that a replication frame will not begin with — so the gate's relay loop can pull admit frames out of the single `incoming` stream and pass only replication frames to the `Quilter`. Pick a prefix byte and assert in the test that it is stable.

- [ ] **Step 1: Write the failing test**

`TapAdmitMessageTest.kt`:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapAdmitMessageTest {
    @Test
    fun challengeRoundTrips() {
        val msg = TapAdmitMessage.Challenge(nonce = ByteArray(16) { it.toByte() })
        val decoded = TapAdmitMessage.decode(TapAdmitMessage.encode(msg)) as TapAdmitMessage.Challenge
        assertTrue(msg.nonce.contentEquals(decoded.nonce))
    }

    @Test
    fun proofAndRejectRoundTrip() {
        val proof = TapAdmitMessage.Proof(tag = ByteArray(32) { 7 })
        assertTrue((TapAdmitMessage.decode(TapAdmitMessage.encode(proof)) as TapAdmitMessage.Proof).tag.contentEquals(proof.tag))
        val reject = TapAdmitMessage.Reject("expired")
        assertEquals("expired", (TapAdmitMessage.decode(TapAdmitMessage.encode(reject)) as TapAdmitMessage.Reject).reason)
    }

    @Test
    fun nonAdmitBytesDecodeToNull() {
        // A replication-style frame (does not start with the prefix byte) is not an admit frame.
        val appFrame = byteArrayOf(0x00, 0x01, 0x02)
        assertFalse(TapAdmitMessage.isAdmitFrame(appFrame))
        assertNull(TapAdmitMessage.decode(appFrame))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*TapAdmitMessageTest"` → FAIL.

- [ ] **Step 3: Write minimal implementation**

`TapAdmitMessage.kt` (mirror `AdmitMessage`'s encode/decode exactly, reusing `LogTapCbor` from `LogTapWire.kt`):
```kotlin
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package us.tractat.kuilt.otel.tap.admit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.otel.tap.LogTapCbor

/**
 * Wire frames for the tap's token-gated admission handshake, multiplexed onto the
 * same `Seam.incoming` the `Quilter`'s replication frames ride. A leading
 * [PREFIX_BYTE] — which a CBOR replication frame does not begin with — lets the
 * gate pull these out and pass only replication frames through.
 *
 * Flow (offering side = verifier, holds the code; pulling side = prover):
 * ```
 * verifier → prover : Challenge(nonce)
 * prover   → verifier: Proof(HMAC(code, nonce))
 * verifier → prover : Reject(reason)   // on mismatch/expiry; otherwise the peer is surfaced
 * ```
 */
@Serializable
public sealed interface TapAdmitMessage {
    @Serializable @SerialName("challenge")
    public data class Challenge(val nonce: ByteArray) : TapAdmitMessage

    @Serializable @SerialName("proof")
    public data class Proof(val tag: ByteArray) : TapAdmitMessage

    @Serializable @SerialName("reject")
    public data class Reject(val reason: String) : TapAdmitMessage

    public companion object {
        /** First byte of every encoded admit frame; replication frames must not start with it. */
        public const val PREFIX_BYTE: Byte = 0x74 // 't' for tap-admit

        public fun encode(message: TapAdmitMessage): ByteArray {
            val cbor = LogTapCbor.encodeToByteArray(message)
            return ByteArray(cbor.size + 1).also { it[0] = PREFIX_BYTE; cbor.copyInto(it, 1) }
        }

        public fun decode(bytes: ByteArray): TapAdmitMessage? {
            if (bytes.isEmpty() || bytes[0] != PREFIX_BYTE) return null
            return runCatching { LogTapCbor.decodeFromByteArray<TapAdmitMessage>(bytes.copyOfRange(1, bytes.size)) }.getOrNull()
        }

        public fun isAdmitFrame(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == PREFIX_BYTE
    }
}
```
> `data class` with `ByteArray` needs no hand-written `equals` here (round-trip tests use `contentEquals`); if detekt flags `ArrayInDataClass`, add a `@Suppress` with a one-line reason (wire DTO, compared by content in tests) or generate `equals`/`hashCode`. The bare `runCatching` in `decode` is a **non-suspend** parse (mirrors `AdmitMessage.decode`), so it is allowed — it is not in a coroutine context.

- [ ] **Step 4: Run test to verify it passes + commit**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*TapAdmitMessageTest"` → PASS.
```bash
git add kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TapAdmitMessage.kt \
        kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TapAdmitMessageTest.kt
git commit -m "feat(otel-tap): TapAdmitMessage — prefix-framed challenge/proof/reject wire"
```

---

### Task 4: `TokenGatedSeam` — the admission decorator (the core)

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeam.kt`
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeamTest.kt`

**Interfaces:**
- Consumes: `Seam` (`selfId`/`peers`/`incoming`/`broadcast`/`sendTo`/`close`); `hmacSha256`/`constantTimeEquals` (Task 1); `LogTapJoinToken` (Task 2); `TapAdmitMessage` (Task 3).
- Produces: `internal class TokenGatedSeam(inner: Seam, role: GateRole, scope: CoroutineScope) : Seam` and a `GateRole` sealed type — `Verifier(token, clock, random)` / `Prover(code)`. Exposes `peers` = **verified** peers only and `incoming` = replication frames from verified peers only.

**Behaviour (verifier = offering side):** for each peer that appears in `inner.peers`, mint a nonce from `random`, send `Challenge(nonce)`, remember `(peer → nonce)` under the lock. On a `Proof(tag)` from that peer, recompute `HMAC(code, nonce)`, `constantTimeEquals` it, and check `token.isValid(clock.now())`. On success add the peer to the verified `MutableStateFlow<Set<PeerId>>` and let its replication frames flow to `incoming`. On failure send `Reject` and never surface. **Prover = pulling side:** on a `Challenge(nonce)`, reply `Proof(hmacSha256(code.encodeToByteArray(), nonce))`.

**Structure (mirror `SeamRoom`):** a single relay coroutine collects `inner.incoming` once (single-collection contract); admit frames are handled by the gate, replication frames from verified peers are re-emitted on a `MutableSharedFlow<Swatch>`; state (`pendingNonces`, `verified`) is guarded by an atomicfu `reentrantLock`, with all `inner.sendTo`/`broadcast` calls **outside** the lock.

- [ ] **Step 1: Write the failing test**

`TokenGatedSeamTest.kt` — drives a host/joiner pair over `InMemoryLoom` under virtual time; asserts accept / wrong-code / expired / `Open`-parity, and that the `Quilter` never sees an unverified peer (proxied by "no replication frame reaches the puller's `incoming`"):
```kotlin
package us.tractat.kuilt.otel.tap.admit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TokenGatedSeamTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private fun clockAt(i: Instant) = object : Clock { override fun now() = i }

    @Test
    fun validCodeSurfacesThePullerToTheHost() = runTest(StandardTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        gatedPair(token, presentedCode = token.code, clock = clockAt(t0)) { hostGate, _ ->
            val verified = hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
            assertTrue(verified.size >= 2)
        }
    }

    @Test
    fun wrongCodeNeverSurfacesThePuller() = runTest(StandardTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        gatedPair(token, presentedCode = "WRONGGGG", clock = clockAt(t0)) { hostGate, _ ->
            val surfaced = withTimeoutOrNull(1.minutes) {
                hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
            }
            assertNull(surfaced) // adversary never enters the host's verified peer set
        }
    }

    @Test
    fun expiredTokenIsRefused() = runTest(StandardTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clockAt(t0), ttl = 5.minutes)
        // Host clock is past the TTL when the proof arrives.
        gatedPair(token, presentedCode = token.code, clock = clockAt(t0 + 6.minutes)) { hostGate, _ ->
            val surfaced = withTimeoutOrNull(1.minutes) {
                hostGate.peers.first { peers -> peers.any { it != hostGate.selfId } }
            }
            assertNull(surfaced)
        }
    }

    // gatedPair: host = InMemoryLoom.host wrapped as Verifier(token); puller = join wrapped as
    // Prover(presentedCode); both on backgroundScope child scopes; runs `block` then tears down.
    // (Helper body written in Step 3 alongside the impl; uses InMemoryLoom's same-instance pair.)
}
```
> The exact `gatedPair` helper depends on `InMemoryLoom`'s pairing API (`host`/`join` returning wired `Seam`s). Ground it against `InMemoryLoomConformanceTest`'s `newLoomPair()` when writing it; keep both gate relay coroutines on `backgroundScope` child scopes so the infinite `inner.incoming` collection cancels cleanly at teardown (no `UncompletedCoroutinesError`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*TokenGatedSeamTest"` → FAIL.

- [ ] **Step 3: Write minimal implementation**

`TokenGatedSeam.kt` — `Seam by inner` for send/lifecycle, overriding `peers` and `incoming`. Sketch:
```kotlin
package us.tractat.kuilt.otel.tap.admit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.Seam
import us.tractat.kuilt.core.Swatch
import us.tractat.kuilt.core.runCatchingCancellable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger("us.tractat.kuilt.otel.tap.admit.TokenGatedSeam")

internal sealed interface GateRole {
    class Verifier(val token: LogTapJoinToken, val clock: Clock, val random: Random) : GateRole
    class Prover(val code: String) : GateRole
}

internal class TokenGatedSeam(
    private val inner: Seam,
    private val role: GateRole,
    scope: CoroutineScope,
) : Seam by inner {

    private val lock = reentrantLock()
    private val pendingNonces = mutableMapOf<PeerId, ByteArray>()   // verifier: peer → issued nonce
    private val _verified = MutableStateFlow<Set<PeerId>>(setOf(inner.selfId))
    private val _incoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

    override val peers: kotlinx.coroutines.flow.StateFlow<Set<PeerId>> get() = _verified.asStateFlow()
    override val incoming: Flow<Swatch> get() = _incoming.asSharedFlow()

    init {
        // Verifier: challenge each peer as it appears.
        if (role is GateRole.Verifier) scope.launch {
            inner.peers.collect { peers -> for (p in peers) if (p != inner.selfId) maybeChallenge(p) }
        }
        // Sole collector of inner.incoming: handle admit frames, relay verified replication frames.
        scope.launch {
            inner.incoming.collect { swatch -> handleFrame(swatch) }
        }
    }

    private suspend fun maybeChallenge(peer: PeerId) {
        val verifier = role as GateRole.Verifier
        val nonce = lock.withLock {
            if (peer in pendingNonces || peer in _verified.value) return
            ByteArray(NONCE_BYTES) { verifier.random.nextInt(256).toByte() }.also { pendingNonces[peer] = it }
        }
        runCatchingCancellable { inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Challenge(nonce))) }
            .onFailure { logger.debug { "challenge send to $peer failed: ${it.message}" } }
    }

    private suspend fun handleFrame(swatch: Swatch) {
        val sender = swatch.sender ?: return
        val bytes = swatch.payloadCopy()               // confirm accessor name against Swatch
        val admit = TapAdmitMessage.decode(bytes)
        if (admit == null) {
            // Replication frame — relay ONLY if the sender is verified.
            if (lock.withLock { sender in _verified.value }) _incoming.emit(swatch)
            return
        }
        when (admit) {
            is TapAdmitMessage.Challenge -> if (role is GateRole.Prover) {
                val tag = hmacSha256(role.code.encodeToByteArray(), admit.nonce)
                runCatchingCancellable { inner.sendTo(sender, TapAdmitMessage.encode(TapAdmitMessage.Proof(tag))) }
                    .onFailure { logger.debug { "proof send failed: ${it.message}" } }
            }
            is TapAdmitMessage.Proof -> if (role is GateRole.Verifier) verify(sender, admit.tag)
            is TapAdmitMessage.Reject -> logger.debug { "admission rejected by $sender: ${admit.reason}" }
        }
    }

    private suspend fun verify(peer: PeerId, tag: ByteArray) {
        val verifier = role as GateRole.Verifier
        val decision = lock.withLock {
            val nonce = pendingNonces[peer] ?: return
            val expected = hmacSha256(verifier.token.code.encodeToByteArray(), nonce)
            val ok = constantTimeEquals(expected, tag) && verifier.token.isValid(verifier.clock.now())
            if (ok) { pendingNonces.remove(peer); _verified.value = _verified.value + peer }
            ok
        }
        if (!decision) {
            runCatchingCancellable { inner.sendTo(peer, TapAdmitMessage.encode(TapAdmitMessage.Reject("invalid or expired join code"))) }
                .onFailure { logger.debug { "reject send failed: ${it.message}" } }
        }
    }

    private companion object { const val NONCE_BYTES = 16 }
}

/** Wrap [this] with the token gate for the given [role]; runs its relay in [scope]. */
internal fun Seam.tokenGated(role: GateRole, scope: CoroutineScope): Seam = TokenGatedSeam(this, role, scope)
```
> **Ground before writing:** (1) confirm the `Swatch` payload accessor name (a `payloadCopy()`-style copy exists per `Swatch.kt`); (2) `hmacSha256` inside the lock is a pure CPU call (no suspension) — acceptable; the only suspend calls (`sendTo`) are outside the lock, per the thread-safety rule. (3) `_incoming` uses `emit` (suspends) from the single relay collector — fine, it is the sole collector. Cross-check the single-collection relay shape against `SeamRoom` (`rawIncoming`/`_incoming`).

- [ ] **Step 4: Run test to verify it passes + commit**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*TokenGatedSeamTest"` → PASS.
```bash
git add kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeam.kt \
        kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/admit/TokenGatedSeamTest.kt
git commit -m "feat(otel-tap): TokenGatedSeam — challenge-response admission decorator over any Seam"
```

---

### Task 5: `LogTapAdmission` + offer/rendezvous-role split (entry-point wiring)

**Files:**
- Create: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapAdmission.kt`
- Modify: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapHost.kt` (add `admission` param + `installLogTapJoining`)
- Modify: `kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapClient.kt` (add `admission` param)
- Test: `kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/GatedTapEndToEndTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface LogTapAdmission { data object Open; class Verify(token, clock, random); class Present(code) }` — carries the live `Clock`/`Random` **out of** `LogTapConfig` (a data class must stay value-only; same lesson as M2's `TraceContextProvider`). `LogTapConfig` is unchanged.
  - `installLogTap(loom, exporter, scope, config, admission: LogTapAdmission = Open): LogTapHost` (existing, device **hosts**; `admission` requires `Verify`/`Open`).
  - `installLogTapJoining(loom, exporter, scope, tag: Tag, config, admission: LogTapAdmission = Open): LogTapHost` (new, device **joins** — the iOS path; requires `Verify`/`Open`).
  - `LogTapClient(seam, scope, config, admission: LogTapAdmission = Open)` — pulling side; requires `Present`/`Open`.
- Consumes: `Seam.tokenGated(GateRole, scope)` (Task 4).

- [ ] **Step 1: Write the failing test**

`GatedTapEndToEndTest.kt` — full tap over `InMemoryLoom`, gated: valid code pulls the buffer; `Open` parity with today; wrong code times out `pull()`:
```kotlin
package us.tractat.kuilt.otel.tap

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.otel.InMemoryDurableStore
import us.tractat.kuilt.otel.LogRecord
import us.tractat.kuilt.otel.WarpLogRecordExporter
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import kotlinx.io.bytestring.ByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GatedTapEndToEndTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now() = t0 }
    private fun exporterWith(n: Int) = WarpLogRecordExporter(ReplicaId("device"), InMemoryDurableStore())
        .also { e -> (1..n).forEach { e.export(LogRecord(recordId = ByteString(ByteArray(8) { _ -> it.toByte() }), body = "log $it")) } }

    @Test
    fun validCodePullsTheBuffer() = runTest(StandardTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val cfg = LogTapConfig(quilterConfig = us.tractat.kuilt.quilter.QuilterConfig(expectVirtualTime = true))
        // host = Verify(token); client = Present(token.code) over a wired InMemoryLoom pair.
        val pulled = gatedPull(exporterWith(3), token, presented = token.code, cfg)
        assertEquals(listOf("log 1", "log 2", "log 3"), pulled.map { it.body })
    }

    @Test
    fun wrongCodeTimesOutThePull() = runTest(StandardTestDispatcher()) {
        val token = LogTapJoinToken.issue(Random(1), clock, ttl = 5.minutes)
        val cfg = LogTapConfig(pullTimeout = 2.minutes, quilterConfig = us.tractat.kuilt.quilter.QuilterConfig(expectVirtualTime = true))
        val pulled = withTimeoutOrNull(3.minutes) { runCatching { gatedPull(exporterWith(3), token, presented = "WRONGGGG", cfg) }.getOrNull() }
        assertNull(pulled) // never converges → pull() throws TimeoutCancellation, caught as null
    }

    // gatedPull: wires an InMemoryLoom host/join pair; installs LogTap host with Verify(token) and a
    // LogTapClient with Present(presented); returns client.pull(). Mirrors LogTapConvergenceTest's harness.
}
```
> Ground `gatedPull` against the existing `LogTapConvergenceTest` (same module) for the `InMemoryLoom` host/join wiring + scope/teardown idiom; set `expectVirtualTime = true` on `QuilterConfig` as that test does.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*GatedTapEndToEndTest"` → FAIL (no `admission` params / `installLogTapJoining`).

- [ ] **Step 3: Write minimal implementation**

`LogTapAdmission.kt`:
```kotlin
package us.tractat.kuilt.otel.tap

import us.tractat.kuilt.otel.tap.admit.GateRole
import us.tractat.kuilt.otel.tap.admit.LogTapJoinToken
import kotlin.random.Random
import kotlin.time.Clock

/**
 * How the tap admits a peer. The default [Open] reproduces the shipped behaviour
 * (no gate) — safe on loopback. On a real network use a token: the offering side
 * holds [Verify] (challenges + verifies), the pulling side holds [Present] (answers).
 *
 * Carries the live [Clock]/[Random] deliberately **outside** [LogTapConfig] (a
 * value `data class`), so equality/hashing stay well-defined.
 */
public sealed interface LogTapAdmission {
    /** No admission gate. The default; intended for loopback. */
    public data object Open : LogTapAdmission

    /** Offering side: challenge joiners and verify their proof against [token]. */
    public class Verify(public val token: LogTapJoinToken, public val clock: Clock, public val random: Random) : LogTapAdmission

    /** Pulling side: answer the offering side's challenge with the shown [code]. */
    public class Present(public val code: String) : LogTapAdmission
}

internal fun LogTapAdmission.offeringRoleOrNull(): GateRole? = when (this) {
    is LogTapAdmission.Open -> null
    is LogTapAdmission.Verify -> GateRole.Verifier(token, clock, random)
    is LogTapAdmission.Present -> error("Present is a pulling-side admission; the offering side needs Verify or Open")
}

internal fun LogTapAdmission.pullingRoleOrNull(): GateRole? = when (this) {
    is LogTapAdmission.Open -> null
    is LogTapAdmission.Present -> GateRole.Prover(code)
    is LogTapAdmission.Verify -> error("Verify is an offering-side admission; the puller needs Present or Open")
}
```
In `LogTapHost.kt`, thread admission into both entry points (wrap the woven seam before constructing the host):
```kotlin
public suspend fun installLogTap(
    loom: Loom, exporter: WarpLogRecordExporter, scope: CoroutineScope,
    config: LogTapConfig = LogTapConfig(), admission: LogTapAdmission = LogTapAdmission.Open,
): LogTapHost {
    val seam = loom.host(config.pattern).gatedIfNeeded(admission.offeringRoleOrNull(), scope)
    admission.logIssuedCodeOnce()   // one-time console print of the code (Verify only)
    return LogTapHost(seam, exporter, scope, config)
}

/** Device **joins** a rendezvous the puller opened (the iOS path: iOS can't host a WS server). */
public suspend fun installLogTapJoining(
    loom: Loom, exporter: WarpLogRecordExporter, scope: CoroutineScope, tag: Tag,
    config: LogTapConfig = LogTapConfig(), admission: LogTapAdmission = LogTapAdmission.Open,
): LogTapHost {
    val seam = loom.join(tag).gatedIfNeeded(admission.offeringRoleOrNull(), scope)
    admission.logIssuedCodeOnce()
    return LogTapHost(seam, exporter, scope, config)
}

private fun Seam.gatedIfNeeded(role: GateRole?, scope: CoroutineScope): Seam =
    if (role == null) this else tokenGated(role, scope)
```
`logIssuedCodeOnce()` prints `token.code` exactly once via `logger.info` (the deliberate issuance print — the only place the code is logged). In `LogTapClient.kt` add `admission: LogTapAdmission = LogTapAdmission.Open` and wrap `seam` with `admission.pullingRoleOrNull()` before building the `Quilter` (the client's `scope` is already a `ScopedCloseable` field — pass it).

- [ ] **Step 4: Run test to verify it passes + commit**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*GatedTapEndToEndTest"` then the whole module `./gradlew :kuilt-otel-tap:jvmTest` (no regression in `LogTapConvergenceTest`/`LogTapWebSocketTest`).
```bash
git add kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapAdmission.kt \
        kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapHost.kt \
        kuilt-otel-tap/src/commonMain/kotlin/us/tractat/kuilt/otel/tap/LogTapClient.kt \
        kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/GatedTapEndToEndTest.kt
git commit -m "feat(otel-tap): LogTapAdmission + installLogTapJoining — opt-in token gate, offer/rendezvous roles split"
```

**→ PR A cut point.** Tasks 1–5 (+ Task 7 docs) are a complete, self-contained, loopback-safe admission-gate PR. Open it before starting Task 6.

---

### Task 6: mDNS + WebSocket reach — wiring sample + gated integration test

**Files:**
- Create: `kuilt-otel-tap/src/jvmTest/kotlin/us/tractat/kuilt/otel/tap/GatedLoopbackWebSocketTest.kt`
- Create: `kuilt-mdns` test or sample demonstrating the laptop-hosts/device-joins wiring (see below)
- Modify: `kuilt-otel-tap/module.md` / `Writerside` how-to (reach + token), if a tap how-to exists

**Interfaces:** no new production types — this task **wires** existing modules (`MDNSPeerLinkFactory`, `MDNSServiceDiscoverer`, `KtorServerLoom`/`KtorClientLoom`) to the Task-5 entry points and proves the gate survives a real WebSocket round-trip.

**Why mostly tests/sample:** the fabric code already exists; the automatable delta is (a) the gate over a real loopback WebSocket, and (b) a real-multicast mDNS discover→join→gated-pull behind the existing `-Pmdns.multicast.tests` gate. True LAN iOS↔laptop is Task 8 (manual).

- [ ] **Step 1: Gated loopback-WebSocket integration test**

Extend the shape of the shipped `LogTapWebSocketTest` (same `testApplication` + `KtorServerLoom`/`KtorClientLoom` + `WebSocketAdvertisement` harness) with a **gated** variant: host installs with `LogTapAdmission.Verify(token, clock, random)`, client with `LogTapAdmission.Present(token.code)`; assert the full sequence reconstructs. Add a negative case: `Present("WRONGGGG")` ⇒ `pull()` throws within `pullTimeout` (bounded `withTimeout`). Runs under **real time** (like `LogTapWebSocketTest`), so use a real `Clock`/`Random` here (this JVM integration test is the sanctioned real-timing harness — inline `@Suppress` with a one-line reason if detekt flags the real `Random`/`Clock`).

- [ ] **Step 2: mDNS discover→join→gated-pull integration (multicast-gated)**

Behind `-Pmdns.multicast.tests=true` (the existing opt-in), stand up: **laptop side** = `MDNSPeerLinkFactory` hosting (`KtorServerLoom` + JmDNS advertiser) as the **puller**; **device side** = `MDNSServiceDiscoverer.discoveries()` → first `MDNSAdvertisement` → `installLogTapJoining(KtorClientLoom(...), exporter, scope, tag = advertisement, admission = Verify(token,…))`. Assert the puller's `LogTapClient(hostSeam, scope, admission = Present(token.code)).pull()` returns the device buffer, and a wrong code refuses. This exercises the **role inversion** (offering side joins; pulling side hosts) end-to-end on a real loopback multicast. Ground the JmDNS/`MDNSPeerLinkFactory` construction against `MDNSMulticastIntegrationTest`.

- [ ] **Step 3: Run the gated integration tests**

Run: `./gradlew :kuilt-otel-tap:jvmTest --tests "*GatedLoopbackWebSocketTest"` (default) and, for the multicast one, `./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true --tests "*<name>"`. Expected: PASS.

- [ ] **Step 4: Commit**
```bash
git add kuilt-otel-tap/src/jvmTest/... kuilt-mdns/src/jvmTest/...
git commit -m "test(otel-tap): gated tap over real WebSocket + mDNS discover→join→pull (role-inverted)"
```

---

### Task 7: Docs — sample, KDoc, and the honest seam

**Files:**
- Modify: `kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt` (add a gated-tap sample)
- Modify: `kuilt-otel-tap/module.md` (reach + token, accessible-first)
- Modify: KDoc on `installLogTap`/`installLogTapJoining`/`LogTapClient`/`LogTapJoinToken`

- [ ] **Step 1: Add a compiled `@sample`**

In `Samples.kt`, add `sampleGatedLogTap()` showing: device mints `LogTapJoinToken.issue(random, clock)`, prints `token.code`, `installLogTap(loom, exporter, scope, admission = LogTapAdmission.Verify(token, clock, random))`; puller `LogTapClient(seam, scope, admission = LogTapAdmission.Present(enteredCode)).pull()`. Samples compile as `commonTest` (per `kuilt.kmp-library`) — a broken sample breaks the build, so keep it real.

- [ ] **Step 2: `module.md` — accessible-first**

Lead in plain language: *"Turn on the tap and read your phone's logs from your laptop over the same Wi-Fi — behind a short code the phone shows you, so only you can pull."* Then, deeper: the token gates *who pulls*, not wire confidentiality (`ws://` is plaintext — the **honest seam**); encrypted transport is the Multipeer follow-up. Name the role inversion for iOS (device joins, laptop hosts). Re-read top-to-bottom to confirm accessible → technical (repo doc rule).

- [ ] **Step 3: KDoc**

Document the `admission` params, `installLogTapJoining`'s "device joins" purpose, and on `LogTapJoinToken` the secret-handling contract (shown once, never re-logged, never on the wire).

- [ ] **Step 4: Build samples + commit**

Run: `./gradlew :kuilt-otel-tap:compileTestKotlinJvm` (compiles samples) → SUCCESS.
```bash
git add kuilt-otel-tap/src/commonSamples/... kuilt-otel-tap/module.md kuilt-otel-tap/src/commonMain/...
git commit -m "docs(otel-tap): gated-tap sample + reach/token module docs (honest-seam: ws:// is plaintext)"
```
*(Task 7 lands with PR A.)*

---

### Task 8: Manual real-device validation (cannot be unit-tested)

**Files:**
- Create/append: validation notes captured in the **PR B** description (and, if a running log exists, `docs/...`), not a committed throwaway.

This is the issue's actual "Done when" — a real iPhone, a real laptop, a real Wi-Fi. Script it as a checklist and record the outcome + device/OS/network in PR B:

- [ ] **1. Happy path (mDNS+WS, iOS source, role-inverted).** Laptop and iPhone on the same Wi-Fi. Run the laptop as **host+puller** (`MDNSPeerLinkFactory` advertising + `LogTapClient(..., Present(code))`). Launch the app on a **real iPhone** with `installLogTapJoining(KtorClientLoom(...), exporter, scope, tag = discoveredAdvertisement, admission = Verify(token,…))`. Confirm: iPhone discovers the laptop with **no IP typed**; the iOS **local-network permission** prompt appears on first Bonjour use (expected — note it); after entering the code the phone printed to the **Xcode console**, `pull()` returns the phone's real logs in order.
- [ ] **2. Wrong code.** Repeat with a mistyped code ⇒ `pull()` refused/times out, no logs pulled. Capture.
- [ ] **3. Expired token.** Let a token pass its TTL before pulling ⇒ refused. Capture.
- [ ] **4. Record** device models, iOS/host OS versions, Wi-Fi/AP details, and any client-isolation gotcha (some guest networks block peer-to-peer mDNS — note if hit).

*(No commit of code; the deliverable is the captured notes in PR B.)*

---

### Task 9: Full-build verification + open the two PRs

**Files:** none (verification + integration).

- [ ] **Step 1: Cache-disabled module build + detekt (PR A scope)**

Run:
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem
./gradlew :kuilt-otel-tap:build detektAll --rerun-tasks
```
Expected: BUILD SUCCESSFUL, tasks EXECUTED (not `FROM-CACHE`; add `--no-build-cache` if any test-compile shows `FROM-CACHE`). detektAll clean.

- [ ] **Step 2: Whole-repo build (Android + Native + wasmJs variants CI runs)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — the gate/token/wire are all `commonMain`, so every target (incl. wasmJs and iOS/macOS via KotlinCrypto) must compile. This is the hard bar `jvmTest` alone won't catch.

- [ ] **Step 3: Open PR A (admission gate)**
```bash
git push -u origin realphone-tap-admission
gh pr create --title "feat(otel): log tap join-token admission gate + offer/rendezvous-role split (#1028)" \
  --body "$(cat <<'EOF'
> 🤖 This PR was generated by Claude on behalf of @keddie.

Part of #986. Implements the admission half of #1028 (design #1032, spec `docs/superpowers/specs/2026-07-01-realphone-tap-reach-design.md`, plan `docs/superpowers/plans/2026-07-01-realphone-tap-reach.md`).

Opt-in, fabric-agnostic **token-gated `Seam` decorator**: the device shows a short code, the puller proves `HMAC-SHA256(code, nonce)`; constant-time verify within a TTL; failed peers never reach the `Quilter`. Default `Open` (loopback) is byte-unchanged. Adds `installLogTapJoining` so an iOS source can **join** a laptop-hosted rendezvous (iOS can't host a WS server). HMAC via KotlinCrypto (KMP-uniform), pinned by RFC 4231 vectors. `ws://` stays plaintext — token gates who-pulls, not confidentiality (Multipeer #1042 / wss #1043 are the encrypted follow-ups).

Next: PR B wires mDNS+WS reach + real-device validation.
EOF
)"
gh pr merge --auto --squash
gh pr view --web
```

- [ ] **Step 4: Open PR B (mDNS+WS reach), stacked on PR A**

After PR A is green, rebase PR B onto it, push, and open with `Part of #986`, referencing `Design for #1028` and PR A. Include the Task 8 real-device validation notes in the body. Draft until the manual validation is captured.

---

## Self-Review

**Spec coverage:**
- Fabric wiring — mDNS+WS, role inversion, `installLogTapJoining` → Tasks 5, 6, 8. ✓
- Token-gated `Seam` decorator, default `Open`, `HMAC(code, nonce)`, constant-time, TTL, reusable-within-TTL, failed peers never reach the `Quilter` → Tasks 1–5. ✓
- KMP-uniform HMAC (KotlinCrypto `hmac-sha2`, RFC 4231-pinned) → Task 1. ✓
- Token issuance/UX (8-char code, shown once, `host.joinCode`, redacted `toString`) → Tasks 2, 5, 7. ✓
- Threat model + honest seam (`ws://` plaintext; token = admission, not confidentiality) → Task 7 docs + plan header. ✓
- Manual real-device validation → Task 8. ✓
- Default byte-unchanged (loopback + `Open`) → Tasks 5, 9 (parity tests + full build). ✓

**Global-constraint coverage:** `explicitApi` (every public decl typed); no `!!`; seeded `Random` + virtual `Clock` + `StandardTestDispatcher` in unit tests; real-timing only in the sanctioned JVM integration tests (Task 6, inline `@Suppress`); `runCatchingCancellable` on every best-effort send; atomicfu `reentrantLock` with suspend sends outside the lock (Task 4); constant-time compare (Task 1); secret never logged/toString'd/on-wire (Tasks 2, 4, 5); `detektAll` + full `./gradlew build --rerun-tasks` (Task 9).

**Deviations / judgement calls (flag for review):**
- **Admission carried outside `LogTapConfig`.** `LogTapConfig` is a value `data class`; the `Verify` variant holds live `Clock`/`Random`, so admission is a separate `installLogTap*` param — same reasoning M2 used to keep `TraceContextProvider` off `CaptureConfig`. `LogTapConfig` is untouched (default byte-parity).
- **Gate is tap-local, not `SeamRoom`-composed** (locked). Avoids a `:kuilt-session` dependency; the wire mirrors `AdmitMessage`'s prefix-byte trick rather than reusing it.
- **`installLogTapJoining` inverts offer vs rendezvous role.** Forced by the audited iOS constraint (no WS server, no mDNS advertiser). The `Quilter`'s symmetric replication makes the offering side's rendezvous role irrelevant to data flow.

**Interface-verification callouts (not pinned from source — confirm when implementing):** (1) KotlinCrypto `HmacSHA256` one-shot method name for the resolved version (Task 1); (2) the `Swatch` payload-copy accessor name (Task 4); (3) `InMemoryLoom` pairing API for the `gatedPair`/`gatedPull` helpers, grounded against `LogTapConvergenceTest`/`InMemoryLoomConformanceTest` (Tasks 4, 5); (4) `MDNSPeerLinkFactory`/JmDNS construction, grounded against `MDNSMulticastIntegrationTest` (Task 6); (5) whether `detekt` flags `ArrayInDataClass` on `TapAdmitMessage` (Task 3).

**Cross-plan dependency:** none blocking. This plan is self-contained on the shipped `:kuilt-otel-tap`/`:kuilt-mdns`/`:kuilt-websocket`. It **spawns** two follow-ups it deliberately excludes: **#1042** (Apple Multipeer reach — the encrypted, no-role-inversion Apple-native path) and **#1043** (`wss://`/TLS on the WS fabric — wire confidentiality). Both build on the `LogTapAdmission`/`TokenGatedSeam` surface this plan introduces, so land this first.
