# Module kuilt-otel-sdk

Bridge an app's existing OpenTelemetry setup into kuilt's offline-first log
buffer — on the JVM and Android.

Two optional, additive pieces, both for apps that already run OpenTelemetry:

- `OtelSdkTraceContextProvider` lets kuilt's log capture follow your tracing
  sampler: logs emitted inside a sampled span are kept and stamped with their
  trace and span id, so logs and spans line up; logs outside a trace follow a
  policy you choose.
- `KuiltLogRecordExporter` is an OpenTelemetry SDK log exporter: point your
  existing log pipeline at it and every record also lands in kuilt's durable,
  extractable buffer — without adopting kuilt's own capture edge.

Neither is required for kuilt logging; off the JVM there is nothing here.
