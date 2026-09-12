---
paths:
  - "kuilt-core/**"
  - "kuilt-stream/**"
  - "kuilt-tcp/**"
  - "kuilt-websocket/**"
  - "kuilt-nw/**"
  - "kuilt-multipeer/**"
  - "kuilt-nearby/**"
  - "kuilt-webrtc/**"
  - "kuilt-mdns/**"
  - "kuilt-conformance/**"
  - "kuilt-test/**"
  - "kuilt-store/**"
---
# Fabrics

Two devices that want to talk to each other need something to carry bytes between
them — over the internet, a local network, or directly between two devices over
the local radio — and a way to find each other before they can start. These
modules are the several ways to move those bytes, the shared contract every one
of them honors, the tests that keep a new one honest, and a place to keep bytes
safely on disk between runs.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/fabrics.md`. This file holds only the
rules for changing the code here.

## Rules

| mDNS multicast integration (off by default — needs a real network) | `./gradlew :kuilt-mdns:jvmTest -Pmdns.multicast.tests=true` |

The mDNS multicast suite is opt-in because it sends real multicast packets. The `-P` flag is
forwarded to JVM tests as a system property, where a JUnit assumption skips them honestly; the K/N
simulator probe is **excluded at the task level** instead, because Kotlin/Native has no assumption
API and a `@Test` that reads a gate and returns early reports **passed**, not `skipped` (#2621). See
`kuilt-mdns/build.gradle.kts`.

- **KMP source-set hierarchy is wired by hand in `:kuilt-websocket`** — a manual
  `jvmAndAndroidMain` intermediate (Ktor server is JVM/Android-only) disables the
  plugin's default auto-wiring, so `iosMain`/`macosMain` intermediates are also
  declared explicitly. Edit those `build.gradle.kts` source-set blocks carefully.
- **Test a new fabric by subclassing `SeamConformanceSuite`** and implementing
  `newLoomPair()`. Every fabric must pass the same suite (see
  `InMemoryLoomConformanceTest`). In-process radio fabrics return the same instance
  twice; role-split fabrics return distinct host/joiner Looms wired to each other.
  Real-radio/real-network tests stay separate and `-P`-gated; the conformance suite
  runs against an in-memory or loopback harness.
- **A documented fixed width is enforced in the wire type's `init { require(...) }`, not in the
  decoder.** kotlinx-serialization invokes the constructor, so an invariant stated there holds on
  **every** decode path automatically — including the one a future consumer adds and forgets to
  guard. A decoder-side check sits one call site away from the type and covers only the paths
  somebody remembered: #1822 found the same defect — *a nonce whose width is documented by a
  `NONCE_BYTES` constant and unenforced on decode* — at three sites in three modules with three
  serializers, and the third was an **independent re-derivation** by a different hand, which is what
  separates a recurring class from a duplicated mistake. `LogRecord`, `SpanRecord` and `MetricKey`
  in `:kuilt-otel` are the exemplars; `MeshHello`, `NwHello` and `TapAdmitMessage.Challenge` are the
  fabric-side ones. `WireCodecConformanceSuite` (`:kuilt-conformance`) is the regression lock —
  subclass it for a new wire type rather than trusting the `require` alone.

  Two things make this more than a one-line move, both learned in-tree rather than reasoned out:

  - **`require` throws, so on a `ReturningNull` codec the decoder must catch it.** A constructor
    throw is a *rejection* only where the codec's `WireRejectionMode` is `Throwing` — a handshake
    path whose caller tears the one connection the bad frame arrived on. On a decoder called from a
    long-lived pump an escaping `IllegalArgumentException` is not a rejection at all: it ends the
    pump. That is #1819 exactly, where 16 bytes from any peer left a `NearbySeam` permanently deaf
    with **no `Torn` to observe**. Writing the `require` and stopping converts a width bug into a
    liveness bug. The width check still belongs on the type; the decoder turns the throw into `null`.
  - **Moving the check into `init` can delete a test's detection while leaving it green.** A test
    that builds its malformed frame by handing the **local** encoder a wrong-width value stops
    exercising the receiver the moment the constructor refuses: the *sender* now throws, the frame
    never exists, and the test passes without the receiver being involved. Inject raw bytes through
    a width-unconstrained **surrogate** instead, and prove the surrogate with a byte-identity
    receipt against the real encoder — an unproven surrogate just moves the vacuity one level up.
    `TapAdmitChallengeWireCodecTest` is the worked pattern. This is "removing vacuity can remove
    detection", and #2650 is the open instance of it.

  **Rejection, never reshaping.** Every field of this kind is an identity or a MAC input, not a
  quantity. Clamping a quantity into range is fine; truncating or padding a wrong-width nonce
  launders the proof of a malformed or forged frame into a valid-looking value, and the forger
  simply receives whichever in-range value the reshaping picks.
- **A seam's `state` is a `SeamStateGate`, not a bare `MutableStateFlow<SeamState>`** (`:kuilt-core`,
  `public`). The close decision and the flow write have to be one atomic step: guarding the write
  with `if (!closed)` does not fix it, because **check-a-flag-then-write IS the race** — four fabrics
  hand-rolled that latch and three wrote exactly it (#1803). `forbidBareSeamStateFlow` in the root
  build enforces the **type**, since the general shape rule is not viable (#1803: "assign a local
  from a locked block" matches 145 production sites and is the mandated idiom). Nine flows are
  exempt, each carrying an `// ALLOW-bareSeamState: <reason>` marker naming its argument — one
  shared lock, an atomic `update {}` CAS, a single-threaded target, a single writer behind a
  close-once latch, or a constant with no retained handle. A blank reason is itself a violation,
  and so is a marker with no bare flow left under it — sweeping a site to the gate means deleting
  its marker in the same change (#2633 swept the tenth, `FlakyLifecycleSeam`, whose own marker
  said its argument was the weakest of the ten: not "cannot lose a write" but "cannot lose one
  HERE", resting on the confined dispatcher it is driven from rather than on the field itself).
