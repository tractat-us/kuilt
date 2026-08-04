package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.sortedByCanonicalKey

/**
 * Custom [KSerializer] for [ORMapEntry]`<S>` that emits [ORMapEntry.contributions] sorted by [Dot]
 * key, so two replicas at the same logical state produce identical bytes regardless of the order
 * their merges happened to run in.
 *
 * Wire format: `Map<Dot, S>` — the same shape [DotFunSerializer] emits, and for the same reason.
 * [ORMapEntry.join] builds a [LinkedHashMap] whose iteration order depends on which side was `self`
 * and which was `other`; sorting before encoding makes the wire form a function of the value.
 *
 * **Sort order:** [sortedByCanonicalKey], the one canonical order shared with the rest of the dot
 * family (#1964).
 */
@OptIn(ExperimentalSerializationApi::class)
public class ORMapEntrySerializer<S : Quilted<S>>(
    private val valueSerializer: KSerializer<S>,
) : KSerializer<ORMapEntry<S>> {

    private val mapSerializer = MapSerializer(Dot.serializer(), valueSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: ORMapEntry<S>) {
        mapSerializer.serialize(
            encoder,
            value.contributions.sortedByCanonicalKey(Dot.serializer(), encoder.serializersModule),
        )
    }

    override fun deserialize(decoder: Decoder): ORMapEntry<S> =
        ORMapEntry(mapSerializer.deserialize(decoder))
}
