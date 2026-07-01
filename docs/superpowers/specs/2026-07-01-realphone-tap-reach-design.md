# Real-phone tap reach — mDNS/Multipeer fabrics + join-token admission

_Design for #1028. Part of the log-capture epic #986 (M2 — "the payoff the epic
was named for"). Builds on M1 (`2026-06-27-log-capture-and-extraction-design.md`)
and the tap itself (`:kuilt-otel-tap`, shipped)._

## What this adds

Today you can pull a device's logs out over a **loopback** WebSocket — great for a
simulator or CI, useless for the phone on your desk. This design gives the tap
**reach**: turn it on, and from your laptop you can discover your **actual iPhone**
on the same Wi-Fi (no IP addresses, no cables) and read its logs live — while
locking the door so a stranger on the coffee-shop network can't do the same.

Two pieces, both **opt-in**, both leaving today's default (loopback-bound, off)
byte-for-byte unchanged:

1. **Reach** — drive the tap's replication `Seam` over a *real-radio* fabric that
   already exists in kuilt (mDNS + WebSocket on a LAN, or Apple Multipeer between
   Apple devices), so a laptop peer finds the device automatically. This is
   *wiring* the tap onto existing `Loom`s — **no new transport code**.
2. **A lock** — a short-lived **join code** the device shows you, that the laptop
   must present to be let in. Without it, an open tap on a shared network is a log
   exfiltration hole. With it, only the person looking at the screen gets in.

## Locked decisions (v1 scope)

These were resolved with @keddie on PR #1032 and are no longer open:

- **mDNS + WebSocket is the only fabric in v1, with role inversion.** The laptop
  **hosts** (WS server + mDNS advertiser); the iOS device **joins** (WS client +
  Bonjour discoverer); the tap's symmetric `Quilter` still replicates the iOS
  buffer to the laptop. Any puller (JVM/CI or Mac).
- **Apple Multipeer is out of this plan** — it is the encrypted follow-up
  (**#1042**). **`wss://`/TLS on the WS path is out** — separate follow-up
  (**#1043**). This spec references them only as the encrypted successors.
- **`ws://` + join-token admission for v1.** Plaintext-on-wire is the documented
  honest seam: the token gates *who may pull*, not *confidentiality on the wire*.
- **Admission is a fabric-agnostic token-gated `Seam` decorator**, default `Open`
  so the shipped loopback-off behaviour is byte-unchanged. Challenge-response: the
  device shows a short code, the puller returns `HMAC(code, nonce)` — the code
  never crosses the wire; per-attempt nonce defeats replay; **constant-time** tag
  compare; **reusable within a TTL**; failed peers never reach the `Quilter`. Gate
  lives tap-local in `:kuilt-otel-tap` (no `:kuilt-session` dependency).
- **HMAC primitive: KotlinCrypto `hmac-sha2` (`HmacSHA256`)**, version-aligned with
  the already-vendored `kotlincrypto-hash-sha2` — KMP-uniform across every kuilt
  target (its sibling SHA-256 already compiles on wasmJs/iOS/macOS in
  `:kuilt-deal`), maintained (no hand-rolled MAC), gated by RFC 4231 test vectors.

The design below is written to those decisions; the trailing "open questions" are
now only minor implementation defaults.

## Current state

- `installLogTap(loom, exporter, scope, config)` hosts a session on any `Loom`,
  rides a `Quilter` over the woven `Seam`, and replicates the device's log buffer
  (`Rga<LogRecord>`) to whoever joins. `LogTapClient.pull()`/`tail()` reads it out.
- The tap is **fabric-agnostic on purpose**: "Discovery and admission are the
  `Loom`'s concern, not the tap's." The only fabric it's *tested* on is loopback
  WebSocket (`LogTapWebSocketTest`), which binds the local interface only.
- **There is no admission gate.** Once a peer is on the `Seam`, the `Quilter`
  replicates the whole buffer to it. On loopback that's fine (only local processes
  reach it). On a LAN it is not.
- The tap does **not** currently go through `:kuilt-session`'s `SeamRoom` — it uses
  a raw `Seam` + `Quilter`. So the existing `Hello`/`Welcome`/`Reject` admit
  handshake and `Principal` attestation are **available to compose with but not yet
  wired in**.

## The constraint that shapes everything: iOS can't host

The issue's driver is "pull telemetry off a **real iOS device**", with "a laptop
peer". A quick capability audit of the existing fabric modules turns up a hard
asymmetry that decides the whole fabric question:

| Capability | JVM | Android | **iOS** | macOS |
|---|---|---|---|---|
| Ktor WS **server** (`KtorServerLoom`, `jvmAndAndroidMain`) | ✓ | ✓ | **✗** | ✗ |
| Ktor WS **client** (`KtorClientLoom`, `commonMain`) | ✓ | ✓ | **✓** | ✓ |
| mDNS **advertiser** | ✓ | ✓ | **✗** (only a discoverer exists) | ✗ |
| mDNS **discoverer** | ✓ | ✓ | **✓** (`NSNetServiceBrowser`) | ✓ |
| Multipeer host + join (`:kuilt-multipeer`, `appleMain`) | ✗ | ✗ | **✓** | ✓ |

**An iOS device cannot run a WebSocket server and cannot advertise over mDNS.** So
the tap's current shape — *the device hosts, the laptop joins* — **cannot work for
an iOS source over mDNS+WS.** Two ways out, and they define the two fabric options:

- **Invert the rendezvous roles.** The tap's data flow is symmetric: a `Quilter`
  replicates state in *both* directions, so *which peer offers logs* is independent
  of *which peer opened the rendezvous*. Make the **laptop** the rendezvous host
  (WS server + mDNS advertiser — both JVM-capable) and the **iOS device** the
  joiner (WS client + Bonjour discoverer — both iOS-capable). iOS discovers the
  laptop, joins as a WS client, and its buffer still replicates *to* the laptop.
- **Use Multipeer**, which is fully iOS-native on both roles — but has **no JVM
  peer**, so the puller must be a **Mac**.

This asymmetry is the single most important thing for @keddie to weigh, so it
leads the fabric design below rather than hiding in a footnote.

## Fabric-wiring design

The tap needs one small refactor to make either option clean, then per-fabric
wiring.

### Prerequisite refactor — decouple *offer-role* from *rendezvous-role*

`installLogTap` currently hard-codes `loom.host(config.pattern)`. Because an iOS
source must *join* (it can't host), split the entry point so the offering side can
weave *either* rendezvous end:

```kotlin
// unchanged: device opens the rendezvous (Android/JVM/macOS source; loopback default)
public suspend fun installLogTap(loom, exporter, scope, config = LogTapConfig()): LogTapHost

// new: device offers its logs by JOINING a rendezvous the puller opened
public suspend fun installLogTapJoining(loom, exporter, scope, tag: Tag, config): LogTapHost
```

Both wrap the same `LogTapHost(seam, exporter, scope, config)` over the woven
`Seam` — only `loom.host(pattern)` vs `loom.join(tag)` differs. The `Quilter`,
replication, and buffer logic are untouched. (This is the honest cost of the iOS
constraint; it's one weave-site, not a rework.)

### Option A — mDNS + WebSocket (recommended first)

The reference wiring is already in `MDNSPeerLinkFactory` (JVM): a `KtorServerLoom`
on a free port + a JmDNS registration on `open`, and a `KtorClientLoom` join from a
discovered `MDNSAdvertisement` on `join`. The tap reuses it verbatim; only the
role orientation changes by source platform:

- **Android / JVM source** (device *hosts*, as today): device runs
  `MDNSPeerLinkFactory` → advertises `_kuilt-log-tap._tcp` + WS server; laptop
  discovers via `MDNSServiceDiscoverer`, `installLogTap`'s counterpart on the
  puller (`LogTapClient`) joins the discovered advertisement. Works on any laptop
  (JVM or Mac).
- **iOS source** (device *joins*, per the constraint): **laptop** hosts —
  `MDNSPeerLinkFactory` advertises + runs the WS server; **iOS** discovers the
  laptop's advertisement via `MDNSServiceDiscoverer` (iOS) and calls
  `installLogTapJoining(KtorClientLoom(...), …, tag = discoveredAdvertisement)`.
  iOS's buffer replicates to the laptop over the joiner→host `Seam`.

No manual addressing on either path: the `MDNSAdvertisement` carries the `ws://`
URL and server `PeerId`; discovery yields it directly.

**Wire encryption: none.** `ws://` on a LAN is plaintext. See threat model — the
token gates *admission*, not *confidentiality on the wire*.

### Option B — Apple Multipeer (follow-up #1042, not in this plan)

iOS device hosts via `MultipeerRoomHost` (`MultipeerPeerLinkFactory` +
`MultipeerServiceBrowser`, all `appleMain`); a **macOS** laptop discovers over the
same Multipeer service and joins. Both roles are native on Apple, so **no
role-inversion is needed** — the device hosts, matching the tap's natural shape.

Note `MultipeerRoomHost` is a `RoomHost` (it wraps `LoomRoomHost` → a `Room`),
whereas the tap consumes a raw `Seam`. The tap wiring uses the underlying
`MultipeerPeerLinkFactory` (a `Loom`) directly with `installLogTap`, *or* — if we
take the "compose with `SeamRoom`" admission path (below) — the `Room` form drops
in and brings the admit handshake for free. That coupling is an open question.

**Wire encryption: yes.** Multipeer sessions are DTLS-encrypted by default — a
genuine confidentiality advantage over `ws://`, at the cost of Apple-only reach.

### Decision: mDNS+WS first (locked), Multipeer follow-up #1042

- **Breadth of puller.** mDNS+WS lets **any** laptop pull — a plain JVM process, a
  CI runner, a Mac terminal. Multipeer's puller must be a Mac. The epic's value is
  "logs off the phone from my laptop"; not everyone's laptop is a Mac, and CI never
  is.
- **Closest to what's tested.** The loopback WS path already works; LAN WS is the
  *same* `KtorClientLoom`/`KtorServerLoom` over a real interface + discovery. The
  delta is discovery + admission, not transport.
- **Reaches Android too**, in the device-hosts orientation, for free.
- Multipeer is the right **second** step: it's the encrypted, cable-free,
  Apple-native experience for the iOS↔Mac case, and it reuses `MultipeerRoomHost`
  as-is.

The cost mDNS+WS pays — the role inversion and plaintext wire — is real but
bounded, and the token gate closes the admission hole either way. The encrypted
experience arrives via Multipeer (#1042) and/or `wss://` (#1043) as follow-ups.

## Admission / join-token design

### Threat model — a shared LAN

The moment the tap leaves loopback, the adversary is **any other device on the same
network**: a coworker's laptop, a guest phone, anything on café Wi-Fi.

- **mDNS is a broadcast directory.** Advertising `_kuilt-log-tap._tcp` tells
  *everyone* on the segment the tap exists and where. Discovery is not a secret.
- **What a stranger can do with no gate:** discover the advertisement, join the
  `Seam`, and let the `Quilter` replicate the **entire log buffer** to them — logs
  that may carry PII, auth tokens, session ids, internal state. This is silent
  telemetry exfiltration.
- **What the join token protects: *who may pull*** — it ensures the peer that
  admits is one the human operator authorized by reading a code off the device. It
  is an **admission** control.
- **What the token does _not_ protect (the honest seam): *confidentiality on the
  wire*.** Over `ws://`, an on-path eavesdropper on the same LAN can sniff the log
  frames *after* a legitimate admission, token or not. Closing that requires an
  encrypted fabric — **Multipeer (already encrypted)** or a future `wss://`/TLS WS
  option. Say so plainly; don't let the token imply confidentiality it doesn't
  provide.

So the token's job is precise: **stop an unauthorized peer from initiating a pull.**
It is necessary and sufficient for that, and nothing more.

### The gate: a token-gated `Seam` decorator

Mirror the existing `withPrincipal`/`PrincipalSeam` pattern (`:kuilt-session`): a
thin `Seam` wrapper that runs a challenge-response on first contact and only
surfaces peers that pass. This keeps the tap fabric-agnostic (works identically
over loopback, mDNS+WS, and Multipeer) and preserves the single-collection
`incoming` contract.

```kotlin
// The offering side holds a short-lived secret; the puller must prove knowledge of it.
public class LogTapJoinToken internal constructor(/* code, issuedAt, ttl */) {
    public val code: String            // what the device shows the operator
    public fun isValid(now: Instant): Boolean
}

// Opt-in admission wired onto installLogTap*/LogTapClient.
public sealed interface LogTapAdmission {
    public data object Open : LogTapAdmission                 // today's behaviour (loopback default)
    public data class Token(val token: LogTapJoinToken) : LogTapAdmission   // offering side
    public data class Present(val code: String) : LogTapAdmission           // pulling side
}
```

`LogTapConfig` gains `admission: LogTapAdmission = LogTapAdmission.Open` — so the
default is unchanged and loopback stays gate-free, exactly as the safety posture
requires. When `Token`/`Present` is set, the woven `Seam` is wrapped in a gate
before the `Quilter` ever sees a peer.

**Handshake** (reuses the `AdmitMessage` prefix-framing idea — a discriminator byte
so protocol frames are distinguishable from app frames, and CBOR bodies):

1. On first contact the **offering** side sends a random `nonce` (challenge).
2. The **pulling** side replies `proof = HMAC(code, nonce)` — the code itself never
   crosses the wire (defends against a passive sniffer learning the code, and
   against replay via the per-attempt nonce).
3. The offering side recomputes `HMAC(code, nonce)`, compares **constant-time**,
   and checks the token's validity window. Match ⇒ the peer is surfaced to the
   `Quilter` (replication proceeds). Mismatch or expired ⇒ send `Reject(reason)` and
   drop/close the peer; it is never surfaced.

Failed peers never reach the `Quilter`, so no log bytes leave before authorization.

### Token issuance & UX

- **Generation.** When admission is `Token`, the offering side mints a short,
  human-transcribable code (e.g. 6–8 chars base32, or a 6-digit number) from the
  **injected `Random`** (determinism discipline; never unseeded), with an
  `issuedAt` from the **injected `Clock`** and a TTL (default e.g. 5 min).
- **Where the device shows it.** Print to the platform log sink the developer is
  already watching — **Xcode console on iOS, logcat on Android, stdout on
  JVM/macOS** — via `kotlin-logging` at install time; and expose it as an API value
  (`host.joinCode`) so an app can surface it in a debug-overlay UI if it wants. The
  operator reads the code and types it into the laptop puller.
- **Expiry & rotation.** The token is valid only within its TTL window; an expired
  proof is rejected. Options for @keddie: single-use (invalidate on first
  successful admit) vs. reusable-within-TTL (multiple pulls / reconnects in one
  debugging session). Default proposal: **reusable within TTL**, because
  `LogTapClient` reconnect/`tail` naturally re-admits and single-use would break the
  reconnect-is-seamless property the `Quilter` gives.

### Composition with `:kuilt-session`

The tap does not use `SeamRoom` today, so there are two integration shapes:

- **Tap-layer gate (proposed default):** the token gate is a self-contained `Seam`
  decorator in `:kuilt-otel-tap`, no new module dependency. Simplest; keeps the tap
  free of `:kuilt-session`. The handshake *borrows the pattern* of `AdmitMessage`
  (prefix byte + CBOR) without depending on it.
- **Route the tap through `SeamRoom`:** gain the `Hello`/`Welcome`/`Reject`
  handshake, roster, and `Principal` attestation, and add token verification either
  as a new `Hello.joinProof` field or as a `Principal` the gate establishes before
  admit. Heavier — pulls `:kuilt-session` into `:kuilt-otel-tap` — but unifies
  admission with the rest of kuilt and is the natural fit **if** we take Option B's
  `MultipeerRoomHost` (`Room`) form. Open question below.

## Module & target considerations

- **No new fabric modules.** Wiring lives in `:kuilt-otel-tap` (the
  offer/pull-role split + the token gate, all `commonMain`) plus thin
  per-fabric helper wiring. mDNS+WS reuses `:kuilt-mdns` (+ `:kuilt-websocket`);
  Multipeer reuses `:kuilt-multipeer`.
- **HMAC dependency.** The challenge-response needs an HMAC available on all tap
  targets (JVM/Android/iOS/macOS/wasmJs). Prefer a KMP-uniform primitive already in
  the tree if one exists; otherwise this is a catalog decision (open question) —
  avoid a JVM-only `javax.crypto` path that would break iOS.
- **`:kuilt-otel-tap` target set** already includes the Apple targets, so the gate
  compiles where Multipeer lives. Multipeer-specific *sample/wiring* code is
  `appleMain`-only; the gate itself is common.
- **Default unchanged.** `LogTapAdmission.Open` + loopback pattern reproduce the
  shipped behaviour exactly; nothing in the M1/tap default path changes.
- **Detekt / `explicitApi`:** every new public type gets explicit visibility; the
  token's `code` and any secret material must not land in `toString()`/logs beyond
  the deliberate one-time issuance print.

## Testing

- **Gate unit tests** (`:kuilt-otel-tap` `commonTest`, `StandardTestDispatcher`,
  seeded `Random`, virtual `Clock`): valid proof ⇒ peer surfaced + buffer
  replicates; wrong code ⇒ `Reject`, `Quilter` never sees the peer, `pull` times
  out; expired token ⇒ rejected; `Open` admission ⇒ current behaviour (parity with
  today's `LogTapConvergenceTest`). Constant-time compare exercised. No production
  dispatcher in tests.
- **Fabric wiring over loopback:** extend the existing loopback-WS test to run the
  *gated* `Seam` end-to-end (host mints token, client presents it) — proves the gate
  composes with real `Quilter` replication without a real radio.
- **mDNS integration** stays behind the existing `-Pmdns.multicast.tests=true`
  opt-in gate (real multicast); add a gated discover→join→pull case there.
- **Role-inversion test:** a JVM "host+puller" ↔ JVM "join+offer" pair (standing in
  for laptop ↔ iOS) proves the offer-role/rendezvous-role split replicates the
  *joiner's* buffer to the *host*.
- **Full `./gradlew build`** must be green (Android + Native variants compile — the
  gate is `commonMain`, so this is a hard bar), verified with `--rerun-tasks`.

### Manual real-device validation (the actual "done")

The `Done when` in #1028 is a *real-device* observation — script it as a checklist,
capture notes in the PR:

1. **mDNS+WS, iOS source:** run the laptop host+puller on the same Wi-Fi; launch the
   app on a real iPhone with the tap in `installLogTapJoining` + `Present(code)`;
   confirm the phone discovers the laptop with **no IP typed**, admits with the code
   the phone printed to the Xcode console, and the laptop `pull()` returns the
   phone's real logs.
2. **Wrong/expired code:** repeat with a bad code → refused, no logs pulled; let a
   token expire → refused. Capture both.
3. **Multipeer, iOS↔Mac (Option B, if built):** iPhone hosts, Mac joins, same
   code-gated pull, cable-free.
4. Record device models, OS versions, network, and any Bonjour/permission prompts
   (iOS local-network permission dialog is expected on first discovery — note it).

## Resolved decisions & remaining minor defaults

The big questions are **resolved** (see "Locked decisions" up top): mDNS+WS first
with role inversion; Multipeer → #1042; `wss://` → #1043; `ws://` + token for v1;
tap-local token-gated `Seam` decorator; KotlinCrypto `hmac-sha2`;
reusable-within-TTL. What remains are small implementation defaults the plan
carries — flag only if you disagree:

1. **Code format** — proposal: **8-char Crockford base32** (~40 bits, no ambiguous
   chars, still transcribable). Alternative was 6-digit numeric (easier to read
   aloud, less entropy). Short TTL keeps online-guessing infeasible either way.
2. **TTL default** — proposal: **5 minutes** from issuance, reusable for repeated
   pulls / `tail` reconnects within the window (keeps the reconnect-is-seamless
   property the `Quilter` gives).
3. **Where the code is shown** — baseline: printed once to the platform log at
   install (Xcode console / logcat / stdout) *and* exposed as `host.joinCode` so an
   app can surface it in a debug-overlay UI. No further UX in v1.

## Done when

A real device advertises (or, for iOS, joins) the tap over mDNS/Multipeer; a second
peer discovers it with no manual addressing, presents a **valid** join code, admits,
and pulls the buffer; an **invalid/expired** code is refused with the `Quilter`
never replicating to it; the default (loopback, `Open`) path is unchanged; the full
`./gradlew build` is green; and **manual real-device validation notes are captured
in the PR(s)**. Likely split: a **fabric-wiring PR** (offer/rendezvous-role split +
mDNS+WS wiring) and an **admission PR** (token gate) — landing the gate before, or
with, any non-loopback wiring so reach is never shipped un-gated.

## Non-goals / notes

- **No new transports.** This is wiring the tap `Seam` onto `:kuilt-mdns`,
  `:kuilt-websocket`, and `:kuilt-multipeer` as they exist.
- **Not wire confidentiality.** The token is admission control only; the encrypted
  transports are follow-ups — Multipeer (#1042) and `wss://`/TLS (#1043).
- **The task-by-task implementation plan** for this spec is
  `docs/superpowers/plans/2026-07-01-realphone-tap-reach.md`.
- **Not a production/always-on feature.** The tap stays a developer-invoked debug
  affordance; reach and the token are strictly opt-in over the unchanged
  loopback-off default.
- **References policy:** abstract use case only; no third-party citations, no
  cross-repo references.
</content>
</invoke>
