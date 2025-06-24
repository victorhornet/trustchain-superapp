package nl.tudelft.trustchain.eurotoken.community

import android.content.Context
import android.util.Log
import kotlin.random.Random
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.Transaction
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.BLOCK_TYPE_ONE_SHOT_BOND
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.KEY_AMOUNT
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.KEY_BALANCE
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.KEY_BOND_EXPIRY
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.KEY_BOND_ID
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository.Companion.KEY_BOND_RECEIVER
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME
import nl.tudelft.trustchain.eurotoken.db.BondStore
import nl.tudelft.trustchain.eurotoken.db.TrustStore
import nl.tudelft.trustchain.eurotoken.db.VouchStore
import nl.tudelft.trustchain.eurotoken.entity.Bond
import nl.tudelft.trustchain.eurotoken.entity.BondStatus
import nl.tudelft.trustchain.eurotoken.entity.Vouch
import nl.tudelft.trustchain.eurotoken.ui.settings.DefaultGateway
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.math.BigInteger

class EuroTokenCommunity(
    store: GatewayStore,
    trustStore: TrustStore,
    vouchStore: VouchStore,
    context: Context,
) : Community() {
    override val serviceId = "f0eb36102436bd55c7a3cdca93dcaefb08df0750"

    private lateinit var transactionRepository: TransactionRepository

    /**
     * The [TrustStore] used to fetch and update trust scores from peers.
     */
    private var myTrustStore: TrustStore

    private var myBondStore: BondStore

    /**
     * The [VouchStore] used to fetch and update vouch information from peers.
     */
    private var myVouchStore: VouchStore

    /**
     * The context used to access the shared preferences.
     */
    private var myContext: Context

    init {
        messageHandlers[MessageId.ROLLBACK_REQUEST] = ::onRollbackRequestPacket
        messageHandlers[MessageId.ATTACHMENT] = ::onLastAddressPacket
        messageHandlers[MessageId.VOUCH_DATA] = ::onVouchDataPacket
        if (store.getPreferred().isEmpty()) {
            DefaultGateway.addGateway(store)
        }

        myTrustStore = trustStore
        myVouchStore = vouchStore
        myContext = context
        myBondStore = BondStore.getInstance(context)
    }

//    fun startCleanupTask() {
//        scope.launch {
//            while (true) {
//                delay(TimeUnit.HOURS.toMillis(1))
//                cleanupExpiredBonds()
//            }
//        }
//    }

    @JvmName("setTransactionRepository1")
    fun setTransactionRepository(transactionRepositoryLocal: TransactionRepository) {
        transactionRepository = transactionRepositoryLocal
//        addRollbackListener()
    }

    private fun onRollbackRequestPacket(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(RollbackRequestPayload.Deserializer)
        onRollbackRequest(peer, payload)
    }

//    private fun addRollbackListener() {
//        transactionRepository.trustChainCommunity.addListener(
//            TransactionRepository.BLOCK_TYPE_ROLLBACK,
//            object : BlockListener {
//                override fun onBlockReceived(block: TrustChainBlock) {
//                    // This is called when a rollback block is received and validated.
//                    // It indicates a previous transaction was invalid, possibly due to double-spending.
//                    handleTransactionRollback(block)
//                }
//            }
//        )
//    }
//
//    private fun handleTransactionRollback(rollbackBlock: TrustChainBlock) {
//        val myKey =
//            transactionRepository.trustChainCommunity.myPeer.publicKey
//                .keyToBin()
//
//        // A rollback block contains the hash of the transaction being rolled back.
//        val rolledBackTxHashHex =
//            rollbackBlock.transaction[TransactionRepository.KEY_TRANSACTION_HASH] as? String
//                ?: return
//
//        val rolledBackBlock =
//            try {
//                transactionRepository.trustChainCommunity.database.getBlockWithHash(rolledBackTxHashHex.hexToBytes())
//            } catch (e: Exception) {
//                null
//            } ?: return
//
//        // Determine the counterparty of the original transaction.
//        val counterpartyKey =
//            if (rolledBackBlock.publicKey.contentEquals(myKey)) {
//                rolledBackBlock.linkPublicKey // I sent the original transaction
//            } else {
//                rolledBackBlock.publicKey // I received the original transaction
//            }
//
//        // Find an active, one-shot bond from me (lender) to the counterparty (receiver)
//        // that could have been collateral for this transaction.
//        val rollbackDate = Date(rollbackBlock.timestamp.toLong() * 1_000L)
//        val bonds =
//            myBondStore
//                .getBondsByLender(myKey)
//                .filter {
//                    it.publicKeyReceiver.contentEquals(counterpartyKey) &&
//                        it.status == BondStatus.ACTIVE &&
//                        it.isOneShot &&
//                        it.createdAt.before(rollbackDate)
//                }
//
//        // Forfeit the most recent matching bond.
//        bonds.maxByOrNull { it.createdAt }?.let { bondToForfeit ->
//            myBondStore.updateBondStatus(bondToForfeit.id, BondStatus.FORFEITED)
//            Log.d("BondForfeiture", "Bond ${bondToForfeit.id} automatically forfeited due to transaction rollback (double-spend).")
//            // The bond amount is now permanently gone from the user's balance,
//            // as it was subtracted on creation and will not be returned.
// //            myTrustStore.decrementTrust(counterpartyKey, PENALTY_VALUE)
// //            Log.d("Trust", "Reduced trust for ${counterpartyKey.toHex()}")
//        }
//    }

    /**
     * Called upon receiving MessageId.ATTACHMENT packet.
     * Payload consists out of latest 50 public keys the sender transacted with.
     * This function parses the packet and increments the trust by 1 for every
     * key received.
     * Format is: '<key>, <key>, ..., <key>' where key is the hex-encoded public key.
     * @param packet : the corresponding packet that contains the right payload.
     */
    private fun onLastAddressPacket(packet: Packet) {
        val (_, payload) =
            packet.getDecryptedAuthPayload(
                TransactionsPayload.Deserializer,
                myPeer.key as PrivateKey
            )

        val addresses: List<ByteArray> = String(payload.data).split(",").map { it.toByteArray() }
        for (i in addresses.indices) {
            myTrustStore.incrementTrust(addresses[i])
        }
    }

    /**
     * Called upon receiving MessageId.VOUCH_DATA packet.
     * Payload consists of vouch data from the sender.
     * This function parses the packet and stores received vouches.
     * Format is JSON-encoded vouch data.
     * @param packet : the corresponding packet that contains the vouch payload.
     */
    private fun onVouchDataPacket(packet: Packet) {
        val (peer, payload) =
            packet.getDecryptedAuthPayload(
                VouchPayload.Deserializer,
                myPeer.key as PrivateKey
            )

        try {
            val vouchDataString = String(payload.data, Charsets.UTF_8)
            val vouchDataList = parseVouchData(vouchDataString)

            for (vouchData in vouchDataList) {
                val vouch =
                    Vouch(
                        vouchedForPubKey = vouchData.vouchedForPubKey,
                        amount = vouchData.amount,
                        expiryDate = vouchData.expiryDate,
                        createdDate = vouchData.createdDate,
                        description = vouchData.description,
                        isActive = vouchData.isActive,
                        isReceived = true,
                        senderPubKey = peer.publicKey.keyToBin()
                    )
                myVouchStore.addVouch(vouch)
            }
        } catch (e: Exception) {
            // Handle parsing errors gracefully
            println("Error parsing vouch data: ${e.message}")
        }
    }

//    fun sendTransferProposalWithRiskCheck(
//        receiver: ByteArray,
//        amount: Long
//    ): TrustChainBlock? {
//        // Get borrower's latest block
//        val borrowerBlock = getLatestBlock(receiver)
//
//        // Calculate risk
//        val risk = riskEstimator.riskEstimationFunction(amount, borrowerBlock)
//
//        // Show risk in UI (need callback to activity)
//        onRiskCalculated(risk)
//
//        if (risk < RISK_THRESHOLD) {
//            return transactionRepository.sendTransferProposalSync(receiver, amount)
//        }
//        return null
//    }

    private fun onRollbackRequest(
        peer: Peer,
        payload: RollbackRequestPayload
    ) {
        transactionRepository.attemptRollback(peer, payload.transactionHash)
    }

    fun connectToGateway(
        public_key: String,
        ip: String,
        port: Int,
        payment_id: String
    ) {
        val key = defaultCryptoProvider.keyFromPublicBin(public_key.hexToBytes())
        val address = IPv4Address(ip, port)
        val peer = Peer(key, address)

        val payload = MessagePayload(payment_id)

        val packet =
            serializePacket(
                MessageId.GATEWAY_CONNECT,
                payload
            )

        send(peer, packet)
    }

    fun requestRollback(
        transactionHash: ByteArray,
        peer: Peer
    ) {
        val payload = RollbackRequestPayload(transactionHash)

        val packet =
            serializePacket(
                MessageId.ROLLBACK_REQUEST,
                payload
            )

        send(peer, packet)
    }

    object MessageId {
        const val GATEWAY_CONNECT = 1
        const val ROLLBACK_REQUEST = 1
        const val ATTACHMENT = 4
        const val VOUCH_DATA = 5
    }

    class Factory(
        private val store: GatewayStore,
        private val trustStore: TrustStore,
        private val vouchStore: VouchStore,
        private val context: Context,
    ) : Overlay.Factory<EuroTokenCommunity>(EuroTokenCommunity::class.java) {
        override fun create(): EuroTokenCommunity = EuroTokenCommunity(store, trustStore, vouchStore, context)
    }

    /**
     * Generate a public key based on the [seed].
     * @param seed : the seed used to generate the public key.
     */
    private fun generatePublicKey(seed: Long): String {
        // Initialize Random with seed
        val random = Random(seed)

        // Generate a random public key of 148 hexadecimal characters
        val key = random.nextBytes(148)
        return key.toHex()
    }

    /**
     * Generate [numberOfKeys] public keys based on the [seed].
     * @param numberOfKeys : the number of keys to generate.
     * @param seed : the seed used to generate the public keys.
     */
    private fun generatePublicKeys(
        numberOfKeys: Int,
        seed: Long = 1337
    ): List<String> {
        val publicKeys = mutableListOf<String>()
        for (i in 0 until numberOfKeys) {
            publicKeys.add(generatePublicKey(seed + i))
        }
        return publicKeys
    }

    /**
     * Parse vouch data from JSON string format.
     * Format: "vouched_for_key|amount|expiry_date|created_date|description|is_active;..."
     */
    private fun parseVouchData(vouchDataString: String): List<Vouch> {
        val vouches = mutableListOf<Vouch>()
        if (vouchDataString.isBlank()) return vouches

        val vouchEntries = vouchDataString.split(";")
        for (entry in vouchEntries) {
            if (entry.isBlank()) continue
            try {
                val parts = entry.split("|")
                if (parts.size >= 6) {
                    val vouch =
                        Vouch(
                            vouchedForPubKey = parts[0].hexToBytes(),
                            amount = parts[1].toLong(),
                            expiryDate = Date(parts[2].toLong()),
                            createdDate = Date(parts[3].toLong()),
                            description = parts[4],
                            isActive = parts[5].toBoolean()
                        )
                    vouches.add(vouch)
                }
            } catch (e: Exception) {
                // Skip invalid entries
                continue
            }
        }
        return vouches
    }

    /**
     * Serialize vouches to string format.
     * Format: "vouched_for_key|amount|expiry_date|created_date|description|is_active;..."
     */
    private fun serializeVouchData(vouches: List<Vouch>): String =
        vouches.joinToString(";") { vouch ->
            listOf(
                vouch.vouchedForPubKey.toHex(),
                vouch.amount,
                vouch.expiryDate.time,
                vouch.createdDate.time,
                vouch.description,
                vouch.isActive
            ).joinToString("|")
        }

    /**
     * Send vouch data to a peer.
     * @param peer : the peer to send the vouch data to.
     * @param num : the maximum number of vouches to send.
     */
    fun sendVouchData(
        peer: Peer,
        num: Int = 10
    ) {
        val vouches = myVouchStore.getOwnActiveVouches().take(num)
        val vouchDataString = serializeVouchData(vouches)

        val payload = VouchPayload(EVAId.EVA_VOUCH_DATA, vouchDataString.toByteArray(Charsets.UTF_8))

        val packet =
            serializePacket(
                MessageId.VOUCH_DATA,
                payload,
                encrypt = true,
                recipient = peer
            )

        send(peer, packet)
    }

    /**
     * Called after the user has finished a transaction with the other party.
     * Sends the [num] public keys of latest transaction counterparties to the receiver.
     * When DEMO mode is enabled, it generates 50 random keys instead.
     * @param peer : the peer to send the keys to.
     * @param num : the number of keys to send.ir
     */
    fun sendAddressesOfLastTransactions(
        peer: Peer,
        num: Int = 50
    ) {
        val pref = myContext.getSharedPreferences(EUROTOKEN_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val demoModeEnabled = pref.getBoolean(DEMO_MODE_ENABLED, false)

        val addresses: ArrayList<String> = ArrayList()
        // Add own public key to list of addresses.
        addresses.add(myPeer.publicKey.keyToBin().toHex())
        if (demoModeEnabled) {
            // Generate [num] addresses if in demo mode
            addresses.addAll(generatePublicKeys(num))
        } else {
            // Get all addresses of the last [num] incoming transactions
            addresses.addAll(
                transactionRepository.getTransactions(num).map { transaction: Transaction ->
                    transaction.sender.toString()
                }
            )
        }

        val payload = TransactionsPayload(EVAId.EVA_LAST_ADDRESSES, addresses.joinToString(separator = ",").toByteArray())

        val packet =
            serializePacket(
                MessageId.ATTACHMENT,
                payload,
                encrypt = true,
                recipient = peer
            )

        send(peer, packet)
    }

    fun forfeitBond(bondId: String) {
        myBondStore.getBond(bondId)?.let {
            if (it.status == BondStatus.ACTIVE) {
                myBondStore.updateBondStatus(bondId, BondStatus.FORFEITED)
            }
        }
    }

    fun calculateBondHybrid(
        trustScore: Int,
        vouchAmount: Double
    ): Double {
        val base = 1.0
        val topUp = (100 - trustScore).coerceAtLeast(0) / 100.0
        return base + vouchAmount * topUp
    }

    fun getActiveBonds(): List<Bond> =
        myBondStore.getActiveBondsByUserKey(
            transactionRepository.trustChainCommunity.myPeer.publicKey
                .keyToBin()
        )

    fun getBondsByLender(): List<Bond> =
        myBondStore.getBondsByLender(
            transactionRepository.trustChainCommunity.myPeer.publicKey
                .keyToBin()
        )

    fun getBondsByReceiver(): List<Bond> =
        myBondStore.getBondsByReceiver(
            transactionRepository.trustChainCommunity.myPeer.publicKey
                .keyToBin()
        )

    fun createOneShotBond(
        receiver: ByteArray,
        // In cents (€0.01 units)
        amount: Long,
        expiryBlocks: Int = 1440
    ): TrustChainBlock? {
        // Verify sufficient spendable balance
        val spendable =
            getSpendableBalance(
                transactionRepository.trustChainCommunity.myPeer.publicKey
                    .keyToBin()
            )
        if (spendable < amount) {
            Log.w("Bond", "Insufficient balance for bond creation")
            return null
        }
        val txId = generateTxId()
        // Create bond record
        val bond =
            Bond(
                id = UUID.randomUUID().toString(),
                // Convert to euros
                amount = amount.toDouble() / 100,
                publicKeyLender =
                    transactionRepository.trustChainCommunity.myPeer.publicKey
                        .keyToBin(),
                publicKeyReceiver = receiver,
                createdAt = Date(),
                expiredAt =
                    Date(
                        System.currentTimeMillis() + expiryBlocks * 60_000
                    ),
                transactionId = txId,
                status = BondStatus.ACTIVE,
                purpose = "One-shot collateral",
                isOneShot = true
            )
        myBondStore.setBond(bond)

        // Create trust chain block
        val transaction =
            mapOf(
                TransactionRepository.KEY_AMOUNT to BigInteger.valueOf(amount),
                TransactionRepository.KEY_BOND_RECEIVER to receiver,
                TransactionRepository.KEY_BOND_ID to txId,
                TransactionRepository.KEY_BOND_EXPIRY to
                    (System.currentTimeMillis() + expiryBlocks * 60_000),
                TransactionRepository.KEY_BALANCE to
                    (transactionRepository.getMyBalance() - amount)
            )

        return transactionRepository.trustChainCommunity
            .createProposalBlock(
                TransactionRepository.BLOCK_TYPE_ONE_SHOT_BOND,
                transaction,
                receiver
            ).also { block ->
                Log.d("Bond", "Created one-shot bond: ${block.calculateHash().toHex()}")
            }
    }

    fun createVouchWithBond(
        vouchee: ByteArray,
        vouchAmount: Double,
        bondAmount: Long,
        expiryHours: Int = 24
    ): Boolean {
//        val riskScore =
//            riskEstimator.riskEstimationFunction(
//                amount = (vouchAmount * 100).toLong(),
//                payerBlock = getLatestBlock(vouchee) // Need to implement peer block fetch
//            )
//
//        // Only create vouch if risk is acceptable
//        if (riskScore < MIN_ACCEPTABLE_RISK) {
//            Log.w("Vouch", "Risk too high: ${"%.2f".format(riskScore * 100)}%")
//            return false
//        }
//
//        // Calculate bond amount based on risk
//        val trustScore = myTrustStore.getScore(vouchee) ?: 0
//        val bondAmount = calculateBondAmount(trustScore, vouchAmount)
        // Create bond
        val bondBlock =
            createOneShotBond(
                receiver = vouchee,
                amount = bondAmount,
                expiryBlocks = expiryHours * 60
            ) ?: return false

        myVouchStore.addVouch(
            Vouch(
                vouchedForPubKey = vouchee,
                amount = (vouchAmount * 100).toLong(),
                expiryDate =
                    Date(
                        System.currentTimeMillis() +
                            TimeUnit.HOURS.toMillis(expiryHours.toLong())
                    ),
                createdDate = Date(),
                description = "Bond ID: ${bondBlock.calculateHash().toHex()}",
                isActive = true,
                isReceived = false,
                senderPubKey = null
            )
        )

        return true
    }

    fun claimBond(bondId: String): Boolean {
        val bond = myBondStore.getBond(bondId) ?: return false

        return when {
            bond.status != BondStatus.ACTIVE -> {
                Log.w("Bond", "Bond $bondId is not active")
                false
            }
            bond.expiredAt.before(Date()) -> {
                myBondStore.updateBondStatus(bondId, BondStatus.FORFEITED)
                Log.w("Bond", "Bond $bondId expired")
                false
            }
            else -> {
                // Transfer funds
                val amountCents = (bond.amount * 100).toLong()
                transactionRepository
                    .sendTransferProposalSync(
                        bond.publicKeyReceiver,
                        amountCents
                    )?.let {
                        myBondStore.updateBondStatus(bondId, BondStatus.RELEASED)
                        Log.d("Bond", "Successfully claimed bond $bondId")
                        true
                    } ?: run {
                    Log.e("Bond", "Failed to transfer bond amount")
                    false
                }
            }
        }
    }

    fun getSpendableBalance(userKey: ByteArray): Long {
        val total = transactionRepository.getMyBalance()
        val locked = myBondStore.getTotalLockedAmount(userKey).toLong()
        return total - locked
    }

    fun enforceBond(
        bondId: String,
        lender: ByteArray
    ): Boolean {
        val bond = myBondStore.getBond(bondId) ?: return false

        return when {
            bond.status != BondStatus.ACTIVE -> false
            bond.expiredAt.before(Date()) -> {
                myBondStore.updateBondStatus(bondId, BondStatus.RELEASED)
                false
            }
            else -> {
                // Mark as claiming to temporarily release funds
                myBondStore.updateBondStatus(bondId, BondStatus.ACTIVE)

                val amountCents = (bond.amount * 100).toLong()
                val success = transactionRepository.sendTransferProposalSync(lender, amountCents) != null

                if (success) {
                    myBondStore.updateBondStatus(bondId, BondStatus.CLAIMED)
                } else {
                    // Forfeit on failure
                    myBondStore.updateBondStatus(bondId, BondStatus.FORFEITED)
                }
                success
            }
        }
    }

    fun cleanupExpiredBonds() {
        val now = System.currentTimeMillis()
        val myKey =
            transactionRepository.trustChainCommunity.myPeer.publicKey
                .keyToBin()

        // Get all active bonds for the current user
        val activeBonds = myBondStore.getActiveBondsByUserKey(myKey)

        // Update status for expired bonds
        activeBonds.forEach { bond ->
            if (bond.expiredAt.time < now && bond.status == BondStatus.ACTIVE) {
                myBondStore.updateBondStatus(bond.id, BondStatus.EXPIRED)
                Log.d("Bond", "Marked expired bond ${bond.id} as EXPIRED")
            }
        }

        // Clean up expired bonds from database
        myBondStore.cleanupExpiredBonds()
    }

    private fun generateTxId(): String = UUID.randomUUID().toString()

    /**
     * Every community initializes a different version of the EVA protocol (if enabled).
     * To distinguish the incoming packets/requests an ID must be used to hold/let through the
     * EVA related packets.
     */
    object EVAId {
        const val EVA_LAST_ADDRESSES = "eva_last_addresses"
        const val EVA_VOUCH_DATA = "eva_vouch_data"
    }
}
