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

<!-- verbatim from kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt#sampleLogTapHostAndPull -->
```kotlin
// The device's captured-log buffer — the same one installLogCapture fills.
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("device-uuid-abc123"),
    store = InMemoryDurableStore(),
)

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

// Release both replicators when finished.
client.close()
host.close()
return logs
```

Or stream the logs live as they are captured:

<!-- verbatim from kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt#sampleLogTapTail -->
```kotlin
val loom = InMemoryLoom()
// Join a device that is already hosting a tap and stream its logs live: each
// record is emitted once, in order, as it is captured. The flow replays
// everything already known on collection, then continues with new lines.
val client = LogTapClient(loom.join(InMemoryTag("tailer")), seamScope)
return client.tail()
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

## Reaching a real phone, safely

On a shared Wi-Fi network anyone nearby could otherwise connect and read your
logs. So when you take the tap off loopback, put a **short code** in front of it:
the phone shows a code, and only someone who types that code into the puller gets
in.

- **On the device:** issue a
  [LogTapJoinToken][us.tractat.kuilt.otel.tap.admit.LogTapJoinToken] from a
  cryptographically secure source
  ([cryptoRandom()][us.tractat.kuilt.otel.tap.admit.cryptoRandom]) and pass
  [LogTapAdmission.Verify][us.tractat.kuilt.otel.tap.LogTapAdmission.Verify]. Your app
  shows the code however it likes — a pairing screen, or a `println` to the Xcode
  console it controls. The library **never logs the code** (a secret must not land in
  `os_log`/logcat), so surfacing it is the app's call.
- **In the puller:** pass
  [LogTapAdmission.Present][us.tractat.kuilt.otel.tap.LogTapAdmission.Present] with
  the code you read off the device. A wrong or expired code is refused, and the
  replicator never sees the unauthorized peer.

<!-- verbatim from kuilt-otel-tap/src/commonSamples/kotlin/us/tractat/kuilt/otel/tap/Samples.kt#sampleGatedLogTap -->
```kotlin
val exporter = WarpLogRecordExporter(
    replica = ReplicaId("device-uuid-abc123"),
    store = InMemoryDurableStore(),
)
val loom = InMemoryLoom()

// On the device: mint a short-lived join code from a CRYPTOGRAPHICALLY SECURE source.
// cryptoRandom() is that source — the code is the only secret, so never Random.Default.
val secure = cryptoRandom()
val token = LogTapJoinToken.issue(random = secure, clock = Clock.System)
val host = installLogTap(loom, exporter, scope, admission = LogTapAdmission.Verify(token, Clock.System, secure))

// Show token.code to the operator OUT OF BAND — a pairing UI, or a deliberate println to
// the Xcode console the app controls. The library never logs the code itself.
// e.g. showJoinCodeInDebugUi(token.code)

// In the puller: present the code the device showed. A wrong or expired code is refused.
val client = LogTapClient(
    loom.join(InMemoryTag("puller")),
    scope,
    admission = LogTapAdmission.Present(token.code),
)
val logs: List<LogRecord> = client.pull()

client.close()
host.close()
return logs
```

The code itself never travels the network — the puller proves it knows the code by
answering a one-time challenge, so a passive listener never learns it. The default
[LogTapAdmission.Open][us.tractat.kuilt.otel.tap.LogTapAdmission.Open] keeps the
ungated loopback behaviour unchanged.

### iOS: the phone joins, the laptop hosts

An iOS device can't run a server or advertise itself on the network, so it can't
*host* the session. It doesn't need to: because replication is symmetric, the phone
can **join** a session your laptop hosts and its logs still flow to the laptop. Use
[installLogTapJoining][us.tractat.kuilt.otel.tap.installLogTapJoining] on the phone
(it discovers and joins) while the laptop hosts and advertises.

### iPhone ↔ Mac, encrypted end to end (Apple Multipeer)

On Apple devices there is a stronger path. Apple's Multipeer Connectivity fabric
carries every frame over an encrypted link out of the box, so the logs themselves —
not just *who may pull* — are protected from anyone snooping the network. It also
drops the role inversion above: an iPhone advertises itself natively over Multipeer,
so it simply **hosts** the tap and a nearby **Mac** discovers it and pulls.

- **On the iPhone:** call
  [installMultipeerLogTap][us.tractat.kuilt.otel.tap.installMultipeerLogTap] with a
  [MultipeerPeerLinkFactory][us.tractat.kuilt.multipeer.MultipeerPeerLinkFactory].
  The link is already encrypted; layer a join code on top with
  [LogTapAdmission.Verify][us.tractat.kuilt.otel.tap.LogTapAdmission.Verify] when you
  also want admission control — the *same* fabric-agnostic gate, unchanged.
- **On the Mac:** discover the iPhone over Multipeer, join, and pull with a
  [LogTapClient][us.tractat.kuilt.otel.tap.LogTapClient]. Metrics ride the same
  encrypted fabric via
  [installMultipeerMetricTap][us.tractat.kuilt.otel.tap.installMultipeerMetricTap].

The one trade-off, by design: Multipeer is Apple-only, so a **Mac** must be the
puller — there is no JVM/CI puller on this path. That is why it is the *encrypted
complement* to the mDNS+WebSocket path, not a replacement: reach a simulator or CI
runner over loopback/WebSocket; reach a real iPhone from a Mac over Multipeer.

Real-device transport verification (a Mac pulling an iPhone's buffer over an
encrypted Multipeer link) needs two physical Apple devices and is tracked as a
manual step in `docs/otel-tap-multipeer-validation.md`.

### Debugging a fabric: tap it over a *different* one

If the thing you are debugging is a fabric — two phones that see each other and
never connect — then the obvious call is the wrong one. `installLogTap(brokenLoom, …)`
compiles, reads naturally, and is dead: the transport carrying the logs is the
transport under test. Nothing stops you writing it, so it is written down here.

Make the two disjoint. Tap a **peer-to-peer** fabric (Network.framework/AWDL,
Multipeer, Nearby) over **ordinary infrastructure Wi-Fi**:

- **On the phone:**
  [installLogTapJoining][us.tractat.kuilt.otel.tap.installLogTapJoining] with a
  `KtorClientLoom` — the phone joins a session your laptop hosts.
- **On the laptop:** host it (`KtorServerLoom`) and pull with
  [LogTapClient][us.tractat.kuilt.otel.tap.LogTapClient].

The log path is then a laptop-hosted WebSocket through the access point, and the
fabric under test has no access point in it. Breaking one cannot break the other.
Point both phones at the same laptop and
[pullStamped][us.tractat.kuilt.otel.tap.LogTapClient.pullStamped] total-orders
their records into one timeline — which is what replaces correlating two
separately-extracted stores by hand.

### Three things the tap does not do

Worth stating before you rely on it in an incident.

1. **It moves logs; it does not create them.** It replicates whatever
   `installLogCapture` put in the buffer, and that is gated by your app's logging
   backend *before* this module ever sees the event. A `logger.debug {}` on a
   device configured at `INFO` produces nothing to replicate. On the field wedge
   that motivated this section a real store held 664 `INFO` / 7 `WARN` /
   1 `ERROR` and **zero** `DEBUG` records without having wrapped — so better log
   *transport* would have delivered exactly the same insufficient lines the manual
   pull did. Turn the backend down **before** you reproduce.
2. **It cannot recover what was never captured.** There is no retro-active
   level: a line the backend dropped is gone, and a tap started after the fact
   pulls a buffer that never had it.
3. **It is another peer on the network.** It holds an interface and a
   connection. When the bug is about contention, interface churn, or radio
   scheduling, the tap is part of the system under test — say so in the write-up
   rather than treating it as a neutral observer.

The full procedure, including what to set and in what order, is in
[`docs/log-capture-and-extraction.md`](../docs/log-capture-and-extraction.md).

### The one honest limitation

Over a plain LAN WebSocket the log bytes themselves travel **unencrypted**. The
join code controls *who is allowed to pull* — it does not encrypt the traffic, so
someone already positioned to snoop the network could read logs of a session that
was legitimately admitted. Where that matters, use the encrypted Multipeer path
above (Apple-only), or another encrypted fabric. On the WebSocket path this module's
guarantee is *admission control*, deliberately and only.
