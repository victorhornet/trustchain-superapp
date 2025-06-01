package nl.tudelft.trustchain.eurotoken.community

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeVarLen
import nl.tudelft.ipv8.messaging.serializeVarLen

/**
 * This class is used to serialize and deserialize vouch data payload messages.
 * The payload encodes vouch information such that remote peers can update their
 * vouch stores with the latest vouch information from the network.
 * Used by EuroTokenCommunity for vouch data exchange.
 */
class VouchPayload(
    val id: String,
    val data: ByteArray
) : Serializable {
    override fun serialize(): ByteArray {
        return serializeVarLen(id.toByteArray()) + serializeVarLen(data)
    }

    companion object Deserializer : Deserializable<VouchPayload> {
        override fun deserialize(
            buffer: ByteArray,
            offset: Int
        ): Pair<VouchPayload, Int> {
            var localOffset = offset
            val (id, idSize) = deserializeVarLen(buffer, localOffset)
            localOffset += idSize
            val (data, dataSize) = deserializeVarLen(buffer, localOffset)
            localOffset += dataSize
            return Pair(
                VouchPayload(
                    id.toString(Charsets.UTF_8),
                    data
                ),
                localOffset - offset
            )
        }
    }
} 