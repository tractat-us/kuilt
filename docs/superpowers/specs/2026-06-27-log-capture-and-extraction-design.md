# Log capture & extraction — design

> **Revision (2026-06-30): capture mechanism simplified.** The first cut of this
> spec assumed a per-platform capture edge — an oshai `Appender` on Native/JS/wasm
> and a separate SLF4J sink on JVM/Android. That split was forced by oshai
> `kotlin-logging` **7.0.7**, which exposes no settable appender on Kotlin/Native.
> oshai **8.x** moves the `Appender` API into common code and makes
> `KotlinLoggingConfiguration.direct.appender` settable on every target (incl.
> Darwin) once `loggerFactory = DirectLoggerFactory`. The design now uses **one
> uniform appender edge on every platform**; the JVM SLF4J sink is dropped (its
> behaviour becomes an optional M2 logback variant). Sections below reflect the
> revised design; this note records why it changed.

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
wasmJs. The logs are intercepted the *same way* everywhere too — one oshai
appender — so the consumer-facing surface and the internal edge are both uniform.

## The shape, in one picture

```
every target:   app → kotlin-logging → oshai direct.appender ──→  LogCapture core (commonMain)
                (DirectLoggerFactory + a kuilt CapturingAppender)         │
                                                                          ▼
                                          WarpLogRecordExporter  =  Rga<LogRecord>
                                                            │
                                              replicate over a Seam (Quilter)
                                                            ▼
                                          a kuilt peer joins and pull()/tail()s
                                          (test asserts · CI dumps NDJSON · [M2] forwards OTLP)
```

The **core, the buffer, the tap, and now the capture edge are all common code**.
The one adapter that hooks logging output into the core — a kuilt oshai
`Appender` registered through `direct.appender` — is identical on every target.

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
| What does "the same across platforms" mean? | **Uniform surface *and* uniform mechanism.** Identical install call, `LogRecord` model, tap, and capture edge (one oshai appender) on every platform. Scope is the app's `kotlin-logging` output, identically everywhere. |
| Capture mechanism | **A shared `commonMain` capture core fed by one uniform oshai-appender edge on every target.** `installLogCapture` sets `KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory` and registers a kuilt `CapturingAppender` via `KotlinLoggingConfiguration.direct.appender`. Requires oshai `kotlin-logging` **8.x** (the 7.x line had no settable Native appender). |
| What the appender delegates to (preserve existing output) | **The `CapturingAppender` delegates to a per-platform *delegate appender* so the platform's normal log output is preserved while it additionally captures.** On JVM/JS/wasm the delegate is the prior `direct.appender` (console). On **Darwin** the delegate is a kuilt **`%`-safe `OSLogAppender`** that writes to `os_log` honouring `KotlinLoggingConfiguration.subsystem`/`category` (so subsystem-filtered Console.app output keeps working) and escapes `%`→`%%` before `_os_log_internal`, sidestepping the format-string crash class that the default `DarwinKLogger` os_log path exhibits. This is the one platform-specific piece of the edge (a small Darwin `actual`); registration and the capture core stay `commonMain`. |
| JVM capture surface | **The same `DirectLoggerFactory` + `direct.appender` path as every other target.** No SLF4J sink. Trade-off: with `DirectLoggerFactory` active, JVM `kotlin-logging` no longer routes through SLF4J, so the app's logback/log4j config and other libraries' raw SLF4J output are not captured — an accepted honest limit (an optional logback-appender variant is an M2 follow-on for apps that need SLF4J-side capture). |
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

### Why not capture at the SLF4J layer on JVM?

The earlier design captured JVM logs at the SLF4J layer (a custom
`SLF4JServiceProvider`), because oshai 7.x had no settable appender on Native and
the JVM needed its own edge anyway. That made the JVM a special case — a second
capture path, a second set of tests, and an exclusive claim on the SLF4J binding.
With oshai 8.x's uniform `direct.appender`, the JVM uses the *same* edge as every
other target, which is simpler and genuinely uniform. The cost is that JVM
`kotlin-logging` no longer flows through SLF4J while capture is installed; apps
that specifically need to capture *raw* SLF4J output (from frameworks, not their
own `kotlin-logging` calls) can add the optional M2 logback-appender variant.

## Module layout

| Module | Targets | Role |
|---|---|---|
| `:kuilt-otel-logging` | all | **Capture.** The `commonMain` core (`LogCapture` event→`LogRecord` mapping, `CaptureConfig`, `installLogCapture`) plus the uniform `commonMain` capture edge — a kuilt `CapturingAppender` registered via oshai's `direct.appender`. The only platform-specific code is a small Darwin `actual` supplying the `%`-safe `OSLogAppender` delegate. Depends on `:kuilt-otel` + kotlin-logging **8.x**. |
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
- Because capture is **always-on and structured** (key/value pairs → `attributes`),
  it also carries structured *diagnostic* and *duration/perf* lines an app emits
  through `kotlin-logging` (e.g. `span=bootstrap durationMs=42`) — these are
  captured and extractable through the same tap with no separate pipeline, and
  always-on means a debugging session never samples them away. (Monotonic-clock
  span *durations* as first-class structured records are a `:kuilt-otel`
  `SpanRecord` concern, layered in M2, not part of M1 log capture.)
- One-call install: `installLogCapture(exporter, config, clock, random, scope)` —
  **the single uniform entry point on every platform.** Its body is common code on
  every target; it builds the `LogCapture` core and installs the capture edge
  below. The only `expect`/`actual` is the per-platform *delegate appender* the
  edge wraps (Darwin supplies the `%`-safe `OSLogAppender`).

### 2. The capture edge (oshai `direct.appender`, `commonMain`)
- oshai `kotlin-logging` 8.x exposes the `Appender` interface and `KLoggingEvent`
  in common code, and `KotlinLoggingConfiguration.direct.appender` is settable on
  every target (JVM, Android, iOS, macOS, wasmJs) once the active logger factory
  is `DirectLoggerFactory`.
- `installLogCapture` therefore: sets
  `KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory` and replaces
  `direct.appender` with a kuilt `CapturingAppender` that **delegates to a
  per-platform delegate appender** (so normal log output is preserved) and
  additionally normalizes each `KLoggingEvent` and feeds it to the shared
  `LogCapture` core.
- The delegate appender is selected by a small `expect`/`actual`:
  - **default (JVM/Android/JS/wasm):** the prior `direct.appender` (console).
  - **Darwin (`actual`):** a kuilt **`%`-safe `OSLogAppender`** — writes to
    `os_log` using `KotlinLoggingConfiguration.subsystem`/`category` (so
    subsystem-filtered Console.app output is preserved) and escapes `%`→`%%`
    before the os_log format string, avoiding the crash class the default
    `DarwinKLogger` path has. This is the only platform-specific code in the edge.
- oshai's `log` callback is synchronous and may run on any thread; `LogCapture`
  is `suspend`. A single dedicated drain coroutine bridges them — `log` hands the
  normalized event to an unbounded `Channel`, the drain coroutine consumes in FIFO
  order on the injected `scope`. This is the sanctioned single-writer channel
  pattern (per-producer order without dispatcher-confinement-as-mutex).

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
targets. (Concretely: the uniform appender edge is exercised in `commonTest`, so
it runs on JVM, Native, and wasm against the same shared core and the same tap; a
Darwin-only test asserts the `%`-safe `OSLogAppender` delegate escapes `%`→`%%`.)

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
10. JVM/Android: optional log4j2 / logback-appender variant. Under the uniform
    `DirectLoggerFactory` edge, JVM `kotlin-logging` no longer flows through SLF4J,
    so the app's own logback/log4j output and other libraries' raw SLF4J logs are
    not captured. This variant adds a kuilt logback (and/or log4j2) appender an app
    can install to *additionally* capture SLF4J-side output into the same RGA —
    for apps that want the old JVM "bonus scope" back.

## Key contracts

- **Uniform surface and mechanism.** `installLogCapture(...)` and
  `LogTapClient.pull()/tail()` are identical on every platform, and so is the
  capture edge — one `commonMain` oshai appender via `direct.appender`. The sole
  platform-specific piece is the Darwin `%`-safe `OSLogAppender` delegate
  (one `expect`/`actual`), invisible to the caller.
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
- **Capture edge:** in `commonTest`, install the appender, emit through a real
  `KotlinLogging.logger`, and assert the resulting `LogRecord` — runs unchanged on
  JVM, Native, and wasm (one edge, one test, every target).
- **Darwin os_log delegate:** a Darwin-target test asserts the `%`-safe
  `OSLogAppender` escapes `%`→`%%` so a `%`-bearing line cannot reach `os_log` as a
  format specifier.
- **Tap:** two exporters over an in-memory `Loom` pair (the conformance harness);
  assert the client `pull()` reconstructs the host's log sequence (order +
  no-duplicate); a loopback-WebSocket integration test for simulator realism.
- Coroutine determinism per repo policy: injected `StandardTestDispatcher`,
  bounded `settle()`/`await*`, no `advanceUntilIdle()`, scope-owning types correct
  under a multi-threaded dispatcher (explicit locks/atomics, not
  `limitedParallelism(1)` confinement).

## Honest limits

- **Installing capture changes the logger factory.** `installLogCapture` sets
  `KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory` on every target.
  On the JVM that means `kotlin-logging` stops routing through SLF4J while capture
  is installed — the app's logback/log4j formatting and routing no longer apply to
  its `kotlin-logging` output, and other libraries' raw SLF4J logs are not
  captured. On Darwin the default `DarwinKLogger` os_log path is replaced; the
  `CapturingAppender` delegates to a kuilt `%`-safe `OSLogAppender` that preserves
  subsystem-filtered Console.app output **and** escapes `%` (fixing the
  format-string crash class the default Darwin path has). Scope is the app's own
  `kotlin-logging`, uniformly. Apps that need SLF4J-side capture add the optional
  M2 logback variant (#10).
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
