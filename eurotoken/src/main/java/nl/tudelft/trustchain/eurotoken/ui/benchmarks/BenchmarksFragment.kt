package nl.tudelft.trustchain.eurotoken.ui.benchmarks

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageAnalyticsDatabase
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageBenchmarkCalculator
import nl.tudelft.trustchain.eurotoken.databinding.FragmentBenchmarksBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment

class BenchmarksFragment : EurotokenBaseFragment(R.layout.fragment_benchmarks) {
    private val binding by viewBinding(FragmentBenchmarksBinding::bind)

    private lateinit var benchmarkCalculator: UsageBenchmarkCalculator
    private lateinit var legendAdapter: LegendAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize the benchmark calculator
        val database = UsageAnalyticsDatabase.getInstance(requireContext())
        benchmarkCalculator = UsageBenchmarkCalculator(database.usageEventsDao())

        // Setup legend RecyclerView
        legendAdapter = LegendAdapter()
        binding.recyclerViewLegend.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = legendAdapter
        }

        // Setup clear button
        binding.btnClearBenchmarks.setOnClickListener {
            showClearConfirmationDialog()
        }

        // Load and display benchmark data
        loadBenchmarkData()
    }

    private fun loadBenchmarkData() {
        lifecycleScope.launch {
            try {
                // Load transaction breakdown data
                val breakdown = benchmarkCalculator.calculateAverageTransactionBreakdown()
                if (breakdown != null && breakdown.averageTotalDurationMs > 0) {
                    binding.txtTransactionSummary.text =
                        "Based on ${breakdown.totalTransactions} transactions (avg: ${breakdown.averageTotalDurationMs}ms)"

                    // Prepare pie chart data (excluding "Other" and normalizing to 100%)
                    val pieData = mutableListOf<Pair<String, Double>>()
                    breakdown.checkpointPercentages.forEach { (name, percentage) ->
                        pieData.add(name to percentage)
                    }
                    if (breakdown.otherPercentage > 0) {
                        pieData.add("Other" to breakdown.otherPercentage)
                    }
                    binding.pieChartTransactionBreakdown.setData(pieData)

                    // Prepare legend data
                    val colors = listOf(
                        Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4"), Color.parseColor("#45B7D1"),
                        Color.parseColor("#96CEB4"), Color.parseColor("#FECA57"), Color.parseColor("#FF9FF3"),
                        Color.parseColor("#54A0FF"), Color.parseColor("#5F27CD"), Color.parseColor("#00D2D3"),
                        Color.parseColor("#FF9F43"), Color.DKGRAY
                    )

                    val legendItems = mutableListOf<LegendItem>()
                    breakdown.averageCheckpointTimings.forEachIndexed { index, timing ->
                        val percentage = breakdown.checkpointPercentages.find { it.first == timing.name }?.second ?: 0.0
                        legendItems.add(
                            LegendItem(
                                color = colors[index % colors.size],
                                label = timing.name,
                                percentage = percentage,
                                duration = "${timing.averageDurationMs}ms"
                            )
                        )
                    }
                    if (breakdown.averageOtherDurationMs > 0) {
                        legendItems.add(
                            LegendItem(
                                color = colors.last(),
                                label = "Other",
                                percentage = breakdown.otherPercentage,
                                duration = "${breakdown.averageOtherDurationMs}ms"
                            )
                        )
                    }
                    legendAdapter.setItems(legendItems)
                    binding.txtOtherTime.visibility = View.GONE
                } else {
                    binding.txtTransactionSummary.text = "No transaction data available"
                    binding.pieChartTransactionBreakdown.setData(emptyList())
                    legendAdapter.setItems(emptyList())
                    binding.txtOtherTime.visibility = View.GONE
                }

                // Load transfer throughput data
                val transferThroughput = benchmarkCalculator.calculateMaxAndAvgTransferThroughput()
                if (transferThroughput != null) {
                    binding.txtMaxTransferThroughput.text = String.format("%.2f kbps", transferThroughput.max)
                    binding.txtAverageTransferThroughput.text = String.format("%.2f kbps", transferThroughput.average)
                } else {
                    binding.txtMaxTransferThroughput.text = "No data"
                    binding.txtAverageTransferThroughput.text = "No data"
                }

                // Load total counts
                val totalTransfers = benchmarkCalculator.calculateTotalTransferCount()
                binding.txtTotalTransfers.text = totalTransfers.toString()

                // Load error rates
                val transferErrorRate = benchmarkCalculator.calculateAvgTransferFailureRate()
                binding.txtTransferErrorRate.text = String.format("%.1f%%", transferErrorRate)
            } catch (e: Exception) {
                // Handle errors gracefully
                binding.txtTransactionSummary.text = "Error loading transaction data: ${e.message}"
                binding.pieChartTransactionBreakdown.setData(emptyList())
                legendAdapter.setItems(emptyList())
                binding.txtOtherTime.visibility = View.GONE
                binding.txtMaxTransferThroughput.text = "Error"
                binding.txtAverageTransferThroughput.text = "Error"
                binding.txtTotalTransfers.text = "Error"
                binding.txtTransferErrorRate.text = "Error"
            }
        }
    }

    private fun showClearConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Benchmark Data")
            .setMessage("This will permanently delete all transaction and transfer benchmark data. This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearBenchmarkData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearBenchmarkData() {
        lifecycleScope.launch {
            try {
                benchmarkCalculator.clearAllBenchmarkData()

                // Refresh the UI to show empty state
                loadBenchmarkData()

                // Show success message
                AlertDialog.Builder(requireContext())
                    .setTitle("Data Cleared")
                    .setMessage("All benchmark data has been successfully cleared.")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                // Show error message
                AlertDialog.Builder(requireContext())
                    .setTitle("Error")
                    .setMessage("Failed to clear benchmark data: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
