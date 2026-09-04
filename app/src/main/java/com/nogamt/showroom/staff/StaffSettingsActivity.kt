package com.nogamt.showroom.staff

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.webkit.WebViewCompat
import com.nogamt.showroom.BuildConfig
import com.nogamt.showroom.Constants
import com.nogamt.showroom.MainActivity
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.R
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner
import com.nogamt.showroom.net.NetworkMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StaffSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var networkMonitor: NetworkMonitor

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            if (MediaRepository.adoptFolder(this, uri)) {
                toast("Folder selected · scanning…")
                MediaRepository.rescan(this) { render() }
            } else {
                toast("Could not keep permission for that folder")
            }
            render()
        }

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
                if (checked) {
                    AlertDialog.Builder(this@StaffSettingsActivity, R.style.Theme_NogaMT_Dialog)
                        .setTitle("Auto start enabled")
                        .setMessage(
                            "The app will try to launch itself after the TV boots.\n\n" +
                                "Some manufacturers (Google TV, parts of the Sony/Philips/Amazon " +
                                "range) block apps from opening themselves at boot. If it does " +
                                "not start on this TV, provision the device in kiosk mode - " +
                                "see docs/KIOSK.md."
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
        findViewById<SwitchCompat>(R.id.switch_recovery).apply {
            isChecked = prefs.autoRecovery
            setOnCheckedChangeListener { _, checked -> prefs.autoRecovery = checked }
        }

        findViewById<Button>(R.id.btn_reload).setOnClickListener {
            finishWithAction(MainActivity.ACTION_RELOAD)
        }
        findViewById<Button>(R.id.btn_force_refresh).setOnClickListener {
            finishWithAction(MainActivity.ACTION_FORCE_REFRESH)
        }
        findViewById<Button>(R.id.btn_select_folder).setOnClickListener { launchFolderPicker() }
        findViewById<Button>(R.id.btn_rescan).setOnClickListener {
            if (!MediaRepository.hasFolder(this)) {
                toast("Select a media folder first")
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
        findViewById<Button>(R.id.btn_network_settings).setOnClickListener { openNetworkSettings() }
        findViewById<Button>(R.id.btn_return).setOnClickListener { finishWithAction(null) }

        findViewById<Button>(R.id.btn_reload).requestFocus()
    }

    private fun render() {
        findViewById<TextView>(R.id.value_start_url).text = prefs.startUrl
        findViewById<TextView>(R.id.value_network).text = networkMonitor.describe()
        findViewById<TextView>(R.id.value_webview).text = webViewVersion()
        findViewById<TextView>(R.id.value_app).text =
            "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        findViewById<TextView>(R.id.value_device).text =
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        findViewById<TextView>(R.id.value_media_folder).text =
            MediaScanner.describe(prefs.mediaTreeUri)
        findViewById<TextView>(R.id.value_media_count).text = buildString {
            append("${MediaIndex.matchedCount} local videos indexed")
            append(" · ${MediaScanner.formatBytes(MediaIndex.totalBytes)}")
            if (!MediaIndex.sourceAvailable) append(" · SOURCE UNAVAILABLE")
            if (MediaIndex.lastScanAt > 0L) {
                append(
                    "\nLast scan " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(Date(MediaIndex.lastScanAt))
                )
            }
        }
        findViewById<TextView>(R.id.value_notice).text =
            "The Lovable web application owns the playlist, ordering and UI. " +
                "This shell only provides kiosk behaviour, the remote, local media and recovery.\n\n" +
                "Publish in Lovable and the TV picks the change up on the next reload - " +
                "no new APK needed."
    }

    private fun webViewVersion(): String = runCatching {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        if (pkg == null) "Unknown WebView provider"
        else "${pkg.packageName} ${pkg.versionName}"
    }.getOrElse { "Unavailable" }

    private fun launchFolderPicker() {
        try {
            folderPicker.launch(null)
        } catch (t: Throwable) {
            Log.e(Constants.LOG, "No SAF picker on this device", t)
            toast("This TV has no document picker. Copy videos to internal storage instead.")
        }
    }

    private fun promptStartUrl() {
        val input = EditText(this).apply {
            setText(prefs.startUrl)
            setTextColor(getColor(R.color.noga_text))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.action_change_url)
            .setView(input)
            .setNeutralButton(R.string.action_reset_url) { _, _ ->
                prefs.resetStartUrl()
                render()
                toast("Start URL reset")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                val host = runCatching { Uri.parse(value).host }.getOrNull()
                when {
                    !value.startsWith("https://") ->
                        toast("Start URL must use https")
                    !Patterns.WEB_URL.matcher(value).matches() ->
                        toast("That does not look like a URL")
                    !Constants.isAllowedHost(host) ->
                        toast("Host not allowed. Permitted: ${Constants.ALLOWED_HOSTS.joinToString()}")
                    else -> {
                        prefs.startUrl = value
                        render()
                        toast("Start URL updated")
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

    override fun onResume() {
        super.onResume()
        render()
    }

    // BACK on a staff screen simply returns to the showroom.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishWithAction(null)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
