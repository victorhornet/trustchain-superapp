package nl.tudelft.trustchain.eurotoken

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageAnalyticsDatabase
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageBenchmarkCalculator
import nl.tudelft.trustchain.common.eurotoken.benchmarks.UsageLogger
import nl.tudelft.trustchain.common.eurotoken.worker.SyncWorker
class EuroTokenMainActivity : BaseActivity() {
    override val navigationGraph = R.navigation.nav_graph_eurotoken
    override val bottomNavigationMenu = R.menu.eurotoken_navigation_menu

    private lateinit var usageAnalyticsDatabase: UsageAnalyticsDatabase
    private lateinit var benchmarkCalculator: UsageBenchmarkCalculator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UsageLogger.initialize(applicationContext)
        usageAnalyticsDatabase = UsageAnalyticsDatabase.getInstance(applicationContext)
        benchmarkCalculator = UsageBenchmarkCalculator(usageAnalyticsDatabase.usageEventsDao())

        scheduleTransactionSync()
    }

    private fun scheduleTransactionSync() {
        lifecycleScope.launch {
            val offlineBlockSyncDao = usageAnalyticsDatabase.offlineBlockSyncDao()
            val unsyncedHashes = offlineBlockSyncDao.getUnsyncedBlockHashes()

            if (unsyncedHashes.isNotEmpty()) {
                Log.d("EuroTokenMainActivity", "Found ${unsyncedHashes.size} unsynced blocks, scheduling periodic sync")
                SyncWorker.schedulePeriodicSync(this@EuroTokenMainActivity)
            } else {
                Log.d("EuroTokenMainActivity", "No unsynced blocks found, periodic sync not needed")
            }
        }
    }

    // /**
    //  * The values for shared preferences used by this activity.
    //  */
    // object EurotokenPreferences {
    //     const val EUROTOKEN_SHARED_PREF_NAME = "eurotoken"
    //     const val DEMO_MODE_ENABLED = "demo_mode_enabled"
    //     const val NFC_MIMETYPE = "vnd.eurotoken"
    // }
}
