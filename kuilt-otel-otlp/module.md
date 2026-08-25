# Module kuilt-otel-otlp

Send kuilt's offline-first telemetry to a standard OpenTelemetry collector.

When the network is available, this module forwards the spans, logs, and metrics
kuilt buffered on the device to any OTLP/HTTP endpoint — the same collector your
existing dashboards already read. It only sends what the endpoint has not already
received, so a reconnect after hours offline uploads the gap, not the whole history.

The wire encoding is your choice: OTLP/JSON (the default, mature on every platform) or
the more compact OTLP/protobuf that many collectors expect by default. Both carry the
same data and reconcile the same way — set `wire = OtlpWireFormat.PROTOBUF` on
`OtlpHttpEdge` to switch, and the requests go out as `application/x-protobuf`.

`OtlpHttpEdge` is the `OtlpEdge` a `WarpOtlpBridge` drains into. Point it at a
collector URL and call `drain` on each reconnect.

## Upgrading past this release: one burst of duplicates, once

Worth knowing before you see it in a dashboard.

"What have I already sent to this collector?" is a note kuilt keeps on the device,
filed under a name built from the collector's address. This release rewrites that
name twice over: entry names are now stored losslessly rather than squashed onto a
narrower alphabet (#2506, #2511), and the part identifying the collector is now a
fingerprint wide enough that two collectors cannot be mistaken for one (#2513). The
old note is left where it lies rather than moved, so the first run after the upgrade
finds no record of what was sent and offers up everything still sitting in the buffer.

What that looks like at the collector is one burst of records it has seen before. It
is bounded by whatever the device's buffer still holds, it happens on the first flush
only, and every flush after it is back to normal. So if telemetry doubles up right
after an upgrade, this is that — not a retry loop worth chasing. Nothing is lost: the
re-sent records carry the same ids they carried the first time, so a collector that
deduplicates by id absorbs them, and the cost is bandwidth rather than double-counted
data.

Both renames deliberately ship in the same release. Landing them separately would have
handed consumers this burst twice instead of once.
