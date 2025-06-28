package nl.tudelft.trustchain.common.eurotoken.benchmarks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MaxAvgResult(val max: Double, val average: Double, val unit: String = "")

data class MaxAvgIntResult(val max: Int, val average: Double, val unit: String = "")

class UsageBenchmarkCalculator(private val dao: UsageEventsDao) {

    private suspend fun <T> onIO(block: suspend CoroutineScope.() -> T) = withContext(Dispatchers.IO, block)

    // --- Transaction Benchmarks ---

    suspend fun calculateMaxAndAvgTransactionPayloadSize(): MaxAvgIntResult? = onIO {
        val successfulTransactions = dao.getAllTransactionDoneEvents()
        if (successfulTransactions.isEmpty()) return@onIO null

        val transactionPayloads = mutableListOf<Int>()
        for (txDone in successfulTransactions) {
            val transfers = dao.getTransferDoneEventsForTransaction(txDone.transactionId)
            var currentTransactionPayload = 0
            for (transfer in transfers) {
                val transferStart = dao.getTransferStartEvent(transfer.transferId) ?: continue
                currentTransactionPayload += transferStart.payloadSize + (transfer.receivedPayload ?: 0)
            }
            if (currentTransactionPayload > 0) {
                transactionPayloads.add(currentTransactionPayload)
            }
        }
        if (transactionPayloads.isEmpty()) return@onIO MaxAvgIntResult(0, 0.0, "bytes")

        MaxAvgIntResult(
            transactionPayloads.maxOrNull() ?: 0,
            transactionPayloads.average(),
            "bytes"
        )
    }

    suspend fun calculateMaxAndAvgTransactionTime(): MaxAvgResult? = onIO {
        val successfulTransactions = dao.getAllTransactionDoneEvents()
        if (successfulTransactions.isEmpty()) return@onIO null

        val transactionDurations = successfulTransactions.mapNotNull { txDone ->
            dao.getTransactionStartEvent(txDone.transactionId)?.let { txStart ->
                val duration = txDone.timestamp - txStart.timestamp
                if (duration >= 0) duration else null
            }
        }

        if (transactionDurations.isEmpty()) return@onIO MaxAvgResult(0.0, 0.0, "ms")
        MaxAvgResult(
            (transactionDurations.maxOrNull() ?: 0L).toDouble(),
            transactionDurations.average(),
            "ms"
        )
    }

    suspend fun calculateTotalTransactionCount(): Long = onIO {
        dao.getTransactionStartCount()
    }

    suspend fun calculateAvgTransactionSuccessRate(): Double = onIO {
        val totalStarted = dao.getTransactionStartCount()
        if (totalStarted == 0L) return@onIO 0.0
        val totalDone = dao.getTransactionDoneCount()
        (totalDone.toDouble() / totalStarted) * 100.0
    }

    suspend fun calculateAvgTransactionErrorRate(): Double = onIO {
        val totalStarted = dao.getTransactionStartCount()
        if (totalStarted == 0L) return@onIO 0.0
        val totalErrored = dao.getTransactionErrorCount()
        (totalErrored.toDouble() / totalStarted) * 100.0
    }

    // --- Transfer Benchmarks ---

    suspend fun calculateMaxAndAvgTransferThroughput(): MaxAvgResult? = onIO {
        val successfulTransfers = dao.getAllTransferDoneEvents()
        if (successfulTransfers.isEmpty()) return@onIO null

        val throughputs = successfulTransfers.mapNotNull { transferDone ->
            dao.getTransferStartEventByTransferId(transferDone.transferId)?.let { transferStart ->
                val durationMs = transferDone.timestamp - transferStart.timestamp
                if (durationMs > 0) {
                    val totalBytes = transferStart.payloadSize + (transferDone.receivedPayload ?: 0)
                    // Convert to kbps: (bytes * 8 bits/byte) / (ms / 1000 ms/s) / 1000 kbits/bit
                    (totalBytes.toDouble() * 8 * 1000) / durationMs / 1000
                } else {
                    null
                }
            }
        }

        if (throughputs.isEmpty()) return@onIO MaxAvgResult(0.0, 0.0, "kbps")
        MaxAvgResult(
            throughputs.maxOrNull() ?: 0.0,
            throughputs.average(),
            "kbps"
        )
    }

    suspend fun calculateTotalTransferCount(): Long = onIO {
        dao.getTransferStartCount()
    }

    suspend fun calculateAvgTransferFailureRate(): Double = onIO {
        val totalStarted = dao.getTransferStartCount()
        if (totalStarted == 0L) return@onIO 0.0
        val totalErrored = dao.getTransferErrorCount()
        (totalErrored.toDouble() / totalStarted) * 100.0
    }

    // --- Breakdown Benchmarks ---

    suspend fun calculateTransactionBreakdown(transactionId: String): TransactionBreakdown? = onIO {
        val startEvent = dao.getTransactionStartEvent(transactionId) ?: return@onIO null
        val endEvent = dao.getTransactionDoneEvent(transactionId) ?: return@onIO null
        val totalDuration = endEvent.timestamp - startEvent.timestamp
        if (totalDuration <= 0) return@onIO null

        val checkpointStarts = dao.getTransactionCheckpointStartEvents(transactionId)
        val checkpointEnds = dao.getTransactionCheckpointEndEvents(transactionId)
        val checkpointTimings = aggregateCheckpointTimings(checkpointStarts, checkpointEnds)
        val totalCheckpointTime = checkpointTimings.sumOf { it.totalDurationMs }
        val otherTime = maxOf(0L, totalDuration - totalCheckpointTime)

        TransactionBreakdown(
            transactionId = transactionId,
            totalDurationMs = totalDuration,
            checkpointTimings = checkpointTimings,
            otherDurationMs = otherTime
        )
    }

    suspend fun calculateAllTransactionBreakdowns(): List<TransactionBreakdown> = onIO {
        dao.getAllTransactionDoneEvents().mapNotNull { transaction ->
            calculateTransactionBreakdown(transaction.transactionId)
        }
    }

    suspend fun calculateAverageTransactionBreakdown(): AverageTransactionBreakdown? = onIO {
        val allBreakdowns = calculateAllTransactionBreakdowns()
        if (allBreakdowns.isEmpty()) return@onIO null

        val totalTransactions = allBreakdowns.size
        val avgTotalDuration = allBreakdowns.sumOf { it.totalDurationMs } / totalTransactions.coerceAtLeast(1)

        val checkpointAggregation = mutableMapOf<String, MutableList<Long>>()
        allBreakdowns.forEach { breakdown ->
            breakdown.checkpointTimings.forEach { timing ->
                checkpointAggregation.getOrPut(timing.name) { mutableListOf() }.add(timing.totalDurationMs)
            }
        }

        val avgCheckpointTimings = checkpointAggregation.map { (name, durations) ->
            AverageCheckpointTiming(
                name = name,
                averageDurationMs = durations.sum() / totalTransactions,
                transactionCount = durations.size
            )
        }

        val totalCheckpointTime = avgCheckpointTimings.sumOf { it.averageDurationMs }
        val otherTime = maxOf(0L, avgTotalDuration - totalCheckpointTime)

        AverageTransactionBreakdown(
            totalTransactions = totalTransactions,
            averageTotalDurationMs = avgTotalDuration,
            averageCheckpointTimings = avgCheckpointTimings,
            averageOtherDurationMs = otherTime
        )
    }

    /**
     * Clears all benchmark data from the database by calling the individual clear methods from the DAO.
     */
    suspend fun clearAllBenchmarkData() = onIO {
        dao.clearTransactionStartEvents()
        dao.clearTransactionErrorEvents()
        dao.clearTransactionCancelEvents()
        dao.clearTransactionDoneEvents()
        dao.clearTransactionCheckpointStartEvents()
        dao.clearTransactionCheckpointEndEvents()
        dao.clearTransferStartEvents()
        dao.clearTransferDoneEvents()
        dao.clearTransferErrorEvents()
        dao.clearTransferCancelledEvents()
    }
}
