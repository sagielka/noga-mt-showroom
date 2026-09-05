package com.nogamt.showroom

import android.content.Context
import android.content.SharedPreferences

/** Storage destination for the local video library. */
enum class StorageMode { NONE, INTERNAL, SAF }

/** Small typed wrapper over SharedPreferences. */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var startUrl: String
        get() = sp.getString(KEY_START_URL, null)?.takeIf { it.isNotBlank() }
            ?: Constants.DEFAULT_START_URL
        set(value) = sp.edit().putString(KEY_START_URL, value).apply()

    /**
     * Manifest endpoint. Defaults to the live NOGA MT endpoint, so a fresh install syncs
     * without anyone entering a URL. A device still holding the old pre-release placeholder
     * is migrated automatically rather than left pointing at a 404.
     */
    var manifestUrl: String
        get() {
            val stored = sp.getString(KEY_MANIFEST_URL, null)?.trim()
            if (stored.isNullOrBlank() || stored == Constants.LEGACY_MANIFEST_URL) {
                if (stored != null) sp.edit().remove(KEY_MANIFEST_URL).apply()
                return Constants.DEFAULT_MANIFEST_URL
            }
            return stored
        }
        set(value) = sp.edit().putString(KEY_MANIFEST_URL, value).apply()

    /** Which storage backend the staff picked. */
    var storageMode: StorageMode
        get() = runCatching {
            StorageMode.valueOf(sp.getString(KEY_STORAGE_MODE, StorageMode.NONE.name)!!)
        }.getOrDefault(StorageMode.NONE)
        set(value) = sp.edit().putString(KEY_STORAGE_MODE, value.name).apply()

    /** Persisted SAF tree URI of the staff-selected media folder. */
    var mediaTreeUri: String?
        get() = sp.getString(KEY_MEDIA_TREE, null)
        set(value) = sp.edit().putString(KEY_MEDIA_TREE, value).apply()

    var mediaTreeLabel: String
        get() = sp.getString(KEY_MEDIA_LABEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_MEDIA_LABEL, value).apply()

    var firstRunCompleted: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN, false)
        set(value) = sp.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = sp.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var autoRecovery: Boolean
        get() = sp.getBoolean(KEY_RECOVERY, true)
        set(value) = sp.edit().putBoolean(KEY_RECOVERY, value).apply()

    // ---------------- sync settings ----------------

    var autoSync: Boolean
        get() = sp.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = sp.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var verifyDownloads: Boolean
        get() = sp.getBoolean(KEY_VERIFY, true)
        set(value) = sp.edit().putBoolean(KEY_VERIFY, value).apply()

    /** Last successfully synchronised remote libraryVersion. */
    var localLibraryVersion: Long
        get() = sp.getLong(KEY_LIB_VERSION, -1L)
        set(value) = sp.edit().putLong(KEY_LIB_VERSION, value).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Cached copy of the last good manifest, so a cold start knows the library offline. */
    var manifestJson: String?
        get() = sp.getString(KEY_MANIFEST_JSON, null)
        set(value) = sp.edit().putString(KEY_MANIFEST_JSON, value).apply()

    /** Cached media index so a restart does not have to rescan a USB drive. */
    var mediaIndexJson: String?
        get() = sp.getString(KEY_INDEX, null)
        set(value) = sp.edit().putString(KEY_INDEX, value).apply()

    var lastScanAt: Long
        get() = sp.getLong(KEY_LAST_SCAN, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SCAN, value).apply()

    /** Persisted download failure records (JSON array), survives restarts. */
    var failuresJson: String?
        get() = sp.getString(KEY_FAILURES, null)
        set(value) = sp.edit().putString(KEY_FAILURES, value).apply()

    fun resetStartUrl() = sp.edit().remove(KEY_START_URL).apply()
    fun resetManifestUrl() = sp.edit().remove(KEY_MANIFEST_URL).apply()

    fun clearStorageSelection() {
        sp.edit()
            .remove(KEY_MEDIA_TREE)
            .remove(KEY_MEDIA_LABEL)
            .putString(KEY_STORAGE_MODE, StorageMode.NONE.name)
            .apply()
    }

    companion object {
        private const val FILE = "noga_mt_showroom"
        private const val KEY_START_URL = "start_url"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_STORAGE_MODE = "storage_mode"
        private const val KEY_MEDIA_TREE = "media_tree_uri"
        private const val KEY_MEDIA_LABEL = "media_tree_label"
        private const val KEY_FIRST_RUN = "first_run_completed"
        private const val KEY_KEEP_AWAKE = "keep_screen_on"
        private const val KEY_AUTOSTART = "auto_start_on_boot"
        private const val KEY_RECOVERY = "auto_recovery"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_VERIFY = "verify_downloads"
        private const val KEY_LIB_VERSION = "local_library_version"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_MANIFEST_JSON = "manifest_json"
        private const val KEY_INDEX = "media_index_json"
        private const val KEY_LAST_SCAN = "media_last_scan"
        private const val KEY_FAILURES = "download_failures"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
