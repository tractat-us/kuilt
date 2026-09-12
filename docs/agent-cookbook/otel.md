# Otel

A record of what an app did — its log lines, its traces, its counts — lives on the
device itself first, so that record survives being offline and can be pulled off later,
or sent on once the network returns. This page also covers reporting a failure on a path
that is going to retry, without the same failure being logged once per attempt.

## Telemetry & log capture

**Intent:** keep an app's own log lines on the device without the logging path costing real time — "capturing logs is slow", "the app stalls when it logs a lot", "logging is slowing us down on a phone".
**Primitive:** `installLogCapture` (`:kuilt-otel-logging`) for the whole path, and `WarpLogRecordExporter.export(records)` (`:kuilt-otel`) when you hold the records yourself. Don't write a flush-per-line loop.

`export(records)` applies a whole run as **one write turn** — one CRDT append pass, one CBOR encode of the active segment, one segment write — instead of paying that fixed cost once per record. `installLogCapture` already drains into it that way, so a consumer gets the amortisation without doing anything; reach for the bulk overload directly only when you hold the records yourself.

Nothing is held back waiting for a batch to form, so durability is unchanged: `export` returns after its own durable write, exactly as the single-record overload does. A batch is only ever what was *already* queued — which is why one forms when the producer is outrunning the drain, and never on an idle app.

Two things stay per-record: a duplicate `LogRecord.recordId` is still skipped, and the buffer cap is still enforced one record at a time. And a run too large for one segment is split across turns, so a `Failure` means "stop", not "none of it landed" — earlier records in the run may already be durable.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleBulkExport -->
```kotlin
val pending: List<LogRecord> = drainedFromSomeQueue()
when (val result = exporter.export(pending)) {
    ExportResult.Success -> Unit // every record in the run is now durable
    is ExportResult.Failure -> {
        // The store refused. Earlier records in the run may already be durable — a run
        // too large for one segment is split across turns — so this is "stop", not
        // "none of it landed".
        println("export failed: ${result.cause}")
    }
}
```

**Intent:** stamp which session / game / request / screen a log line belongs to — an MDC equivalent, a "logger context". Don't keep a mutable global holding "the current session" for a `CaptureConfig.attributeMapper` to read.
**Primitive:** `withLogContext(attributes) { … }` (`:kuilt-otel-logging`).

`CaptureConfig.attributeMapper` is installed once on the whole **process's** capture edge, so it has one answer to "which session is this?". An app that runs two at a time — a server-mediated game alongside an offline mesh one — therefore stamps the second one's lines with the first one's id, and no downstream filter can tell: selecting on `session.id` hands back records that never belonged to it. Edge resolution does not help, and this is the distinction worth holding on to — resolving the mapper at the emit edge (#1630) fixes *when* it is asked, not *which* of the concurrent sessions it is able to see. Keep the mapper for facts that really are app-wide (device, build, logger name).

Precedence is one rule at every level, **narrower scope wins**: mapper < outer `withLogContext` < inner. Nesting merges rather than replaces, so a key only an outer scope set is inherited, and leaving an inner scope restores the outer binding. The direction is what makes it a fix — entering the emitting session's scope must *correct* a stale app-wide stamp, not lose to it. The consequence to know: a scope attribute also beats that key in the mapper's output, including one the mapper derived from the log call's own payload.

Reach is exactly `withActiveTrace`'s, and for the same reason — the capture edge is a non-`suspend` callback, so it reads an execution-local slot rather than the coroutine context. **The guarantee is not the same on every platform, and the weaker half is easy to over-trust.**

On **JVM/Android** a `ThreadContextElement` re-establishes that slot on every dispatch, so the binding survives thread hops, is inherited by child coroutines, and keeps two **interleaved** sessions apart. "Enclosing" there means the true structural parent, read from the coroutine context.

On **iOS/macOS/wasmJs** that primitive does not exist (coroutines 1.11.0), so the slot is set once on entry and the binding is reliable only for a line logged **synchronously within the block**. A scope that suspends and resumes while a sibling scope is mid-block on the same thread **reads the sibling's attributes** — a real mis-attribution, not a dropped stamp — and an app running concurrent sessions on `Dispatchers.Main` is exactly that shape. Relatedly, "merged over the enclosing binding" degrades to "merged over whatever the thread last set", so a sibling's keys can be inherited into this scope (this scope's own keys still win). Still a strict improvement on the process-global mapper, which is wrong for every line of every non-armed session — but an improvement, not a guarantee. Keep a session's logging synchronous within its block there; the gap is tracked in [#2569](https://github.com/tractat-us/kuilt/issues/2569).

<!-- verbatim from kuilt-otel-logging/src/commonSamples/kotlin/us/tractat/kuilt/otel/logging/Samples.kt#sampleWithLogContext -->
```kotlin
val log = KotlinLogging.logger("com.example.Session")

// This process runs two sessions at once. A CaptureConfig.attributeMapper is
// installed on the whole process, so it could only ever stamp whichever session
// is "current" — and would stamp the other session's lines with it too. Binding
// the id to the scope that emits makes it per-emitter instead.
withLogContext("session.id" to "server-game-42") {
    log.info { "dealt the opening hand" } // session.id = server-game-42
}

// Concurrently, on another scope, with its own binding. Neither borrows the
// other's id, however they interleave.
withLogContext("session.id" to "mesh-7") {
    // Nesting merges, and the inner scope wins a collision — narrower scope wins.
    withLogContext("turn" to "3") {
        log.info { "peer joined" } // session.id = mesh-7, turn = 3
    }
}

// Outside any scope, capture is exactly what it was before.
log.info { "background heartbeat" }
```

**Intent:** empty a telemetry store — "reset the logs", "clear my data", "start the next run clean". Don't delete the store's files per platform, and don't set a "clear on next launch" flag so the delete lands before recovery.
**Primitive:** `WarpTelemetry.clear()` (`:kuilt-otel`), or a single signal's own `clear()` on `WarpLogRecordExporter` / `WarpSpanExporter` / `WarpMetricExporter`.

It runs on a **live** instance — no restart — and the same instance keeps exporting straight afterwards; a later restart sees only what was written after the clear. That is the point: a `DurableStore` has no key-enumeration API, so a consumer holding one cannot discover the segment keys to delete them, which is what forced the per-platform directory delete this replaces (#2208).

Logs and spans **suppress** what they drop rather than merely forgetting it, so a peer still holding the pre-clear ops cannot push them back through a merge. Metrics can only forget **locally** — a monotonic join has no merge-safe forget, so merging with a peer that still holds the old totals restores them. On a replica that does not gossip its metrics, that distinction never arises.

<!-- verbatim from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetryClear -->
```kotlin
when (val result = telemetry.clear()) {
    is ExportResult.Success -> println("store emptied; the same instance keeps exporting")
    is ExportResult.Failure -> println("clear failed: ${result.cause}; retry converges")
}
```

**Intent:** know whether telemetry is still being written at all — "has anything landed since launch?", "are we losing log lines?" — instead of keeping your own flag or counter beside the exporter.
**Primitive:** `WarpLogRecordExporter.health` (`ExporterHealth`, `:kuilt-otel`) and `LogCaptureInstallation.health` (`CaptureHealth`, `:kuilt-otel-logging`). Both are `StateFlow`s, so read a point-in-time answer or collect and alarm on a stall.

A failed durable write returns `ExportResult.Failure`, but on the logging path every caller discards it — the per-platform appender signatures return `void`. A device therefore stopped accepting telemetry and stayed that way for hours with nothing written and nothing logged (#1860); these counters are the out-of-band answer. `ExporterHealth.isDead` already derives "nothing accepted since process start" — there is deliberately no timestamp, because an exporter holds no `Clock` and a wall-clock read does not belong on the export path.

Read the two together. `CaptureHealth.droppedEvents` climbing while the exporter is healthy is not a broken export path — it is a bounded queue shedding its oldest events because the app logs faster than the drain exports (#2124), which is what the queue is for.

## Reporting a failure that will be retried

**Intent:** log something that has gone wrong on a path that will simply try again — a durable write the store refused, a delete that keeps failing, a send that keeps bouncing. Don't put `logger.error(cause) { … }` in the failure arm and leave it there.
**Rule:** report the failure that **opens** the outage; stay quiet until the thing works again.

A retried failure is reported once per *attempt*, and the retry decides how many attempts there are — so one unchanging condition becomes unbounded log volume. That is not a hypothetical tidiness point. A quota-bound `IndexedDbDurableStore` refuses every write; the exporter above it retries on the next export; the result was measured on three separate exporters in this repo at **one line, and one stack trace, per export, forever** — 300 of each over a 300-export outage. It cost real time on Apple targets, where every trace is symbolicated, and it silently destroyed test results on wasm, where the volume walked a class's output past the harness's 1 MB-per-message ceiling — past which it drops the class and **exits 0**.

Latch the outage, and let the *success* arm clear it.

The failure arm reports only when it *wins* the latch, and returns the failure either way:

<!-- verbatim from kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt#durableWriteFailed -->
```kotlin
private fun durableWriteFailed(cause: Throwable, report: (Throwable) -> Unit): ExportResult {
    if (durableWriteOutage.compareAndSet(expect = false, update = true)) report(cause)
    return ExportResult.Failure(cause)
}
```

The success arm clears it, and every durable write must call this — including the ones you think of as rare:

<!-- verbatim from kuilt-otel/src/commonMain/kotlin/us/tractat/kuilt/otel/WarpSpanExporter.kt#durableWriteSucceeded -->
```kotlin
private fun durableWriteSucceeded() {
    durableWriteOutage.value = false
}
```

Four things decide whether this is a fix or a worse bug.

**The key must cover exactly the population the line is about.** The tempting key is a counter you already keep — a health streak, "consecutive failures", a last-error field. It is almost always *wider* than the set of failures this particular line reports, and then a member of the difference opens the latch first and the outage is reported **zero** times instead of once, with the log pointing at whatever failed earlier. That is strictly worse than the noise it replaced, and it is what happened here on the first attempt. A private latch owned by the one function that performs the retried operation makes the two populations the same set by construction. Where two operations really are one condition — five metric kinds writing five keys in one store — share a latch; where they are not — a refused *delete* says nothing about whether a *write* would land — keep them apart.

**A boolean is the wrong latch as soon as one success does not prove the next attempt will land.** Ask what a *partial* recovery looks like. If each attempt targets one resource — one store key, one endpoint, one peer — a backend that refuses one while accepting another makes a boolean **alternate**: the refused one opens it, the accepted one clears it, the refused one reports again. That is the original defect back at a workload-dependent constant, under a comment still promising "once per outage". Hold the **set of things currently failing**, report on `empty → non-empty`, and remove only the one that succeeded. A boolean is safe only where a turn's operations are grouped so that a partial refusal fails the whole turn. And this is not exotic: an `IndexedDbDurableStore` under quota pressure refuses **large** writes while small ones succeed, so a big blob and a small counter alternate by construction.

**Clearing it must be unconditional.** Set the latch with a CAS so exactly one racing caller reports; clear it with a CAS loop that retries until it lands. A lost update on the failure side costs one duplicate line, which is honest. A lost update on the success side leaves the latch stuck against a healthy backend, which silences the *next* outage entirely.

**Deduplicate the line, never the result.** Every failure still comes back to the caller carrying its cause, so a programmatic reader loses nothing — only the log gets quieter.

Keep the throwable on the once-per-outage line: at one line per outage the trace is affordable, and a store rejecting the application's own data is not routine. Drop it (interpolating `"…: $cause"` so the type and message survive) only where the failure is *both* routine and high-multiplicity — a per-segment sweep of superseded garbage that is retried on every pass, where the count is `Θ(passes × ledger)` rather than one.
