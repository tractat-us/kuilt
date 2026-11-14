# Module kuilt-otel-sdk

Bridge an app's existing OpenTelemetry setup into kuilt's offline-first
telemetry buffer — on the JVM and Android.

Three optional, additive pieces, all for apps that already run OpenTelemetry:

- `OtelSdkTraceContextProvider` lets kuilt's log capture follow your tracing
  sampler: logs emitted inside a sampled span are kept and stamped with their
  trace and span id, so logs and spans line up; and whether logs outside a trace
  are kept is your capture-config choice.
- `KuiltLogRecordExporter` is an OpenTelemetry SDK log exporter: point your
  existing log pipeline at it and every record also lands in kuilt's durable,
  extractable buffer — without adopting kuilt's own capture edge.
- `KuiltMetricExporter` is its metric twin: register it on a metric reader of
  its own and every counter, gauge and histogram also lands in the same durable
  buffer. It asks the SDK for **delta** temporality on counters and histograms,
  because kuilt adds each interval to what it already holds, and for
  **cumulative** on UpDownCounters, so the gauge each becomes holds the current
  level rather than the last interval's change. Your own OTLP exporter, on its
  own reader, keeps whatever it asked for.

  Histograms arrive as **exponential** histograms, because the bridge asks the
  SDK for them at the scale kuilt keeps its sketches at, and each interval's
  bucket counts are added to the series. Four things are dropped with a
  warning, while the rest of the export lands: an explicit-bucket histogram,
  which reaches the bridge only when one of your Views asks for it; a histogram
  that arrives cumulative; one whose values spread so wide that the SDK lowered
  its scale below kuilt's (raise `maxBuckets` if that is normal for you); and a
  summary.

None is required for kuilt telemetry; off the JVM there is nothing here. What
the metric bridge sends a collector, and where kuilt fits on a server, is in the
`kuilt-otel` module documentation.
