# :demo-web — the Patchwork browser page

The headline surface of the Patchwork demo
([design](../../docs/superpowers/specs/2026-07-07-demo-app-design.md)): a
wasmJs page where **every open tab is one more peer** stitching patches onto
one shared quilt, live, through the `:demo-relay` WebSocket hub.

## Run it

1. **Start the relay** (terminal 1):

   ```bash
   ./gradlew :demo-relay:run
   ```

   It listens on `ws://localhost:9190/patchwork`.

2. **Serve the page** (terminal 2):

   ```bash
   ./gradlew :demo-web:wasmJsBrowserDevelopmentRun
   ```

   A browser opens at `http://localhost:8080`. Open the same URL in **more
   tabs** (or from another machine on the LAN — the page dials the relay on
   the host it was served from): each tab is another stitcher, and every
   stitch appears on every tab within a round trip.

3. **The headline moment — convergence under partition:**
   - In one tab, press **Enter the tunnel** (the status line turns amber —
     that tab is offline).
   - Keep stitching in the tunneled tab *and* in the others; the two boards
     drift apart.
   - Press **Reconnect** — both sides' patches merge into one quilt on every
     tab, nothing lost, nothing doubled. Concurrent stitches to the same cell
     resolve last-writer-wins, identically everywhere.

`:demo-cli` terminal peers (`./gradlew :demo-cli:run`) join the same quilt —
browser and terminal stitchers interoperate.

Query parameters: `?name=alice` picks the peer name (random default);
`?relay=ws://host:9190/patchwork` points at a non-default relay.

## What it is

Thin DOM glue (plain `@JsFun` bindings, no UI framework) over the tested
pieces: `:demo-shared`'s `PatchworkSession` (the LWW-map board + `Quilter`
replication; tunnel mode is just `disconnect()` + a kept local board) and
`RelaySpokeLoom` (a browser-WebSocket spoke into the relay's gossip hub, on
Ktor's Js engine). Browser test *execution* is disabled for the same
infrastructure reason as `:demo-shared` — see the note in
[build.gradle.kts](build.gradle.kts); the convergence logic is covered by
JVM tests in `:demo-shared` and the real-relay integration test in
`:demo-cli`.
