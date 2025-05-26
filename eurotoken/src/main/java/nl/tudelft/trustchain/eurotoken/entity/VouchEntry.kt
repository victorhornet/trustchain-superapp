package nl.tudelft.trustchain.eurotoken.entity

/**
 * Represents a vouch entry for a user by public key.
 */
data class VouchEntry(
    val pubKey: ByteArray,
    var vouchAmount: Double,
    var vouchUntil: Long? = null // Expiration timestamp (ms since epoch)
) 