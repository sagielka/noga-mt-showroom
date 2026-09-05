package com.nogamt.showroom.staff

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.WebViewCompat
import com.nogamt.showroom.BuildConfig
import com.nogamt.showroom.Constants
import com.nogamt.showroom.MainActivity
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.R
import com.nogamt.showroom.StorageMode
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner
import com.nogamt.showroom.media.MediaState
import com.nogamt.showroom.media.SyncEngine
import com.nogamt.showroom.media.SyncProgress
import com.nogamt.showroom.net.NetworkMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StaffSettingsActivity : AppCompatActivity(), SyncEngine.Listener {

    private lateinit var prefs: Prefs
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        networkMonitor = NetworkMonitor(this)
        setContentView(R.layout.activity_staff_settings)
        wireControls()
        render()
    }

    private fun wireControls() {
        findViewById<SwitchCompat>(R.id.switch_keep_awake).apply {
            isChecked = prefs.keepScreenOn
            setOnCheckedChangeListener { _, checked -> prefs.keepScreenOn = checked }
        }
        findViewById<SwitchCompat>(R.id.switch_autostart).apply {
            isChecked = prefs.autoStartOnBoot
            setOnCheckedChangeListener { _, checked ->
                prefs.autoStartOnBoot = checked
                if (checked) showAutoStartCaveat()
            }
        }
        findViewById<SwitchCompat>(R.id.switch_recovery).apply {
            isChecked = prefs.autoRecovery
            setOnCheckedChangeListener { _, checked -> prefs.autoRecovery = checked }
        }
        findViewById<SwitchCompat>(R.id.switch_auto_sync).apply {
            isChecked = prefs.autoSync
            setOnCheckedChangeListener { _, checked ->
                prefs.autoSync = checked
                render()
            }
        }
        findViewById<SwitchCompat>(R.id.switch_wifi_only).apply {
            isChecked = prefs.wifiOnly
            setOnCheckedChangeListener { _, checked -> prefs.wifiOnly = checked }
        }
        findViewById<SwitchCompat>(R.id.switch_verify).apply {
            isChecked = prefs.verifyDownloads
            setOnCheckedChangeListener { _, checked -> prefs.verifyDownloads = checked }
        }

        findViewById<Button>(R.id.btn_sync_now).setOnClickListener {
            toast("Checking the media library…")
            MediaRepository.syncNow(this, force = true) { render() }
        }
        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            finishWithAction(MainActivity.ACTION_RELOAD)
        }
        findViewById<Button>(R.id.btn_force_refresh).setOnClickListener {
            finishWithAction(MainActivity.ACTION_FORCE_REFRESH)
        }
        findViewById<Button>(R.id.btn_change_storage).setOnClickListener {
            startActivity(Intent(this, MediaManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_rescan).setOnClickListener {
            if (!MediaRepository.hasStorage(this)) {
                startActivity(Intent(this, FirstRunSetupActivity::class.java))
            } else {
                toast(getString(R.string.scanning))
                MediaRepository.rescan(this) {
                    render()
                    toast("Indexed ${MediaIndex.matchedCount} videos")
                }
            }
        }
        findViewById<Button>(R.id.btn_media_manager).setOnClickListener {
            startActivity(Intent(this, MediaManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_clear_cache).setOnClickListener { confirmClearCache() }
        findViewById<Button>(R.id.btn_change_url).setOnClickListener { promptStartUrl() }
        findViewById<Button>(R.id.btn_manifest_url).setOnClickListener { promptManifestUrl() }
        findViewById<Button>(R.id.btn_network_settings).setOnClickListener { openNetworkSettings() }
        findViewById<Button>(R.id.btn_return).setOnClickListener { finishWithAction(null) }

        findViewById<Button>(R.id.btn_sync_now).requestFocus()
    }

    private fun showAutoStartCaveat() {
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle("Auto start enabled")
            .setMessage(
                "The app will try to launch itself after the TV boots.\n\n" +
                    "Some manufacturers (Google TV, parts of the Sony/Philips/Amazon range) " +
                    "block apps from opening themselves at boot. If it does not start on this " +
                    "TV, provision the device in kiosk mode - see docs/KIOSK.md."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val res = MediaIndex.resolution()

        findViewById<TextView>(R.id.value_health).text = buildString {
            appendLine(row("BRIDGE", if (MainActivity.bridgeReady) "READY" else "NOT READY"))
            appendLine(row("STORAGE", storageHealth()))
            appendLine(row("SYNC", MediaIndex.syncProgress.describe()))
            appendLine(
                row(
                    "LOCAL VIDEOS",
                    "${res.count(MediaState.LOCAL_READY)} / " +
                        if (MediaIndex.remoteCount > 0) "${MediaIndex.remoteCount}"
                        else "${MediaIndex.matchedCount}"
                )
            )
            append(row("HEALTH", overallHealth()))
        }

        findViewById<TextView>(R.id.value_start_url).text = prefs.startUrl
        findViewById<TextView>(R.id.value_network).text = networkMonitor.describe()
        findViewById<TextView>(R.id.value_webview).text = webViewVersion()
        findViewById<TextView>(R.id.value_app).text =
            "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} " +
                "(build ${BuildConfig.VERSION_CODE})"
        findViewById<TextView>(R.id.value_device).text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})"

        findViewById<TextView>(R.id.value_media_folder).text =
            MediaRepository.storageLabel(this)
        findViewById<TextView>(R.id.value_media_count).text = buildString {
            append("${MediaIndex.matchedCount} indexed")
            append(" · ${MediaScanner.formatBytes(MediaIndex.totalBytes)} used")
            MediaIndex.freeBytes?.let { append(" · ${MediaScanner.formatBytes(it)} free") }
            if (prefs.storageMode == StorageMode.SAF) {
                append("\nSAF permission: ${if (MediaIndex.permissionValid) "VALID" else "INVALID"}")
            }
            if (!MediaIndex.sourceAvailable && prefs.storageMode != StorageMode.NONE) {
                append(" · SOURCE UNAVAILABLE")
            }
            if (MediaIndex.lastScanAt > 0L) append("\nLast scan ${timeOf(MediaIndex.lastScanAt)}")
        }

        findViewById<TextView>(R.id.value_manifest_url).text = prefs.manifestUrl
        findViewById<TextView>(R.id.value_sync_state).text = buildString {
            append("Library version: ")
            append(MediaIndex.manifest?.libraryVersion?.toString() ?: "unknown")
            append(" · remote entries ${MediaIndex.remoteCount}")
            append("\nMissing ${res.count(MediaState.MISSING)}")
            append(" · updates ${res.count(MediaState.UPDATE_AVAILABLE)}")
            append(" · failed ${res.count(MediaState.FAILED)}")
            append("\nLast sync ${timeOf(MediaIndex.lastSyncAt)}")
            if (!prefs.autoSync) append("\nAuto-sync is OFF - use SYNC NOW")
        }

        findViewById<TextView>(R.id.value_notice).text =
            "Lovable owns the playlist, ordering, presentation and timing. This shell provides " +
                "kiosk behaviour, the remote, local media and recovery.\n\n" +
                "Publishing in Lovable updates the TV on the next reload, and new videos arrive " +
                "through the media manifest - neither needs a new APK."
    }

    private fun storageHealth(): String = when {
        prefs.storageMode == StorageMode.NONE -> "NOT SET UP"
        !MediaIndex.sourceAvailable -> "MISSING"
        else -> "READY"
    }

    private fun overallHealth(): String {
        val res = MediaIndex.resolution()
        val error = !MainActivity.bridgeReady ||
            res.count(MediaState.FAILED) > 0 ||
            (prefs.storageMode != StorageMode.NONE && !MediaIndex.sourceAvailable)
        val attention = res.count(MediaState.MISSING) > 0 ||
            res.count(MediaState.UPDATE_AVAILABLE) > 0 ||
            MediaRepository.lowStorage() ||
            prefs.storageMode == StorageMode.NONE
        return when {
            error -> "ERROR"
            attention -> "ATTENTION"
            else -> "GOOD"
        }
    }

    private fun row(label: String, value: String): String = label.padEnd(16) + value

    private fun timeOf(millis: Long): String =
        if (millis <= 0L) "never"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    private fun webViewVersion(): String = runCatching {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        if (pkg == null) "Unknown WebView provider" else "${pkg.packageName} ${pkg.versionName}"
    }.getOrElse { "Unavailable" }

    // ---------------------------------------------------------------- dialogs

    private fun promptStartUrl() {
        promptUrl(
            title = getString(R.string.action_change_url),
            current = prefs.startUrl,
            restrictToAllowedHosts = true,
            onReset = { prefs.resetStartUrl() },
            onAccepted = { prefs.startUrl = it }
        )
    }

    private fun promptManifestUrl() {
        promptUrl(
            title = getString(R.string.action_manifest_url),
            current = prefs.manifestUrl,
            restrictToAllowedHosts = true,
            onReset = { prefs.resetManifestUrl() },
            onAccepted = {
                prefs.manifestUrl = it
                prefs.localLibraryVersion = -1L
                MediaRepository.syncNow(this, force = true) { render() }
            }
        )
    }

    private fun promptUrl(
        title: String,
        current: String,
        restrictToAllowedHosts: Boolean,
        onReset: () -> Unit,
        onAccepted: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(current)
            setTextColor(getColor(R.color.noga_text))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(title)
            .setView(input)
            .setNeutralButton(R.string.action_reset_url) { _, _ ->
                onReset()
                render()
                toast("Reset to default")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                val host = runCatching { Uri.parse(value).host }.getOrNull()
                when {
                    !value.startsWith("https://") -> toast("The URL must use https")
                    !Patterns.WEB_URL.matcher(value).matches() -> toast("That is not a URL")
                    restrictToAllowedHosts && !Constants.isAllowedHost(host) ->
                        toast("Host not allowed: ${Constants.ALLOWED_HOSTS.joinToString()}")
                    else -> {
                        onAccepted(value)
                        render()
                        toast("Updated")
                    }
                }
            }
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.action_clear_cache)
            .setMessage(
                "Clears the WebView HTTP cache and web storage, then reloads the showroom. " +
                    "Local video files are not affected."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                finishWithAction(MainActivity.ACTION_FORCE_REFRESH)
            }
            .show()
    }

    private fun openNetworkSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Throwable) {
                // try the next one
            }
        }
        toast("No settings screen available on this device")
    }

    private fun finishWithAction(action: String?) {
        val data = Intent()
        if (action != null) data.putExtra(MainActivity.EXTRA_STAFF_ACTION, action)
        setResult(RESULT_OK, data)
        finish()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        SyncEngine.addListener(this)
        MediaRepository.verifySourceAvailability(this) { render() }
        render()
    }

    override fun onPause() {
        SyncEngine.removeListener(this)
        super.onPause()
    }

    override fun onSyncProgress(progress: SyncProgress) {
        runOnUiThread { render() }
    }

    // BACK on a staff screen simply returns to the showroom.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishWithAction(null)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
