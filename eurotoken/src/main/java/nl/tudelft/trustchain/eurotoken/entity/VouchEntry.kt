package nl.tudelft.trustchain.eurotoken.entity

/**
 * Represents a vouch entry for a user by public key.
 */
data class VouchEntry(
    val pubKey: ByteArray,
    var vouchAmount: Double,
    // Expiration timestamp (ms since epoch)
    var vouchUntil: Long? = null,
    var bondId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VouchEntry

        if (vouchAmount != other.vouchAmount) return false
        if (vouchUntil != other.vouchUntil) return false
        if (!pubKey.contentEquals(other.pubKey)) return false
        if (bondId != other.bondId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = vouchAmount.hashCode()
        result = 31 * result + (vouchUntil?.hashCode() ?: 0)
        result = 31 * result + pubKey.contentHashCode()
        result = 31 * result + bondId.hashCode()
        return result
    }
}
