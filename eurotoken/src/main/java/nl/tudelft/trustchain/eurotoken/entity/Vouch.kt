package nl.tudelft.trustchain.eurotoken.entity

import java.util.*

/**
 * The [Vouch] represents a commitment to pay a certain amount for another user until a set date.
 */
data class Vouch(
    /**
     * The public key of the user being vouched for.
     */
    val vouchedForPubKey: ByteArray,
    /**
     * The amount in cents that the voucher commits to pay.
     */
    val amount: Long,
    /**
     * The expiry date of the vouch.
     */
    val expiryDate: Date,
    /**
     * The date when the vouch was created.
     */
    val createdDate: Date,
    /**
     * Optional description or reason for the vouch.
     */
    val description: String = "",
    /**
     * Whether this vouch is still active.
     */
    val isActive: Boolean = true,
    /**
     * Whether this vouch was received from another user.
     */
    val isReceived: Boolean = false,
    /**
     * The public key of the user who sent this vouch (null for own vouches).
     */
    val senderPubKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Vouch

        if (!vouchedForPubKey.contentEquals(other.vouchedForPubKey)) return false
        if (amount != other.amount) return false
        if (expiryDate != other.expiryDate) return false
        if (createdDate != other.createdDate) return false
        if (description != other.description) return false
        if (isActive != other.isActive) return false
        if (isReceived != other.isReceived) return false
        if (senderPubKey != null) {
            if (other.senderPubKey == null) return false
            if (!senderPubKey.contentEquals(other.senderPubKey)) return false
        } else if (other.senderPubKey != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = vouchedForPubKey.contentHashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + expiryDate.hashCode()
        result = 31 * result + createdDate.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + isReceived.hashCode()
        result = 31 * result + (senderPubKey?.contentHashCode() ?: 0)
        return result
    }
}
