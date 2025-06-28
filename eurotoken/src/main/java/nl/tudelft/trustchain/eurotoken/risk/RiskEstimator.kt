package nl.tudelft.trustchain.eurotoken.risk

import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.db.TrustStore
import nl.tudelft.trustchain.eurotoken.db.VouchStore

class RiskEstimator(
    private val vouchStore: VouchStore,
    private val trustStore: TrustStore,
    private val transactionRepository: TransactionRepository,
) {
    /**
     * @param payerPubKey   who’s paying
     * @param amount        how much they promise
     * @return confidence ∈ [0.0..1.0]
     */
    fun riskEstimationFunction(
        amount: Long,
        payerPublicKey: ByteArray,
    ): Double {
        // Normalize the payer trust
        val payerBlock = transactionRepository.getLatestBlock(payerPublicKey)
            ?: return 0.0
        val payerBalance = transactionRepository.getMyVerifiedBalance()
        val payerPubKeyBytes: ByteArray = payerBlock.publicKey
        val rawTrustScore = trustStore.getScore(payerPubKeyBytes)?.toDouble() ?: 0.0
        val payerTrust = trustStore.normalizeTrustScoreSigmoid(rawTrustScore.toLong())
        // How much payer can cover
        var expCoverage = payerTrust * minOf(amount, payerBalance)
        var coverageByGuarantor = 0L
        val guarantorList = vouchStore.getGuarantorsForUser(payerBlock.publicKey, trustStore)
        // find how much can guarantors cover
        val now = System.currentTimeMillis()
        val validGuarantors =
            guarantorList.filter {
                it.vouchUntil == null || it.vouchUntil > now
            }
        for (g in validGuarantors) {
            coverageByGuarantor += g.trust * minOf(g.vouchAmount.toLong(), amount)
        }
        expCoverage += coverageByGuarantor
        val shortfall = maxOf(0.0, amount - expCoverage)
        // Find the percentage of shortcomings and dynamically choose lambda
        val lambda = lambdaForAmount(shortfall, amount)
        return kotlin.math.exp(-lambda * (shortfall / amount)).coerceIn(0.0, 1.0)
    }

    private fun lambdaForAmount(
        shortfall: Double,
        amount: Long,
        baseLambda: Double = 5.0,
        gamma: Double = 4.0
    ): Double {
        val confidence = (shortfall / amount).coerceIn(0.0, 1.0)
        return baseLambda * (1.0 + gamma * confidence)
    }
}
