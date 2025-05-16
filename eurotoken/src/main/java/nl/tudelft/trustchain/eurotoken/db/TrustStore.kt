package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.eurotoken.sqldelight.Database
import nl.tudelft.trustchain.eurotoken.entity.TrustScore

/**
 * TrustStore stores the trust scores of other wallets.
 * When we receive EuroTokens, the sender also sends its latest
 * 50 public keys of transactions he/she made. For every public key,
 * a trust score is maintained in order to build the web of trust.
 */
class TrustStore(context: Context) {
    private val driver = AndroidSqliteDriver(Database.Schema, context, "eurotoken.db")
    private val database = Database(driver)

    /**
     * Maps the keys and accompanying trust scores out of the database into a kotlin [TrustScore] object.
     */
    private val messageMapper = {
            public_key: ByteArray,
            score: Long
        ->
        TrustScore(
            public_key,
            score.toInt()
        )
    }

    /**
     * Retrieve all [TrustScore]s from the database.
     */
    fun getAllScores(): List<TrustScore> {
        return database.dbTrustScoreQueries.getAll(messageMapper).executeAsList()
    }

    /**
     * Retrieve the [TrustScore]s of a specific public key.
     */
    fun getScore(publicKey: ByteArray): Long? {
        return database.dbTrustScoreQueries.getScore(publicKey).executeAsOneOrNull()
    }

    /**
     * Enumeration of available trust score update strategies.
     */
    enum class UpdateStrategy {
        LINEAR,
        DECAY_WEIGHTED,
        THRESHOLD_BOOST,
        LOGISTIC_CAP
    }

    /**
     * Insert a new [TrustScore] into the database.
     * If the score already exists, it will be updated according to the specified strategy.
     *
     * @param publicKey The public key to update the trust score for
     * @param strategy The update strategy to use (defaults to LINEAR)
     * @param params Optional parameters for the update strategy
     */
    fun incrementTrust(
        publicKey: ByteArray,
        strategy: UpdateStrategy = UpdateStrategy.LINEAR,
        params: Map<String, Double> = emptyMap()
    ) {
        val score: Long? = getScore(publicKey)

        if (score != null) {
            val currentScore = score.toInt()
            // If already at max score, don't increment
            if (currentScore >= MAX_SCORE) {
                return
            }

            val newScore = when (strategy) {
                UpdateStrategy.LINEAR -> linearUpdate(currentScore)
                UpdateStrategy.DECAY_WEIGHTED -> decayWeightedUpdate(currentScore, params)
                UpdateStrategy.THRESHOLD_BOOST -> thresholdBoostUpdate(currentScore, params)
                UpdateStrategy.LOGISTIC_CAP -> logisticCapUpdate(currentScore)
            }

            // We need to implement custom update methods since SQLDelight only provides increment by 1
            updateScoreDirectly(publicKey, newScore)
        } else {
            database.dbTrustScoreQueries.addScore(publicKey, 0)
        }
    }

    /**
     * Linear update strategy (original implementation):
     * score = score + 1
     */
    private fun linearUpdate(currentScore: Int): Int {
        return minOf(currentScore + 1, MAX_SCORE)
    }

    /**
     * Decay-weighted update strategy:
     * score = score + a*score + c
     * where 0 < a < 1
     *
     * @param currentScore The current trust score
     * @param params Map containing "a" (decay factor) and "c" (constant addition)
     * @return The updated score capped at MAX_SCORE
     */
    private fun decayWeightedUpdate(currentScore: Int, params: Map<String, Double>): Int {
        val a = params["a"] ?: DEFAULT_DECAY_FACTOR
        val c = params["c"] ?: DEFAULT_CONSTANT_ADDITION

        val increment = (a * currentScore + c).toInt()
        return minOf(currentScore + increment, MAX_SCORE)
    }

    /**
     * Threshold boost update strategy:
     * if count >= N then score = score + score + B
     *
     * @param currentScore The current trust score
     * @param params Map containing "N" (threshold) and "B" (boost amount)
     * @return The updated score capped at MAX_SCORE
     */
    private fun thresholdBoostUpdate(currentScore: Int, params: Map<String, Double>): Int {
        val threshold = params["N"]?.toInt() ?: DEFAULT_THRESHOLD
        val boost = params["B"]?.toInt() ?: DEFAULT_BOOST

        return if (currentScore >= threshold) {
            minOf(currentScore + currentScore + boost, MAX_SCORE)
        } else {
            minOf(currentScore + 1, MAX_SCORE)
        }
    }

    /**
     * Logistic cap update strategy:
     * score = score + score + 4*(1 - score/MAX_SCORE)
     *
     * @param currentScore The current trust score
     * @return The updated score capped at MAX_SCORE
     */
    private fun logisticCapUpdate(currentScore: Int): Int {
        val factor = 4 * (1 - currentScore.toDouble() / MAX_SCORE)
        val increment = (currentScore + factor).toInt()
        return minOf(currentScore + increment, MAX_SCORE)
    }

    /**
     * Updates the score directly with a new value.
     * We need this because SQLDelight only provides increment by 1.
     */
    private fun updateScoreDirectly(publicKey: ByteArray, newScore: Int) {
        // Update the score directly
        database.dbTrustScoreQueries.updateScore(newScore.toLong(), publicKey)
    }

    /**
     * Initialize the database.
     */
    fun createContactStateTable() {
        database.dbTrustScoreQueries.createContactStateTable()
    }

    companion object {
        private lateinit var instance: TrustStore
        
        // Constants for trust score calculations
        const val MAX_SCORE = 100
        const val DEFAULT_DECAY_FACTOR = 0.2
        const val DEFAULT_CONSTANT_ADDITION = 1.0
        const val DEFAULT_THRESHOLD = 20
        const val DEFAULT_BOOST = 5

        fun getInstance(context: Context): TrustStore {
            if (!::instance.isInitialized) {
                instance = TrustStore(context)
            }
            return instance
        }
    }
}
