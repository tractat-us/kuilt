package us.tractat.kuilt.otel.otlp

/**
 * What an [OtlpHttpEdge] wraps around every request it sends, on both wires: the
 * producer's resource attributes and its instrumentation scope.
 *
 * A producer's resource is fixed for its life, so one envelope serves every request. An
 * empty [resource] and an empty [scopeName]/[scopeVersion] are OTLP's "absent" and
 * "unknown", and the mappers leave those fields off the wire entirely — so [NONE] emits
 * exactly the bytes the edge sent before it had an envelope.
 */
internal class OtlpEnvelope(
    val resource: Map<String, String>,
    val scopeName: String,
    val scopeVersion: String,
) {
    /** True when there is no scope to send: both strings are OTLP's empty default. */
    val scopeIsEmpty: Boolean get() = scopeName.isEmpty() && scopeVersion.isEmpty()

    companion object {
        /** No resource, no scope: the envelope of an edge configured with neither. */
        val NONE: OtlpEnvelope = OtlpEnvelope(emptyMap(), "", "")
    }
}
