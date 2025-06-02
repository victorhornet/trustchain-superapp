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
            purpose = "",
            isOneShot = true
        )
    }

    /**
     * Create a new bond or update an existing one.
     *
     * @param bond The bond to create or update
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
            purpose = "",
            is_one_shot = 1,
            updated_at = now
        )
    }

    /**
     * Get a specific bond by its ID.
     *wh
     * @param bondId The ID of the bond
     * @return The bond or null if not found
     */
    fun getBond(bondId: String): Bond? =
        database.dbBondQueries
            .getBond(bondId, bondMapper)
            .executeAsOneOrNull()

    /**
     * Get all bonds created by a specific user.
     *
     * @param lenderKey The public key of the lender
     * @return List of bonds
     */
    fun getBondsByLender(lenderKey: ByteArray): List<Bond> =
        database.dbBondQueries
            .getBondsByLender(lenderKey, bondMapper)
            .executeAsList()

    /**
     * Get all bonds received by a specific user.
     *
     * @param receiverKey The public key of the receiver
     * @return List of bonds
     */
    fun getBondsByReceiver(receiverKey: ByteArray): List<Bond> =
        database.dbBondQueries
            .getBondsByReceiver(receiverKey, bondMapper)
            .executeAsList()

    /**
     * Get all active bonds for a user (either as lender or receiver).
     *
     * @param userKey The public key of the user
     * @return List of active bonds
     */
    fun getActiveBondsByUserKey(userKey: ByteArray): List<Bonds> =
        database.dbBondQueries
            .getActiveBonds(
                userKey,
                BondStatus.ACTIVE.name,
                System.currentTimeMillis().toString(),
                bondMapper
            ).executeAsList()

    /**
     * Delete a specific bond.
     *
     * @param bondId The ID of the bond to delete
     */
    fun deleteBond(bondId: String) {
        database.dbBondQueries.deleteBond(bondId)
    }

    /**
     * Get all bonds in the database (for debugging/admin purposes).
     *
     * @return List of all bonds
     */
    fun getAllBonds(): List<Bond> =
        database.dbBondQueries
            .getAllBonds(bondMapper)
            .executeAsList()

    /**
     * Clean up expired bonds from the database.
     * Should be called periodically to maintain database hygiene.
     */
    fun cleanupExpiredBonds() {
        val now = System.currentTimeMillis()
        database.dbBondQueries.deleteExpiredBonds(now)
    }

    /**
     * Update the status of a bond.
     *
     * @param bondId The ID of the bond
     * @param status The new status
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
     *
     * @param userKey The public key of the user
     * @return Total locked amount
     */
    fun getTotalLockedAmount(userKey: ByteArray): Long = getActiveBondsByUserKey(userKey).sumOf { it.amount }

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
