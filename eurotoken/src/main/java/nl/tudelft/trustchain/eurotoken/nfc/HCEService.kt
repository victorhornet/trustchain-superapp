package nl.tudelft.trustchain.eurotoken.nfc
import kotlin.math.min
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.util.*
// import com.google.gson.Gson

// emulate eurotoken card
// we respond to specific AID and implemented custom READ command.
class EuroTokenHCEService : HostApduService() {
    companion object {
        private const val TAG = "EuroTokenHCE"

        // isodep apdu max size 255 bytes -> saftey margin
        private const val MAX_CHUNK = 250 // now apdu ? potentially chunkking?

        // Status words
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte()) // success
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
        private val SW_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_CLA_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte()) // for chunking
        private val SW_WRONG_PARAMETERS_P1P2 = byteArrayOf(0x6B.toByte(), 0x00.toByte())

        // AID ->HCE service only activated when read specific AID
        const val AID_EUROTOKEN = "F222222222"

        // Custom APDU command
        // (READ APDU: 00 B0 00 00 00 (CLA=00, INS=B0, P1=00, P2=00, Le=00 - Read max available ->256Bytes))
        val CMD_SELECT_AID = byteArrayOf(
            0x00.toByte(), // CLA -> iso7816-4 command class
            0xA4.toByte(), // INS -> SELECT
            0x04.toByte(), // P1  -> select by DF name
            0x00.toByte(), // P2  -> first or only occurrence
        )
        val CMD_READ_DATA = byteArrayOf(
            0x00.toByte(), // CLA
            0xB0.toByte(), // INS -> READ BINARY
            0x00.toByte(), // P1 = 00
            0x00.toByte(), // P2 = 00
            0x00.toByte() // Le = 00 (Request maximum available data)
        )

        val CMD_READ_BINARY_INS = 0xB0.toByte()

        // didnt work without
        // so now shared mutable state
        @Volatile
        private var currentPayloadBytes: ByteArray? = null

        fun setPayload(payloadWithHeader: ByteArray?) {
            synchronized(this) {
                currentPayloadBytes = payloadWithHeader
                if (payloadWithHeader != null) {
                    Log.d(TAG, "Payload (with header) set for HCE. Total HCE payload size: ${payloadWithHeader.size}")
                } else {
                    Log.d(TAG, "Payload cleared for HCE.")
                }
            }
        }

        fun clearPayload() {
            setPayload(null)
        }
        // fun setPayload(payload: ByteArray?) {
        //     synchronized(this) {
        //         currentPayloadBytes = payload
        //         if (payload != null) {
        //             if (payload.size > MAX_CHUNK) {
        //                 Log.w(TAG, "Payload size (${payload.size}) exceeds MAX_EXPECTED_CHUNK_SZE ($MAX_CHUNK). .")
        //             }
        //             Log.d(TAG, "Payload set for HCE. Size: ${payload.size}")
        //         } else {
        //             Log.d(TAG, "Payload cleared for HCE.")
        //         }
        //     }
        // }

        // fun clearPayload() {
        //     setPayload(null)
        // }
    }

    // android sys calls this when apdu command is received from the reader
    // after aid has been selected
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        if (commandApdu == null) {
            return SW_UNKNOWN_ERROR
        }
        Log.i(TAG, "APDU Received: ${commandApdu.toHex()}")

        // AIDlength is lenght of commanddata
        // included  0xFF to counteract sign extension
        // ensures 255, not -1.. needed for proper process :)
        if (isSelectAidCommand(commandApdu)) {
            Log.i(TAG, "SELECT AID command received")
            val aidLength = commandApdu[4].toInt() and 0xFF // issssssssssxxxxxx
            val aidBytes = commandApdu.copyOfRange(5, 5 + aidLength)
            if (AID_EUROTOKEN == aidBytes.toHex()) {
                Log.i(TAG, "EuroToken AID selected successfully")
                return SW_OK
            } else {
                Log.w(TAG, "Received SELECT AID for unknown AID: ${aidBytes.toHex()}")
                return SW_CLA_NOT_SUPPORTED
            }
        }
        val cmd_read_data_ins = 0xB0.toByte()
        if (commandApdu.size >= 4 && commandApdu[0] == 0x00.toByte() && commandApdu[1] == cmd_read_data_ins) {
            synchronized(EuroTokenHCEService) {
                val payload = currentPayloadBytes
                if (payload == null) {
                    Log.w(TAG, "READ DATA: No payload available.")
                    return SW_CONDITIONS_NOT_SATISFIED
                }

                if (commandApdu.size < 5) { // should be-> CLA, INS, P1, P2, Le
                    Log.w(TAG, "READ BINARY: APDU too short.")
                    return SW_WRONG_PARAMETERS_P1P2
                }

                // p1 & p2 offset for chunking
                val p1Offset = commandApdu[2].toInt() and 0xFF
                val p2Offset = commandApdu[3].toInt() and 0xFF
                val offset = (p1Offset shl 8) + p2Offset

                var le = commandApdu[4].toInt() and 0xFF
                if (le == 0) {
                    // ISO7816-4 state that Le=0 means 256 for short APDUs
                    // thus reader expects up to 256 bytes
                    le = 256
                }
                // (header + actual data),  check if offset  isnt out of bounds
                if (offset < 0 || offset > payload.size) {
                    Log.e(TAG, "READ BINARY: Offset $offset out of bounds for HCE payload size ${payload.size}")
                    return SW_WRONG_PARAMETERS_P1P2
                }

                // bytes to read by reader && actual bytes to send to reader
                val bytesToRead = min(le, MAX_CHUNK)
                val actualBytes = min(bytesToRead, payload.size - offset)

                if (actualBytes < 0) {
                    Log.e(TAG, "READ BINARY: actualBytesToSend calculation error. Offset: $offset, Payload Size: ${payload.size}")
                    return SW_UNKNOWN_ERROR // Should not happen with correct offset check
                }

                val chunk = payload.copyOfRange(offset, offset + actualBytes)
                Log.d(TAG, "READ BINARY: HCE_payload_offset=$offset, Le_from_reader=$le (card_will_send_max $bytesToRead), sending $actualBytes bytes.")
                return chunk + SW_OK
            }
        }
        return SW_INS_NOT_SUPPORTED
    }

    // called when nfc connection is lost or the reader deselects our aid
    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "HCE Service Deactivated. Reason: $reason")
        // potentially clear data???!
    }

    private fun isSelectAidCommand(apdu: ByteArray): Boolean {
        return apdu.size >= 5 &&
            apdu[0] == CMD_SELECT_AID[0] &&
            apdu[1] == CMD_SELECT_AID[1] &&
            apdu[2] == CMD_SELECT_AID[2] &&
            apdu[3] == CMD_SELECT_AID[3]
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }.uppercase()

    private fun String.hexToBytes(): ByteArray {
        // check even?
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
    private fun Byte.toHex(): String = "%02x".format(this).uppercase()
}
