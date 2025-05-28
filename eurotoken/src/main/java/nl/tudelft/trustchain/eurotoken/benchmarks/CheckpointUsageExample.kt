package nl.tudelft.trustchain.eurotoken.benchmarks

/**
 * Example showing how to use the transaction checkpoint logging system
 */
class CheckpointUsageExample {

    /**
     * Example 1: Using manual checkpoint logging
     */
    fun exampleManualCheckpoints() {
        // Transaction already started with UsageLogger.logTransactionStart()

        // Start UI setup phase
        UsageLogger.logTransactionCheckpointStart("UI setup")
        // ... do UI work ...
        UsageLogger.logTransactionCheckpointEnd("UI setup")

        // Start NFC connection phase
        UsageLogger.logTransactionCheckpointStart("creating connection")
        // ... establish NFC connection ...
        UsageLogger.logTransactionCheckpointEnd("creating connection")

        // Start data transfer phase
        UsageLogger.logTransactionCheckpointStart("sending data")
        // ... send transaction data ...
        UsageLogger.logTransactionCheckpointEnd("sending data")

        // Another connection phase (same name, will be aggregated)
        UsageLogger.logTransactionCheckpointStart("creating connection")
        // ... another connection step ...
        UsageLogger.logTransactionCheckpointEnd("creating connection")

        // Transaction ends with UsageLogger.logTransactionDone()
    }

    /**
     * Example 2: Using the convenient trackCheckpoint function
     */
    fun exampleConvenientCheckpoints() {
        // Transaction already started

        trackCheckpoint("UI setup") {
            // UI work happens here
            setupUserInterface()
        }

        trackCheckpoint("creating connection") {
            // Connection work happens here
            establishNfcConnection()
        }

        trackCheckpoint("sending data") {
            // Data transfer happens here
            sendTransactionData()
        }

        // Transaction ends
    }

    /**
     * Example 3: Using try-with-resources pattern
     */
    fun exampleAutoCloseableCheckpoints() {
        // Transaction already started

        TransactionCheckpoint("validation").use {
            // Validation work happens here
            validateTransactionData()
        }

        TransactionCheckpoint("blockchain update").use {
            // Blockchain operations happen here
            updateBlockchain()
        }

        // Transaction ends
    }

    private fun setupUserInterface() {
        // Mock UI setup work
        Thread.sleep(100)
    }

    private fun establishNfcConnection() {
        // Mock NFC connection work
        Thread.sleep(200)
    }

    private fun sendTransactionData() {
        // Mock data sending work
        Thread.sleep(150)
    }

    private fun validateTransactionData() {
        // Mock validation work
        Thread.sleep(50)
    }

    private fun updateBlockchain() {
        // Mock blockchain work
        Thread.sleep(300)
    }
}

/**
 * Example of how to view transaction breakdown data
 */
suspend fun exampleViewBreakdown(calculator: UsageBenchmarkCalculator) {
    // Get breakdown for a specific transaction
    val breakdown = calculator.calculateTransactionBreakdown("some-transaction-id")
    breakdown?.let {
        println("Transaction ${it.transactionId} took ${it.totalDurationMs}ms total:")
        
        it.checkpointTimings.forEach { timing ->
            val percentage = timing.totalDurationMs.toDouble() / it.totalDurationMs * 100
            println("  ${timing.name}: ${timing.totalDurationMs}ms (${percentage.format(1)}%) - ${timing.occurrences} occurrences")
        }
        
        println("  Other: ${it.otherDurationMs}ms (${it.otherPercentage.format(1)}%)")
    }

    // Get breakdown for all transactions
    val allBreakdowns = calculator.calculateAllTransactionBreakdowns()
    println("Analyzed ${allBreakdowns.size} completed transactions")
}

private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
