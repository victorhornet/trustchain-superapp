package nl.tudelft.trustchain.common.eurotoken

import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.keyvault.PublicKey
import java.util.*

data class Transaction(
    val block: TrustChainBlock,
    val sender: PublicKey,
    val receiver: PublicKey,
    val amount: Long,
    val type: String,
    val outgoing: Boolean,
    val timestamp: Date
) {
    override fun equals(other: Any?): Boolean =
        other is Transaction &&
            other.block == block

    override fun hashCode(): Int {
        var result = amount.hashCode()
        result = 31 * result + outgoing.hashCode()
        result = 31 * result + block.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + receiver.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
