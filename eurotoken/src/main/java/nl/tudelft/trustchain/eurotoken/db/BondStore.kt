package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.eurotoken.sqldelight.Database
import nl.tudelft.trustchain.eurotoken.entity.BondStatus
import java.util.Date
import nl.tudelft.trustchain.eurotoken.entity.Bond

/**
 * BondStore manages bond information between users.
 * A bond represents a locked amount of funds as collateral for vouching.
 */
class BondStore(
    context: Context
) {
    private val driver = AndroidSqliteDriver(Database.Schema, context, "eurotoken.db")
    private val database = Database(driver)

    /**
     * Maps the database columns to a kotlin [Bond] object.
     */
    private val bondMapper = {
            id: String,
            amount: Double,
            lender_public_key: ByteArray,
            receiver_public_key: ByteArray,
            created_at: Long,
            expired_at: Long,
            transaction_id: String,
            status: String,
            purpose: String,
            is_one_shot: Long,
            updated_at: Long
        ->
        Bond(
            id = id,
            amount = amount,
            publicKeyLender = lender_public_key,
            publicKeyReceiver = receiver_public_key,
            createdAt = Date(created_at),
            expiredAt = Date(expired_at),
            transactionId = transaction_id,
            status = BondStatus.valueOf(status),
            purpose = purpose,
            isOneShot = is_one_shot == 1L
        )
    }

    /**
     * Create a new bond or update an existing one.
     */
    fun setBond(bond: Bond) {
        val now = System.currentTimeMillis()
        database.dbBondQueries.insertOrUpdateBond(
            id = bond.id,
            amount = bond.amount,
            lender_public_key = bond.publicKeyLender,
            receiver_public_key = bond.publicKeyReceiver,
            created_at = bond.createdAt.time,
            expired_at = bond.expiredAt.time,
            transaction_id = bond.transactionId,
            status = bond.status.name,
            purpose = bond.purpose,
            is_one_shot = if (bond.isOneShot) 1L else 0L,
            updated_at = now
        )
    }

    /**
     * Get a specific bond by its ID.
     */
    fun getBond(bondId: String): Bond? =
        database.dbBondQueries
            .getBond(bondId, bondMapper)
            .executeAsOneOrNull()

    /**
     * Get all bonds created by a specific user.
     */
    fun getBondsByLender(lenderKey: ByteArray): List<Bond> =
        database.dbBondQueries
            .getBondsByLender(lenderKey, bondMapper)
            .executeAsList()

    /**
     * Get all bonds received by a specific user.
     */
    fun getBondsByReceiver(receiverKey: ByteArray): List<Bond> =
        database.dbBondQueries
            .getBondsByReceiver(receiverKey, bondMapper)
            .executeAsList()

    /**
     * Get all active bonds for a user (either as lender or receiver).
     */
    fun getActiveBondsByUserKey(userKey: ByteArray): List<Bond> =
        database.dbBondQueries
            .getActiveBondsByUserKey(
                userKey,
                userKey,
                BondStatus.ACTIVE.name,
                System.currentTimeMillis(),
                bondMapper
            ).executeAsList()

    /**
     * Delete a specific bond.
     */
    fun deleteBond(bondId: String) {
        database.dbBondQueries.deleteBond(bondId)
    }

    /**
     * Get all bonds in the database (for debugging/admin purposes).
     */
    fun getAllBonds(): List<Bond> =
        database.dbBondQueries
            .getAllBonds(bondMapper)
            .executeAsList()

    /**
     * Clean up expired bonds from the database.
     */
    fun cleanupExpiredBonds() {
        val now = System.currentTimeMillis()
        database.dbBondQueries.deleteExpiredBonds(now)
    }

    /**
     * Update the status of a bond.
     */
    fun updateBondStatus(
        bondId: String,
        status: BondStatus
    ) {
        val now = System.currentTimeMillis()
        database.dbBondQueries.updateBondStatus(status.name, now, bondId)
    }

    /**
     * Calculate total locked amount for a user (sum of all active bonds).
     */
    fun getTotalLockedAmount(userKey: ByteArray): Double = getActiveBondsByUserKey(userKey).sumOf { bond -> bond.amount }

    /**
     * Initialize the bond table in the database.
     */
    fun createBondTable() {
        database.dbBondQueries.createBondTable()
    }

    companion object {
        private lateinit var instance: BondStore

        fun getInstance(context: Context): BondStore {
            if (!::instance.isInitialized) {
                instance = BondStore(context)
            }
            return instance
        }
    }
}
