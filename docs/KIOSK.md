# Optional: dedicated-device (kiosk) provisioning

**Not required.** The app runs fine as an ordinary sideloaded app. This document is for the
case where one TV is dedicated permanently to the NOGA MT showroom and you want guaranteed
auto-start and no way out to the launcher.

Two levels are described. Level 1 is enough for most exhibitions.

---

## Level 1 — plain sideload (default)

- Install the APK, open it from the TV Apps row.
- Staff settings → **Auto launch on TV boot** → ON.
- BACK cannot exit; only the staff menu can.

Limitation: some manufacturers block boot auto-start (see README). If the TV is powered by a
switched socket at the end of each day and it does not come back on its own, either leave the
TV on standby instead of cutting power, or move to level 2.

---

## Level 2 — device owner + lock task mode

Turns the TV into a true single-purpose device: the app launches at boot, the home and recents
keys stop leaving it, and it cannot be uninstalled without factory reset.

### What it costs you

- **The TV must have no accounts on it.** Device owner can only be set on a device with zero
  configured Google accounts — in practice, straight after a factory reset, before signing in.
- Undoing it means another factory reset.
- The current APK does **not** ship a `DeviceAdminReceiver`, so the commands below are the
  provisioning half of the job; the native half is a small, well-understood addition
  (one receiver class, one `device_admin.xml`, and a `startLockTask()` call in `MainActivity`).
  Ask for it if you decide to go this route and it can be added without touching anything else.

### Provisioning commands

Factory reset the TV, skip account setup entirely, enable Developer options and USB debugging,
then:

```bash
adb connect 192.168.1.42:5555

# 1. Install the app for all users
adb install -r -g app-debug.apk

# 2. Promote it to device owner (requires the DeviceAdminReceiver mentioned above)
adb shell dpm set-device-owner com.nogamt.showroom/.admin.ShowroomDeviceAdminReceiver

# 3. Verify
adb shell dumpsys device_policy | head -20
```

With device owner granted, the app can then (in native code) call:

```kotlin
val dpm = getSystemService(DevicePolicyManager::class.java)
val admin = ComponentName(this, ShowroomDeviceAdminReceiver::class.java)
if (dpm.isDeviceOwnerApp(packageName)) {
    dpm.setLockTaskPackages(admin, arrayOf(packageName))
    dpm.addPersistentPreferredActivity(
        admin,
        IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
        ComponentName(this, MainActivity::class.java),
    )
    startLockTask()
}
```

That combination gives: launch at boot as the home activity, no exit via HOME or RECENTS, and
silent survival of reboots.

---

## Level 1.5 — a middle option worth knowing

Several signage-oriented TV boxes (Nvidia Shield, most generic Android TV boxes, MeCool, Mi Box)
let you install a replacement launcher. Installing a third-party kiosk launcher and pointing it
at `com.nogamt.showroom` gets you reliable auto-start without a factory reset, and is reversible
by switching the launcher back. Cheaper to undo than device owner, less bulletproof.

---

## Removing kiosk mode

- Level 1: uninstall or turn the auto-start toggle off.
- Level 1.5: switch back to the stock launcher.
- Level 2: `adb shell dpm remove-active-admin com.nogamt.showroom/.admin.ShowroomDeviceAdminReceiver`
  where supported, otherwise factory reset.
