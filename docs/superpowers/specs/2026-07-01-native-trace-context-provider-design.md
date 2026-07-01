# Native trace context — make sampled logging follow your trace on iPhone and in the browser too

_Design for #1029. Part of the log-capture epic #986 (M2). Builds on the sampling
gate shipped in #990
(`2026-07-01-log-capture-m2-otel-sdk-design.md`)._

> **Status — decisions locked (2026-07-01).** Reviewed by @keddie on PR #1033. The
> outcomes are folded into the sections below; the task-by-task build sequence is in
> the companion plan `docs/superpowers/plans/2026-07-01-native-trace-context-provider.md`.
>
> - **Primary API: the coroutine-context element** (Option A) — `withActiveTrace(trace)
>   { … }` + `CoroutineContextTraceProvider`. The plain holder (Option C) is demoted to
>   a **minimal documented escape hatch**, not the primary surface.
> - **The resolution-site fix already landed** as #1034 (+ follow-up #1041):
>   `provider.current()` is now called synchronously at `CapturingAppender.log()` on the
>   caller and carried on `NormalizedLogEvent.activeTrace`. This design plugs the native
>   provider into that merged edge — it is no longer a change this work must make.
> - `TraceContextProvider.current()` **stays non-`suspend`**; `withActiveTrace` is
>   **app-owned** (there is no ambient tracer off the JVM to set it for you).
> - Lives in `:kuilt-otel-logging` `commonMain`, **no OTel SDK dependency** — same
>   `TraceContextProvider` interface, so JVM keeps `OtelSdkTraceContextProvider`.

## What this adds

When an app runs distributed tracing, kuilt can already let the trace's sampling
decision decide which log lines are worth keeping, and stamp the ones it keeps
with the trace and span they belong to — so later, someone reading the logs can
line each line up with the request that produced it. That's the **sampling gate**,
and it already works on the JVM and Android.

It does **not** work yet on an iPhone, a Mac, or in a browser. Those are exactly
the places the log-extraction story cares about — a phone that was offline, a
browser tab with no server nearby. This design closes that gap: it lets a Kotlin
Multiplatform app tell kuilt "this log line happened inside *this* trace" on every
platform, using nothing but Kotlin and coroutines — no OpenTelemetry SDK, no
platform-specific tracer.

The default is unchanged. An app that sets no trace context still captures every
log line, exactly as today. This only adds a way to *opt in* to trace-aware
logging on the platforms that couldn't before.

## Current state — the gate exists, but only the JVM can feed it

#990 split the gate into a **mechanism** and a **source**:

- **Mechanism** (`:kuilt-otel-logging`, `commonMain`, already shipped): the
  `LogCapture` core consults a `TraceContextProvider` per event and either drops
  the line (unsampled trace, or untraced under `UntracedPolicy.DROP`) or stamps it
  with the trace/span id (sampled trace). This is platform-independent and needs
  no changes to its *contract*:

  ```kotlin
  public data class ActiveTrace(val traceId: ByteString, val spanId: ByteString, val sampled: Boolean)
  public fun interface TraceContextProvider { public fun current(): ActiveTrace? }
  public enum class UntracedPolicy { CAPTURE, DROP }
  ```

- **Source** (only one exists): `OtelSdkTraceContextProvider`, in
  `:kuilt-otel-sdk`'s `jvmAndAndroidMain`. Its `current()` reads
  `io.opentelemetry.api.trace.Span.current()` — the OpenTelemetry Java SDK's
  ambient, thread-local-backed "current span." **There is no equivalent ambient on
  wasmJs or Kotlin/Native**, and the OTel Java SDK is a JVM-world artifact that
  can't be brought to those targets. So on an iPhone or in a browser the gate has
  nothing to consult, and trace-aware logging is unavailable.

#1029 supplies the missing source: a **kuilt-native provider in `commonMain`** that
resolves the current trace with no OTel dependency, so the same gate works on
wasmJs, iOS and macOS.

### A load-bearing finding about *when* the gate resolves the trace — now fixed (#1034)

> **Resolved in #1034/#1041 (merged).** The gate now resolves the trace at the
> synchronous edge — `CapturingAppender.log()` calls `LogCapture.resolveTrace()` on
> the caller and stamps `NormalizedLogEvent.activeTrace`; `LogCapture.capture()`
> gates on that snapshot and never re-consults the provider on the drain. The native
> provider below is exactly what `resolveTrace()` invokes at that edge. The finding
> is retained here because it is *why* the coroutine-element approach is correct and
> why an ambient read on the drain would have been wrong.

The gate must not consult the provider on the drain coroutine. Look at the capture
edge: `CapturingAppender.log(event)` runs **synchronously on whatever
thread/coroutine emitted the log line**, hands the event to an unbounded channel,
and returns. A single drain coroutine (`scope.launch { for (event in events) …
}`) later calls `LogCapture.capture(event)`, and *that* is where
`provider.current()` is invoked — on the **drain coroutine**, not the caller.

For any *ambient* trace source this is the wrong place to look:

- The drain coroutine has no "current span," so an ambient read there returns
  whatever the drain context happens to hold — generally nothing. (This is a
  latent hazard for the shipped `OtelSdkTraceContextProvider` too: by the time the
  drain runs, `Span.current()` on the drain thread is the *empty* context, not the
  caller's. See Open questions.)
- Even an explicitly app-set value is unsafe to read late: the app may have moved
  on — ended the span, set the next one — before the drain reaches this event, so
  a late read races the app and can stamp the wrong trace or none.

**Conclusion that shapes the whole design:** the active trace must be *snapshotted
at the synchronous capture edge* (`CapturingAppender.log`, on the caller), carried
on the event, and read back at drain time. This is true regardless of which source
we pick. It is the one place #1029 must touch the gate, and it's flagged for
@keddie below.

## Trace-source analysis — where does "the current trace" come from off the JVM?

Three candidate sources. All feed the same `TraceContextProvider` interface, so an
app can mix them: JVM keeps `OtelSdkTraceContextProvider`, wasm/iOS use the native
one — same gate, same `ActiveTrace`, chosen per platform at install time.

### Option A — a kuilt-owned coroutine-context element (recommended)

The app-facing source of truth is an `ActiveTrace` carried in the
`CoroutineContext`. Whoever starts a span wraps the work:

```kotlin
withActiveTrace(trace) {           // = withContext(ActiveTraceElement(trace)) { … }
    doWork()                       // every log line in here belongs to `trace`
}
```

Structured concurrency then propagates the trace to child coroutines for free, and
it disappears again when the block exits — the same lexical scoping tracers give
you, expressed in the one context mechanism every KMP target already has. It's
dependency-light (kotlinx-coroutines is already on the module), works identically
on JVM, Android, wasmJs, iOS and macOS, and is the idiomatic KMP answer to
"ambient value for the current logical task."

The **catch** is the finding above: the oshai `log()` callback is a *synchronous,
non-`suspend`* function, so it cannot call `currentCoroutineContext()` to read the
element. A coroutine-context element is invisible at the exact edge where we must
snapshot. Bridging that gap is what makes this option more than a one-liner — see
the API sketch. The bridge is a `ThreadContextElement` that mirrors the element
into an execution-local slot the synchronous edge *can* read; on single-threaded
wasmJs the "slot" is just a module-level holder. This keeps the ergonomics (scoped
`withActiveTrace`) while satisfying the resolve-at-edge requirement.

### Option B — a platform expect/actual over a native trace API (rejected)

Is there an ambient "current trace" to read on each platform, the way JVM has
`Span.current()`?

- **wasmJs / browser:** no. W3C Trace Context (`traceparent`) is a *wire header
  format* — a string propagated across HTTP/fetch boundaries — not a
  runtime-ambient the browser maintains for you. There is no `Span.current()`
  equivalent in the DOM or the JS engine. Reading `traceparent` would require the
  app to have parsed and stashed it somewhere — i.e. it collapses back into
  Option A or C.
- **iOS / macOS:** no ambient tracer in the platform SDK. Apple's `os.signpost`
  and the Swift `swift-distributed-tracing` `ServiceContext` are opt-in APIs the
  app drives; from Kotlin/Native there is nothing free to read.

So there is generically nothing to read. An expect/actual here would be a thin
shim over "whatever the app already stashed," which is Option A/C with extra
per-platform surface and no gain. **Rejected.**

### Option C — an explicit app-provided mutable holder

A `commonMain` object the app writes imperatively: `holder.set(trace)` on span
entry, `holder.clear()` on exit; `current()` returns the last value.

- **Pro:** dead simple, readable synchronously from the non-`suspend` edge (unlike
  a bare coroutine element), and on single-threaded wasmJs it is trivially correct.
- **Con:** it's a shared mutable ambient with no locality. On a multi-threaded
  runtime (Kotlin/Native with a multi-threaded dispatcher — which kuilt's own
  policy requires types to tolerate), thread A's `set` is visible to thread B's
  concurrent log line, so B stamps A's trace. It reintroduces exactly the
  thread-locality problem `Span.current()`'s ThreadLocal solves. It also puts the
  set/clear lifecycle on the app by hand, which is easy to leak.

**Not the primary**, but it is the natural *degenerate* form of Option A (a
`withActiveTrace` is a scoped set/clear), and on wasmJs the two converge. We keep a
holder as the wasm actual and as an escape hatch for apps that can't express their
tracing as coroutine scopes.

### Recommendation — LOCKED

**Option A — the coroutine-context element — as the app-facing API, resolved to
the appender at the synchronous log edge via a thread-context mirror; Option C's
holder is a minimal documented escape hatch only (not the wasm actual — the slot
below is).** Rationale:
it is the one source that is both idiomatic KMP *and* correct under kuilt's
multi-threaded-dispatcher policy, it needs no dependency beyond coroutines, and it
degrades cleanly to a plain holder on the one target (wasmJs) where locality is a
non-issue. The unavoidable cost — bridging a non-`suspend` edge — is small and
contained, and it also fixes the latent drain-time resolution hazard for *every*
provider, including the JVM one.

## Public API sketch (`:kuilt-otel-logging`, `commonMain`, no OTel dependency)

All of this lands in `commonMain`; only the execution-local *slot* is expect/actual.

```kotlin
// ── The source of truth an app sets ──────────────────────────────────────────
/** Carries the trace active for a logical task across its child coroutines. */
public class ActiveTraceElement(public val trace: ActiveTrace) :
    ThreadContextElement<ActiveTrace?> {
    public companion object Key : CoroutineContext.Key<ActiveTraceElement>
    // updateThreadContext: stash prior slot value, write `trace`; restore on exit.
    // (ThreadContextElement is multiplatform in kotlinx-coroutines; on wasmJs it
    //  degenerates to writing the module-level holder with no thread hop.)
}

/** Run [block] with [trace] as the active trace for every log line it emits. */
public suspend fun <T> withActiveTrace(trace: ActiveTrace, block: suspend () -> T): T =
    withContext(ActiveTraceElement(trace)) { block() }

// ── The provider the gate consults ───────────────────────────────────────────
/**
 * Resolves the trace an app set via [withActiveTrace] / the holder. Reads the
 * execution-local slot synchronously, so it is correct when consulted at the
 * capture edge (see gate change below). Same `TraceContextProvider` interface as
 * `OtelSdkTraceContextProvider`, so JVM apps may keep the OTel one while
 * wasm/iOS/macOS use this — the gate and `ActiveTrace` are identical.
 */
public class CoroutineContextTraceProvider : TraceContextProvider {
    override fun current(): ActiveTrace? = currentActiveTraceSlot()
}

// ── Low-level / escape hatch (also the wasmJs actual) ────────────────────────
/** Directly settable ambient for apps that can't scope tracing as coroutines. */
public class MutableTraceContextHolder : TraceContextProvider {
    public fun set(trace: ActiveTrace?)   // update the execution-local slot
    override fun current(): ActiveTrace?
}

// ── The execution-local slot (the ONLY expect/actual) ────────────────────────
//   jvmAndAndroid / native: backed by a ThreadLocal so concurrent tasks don't
//   clobber each other; wasmJs: a plain module-level var (single-threaded).
internal expect fun currentActiveTraceSlot(): ActiveTrace?
internal expect fun setActiveTraceSlot(value: ActiveTrace?): ActiveTrace? // returns prior
```

### The gate change — already landed in #1034 (no work here)

Snapshotting the trace at the caller edge instead of the drain coroutine shipped
in #1034/#1041:

1. `NormalizedLogEvent` gained `activeTrace: ActiveTrace?`.
2. `CapturingAppender.log()` — on the caller's thread/coroutine — calls
   `LogCapture.resolveTrace()` (→ `provider.current()`) and stamps the event.
3. `LogCapture.capture()` reads `event.activeTrace`; the drop/stamp table is
   unchanged.

So #1029 touches **no shipped gate code** — it only adds the native provider that
`resolveTrace()` calls, plus the `withActiveTrace` machinery that populates it.

### How an app wires it

```kotlin
// wasmJs / iOS / macOS: kuilt-native source
installLogCapture(exporter, config, clock, random, scope,
    traceContextProvider = CoroutineContextTraceProvider())
// … then, wherever a span begins:
withActiveTrace(ActiveTrace(traceId, spanId, sampled = true)) { doWork() }

// JVM/Android: unchanged — keep the OTel-backed source
installLogCapture(exporter, config, clock, random, scope,
    traceContextProvider = OtelSdkTraceContextProvider())
```

## Testing (wasmJs, iOS, macOS)

The gate's decision table is already covered by `LogCaptureGateTest`
(`commonTest`) with a fake provider; this work adds *source* coverage on the new
targets:

- **`commonTest` — `CoroutineContextTraceProvider` resolution.** Inside
  `withActiveTrace(sampledTrace) { … }`, `provider.current()` returns that trace;
  outside any scope it returns `null`; a nested `withActiveTrace` shadows and then
  restores the outer trace. Runs on every target (JVM, wasmJs, iOS, macOS) via the
  shared `commonTest` source set, so the same assertions execute natively — this
  is the acceptance proof the gate now works off the JVM.
- **`commonTest` — end-to-end through the edge snapshot.** Emit a log line inside
  `withActiveTrace` through a real `CapturingAppender` + `LogCapture` +
  `InMemoryDurableStore`, under `StandardTestDispatcher(testScheduler)`, seeded
  `Random`, virtual `Clock`; assert the stored record carries the trace/span
  bytes, and that a line emitted *outside* the scope is stamped `null` (or dropped
  under `UntracedPolicy.DROP`). This is the regression that would have caught the
  drain-time resolution hazard.
- **Concurrency / locality.** On a genuinely multi-threaded native dispatcher, two
  coroutines each in their own `withActiveTrace` must not cross-stamp. Follows the
  repo's real-primitives discipline (no dispatcher confinement); the ThreadLocal
  slot is the correctness mechanism under test. (wasmJs is single-threaded and
  exempt.)
- **wasmJs holder actual.** `MutableTraceContextHolder.set/current` round-trips on
  the module-level slot.
- **JVM parity.** `OtelSdkTraceContextProvider` continues to pass, now consulted
  at the edge; add one test that an OTel span active at emit time reaches the
  stored record end-to-end (guards the latent-hazard fix).

CI runs the full `./gradlew build`, so the wasmJs/native compiles and the
`commonTest` assertions on those targets are the hard acceptance bar.

## Alternatives & open questions for @keddie

> **Resolved (2026-07-01):** Q1 → coroutine element is primary, holder is a minimal
> escape hatch. Q3 → `current()` stays non-`suspend`. Q4 → the edge-snapshot fix
> already landed separately (#1034/#1041). Q2 → `withActiveTrace` is app-owned for
> now. Q5 (slot primitive) is settled in the plan: `ThreadLocal` on JVM/Android, a
> Kotlin/Native `@ThreadLocal` on Apple, a plain module-level var on single-threaded
> wasmJs. The original questions are kept below for the record.

1. **Context-element vs plain holder as the *primary* API.** The recommendation is
   the coroutine element (scoped, propagates to children, multi-thread-safe) with
   the holder as escape hatch. The counter-argument: the element needs the
   `ThreadContextElement`-to-slot bridge to be readable at the non-`suspend` edge,
   which is more machinery than a bare holder. If we expect most native/wasm apps
   to be effectively single-threaded around logging, a **holder-only** design is
   materially simpler and wasm-correct — at the cost of hand-managed set/clear and
   multi-threaded-native fragility. **Which is the primary surface we ship?**
2. **Who sets the trace, and how automatic should it be?** With no ambient tracer
   off the JVM, *something* has to call `withActiveTrace` / `set`. Options: (a)
   leave it entirely to the app (documented pattern); (b) ship thin helpers that
   wrap a "start span" call; (c) if kuilt ever grows its own span/trace primitive
   (out of scope here), it sets the element itself. Recommendation: (a) for
   #1029, design the API so (c) is a later drop-in. **Agree?**
3. **Should `TraceContextProvider.current()` become `suspend`?** A `suspend`
   `current()` could read `currentCoroutineContext()` directly and drop the
   thread-context mirror — cleaner *if* resolution happened in a suspend context
   that inherits the caller's context. But the capture edge (`log()`) is
   non-`suspend` and the drain coroutine is the wrong context, so `suspend` alone
   doesn't fix resolution — we'd *still* need the edge snapshot, and we'd churn the
   shipped interface + the JVM provider. Recommendation: keep `current()`
   non-`suspend`, resolve at the edge. **Confirm we don't want the `suspend`
   variant.**
4. **Is the drain-time resolution a bug to fix in this PR or separately?** The
   edge-snapshot change also repairs the latent `OtelSdkTraceContextProvider`
   drain-thread read. Fold it into #1029 (one coherent "resolve at the edge"
   change, recommended), or split a JVM-only fix out first? **Preference?**
5. **Execution-local slot primitive on native.** `ThreadLocal` vs
   `kotlinx.coroutines.ThreadContextElement`'s own mechanics vs a kotlin-native
   `@ThreadLocal`. All are viable; the exact choice is an implementation detail the
   plan settles, noted here because it's the one non-`commonMain` seam.

## Done when

A `commonMain` `CoroutineContextTraceProvider` (plus `withActiveTrace` and a
`MutableTraceContextHolder` escape hatch) resolves an app-set `ActiveTrace`; the
gate snapshots it at the capture edge and stamps/drops correctly; `commonTest`
assertions prove the source resolves and stamps end-to-end on **wasmJs, iOS and
macOS** (not just JVM); the full `./gradlew build` is green; one ready PR closes
#1029.

## Non-goals / notes

- **No OTel SDK anywhere in this work.** The provider is pure Kotlin + coroutines
  in `:kuilt-otel-logging` `commonMain`; the JVM `OtelSdkTraceContextProvider`
  stays where it is.
- **kuilt does not become a tracer.** It supplies no span ids, sampling decision,
  or trace propagation — the app (or its OTel SDK on JVM) owns those and hands
  kuilt an `ActiveTrace`. This is only the *source* wiring for a gate that already
  exists.
- **References policy:** abstract use cases only; W3C Trace Context and the Apple
  APIs are named only to establish that no ambient tracer exists to read — no
  third-party issue/PR citations.
</content>
</invoke>
