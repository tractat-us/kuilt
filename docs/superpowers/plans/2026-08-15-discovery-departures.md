# Plan — `PeerDiscoverySource.departures()`: make the silent gap impossible

## Context

`PeerDiscoverySource.departures()` defaults to `emptyFlow()`. Four of five implementations in the
repo never emit a departure, and nothing catches it — there is no conformance suite for
`PeerDiscoverySource`. Consumers folding these feeds through `discoveryRoster` accumulate **ghost
peers**: a host that stopped advertising stays listed and dialable forever.

Survey (the reason this is a restructure, not three point fixes):

| Implementation | `departures()` today |
|---|---|
| `kuilt-mdns` **jvmMain** | implemented but broken — reads a TXT record the goodbye packet no longer carries (#1917) |
| `kuilt-mdns` **androidMain** | missing → silent `emptyFlow()` (#1903) |
| `kuilt-mdns` **iosMain** | missing → silent `emptyFlow()` (unfiled) |
| `kuilt-multipeer` **androidMain** | missing → silent `emptyFlow()` (unfiled) |
| `kuilt-multipeer` **appleMain** | correct — the only one |

Downstream: `fireworks-compose#3871`.

### The constraint that shapes every mDNS fix

On all three mDNS platforms the **removal event carries only the service name**, never the TXT map:

- JVM `serviceRemoved(ServiceEvent)` — `event.info` is non-null but its text is the qualified
  service name (PTR rdata), so `getPropertyString("peerId")` is null. Evidence in #1917.
- Android `onServiceLost(NsdServiceInfo)` — unresolved, `attributes` empty.
- iOS `didRemoveService: NSNetService` — not resolved.

So the peer id is knowable at removal time **only** from state built on the resolve path.

### The trap in #1917's prescribed fix

#1917 suggests keeping a `name → peerId` map populated in `serviceResolved`. Correct as far as it
goes, but `departures()` registers its **own** listener whose `serviceAdded` is `Unit` — it never
calls `requestServiceInfo`. A consumer collecting only `departures()` therefore resolves nothing,
the map stays empty, and it still emits nothing. Each listener must be **self-sufficient**: request
resolution itself, and maintain its own map.

### Why cold, not `shareIn`

A shared hot browse flow (`shareIn(scope, WhileSubscribed(), replay = 0)`) was considered and
rejected. `discoveryRoster` does `events.merge()` over `discoveries()` and `departures()`
(`DiscoveryRoster.kt:46-52`); `merge` subscribes to inner flows in separately-launched coroutines, so
the second view attaches a turn late and silently drops whatever the upstream emitted in between.
Each view stays a **cold** flow with its own listener and its own map: no shared mutable state, no
replay hazard, no required-scope constructor change.

## Global Constraints

- `explicitApi()` is enforced — every public declaration needs an explicit visibility modifier.
- Test methods take no `test` prefix; multi-assert tests use `assertAll()`.
- Production dispatchers (`Dispatchers.{Unconfined,Default,IO,Main}`, `GlobalScope`) are **banned in
  test sources**. iosMain's existing `flowOn(Dispatchers.Main)` is production source and stays.
- No `Dispatchers.X.limitedParallelism(1)` confinement as a substitute for mutual exclusion. Guard
  shared mutable state with explicit primitives, or keep it flow-local so there is none.
- Never swallow cancellation. Bare `runCatching` is banned in suspend/coroutine context; use
  `runCatchingCancellable` or `try { … } catch (_: Throwable) { currentCoroutineContext().ensureActive() }`.
- Real-multicast tests are opt-in behind `-Pmdns.multicast.tests=true` and must self-skip otherwise.
- Do not reorder existing code or make whitespace-only edits.

## Task 1 — Contract: remove the `emptyFlow()` default, add the TCK

**Files:** `kuilt-core/src/commonMain/.../discovery/PeerDiscoverySource.kt`,
new `kuilt-conformance/src/commonMain/.../DiscoverySourceConformanceSuite.kt`,
plus every implementor/fake the removed default breaks.

1. Remove the `= emptyFlow()` default from `PeerDiscoverySource.departures()`. Opting out becomes a
   visible line. Update the KDoc: state that a source with no leave signal must return `emptyFlow()`
   **explicitly**, and that `discoveryRoster`'s ghost caveat applies to exactly those sources.
2. Fix every site the removal breaks — `kuilt-multipeer` androidMain/jvmMain/wasmJsMain, any test
   fakes in `DiscoveryRosterTest`, `DiscoverySamples.kt`. For genuine no-signal sources write
   `override fun departures(): Flow<String> = emptyFlow()` with a one-line comment saying why.
   Do **not** implement real departures for multipeer androidMain here — that is Task 5.
3. Add `DiscoverySourceConformanceSuite` in `:kuilt-conformance`, `commonMain`, for subclassing.
   Two-armed sealed fixture so an absent leave signal cannot hide a bug:

```kotlin
public sealed interface DepartureFixture {
    /** The source has a real leave signal; [cause] makes one peer depart. */
    public class Emits(public val cause: suspend () -> Unit) : DepartureFixture

    /** The source genuinely has no leave signal (a fixed-roster fake, a transport without one). */
    public data object NoLeaveSignal : DepartureFixture
}
```

The suite exposes **non-nullable** abstract hooks — `newSource(): PeerDiscoverySource`,
`departureFixture(source): DepartureFixture`, `causeArrival(source)`. KDoc each with *why* it is
non-nullable: a nullable "I cannot reach this state" opt-out moves the vacuity one level up where it
is harder to see.

Properties:
- **Emits arm:** after `causeArrival` then `cause`, `departures()` emits a key **equal to the
  `Tag.peerKey` of the Tag `discoveries()` emitted** for that peer. This is the property that would
  have caught jvmMain: emitting *something* is not enough, it must be the right key.
- **Emits arm:** `departures()` collected **alone**, with no concurrent `discoveries()` collector,
  still emits. This is the property that catches the #1917 trap.
- Both arms: `departures()` completes when the collector's scope is cancelled and does not leak a
  listener.
- **NoLeaveSignal arm:** `departures()` is empty. KDoc must state plainly what this arm cannot
  detect — it proves only that the source is honest about having no signal.

Each property asserts its own precondition, so a backend handing back a healthy fixture fails loudly
rather than passing quietly.

**Verify:** `./gradlew :kuilt-core:build :kuilt-conformance:build detektAll --rerun-tasks`

## Task 2 — mDNS jvmMain: emit the mapped peer id (#1917)

**File:** `kuilt-mdns/src/jvmMain/.../MDNSServiceDiscoverer.kt`

Restructure both flows onto one self-sufficient listener shape. `departures()`'s listener must:

- `serviceAdded` → `jmdns.requestServiceInfo(event.type, event.name)` (**this is the fix for the
  trap** — without it, a lone `departures()` collector resolves nothing).
- `serviceResolved` → record `event.name → peerId` from the TXT map, in a flow-local
  `MutableMap` created inside the `callbackFlow` block (flow-local ⇒ no shared mutable state).
- `serviceRemoved` → look up `event.name`, `trySend` the mapped id, and remove the entry. A name
  never resolved emits nothing — it could never have reached `discoveries()` either.

Keep `discoveries()` behaviourally identical.

**Tests:** subclass `DiscoverySourceConformanceSuite` against a loopback/in-memory JmDNS harness.
Add a real-multicast regression test gated behind `-Pmdns.multicast.tests=true` that registers a
service, unregisters it, and asserts a departure carrying the advertised peer id — only a live
goodbye packet reveals the missing TXT, so the unit path alone cannot pin this.

**Verify:** `./gradlew :kuilt-mdns:build detektAll --rerun-tasks`, then the multicast suite with
`-Pmdns.multicast.tests=true`.

## Task 3 — mDNS androidMain: implement `departures()` (#1903)

**File:** `kuilt-mdns/src/androidMain/.../MDNSServiceDiscoverer.kt`

`onServiceLost` is currently `{}` at line 134 and there is no `departures()` override at all. Add
one, same shape as Task 2: its own `discoverServices` registration, its own flow-local
`serviceName → peerId` map populated from the existing resolve path (reuse the serialised
`resolveNext` queue — NSD resolves one at a time), emitting on `onServiceLost`.

Keep `discoveries()` behaviourally identical, including its `synchronized(lock)` guarding.

**Tests:** subclass `DiscoverySourceConformanceSuite` against a fake `NsdManager`. The fake must be
able to *fail* to emit — a permissive fake makes the enforcement test vacuous.

**Verify:** `./gradlew :kuilt-mdns:build detektAll --rerun-tasks` (Android variant must compile —
`jvmTest` alone does not cover it).

## Task 4 — mDNS iosMain: implement `departures()` (new, unfiled)

**File:** `kuilt-mdns/src/iosMain/.../MDNSServiceDiscoverer.kt`

`netServiceBrowser(_, didRemoveService:, moreComing:)` is `{}` at line 98 and there is no
`departures()` override. Add one, same shape: `ServiceDelegate` gains an `onRemoved` callback and a
delegate-local `name → peerId` map populated in `netServiceDidResolveAddress`, keyed on
`sender.name()`. Keep the `flowOn(Dispatchers.Main)` and the run-loop scheduling/teardown as they are.

**Tests:** subclass `DiscoverySourceConformanceSuite`. If a real `NSNetServiceBrowser` cannot be
driven deterministically under test, drive `ServiceDelegate` directly and say so in the suite
subclass KDoc.

**Verify:** `./gradlew :kuilt-mdns:build detektAll --rerun-tasks`, plus `iosSimulatorArm64Test`.

## Task 5 — multipeer androidMain + close the survey

**File:** `kuilt-multipeer/src/androidMain/.../MultipeerServiceBrowser.android.kt`

Determine whether this browser has a real leave signal. If it does, implement `departures()` the same
way. If it is a genuine no-op stub for a platform where Multipeer does not exist, keep the explicit
`emptyFlow()` from Task 1 and give it a KDoc line saying so.

Then subclass `DiscoverySourceConformanceSuite` for **every** `PeerDiscoverySource` in the repo that
does not yet have one — including `appleMain`, which is believed correct and should now be *proven*
correct.

**Verify:** `./gradlew build detektAll --rerun-tasks` (full build — this task touches several modules).

## Task 6 — Docs + citations

- `discoveryRoster`'s ghost caveat (`DiscoveryRoster.kt:29-34`) currently says the default is
  `emptyFlow()`. That is no longer true; rewrite it to describe a source that *explicitly* opts out.
- `PeerDiscoverySource` KDoc line 21 ("it may never remove departed peers (see [departures])") needs
  the same treatment.
- Add a `docs/agent-cookbook.md` symptom→primitive entry for "peers accumulate and are never
  removed", quoting a compiled snippet verbatim.
- Confirm `.claude/skills/kuilt-primitives/SKILL.md` still routes to the discovery primitives.

**Verify:** `./gradlew verifyDocCitations verifySampleLinks verifyModuleTable`
