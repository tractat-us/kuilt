# Module kuilt-nw

Network.framework peer-to-peer fabric: a full-mesh `Loom`/`Seam` implementation
built on Apple's Network.framework, replacing Multipeer Connectivity's AWDL
teardown regression on iOS 26 with a direct point-to-point transport.

This module hand-wires the `appleMain`/`macosMain` (and `appleTest`) source-set
intermediates manually, mirroring `:kuilt-multipeer` — required up front so the
first real Apple-only source added later doesn't trip the Dokka
"no source module for appleMain" gotcha that hits modules relying on the
default hierarchy template's auto-wiring.
