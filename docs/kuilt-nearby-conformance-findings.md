# `:kuilt-nearby` — Nearby Conformance Spike Findings

**Date:** 2026-05-27

Phase 3 wrote `:kuilt-nearby` as a contract-conformance adapter for Google Nearby
Connections against the cohered `Loom` / `Seam` / `Swatch` contract (ADR-034), to
learn whether the contract survives Nearby's three sharp seams. **Result: it does**
— `NearbyLoom` passes all six `SeamConformanceSuite` invariants on the JVM via a
fake radio. Below is what each strain taught us, plus one structural finding that
blocks the roadmap's "one suite, all fabrics pass" invariant until addressed.

## Strain 1 — bounded payload / chunking ✅ contract unchanged

A `Swatch` payload is unbounded (`payloadSize` can be any value); Nearby `BYTES` caps at ~32 KB. The
adapter absorbs the bound with an 8-byte-header chunk codec (`ChunkCodec`:
`msgId:Int, chunkIndex:UShort, chunkCount:UShort`), splitting on send and
reassembling per-endpoint by `msgId`. All chunks ride the one ordered/reliable
`BYTES` channel, so **no resequencing buffer is needed** and the common case
(sub-KB game frames) is a single chunk.

**Recommendation:** the contract should **not** grow a `maxFrameHint` — the adapter
absorbs the constraint cleanly and the contract stays transport-agnostic. The one
open question worth carrying to Phase 2 is whether `Seam.incoming`'s strict
send-order guarantee should ever relax to per-sender/causal order so a fabric could
parallelise large transfers on a side channel (the `STREAM` idea). Under the current
total-order contract that parallelism is unusable, so we did **not** pursue it; flag
only, no change proposed.

## Strain 2 — multi-step async connect ✅ + identity-exchange finding

`ConnectStateMachine` maps Nearby's request→initiate→accept→result handshake onto
the contract's single `suspend join()` / `open()` cleanly: it subscribes to the
event flows, kicks off the role-specific trigger, and suspends until a live link
resolves or it fails/times out. **`suspend join()` absorbs the 4-step handshake
without contract friction.**

**Finding (notable):** Nearby assigns *local-namespace* endpoint IDs — each side
refers to the other by an ID meaningful only locally. So `Swatch.sender` (a stable
`PeerId`) **cannot** be derived from an endpointId. The adapter must **exchange
stable `PeerId`s during the handshake** (each side sends its `selfId` as the first
`BYTES` payload after CONNECTED) and stamp `sender` from the exchanged value. This
is invisible to the contract but is a real obligation any P2P fabric with
local-namespace addressing inherits. Worth noting in the contract docs as guidance
for future fabrics.

## Strain 3 — runtime `availability()` ✅ contract sufficient

`FabricAvailability` cleanly expresses the distinction Nearby needs:
`Unavailable("Play Services: <status>")` when `GoogleApiAvailability` ≠ SUCCESS
(AOSP/GrapheneOS) vs **absent from the classpath** when the platform is wrong
(Nearby is Android-only). No contract change needed.

## Structural finding (blocks the epic invariant) — conformance suite was not shareable

`SeamConformanceSuite` lived in `kuilt-core/src/commonTest`. **A sibling module's
test source set cannot see another module's `commonTest`**, so *no fabric adapter
could actually subclass the suite* — only `kuilt-core`'s own
`InMemoryLoomConformanceTest` used it. The roadmap's exit invariant ("one contract,
four fabrics, one conformance suite they all pass") was therefore not wired.

**Done here:** extracted the suite into a new **`:kuilt-conformance`** module
(`commonMain`), depended on as a `commonTest` dependency by both `:kuilt-core` and
`:kuilt-nearby`. Because the suite is kotlin-test-based and now lives in *main*, the
module wires the test framework into its main source sets: `kotlin("test")` in
`commonMain` plus `kotlin("test-junit")` in `jvmMain`/`androidMain` (Native/wasm get
their actuals from `kotlin("test")`). Compiles on all targets; `NearbyLoom` passes
the shared suite.

**Proposed to #1515 (do not assume landed):** this is a kuilt-wide structural change.
The existing fabrics (`:kuilt-websocket`, `:kuilt-mdns`, `:kuilt-webrtc`) currently do
**not** subclass `SeamConformanceSuite`; to truly satisfy "all fabrics pass one
suite" they should each add a `*ConformanceTest` depending on `:kuilt-conformance`.
Recommend reviewing the module name/placement and adopting it across fabrics.

## Testing note — fakes for hot-flow callback APIs need UNDISPATCHED collectors

Nearby's callback API is modelled as hot `MutableSharedFlow`s (no replay). Under
`runTest`'s `StandardTestDispatcher`, a lazily-launched collector subscribes *after*
the kickoff emits, dropping handshake events and hanging the test. Fix: every event
collector launches `CoroutineStart.UNDISPATCHED` so it subscribes before the trigger
fires (`ConnectStateMachine.run(scope, trigger)` makes "subscribe, then trigger"
explicit). Future fabric adapters with callback-shaped APIs will want the same
pattern. Background work uses caller-context scopes (`currentCoroutineContext() +
SupervisorJob()`) so it runs on the test's virtual clock and is cancelled on
`Seam.close()`.

## Real-radio smoke — manual, two devices (external)

The on-device smoke is **inherently manual**: Nearby needs two physical Play-Services
devices, so it cannot run in CI or on a single emulator. The JVM fake-harness
conformance is the automated proof; the real binding (`GmsNearbyApi`) proves the API
surface maps to `NearbyApi` at compile time. Manual procedure: install on two
devices, one calls `open()` (advertise), the other `join()` (discover), exchange a
broadcast, confirm receipt. Flagged as the external follow-up on #1518.

## Summary of recommendations to epic #1515

1. Adopt `:kuilt-conformance` across all fabrics so "one suite, all fabrics pass" is real. ✅ Unblocked: `SeamConformanceSuite` now exposes `newLoomPair()` (ADR-001), enabling role-split fabrics (websocket, mdns, webrtc, multipeer) to supply distinct host/joiner Looms rather than a single loopback instance.
2. Document the identity-exchange obligation for local-namespace P2P fabrics in the contract guide.
3. Park (don't pursue) relaxing `incoming`'s total-order guarantee for `STREAM`-style parallelism.
4. No `Loom`/`Seam`/`Swatch` type changes are needed — the contract held.
