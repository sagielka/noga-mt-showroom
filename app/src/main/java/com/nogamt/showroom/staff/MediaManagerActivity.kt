package com.nogamt.showroom.staff

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.R
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Staff-only view of the local media index.
 *
 * Nothing here edits the customer's files. "Clear media index" clears the app's index only.
 */
class MediaManagerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var progress: ProgressBar

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            if (MediaRepository.adoptFolder(this, uri)) {
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

        findViewById<Button>(R.id.btn_select_folder).setOnClickListener {
            try {
                folderPicker.launch(null)
            } catch (t: Throwable) {
                Log.e(Constants.LOG, "No SAF picker available", t)
                toast("This TV has no document picker")
            }
        }
        findViewById<Button>(R.id.btn_rescan).setOnClickListener {
            if (MediaRepository.hasFolder(this)) rescan() else toast("Select a media folder first")
        }
        findViewById<Button>(R.id.btn_view_matched).setOnClickListener { showMatched() }
        findViewById<Button>(R.id.btn_view_missing).setOnClickListener { showMissing() }
        findViewById<Button>(R.id.btn_view_unmatched).setOnClickListener { showUnmatched() }
        findViewById<Button>(R.id.btn_clear_index).setOnClickListener { confirmClearIndex() }
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_select_folder).requestFocus()
        render()
    }

    override fun onResume() {
        super.onResume()
        MediaRepository.verifySourceAvailability(this) { render() }
        render()
    }

    private fun rescan() {
        progress.visibility = View.VISIBLE
        toast(getString(R.string.scanning))
        MediaRepository.rescan(this) {
            progress.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.value_source).text =
            MediaScanner.describe(prefs.mediaTreeUri)
        findViewById<TextView>(R.id.value_source_state).text = when {
            prefs.mediaTreeUri.isNullOrBlank() -> "No folder selected · online playback only"
            MediaIndex.sourceAvailable -> "Available"
            else -> "UNAVAILABLE · drive removed or permission lost · online fallback active"
        }
        findViewById<TextView>(R.id.value_found).text = MediaIndex.totalFilesSeen.toString()
        findViewById<TextView>(R.id.value_matched).text = MediaIndex.matchedCount.toString()
        findViewById<TextView>(R.id.value_unmatched).text = MediaIndex.unmatchedCount.toString()
        findViewById<TextView>(R.id.value_duplicates).text = MediaIndex.duplicateCount.toString()
        findViewById<TextView>(R.id.value_storage).text =
            MediaScanner.formatBytes(MediaIndex.totalBytes)
        findViewById<TextView>(R.id.value_last_scan).text =
            if (MediaIndex.lastScanAt <= 0L) "Never"
            else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(MediaIndex.lastScanAt))
    }

    private fun showMatched() {
        val items = MediaIndex.all().map {
            "${it.id}  ·  ${MediaScanner.formatBytes(it.sizeBytes)}\n${it.fileName}"
        }
        listDialog(getString(R.string.btn_view_matched), items)
    }

    private fun showUnmatched() {
        val items = MediaIndex.unmatched().map {
            "${it.fileName}\n${MediaScanner.formatBytes(it.sizeBytes)} · no video id in the name"
        }
        listDialog(getString(R.string.btn_view_unmatched), items)
    }

    private fun showMissing() {
        val missing = MediaIndex.missing()
        val duplicates = MediaIndex.duplicates().map {
            "DUPLICATE ${it.id}\nkept: ${it.kept}\nignored: ${it.ignored}"
        }
        val items = missing.map { "$it\nrequested by the web app · no local copy" } + duplicates
        listDialog(getString(R.string.btn_view_missing), items)
    }

    private fun listDialog(title: String, items: List<String>) {
        val entries = items.ifEmpty { listOf(getString(R.string.none_found)) }
        AlertDialog.Builder(this, R.style.Theme_NogaMT_Dialog)
            .setTitle("$title (${items.size})")
            .setItems(entries.toTypedArray(), null)
            .setPositiveButton(R.string.btn_close, null)
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
