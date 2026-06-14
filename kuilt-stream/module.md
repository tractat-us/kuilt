# Module kuilt-stream

Adapts byte streams into kuilt message links — the bridge a *stream* transport
crosses to become a fabric.

## What it provides

A message transport (WebSocket, Multipeer, Nearby) implements `Conn` directly,
because it already preserves frame boundaries. A **stream** transport (TCP, a
Unix socket, an in-house byte-stream RPC) only offers an ordered byte pipe with
no message boundaries. `:kuilt-stream` closes that gap.

`framed(source, sink)` adapts a kotlinx-io `Source`/`Sink` byte-stream into a
`Conn` using a 4-byte big-endian length prefix per frame:

- **Framing:** each `send` writes a 4-byte length then the frame bytes.
- **Reassembly:** the prefix is read, then exactly that many bytes — restoring
  whole-message boundaries over the raw stream.
- **Oversize protection:** a length prefix above `maxFrameSize`
  (`DEFAULT_MAX_FRAME_SIZE`, 16 MiB) throws `FrameTooLargeException` *before* any
  allocation, so a hostile peer cannot trigger an OOM.
- **Clean EOF:** an end-of-stream at a frame boundary completes `incoming`
  normally; a mid-frame EOF propagates as `EOFException`.

The resulting `Conn` is **cold and single-collection**: its `incoming` may be
collected only once. Hand it straight to `:kuilt-core`'s `handshaking` (2-peer)
or `meshSeam` (N-peer) — both wrap the conn so the preamble read and the read
loop share that single collection. No hot-reader pump is needed.

`:kuilt-tcp` is the reference consumer: `tcpConn` wires a Ktor socket's channels
through `framed()` to yield a `Conn`.
