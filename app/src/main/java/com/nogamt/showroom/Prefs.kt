package com.nogamt.showroom

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper over SharedPreferences. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var startUrl: String
        get() = sp.getString(KEY_START_URL, null)?.takeIf { it.isNotBlank() }
            ?: Constants.DEFAULT_START_URL
        set(value) = sp.edit().putString(KEY_START_URL, value).apply()

    /** Persisted SAF tree URI of the staff-selected media folder. */
    var mediaTreeUri: String?
        get() = sp.getString(KEY_MEDIA_TREE, null)
        set(value) = sp.edit().putString(KEY_MEDIA_TREE, value).apply()

    var mediaTreeLabel: String
        get() = sp.getString(KEY_MEDIA_LABEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_MEDIA_LABEL, value).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = sp.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var autoRecovery: Boolean
        get() = sp.getBoolean(KEY_RECOVERY, true)
        set(value) = sp.edit().putBoolean(KEY_RECOVERY, value).apply()

    /** Cached media index so a restart does not have to rescan a USB drive. */
    var mediaIndexJson: String?
        get() = sp.getString(KEY_INDEX, null)
        set(value) = sp.edit().putString(KEY_INDEX, value).apply()

    var lastScanAt: Long
        get() = sp.getLong(KEY_LAST_SCAN, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SCAN, value).apply()

    fun resetStartUrl() = sp.edit().remove(KEY_START_URL).apply()

    companion object {
        private const val FILE = "noga_mt_showroom"
        private const val KEY_START_URL = "start_url"
        private const val KEY_MEDIA_TREE = "media_tree_uri"
        private const val KEY_MEDIA_LABEL = "media_tree_label"
        private const val KEY_KEEP_AWAKE = "keep_screen_on"
        private const val KEY_AUTOSTART = "auto_start_on_boot"
        private const val KEY_RECOVERY = "auto_recovery"
        private const val KEY_INDEX = "media_index_json"
        private const val KEY_LAST_SCAN = "media_last_scan"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
