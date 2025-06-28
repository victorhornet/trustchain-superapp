package nl.tudelft.trustchain.common.eurotoken.benchmarks

data class CheckpointTiming(
    val name: String,
    val totalDurationMs: Long,
    val occurrences: Int
)

data class AverageCheckpointTiming(
    val name: String,
    val averageDurationMs: Long,
    val transactionCount: Int
)

data class AverageTransactionBreakdown(
    val totalTransactions: Int,
    val averageTotalDurationMs: Long,
    val averageCheckpointTimings: List<AverageCheckpointTiming>,
    val averageOtherDurationMs: Long
) {
    val checkpointPercentages: List<Pair<String, Double>>
        get() = averageCheckpointTimings.map {
            it.name to (it.averageDurationMs.toDouble() / averageTotalDurationMs * 100)
        }

    val otherPercentage: Double
        get() = averageOtherDurationMs.toDouble() / averageTotalDurationMs * 100
}

data class TransactionBreakdown(
    val transactionId: String,
    val totalDurationMs: Long,
    val checkpointTimings: List<CheckpointTiming>,
    val otherDurationMs: Long
) {
    val checkpointPercentages: List<Pair<String, Double>>
        get() = checkpointTimings.map {
            it.name to (it.totalDurationMs.toDouble() / totalDurationMs * 100)
        }

    val otherPercentage: Double
        get() = otherDurationMs.toDouble() / totalDurationMs * 100
}

/**
 * Aggregates checkpoint timings by name, summing durations for repeated checkpoint names
 */
fun aggregateCheckpointTimings(
    startEvents: List<TransactionCheckpointStartEvent>,
    endEvents: List<TransactionCheckpointEndEvent>
): List<CheckpointTiming> {
    val timingMap = mutableMapOf<String, MutableList<Long>>()

    // Match start and end events by checkpoint name and calculate durations
    startEvents.forEach { start ->
        val matchingEnd = endEvents.find { end ->
            end.checkpointName == start.checkpointName &&
                end.timestamp >= start.timestamp
        }
        if (matchingEnd != null) {
            val duration = matchingEnd.timestamp - start.timestamp
            timingMap.getOrPut(start.checkpointName) { mutableListOf() }.add(duration)
        }
    }

    // Aggregate by summing all durations for each checkpoint name
    return timingMap.map { (name, durations) ->
        CheckpointTiming(
            name = name,
            totalDurationMs = durations.sum(),
            occurrences = durations.size
        )
    }
}
