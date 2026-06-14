# Module kuilt-tcp

A raw-TCP fabric — the headline of the pluggable fabric kit.

## What it provides

`TcpLoom` is a `Loom` backed by a Ktor TCP socket. It shows how few lines a
stream transport needs to become a kuilt fabric: obtain a connected socket, adapt
it to a `Conn` with `tcpConn` (Ktor channels → kotlinx-io `Source`/`Sink` →
`:kuilt-stream`'s `framed()`), then hand that to `:kuilt-core`'s `handshaking` to
negotiate identity in-band and yield a 2-peer `Seam`.

- `TcpLoom.host(serverSocket, …)` — `weave(Rendezvous.New)` accepts one connection
  on a pre-bound `ServerSocket`.
- `TcpLoom.join(…)` — `weave(Rendezvous.Existing(TcpAddress))` dials the address.

`TcpAddress(host, port)` is the `Tag` a joiner resolves.

## Real IO, real clock

TCP is real-network IO with no virtual clock. The seam's read/write loops run on a
real production dispatcher; the seam is thread-safe via atomics/locks, so that
dispatcher is the **scheduler**, not a mutex. Blocking socket reads are pinned to
an IO dispatcher, so the scheduling dispatcher never blocks on the wire.

`weave` guards against accidental virtual-time construction: building the seam
under a `kotlinx.coroutines.test.TestDispatcher` fails loudly — under virtual time
the blocking socket IO would never advance, deadlocking the test silently. For
virtual-time tests, use an in-memory `connPair()`-backed seam instead.

## Beyond Ktor

The adapter generalises to any byte-stream RPC. `ProprietaryRpcExampleTest` weaves
a `Seam` over a plain `java.net.Socket` with the same `framed()` + `handshaking`
bridge, and `TcpClusterExampleTest` stands up a 3-peer cluster over loopback via
`meshSeam` + `addLink`.

## Targets

`jvmAndAndroidMain` only — the Ktor TCP socket and blocking-IO adapters are
JVM/Android. The pure-Kotlin framing in `:kuilt-stream` is multiplatform; the TCP
binding here is not.
