package com.nogamt.showroom.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nogamt.showroom.Constants
import com.nogamt.showroom.MainActivity
import com.nogamt.showroom.Prefs

/**
 * Optional auto-start after a TV power cycle.
 *
 * Reality check: launching a foreground Activity from BOOT_COMPLETED works on many Android TV
 * boxes and on most digital-signage/OEM firmware, but Google TV and several manufacturers
 * (notably some Sony/Philips/Amazon builds) suppress it. It is best-effort by design, and the
 * failure is logged rather than crashed. For a guaranteed kiosk start, provision the TV as a
 * device owner - see docs/KIOSK.md.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = Prefs.get(context)
        if (!prefs.autoStartOnBoot) {
            Log.i(Constants.LOG, "Boot completed, auto-start disabled")
            return
        }

        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_FROM_BOOT, true)
            }
            context.startActivity(launch)
            Log.i(Constants.LOG, "Auto-start after boot requested")
        } catch (t: Throwable) {
            Log.w(Constants.LOG, "Auto-start blocked by the platform: ${t.message}")
        }
    }
}
