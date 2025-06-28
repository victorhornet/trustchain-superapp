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
    val timestamp: Date,
//    val isSynced: Boolean = false,
//    val isOffline: Boolean = false
    val syncStatus: String = "Synced" // Pending / Syncing / Synced

) {
    override fun equals(other: Any?): Boolean {
        return other is Transaction &&
            other.block == block
    }
}
