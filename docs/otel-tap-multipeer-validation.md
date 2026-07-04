# Multipeer reach — manual validation

The Apple-native encrypted reach path (`installMultipeerLogTap` /
`installMultipeerMetricTap` in `:kuilt-otel-tap`) can only be verified end to end on
**two physical Apple devices** — an iPhone and a Mac on the same local network. iOS
Simulators do not do Multipeer Connectivity reliably, so this is a human step that CI
and unit tests cannot cover.

Automated Apple-target tests (`MultipeerLogTapReachTest` in `:kuilt-otel-tap`'s
`appleTest`) already cover everything expressible over an **injected in-memory fabric**:
the admission gate composing (a valid code pulls in order, a wrong code never converges),
the gated pull re-merging without gaps or duplicates when a puller drops and a fresh one
rejoins, the metric buffer round-tripping, and the Multipeer fabric linking into the Apple
variants. Each checklist item below is tagged **[automated]** or **[manual]** accordingly.
The **[manual]** items are what genuinely needs real hardware — a real Multipeer transport
between two devices, and the packet-capture proof that the wire carries only ciphertext.

## Topology

The iPhone **hosts** the tap and advertises itself over Multipeer (no role
inversion — unlike the WebSocket path, an iPhone can advertise natively). A **Mac**
discovers it, joins, and pulls. A Mac must be the puller because Multipeer is
Apple-only; there is no JVM/CI puller on this path.

## Steps

Setup (both **[manual]**): construct a `MultipeerPeerLinkFactory(displayName, serviceType)` on
the **iPhone** and call `installMultipeerLogTap(factory, exporter, scope, admission =
LogTapAdmission.Verify(token, clock, cryptoRandom()))`, surfacing the join code out-of-band (a
pairing screen or a `println` to the Xcode console); on the **Mac**, discover the iPhone over
Multipeer, join its session, and construct a `LogTapClient(seam, scope, admission =
LogTapAdmission.Present(code))` with the code shown on the phone. The discovery and real
Multipeer transport are what only two devices can exercise.

- [x] **[manual]** Confirm `client.pull()` returns the iPhone's captured log records, in order,
      with no duplicates. *(The pull ordering + admission logic itself is **[automated]** over an
      injected fabric by `gatedTapComposesOverAnInjectedFabricOnApple`; only the real Multipeer
      transport is manual.)*
- [x] **[manual]** Confirm a **wrong** code never converges the pull (it times out) — admission
      control still holds over the encrypted link. *(**[automated]** over an injected fabric by
      `wrongCodeNeverConvergesThePullOnApple`; only the real transport is manual.)*
- [x] **[manual]** Repeat with `installMultipeerMetricTap` + `MetricTapClient` and confirm the
      iPhone's converged metric buffer pulls across. *(The metric round-trip is **[automated]** over
      an injected fabric by `metricTapRoundTripsOverAnInjectedFabricOnApple`; the metric path carries
      no join code — confidentiality is the fabric's — so there is no wrong-code variant to check.)*
- [x] **[manual]** Confirm the transport is **encrypted end to end**: the underlying `MCSession`
      is created with `encryptionPreference = MCEncryptionRequired`. Verify no plaintext log/metric
      bytes are observable on the wire (e.g. a packet capture on the shared network shows only
      encrypted Multipeer traffic, not readable log bodies). *(Genuinely manual — no in-memory fabric
      can prove a wire is ciphertext.)*
- [x] **[manual]** Confirm reconnect behaviour: drop and re-establish the Multipeer link
      mid-session; the pull re-merges without gaps or repeats. *(The re-merge invariant — a rejoining
      puller re-admits through the gate and reconstructs the same sequence with no gap or repeat — is
      **[automated]** by `gatedReconnectReMergesWithoutGapsOrDuplicatesOnApple`, which models the
      rejoin as a client close + fresh join over the injected fabric. Only the real DTLS session
      tearing and healing is manual.)*

## Validated

2026-07-03, iPhone 17 Pro (iOS 26.5.1) hosting, MacBook (macOS 26.5.1) pulling, same LAN.
Both taps discovered and joined over real Multipeer; the reconnect check forced a real link
drop by toggling Wi-Fi off/on on the phone mid-session (host left running throughout) and a
fresh Mac-side join converged cleanly afterward. The encryption check captured the live
session's traffic (`en0`, ~300 packets during a pull) and grepped the raw payloads for every
plaintext marker the test data would produce (log bodies, the metric value, both join codes)
— zero hits, all payload bytes high-entropy, consistent with `MCEncryptionRequired`.

One discovery-side gotcha worth recording for whoever builds the next manual harness: the
`MultipeerServiceBrowser.discoveries()` Flow must stay **collected** across the `join()` call
itself — cancelling it beforehand (e.g. via `Flow.first()`) tears down the native browser
before `join()` can use it and fails with `mc_runtime session open failed`. Keep the browse
collector running (e.g. into a `CompletableDeferred`) until after `join()` returns, matching
`MultipeerCrossProcessProbe.runJoinFirst`'s existing pattern.
