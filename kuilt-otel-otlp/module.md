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
