package nl.tudelft.trustchain.eurotoken.nfc

import android.app.Service
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import org.json.JSONObject
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageAnalyticsDatabase
import nl.tudelft.trustchain.common.eurotoken.benchmarks.trackCheckpoint
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageLogger
import nl.tudelft.trustchain.common.eurotoken.worker.SyncWorker

class EuroTokenHCEService : HostApduService() {
    companion object {
        private const val TAG = "EuroTokenHCE"

        // Actions
        const val ACTION_START_TERMINAL_MODE = "start_terminal_mode"
        const val ACTION_TRANSACTION_COMPLETED = "transaction_completed"
        const val ACTION_TRANSACTION_FAILED = "transaction_failed"
        const val ACTION_CUSTOMER_CONNECTED = "customer_connected"

        // Extras
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_NAME = "name"
        const val EXTRA_SENDER_NAME = "sender_name"
        const val EXTRA_SENDER_KEY = "sender_key"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_MORE_DATA = byteArrayOf(0x61.toByte(), 0x00.toByte())
        private val SW_TOO_LONG = byteArrayOf(0x6C.toByte(), 0x00.toByte())

        // AID
        const val AID_EUROTOKEN = "F222222222"

        // Commands
        // both proposal and agreement are too large therefore we need to use chunking
        private const val INS_SELECT = 0xA4.toByte()
        private const val INS_GET_PAYMENT_INFO = 0xB0.toByte()
        private const val INS_SEND_PROPOSAL_CHUNK = 0xB1.toByte()
        private const val INS_GET_AGREEMENT_CHUNK = 0xB2.toByte()

        // private const val INS_SEND_TRUST_INFO = 0xB3.toByte()
        // private const val INS_GET_TRUST_INFO = 0xB4.toByte()
        private const val INS_GET_RESPONSE = 0xC0.toByte()
//        private const val INS_READ_AGREEMENT: Byte = 0xB2.toByte()

        private const val MAX_CHUNK = 220
    }

    // Instance state (not static!)
    private var terminalMode = false
    private var terminalAmount: Long = 0
    private var terminalPublicKey: String? = null
    private var terminalName: String? = null

    // Transaction state for current session
    private var proposalAssembler = NfcChunkingProtocol.ChunkAssembler()

//    private var agreementChunks: List<ByteArray>? = null
//    private var agreementChunkIndex = 0
    // private var trustInfoChunks: List<ByteArray>? = null
    // private var trustInfoChunkIndex = 0
    private var paymentInfoChunks: List<ByteArray>? = null
    private var paymentInfoChunkIndex = 0

    private var activeDataChunks: List<ByteArray>? = null
    private var activeChunkIndex: Int = 0

    private var fullPaymentInfoPayload: ByteArray? = null

    private var agreementBlockData: ByteArray? = null

    // private var trustInfoAssembler = NfcChunkingProtocol.ChunkAssembler()

    // Dependencies
    private val transactionRepository: TransactionRepository by lazy {
        val ipv8 = IPv8Android.getInstance()
        val trustChainCommunity = ipv8.getOverlay<TrustChainCommunity>()
            ?: throw IllegalStateException("TrustChainCommunity is not configured")
        val gatewayStore = GatewayStore.getInstance(this)
        val db = UsageAnalyticsDatabase.getInstance(this)
        TransactionRepository(trustChainCommunity, gatewayStore, db.offlineBlockSyncDao(), this)
    }

    private val euroTokenCommunity: EuroTokenCommunity by lazy {
        IPv8Android.getInstance().getOverlay<EuroTokenCommunity>()
            ?: throw IllegalStateException("EuroTokenCommunity is not configured")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HCE Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TERMINAL_MODE -> {
                terminalMode = true
                terminalAmount = intent.getLongExtra(EXTRA_AMOUNT, 0)
                terminalPublicKey = intent.getStringExtra(EXTRA_PUBLIC_KEY)
                terminalName = intent.getStringExtra(EXTRA_NAME)

                Log.d(TAG, "Terminal mode activated: amount=$terminalAmount")
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) {
            Log.e(TAG, "processCommandApdu: Received null command APDU.")
            return SW_UNKNOWN_ERROR
        }
        Log.i(TAG, "processCommandApdu: <-- Received APDU: ${commandApdu.toHex()}")

        val ins = commandApdu[1]

        // handle SELECT AID
        if (ins == INS_SELECT && commandApdu.size >= 5) {
            return handleSelectAid(commandApdu)
        }

        // all other commands require terminal mode --> nfc mode
        if (!terminalMode) {
            Log.w(TAG, "Command received but not in terminal mode")
            return SW_CONDITIONS_NOT_SATISFIED
        }

        return when (ins) {
            INS_GET_PAYMENT_INFO -> handleGetPaymentInfo(commandApdu)
            INS_GET_RESPONSE -> handleGetResponse()
            INS_SEND_PROPOSAL_CHUNK -> handleReceiveProposalChunk(commandApdu)
            INS_GET_AGREEMENT_CHUNK -> handleGetAgreementChunk(commandApdu)
            // INS_SEND_TRUST_INFO -> handleReceiveTrustInfo(commandApdu)
            // INS_GET_TRUST_INFO -> handleGetTrustInfo()
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    private fun handleSelectAid(commandApdu: ByteArray): ByteArray {
        val aidLength = commandApdu[4].toInt() and 0xFF
        if (commandApdu.size < 5 + aidLength) {
            return SW_WRONG_LENGTH
        }

        val aidBytes = commandApdu.copyOfRange(5, 5 + aidLength)
        return if (AID_EUROTOKEN == aidBytes.toHex()) {
            Log.i(TAG, "EuroToken AID selected successfully")

            UsageLogger.logTransactionStart("nfc_receive_payment")

            // custoemr connected so we have to broadcast
            sendBroadcast(ACTION_CUSTOMER_CONNECTED)

            SW_OK
        } else {
            Log.w(TAG, "Unknown AID: ${aidBytes.toHex()}")
            SW_INS_NOT_SUPPORTED
        }
    }

    private fun handleGetResponse(): ByteArray {
        val chunks = activeDataChunks
        if (chunks == null || activeChunkIndex >= chunks.size) {
            Log.e(TAG, "GET RESPONSE requested but no data is available or all chunks sent.")
            activeDataChunks = null
            return SW_CONDITIONS_NOT_SATISFIED
        }

        val chunk = chunks[activeChunkIndex]
        activeChunkIndex++

        // last chunk??
        return if (activeChunkIndex >= chunks.size) {
            Log.d(TAG, "--> Sending final chunk $activeChunkIndex/${chunks.size} (${chunk.size} bytes) with SW_OK.")
            activeDataChunks = null
            chunk + SW_OK
        } else {
            // incorrect more chunks available
            val nextChunkSize = chunks[activeChunkIndex].size
            Log.d(TAG, "--> Sending chunk $activeChunkIndex/${chunks.size} (${chunk.size} bytes). Next is $nextChunkSize bytes.")
            chunk + byteArrayOf(0x61.toByte(), nextChunkSize.toByte())
        }
    }

    private fun handleGetPaymentInfo(commandApdu: ByteArray): ByteArray {
        // not prepared yet? -> handle if ins bytes
        if (fullPaymentInfoPayload == null) {
            Log.d(TAG, "Preparing full payment info payload for HCE.")
            val paymentDataJson = JSONObject().apply {
                put("amount", terminalAmount)
                put("public_key", terminalPublicKey)
                put("name", terminalName ?: "Merchant")
                put("type", "payment_request")
            }.toString()
            fullPaymentInfoPayload = paymentDataJson.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Full payload to send: $paymentDataJson")
        }

        val data = fullPaymentInfoPayload ?: return SW_CONDITIONS_NOT_SATISFIED

        // we check for p1/p2 and le
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF
        val offset = (p1 shl 8) or p2
        val le = if (commandApdu.size > 4) commandApdu[4].toInt() and 0xFF else 0

        // safety margin max isodepapu is 255 bytes
        val maxLe = if (le == 0) 253 else le

        Log.d(TAG, "Serving READ BINARY at offset: $offset with Le: $maxLe")

        if (offset >= data.size) {
            Log.w(TAG, "Offset $offset is out of bounds for data size ${data.size}. No more data to send.")
            return SW_OK
        }

        // how much should we send in repsonse  - limited to chunking amount
        val remainingBytes = data.size - offset
        val bytesToSend = minOf(remainingBytes, maxLe)

        val chunk = data.copyOfRange(offset, offset + bytesToSend)

        Log.d(TAG, "--> Sending chunk. Size: ${chunk.size}, Offset: $offset, Total: ${data.size}")

        // signal to the reader that we are done
        return chunk + SW_OK
    }

    private fun handleReceiveProposalChunk(commandApdu: ByteArray): ByteArray {
        val dataLength = if (commandApdu.size > 4) {
            (commandApdu[4].toInt() and 0xFF)
        } else {
            return SW_WRONG_LENGTH
        }

        if (commandApdu.size < 5 + dataLength) {
            return SW_WRONG_LENGTH
        }

        val chunkData = commandApdu.copyOfRange(5, 5 + dataLength)

        try {
            val chunk = NfcChunkingProtocol.parseChunk(chunkData)
            val completeData = proposalAssembler.addChunk(chunk)

            if (completeData != null) {
                // we have the complete proposal
                // full propposal block
                return processCompleteProposal(completeData)
            }

            // we need more chunks
            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error processing proposal chunk", e)
            proposalAssembler.reset()
            return SW_UNKNOWN_ERROR
        }
    }

    private fun processCompleteProposal(proposalData: ByteArray): ByteArray {
        try {
            Log.d("NFC_DEBUG_RECEIVER", "Reassembled Proposal Data (raw): ${proposalData.toHex()}")

            val proposalBlock = trackCheckpoint("deserialize_proposal") {
                transactionRepository.deserializeBlock(proposalData)
            } ?: run {
                Log.e(TAG, "Failed to deserialize proposal block")
                sendTransactionFailed("Invalid proposal format")
                return SW_UNKNOWN_ERROR
            }

            Log.d("SignatureValidation", "--- RECEIVER: BLOCK DATA ---")
            Log.d("SignatureValidation", "Block Type:         ${proposalBlock.type}")
            Log.d("SignatureValidation", "Transaction Map:    ${proposalBlock.transaction}")
            Log.d("SignatureValidation", "PublicKey:          ${proposalBlock.publicKey.toHex()}")
            Log.d("SignatureValidation", "Sequence Number:    ${proposalBlock.sequenceNumber}")
            Log.d("SignatureValidation", "Link Public Key:    ${proposalBlock.linkPublicKey.toHex()}")
            Log.d("SignatureValidation", "Link Seq Number:    ${proposalBlock.linkSequenceNumber}")
            Log.d("SignatureValidation", "Previous Hash:      ${proposalBlock.previousHash.toHex()}")
            Log.d("SignatureValidation", "Timestamp:          ${proposalBlock.timestamp.time}")
            Log.d("SignatureValidation", "------------------------------")

            val isValid = trackCheckpoint("validate_proposal") {
                transactionRepository.validateTransferProposal(
                    proposalBlock,
                    terminalAmount,
                    terminalPublicKey!!.hexToBytes()
                )
            }
            if (!isValid) {
                Log.e(TAG, "Proposal validation failed")
                sendTransactionFailed("Invalid payment proposal")
                return SW_CONDITIONS_NOT_SATISFIED
            }

            val agreementBlock = trackCheckpoint("create_agreement_block") {
                transactionRepository.createAgreementBlock(proposalBlock)
            } ?: run {
                Log.e(TAG, "Failed to create agreement block")
                sendTransactionFailed("Failed to process payment")
                return SW_UNKNOWN_ERROR
            }

            val senderPublicKeyBytes = proposalBlock.publicKey

            val senderPublicKey = defaultCryptoProvider.keyFromPublicBin(senderPublicKeyBytes)

            val contactStore = ContactStore.getInstance(applicationContext)
            val senderContact = contactStore.getContactFromPublicKey(senderPublicKey)
            val senderName = senderContact?.name ?: "Unknown"

            val senderNameFromProposal = proposalBlock.transaction["sender_name"] as? String ?: "Unknown"

            val senderKeyHex = proposalBlock.publicKey.toHex()

            // store both blocks locally
            trackCheckpoint("store_offline_blocks") {
                transactionRepository.storeOfflineBlock(proposalBlock)
                transactionRepository.storeOfflineBlock(agreementBlock)
            }

            // lets tell sync worker to sync this block
            SyncWorker.scheduleImmediateSync(applicationContext)

            // lets prepare agreement chunks for sending
            agreementBlockData = transactionRepository.serializeBlock(agreementBlock)
            Log.d(TAG, "Agreement block stored for retrieval: ${agreementBlock.blockId}")

            // okay now we have the sender and amount lets send to ui
            sendTransactionCompleted(terminalAmount, senderNameFromProposal, senderKeyHex)

            UsageLogger.logTransactionDone()
            proposalAssembler.reset()

            Log.d(TAG, "Proposal processed successfully")
            return SW_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error processing complete proposal", e)
            sendTransactionFailed("Processing error: ${e.message}")
            return SW_UNKNOWN_ERROR
        }
    }

    private fun handleGetAgreementChunk(commandApdu: ByteArray): ByteArray {
        val data = agreementBlockData ?: return SW_CONDITIONS_NOT_SATISFIED

        if (commandApdu.size < 5) return SW_WRONG_LENGTH

        // for agreement reading, weuse the GET RESPONSE pattern like payment info
        // initially b2 request  -> get agreement chunk
        // response is 61 xx (first) instead of 90 00 (OK) to showcase so many bytes are waiting)
        //
        // then c0 requests (get response)
        // repeats until  90 00 (OK) is received

        if (activeDataChunks == null) {
            val chunks = mutableListOf<ByteArray>()
            var offset = 0

            while (offset < data.size) {
                val chunkSize = minOf(MAX_CHUNK, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + chunkSize)
                chunks.add(chunk)
                offset += chunkSize
            }

            activeDataChunks = chunks

            activeChunkIndex = 0

            Log.d(TAG, "Agreement: prepared ${chunks.size} chunks for GET RESPONSE pattern")

            agreementBlockData = null
        }

        return handleGetResponse()
    }

    // private fun handleReceiveTrustInfo(commandApdu: ByteArray): ByteArray {
    //     val dataLength = if (commandApdu.size > 4) {
    //         (commandApdu[4].toInt() and 0xFF)
    //     } else {
    //         return SW_WRONG_LENGTH
    //     }

    //     if (commandApdu.size < 5 + dataLength) {
    //         return SW_WRONG_LENGTH
    //     }

    //     val chunkData = commandApdu.copyOfRange(5, 5 + dataLength)

    //     try {
    //         val chunk = NfcChunkingProtocol.parseChunk(chunkData)
    //         val completeData = trustInfoAssembler.addChunk(chunk)

    //         if (completeData != null) {
    //             Log.d(TAG, "Reassembled complete trust info data.")
    //             val trustJson = JSONObject(String(completeData, Charsets.UTF_8))
    //             val addresses = trustJson.getString("addresses").split(",")

    //             euroTokenCommunity.processTrustAddresses(addresses)

    //             Log.d(TAG, "Processed ${addresses.size} trust addresses")
    //             trustInfoAssembler.reset()
    //             return SW_OK
    //         } else {
    //             return SW_OK
    //         }
    //     } catch (e: Exception) {
    //         Log.e(TAG, "Failed to process trust info chunk", e)
    //         trustInfoAssembler.reset()
    //         return SW_UNKNOWN_ERROR
    //     }
    // }

    // private fun handleGetTrustInfo(): ByteArray {
    //     if (activeDataChunks == null) {
    //         val addresses = euroTokenCommunity.collectTrustAddresses(50)
    //         val trustJson = JSONObject().apply {
    //             put("addresses", addresses.joinToString(","))
    //             put("timestamp", System.currentTimeMillis())
    //         }
    //         val trustData = trustJson.toString().toByteArray(Charsets.UTF_8)

    //         val chunks = NfcChunkingProtocol.createChunks(trustData)

    //         activeDataChunks = chunks
    //         activeChunkIndex = 0
    //         Log.d(TAG, "Trust Info: prepared ${chunks.size} chunks for GET RESPONSE pattern")
    //     }

    //     return handleGetResponse()
    // }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "HCE Service Deactivated. Reason: $reason")

        fullPaymentInfoPayload = null
        proposalAssembler.reset()
        agreementBlockData = null
        // trustInfoChunks = null
        // trustInfoChunkIndex = 0

        activeDataChunks = null
        activeChunkIndex = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HCE Service destroyed")
        terminalMode = false
        terminalAmount = 0
        terminalPublicKey = null
        terminalName = null
    }

    private fun sendBroadcast(action: String, extras: Bundle = Bundle()) {
        val intent = Intent(action).apply {
            putExtras(extras)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendTransactionCompleted(amount: Long, senderName: String, senderKey: String) {
        val extras = Bundle().apply {
            putLong(EXTRA_AMOUNT, amount)
            putString(EXTRA_SENDER_NAME, senderName)
            putString(EXTRA_SENDER_KEY, senderKey)
        }
        sendBroadcast(ACTION_TRANSACTION_COMPLETED, extras)
    }

    private fun sendTransactionFailed(error: String) {
        val extras = Bundle().apply {
            putString(EXTRA_ERROR_MESSAGE, error)
        }
        sendBroadcast(ACTION_TRANSACTION_FAILED, extras)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }.uppercase()

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
