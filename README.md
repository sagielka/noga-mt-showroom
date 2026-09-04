# NOGA MT Showroom — Android TV

A kiosk shell for the exhibition TV. It opens the live Lovable web app at
`https://noga-exhibit-buddy.lovable.app/tv` full-screen, plays local MP4 files natively when
they exist, survives an unattended 12-hour exhibition day, and stays out of the way.

**Package:** `com.nogamt.showroom` · **Version:** 1.0.0 (1) · **minSdk:** 26 (Android 8) ·
**compileSdk/targetSdk:** 34

---

## The division of labour

| Lovable web app | Android APK |
|---|---|
| Playlist, ordering, enabled/disabled videos | Full-screen kiosk window |
| Products, product pages, branding, UI | TV remote handling |
| Screensaver / attract mode | Local media index + native playback |
| QR / NFC sharing | Crash, network and renderer recovery |
| Everything a visitor sees | Boot behaviour, staff tooling |

Publish in Lovable → the TV picks the change up on the next load. **A new APK is only needed
for native changes.** There is no second playlist anywhere in this project, no hard-coded video
ids and no 28-video limit.

The ~620 MB video library is **not** in the APK. The APK is a shell of a few MB.

---

## Project layout

```
noga-mt-showroom/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nogamt/showroom/
│       │   ├── MainActivity.kt          kiosk shell, remote, recovery, staff menu
│       │   ├── ShowroomApp.kt           app entry, restores the media index
│       │   ├── Constants.kt             every tunable value
│       │   ├── Prefs.kt                 settings storage
│       │   ├── bridge/
│       │   │   ├── NogaBridge.kt        the @JavascriptInterface surface
│       │   │   ├── BridgeScript.kt      the window.NogaAndroidTV facade (JS)
│       │   │   └── BridgeHost.kt        what JS may ask the Activity to do
│       │   ├── media/
│       │   │   ├── MediaIndex.kt        in-memory index (NOT a playlist)
│       │   │   ├── MediaScanner.kt      SAF directory walk
│       │   │   ├── MediaRepository.kt   permissions + background scanning
│       │   │   ├── VideoIdMatcher.kt    file name → video id rules
│       │   │   ├── LocalPlayerController.kt  Media3 / ExoPlayer lifecycle
│       │   │   └── LocalVideo.kt
│       │   ├── web/
│       │   │   ├── WebViewFactory.kt    WebView configuration
│       │   │   ├── ShowroomWebViewClient.kt   navigation policy + errors
│       │   │   └── ShowroomWebChromeClient.kt HTML5 fullscreen video
│       │   ├── net/NetworkMonitor.kt
│       │   ├── boot/BootReceiver.kt
│       │   └── staff/
│       │       ├── StaffSettingsActivity.kt
│       │       └── MediaManagerActivity.kt
│       └── res/                         TV banner, icons, dark/orange staff UI
├── docs/KIOSK.md                        optional device-owner provisioning
├── INTEGRATION.md                       the contract for the Lovable developer
├── build.sh
├── .github/workflows/android.yml        builds the APK in CI, no local SDK needed
└── gradlew, gradle/wrapper/             Gradle 8.7 wrapper (official)
```

---

## Building

### Android Studio (simplest)

Open the `noga-mt-showroom` folder, let it sync (it downloads AGP 8.5.2, Kotlin 1.9.24 and the
SDK components), then **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Command line

```bash
cd noga-mt-showroom
export ANDROID_HOME=/path/to/android/sdk      # or run Android Studio once
./gradlew assembleDebug
./gradlew assembleRelease
```

or just `./build.sh`.

### GitHub Actions (no local SDK at all)

Push this folder to a repository. `.github/workflows/android.yml` builds both APKs on every
push and attaches them as a downloadable artifact called `noga-mt-showroom-apks`.

### APK output paths

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

Use the **debug** APK for the exhibition unless you have a reason not to — it installs directly
and is already signed with the local debug key.

### Signing a release build

```bash
keytool -genkey -v -keystore noga-mt.jks -keyalg RSA -keysize 2048 -validity 10000 -alias nogamt
```

Then add to `app/build.gradle.kts` inside `android { }`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../noga-mt.jks")
        storePassword = System.getenv("NOGA_STORE_PASSWORD")
        keyAlias = "nogamt"
        keyPassword = System.getenv("NOGA_KEY_PASSWORD")
    }
}
```

and `signingConfig = signingConfigs.getByName("release")` inside `buildTypes { release { … } }`.

---

## Installing on the TV

### Over ADB (recommended)

```bash
# TV: Settings → Device Preferences → About → click "Build" 7 times → Developer options
#     → USB debugging ON, and note the TV's IP address.
adb connect 192.168.1.42:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.nogamt.showroom -c android.intent.category.LEANBACK_LAUNCHER 1
```

Reinstall over an existing copy with `-r`. Logs: `adb logcat -s NogaMT`.

### From a USB stick

1. Copy `app-debug.apk` to the root of a FAT32/exFAT USB stick.
2. On the TV install a file manager + sideload permission — *X-plore File Manager* or
   *File Commander* from the Play Store both work.
3. Plug the stick in, open the file manager, select the APK, allow "install unknown apps" for
   the file manager when prompted, install.
4. **NOGA MT Showroom** now appears in the TV Apps row with its banner.

Some Google TV models hide sideloaded apps: the app is on the device, it just needs
*Apps → See all apps* once, or add it to favourites.

---

## Local video library

### Folder layout on the drive

```
NOGA-MT/
└── videos/
    ├── NOGA MT – Advanced CNC Deburring… [N-mTagRbXuM].mp4
    ├── … more .mp4 / .webm / .m4v
```

Sub-folders are scanned too (up to 6 levels), so grouping by UBACK / UBURR / UFIBER / GENERAL
is fine. The bear/mascot video is treated like any other file — nothing is filtered by product
family.

### Naming

The id is read from the file name: bracketed `[N-mTagRbXuM]` first, then a trailing
`-N-mTagRbXuM`, then a name that is itself an id, then an exact file-name match. The default
yt-dlp naming already used by the current library works as-is. Full rules and examples are in
**INTEGRATION.md §8**.

### Selecting the folder (staff, once)

1. Hold **BACK** on the remote for ~3 seconds → staff menu.
2. **Local media manager** → **SELECT MEDIA FOLDER**.
3. In the Android picker choose the drive, open `NOGA-MT/videos`, press **USE THIS FOLDER**
   (or **ALLOW**).
4. The scan runs immediately and the counters fill in.

The permission is persisted, so this survives restarts and power cuts — staff never repeat it.
Selecting `NOGA-MT` (the parent) works too, since sub-folders are scanned.

### After adding new videos

Staff menu → **Local media manager** → **RESCAN VIDEOS**. Or just restart the app.

### USB removal

Pulling the drive does not crash anything. The index is marked unavailable, `hasLocalVideo()`
starts returning false and the web app falls back to online playback. Re-insert the drive and
the app re-checks on the next resume; **RESCAN VIDEOS** forces it immediately.

---

## Operating the TV

### Staff menu — hold BACK for 3 seconds

- **Return to showroom**
- **Reload web app** — safe reload, deferred if a local video is playing
- **Settings** — the staff settings screen
- **Local media manager**
- **Exit application** — asks for confirmation first

A short BACK press never exits: it closes HTML5 fullscreen, stops a local video, or navigates
web history. Visitors cannot reach the Android launcher by pressing BACK.

### Staff settings

Read-out: start URL, network state, Android System WebView version, app version, device model,
selected media folder, local video count and last scan time.

Toggles: **Keep screen awake**, **Auto launch on TV boot**, **Automatic recovery**.

Actions: Reload Web App · Force Web Refresh · Select Media Folder · Rescan Media · Local Media ·
Clear Web Cache · Change Start URL · Open Android Network Settings · Return To Showroom.

The start URL only accepts HTTPS on the allow-listed hosts.

### Auto-start after a TV power cycle

Staff settings → **Auto launch on TV boot** → ON. See the manufacturer caveats below; for a
guaranteed start on dedicated exhibition hardware, use `docs/KIOSK.md`.

### Recovery, unattended

Renderer crashes rebuild the WebView. Failed loads show the branded
**NOGA MT / SHOWROOM TEMPORARILY OFFLINE / Reconnecting…** screen and retry with exponential
back-off (3s → 60s), and retry immediately when the network returns. A single failed request
never replaces a working UI — the reconnect screen appears only when the app genuinely has no
usable page. Reloads never interrupt a playing local video; they wait for it to finish.

---

## Manufacturer limitations, honestly

- **Auto-start on boot is best-effort.** `RECEIVE_BOOT_COMPLETED` works on most Android TV
  boxes, Nvidia Shield, Xiaomi/Mi Box, TCL and generic Chinese signage firmware. Google TV
  (Chromecast HD/4K), several Sony and Philips models, and Amazon Fire TV commonly suppress an
  app opening itself at boot. Nothing crashes when it is blocked; it is logged. Guaranteed
  start needs device-owner provisioning (`docs/KIOSK.md`).
- **Sideloaded apps can be hidden** in the Google TV launcher (see the install section).
- **USB behaviour varies.** Most TVs expose USB drives through the Storage Access Framework
  picker; a few budget models expose no document provider at all, in which case copy the videos
  to internal storage and select that folder instead. The app never assumes a fixed USB path.
- **Android System WebView version matters.** The app needs the WebView that ships with the TV;
  very old ones (Android 8 devices never updated) may lack newer web APIs the Lovable app uses.
  The installed version is shown in staff settings — update it from the Play Store if the web
  app misbehaves.
- **Immersive mode is a request, not a guarantee.** Some OEM launchers redraw a top bar
  briefly; the app re-asserts immersive mode whenever it regains focus.
- **4K TVs** render the WebView at the platform's TV density. Layouts are TV-safe at both
  1920×1080 and 3840×2160, with a 5% overscan margin on the native screens.

---

## Troubleshooting

| Symptom | Where to look |
|---|---|
| Anything at all | `adb logcat -s NogaMT` — every recovery event, scan and bridge call is logged |
| Web app blank | Staff settings → check network state and WebView version → Force Web Refresh |
| Local videos not found | Media manager → is MEDIA SOURCE "Available"? → RESCAN VIDEOS |
| A video plays online despite a local file | Media manager → VIEW UNMATCHED FILES — its name probably has no id |
| Two files, one id | Media manager → VIEW MISSING VIDEOS lists duplicates and which file won |
| Web app can't see the bridge | DevTools console: `window.NogaAndroidTV` — see INTEGRATION.md §1 |

Remote debugging: `adb connect <tv-ip>:5555`, then `chrome://inspect` in desktop Chrome.

---

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`. That is all. Media access goes
through the Storage Access Framework, so there is no `READ_EXTERNAL_STORAGE` and no
`MANAGE_EXTERNAL_STORAGE`.

---

## Acceptance tests

| # | Test | Where it is handled |
|---|---|---|
| 1 | Appears in TV Apps | `LEANBACK_LAUNCHER` intent filter + `@drawable/banner` |
| 2 | Opens `/tv` immediately | `MainActivity.loadStartUrl` |
| 3 | No browser chrome | `WebView` in a no-action-bar immersive Activity |
| 4 | Remote works | `dispatchKeyEvent`, focusable WebView, DPAD-navigable staff UI |
| 5 | Video plays | Media3 locally, WebView `WebChromeClient` fullscreen online |
| 6 | Keep awake | `FLAG_KEEP_SCREEN_ON`, toggleable |
| 7 | Long BACK → staff menu | `Constants.LONG_BACK_MS` = 3000 |
| 8 | Select USB folder | SAF `OpenDocumentTree` + persisted permission |
| 9 | Index discovers MP4s | `MediaScanner` |
| 10 | `[N-mTagRbXuM]` → `N-mTagRbXuM` | `VideoIdMatcher` — **verified by an executed test** |
| 11 | Local playback on request | `NogaBridge.playLocalVideo` → `LocalPlayerController` |
| 12 | Ended event reaches the web app | `nogamt-local-video-ended` |
| 13 | Online fallback | `hasLocalVideo()` false → web app's normal path |
| 14 | Offline: no crash, local still plays | Requirement 20 handling in `onMainFrameFailure` |
| 15 | Reconnect recovers | `NetworkMonitor` + back-off retry |
| 16 | New Lovable version, no APK | Live URL, no bundled copy |
| 17 | Auto-start | `BootReceiver` (+ caveats above) |
| 18 | USB hot-plug | `MediaRepository.verifySourceAvailability` |
| 19 | No videos in the APK | Nothing under `app/src/main/assets` or `res/raw` |
| 20 | Clean compile | Run one of the build paths above |

Tests 1–9 and 11–19 are behavioural and need the APK on a TV. See "What was verified" below
for what was actually executed while the project was built.

---

## What was verified when this project was generated

The build environment had a JDK but **no Android SDK**, and `dl.google.com`, `maven.google.com`,
`repo1.maven.org` and `services.gradle.org` were all blocked by the network policy, so
`./gradlew assembleDebug` could not run there. What *was* executed:

- **Kotlin 1.9.24 compiler over all 17 source files** — zero syntax/parse errors. Semantic
  checking was not possible without `android.jar`, so the first real compile happens on your
  machine or in CI.
- **`VideoIdMatcher` compiled and executed** against 20 cases, including the exact NOGA MT
  file name from the spec and path-traversal rejection — all pass.
- **The injected bridge JavaScript run under Node** with a simulated native object: facade
  installation, the ready event, every method, JSON parsing, double-injection safety, and
  containment of a throwing native call — all pass.
- **All 12 XML files parsed** and every `@string`/`@color`/`@style`/`@drawable`/`@id`/`@layout`
  reference cross-checked against the resources — no dangling references.
- The Gradle wrapper is the official 8.7 wrapper jar and scripts from the `gradle/gradle`
  repository.

If a first compile does surface something, it will be a missing import or an API-level detail,
not a structural problem — and `adb logcat -s NogaMT` plus the file map above will point at it
quickly.
