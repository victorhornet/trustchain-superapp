package nl.tudelft.trustchain.eurotoken.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ActivityNfcReaderBinding
import nl.tudelft.trustchain.eurotoken.nfc.NfcError
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import nl.tudelft.trustchain.eurotoken.nfc.NfcChunkingProtocol
import org.json.JSONObject
import android.nfc.TagLostException
import java.io.IOException
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageAnalyticsDatabase
import java.io.ByteArrayOutputStream
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageLogger
import nl.tudelft.trustchain.common.eurotoken.benchmarks.TransferDirection
import nl.tudelft.trustchain.common.eurotoken.benchmarks.TransferError
import nl.tudelft.trustchain.common.eurotoken.benchmarks.trackCheckpoint
import nl.tudelft.trustchain.common.eurotoken.worker.SyncWorker

class NfcReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityNfcReaderBinding

    private val transactionRepository: TransactionRepository by lazy {
        val ipv8 = IPv8Android.getInstance()
        val trustChainCommunity = ipv8.getOverlay<nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity>()
            ?: throw IllegalStateException("TrustChainCommunity is not configured")

        val gatewayStore = GatewayStore.getInstance(this)
        val db = UsageAnalyticsDatabase.getInstance(this)
        TransactionRepository(trustChainCommunity, gatewayStore, db.offlineBlockSyncDao(), this)
    }

    private val euroTokenCommunity: EuroTokenCommunity by lazy {
        IPv8Android.getInstance().getOverlay<EuroTokenCommunity>()
            ?: throw IllegalStateException("EuroTokenCommunity is not configured")
    }

    private var isPaymentMode = false
    private var senderPublicKey: String? = null
    private var paymentAmount: Long = 0
    private var recipientName: String = ""
    private var recipientPublicKey: String = ""

    companion object {
        private const val TAG = "NfcReader"
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        // AID
        // this is what HCE service has to respond to
        // must match apduservice.xml!!
        // ISO/IEC 7816-4 -> 5-16 bytes, start with 0xF2
        private const val HCE_GOAL_AID = "F222222222"

        val CMD_SELECT_AID: ByteArray = byteArrayOf(
            0x00.toByte(),
            0xA4.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            HCE_GOAL_AID.length.div(2).toByte()
        ) + HCE_GOAL_AID.hexToBytes()

        //  extra cmds
        fun CMD_SEND_PROPOSAL_CHUNK(data: ByteArray) = createApdu(0xB1.toByte(), data) // please send
        val CMD_GET_AGREEMENT_CHUNK = createApdu(0xB2.toByte())
        // fun CMD_SEND_TRUST_INFO(data: ByteArray) = createApdu(0xB3.toByte(), data)
        // val CMD_GET_TRUST_INFO = createApdu(0xB4.toByte())

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())

        // select app via AID
        // follows ISO/IEC 7816-4 (AID) and ISO/IEC 7816-3 (SELECT)
        // CLA | INS | P1 | P2 | Lc | Data
        private fun createApdu(ins: Byte, data: ByteArray? = null): ByteArray {
            val header = byteArrayOf(
                0x00.toByte(), // CLA
                ins, // INS
                0x00.toByte(), // P1
                0x00.toByte() // P2
            )

            return if (data != null && data.isNotEmpty()) {
                header + byteArrayOf(data.size.toByte()) + data
            } else {
                header + byteArrayOf(0x00.toByte()) // Le = 0 thus max response length ->256
            }
            // same as in hceservice
            // ISO7816-4 state that Le=0 means 256 for short APDUs
            // thus reader expects up to 256 bytes
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcReaderBinding.inflate(layoutInflater)

        setContentView(binding.root)

        isPaymentMode = intent.getBooleanExtra("PAYMENT_MODE", true)
        senderPublicKey = intent.getStringExtra("SENDER_PUBLIC_KEY")

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showError("NFC is not available on this device.", NfcError.NFC_UNSUPPORTED)
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            showError("NFC is disabled. Please enable it.", NfcError.NFC_DISABLED)
            return
        }

        setupUI()
    }

    private fun setupUI() {
        binding.headerTitle.text = "💳 Connecting..."
        binding.tvReaderStatus.text = "Hold devices together and wait for connection"
        binding.btnConfirm.visibility = View.GONE

        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        startRippleAnimation()
    }

    private fun startRippleAnimation() {
        lifecycleScope.launch {
            while (!isFinishing) {
                binding.ripple1.animate()
                    .scaleX(2.0f).scaleY(2.0f)
                    .alpha(0f)
                    .setDuration(2000)
                    .withEndAction {
                        binding.ripple1.scaleX = 1f
                        binding.ripple1.scaleY = 1f
                        binding.ripple1.alpha = 0.6f
                    }

                delay(600)

                binding.ripple2.animate()
                    .scaleX(2.0f).scaleY(2.0f)
                    .alpha(0f)
                    .setDuration(2000)
                    .withEndAction {
                        binding.ripple2.scaleX = 1f
                        binding.ripple2.scaleY = 1f
                        binding.ripple2.alpha = 0.4f
                    }

                delay(600)

                binding.ripple3.animate()
                    .scaleX(2.0f).scaleY(2.0f)
                    .alpha(0f)
                    .setDuration(2000)
                    .withEndAction {
                        binding.ripple3.scaleX = 1f
                        binding.ripple3.scaleY = 1f
                        binding.ripple3.alpha = 0.2f
                    }

                delay(800)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, this, READER_FLAGS, null)
        Log.d(TAG, "Reader Mode enabled.")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        Log.d(TAG, "Reader Mode disabled.")
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.i(TAG, "NFC Tag Discovered: $tag")
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    executeOfflineProtocol(isoDep)
                }
                withContext(Dispatchers.Main) {
                    handleProtocolResult(result)
                }
            }
        } else {
            Log.w(TAG, "Tag does not support IsoDep communication.")
            runOnUiThread {
                showError("Incompatible NFC tag found.", NfcError.UNKNOWN_ERROR)
            }
        }
    }

    @Throws(IOException::class)
    private fun readAgreementInChunks(isoDep: IsoDep): ByteArray {
        val result = ByteArrayOutputStream()

        // we looking for the first agreement chunk
        // if found lets process
        var command = byteArrayOf(0x00, 0xB2.toByte(), 0x00, 0x00, 0x00)
        Log.d(TAG, "--> 1st agreement chunk: ${command.toHex()}")
        var response = isoDep.transceive(command)

        while (true) {
            if (response.size < 2) throw IOException("APDU too short")

            val sw1 = response[response.size - 2]
            val sw2 = response[response.size - 1]
            val payload = response.copyOfRange(0, response.size - 2)

            if (payload.isNotEmpty()) {
                result.write(payload)
                Log.d(TAG, "<-- Received agreement chunk (${payload.size} bytes). Total: ${result.size()}")
            }

            when (sw1) {
                0x90.toByte() -> {
                    // 90 00 = done
                    Log.i(TAG, "Agreement complete (${result.size()} bytes)")
                    return result.toByteArray()
                }
                0x61.toByte() -> {
                    // 61 xx = more data available, send GET RESPONSE
                    // nfc card is unsigned
                    // kotlin/java byte is signed
                    // initially problem was here
                    // since get response is not a valid length --> card will not respond
                    val le = if (sw2 == 0x00.toByte()) 0xFF else (sw2.toInt() and 0xFF)
                    command = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, le.toByte())
                    Log.d(TAG, "--> GET RESPONSE ($le bytes): ${command.toHex()}")
                    response = isoDep.transceive(command)
                }
                else -> throw IOException("Card returned error SW ${sw1.toHex()}${sw2.toHex()}")
            }
        }
    }

    // @Throws(IOException::class)
    // private fun readTrustInfoInChunks(isoDep: IsoDep): ByteArray {
    //     val assembler = NfcChunkingProtocol.ChunkAssembler()
    //     var command = CMD_GET_TRUST_INFO
    //     Log.d(TAG, "--> 1st trust info chunk: ${command.toHex()}")
    //     var response = isoDep.transceive(command)

    //     while (true) {
    //         if (response.size < 2) throw IOException("APDU response is too short")
    //         val sw1 = response[response.size - 2]
    //         val sw2 = response[response.size - 1]
    //         val payload = response.copyOfRange(0, response.size - 2)

    //         if (payload.isNotEmpty()) {
    //             val chunk = NfcChunkingProtocol.parseChunk(payload)
    //             val assembledData = assembler.addChunk(chunk)
    //             if (assembledData != null) {
    //                 Log.i(TAG, "Trust info complete (${assembledData.size} bytes)")
    //                 return assembledData
    //             }
    //             Log.d(TAG, "<-- Received trust info chunk (${chunk.data.size} bytes). Total assembled: ${assembler.chunks.values.sumOf { it.size }}")
    //         }

    //         if (sw1 == 0x90.toByte() && sw2 == 0x00.toByte()) {
    //             val assembledData = assembler.chunks.values.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    //             Log.i(TAG, "Trust info complete (${assembledData.size} bytes)")
    //             return assembledData
    //         }

    //         if (sw1 == 0x61.toByte()) {
    //             val le = if (sw2 == 0x00.toByte()) 256 else (sw2.toInt() and 0xFF)
    //             command = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, le.toByte())
    //             Log.d(TAG, "--> GET RESPONSE for trust info (${le} bytes): ${command.toHex()}")
    //             response = isoDep.transceive(command)
    //         } else {
    //             throw IOException("Unexpected SW: ${sw1.toHex()}${sw2.toHex()}")
    //         }
    //     }
    // }

    // this eads the full data stream from an IsoDep tag using the READ BINARY command and handling chunking
    // it relies on the HCE service sending an empty payload with SW_OK to signal the end of data
    @Throws(IOException::class)
    private fun readDataInChunks(isoDep: IsoDep): ByteArray {
        val fullPayload = mutableListOf<Byte>()
        var offset = 0
        val maxChunkSize = 253

        Log.d(TAG, "Starting chunked read using READ BINARY...")

        while (true) {
            val p1 = (offset ushr 8).toByte()
            val p2 = (offset and 0xFF).toByte()
            val command = byteArrayOf(0x00, 0xB0.toByte(), p1, p2, 0x00)

            Log.d(TAG, "--> Requesting chunk. Command: ${command.toHex()}")
            val response = isoDep.transceive(command)

            if (response.size < 2) {
                throw IOException("Invalid APDU response: too short")
            }

            val statusCode = response.takeLast(2).toByteArray()
            val payloadChunk = response.dropLast(2).toByteArray()

            // card sends back 90 00 + empty payload with SW_OK --> no more data
            if (statusCode.contentEquals(SW_OK) && payloadChunk.isEmpty()) {
                Log.i(TAG, "Received SW_OK with empty payload. Transmission complete.")
                break }

            if (payloadChunk.isNotEmpty()) {
                fullPayload.addAll(payloadChunk.toList())
                offset += payloadChunk.size
                Log.d(TAG, "<-- Received chunk (${payloadChunk.size} bytes). Total so far: ${fullPayload.size}")
            }

            if (statusCode.contentEquals(SW_OK)) {
                break
            } else if (statusCode[0] != 0x61.toByte()) {
                throw IOException("Received error status from card: ${statusCode.toHex()}")
            } }

        Log.i(TAG, "Finished chunked read. Total size: ${fullPayload.size} bytes.")
        return fullPayload.toByteArray()
    }

    private suspend fun executeOfflineProtocol(isoDep: IsoDep): ProtocolResult {
        var transferId: String? = null
        try {
            isoDep.connect()
            isoDep.timeout = 5000
            Log.d(TAG, "executeOfflineProtocol: IsoDep connected.")
            updateProgress(1, "Connecting to terminal...")

            // 1
            val selectResult = trackCheckpoint("select_aid") {
                Log.d(TAG, "--> Sending SELECT APDU: ${CMD_SELECT_AID.toHex()}")
                val result = isoDep.transceive(CMD_SELECT_AID)
                if (checkSuccess(result)) {
                    Log.d(TAG, "AID selection successful.")
                } else {
                    Log.e(TAG, "AID selection failed!")
                }
                result
            }
            if (!checkSuccess(selectResult)) {
                return ProtocolResult.Error(NfcError.AID_SELECT_FAILED)
            }
            // 1

            transferId = UsageLogger.logTransferStart(TransferDirection.OUTBOUND, null)

            // 2
            val paymentData = trackCheckpoint("get_payment_info") {
                updateProgress(2, "Getting payment details...")
                Log.d(TAG, "Step 1: Reading data stream from HCE using APDU chaining...")
                val assembledData = try {
                    trackCheckpoint("read_payment_info_chunks") {
                        readDataInChunks(isoDep)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read data stream from card.", e)
                    return@trackCheckpoint null
                }

                if (assembledData.isEmpty()) {
                    Log.e(TAG, "Assembled data is empty after chunked read.")
                    return@trackCheckpoint null
                }

                val parsedData = parsePaymentInfo(assembledData)
                if (parsedData != null) {
                    updatePaymentDisplay(parsedData)
                    delay(1500)
                }
                parsedData
            } ?: return ProtocolResult.Error(NfcError.READ_FAILED)
            // 2

            // 3
            val proposalBlock = trackCheckpoint("send_proposal") {
                updateProgress(3, "Sending payment...")
                Log.d(TAG, "Step 2: Creating and sending proposal block...")
                val block = trackCheckpoint("create_proposal_block") {
                    transactionRepository.createNfcTransferProposal(
                        recipientPublicKey.hexToBytes(),
                        paymentAmount
                    )
                }

                if (block != null) {
                    val serializedProposal = trackCheckpoint("serialize_proposal") {
                        transactionRepository.serializeBlock(block)
                    }
                    Log.d("NFC_DEBUG_SENDER", "Final Serialized Proposal (to be sent): ${serializedProposal.toHex()}")
                    Log.d(TAG, "Serialized proposal size: ${serializedProposal.size} bytes")

                    val proposalChunks = NfcChunkingProtocol.createChunks(serializedProposal)
                    Log.d(TAG, "Sending proposal in ${proposalChunks.size} chunks.")

                    for ((index, chunk) in proposalChunks.withIndex()) {
                        Log.d(TAG, "--> Sending proposal chunk ${index + 1}/${proposalChunks.size} (${chunk.size} bytes)")
                        val chunkResult = isoDep.transceive(CMD_SEND_PROPOSAL_CHUNK(chunk))
                        if (!checkSuccess(chunkResult)) {
                            Log.e(TAG, "Proposal chunk ${index + 1} was rejected by the terminal.")
                            return@trackCheckpoint null
                        }
                    }
                    Log.d(TAG, "Successfully sent all proposal chunks.")
                }
                block
            } ?: return ProtocolResult.Error(NfcError.PROPOSAL_REJECTED)
            // 3

            // 4
            val agreementBlock = trackCheckpoint("get_agreement") {
                updateProgress(4, "Getting confirmation...")
                Log.d(TAG, "Step 3: Reading agreement block (with chunking)...")
                val assembledAgreement = try {
                    trackCheckpoint("read_agreement_chunks") {
                        readAgreementInChunks(isoDep)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to read agreement block from card.", e)
                    return@trackCheckpoint null
                }

                if (assembledAgreement.isEmpty()) {
                    Log.e(TAG, "Assembled agreement is empty.")
                    return@trackCheckpoint null
                }

                Log.d(TAG, "Successfully received ${assembledAgreement.size} bytes of agreement data.")
                transactionRepository.deserializeBlock(assembledAgreement)
            } ?: return ProtocolResult.Error(NfcError.AGREEMENT_FAILED)
            // 4

            updateProgress(5, "Transaction complete!")

            // transcation complete thus we store both locally

            transactionRepository.storeOfflineBlock(proposalBlock)
            transactionRepository.storeOfflineBlock(agreementBlock)
            Log.d(TAG, "Stored proposal and agreement blocks locally.")

            // lets tell sync worker to sync this block
            SyncWorker.scheduleImmediateSync(applicationContext)

            val serializedProposal = transactionRepository.serializeBlock(proposalBlock)
            val serializedAgreement = transactionRepository.serializeBlock(agreementBlock)
            UsageLogger.logTransferDone(serializedProposal.size + serializedAgreement.size)

            // trackCheckpoint("exchange_trust_info") {
            //     updateProgress(5, "Exchanging trust data...")
            //     Log.d(TAG, "Step 4: Exchanging trust info...")
            //     val trustData = prepareTrustData()
            //     Log.d(TAG, "--> Sending own trust data (${trustData.size} bytes)")

            //     val trustChunks = NfcChunkingProtocol.createChunks(trustData)
            //     Log.d(TAG, "Sending trust data in ${trustChunks.size} chunks.")

            //     for ((index, chunk) in trustChunks.withIndex()) {
            //         Log.d(TAG, "--> Sending trust chunk ${index + 1}/${trustChunks.size}")
            //         val chunkResult = isoDep.transceive(CMD_SEND_TRUST_INFO(chunk))
            //         if (!checkSuccess(chunkResult)) {
            //             Log.w(TAG, "Failed to send trust chunk ${index + 1}, but proceeding.")
            //             break
            //         }
            //     }

            //     Log.d(TAG, "--> Requesting peer's trust data.")
            //     try {
            //         val theirTrustData = readTrustInfoInChunks(isoDep)
            //         if (theirTrustData.isNotEmpty()) {
            //             processTrustInfo(theirTrustData)
            //         } else {
            //             Log.w(TAG, "Received empty trust info from peer.")
            //         }
            //     } catch (e: IOException) {
            //         Log.e(TAG, "Could not retrieve peer's trust info.", e)
            //     }
            // }

            Log.i(TAG, "Offline protocol completed successfully!")
            return ProtocolResult.Success(paymentData)
        } catch (e: TagLostException) {
            if (transferId != null) UsageLogger.logTransferError(TransferError.DISCONNECTED)
            Log.e(TAG, "Tag lost during communication.", e)
            return ProtocolResult.Error(NfcError.TAG_LOST)
        } catch (e: IOException) {
            if (transferId != null) UsageLogger.logTransferError(TransferError.IO_ERROR)
            Log.e(TAG, "IOException during NFC communication", e)
            return ProtocolResult.Error(NfcError.IO_ERROR)
        } catch (e: Exception) {
            if (transferId != null) UsageLogger.logTransferError(TransferError.UNKNOWN)
            Log.e(TAG, "An unexpected error occurred in executeOfflineProtocol", e)
            return ProtocolResult.Error(NfcError.UNKNOWN_ERROR)
        } finally {
            try {
                if (isoDep.isConnected) {
                    isoDep.close()
                    Log.d(TAG, "executeOfflineProtocol: IsoDep connection closed.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error closing IsoDep", e)
            }
        }
    }

    private fun parsePaymentInfo(response: ByteArray): PaymentData? {
        Log.d(TAG, "parsePaymentInfo: Attempting to parse payload: ${response.toHex()}")
        return try {
            val jsonString = String(response, Charsets.UTF_8)
            Log.d(TAG, "parsePaymentInfo: Parsed JSON: $jsonString")

            val json = JSONObject(jsonString)
            PaymentData(
                amount = json.getLong("amount"),
                publicKey = json.getString("public_key"),
                name = json.optString("name", "Unknown"),
                type = json.getString("type")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payment info", e)
            null
        }
    }

    private suspend fun updatePaymentDisplay(paymentData: PaymentData) {
        withContext(Dispatchers.Main) {
            paymentAmount = paymentData.amount
            recipientName = paymentData.name
            recipientPublicKey = paymentData.publicKey

            binding.tvAmount.text = TransactionRepository.prettyAmount(paymentData.amount)
            binding.tvRecipientName.text = "Sending to: ${paymentData.name}"
            binding.tvRecipientKey.text = "${paymentData.publicKey.take(12)}...${paymentData.publicKey.takeLast(4)}"

            binding.transactionDetailsLayout.visibility = View.VISIBLE
            binding.transactionDetailsLayout.alpha = 0f
            binding.transactionDetailsLayout.animate()
                .alpha(1f)
                .setDuration(500)
                .start()
        }
    }

    // private fun prepareTrustData(): ByteArray {
    //     val addresses = euroTokenCommunity.collectTrustAddresses(50)
    //     val trustJson = JSONObject().apply {
    //         put("addresses", addresses.joinToString(","))
    //     }
    //     return trustJson.toString().toByteArray(Charsets.UTF_8)
    // }

    // private fun processTrustInfo(data: ByteArray) {
    //     try {
    //         val json = JSONObject(String(data, Charsets.UTF_8))
    //         val addresses = json.getString("addresses").split(",").filter { it.isNotEmpty() }

    //         euroTokenCommunity.processTrustAddresses(addresses)

    //         Log.d(TAG, "Successfully processed ${addresses.size} trust addresses from peer.")
    //     } catch (e: JSONException) {
    //         Log.e(TAG, "Failed to process trust info due to a JSON parsing error.", e)
    //     } catch (e: Exception) {
    //         Log.e(TAG, "An unexpected error occurred while processing trust info.", e)
    //     }
    // }

    private suspend fun updateProgress(step: Int, status: String) {
        withContext(Dispatchers.Main) {
            binding.tvReaderStatus.text = status

            val activeColor = getColor(R.color.primary)
            val inactiveColor = getColor(R.color.light_gray)

            binding.step1.setBackgroundColor(if (step >= 1) activeColor else inactiveColor)
            binding.step2.setBackgroundColor(if (step >= 2) activeColor else inactiveColor)
            binding.step3.setBackgroundColor(if (step >= 3) activeColor else inactiveColor)

            if (step >= 2) {
                binding.progressSteps.visibility = View.VISIBLE
                binding.progressSteps.animate().alpha(1f).setDuration(300)
            }

            // more user friendly
            // TODO: add something different then emojis..
            // sufficient for now
            when {
                step <= 1 -> binding.headerTitle.text = "🔗 Connecting..."
                step <= 2 -> binding.headerTitle.text = "📋 Processing..."
                step <= 3 -> binding.headerTitle.text = "💳 Sending Payment..."
                step <= 4 -> binding.headerTitle.text = "✅ Confirming..."
                else -> binding.headerTitle.text = "🎉 Complete!"
            }
        }
    }

    private fun handleProtocolResult(result: ProtocolResult) {
        when (result) {
            is ProtocolResult.Success -> showSuccess(result.paymentData)
            is ProtocolResult.Error -> showError(getErrorMessage(result.error), result.error)
        }
    }

    private fun showSuccess(paymentData: PaymentData) {
        binding.rippleContainer.visibility = View.GONE

        // we have to show succes instead of ripple
        binding.ivNfcResultIcon.setImageResource(R.drawable.ic_check_circle)
        binding.ivNfcResultIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.green)
        binding.ivNfcResultIcon.visibility = View.VISIBLE
        binding.ivNfcResultIcon.scaleX = 0f
        binding.ivNfcResultIcon.scaleY = 0f
        binding.ivNfcResultIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .start()

        binding.headerTitle.text = "✅ Payment Complete!"
        binding.tvReaderStatus.text = "Transaction successful"
        binding.tvReaderResult.text = "You sent ${TransactionRepository.prettyAmount(paymentData.amount)} to ${paymentData.name}"
        binding.tvReaderResult.setTextColor(getColor(R.color.green))
        binding.tvReaderResult.visibility = View.VISIBLE

        binding.btnCancel.visibility = View.GONE
        binding.btnConfirm.apply {
            text = "Done"
            visibility = View.VISIBLE
            setOnClickListener {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }

        binding.progressSteps.visibility = View.GONE
    }

    private fun showError(message: String, error: NfcError) {
        binding.rippleContainer.visibility = View.GONE

        // same as above but now we show an error
        binding.ivNfcResultIcon.setImageResource(R.drawable.ic_error)
        binding.ivNfcResultIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.red)
        binding.ivNfcResultIcon.visibility = View.VISIBLE

        binding.headerTitle.text = "❌ Payment Failed"
        binding.tvReaderStatus.text = "Connection failed"
        binding.tvReaderResult.text = message
        binding.tvReaderResult.setTextColor(getColor(R.color.red))
        binding.tvReaderResult.visibility = View.VISIBLE

        binding.btnCancel.visibility = View.GONE
        binding.btnConfirm.apply {
            text = "Close"
            visibility = View.VISIBLE
            setOnClickListener {
                val resultIntent = Intent().apply {
                    putExtra("nl.tudelft.trustchain.eurotoken.NFC_ERROR", error.name)
                }
                setResult(Activity.RESULT_CANCELED, resultIntent)
                finish()
            }
        }
    }

    private fun checkSuccess(response: ByteArray?): Boolean {
        return response != null && response.size >= 2 &&
            response[response.size - 2] == SW_OK[0] &&
            response[response.size - 1] == SW_OK[1]
    }

    private fun getErrorMessage(error: NfcError): String {
        return when (error) {
            NfcError.NFC_UNSUPPORTED -> "NFC is not supported on this device"
            NfcError.NFC_DISABLED -> "Please enable NFC in settings"
            NfcError.TAG_LOST -> "Connection lost. Please try again"
            NfcError.AID_SELECT_FAILED -> "Incompatible device detected"
            NfcError.READ_FAILED -> "Failed to read payment information"
            NfcError.INSUFFICIENT_BALANCE -> "Insufficient balance for this payment"
            NfcError.PROPOSAL_REJECTED -> "Payment was declined"
            NfcError.AGREEMENT_FAILED -> "Failed to complete payment"
            NfcError.IO_ERROR -> "Communication error. Please try again"
            NfcError.UNKNOWN_ERROR -> "An unexpected error occurred"
            NfcError.HCE_DATA_NOT_READY -> "The other device is not ready."
            NfcError.OTHER -> "An unexpected error occurred."
        }
    }

    private data class PaymentData(
        val amount: Long,
        val publicKey: String,
        val name: String,
        val type: String
    )

    private sealed class ProtocolResult {
        data class Success(val paymentData: PaymentData) : ProtocolResult()
        data class Error(val error: NfcError) : ProtocolResult()
    }
    private fun Byte.toHex(): String = "%02X".format(this)
}
