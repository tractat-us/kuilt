# Multipeer reach — manual validation

The Apple-native encrypted reach path (`installMultipeerLogTap` /
`installMultipeerMetricTap` in `:kuilt-otel-tap`) can only be verified end to end on
**two physical Apple devices** — an iPhone and a Mac on the same local network. iOS
Simulators do not do Multipeer Connectivity reliably, so this is a human step that CI
and unit tests cannot cover. The automated build proves only that the Apple variants
compile, link, and that the admission gate composes over an in-memory fabric; the
real transport is validated here.

## Topology

The iPhone **hosts** the tap and advertises itself over Multipeer (no role
inversion — unlike the WebSocket path, an iPhone can advertise natively). A **Mac**
discovers it, joins, and pulls. A Mac must be the puller because Multipeer is
Apple-only; there is no JVM/CI puller on this path.

## Steps

- [ ] On the **iPhone**, construct a `MultipeerPeerLinkFactory(displayName, serviceType)`
      and call `installMultipeerLogTap(factory, exporter, scope, admission = LogTapAdmission.Verify(token, clock, cryptoRandom()))`.
      Surface the join code out-of-band (a pairing screen or a `println` to the Xcode console).
- [ ] On the **Mac**, discover the iPhone over Multipeer, join its session, and construct a
      `LogTapClient(seam, scope, admission = LogTapAdmission.Present(code))` with the code shown on the phone.
- [ ] Confirm `client.pull()` returns the iPhone's captured log records, in order, with no duplicates.
- [ ] Confirm a **wrong** code never converges the pull (it times out) — admission control still holds over the encrypted link.
- [ ] Repeat with `installMultipeerMetricTap` + `MetricTapClient` and confirm the iPhone's converged metric buffer pulls across.
- [ ] Confirm the transport is **encrypted end to end**: the underlying `MCSession` is created with
      `encryptionPreference = MCEncryptionRequired`. Verify no plaintext log/metric bytes are observable on the wire
      (e.g. a packet capture on the shared network shows only encrypted Multipeer traffic, not readable log bodies).
- [ ] Confirm reconnect behaviour: drop and re-establish the Multipeer link mid-session; the pull re-merges without gaps or repeats.

Leave the boxes unchecked until validated on real hardware.
