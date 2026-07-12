# Module kuilt-nw

Lets nearby iPhones and Macs talk to each other directly — no server, and no
shared Wi-Fi network needed. Put a few devices in the same room and they find
each other and start exchanging messages on their own.

Under the hood it is a full-mesh `Loom`/`Seam` fabric built on Apple's
Network.framework: every peer advertises, browses, and dials, so each pair forms
a direct point-to-point link and the redundant double-dial is deduplicated into
one connection. It replaces Multipeer Connectivity, whose AWDL teardown
regressed on iOS 26.

---

**Source-set wiring note (maintainers).** This module hand-wires the
`appleMain`/`macosMain` (and `appleTest`) source-set intermediates manually,
mirroring `:kuilt-multipeer` — required up front so the first real Apple-only
source added later doesn't trip the Dokka "no source module for appleMain"
gotcha that hits modules relying on the default hierarchy template's auto-wiring.
