package us.tractat.kuilt.crdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A JSON scalar value held by a [JsonNode.Leaf].
 *
 * Models the four primitive JSON types: null, boolean, number, and string.
 * Structured types (object, array) are represented as [JsonNode.Object] and
 * [JsonNode.Array] — not as [JsonValue] — so the type lattice stays clean.
 */
@Serializable
public sealed class JsonValue {

    @Serializable
    @SerialName("null")
    public data object Null : JsonValue()

    @Serializable
    @SerialName("bool")
    public data class Bool(public val value: Boolean) : JsonValue()

    @Serializable
    @SerialName("num")
    public data class Num(public val value: Double) : JsonValue()

    @Serializable
    @SerialName("str")
    public data class Str(public val value: String) : JsonValue()
}
