# Star Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Revision 3.** Revision 1 was withdrawn (remotely-triggerable co-joiner takeover, fan-out suspended
inside the single inbound collector, a tautological de-risking prototype). Revision 2 fixed those and
was then reviewed; that review found **five more blocking defects**, folded in here. Written against
**revision 2** of
[`docs/superpowers/specs/2026-08-03-star-relay-design.md`](../specs/2026-08-03-star-relay-design.md),
whose "What revision 1 got wrong" table is one of the two checklists it satisfies.

### What the review of revision 2 changed

| # | Defect | Correction |
|---|---|---|
| **B1** | **The host was excluded from every relayed delivery.** `admittedById` never contains `selfId` (`addToRoster` is called only for *other* peers, `SeamRoom.kt:1217`/`:1376`), so `One(hostId)` resolved to `None` and was dropped, and `Everyone` forwarded to co-joiners while never delivering to the host. Meanwhile the joiner's relay path replaced `seam.broadcast` — which **does** reach the host today — with a send the host forwards away. A joiner's frames would have stopped reaching the host entirely on any 3+ member star. | `Resolved` gains explicit local-delivery cases; the host delivers to itself **and** forwards. Two new positive-control tests (Task 5 §T1, §T2). |
| **B2** | **No admitted-sender gate on the host relay arm.** It fired before the existing `isAdmittedPeer` arm and never re-checked admission, so an unadmitted peer could drive an unbounded fan-out. Every other application-data path in `dispatchIncoming` is admit-gated; this was the first that was not. | `isAdmittedPeer(sender)` is the **first** gate in the host branch, with its own test. |
| **B3** | **Sharing `admitFanOuts` invalidated that queue's own growth analysis.** Its KDoc says growth is bounded because "what enqueues" is membership *transitions*, "on the heartbeat timescale rather than **per-frame**". Moving the data plane onto it makes one wedged spoke (75 s budget, `Channel.UNLIMITED`) delay every `Paused`/`Unpaused`/`Farewell` behind it — the exact permanent roster divergence #1781 built the queue to prevent. | A **dedicated, bounded** relay queue and writer. See "Why a second writer does not violate C3". |
| **B4** | **Task 3's "watch it fail" step could not fail.** `Room.roster` excludes self, and the *first* joiner's roster only reaches 2 after the host-intro has already set `hostPeerId`. The test subject could never exhibit the window — C5 reintroduced in the task that exists to close a security hole. | Observe the **last** joiner and gate on holding a non-host member, not on a size. |
| **B5** | **The host-side leak-boundary negative was vacuous** — green because of B1, not because of the boundary. | Paired with the B1 positive controls; it only becomes meaningful once the host receives relayed frames at all. |

Five `SHOULD-FIX` items are also folded in: fail-fast on a null `hostPeerId` (**S1**, restoring spec
item I4, which revision 2 had reversed); relayed payloads must not feed liveness detectors (**S2**);
log a dropped `tryEmit` (**S3**); `@SerialName` on `RelayDest` (**S4**); Tasks 5 and 6 **merged**
(**S5**); the head-of-line test specified rather than delegated (**S6**).

**Upheld on review:** the module-placement deviation below (J1 — "do not overturn it"), and the
`validFirstHop(…, emptySet())` degeneracy, which is *not* the revision-1 tautology because
`:kuilt-cluster` exercises the function non-degenerately and the swap in Task 2 can genuinely go red.

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
- **Consensus/runtime behavior gate:** the final gate for Tasks 5–7 is the **full** `./gradlew build`,
  not a module-scoped build — a `:kuilt-session`-scoped build skips the `:examples` / `:kuilt-cluster`
  E2E tests.
- **Cache-disabled verification before auto-merge:** `--rerun-tasks`, and confirm tasks are `EXECUTED`
  not `FROM-CACHE`.
- **There is a mandatory REVIEW GATE after Task 5** — full build, an early anti-vacuity check, the
  mutation set run early, and an independent adversarial review. Tasks 6–9 do not start until it
  passes. See the gate's own section for why it is not a formality.

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

**Reviewed and upheld** — "the module-placement deviation is correct; do not overturn it". The review
verified independently that nothing outside `:kuilt-session` plausibly needs `RelayEnvelope`
(`:kuilt-cluster` keeps its own `RaftRelay` dialect and delivers into a
`MutableSharedFlow<RaftEnvelope>` rather than a peer set; `:kuilt-gossip` decorates below the Room
layer), that `internal` blocks nothing today and `internal` → `public` is a non-breaking one-line
change pre-1.0, and that #2007's body says verbatim "One registry in `:kuilt-session` owning the byte
space" — so an envelope framed behind `RoomFramePrefix.Relay` genuinely cannot live in `:kuilt-core`
without either contradicting #2007 or splitting the prefix from the codec.

**One follow-up, owned by Task 9:** the spec's Architecture bullet contradicts its own Decisions row.
Fold this resolution back into the spec (a revision-3 note is enough) rather than leaving the deviation
plan-level — a design of record left self-contradicting is the stale-body hazard this repo keeps
re-learning. **Done** — see the spec's "Revision 3 corrections, folded back from implementation",
§R2, which carries this argument and the honest weakness below.

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
  `SeamRoom.kt:1525` and #1994's own body (lines 29 and 91). Task 9 fixes exactly those two.
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

Task 5's joiner-side receive gates on `sender == hostPeerId`, so this must land first.

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
  plus `internal fun SeamRoom.hostPeer(): PeerId?`. Task 5's `handleRelayedDelivery` depends on both.

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
            val star = star(coJoiners = 3)

            // MUST be the LAST joiner, and MUST gate on holding a NON-HOST member.
            //
            // `Room.roster` excludes self, and the FIRST joiner is admitted while
            // `existingMembers` is empty — so it receives only the self-admission Welcome (early
            // return, no roster add) and then the host-intro, which sets `hostPeerId` and adds the
            // host in the same call. Its roster therefore never holds a co-member before
            // `hostPeerId` is set: the window is structurally unreachable for that subject, and a
            // size-keyed wait on it makes this test green before the fix exists.
            //
            // The last joiner is admitted with two pre-existing members, so `admitPeer` sends
            // self-admission, then a bootstrap Welcome per existing member, and only THEN the
            // host-intro. Its roster holds a non-host member across that gap — the window.
            star.lastJoiner.roster.first { members -> members.any { it.id != star.hostId } }

            assertAll(
                {
                    assertNotNull(
                        star.lastJoinerHostPeerId(),
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
                        star.lastJoinerHostPeerId(),
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
            val star = star(coJoiners = 3)
            star.lastJoiner.roster.first { members -> members.any { it.id != star.hostId } }
            val identified = assertNotNull(star.lastJoinerHostPeerId())

            // A co-joiner forges a host self-introduction naming itself.
            star.forgeWelcomeFromCoJoiner()
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        identified,
                        star.lastJoinerHostPeerId(),
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
        /** The joiner admitted LAST — the only one whose admit burst has bootstrap sends. */
        val lastJoiner: Room,
        val lastJoinerHostPeerId: () -> PeerId?,
        val forgeWelcomeFromCoJoiner: suspend () -> Unit,
    )

    private suspend fun TestScope.star(coJoiners: Int): Star {
        TODO(
            "Build with InMemoryRoomFabric exactly as LivenessRouteGateTest.star() does " +
                "(fabric.serverLoom for the host; fabric.clientLoom + adopt(seam, Joiner) per " +
                "joiner, keeping each joiner's own seam handle). Join the joiners SEQUENTIALLY, " +
                "awaiting each one's admission before starting the next, so the last one is " +
                "admitted with two pre-existing members and admitPeer performs real bootstrap " +
                "sends before the host-intro. lastJoinerHostPeerId reads " +
                "(lastJoiner as SeamRoom).hostPeer(). forgeWelcomeFromCoJoiner sends " +
                "AdmitMessage.encode(Welcome(assignedPeerId = <coJoiner>.value, displayName = " +
                "\"forged\", sessionId = \"forged\")) from a co-joiner's raw seam to lastJoiner."
        )
    }
}
```

> **Sequential joins are load-bearing, not incidental.** If all three joiners are admitted
> concurrently the interleaving decides whether `existingMembers` is populated when the subject is
> admitted, and the test becomes order-dependent — green on some runs for the B4 reason above. Await
> each admission before starting the next.

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

**If the first test PASSES here, stop and fix the test, not the code.** That is the B4 failure
recurring: it means the subject you are observing cannot exhibit the window (wrong joiner, or a
size-keyed wait instead of a holds-a-non-host-member wait). A green here proves nothing and would
carry a vacuous test into the security-critical task.

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
import kotlinx.serialization.SerialName
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

    /**
     * The host, plus every admitted member except the origin. The relayed form of
     * `Room.broadcast`.
     */
    @Serializable
    @SerialName("all")
    data object Everyone : RelayDest

    /** Exactly [peer] — which may be the host itself. The relayed form of `Room.sendTo`. */
    @Serializable
    @SerialName("one")
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
 *
 * [RelayDest]'s subclasses carry explicit `@SerialName`s for the same reason `AdmitMessage`'s do
 * (`@SerialName("hello")`, `"paused"`, …): without one the CBOR discriminator is the
 * fully-qualified class name, so a package move or rename would silently break cross-version wire
 * compatibility with no compile error — and a 44-byte discriminator would put the envelope well
 * past the spec's "roughly 40–60 bytes" estimate, which matters while budgeting is deferred.
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

### Task 5: The relay itself — host forwarding, local delivery, and joiner routing

**Tasks 5 and 6 of revision 2 are merged here.** They were separable on paper only: every host test
observes delivery through the joiner side, and revision 2's escape hatch ("merge them if you must")
invited a worker to commit a half-wired relay and verify it by grepping the debug log for
`reason=unimplemented` — recording a green that was never green. One task, one gate.

Six properties this task exists to establish. The first five were revision-1 or revision-2 defects;
the sixth is the spec's liveness carve-out, which revision 2 silently violated.

- **The host is a recipient, not just a router (B1).** `admittedById` **never contains `selfId`** —
  `addToRoster` is called only for *other* peers (`SeamRoom.kt:1217` from `admitPeer`, `:1376` from
  `handleWelcome`). So a resolver that consults it alone drops `One(hostId)` as unresolvable and
  fans `Everyone` to co-joiners while never delivering to the host. And the joiner's relay path
  *replaces* `seam.broadcast` — which **does** reach the host today (`MuxServerLoom.readLoop` spools
  a spoke's frame into the host's `incoming`; the spec says so itself) — with a send the host would
  then forward away. Net effect of getting this wrong: a joiner's frames stop reaching the host
  entirely on any 3+ member star, and no mesh test can see it.
- **The relay arm is admit-gated (B2).** It fires **before** the existing
  `isAdmittedPeer(sender) -> routeApplicationFrame` arm, so it inherits none of that arm's gating.
  Every other application-data path in `dispatchIncoming` is admit-gated — channel frames by
  `RoomChannelSeam.incoming`'s `room.isAdmitted(swatch.sender)` filter (`RoomChannel.kt:147`), plain
  app frames by the `isAdmittedPeer` arm. This must not be the first that is not.
- **Relay traffic gets its own queue (B3).** See below.
- **Cardinality in the type (I1).** `Resolved` is sealed and its cases name *what happens*, not just
  *to whom*. `Exactly` cannot hold two peers; deleting a branch cannot compile.
- **The allow-list (C1).** Relayability is an allow-list — a channel frame, or a payload claiming
  **no** registered prefix. Revision 1 re-entered the full `dispatchIncoming`, which routes any
  `0x61` payload to `handleAdmitFrame`; since `handleWelcome` is host-authoritative only *after* a
  host exists, any admitted joiner could relay a crafted `Welcome` and capture a co-joiner's
  `hostPeerId`. A future frame family is excluded by default rather than needing to be remembered.
- **Data is relayed; liveness is not (S2).** Relayed payloads must **not** reach the per-peer
  liveness detectors. See "The second inbound flow" below.

#### Why a second writer does not violate correction C3

C3's defect was **suspending inside the single inbound collector**, not "using more than one writer".
Revision 2 satisfied it by sharing `admitFanOuts` — and thereby broke that queue's own stated growth
bound. Its KDoc (`SeamRoom.kt:1961-1972`) says unbounded growth is tolerable because *"what enqueues:
membership **transitions** … which occur on the heartbeat timescale rather than per-frame."* Relay
traffic is exactly per-frame. With `Channel.UNLIMITED` and a per-recipient budget of
`reconnectWindow + timeout` (**75 s** by default), one black-holed spoke would delay every
`Paused`/`Unpaused`/`Farewell` queued behind it by 75 s × items-ahead — the permanent roster
divergence #1781 built that queue to prevent, now reachable by one slow peer.

Revision 2's ordering argument for sharing was also weak. It claimed a `Farewell(X)` overtaking
relayed data from X is a hazard; in fact dropping a departed peer's in-flight frame at
`RoomChannelSeam.incoming`'s admitted-sender filter is the **correct** semantics, not a bug. So
sharing bought little and cost the growth bound.

A dedicated queue is therefore the right shape, and the two queues' policies differ *because their
contents differ*:

| | `admitFanOuts` | `relayForwards` (new) |
|---|---|---|
| Contents | membership transitions | application data, per-frame |
| Capacity | `UNLIMITED` | bounded (64) |
| Overflow | n/a | `DROP_OLDEST` |
| Per-recipient budget | `reconnectWindow + timeout` (~75 s) | `heartbeatConfig.interval` (~seconds) |
| Loss semantics | a dropped `Unpaused` pins a peer `Partitioned` **forever** — must not drop | already lossy-without-error by documented contract |

`DROP_OLDEST` is *semantically correct* for relay and *wrong* for membership, which is precisely why
they cannot share. The relay forward still leaves the inbound collector, so C3 holds.

**Files:**
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` — new arm in
  `dispatchIncoming` (`:1037-1079`); new members near `fanOutToOtherMembers` (`:1991`); a second
  writer launched beside `runAdmitFanOutWriter` (`:781`); `broadcast` (`:2321`); `sendTo` (`:2332`);
  `channel()` (`:2346`)
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt:120-149` —
  `RoomChannelSeam` collects the merged inbound
- Create: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt`
- Test: `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayTest.kt`

**Interfaces:**
- Consumes: `RelayEnvelope`/`RelayDest` (Task 4), `validFirstHop` (Task 2), `RoomFramePrefix` (Task 1),
  and Task 3's invariant that `hostPeerId` is set from the first accepted `Welcome`.
- Produces: nothing later tasks depend on beyond working relay behaviour.

**The send rules — keyed on the destination-set state, not on the call:**

| Condition | `sendTo(p)` | `broadcast()` |
|---|---|---|
| `role == Host` | direct | direct |
| `rosterPeers ⊆ seam.peers` | direct | direct |
| any divergence | relay via host | relay via host |

- **The host early-returns explicitly on `role == Host` (I3).** Revision 1 claimed a host always
  satisfies `rosterPeers ⊆ seam.peers`. **False** — a member inside its reconnect window stays in the
  roster while `Seam.peers` has dropped it (#1557/#1614), so a host with one partitioned member would
  have entered the relay branch and tried to relay through itself.
- **Once any divergence exists, relay everything (I2)** — including to a peer that *is* directly
  reachable. Keying `broadcast` on the roster subset but `sendTo` on the individual peer would, on a
  partial mesh, give one destination two different hop counts, and a `Quilter`'s ack
  (`Quilter.kt:717`) could then overtake the delta it acknowledges (`:557`).

#### The second inbound flow (S2)

`rawIncoming` is collected by **two** consumers with different needs: `RoomChannelSeam.incoming`
(which must see relayed channel frames) and `PerPeerSeam` (`SeamRoom.kt:2447`), which feeds each
peer's `HeartbeatPartitionDetector` — and that detector treats **any** inbound frame as proof of
liveness (`HeartbeatPartitionDetector.kt:110-127`, `observedPeer(peerId)`).

Emitting relayed payloads into `rawIncoming` stamped with the origin would therefore let A's *relayed
data* refresh B's detector for A. On a pure star that is inert (no detector exists for an unroutable
co-joiner), but rule I2 relays **everything** once the roster diverges — so on a partial-mesh,
composite or tiered topology where B *does* hold a direct edge to A, a dead A↔B link would be masked
by relayed traffic and never mature into `PeerUnresponsive`. That is the exact inverse of the spec's
carve-out ("Data is relayed; liveness is not").

So relayed payloads go to a **separate** flow that only the channel views collect.

- [ ] **Step 1: Build the shared star harness**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt` holding:

- an `InMemoryRoomFabric` star (host + N joiners), joiners admitted **sequentially** (await each
  admission before starting the next), each keeping its own raw seam handle — model on
  `LivenessRouteGateTest.star()` (`:223-254`), but **not** its `timeout = 5.seconds`;
- `sendRelay(dest, payload)` and `sendRelayForgingOrigin(origin, dest, payload)`, writing a
  `RelayEnvelope.encode(...)` straight onto a joiner's raw seam;
- `sendRelayFromUnadmitted(dest, payload)` — a client seam wired to the fabric that has **not**
  completed the admit handshake (needed by §T3);
- per-room collectors recording `RoomFrame`s so `appFramesFrom(peer): List<String>` can be asserted,
  on **every** room including the host (`host.appFramesFrom(peer)`);
- `wireFramesTo(peer): List<ByteArray>` so a test can assert whether a `0x72` frame was ever emitted;
- `appPayload(s: String) = s.encodeToByteArray()`.

Fold Task 3's `star()` in here — one harness, not two.

**Payload-byte trap:** a plain-text payload is relayable only because it claims no registered prefix.
Lowercase `a`, `c`, `e`, `k`, `r` **are** `0x61`, `0x63`, `0x65`, `0x6b`, `0x72`. Every string used
below ("hello", "legit", "plain", "nested", "for-b", "for-host", "somewhere", "honest", "forged",
"nowhere", "unadmitted", "to-host") is safe; do not add one starting with those letters.

- [ ] **Step 2: Write the failing tests**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayTest.kt`. Standard
preamble: `StandardTestDispatcher`, `private val backstop = 30.seconds` with the backstop KDoc, every
negative paired with a positive control **in the same test**.

**§T1 — the host receives a joiner's broadcast** *(the B1 regression guard; revision 2 had no such
test, which is why B1 survived its whole test set)*

```kotlin
    @Test
    fun `a joiner's broadcast reaches the host and every co-joiner`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("hello"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("hello"),
                        star.host.appFramesFrom(star.joinerAId),
                        "the HOST must receive a joiner's broadcast. Today an un-relayed " +
                            "seam.broadcast reaches it; the relay must not regress that",
                    )
                },
                {
                    assertEquals(
                        listOf("hello"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "and so must the co-joiner — the thing the relay adds",
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
```

**§T2 — the host receives a joiner's unicast** *(B1 for the `One` case)*

```kotlin
    @Test
    fun `a joiner's unicast to the host reaches the host and no one else`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.sendTo(star.hostId, appPayload("to-host"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("to-host"),
                        star.host.appFramesFrom(star.joinerAId),
                        "One(host) must resolve to LOCAL delivery — the host is not in its own " +
                            "admittedById, so a roster-only resolver drops this silently",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "and must not be widened to the co-joiner",
                    )
                },
            )
        }
```

**§T3 — an unadmitted sender cannot drive the relay** *(B2)*

```kotlin
    @Test
    fun `an unadmitted peer cannot drive a relay fan-out`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            star.sendRelayFromUnadmitted(RelayDest.Everyone, appPayload("unadmitted"))
            // Positive control from an ADMITTED peer, so total non-delivery cannot pass this.
            star.joinerA.sendRelay(RelayDest.Everyone, appPayload("honest"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("honest"),
                        star.joinerB.appFramesFrom(star.joinerAId),
                        "an admitted peer's relay must work — the control",
                    )
                },
                {
                    assertTrue(
                        star.joinerB.appFrames().none { it == "unadmitted" },
                        "a peer that never completed the admit handshake must not reach the relay " +
                            "at all: the arm fires BEFORE the isAdmittedPeer guard, so it must " +
                            "carry its own",
                    )
                },
            )
        }
```

**§T4–§T9 — carried from revision 2, unchanged in intent.** Write them exactly as revision 2 specified
(they were reviewed and not faulted), adjusting only for the merged harness:

- `a unicast reaches only its target` — target receives; an unaddressed co-joiner does not; **and**
  the host does not surface a unicast it merely forwarded. *(That last negative is only meaningful
  now that §T1/§T2 prove the host receives relayed frames at all — before them it was vacuous, B5.)*
- `a spoke cannot forge another peer's origin` — honest frame arrives, forged origin refused.
- `a relayed admit frame changes nothing on the recipient` — forged `Welcome` does not move
  `hostPeer()`, paired with a legitimate app frame that does arrive.
- `a nested relay envelope is not honoured` — paired with a plain frame.
- `an unresolvable destination is dropped and never fanned` — paired with a resolvable one.
- `a relayed frame is credited to its origin, not to the relaying host` — assert
  `RoomFrame.sender == originId` **and** `!= hostId`.

**§T10 — the joiner-side gates** *(specified here rather than left as `TODO()`; this is the
security-critical half)*

```kotlin
    @Test
    fun `a joiner ignores a relay frame that did not come from its host`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // A genuine host-relayed frame (control), and a co-joiner injecting a relay frame
            // directly onto B's link — the flat-loom / compromised-peer case.
            star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("honest"))
            star.joinerC.injectRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerBId), appPayload("forged")),
            )
            testScheduler.runCurrent()

            assertEquals(
                listOf("honest"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "only the identified host may relay; a co-joiner's injected relay frame is refused",
            )
        }

    @Test
    fun `a joiner ignores a relayed unicast addressed to someone else`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 3)

            // The far-end leak-boundary re-check: the HOST is made to misroute, and the joiner
            // refuses anyway. Paired with a One(self) that IS honoured.
            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerCId), appPayload("forged")),
            )
            star.hostRelayDirectlyTo(
                star.joinerB,
                RelayEnvelope(star.joinerAId, RelayDest.One(star.joinerBId), appPayload("legit")),
            )
            testScheduler.runCurrent()

            assertEquals(
                listOf("legit"),
                star.joinerB.appFramesFrom(star.joinerAId),
                "dest is re-checked at the far end — a misrouting host cannot widen a unicast",
            )
        }
```

**§T11 — the host does not relay through itself** *(I3)*

```kotlin
    @Test
    fun `a host sends directly even while a member is inside its reconnect window`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)
            star.partition(star.joinerBId) // in the roster, gone from seam.peers

            star.host.room.broadcast(appPayload("plain"))
            testScheduler.runCurrent()

            assertAll(
                {
                    assertEquals(
                        listOf("plain"),
                        star.joinerA.appFramesFrom(star.hostId),
                        "the healthy joiner still receives it",
                    )
                },
                {
                    assertTrue(
                        star.wireFramesTo(star.joinerAId).none { RelayEnvelope.isRelayFrame(it) },
                        "the host must take the DIRECT path — keyed on role, not on the subset " +
                            "test, which a host with a partitioned member fails",
                    )
                },
            )
        }
```

**§T12 — one destination, one path** *(I2)*

```kotlin
    @Test
    fun `a joiner relays both broadcast and unicast once its roster diverges`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            val star = relayStar(coJoiners = 2)

            star.joinerA.room.broadcast(appPayload("plain"))
            star.joinerA.room.sendTo(star.joinerBId, appPayload("for-b"))
            testScheduler.runCurrent()

            assertAll(
                { assertEquals(listOf("plain", "for-b"), star.joinerB.appFramesFrom(star.joinerAId)) },
                {
                    assertTrue(
                        star.wireFramesFrom(star.joinerAId).all { RelayEnvelope.isRelayFrame(it) },
                        "BOTH call shapes must take the relayed path — mixing hop counts to one " +
                            "destination lets a Quilter ack overtake the delta it acks",
                    )
                },
            )
        }
```

**§T13 — head-of-line** *(C3; specified, not delegated — S6)*

```kotlin
    @Test
    fun `a relay forward to a wedged spoke does not delay membership announcements`() =
        runTest(StandardTestDispatcher(), timeout = backstop) {
            // The C3 property, asserted on the thing that actually matters: a wedged relay
            // recipient must not stall the MEMBERSHIP queue. Sharing one writer would.
            val star = relayStar(coJoiners = 3, wedge = setOf("joiner-b"))

            // Fill the relay queue toward a wedged recipient.
            repeat(RELAY_FLOOD) { star.joinerA.sendRelay(RelayDest.One(star.joinerBId), appPayload("plain")) }
            // Now raise a membership transition and require it to land promptly.
            star.partition(star.joinerCId)
            testScheduler.advanceTimeBy(membershipBudget)
            testScheduler.runCurrent()

            assertAll(
                {
                    assertTrue(
                        star.joinerA.sawPartitioned(star.joinerCId),
                        "a Paused for C must reach A while relay traffic to a wedged B is " +
                            "backed up — this is why relay does not share admitFanOuts",
                    )
                },
                {
                    // Positive control: the wedge is real. Without this the test could pass
                    // because nothing was ever queued.
                    assertTrue(
                        star.joinerB.appFramesFrom(star.joinerAId).isEmpty(),
                        "sanity: B really is wedged",
                    )
                },
            )
        }
```

`membershipBudget` must be **well under** `reconnectWindow + timeout`; the whole point is that the
membership announcement is not stuck behind a relay budget. `RELAY_FLOOD` should exceed the relay
queue capacity so `DROP_OLDEST` is exercised too.

- [ ] **Step 3: Run the tests and watch them fail**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelayTest*"
```

Expected: every test FAILS — no relay exists, so every positive control is empty. **That is what the
positive controls are for**: they fail first. A test that passes at this step is vacuous — fix the
test before writing any implementation.

- [ ] **Step 4: Add the relay arm to `dispatchIncoming`**

In `SeamRoom.kt`, insert a new arm into the `when` at `:1037` — **before** the
`isAdmittedPeer(sender) -> routeApplicationFrame(...)` arm at `:1077`, after the lobby arm:

```kotlin
            RelayEnvelope.isRelayFrame(bytes) -> handleRelayFrame(sender, bytes)
```

That ordering is deliberate and is a known hazard: this arm fires **before** the existing admit
guard, which is the "an earlier guard un-pins an older test" shape this repo has hit four times.
It is why `handleRelayFrame` carries its own admission check (B2) and why Task 8 mutates both in
combination.

- [ ] **Step 5: The second inbound flow, so relayed data does not feed liveness (S2)**

Add beside `rawIncoming` (`SeamRoom.kt:557`):

```kotlin
    /**
     * Relayed payloads, delivered to **channel views only** — deliberately *not* [rawIncoming].
     *
     * [rawIncoming] feeds two consumers with different needs: [RoomChannelSeam.incoming], which must
     * see relayed channel frames, and [PerPeerSeam] (`:2447`), which feeds each peer's
     * [HeartbeatPartitionDetector] — and that detector treats **any** inbound frame as proof of
     * liveness. Emitting a relayed payload into [rawIncoming] stamped with its origin would let A's
     * relayed *data* refresh B's detector for A.
     *
     * On a pure star that is inert (a joiner has no detector for an unroutable co-joiner), but the
     * send rule relays **everything** once the roster diverges — so on a partial-mesh, composite or
     * tiered topology where B does hold a direct edge to A, a dead A↔B link would be masked by
     * relayed traffic and never mature into `PeerUnresponsive`. That is the exact inverse of the
     * documented carve-out: **data is relayed; liveness is not** (#1592/#1576).
     */
    private val relayedIncoming = MutableSharedFlow<Swatch>(extraBufferCapacity = 256)
```

Merge the two for channel views. In `channel()` (`:2346`), pass the merged flow:

```kotlin
                RoomChannelSeam(room = this, subId = subId, sharedRaw = merge(rawIncoming, relayedIncoming))
```

and widen `RoomChannelSeam`'s parameter in `RoomChannel.kt:123` from `SharedFlow<Swatch>` to
`Flow<Swatch>` (it only calls `.filter { }.map { }` on it). Update that constructor's KDoc: the
upstream is now a merge of the direct and relayed inbound streams, and `replay = 0` semantics are
unchanged.

- [ ] **Step 6: Implement the host half**

Add to `SeamRoom`, next to `fanOutToOtherMembers` (`:1991`):

```kotlin
    /**
     * What a relayed frame resolves to on this host — **the outcome, carried in the type**.
     *
     * The cases name *what happens*, not just *to whom*, because the host is a **recipient as well
     * as a router** and a resolver that only answers "which peers" cannot say so. [admittedById]
     * never contains [selfId] — `addToRoster` is called only for other peers (`:1217`, `:1376`) —
     * so a `Set<PeerId>` resolver silently drops `One(host)` as unresolvable and fans `Everyone`
     * past the host. That would stop a joiner's frames reaching the host at all, and no mesh test
     * could see it.
     *
     * Cardinality stays in the type (I1): [Exactly] *cannot* hold two peers, and removing a branch
     * from the `when` that consumes this *cannot* compile. For `:kuilt-deal`'s per-recipient card
     * secrets the security property **is** cardinality, so it belongs here.
     */
    private sealed interface Resolved {
        /** Unknown, departed, or self-addressed-by-the-origin destination. Drop it. */
        data object None : Resolved

        /** Addressed to this host alone — deliver locally, forward to nobody. */
        data object SelfOnly : Resolved

        /** Forward to exactly one other member; not for us. */
        data class Exactly(val peer: PeerId) : Resolved

        /** Deliver locally **and** forward to [others] (which may legitimately be empty). */
        data class SelfAndEvery(val others: Set<PeerId>) : Resolved
    }

    /**
     * Whether a relayed payload may be honoured — an **allow-list**, deliberately not a deny-list.
     *
     * Honoured only if it is an explicit channel frame, or claims **no** registered prefix at all
     * (a plain application frame). That excludes admit, lobby, heartbeat and a nested
     * [RelayEnvelope] in one predicate — and excludes a *future* frame family by default rather
     * than requiring someone to remember it.
     *
     * **Why not "re-dispatch with a synthesized sender".** That re-enters [dispatchIncoming], which
     * routes any `0x61` payload to [handleAdmitFrame]. [handleWelcome] is host-authoritative only
     * *after* a host exists, so any admitted joiner could relay a crafted `Welcome` naming itself
     * and capture a co-joiner's `hostPeerId` — then drive every host-authoritative gate on the
     * victim. #1180 hardened that on a flat loom; the four star fabrics were protected by
     * *topology*, and a relay removes that protection on all of them.
     *
     * A relayed admit frame has no legitimate sender: the admit protocol is by construction
     * host↔joiner over the direct edge.
     */
    private fun isRelayable(payload: ByteArray): Boolean =
        RoomChannel.isChannelFrame(payload) || RoomFramePrefix.entries.none { it.matches(payload) }

    /** Host-side: forward and/or deliver one relayed frame, or drop it. */
    private fun handleRelayFrame(sender: PeerId, bytes: ByteArray) {
        if (_role.value != SessionRole.Host) {
            handleRelayedDelivery(sender, bytes)
            return
        }
        // FIRST gate. This arm fires before the `isAdmittedPeer(sender)` arm it precedes, so it
        // inherits none of that arm's gating and must carry its own: every other application-data
        // path in dispatchIncoming is admit-gated, and an ungated relay lets a peer that never
        // completed the handshake drive an N-recipient fan-out per frame.
        if (!isAdmittedPeer(sender)) {
            logger.debug {
                "room.relay.drop self=${selfId.value} from=${sender.value} reason=sender-not-admitted"
            }
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
        // Forwards carry the ORIGINAL bytes unchanged — `dest` is meaningful on this hop only, so
        // there is no per-recipient re-wrapping and `Everyone` stays `Everyone` on the wire.
        when (val resolved = resolveRecipients(envelope)) {
            Resolved.None ->
                logger.debug {
                    "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                        "dest=${envelope.dest} reason=unresolvable"
                }
            Resolved.SelfOnly -> deliverRelayedPayload(envelope)
            is Resolved.Exactly -> enqueueRelayForward(listOf(resolved.peer), bytes)
            is Resolved.SelfAndEvery -> {
                deliverRelayedPayload(envelope)
                if (resolved.others.isNotEmpty()) enqueueRelayForward(resolved.others.toList(), bytes)
            }
        }
    }

    /**
     * Resolve a relayed frame's destination against the current roster **and this host itself**.
     *
     * A destination the origin addressed to itself, or one naming a peer this room does not hold,
     * resolves to [Resolved.None] and is dropped — never widened into a fan-out, which is how a
     * unicast would leak.
     */
    private fun resolveRecipients(envelope: RelayEnvelope): Resolved = lock.withLock {
        if (closed) return@withLock Resolved.None
        when (val dest = envelope.dest) {
            RelayDest.Everyone ->
                // `others` may legitimately be empty (a 2-peer room): the host is still a
                // recipient, so this is SelfAndEvery(emptySet()), NOT None.
                Resolved.SelfAndEvery(admittedById.keys.filterTo(mutableSetOf()) { it != envelope.origin })
            is RelayDest.One -> when {
                dest.peer == envelope.origin -> Resolved.None
                dest.peer == selfId -> Resolved.SelfOnly
                admittedById.containsKey(dest.peer) -> Resolved.Exactly(dest.peer)
                else -> Resolved.None
            }
        }
    }

    /**
     * Deliver a relayed payload to this member's own consumers, stamped with the **origin**.
     *
     * Shared by the host (which is a recipient of anything addressed to it) and the joiner (after
     * its own gates in [handleRelayedDelivery]). Callers must have already applied [isRelayable].
     *
     * The two surfaces mirror [dispatchIncoming]'s own arms for these payload kinds — channel
     * frames to the channel views, plain application frames to [routeApplicationFrame] — but this
     * is a **narrow, explicit** re-implementation of exactly those two, deliberately *not* a call
     * back into [dispatchIncoming], which would restore the admit-frame path the allow-list exists
     * to remove.
     */
    private fun deliverRelayedPayload(envelope: RelayEnvelope) {
        if (RoomChannel.isChannelFrame(envelope.payload)) {
            val accepted = relayedIncoming.tryEmit(Swatch(envelope.payload, sender = envelope.origin))
            if (!accepted) {
                // Relayed delivery is the one place weaker than direct delivery: the direct path
                // uses a suspending `emit` inside the collector (`:1027`), which this cannot.
                // Absence has to be diagnosable off-device (#1781).
                logger.debug {
                    "room.relay.drop self=${selfId.value} origin=${envelope.origin.value} " +
                        "reason=inbound-buffer-full"
                }
            }
        } else {
            routeApplicationFrame(envelope.origin, envelope.payload)
        }
    }
```

- [ ] **Step 7: The dedicated relay queue and its writer**

```kotlin
    /** One queued relay forward: the original envelope bytes plus its recipient snapshot. */
    private class RelayForward(val recipients: List<PeerId>, val bytes: ByteArray)

    /**
     * Queued relay forwards, drained by [runRelayForwardWriter].
     *
     * **Separate from [admitFanOuts], deliberately.** That queue's growth analysis rests on *what
     * enqueues*: membership **transitions**, "on the heartbeat timescale rather than per-frame".
     * Relay traffic is exactly per-frame, so putting it there would invalidate the bound — with
     * `Channel.UNLIMITED` and a `reconnectWindow + timeout` per-recipient budget, one black-holed
     * spoke would delay every `Paused`/`Unpaused`/`Farewell` behind it, which is the permanent
     * roster divergence #1781 built that queue to prevent.
     *
     * The policies differ because the contents do. A dropped `Unpaused` pins a recovered member
     * `Partitioned` in a remote roster **forever**, so that queue must never drop; a dropped relay
     * frame is loss the `Room` contract already documents (lossy-without-error on a star) and that
     * `Quilter` anti-entropy heals. So this one is **bounded** with [BufferOverflow.DROP_OLDEST] —
     * back-pressure is unavailable here for the same reason it is there (enqueue happens from a
     * non-suspending frame handler), and dropping the *oldest* relayed frame under sustained
     * overload is strictly better than growing without bound.
     */
    private val relayForwards = Channel<RelayForward>(
        capacity = RELAY_FORWARD_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Per-recipient deadline for one relay forward — [HeartbeatConfig.interval], **not**
     * `admitFanOuts`'s `reconnectWindow + timeout`.
     *
     * That budget is deliberately loose because an announcement stays meaningful for the whole span
     * of the hold it describes. A relayed data frame does not: it is superseded by the next one,
     * and `Quilter` anti-entropy heals the gap. A budget on the order of one heartbeat interval
     * keeps a wedged spoke from consuming the writer while staying far above what a healthy link
     * needs.
     */
    private val relaySendBudget: Duration get() = heartbeatConfig.interval

    /** Drains [relayForwards]. Guard discipline is identical to [runAdmitFanOutWriter] — see its KDoc. */
    private suspend fun runRelayForwardWriter() {
        for (forward in relayForwards) {
            for (recipient in forward.recipients) {
                try {
                    val accepted = withTimeoutOrNull(relaySendBudget) {
                        seam.sendTo(recipient, forward.bytes)
                    } != null
                    if (!accepted) {
                        logger.debug {
                            "room.relay.drop self=${selfId.value} to=${recipient.value} " +
                                "reason=send-budget-exceeded budget=$relaySendBudget"
                        }
                    }
                } catch (failure: Throwable) {
                    // Genuinely OUR cancellation ends the loop; anything else — including a
                    // CancellationException a consumer's `sendTo` minted itself — is that
                    // recipient's failure and must not kill the relay writer.
                    currentCoroutineContext().ensureActive()
                    logger.debug {
                        "room.relay.drop self=${selfId.value} to=${recipient.value} " +
                            "cause=${failure::class.simpleName}: ${failure.message}"
                    }
                }
            }
        }
    }

    /** Enqueue a relay forward. Never suspends; drops the oldest under sustained overload. */
    private fun enqueueRelayForward(recipients: List<PeerId>, bytes: ByteArray) {
        if (recipients.isEmpty()) return
        val queued = relayForwards.trySend(RelayForward(recipients, bytes)).isSuccess
        if (!queued) {
            logger.debug {
                "room.relay.drop self=${selfId.value} reason=room-terminal " +
                    "recipients=${recipients.size}"
            }
        }
    }
```

Launch the writer beside the existing one (`SeamRoom.kt:781`), and close the queue in `leave()`
beside `admitFanOuts.close()` (`:2409`):

```kotlin
        scope.launch { runRelayForwardWriter() }
```

`RELAY_FORWARD_CAPACITY` goes in the file's companion with a one-line rationale (64: several
`Quilter` deltas in flight per recipient without letting a wedged link accumulate unboundedly).

Add `import us.tractat.kuilt.core.validFirstHop` and the `BufferOverflow` / `merge` imports.

- [ ] **Step 8: Implement joiner-side receipt**

```kotlin
    /**
     * Joiner-side: accept a frame the host relayed on a co-member's behalf.
     *
     * Four gates, each of which must independently hold:
     *
     * 1. **The sender is our identified host.** A relay frame from anyone else is a co-joiner
     *    injecting directly, which on a flat loom is reachable. Depends on `hostPeerId` being set
     *    from the *first* Welcome — see [handleWelcome].
     * 2. **`dest` names us.** The host already resolved this, but the leak boundary is re-checked
     *    at the far end rather than trusting the host's routing — cheap, and it means a misrouting
     *    host cannot silently widen a unicast.
     * 3. **The payload is relayable.** The same allow-list the host applied, applied again: a host
     *    is not trusted to have applied it.
     * 4. **The origin is an admitted member.** Otherwise the frame would be credited to a peer
     *    outside the roster, which the channel views' own `isAdmitted(sender)` filter would drop
     *    anyway — failing here keeps the reason loggable.
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
        deliverRelayedPayload(envelope)
    }
```

- [ ] **Step 9: Implement joiner-side send routing**

Replace `broadcast` (`:2321`) and `sendTo` (`:2332`):

```kotlin
    /**
     * Broadcast [bytes] to all admitted members.
     *
     * On a star fabric a spoke's frame reaches only the host, so once this member's roster diverges
     * from what the transport can address, the frame is wrapped and **relayed via the host**.
     * `RoomChannelSeam` therefore needs no change: it already delegates here, so
     * `peers = room.rosterPeers` becomes honest for free.
     *
     * Silent no-op when the room is terminal (after [MembershipEvent.HostLost] or [leave]).
     *
     * **Lossy without error on a star.** An unresolvable destination is dropped with a debug log and
     * a torn or wedged recipient's send is dropped by [runRelayForwardWriter]. On a mesh this call
     * surfaces failures; relayed, it does not.
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
     *   itself. An earlier revision was saved from that only by `hostPeerId` being incidentally
     *   `null` on a host — which a plausible tidy-up would have broken.
     * - **The roster is a subset of what the transport can address**, i.e. a full mesh.
     *
     * Otherwise **everything** relays, including frames to a peer that *is* directly reachable.
     * Keying `broadcast` on the roster subset but `sendTo` on the individual peer would, on a
     * partial mesh, give one destination two different hop counts — and a `Quilter`'s ack
     * (`Quilter.kt:717`) could then overtake the delta it acknowledges (`:557`).
     *
     * **A null [hostPeerId] here throws.** It is an invariant violation on a joiner, not a
     * degrade-quietly case: [handleWelcome] sets `hostPeerId` from the first accepted `Welcome`,
     * which necessarily precedes any co-member entering the roster, so a diverged roster with no
     * identified host means that invariant has already been broken upstream. Falling back to a
     * direct send would re-create #1994's own symptom — silent non-delivery — and hide the cause.
     */
    private fun relayHostOrNull(): PeerId? {
        if (_role.value == SessionRole.Host) return null
        val (roster, host) = lock.withLock { _rosterPeers.value to hostPeerId }
        if (roster.all { it in seam.peers.value }) return null
        return requireNotNull(host) {
            "relay required but no host identified — roster=${roster.map { it.value }} " +
                "seamPeers=${seam.peers.value.map { it.value }}; hostPeerId must be set by the " +
                "first accepted Welcome (see handleWelcome)"
        }
    }
```

- [ ] **Step 10: Run the suite — it must pass**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 90 ./gradlew \
  :kuilt-session:jvmTest --tests "*StarRelayTest*"
```

- [ ] **Step 11: Full build — this is a runtime-behavior change**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew build --rerun-tasks
```

A `:kuilt-session`-scoped build is a **false green**: it skips the `:examples` and `:kuilt-cluster`
E2E tests, and `ClusterClient` rides `Room.channel("raft")` — the exact surface this task changes.
`RoomChannelSeam`'s constructor signature also changed, so the whole module graph must compile.

- [ ] **Step 12: Commit**

```bash
git add kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt \
        kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayTest.kt \
        kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/StarRelayHarness.kt
git commit -m "feat(session): relay frames between the spokes of a star

The host forwards a spoke's frame to its co-spokes AND delivers to itself:
admittedById never contains selfId, so a roster-only resolver would drop
One(host) and fan Everyone past the host — silently ending a joiner's ability
to reach the host at all. Resolved's cases name the outcome, not just the
recipients, so local delivery cannot be forgotten.

The arm fires before the isAdmittedPeer arm, so it carries its own admission
gate: every other application-data path in dispatchIncoming is admit-gated and
this must not be the first that is not.

Relay forwards get their own bounded DROP_OLDEST queue rather than sharing
admitFanOuts, whose growth bound rests on enqueueing membership TRANSITIONS
rather than per-frame data. A dropped Unpaused pins a peer Partitioned forever;
a dropped relay frame is loss the Room contract already documents.

Relayed payloads reach channel views only, never the per-peer liveness
detectors — data is relayed, liveness is not.

A joiner re-checks the host identity, that dest names it, the relayability
allow-list, and roster membership of the origin, then delivers stamped with the
ORIGIN. A diverged roster with no identified host throws rather than falling
back to a send known not to deliver.

Part of #1994.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
---

## ⛔ REVIEW GATE — stop here. Do not start Task 6 until this passes.

**Why this gate exists, stated so it is not waved through.** Two independent review rounds have each
found a defect class the previous round missed, and **both landed in the same place**: the interaction
between the relay and `SeamRoom`'s existing invariants. Revision 1 shipped a co-joiner `hostPeerId`
takeover past a normal review. Revision 2 — written specifically to fix that — shipped a change that
would have stopped a joiner's frames reaching the host at all, and did so *while passing its own test
set*, because the test set had no positive control asserting the host receives anything.

That is not a run of bad luck; it is a property of this task. Task 5 touches the one method every
inbound room frame passes through (`dispatchIncoming`), reorders a guard chain, adds a second writer,
adds a second inbound flow, and changes `RoomChannelSeam`'s constructor. The blast radius is the whole
session layer, and the failure mode is *silent non-delivery* — which is #1994's own symptom, so a
regression looks exactly like the bug not being fixed yet.

**Do not fold this into the end-of-branch review.** By then Tasks 6–9 are built on top, and a design
fault found there costs the rebuild of everything above it.

- [ ] **Gate 1 — the full build actually ran, and was not cached**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.5-tem timeout 600 ./gradlew build --rerun-tasks
```

Confirm the test-compile tasks show `EXECUTED`, not `FROM-CACHE`. A `:kuilt-session`-scoped build is a
false green — `ClusterClient` rides `Room.channel("raft")`, which this task changes underneath it.

- [ ] **Gate 2 — the anti-vacuity check, early**

Run Task 7's `StarQuilterConvergenceTest` **now**, ahead of its own task, and run it both ways: as-is
(must PASS) and with `relayHostOrNull()` forced to `return null` (must FAIL). If it passes both ways
the relay is not doing the work and everything downstream is measuring nothing. Record both outputs.

- [ ] **Gate 3 — the mutation set, early, on the guards Task 5 introduced**

Run **M1–M5 and M6–M8** from Task 8 now rather than at the end. These are the guards whose ordering is
the known hazard (the relay arm fires *before* `isAdmittedPeer`), and a surviving mutant here is a
design fault, not a missing test — it is much cheaper to learn that before Tasks 6–9 exist. Abort on a
non-zero build exit **before** parsing any results XML.

- [ ] **Gate 4 — an independent adversarial review of the diff**

Dispatch a `code-reviewer` on **opus** over `git diff origin/main...HEAD` for Task 5's commit, briefed
with:

- the spec (`docs/superpowers/specs/2026-08-03-star-relay-design.md`, revision 2) and this plan's
  revision-3 defect table, so it knows what has already been gotten wrong twice;
- the standing question: **"which existing `SeamRoom` invariant does this change break?"** — that is
  where both prior defects lived. Name the invariants explicitly: `admittedById` excludes `selfId`
  while `_rosterPeers` includes it; `rawIncoming` feeds both channel views and liveness detectors;
  `admitFanOuts`'s growth bound assumes membership transitions only; guard order in
  `dispatchIncoming`; `hostPeerId`'s consumers (`handleFarewell`, `handlePaused`, `handleResumeAck`,
  `HostLost`, `runDetectorRouteWatcher`);
- the instruction to check for **silent non-delivery** specifically — a path where a frame is dropped
  with only a debug log and no test would notice.

**If Gate 4 returns a blocking finding, fix it and re-run Gates 1–3 before proceeding.** If it returns
nothing blocking, say so plainly in the PR and continue.

Iain asked for this gate explicitly (2026-08-03). It is not optional and it is not a formality.

---

### Task 6: Protocol version 2, and close the `isSupported(null)` carve-out

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

### Task 7: The done criteria — a Quilter converges between two spokes

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
            val onA = setReplicator(star.joinerA.room, backgroundScope)
            val onB = setReplicator(star.joinerB.room, backgroundScope)
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

> **Worker note:** `relayStar()` and its `joinerA.room` / `joinerB.room` accessors come from Task 5's
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

Temporarily change Task 5's `relayHostOrNull()` to `return null` unconditionally (always direct) and
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

### Task 8: Mutation-verify the guard set

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

**M1–M8 as written never reach `resolveRecipients`, and that is a blind spot, not a gap in
coverage.** Every mutation above sits in `handleRelayFrame`'s gate chain; the resolver decides *who
receives a frame that passed every gate*, which is where a widening bug leaks bytes rather than
admitting them. The review gate extended the set there and found two survivors and one kill by a
misleading assertion. Run these too:

| # | Mutation | Expected to redden |
|---|---|---|
| M9 | `is RelayDest.One`: `admittedById.containsKey(dest.peer) -> Exactly` → `Exactly(dest.peer)` unconditionally | `an unresolvable unicast dest is dropped, never widened` — but **only** via its wire assertion. Roster-membership is the boundary, so the refusal must be asserted where the decision is: no forward leaves the host. A test that asserts only "no co-spoke received it" holds equally if the host faithfully forwarded to a non-member and the send failed on the wire — a different program, in which an admitted spoke can push bytes at any peer the host's transport can address but its roster does not hold. |
| M10 | `is RelayDest.One`: `dest.peer == envelope.origin -> Resolved.None` → arm removed | `a relayed frame is never echoed back to its origin`. Without the arm a self-addressed unicast falls through to the roster arm, which matches — an origin *is* admitted — and the host forwards the frame straight back. `Room.incoming` then surfaces a frame credited to `selfId`, which a `Quilter` reads as a delta from a replica it *is*. |
| M11 | `RelayDest.Everyone`: drop the `it != envelope.origin` exclusion | same test. Before it existed this was caught only by an unrelated §T12 wire assertion whose message speaks about the host's *direct* path — a red that names the wrong cause is barely better than a green. |

M9–M11 are the "an earlier guard un-pins an older test" shape read one layer further in: the gates
absorb every *inadmissible* frame, so the resolver's own guards are only ever exercised by frames
that got through, and a test written against the gates never touches them.

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

### Task 9: Documentation

**Files:**
- Modify: `docs/fabric-peer-routing.md` — four fabric rows, plus the liveness carve-out
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Seam.kt` — `peers` KDoc
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/Room.kt` —
  `broadcast`/`sendTo` KDoc, including the reserved leading bytes
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/SeamRoom.kt` —
  `startDetector`'s stale `TieredSeam` reason
- Modify: `kuilt-session/src/commonMain/kotlin/us/tractat/kuilt/session/RoomChannel.kt` —
  `CHANNEL_PREFIX`'s promise of "namespace-collision guarantees"
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

The KDoc must **not** promise "reaches that member on every fabric" (spec correction **I5**).

**The text this step originally prescribed is stale — do not paste it.** It said "`sendTo` surfaces
a failure as `PeerNotConnected` on a mesh; relayed, it does not", which
[`b242fb03`](https://github.com/tractat-us/kuilt/commit/b242fb03) resolved the other way while
implementing Task 5. What shipped, and what the KDoc must match:

- **`broadcast` is lossy without error.** When the relay hop throws `PeerNotConnected` it catches and
  falls back to `seam.broadcast(bytes)`; it never throws. A `Quilter`'s timer-driven broadcast that
  threw here would kill the coroutine driving anti-entropy — the mechanism that heals the gap.
- **`sendTo` throws when relayed and the host hop fails**, and the exception names the **host**, not
  the addressed peer, because the host is the hop that failed. An addressed send that silently
  vanished would re-create #1994's own symptom at the send side.
- **"Lossy without error" is true of the *second* hop only** — host → final recipient is a
  best-effort fan-out, and nothing is reported back across it either way.

Write the KDoc from the code, not from this plan. `SeamRoom.broadcast` / `SeamRoom.sendTo` carry the
full argument; `Room`'s interface KDoc states the contract callers program against.

- [ ] **Step 3b: Document the reserved byte space where a consumer actually reads it**

`RoomFramePrefix` reserves ASCII `a c e k r` (`0x61 0x63 0x65 0x6b 0x72`) as leading payload bytes,
and today that is documented **only** in the enum's own KDoc, which no consumer opens.
`RoomChannel`'s class doc set the precedent — "Applications **must not** emit raw payloads starting
with `0x63`" — for one byte; extend it to all five on `Room.broadcast` / `Room.sendTo`, where a
caller composing a payload is actually looking.

State the subtlety accurately rather than over-promising a blanket ban. `matches` is a single-byte
test but each family's real classifier (`classifies`) differs, and the dispatcher runs the
classifiers: `0x61`/`0x65`/`0x72` swallow **every** payload leading with them, `0x63` only payloads
of 3 bytes or more, and `0x6b` only the literal `"kuilt.heartbeat.ping"`/`"…pong"` prefixes — so a
bare `"keepalive"` is delivered. Say which rows are conditional and say not to build on them: the
classifiers may move and the failure mode is a frame that vanishes with no error.

While there, soften `RoomChannel.CHANNEL_PREFIX`'s KDoc, which still ends "See the class-level
documentation for **namespace-collision guarantees**". The class doc now says the registry asserts
distinctness and explicitly **cannot** assert safety — a pointer promising a guarantee at the far
end of it is the same stale-claim shape as Step 4.

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
- [ ] Post the mutation table (Task 8) and the anti-vacuity output (Task 7 Step 3) in the PR body.

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
