# Module kuilt-nw

Lets nearby iPhones and Macs talk to each other directly — no server, and no
shared Wi-Fi network needed. Put a few devices in the same room and they find
each other and start exchanging messages on their own.

Under the hood it is a full-mesh `Loom`/`Seam` fabric built on Apple's
Network.framework: every peer advertises, browses, and dials, so each pair forms
a direct point-to-point link and the redundant double-dial is deduplicated into
one connection. It replaces Multipeer Connectivity, whose AWDL teardown
regressed on iOS 26.

## Starting a session

One device hosts, the others join. Everyone shares a short **code** ahead of
time — through a QR image, a spoken word, a chat message, anything outside this
fabric. That code is the session's password.

```kotlin
// Host device:
val seam = nwHost(Pattern(sessionName = "kitchen-game", roomKey = code), "_kuilt._tcp")

// Joining device (given the same code out of band):
val seam = nwJoin(NwTag("kitchen-game", peerKey = myId, roomKey = code), "_kuilt._tcp")
```

Both calls return a `Seam` that is already connected to the other peers, ready
to exchange frames.

## Security — the code encrypts the link

The code you share is never sent over the air. It is run through HKDF to derive
a TLS pre-shared key, and every link is a TLS PSK connection. So the code is
a **bearer secret**: anyone who has it can join and read the session's traffic,
and anyone who doesn't cannot connect at all. It is therefore *required* —
`nwHost`/`nwJoin` throw if `roomKey` is null rather than quietly opening an
unencrypted session — and it doubles as the session boundary: two groups using
the same Bonjour service type but different codes derive different keys, so their
meshes can never merge.

`SeamCapabilities.securesTransport` is `true` for this fabric, proven by the
Apple nightly lane's loopback TLS-PSK conformance run (a scheduled macOS build —
out of band, **not** the per-PR `ci-required` check, which is Linux-only and
cannot execute the Apple test binaries). Use a **high-entropy** code (≥128-bit
random, carried via QR/link) where you can: a short human-typed code is guessable
offline from a single captured handshake — a proper fix (a PAKE) is future work.

## Consuming apps must declare (iOS/macOS)

Network.framework's local-network and Bonjour access is gated by Info.plist keys
the host app supplies:

- `NSLocalNetworkUsageDescription` — a human-readable reason string (shown in the
  iOS Local Network permission prompt).
- `NSBonjourServices` — an array listing every service type you advertise/browse,
  e.g. `<string>_kuilt._tcp</string>`.

Without these the OS silently blocks discovery.

## Running it from a macOS desktop JVM

The fabric is not iOS/macOS-native-only: a **macOS-arm64 JVM** can host and join too, via the same
`nwHost`/`nwJoin` calls. On the JVM they bridge over JNA to a bundled `libkuilt.dylib` (the module's
own `macosArm64` shared library) that drives the real Network.framework binding. The key derivation
(HKDF) runs JVM-side; only the derived key bytes cross into the native library.

This is **macOS-arm64 only**. On any other JVM (Linux, Windows, Intel Macs) the dylib does not load,
`availability()` reports `Unavailable`, and `nwHost`/`nwJoin` fail fast with an actionable message —
use the mDNS/WebSocket fabrics for cross-platform LAN there. Probe `NwNativeLib.jvmAvailability()`
first if you need to branch gracefully.

## When two devices see each other but never connect

Both phones are in the lobby, the signal is strong, and no session ever forms. Nothing crashes and
nothing is logged as an error, because from each device's point of view it is simply still trying.

Pull the log off **both** devices and read these lines, in this order. Every one of them is `INFO` or
above, so they survive a normal on-device capture — but only if the app's logging backend is left at
`INFO`; a backend pinned to `WARN` drops most of the trail. Note that the fabric identifies peers by
the `PeerId` in the Bonjour TXT record while it *dials* a Bonjour **name**, and mDNS re-resolves that
name at connect time — so "who we meant to reach" and "who answered" are separate facts, and the log
prints both.

| Line | What it settles |
|---|---|
| `nw.loom.name-collision` | Another device is advertising the same Bonjour name as this one. This is the root condition of #2416 — every dial to that name is a coin flip. If it appears, stop here; the rest is consequence. |
| `nw.api.browse-result` | What discovery actually saw: the advertised `name`, the TXT id (`txt=ABSENT` means identity has not resolved yet), and the id the endpoint was keyed under. |
| `nw.loom.self-skip` / `nw.loom.discovered` / `nw.loom.identity-deferred` | The verdict per sighting — filtered as self, armed for a dial, or held back until identity resolves. |
| `nw.api.retain-start` | The dial itself: which connection id (`nw-N`) was minted **for which endpoint**. This is the join key between a dial and every later line about that connection. |
| `nw.seam.resolved.first` / `nw.seam.self-connection` | Who answered. Compare `dialled=` against `remote=`: equal is healthy, different means the name resolved to another device. |
| `nw.seam.dialled-mismatch` | Fired when they differ on the success path — a peer's endpoint has been recorded under another peer's id. |
| `nw.seam.settled` | The set of endpoints that need no further dial, with `(self)` or `(peer=…)` provenance for each. An endpoint listed `(self)` that is really another device is the failure. |
| `nw.loom.redial-parked` | This endpoint's dials have stopped. Paired with the `nw.seam.settled` line above it, this says which peer is starved and why. A `redial-parked` with no matching `redial-resumed` is a peer that will never be dialled again for the seam's lifetime. |

`nw.loom.weave-timeout` is the *symptom*, not the diagnosis — by the time it fires the decisive
evidence is already minutes earlier in the trail above.

Two related notes. If you are extracting these logs with `:kuilt-otel-tap`, host the tap on a
**different** fabric from the one you are debugging (`installLogTapJoining` over a WebSocket loom,
say) — tapping a broken fabric over itself cannot work. And a capture only contains what the app's
logging backend admitted: turn the level down *before* reproducing, not after.

---

**Source-set wiring note (maintainers).** This module hand-wires the
`appleMain`/`macosMain` (and `appleTest`) source-set intermediates manually,
mirroring `:kuilt-multipeer` — required up front so the first real Apple-only
source added later doesn't trip the Dokka "no source module for appleMain"
gotcha that hits modules relying on the default hierarchy template's auto-wiring.

**What CI proves — and what it doesn't (maintainers).** `NwLoopbackConformanceTest`
runs the full `SeamConformanceSuite` against the real `RealNwApi` over a
`127.0.0.1` link with **TLS-PSK enabled**. It runs on the **scheduled Apple
nightly lane** (`apple-nightly.yml`, a macOS runner) — *not* the per-PR
`ci-required` check, which is Linux-only and skips the Apple test executions. So
a regression here surfaces out of band, not as a blocked PR. That run covers the
whole connection surface: the `sec_protocol_options` PSK handshake, send/receive,
framing, cancel/close plumbing, and the strong-ref registry (whose drain-to-empty
is separately asserted by `NwConnectionDrainTest`). It deliberately does **not**
cover — and nothing below real hardware does — `NWBrowser`/Bonjour discovery,
`includePeerToPeer`, AWDL routing, TLS over a real peer-to-peer path, the
Local-Network-Privacy denial path, or IPv6-required behaviour. Those are proven
only by the Phase-0 on-device spike and the hardware-validation pass.

**The JVM bridge — coverage layers (maintainers).** The macOS-desktop JVM path
(`BridgeNwApi` over JNA → `libkuilt.dylib` → `RealNwApi`) is proven in three
layers, none of which alone is enough: (1) `BridgeNwApiTest` runs on **every**
runner including Linux `ci-required`, over an in-JVM `FakeNwNativeLib` — it covers
the JVM-side wiring (callback → staging-channel → `SharedFlow` FIFO, result-code
mapping, availability gating, exactly-once teardown) but touches no dylib.
(2) `NwNativeLibTest` gates on `assumeTrue(isMacOs())`, so on a macOS runner it
loads the **real** dylib and exercises the cdecl surface end-to-end — protocol
version, the `StableRef` runtime create/destroy lifecycle, callback registration,
local browse start/stop, and `BridgeNwApi.close()` disposing the real native
handle — while no-opping on Linux. (3) The K/N `NwLoopbackConformanceTest` proves
`RealNwApi` itself over real `127.0.0.1` TLS-PSK. What **no** automated test yet
covers is a full two-`BridgeNwApi` TLS-PSK handshake *through* the JNA boundary
(the bridge builds the P2P/Bonjour `RealNwApi`, not the loopback-configured one, so
a JVM↔JVM loopback would need extra loopback ABI) — that seam is left to the
manual cross-process probe and the hardware pass.
