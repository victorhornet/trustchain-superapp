package nl.tudelft.trustchain.eurotoken.ui.benchmarks

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageAnalyticsDatabase
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageBenchmarkCalculator
import nl.tudelft.trustchain.eurotoken.databinding.FragmentBenchmarksBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment

class BenchmarksFragment : EurotokenBaseFragment(R.layout.fragment_benchmarks) {
    private val binding by viewBinding(FragmentBenchmarksBinding::bind)

    private lateinit var benchmarkCalculator: UsageBenchmarkCalculator

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the benchmark calculator
        val database = UsageAnalyticsDatabase.getInstance(requireContext())
        benchmarkCalculator = UsageBenchmarkCalculator(database.usageEventsDao())

        // Load and display benchmark data
        loadBenchmarkData()
    }

    private fun loadBenchmarkData() {
        lifecycleScope.launch {
            try {
                // Load transfer throughput data
                val transferThroughput = benchmarkCalculator.calculateMaxAndAvgTransferThroughput()
                if (transferThroughput != null) {
                    // Convert from bytes/ms to kbps: bytes/ms * 8 = kbps
                    val maxKbps = transferThroughput.max * 8
                    val avgKbps = transferThroughput.average * 8
                    binding.txtMaxTransferThroughput.text = String.format("%.2f kbps", maxKbps)
                    binding.txtAverageTransferThroughput.text = String.format("%.2f kbps", avgKbps)
                } else {
                    binding.txtMaxTransferThroughput.text = "No data"
                    binding.txtAverageTransferThroughput.text = "No data"
                }

                // Load transaction completion time data
                val transactionTime = benchmarkCalculator.calculateMaxAndAvgTransactionTime()
                if (transactionTime != null) {
                    // Convert from milliseconds to seconds
                    val maxSeconds = transactionTime.max / 1000.0
                    val avgSeconds = transactionTime.average / 1000.0
                    binding.txtMaxTransactionThroughput.text = String.format("%.4f s", maxSeconds)
                    binding.txtAverageTransactionThroughput.text = String.format("%.4f s", avgSeconds)
                } else {
                    binding.txtMaxTransactionThroughput.text = "No data"
                    binding.txtAverageTransactionThroughput.text = "No data"
                }

                // Load total counts
                val totalTransfers = benchmarkCalculator.calculateTotalTransferCount()
                val totalTransactions = benchmarkCalculator.calculateTotalTransactionCount()
                binding.txtTotalTransfers.text = totalTransfers.toString()
                binding.txtTotalTransactions.text = totalTransactions.toString()

                // Load error rates
                val transferErrorRate = benchmarkCalculator.calculateAvgTransferFailureRate()
                val transactionErrorRate = benchmarkCalculator.calculateAvgTransactionErrorRate()
                binding.txtTransferErrorRate.text = String.format("%.1f%%", transferErrorRate)
                binding.txtTransactionErrorRate.text = String.format("%.1f%%", transactionErrorRate)

            } catch (e: Exception) {
                // Handle errors gracefully
                binding.txtMaxTransferThroughput.text = "Error loading data"
                binding.txtAverageTransferThroughput.text = "Error loading data"
                binding.txtMaxTransactionThroughput.text = "Error loading data"
                binding.txtAverageTransactionThroughput.text = "Error loading data"
                binding.txtTotalTransfers.text = "Error"
                binding.txtTotalTransactions.text = "Error"
                binding.txtTransferErrorRate.text = "Error"
                binding.txtTransactionErrorRate.text = "Error"
            }
        }
    }
}
