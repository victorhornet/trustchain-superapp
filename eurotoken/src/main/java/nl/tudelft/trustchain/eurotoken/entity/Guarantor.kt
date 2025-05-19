package nl.tudelft.trustchain.eurotoken.entity

data class Guarantor(
    val trust: Int,
    val balance: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Guarantor) return false

        if (trust != other.trust) return false
        if (balance != other.balance) return false

        return true
    }

    override fun hashCode(): Int {
        var result = trust.hashCode()
        result = 31 * result + balance.hashCode()
        return result
    }
}
