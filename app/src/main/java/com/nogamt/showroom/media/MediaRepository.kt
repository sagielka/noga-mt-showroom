package com.nogamt.showroom.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns SAF permission handling and off-main-thread scanning.
 * Both the shell and the staff screens go through here so there is one code path.
 */
object MediaRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Persists read permission for a freshly picked tree so it survives reboots. */
    fun adoptFolder(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val prefs = Prefs.get(context)
        prefs.mediaTreeUri = treeUri.toString()
        prefs.mediaTreeLabel = MediaScanner.describe(treeUri.toString())
        Log.i(Constants.LOG, "Adopted media folder: ${prefs.mediaTreeLabel}")
        true
    } catch (t: Throwable) {
        Log.e(Constants.LOG, "Could not persist folder permission", t)
        false
    }

    fun hasFolder(context: Context): Boolean =
        !Prefs.get(context).mediaTreeUri.isNullOrBlank()

    /**
     * Rescans the selected folder. [onFinished] is invoked on the caller's dispatcher
     * (main, in practice) with the fresh result - or null if nothing was scanned.
     */
    fun rescan(context: Context, onFinished: ((MediaScanner.ScanResult?) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        if (MediaIndex.scanning) {
            onFinished?.invoke(null)
            return
        }
        MediaIndex.scanning = true
        scope.launch {
            val result = try {
                MediaScanner.scan(appContext, prefs.mediaTreeUri)
            } catch (t: Throwable) {
                Log.e(Constants.LOG, "Media scan failed", t)
                MediaScanner.ScanResult.unavailable(MediaScanner.describe(prefs.mediaTreeUri))
            }
            MediaIndex.replace(result, prefs)
            MediaIndex.scanning = false
            withContext(Dispatchers.Main) { onFinished?.invoke(result) }
        }
    }

    /**
     * Cheap liveness check - used when the app resumes or the USB drive may have been pulled.
     * Marks the index unavailable (so the web app falls back online) without wiping it, so a
     * reinserted drive recovers with a rescan instead of a fresh folder pick.
     */
    fun verifySourceAvailability(context: Context, onFinished: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.get(appContext)
        scope.launch {
            val reachable = MediaScanner.isSourceReachable(appContext, prefs.mediaTreeUri)
            val was = MediaIndex.sourceAvailable
            MediaIndex.setSourceAvailable(reachable)
            if (was != reachable) {
                Log.i(Constants.LOG, "Media source availability changed: $reachable")
                if (reachable) {
                    // Drive came back - refresh the index quietly.
                    val result = MediaScanner.scan(appContext, prefs.mediaTreeUri)
                    MediaIndex.replace(result, prefs)
                }
            }
            withContext(Dispatchers.Main) { onFinished?.invoke(reachable) }
        }
    }
}
