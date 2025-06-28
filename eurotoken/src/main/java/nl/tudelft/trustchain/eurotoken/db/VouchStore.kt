package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.eurotoken.sqldelight.Database
import nl.tudelft.trustchain.eurotoken.entity.Vouch
import java.util.*
import nl.tudelft.trustchain.eurotoken.entity.Guarantor

/**
 * VouchStore manages the vouch commitments made by the user.
 * Vouches represent commitments to pay certain amounts for other users until set dates.
 * This store handles creating, updating, retrieving, and managing vouches.
 */
class VouchStore(
    context: Context
) {
    private val driver = AndroidSqliteDriver(Database.Schema, context, "eurotoken.db")
    private val database = Database(driver)

    init {
        // Ensure the vouches table is created when the store is initialized
        initializeDatabase()
    }

    private fun initializeDatabase() {
        try {
            // First try to create the table
            database.dbVouchQueries.createVouchTable()

            // Test if the table has the correct schema by trying a simple query
            database.dbVouchQueries.getAllActive(vouchMapper).executeAsList()
        } catch (e: Exception) {
            // If there's any error (table doesn't exist or wrong schema), recreate it
            try {
                database.dbVouchQueries.dropVouchTable()
                database.dbVouchQueries.createVouchTable()
            } catch (recreateError: Exception) {
                // If recreate fails, log and continue - table might be created on first use
                println("Warning: Could not initialize vouches table: ${recreateError.message}")
            }
        }
    }

    /**
     * Maps the database fields to a kotlin [Vouch] object.
     */
    private val vouchMapper = {
            // id is not used in the Vouch entity
            _: Long,
            vouched_for_pub_key: ByteArray,
            amount: Long,
            expiry_date: Long,
            created_date: Long,
            description: String,
            is_active: Long,
            is_received: Long,
            sender_pub_key: ByteArray?
        ->
        Vouch(
            vouchedForPubKey = vouched_for_pub_key,
            amount = amount,
            expiryDate = Date(expiry_date),
            createdDate = Date(created_date),
            description = description,
            isActive = is_active == 1L,
            isReceived = is_received == 1L,
            senderPubKey = sender_pub_key
        )
    }

    /**
     * Retrieve all vouches from the database.
     */
    fun getAllVouches(): List<Vouch> = database.dbVouchQueries.getAll(vouchMapper).executeAsList()

    /**
     * Retrieve all active vouches from the database.
     */
    fun getAllActiveVouches(): List<Vouch> = database.dbVouchQueries.getAllActive(vouchMapper).executeAsList()

    /**
     * Retrieve all active vouches created by this user.
     */
    fun getOwnActiveVouches(): List<Vouch> = database.dbVouchQueries.getOwnActive(vouchMapper).executeAsList()

    /**
     * Retrieve all active vouches received from other users.
     */
    fun getReceivedActiveVouches(): List<Vouch> = database.dbVouchQueries.getReceivedActive(vouchMapper).executeAsList()

    /**
     * Retrieve vouches for a specific public key.
     */
    fun getVouchesByPubKey(publicKey: ByteArray): List<Vouch> = database.dbVouchQueries.getByPubKey(publicKey, vouchMapper).executeAsList()

    /**
     * Retrieve active vouches for a specific public key.
     */
    fun getActiveVouchesByPubKey(publicKey: ByteArray): List<Vouch> =
        database.dbVouchQueries.getActiveByPubKey(publicKey, vouchMapper).executeAsList()

    /**
     * Add a new vouch to the database.
     */
    fun addVouch(vouch: Vouch) {
        database.dbVouchQueries.addVouch(
            vouched_for_pub_key = vouch.vouchedForPubKey,
            amount = vouch.amount,
            expiry_date = vouch.expiryDate.time,
            created_date = vouch.createdDate.time,
            description = vouch.description,
            is_active = if (vouch.isActive) 1L else 0L,
            is_received = if (vouch.isReceived) 1L else 0L,
            sender_pub_key = vouch.senderPubKey
        )
    }

    /**
     * Deactivate a vouch by its ID.
     */
    fun deactivateVouch(vouchId: Long) {
        database.dbVouchQueries.deactivateVouch(vouchId)
    }

    /**
     * Delete a vouch by its ID.
     */
    fun deleteVouch(vouchId: Long) {
        database.dbVouchQueries.deleteVouch(vouchId)
    }

    /**
     * Delete all expired vouches.
     */
    fun deleteExpiredVouches() {
        val currentTime = System.currentTimeMillis()
        database.dbVouchQueries.deleteExpiredVouches(currentTime)
    }

    /**
     * Get the total amount currently vouched for active vouches.
     */
    fun getGuarantorsForUser(
        userKey: ByteArray,
        trustStore: TrustStore,
        onlyActive: Boolean = true
    ): List<Guarantor> {
        val now = System.currentTimeMillis()
        val vouches = getActiveVouchesByPubKey(userKey)
        return vouches
            .filter {
                val until = it.expiryDate.time
                until > now
            }.mapNotNull { vouch ->
                vouch.senderPubKey?.let { pubKey ->
                    val trustScore = trustStore.getScore(pubKey) ?: 0L
                    Guarantor(
                        publicKey = pubKey,
                        trust = trustScore.toInt(),
                        vouchAmount = vouch.amount.toDouble(),
                        vouchUntil = vouch.expiryDate.time
                    )
                }
            }
    }

    /**
     * Get the total amount currently vouched by this user for others (excludes received vouches).
     */
    fun getTotalOwnVouchedAmount(): Long {
        val currentTime = System.currentTimeMillis()
        val result = database.dbVouchQueries.getTotalOwnVouchedAmount(currentTime) { amount -> amount ?: 0L }
        return result.executeAsOne()
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
