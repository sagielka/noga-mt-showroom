package com.nogamt.showroom.staff

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import com.nogamt.showroom.R
import com.nogamt.showroom.media.MediaIndex
import com.nogamt.showroom.media.MediaRepository
import com.nogamt.showroom.media.MediaScanner

/**
 * PREPARE OFFLINE MEDIA - shown once after installation, and again whenever staff choose
 * "Change storage". Choosing SKIP FOR NOW is a first-class option: the showroom runs online.
 */
class FirstRunSetupActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                status.text = "Folder selection cancelled"
                return@registerForActivityResult
            }
            if (MediaRepository.adoptSafFolder(this, uri)) {
                status.text = "Folder accepted · scanning…"
                startScanAndFinish()
            } else {
                status.text = "Could not keep permission for that folder. Try another one."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_first_run)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_internal).setOnClickListener {
            MediaRepository.useInternalStorage(this)
            status.text = "Internal storage selected · preparing…"
            startScanAndFinish()
        }

        findViewById<Button>(R.id.btn_usb).setOnClickListener {
            try {
                folderPicker.launch(null)
            } catch (t: Throwable) {
                Log.e(Constants.LOG, "No SAF picker on this device", t)
                status.text =
                    "This TV has no document picker. Use Internal Storage instead."
            }
        }

        findViewById<Button>(R.id.btn_skip).setOnClickListener {
            MediaRepository.skipStorageSetup(this)
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        findViewById<Button>(R.id.btn_internal).requestFocus()
    }

    private fun startScanAndFinish() {
        MediaRepository.rescan(this) {
            val label = MediaRepository.storageLabel(this)
            Toast.makeText(
                this,
                "$label · ${MediaIndex.matchedCount} videos " +
                    "(${MediaScanner.formatBytes(MediaIndex.totalBytes)})",
                Toast.LENGTH_LONG
            ).show()
            // Kick off the first background synchronisation; it never blocks the showroom.
            MediaRepository.syncNow(this)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // BACK during first run behaves like "skip for now" - never a dead end.
        MediaRepository.skipStorageSetup(this)
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
