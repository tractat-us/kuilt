# OTLP egress — drain logs + metrics + spans by digest, plus a concrete Ktor edge

_Design for #1027 **and #846**. Part of the log-capture epic #986 (M2). Builds on
the M1 capture buffer (`2026-06-27-log-capture-and-extraction-design.md`) and the
spans-only egress bridge already in `:kuilt-otel`._

> **#846 folded in.** @keddie folded issue #846 ("A8 slice 2 — auto-stamp on export
> + bridge link emission") into this egress slice: it lives entirely on the
> export/drain path designed here, so it is no longer separate work. This spec
> designs it as a first-class part — see **Auto-stamp on export & causal-link
> emission** below. **This design closes #846** (the implementation PR carries the
> closing keyword; this design PR does not).

## What this adds (the plain version)

Your app already writes logs, metrics, and traces. Normally each one is shipped
straight to a server the moment it happens — and if the phone is offline, or the
laptop is on a plane, that telemetry is simply lost.

kuilt keeps that telemetry in a small on-device store that survives being offline,
merges cleanly when several devices later sync, and never double-counts. This
change adds the **last mile**: when the network comes back, quietly forward
everything the server hasn't seen yet to a **standard OpenTelemetry collector** —
the same endpoint your existing dashboards already read from.

So kuilt becomes an **offline-first front for the observability you already run**,
not a replacement for it. Nothing downstream changes: Grafana, Honeycomb, a
self-hosted collector — they all keep working. kuilt just makes sure the data
still arrives after a gap, and arrives exactly once.

The "exactly once after a gap" trick is the whole design. On every reconnect the
bridge asks a simple question — *what have I already delivered here?* — and sends
only the difference. Reconnect ten times in a row and only the first one puts
anything on the wire. That "send only the difference" idea is what the rest of
this doc calls reconciling **by digest**.

Traces get one extra thing here. When your code does work in one span that was
*caused by* work in another — even on a different device, even across a network gap
— kuilt already quietly records that "this-came-after-that" relationship on the
device (no clocks, no manual bookkeeping). This slice makes sure that relationship
is **stamped on automatically as spans leave** and **survives all the way to the
collector**, so your trace view shows the causal link instead of two disconnected
traces. You never call a "stamp this" API by hand.

Three concrete deliverables:

1. Teach the existing `WarpOtlpBridge` to drain **all three signals** — spans (done),
   plus **logs** and **metrics** — each by its own digest, without breaking the
   spans path.
2. Ship the first real transport: a new module **`:kuilt-otel-otlp`** — a Ktor
   HTTP client that serializes each signal to the OTLP wire and POSTs it to a
   collector.
3. **(folds in #846)** Auto-stamp the causal frontier onto spans on the export path,
   and emit the inferred causal links onto the OTLP `Span.links` wire field at drain
   time, so cross-device happens-before edges survive to the collector.

## Current state

`:kuilt-otel` today:

- **`WarpTelemetry`** owns three CRDT-backed exporters — `spans`
  (`ORSet<SpanRecord>`), `logs` (`Rga<LogRecord>`), `metrics`
  (`WarpMetricExporter`: a `GCounter`/`LWWRegister<Double>`/`HyperLogLog` per
  `MetricKey`). All three persist to a `DurableStore` and converge by CRDT merge.
- **`WarpOtlpBridge`** is **spans-only**. `drain(edge)` snapshots the span `ORSet`,
  fetches a `SpanDigest` from the edge, subtracts, and sends the difference via
  `OtlpEdge.send(spans)`. Its KDoc explicitly defers metrics (A3) and logs (A4).
- **`OtlpEdge`** is an interface with **no concrete implementation** — its only
  members are `digest(): SpanDigest` and `send(spans: Set<SpanRecord>)`. There is
  no transport yet; tests use a recording fake.
- **The causal-link machinery already exists but is not wired into the export
  path** (this is the #846 gap). `WarpCausalClock.tick()` mints a `CausalStamp`
  (a `Dot` + the observed frontier); `SpanRecord.causalStamp` is a nullable field
  (`null` = unstamped, today's default); `inferCausalLinks(spans)` derives
  `SpanLink`s from stamped spans with the cross-boundary filter; `SpanLink` maps
  onto an OTLP `Span.Link` tagged `kuilt.causality=potential`. What's missing:
  `WarpSpanExporter.export()` does **not** call `tick()` (callers would have to
  stamp by hand), `WarpSpanExporter.merge()` does **not** call
  `WarpCausalClock.observe()` (so cross-replica links never form), and the bridge
  does **not** run `inferCausalLinks` or put links on the wire.

So the gap is exactly: (1) two more signals through the bridge, (2) a real edge,
(3) auto-stamp + link emission on the span path (#846).

## The digest model, per signal

The digest answers one question: *which records has the destination already got, so
I can skip them?* The right shape of that answer differs by signal, because the
three signals have different **identity** semantics.

### The crux, resolved first: where does the digest come from?

The existing `SpanDigest` KDoc is written as if the **edge** (the collector) reports
what it holds. **A real OTLP/HTTP collector cannot do that.** OTLP is a
**write-only** protocol: `POST /v1/traces`, `/v1/logs`, `/v1/metrics`. There is no
"GET what you already have" endpoint. A concrete edge therefore *cannot* implement
`digest()` by querying the collector.

**Resolution: the digest is producer-local dedup state — "what have I already
successfully delivered to *this* endpoint?" — persisted on the device, not a
collector round-trip.** The concrete edge keeps its own sent-set in a
`DurableStore`, keyed per endpoint, and updates it after each successful POST.
`digest()` returns that local set.

This is the load-bearing decision of the whole design, and it has clean
consequences:

- **Offline-first survives.** No dependency on a collector read API that doesn't
  exist. The bridge works against a dumb, standard, write-only collector.
- **Correctness backstop stays at the collector.** If two producers both deliver
  span *X*, the collector deduplicates by span-id (OTLP's own guarantee) — so the
  local digest is an *optimization* (don't waste the wire), and the collector is the
  *safety net* (never double-count). Losing the digest can only cost bandwidth,
  never correctness.
- **Per-(producer, endpoint) scope.** A device that has delivered to collector A
  still delivers everything fresh to collector B — correct, because the local
  sent-set is keyed by endpoint.
- **The old "edge GC'd its digest → we under-send" hazard dissolves.** We track our
  own transmissions, never the collector's retention, so we can never be misled by
  the collector pruning. (The remaining bound is on *our* sent-set size — below.)

The `SpanDigest` KDoc that frames the digest as edge-held should be updated to this
producer-local framing as part of the digest-extension PR.

### Spans — `SpanDigest` (unchanged)

A span is **immutable once completed** and content-addressed by an 8-byte
`spanId`. The digest is a flat `Set<ByteString>` of span-ids already delivered.
Delta = local span-ids ∖ digest. No change needed.

### Logs — `LogDigest` (new, mirrors spans)

A `LogRecord` is also **immutable once written** and carries an 8-byte
caller-assigned `recordId` that the `Rga` exporter already dedups on. So logs get
the *same* shape as spans:

```kotlin
/** Which log records the endpoint has already received, keyed by recordId. */
public class LogDigest(public val recordIds: Set<ByteString>)
```

Drain: `logs.snapshot().toList()` (RGA order preserved) filtered to
`recordId !in digest.recordIds`. Idempotent re-drain sends nothing.

### Metrics — `MetricDigest` (new, the genuinely different one)

Metrics are **not immutable**. A `SUM` series climbs 5 → 10 → 15; a `GAUGE`
overwrites; a `CARDINALITY` estimate rises. So "does the endpoint have this key?"
is the wrong question — it has *a* value for the key, but maybe a stale one.

Identity is the `MetricKey`; freshness needs a **version of the converged value**.
So the metric digest keys by `MetricKey` and carries a content hash of the value we
last delivered:

```kotlin
/**
 * The value-version last delivered for each metric series.
 * A series is re-sent only when its current value-hash differs from this.
 */
public class MetricDigest(public val versions: Map<MetricKey, Long>)
```

The hash is over the **rendered OTLP data-point value**, not the raw CRDT lattice
state — because two different lattice states with the same observable value produce
the same OTLP point, and re-sending it is pure waste:

| Kind | Value hashed |
|---|---|
| `SUM` | cumulative total (`GCounter.value`) + series start time |
| `GAUGE` | `(value, timestamp)` of the winning `LWWRegister` write |
| `CARDINALITY` | `HyperLogLog.estimate()` |

Drain, per series: render the OTLP point, hash it; if `key` absent from the digest
**or** the hash differs, send it and record the new hash. Idempotent re-drain: all
hashes match → nothing sent. A series whose value advanced re-sends exactly once.

> **Cumulative-temporality note.** OTLP `SUM` points carry
> `(start_time, time, value)`. `start_time` is fixed per series (CRDT genesis);
> only `value` moves, so hashing the total suffices to detect "advanced." Sends
> stay monotonic because the CRDT value is monotonic — the bridge never emits a
> smaller total than one it already sent.

### Sent-set retention (all signals)

Because the digest is now *our* persisted sent-set, it grows as we deliver. It needs
the same kind of bound the exporters already have: a high-water-mark / ring cap per
signal, documented on the concrete edge, chosen to comfortably exceed the device's
realistic offline window. For spans/logs this is a bounded id-set; for metrics it is
one hash per live series (naturally bounded by series count). This replaces the old
"choose an edge retention window" caveat with "choose a *local* sent-set window."

## The widened `OtlpEdge` and `WarpOtlpBridge` (additive)

### `OtlpEdge` — new members with default no-op impls

The spans path must not break. Kotlin interface **default implementations** make the
evolution purely additive: existing span-only edges (and the test fake) keep
compiling untouched.

Note we **cannot** overload `send(Set<SpanRecord>)` / `send(Set<LogRecord>)` /
`send(Set<MetricPoint>)` — all three erase to `send(Set)` on the JVM (platform
declaration clash). So the new members get distinct names:

```kotlin
public interface OtlpEdge {

    // ── Spans ────────────────────────────────────────────────────────────
    public suspend fun digest(): SpanDigest
    // `links` param is additive with a default — span-only fakes still compile.
    // Carries the inferred causal links (#846) for the edge to put on Span.links[].
    public suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink> = emptyList())

    // ── Logs (new; default no-op keeps existing impls valid) ─────────────
    public suspend fun logDigest(): LogDigest = LogDigest(emptySet())
    public suspend fun sendLogs(logs: Set<LogRecord>) {}

    // ── Metrics (new) ────────────────────────────────────────────────────
    public suspend fun metricDigest(): MetricDigest = MetricDigest(emptyMap())
    public suspend fun sendMetrics(points: Set<MetricPoint>) {}
}
```

`MetricPoint` is a new small rendered-OTLP carrier (`MetricKey` + kind-specific
value + timestamps) so the edge serializes one uniform type rather than reaching
into three CRDTs. The bridge produces it; the edge encodes it.

> A future major could unify these into `digest(signal)` / `send(batch)` over a
> `sealed SignalBatch` — cleaner, but a breaking reshape. Deferred; see open
> questions. The default-method form is what ships here.

### `WarpOtlpBridge` — drain all three, richer result

The bridge is constructed from `WarpTelemetry` (or the three exporters) rather than
just the span exporter, and `drain()` reconciles each signal against its own digest:

```kotlin
public class WarpOtlpBridge(private val telemetry: WarpTelemetry) {
    public suspend fun drain(edge: OtlpEdge): DrainResult {
        // spans:   ORSet snapshot        − edge.digest()        → edge.send(delta, links)
        //          links = inferCausalLinks(snapshot) filtered to delta (#846)
        // logs:    Rga snapshot (ordered) − edge.logDigest()    → edge.sendLogs(...)
        // metrics: per-key render+hash    − edge.metricDigest() → edge.sendMetrics(...)
        // Each signal best-effort & independent: a failing signal
        // doesn't abort the others; the CRDT is left intact to retry.
    }
}
```

`DrainResult.Success` gains fields, kept backward-compatible with defaults so
existing positional/named callers still compile:

```kotlin
public data class Success(
    public val spansSent: Int,
    public val logsSent: Int = 0,
    public val metricPointsSent: Int = 0,
) : DrainResult
```

Each signal is wrapped in `runCatchingCancellable` independently (matching today's
best-effort span drain): a `DrainResult.Failure` is returned only if *nothing* got
through; a partial success reports what did. Cancellation always rethrows.

### One small exporter addition required

`WarpMetricExporter` today exposes per-key snapshots but **no way to enumerate its
keys**. The bridge needs to iterate every live series to render + hash it. Add a
minimal, lock-guarded accessor — e.g. `snapshotAll(): List<MetricPoint>` (or
`keys(): Set<MetricKey>` plus the existing per-key snapshots). This is the only
change to an existing exporter; spans (`snapshot().elements`) and logs
(`snapshot().toList()`) already expose what the bridge needs.

## The concrete edge — `:kuilt-otel-otlp`

A new module: a Ktor HTTP client `OtlpEdge` that serializes each signal to OTLP and
POSTs to a collector.

### Target set

Mirror `:kuilt-websocket`'s **client** side — the Ktor client runs on **all**
targets, so this module is all-targets (no server piece):

| Target | Ktor engine |
|---|---|
| commonMain | `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` |
| jvm | `ktor-client-okhttp` |
| android | `ktor-client-cio` |
| ios / macos | `ktor-client-darwin` |
| wasmJs | `ktor-client-js` |

Depends on `:kuilt-otel` (the record types, `OtlpEdge`, `DurableStore`) and
`:kuilt-core` (`runCatchingCancellable`). No `:kuilt-core` fabric types on the
surface — consistent with the `OtlpEdge` decoupling rule.

### Wire format — **OTLP/JSON**, with proto as a documented follow-up

OTLP/HTTP accepts two encodings on the same endpoints: protobuf
(`application/x-protobuf`) and OTLP/JSON (`application/json`). This design picks
**JSON** for the first edge:

- **KMP reality.** `kotlinx-serialization-json` is first-class multiplatform,
  already in the catalog, and battle-tested on all five targets. The multiplatform
  protobuf path (`kotlinx-serialization-protobuf`) is **experimental**, and exact
  OTLP proto-wire fidelity — field numbers, packed repeated, `oneof`, `bytes` — is
  fiddly and strictly validated by collectors. Getting it subtly wrong on Native or
  wasm is a real risk; JSON removes that risk class.
- **Debuggable.** For an offline-first egress you'll operate and diagnose, being
  able to `curl` a readable body is worth a lot.
- **Accepted.** The OTel Collector's OTLP receiver ingests JSON on the same
  `/v1/{traces,logs,metrics}` paths with `Content-Type: application/json`.

Cost: JSON is larger and marginally slower than proto — acceptable on a
reconnect-drain (not a hot path). Proto is left as a **follow-up**: either a second
edge or a content-type switch, once a codegen'd/vetted multiplatform proto encoder
is in place. Flagged in open questions because proto is OTLP's default and some
vendor endpoints prefer or require it.

**OTLP/JSON encoding quirks to honor** (hand-mapped in `@Serializable` DTOs with
custom serializers): `trace_id`/`span_id` are **lowercase hex strings** (not
base64); 64-bit ints (`*UnixNano`, sums) are **strings**; enums may serialize as
numbers; the top-level shape is
`resourceSpans[].scopeSpans[].spans[]` (and the `logs`/`metrics` analogues). The
kuilt records already store ids as raw `ByteString`, so the serializer hex-encodes
at the boundary.

### `digest()` on the client side

As resolved in the crux: **local, persisted, per-endpoint** — not a collector
query. The edge takes a `DurableStore` and stores its sent-set under keys like
`otlp.sent.spans@<endpointHash>` / `.logs@…` / `.metrics@…`. After a successful
POST it folds the delivered ids (spans/logs) or `MetricKey→hash` (metrics) into that
set and flushes. `digest()`/`logDigest()`/`metricDigest()` read it back. The
sent-set is bounded (retention cap above). A brand-new endpoint starts with an empty
digest → full first drain; every drain after that sends only what advanced.

### Span serializer emits `Span.links`

The span→OTLP/JSON serializer **must** map inferred links onto the OTLP
`Span.links[]` wire field (see the next section for where the links come from). An
`OtlpSpan.Link` carries `traceId`/`spanId` (hex-encoded, from
`SpanLink.linkedTraceId`/`linkedSpanId`) and `attributes` (carrying
`kuilt.causality=potential`). Omit the field for spans with no links. If the
serializer drops `links`, all of #846's value is lost silently at the wire — so this
is a hard acceptance test, not a nicety.

## Auto-stamp on export & causal-link emission (folds in #846)

This is #846, designed as a first-class part of the egress slice. The building
blocks already exist in `:kuilt-otel` (`WarpCausalClock`, `CausalStamp`,
`SpanRecord.causalStamp`, `inferCausalLinks`, `SpanLink`); #846 is the **wiring** of
them into the export/drain path — no new causal *types*, only connections. The
result: a caller that never touches a stamping API still gets cross-device causal
links on its traces at the collector.

### 1 — Auto-stamp on export (no caller change)

Today `WarpSpanExporter.export(span)` inserts the span as given; if the caller
didn't populate `SpanRecord.causalStamp`, the span is unstamped and
`inferCausalLinks` ignores it. #846 moves the stamp onto the export path so it is
automatic:

- `WarpTelemetry` **owns one `WarpCausalClock`** (constructed from the same
  `replica`), recovered in `WarpTelemetry.recover()` alongside the exporters. It is
  wired into `spans`.
- `WarpSpanExporter.export(span)` calls `clock.tick()` and attaches the returned
  `CausalStamp` to the span **before** inserting into the `ORSet` — *unless the
  caller already supplied a `causalStamp`* (an explicit stamp always wins;
  auto-stamp only fills the `null` default). This keeps the field's existing
  "null = unstamped" contract and is byte-compatible with records written before the
  field existed.
- `WarpSpanExporter.merge(remote)` calls `clock.observe(remoteFrontier)` on each
  anti-entropy round, folding the remote device's causal frontier into the local
  clock. This is what makes **cross-device** links form: the next local span's
  predecessors then point back at spans that arrived from another replica, and
  `inferCausalLinks` emits the cross-boundary edge for free.
- **Persistence discipline (mandatory).** `WarpCausalClock` warns that a restart
  which reset `seq` to 0 re-mints used dots and corrupts causality. So
  `WarpCausalClock.persist(store)` must run **after** the span batch is durably
  exported — i.e. the export path persists the clock in the same durable step that
  persists the span `ORSet`. Recovery (`WarpCausalClock.recover(store)` in
  `WarpTelemetry.recover()`) is likewise mandatory. Both are on the injected
  `DurableStore`; no new store.

Because `tick()`/`observe()`/`frontier()` are pure and lock-guarded (no suspend
inside the lock, per the clock's existing design), threading them into `export`/
`merge` adds no new concurrency surface — the exporter's existing `reentrantLock`
discipline is unchanged.

### 2 — Link emission at drain time

`WarpOtlpBridge.drain()` already snapshots the span `ORSet`. #846 adds, on the span
leg of the drain (after computing the by-digest delta, before handing spans to the
edge):

```kotlin
// span leg of drain():
val snapshot = telemetry.spans.snapshot().elements
val links: List<SpanLink> = inferCausalLinks(snapshot)   // over the FULL snapshot
val delta = snapshot.filter { it.spanId !in edge.digest().spanIds }
edge.send(delta, links)   // links threaded to the edge for wire emission
```

Design points:

- **Infer over the full snapshot, not just the delta.** A link's *predecessor* may
  already have been delivered on an earlier drain (so it's absent from today's
  delta), while the *successor* is new. Running `inferCausalLinks` over the whole
  snapshot resolves both endpoints; only links whose `fromSpanId` is in the delta
  need to ride along (a predecessor already at the collector is referenced by id, not
  re-sent). So: infer over the snapshot, then filter links to those whose
  `fromSpanId ∈ delta`.
- **Threading links to the edge.** `OtlpEdge.send` grows an additive links
  parameter with a default so span-only fakes still compile:
  `suspend fun send(spans: Set<SpanRecord>, links: List<SpanLink> = emptyList())`.
  The concrete edge attaches each link to its owning span's OTLP `Span.links[]` at
  encode time (join `SpanLink.fromSpanId` → the span's id).
- **Idempotency holds.** Links are derived, not stored state: a re-drain that finds
  an empty delta sends no spans and therefore no links; the collector already has
  both the spans and their links from the first drain. `inferCausalLinks` is
  deterministic (sorted output), so a re-drain that *did* have a delta emits exactly
  the same links — the collector dedups spans (and thus their attached links) by
  span-id. No link is ever emitted twice for the same span.

### Coordination / ordering note

This design and the pre-fold #846 both reshape `drain()` and `export()`; folding
#846 in here means one coherent change instead of two that race on the same methods.
There is no separate #846 PR to rebase against — it is authored as part of the
digest-extension PR (see PR split in open questions).

## Testing

**Bridge (`:kuilt-otel` commonTest), against a fake `OtlpEdge`:**
- The fake holds in-memory sets/maps for all three signals and returns digests that
  reflect what it has received.
- Assert first drain delivers spans + logs + metrics; **idempotent re-drain sends
  nothing new** for all three (the headline done-when).
- Assert a metric whose value **advances** re-sends exactly once; an unchanged
  metric never re-sends.
- Assert **partial failure isolation**: a fake that fails only `sendLogs` still
  delivers spans + metrics and reports them.
- Discipline per repo policy: `StandardTestDispatcher(testScheduler)`, seeded
  `Random`, injected virtual `Clock`, `backgroundScope` for any owned coroutine — no
  production dispatcher, no `advanceUntilIdle()`.

**Auto-stamp + link emission (#846), `:kuilt-otel` commonTest:**
- **Auto-stamp on export without caller change.** Export spans through
  `WarpTelemetry.spans` *without* setting `causalStamp`; assert each stored span
  comes out stamped (non-null `causalStamp`, monotonic `Dot` seq) and that an
  explicitly-supplied stamp is preserved (auto-stamp only fills the `null` default).
- **Cross-device links via merge.** Two replicas over the in-memory `DurableStore`:
  replica A exports, B `merge`s A's `ORSet` (so `observe` folds A's frontier), B
  exports a successor; assert `inferCausalLinks` over B's snapshot yields the
  cross-boundary edge, and that `drain()` hands that link to the fake edge attached
  to the correct span.
- **Links survive a re-drain idempotently.** First drain emits spans + their links;
  a re-drain with an empty delta sends nothing (no spans, no links); a re-drain that
  *does* have a delta emits the identical (sorted) links — the fake asserts no span
  or link is delivered twice.
- **Clock persistence across restart.** Persist after export, construct a fresh
  `WarpCausalClock`/`WarpTelemetry` over the same store, `recover()`, export again;
  assert `seq` continues (no re-minted `Dot`) so causality is not corrupted.
- Same coroutine discipline as above (seeded `Random`, virtual clock).

**Concrete edge (`:kuilt-otel-otlp`):**
- **Multiplatform request-shape tests** with Ktor `MockEngine` (runs on all
  targets): drive `send*`, capture the outgoing request, assert method/path/
  `Content-Type` and that the JSON body matches expected OTLP (hex ids, string
  64-bit ints, `resourceSpans`/`resourceLogs`/`resourceMetrics` envelopes).
- **`Span.links` on the wire (#846).** Drive `send(spans, links)` and assert the
  emitted OTLP JSON puts each link on its owning span's `links[]` with hex
  `traceId`/`spanId` and the `kuilt.causality=potential` attribute; a span with no
  links omits the field. This is a hard acceptance bar — a serializer that drops
  `links` fails.
- **JVM integration test** against a stub HTTP server (`ktor-server-test-host`):
  POST a real drain, capture bodies server-side, assert each signal's OTLP JSON and
  the correct `/v1/{traces,logs,metrics}` routing; assert a 200 folds the ids into
  the persisted sent-set and a subsequent drain is a no-op; assert a 5xx leaves the
  sent-set untouched so the next drain retries.
- Full `./gradlew build` (all variants) is the compile bar — Android + Native +
  wasm, not just `jvmTest`.

**Catalog additions:** `ktor-client-content-negotiation`,
`ktor-serialization-kotlinx-json`, `ktor-client-mock` (test), and
`kotlinx-serialization-json` is already present. `settings.gradle.kts` gains
`include(":kuilt-otel-otlp")`.

## Alternatives & open questions for @keddie

1. **Wire format: JSON-first (recommended) vs proto-first.** This design ships
   OTLP/JSON to sidestep experimental multiplatform proto and to stay debuggable,
   with proto as a follow-up. But proto is OTLP's *default*, is more compact, and a
   few vendor endpoints prefer/require it. **Accept JSON-first**, or do you want
   proto in the first edge (accepting the `kotlinx-serialization-protobuf`
   experimental risk and wire-fidelity test burden across Native/wasm)?

2. **Digest source (the crux) — confirm producer-local.** The design resolves
   `digest()` to *our persisted sent-set per endpoint*, because OTLP has no
   read-back. The alternative — a collector-side "what do you have" — would need a
   non-standard read API and would break against stock collectors. **Confirm the
   producer-local sent-set model** (and its retention-cap responsibility) is what you
   want. This also means the `SpanDigest` KDoc gets rewritten from edge-held to
   producer-local framing.

3. **`OtlpEdge` shape: additive default-methods (recommended) vs sealed-signal
   unify.** Default no-op methods keep the change non-breaking now; a
   `digest(signal)/send(SignalBatch)` unification is cleaner but breaking. Ship
   additive now and unify at the next major, or unify now while pre-1.0 churn is
   cheap?

4. **PR split (now includes #846).** The issue allows splitting. Natural seam:
   **PR 1** = digest extension + widened `OtlpEdge`/bridge + **auto-stamp on export +
   link inference at drain (#846)** + fake-edge tests (all in `:kuilt-otel`);
   **PR 2** = the `:kuilt-otel-otlp` module + wire encoding (**including the
   `Span.links` serializer**) + stub-server tests. PR 1 is independently valuable,
   unblocks any edge, and is where **#846 closes**. One caveat with this split: the
   *link-inference* half of #846 lands in PR 1 but the *wire emission* half lands in
   PR 2 — between the two, links are inferred but the only edge is a fake. That's
   fine (the fake asserts the links are threaded through), but if you'd rather #846
   not "close" until links actually reach a real collector, keep it a single PR.
   Confirm the split and where #846 closes.

5. **Endpoint config surface.** Minimal (base URL + headers for auth) vs richer
   (separate per-signal URLs, gzip, timeout/retry policy, TLS). Recommend minimal
   for the first edge (one base URL, optional auth header, injected `HttpClient` so
   the consumer owns engine/timeouts) and grow later.

6. **Sent-set retention bound.** What default cap for the local spans/logs sent-set
   (metrics is naturally series-bounded)? Suggest reusing the exporter buffer-cap
   defaults' order of magnitude, documented as "must exceed your realistic offline
   window."

7. **(#846) Auto-stamp default: on or opt-in?** This design makes `export()`
   auto-stamp **by default** (fill the `null` `causalStamp`), so causal links "just
   work" with no caller change — the accessible-first goal. The cost: every span now
   carries a `CausalStamp` and the clock must be persisted on the export path (a
   durable write of clock state alongside each span batch). If you'd rather keep
   stamping **opt-in** (a flag on `WarpTelemetry`, default off) to avoid that
   overhead for apps that don't want causal links, say so — I recommend on-by-default
   since the whole point of folding #846 in is that links arrive without ceremony.
   Also confirm the **explicit-stamp-wins** precedence (auto-stamp only fills a
   `null`) is the behaviour you want.

## Done when

- `WarpOtlpBridge.drain()` reconciles **spans + logs + metrics** each by its own
  digest against a fake `OtlpEdge`; an idempotent re-drain sends nothing new for all
  three; an advanced metric re-sends exactly once; partial per-signal failure is
  isolated.
- `LogDigest` / `MetricDigest` / `MetricPoint` land; `OtlpEdge` is widened additively
  (defaults keep the spans path and the existing fake compiling);
  `WarpMetricExporter` gains a key/point enumeration accessor.
- `:kuilt-otel-otlp` serializes each signal to OTLP/JSON and POSTs via Ktor;
  `MockEngine` request-shape tests pass on all targets and a JVM stub-server
  integration test round-trips all three signals, including sent-set persistence and
  retry-on-failure.
- **(#846) Auto-stamp on export** — `WarpSpanExporter.export()` auto-stamps via a
  `WarpTelemetry`-owned `WarpCausalClock` with **no caller change** (explicit stamps
  preserved); `merge()` calls `observe()` so cross-replica links form; the clock is
  recovered/persisted on the durable path (no re-minted dots across restart).
- **(#846) Link emission** — `drain()` runs `inferCausalLinks` over the span
  snapshot and threads the delta's links to the edge; the span→OTLP serializer emits
  them on `Span.links` with `kuilt.causality=potential`; a re-drain emits links
  idempotently (no double emission).
- Full `./gradlew build` green. One or two ready PRs per the split above. The
  digest-extension PR **closes #846** (the concrete-edge PR, if split out, still
  carries the `Span.links` serializer test).

## Non-goals / notes

- No collector read-back, no non-standard endpoint — stock OTLP/HTTP collector only.
- Histogram metrics remain deferred (no merge-able quantile CRDT yet); metric egress
  covers `SUM`/`GAUGE`/`CARDINALITY`.
- Clock-skew / HLC timestamp correction stays a separate follow-up (as noted on the
  record types); egress ships timestamps as-produced.
- **(#846) Causal stamping is span-scoped.** `WarpCausalClock` advances on span
  events only, so links are span→span. Cross-signal causality (a log or metric
  happens-before a span) is explicit future work, per the clock's own KDoc — not part
  of this slice.
- References policy: abstract use case only; OTLP is a public wire spec (the
  unavoidable shared-identifier exception). No third-party tracker citations.
