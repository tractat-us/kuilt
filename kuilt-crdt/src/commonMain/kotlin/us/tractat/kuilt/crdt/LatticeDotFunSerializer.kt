package us.tractat.kuilt.crdt

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import us.tractat.kuilt.crdt.internal.sortedByCanonicalKey

/**
 * Custom [KSerializer] for [LatticeDotFun]`<V>`: the same wire layout as [DotFunSerializer]
 * (`Map<Dot, V>`), entries emitted in the one canonical [Dot] order the dot family shares, so two
 * replicas at the same logical state encode to identical bytes whichever way their joins ran.
 *
 * The sort matters for the same reason it does in [DotFunSerializer]: [LatticeDotFun.join] builds a
 * [LinkedHashMap] whose order depends on which operand was `this`.
 *
 * **The layout is [DotFunSerializer]'s exactly**, so a `DotFun<V>` blob decodes as a
 * `LatticeDotFun<V>` and the other way round, with nothing to flag the mismatch. A frame's type is
 * settled by whoever chose the serializer, not by the bytes.
 */
@OptIn(ExperimentalSerializationApi::class)
public class LatticeDotFunSerializer<V : Quilted<V>>(
    private val vSerializer: KSerializer<V>,
) : KSerializer<LatticeDotFun<V>> {

    private val mapSerializer = MapSerializer(Dot.serializer(), vSerializer)

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: LatticeDotFun<V>) {
        mapSerializer.serialize(
            encoder,
            value.values.sortedByCanonicalKey(Dot.serializer(), encoder.serializersModule),
        )
    }

    override fun deserialize(decoder: Decoder): LatticeDotFun<V> =
        LatticeDotFun(mapSerializer.deserialize(decoder))
}
