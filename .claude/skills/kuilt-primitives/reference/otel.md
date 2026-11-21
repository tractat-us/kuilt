# kuilt-primitives — otel

Routes for telemetry and on-device log capture.
Each `Jump to` names a section of `docs/agent-cookbook/<file>`; fetch it by the ladder in this skill's `SKILL.md`.
Rows pair `;`-separated groups in order.

|You're about to write…|Use instead|Jump to|
|---|---|---|
|a flush-per-line log exporter, "logging stalls the app"; stamping a line's session/game; resetting a telemetry store; is telemetry landing; a loop of metric writes|`WarpLogRecordExporter.export(records)` + `installLogCapture`; `withLogContext { … }`, **not** `CaptureConfig.attributeMapper`; `WarpTelemetry.clear()`; `WarpTelemetry.health` + `LogCaptureInstallation.health` (`CaptureHealth.droppedEvents`); `WarpMetricExporter.record`|`otel.md#telemetry--log-capture`|
