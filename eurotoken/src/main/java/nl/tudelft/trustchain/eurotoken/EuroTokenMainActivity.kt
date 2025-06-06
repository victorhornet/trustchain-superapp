package nl.tudelft.trustchain.eurotoken

import android.os.Bundle
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageAnalyticsDatabase
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageBenchmarkCalculator
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageEventsDao
import nl.tudelft.trustchain.eurotoken.benchmarks.UsageLogger

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
    }

    /**
     * The values for shared preferences used by this activity.
     */
    object EurotokenPreferences {
        const val EUROTOKEN_SHARED_PREF_NAME = "eurotoken"
        const val DEMO_MODE_ENABLED = "demo_mode_enabled"
        const val NFC_MIMETYPE = "vnd.eurotoken"
    }
}
