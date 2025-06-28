package nl.tudelft.trustchain.common.eurotoken

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainStore
import nl.tudelft.ipv8.attestation.trustchain.validation.TransactionValidator
import nl.tudelft.ipv8.attestation.trustchain.validation.ValidationResult
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.trustchain.common.bitcoin.WalletService
import nl.tudelft.trustchain.common.eurotoken.blocks.EuroTokenCheckpointValidator
import nl.tudelft.trustchain.common.eurotoken.blocks.EuroTokenDestructionValidator
import nl.tudelft.trustchain.common.eurotoken.blocks.EuroTokenRollBackValidator
import nl.tudelft.trustchain.common.eurotoken.blocks.EuroTokenTransferValidator
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.wallet.SendRequest
import nl.tudelft.trustchain.common.util.TrustChainHelper
import java.lang.Math.abs
import java.math.BigInteger
import java.util.SortedMap
import nl.tudelft.ipv8.attestation.trustchain.payload.HalfBlockPayload
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import android.content.Context
import java.util.Date
import nl.tudelft.trustchain.common.eurotoken.benchmarks.OfflineBlockSyncDao
import nl.tudelft.trustchain.common.eurotoken.benchmarks.OfflineBlockSyncState
import nl.tudelft.trustchain.common.eurotoken.worker.SyncWorker

class TransactionRepository(
    val trustChainCommunity: TrustChainCommunity,
    val gatewayStore: GatewayStore,
    private val offlineBlockSyncDao: OfflineBlockSyncDao? = null,
    private val context: Context? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun getGatewayPeer(): Peer? {
        return gatewayStore.getPreferred().getOrNull(0)?.peer
    }

    /*
      creates a transfer proposal block WITHOUT sending it over the network
     */
    fun createNfcTransferProposal(recipientKey: ByteArray, amount: Long): TrustChainBlock? {
        val balance1 = getMyBalance()
        val verifiedBalance = getMyVerifiedBalance()

        Log.e("TransactionRepository", "balance traditional: $balance1 balanceverified: $verifiedBalance")
        if (getMyVerifiedBalance() - amount < 0) {
            Log.e("TransactionRepository", "Insufficient balance. Need: $amount")
            return null
        }
        Log.d("TransactionRepository", "Creating offline proposal: amount=$amount")

        // had to change in the same way as the validator does it
        // otherwise annoying errors in balalance verification/discrepancies
        val myPublicKey = trustChainCommunity.myPeer.publicKey.keyToBin()
        val latestBlock = trustChainCommunity.database.getLatest(myPublicKey)

        val previousBalance = if (latestBlock != null) {
            getBalanceForBlock(latestBlock, trustChainCommunity.database) ?: run {
                Log.e("TransactionRepository", "Could not get balance from latest block")
                return null
            }
        } else {
            // Genesis case
            initialBalance
        }

        val newBalance = previousBalance - amount

        Log.d("TransactionRepository", "Previous balance: $previousBalance, new balance: $newBalance")

        val transaction = mapOf(
            KEY_AMOUNT to BigInteger.valueOf(amount),
            KEY_BALANCE to newBalance,
            "offline" to true
        )

        Log.d("NFC_DEBUG_SENDER", "Data for Proposal Creation: $transaction")

        return try {
            // In contrast to transferproposal we create the block but dont actually send it
            val block = trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_TRANSFER,
                transaction,
                recipientKey
            )

            val dataToSign = block.calculateHash()
            Log.d("SignatureCreation", "--- SIGNATURE CREATION ---")
            Log.d("SignatureCreation", "Block ID: ${block.blockId}")
            Log.d("SignatureCreation", "Data to be Signed (toHex): ${dataToSign.toHex()}")
            Log.d("SignatureCreation", "Signature (toHex):         ${block.signature.toHex()}")
            Log.d("SignatureCreation", "Public Key (toHex):        ${block.publicKey.toHex()}")
            Log.d("SignatureCreation", "---------------------------")

            Log.d("TransactionRepository", "Created proposal block: ${block.blockId}")
            block
        } catch (e: RuntimeException) {
            Log.e("TransactionRepository", "Failed to create proposal block due to validation error", e)
            null
        }
    }

    /*
      creates an agreement block for a received proposal WITHOUT sending it over the network.

     */
    fun createAgreementBlock(proposalBlock: TrustChainBlock): TrustChainBlock? {
        try {
            // validate the proposal
            if (proposalBlock.type != BLOCK_TYPE_TRANSFER) {
                Log.e("createAgreementBlock", "Invalid block type: ${proposalBlock.type}")
                return null
            }

            if (!proposalBlock.transaction.containsKey(KEY_AMOUNT)) {
                Log.e("createAgreementBlock", "Proposal missing amount")
                return null
            }

            // create the agreement block
            val agreementBlock = trustChainCommunity.createAgreementBlock(
                proposalBlock,
                proposalBlock.transaction
            )

            Log.d("createAgreementBlock", "Successfully created agreement block")
            return agreementBlock
        } catch (e: Exception) {
            Log.e("createAgreementBlock", "Failed to create agreement block", e)
            return null
        }
    }

    // returns either synced / syncing / pending
    // necessary for eventual stransactionitemrenderer, ui
    // syncworker
    suspend fun getTransactionStatus(blockHash: String): String {
        val syncState = offlineBlockSyncDao?.getSyncState(blockHash)
        return when {
            syncState == null -> "Unknown"
            syncState.isSynced -> "Synced"
            syncState.status == "Offline" && isNetworkAvailable() -> {
                offlineBlockSyncDao?.updateSyncState(syncState.copy(status = "Pending"))
                "Pending"
            }
            else -> syncState.status
        }
    }

    private fun isNetworkAvailable(): Boolean {
        // connnected -> if we have peers
        return trustChainCommunity.getPeers().isNotEmpty()
    }

    // before creating an agreement block we have to ensure that the proposal block is valid
    fun validateTransferProposal(
        proposalBlock: TrustChainBlock,
        expectedAmount: Long,
        expectedRecipient: ByteArray
    ): Boolean {
        // check block type
        if (proposalBlock.type != BLOCK_TYPE_TRANSFER) {
            Log.e("TransactionRepository", "Invalid block type: ${proposalBlock.type}")
            return false
        }

        // shouldnt be an agreement
        if (!proposalBlock.isProposal) {
            Log.e("TransactionRepository", "Block is not a proposal")
            return false
        }

        // check amount
        val blockAmount = (proposalBlock.transaction[KEY_AMOUNT] as? BigInteger)?.toLong()
        if (blockAmount != expectedAmount) {
            Log.e("TransactionRepository", "Amount mismatch: expected=$expectedAmount, actual=$blockAmount")
            return false
        }

        // check recipient
        if (!proposalBlock.linkPublicKey.contentEquals(expectedRecipient)) {
            Log.e("TransactionRepository", "Recipient mismatch")
            return false
        }

        // verify signature
        if (!validateBlockSignature(proposalBlock)) {
            Log.e("TransactionRepository", "Invalid block signature")
            return false
        }

        Log.d("TransactionRepository", "Proposal validation successful")
        return true
    }

    // lets attempt to broadcast all blocks that are marked as unsynced in the database
    // called by syncworker if periodic background exec
    // we find recipients and send their blocks
    fun syncOfflineTransactions() {
        scope.launch {
            val unsyncedHashes = offlineBlockSyncDao?.getUnsyncedBlockHashes() ?: run {
                Log.d("Repo/Sync", "No DAO available for sync")
                return@launch
            }

            if (unsyncedHashes.isEmpty()) {
                Log.d("Repo/Sync", "No unsynced blocks found")
                return@launch
            }

            Log.d("Repo/Sync", "Found ${unsyncedHashes.size} unsynced blocks. Starting sync...")

            var successCount = 0
            var failureCount = 0

            for (hash in unsyncedHashes) {
                try {
                    // we are syncing
                    offlineBlockSyncDao.updateSyncState(OfflineBlockSyncState(hash, "Syncing"))

                    val block = trustChainCommunity.database.getBlockWithHash(hash.hexToBytes())
                    if (block == null) {
                        Log.w("Repo/Sync", "Block $hash not found in DB. Marking as synced to ignore.")
                        offlineBlockSyncDao.markAsSynced(hash)
                        successCount++
                        continue
                    }

                    val peer = findPeerForBlock(block)

                    if (peer != null) {
                        Log.d("Repo/Sync", "Sending block ${block.blockId} to peer")
                        trustChainCommunity.sendBlock(block, peer)

                        // got race conditions
                        // so for more reliability we wait a bit
                        delay(500)

                        // Mark as synced on successful send
                        offlineBlockSyncDao.markAsSynced(hash)
                        Log.i("Repo/Sync", "Successfully synced block: $hash")
                        successCount++

                        // notifyTransactionUpdate()
                        // already polled in transactionsfragment
                        // TODO would be nicer, no priority for now
                    } else {
                        // no peer found? mark as pending
                        offlineBlockSyncDao.updateSyncState(OfflineBlockSyncState(hash, "Pending"))
                        Log.w("Repo/Sync", "Peer for block $hash not found. Will retry later.")
                        failureCount++
                    }
                } catch (e: Exception) {
                    Log.e("Repo/Sync", "Error syncing block $hash", e)
                    // error? mark as pending
                    offlineBlockSyncDao.updateSyncState(OfflineBlockSyncState(hash, "Pending"))
                    failureCount++
                }
            }

            Log.d("Repo/Sync", "Sync completed: $successCount successful, $failureCount failed")

            if (failureCount == 0) {
                Log.d("Repo/Sync", "All blocks synced successfully")
            }
        }
    }

    private fun findPeerForBlock(block: TrustChainBlock): Peer? {
        val targetPublicKey = when {
            block.isProposal -> {
                Log.d("Repo/Sync", "Block is proposal, targeting recipient: ${block.linkPublicKey.toHex().take(8)}...")
                block.linkPublicKey
            }
            block.isAgreement -> {
                Log.d("Repo/Sync", "Block is agreement, targeting proposer: ${block.publicKey.toHex().take(8)}...")
                block.publicKey
            }
            else -> {
                Log.w("Repo/Sync", "Unknown block type for syncing")
                return null
            }
        }

        // lets look for peers in the trustchain
        val connectedPeer = trustChainCommunity.getPeers()
            .find { it.publicKey.keyToBin().contentEquals(targetPublicKey) }

        if (connectedPeer != null) {
            Log.d("Repo/Sync", "Found target peer in connected peers")
            return connectedPeer
        }

        // found so creating peer
        return try {
            val key = defaultCryptoProvider.keyFromPublicBin(targetPublicKey)
            val peer = Peer(key)
            Log.d("Repo/Sync", "Created peer from public key for discovery")
            peer
        } catch (e: Exception) {
            Log.e("Repo/Sync", "Could not create peer from public key", e)
            null
        }
    }

    fun serializeBlock(block: TrustChainBlock): ByteArray {
        val payload = HalfBlockPayload.fromHalfBlock(block, sign = true)
        return payload.serialize()
    }

    fun deserializeBlock(data: ByteArray): TrustChainBlock? {
        return try {
            val (payload, _) = HalfBlockPayload.deserialize(data)
            TrustChainBlock(
                type = payload.blockType,
                rawTransaction = payload.transaction,
                publicKey = payload.publicKey,
                sequenceNumber = payload.sequenceNumber.toUInt(),
                linkPublicKey = payload.linkPublicKey,
                linkSequenceNumber = payload.linkSequenceNumber.toUInt(),
                previousHash = payload.previousHash,
                signature = payload.signature,
                timestamp = Date(payload.timestamp.toLong())
            )
        } catch (e: Exception) {
            Log.e("TransactionRepository", "Failed to deserialize block", e)
            null
        }
    }

    // lets store in db
    fun storeOfflineBlock(block: TrustChainBlock) {
        scope.launch {
            try {
                trustChainCommunity.database.addBlock(block)
                Log.d("Repo/Offline", "Stored offline block: ${block.blockId}")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                Log.d("Repo/Offline", "Block already exists: ${block.blockId}")
            }

            // we need to track it in our sync table
            try {
                val blockHash = block.calculateHash().toHex()
                val hasConnectedPeers = trustChainCommunity.getPeers().isNotEmpty()

                val initialStatus = "Pending"

                val syncState = OfflineBlockSyncState(
                    blockHash = blockHash,
                    status = initialStatus,
                    isSynced = false
                )
                offlineBlockSyncDao?.insertSyncState(syncState)
                Log.d("Repo/Offline", "Created sync tracking record with status: $initialStatus")

                // immediate sync and schedule periodic sync
                // no null pointer exception here, since context is not null
                // fixes errors
                if (context != null) {
                    SyncWorker.scheduleImmediateSync(context)
                    SyncWorker.schedulePeriodicSync(context)
                }
            } catch (e: Exception) {
                Log.w("Repo/Offline", "Sync state may already exist", e)
            }
        }
    }

    private fun validateBlockSignature(block: TrustChainBlock): Boolean {
        Log.d("NFC_DEBUG_RECEIVER", "--- Block Data Before Verification ---")
        Log.d("NFC_DEBUG_RECEIVER", "Block Type: ${block.type}, Seq: ${block.sequenceNumber}")
        Log.d("NFC_DEBUG_RECEIVER", "Transaction: ${block.transaction}")
        Log.d("NFC_DEBUG_RECEIVER", "Public Key: ${block.publicKey.toHex()}")
        Log.d("NFC_DEBUG_RECEIVER", "Link Public Key: ${block.linkPublicKey.toHex()}")
        Log.d("NFC_DEBUG_RECEIVER", "Previous Hash: ${block.previousHash.toHex()}")
        Log.d("NFC_DEBUG_RECEIVER", "Timestamp: ${block.timestamp.time}")

        try {
            val payload = HalfBlockPayload.fromHalfBlock(block, false).serialize()
            Log.d("NFC_DEBUG_RECEIVER", "ACTUAL DATA TO VERIFY (Hex): ${payload.toHex()}")
            Log.d("NFC_DEBUG_RECEIVER", "SIGNATURE TO VERIFY (Hex): ${block.signature.toHex()}")
            Log.d("NFC_DEBUG_RECEIVER", "--------------------------------------")

            val key = defaultCryptoProvider.keyFromPublicBin(block.publicKey)
            val isValid = key.verify(block.signature, payload)

            if (!isValid) {
                Log.e("SignatureValidation", "--- SIGNATURE VALIDATION FAILED ---")
                Log.e("SignatureValidation", "Block ID: ${block.blockId}")
                Log.e("SignatureValidation", "Block Type: ${block.type}, SeqNum: ${block.sequenceNumber}")
                Log.e("SignatureValidation", "Actual Signed Data (toHex): ${payload.toHex()}")
                Log.e("SignatureValidation", "Signature (toHex):   ${block.signature.toHex()}")
                Log.e("SignatureValidation", "Public Key (toHex):  ${block.publicKey.toHex()}")
                Log.e("SignatureValidation", "------------------------------------")
            }

            return isValid
        } catch (e: Exception) {
            Log.e("SignatureValidation", "Exception during signature validation", e)
            return false
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun getBalanceChangeForBlock(block: TrustChainBlock?): Long {
        if (block == null) return 0
        return if (
            (listOf(BLOCK_TYPE_TRANSFER).contains(block.type) && block.isProposal) ||
            (listOf(BLOCK_TYPE_ROLLBACK).contains(block.type) && block.isProposal) ||
            (listOf(BLOCK_TYPE_DESTROY).contains(block.type) && block.isProposal)
        ) {
            // block is sending money
            -(block.transaction[KEY_AMOUNT] as BigInteger).toLong()
        } else if (
            (listOf(BLOCK_TYPE_TRANSFER).contains(block.type) && block.isAgreement) ||
            (listOf(BLOCK_TYPE_CREATE).contains(block.type) && block.isAgreement)
        ) {
            // block is receiving money
            (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
            // block.transaction[KEY_AMOUNT] as Long
        } else {
            // block does nothing
            0
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun crawlForLinked(block: TrustChainBlock): TrustChainBlock? {
        val range =
            LongRange(
                block.sequenceNumber.toLong(),
                block.sequenceNumber.toLong()
            )
        val peer =
            trustChainCommunity.getPeers()
                .find { it.publicKey.keyToBin().contentEquals(block.publicKey) }
                ?: Peer(defaultCryptoProvider.keyFromPublicBin(block.linkPublicKey))
        // Should only be run when receiving blocks, not when sending
        val blocks =
            runBlocking {
                trustChainCommunity.sendCrawlRequest(
                    peer,
                    block.publicKey,
                    range,
                    forHalfBlock = block
                )
            }
        if (blocks.isEmpty()) return null // No connection partial previous
        return blocks.find { // linked block
            it.publicKey.contentEquals(block.linkPublicKey) &&
                it.sequenceNumber == block.linkSequenceNumber
        } ?: block // no linked block exists
    }

    fun ensureCheckpointLinks(
        block: TrustChainBlock,
        database: TrustChainStore
    ) {
        if (block.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) return // no need to crawl own chain
        val blockBefore = database.getBlockWithHash(block.previousHash)
        if (BLOCK_TYPE_CHECKPOINT == block.type && block.isProposal) {
            // block could verify balance
            if (database.getLinked(block) != null) { // verified
                return
            } else { // gateway verification is missing
                val linked =
                    crawlForLinked(block) // try to crawl for it
                        ?: return // peer didnt repond, TODO: Store this detail to make verification work better
                if (linked == block) { // No linked block exists in peer, so they sent the transaction based on a different checkpoint
                    ensureCheckpointLinks(blockBefore ?: return, database) // check next
                } else {
                    return // linked
                }
            }
        } else {
            ensureCheckpointLinks(blockBefore ?: return, database)
        }
    }

    fun getVerifiedBalanceForBlock(
        block: TrustChainBlock?,
        database: TrustChainStore
    ): Long? {
        if (block == null) {
            Log.d("getVerifiedBalanceForBl", "Found null block!")
            return null
        } // Missing block
        Log.d("getVerifiedBalanceForBl", "Found block with ID ${block.blockId}")
        if (!EUROTOKEN_TYPES.contains(block.type)) {
            Log.d("EuroTokenBlock", "Validation, not eurotoken ")
            return getVerifiedBalanceForBlock(
                database.getBlockWithHash(
                    block.previousHash
                ),
                database
            )
        }
        if (BLOCK_TYPE_CHECKPOINT == block.type && block.isProposal) {
            // block contains balance but linked block determines verification
            if (database.getLinked(block) != null) { // verified
                Log.d("EuroTokenBlock", "Validation, valid checkpoint returning")
                return (block.transaction[KEY_BALANCE] as Long)
            } else {
                Log.d("EuroTokenBlock", "Validation, checkpoint missing acceptance")
                return getVerifiedBalanceForBlock(
                    database.getBlockWithHash(block.previousHash),
                    database
                )
            }
        } else if (listOf(
                BLOCK_TYPE_TRANSFER,
                BLOCK_TYPE_CREATE
            ).contains(block.type) && block.isAgreement
        ) {
            // block is receiving money, but balance is not verified, just recurse
            Log.d("EuroTokenBlock", "Validation, receiving money")
            return getVerifiedBalanceForBlock(
                database.getBlockWithHash(block.previousHash),
                database
            )
        } else if (listOf(BLOCK_TYPE_TRANSFER, BLOCK_TYPE_DESTROY, BLOCK_TYPE_ROLLBACK).contains(
                block.type
            ) && block.isProposal
        ) {
            Log.d("EuroTokenBlock", "Validation, sending money")
            if (block.isGenesis) {
                return block.transaction[KEY_BALANCE] as Long
            }
            // block is sending money, but balance is not verified, subtract transfer amount and recurse
            val amount = (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
            return getVerifiedBalanceForBlock(
                database.getBlockWithHash(block.previousHash),
                database
            )?.minus(
                amount
            )
        } else {
            // bad type that shouldn't exist, for now just ignore and return for next
            Log.d("EuroTokenBlock", "Validation, bad type")
            return getVerifiedBalanceForBlock(
                database.getBlockWithHash(block.previousHash),
                database
            )
        }
    }

    fun getBalanceForBlock(
        block: TrustChainBlock?,
        database: TrustChainStore
    ): Long? {
        if (block == null) {
//            Log.d("getBalanceForBlock", "Found null block!")
            Log.e("getBalanceForBlock", "FATAL: getBalanceForBlock called with null. Returning initial balance.")
            // temporary fix, should be readjusted#TODO
//            return null
            return initialBalance
        } // Missing block
        Log.d("getBalanceForBlock", "Found block with ID: ${block.blockId}")

        // helper function to get previousBalance instead of NULL
        fun getPreviousBalance(currentBlock: TrustChainBlock): Long? {
            val previousBlock = database.getBlockWithHash(currentBlock.previousHash)
            if (previousBlock == null) {
                Log.w("getBalanceForBlock", "Could not find previous block for block with Seq: ${currentBlock.sequenceNumber}.")
                if (currentBlock.isGenesis) {
                    Log.i("getBalanceForBlock", "This is the GENESIS block. Traversal finished as expected.")
                } else {
                    Log.e("getBalanceForBlock", "This is NOT genesis. The local blockchain is BROKEN.")
                }
                return initialBalance
            }
            return getBalanceForBlock(previousBlock, database)
        }

        if (!EUROTOKEN_TYPES.contains(block.type)) {
//            return getBalanceForBlock(
//                database.getBlockWithHash(
//                    block.previousHash
//                ),
//                database
//            )
            // readjusted #TODO change back
            return getPreviousBalance(block)
        }
        return if ( // block contains balance (base case)
            (
            listOf(
                BLOCK_TYPE_TRANSFER,
                BLOCK_TYPE_DESTROY,
                BLOCK_TYPE_CHECKPOINT,
                BLOCK_TYPE_ROLLBACK
            ).contains(block.type) && block.isProposal
            )
        ) {
            (block.transaction[KEY_BALANCE] as Long)
        } else if (listOf(
                BLOCK_TYPE_TRANSFER,
                BLOCK_TYPE_CREATE
            ).contains(block.type) && block.isAgreement
        ) {
            // block is receiving money add it and recurse
            if (block.isGenesis) {
                return initialBalance + (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
            }
//            getBalanceForBlock(database.getBlockWithHash(block.previousHash), database)?.plus(
//                (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
//            )
            // readjusted #TODO
            getPreviousBalance(block)?.plus(
                (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
            )
        } else {
            // bad type that shouldn't exist, for now just ignore and return for next
//            getBalanceForBlock(database.getBlockWithHash(block.previousHash), database)
            // readjusted #TODO
            getPreviousBalance(block)
        }
    }

    fun getMyVerifiedBalance(): Long {
        Log.d("PEERDISCOVERY", "${trustChainCommunity.getPeers()}")
        val myPublicKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
        val latestBlock = trustChainCommunity.database.getLatest(myPublicKey)
        val myVerifiedBalance =
            getVerifiedBalanceForBlock(latestBlock, trustChainCommunity.database)
        if (latestBlock == null || myVerifiedBalance == null) {
            Log.d("getMyVerifiedBalance", "no latest block, defaulting to initial balance")
            return initialBalance
        }
        Log.d("getMyVerifiedBalance", "balance = $myVerifiedBalance")
        return myVerifiedBalance
    }

    fun getMyBalance(): Long {
        val myPublicKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
        val latestBlock = trustChainCommunity.database.getLatest(myPublicKey)
        if (latestBlock == null) {
            Log.d("getMyBalance", "no latest block, defaulting to initial balance")
            return initialBalance
        }
        Log.d("getMyBalance", "latest block found")
        val myBalance = getBalanceForBlock(latestBlock, trustChainCommunity.database)
        if (myBalance == null) {
            Log.d("getMyBalance", "no balance found, defaulting to initial balance")
            return initialBalance
        }
        Log.d("getMyBalance", "balance = $myBalance")
        return myBalance
    }

    fun sendTransferProposal(
        recipient: ByteArray,
        amount: Long
    ): Boolean {
        Log.d("sendTransferProposal", "sending amount: $amount")
        if (getMyBalance() - amount < 0) {
            return false
        }
        scope.launch {
            sendTransferProposalSync(recipient, amount)
        }
        return true
    }

    fun sendTransferProposalSync(
        recipient: ByteArray,
        amount: Long
    ): TrustChainBlock? {
        // CAUSED A validation conflict recalculates the balance
//        if (getMyBalance() - amount < 0) {
//            return null
//        }

        val currentBalance = getMyBalance()
        val newBalance = currentBalance - amount

        Log.d("sendTransferProposalSync", "sending amount: $amount")

        if (currentBalance < amount) {
            Log.e("BlockCreateDebug", "Insufficient balance. Have: $currentBalance, Need: $amount")
            return null
        }

        val transaction: SortedMap<String, Any> = sortedMapOf(
            KEY_AMOUNT to BigInteger.valueOf(amount),
            KEY_BALANCE to newBalance
        )

        Log.d("NFC-DEBUG", "Transaction data to be signed: $transaction")

        Log.d("BlockCreateDebug", "==================================================")
        Log.d("BlockCreateDebug", "---- Attempting to create new transaction block ----")
        Log.d("BlockCreateDebug", "Current Wallet Balance: $currentBalance")
        Log.d("BlockCreateDebug", "Transaction Amount: $amount")
        Log.d("BlockCreateDebug", "New Calculated Balance: $newBalance")
        Log.d("BlockCreateDebug", "Recipient Public Key (Hex): ${recipient.toHex()}")
        Log.d("BlockCreateDebug", "Transaction Data Map to be signed: $transaction")

        val latestBlock = trustChainCommunity.database.getLatest(trustChainCommunity.myPeer.publicKey.keyToBin())
        if (latestBlock != null) {
            Log.d("BlockCreateDebug", "Latest own block (previous block) info:")
            Log.d("BlockCreateDebug", "  - Sequence #: ${latestBlock.sequenceNumber}")
            Log.d("BlockCreateDebug", "  - Hash (Hex): ${latestBlock.calculateHash().toHex()}")
            Log.d("BlockCreateDebug", "  - Type: ${latestBlock.type}")
        } else {
            Log.w("BlockCreateDebug", "Could not find any previous blocks for self. This will be a genesis block.")
        }
        Log.d("BlockCreateDebug", "Calling 'createProposalBlock' NOW...")
        Log.d("BlockCreateDebug", "==================================================")

        // crash happens somewhere here
        try {
            return trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_TRANSFER,
                transaction,
                recipient
            )
        } catch (e: Exception) {
            Log.e("BlockCreateDebug", "CRITICAL ERROR in createProposalBlock: ${e.message}", e)
            throw e
        }
//        return trustChainCommunity.createProposalBlock(
//            BLOCK_TYPE_TRANSFER,
//            transaction,
//            recipient
//        )
    }

    fun verifyBalanceAvailable(
        block: TrustChainBlock,
        database: TrustChainStore
    ): ValidationResult {
        val balance =
            getVerifiedBalanceForBlock(block, database) ?: return ValidationResult.PartialPrevious
        if (balance < 0) {
            val blockBefore =
                database.getBlockWithHash(block.previousHash)
                    ?: return ValidationResult.PartialPrevious
            if (lastCheckpointIsEmpty(blockBefore, database)) {
                // IF INVALID IS RETURNED WE WONT CRAWL FOR LINKED BLOCKS
                return ValidationResult.PartialPrevious
            }
            val errorMsg =
                "Insufficient balance ($balance) for amount (${getBalanceChangeForBlock(block)})"
            return ValidationResult.Invalid(listOf(errorMsg))
        }
        return ValidationResult.Valid
    }

    fun verifyListedBalance(
        block: TrustChainBlock,
        database: TrustChainStore
    ): ValidationResult {
        if (!block.transaction.containsKey(KEY_BALANCE)) return ValidationResult.Invalid(listOf("Missing balance"))
        if (block.isGenesis) {
            if (block.transaction.containsKey(KEY_AMOUNT)) {
                if (block.transaction[KEY_BALANCE] != -(block.transaction[KEY_AMOUNT] as BigInteger).toLong()) {
                    return ValidationResult.Invalid(listOf("Invalid genesis balance"))
                } else {
                    return ValidationResult.Valid
                }
            } else {
                if (block.transaction[KEY_BALANCE] != 0L) {
                    return ValidationResult.Invalid(listOf("Invalid genesis balance"))
                } else {
                    return ValidationResult.Valid
                }
            }
        }
        val blockBefore = database.getBlockWithHash(block.previousHash)
        if (blockBefore == null) {
            Log.d("EuroTokenBlock", "Has to crawl for previous!!")
            return ValidationResult.PartialPrevious
        }
        val balanceBefore =
            getBalanceForBlock(blockBefore, database) ?: return ValidationResult.PartialPrevious
        val change = getBalanceChangeForBlock(block)
        if (block.transaction[KEY_BALANCE] != balanceBefore + change) {
            Log.w("EuroTokenBlock", "Invalid balance")
            return ValidationResult.Invalid(listOf("Invalid balance"))
        }
        return ValidationResult.Valid
    }

    fun verifyBalance() {
        getGatewayPeer()?.let { sendCheckpointProposal(it) }
    }

    /**
     * Sends a proposal block to join a liquidity pool with the hashes of a bitcoin and eurotoken transaction
     */
    fun sendJoinProposal(
        recipient: ByteArray,
        btcHash: String,
        euroHash: String
    ): TrustChainBlock? {
        if (btcHash == euroHash) {
            Log.d("LiquidityPool", "This is a bullsh*t check to make the app build")
        }
        val transaction =
            mapOf(
                "btcHash" to btcHash,
                "euroHash" to euroHash
            )
        return trustChainCommunity.createProposalBlock(
            BLOCK_TYPE_JOIN,
            transaction,
            recipient
        )
    }

    /**
     * Sends a proposal block to trade with the liquidity pool by specifying the direction you want to trade in (i.e. which currency you want to receive),
     * a hash of a transaction in the opposing currency, as well as an address where you would like to receive the currency specified in the direction.
     */
    fun sendTradeProposal(
        recipient: ByteArray,
        hash: String,
        direction: String,
        receiveAddress: String
    ): TrustChainBlock? {
        val transaction =
            mapOf(
                "hash" to hash,
                "receive" to receiveAddress,
                "direction" to direction
            )
        return trustChainCommunity.createProposalBlock(
            BLOCK_TYPE_TRADE,
            transaction,
            recipient
        )
    }

    fun sendCheckpointProposal(peer: Peer): TrustChainBlock {
        Log.w("EuroTokenBlockCheck", "Creating check...")
        val transaction =
            mapOf(
                KEY_BALANCE to BigInteger.valueOf(getMyBalance()).toLong()
            )
        val block =
            trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_CHECKPOINT,
                transaction,
                peer.publicKey.keyToBin()
            )
        scope.launch {
            trustChainCommunity.sendBlock(block, peer)
        }
        return block
    }

    fun attemptRollback(
        peer: Peer?,
        blockHash: ByteArray
    ) {
        if (peer != null && peer.publicKey != getGatewayPeer()?.publicKey) {
            Log.w("EuroTokenBlockRollback", "Not a valid gateway")
            return
        }
        val rolledBackBlock = trustChainCommunity.database.getBlockWithHash(blockHash)
        if (rolledBackBlock == null) {
            Log.d("EuroTokenBlockRollback", "block not found")
            return
        }
        if (!rolledBackBlock.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) {
            Log.d("EuroTokenBlockRollback", "Not my block")
            return
        }
        val amount = rolledBackBlock.transaction[KEY_AMOUNT] as BigInteger
        val transaction =
            mapOf(
                KEY_TRANSACTION_HASH to blockHash.toHex(),
                KEY_AMOUNT to amount,
                KEY_BALANCE to (BigInteger.valueOf(getMyBalance() - amount.toLong()).toLong())
            )
        @Suppress("CAST_NEVER_SUCCEEDS")
        Log.d("EuroTokenBlockRollback", (transaction[KEY_BALANCE] as Long).toString())
        scope.launch {
            trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_ROLLBACK,
                transaction,
                rolledBackBlock.publicKey
            )
        }
    }

    fun sendDestroyProposalWithIBAN(
        iban: String,
        amount: Long
    ): TrustChainBlock? {
        Log.w("EuroTokenBlockDestroy", "Creating destroy...")
        val peer = getGatewayPeer() ?: return null

        if (getMyBalance() - amount < 0) {
            return null
        }

        val transaction =
            mapOf(
                KEY_IBAN to iban,
                KEY_AMOUNT to BigInteger.valueOf(amount),
                KEY_BALANCE to (BigInteger.valueOf(getMyBalance() - amount).toLong())
            )
        val block =
            trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_DESTROY,
                transaction,
                peer.publicKey.keyToBin()
            )

        trustChainCommunity.sendBlock(block, peer)
        return block
    }

    fun sendDestroyProposalWithPaymentID(
        recipient: ByteArray,
        ip: String,
        port: Int,
        paymentId: String,
        amount: Long
    ): TrustChainBlock? {
        Log.w("EuroTokenBlockDestroy", "Creating destroy...")
        val key = defaultCryptoProvider.keyFromPublicBin(recipient)
        val address = IPv4Address(ip, port)
        val peer = Peer(key, address)

        if (getMyBalance() - amount < 0) {
            return null
        }

        val transaction =
            mapOf(
                KEY_PAYMENT_ID to paymentId,
                KEY_AMOUNT to BigInteger.valueOf(amount),
                KEY_BALANCE to (BigInteger.valueOf(getMyBalance() - amount).toLong())
            )
        val block =
            trustChainCommunity.createProposalBlock(
                BLOCK_TYPE_DESTROY,
                transaction,
                recipient
            )

        trustChainCommunity.sendBlock(block, peer)
        return block
    }

    fun getTransactions(limit: Int = 1000): List<Transaction> {
        val myKey = trustChainCommunity.myPeer.publicKey.keyToBin()
//        val unsyncedHashes = runBlocking {
//            offlineBlockSyncDao?.getUnsyncedBlockHashes() ?: emptyList()
//        }.toSet()
        return trustChainCommunity.database.getLatestBlocks(myKey, limit)
            .filter { block: TrustChainBlock -> EUROTOKEN_TYPES.contains(block.type) }
            .map { block: TrustChainBlock ->
                val blockHash = block.calculateHash().toHex()
//                val isUnsynced = unsyncedHashes.contains(blockHash)
                val syncStatus = runBlocking { getTransactionStatus(blockHash) }
                val sender = defaultCryptoProvider.keyFromPublicBin(block.publicKey)
                Transaction(
                    block,
                    sender,
                    defaultCryptoProvider.keyFromPublicBin(block.linkPublicKey),
                    if (block.transaction.containsKey(KEY_AMOUNT)) {
                        (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
                    } else {
                        0L
                    },
                    block.type,
                    getBalanceChangeForBlock(block) < 0,
                    block.timestamp,
//                    isOffline = isUnsynced,
//                    isSynced = !isUnsynced
                    syncStatus = syncStatus
                )
            }
    }

    fun getLatestBlockOfType(
        trustchain: TrustChainHelper,
        allowedTypes: List<String>
    ): TrustChainBlock {
        return trustchain.getChainByUser(trustchain.getMyPublicKey())
            .first { block: TrustChainBlock ->
                allowedTypes.contains(block.type)
            }
    }

    fun getTransactionsBetweenMeAndOther(
        other: PublicKey,
        trustchain: TrustChainHelper
    ): List<Transaction> {
        return trustchain.getChainByUser(trustchain.getMyPublicKey())
            .asSequence()
            .filter { block ->
                block.publicKey.contentEquals(other.keyToBin())
            }
            .map { block: TrustChainBlock ->
                val sender = defaultCryptoProvider.keyFromPublicBin(block.publicKey)
                Transaction(
                    block,
                    sender,
                    defaultCryptoProvider.keyFromPublicBin(block.linkPublicKey),
                    if (block.transaction.containsKey(KEY_AMOUNT)) {
                        (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
                    } else {
                        0L
                    },
                    block.type,
                    getBalanceChangeForBlock(block) < 0,
                    block.timestamp,
                    syncStatus = "Synced" // Set to Synced for online transactions
                )
            }
            .toList()
    }

    fun getLatestNTransactionsOfType(
        trustchain: TrustChainHelper,
        limit: Int,
        allowedTypes: List<String>
    ): List<Transaction> {
        val myKey = trustChainCommunity.myPeer.publicKey.keyToBin()
        val blocks = trustChainCommunity.database.getLatestBlocks(myKey, 1000)

        return trustchain.getChainByUser(trustchain.getMyPublicKey())
            .asSequence()
            .filter { block: TrustChainBlock ->
                allowedTypes.contains(block.type)
            }
            .filter { block ->
                val linkedBlock = blocks.find { it.linkedBlockId == block.blockId }
                val hasLinkedBlock = linkedBlock != null
                val outgoing = getBalanceChangeForBlock(block) < 0

                val outgoingTransaction =
                    outgoing && hasLinkedBlock && block.type == BLOCK_TYPE_TRANSFER
                val incomingTransaction =
                    !outgoing && block.type == BLOCK_TYPE_TRANSFER &&
                        (blocks.find { it.blockId == block.linkedBlockId } != null)
                val buyFromExchange =
                    !outgoing && block.type == BLOCK_TYPE_CREATE && !block.isAgreement
                val sellToExchange =
                    outgoing && block.type == BLOCK_TYPE_DESTROY && !block.isAgreement

                buyFromExchange || sellToExchange || outgoingTransaction || incomingTransaction
            }
            .take(limit)
            .map { block: TrustChainBlock ->
                val sender = defaultCryptoProvider.keyFromPublicBin(block.publicKey)
                Transaction(
                    block,
                    sender,
                    defaultCryptoProvider.keyFromPublicBin(block.linkPublicKey),
                    if (block.transaction.containsKey(KEY_AMOUNT)) {
                        (block.transaction[KEY_AMOUNT] as BigInteger).toLong()
                    } else {
                        0L
                    },
                    block.type,
                    getBalanceChangeForBlock(block) < 0,
                    block.timestamp,
                    syncStatus = "Synced" // Set to Synced for online transactions
                )
            }
            .toList()
    }

    fun getTransactionWithHash(hash: ByteArray?): TrustChainBlock? {
        return hash?.let {
            trustChainCommunity.database
                .getBlockWithHash(it)
        }
    }

    fun lastCheckpointIsEmpty(
        block: TrustChainBlock,
        database: TrustChainStore
    ): Boolean {
        if (BLOCK_TYPE_CHECKPOINT == block.type && block.isProposal) {
            return database.getLinked(block) == null // Checkpoint acceptance is missing and should be crawled to prove validity
        } else {
            val blockBefore =
                database.getBlockWithHash(
                    block.previousHash
                )
                    ?: return true // null will not actually happen, but true will result in PartialPrevious
            return lastCheckpointIsEmpty(blockBefore, database)
        }
    }

    /**
     * Checks the chain for the hashes specified in the join proposal
     */
    private fun verifyJoinTransactions(
        btcHash: String,
        euroHash: String,
        euroAddress: ByteArray
    ): ValidationResult {
        val myKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
        var latestBlock =
            trustChainCommunity.database.getLatest(myKey) ?: return ValidationResult.Invalid(
                listOf("Empty Chain")
            )
        var btcConfirmed = false
        var euroConfirmed = false
        // Traverse the chain while the corresponding btc/euro transfer blocks are not found
        while (!euroConfirmed || !btcConfirmed) {
            // For eurotoken blocks check the linked block for the correct hash
            if (latestBlock.type.equals(BLOCK_TYPE_TRANSFER)) {
                if (trustChainCommunity.database.getLinked(latestBlock)?.calculateHash()?.toHex()
                        .equals(euroHash)
                ) {
                    if (!latestBlock.linkPublicKey.toHex().equals(euroAddress.toHex())) {
                        return ValidationResult.Invalid(
                            listOf("Not your Eurotoken transaction!")
                        )
                    }
                    euroConfirmed = true
                }
            } else if (latestBlock.type.equals("bitcoin_transfer")) { // For bitcoin blocks check the value in the transactions of the block
                if (latestBlock.transaction.get("bitcoin_tx")!!.equals(btcHash)) {
                    btcConfirmed = true
                }
            }
            // Stop if you have reached the end of the chain
            if (latestBlock.isGenesis) {
                break
            }
            latestBlock = trustChainCommunity.database.getBlockWithHash(latestBlock.previousHash)!!
        }
        Log.d("VerifyJoinTransactions", "btc: $btcConfirmed, euro: $euroConfirmed")

        if (btcConfirmed && euroConfirmed) {
            Log.d("JoinPool", "Pool joined!")
            return ValidationResult.Valid
        } else {
            return ValidationResult.Invalid(
                listOf("Wrong Hashes")
            )
        }
    }

    /**
     * Checks the chain for the hash specified in the trade proposals
     * The hash belongs to a transaction of the opposing currency of direction.
     * I.e. if direction is eurotoken, you are looking for a bitcoin transaction.
     */
    private fun verifyTradeTransactions(
        hash: String,
        direction: String,
        euroAddress: ByteArray
    ): ValidationResult {
        val myKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
        var latestBlock =
            trustChainCommunity.database.getLatest(myKey) ?: return ValidationResult.Invalid(
                listOf("Empty Chain")
            )
        while (true) {
            if (direction.equals("bitcoin") && latestBlock.type.equals(BLOCK_TYPE_TRANSFER)) {
                if (trustChainCommunity.database.getLinked(latestBlock)?.calculateHash()?.toHex()
                        .equals(hash)
                ) {
                    if (!latestBlock.linkPublicKey.toHex().equals(euroAddress.toHex())) {
                        return ValidationResult.Invalid(
                            listOf("Not your Eurotoken transaction!")
                        )
                    }
                    Log.d("verifyTradeTransactions", "Found valid eurotoken transfer")
                    return ValidationResult.Valid
                }
            } else if (direction.equals("eurotoken") && latestBlock.type.equals("bitcoin_transfer")) {
                if (latestBlock.transaction.get("bitcoin_tx")!!.equals(hash)) {
                    Log.d("verifyTradeTransactions", "Found valid bitcoin transfer")
                    return ValidationResult.Valid
                }
            }
            // Stop if you have reached the end of the chain
            if (latestBlock.isGenesis) {
                break
            }
            latestBlock = trustChainCommunity.database.getBlockWithHash(latestBlock.previousHash)!!
        }
        Log.d("VerifyTradeTransactions", "Wrong hashes")
        return ValidationResult.Invalid(
            listOf("Wrong Hash")
        )
    }

    private fun addTransferListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_TRANSFER,
            EuroTokenTransferValidator(this)
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_TRANSFER,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    Log.w("EuroTokenBlockTransfer", "sig request ${block.transaction}")
                    // agree if validated
                    trustChainCommunity.sendBlock(
                        trustChainCommunity.createAgreementBlock(
                            block,
                            block.transaction
                        )
                    )
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_TRANSFER,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    // Auto verifyBalance
                    if (block.isAgreement && block.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) {
                        verifyBalance()
                    }
                    Log.d(
                        "EuroTokenBlock",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    private fun addJoinListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_JOIN,
            object : TransactionValidator {
                override fun validate(
                    block: TrustChainBlock,
                    database: TrustChainStore
                ): ValidationResult {
                    val mykey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()

                    if (block.publicKey.toHex() == mykey.toHex() && block.isProposal) return ValidationResult.Valid

                    if (block.isProposal) {
                        Log.d(
                            "RecvProp",
                            "Received Proposal Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )

                        if (!block.transaction.containsKey("btcHash") ||
                            !block.transaction.containsKey(
                                "euroHash"
                            )
                        ) {
                            return ValidationResult.Invalid(
                                listOf("Missing hashes")
                            )
                        }
                        Log.d(
                            "EuroTokenBlockJoin",
                            "Received join request with hashes\nBTC: ${
                                block.transaction.get(
                                    "btcHash"
                                )
                            }\nEuro: ${block.transaction.get("euroHash")}"
                        )
                        // Check if hashes are valid by searching in own chain
                        return verifyJoinTransactions(
                            block.transaction.get("btcHash") as String,
                            block.transaction.get("euroHash") as String,
                            block.publicKey
                        )
                    } else {
                        Log.d(
                            "AgreementProp",
                            "Received Agreement Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )
                        if (database.getLinked(block)?.transaction?.equals(block.transaction) != true) {
                            return ValidationResult.Invalid(
                                listOf(
                                    "Linked transaction doesn't match (${block.transaction}, ${
                                        database.getLinked(
                                            block
                                        )?.transaction ?: "MISSING"
                                    })"
                                )
                            )
                        }
                    }
                    if (block.isProposal) {
                        Log.d(
                            "RecvProp",
                            "Received Proposal Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )
                    } else {
                        Log.d(
                            "AgreementProp",
                            "Received Agreement Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )
                    }

                    return ValidationResult.Valid
                }
            }
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_JOIN,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    Log.w("EuroTokenBlockJoin", "sig request ${block.transaction}")
                    // agree if validated
                    trustChainCommunity.sendBlock(
                        trustChainCommunity.createAgreementBlock(
                            block,
                            block.transaction
                        )
                    )
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_JOIN,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    ensureCheckpointLinks(block, trustChainCommunity.database)
                    if (block.isAgreement && block.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) {
                        verifyBalance()
                    }
                    Log.d(
                        "EuroTokenBlock",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    private fun addTradeListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_TRADE,
            object : TransactionValidator {
                override fun validate(
                    block: TrustChainBlock,
                    database: TrustChainStore
                ): ValidationResult {
                    val mykey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
                    if (block.publicKey.toHex() == mykey.toHex() && block.isProposal) return ValidationResult.Valid

                    if (block.isProposal) {
                        // Check if hash is valid for the corresponding direction
                        val result =
                            verifyTradeTransactions(
                                block.transaction.get("hash") as String,
                                block.transaction.get("direction") as String,
                                euroAddress = block.publicKey
                            )
                        if (result != ValidationResult.Valid) {
                            return result
                        }
                        Log.d("Trade", "Valid trade")
                        // It is valid, so we need to send some funds back
                        if ((block.transaction.get("direction") as String).equals("bitcoin")) {
                            Log.d(
                                "TradeBitcoin",
                                "Sending bitcoins back to some address: ${block.transaction.get("receive") as String}"
                            )
                            val wallet = WalletService.getGlobalWallet().wallet()
                            val sendRequest =
                                SendRequest.to(
                                    Address.fromString(
                                        WalletService.params,
                                        block.transaction.get("receive") as String
                                    ),
                                    Coin.valueOf(10000000)
                                )
                            wallet.sendCoins(sendRequest)
                        } else if ((block.transaction.get("direction") as String).equals("eurotoken")) {
                            Log.d(
                                "TradeEurotoken",
                                "Sending eurotokens back to some address: ${block.transaction.get("receive") as String}"
                            )
                            sendTransferProposal(
                                (block.transaction.get("receive") as String).hexToBytes(),
                                0
                            )
                        }
                    } else {
                        Log.d(
                            "AgreementProp",
                            "Received Agreement Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )
                        if (database.getLinked(block)?.transaction?.equals(block.transaction) != true) {
                            return ValidationResult.Invalid(
                                listOf(
                                    "Linked transaction doesn't match (${block.transaction}, ${
                                        database.getLinked(
                                            block
                                        )?.transaction ?: "MISSING"
                                    })"
                                )
                            )
                        }
                    }
                    return ValidationResult.Valid
                }
            }
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_JOIN,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    Log.w("EuroTokenBlockJoin", "sig request ${block.transaction}")
                    // agree if validated
                    trustChainCommunity.sendBlock(
                        trustChainCommunity.createAgreementBlock(
                            block,
                            block.transaction
                        )
                    )
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_JOIN,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    Log.d(
                        "EuroTokenBlockJoin",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    private fun addCreationListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_TRADE,
            object : TransactionValidator {
                override fun validate(
                    block: TrustChainBlock,
                    database: TrustChainStore
                ): ValidationResult {
                    val mykey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
                    if (block.publicKey.toHex() == mykey.toHex() && block.isProposal) return ValidationResult.Valid

                    if (block.isProposal) {
                        // Check if hash is valid for the corresponding direction
                        val result =
                            verifyTradeTransactions(
                                block.transaction.get("hash") as String,
                                block.transaction.get("direction") as String,
                                euroAddress = block.publicKey
                            )
                        if (result != ValidationResult.Valid) {
                            return result
                        }
                        Log.d("Trade", "Valid trade")
                        // It is valid, so we need to send some funds back
                        if ((block.transaction.get("direction") as String).equals("bitcoin")) {
                            Log.d(
                                "TradeBitcoin",
                                "Sending bitcoins back to some address: ${block.transaction.get("receive") as String}"
                            )
                            val wallet = WalletService.getGlobalWallet().wallet()
                            val sendRequest =
                                SendRequest.to(
                                    Address.fromString(
                                        WalletService.params,
                                        block.transaction.get("receive") as String
                                    ),
                                    Coin.valueOf(10000000)
                                )
                            wallet.sendCoins(sendRequest)
                        } else if ((block.transaction.get("direction") as String).equals("eurotoken")) {
                            Log.d(
                                "TradeEurotoken",
                                "Sending eurotokens back to some address: ${block.transaction.get("receive") as String}"
                            )
                            sendTransferProposal(
                                (block.transaction.get("receive") as String).hexToBytes(),
                                100
                            )
                        }
                    } else {
                        Log.d(
                            "AgreementProp",
                            "Received Agreement Block!" + "from : " + block.publicKey.toHex() + " our key : " + mykey.toHex()
                        )
                        if (database.getLinked(block)?.transaction?.equals(block.transaction) != true) {
                            return ValidationResult.Invalid(
                                listOf(
                                    "Linked transaction doesn't match (${block.transaction}, ${
                                        database.getLinked(
                                            block
                                        )?.transaction ?: "MISSING"
                                    })"
                                )
                            )
                        }
                    }
                    return ValidationResult.Valid
                }
            }
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_TRADE,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    Log.w("EuroTokenBlockTrade", "sig request ${block.transaction}")
                    // agree if validated
                    trustChainCommunity.sendBlock(
                        trustChainCommunity.createAgreementBlock(
                            block,
                            block.transaction
                        )
                    )
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_TRADE,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    ensureCheckpointLinks(block, trustChainCommunity.database)
                    if (block.isAgreement && block.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) {
                        verifyBalance()
                    }
                    Log.w(
                        "EuroTokenBlockCreate",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

//    private fun addCreationListeners() {
//        trustChainCommunity.registerTransactionValidator(
//            BLOCK_TYPE_DESTROY,
//            object : TransactionValidator {
//                override fun validate(
//                    block: TrustChainBlock,
//                    database: TrustChainStore
//                ): ValidationResult {
//                    if (!block.transaction.containsKey(KEY_AMOUNT)) return ValidationResult.Invalid(
//                        listOf("Missing amount")
//                    )
//                    if (!block.transaction.containsKey(KEY_PAYMENT_ID) && !block.transaction.containsKey(KEY_IBAN)) return ValidationResult.Invalid(
//                        listOf("Missing Payment id")
//                    )
//                    return ValidationResult.Valid
//                }
//            }
//        )
//
//        trustChainCommunity.addListener(
//            BLOCK_TYPE_CREATE,
//            object : BlockListener {
//                override fun onBlockReceived(block: TrustChainBlock) {
//                    if (block.isAgreement && block.publicKey.contentEquals(trustChainCommunity.myPeer.publicKey.keyToBin())) {
//                        verifyBalance()
//                    }
//                    Log.w(
//                        "EuroTokenBlockCreate",
//                        "onBlockReceived: ${block.blockId} ${block.transaction}"
//                    )
//                }
//            }
//        )
//    }

    private fun addDestructionListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_DESTROY,
            EuroTokenDestructionValidator(this)
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_DESTROY,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    // only gateways should sign destructions
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_DESTROY,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    ensureCheckpointLinks(block, trustChainCommunity.database)
                    Log.d(
                        "EuroTokenBlockDestroy",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    private fun addCheckpointListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_CHECKPOINT,
            EuroTokenCheckpointValidator(this)
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_CHECKPOINT,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    // only gateways should sign checkpoints
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_CHECKPOINT,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    Log.d(
                        "EuroTokenBlockCheck",
                        "${block.type} onBlockReceived: ${block.isProposal} ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    private fun addRollbackListeners() {
        trustChainCommunity.registerTransactionValidator(
            BLOCK_TYPE_ROLLBACK,
            EuroTokenRollBackValidator(this)
        )

        trustChainCommunity.registerBlockSigner(
            BLOCK_TYPE_ROLLBACK,
            object : BlockSigner {
                override fun onSignatureRequest(block: TrustChainBlock) {
                    // rollbacks don't need to be signed, their existence is a declaration of forfeit
                }
            }
        )

        trustChainCommunity.addListener(
            BLOCK_TYPE_ROLLBACK,
            object : BlockListener {
                override fun onBlockReceived(block: TrustChainBlock) {
                    ensureCheckpointLinks(block, trustChainCommunity.database)
                    Log.d(
                        "EuroTokenBlockRollback",
                        "${block.type} onBlockReceived: ${block.blockId} ${block.transaction}"
                    )
                }
            }
        )
    }

    fun initTrustChainCommunity() {
        addTransferListeners()
        addJoinListeners()
        addCreationListeners()
        addDestructionListeners()
        addCheckpointListeners()
        addRollbackListeners()
        addTradeListeners()
    }

    companion object {
//        private lateinit var instance: TransactionRepository
//        fun getInstance(gatewayStore: GatewayStore, trustChainCommunity: TrustChainCommunity): TransactionRepository {
//            if (!Companion::instance.isInitialized) {
//                instance = TransactionRepository(trustChainCommunity, gatewayStore )
//            }
//            return instance
//        }

        fun prettyAmount(amount: Long): String {
            return "€" + (amount / 100).toString() + "," +
                (abs(amount) % 100).toString()
                    .padStart(2, '0')
        }

        const val BLOCK_TYPE_TRANSFER = "eurotoken_transfer"
        const val BLOCK_TYPE_CREATE = "eurotoken_creation"
        const val BLOCK_TYPE_DESTROY = "eurotoken_destruction"
        const val BLOCK_TYPE_CHECKPOINT = "eurotoken_checkpoint"
        const val BLOCK_TYPE_ROLLBACK = "eurotoken_rollback"
        const val BLOCK_TYPE_JOIN = "eurotoken_join"
        const val BLOCK_TYPE_TRADE = "eurotoken_trade"

        @Suppress("ktlint:standard:property-naming")
        val EUROTOKEN_TYPES =
            listOf(
                BLOCK_TYPE_TRANSFER,
                BLOCK_TYPE_CREATE,
                BLOCK_TYPE_DESTROY,
                BLOCK_TYPE_CHECKPOINT,
                BLOCK_TYPE_ROLLBACK,
                BLOCK_TYPE_JOIN,
                BLOCK_TYPE_TRADE
            )

        const val KEY_AMOUNT = "amount"
        const val KEY_BALANCE = "balance"
        const val KEY_TRANSACTION_HASH = "transaction_hash"
        const val KEY_PAYMENT_ID = "payment_id"
        const val KEY_IBAN = "iban"
        var initialBalance: Long = 0
    }
}
