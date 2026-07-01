package us.tractat.kuilt.otel.logging

/**
 * A [TraceContextProvider] backed by the coroutine-context trace an app sets with
 * [withActiveTrace] — the dependency-light, `commonMain` source for platforms with
 * no ambient tracer (wasmJs, iOS, macOS).
 *
 * [current] reads the [execution-local slot][currentActiveTrace] the enclosing
 * [withActiveTrace] scope populated. It is non-`suspend` and safe to call from the
 * synchronous capture edge (`CapturingAppender.log`), which is where the gate
 * resolves the trace. Returns `null` outside any [withActiveTrace] scope.
 *
 * On the JVM an app may instead use `OtelSdkTraceContextProvider` (reads the OTel
 * SDK's `Span.current()`); both implement [TraceContextProvider], so the gate and
 * [ActiveTrace] shape are identical and the choice is per-install.
 */
public class CoroutineContextTraceProvider : TraceContextProvider {
    override fun current(): ActiveTrace? = currentActiveTrace()
}
