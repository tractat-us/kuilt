# Log capture & extraction — design

> Epic spec. Captures an application's existing `kotlin-logging` output into
> kuilt's offline-first telemetry buffer **through one uniform surface on every
> platform**, and lets a test or CI process **join the device as a kuilt peer and
> pull the logs out automatically** — the answer to "I can't get at the logs on
> an iOS simulator or a real phone while debugging."

## What it is, plainly

An app keeps logging exactly the way it already does — `kotlin-logging`, no code
changes at the call sites. Those log lines are captured into a durable on-device
buffer that survives going offline. When you want them, a test or CI process
**connects to the running app as a peer and pulls the logs out** — on an iOS
simulator or a real phone, where logs are otherwise hard to reach.

The design priority is **a single uniform surface across all platforms**: one
install call, one log model, one tap, identical on JVM, Android, iOS, macOS, and
wasmJs. How the logs are *intercepted* differs per platform (it has to — see
below), but everything the consumer touches is the same everywhere.

## The shape, in one picture

```
Native / JS / wasm:   app → oshai Appender ─────┐
JVM / Android:         app → SLF4J sink ─────────┼──→  LogCapture core (commonMain)
                       (kotlin-logging + raw SLF4J)         │
                                                            ▼
                                          WarpLogRecordExporter  =  Rga<LogRecord>
                                                            │
                                              replicate over a Seam (Quilter)
                                                            ▼
                                          a kuilt peer joins and pull()/tail()s
                                          (test asserts · CI dumps NDJSON · [M2] forwards OTLP)
```

The **core, the buffer, and the tap are common code**. Only the thin *capture
edge* — the adapter that hooks the platform's logging output into the core — is
per-platform.

## Why kuilt is the right substrate

The device already buffers logs as a CRDT (`WarpLogRecordExporter`, an
`Rga<LogRecord>` in `:kuilt-otel`). Extraction is therefore not a new transport
problem — it is *"a peer joins the device's Room and the log CRDT replicates over
whatever fabric reaches it"*: loopback WebSocket for a simulator/CI, mDNS+WebSocket
or Apple Multipeer for a real phone. kuilt already ships every one of those
fabrics, and replication is `:kuilt-quilter` anti-entropy over a `Seam` —
idempotent and order-preserving by construction, so a reconnect never
double-counts.

## What already exists (the observability work)

- `:kuilt-otel` — `WarpTelemetry` facade owning `spans` / `metrics` / `logs`
  exporters; each CRDT-backed and persisted to a per-platform durable WAL.
- `WarpLogRecordExporter` + `LogRecord` — the log buffer. `LogRecord` is
  OTLP-shaped, already carries optional `traceId`/`spanId`, is idempotent by
  `recordId`, and is stored as an `Rga`.
- `WarpOtlpBridge` + `OtlpEdge` — egress seam to a real OTLP backend (currently
  wires **spans only**; logs/metrics deferred). No concrete `OtlpEdge`
  implementation exists yet (interface + test double only).

This epic adds the two missing ends: **capture** (get app logs into
`WarpLogRecordExporter`, uniformly) and **extraction** (get them off the device
to a test harness).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| What does "the same across platforms" mean? | **Uniform *surface*, not uniform *scope*.** Identical install call, `LogRecord` model, and tap on every platform. JVM additionally catches raw SLF4J output — a documented bonus, not a contract difference. |
| Capture mechanism | **A shared `commonMain` capture core fed by a thin per-platform edge.** oshai `Appender` on Native/JS/wasm; an **SLF4J sink** on JVM/Android (oshai *delegates to* SLF4J there, so capture must sit at the SLF4J layer, not above it). |
| JVM capture surface | **A custom `SLF4JServiceProvider`** (kuilt is the SLF4J backend; framework-agnostic, catches every SLF4J logger) preferred over a logback-only appender. |
| Where do logs go | **kuilt's own offline RGA buffer** is the spine. The OpenTelemetry SDK is **not** required to use kuilt logging. |
| Receiving end of extracted logs | **The kuilt-native log-tap peer** (a peer joins and pulls/tails the RGA). OTLP-forwarding is one *sink behind that peer*, deferred to M2. |
| Who pulls the logs | **In-test kuilt peer** (assert / dump-on-failure) **and CI / simulator-harness auto-collect.** Standalone dev CLI deprioritized. |
| Sampling / trace gate | **Deferred to M2.** It's inherently JVM-only (OTel SDK), so it can't be part of a uniform M1. M1 is always-on capture; the gate layers on later behind a common interface. |
| OTel-SDK integration | **Demoted to an optional M2 bridge.** An app already running the OTel SDK can point its `LogRecordExporter` at kuilt — but nobody is *required* to run the OTel SDK. |
| Production safety | **Opt-in, off by default, loopback-bound by default.** kuilt stays policy-free; the app decides when to enable. Join-token admission is a follow-on. |
| First milestone | **Uniform capture + extraction, end-to-end.** |

### Why not the OTel-SDK appender as the primary capture path?

It was the obvious idea (use the standard OpenTelemetry logback/log4j appender →
a kuilt `LogRecordExporter`), and it's clean — **on JVM/Android only.** There is
no OpenTelemetry SDK for Kotlin/Native (iOS) or wasmJs, and **iOS log-extraction
is the driver of this epic.** A JVM-only capture path can't be the spine of a
uniform-surface design, so the OTel-SDK pieces move to M2 as an optional ingress
bridge.

### Why not an SLF4J → kotlin-logging bridge on JVM?

It's circular. oshai routes `kotlin-logging → SLF4J` on the JVM; routing
`SLF4J → kotlin-logging` back the other way loops every record through oshai
forever. The correct move is to capture *at* the SLF4J layer (the thing oshai
sits on top of), which is what the SLF4J sink does.

## Module layout

| Module | Targets | Role |
|---|---|---|
| `:kuilt-otel-logging` | all | **Capture.** The `commonMain` core (`LogCapture` event→`LogRecord` mapping, `CaptureConfig`, `installLogCapture`) plus two per-platform capture edges: an oshai `Appender` (Native/JS/wasm source sets) and an **SLF4J sink** in `jvmAndAndroidMain`. Depends on `:kuilt-otel` + kotlin-logging. |
| `:kuilt-otel-tap` | all | **The log-tap peer.** Host side opens an opt-in, loopback-default log Room; client side joins and pulls a snapshot or live-tails. Replicates the log `Rga` over a `Seam` via `:kuilt-quilter`. Fabric-agnostic — the milestone wires loopback WebSocket (`:kuilt-websocket`); mDNS/Multipeer is a config swap (follow-on). Depends on `:kuilt-otel` + `:kuilt-quilter` + `:kuilt-core`. |
| `:kuilt-otel-logging-otel` *(M2)* | JVM, Android | The OTel-SDK trace-context provider for the sampling-gate, **and** a kuilt `LogRecordExporter` so an already-instrumented OTel app can feed the same RGA. Isolates the opt-in `opentelemetry-api`/`opentelemetry-sdk` dependency. |
| `:kuilt-otel-otlp` *(M2)* | all | Concrete Ktor OTLP/HTTP edge → a standard OpenTelemetry Collector. The OTLP-forwarding sink behind the tap peer. |

`:kuilt-otel` already uses `kotlin-logging` for its *own* internal logging;
capture of *application* logs is a distinct concern and a separate module, which
also avoids any "capture our own captured logs" feedback risk.

## Milestone 1 — uniform capture + extraction (the useful unit)

### 1. Capture core (`:kuilt-otel-logging`, `commonMain`)
- `LogCapture` maps a normalized log event to a `LogRecord`:
  - level → OTLP `severityNumber` + `severityText`
  - message → `body`
  - logger name + key/value pairs (MDC-equivalent) → `attributes`
  - fresh 8-byte `recordId` per record; `timestampEpochNanos` / `observedEpochNanos`
    from the injected `Clock` (time is a dependency — never the wall clock directly;
    `recordId` RNG is injected too).
  - calls `exporter.export(record)`.
- `CaptureConfig`: minimum level and attribute mapping. (The trace/sampling
  policy is M2 — M1 capture is always-on.)
- One-call install: `installLogCapture(exporter, config)` — **the single
  uniform entry point on every platform.** The actual platform edge it wires up
  is selected by `expect`/`actual` (or platform source sets) and is invisible to
  the caller.

### 2a. Non-JVM capture edge (oshai `Appender`)
- On Native/JS/wasm, kotlin-logging (oshai) uses its own `Appender` mechanism.
  Register a kuilt `Appender` via `KotlinLoggingConfiguration` that normalizes
  the oshai event and feeds the shared `LogCapture` core.

### 2b. JVM/Android capture edge (SLF4J sink, `jvmAndAndroidMain`)
- On JVM/Android `kotlin-logging` is an **SLF4J facade** — it logs *through*
  SLF4J, so capture must sit at the SLF4J layer, not at the oshai `Appender`
  (which oshai ignores on the JVM).
- Provide a custom **`SLF4JServiceProvider`** (kuilt *is* the SLF4J backend) that
  normalizes each SLF4J event and feeds the same `LogCapture` core. A
  logback-appender variant is the fallback for apps that pin their own SLF4J
  provider (M2 #10).
- **Consequence (intended):** this captures *all* SLF4J output on the JVM —
  kotlin-logging *and* raw SLF4J from frameworks (Ktor, Android, libraries). The
  surface is identical to other platforms; the JVM simply catches more. Document
  it as a bonus.

### 3. The log-tap peer (`:kuilt-otel-tap`)
- **Host (on the device, opt-in):** `installLogTap(loom, exporter, config)` — a
  no-op unless called; binds loopback by default. Hosts a kuilt Room and
  continuously offers the exporter's `Rga<LogRecord>` for replication via
  `:kuilt-quilter`.
- **Client (in the test/harness):** `LogTapClient(seam)` exposing
  - `pull(): List<LogRecord>` — one anti-entropy round, then `toList()`.
  - `tail(): Flow<LogRecord>` — live replication for an interactive run.
- **Test/CI helpers:**
  - in-test peer: assert-on-logs and dump-on-failure utilities (test-support flavor).
  - sim/CI harness: a per-device artifact writer (NDJSON of `LogRecord`s).
- **Rendezvous:** device hosts `Rendezvous.New(pattern)` on a loopback WebSocket
  `Loom` at a debug port; the harness assigns one port per simulator (host-shared
  loopback means N sims need N ports) and the client joins
  `Rendezvous.Existing(tag)`.

### Milestone-1 done when
On **every target**, an app's `kotlin-logging` output is captured into
`WarpLogRecordExporter` through the one `installLogCapture` call; an in-test peer
over a loopback-WebSocket `Loom` pair `pull()`s and reconstructs the host's log
sequence in order with no duplicates; and the full build is green across all
targets. (Concretely: the JVM/Android SLF4J edge and at least one non-JVM oshai
edge are both exercised against the same shared core and the same tap.)

## Milestone 2 — sampling, secondary egress & reach (follow-on sub-issues)

5. **Sampling-gate** — `TraceContextProvider` interface (common) with the
   OTel-SDK provider (`:kuilt-otel-logging-otel`, JVM/Android) reading
   `Span.current().spanContext`; the appender consults it per record (sampled →
   capture + stamp `traceId`/`spanId`; unsampled → drop before `export`;
   no-active-trace → per `CaptureConfig.untracedPolicy`).
6. **OTel-SDK ingress bridge** — a kuilt `LogRecordExporter` so an app already
   running the OTel SDK can point its log pipeline at the same RGA.
7. Wire logs (and metrics) through `WarpOtlpBridge` by digest; extend
   `OtlpEdge.send` to accept log records.
8. Concrete Ktor OTLP/HTTP edge (`:kuilt-otel-otlp`) → standard OTel Collector —
   the **OTLP-forwarding sink behind the tap peer** (the "collector" role).
9. Real-phone reach: mDNS + Multipeer fabrics for the tap; **join-token
   admission control** so extraction is safe over LAN; a kuilt-native
   coroutine-scoped `TraceContextProvider` so the sampled-gate works on wasm/iOS.
10. JVM/Android: optional log4j2 / logback-appender variant for apps that pin a
    non-kuilt SLF4J provider.

## Key contracts

- **Uniform surface.** `installLogCapture(exporter, config)` and
  `LogTapClient.pull()/tail()` are identical on every platform; the per-platform
  capture edge is an internal `expect`/`actual` detail.
- **Capture** is a pure mapping plus an `export` call; the `Clock` and the RNG for
  `recordId` are injected dependencies (test determinism, repo policy).
- **Extraction** replicates the existing `Rga<LogRecord>` over a `Seam` via
  Quilter; `pull()` is idempotent and order-preserving by CRDT construction.
- **Safety:** the tap is off until `installLogTap` is called and binds loopback by
  default; kuilt ships no implicit always-on log Room.
- **No OTel-SDK requirement.** kuilt's own capture is the spine; the OTel SDK is
  an optional M2 bridge, never a prerequisite.

## Testing

- **Capture core:** drive `LogCapture` directly against an `InMemoryDurableStore`;
  assert the resulting `LogRecord` shape (severity/body/attributes/timestamps).
- **Capture edges:** the SLF4J edge wired to a real SLF4J logger (assert raw-SLF4J
  *and* kotlin-logging both land); at least one oshai edge on a non-JVM target —
  both feeding the same core, asserting identical `LogRecord` output.
- **Tap:** two exporters over an in-memory `Loom` pair (the conformance harness);
  assert the client `pull()` reconstructs the host's log sequence (order +
  no-duplicate); a loopback-WebSocket integration test for simulator realism.
- Coroutine determinism per repo policy: injected `StandardTestDispatcher`,
  bounded `settle()`/`await*`, no `advanceUntilIdle()`, scope-owning types correct
  under a multi-threaded dispatcher (explicit locks/atomics, not
  `limitedParallelism(1)` confinement).

## Honest limits

- **JVM scope is wider than other platforms.** The SLF4J edge captures all SLF4J
  output on the JVM, not just kotlin-logging. This is intended (uniform surface,
  bonus scope) but means JVM log volume differs from iOS/Native — filter by logger
  if strict scope-parity is ever needed.
- **JVM SLF4J provider is exclusive.** A custom `SLF4JServiceProvider` is the sole
  SLF4J binding in the test/app classpath; apps that must keep their own provider
  need the appender variant (M2 #10).
- **Sampled-gate is M2 and JVM/Android-first.** All platforms get always-on
  capture in M1; the trace gate lands later and reaches wasm/iOS only once the
  kuilt-native `TraceContextProvider` exists (M2 #9).
- **Per-sim ports.** iOS simulators share the host's loopback, so multi-sim
  extraction needs one port per sim; the harness owns that assignment.
- **Clock skew.** Inherited from `LogRecord`: timestamps are the producer's local
  clock; cross-producer ordering is RGA Lamport-tiebroken, not wall-clock exact.

## References policy

Abstract use cases only; no third-party issue/PR citations; no references to other
`tractat-us/*` repos. The iOS-debugging motivation is described generically.
