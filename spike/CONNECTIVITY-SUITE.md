# kuilt-nw connectivity suite — field guide (#1467)

Two phones, no Mac. One person taps **Host**, the other taps **Join**. The app runs a five-scenario
battery against the real `kuilt-nw` fabric, shows a pass/fail matrix, and produces a plaintext report
you **Share** or **Copy** and text back. The point: reproduce the adverse-network failures (coffee-shop
captive Wi-Fi, airplane, Wi-Fi-off/cellular-on, two SSIDs) *where they actually happen*.

It works. Its first real two-phone run found a bug that made roughly **one room in eight permanently
unable to connect** — silently, with no error — that every automated test had passed straight over.
[What it found](#what-it-found) tells that story; it is the best argument for running this in odd places.

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
| 4 | Teardown + reconnect | The host drops the link. The **host** sees its own `close()` latch `Torn`; the **joiner** sees the recoverable `Woven → Weaving` re-form ([why they differ](#scenario-4-the-two-sides-expect-different-things)). Both then re-weave on a second service type. |
| 5 | Soak (~2 min) | Continuous round-trip stays healthy: RTT distribution (min/p50/p95/max) with few/no stalls. This is where an AWDL data-path stall (the MC failure mode) would show up as a FAIL. |

Every report is prefixed with the **environment** captured from `nw_path_monitor`
(`path=satisfied ifaces=[wifi,cell,…] expensive=… constrained=…`) so a failing report is
self-describing — you can read "join FAILed with Wi-Fi off, path unsatisfied" without a Mac.

### Scenario 4: the two sides expect different things

This trips people up, so it is worth stating plainly. When the host drops the link, the two peers are
contractually *required* to observe different things (`NwSeam`, "Peer loss is recoverable — re-form,
don't tear", #1513):

- **Host** — its own explicit `close()` latches `SeamState.Torn`.
- **Joiner** — losing its last remote is **not** terminal. The seam goes `Woven → Weaving`, resets
  `peers` to `{selfId}`, keeps `incoming` open and waits for a redial. `Torn` latches on *only* an
  explicit consumer `close()` or the initial `weave` timeout; it "is never a consequence of peer loss".

The suite originally asserted the joiner would see `Torn` — an expectation written before #1513 — so it
could only ever time out. Nobody noticed for a long time because a *different* bug (#1577) was failing
leg 1 first, so the teardown path was never reached at all. If you change scenario 4, read the `NwSeam`
contract first.

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
[4] Teardown+reconnect     PASS     4.1s  peer-loss seen; re-wove in 1.3s
[5] Soak 120.0s            FAIL   121.0s  n=90 p50=31ms p95=410ms stalls=7
----------------------------------
· [5] Soak 120.0s
    weave svc=_ksuite5._tcp; soaking 120.0s
    wove peers=2
    n=90/480 rtt min=22 p50=31 p95=410 max=980 stalls=7
```

The matrix is the headline; the per-scenario **hop traces** below it name the failing hop.

## Diagnostic mode — when a scenario fails and you need to know *why*

Two extra buttons run **scenario 4 alone**: **Host · S4 only** / **Join · S4 only**. Use them when the
pass/fail matrix isn't enough. They do two things a normal run doesn't:

1. **Isolate.** Scenario 4 runs in a pristine process, with no earlier scenario having left a Bonjour
   listener or browser alive. That separates "this scenario is broken" from "something earlier
   contaminated it" in a single run — a distinction otherwise very hard to make in the field.
2. **Turn on the fabric's own logging.** kuilt-nw already logs its whole dial/connection path
   (`nw.loom.weave`, `nw.api.connect.dial`, `nw.api.state`, `nw.api.state.error` with the decoded
   `nw_error` domain/code, `nw.api.close`). Diagnostic mode routes it to **stdout** at DEBUG so a
   cabled console run captures it.

   The default Darwin logger writes to os_log, which `devicectl --console` does **not** capture — which
   is why an ordinary run shows zero `nw.*` lines. Diagnostic mode switches to `DirectLoggerFactory`.

### The discovery watcher — read this line first on any weave failure

Every weave in scenarios 2 and 4 is wrapped so the **discovery boundary** stays visible even when the
weave times out and throws:

```
  leg1 disco t=826ms  n=1 ids=[kuilt-suite]
  leg1 disco t=3737ms n=2 ids=[kuilt-suite,kuilt-suite (2)]
leg1 discovery: events=3 finalSeen=2 ids=[…] elapsed=45011ms
leg1 weave THREW after 45011ms: NwUnreachableException: no peer reached …
```

That summary line splits the search space in half:

- **`events=0 finalSeen=0`** → the peers never saw each other. Browse/advertise never met — Bonjour,
  AWDL, Local Network permission, or a lingering listener.
- **`finalSeen ≥ 1` but the weave still timed out** → discovery is fine; the failure is **connection
  establishment** (TLS-PSK handshake / transport). Read the `nw.api.state.error` codes next.

Why it's needed: `weave()` waits for `seam.peers.size > 1` — a *fully connected* peer, not a discovered
one — so "no peer reached" covers both classes and cannot distinguish them on its own. The traces are
drained into the report on **both** the success and the throw path; before that existed, a timed-out
weave discarded every clue and the report showed two useless lines.

Seeing two endpoints (e.g. `kuilt-suite` and `kuilt-suite (2)`) is normal, not a fault: both phones
advertise the same Bonjour instance name, so mDNS conflict-renames one, and each phone discovers its own
record alongside the peer's.

### `nw_error` codes you will actually meet

`nw.api.state.error` prints the decoded domain and OSStatus:

| Code | Name | Means |
|------|------|-------|
| `-9864` | `errSSLUnknownPSKIdentity` | The acceptor didn't recognise the presented PSK identity. |
| `-9858` | `errSSLHandshakeFail` | Identity accepted; the handshake collapsed later. |
| `-9848` / `-9854` | `errSSLBadConfiguration` / `errSSLConfigurationFailed` | NW rejected the TLS options before any handshake. |
| `-9816` | `errSSLClosedNoNotify` | Peer vanished without a close_notify — often the *other* side of a failure. |

## Mac-tethered runs (optional)

The field path needs no Mac. For bench work, the launch arguments auto-start the battery headlessly and
the app prints its report to stdout:

```bash
BID=us.tractat.spike.nw
xcrun devicectl list devices                       # get the device identifiers
xcrun devicectl device process launch --terminate-existing --console \
  --device <HOST_DEVICE_ID> $BID host              # host | join | host-s4 | join-s4
```

Launch both within a few seconds of each other so they overlap. `harness.sh <HOST_ID> <JOIN_ID> <APP>`
wraps install + launch + report extraction for the full battery.

`devicectl --console` rides Wi-Fi for network-attached devices, so a Wi-Fi-off scenario goes dark on
that device — keep at least one on USB if you want live console (see `PAINPOINTS.md`).

## What's real vs. control

- **Scenarios 2–5 drive the shipping API** — `appleNwLoom`, `SeamRoomFactory.electLobby`,
  `ElectionLobby.start`/`awaitRoom`, `Seam`/`Room` lifecycle. A FAIL here is a real `kuilt-nw`/
  `kuilt-session` field failure.
- **Scenario 1 is the raw transport control** (`spike.nw.SpikeNw`) — if it passes but scenario 2 fails,
  the fabric layer is at fault, not the radio.

## Building

Kotlin (the Mac-free gate):

```bash
./gradlew -PincludeSpike :spike:compileKotlinIosArm64
```

Build and install on devices, from a worktree of this repo:

```bash
./gradlew -PincludeSpike :spike:linkDebugFrameworkIosArm64   # produces SpikeKit.framework
cd spike/app && xcodegen generate                            # regenerate SpikeNw.xcodeproj
xcodebuild -project SpikeNw.xcodeproj -scheme SpikeNw -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath /tmp/spike-dd -allowProvisioningUpdates build
xcrun devicectl device install app --device <DEVICE_ID> \
  /tmp/spike-dd/Build/Products/Debug-iphoneos/SpikeNw.app
```

The SwiftUI target links the `SpikeKit` framework Gradle produces, via `FRAMEWORK_SEARCH_PATHS`. An IDE
"No such module 'SpikeKit'" warning before the first Gradle link is expected — `xcodebuild` resolves it.

**Every Bonjour service type the app browses or advertises must be declared in `NSBonjourServices`** in
`spike/app/project.yml` — xcodegen writes `Info.plist` from it, so edit the yml, not the plist. iOS
silently blocks discovery for undeclared types: no error, just nothing found. Verify the built bundle
with `plutil -extract NSBonjourServices xml1 -o - <path>/SpikeNw.app/Info.plist`.

## What it found

The first real two-phone run failed scenario 4 with a 45-second timeout on `_ksuite4a._tcp`, while
scenarios 2, 3 and 5 wove in under a second on their own service types.

The discovery watcher showed both phones finding each other in ~1s, so discovery was fine. The fabric
log then showed every inbound connection failing TLS with `-9864 errSSLUnknownPSKIdentity`. That traced
to `NwPsk.derive` handing Apple's external-PSK path a **raw 32-byte HMAC output** as the PSK *identity*.
That path is C-string-based, so an embedded `0x00` truncated the identity and no handshake could match.

A random 32-byte identity contains a NUL about **11.8%** of the time, so ~1 room in 8 could never
connect — and `weave()` reported only "no peer reached". Every `(roomKey, serviceType)` pair committed to
the repo happened to derive a NUL-free identity, so CI stayed green throughout. Fixed in #1577 by
hex-encoding the identity (RFC 4279 §5.1 requires the identity be a UTF-8 character string), with a
loopback regression test that reproduces the whole thing with no hardware.

Three things worth keeping from that hunt:

- **A suite whose fixtures are all lucky proves very little.** The regression test pins a *property*
  across many derived inputs rather than one golden vector.
- **Instrument the boundary before hypothesising.** The first two explanations were both wrong (the
  wrong leg, then cross-talk from a leaked listener); the discovery line and the isolation mode
  falsified each in one run.
- **Fixing one bug can expose the next.** With leg 1 finally establishing, scenario 4's stale `Torn`
  expectation surfaced immediately — it had never been reachable before.

## Status

On-device validation is **done**: 5/5 on both phones (iPhone XS / iOS 18.7.9 and iPhone 17 Pro /
iOS 26.5.1) on infrastructure Wi-Fi, including teardown + reconnect and the 2-minute soak with zero
stalls. The adverse-network matrix the suite was built for — captive-portal Wi-Fi, airplane mode,
Wi-Fi-off/cellular-on, two SSIDs — is still worth walking through; that is what it is for.
