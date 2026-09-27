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
- `KuiltMetricExporter` is its metric twin: register it on your metric reader
  and every counter and gauge also lands in the same durable buffer. Histograms
  and summaries are dropped with a warning.

None is required for kuilt telemetry; off the JVM there is nothing here.
