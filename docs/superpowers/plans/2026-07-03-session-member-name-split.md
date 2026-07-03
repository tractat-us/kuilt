# Session-name / Member-name Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop peers from taking their roster (member) name from the discovered session name, and rename the conflated field so the two concepts are distinct in the API.

**Architecture:** Three tasks, sequential (each depends on the prior). Task 1 is a pure, atomic, cross-repo rename (`displayName` → `sessionName` on the two contract types `Pattern` and `Tag`) with zero behavior change. Task 2 adds a per-call `memberName` at the membership layer and rewires `SeamRoom` to use it instead of the session name, with a `PeerId`-derived default. Task 3 pins the fix in the fabric-agnostic TCK.

**Tech Stack:** Kotlin Multiplatform, Gradle, JDK 21 (`source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem`). Design spec: `docs/superpowers/specs/2026-07-03-session-member-name-split-design.md`.

## Global Constraints

- **`explicitApi()` enforced** — every public declaration needs an explicit visibility modifier.
- **Full build before declaring a task done:** `./gradlew build detektAll` (JVM-only `jvmTest` hides Android/native variants). Use `--rerun-tasks` if any test-compile task shows `FROM-CACHE`.
- **Use `detektAll`, not bare `detekt`** (bare is NO-SOURCE, a false green).
- **No production dispatchers in test sources** (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) — detekt `ForbiddenImport` fails the build.
- Test methods: no `test` prefix; multi-assert tests use `assertAll()`.
- This branch (`feat/1177-session-member-name-split`) is **stacked on #1190** — `Pattern.roomKey` is already `String? = null` here.

---

### Task 1: Rename `displayName` → `sessionName` on `Pattern` and `Tag`

Pure mechanical rename of the two contract properties (and their implementations' backing
data-class ctor params) plus every read. **Zero behavior change** — `SeamRoom` keeps reading
the session name as the member name (still the latent bug; Task 2 fixes it). The value is a
green build with the conflated name gone.

**Files (rename the property + fix all compiler-flagged reads):**
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Tag.kt` — `Tag.displayName` → `sessionName` (+ KDoc: "Human-readable **session** name as broadcast by the advertising peer.")
- Modify: `kuilt-core/src/commonMain/kotlin/us/tractat/kuilt/core/Pattern.kt` — `Pattern.displayName` → `sessionName`.
- Modify: `kuilt-core/.../InMemoryLoom.kt` — `InMemoryTag`'s `displayName` property/param → `sessionName`.
- Modify: `kuilt-websocket/.../WebSocketAdvertisement.kt` — `displayName` → `sessionName`.
- Modify: `kuilt-mdns/.../MDNSAdvertisement.kt` — `displayName` → `sessionName`; and `MDNSServiceDiscoverer.toAdvertisement` construction site (`displayName = info.name` → `sessionName = info.name`).
- Modify: `kuilt-multipeer/.../MultipeerAdvertisement.kt` — `displayName` → `sessionName`.
- Modify: `kuilt-nearby/.../NearbyTag.kt` — `displayName` → `sessionName`.
- Modify: any conformance-test Tag impls the compiler flags (`MDNSConformanceTest`, `WebSocketConformanceTest`, `MultipeerConformanceTest`, `WebRTC*Test`, etc.).
- Modify: the #1189 read sites `MDNSMultiAcceptHost.kt` (`displayName = pattern.displayName`) and `MDNSPeerLinkFactory.kt` (`registerMDNS(rendezvous.pattern.displayName)`) → `pattern.sessionName`.
- Modify: `SeamRoomFactory.host`/`join` (`SeamRoom.kt:96,114`) — the reads `pattern.displayName` / `tag.displayName` → `.sessionName` (Task 2 replaces these entirely; for now just rename to keep it green).

**MUST NOT rename** (these are *not* the session name):
- `MemberIdentity.displayName` (`Member.kt`) — this is the member name; the whole point.
- `AdmitMessage.Hello.displayName` / `Welcome.displayName` — wire fields carrying the member name.
- `SeamRoom`'s private `displayName` field (`SeamRoom.kt:218`) — Task 2 renames it to `memberName`.
- Non-Tag helper params `MDNSServiceAdvertiser(displayName = …)` — a local param on the advertiser; leave its name, it is fed by `pattern.sessionName`.

**Interfaces:**
- Produces (for Task 2): `Pattern.sessionName: String`, `Tag.sessionName: String`.

- [ ] **Step 1: Rename the two contract properties.** In `Tag.kt` and `Pattern.kt`, rename `displayName` → `sessionName` (update KDoc on both).

- [ ] **Step 2: Compile-fix loop.** Run `source ~/.sdkman/bin/sdkman-init.sh && sdk use java 21.0.5-tem && ./gradlew build` repeatedly; for each unresolved-reference error, rename the read to `.sessionName` (or the data-class ctor param, matching the property) per the file list above. Consult the **MUST NOT rename** list before touching any `displayName` — only Tag/Pattern session-name occurrences change.
  Expected while iterating: `unresolved reference: displayName` at Tag/Pattern read sites.

- [ ] **Step 3: Full green build.**

Run: `./gradlew build detektAll`
Expected: `BUILD SUCCESSFUL`. All existing tests pass unchanged (rename is behavior-preserving).

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "refactor(core): rename Pattern/Tag.displayName -> sessionName (#1177, no behavior change)"
```

---

### Task 2: Add per-call `memberName`; wire member identity to it (the fix)

Add `memberName: String? = null` to the membership factory entry points and `SeamRoom`; mint
the peer's own `MemberIdentity.displayName` from `memberName ?: selfId.value` instead of the
session name. This changes behavior: null-`memberName` call sites now get a `PeerId`-derived
roster name (not the counterpart's session name), so roster-asserting tests that relied on the
old `InMemoryTag("bob")`-doubles-as-member-name convention break loudly and are fixed here by
passing `memberName`.

**Files:**
- Modify: `kuilt-session/.../Room.kt` — `RoomFactory.host`/`join` signatures.
- Modify: `kuilt-session/.../SeamRoom.kt` — `SeamRoomFactory.host`/`join`; `SeamRoom` ctor field; `sendHello` (:944) and `admitPeer` host self-intro (:935).
- Modify: `kuilt-session-test/.../FakeRoomFactory.kt` — mirror the signature.
- Test: `kuilt-session/src/commonTest/.../SeamRoomMemberNameTest.kt` (new).
- Modify: any `:kuilt-session` / `examples` / conformance tests whose roster-name assertions now fail.

**Interfaces:**
- Consumes: `Pattern.sessionName`, `Tag.sessionName` (Task 1).
- Produces (for Task 3):
  - `RoomFactory.host(pattern: Pattern, memberName: String? = null): Room`
  - `RoomFactory.join(tag: Tag, memberName: String? = null): Room`

- [ ] **Step 1: Write the failing test.**

Create `kuilt-session/src/commonTest/kotlin/us/tractat/kuilt/session/SeamRoomMemberNameTest.kt`.
Uses the real roster API — `Room.roster: StateFlow<Set<Member>>`, awaited via
`roster.first { it.size == N }`, name read as `.identity.displayName` (see
`RoomBoundAdmissionTest` for the pattern). Confirm the `SeamRoomFactory(loom, scope)`
ctor shape against `RoomBoundAdmissionTest`'s `factory(loom, backgroundScope)` helper.

```kotlin
package us.tractat.kuilt.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import us.tractat.kuilt.core.InMemoryLoom
import us.tractat.kuilt.core.InMemoryTag
import us.tractat.kuilt.core.Pattern
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A joiner's roster name comes from its own [memberName], NOT the discovered
 * session name — the #1177 fix.
 */
class SeamRoomMemberNameTest {

    @Test
    fun `joiner appears under its own memberName, not the session name`() = runTest {
        val loom = InMemoryLoom()
        val host = SeamRoomFactory(loom, backgroundScope)
            .host(Pattern(sessionName = "Alice's game"), memberName = "Alice")
        // Joiner discovers the session ("Alice's game") but names itself "Bob".
        val joiner = SeamRoomFactory(loom, backgroundScope)
            .join(InMemoryTag("Alice's game"), memberName = "Bob")

        val hostRoster = host.roster.first { it.size == 1 }      // the admitted joiner
        val joinerRoster = joiner.roster.first { it.isNotEmpty() } // the host
        assertAll(
            { assertEquals("Bob", hostRoster.single().identity.displayName) },
            { assertEquals("Alice", joinerRoster.single().identity.displayName) },
        )
    }
}
```

- [ ] **Step 2: Run it — verify it fails to compile** (memberName param missing).

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamRoomMemberNameTest"`
Expected: compile error — `no value passed for parameter` / unresolved `memberName`.

- [ ] **Step 3: Add the param to the interface and factories.**

In `Room.kt`:
```kotlin
public interface RoomFactory {
    /** Host a new room. [memberName] is this peer's roster name (defaults to its peer id). */
    public suspend fun host(pattern: Pattern, memberName: String? = null): Room
    /** Join an existing room. [memberName] is this peer's roster name (defaults to its peer id). */
    public suspend fun join(tag: Tag, memberName: String? = null): Room
}
```

In `SeamRoom.kt` `SeamRoomFactory`:
```kotlin
override suspend fun host(pattern: Pattern, memberName: String? = null): Room {
    // ...existing seam = loom.host(pattern); construct SeamRoom with:
    memberName = memberName,          // was: displayName = pattern.sessionName
    // ...
}
override suspend fun join(tag: Tag, memberName: String? = null): Room {
    memberName = memberName,          // was: displayName = tag.sessionName
    // ...
}
```

Mirror the signatures in `kuilt-session-test/.../FakeRoomFactory.kt`.

- [ ] **Step 4: Rewire `SeamRoom` to mint identity from `memberName`.**

In `SeamRoom.kt`, replace the private field (`:218`):
```kotlin
    private val memberName: String? = null,   // was: private val displayName: String,
```
Add a resolved accessor near `selfId`:
```kotlin
    /** This peer's roster name: the caller-supplied [memberName], else its own peer id. */
    private val resolvedMemberName: String get() = memberName ?: selfId.value
```
Use it where the peer names *itself* — `sendHello` (`:944`) and the host self-intro in
`admitPeer` (`:935`):
```kotlin
    // sendHello:
    displayName = resolvedMemberName,   // was: displayName = displayName
    // admitPeer host self-intro Welcome:
    displayName = resolvedMemberName,   // was: displayName = displayName
```
Leave the joiner-echo sites (`hello.displayName`, `existing.identity.displayName`) untouched —
those already carry the *sender's* member name.

- [ ] **Step 5: Run the new test — verify it passes.**

Run: `./gradlew :kuilt-session:jvmTest --tests "*SeamRoomMemberNameTest"`
Expected: PASS.

- [ ] **Step 6: Fix roster-asserting tests broken by the behavior change.**

Run the broader suites and fix each roster-name assertion failure by adding `memberName` to the
relevant `host()`/`join()` call (the value the test previously smuggled through the tag/pattern
session name):

Run: `./gradlew :kuilt-session:jvmTest examples:jvmTest`
Expected: assertion failures where a roster now shows a `PeerId` instead of e.g. `"Bob"`.
**Known example:** `RoomBoundAdmissionTest` asserts `hostRoster.single().identity.displayName == "Bob"`
for a joiner built as `join(InMemoryTag("Bob", roomKey = "room-A"))` — now fails (roster shows the
peer id). Fix by naming the joiner: `join(InMemoryTag("Bob", roomKey = "room-A"), memberName = "Bob")`
(and the host `host(Pattern("HostA", roomKey = "room-A"), memberName = "HostA")`). Apply the same
only where a test asserts on the roster name; pure-replication tests that ignore the label need no change.

- [ ] **Step 7: Full green build.**

Run: `./gradlew build detektAll`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit.**

```bash
git add -A
git commit -m "feat(session): supply member name per-call at host()/join(); stop borrowing the session name (#1177)"
```

---

### Task 3: TCK pin — named members across every fabric

Add a `RoomConformanceSuite` test so **every** room impl (every fabric subclass) proves a joiner
appears under its own member name, not the discovered session name. This is the test that would
have caught #1177 on every fabric.

**Files:**
- Modify: `kuilt-conformance/src/commonMain/kotlin/us/tractat/kuilt/conformance/RoomConformanceSuite.kt` (the TCK; shipped in `commonMain` for subclassing).

**Interfaces:**
- Consumes: `RoomFactory.host(pattern, memberName)`, `RoomFactory.join(tag, memberName)` (Task 2).

- [ ] **Step 1: Add the conformance test.**

In `RoomConformanceSuite.kt`, using the suite's existing `newHarness(scope)` harness
(`h.hostFactory` / `h.joinerFactory`) and roster API exactly as the neighbouring suite tests
do (e.g. `broadcastDeliversRoomFrameTaggedWithSender`). Match the suite's method style —
`public fun …(): TestResult = runTest { … }` with `@Test`, `explicitApi()` visibility:

```kotlin
// #1177: a joiner's roster identity is its own member name, never the discovered
// session name. Runs against every fabric subclass.
@Test
public fun membersAppearUnderTheirOwnMemberName(): TestResult =
    runTest {
        val h = newHarness(backgroundScope)
        val host = h.hostFactory.host(Pattern("Alice's game"), memberName = "Alice")
        val joiner = h.joinerFactory.join(InMemoryTag("Alice's game"), memberName = "Bob")

        val hostRoster = host.roster.first { it.size == 1 }
        val joinerRoster = joiner.roster.first { it.isNotEmpty() }
        assertAll(
            // The joiner shows under its OWN name, not the discovered session name.
            { assertEquals("Bob", hostRoster.single().identity.displayName) },
            { assertEquals("Alice", joinerRoster.single().identity.displayName) },
        )
    }
```

Add imports as needed (`kotlinx.coroutines.flow.first`, `us.tractat.kuilt.test.assertAll`) if
not already present in the file.

- [ ] **Step 2: Run it against the in-memory reference impl — verify it passes** (behavior fixed in Task 2).

Run: `./gradlew :kuilt-session:jvmTest --tests "*RoomConformance*"`
Expected: PASS. (It would have FAILED before Task 2 — the joiner would have shown the session name.)

- [ ] **Step 3: Full green build (exercises the pin on JVM fabrics).**

Run: `./gradlew build detektAll`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**

```bash
git add -A
git commit -m "test(conformance): pin named members across every fabric (#1177)"
```

---

## Self-Review notes

- **Spec coverage:** §1 rename → Task 1; §2 per-call optional `memberName` + PeerId default → Task 2; §3 SeamRoom rewire → Task 2 Step 4; §4 TCK pin → Task 3; migration (fix roster-asserting tests only) → Task 2 Step 6. All covered.
- **Type consistency:** `memberName: String? = null` identical across `RoomFactory`, `SeamRoomFactory`, `FakeRoomFactory`, `SeamRoom`. `resolvedMemberName` used in both self-naming sites.
- **Roster API is concrete:** all test bodies use the real `Room.roster: StateFlow<Set<Member>>` awaited via `roster.first { it.size == N }`, name read as `.identity.displayName` (verified against `RoomBoundAdmissionTest` and `RoomConformanceSuite`). Task 3 uses the suite's real `newHarness(scope).hostFactory`/`joinerFactory`.
- **Migration is self-locating:** Task 2 Step 6 names the one known breaker (`RoomBoundAdmissionTest`); the rest surface as loud assertion failures, not silent drift.
