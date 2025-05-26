package nl.tudelft.trustchain.eurotoken.entity

import java.util.Date
import java.util.UUID

data class Bond(
    val id: String = UUID.randomUUID().toString(),
    val amount: Long,
    val publicKeyLender: ByteArray,
    val publicKeyReceiver: ByteArray,
    val createdAt: Date,
    val expiredAt: Date,
    val transactionId: String,
    val status: BondStatus = BondStatus.ACTIVE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Bond

        if (amount != other.amount) return false
        if (id != other.id) return false
        if (!publicKeyLender.contentEquals(other.publicKeyLender)) return false
        if (!publicKeyReceiver.contentEquals(other.publicKeyReceiver)) return false
        if (createdAt != other.createdAt) return false
        if (expiredAt != other.expiredAt) return false
        if (transactionId != other.transactionId) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = amount.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + publicKeyLender.contentHashCode()
        result = 31 * result + publicKeyReceiver.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiredAt.hashCode()
        result = 31 * result + transactionId.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

enum class BondStatus { ACTIVE, RELEASED, FORFEITED }
