package nl.tudelft.trustchain.eurotoken.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.nfc.NfcError
import java.io.IOException
import android.nfc.TagLostException
import android.view.View
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.trustchain.eurotoken.databinding.ActivityNfcReaderBinding
import java.util.*
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
// for reading data

// TODO check if we can delete nfchandler, nfcState, and nfcViewModel
// not for now

// activity handles reading dtaa from nfc HCE service
// on other device -> uses NFC reader mode
// apdu exchange
class NfcReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityNfcReaderBinding

    companion object {
        private const val TAG = "NfcReader"
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        // private const val CONNECTION_TIMEOUT_MS = 5000

        // isodep apdu max size 255 bytes -> saftey margin
        private const val MAX_CHUNK = 250
        private const val LENGTH_HEADER_SIZE = 4 // PREPENDING LENGTH

        // same as in hceservice
        // ISO7816-4 state that Le=0 means 256 for short APDUs
        // thus reader expects up to 256 bytes
        private const val CHUNK_SIZE_LE: Byte = 0x00.toByte()

        // extra
//        const val EXTRA_NFC_DATA = "nl.tudelft.trustchain.eurotoken.NFC_DATA"
//        const val EXTRA_NFC_STATUS = "nl.tudelft.trustchain.eurotoken.NFC_STATUS"
//        const val EXTRA_NFC_ERROR = "nl.tudelft.trustchain.eurotoken.NFC_ERROR"

        // AID
        // this is what HCE service has to respond to
        // must match apduservice.xml!!
        // ISO/IEC 7816-4 -> 5-16 bytes, start with 0xF2
        private const val HCE_GOAL_AID = "F222222222"

        // select app via AID
        // follows ISO/IEC 7816-4 (AID) and ISO/IEC 7816-3 (SELECT)
        // CLA | INS | P1 | P2 | Lc | Data
        val CMD_SELECT_AID: ByteArray = byteArrayOf(
            0x00.toByte(),
            0xA4.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            HCE_GOAL_AID.length.div(2).toByte() // Lc field = AID length
        ) + HCE_GOAL_AID.hexToBytes()

        // Command to request the data after successful selection
        val CMD_READ_DATA: ByteArray = byteArrayOf(
            0x00.toByte(),
            0xB0.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte()) // success

        // private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
        // private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        // private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        private val SW_CMD_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00.toByte())
        private val SW_WRONG_PARAMETERS_P1P2 = byteArrayOf(0x6B.toByte(), 0x00.toByte())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            updateStatus("NFC is not available on this device.")
            finishWithError(NfcError.NFC_UNSUPPORTED)
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            updateStatus("NFC is disabled. Please enable it.")
            finishWithError(NfcError.NFC_DISABLED)
            return
        }
        updateStatus(getString(R.string.waiting_for_nfc_tap))
        binding.tvReaderResult.visibility = View.INVISIBLE
    }

    override fun onResume() {
        super.onResume()
        // enable reader mode when Activity is resumed
        // when the tag is discovered --> this will be invoked
        nfcAdapter?.enableReaderMode(this, this, READER_FLAGS, null)
        Log.d(TAG, "Reader Mode enabled.")
    }

    override fun onPause() {
        // TODO check if sufficient!
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        Log.d(TAG, "Reader Mode disabled.")
    }

    // nfc tag is found that matches the rquired flags --> NFC-A
    override fun onTagDiscovered(tag: Tag?) {
        Log.i(TAG, "NFC Tag Discovered: $tag")
        // here we try to get the isodep interface
        // ISO 7816-4 APDUs
        // first check if supports isodep
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            // now handled thread handling here, must not run on main thread
            // blocking nfc ops on background threads
            // only one worker for interactions --> app needds to stay smooth
            // its necessary to launch a dispatchers.io thread
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    commWithTag(isoDep)
                }
                // communication result for ui
                handleCommResult(result)
            }
        } else {
            Log.w(TAG, "Tag does not support IsoDep communication.")
            runOnUiThread { updateStatus("Incompatible NFC tag found.") }
            // Maybe finish with error or just wait for a compatible tag
        }
    }

    // le is length of data to read
    private fun createReadBinaryApdu(offset: Int, le: Byte): ByteArray {
        return byteArrayOf(
            0x00.toByte(),
            0xB0.toByte(),
            (offset shr 8).toByte(),
            (offset and 0xFF).toByte(),
            le
        )
    }

    // communication with tag
    // reads string data
    private suspend fun commWithTag(isoDep: IsoDep): Pair<String?, NfcError?> {
        // isodep -> |connect | |transceive| |close| are blocking
        try {
            isoDep.connect()
            isoDep.timeout = 5000

            runOnUiThread { updateStatus("Tag connected. Selecting App...") }
            Log.d(TAG, "Sending SELECT AID: ${CMD_SELECT_AID.toHex()}")
            val selectResult = isoDep.transceive(CMD_SELECT_AID)
            Log.d(TAG, "SELECT AID response: ${selectResult.toHex()}")

            if (!checkSuccess(selectResult)) {
                Log.e(TAG, "SELECT AID failed. Status: ${selectResult.getStatusString()}")
                return Pair(null, NfcError.AID_SELECT_FAILED)
            }

            Log.i(TAG, "AID Selected successfully.")

            // NOW read 4-byte header for data
            runOnUiThread { updateStatus("App selected. Reading payload size...") }
            val lenHeaderCmd = createReadBinaryApdu(0, LENGTH_HEADER_SIZE.toByte())
            Log.d(TAG, "Reading length header: ${lenHeaderCmd.toHex()}")
            val lenHeaderResponse = isoDep.transceive(lenHeaderCmd)

            // Log.d(TAG, "Sending READ DATA: ${CMD_READ_DATA.toHex()}")
            // val readResult = isoDep.transceive(CMD_READ_DATA)
            // Log.d(TAG, "READ DATA response: ${readResult.toHex()}")

            if (!checkSuccess(lenHeaderResponse)) {
                Log.e(TAG, "Failed to read length header. Status: ${lenHeaderResponse.getStatusString()}")
                return Pair(null, NfcError.READ_FAILED)
            }

            val lenHeaderBytes = lenHeaderResponse.copyOfRange(0, lenHeaderResponse.size - 2)
            if (lenHeaderBytes.size != LENGTH_HEADER_SIZE) {
                Log.e(TAG, "Length header incorrect size. Expected $LENGTH_HEADER_SIZE, got ${lenHeaderBytes.size}")
                return Pair(null, NfcError.READ_FAILED)
            }
            val dataSize = ByteBuffer.wrap(lenHeaderBytes).int // not sure if here we need to check for overflow

            Log.i(TAG, "Actual data size to read (from header): $dataSize bytes.")

            if (dataSize < 0 || dataSize > 1024 * 1024) {
                Log.e(TAG, "Invalid total actual data size from header: $dataSize")
                return Pair(null, NfcError.READ_FAILED)
            }
            if (dataSize == 0) {
                Log.i(TAG, "Actual data size is 0. Returning empty payload.")
                return Pair("", null) // Successfully read an empty payload
            }

            runOnUiThread { updateStatus("Payload size: $dataSize bytes. Reading data...") }

            // correct datas size,,  lets read some data :)

            // inmem buffer
            // stream destination of written bytes/ internal byte array to keep track of portions/complete payload
            val concPayload = ByteArrayOutputStream()

            // how many bytes we read from hce service?
            var readDataB = 0

            // offset HCE buffer -> hedaer+data
            var HCEOffset = LENGTH_HEADER_SIZE

            while (readDataB < dataSize) {
                val chunk = createReadBinaryApdu(HCEOffset, CHUNK_SIZE_LE)
                Log.d(TAG, "Reading data: ${chunk.toHex()}")

                val chunkResponse = isoDep.transceive(chunk)

                if (!checkSuccess(chunkResponse)) {
                    // -2 for status bytes sw1,sw2
                    val statusB = chunkResponse.copyOfRange(chunkResponse.size - 2, chunkResponse.size)
                    if (Arrays.equals(statusB, SW_WRONG_PARAMETERS_P1P2) && readDataB > 0) {
                        // fail
                        // Log.e(TAG, "READ data chunk failed. HCE reported Offset out of bounds (6B00) prematurely. Expected $totalActualDataSize, got $dataBytesReadSoFar. HCE Offset=$currentHcePayloadOffset")
                        Log.e(TAG, "READ data chunk failed. HCE reported Offset out of bounds (6B00) prematurely. Expected $dataSize, got $readDataB. HCE Offset=$HCEOffset")
                    } else {
                        Log.e(TAG, "READ data chunk failed. Status: $chunkResponse")
                    }
                    return Pair(null, NfcError.READ_FAILED)
                }

                // -2 for status bytes sw1,sw2
                val chunk2 = chunkResponse.copyOfRange(0, chunkResponse.size - 2)
                if (chunk2.isEmpty() && readDataB < dataSize) {
                    Log.e(TAG, "READ data chunk failed. HCE reported Offset out of bounds (6B00) prematurely. Expected $dataSize, got $readDataB. HCE Offset=$HCEOffset")
                    return Pair(null, NfcError.READ_FAILED)
                }
                if (chunk2.isEmpty()) {
                    break
                }
                concPayload.write(chunk2)
                readDataB += chunk2.size
                HCEOffset += chunk2.size

                Log.d(TAG, "Read $readDataB bytes from HCE service, HCE Offset=$HCEOffset, Total data size: $dataSize, actual data = ${concPayload.size()}/$dataSize")
                runOnUiThread { updateStatus("Reading data... $readDataB/$dataSize") }
            }
            // not sure if enough is validated?

            val dataBytes = concPayload.toByteArray()
            val payloadString = String(dataBytes, Charsets.UTF_8)
            Log.i(TAG, "Data read successfully: $payloadString, size=${dataBytes.size}")
            return Pair(payloadString, null)
        }

        //             if (checkSuccess(lenHeaderResponse)) {
//                 val payloadBytes = lenHeaderResponse.copyOfRange(0, lenHeaderResponse.size - 2) // no SW1,SW2
//                 if (payloadBytes.isEmpty()) {
//                     Log.w(TAG, "No data payload received from HCE service (empty response).")
//                     finishWithError(NfcError.READ_FAILED)
//                     return@withContext
//                 }
//                 val payloadString = String(payloadBytes, Charsets.UTF_8)
//                 Log.i(TAG, "Data read successfully: $payloadString")

//                 val confirmationStatus = "Received data: $payloadString"
//                 runOnUiThread {
//                     binding.progressSpinner.visibility = View.GONE
//                     binding.ivNfcResultIcon.visibility = View.VISIBLE
//                     updateStatus("Data received.")
//                     updateResult(confirmationStatus)

//                     binding.btnConfirm.visibility = View.VISIBLE
//                     binding.btnConfirm.setOnClickListener {
//                         finishWithSuccess(payloadString)
//                     }
//                 }
// //                    finishWithSuccess(payloadString)
//             } else {
//                 val statusString = readResult.getStatusString()
//                 Log.e(TAG, "READ DATA failed. Status: $statusString")
//                 val error = when {
//                     Arrays.equals(readResult, SW_CONDITIONS_NOT_SATISFIED) -> NfcError.HCE_DATA_NOT_READY
//                     else -> NfcError.READ_FAILED
//                 }
//                 runOnUiThread { updateStatus("Failed to read data. Status: $statusString") }
// //                    finishWithError(error)
//             }

        catch (e: TagLostException) {
            Log.e(TAG, "Tag lost during communication.", e)
            return Pair(null, NfcError.TAG_LOST)
        } catch (e: IOException) {
            Log.e(TAG, "IOException during NFC communication: ${e.message}", e)
            return Pair(null, NfcError.IO_ERROR)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during NFC communication.", e)
            return Pair(null, NfcError.UNKNOWN_ERROR)
        } finally {
            try {
                if (isoDep.isConnected) {
                    isoDep.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error closing IsoDep.", e)
            }
        }
    }

    // handle ui finally

    private fun handleCommResult(resultFromCommWithTag: Pair<String?, NfcError?>) {
        val (payloadString, error) = resultFromCommWithTag

        binding.progressSpinner.visibility = View.GONE
        binding.ivNfcResultIcon.visibility = View.VISIBLE

        if (error == null && payloadString != null) {
            Log.i(TAG, "Data read successfully (in handleCommResult): $payloadString")
            val confirmationStatus = "Received data: $payloadString"

            binding.ivNfcResultIcon.setImageResource(R.drawable.ic_baseline_check_circle_outline_24)
            binding.ivNfcResultIcon.setColorFilter(getColor(R.color.green))

            updateStatus("Data received.")
            updateResult(confirmationStatus)

            binding.btnConfirm.visibility = View.VISIBLE
            binding.btnConfirm.text = "Confirm"
            binding.btnConfirm.setOnClickListener {
                finishWithSuccess(payloadString)
            }
        } else {
            val resolvedError = error ?: NfcError.UNKNOWN_ERROR
            val statusMessageForUI = "Failed to read data. Error: ${resolvedError.name}"

            Log.e(TAG, "NFC operation failed (in handleCommResult). Error: ${resolvedError.name}")

            binding.ivNfcResultIcon.setColorFilter(getColor(R.color.red))

            updateStatus(statusMessageForUI)
            updateResult("NFC Error. Please try again.")

            binding.btnConfirm.visibility = View.VISIBLE
            binding.btnConfirm.text = "Close"
            binding.btnConfirm.setOnClickListener {
                finishWithError(resolvedError)
            }
        }
    }

    // helpers

    private fun finishWithSuccess(data: String) {
        val resultIntent = Intent()
        resultIntent.putExtra("nl.tudelft.trustchain.eurotoken.NFC_DATA", data)
        resultIntent.putExtra("nl.tudelft.trustchain.eurotoken.NFC_STATUS", "SUCCESS")
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun finishWithError(error: NfcError) {
        val resultIntent = Intent()
        resultIntent.putExtra("nl.tudelft.trustchain.eurotoken.NFC_STATUS", "ERROR")
        resultIntent.putExtra("nl.tudelft.trustchain.eurotoken.NFC_ERROR", error.name)
        setResult(Activity.RESULT_CANCELED, resultIntent)
        finish()
    }

    private fun updateStatus(message: String) {
        // not on main thread
        runOnUiThread {
            binding.tvReaderStatus.text = message
        }
    }

    private fun updateResult(result: String) {
        runOnUiThread {
            binding.tvReaderResult.text = result
            binding.tvReaderResult.visibility = View.VISIBLE
            // binding.tvReaderResult.visibility = View.VISIBLE
        }
    }

    private fun checkSuccess(response: ByteArray?): Boolean {
        return response != null && response.size >= 2 &&
            response[response.size - 2] == SW_OK[0] &&
            response[response.size - 1] == SW_OK[1]
    }

    private fun ByteArray.getStatusString(): String {
        return if (this.size >= 2) {
            this.copyOfRange(this.size - 2, this.size).toHex()
        } else {
            "Invalid response"
        }
    }

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }.uppercase()
}
