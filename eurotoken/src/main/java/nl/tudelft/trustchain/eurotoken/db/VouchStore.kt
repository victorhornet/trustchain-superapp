package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.eurotoken.sqldelight.Database
import nl.tudelft.trustchain.eurotoken.entity.VouchEntry

/**
 * VouchStore stores vouch information between users.
 * A vouch represents a user's willingness to vouch for another user
 * with a specific amount and optional expiration time.
 */
class VouchStore(context: Context) {
    private val driver = AndroidSqliteDriver(Database.Schema, context, "eurotoken.db")
    private val database = Database(driver)

    /**
     * Maps the database columns to a kotlin [VouchEntry] object.
     */
    private val vouchMapper = { 
        id: Long,
        voucher_public_key: ByteArray,
        vouchee_public_key: ByteArray,
        vouch_amount: Double,
        vouch_until: Long?,
        created_at: Long,
        updated_at: Long ->
        VouchEntry(
            vouchee_public_key,
            vouch_amount,
            vouch_until
        )
    }

    /**
     * Set or update a vouch from voucher to vouchee.
     * 
     * @param voucherKey The public key of the user making the vouch
     * @param voucheeKey The public key of the user being vouched for
     * @param amount The amount being vouched for
     * @param until Optional expiration timestamp (ms since epoch)
     */
    fun setVouch(voucherKey: ByteArray, voucheeKey: ByteArray, amount: Double, until: Long? = null) {
        val now = System.currentTimeMillis()
        database.dbVouchQueries.insertOrUpdateVouch(
            voucherKey, 
            voucheeKey, 
            amount, 
            until, 
            now, 
            now
        )
    }

    /**
     * Get a specific vouch between two users.
     * 
     * @param voucherKey The public key of the user making the vouch
     * @param voucheeKey The public key of the user being vouched for
     * @return The vouch entry or null if not found
     */
    fun getVouch(voucherKey: ByteArray, voucheeKey: ByteArray): VouchEntry? {
        return database.dbVouchQueries.getVouch(voucherKey, voucheeKey, vouchMapper)
            .executeAsOneOrNull()
    }

    /**
     * Get all vouches made by a specific user.
     * 
     * @param voucherKey The public key of the voucher
     * @return List of vouch entries
     */
    fun getVouchesByVoucher(voucherKey: ByteArray): List<VouchEntry> {
        return database.dbVouchQueries.getVouchesByVoucher(voucherKey, vouchMapper)
            .executeAsList()
    }

    /**
     * Get all vouches received by a specific user.
     * 
     * @param voucheeKey The public key of the vouchee
     * @return List of vouch entries
     */
    fun getVouchesByVouchee(voucheeKey: ByteArray): List<VouchEntry> {
        return database.dbVouchQueries.getVouchesByVouchee(voucheeKey, vouchMapper)
            .executeAsList()
    }

    /**
     * Delete a specific vouch between two users.
     * 
     * @param voucherKey The public key of the user making the vouch
     * @param voucheeKey The public key of the user being vouched for
     */
    fun deleteVouch(voucherKey: ByteArray, voucheeKey: ByteArray) {
        database.dbVouchQueries.deleteVouch(voucherKey, voucheeKey)
    }

    /**
     * Get all vouches in the database (for debugging/admin purposes).
     * 
     * @return List of all vouch entries
     */
    fun getAllVouches(): List<VouchEntry> {
        return database.dbVouchQueries.getAllVouches(vouchMapper)
            .executeAsList()
    }

    /**
     * Clean up expired vouches from the database.
     * Should be called periodically to maintain database hygiene.
     */
    fun cleanupExpiredVouches() {
        val now = System.currentTimeMillis()
        database.dbVouchQueries.deleteExpiredVouches(now)
    }

    /**
     * Update only the vouch amount for an existing vouch.
     * 
     * @param voucherKey The public key of the user making the vouch
     * @param voucheeKey The public key of the user being vouched for
     * @param amount The new vouch amount
     */
    fun updateVouchAmount(voucherKey: ByteArray, voucheeKey: ByteArray, amount: Double) {
        val now = System.currentTimeMillis()
        database.dbVouchQueries.updateVouchAmount(amount, now, voucherKey, voucheeKey)
    }

    /**
     * Update only the vouch expiration for an existing vouch.
     * 
     * @param voucherKey The public key of the user making the vouch
     * @param voucheeKey The public key of the user being vouched for
     * @param until The new expiration timestamp (ms since epoch), null for no expiration
     */
    fun updateVouchUntil(voucherKey: ByteArray, voucheeKey: ByteArray, until: Long?) {
        val now = System.currentTimeMillis()
        database.dbVouchQueries.updateVouchUntil(until, now, voucherKey, voucheeKey)
    }

    /**
     * Calculate aggregated vouch score for a user based on all vouches they received.
     * This considers the trust scores of the vouchers to weight the vouches.
     * 
     * @param userKey The public key of the user to calculate score for
     * @param trustStore The trust store to get voucher trust scores
     * @return Aggregated vouch score
     */
    fun getAggregatedVouchScore(userKey: ByteArray, trustStore: TrustStore): Double {
        val allVouches = getVouchesByVouchee(userKey)
        
        // Filter out expired vouches
        val now = System.currentTimeMillis()
        val validVouches = allVouches.filter { vouch ->
            val vouchUntil = vouch.vouchUntil
            vouchUntil == null || vouchUntil > now
        }
        
        // Calculate weighted sum based on voucher trust scores
        return validVouches.sumOf { vouch ->
            val voucherTrustScore = trustStore.getScore(vouch.pubKey) ?: 0L
            vouch.vouchAmount * (voucherTrustScore.toDouble() / TrustStore.MAX_SCORE)
        }
    }

    /**
     * Initialize the vouch table in the database.
     */
    fun createVouchTable() {
        database.dbVouchQueries.createVouchTable()
    }

    companion object {
        private lateinit var instance: VouchStore
        
        fun getInstance(context: Context): VouchStore {
            if (!::instance.isInitialized) {
                instance = VouchStore(context)
            }
            return instance
        }
    }
} 