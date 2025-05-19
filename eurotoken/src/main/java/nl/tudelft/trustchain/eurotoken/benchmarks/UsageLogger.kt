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
    private var currentTransactionId: String? = null;
    private var currentTransferId: String? = null

    // Call this ideally from your Application class or a central initialization point
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
        currentTransactionId = transactionId;
        Log.i("UsageLogger", "Transaction started: $transactionId")
        return transactionId // Return to caller to correlate subsequent events
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
            throw IllegalStateException("logTransactionDone called before logTransactionStart")
        }
        val event = TransactionDoneEvent(
            transactionId = currentTransactionId !!,
            timestamp = getCurrentTimestamp()
        )
        scope.launch { dao?.insertTransactionDoneEvent(event) }
        Log.i("UsageLogger", "Transaction done")
    }

    fun logTransferStart(payloadSize: Long, direction: TransferDirection): String {
        if (currentTransactionId == null) {
            throw IllegalStateException("logTransferStart called before logTransactionStart")
        }
        val transferId = generateTransferId()
        val event = TransferStartEvent(
            transactionId = currentTransactionId !!,
            transferId = transferId,
            timestamp = getCurrentTimestamp(),
            payloadSize = payloadSize,
            direction = direction
        )
        currentTransferId = transferId
        scope.launch { dao?.insertTransferStartEvent(event) }
        Log.i("UsageLogger", "Transfer started: $transferId")
        return transferId // Return to caller for correlating end/error events
    }

    fun logTransferDone() {
        if (currentTransferId == null) {
            throw IllegalStateException("logTransferDone called before logTransferStart")
        }
        val event = TransferDoneEvent(
            transferId = currentTransferId !!,
            timestamp = getCurrentTimestamp()
        )
        scope.launch { dao?.insertTransferDoneEvent(event) }
        Log.i("UsageLogger", "Transfer done")
    }

    fun logTransferError(error: TransferError) {
        if (currentTransferId == null) {
            throw IllegalStateException("logTransferError called before logTransferStart")
        }
        val event = TransferErrorEvent(
            transferId = currentTransferId !!,
            timestamp = getCurrentTimestamp(),
            error = error
        )
        scope.launch { dao?.insertTransferErrorEvent(event) }
        Log.i("UsageLogger", "Transfer error: $error")
    }
}
