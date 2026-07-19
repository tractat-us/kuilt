# kuilt-nw connectivity suite — field guide (#1467)

Two phones, no Mac. One person taps **Host**, the other taps **Join**. The app runs a five-scenario
battery against the real `kuilt-nw` fabric, shows a pass/fail matrix, and produces a plaintext report
you **Share** or **Copy** and text back. The point: reproduce the adverse-network failures (coffee-shop
captive Wi-Fi, airplane, Wi-Fi-off/cellular-on, two SSIDs) *where they actually happen*.

## Running it in the field

1. Both phones have the app installed (TestFlight or a cabled install beforehand).
2. Stand in the weird place. Put the phones in the network state you want to test (toggle Wi-Fi,
   airplane mode, join different SSIDs, etc.).
3. On one phone tap **Host**; on the other tap **Join**. Order doesn't matter for scenarios 2–5.
4. Watch the matrix fill in. The soak (scenario 5) takes ~2 minutes, so the whole run is ~3–4 min.
5. When it finishes, tap **Share report** (or **Copy**) and send the text back.

That's it. There is no Mac in the loop.

## What each scenario proves

| # | Scenario | What a PASS means |
|---|----------|-------------------|
| 1 | Raw NW round-trip | Raw Network.framework P2P connects + round-trips (the transport control). Join reports RTT; host confirms it echoed an inbound frame. |
| 2 | Fabric Seam weave | The real `appleNwLoom(...).weave` reaches `Woven` with `peers == 2` — the fabric layer, not just raw sockets. |
| 3 | Election establish | `SeamRoomFactory.electLobby` → the elected host (`min(peerId)`) runs `start()`, the other runs `awaitRoom()`, both adopt a `Room`. This is the `#1466` lobby path. |
| 4 | Teardown + reconnect | The host drops the link; the joiner observes the terminal `Torn` signal; both re-weave and reconnect within the bound (`#1450`). |
| 5 | Soak (~2 min) | Continuous round-trip stays healthy: RTT distribution (min/p50/p95/max) with few/no stalls. This is where the AWDL data-path stall (the MC failure mode) would show up as a FAIL. |

Every report is prefixed with the **environment** captured from `nw_path_monitor`
(`path=satisfied ifaces=[wifi,cell,…] expensive=… constrained=…`) so a failing report is
self-describing — you can read "join FAILed with Wi-Fi off, path unsatisfied" without a Mac.

## Reading the report

```
kuilt-nw connectivity suite
role=join  passed=4/5
env: path=satisfied ifaces=[wifi,lo] expensive=false constrained=false
device: Version 18.5 (Build 22F76)
----------------------------------
[1] Raw NW round-trip      PASS    412ms  RTT=28ms
[2] Fabric Seam weave      PASS     1.2s  peers=2 Woven
[3] Election establish     PASS     0.9s  adopted as member
[4] Teardown+reconnect     PASS     3.4s  Torn seen; re-wove in 1.1s
[5] Soak 120.0s            FAIL   121.0s  n=90 p50=31ms p95=410ms stalls=7
----------------------------------
· [5] Soak 120.0s
    weave svc=_ksuite5._tcp; soaking 120.0s
    wove peers=2
    n=90/480 rtt min=22 p50=31 p95=410 max=980 stalls=7
```

The matrix is the headline; the per-scenario **hop traces** below it name the failing hop.

## Mac-tethered convenience (optional)

`harness.sh <HOST_DEVICE_ID> <JOIN_DEVICE_ID> <APP_PATH>` installs on two USB/network-attached devices,
launches each with its role, and extracts the verbatim report each prints to stdout. This is only a
convenience for bench runs — the field path is the buttons above. Note `devicectl --console` rides
Wi-Fi for network-attached devices, so a Wi-Fi-off scenario goes dark on that device (keep one on USB);
see `PAINPOINTS.md`.

## What's real vs. control

- **Scenarios 2–5 drive the shipping API** — `appleNwLoom`, `SeamRoomFactory.electLobby`,
  `ElectionLobby.start`/`awaitRoom`, `Seam`/`Room` lifecycle. A FAIL here is a real `kuilt-nw`/
  `kuilt-session` field failure.
- **Scenario 1 is the raw transport control** (`spike.nw.SpikeNw`) — if it passes but scenario 2 fails,
  the fabric layer is at fault, not the radio.

## Building

Kotlin (the hard, Mac-free gate):

```bash
./gradlew -PincludeSpike :spike:compileKotlinIosArm64
```

The app is an xcodegen project under `app/`; the SwiftUI target links the `SpikeKit` framework Gradle
produces (`linkDebugFrameworkIosArm64`). Build/run it from Xcode onto two devices.

> **On-device validation is still required.** This suite compiles and the scenarios are coded against
> the real API, but the two-phone adverse-network behaviour has not been run on hardware — that's the
> whole point of the tool. New Bonjour service types (`_ksuite2._tcp` … `_ksuite5._tcp`) are declared
> in `NSBonjourServices`; iOS silently blocks discovery for any undeclared type.
