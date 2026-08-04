# Star Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Revision 2.** Revision 1 of this plan was withdrawn: it contained a remotely-triggerable co-joiner
takeover, suspended its fan-out inside the single inbound collector, and gated its own de-risking
prototype on a tautology. This plan is written against **revision 2** of
[`docs/superpowers/specs/2026-08-03-star-relay-design.md`](../specs/2026-08-03-star-relay-design.md),
whose "What revision 1 got wrong" table is the checklist it satisfies. No code was carried over.

**Goal:** Make a room's roster genuinely routable on star fabrics — the host forwards peer-addressed
frames between spokes — so a `Quilter` over `Room.channel(...)` converges between two spokes that have
no direct edge ([#1994](https://github.com/tractat-us/kuilt/issues/1994)).

**Architecture:** Two halves. The **host** grows a branch in its *existing* `SeamRoom.dispatchIncoming`
`when` (never a second collector — ADR-034) that decodes a `RelayEnvelope`, validates the first hop,
checks an `isRelayable` **allow-list**, resolves recipients into a sealed `Resolved`, and enqueues the
**original bytes unchanged** on the *existing* `admitFanOuts` writer. The **joiner** routes
`broadcast`/`sendTo` through the host once its roster diverges from `seam.peers`, and on receipt
independently re-checks that `dest` names it before re-emitting the inner payload stamped with the
**origin** as sender. Internal protocol traffic (admit / lobby / heartbeat) is never relayed — it is
excluded by construction, not by a deny-list.

**Tech Stack:** Kotlin Multiplatform, kotlinx-serialization CBOR, kotlinx-coroutines,
`kotlin.test` + `assertAll`, Gradle.

## Global Constraints

Every task's requirements implicitly include this section.

- **JDK 21 inline, never `source`:** prefix Gradle calls with
  `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem`. Do **not** run
  `source ~/.sdkman/bin/sdkman-init.sh` — it is refused in a dispatched worktree.
- **OS-fence every Gradle call:** `timeout 600 …` for a module build, `timeout 90 …` for a single test.
  The tight fence lives on the shell command; **never** on `runTest`.
- **`runTest` ceiling is a generous wedge backstop, never a tight assertion.** Use
  `timeout = 30.seconds` via a named constant with KDoc saying it is a backstop. A real-time ceiling
  must never be load-bearing for a virtual-time test (#1739, #1891). **Do not copy the `5.seconds`
  ceiling** you will see in `MuxHubPrincipalTest` / `LivenessRouteGateTest` — it is the known-bad
  pattern.
- **`StandardTestDispatcher` + bounded `advanceTimeBy`/`runCurrent`. Never `advanceUntilIdle()`.**
- **`explicitApi()` is enforced.** Every new public declaration needs an explicit modifier. Prefer
  `internal` — see "Visibility" below.
- **`detektAll`, never bare `detekt`** (bare `detekt` is `NO-SOURCE` here and is a false green).
- **Never bare `runCatching`** in a suspend/coroutine context — use `runCatchingCancellable`
  (`:kuilt-core`). Not inside a `withContext(NonCancellable)` shield (none here).
- **`assertAll` is `vararg`** (`kuilt-test/.../Assertions.kt:8`) — a `List` needs a spread (`*`).
- **Test methods carry no `test` prefix**; `@Test` suffices.
- **Every negative assertion shares a test with its positive control.** A test whose only assertion is
  `assertTrue(x.none { … })` is green before the feature exists and is therefore not a test. This is
  spec correction **C5** and it is non-negotiable.
- **Consensus/runtime behavior gate:** the final gate for Tasks 5–8 is the **full** `./gradlew build`,
  not a module-scoped build — a `:kuilt-session`-scoped build skips the `:examples` / `:kuilt-cluster`
  E2E tests.
- **Cache-disabled verification before auto-merge:** `--rerun-tasks`, and confirm tasks are `EXECUTED`
  not `FROM-CACHE`.

### Module placement: NO new dependency on `:kuilt-core` — a deliberate deviation from the spec

**This is the first thing a reviewer should challenge.**

The spec's Architecture section places `RelayDest` and `RelayEnvelope` in **`:kuilt-core`, public**.
This plan places them in **`:kuilt-session`, `internal`**, and lifts **only** `validFirstHop` to core.
Three reasons, all checkable:

1. **`:kuilt-core` has no CBOR dependency, and this plan does not add one.**
   `kuilt-core/build.gradle.kts` declares `kotlinx.serialization.core` only — not
   `kotlinx.serialization.cbor`. Following the spec literally means adding a serialization *format*
   dependency to the contract module (whose stated charter is "depends on nothing but coroutines +
   serialization") for a type nothing outside `:kuilt-session` consumes. `validFirstHop` is pure and
   generic and needs no dependency at all.
2. **The spec's own Decisions table says "Reuse: *Lift `validFirstHop` only*"** and "there is
   consequently **no** cluster-migration follow-up". The Decisions table is the binding statement; the
   Architecture bullet list is the looser one, inherited from revision 1 (which lifted the envelope,
   the rule *and* the resolver). Applying the decision consistently pulls the envelope back too.
3. **`RoomFramePrefix` must live in `:kuilt-session`** ([#2007](https://github.com/tractat-us/kuilt/issues/2007)
   says so, and the families it reserves live in `:kuilt-session` and `:kuilt-liveness`). An envelope in
   `:kuilt-core` referencing `RoomFramePrefix.Relay` would invert the dependency arrow.

So: `validFirstHop` → `:kuilt-core`, **public**, no new dependency. Everything else →
`:kuilt-session`, **internal**. The only public surface this track adds is `RoomFramePrefix` and
`validFirstHop`.

**If the reviewer wants the envelope in core anyway**, the cost is explicit: add
`implementation(libs.kotlinx.serialization.cbor)` to `kuilt-core/build.gradle.kts`, move
`RoomFramePrefix` to core with it, and accept that the contract module now knows about admit, lobby and
heartbeat framing. That is a real architectural change and should be an explicit decision, not a side
effect of following a bullet list.

### An honest weakness to put in front of the reviewer

At the Room layer there is **no trusted relayer set** — every sender is a spoke — so the session's call
is `validFirstHop(sender, origin, trusted = emptySet())`, which degenerates to `origin == sender`. The
lift is justified by the **cluster** keeping its non-empty `voters` call, not by the session's. A
reviewer who thinks a shared function with a degenerate instantiation is worse than an inline
`origin == sender` plus a comment is making a legitimate call. Do not paper over this; it is adjacent
to the tautology that sank revision 1 (**C2**), and it should be argued, not assumed.

### Two spec claims that drifted before this plan was written

- **The spec says three stale `TieredSeam` claims need correcting. Only two do.**
  `docs/fabric-peer-routing.md:47` was **already corrected on `main`** by commit `c82e39a6`
  (2026-07-31) and now reads "…now throws `PeerNotConnected` (#1935)". The two that remain are
  `SeamRoom.kt:1525` and #1994's own body (lines 29 and 91). Task 10 fixes exactly those two.
- **The join-window fix widens one surface while narrowing another** — see Task 3. It is not a pure
  win and the plan says so.

---

### Task 1: The frame-prefix registry (slice 0 — closes #2007)

The relay needs a fifth discriminator (`0x72`, 'r'). Adding it as a sixth loose `public const val` in a
sixth package is the thing #2007 exists to prevent, and doing the registry *afterwards* means touching
the relay's framing twice. So the registry lands first.

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomFramePrefix.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt:236`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt:14-49`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt:48`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomFramePrefixTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `public enum class RoomFramePrefix(public val byte: Byte)` with members
  `Admit`, `Channel`, `Lobby`, `Heartbeat`, `Relay`, and `public fun matches(bytes: ByteArray): Boolean`.
  Tasks 4–6 use `RoomFramePrefix.Relay` and `RoomFramePrefix.entries`.

**What is deliberately NOT in scope** (quoting #2007): forcing every decoder to change. The registry
**reserves** `0x6b` for heartbeat while `HeartbeatPartitionDetector.isHeartbeatFrame` keeps its String
predicate. `:kuilt-liveness` cannot depend on `:kuilt-session`, so derivation is impossible in that
direction — a test pins the correspondence instead.

- [ ] **Step 1: Write the failing registry test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomFramePrefixTest.kt`:

```kotlin
package us.tractat.kuilt.session

import us.tractat.kuilt.liveness.HeartbeatPartitionDetector
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.session.election.LobbyMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry owns the room frame-prefix byte space (#2007).
 *
 * Before it existed, five discriminators were declared as loose `public const val`s in four
 * packages — one of them not even a byte (heartbeat declares a *String* whose first byte happens
 * to be `0x6b`) — with nothing but prose keeping them from colliding.
 */
class RoomFramePrefixTest {

    @Test
    fun `every reserved byte is distinct`() {
        val bytes = RoomFramePrefix.entries.map { it.byte }
        assertEquals(
            RoomFramePrefix.entries.size,
            bytes.toSet().size,
            "two frame families claim the same byte: $bytes",
        )
    }

    @Test
    fun `each family's own discriminator agrees with its reservation`() {
        assertAll(
            { assertEquals(RoomFramePrefix.Admit.byte, AdmitMessage.PREFIX_BYTE) },
            { assertEquals(RoomFramePrefix.Channel.byte, RoomChannel.CHANNEL_PREFIX) },
            { assertEquals(RoomFramePrefix.Lobby.byte, LobbyMessage.PREFIX_BYTE) },
            {
                // Heartbeat cannot derive from the registry — :kuilt-liveness does not (and must
                // not) depend on :kuilt-session. The registry reserves the byte; this pins the
                // correspondence in the only direction available.
                assertEquals(
                    RoomFramePrefix.Heartbeat.byte,
                    HeartbeatPartitionDetector.PING_PREFIX.encodeToByteArray()[0],
                )
                assertEquals(
                    RoomFramePrefix.Heartbeat.byte,
                    HeartbeatPartitionDetector.PONG_PREFIX.encodeToByteArray()[0],
                )
            },
        )
    }

    @Test
    fun `matches keys on the first byte and tolerates an empty payload`() {
        assertAll(
            // Positive control: without this a `matches` that always returned false would pass
            // every negative below (spec correction C5).
            { assertTrue(RoomFramePrefix.Relay.matches(byteArrayOf(0x72, 0x01))) },
            { assertFalse(RoomFramePrefix.Relay.matches(byteArrayOf(0x71, 0x72))) },
            { assertFalse(RoomFramePrefix.Relay.matches(ByteArray(0))) },
        )
    }

    /**
     * `0x72` was legal application data before this track. Nothing in the registry can make an
     * application payload safe — this pins only that the *reservation* is what a relay frame is
     * recognised by, so the v2 release note ("a `Room.broadcast` payload starting `0x72` is now
     * swallowed as a relay frame") is truthful.
     */
    @Test
    fun `Relay reserves 0x72`() {
        assertEquals(0x72.toByte(), RoomFramePrefix.Relay.byte)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*RoomFramePrefixTest*"
```

Expected: FAIL — `Unresolved reference: RoomFramePrefix`.

- [ ] **Step 3: Create the registry**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomFramePrefix.kt`:

```kotlin
package us.tractat.kuilt.session

/**
 * The single source of truth for the **room frame-prefix byte space** (#2007).
 *
 * Every frame `SeamRoom.dispatchIncoming` classifies is discriminated by its first byte. Before
 * this enum those bytes were five loose `public const val`s in four packages, and nothing made a
 * collision a compile error. One member per frame family means a family cannot be added without
 * claiming a byte, and [RoomFramePrefixTest] pins that no two claim the same one.
 *
 * ## The real collision band is `0x60..0x7f`, not `0xe0..0xff`
 *
 * Inherited prose (originally `RoomChannel`'s KDoc) claimed these bytes are safe because they sit
 * "outside the CBOR major-type-7 range (`0xe0`–`0xff`) used by serialization". **That is false**,
 * and it is false in the direction that matters. CBOR text-string headers are `0x60 or len`, so a
 * bare top-level CBOR string collides with *every* byte claimed here:
 *
 * | payload | first byte | collides with |
 * |---|---|---|
 * | 1-char string | `0x61` | [Admit] |
 * | 3-char string | `0x63` | [Channel] |
 * | 5-char string | `0x65` | [Lobby] |
 * | 11-char string | `0x6b` | [Heartbeat] |
 * | 18-char string | `0x72` | [Relay] |
 *
 * The codebase lives with this because room payloads are **framed**, not bare — an application
 * payload is wrapped by the channel header before it reaches the wire. This registry's job is
 * single-source-of-truth for the byte space and **distinctness**; it is deliberately *not* a
 * safety proof, because no registry can make one.
 *
 * @property byte the first byte of every frame in this family.
 */
public enum class RoomFramePrefix(public val byte: Byte) {
    /** The admit handshake — `AdmitMessage`. `0x61`, ASCII 'a'. */
    Admit(0x61),

    /** A `Room.channel(id)` view's frames — `RoomChannel`. `0x63`, ASCII 'c'. */
    Channel(0x63),

    /** Host election — `LobbyMessage`. `0x65`, ASCII 'e'. */
    Lobby(0x65),

    /**
     * Liveness ping/pong — `HeartbeatPartitionDetector`.
     *
     * The odd one out: heartbeat declares a *String* prefix (`"kuilt.heartbeat.ping"`) whose first
     * byte happens to be `0x6b`, and `:kuilt-liveness` cannot depend on `:kuilt-session` to derive
     * from here. The reservation is one-directional and pinned by test, not by construction.
     */
    Heartbeat(0x6b),

    /**
     * A host-forwarded frame between two spokes of a star — `RelayEnvelope` (#1994). `0x72`, ASCII 'r'.
     *
     * **Release note:** an application payload sent via `Room.broadcast` whose first byte is `0x72`
     * was previously legal and is now swallowed as a relay frame.
     */
    Relay(0x72),
    ;

    /** Whether [bytes] is a frame of this family — i.e. its first byte is [byte]. Empty is never. */
    public fun matches(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == byte
}
```

- [ ] **Step 4: Point the three byte-declaring families at the registry**

These stop being `const` (source-breaking; #2007 states pre-1.0 is the window and none of the three is
used as a `when` constant pattern — verify by grep before editing).

In `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt:236`, replace:

```kotlin
        public const val PREFIX_BYTE: Byte = 0x61
```

with:

```kotlin
        /** First byte of every admit frame. Reserved by [RoomFramePrefix.Admit] (#2007). */
        public val PREFIX_BYTE: Byte = RoomFramePrefix.Admit.byte
```

and add `import us.tractat.kuilt.session.RoomFramePrefix` to that file.

In `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt:48`, replace:

```kotlin
        public const val PREFIX_BYTE: Byte = 0x65
```

with:

```kotlin
        /** First byte of every lobby frame. Reserved by [RoomFramePrefix.Lobby] (#2007). */
        public val PREFIX_BYTE: Byte = RoomFramePrefix.Lobby.byte
```

and add `import us.tractat.kuilt.session.RoomFramePrefix`.

In `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt:49`, replace:

```kotlin
    public const val CHANNEL_PREFIX: Byte = 0x63
```

with:

```kotlin
    public val CHANNEL_PREFIX: Byte = RoomFramePrefix.Channel.byte
```

- [ ] **Step 5: Correct the false CBOR claim at its source**

In `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt`, replace lines 17–29
(the `## Reserved prefix` block) with:

```kotlin
 * ## Reserved prefix
 *
 * Every channel frame begins with [CHANNEL_PREFIX] (`0x63`, ASCII 'c' for
 * "channel"), reserved by [RoomFramePrefix.Channel] — the registry that owns the
 * whole room frame-prefix byte space (#2007). It is distinct from every other
 * claimed byte by construction.
 *
 * This KDoc previously claimed `0x63` is safe because it sits "outside the CBOR
 * major-type-7 range (`0xe0`–`0xff`) used by serialization". **That was false.**
 * CBOR text-string headers are `0x60 or len`, so a bare 3-character CBOR string
 * begins `0x63` — and the same is true of every other prefix in the registry. The
 * real collision band is `0x60..0x7f`; see [RoomFramePrefix] for the full table.
 * The codebase lives with it because room payloads are framed, not bare.
 *
 * Applications **must not** emit raw payloads starting with `0x63` via [Room.broadcast]
 * or [Room.sendTo] — that byte is reserved for channel framing. Application frames
 * that happen to start with `0x63` will be misclassified as channel frames and routed
 * (or silently dropped).
```

- [ ] **Step 6: Run the test — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*RoomFramePrefixTest*"
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Full module gate**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  :kuilt-session:build detektAll --rerun-tasks
```

Expected: BUILD SUCCESSFUL. Dropping `const` can break a downstream `const`-context use — this build is
what proves it did not. If a Kotlin/Native or Android variant fails where JVM passed, that is the
known KMP false-green (`jvmTest` does not compile the Android variant); fix it, do not skip it.

- [ ] **Step 8: Correct #2007's own body, then commit**

#2007's body repeats the false CBOR claim ("The reserved ranges are documented only in `RoomChannel`'s
KDoc — CBOR major-type-7 (`0xe0`–`0xff`)"). Edit the body so it leads with what is now true — a body
contradicted by its own comments is worse than a thin one. Replace that bullet with:

```markdown
1. **No registry.** Nothing makes a collision a compile error. The reserved ranges are documented
   only in `RoomChannel`'s KDoc — and that documentation is **wrong**: it names CBOR major-type-7
   (`0xe0`–`0xff`) as the hazard band. The real band is `0x60..0x7f`, because CBOR text-string
   headers are `0x60|len` and therefore collide with *all five* claimed prefixes (len 1 → `0x61`,
   3 → `0x63`, 5 → `0x65`, 11 → `0x6b`, 18 → `0x72`). A registry can assert distinctness; it
   cannot assert safety, and must not pretend to.
```

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomFramePrefix.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/AdmitMessage.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/election/LobbyMessage.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RoomFramePrefixTest.kt
git commit -m "feat(session): own the room frame-prefix byte space in one registry

Five discriminators in four packages, one of them a String, become one enum:
a family cannot be added without claiming a byte, and no two can claim the same.

Also corrects the inherited CBOR claim. The hazard band is 0x60..0x7f (text-string
headers are 0x60|len, colliding with all five prefixes), not major-type-7
0xe0..0xff. The registry asserts distinctness only — it cannot assert safety.

Closes #2007. Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Lift `validFirstHop` — and prove the lift by swapping the cluster onto it

This is the de-risking gate. Revision 1's prototype compared
`sender in core || origin == sender` against `sender in trusted || origin == sender` — textually the
same expression — so it could not fail and its verdict was pre-written (spec correction **C2**). The
replacement is a **real swap against a real suite**.

**A compile error or a red cluster test IS the signal. Do not write the verdict before running it.**
If the cluster suite goes red, STOP and report — the lift is wrong and the design needs re-work. Do not
adjust the cluster to fit.

**Files:**
- Create: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RelayFirstHop.kt`
- Create: `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RelayFirstHopTest.kt`
- Modify: `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RaftRelay.kt:10-25`
- Modify: `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RaftRelayHub.kt:131`
- Modify: `kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RoutedRaftTransport.kt:304`

**No build-file change.** `validFirstHop` is a pure generic function — no serialization, no
coroutines. This is the whole reason it, and only it, goes to `:kuilt-core`.

**Interfaces:**
- Consumes: nothing.
- Produces: `public fun <Id> validFirstHop(sender: Id, origin: Id, trusted: Set<Id>): Boolean` in
  `us.tractat.kuilt.core`. Task 5 calls it with `trusted = emptySet()`.

- [ ] **Step 1: Write the failing test**

Create `kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RelayFirstHopTest.kt`:

```kotlin
package us.tractat.kuilt.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-hop origin-spoofing rule, shared by every point that accepts a relay frame off a
 * fabric. `origin` rides *inside* a forgeable envelope, so it is checked against the frame's
 * fabric-stamped sender before anything trusts it.
 */
class RelayFirstHopTest {

    @Test
    fun `a spoke may speak only for itself`() {
        assertAll(
            // Positive control first: a rule that rejected everything would satisfy every
            // negative below.
            { assertTrue(validFirstHop(sender = "a", origin = "a", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "b", trusted = emptySet())) },
        )
    }

    @Test
    fun `a trusted relayer is believed about a third party`() {
        val trusted = setOf("s1", "s2")
        assertAll(
            { assertTrue(validFirstHop(sender = "s1", origin = "b", trusted = trusted)) },
            { assertTrue(validFirstHop(sender = "s1", origin = "s1", trusted = trusted)) },
            // …but a spoke is still not, even when the origin it names is trusted.
            { assertFalse(validFirstHop(sender = "b", origin = "s1", trusted = trusted)) },
        )
    }

    /**
     * The session layer's instantiation. With no trusted relayers the rule degenerates to
     * `origin == sender` — pinned here so the degeneracy is a *stated* property rather than an
     * accident a later edit could silently change.
     */
    @Test
    fun `an empty trusted set reduces the rule to origin equals sender`() {
        assertAll(
            { assertTrue(validFirstHop(sender = "a", origin = "a", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "b", trusted = emptySet())) },
            { assertFalse(validFirstHop(sender = "a", origin = "c", trusted = emptySet())) },
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-core:jvmTest --tests "*RelayFirstHopTest*"
```

Expected: FAIL — `Unresolved reference: validFirstHop`.

- [ ] **Step 3: Create the lifted function**

Create `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RelayFirstHop.kt`:

```kotlin
package us.tractat.kuilt.core

/**
 * The first-hop origin-spoofing rule, shared by every point that accepts a relay frame off a
 * fabric — safety-critical wherever a relayed identity is credited.
 *
 * A relayed frame carries its true [origin] *inside* the envelope, because the fabric stamps the
 * **relaying** peer as the sender rather than the peer that minted the frame. That inner field is
 * forgeable, so before it is credited to anything it is checked against the fabric-stamped
 * [sender]:
 *
 * - A frame from an **untrusted** peer ([sender] not in [trusted]) is accepted only if its [origin]
 *   *is* that sender — a peer may speak only for itself, never on another's behalf.
 * - A frame from a **trusted** relayer ([sender] in [trusted]) is believed to carry an
 *   already-validated [origin]; trusted relayers preserve identity.
 *
 * Generic in the id type because the two callers key on different ones: `:kuilt-cluster` passes
 * `NodeId` with the voter core as [trusted], and `:kuilt-session` passes [PeerId] with
 * **`emptySet()`** — a room has no trusted relayer tier, so there the rule degenerates to
 * `origin == sender`. That degeneracy is deliberate and pinned by test; the shared function exists
 * for the cluster's non-empty case and for one statement of the rule.
 *
 * Pure and dependency-free by design: it is the *only* piece of the star relay that belongs in the
 * contract module, because it is the only piece that needs nothing from it.
 *
 * @return `true` if the frame passes first-hop validation.
 */
public fun <Id> validFirstHop(sender: Id, origin: Id, trusted: Set<Id>): Boolean =
    sender in trusted || origin == sender
```

- [ ] **Step 4: Run the test — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-core:jvmTest --tests "*RelayFirstHopTest*"
```

Expected: PASS, 3 tests.

- [ ] **Step 5: THE PROOF — swap the cluster onto it**

Delete the cluster's local copy. In
`kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RaftRelay.kt`, remove lines 10–25 (the
KDoc block and the `internal fun validFirstHop(...)` declaration) and add
`import us.tractat.kuilt.core.validFirstHop` to that file's imports.

Then update the two call sites to the lifted parameter name. In `RaftRelayHub.kt:131`:

```kotlin
                    if (!validFirstHop(sender = sender, origin = relay.origin, trusted = voters)) {
```

In `RoutedRaftTransport.kt:304`:

```kotlin
        if (!validFirstHop(sender = sender, origin = relay.origin, trusted = core)) {
```

Leave every surrounding KDoc reference to `[validFirstHop]` as-is — the symbol still resolves through
the import.

- [ ] **Step 6: Run the cluster suite. This is the verdict.**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  :kuilt-cluster:build --rerun-tasks 2>&1 | tail -40
```

**Record the actual outcome before interpreting it.** Three possibilities:

- **BUILD SUCCESSFUL** → the lift subsumes the cluster dialect. Keep the swap; it is both the proof
  and the deduplication.
- **Compile error** → the generic signature does not fit a real caller. STOP. Report the exact error.
  Do not add casts or overloads to force it.
- **Red test** → the lift changed cluster behavior. STOP. Report which test and its assertion message.
  This means the two rules were not the same rule, and the design's "Lift `validFirstHop` only"
  decision is wrong.

- [ ] **Step 7: Commit (only on a green Step 6)**

```bash
git add kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/RelayFirstHop.kt \
        kuilt-core/src/commonTest/kotlin/us/tractat/kuilt/core/RelayFirstHopTest.kt \
        kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RaftRelay.kt \
        kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RaftRelayHub.kt \
        kuilt-cluster/src/commonMain/kotlin/us/tractat/kuilt/cluster/RoutedRaftTransport.kt
git commit -m "feat(core): lift the relay first-hop rule out of :kuilt-cluster

Generic in the id type so :kuilt-session can reuse it. The lift is PROVEN by
swapping :kuilt-cluster's two call sites onto it and running that module's
suite — the cluster no longer carries its own copy.

Pure and dependency-free, which is why this and only this belongs in the contract
module. No build-file change.

At the room layer the trusted set is empty, so the rule degenerates to
origin == sender. Stated and pinned rather than left implicit.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Close the join window at its source

`admitPeer` adds the joiner to the roster under lock **before any send** (`SeamRoom.kt:1215-1219`), and
the host-intro that sets `hostPeerId` is the **last** of K+1 sequential suspending sends
(`:1250-1270`). So a fresh joiner holds a populated roster with `hostPeerId == null` across every one
of those sends. That window matters twice:

1. It is the capture window for the co-joiner takeover the allow-list (Task 5) closes — this is
   defense in depth, not the primary defense.
2. A `Quilter` collecting `rosterPeers` fires `onPeersChanged` → `sendFullStateTo(coJoiner)`
   (`Quilter.kt:566`, `:576`) straight into `PeerNotConnected` — **#1994's own symptom, transiently
   reintroduced at the exact moment convergence is being established.**

Task 6's joiner-side receive gates on `sender == hostPeerId`, so this must land first.

**This is not a pure win — say so in review.** Today `hostPeerId` is captured only by a *self-intro*
shaped Welcome (`assignedId == sender`). Pinning it to the **first** Welcome from any sender narrows
the window from K+1 sends to 1, but on a **flat loom** it widens which Welcome *shapes* can capture:
a foreign host that broadcasts a bootstrap Welcome before the real host's arrives would now capture.
The star fabrics this track targets give a joiner exactly one edge (the host), so the widening is
unreachable there; on a flat loom #1180's `sender != establishedHost` gate then locks out the real
host, which surfaces as a failed join rather than a silent takeover. Net: strictly narrower window,
marginally wider shape-surface on a topology this track does not target, and the failure mode moves
from silent to loud.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:1340-1364`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinWindowHostIdentityTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: the invariant *"a joiner's `hostPeerId` is non-null from the first `Welcome` it accepts"*,
  plus `internal fun SeamRoom.hostPeer(): PeerId?`. Task 6's `handleRelayedDelivery` depends on both.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinWindowHostIdentityTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A joiner must identify its host from the **first** Welcome it accepts, not from the host's
 * self-introduction — which `admitPeer` sends *last*, after the roster-sync Welcome and one
 * bootstrap Welcome per pre-existing member (`SeamRoom.kt:1250-1270`).
 *
 * Across that K+1-send window the joiner holds co-members in its roster with `hostPeerId == null`.
 * A `Quilter` collecting `rosterPeers` fires `onPeersChanged` -> `sendFullStateTo(coJoiner)` into
 * `PeerNotConnected` — #1994's own symptom, transiently reintroduced while convergence is being
 * established — and it is the capture window for a forged host identity.
 */
class JoinWindowHostIdentityTest {

    /**
     * A generous wedge backstop, NOT an assertion. It is wall-clock over a virtual-time
     * trajectory, so tightening it measures the host machine, not this code (#1739, #1891).
     * Fast failure comes from the bounded `first { }` awaits below.
     */
    private val backstop = 30.seconds

    @Test
    fun `a joiner knows its host before it holds any co-member`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = star(coJoiners = 2)

            // The joiner's roster reaching 2 means it has accepted a Welcome describing a
            // co-member. hostPeerId must ALREADY be set at that point.
            star.joinerA.roster.first { it.size == 2 }

            assertAll(
                {
                    assertNotNull(
                        star.joinerAHostPeerId(),
                        "hostPeerId is still null while the roster already holds a co-member — " +
                            "this is the window in which a Quilter's sendFullStateTo hits " +
                            "PeerNotConnected, and in which a forged Welcome can capture the host",
                    )
                },
                {
                    // Positive control: the host it identified is the real one. Without this a
                    // change that set hostPeerId to some arbitrary non-null peer would pass above.
                    assertEquals(
                        star.hostId,
                        star.joinerAHostPeerId(),
                        "the identified host must be the actual host",
                    )
                },
            )
        }

    /**
     * The #1180 gate still holds afterwards: once identified, a Welcome from anyone else changes
     * nothing. Pinning both halves means neither a permissive nor a reject-everything
     * implementation passes.
     */
    @Test
    fun `a second sender cannot displace an identified host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = star(coJoiners = 2)
            star.joinerA.roster.first { it.size == 2 }
            val identified = assertNotNull(star.joinerAHostPeerId())

            // A co-joiner forges a host self-introduction naming itself.
            star.forgeWelcomeFromCoJoiner()
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        identified,
                        star.joinerAHostPeerId(),
                        "a co-joiner's forged Welcome must not move hostPeerId (#1180)",
                    )
                },
                {
                    assertEquals(
                        star.hostId,
                        identified,
                        "sanity: the identified host was the real one to begin with",
                    )
                },
            )
        }

    // ── Harness ───────────────────────────────────────────────────────────────

    private class Star(
        val hostId: PeerId,
        val joinerA: Room,
        val joinerAHostPeerId: () -> PeerId?,
        val forgeWelcomeFromCoJoiner: suspend () -> Unit,
    )

    private suspend fun TestScope.star(coJoiners: Int): Star {
        TODO(
            "Build with InMemoryRoomFabric exactly as LivenessRouteGateTest.star() does " +
                "(fabric.serverLoom for the host; fabric.clientLoom + adopt(seam, Joiner) per " +
                "joiner, keeping each joiner's own seam handle), with `coJoiners` clients so " +
                "admitPeer performs more than one bootstrap send. joinerAHostPeerId reads " +
                "(joinerA as SeamRoom).hostPeer(). forgeWelcomeFromCoJoiner sends " +
                "AdmitMessage.encode(Welcome(assignedPeerId = <coJoiner>.value, displayName = " +
                "\"forged\", sessionId = \"forged\")) from the co-joiner's raw seam to joinerA."
        )
    }
}
```

> **Worker note:** the `TODO(...)` above is the ONE place this plan does not hand you finished code,
> and it is deliberate — the harness is a mechanical transcription of
> `LivenessRouteGateTest.star()` (`kuilt-session/src/commonTest/.../LivenessRouteGateTest.kt:223-254`),
> which you must read and adapt rather than re-invent. Everything it needs is in that method:
> `InMemoryRoomFabric(backgroundScope, dispatcher, random = Random(0L))`, `SeamRoomFactory(...)`,
> `adopt(seam, SessionRole.Joiner)`, and `roster.first { it.size == N }`. Do not leave the `TODO` in
> the committed test. Task 5 generalises this into a shared `StarRelayHarness.kt` — if you can see
> that shape now, write it there directly and have this test use it.

- [ ] **Step 2: Add the internal accessor the test reads**

`hostPeerId` is a `private var`. Add a test-visible read beside `isAdmitted` (`SeamRoom.kt:2306`),
which is the established pattern for exactly this:

```kotlin
    /**
     * The peer this member has identified as its host, or `null` before identification.
     *
     * Exposed `internal` for tests and for [handleRelayedDelivery]'s host-authoritative gate; the
     * field itself stays `private var` and is only ever written under [lock].
     */
    internal fun hostPeer(): PeerId? = lock.withLock { hostPeerId }
```

There is already an anonymous-object `override fun hostPeer(): PeerId? = lock.withLock { hostPeerId }`
at `SeamRoom.kt:662` — read it first and reuse that interface if it fits rather than adding a
near-duplicate.

- [ ] **Step 3: Run the test and watch it fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*JoinWindowHostIdentityTest*"
```

Expected: FAIL on `a joiner knows its host before it holds any co-member` with
"hostPeerId is still null while the roster already holds a co-member".
The second test should already PASS (it pins the pre-existing #1180 gate) — that is the control
proving the harness itself works.

- [ ] **Step 4: Set `hostPeerId` from the first accepted Welcome**

In `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt`, in `handleWelcome`,
replace lines 1350–1364:

```kotlin
            val establishedHost = hostPeerId
            if (establishedHost != null && sender != establishedHost) return@withLock

            // Self-admission welcome: mint the resume token (once) from the roomId carried here.
            if (assignedId == selfId) {
                resumeMachine?.mintTokenIfAbsent(welcome.roomId)
                // The host explicitly admitted us — disarm the admit deadline (#1178).
                admitted.complete(Unit)
                return@withLock
            }

            // Host self-intro: the described peer IS the sender.
            if (assignedId == sender && hostPeerId == null) {
                hostPeerId = sender
            }
```

with:

```kotlin
            val establishedHost = hostPeerId
            if (establishedHost != null && sender != establishedHost) return@withLock

            // Identify the host from the FIRST Welcome we accept, whatever its shape (#1994).
            //
            // Any Welcome is by definition minted by the host, so the sender of the first one IS
            // the host. Keying on the self-introduction instead (`assignedId == sender`) left
            // `hostPeerId` null across `admitPeer`'s whole K+1-send burst — the roster-sync
            // Welcome, one bootstrap Welcome per pre-existing member, and only THEN the host
            // intro (`SeamRoom.kt:1250-1270`) — while `addToRoster` had already run under lock
            // before the first of those sends (`:1215-1219`). Across that window a joiner holds
            // co-members with no identified host, which is both:
            //   * the capture window for a forged host identity, and
            //   * a transient re-run of #1994 itself: a Quilter collecting `rosterPeers` fires
            //     onPeersChanged -> sendFullStateTo(coJoiner) into PeerNotConnected.
            //
            // This does NOT weaken the #1180 gate above — it strengthens it, by arming it one
            // send earlier. Trade, stated honestly: on a *flat* loom a foreign host whose Welcome
            // arrives first now captures via any Welcome shape rather than only a self-intro. The
            // real host's Welcomes are then rejected by the gate, so that surfaces as a failed
            // join rather than a silent takeover; and on the star fabrics this track targets a
            // joiner has exactly one edge, so it is unreachable there.
            if (establishedHost == null) hostPeerId = sender

            // Self-admission welcome: mint the resume token (once) from the roomId carried here.
            if (assignedId == selfId) {
                resumeMachine?.mintTokenIfAbsent(welcome.roomId)
                // The host explicitly admitted us — disarm the admit deadline (#1178).
                admitted.complete(Unit)
                return@withLock
            }
```

- [ ] **Step 5: Run the test — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*JoinWindowHostIdentityTest*"
```

Expected: PASS, 2 tests.

- [ ] **Step 6: Revert the fix and confirm the test catches it (TDD step 3)**

Comment out `if (establishedHost == null) hostPeerId = sender` and restore the old
`if (assignedId == sender && hostPeerId == null) { hostPeerId = sender }`. Re-run Step 5.
Expected: FAIL on the first test only. **Restore the fix.**

- [ ] **Step 7: Full module gate**

`hostPeerId` is read by `handleFarewell`, `handlePaused`, `handleResumeAck`, `HostLost` detection and
`runDetectorRouteWatcher`. Arming it earlier changes when those gates become active, and the existing
suite is what proves that is safe.

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  :kuilt-session:build --rerun-tasks
```

Expected: BUILD SUCCESSFUL. **If any pre-existing test goes red here, STOP and report it** — a
membership test failing is the design's own assumption being wrong, not a test to update.

- [ ] **Step 8: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/JoinWindowHostIdentityTest.kt
git commit -m "fix(session): identify the host from the first Welcome, not the last

admitPeer adds the joiner to the roster before any send and sends the host
self-introduction LAST, so a joiner held co-members with hostPeerId == null
across K+1 sends. In that window a Quilter's onPeersChanged -> sendFullStateTo
hits PeerNotConnected — #1994's own symptom — and a forged Welcome can capture
the host identity.

Any Welcome is minted by the host, so the first one's sender is the host. Arms
#1180's cross-admit gate one send earlier rather than weakening it.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: The relay envelope and its frame codec

**Files:**
- Create: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RelayEnvelope.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RelayEnvelopeTest.kt`

**No build-file change.** `:kuilt-session` already declares `kotlinx.serialization.cbor`. This is the
placement decision argued in Global Constraints — the envelope lives where the CBOR dependency already
is, rather than pulling that dependency into `:kuilt-core`.

**Interfaces:**
- Consumes: `RoomFramePrefix.Relay` (Task 1).
- Produces, all `internal` to `:kuilt-session`:
  - `sealed interface RelayDest` with `data object Everyone` and `data class One(val peer: PeerId)`
  - `class RelayEnvelope(val origin: PeerId, val dest: RelayDest, val payload: ByteArray)`
  - `RelayEnvelope.encode(envelope: RelayEnvelope): ByteArray` (prefixed)
  - `RelayEnvelope.decode(bytes: ByteArray): RelayEnvelope?` (null on wrong prefix **or** garbled)
  - `RelayEnvelope.isRelayFrame(bytes: ByteArray): Boolean`

  Tasks 5 and 6 use all five.

- [ ] **Step 1: Write the failing test**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RelayEnvelopeTest.kt`:

```kotlin
package us.tractat.kuilt.session

import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire envelope a host forwards between two spokes of a star (#1994).
 *
 * [RelayEnvelope.origin] is the peer that minted the payload — the fabric stamps the *relaying*
 * host as the sender, so without this field the far end would credit the host. It is carried
 * inside a forgeable frame and is therefore first-hop-validated by the receiver, never trusted.
 */
class RelayEnvelopeTest {

    private val origin = PeerId("joiner-a")
    private val target = PeerId("joiner-b")
    private val payload = byteArrayOf(0x63, 0x00, 0x07, 0x2a, 0x2b)

    @Test
    fun `an Everyone envelope round-trips with origin and payload intact`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        val decoded = assertNotNull(RelayEnvelope.decode(encoded))
        assertAll(
            { assertEquals(origin, decoded.origin) },
            { assertEquals(RelayDest.Everyone, decoded.dest) },
            { assertContentEquals(payload, decoded.payload) },
        )
    }

    @Test
    fun `a One envelope round-trips with its target intact`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.One(target), payload))
        val decoded = assertNotNull(RelayEnvelope.decode(encoded))
        assertAll(
            { assertEquals(origin, decoded.origin) },
            { assertEquals(RelayDest.One(target), decoded.dest) },
            { assertContentEquals(payload, decoded.payload) },
        )
    }

    /**
     * The two destinations must not decode to each other. A codec that dropped `dest` entirely
     * would pass both round-trip tests above independently; this is what makes them meaningful.
     */
    @Test
    fun `Everyone and One are distinguishable on the wire`() {
        val everyone = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload)),
        )
        val one = RelayEnvelope.decode(
            RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.One(target), payload)),
        )
        assertAll(
            { assertEquals(RelayDest.Everyone, assertNotNull(everyone).dest) },
            { assertEquals(RelayDest.One(target), assertNotNull(one).dest) },
        )
    }

    @Test
    fun `an encoded envelope claims the registry's relay prefix`() {
        val encoded = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        assertAll(
            { assertEquals(RoomFramePrefix.Relay.byte, encoded[0]) },
            { assertTrue(RelayEnvelope.isRelayFrame(encoded)) },
        )
    }

    @Test
    fun `a garbled body decodes to null rather than throwing`() {
        val good = RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload))
        val garbled = byteArrayOf(RoomFramePrefix.Relay.byte, 0x01, 0x02, 0x03)
        assertAll(
            // Positive control: a decoder that returned null unconditionally would satisfy every
            // negative in this test (spec correction C5).
            { assertNotNull(RelayEnvelope.decode(good)) },
            { assertNull(RelayEnvelope.decode(garbled)) },
            { assertNull(RelayEnvelope.decode(ByteArray(0))) },
        )
    }

    @Test
    fun `a frame claiming another family's prefix is not a relay frame`() {
        val channelFrame = byteArrayOf(RoomFramePrefix.Channel.byte, 0x00, 0x01)
        assertAll(
            {
                assertTrue(
                    RelayEnvelope.isRelayFrame(
                        RelayEnvelope.encode(RelayEnvelope(origin, RelayDest.Everyone, payload)),
                    ),
                )
            },
            { assertFalse(RelayEnvelope.isRelayFrame(channelFrame)) },
            { assertNull(RelayEnvelope.decode(channelFrame)) },
        )
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*RelayEnvelopeTest*"
```

Expected: FAIL — `Unresolved reference: RelayEnvelope`.

- [ ] **Step 3: Implement the envelope**

Create `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RelayEnvelope.kt`:

```kotlin
package us.tractat.kuilt.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.core.runCatchingCancellable

/**
 * Where a relayed frame is bound.
 *
 * A sealed type rather than a nullable `PeerId` so the two cases are exhaustive at every use site
 * and neither can be reached by forgetting a null check.
 */
@Serializable
internal sealed interface RelayDest {

    /** Every admitted member except the origin. The relayed form of `Room.broadcast`. */
    @Serializable
    data object Everyone : RelayDest

    /** Exactly [peer]. The relayed form of `Room.sendTo`. */
    @Serializable
    data class One(val peer: PeerId) : RelayDest
}

/**
 * One frame travelling spoke → host → spoke, with the **true originator kept intact** (#1994).
 *
 * On a star fabric a joiner's frame reaches only the host; nothing forwards it onward, so a
 * `Quilter` over `Room.channel(...)` targets co-members it can never address. The host forwards
 * the frame instead — and because the fabric then stamps the *host* as the sender, the real
 * originator has to ride inside the frame. [origin] is that field.
 *
 * ## The origin is forgeable and is never trusted
 *
 * [origin] is attacker-controlled wire data. Every receiver checks it against the fabric-stamped
 * sender via `validFirstHop` before crediting it: the host rejects a spoke naming another spoke,
 * and the joiner accepts a relay frame only from its identified host. The payload is additionally
 * gated by `SeamRoom.isRelayable`, so a relayed frame can carry application data only — never
 * admit, lobby, heartbeat, or a nested relay.
 *
 * ## The host forwards these bytes unchanged
 *
 * [dest] is meaningful on the host hop only, so there is no per-recipient re-wrapping: one
 * enqueue, one encoding, and [RelayDest.Everyone] stays `Everyone` on the wire. The joiner
 * independently re-checks that [dest] names it — a second, cheap check of the leak boundary at the
 * far end rather than trusting the host's routing.
 *
 * CBOR behind [RoomFramePrefix.Relay] (`0x72`), matching every other room frame family. Lives in
 * `:kuilt-session` rather than `:kuilt-core` because the contract module carries no CBOR
 * dependency and nothing outside this module consumes this type.
 * [equals]/[hashCode] compare [payload] by content (kuilt convention for byte-carrying types).
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal class RelayEnvelope(
    val origin: PeerId,
    val dest: RelayDest,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayEnvelope) return false
        return origin == other.origin && dest == other.dest && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = origin.hashCode()
        result = 31 * result + dest.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RelayEnvelope(origin=$origin, dest=$dest, payload=${payload.size}B)"

    internal companion object {
        /**
         * `ignoreUnknownKeys` for the same forward-compatibility reason `AdmitMessage`'s codec has
         * it: a frame minted by a newer build carrying an extra field still decodes here instead
         * of being dropped as malformed.
         */
        private val cbor = Cbor { ignoreUnknownKeys = true }

        /** Encode [envelope] with the [RoomFramePrefix.Relay] framing prefix. */
        fun encode(envelope: RelayEnvelope): ByteArray {
            val encoded = cbor.encodeToByteArray(envelope)
            return ByteArray(encoded.size + 1).also { out ->
                out[0] = RoomFramePrefix.Relay.byte
                encoded.copyInto(out, destinationOffset = 1)
            }
        }

        /**
         * Attempt to decode [bytes] as a relay frame.
         *
         * Returns `null` if [bytes] does not claim [RoomFramePrefix.Relay], or if the body is
         * malformed. Never throws: this decodes attacker-controlled wire data on the inbound path,
         * and a throw there would kill the room's single inbound collector.
         */
        fun decode(bytes: ByteArray): RelayEnvelope? {
            if (!isRelayFrame(bytes)) return null
            return runCatchingCancellable {
                cbor.decodeFromByteArray<RelayEnvelope>(bytes.copyOfRange(1, bytes.size))
            }.getOrNull()
        }

        /** Whether [bytes] claims the relay prefix. Does not decode. */
        fun isRelayFrame(bytes: ByteArray): Boolean = RoomFramePrefix.Relay.matches(bytes)
    }
}
```

- [ ] **Step 4: Run the test — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*RelayEnvelopeTest*"
```

Expected: PASS, 6 tests. If `RelayDest`'s polymorphic CBOR encoding fails to round-trip, the sealed
hierarchy needs its subclasses registered — do **not** flatten `dest` to a nullable `PeerId` to make it
pass; that reintroduces exactly the nullability the sealed type removes. Report instead.

- [ ] **Step 5: Module gate and commit**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  :kuilt-session:build detektAll --rerun-tasks
```

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RelayEnvelope.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/RelayEnvelopeTest.kt
git commit -m "feat(session): the star-relay wire envelope

Carries the true originator across the host hop, because the fabric stamps the
relaying host as the sender. Sealed RelayDest so Everyone/One are exhaustive at
every use site. Decodes attacker-controlled bytes without throwing.

Lives in :kuilt-session, not :kuilt-core — the contract module carries no CBOR
dependency and nothing outside this module consumes the type.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Host-side forwarding

The host decodes, validates, allow-lists, resolves, and enqueues on the **existing** writer.

Three properties this task exists to establish, each of which was a revision-1 defect:

- **The allow-list (C1).** Revision 1 re-entered the full `dispatchIncoming` with a synthesized
  sender, and that routes any `0x61` payload to `handleAdmitFrame`. Since `handleWelcome` is
  host-authoritative only *after* a host exists, any admitted joiner could relay a crafted `Welcome`
  and permanently capture a co-joiner's `hostPeerId`. Relayability is therefore an **allow-list**:
  a channel frame, or a payload claiming **no** registered prefix. A future frame family is excluded
  by default rather than needing to be remembered.
- **The writer (C3).** Forwards go through `admitFanOuts` (`SeamRoom.kt:1974`, drained at `:2073`),
  which already carries the per-recipient budget and `ensureActive()` discipline. **Do not add a second
  writer.** Inline sends inside the collector would let one slow spoke stall the host's entire inbound
  pipeline and manufacture false partitions.
- **Cardinality in the type (I1).** `Resolved` is sealed; `Exactly` cannot hold two peers, and
  deleting a `when` branch cannot compile.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` — new arm in
  `dispatchIncoming` (`:1037-1079`), new members near `fanOutToOtherMembers` (`:1991`)
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHostTest.kt`

**Interfaces:**
- Consumes: `RelayEnvelope`/`RelayDest` (Task 4), `validFirstHop` (Task 2), `RoomFramePrefix` (Task 1).
- Produces: `private sealed interface Resolved { None; Exactly(peer); Every(peers) }`,
  `private fun resolveRecipients(envelope: RelayEnvelope): Resolved`,
  `private fun isRelayable(payload: ByteArray): Boolean`,
  `private fun enqueueRelayForward(recipients: List<PeerId>, bytes: ByteArray)`.
  Task 6 calls `isRelayable`.

> **Scope note for the worker:** the host tests below observe delivery through the *joiner* side, which
> Task 6 implements. If you cannot make Tasks 5 and 6 independently green, **merge them into one task**
> rather than committing a half-wired relay — and say which you did. Do not weaken the tests to
> decouple them.

- [ ] **Step 1: Build the shared star harness**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt` holding:

- an `InMemoryRoomFabric` star (host + N joiners), each joiner keeping its own raw seam handle —
  model on `LivenessRouteGateTest.star()` (`:223-254`);
- `sendRelay(dest, payload)` and `sendRelayForgingOrigin(origin, dest, payload)`, writing a
  `RelayEnvelope.encode(...)` straight onto a joiner's raw seam;
- per-room collectors recording `RoomFrame`s so `appFramesFrom(peer): List<String>` can be asserted,
  plus `hostAppFramesFrom(peer)`;
- `appPayload(s: String) = s.encodeToByteArray()`.

**Payload-byte trap:** a plain-text payload is relayable only because it claims no registered prefix.
Lowercase `a`, `c`, `e`, `k`, `r` **are** `0x61`, `0x63`, `0x65`, `0x6b`, `0x72`. Every string used in
these tests ("hello", "legit", "plain", "nested", "for-b", "somewhere", "honest", "forged",
"nowhere") is safe; do not add one starting with those letters without noticing.

If Task 3's `star()` is still inline in `JoinWindowHostIdentityTest`, fold it in here — one harness,
not three.

- [ ] **Step 2: Write the failing tests**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHostTest.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package us.tractat.kuilt.session

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.PeerId
import us.tractat.kuilt.session.admit.AdmitMessage
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The host half of the star relay (#1994): decode, first-hop-validate, allow-list, resolve,
 * enqueue on the existing admit fan-out writer.
 *
 * Every negative here is paired with a positive control in the same test. Revision 1's two
 * security tests were bare `assertTrue(seen.none { … })` assertions and were green *before any
 * relay code existed* — a bug that dropped everything would have left them passing.
 */
class StarRelayHostTest {

    /** Generous wedge backstop, not an assertion — see JoinWindowHostIdentityTest. */
    private val backstop = 30.seconds

    @Test
    fun `a broadcast relay reaches the co-joiner and not the origin`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar()

            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("hello"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("hello"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the co-joiner must receive the relayed frame, credited to its ORIGIN",
                    )
                },
                {
                    assertTrue(
                        star.joinerA.appFramesFrom(star.joinerAId).isEmpty(),
                        "the origin must not receive its own broadcast back",
                    )
                },
            )
        }

    @Test
    fun `a unicast reaches only its target`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("for-b"))
            testScheduler.runCurrent()

            assertAll(
                // Positive control — without it, a relay that dropped everything passes the two
                // negatives below.
                {
                    assertEquals(
                        listOf("for-b"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the addressed peer must receive it",
                    )
                },
                {
                    assertTrue(
                        star.joinerC.appFramesFrom(star.joinerAId).isEmpty(),
                        "an unaddressed co-joiner must not observe a unicast — the leak boundary",
                    )
                },
                {
                    assertTrue(
                        star.hostAppFramesFrom(star.joinerAId).isEmpty(),
                        "the relaying host must not surface a unicast it merely forwarded",
                    )
                },
            )
        }

    @Test
    fun `a spoke cannot forge another peer's origin`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // A speaks for itself — accepted.
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("honest"))
            // A claims to be C — must be refused outright, not re-attributed.
            star.joinerA.sendRelayForgingOrigin(star.joinerCId, RelayDest.Everyone, appPayload("forged"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("honest"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the honest frame must arrive — the positive control for the refusal below",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerCId).isEmpty(),
                        "a frame whose origin names another peer must be refused, not relayed",
                    )
                },
            )
        }

    /**
     * The C1 defect, pinned. A relayed `Welcome` naming the sender as host must change nothing on
     * the recipient — specifically it must not move `hostPeerId`.
     */
    @Test
    fun `a relayed admit frame changes nothing on the recipient`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar()
            val hostBefore = star.joinerB.hostPeer()

            val forgedWelcome = AdmitMessage.encode(
                AdmitMessage.Welcome(
                    assignedPeerId = star.joinerAId.value,
                    displayName = "attacker",
                    sessionId = star.joinerAId.value,
                ),
            )
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), forgedWelcome)
            // …and one legitimate application frame, so total non-delivery cannot pass this test.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("legit"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("legit"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the application frame must still arrive — otherwise this test is vacuous",
                    )
                },
                {
                    assertEquals(
                        hostBefore,
                        star.joinerB.hostPeer(),
                        "a relayed Welcome must not capture the recipient's hostPeerId (C1)",
                    )
                },
                {
                    assertEquals(
                        star.hostId,
                        star.joinerB.hostPeer(),
                        "sanity: the recipient's host is still the real host",
                    )
                },
            )
        }

    @Test
    fun `a nested relay envelope is not honoured`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar()
            val inner = RelayEnvelope.encode(
                RelayEnvelope(star.joinerAId, RelayDest.Everyone, appPayload("nested")),
            )

            star.joinerA.sendRelay(RelayDest.Everyone, inner)
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("plain"))
            testScheduler.runCurrent()

            assertEquals(
                listOf("plain"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "a relay envelope nested inside a relay payload must be dropped, not unwrapped; " +
                    "the plain frame proves delivery works at all",
            )
        }

    @Test
    fun `an unresolvable destination is dropped and never fanned`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.joinerA.sendRelay(RelayDest.One(PeerId("ghost")), appPayload("nowhere"))
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("somewhere"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("somewhere"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "the resolvable unicast must arrive",
                    )
                },
                {
                    assertTrue(
                        star.joinerC.appFramesFrom(star.joinerAId).isEmpty(),
                        "an unresolvable dest must be DROPPED, never widened into a fan-out",
                    )
                },
            )
        }

    @Test
    fun `a relay forward to a stalled spoke does not delay a concurrent Hello`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // Head-of-line (C3): the forward is enqueued on admitFanOuts and sent by the writer, so
            // a wedged recipient cannot block the inbound collector.
            TODO(
                "Wrap joinerB's client seam in a sendTo that never returns (a black-holed link, " +
                    "the #1655 shape), relay to it, then join a fourth peer and assert " +
                    "host.roster reaches 4 within a bounded advanceTimeBy. With inline sends in " +
                    "the collector the Hello is never dispatched at all."
            )
        }
}
```

> **Worker note:** the one `TODO(...)` is the head-of-line test body — the test that proves **C3**.
> Fill it; do not delete it. If it is genuinely hard to build, say so and propose an alternative that
> still discriminates "enqueued on the writer" from "sent inline".

- [ ] **Step 3: Run the tests and watch them fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelayHostTest*"
```

Expected: every test FAILS — no relay arm exists, so nothing is forwarded and every positive control
is empty. **That is the point of the positive controls**: they fail first.

- [ ] **Step 4: Add the relay arm to `dispatchIncoming`**

In `SeamRoom.kt`, insert a new arm into the `when` at `:1037` — **before** the
`isAdmittedPeer(sender) -> routeApplicationFrame(...)` arm at `:1077`, and after the lobby arm:

```kotlin
            RelayEnvelope.isRelayFrame(bytes) -> handleRelayFrame(sender, bytes)
```

Ordering matters and is a known hazard: this arm fires **before** the existing admit guard, which is
the "an earlier guard un-pins an older test" shape this repo has hit four times. Task 9 mutates both.

- [ ] **Step 5: Implement the host half**

Add these members to `SeamRoom`, next to `fanOutToOtherMembers` (`:1991`):

```kotlin
    /**
     * Which members a relayed frame resolves to — **cardinality carried in the type**.
     *
     * A `Set<PeerId>` would erase the property the leak boundary depends on: a set of one and a
     * set of three have the same type, so "a unicast never fans out" would be a runtime property
     * of a value, invisible to the compiler and deletable by a later edit. [Exactly] *cannot* hold
     * two peers, and removing a branch from the `when` that consumes this *cannot* compile.
     *
     * For `:kuilt-deal`'s per-recipient card secrets the security property **is** cardinality, so
     * it belongs in the type.
     */
    private sealed interface Resolved {
        /** Nothing to forward — an unknown, departed, or self-addressed destination. Drop it. */
        data object None : Resolved

        /** Exactly one recipient. */
        data class Exactly(val peer: PeerId) : Resolved

        /** Every admitted member except the origin. */
        data class Every(val peers: Set<PeerId>) : Resolved
    }

    /**
     * Whether a relayed payload may be honoured — an **allow-list**, deliberately not a deny-list.
     *
     * A relayed payload is honoured only if it is an explicit channel frame, or claims **no**
     * registered prefix at all (a plain application frame). That excludes admit, lobby, heartbeat
     * and a nested [RelayEnvelope] in one predicate — and excludes a *future* frame family by
     * default rather than requiring someone to remember it.
     *
     * **Why an allow-list and not "re-dispatch with a synthesized sender".** An earlier revision of
     * this design re-entered [dispatchIncoming], which routes any `0x61` payload to
     * [handleAdmitFrame]. [handleWelcome] is host-authoritative only *after* a host exists, so any
     * admitted joiner could relay a crafted `Welcome` naming itself and capture a co-joiner's
     * `hostPeerId` — then drive every host-authoritative gate on the victim and permanently break
     * its sends. #1180 hardened that on a flat loom; the four star fabrics were protected by
     * *topology*, and a relay removes that protection on all of them.
     *
     * A relayed admit frame has no legitimate sender: the admit protocol is by construction
     * host↔joiner over the direct edge.
     */
    private fun isRelayable(payload: ByteArray): Boolean =
        RoomChannel.isChannelFrame(payload) || RoomFramePrefix.entries.none { it.matches(payload) }

    /**
     * Host-side: forward one relayed frame, or drop it.
     *
     * A joiner reaching here instead delegates to [handleRelayedDelivery] — the two roles share the
     * `0x72` prefix and are split by role, not by frame.
     */
    private fun handleRelayFrame(sender: PeerId, bytes: ByteArray) {
        if (_role.value != SessionRole.Host) {
            handleRelayedDelivery(sender, bytes)
            return
        }
        val envelope = RelayEnvelope.decode(bytes) ?: run {
            logger.debug { "room.relay.drop self=${selfId.value} from=${sender.value} reason=malformed" }
            return
        }
        // No trusted relayer tier exists at the room layer, so `trusted` is empty by construction
        // and the rule reduces to `origin == sender`: a spoke may speak only for itself. Shared
        // with :kuilt-cluster, which passes its voter core.
        if (!validFirstHop(sender = sender, origin = envelope.origin, trusted = emptySet())) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} " +
                    "origin=${envelope.origin.value} reason=origin-spoof"
            }
            return
        }
        if (!isRelayable(envelope.payload)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=not-relayable"
            }
            return
        }
        // The ORIGINAL bytes are forwarded unchanged — `dest` is meaningful on this hop only, so
        // there is no per-recipient re-wrapping, and `Everyone` stays `Everyone` on the wire.
        when (val resolved = resolveRecipients(envelope)) {
            Resolved.None ->
                logger.debug {
                    "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                        "dest=${envelope.dest} reason=unresolvable"
                }
            is Resolved.Exactly -> enqueueRelayForward(listOf(resolved.peer), bytes)
            is Resolved.Every -> enqueueRelayForward(resolved.peers.toList(), bytes)
        }
    }

    /**
     * Resolve a relayed frame's destination against the current roster.
     *
     * A self-addressed or departed destination resolves to [Resolved.None] and is dropped — never
     * widened into a fan-out, which is how a unicast would leak.
     */
    private fun resolveRecipients(envelope: RelayEnvelope): Resolved = lock.withLock {
        if (closed) return@withLock Resolved.None
        when (val dest = envelope.dest) {
            RelayDest.Everyone -> {
                val others = admittedById.keys.filterTo(mutableSetOf()) { it != envelope.origin }
                if (others.isEmpty()) Resolved.None else Resolved.Every(others)
            }
            is RelayDest.One ->
                if (dest.peer != envelope.origin && admittedById.containsKey(dest.peer)) {
                    Resolved.Exactly(dest.peer)
                } else {
                    Resolved.None
                }
        }
    }

    /**
     * Enqueue a relay forward on the **existing** [admitFanOuts] writer.
     *
     * Deliberately not a second writer, and deliberately not an inline send. [dispatchIncoming]
     * runs inside the room's single `seam.incoming.collect` body, which is effectively
     * non-blocking today; issuing up to N−1 suspending `seam.sendTo` calls there would let one
     * slow spoke stall the host's whole inbound pipeline — no `Hello` admitted, no `Resume`
     * answered, no heartbeat pong observed — which then trips the host's own detectors and
     * manufactures false partitions. Relay is unconditional and unbudgeted at the call site, so
     * any single admitted member could reach that.
     *
     * Sharing the writer also keeps order between membership announcements and relayed data: a
     * `Farewell(X)` overtaking relayed data from X would make the recipient drop X and then reject
     * X's frame at `RoomChannelSeam.incoming`'s `isAdmitted(sender)` filter.
     */
    private fun enqueueRelayForward(recipients: List<PeerId>, bytes: ByteArray) {
        if (recipients.isEmpty()) return
        val queued = admitFanOuts.trySend(
            AdmitFanOut(recipients = recipients, bytes = bytes, label = "Relay"),
        ).isSuccess
        if (!queued) {
            logger.debug {
                "room.relay.drop self=${selfId.value} reason=room-terminal " +
                    "recipients=${recipients.size}"
            }
        }
    }
```

Add `import us.tractat.kuilt.core.validFirstHop` to `SeamRoom.kt`.

- [ ] **Step 6: Stub the joiner half so this compiles**

```kotlin
    /** Implemented in the joiner-side task; see [handleRelayFrame]'s role split. */
    private fun handleRelayedDelivery(sender: PeerId, bytes: ByteArray) {
        logger.debug { "room.relay.drop self=${selfId.value} from=${sender.value} reason=unimplemented" }
    }
```

- [ ] **Step 7: Run the tests**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelayHostTest*"
```

They will still fail while the joiner half is a stub — that is expected and is why the scope note above
allows merging Tasks 5 and 6. Confirm the *host* is forwarding by reading the debug log for
`room.relay.drop … reason=unimplemented` on the recipient: that line firing proves decode,
first-hop, allow-list, resolve and enqueue all ran.

- [ ] **Step 8: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHostTest.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt
git commit -m "feat(session): host-side star relay forwarding

The host forwards a spoke's frame to its co-spokes: decode, first-hop validate,
allow-list, resolve, enqueue on the EXISTING admit fan-out writer.

Relayability is an allow-list (channel frames, or payloads claiming no registered
prefix), so admit/lobby/heartbeat/nested-relay are excluded by construction and a
future frame family is excluded by default. Re-entering the full dispatch would
let any admitted joiner relay a crafted Welcome and capture a co-joiner's host.

Recipients resolve into a sealed Resolved, so Exactly cannot hold two peers and a
deleted branch cannot compile.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Joiner-side send routing and receipt

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` —
  `broadcast` (`:2321`), `sendTo` (`:2332`), and `handleRelayedDelivery`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayJoinerTest.kt` (create)

**Interfaces:**
- Consumes: everything from Tasks 3–5.
- Produces: nothing new for later tasks.

**The send rules — keyed on the destination-set state, not on the call:**

| Condition | `sendTo(p)` | `broadcast()` |
|---|---|---|
| `role == Host` | direct | direct |
| `rosterPeers ⊆ seam.peers` | direct | direct |
| any divergence | relay via host | relay via host |

Two properties, each a revision-1 correction:

- **The host early-returns explicitly on `role == Host` (I3).** Revision 1 claimed a host always
  satisfies `rosterPeers ⊆ seam.peers`. **False** — a member inside its reconnect window stays in the
  roster while `Seam.peers` has dropped it (#1557/#1614), so a host with one partitioned member would
  have entered the relay branch. It was saved only by `hostPeerId` being incidentally `null` on a
  host, which a plausible tidy-up would break.
- **Once any divergence exists, relay everything (I2)** — including to a peer that *is* directly
  reachable. Keying `broadcast` on the roster subset but `sendTo` on the individual peer would, on a
  partial mesh, give one destination two different hop counts — and a `Quilter`'s ack
  (`Quilter.kt:717`) could then overtake the delta it acknowledges (`:557`). One consistent path per
  destination-set state is monotone and cheap.

- [ ] **Step 1: Write the failing tests**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayJoinerTest.kt` with these
five tests, against the Task 5 harness. **Each must carry a positive control in the same test.**

```kotlin
    @Test
    fun `a host sends directly even while a member is inside its reconnect window`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // I3: rosterPeers is NOT a subset of seam.peers here (the partitioned member is in the
            // roster and gone from the transport), so a subset-keyed rule would wrongly relay.
            // Assert the host's frame reaches the healthy joiner AND that nothing with the 0x72
            // prefix was ever written to the wire.
            TODO("partition one member via the fabric, then host.broadcast; assert direct delivery")
        }

    @Test
    fun `a joiner relays both broadcast and unicast once its roster diverges`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // I2: the SAME destination must take the SAME path for both call shapes.
            TODO("assert both frames arrive at the co-joiner AND both crossed the wire as 0x72")
        }

    @Test
    fun `a joiner ignores a relay frame that did not come from its host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // Positive control paired: a genuine host-relayed frame arrives; a co-joiner-injected
            // relay frame does not.
            TODO()
        }

    @Test
    fun `a joiner ignores a relayed unicast addressed to someone else`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // The far-end leak-boundary re-check: even a correctly-relayed One(other) is refused,
            // paired with a One(self) that IS honoured.
            TODO()
        }

    @Test
    fun `a relayed frame is credited to its origin, not to the relaying host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // The property RoomChannelSeam.incoming's isAdmitted(sender) filter and every Quilter
            // depend on. Assert RoomFrame.sender == originId AND != hostId.
            TODO()
        }
```

> **Worker note:** these five `TODO(...)` bodies are yours to write against the Task 5 harness. Fill
> them before running anything; none may remain in the commit.

- [ ] **Step 2: Run and watch them fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelayJoinerTest*"
```

- [ ] **Step 3: Implement joiner-side receipt**

Replace the Task 5 stub in `SeamRoom.kt`:

```kotlin
    /**
     * Joiner-side: accept a frame the host relayed on a co-member's behalf.
     *
     * Four gates, in order, each of which must independently hold:
     *
     * 1. **The sender is our identified host.** A relay frame from anyone else is a co-joiner
     *    injecting directly, which on a flat loom is reachable. Depends on `hostPeerId` being set
     *    from the *first* Welcome — see [handleWelcome].
     * 2. **`dest` names us.** The host already resolved this, but the leak boundary is re-checked
     *    at the far end rather than trusting the host's routing — cheap, and it means a
     *    misrouting host cannot silently widen a unicast.
     * 3. **The payload is relayable.** The same allow-list the host applied ([isRelayable]),
     *    applied again: a host is not trusted to have applied it.
     * 4. **The origin is an admitted member.** Otherwise the frame would be credited to a peer
     *    outside the roster, which `RoomChannelSeam.incoming`'s own `isAdmitted(sender)` filter
     *    would then drop anyway — failing here keeps the reason loggable.
     *
     * The inner payload is re-emitted stamped with the **origin** as sender, never the host. Every
     * consumer — `RoomChannelSeam.incoming`'s admitted-sender filter, every `Quilter`'s
     * per-replica accounting — keys on that field, and crediting the host would silently
     * misattribute the whole relayed stream.
     *
     * The two delivery surfaces mirror [dispatchIncoming]'s own arms for these payload kinds:
     * channel frames reach subscribers via `rawIncoming`, application frames via
     * [routeApplicationFrame]. This is a **narrow, explicit** re-implementation of exactly those
     * two arms — deliberately *not* a call back into [dispatchIncoming], which would restore the
     * admit-frame path this allow-list exists to remove.
     */
    private fun handleRelayedDelivery(sender: PeerId, bytes: ByteArray) {
        val host = lock.withLock { hostPeerId }
        if (host == null || sender != host) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} " +
                    "host=${host?.value} reason=not-from-host"
            }
            return
        }
        val envelope = RelayEnvelope.decode(bytes) ?: run {
            logger.debug { "room.relay.drop self=${selfId.value} reason=malformed" }
            return
        }
        val addressed = when (val dest = envelope.dest) {
            RelayDest.Everyone -> true
            is RelayDest.One -> dest.peer == selfId
        }
        if (!addressed) {
            logger.debug {
                "room.relay.drop self=${selfId.value} dest=${envelope.dest} reason=not-addressed"
            }
            return
        }
        if (!isRelayable(envelope.payload)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=not-relayable"
            }
            return
        }
        if (!isAdmittedPeer(envelope.origin)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                    "reason=origin-not-admitted"
            }
            return
        }
        // Stamped with the ORIGIN, never the relaying host.
        rawIncoming.tryEmit(Swatch(envelope.payload, sender = envelope.origin))
        if (!RoomChannel.isChannelFrame(envelope.payload)) {
            routeApplicationFrame(envelope.origin, envelope.payload)
        }
    }
```

- [ ] **Step 4: Implement joiner-side send routing**

Replace `broadcast` (`:2321`) and `sendTo` (`:2332`):

```kotlin
    /**
     * Broadcast [bytes] to all admitted members.
     *
     * On a star fabric a spoke's frame reaches only the host, so once this member's roster diverges
     * from what the transport can address, the frame is wrapped and **relayed via the host**
     * instead. `RoomChannelSeam` therefore needs no change: it already delegates here, so
     * `peers = room.rosterPeers` becomes honest for free.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     *
     * **Lossy without error on a star.** An unresolvable destination is dropped with a debug log and
     * a torn recipient's send is swallowed by [runAdmitFanOutWriter]'s best-effort discipline. On a
     * mesh this call surfaces failures; relayed, it does not.
     */
    override suspend fun broadcast(bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        val host = relayHostOrNull() ?: return seam.broadcast(bytes)
        seam.sendTo(host, RelayEnvelope.encode(RelayEnvelope(selfId, RelayDest.Everyone, bytes)))
    }

    /**
     * Send [bytes] to one specific admitted member, relaying via the host when the transport cannot
     * address that member directly — see [broadcast] for the routing rule and the loss semantics.
     */
    override suspend fun sendTo(peer: PeerId, bytes: ByteArray) {
        val terminal = lock.withLock { hostLost || closed }
        if (terminal) return
        val host = relayHostOrNull() ?: return seam.sendTo(peer, bytes)
        seam.sendTo(host, RelayEnvelope.encode(RelayEnvelope(selfId, RelayDest.One(peer), bytes)))
    }

    /**
     * The host to relay through, or `null` to send directly.
     *
     * Returns `null` — direct — in two cases:
     *
     * - **This member is the host.** Keyed on the role *explicitly*, not on the subset test below.
     *   A host does **not** always satisfy `rosterPeers ⊆ seam.peers`: a member inside its reconnect
     *   window stays in the roster while the transport has dropped it (#1557/#1614), so a host with
     *   one partitioned member would otherwise enter the relay branch and try to relay through
     *   itself. An earlier revision of this design was saved from that only by `hostPeerId` being
     *   incidentally `null` on a host — which a plausible tidy-up would have broken.
     * - **The roster is a subset of what the transport can address**, i.e. a full mesh. Nothing to
     *   relay.
     *
     * Otherwise **everything** relays, including frames to a peer that *is* directly reachable.
     * Keying `broadcast` on the roster subset but `sendTo` on the individual peer would, on a
     * partial mesh, give one destination two different hop counts — and a `Quilter`'s ack
     * (`Quilter.kt:717`) could then overtake the delta it acknowledges (`:557`). One consistent
     * path per destination-set state is monotone and cheap.
     *
     * A `null` [hostPeerId] while the roster diverges is an **invariant violation on a joiner**, not
     * a degrade-quietly case: [handleWelcome] sets it from the first Welcome, which necessarily
     * precedes any co-member entering the roster. It is logged at warn and the send falls back to
     * direct, which is the pre-#1994 behaviour.
     */
    private fun relayHostOrNull(): PeerId? {
        if (_role.value == SessionRole.Host) return null
        val (roster, host) = lock.withLock { _rosterPeers.value to hostPeerId }
        if (roster.all { it in seam.peers.value }) return null
        if (host == null) {
            logger.warn {
                "room.relay.no-host self=${selfId.value} roster=${roster.map { it.value }} " +
                    "seamPeers=${seam.peers.value.map { it.value }} — sending direct"
            }
            return null
        }
        return host
    }
```

- [ ] **Step 5: Run both relay suites — they must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelay*"
```

- [ ] **Step 6: Full build — this is a runtime-behavior change**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew build --rerun-tasks
```

A `:kuilt-session`-scoped build is a **false green** here: it skips the `:examples` and
`:kuilt-cluster` E2E tests, and `ClusterClient` rides `Room.channel("raft")` — the exact surface this
task changes. Run the full build.

- [ ] **Step 7: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayJoinerTest.kt
git commit -m "feat(session): route a joiner's sends through the host when the roster diverges

Both broadcast and sendTo relay once rosterPeers is not a subset of seam.peers,
so one destination never takes two different hop counts (an ack could otherwise
overtake the delta it acks). The host early-returns to the direct path on ROLE,
not on the subset test — a host with a partitioned member fails that test.

On receipt a joiner re-checks the host identity, that dest names it, the
relayability allow-list, and roster membership of the origin — then re-emits the
payload stamped with the ORIGIN, never the relaying host.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Protocol version 2, and close the `isSupported(null)` carve-out

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/ProtocolVersion.kt`
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:1089-1095` (the
  comment above the gate, which currently states the opposite)
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/admit/ProtocolVersionTest.kt`

**Interfaces:** consumes nothing; produces nothing for later tasks.

Spec correction **C4**: revision 1 left `isSupported(null)` permissive, which re-admits precisely the
population the bump exists to exclude.

- [ ] **Step 1: Write the failing test**

```kotlin
package us.tractat.kuilt.session.admit

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admit-time version gate after the star-relay bump (#1994).
 *
 * A version-less peer predates #1569 and is therefore definitionally incapable of relaying, so it
 * is locked out of rooms. That is the intended cost of the bump, not an oversight: leaving `null`
 * permissive would re-admit exactly the population the bump exists to exclude.
 */
class ProtocolVersionTest {

    @Test
    fun `the version line is 2`() {
        assertAll(
            { assertEquals(2, ProtocolVersion.CURRENT) },
            { assertEquals(2, ProtocolVersion.MIN_SUPPORTED) },
            { assertEquals(2, ProtocolVersion.MAX_SUPPORTED) },
        )
    }

    @Test
    fun `a version-less peer is refused`() {
        assertAll(
            // Positive control: a gate that refused everything would satisfy the negatives.
            { assertTrue(ProtocolVersion.isSupported(2)) },
            { assertFalse(ProtocolVersion.isSupported(null)) },
            { assertFalse(ProtocolVersion.isSupported(1)) },
            { assertFalse(ProtocolVersion.isSupported(3)) },
        )
    }
}
```

- [ ] **Step 2: Run and watch it fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*ProtocolVersionTest*"
```

Expected: FAIL on both — `CURRENT` is 1 and `isSupported(null)` is `true`.

- [ ] **Step 3: Bump the line and close the carve-out**

Replace everything below the `package` line in `ProtocolVersion.kt`:

```kotlin
/**
 * The admit-handshake protocol version this build speaks, and the range of versions it can admit.
 *
 * Two peers running incompatible kuilt builds otherwise complete the handshake and then fail
 * later, opaquely, on the first frame neither side can decode. A joiner declares its version in
 * [AdmitMessage.Hello.protocolVersion]; the host compares it against [MIN_SUPPORTED]..[MAX_SUPPORTED]
 * and rejects a mismatch at admit time with [RejectCode.ProtocolMismatch] (#1569).
 *
 * **The version is the single source of truth** — never a magic number scattered across call
 * sites. A joiner stamps [CURRENT]; the host gates on [isSupported].
 *
 * ## Version 2 — the star relay (#1994), and why `null` is no longer tolerated
 *
 * Version 2 relays frames between spokes of a star fabric. Version 1 does not, so a v1 peer
 * admitted to a v2 room would have every relayed frame black-holed — the failure the gate exists
 * to prevent, arriving later and more opaquely than a refusal.
 *
 * A peer that predates the version field sends no version, decoding as `null`. Before v2 that was
 * treated as legacy and admitted. It no longer is: `null` means "predates #1569", i.e.
 * *definitionally* incapable of relaying — exactly the population this bump exists to exclude. A
 * version-less peer is locked out of rooms, and that is the intended cost.
 *
 * ## The limit of this gate, stated plainly
 *
 * The gate lives **host-side**, in `SeamRoom.handleAdmitFrame`. A **pre-#1569 host** has no gate at
 * all: it will admit a v2 joiner and then black-hole every relayed frame. Nothing on this side can
 * defend that case — it is documented, not fixed.
 */
public object ProtocolVersion {
    /** The protocol version this build stamps into every [AdmitMessage.Hello] it sends. */
    public const val CURRENT: Int = 2

    /** Oldest declared version this build will admit. */
    public const val MIN_SUPPORTED: Int = 2

    /** Newest declared version this build will admit. */
    public const val MAX_SUPPORTED: Int = 2

    /**
     * Whether a joiner's declared [version] is one this host can admit.
     *
     * `null` — a peer predating the version field — is **refused**. See the class KDoc for why the
     * pre-v2 permissive carve-out was closed rather than kept.
     */
    public fun isSupported(version: Int?): Boolean =
        version != null && version in MIN_SUPPORTED..MAX_SUPPORTED
}
```

- [ ] **Step 4: Correct the now-false comment at the gate**

In `SeamRoom.kt:1089-1095`, replace:

```kotlin
                    // Protocol-version gate (#1569). A joiner declaring a version outside this
                    // build's supported range is refused at admit time with a terminal
                    // ProtocolMismatch — better than completing the handshake and failing later on
                    // a frame neither side can decode. A version-less Hello (a peer predating the
                    // field) is legacy and stays permissive: ProtocolVersion.isSupported(null) is
                    // true, so older peers are never locked out.
```

with:

```kotlin
                    // Protocol-version gate (#1569, tightened for #1994). A joiner declaring a
                    // version outside this build's supported range is refused at admit time with a
                    // terminal ProtocolMismatch — better than completing the handshake and failing
                    // later on a frame neither side can decode. Since v2 a version-LESS Hello is
                    // refused too: no declared version means the peer predates #1569 and therefore
                    // cannot relay, which is exactly the population the bump excludes. See
                    // ProtocolVersion's KDoc, including why a pre-#1569 HOST is undefendable.
```

- [ ] **Step 5: Run the test — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*ProtocolVersionTest*"
```

- [ ] **Step 6: Full build**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew build --rerun-tasks
```

Any existing test that constructs a `Hello` without a version, or asserts a version-less peer is
admitted, now fails **correctly**. Update those to declare `ProtocolVersion.CURRENT` — and if one
exists specifically to pin "a version-less peer is admitted", **invert it** to pin the refusal and cite
#1994 in its KDoc. Do not delete it.

- [ ] **Step 7: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/admit/ProtocolVersion.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/admit/ProtocolVersionTest.kt
git commit -m "feat(session): protocol version 2, and refuse a version-less peer

v2 relays between spokes; v1 does not, so a v1 peer in a v2 room would have every
relayed frame black-holed. A version-less Hello predates #1569 and is therefore
definitionally incapable of relaying — leaving isSupported(null) permissive would
re-admit exactly the population the bump exists to exclude, so the carve-out is
closed.

The gate is host-side, so a pre-#1569 HOST has no gate at all and will admit a v2
joiner then black-hole its relays. Undefendable from this side; documented.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: The done criteria — a Quilter converges between two spokes

This is #1994's acceptance test and the reason the whole track exists.

**Files:**
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarQuilterConvergenceTest.kt` (create)

`:kuilt-session` already test-depends on `:kuilt-quilter` and `:kuilt-crdt` — **no build-file change.**

**Interfaces:** consumes everything; produces nothing.

- [ ] **Step 1: Write the test**

```kotlin
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package us.tractat.kuilt.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import us.tractat.kuilt.crdt.GSet
import us.tractat.kuilt.crdt.ReplicaId
import us.tractat.kuilt.quilter.QuiltMessage
import us.tractat.kuilt.quilter.Quilter
import us.tractat.kuilt.quilter.QuilterConfig
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * #1994's done criterion: two spokes of a star fabric, each with a [Quilter] over the same
 * `Room.channel`, converge.
 *
 * Before the relay this was impossible. `RoomChannelSeam` publishes the **roster** as `Seam.peers`
 * but routed `sendTo` through the **transport**, and on a star those are different sets — so a
 * Quilter targeted co-members it could never address. `broadcast` did not save it either:
 * `MuxServerLoom.readLoop` spools a spoke's frame into the *host's* incoming and stops, pinned by
 * #1588's `spokeFramesReachOnlyTheHostNeverAnotherSpoke`.
 */
class StarQuilterConvergenceTest {

    /** Generous wedge backstop, not an assertion (#1739, #1891). */
    private val backstop = 30.seconds

    /**
     * Comfortably past the Quilter's anti-entropy interval. *Virtual* time, so this costs nothing —
     * unlike the wall-clock [backstop], this one is allowed to be a real bound.
     */
    private val convergenceBudget = 10.seconds

    private val quilterConfig = QuilterConfig(expectVirtualTime = true)

    private fun setReplicator(room: Room, scope: CoroutineScope): Quilter<GSet<String>> = Quilter(
        replica = ReplicaId(room.selfId.value),
        seam = room.channel("star-set"),
        initial = GSet.empty(),
        messageSerializer = QuiltMessage.serializer(GSet.serializer(String.serializer())),
        scope = scope,
        config = quilterConfig,
    )

    @Test
    fun `a mutation on one spoke becomes observable on the other`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar()

            // Quilter auto-starts — there is no start().
            val onA = setReplicator(star.joinerARoom, backgroundScope)
            val onB = setReplicator(star.joinerBRoom, backgroundScope)
            testScheduler.runCurrent()

            onA.apply(onA.state.value.add("from-a"))
            onB.apply(onB.state.value.add("from-b"))

            // Bounded advancement — never advanceUntilIdle(); the Quilter's anti-entropy timer
            // re-arms forever, so the idle state is never reached.
            testScheduler.advanceTimeBy(convergenceBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        "from-a" in onB.state.value.elements,
                        "A's mutation must reach B — the #1994 done criterion. " +
                            "Got ${onB.state.value.elements}",
                    )
                },
                {
                    assertTrue(
                        "from-b" in onA.state.value.elements,
                        "and symmetrically. Got ${onA.state.value.elements}",
                    )
                },
                // Positive control on the harness itself: a run where neither replicator saw its
                // OWN mutation would be measuring nothing at all.
                { assertTrue("from-a" in onA.state.value.elements) },
                { assertTrue("from-b" in onB.state.value.elements) },
            )
        }
}
```

> **Worker note:** `relayStar()` and its `joinerARoom` / `joinerBRoom` accessors come from Task 5's
> `StarRelayHarness.kt`. Extend that harness rather than building a third star.

- [ ] **Step 2: Run it**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarQuilterConvergenceTest*"
```

Expected: PASS. If it fails, **do not widen `convergenceBudget` and retry** — a non-converging
trajectory is the bug this whole track is about. Read the Quilter's debug log for `PeerNotConnected`
or a `room.relay.drop`, and report.

- [ ] **Step 3: Prove it was actually impossible before (the anti-vacuity check)**

Temporarily change Task 6's `relayHostOrNull()` to `return null` unconditionally (always direct) and
re-run Step 2. Expected: **FAIL** — neither element crosses. Restore. Record the observed failure
output in the PR: this is what proves the test measures the relay and not the harness.

- [ ] **Step 4: Full build and commit**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew build --rerun-tasks
```

```bash
git add kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarQuilterConvergenceTest.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt
git commit -m "test(session): a Quilter converges between two spokes of a star

#1994's done criterion. Verified non-vacuous by reverting the relay routing and
confirming neither element crosses.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Mutation-verify the guard set

The relay branch fires **before** the existing `isAdmittedPeer(sender) -> routeApplicationFrame` arm.
That is the "an earlier guard un-pins an older test" shape this repo has hit four times: an earlier
guard can absorb every input that used to reach a later one, leaving the later one's tests green
against a mutant.

**Files:** none committed — this is a verification pass whose output is a table in the PR body.

**The mutation set, individually AND in pairs** (a test can pin `G₁ ∨ G₂` while pinning neither
conjunct):

| # | Mutation | Expected to redden |
|---|---|---|
| M1 | `isAdmittedPeer(sender) -> routeApplicationFrame` → `true -> routeApplicationFrame` | pre-existing admit-gate tests |
| M2 | `validFirstHop(...)` → `true` | `a spoke cannot forge another peer's origin` |
| M3 | joiner's `sender != host` check → always accept | `a joiner ignores a relay frame that did not come from its host` |
| M4 | `isRelayable(...)` → `true` | `a relayed admit frame changes nothing`, `a nested relay envelope is not honoured` |
| M5 | `is RelayDest.One -> dest.peer == selfId` → `true` | `a joiner ignores a relayed unicast addressed to someone else` |
| M6 | M2 ∧ M4 | at least one of M2's / M4's tests |
| M7 | M3 ∧ M5 | at least one of M3's / M5's tests |
| M8 | M1 ∧ M4 | at least one |

- [ ] **Step 1: For each mutation — apply, build, record, revert**

```bash
# Per mutation, in order. ABORT ON A NON-ZERO BUILD EXIT BEFORE PARSING ANY XML.
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  :kuilt-session:jvmTest --rerun-tasks
echo "build exit: $?"
```

**A mutation that fails to COMPILE makes an XML-parsing harness report the PREVIOUS mutation's
verdict** — inverting the answer toward a plausible-looking finding. Check the exit code *before*
reading `kuilt-session/build/test-results/`. A non-zero exit means "this mutation is not testable as
written", not "green".

Write each build's log to a file **inside your own worktree**, never the shared scratchpad — it is
shared across concurrent workers and a sibling's log can be quoted as your own.

- [ ] **Step 2: Investigate every GREEN**

A surviving mutant is a hole. Six ways a green verdict lies, all seen in this repo: an earlier guard
shadowing a later one; an inert house-style change; a permissive-direction mutation the tests never
exercise; a stubbed-out double; a blast-radius-only red; and the stale-XML inversion above. For each
green, either write the test that reddens it or state in the PR why the mutant is genuinely
unobservable.

- [ ] **Step 3: Put the table in the PR body**

Report the *actual* verdicts, including greens. A mutation table with no greens is more suspicious
than one with two explained greens.

---

### Task 10: Documentation

**Files:**
- Modify: `docs/fabric-peer-routing.md` — four fabric rows, plus the liveness carve-out
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt` — `peers` KDoc
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt` —
  `broadcast`/`sendTo` KDoc
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt:1524-1526` —
  `startDetector`'s stale `TieredSeam` reason
- Modify: `docs/agent-cookbook.md` — a symptom→primitive entry
- Verify: `.claude/skills/kuilt-primitives/SKILL.md` still routes to it

- [ ] **Step 1: Fill the matrix's empty middle category**

Four rows move from "Star without relay / **No**" to "**Star with relay** / **Yes, via the host**":
`MeshSeam` via `hubMesh` (spoke side), `RoomHubSeam` / `MuxServerLoom`, `:kuilt-multipeer`,
`:kuilt-nearby`. The doc already defines that category and has never had a member — say so, and note
that the route exists **at the Room layer**, not at the Seam layer: `Seam.sendTo` on those fabrics
still throws `PeerNotConnected`, and that is correct.

Add the liveness carve-out immediately after the matrix:

```markdown
### Data is relayed; liveness is not

Heartbeat detectors stay on **direct edges only**. #1592's route gate reads the *underlying*
`seam.peers`, which the relay does not alter — and that is deliberate. A detector's traffic is
per-peer, continuous, and O(N²) in the roster, which is exactly the load #1576 removed; relaying it
would put all of it through the host. Presence for a member with no direct edge still comes from the
host's authoritative fan-out (#1557).

Once the roster is routable this gate reads as over-conservative and invites a "fix". It is not one.
```

- [ ] **Step 2: `Seam.peers` — membership versus routability**

Add to the `peers` KDoc:

```kotlin
     * **Membership, not one-hop reachability.** A peer in this set is reachable, but not
     * necessarily *directly*: a `Room` over a star fabric relays through the host, so a peer here
     * may be two hops away. Do not read this set as "peers I hold a link to" — on a star that is a
     * strictly smaller set, and the gap between the two is what #1994 was.
```

- [ ] **Step 3: `Room.broadcast` / `Room.sendTo` — the honest loss semantics**

The KDoc must **not** promise "reaches that member on every fabric" (spec correction **I5**):

```kotlin
     * **Reaches every admitted member**, relaying through the host on a star fabric where no
     * direct route exists (#1994).
     *
     * **Lossy without error when relayed.** On a mesh, `sendTo` surfaces a failure as
     * `PeerNotConnected`. Relayed, it does not: an unresolvable destination is dropped with a debug
     * log, and a torn recipient's send is swallowed by the host's best-effort fan-out writer. A
     * caller that needs delivery confirmation must get it from the application protocol, not from
     * this call returning normally.
```

- [ ] **Step 4: Correct the two live stale `TieredSeam` claims**

The spec lists three. **`docs/fabric-peer-routing.md:47` was already corrected on `main`** by commit
`c82e39a6` (2026-07-31) — verify that before touching it, and do not "fix" it twice.

The two that remain:

`SeamRoom.kt:1524-1526` — replace:

```kotlin
     * The gate must not be keyed off catching [us.tractat.kuilt.core.PeerNotConnected]:
     * `TieredSeam.sendTo` silently *drops* a peer owned by neither tier, so an exception-keyed
     * check would miss it entirely.
```

with:

```kotlin
     * The gate is keyed on `Seam.peers` membership rather than on catching
     * [us.tractat.kuilt.core.PeerNotConnected]. The original reason — that `TieredSeam.sendTo`
     * *silently dropped* a peer owned by neither tier — is **no longer true**: #1935 is closed and
     * `TieredSeam.kt:236` now throws. The gate's design is still right, for a better reason: a
     * membership test is a *positive* statement about reachability, while an exception-keyed check
     * infers it from a failure and so cannot distinguish "no route" from "route, send failed".
     *
     * Kept as a worked example of the stale-citation hazard: a claim tied to an issue number
     * silently inverts when that issue is fixed. Verify before resting an argument on one.
```

And #1994's own body, at lines 29 and 91 ("`TieredSeam` is worse — it drops silently rather than
throwing" / "the only option that also covers `TieredSeam`'s silent drop"):

```bash
gh issue view 1994 --json body --jq .body > /tmp/1994-body.md   # edit, then:
gh issue edit 1994 --body-file /tmp/1994-body.md
```

Lead the edited body with what is now true and link the superseding analysis rather than restating it.
A body contradicted by its own comments is worse than a thin one.

- [ ] **Step 5: Cookbook entry + skill routing**

Add to `docs/agent-cookbook.md` a symptom→primitive entry — *"two peers in the same room can't see
each other's updates / a Quilter never converges between two joiners"* → the relay is automatic at the
Room layer; use `Room.channel(...)`, not the raw fabric `Seam`. Quote a **compiled** snippet with a
`<!-- verbatim from … -->` citation (point it at `StarQuilterConvergenceTest`), then run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew verifyDocCitations
```

A citation that has drifted fails the build. If the block cannot be a literal quote, use
`condensed from` — but only when re-copying is not a two-line edit, and never merely because you left
the middle out (use `// …` markers for that, and name a `#symbol`).

Then confirm `.claude/skills/kuilt-primitives/SKILL.md` still routes to this and that its `description`
matches how a developer would actually phrase the need.

- [ ] **Step 6: Docs gate and commit**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew \
  verifyDocCitations detektAll build --rerun-tasks
```

```bash
git add docs/ kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt
git commit -m "docs: a star's roster is routable, and two stale TieredSeam claims

Four fabrics move into the routing matrix's 'star with relay' category, which was
defined but empty. Records that data is relayed and liveness deliberately is not.

Room.broadcast/sendTo now say they are lossy-WITHOUT-error when relayed, rather
than promising delivery on every fabric. Seam.peers distinguishes membership from
one-hop reachability.

Corrects startDetector's stale reason: TieredSeam.sendTo throws since #1935, so
the argument that it drops silently no longer holds. The gate is still right, for
a better reason. (docs/fabric-peer-routing.md was already corrected in c82e39a6.)

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Landing

- [ ] Take PR [#2026](https://github.com/tractat-us/kuilt/pull/2026) out of draft, retitle it for the
      implementation, and **add `closes #1994`** — it is currently a docs-only claim PR with no closing
      keyword. Keep `closes #2007` on Task 1's work.
- [ ] Rebase onto current `origin/main` **before** `gh pr ready`.
- [ ] `~/.claude/bin/gh-pr-wait 2026 --arm-auto`. Do not hand-roll a poll loop.
- [ ] Post the mutation table (Task 9) and the anti-vacuity output (Task 8 Step 3) in the PR body.

## Follow-ups to file when this lands

Both are named as non-goals in the spec, and each should be filed **in the same turn** as the decision
that defers it:

- **Relay payload budgeting.** The envelope is unbudgeted. Revision 1 claimed "the
  `RELAY_HEADER_BUDGET` reservation is honoured" — **false** (spec correction **I7**): that constant is
  `internal` to `:kuilt-cluster` and invisible here, and there is no payload-limit surface at this
  layer. A payload that fits unrelayed can exceed the fabric's limit once wrapped
  (`FrameTooLargeException` from `:kuilt-stream`). The envelope adds roughly 40–60 bytes and nothing
  reserves it. Only meaningful once the relay lands.
- **Per-`PeerId` fan-out keying.** `admitFanOuts` is a global FIFO, so a wedged recipient delays
  healthy ones by up to one budget each. Already noted as a follow-up in `runAdmitFanOutWriter`'s
  KDoc; the relay increases the traffic through it, which strengthens the case.

**Not a follow-up:** migrating `:kuilt-cluster` onto the session's relay. **Withdrawn as a goal**, not
deferred — with `resolveRecipients` staying session-local there is nothing left to migrate beyond
`validFirstHop`, which Task 2 already does.
