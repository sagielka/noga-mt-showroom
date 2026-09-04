package com.nogamt.showroom

import android.app.Application
import android.util.Log
import com.nogamt.showroom.media.MediaIndex

class ShowroomApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Never let an unexpected exception on a background thread take the showroom
        // down silently - log it so `adb logcat -s NogaMT` tells the story.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(Constants.LOG, "Uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        // Restore the cached media index immediately so the bridge can answer
        // hasLocalVideo() before the first rescan finishes.
        MediaIndex.restore(Prefs.get(this))
        Log.i(Constants.LOG, "ShowroomApp started, version ${BuildConfig.VERSION_NAME}")
    }
}
