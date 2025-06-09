package nl.tudelft.trustchain.eurotoken.benchmarks

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

object UsageLogger {

    private var dao: UsageEventsDao? = null
    private val scope = CoroutineScope(Dispatchers.IO) // Use IO dispatcher for database operations
    private var currentTransactionId: String? = null
    private var currentTransferId: String? = null
    private val activeCheckpoints = mutableMapOf<String, Long>() // checkpointName -> startTimestamp


    fun initialize(context: Context) {
        if (dao == null) {
            dao = UsageAnalyticsDatabase.getInstance(context.applicationContext).usageEventsDao()
        }
    }

    private fun generateTransactionId(): String = UUID.randomUUID().toString()
    private fun generateTransferId(): String = UUID.randomUUID().toString()
    private fun getCurrentTimestamp(): Long = System.currentTimeMillis()

    // --- Logging methods ---

    fun logTransactionStart(payload: String): String {
        val transactionId = generateTransactionId()
        val event = TransactionStartEvent(
            transactionId = transactionId,
            timestamp = getCurrentTimestamp(),
            payload = payload
        )
        scope.launch { dao?.insertTransactionStartEvent(event) }
        currentTransactionId = transactionId
        activeCheckpoints.clear() // Clear any previous checkpoint state
        Log.i("UsageLogger", "Transaction started: $transactionId")
        return transactionId
    }

    fun logTransactionError(error: String) {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransactionError called before logTransactionStart")
        }
        val event = TransactionErrorEvent(
            transactionId = currentTransactionId !!,
            timestamp = getCurrentTimestamp(),
            error = error
        )
        scope.launch { dao?.insertTransactionErrorEvent(event) }
        Log.i("UsageLogger", "Transaction error: $error")
    }

    fun logTransactionCancel(reason: TransactionCancelReason) {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransactionCancel called before logTransactionStart")
        }
        val event = TransactionCancelEvent(
            transactionId = currentTransactionId !!,
            timestamp = getCurrentTimestamp(),
            reason = reason
        )
        scope.launch { dao?.insertTransactionCancelEvent(event) }
        Log.i("UsageLogger", "Transaction cancelled: $reason")
    }

    fun logTransactionDone() {
        if (currentTransactionId == null) {
            // do nothing
            return
        }
        val event = TransactionDoneEvent(
            transactionId = currentTransactionId !!,
            timestamp = getCurrentTimestamp()
        )
        scope.launch { dao?.insertTransactionDoneEvent(event) }
        activeCheckpoints.clear() // Clear checkpoint state when transaction ends
        Log.i("UsageLogger", "Transaction done")
    }

    fun logTransferStart( direction: TransferDirection, payloadSize: Int?): String {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransferStart called before logTransactionStart")
        }
        val transferId = generateTransferId()
        val event = TransferStartEvent(
            transactionId = currentTransactionId !!,
            transferId = transferId,
            timestamp = getCurrentTimestamp(),
            payloadSize = payloadSize ?: 0,
            direction = direction
        )
        currentTransferId = transferId
        scope.launch { dao?.insertTransferStartEvent(event) }
        Log.i("UsageLogger", "Transfer started: $transferId")
        return transferId
    }

    fun logTransferDone(receivedPayload: Int?) {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransferDone called before logTransactionStart")
        }
        if (currentTransferId == null) {
            throw IllegalStateException("logTransferDone called before logTransferStart")
        }
        val event = TransferDoneEvent(
            transferId = currentTransferId !!,
            transactionId = currentTransactionId !!,
            timestamp = getCurrentTimestamp(),
            receivedPayload = receivedPayload
        )
        scope.launch { dao?.insertTransferDoneEvent(event) }
        Log.i("UsageLogger", "Transfer done")
    }

    fun logTransferError(error: TransferError) {
        if (currentTransferId == null) {
            return
        }
        val event = TransferErrorEvent(
            transferId = currentTransferId !!,
            timestamp = getCurrentTimestamp(),
            error = error
        )
        scope.launch { dao?.insertTransferErrorEvent(event) }
        Log.i("UsageLogger", "Transfer error: $error")
    }

    fun logTransferCancelled() {
        if (currentTransferId == null) {
            return
        }
        val event = TransferCancelledEvent(
            transferId = currentTransferId !!,
            timestamp = getCurrentTimestamp()
        )
        scope.launch { dao?.insertTransferCancelledEvent(event) }
        Log.i("UsageLogger", "Transfer cancelled")
    }

    fun logTransactionCheckpointStart(checkpointName: String) {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransactionCheckpointStart called before logTransactionStart")
        }
        val timestamp = getCurrentTimestamp()
        activeCheckpoints[checkpointName] = timestamp
        val event = TransactionCheckpointStartEvent(
            transactionId = currentTransactionId!!,
            checkpointName = checkpointName,
            timestamp = timestamp
        )
        scope.launch { dao?.insertTransactionCheckpointStartEvent(event) }
        Log.i("UsageLogger", "Transaction checkpoint '$checkpointName' started")
    }

    fun logTransactionCheckpointEnd(checkpointName: String) {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransactionCheckpointEnd called before logTransactionStart")
        }
        val timestamp = getCurrentTimestamp()
        val event = TransactionCheckpointEndEvent(
            transactionId = currentTransactionId!!,
            checkpointName = checkpointName,
            timestamp = timestamp
        )
        scope.launch { dao?.insertTransactionCheckpointEndEvent(event) }
        
        // Calculate duration if start was logged
        val startTime = activeCheckpoints[checkpointName]
        val duration = if (startTime != null) timestamp - startTime else null
        Log.i("UsageLogger", "Transaction checkpoint '$checkpointName' ended${if (duration != null) " (${duration}ms)" else ""}")
    }
}
