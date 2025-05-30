package nl.tudelft.trustchain.eurotoken.benchmarks

import android.util.Log

/**
 * Utility class for convenient checkpoint tracking with try-with-resources pattern
 */
class TransactionCheckpoint(private val checkpointName: String) : AutoCloseable {
    
    init {
        try {
            UsageLogger.logTransactionCheckpointStart(checkpointName)
        } catch (e: Exception) {
            Log.w("TransactionCheckpoint", "Failed to log checkpoint start for '$checkpointName'", e)
        }
    }
    
    override fun close() {
        try {
            UsageLogger.logTransactionCheckpointEnd(checkpointName)
        } catch (e: Exception) {
            Log.w("TransactionCheckpoint", "Failed to log checkpoint end for '$checkpointName'", e)
        }
    }
}

/**
 * Inline function for convenient checkpoint usage:
 * 
 * trackCheckpoint("creating connection") {
 *     // do connection work
 * }
 */
inline fun <T> trackCheckpoint(checkpointName: String, block: () -> T): T {
    return TransactionCheckpoint(checkpointName).use {
        block()
    }
}
