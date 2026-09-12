---
paths:
  - "kuilt-otel/**"
  - "kuilt-otel-tap/**"
  - "kuilt-otel-tap-test/**"
  - "kuilt-otel-logging/**"
  - "kuilt-otel-logback/**"
  - "kuilt-otel-log4j2/**"
  - "kuilt-otel-sdk/**"
  - "kuilt-otel-otlp/**"
---
# Otel

An app on a phone or a laptop keeps running even when it has no signal, and
whatever it logged while offline shouldn't be lost just because nobody was there
to receive it. These modules keep that record — logs, traces, counts — safely on
the device until it can be pulled off by someone testing it, or sent on to a
collector once the network comes back, with nothing missing and nothing counted
twice.

What a consumer is about to hand-roll, and the primitive that already exists, with
compiled snippets: `docs/agent-cookbook/otel.md`. This file holds only the
rules for changing the code here.

## Rules

(none yet — the family's conventions move here from the root `CLAUDE.md` in a later change)
