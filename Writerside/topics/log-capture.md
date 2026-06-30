# Capturing & pulling logs

Your app is misbehaving on a phone that isn't on your desk — a tester's device, or
an iOS simulator on a CI machine. The lines that would tell you *why* are scrolling
past inside the running app, and you can't get to them: there's no file to open and
no cable long enough.

kuilt closes that gap with two small calls. One **captures** the logs your app
already writes into a safe on-device buffer. The other lets a test or CI job
**reach in and pull them out** — every line, in order, exactly as the app wrote
them. Your logging code does not change at all.

```kotlin
// On the device — the app logs the way it always has:
val log = KotlinLogging.logger("com.example.Checkout")
log.info { "user checked out" }

// In a test or CI job — reach in and take the logs:
val logs = client.pull()        // every line the device buffered, in order, no repeats
```

This page is the practical how-to. For the story of *why* it works — and why it's
two calls and not a project — read
[Reaching into a device for its logs](https://github.com/tractat-us/kuilt/blob/main/docs/log-capture-and-extraction.md).

## Capture: record your existing logs

Call `installLogCapture` once at startup. From then on, every line your app logs
through `kotlin-logging` is also written into a durable, offline-first buffer (a
`WarpLogRecordExporter`). You change nothing at your call sites — `log.info { … }`
stays `log.info { … }`.

<!-- verbatim from kuilt-otel-logging/src/commonSamples/kotlin/us/tractat/kuilt/otel/logging/Samples.kt#sampleInstallLogCapture -->
```kotlin
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("device-uuid-abc123"),
    store = InMemoryDurableStore(),
)

// One call, identical on JVM, Android, iOS, macOS and wasmJs. Time and
// randomness are injected — `Clock.System` and `Random.Default` in production,
// a virtual clock and a seeded RNG in a test.
val installation = installLogCapture(
    exporter = exporter,
    config = CaptureConfig(minLevel = LogLevel.INFO),
    clock = Clock.System,
    random = Random.Default,
    scope = scope,
)

// Your app keeps logging exactly the way it always has — no call-site change.
// Every line at or above INFO now also lands in the buffer.
val log = KotlinLogging.logger("com.example.Checkout")
log.info { "user checked out" }
```

The same call is used on every platform — JVM, Android, iOS, macOS and the
browser. `CaptureConfig(minLevel = …)` is the only filter in this first
milestone: capture keeps **everything** at or above that level, so the one line
you needed is never thrown away by a sampler.

### Stopping capture

`installLogCapture` returns a handle. Closing it is the way to stop capture — it
restores the appender that was there before and stops buffering further lines.

<!-- verbatim from kuilt-otel-logging/src/commonTest/kotlin/us/tractat/kuilt/otel/logging/CaptureEdgeTest.kt#closeRestoresPreviousAppenderAndStopsCapture -->
```kotlin
installation.close()
```

Cancelling the scope alone is **not** enough: that stops the drain but leaves the
capturing appender wired into the global logging config, buffering forever. Hold
the handle for as long as capture should run, then close it.

### Two platform notes

- **iOS / macOS.** Capture still forwards each line to the Apple system log
  (`os_log`), so filtering in Console.app by your app's subsystem and category
  keeps working. kuilt writes the message as an *argument*, never as the format
  string, so a stray `%` in a line (a URL with `%20`, "100% done") renders
  literally and can never trigger the printf-format-string crash.
- **JVM.** Installing capture routes your `kotlin-logging` output through kuilt's
  appender, so while capture is on it no longer flows through SLF4J — your
  logback/log4j formatting and other libraries' raw SLF4J output aren't captured.
  The scope is *your app's own `kotlin-logging`*, identically on every platform. A
  `:kuilt-otel-logback` add-on that captures every SLF4J logger on the JVM is a
  planned, additive option.

## Pull or tail: take the logs off the device

Capture fills the buffer; the **tap** moves it to another machine. Turn it on with
`installLogTap` on the device, then join from a test or harness with a
`LogTapClient` and either `pull()` the backlog once or `tail()` it live.

<!-- verbatim from kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt#sampleLogTapHostAndPull -->
```kotlin
// The fabric the two peers meet over. An in-memory/loopback Loom is the
// simulator-and-CI case; swap it for a LAN (mDNS + WebSocket) or peer-to-peer
// (Multipeer) Loom to reach a real phone — the tap code below is unchanged.
val loom = InMemoryLoom()

// On the device: turn the opt-in tap on. It does nothing until called and is
// loopback-bound by default. Hold the host; close it to stop offering logs.
val host = installLogTap(loom, exporter, scope)

// In the test / CI harness: join the same session and pull the backlog —
// every line the device captured, in the device's order, with no duplicates.
val client = LogTapClient(loom.join(InMemoryTag("puller")), scope)
val logs: List<LogRecord> = client.pull()
```

`pull()` returns a point-in-time snapshot: the whole log so far, in the device's
order, with no duplicates — even across a reconnect. To watch lines arrive as
they're captured, `tail()` instead:

<!-- verbatim from kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt#sampleLogTapTail -->
```kotlin
val loom = InMemoryLoom()
// Join a device that is already hosting a tap and stream its logs live: each
// record is emitted once, in order, as it is captured. The flow replays
// everything already known on collection, then continues with new lines.
val client = LogTapClient(loom.join(InMemoryTag("tailer")), seamScope)
return client.tail()
```

The tap is **opt-in and off by default**, and `installLogTap` binds only the local
loopback interface unless you hand it a wider fabric. That loopback case *is* the
iOS-simulator and CI story: the app hosts on the device's own loopback, your test
joins on the same machine, and `pull()` hands back the log the simulator just
produced. Reach a real phone by changing only the `Loom` you pass — to mDNS +
WebSocket on the same Wi-Fi, or Apple Multipeer over Bluetooth with no network at
all. Nothing else changes.

## In a test or CI job

The whole point is asserting on logs from an otherwise-unreachable device. Join,
pull, and assert — order and de-duplication come for free, so the assertions are
plain equality checks:

<!-- verbatim from kuilt-otel-tap/src/commonTest/kotlin/us/tractat/kuilt/otel/tap/LogTapConvergenceTest.kt#pullReconstructsBacklogInOrderWithoutDuplicates -->
```kotlin
val pulled = client.pull()

assertEquals(sent.map { it.recordId }, pulled.map { it.recordId }, "order preserved")
assertEquals(sent.size, pulled.size, "no duplicates")
assertEquals(sent.map { it.body }, pulled.map { it.body })
```

To dump the logs only when a test fails, wrap the body and `pull()` in the failure
path — for example in a JUnit `@AfterTest`/watcher, or a `try`/`catch` that
prints `client.pull()` before rethrowing. Dedicated test helpers for this
assert-and-dump pattern are landing as a `:kuilt-otel-tap-test` module; until then,
the raw `pull()` above plus your test framework's failure hook is the whole
recipe.

## Going deeper

- **[Reaching into a device for its logs](https://github.com/tractat-us/kuilt/blob/main/docs/log-capture-and-extraction.md)**
  — the full story: how four pieces kuilt already ships become this feature, the
  one place a platform makes you look, and the reach from a simulator to a phone
  across the room.
- **[Observability](https://tractat-us.github.io/kuilt/guide/observability.html)**
  — the offline-first buffer the captured lines land in, shared with traces and
  metrics.
- **[Replicated Data — `Rga`](https://tractat-us.github.io/kuilt/guide/crdt-rga.html)**
  and **[Live Replication — `Quilter`](https://tractat-us.github.io/kuilt/guide/crdt-quilter.html)**
  — the ordered-sequence data type and the replicator that make a resend safe and
  in-order by construction.
- **[API reference](https://tractat-us.github.io/kuilt/api/)** — every type in
  `kuilt-otel-logging` and `kuilt-otel-tap`, with compiled examples.
