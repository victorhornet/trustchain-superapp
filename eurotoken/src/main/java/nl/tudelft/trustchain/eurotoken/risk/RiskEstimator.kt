package nl.tudelft.trustchain.eurotoken.risk

import android.util.Log
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
    // In RiskEstimator.kt
    fun riskEstimationFunction(
        amount: Long,
        payerPublicKey: ByteArray,
    ): Double {
        val payerBalance = transactionRepository.getBalance(payerPublicKey)

        // Normalize the payer trust
        val rawTrustScore = trustStore.getScore(payerPublicKey)?.toDouble() ?: 0.0
        val payerTrust = trustStore.normalizeTrustScoreSigmoid(rawTrustScore.toLong())

        // How much payer can cover
        var expCoverage = payerTrust * minOf(amount.toDouble(), payerBalance.toDouble())
        var coverageByGuarantor = 0.0

        val guarantorList = vouchStore.getGuarantorsForUser(payerPublicKey, trustStore)
        val now = System.currentTimeMillis()
        val validGuarantors = guarantorList.filter {
            it.vouchUntil == null || it.vouchUntil > now
        }

        for (g in validGuarantors) {
            coverageByGuarantor += g.trust * minOf(g.vouchAmount.toDouble(), amount.toDouble())
        }

        expCoverage += coverageByGuarantor
        val shortfall = maxOf(0.0, amount - expCoverage)

        // Handle division by zero
        if (amount == 0L) return 1.0

        val lambda = lambdaForAmount(shortfall, amount.toDouble())
        Log.d("RiskEstimator", "rawTrustScore=$rawTrustScore, payerTrust=$payerTrust, " +
            "expCoverage=$expCoverage, coverageByGuarantor=$coverageByGuarantor, " +
            "payerBalance=$payerBalance")
        return kotlin.math.exp(-lambda * (shortfall / amount)).coerceIn(0.0, 1.0)
    }

    private fun lambdaForAmount(
        shortfall: Double,
        amount: Double,
        baseLambda: Double = 5.0,
        gamma: Double = 4.0
    ): Double {
        val confidence = (shortfall / amount).coerceIn(0.0, 1.0)
        return baseLambda * (1.0 + gamma * confidence)
    }
}
