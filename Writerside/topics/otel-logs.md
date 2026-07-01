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

## Logs you already write

Your app almost certainly writes logs already, through a logging library. You do not
have to rewrite any of that to get those lines into the same offline-safe buffer — and
you can then reach into a device you cannot otherwise get to (a tester's phone, a CI
simulator) and pull the logs off from a test. That is its own short how-to:
**[Capturing](log-capture.md)**.
