# Fabric resilience testing (reconnect / lifecycle-flap) — design

**Status:** design, pre-implementation.
**Date:** 2026-05-31
**Scope:** `kuilt-core` test-infrastructure addition (a lifecycle-flap `Seam`
wrapper), new contract + session resilience tests, and composite-fabric
resilience tests folded into the Ply epic (#49) as a dedicated sub-issue.

## Problem

The fabric layer has a deterministic frame-fault kit (`FaultProfile`,
`FaultySeam`, `FaultyLoom`: drop / probabilistic-drop / delay / reorder-window /
buffer-ceiling / close-at) and the session layer has mature partition/reconnect
machinery (`HeartbeatPartitionDetector`, `JoinerReconnectController`,
`ResumeToken`). But there is **no way to simulate a transport link that drops and
recovers** — a `Seam` whose lifecycle flaps `Woven → Weaving → Woven`, or that
flaps a few times then gives up (`→ Torn`).

Consequences:

- `FaultProfile.CloseAt` closes a link **permanently**; `FaultySeam` **delegates**
  `state`, so neither can produce a transient lifecycle flap.
- The session reconnect tests fake a partition by **dropping frames** while
  `state` stays `Woven` — they have **never** exercised a `Seam` whose `state`
  actually transitions through `Weaving` and back. The recoverable-blip path the
  axis-1 contract explicitly permits (*"Woven → Weaving is permitted if a fabric
  supports re-establishment"*) is untested end-to-end.
- The composite (Ply) fabric's core value — a ply dropping and rejoining while the
  aggregate stays `Woven` — **cannot be honestly tested** without this primitive.

## The three axes of fault, and where this fits

| Axis | Models | Existing | This design |
|------|--------|----------|-------------|
| **Frame faults** | drop / delay / reorder of individual frames | `FaultProfile` / `FaultySeam` | reuse as-is |
| **Lifecycle flap** | a link going `Woven→Weaving→Woven` or `→Torn` | **missing** | **`FlakyLifecycleSeam`** |
| **Membership churn** | peers joining/leaving | `peers` StateFlow | reuse as-is |

Frame-fault and lifecycle-flap are orthogonal and **compose**: a flaky link both
loses frames *and* blips its connection. They are kept as separate wrappers so each
has one clear responsibility.

## Part A — the reconnect primitive

### `FlakyLifecycleSeam` (new, `kuilt-core` commonMain, `public`)

A `Seam` wrapper that **owns its own `state`** (the key difference from
`FaultySeam`, which delegates it) and gates the contract on it.

```kotlin
public class FlakyLifecycleSeam(
    private val delegate: Seam,
    private val scope: CoroutineScope,
) : Seam {
    // Imperative control surface (deterministic scenario tests):
    public suspend fun blip(weavingFor: Duration)          // Woven → Weaving → (delay) → Woven
    public fun enterWeaving()                              // Woven → Weaving (held until recover/tear)
    public fun recover()                                  // Weaving → Woven
    public fun tear(reason: CloseReason = CloseReason.Unreachable) // → Torn (terminal)
    public suspend fun flapThenTear(flaps: Int, weavingFor: Duration, reason: CloseReason) // N blips then Torn
}
```

**Behaviour while `Weaving`** (models a real link-down):

- `state.value == SeamState.Weaving`.
- `peers` collapses to `{selfId}` (this peer is momentarily alone).
- `broadcast` is the contract's **defined no-op** (observable, never silent) —
  there are no other peers; `sendTo(absent)` throws `PeerNotConnected` as always.
- Inbound delivery is **suspended**: frames arriving from the delegate while
  `Weaving` are dropped (the link is down). Delivery resumes on `recover()`.

**Behaviour on `recover()`:** `state → Woven`, `peers` refills from the delegate,
delivery resumes — exercising the consumer contract "pause while `Weaving`, resume
on `Woven`."

**Behaviour on `tear(reason)`:** `state → Torn(reason)` (terminal), `incoming`
completes, subsequent sends throw — same as any closed `Seam`.

### `FlakyLifecycleLoom` (new, `public`)

Wraps a delegate `Loom`, producing `FlakyLifecycleSeam`s; exposes the created
seams (in weave order) so scenario tests can drive specific links — mirroring
`FaultyLoom`.

### `FlapSchedule` (new, `public`) — declarative soak driver

A seeded, virtual-time loop for sustained chaos:

```kotlin
public data class FlapSchedule(
    val seed: Long,
    val meanUptime: Duration,
    val meanDowntime: Duration,
    val giveUpAfter: Int,   // flaps before escalating to Torn(Unreachable); 0 = never
)
```

`FlakyLifecycleSeam.drive(schedule: FlapSchedule)` launches the loop in `scope`:
alternate `Woven` (≈`meanUptime`) and `Weaving` (≈`meanDowntime`) for
`giveUpAfter` flaps, then `tear(Unreachable)`. Durations are seed-jittered and use
`kotlinx.coroutines.delay`, so `runTest` virtual time controls everything —
deterministic across runs.

### Composition

Lifecycle wrapper **outer**, frame-fault **inner**:
`FlakyLifecycleSeam(FaultySeam(realSeam), scope)`. The lifecycle wrapper gates the
consumer-facing contract on its own `state`; the inner `FaultySeam` applies
frame-level faults to whatever flows while `Woven`.

## Part B — foundation tests (buildable now, independent of Ply)

1. **Lifecycle contract tests** — `kuilt-core` commonTest, `FlakyLifecycleSeam`
   over `InMemoryLoom`:
   - `blip`: `state` traces `Woven→Weaving→Woven`; a `broadcast` issued while
     `Weaving` does **not** silently vanish and does **not** arrive; a `broadcast`
     after `recover()` **does** arrive; send order across the blip is preserved.
   - `peers` collapses to `{selfId}` while `Weaving` and refills on `recover()`.
   - `flapThenTear`: after N blips, `state` is `Torn(Unreachable)` and terminal
     (sends throw, `incoming` completed).

2. **Session resilience** — `kuilt-session` commonTest, `SeamRoom`/`Room` over a
   `FlakyLifecycleSeam`:
   - The room survives a `Woven→Weaving→Woven` flap: membership is preserved (or
     re-established) and frames flow again after `recover()` — the path the
     frame-drop partition tests never covered.
   - A flap that escalates to `Torn` surfaces the expected terminal
     membership/partition event.
   - This is the highest-value new coverage for the observed multi-peer fragility.

3. **Soak test** — `kuilt-core` commonTest:
   `FlakyLifecycleSeam` driven by a `FlapSchedule` **composed with** a
   probabilistic `FaultProfile`, asserting **eventual** convergence — every frame
   sent while `Woven` is eventually delivered, and the peer set converges — under
   sustained chaos. Virtual-time + seeded ⇒ always runs, fully deterministic. A
   real-network/-radio `-P`-gated variant is **out of scope** here.

## Part C — composite (Ply) resilience tests

A **dedicated sub-issue under epic #49** (sibling to #59–#63). Depends on the
composite landing (#62) and on Part A. Asserts:

- A ply `tear`s while another stays `Woven` → aggregate `state` stays `Woven`,
  `peers` does **not** flap (the peer is still reachable on the survivor).
- On ply **recovery** (`Weaving→Woven` after a blip), the composite **re-sends
  `Announce`** and restores that ply's contribution to membership/routing.
- Each ply independently flaky (`FlapSchedule` per ply, composed with frame
  faults) → **eventual exactly-once delivery** (dedup holds under reorder/retry)
  and a **convergent peer set**.
- A ply that escalates to `Torn` is dropped from the rollup; the survivor carries
  the session; aggregate never goes `Torn` until the **last** ply does.

**Spec feedback into #49:** the composite must **re-send `Announce` on every
`Woven` transition of a ply**, not only the first — otherwise a recovered ply's
peers are not re-learned. The Ply design's per-ply `state` collector already fires
on each transition; this makes the requirement explicit and is what the recovery
test above proves. (Add this one line to the Ply spec's identity-reconciliation
section.)

## Module placement & guardrails

- `FlakyLifecycleSeam`, `FlakyLifecycleLoom`, `FlapSchedule` live in `kuilt-core`
  commonMain beside `FaultySeam` — `public` test infrastructure depending only on
  the `Loom`/`Seam` contract (same precedent as `FaultySeam`). `explicitApi()`
  applies: explicit `public`.
- All timing uses `kotlinx.coroutines.delay` (virtual time under `runTest`); all
  randomness is seeded. No wall-clock, no flakiness in the flakiness-tester.
- Frame-fault (`FaultProfile`) is reused unchanged; this design adds the
  orthogonal lifecycle axis only.

## Out of scope

- Real-network / real-radio reconnect tests (would need OS-level link
  manipulation; stays `-P`-gated and separate, not built here).
- Changes to `FaultProfile` — lifecycle flap is a distinct axis with its own
  wrapper, not a new `FaultProfile` variant (which is evaluated per-frame, not over
  time).
- Application-level resume semantics (`ResumeToken`) — already covered at the
  session layer; this design feeds it a real flap to chew on, but does not change
  it.

## Sequencing

Detailed PR breakdown is deferred to the implementation plan (writing-plans). At a
glance: (A) `FlakyLifecycleSeam`/`Loom` + `FlapSchedule` + their own unit tests;
(B) contract tests, then session resilience tests, then the soak test; (C) the
composite resilience sub-issue under #49, implemented after the composite (#62)
lands.
