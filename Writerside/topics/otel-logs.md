# Logs

A log is the simplest kind of note: **a line of text your app writes down as something
happens** — "user signed in", "payment failed: card declined", "cache rebuilt in
1.2s". If you have ever added a `println` to work out what your program was doing,
you have written a log.

Logs are the running diary of your app. When something goes wrong on a real person's
device, the logs are usually the first place you look.

## Record a log

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetry -->

```kotlin
telemetry.logs.export(logRecord)   // one line of text
```

Each line finishes the moment it is written to the device — the same as traces and
metrics — and is delivered later with no duplicates. That whole journey is on
**[Device to dashboard](observability.md)**.

## Starting over with a clean slate

Sometimes you want to throw away everything the app has written down and start fresh — a
tester finished a run, someone hit "clear my data", or you simply want the next recording
to start empty. You can do that while the app is still running:

<!-- condensed from kuilt-otel/src/commonSamples/kotlin/us/tractat/kuilt/otel/Samples.kt#sampleWarpTelemetryClear -->

```kotlin
telemetry.clear()   // everything written down so far is gone
```

No restart, and nothing to hunt down on disk. The same `telemetry` keeps working
immediately afterwards — anything recorded from here on is kept, and a later restart sees
only that.

One honest caveat, if this device shares its data with others. Notes and timings are
forgotten in a way the other devices respect, so a device you sync with later cannot
hand them back. **Counts and totals are different**: they can only be forgotten on *this*
device, and syncing with a device that still remembers them brings the old totals back.
That is a property of how mergeable counters work rather than something left unfinished —
on a device that never shares its metrics, the distinction never comes up.

## Logs you already write

Your app almost certainly writes logs already, through a logging library. You do not
have to rewrite any of that to get those lines into the same offline-safe buffer — and
you can then reach into a device you cannot otherwise get to (a tester's phone, a CI
simulator) and pull the logs off from a test. That is its own short how-to:
**[Capturing](log-capture.md)**.
