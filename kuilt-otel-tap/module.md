# Module kuilt-otel-tap

**Pull the logs off a device by joining it as a peer.**

You are debugging an app on a phone or a simulator and you cannot get at its
logs. This module lets a test or CI process **connect to the running app and
read its logs out** — the same logs the app already keeps in its own
offline-first buffer, now reachable from your machine.

## How it works, plainly

The app turns the tap on with a single call. From that moment it quietly offers
its captured logs to any peer that joins. A test or harness joins and either
takes a one-shot snapshot of everything so far or watches the logs stream in
live. What comes out matches what the device recorded — in order, with nothing
repeated — even if the connection drops and comes back.

Two ends:

- **On the device:** [installLogTap][us.tractat.kuilt.otel.tap.installLogTap]
  hosts a small session and continuously offers the app's log buffer. It does
  **nothing** until you call it, and binds only the local loopback interface by
  default, so turning it on is safe.
- **In the test/harness:** [LogTapClient][us.tractat.kuilt.otel.tap.LogTapClient]
  joins that session and exposes `pull()` (a snapshot) and `tail()` (a live
  stream) of the records.

## Quick start

Host the tap on the device, then join and pull the backlog:

```kotlin
@sample us.tractat.kuilt.otel.tap.sampleLogTapHostAndPull
```

Or stream the logs live as they are captured:

```kotlin
@sample us.tractat.kuilt.otel.tap.sampleLogTapTail
```

## Why it is correct, deeper down

The device already stores its logs as a conflict-free replicated value (an
ordered `Rga` of log records). Extraction is therefore not a fragile log-shipping
problem — it is ordinary CRDT replication over a kuilt fabric, driven by the
`kuilt-quilter` replicator. Replication is idempotent and order-preserving by
construction, so a puller that reconnects re-merges without ever double-counting
or losing a record.

The tap is fabric-agnostic: it takes a `Loom`/`Seam`, so the same code reaches a
simulator over a loopback WebSocket or a real phone over a LAN or peer-to-peer
fabric — the fabric is a configuration choice, not a code change.
