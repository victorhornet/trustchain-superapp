package nl.tudelft.trustchain.common.eurotoken

// The values for shared preferences used by this activity.
// initially in eurotokenmainactivity, but moved to common to avoid circular dependency
object EurotokenPreferences {
    const val EUROTOKEN_SHARED_PREF_NAME = "eurotoken"
    const val DEMO_MODE_ENABLED = "demo_mode_enabled"
    const val NFC_MIMETYPE = "vnd.eurotoken"
}
