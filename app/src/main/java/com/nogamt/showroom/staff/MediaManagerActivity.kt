package com.nogamt.showroom.staff

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.R
import com.nogamt.showroom.StorageMode
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner
import com.nogamt.showroom.media.MediaState
import com.nogamt.showroom.media.MediaStateResolver
import com.nogamt.showroom.media.SyncEngine
import com.nogamt.showroom.media.SyncPhase
import com.nogamt.showroom.media.SyncProgress
import com.nogamt.showroom.media.VideoIdMatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Staff-only view of the local media library and the sync engine.
 *
 * Nothing here edits customer files without explicit confirmation: "clear media index" only
 * clears the app's index, and "clean unused media" always shows what it would delete first.
 */
class MediaManagerActivity : AppCompatActivity(), SyncEngine.Listener {

    private lateinit var prefs: Prefs
    private lateinit var progress: ProgressBar

    /** Set when CHANGE STORAGE is picking a new folder rather than the initial selection. */
    private var pendingSwitchMode: MediaRepository.SwitchMode? = null

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val switchMode = pendingSwitchMode
            pendingSwitchMode = null
            if (uri == null) return@registerForActivityResult
            if (switchMode != null) {
                applyStorageSwitch(StorageMode.SAF, uri, switchMode)
            } else if (MediaRepository.adoptSafFolder(this, uri)) {
                rescan()
            } else {
                toast("Could not keep permission for that folder")
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_media_manager)
        progress = findViewById(R.id.progress)

        findViewById<Button>(R.id.btn_sync_now).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.btn_rescan).setOnClickListener {
            if (MediaRepository.hasStorage(this)) rescan() else promptChangeStorage()
        }
        findViewById<Button>(R.id.btn_change_storage).setOnClickListener { promptChangeStorage() }
        findViewById<Button>(R.id.btn_view_matched).setOnClickListener { showMatched() }
        findViewById<Button>(R.id.btn_view_missing).setOnClickListener { showMissing() }
        findViewById<Button>(R.id.btn_view_failed).setOnClickListener { showFailed() }
        findViewById<Button>(R.id.btn_view_unmatched).setOnClickListener { showUnmatched() }
        findViewById<Button>(R.id.btn_view_duplicates).setOnClickListener { showDuplicates() }
        findViewById<Button>(R.id.btn_clean_unused).setOnClickListener { confirmCleanUnused() }
        findViewById<Button>(R.id.btn_test_local).setOnClickListener { testLocalVideo() }
        findViewById<Button>(R.id.btn_test_by_id).setOnClickListener { promptTestById() }
        findViewById<Button>(R.id.btn_clear_index).setOnClickListener { confirmClearIndex() }
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_sync_now).requestFocus()
        render()
    }

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

    override fun onSyncProgress(progressUpdate: SyncProgress) {
        runOnUiThread { render() }
    }

    // ---------------------------------------------------------------- actions

    private fun syncNow() {
        if (!MediaRepository.hasStorage(this)) {
            promptChangeStorage()
            return
        }
        toast("Checking the media library…")
        progress.visibility = View.VISIBLE
        MediaRepository.syncNow(this, force = true) {
            progress.visibility = View.GONE
            render()
        }
        render()
    }

    private fun rescan() {
        progress.visibility = View.VISIBLE
        MediaRepository.rescan(this) {
            progress.visibility = View.GONE
            render()
            toast("Indexed ${MediaIndex.matchedCount} videos")
        }
    }

    private fun promptChangeStorage() {
        val options = arrayOf(
            "Internal storage",
            "USB / external folder…",
            "Cancel"
        )
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.action_change_storage)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> promptSwitchMode(StorageMode.INTERNAL, null)
                    1 -> promptSwitchMode(StorageMode.SAF, null)
                    else -> Unit
                }
            }
            .show()
    }

    private fun promptSwitchMode(mode: StorageMode, uri: Uri?) {
        val options = arrayOf(
            "USE NEW LOCATION (leave old files where they are)",
            "COPY EXISTING MEDIA to the new location",
            "START FRESH (clear the index only, delete nothing)"
        )
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.action_change_storage)
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                val switchMode = when (which) {
                    1 -> MediaRepository.SwitchMode.COPY_EXISTING
                    2 -> MediaRepository.SwitchMode.START_FRESH
                    else -> MediaRepository.SwitchMode.USE_NEW
                }
                if (mode == StorageMode.SAF && uri == null) {
                    pendingSwitchMode = switchMode
                    runCatching { folderPicker.launch(null) }.onFailure {
                        pendingSwitchMode = null
                        toast("This TV has no document picker")
                    }
                } else {
                    applyStorageSwitch(mode, uri, switchMode)
                }
            }
            .show()
    }

    private fun applyStorageSwitch(
        mode: StorageMode,
        uri: Uri?,
        switchMode: MediaRepository.SwitchMode
    ) {
        progress.visibility = View.VISIBLE
        toast("Switching storage…")
        MediaRepository.switchStorage(
            context = this,
            newMode = mode,
            newTreeUri = uri,
            switchMode = switchMode,
            onProgress = { message -> runOnUiThread { toast(message) } },
            onFinished = { ok ->
                progress.visibility = View.GONE
                render()
                toast(if (ok) "Storage updated" else "Could not switch storage")
            }
        )
    }

    private fun testLocalVideo() {
        val playable = MediaIndex.all().firstOrNull { MediaIndex.isPlayable(it.id) }
        if (playable == null) {
            AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
                .setTitle(R.string.btn_test_local)
                .setMessage(
                    "No playable local video.\n\n" +
                        "Check MEDIA SOURCE above, run RESCAN VIDEOS, and if the library is " +
                        "empty run SYNC NOW."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        startActivity(MediaTestActivity.firstVideo(this))
    }

    private fun promptTestById() {
        val input = EditText(this).apply {
            hint = getString(R.string.test_by_id_hint)
            setTextColor(getColor(R.color.noga_text))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.test_by_id_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val id = input.text.toString().trim()
                if (!VideoIdMatcher.isSafeRequestKey(id)) {
                    toast("That is not a valid video id")
                } else {
                    showLookupResult(id)
                }
            }
            .show()
    }

    private fun showLookupResult(id: String) {
        val video = MediaIndex.find(id)
        if (video == null) {
            AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
                .setTitle("NOT FOUND · $id")
                .setMessage(
                    "No local copy of this id.\n\n" +
                        "Remote library state: ${MediaIndex.stateOf(id).name}\n" +
                        "Run SYNC NOW, or check the file naming rules in the README."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val available = MediaIndex.isPlayable(video.id)
        val storageLabel = MediaRepository.storageLabel(this)
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle("FOUND · ${video.id}")
            .setMessage(
                buildString {
                    appendLine("File: ${video.fileName}")
                    appendLine("Size: ${MediaScanner.formatBytes(video.sizeBytes)}")
                    appendLine("Match type: ${video.matchType.name}")
                    appendLine("State: ${MediaIndex.stateOf(video.id).name}")
                    appendLine("Available: ${if (available) "YES" else "NO"}")
                    append("Storage: $storageLabel")
                }
            )
            .setNegativeButton(R.string.btn_close, null)
            .setPositiveButton("PLAY TEST") { _, _ ->
                startActivity(MediaTestActivity.byId(this, video.id))
            }
            .show()
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val res = MediaIndex.resolution()
        val available = MediaIndex.sourceAvailable
        val permission = MediaIndex.permissionValid

        findViewById<TextView>(R.id.value_source).text = MediaRepository.storageLabel(this)
        findViewById<TextView>(R.id.value_permission).text = when {
            prefs.storageMode == StorageMode.INTERNAL -> "N/A · internal storage"
            prefs.storageMode == StorageMode.NONE -> "No storage selected"
            permission -> "VALID"
            else -> "INVALID · reselect the folder"
        }
        findViewById<TextView>(R.id.value_source_state).text = when {
            prefs.storageMode == StorageMode.NONE -> "SOURCE AVAILABLE · NO · online playback only"
            available -> "SOURCE AVAILABLE · YES"
            else -> "SOURCE AVAILABLE · NO · drive removed or permission lost"
        }

        findViewById<TextView>(R.id.value_counters).text = buildString {
            appendLine(row("REMOTE LIBRARY", MediaIndex.remoteCount.toString()))
            appendLine(row("FILES DISCOVERED", MediaIndex.totalFilesSeen.toString()))
            appendLine(row("MATCHED", MediaIndex.matchedCount.toString()))
            appendLine(row("LOCAL READY", res.count(MediaState.LOCAL_READY).toString()))
            appendLine()
            appendLine(row("MISSING", res.count(MediaState.MISSING).toString()))
            appendLine(row("UPDATE AVAILABLE", res.count(MediaState.UPDATE_AVAILABLE).toString()))
            appendLine(row("DOWNLOADING", res.count(MediaState.DOWNLOADING).toString()))
            appendLine(row("FAILED", res.count(MediaState.FAILED).toString()))
            appendLine(row("ONLINE ONLY", res.count(MediaState.ONLINE_ONLY).toString()))
            appendLine(row("UNUSED", res.count(MediaState.UNUSED).toString()))
            appendLine(row("UNMATCHED", MediaIndex.unmatchedCount.toString()))
            append(row("DUPLICATES", MediaIndex.duplicateCount.toString()))
        }

        findViewById<TextView>(R.id.value_storage_line).text = buildString {
            appendLine(row("STORAGE USED", MediaScanner.formatBytes(MediaIndex.totalBytes)))
            appendLine(
                row(
                    "STORAGE FREE",
                    MediaIndex.freeBytes?.let { MediaScanner.formatBytes(it) } ?: "unknown"
                )
            )
            appendLine(row("LAST SCAN", timeOf(MediaIndex.lastScanAt)))
            appendLine(row("LAST SYNC", timeOf(MediaIndex.lastSyncAt)))
            append(row("SYNC STATUS", MediaIndex.syncProgress.describe()))
        }

        val low = findViewById<TextView>(R.id.value_low_storage)
        if (MediaRepository.lowStorage()) {
            low.visibility = View.VISIBLE
            low.text = "LOW STORAGE · " +
                (MediaIndex.freeBytes?.let { MediaScanner.formatBytes(it) } ?: "unknown") + " FREE"
        } else {
            low.visibility = View.GONE
        }

        val banner = findViewById<TextView>(R.id.value_sync_banner)
        val sync = MediaIndex.syncProgress
        if (sync.phase == SyncPhase.SYNCING) {
            banner.visibility = View.VISIBLE
            banner.text = "SYNCING MEDIA · video ${sync.currentIndex} / ${sync.totalItems} · " +
                "${MediaScanner.formatBytes(sync.bytesDone)} / " +
                MediaScanner.formatBytes(sync.bytesTotal)
        } else {
            banner.visibility = View.GONE
        }

        findViewById<TextView>(R.id.value_health).text = healthLabel(res)
    }

    private fun healthLabel(res: MediaStateResolver.Resolution): String {
        val error = res.count(MediaState.FAILED) > 0 ||
            (!MediaIndex.sourceAvailable && prefs.storageMode != StorageMode.NONE)
        val attention = res.count(MediaState.MISSING) > 0 ||
            res.count(MediaState.UPDATE_AVAILABLE) > 0 ||
            MediaIndex.duplicateCount > 0 ||
            MediaRepository.lowStorage()
        return when {
            error -> "HEALTH: ERROR"
            attention -> "HEALTH: ATTENTION"
            else -> "HEALTH: GOOD"
        }
    }

    private fun row(label: String, value: String): String = label.padEnd(20) + value

    private fun timeOf(millis: Long): String =
        if (millis <= 0L) "never"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    // ---------------------------------------------------------------- lists

    private fun showMatched() {
        val states = MediaIndex.resolution().states
        val items = MediaIndex.all().map { video ->
            "${video.id}  ·  ${states[video.id]?.name ?: MediaState.LOCAL_READY.name}\n" +
                "${video.fileName}\n${MediaScanner.formatBytes(video.sizeBytes)}"
        }
        listDialog(getString(R.string.btn_view_matched), items)
    }

    private fun showUnmatched() {
        val items = MediaIndex.unmatched().map {
            "${it.fileName}\n${MediaScanner.formatBytes(it.sizeBytes)} · no video id in the name"
        }
        listDialog(getString(R.string.btn_view_unmatched), items)
    }

    private fun showDuplicates() {
        val items = MediaIndex.duplicates().map {
            "${it.id}\nkept: ${it.kept}\nignored: ${it.ignored}"
        }
        listDialog(getString(R.string.media_duplicates), items)
    }

    private fun showMissing() {
        val manifest = MediaIndex.manifest
        val items = MediaIndex.missing().map { id ->
            val title = manifest?.byId(id)?.title ?: "not in the remote library"
            "$id\n$title\nstate: ${MediaIndex.stateOf(id).name}"
        }
        listDialog(getString(R.string.btn_view_missing), items)
    }

    private fun showFailed() {
        val failures = MediaIndex.failureList()
        val items = failures.map { failure ->
            buildString {
                appendLine("${failure.id} · ${failure.title}")
                appendLine("host: ${failure.host.ifBlank { "unknown" }}")
                appendLine(
                    "http: ${failure.httpStatus?.toString() ?: "-"} · " +
                        "downloaded ${MediaScanner.formatBytes(failure.bytesDownloaded)}"
                )
                appendLine("retries: ${failure.retryCount}")
                appendLine("error: ${failure.lastError}")
                append("last attempt: ${timeOf(failure.lastAttemptAt)}")
            }
        }
        val entries = items.ifEmpty { listOf(getString(R.string.none_found)) }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle("${getString(R.string.btn_view_failed)} (${failures.size})")
            .setItems(entries.toTypedArray(), null)
            .setNegativeButton(R.string.btn_close, null)
            .setPositiveButton(R.string.btn_retry_failed) { _, _ ->
                MediaIndex.clearAllFailures(prefs)
                syncNow()
            }
            .show()
    }

    private fun listDialog(title: String, items: List<String>) {
        val entries = items.ifEmpty { listOf(getString(R.string.none_found)) }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle("$title (${items.size})")
            .setItems(entries.toTypedArray(), null)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    // ---------------------------------------------------------------- destructive actions

    private fun confirmCleanUnused() {
        val plan = MediaRepository.planCleanup()
        if (plan.videos.isEmpty()) {
            toast("Nothing unused to clean")
            return
        }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.btn_clean_unused)
            .setMessage(
                buildString {
                    appendLine("FILES TO DELETE: ${plan.videos.size}")
                    appendLine("SPACE TO RECOVER: ${MediaScanner.formatBytes(plan.totalBytes)}")
                    appendLine()
                    plan.videos.take(12).forEach { appendLine("· ${it.fileName}") }
                    if (plan.videos.size > 12) appendLine("· … and ${plan.videos.size - 12} more")
                    appendLine()
                    append("These files are no longer listed in the remote library. ")
                    append("Playing and downloading files are never deleted.")
                }
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("DELETE") { _, _ ->
                progress.visibility = View.VISIBLE
                MediaRepository.runCleanup(this, plan) { removed ->
                    progress.visibility = View.GONE
                    render()
                    toast("Deleted $removed file(s)")
                    Log.i(Constants.LOG, "Clean unused media removed $removed files")
                }
            }
            .show()
    }

    private fun confirmClearIndex() {
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle(R.string.clear_index_title)
            .setMessage(R.string.clear_index_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MediaIndex.clear(prefs)
                render()
                toast("Index cleared · media files untouched")
            }
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
