package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.sortedByCanonicalKey

/**
 * Custom [KSerializer] for [DotSet] that emits [DotSet.dots] sorted by [Dot]
 * (lexicographically by replica, then by seq), producing identical bytes for
 * any two replicas at the same logical state regardless of delivery order.
 *
 * Wire format: `List<Dot>` — a list rather than a set, because [ListSerializer]
 * preserves insertion order and most formats (JSON, CBOR) encode lists as arrays.
 * The canonical sort makes the array byte-stable.
 *
 * The auto-generated serializer is delivery-order-dependent because [DotSet.join]
 * builds a [LinkedHashSet] whose iteration order depends on which side is `self`
 * vs `other` in the merge. Sorting before encoding makes the wire form a function
 * of the logical value, not history.
 *
 * **Sort order:** [sortedByCanonicalKey], the one canonical order shared with
 * [CanonicalSetSerializer] and the rest of the dot family (#1964). It is
 * byte-identical to the `dots.sorted()` it replaced — [Dot]'s leaf sequence is
 * `(replica, seq)`, compared in exactly the order [Dot.compareTo] compares them —
 * which `dotOrderMatchesCanonicalKeyOrder` pins.
 */
@OptIn(ExperimentalSerializationApi::class)
public class DotSetSerializer : KSerializer<DotSet> {

    private val listSerializer = ListSerializer(Dot.serializer())

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: DotSet) {
        listSerializer.serialize(
            encoder,
            value.dots.sortedByCanonicalKey(Dot.serializer(), encoder.serializersModule),
        )
    }

    override fun deserialize(decoder: Decoder): DotSet =
        DotSet(listSerializer.deserialize(decoder).toSet())
}
