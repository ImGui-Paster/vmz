package top.niunaijun.blackboxa.app

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

/**
 * Owns the light/dark theme mode.
 *
 * The persisted value is the raw [AppCompatDelegate] night-mode constant stored
 * as a string, because [androidx.preference.ListPreference] can only store
 * strings and this avoids a second mapping table (see res/values/arrays.xml).
 *
 * Why a dedicated preference file: the theme has to be applied in
 * [App.onCreate], i.e. long before any Activity or PreferenceFragment exists.
 * Reading it through the SharedPreferences instance that
 * PreferenceFragmentCompat uses by default ("<pkg>_preferences") keeps the
 * ListPreference and this reader in sync automatically.
 */
object ThemeManager {

    private const val TAG = "ThemeManager"

    const val KEY_THEME_MODE = "theme_mode"

    /** Matches app:defaultValue in res/xml/setting.xml. */
    private const val DEFAULT_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    /**
     * Default SharedPreferences name used by PreferenceManager, so the value
     * written by the ListPreference is the one we read here.
     */
    private fun prefsName(context: Context) = "${context.packageName}_preferences"

    /**
     * Applies the stored theme mode process-wide.
     *
     * MUST be called from the host UI process only. Virtual (sandboxed) app
     * processes share this Application class, and calling into AppCompat there
     * would touch the guest app's resources - so callers gate on
     * BlackBoxCore.isMainProcess().
     */
    fun apply(context: Context) {
        setMode(readMode(context))
    }

    fun readMode(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(prefsName(context), Context.MODE_PRIVATE)
            // Stored as String by ListPreference; tolerate a legacy Int value.
            val raw = try {
                prefs.getString(KEY_THEME_MODE, null)
            } catch (e: ClassCastException) {
                prefs.getInt(KEY_THEME_MODE, DEFAULT_MODE).toString()
            }
            raw?.toIntOrNull()?.takeIf { it.isValidNightMode() } ?: DEFAULT_MODE
        } catch (e: Exception) {
            Log.e(TAG, "Error reading theme mode: ${e.message}")
            DEFAULT_MODE
        }
    }

    /**
     * Applies [mode] immediately. AppCompat recreates every started Activity,
     * so the change is visible without asking the user to restart the app.
     */
    fun setMode(mode: Int) {
        try {
            val safe = if (mode.isValidNightMode()) mode else DEFAULT_MODE
            if (AppCompatDelegate.getDefaultNightMode() != safe) {
                AppCompatDelegate.setDefaultNightMode(safe)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying theme mode: ${e.message}")
        }
    }

    private fun Int.isValidNightMode(): Boolean = this == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
            this == AppCompatDelegate.MODE_NIGHT_NO ||
            this == AppCompatDelegate.MODE_NIGHT_YES ||
            this == AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
}
